package com.example.ui.permissions

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.example.notification.CriticalAlertManager
import com.example.data.model.UserRole
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

object PermissionUtils {
    fun hasPreciseLocationPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun hasLocationPermission(context: Context): Boolean {
        val fine = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    fun hasBackgroundLocationPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    fun isGpsEnabled(context: Context): Boolean {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        return lm?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true ||
               lm?.isProviderEnabled(LocationManager.NETWORK_PROVIDER) == true
    }

    fun hasNotificationPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    fun openAppSettings(context: Context) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
    }

    fun openLocationSettings(context: Context) {
        val intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
    }

    fun openNotificationSettings(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                    putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
            } catch (e: Exception) {
                openAppSettings(context)
            }
        } else {
            openAppSettings(context)
        }
    }

    fun isDriverNotificationChannelEnabled(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            val channel = nm?.getNotificationChannel(com.example.notification.CriticalAlertManager.CHANNEL_ID)
            return channel != null && channel.importance != NotificationManager.IMPORTANCE_NONE
        }
        return true
    }

    fun isFcmTokenRegistered(context: Context): Boolean {
        val prefs = context.getSharedPreferences("campus_ride_prefs", Context.MODE_PRIVATE)
        val token = prefs.getString("fcm_token", null) ?: prefs.getString("driver_fcm_token", null)
        if (com.example.notification.FcmRoleNotificationManager.isRealFcmToken(token)) {
            return true
        }

        // In environments where FCM registration experiences a hard failure (e.g., emulator without GMS),
        // a fallback device token allows fallback real-time Firestore messaging.
        val hadHardFailure = prefs.getBoolean("fcm_hard_failure_detected", false)
        if (hadHardFailure && !token.isNullOrBlank()) {
            return true
        }

        // Trigger real FCM subscription & token fetch without faking tokens
        if (hasNotificationPermission(context)) {
            com.example.notification.FcmRoleNotificationManager.syncRoleFcmSubscription(context, UserRole.DRIVER)
        }
        return false
    }

    fun isBackgroundOperationAvailable(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
            am?.isBackgroundRestricted == false
        } else {
            true
        }
    }

    fun isChannelSoundEnabled(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            val channel = nm?.getNotificationChannel(com.example.notification.CriticalAlertManager.CHANNEL_ID)
            return channel == null || (channel.sound != null && channel.importance >= NotificationManager.IMPORTANCE_DEFAULT)
        }
        return true
    }

    fun isChannelVibrationEnabled(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            val channel = nm?.getNotificationChannel(com.example.notification.CriticalAlertManager.CHANNEL_ID)
            return channel == null || channel.shouldVibrate()
        }
        return true
    }

    fun openNotificationChannelSettings(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val intent = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).apply {
                    putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    putExtra(Settings.EXTRA_CHANNEL_ID, com.example.notification.CriticalAlertManager.CHANNEL_ID)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
            } catch (e: Exception) {
                openAppSettings(context)
            }
        } else {
            openAppSettings(context)
        }
    }

    fun isBatteryOptimizationIgnored(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
            return pm?.isIgnoringBatteryOptimizations(context.packageName) == true
        }
        return true
    }

    fun requestIgnoreBatteryOptimizations(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:${context.packageName}")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
            } catch (e: Exception) {
                try {
                    val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(intent)
                } catch (ex: Exception) {
                    openAppSettings(context)
                }
            }
        }
    }

    fun isDriverAllRequirementsMet(context: Context): Boolean {
        val hasPrecise = hasPreciseLocationPermission(context)
        val hasBgLoc = hasBackgroundLocationPermission(context)
        val hasNotif = hasNotificationPermission(context)
        val isChan = isDriverNotificationChannelEnabled(context)
        val isGps = isGpsEnabled(context)
        val isBat = isBatteryOptimizationIgnored(context)
        val isFcm = isFcmTokenRegistered(context)
        val isBgOp = isBackgroundOperationAvailable(context)
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val isRinger = audioManager?.ringerMode == AudioManager.RINGER_MODE_NORMAL
        return hasPrecise && hasBgLoc && hasNotif && isChan && isGps && isBat && isFcm && isBgOp && isRinger
    }
}

@Composable
fun StudentPermissionGuard(
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    var hasLocation by remember { mutableStateOf(PermissionUtils.hasLocationPermission(context)) }
    var isGpsOn by remember { mutableStateOf(PermissionUtils.isGpsEnabled(context)) }

    fun refreshStatus() {
        hasLocation = PermissionUtils.hasLocationPermission(context)
        isGpsOn = PermissionUtils.isGpsEnabled(context)
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                refreshStatus()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val locationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { refreshStatus() }

    if (hasLocation && isGpsOn) {
        content()
    } else {
        val (title, description, primaryLabel, primaryAction, icon) = when {
            !isGpsOn -> Quintuple(
                "Location Services Required",
                "Location Services (GPS) are currently turned off. Campus Ride requires GPS to verify whether you are within 70 meters of the IIIT Bhagalpur Main Gate before requesting a ride.",
                "Turn On GPS",
                { PermissionUtils.openLocationSettings(context) },
                Icons.Default.GpsFixed
            )
            else -> Quintuple(
                "Location Permission Required",
                "Campus Ride requires access to your location to verify your proximity to the IIIT Bhagalpur Main Gate.",
                "Grant Location Permission",
                { locationLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION) },
                Icons.Default.LocationOn
            )
        }

        PermissionRequiredPopupScreen(
            title = title,
            description = description,
            icon = icon,
            primaryButtonLabel = primaryLabel,
            onPrimaryAction = primaryAction,
            onRetry = { refreshStatus() }
        )
    }
}

@Composable
fun FacultyPermissionGuard(
    content: @Composable () -> Unit
) {
    // Faculty does NOT require any permissions. Direct access to Faculty Dashboard.
    content()
}

@Composable
fun DriverPermissionGuard(
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("campus_ride_prefs", Context.MODE_PRIVATE) }
    val audioManager = remember { context.getSystemService(android.content.Context.AUDIO_SERVICE) as? android.media.AudioManager }
    var isSetupCompleted by remember {
        mutableStateOf(prefs.getBoolean("pref_driver_setup_completed", false))
    }
    val repository = remember { com.example.data.repository.CampusRideRepository.getInstance(context) }
    val driverDutyState by repository.driverDutyState.collectAsState()
    val lunchBreakRemainingSeconds by repository.lunchBreakRemainingSeconds.collectAsState()
    val isLunchBreakActive = (driverDutyState == "Lunch Break" || lunchBreakRemainingSeconds > 0)

    var hasPreciseLocation by remember { mutableStateOf(PermissionUtils.hasPreciseLocationPermission(context)) }
    var hasBackgroundLocation by remember { mutableStateOf(PermissionUtils.hasBackgroundLocationPermission(context)) }
    var hasNotif by remember { mutableStateOf(PermissionUtils.hasNotificationPermission(context)) }
    var isChannelEnabled by remember { mutableStateOf(PermissionUtils.isDriverNotificationChannelEnabled(context)) }
    var isGpsOn by remember { mutableStateOf(PermissionUtils.isGpsEnabled(context)) }
    var isBatteryOptIgnored by remember { mutableStateOf(PermissionUtils.isBatteryOptimizationIgnored(context)) }
    var isFcmRegistered by remember { mutableStateOf(PermissionUtils.isFcmTokenRegistered(context)) }
    var isBackgroundOpAvailable by remember { mutableStateOf(PermissionUtils.isBackgroundOperationAvailable(context)) }
    var isRingerNormal by remember { mutableStateOf(audioManager?.ringerMode == android.media.AudioManager.RINGER_MODE_NORMAL) }

    fun refreshStatus() {
        CriticalAlertManager.initNotificationChannel(context)
        hasPreciseLocation = PermissionUtils.hasPreciseLocationPermission(context)
        hasBackgroundLocation = PermissionUtils.hasBackgroundLocationPermission(context)
        hasNotif = PermissionUtils.hasNotificationPermission(context)
        isChannelEnabled = PermissionUtils.isDriverNotificationChannelEnabled(context)
        isGpsOn = PermissionUtils.isGpsEnabled(context)
        isBatteryOptIgnored = PermissionUtils.isBatteryOptimizationIgnored(context)
        isFcmRegistered = PermissionUtils.isFcmTokenRegistered(context)
        isBackgroundOpAvailable = PermissionUtils.isBackgroundOperationAvailable(context)
        isRingerNormal = audioManager?.ringerMode == android.media.AudioManager.RINGER_MODE_NORMAL
        isSetupCompleted = prefs.getBoolean("pref_driver_setup_completed", false)
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                refreshStatus()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val isAllReady = hasPreciseLocation && hasBackgroundLocation && hasNotif && isChannelEnabled && isGpsOn && isBatteryOptIgnored && isFcmRegistered && isBackgroundOpAvailable && isRingerNormal

    if (isAllReady || isLunchBreakActive || isSetupCompleted) {
        content()
    } else {
        DriverNotificationSetupScreen(onContinue = {
            prefs.edit().putBoolean("pref_driver_setup_completed", true).commit()
            isSetupCompleted = true
            refreshStatus()
        })
    }
}

@Composable
private fun PermissionRequiredPopupScreen(
    title: String,
    description: String,
    icon: ImageVector,
    primaryButtonLabel: String,
    onPrimaryAction: () -> Unit,
    onRetry: () -> Unit
) {
    val context = LocalContext.current

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Card(
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .size(68.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(36.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    Text(
                        text = title,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    Text(
                        text = description,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        lineHeight = 18.sp
                    )

                    Spacer(modifier = Modifier.height(28.dp))

                    // Primary Action Button (Grant or Open Specific Setting)
                    Button(
                        onClick = onPrimaryAction,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = primaryButtonLabel,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Open App Settings Button
                    OutlinedButton(
                        onClick = { PermissionUtils.openAppSettings(context) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Open Settings",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Retry Button
                    OutlinedButton(
                        onClick = onRetry,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Retry",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }
}

private data class Quintuple<A, B, C, D, E>(
    val first: A,
    val second: B,
    val third: C,
    val fourth: D,
    val fifth: E
)
