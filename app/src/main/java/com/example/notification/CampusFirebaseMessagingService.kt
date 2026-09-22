package com.example.notification

import android.app.ActivityManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.data.api.CampusBackendClient
import com.example.data.api.FcmTokenSyncRequest
import com.example.data.model.RequesterType
import com.example.data.model.RideRequest
import com.example.data.model.RideRequestStatus
import com.example.data.model.UserRole
import com.example.data.repository.CampusRideRepository
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class CampusFirebaseMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        val masked = FcmRoleNotificationManager.maskToken(token)
        Log.d("CAMPUS_RIDE_AUDIT", "DRIVER_TOKEN_REFRESH_ON_NEW_TOKEN: onNewToken() triggered. Token: $masked")

        if (token.isBlank() || token.startsWith("fallback_")) {
            Log.w("CAMPUS_RIDE_AUDIT", "onNewToken received invalid or fallback token, skipping transmission.")
            return
        }

        // Acquire a partial wake lock to guarantee CPU stays active during immediate network transmission
        try {
            val powerManager = applicationContext.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
            val wakeLock = powerManager?.newWakeLock(
                android.os.PowerManager.PARTIAL_WAKE_LOCK,
                "CampusRide:FcmTokenRefreshWakeLock"
            )
            wakeLock?.acquire(20000L) // Auto-release after 20 seconds
        } catch (e: Exception) {
            Log.w("CampusFcmService", "WakeLock acquisition notice: ${e.message}")
        }

        val prefs = getSharedPreferences("campus_ride_prefs", Context.MODE_PRIVATE)
        prefs.edit()
            .putString("fcm_token", token)
            .putString("driver_fcm_token", token)
            .putBoolean("fcm_hard_failure_detected", false)
            .putLong("last_token_refresh_timestamp", System.currentTimeMillis())
            .apply()

        val savedRoleStr = prefs.getString("saved_user_role", null)
        val savedRole = UserRole.fromString(savedRoleStr) ?: UserRole.STUDENT
        val savedCartId = prefs.getString("selected_driver_cart_id", "cart_1") ?: "cart_1"

        Log.d("CAMPUS_RIDE_AUDIT", "onNewToken() dispatching token refresh for role=${savedRole.name}, cartId=$savedCartId")

        // If the device is running as a driver, immediately transmit the refreshed token to the backend
        if (savedRole == UserRole.DRIVER) {
            FcmRoleNotificationManager.transmitDriverTokenImmediately(applicationContext, token, savedCartId)
        } else {
            FcmRoleNotificationManager.saveAndSyncToken(applicationContext, token, savedRole)
        }
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)
        
        val isForeground = isAppInForeground()
        Log.d(TAG, "FCM message received, app foreground=$isForeground")

        val data = remoteMessage.data
        if (data.isNotEmpty()) {
            val type = data["type"] ?: "RIDE_REQUEST"
            if (type == "RIDE_REQUEST" || type == "NEW_RIDE_REQUEST") {
                // Check saved user role and driver preferences from SharedPreferences
                val prefs = getSharedPreferences("campus_ride_prefs", Context.MODE_PRIVATE)
                val savedRoleStr = prefs.getString("saved_user_role", null)
                val activeRole = UserRole.fromString(savedRoleStr)
                val hasDriverToken = !prefs.getString("driver_fcm_token", null).isNullOrBlank()
                val isDriverDevice = (activeRole == UserRole.DRIVER) || hasDriverToken || prefs.contains("selected_driver_cart_id")

                if (!isDriverDevice) {
                    Log.d(TAG, "Ignoring $type message on non-driver device (role=${savedRoleStr ?: "none"})")
                    return
                }

                val reqId = data["requestId"] ?: data["rideId"] ?: data["id"] ?: data["request_id"] ?: data["ride_id"] ?: "req_dispatch"

                if (CriticalAlertManager.isRequestHandled(reqId)) {
                    Log.d(TAG, "Request $reqId already handled")
                    return
                }

                val cartId = data["assignedCartId"] ?: "cart_1"
                val myCartId = prefs.getString("selected_driver_cart_id", "cart_1") ?: "cart_1"

                // If request specifies a different cart than the one this driver is operating, ignore
                if (cartId.isNotBlank() && cartId != "all" && cartId != myCartId) {
                    Log.d(TAG, "Ignoring $type message for cart $cartId (this device is driving $myCartId)")
                    return
                }

                val requesterTypeStr = data["requesterType"] ?: "STUDENT"
                val requesterType = if (requesterTypeStr == "FACULTY") RequesterType.FACULTY else RequesterType.STUDENT
                val pickupLoc = data["pickupLocation"] ?: "Main Gate"
                
                val passengerName = data["passengerName"]?.takeIf { it.isNotBlank() }
                    ?: data["studentName"]?.takeIf { it.isNotBlank() }
                    ?: if (requesterType == RequesterType.FACULTY) "Faculty Member" else "Passenger"

                val distMeters = data["distanceToGateMeters"]?.toIntOrNull() ?: 0
                val cartName = data["assignedCartName"] ?: "Golf Cart 1"

                try {
                    val powerManager = applicationContext.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
                    val wakeLock = powerManager?.newWakeLock(
                        android.os.PowerManager.PARTIAL_WAKE_LOCK,
                        "CampusRide:FcmServiceWakeLock"
                    )
                    wakeLock?.acquire(10000L)
                } catch (e: Exception) {
                    Log.w("CampusFcmService", "WakeLock notice: ${e.message}")
                }

                val studentsWaitingCount = data["studentsWaiting"]?.toIntOrNull() ?: 1

                val rideRequest = RideRequest(
                    id = reqId,
                    requesterType = requesterType,
                    studentName = passengerName,
                    pickupLocation = pickupLoc,
                    distanceToGateMeters = distMeters,
                    studentsWaiting = studentsWaitingCount,
                    status = RideRequestStatus.PENDING,
                    timestamp = System.currentTimeMillis(),
                    assignedCartId = cartId,
                    assignedCartName = cartName
                )

                // Check notification permission
                val hasNotificationPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
                } else {
                    true
                }
                Log.d(TAG, "POST_NOTIFICATIONS permission granted: $hasNotificationPermission")

                CriticalAlertManager.initNotificationChannel(applicationContext)
                Log.d("CAMPUS_RIDE_AUDIT", "10. DRIVER_NOTIFICATION_RECEIVED: requestId=$reqId, cartId=$cartId, pickup=$pickupLoc, waitingCount=$studentsWaitingCount, activeRole=${activeRole?.name ?: "DRIVER"}")

                try {
                    CampusRideRepository.getInstance(applicationContext).onIncomingRideRequestReceived(rideRequest)
                } catch (e: Exception) {
                    Log.w("CampusFcmService", "Failed to update repository state from FCM: ${e.message}")
                }

                CriticalAlertManager.triggerCriticalDriverAlert(
                    context = applicationContext,
                    request = rideRequest,
                    currentRole = UserRole.DRIVER
                )
            }
        }
    }

    private fun isAppInForeground(): Boolean {
        return try {
            val appProcessInfo = ActivityManager.RunningAppProcessInfo()
            ActivityManager.getMyMemoryState(appProcessInfo)
            appProcessInfo.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
        } catch (e: Exception) {
            false
        }
    }

    companion object {
        private const val TAG = "CampusFcmService"
    }
}
