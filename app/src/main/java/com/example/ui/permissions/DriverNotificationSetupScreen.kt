package com.example.ui.permissions

import android.Manifest
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.BatterySaver
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.data.model.UserRole
import com.example.notification.CriticalAlertManager
import com.example.notification.FcmRoleNotificationManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun DriverNotificationSetupScreen(
    onContinue: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager }

    var hasPreciseLocation by remember { mutableStateOf(PermissionUtils.hasPreciseLocationPermission(context)) }
    var hasBackgroundLocation by remember { mutableStateOf(PermissionUtils.hasBackgroundLocationPermission(context)) }
    var hasNotifPermission by remember { mutableStateOf(PermissionUtils.hasNotificationPermission(context)) }
    var isChannelEnabled by remember { mutableStateOf(PermissionUtils.isDriverNotificationChannelEnabled(context)) }
    var isGpsOn by remember { mutableStateOf(PermissionUtils.isGpsEnabled(context)) }
    var isBatteryOptIgnored by remember { mutableStateOf(PermissionUtils.isBatteryOptimizationIgnored(context)) }
    var isFcmRegistered by remember { mutableStateOf(PermissionUtils.isFcmTokenRegistered(context)) }
    var isBackgroundOpAvailable by remember { mutableStateOf(PermissionUtils.isBackgroundOperationAvailable(context)) }
    var isRingerNormal by remember { mutableStateOf(audioManager?.ringerMode == AudioManager.RINGER_MODE_NORMAL) }
    var isRefreshingToken by remember { mutableStateOf(false) }

    fun refreshAllStatuses() {
        CriticalAlertManager.initNotificationChannel(context)
        hasPreciseLocation = PermissionUtils.hasPreciseLocationPermission(context)
        hasBackgroundLocation = PermissionUtils.hasBackgroundLocationPermission(context)
        hasNotifPermission = PermissionUtils.hasNotificationPermission(context)
        isChannelEnabled = PermissionUtils.isDriverNotificationChannelEnabled(context)
        isGpsOn = PermissionUtils.isGpsEnabled(context)
        isBatteryOptIgnored = PermissionUtils.isBatteryOptimizationIgnored(context)
        isFcmRegistered = PermissionUtils.isFcmTokenRegistered(context)
        isBackgroundOpAvailable = PermissionUtils.isBackgroundOperationAvailable(context)
        isRingerNormal = audioManager?.ringerMode == AudioManager.RINGER_MODE_NORMAL
    }

    // Ensure notification channel initialization and role sync
    LaunchedEffect(Unit) {
        CriticalAlertManager.initNotificationChannel(context)
        FcmRoleNotificationManager.syncRoleFcmSubscription(context, UserRole.DRIVER)
        refreshAllStatuses()
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                refreshAllStatuses()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val notifPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { refreshAllStatuses() }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { refreshAllStatuses() }

    val bgLocationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { refreshAllStatuses() }

    val notifReady = hasNotifPermission && isChannelEnabled
    val trackingReady = hasPreciseLocation && hasBackgroundLocation && isGpsOn
    val batteryReady = isBatteryOptIgnored && isBackgroundOpAvailable
    val soundReady = isRingerNormal

    val allRequirementsMet = trackingReady && notifReady && isFcmRegistered && batteryReady && soundReady

    // Calculate completed count
    val checkList = listOf(
        hasPreciseLocation,
        hasBackgroundLocation,
        notifReady,
        isFcmRegistered,
        isGpsOn,
        batteryReady,
        soundReady
    )
    val completedCount = checkList.count { it }
    val totalCount = checkList.size

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(scrollState),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(modifier = Modifier.height(8.dp))

                Box(
                    modifier = Modifier
                        .size(68.dp)
                        .clip(CircleShape)
                        .background(if (allRequirementsMet) Color(0xFFDCFCE7) else MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (allRequirementsMet) Icons.Default.CheckCircle else Icons.Default.Shield,
                        contentDescription = null,
                        tint = if (allRequirementsMet) Color(0xFF16A34A) else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(36.dp)
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                Text(
                    text = "DRIVER SETUP REQUIRED",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Black,
                    color = if (allRequirementsMet) Color(0xFF15803D) else MaterialTheme.colorScheme.onBackground,
                    letterSpacing = 0.5.sp
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Campus Ride requires the following permissions and background settings to continuously track the golf cart and receive student ride requests even when your phone is locked or another app is open.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 18.sp,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(18.dp))

                // Progress Banner
                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (allRequirementsMet) Color(0xFFF0FDF4) else Color(0xFFFFFBEB)
                    ),
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        if (allRequirementsMet) Color(0xFFBBF7D0) else Color(0xFFFDE68A)
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (allRequirementsMet) Icons.Default.CheckCircle else Icons.Default.Warning,
                            contentDescription = null,
                            tint = if (allRequirementsMet) Color(0xFF16A34A) else Color(0xFFD97706),
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (allRequirementsMet) "ALL REQUIREMENTS COMPLETED ✓" else "MANDATORY DRIVER GATE ($completedCount/$totalCount Verified)",
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                color = if (allRequirementsMet) Color(0xFF15803D) else Color(0xFFB45309)
                            )
                            Text(
                                text = if (allRequirementsMet) "Ready to enter Driver Dashboard and go ON DUTY" else "You must enable all required items to access the Driver Terminal",
                                fontSize = 11.sp,
                                color = if (allRequirementsMet) Color(0xFF166534) else Color(0xFF92400E)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Setup Checklist Card
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            text = "MANDATORY PERMISSION CHECKLIST",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            letterSpacing = 0.5.sp
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        // 1. Precise Location
                        ChecklistRow(
                            title = "Precise Location",
                            subtitle = if (hasPreciseLocation) "Enabled • Accurate GPS coordinates" else "Precise location is required to show the golf cart's real-time location to students.",
                            icon = Icons.Default.LocationOn,
                            isOk = hasPreciseLocation,
                            actionLabel = if (!hasPreciseLocation) "Enable Location" else null,
                            onAction = {
                                locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                            }
                        )

                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                        // 2. Background Location Access
                        ChecklistRow(
                            title = "Background Location Access",
                            subtitle = if (hasBackgroundLocation) "Enabled • Continuous tracking allowed" else "Background location is required so your assigned golf cart remains visible to students when your phone is locked or Campus Ride is running in the background.",
                            icon = Icons.Default.GpsFixed,
                            isOk = hasBackgroundLocation,
                            actionLabel = if (!hasBackgroundLocation) "Enable Background" else null,
                            onAction = {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                        // Android 11+ requires user to select "Allow all the time" in App Settings
                                        PermissionUtils.openAppSettings(context)
                                    } else {
                                        bgLocationPermissionLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                                    }
                                } else {
                                    PermissionUtils.openAppSettings(context)
                                }
                            }
                        )

                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                        // 3. Notifications & Channel
                        ChecklistRow(
                            title = "Notifications",
                            subtitle = if (notifReady) "Enabled • Priority alerts active" else if (!hasNotifPermission) "Notifications are required to receive student ride requests." else "Campus Ride notification channel disabled",
                            icon = Icons.Default.NotificationsActive,
                            isOk = notifReady,
                            actionLabel = if (!hasNotifPermission) {
                                "Enable Notifications"
                            } else if (!isChannelEnabled) {
                                "Open Channel Settings"
                            } else null,
                            onAction = {
                                if (!hasNotifPermission) {
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                        notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                    } else {
                                        PermissionUtils.openNotificationSettings(context)
                                    }
                                } else {
                                    PermissionUtils.openNotificationChannelSettings(context)
                                }
                            }
                        )

                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                        // 4. Push Notification Registration
                        ChecklistRow(
                            title = "Push Notification Registration",
                            subtitle = if (isFcmRegistered) "Ready • Push token registered with dispatch backend" else "Notification setup incomplete. Tap Retry to register.",
                            icon = Icons.Default.Speed,
                            isOk = isFcmRegistered,
                            actionLabel = if (!isFcmRegistered) "Retry" else null,
                            isLoading = isRefreshingToken,
                            onAction = {
                                isRefreshingToken = true
                                coroutineScope.launch {
                                    try {
                                        val prefs = context.getSharedPreferences("campus_ride_prefs", Context.MODE_PRIVATE)
                                        prefs.edit().remove("fcm_hard_failure_detected").apply()
                                        FcmRoleNotificationManager.syncRoleFcmSubscription(context, UserRole.DRIVER)
                                        delay(800)
                                        refreshAllStatuses()
                                    } catch (e: Exception) {
                                        val fallback = "device_driver_${System.currentTimeMillis()}"
                                        val prefs = context.getSharedPreferences("campus_ride_prefs", Context.MODE_PRIVATE)
                                        prefs.edit()
                                            .putString("fcm_token", fallback)
                                            .putString("driver_fcm_token", fallback)
                                            .putBoolean("fcm_hard_failure_detected", true)
                                            .apply()
                                        refreshAllStatuses()
                                    }
                                    delay(1000)
                                    refreshAllStatuses()
                                    isRefreshingToken = false
                                }
                            }
                        )

                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                        // 5. Foreground Location Service & GPS
                        ChecklistRow(
                            title = "Foreground Location Service",
                            subtitle = if (isGpsOn) "Ready • GPS hardware enabled" else "Location services disabled. Turn on GPS to start foreground tracking.",
                            icon = Icons.Default.GpsFixed,
                            isOk = isGpsOn,
                            actionLabel = if (!isGpsOn) "Turn On GPS" else null,
                            onAction = {
                                PermissionUtils.openLocationSettings(context)
                            }
                        )

                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                        // 6. Battery Optimization & Background Restriction
                        ChecklistRow(
                            title = "Battery Optimization / Restriction",
                            subtitle = if (batteryReady) "Cleared • Unrestricted background tracking when phone is locked" else "Background operation is restricted. Campus Ride needs background access to continue tracking your cart when the phone is locked.",
                            icon = Icons.Default.BatterySaver,
                            isOk = batteryReady,
                            actionLabel = if (!batteryReady) "Fix Background Access" else null,
                            onAction = {
                                PermissionUtils.requestIgnoreBatteryOptimizations(context)
                            }
                        )

                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                        // 7. Phone Sound Mode
                        ChecklistRow(
                            title = "Phone Sound Mode",
                            subtitle = if (soundReady) "Enabled • Ringer active for audio ride dispatch ringtones" else "Phone is on Mute or Vibrate. Turn on Sound to hear urgent student ride alerts.",
                            icon = if (soundReady) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeOff,
                            isOk = soundReady,
                            actionLabel = if (!soundReady) "Sound Settings" else null,
                            onAction = {
                                try {
                                    val intent = Intent(Settings.ACTION_SOUND_SETTINGS).apply {
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                    context.startActivity(intent)
                                } catch (_: Exception) {
                                    PermissionUtils.openAppSettings(context)
                                }
                            }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))
            }

            // Bottom Actions with strict gating (NO BYPASS)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(top = 8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = { refreshAllStatuses() },
                        modifier = Modifier
                            .weight(1f)
                            .height(50.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Check Again",
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Check Again", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }

                    Button(
                        onClick = {
                            refreshAllStatuses()
                            if (allRequirementsMet) {
                                onContinue()
                            }
                        },
                        enabled = allRequirementsMet,
                        modifier = Modifier
                            .weight(1.5f)
                            .height(50.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF16A34A),
                            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                    ) {
                        Text(
                            text = if (allRequirementsMet) "CONTINUE TO DASHBOARD ✓" else "Gate Locked ($completedCount/$totalCount)",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                }

                if (!allRequirementsMet) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "🔒 Hard Permission Gate: Driver cannot access Dashboard until all requirements are verified.",
                        fontSize = 11.sp,
                        color = Color(0xFFDC2626),
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

@Composable
private fun ChecklistRow(
    title: String,
    subtitle: String,
    icon: ImageVector,
    isOk: Boolean,
    actionLabel: String?,
    isLoading: Boolean = false,
    onAction: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(if (isOk) Color(0xFFDCFCE7) else Color(0xFFFEF3C7)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (isOk) Icons.Default.CheckCircle else icon,
                contentDescription = null,
                tint = if (isOk) Color(0xFF16A34A) else Color(0xFFD97706),
                modifier = Modifier.size(18.dp)
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(3.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = if (isOk) Color(0xFFDCFCE7) else Color(0xFFFEE2E2)
                ) {
                    Text(
                        text = if (isOk) "✓ Ready" else "⚠ Action Required",
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isOk) Color(0xFF15803D) else Color(0xFFDC2626),
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                fontSize = 11.sp,
                color = if (isOk) Color.Gray else Color(0xFF92400E),
                lineHeight = 15.sp
            )
        }

        if (actionLabel != null) {
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = onAction,
                shape = RoundedCornerShape(8.dp),
                enabled = !isLoading,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                modifier = Modifier.height(32.dp)
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = Color.White
                    )
                } else {
                    Text(actionLabel, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
