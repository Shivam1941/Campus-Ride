package com.example.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.data.model.RideRequest
import com.example.data.model.RideRequestStatus
import com.example.data.model.UserRole
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object CriticalAlertManager {
    const val CHANNEL_ID = "driver_critical_alert_v2"
    const val CHANNEL_NAME = "Critical Ride Requests"
    const val NOTIFICATION_ID = 8801

    private const val STUDENT_CHANNEL_ID = "student_ride_updates"
    private const val STUDENT_CHANNEL_NAME = "Ride Status Updates"
    private const val STUDENT_NOTIFICATION_ID = 8802

    private const val TAG = "CriticalAlertManager"

    // Sound & Vibration State
    private var activeRingtone: Ringtone? = null
    private var activeMediaPlayer: android.media.MediaPlayer? = null
    private var activeVibrator: Vibrator? = null
    private var previewRingtone: Ringtone? = null
    private var previewMediaPlayer: android.media.MediaPlayer? = null
    private var audioFocusRequest: Any? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private var ringtoneLoopRunnable: Runnable? = null
    private var autoExpireRunnable: Runnable? = null

    private val _activeAlertRequest = MutableStateFlow<RideRequest?>(null)
    val activeAlertRequest: StateFlow<RideRequest?> = _activeAlertRequest.asStateFlow()

    private var lastAlertedRequestId: String? = null
    private var lastAlertTimestamp: Long = 0L
    private var isAlertActive: Boolean = false
    private val handledRequestIds = java.util.Collections.synchronizedSet(java.util.LinkedHashSet<String>())
    private val recentAlertFingerprints = java.util.Collections.synchronizedMap(
        object : java.util.LinkedHashMap<String, Long>(50, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean {
                return size > 100
            }
        }
    )

    @Synchronized
    fun markRequestHandled(requestId: String) {
        if (requestId.isBlank()) return
        handledRequestIds.add(requestId)
        if (handledRequestIds.size > 200) {
            val iterator = handledRequestIds.iterator()
            if (iterator.hasNext()) {
                iterator.next()
                iterator.remove()
            }
        }
    }

    @Synchronized
    fun isRequestHandled(requestId: String?): Boolean {
        if (requestId.isNullOrBlank()) return false
        return handledRequestIds.contains(requestId)
    }

    fun resolveRingtoneUri(context: Context, savedUriStr: String?): Uri {
        // 1. If driver explicitly picked a custom URI from device picker
        if (!savedUriStr.isNullOrBlank() && savedUriStr != "default" && savedUriStr != "bundled") {
            try {
                val parsedUri = Uri.parse(savedUriStr)
                val testRingtone = RingtoneManager.getRingtone(context, parsedUri)
                if (testRingtone != null) {
                    return parsedUri
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to open custom ringtone URI: $savedUriStr, falling back to system defaults", e)
            }
        }

        // 2. System Default Ringtone
        try {
            val defaultRingtone = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            if (defaultRingtone != null && RingtoneManager.getRingtone(context, defaultRingtone) != null) {
                return defaultRingtone
            }
        } catch (e: Exception) {
            Log.w(TAG, "Default ringtone check error", e)
        }

        // 3. System Default Notification
        try {
            val defaultNotif = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            if (defaultNotif != null && RingtoneManager.getRingtone(context, defaultNotif) != null) {
                return defaultNotif
            }
        } catch (e: Exception) {
            Log.w(TAG, "Default notification check error", e)
        }

        // 4. System Default Alarm
        try {
            val defaultAlarm = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            if (defaultAlarm != null && RingtoneManager.getRingtone(context, defaultAlarm) != null) {
                return defaultAlarm
            }
        } catch (e: Exception) {
            Log.w(TAG, "Default alarm check error", e)
        }

        // 5. Ultimate Fallback
        return Settings.System.DEFAULT_RINGTONE_URI
    }

    fun playRingtonePreview(context: Context, uriStr: String) {
        stopRingtonePreview()
        try {
            val uri = resolveRingtoneUri(context, uriStr)
            val ringtone = RingtoneManager.getRingtone(context, uri)
            if (ringtone != null) {
                val audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                ringtone.audioAttributes = audioAttributes
                ringtone.play()
                previewRingtone = ringtone
                Log.d(TAG, "Preview ringtone started: $uri")
                return
            }
        } catch (e: Exception) {
            Log.w(TAG, "Preview via RingtoneManager failed, trying MediaPlayer fallback", e)
        }

        try {
            val uri = resolveRingtoneUri(context, uriStr)
            previewMediaPlayer = android.media.MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .build()
                )
                setDataSource(context, uri)
                isLooping = false
                prepare()
                start()
            }
            Log.d(TAG, "Preview media player started: $uri")
        } catch (e: Exception) {
            Log.e(TAG, "Error playing sound preview", e)
        }
    }

    fun stopRingtonePreview() {
        try {
            previewRingtone?.let {
                if (it.isPlaying) it.stop()
            }
            previewRingtone = null
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping preview ringtone", e)
        }

        try {
            previewMediaPlayer?.let {
                if (it.isPlaying) it.stop()
                it.release()
            }
            previewMediaPlayer = null
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping preview media player", e)
        }
    }

    fun initNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            try {
                notificationManager.deleteNotificationChannel("driver_critical_alerts")
            } catch (ignored: Exception) {}

            val driverChannel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "High priority full-screen alerts for golf cart drivers"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 500, 200, 500)
                setSound(null, null) // System notification sound disabled; single ringtone playback is driven by CriticalAlertManager
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                setShowBadge(true)
                setBypassDnd(true)
            }

            val studentChannel = NotificationChannel(
                STUDENT_CHANNEL_ID,
                STUDENT_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Informational status updates for students"
                enableVibration(false)
                setSound(null, null)
            }

            notificationManager.createNotificationChannel(driverChannel)
            notificationManager.createNotificationChannel(studentChannel)
            Log.d(TAG, "NOTIFICATION CHANNEL = $CHANNEL_ID initialized (IMPORTANCE_HIGH)")
        }
    }

    @Synchronized
    fun triggerCriticalDriverAlert(context: Context, request: RideRequest, currentRole: UserRole?) {
        Log.d(TAG, "ALERT RECEIVED: REQUEST ID = ${request.id}, PICKUP = ${request.pickupLocationEnum.displayName}, ROLE = $currentRole")

        // High priority vibration, audio loop, and full screen alert MUST ONLY trigger on DRIVER devices.
        if (currentRole != UserRole.DRIVER) {
            Log.d(TAG, "Ignoring trigger on non-DRIVER device: role = $currentRole")
            return
        }

        // 1. Ignore if request is no longer PENDING (e.g. already ACCEPTED or CANCELLED)
        if (request.status != RideRequestStatus.PENDING) {
            Log.d(TAG, "ALERT_DEDUPLICATED: Request ${request.id} has status ${request.status} (not PENDING)")
            return
        }

        // 2. Ignore if request was already handled/accepted/declined
        if (isRequestHandled(request.id)) {
            Log.d(TAG, "ALERT_DEDUPLICATED: Request ${request.id} was already handled or completed.")
            return
        }

        val now = System.currentTimeMillis()
        val fingerprint = "${request.pickupLocation}_${request.requesterType.name}_${request.studentName}"
        val lastFingerprintTime = recentAlertFingerprints[fingerprint] ?: 0L

        // 3. Deduplication: prevent duplicate triggers by ID or by matching location/requester fingerprint within 20s
        if (isAlertActive && (request.id == lastAlertedRequestId || (now - lastFingerprintTime) < 20000L)) {
            Log.d(TAG, "ALERT_DEDUPLICATED: Ignoring duplicate alert trigger for active request ${request.id}")
            _activeAlertRequest.value = request
            return
        }

        if ((now - lastFingerprintTime) < 20000L && request.id != lastAlertedRequestId) {
            Log.d(TAG, "ALERT_DEDUPLICATED: Rapid duplicate fingerprint detected ($fingerprint), suppressing alert")
            markRequestHandled(request.id)
            return
        }

        recentAlertFingerprints[fingerprint] = now
        lastAlertedRequestId = request.id
        lastAlertTimestamp = now
        isAlertActive = true
        _activeAlertRequest.value = request

        Log.d("CRITICAL_ALERT", "ALERT_TRIGGERED: Driver alert dispatched for requestId=${request.id}, requester=${request.requesterType}, pickup=${request.pickupLocation}, waitingCount=${request.studentsWaiting}")
        Log.d(TAG, "TRIGGERING RIDE ALERT for request ${request.id}")

        // 0. Acquire temporary WakeLock to wake up display and keep CPU running during alert dispatch
        try {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
            @Suppress("DEPRECATION")
            val screenLock = powerManager?.newWakeLock(
                android.os.PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP or
                android.os.PowerManager.ON_AFTER_RELEASE,
                "CampusRide:DriverCriticalAlertScreenWakeLock"
            )
            screenLock?.acquire(10000L)
        } catch (e: Exception) {
            Log.w(TAG, "Screen WakeLock notice: ${e.message}")
        }
        try {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
            val wakeLock = powerManager?.newWakeLock(
                android.os.PowerManager.PARTIAL_WAKE_LOCK,
                "CampusRide:DriverCriticalAlertWakeLock"
            )
            wakeLock?.acquire(15000L)
        } catch (e: Exception) {
            Log.w(TAG, "WakeLock acquisition notice: ${e.message}")
        }

        // Clean up any stale preview or previous audio/vibration before starting fresh
        stopSoundAndVibration(context)

        // Load driver sound preference
        val prefs = context.getSharedPreferences("campus_ride_prefs", Context.MODE_PRIVATE)
        val ringtoneUriStr = prefs.getString("driver_ringtone_uri", "default") ?: "default"
        val repeatingEnabled = prefs.getBoolean("driver_repeating_alert_enabled", true)

        val resolvedUri = resolveRingtoneUri(context, ringtoneUriStr)

        // 1. Start Ringtone Audio Playback (Single Play on ALARM audio stream)
        startRingtonePlayback(context, resolvedUri, repeating = false)

        // 2. Start Single Waveform Vibration Pattern
        startVibration(context)

        // 3. Post system high-priority notification with full-screen intent
        try {
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra("requestId", request.id)
                putExtra("rideId", request.id)
                putExtra("type", "RIDE_REQUEST")
                putExtra("requesterType", request.requesterType.name)
                putExtra("pickupLocation", request.pickupLocation)
                putExtra("passengerName", request.studentName)
            }
            val pendingIntent = PendingIntent.getActivity(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val acceptIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra("requestId", request.id)
                putExtra("rideId", request.id)
                putExtra("type", "RIDE_REQUEST")
                putExtra("action", "ACCEPT")
            }
            val acceptPendingIntent = PendingIntent.getActivity(
                context,
                1,
                acceptIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val declineIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra("requestId", request.id)
                putExtra("rideId", request.id)
                putExtra("type", "RIDE_REQUEST")
                putExtra("action", "DECLINE")
            }
            val declinePendingIntent = PendingIntent.getActivity(
                context,
                2,
                declineIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val title = if (request.requesterType == com.example.data.model.RequesterType.FACULTY) {
                "🚨 FACULTY • ${request.pickupLocationEnum.shortLabel}"
            } else {
                "🚨 ${request.pickupLocationEnum.shortLabel}"
            }
            val waitingStr = if (request.studentsWaiting == 1) "1 STUDENT WAITING" else "${request.studentsWaiting} STUDENTS WAITING"
            val text = "$waitingStr • Campus Ride request"

            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle(title)
                .setContentText(text)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setContentIntent(pendingIntent)
                .setFullScreenIntent(pendingIntent, true)
                .addAction(android.R.drawable.ic_input_add, "ACCEPT", acceptPendingIntent)
                .addAction(android.R.drawable.ic_delete, "DECLINE", declinePendingIntent)
                .setOngoing(true)
                .setAutoCancel(false)
                .setSound(null)
                .setVibrate(longArrayOf(0))
                .build()

            val notificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.notify(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.e(TAG, "Error posting notification", e)
        }

        // 4. Auto-expire alert after 90 seconds if not accepted or declined
        autoExpireRunnable?.let { mainHandler.removeCallbacks(it) }
        autoExpireRunnable = Runnable {
            if (isAlertActive && _activeAlertRequest.value?.id == request.id) {
                Log.d(TAG, "Alert timed out after 90 seconds without driver action")
                stopAlert(context, reason = "EXPIRED")
            }
        }
        mainHandler.postDelayed(autoExpireRunnable!!, 90_000L)
    }

    private fun startRingtonePlayback(context: Context, ringtoneUri: Uri, repeating: Boolean) {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val ringerMode = audioManager?.ringerMode ?: AudioManager.RINGER_MODE_NORMAL
        val ringerModeStr = when (ringerMode) {
            AudioManager.RINGER_MODE_SILENT -> "SILENT"
            AudioManager.RINGER_MODE_VIBRATE -> "VIBRATE"
            AudioManager.RINGER_MODE_NORMAL -> "NORMAL"
            else -> "UNKNOWN ($ringerMode)"
        }
        Log.d(TAG, "RINGER MODE = $ringerModeStr")

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        requestAudioFocus(context, audioAttributes)

        try {
            val ringtone = RingtoneManager.getRingtone(context, ringtoneUri)
            if (ringtone != null) {
                ringtone.audioAttributes = audioAttributes
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    ringtone.isLooping = repeating
                }
                ringtone.play()
                activeRingtone = ringtone
                Log.d(TAG, "RINGTONE STARTED: uri = $ringtoneUri, looping = $repeating, title = ${ringtone.getTitle(context)}")

                // For pre-Android P devices where isLooping is not supported on Ringtone:
                if (repeating && Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
                    scheduleRingtoneLoopCheck(ringtone)
                }
                return
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed playing Ringtone via RingtoneManager, trying MediaPlayer fallback", e)
        }

        // Secondary fallback: MediaPlayer
        try {
            val mp = android.media.MediaPlayer().apply {
                setAudioAttributes(audioAttributes)
                setDataSource(context, ringtoneUri)
                isLooping = repeating
                prepare()
                start()
            }
            activeMediaPlayer = mp
            Log.d(TAG, "RINGTONE STARTED via MediaPlayer fallback: uri = $ringtoneUri")
        } catch (e: Exception) {
            Log.e(TAG, "Failed playing sound via MediaPlayer fallback", e)
        }
    }

    private fun scheduleRingtoneLoopCheck(ringtone: Ringtone) {
        ringtoneLoopRunnable?.let { mainHandler.removeCallbacks(it) }
        ringtoneLoopRunnable = object : Runnable {
            override fun run() {
                if (isAlertActive && activeRingtone == ringtone) {
                    if (!ringtone.isPlaying) {
                        try {
                            ringtone.play()
                        } catch (e: Exception) {
                            Log.w(TAG, "Error re-triggering ringtone loop", e)
                        }
                    }
                    mainHandler.postDelayed(this, 1500L)
                }
            }
        }
        mainHandler.postDelayed(ringtoneLoopRunnable!!, 1500L)
    }

    private fun startVibration(context: Context) {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager =
                    context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vibratorManager?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }

            if (vibrator == null || !vibrator.hasVibrator()) {
                Log.w(TAG, "Device has no vibrator available")
                return
            }

            activeVibrator = vibrator
            // Single distinct alert pattern: 800ms vibrate, 300ms pause, 800ms vibrate (non-repeating: -1)
            val pattern = longArrayOf(0, 800, 300, 800)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val vibrationAttributes = VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM)
                val effect = VibrationEffect.createWaveform(pattern, -1) // -1 = play once
                vibrator.vibrate(effect, vibrationAttributes)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                val effect = VibrationEffect.createWaveform(pattern, -1) // -1 = play once
                vibrator.vibrate(effect, audioAttributes)
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(pattern, -1)
            }
            Log.d(TAG, "VIBRATION STARTED: Single waveform pattern [0, 800, 300, 800] (repeat=-1)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed starting vibration", e)
        }
    }

    private fun requestAudioFocus(context: Context, audioAttributes: AudioAttributes) {
        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(audioAttributes)
                    .setAcceptsDelayedFocusGain(false)
                    .setOnAudioFocusChangeListener { focusChange ->
                        Log.d(TAG, "Audio focus state changed: $focusChange")
                    }
                    .build()
                audioFocusRequest = request
                val res = audioManager.requestAudioFocus(request)
                Log.d(TAG, "AUDIO STATE = Focus requested (O+), result = $res")
            } else {
                @Suppress("DEPRECATION")
                val res = audioManager.requestAudioFocus(
                    null,
                    AudioManager.STREAM_ALARM,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
                )
                Log.d(TAG, "AUDIO STATE = Focus requested (pre-O), result = $res")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error requesting audio focus", e)
        }
    }

    private fun abandonAudioFocus(context: Context) {
        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                (audioFocusRequest as? AudioFocusRequest)?.let {
                    audioManager.abandonAudioFocusRequest(it)
                }
                audioFocusRequest = null
            } else {
                @Suppress("DEPRECATION")
                audioManager.abandonAudioFocus(null)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error abandoning audio focus", e)
        }
    }

    fun triggerStudentAcceptanceNotification(context: Context, currentRole: UserRole?) {
        if (currentRole != UserRole.STUDENT) {
            return
        }

        try {
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pendingIntent = PendingIntent.getActivity(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(context, STUDENT_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("Request Accepted")
                .setContentText("Your request has been accepted. The driver is on the way.")
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .setVibrate(longArrayOf(0))
                .setSound(null)
                .build()

            val notificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.notify(STUDENT_NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.e(TAG, "Error posting student acceptance notification", e)
        }
    }

    @Synchronized
    fun stopAlert(context: Context, reason: String = "USER_ACTION") {
        Log.d(TAG, "ALERT STOPPED: REASON = $reason")
        isAlertActive = false
        val prevReqId = _activeAlertRequest.value?.id ?: lastAlertedRequestId
        prevReqId?.let { markRequestHandled(it) }
        _activeAlertRequest.value = null

        ringtoneLoopRunnable?.let { mainHandler.removeCallbacks(it) }
        ringtoneLoopRunnable = null
        autoExpireRunnable?.let { mainHandler.removeCallbacks(it) }
        autoExpireRunnable = null

        stopSoundAndVibration(context)

        try {
            val notificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.cancel(NOTIFICATION_ID)
        } catch (e: Exception) {
            Log.e(TAG, "Error cancelling notification", e)
        }
    }

    private fun stopSoundAndVibration(context: Context) {
        stopRingtonePreview()

        try {
            activeRingtone?.let {
                if (it.isPlaying) {
                    it.stop()
                }
            }
            activeRingtone = null
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping active ringtone", e)
        }

        try {
            activeMediaPlayer?.let {
                if (it.isPlaying) {
                    it.stop()
                }
                it.release()
            }
            activeMediaPlayer = null
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing active media player", e)
        }

        try {
            activeVibrator?.cancel()
            activeVibrator = null
        } catch (e: Exception) {
            Log.e(TAG, "Error cancelling vibrator", e)
        }

        abandonAudioFocus(context)
    }
}
