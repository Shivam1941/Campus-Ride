package com.example.ui.driver

import android.widget.Toast
import com.example.data.model.CampusCartConfig
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LocationOff
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import com.example.ui.components.LiveRouteTrackingCard
import com.example.ui.components.CampusPullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.RequesterType
import com.example.data.model.RideRequest
import com.example.data.model.RideRequestStatus
import com.example.data.model.UserRole
import com.example.data.repository.CampusRideRepository
import com.example.location.CampusLandmarkZone
import com.example.location.DriverLocationService
import com.example.ui.permissions.PermissionUtils
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.BatterySaver

import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.GpsOff
import androidx.compose.material.icons.filled.Warning
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.DisposableEffect
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DriverDashboardScreen(
    repository: CampusRideRepository,
    intent: android.content.Intent? = null,
    onOpenSettings: () -> Unit,
    onOpenDiagnostics: () -> Unit = {},
    onOpenRingtoneSettings: () -> Unit = {}
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        repository.saveRole(UserRole.DRIVER)
    }

    val selectedCartId by repository.selectedDriverCartId.collectAsState()
    val isTripActive by repository.isTripActive.collectAsState()
    val cart1State by repository.cart1State.collectAsState()
    val cart2State by repository.cart2State.collectAsState()
    val activeCartState = if (selectedCartId == "cart_1") cart1State else cart2State

    val requests by repository.requests.collectAsState()
    val overrideHours by repository.overrideWorkingHours.collectAsState()
    val driverDutyState by repository.driverDutyState.collectAsState()
    val effectiveDutyStatus by repository.effectiveDutyStatus.collectAsState()
    val schedule = com.example.data.model.ScheduleStatus.getCurrentStatus(
        overrideHours = overrideHours,
        isDriverAvailable = (effectiveDutyStatus == "ON_DUTY" || driverDutyState == "Available")
    )
    val criticalAlertRequest by com.example.notification.CriticalAlertManager.activeAlertRequest.collectAsState()
    val driverName by repository.driverName.collectAsState()
    val driverCartLocked by repository.driverCartLocked.collectAsState()
    val manualDutyOverride by repository.manualDutyOverride.collectAsState()
    val isInsideCampus by repository.isInsideCampus.collectAsState()
    val lunchBreakRemainingSeconds by repository.lunchBreakRemainingSeconds.collectAsState()
    val isOnLunchBreak = (driverDutyState == "Lunch Break" || lunchBreakRemainingSeconds > 0)
    val isLunchBreakUsedToday by repository.isLunchBreakUsedToday.collectAsState()

    val hasGpsLocation by repository.hasGpsLocation.collectAsState()

    var hasLocationPermission by remember { mutableStateOf(PermissionUtils.hasLocationPermission(context)) }
    var isBatteryOptIgnored by remember { mutableStateOf(PermissionUtils.isBatteryOptimizationIgnored(context)) }
    var hasNotifPermission by remember { mutableStateOf(PermissionUtils.hasNotificationPermission(context)) }

    // Handle Intent Action (ACCEPT / DECLINE) from Notification
    LaunchedEffect(intent) {
        val action = intent?.getStringExtra("action")
        val reqId = intent?.getStringExtra("requestId") ?: intent?.getStringExtra("rideId")
        if (!reqId.isNullOrBlank()) {
            if (action == "ACCEPT") {
                repository.acceptRideRequest(reqId)
                com.example.notification.CriticalAlertManager.stopAlert(context, reason = "ACCEPTED")
                snackbarHostState.showSnackbar("Ride Request Accepted")
            } else if (action == "DECLINE") {
                repository.declineRideRequest(reqId)
                com.example.notification.CriticalAlertManager.stopAlert(context, reason = "DECLINED")
                snackbarHostState.showSnackbar("Ride Request Declined")
            }
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasLocationPermission = PermissionUtils.hasLocationPermission(context)
                isBatteryOptIgnored = PermissionUtils.isBatteryOptimizationIgnored(context)
                hasNotifPermission = PermissionUtils.hasNotificationPermission(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
        val coarseGranted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] ?: false
        if (fineGranted || coarseGranted) {
            hasLocationPermission = true
        } else {
            hasLocationPermission = false
            repository.onGpsDisabledOrPermissionMissing()
        }
    }

    LaunchedEffect(Unit) {
        repository.fetchServerAuthoritativeDriverDutyState()
        val granted = PermissionUtils.hasLocationPermission(context)
        hasLocationPermission = granted
        if (!granted) {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    // Launch & maintain continuous background tracking & geofencing service (works when screen is locked/sleeping)
    LaunchedEffect(hasLocationPermission, selectedCartId, manualDutyOverride) {
        if (hasLocationPermission && !manualDutyOverride) {
            DriverLocationService.startTrip(context, selectedCartId)
        } else if (manualDutyOverride) {
            DriverLocationService.stopTrip(context)
        }
    }

    var showLunchBreakConfirmDialog by remember { mutableStateOf(false) }
    var isRefreshing by remember { mutableStateOf(false) }

    val pendingRequests = requests.filter { it.status == RideRequestStatus.PENDING }
        .sortedWith(
            compareByDescending<RideRequest> { it.isPriority }
                .thenByDescending { it.studentsWaiting }
                .thenBy { it.timestamp }
        )
    val activeAcceptedRequests = requests.filter { it.status == RideRequestStatus.ACCEPTED }

    val isDriverAvailable = (driverDutyState == "Available")
    val canStartLunchBreak = isDriverAvailable && activeAcceptedRequests.isEmpty() && pendingRequests.isEmpty() && !isLunchBreakUsedToday

    val displayList = if (isOnLunchBreak) emptyList() else activeAcceptedRequests + pendingRequests

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(MaterialTheme.colorScheme.primary),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Navigation,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = "Driver Terminal",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 18.sp,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "IIIT Bhagalpur Transport",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    },
                    actions = {
                        IconButton(
                            onClick = onOpenSettings,
                            modifier = Modifier.testTag("driver_settings_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Settings,
                                contentDescription = "Settings",
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        titleContentColor = MaterialTheme.colorScheme.onSurface
                    )
                )
            },
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) }
        ) { padding ->
            CampusPullToRefreshBox(
                isRefreshing = isRefreshing,
                onRefresh = {
                    if (!isRefreshing) {
                        isRefreshing = true
                        scope.launch {
                            try {
                                val result = repository.refreshAllData(UserRole.DRIVER)
                                if (result.isFailure) {
                                    snackbarHostState.showSnackbar(
                                        message = "Couldn't refresh. Check your internet connection."
                                    )
                                }
                            } catch (e: Exception) {
                                snackbarHostState.showSnackbar(
                                    message = "Couldn't refresh. Check your internet connection."
                                )
                            } finally {
                                isRefreshing = false
                            }
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                val hasNotifPermission = remember {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                        ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.POST_NOTIFICATIONS
                        ) == PackageManager.PERMISSION_GRANTED
                    } else true
                }

                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                        .testTag("driver_dashboard_scroll_list"),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 48.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    // 1. Missing Notification Permission Banner check
                    if (!hasNotifPermission && !isOnLunchBreak) {
                        item(key = "permission_banner") {
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = Color(0xFFFEF2F2),
                                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFFCA5A5)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(14.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.NotificationsActive,
                                            contentDescription = "Permission Warning",
                                            tint = Color(0xFFDC2626)
                                        )
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Column {
                                            Text(
                                                text = "Alert Notifications Disabled",
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = Color(0xFF991B1B)
                                            )
                                            Text(
                                                text = "Allow notifications for loud incoming pickup alerts",
                                                fontSize = 11.sp,
                                                color = Color(0xFF7F1D1D)
                                            )
                                        }
                                    }
                                    Button(
                                        onClick = {
                                            val intent = android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                                data = android.net.Uri.fromParts("package", context.packageName, null)
                                            }
                                            context.startActivity(intent)
                                        },
                                        shape = RoundedCornerShape(10.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626))
                                    ) {
                                        Text("Settings", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }

                    // 1b. Background Battery Optimization Notice (Sleep / Screen Lock continuous tracking)
                    if (!isBatteryOptIgnored && !isOnLunchBreak) {
                        item(key = "battery_opt_banner") {
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = Color(0xFFFFFBEB),
                                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFFDE68A)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(14.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.BatterySaver,
                                            contentDescription = "Battery Optimization",
                                            tint = Color(0xFFD97706)
                                        )
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Column {
                                            Text(
                                                text = "Enable Lock Screen GPS",
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = Color(0xFF92400E)
                                            )
                                            Text(
                                                text = "Allow background tracking so GPS continues when phone is locked",
                                                fontSize = 11.sp,
                                                color = Color(0xFFB45309)
                                            )
                                        }
                                    }
                                    Button(
                                        onClick = {
                                            PermissionUtils.requestIgnoreBatteryOptimizations(context)
                                        },
                                        shape = RoundedCornerShape(10.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD97706))
                                    ) {
                                        Text("Allow", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }

                    // 1c. 🛺 Assigned Vehicle / Cart Operating Selector
                    item(key = "driver_cart_assignment_card") {
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surface,
                            tonalElevation = 2.dp,
                            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = Icons.Default.Navigation,
                                            contentDescription = "Assigned Cart",
                                            modifier = Modifier.size(20.dp),
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Column {
                                            Text(
                                                text = "ASSIGNED VEHICLE",
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.ExtraBold,
                                                letterSpacing = 1.sp,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                            Text(
                                                text = "Operating as " + (if (selectedCartId == "cart_2") "Cart 2 (Royal Blue)" else "Cart 1 (Emerald Green)"),
                                                fontSize = 11.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }

                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = if (hasLocationPermission && !manualDutyOverride) Color(0xFFDCFCE7) else Color(0xFFF1F5F9)
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(6.dp)
                                                    .clip(CircleShape)
                                                    .background(if (hasLocationPermission && !manualDutyOverride) Color(0xFF16A34A) else Color(0xFF94A3B8))
                                            )
                                            Spacer(modifier = Modifier.width(5.dp))
                                            Text(
                                                text = if (hasLocationPermission && !manualDutyOverride) "Broadcasting GPS" else "GPS Idle",
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = if (hasLocationPermission && !manualDutyOverride) Color(0xFF15803D) else Color(0xFF64748B)
                                            )
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(12.dp))

                                val isCart2 = selectedCartId == "cart_2"
                                val cartLabel = if (isCart2) "CART 2" else "CART 1"
                                val cartThemeColor = if (isCart2) Color(0xFF2563EB) else Color(0xFF16A34A)
                                val cartBgColor = if (isCart2) Color(0xFFEFF6FF) else Color(0xFFF0FDF4)
                                val cartBorderColor = if (isCart2) Color(0xFFBFDBFE) else Color(0xFFBBF7D0)

                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = cartBgColor,
                                    border = androidx.compose.foundation.BorderStroke(1.5.dp, cartBorderColor),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(14.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Box(
                                                modifier = Modifier
                                                    .size(36.dp)
                                                    .clip(CircleShape)
                                                    .background(cartThemeColor.copy(alpha = 0.15f)),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Navigation,
                                                    contentDescription = null,
                                                    tint = cartThemeColor,
                                                    modifier = Modifier.size(20.dp)
                                                )
                                            }
                                            Spacer(modifier = Modifier.width(12.dp))
                                            Column {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Text(
                                                        text = cartLabel,
                                                        fontWeight = FontWeight.Black,
                                                        fontSize = 15.sp,
                                                        color = cartThemeColor
                                                    )
                                                    Spacer(modifier = Modifier.width(8.dp))
                                                    Surface(
                                                        shape = RoundedCornerShape(4.dp),
                                                        color = cartThemeColor.copy(alpha = 0.12f)
                                                    ) {
                                                        Text(
                                                            text = "PERMANENTLY LOCKED",
                                                            fontSize = 9.sp,
                                                            fontWeight = FontWeight.ExtraBold,
                                                            color = cartThemeColor,
                                                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                                                        )
                                                    }
                                                }
                                                val driverPhoneDisplay = if (isCart2) CampusCartConfig.getCartDisplayNumber("cart_2") else CampusCartConfig.getCartDisplayNumber("cart_1")
                                                Text(
                                                    text = "Driver: ${driverName.ifBlank { if (isCart2) "Kartik" else "Shivam" }} ($driverPhoneDisplay)",
                                                    fontSize = 11.5.sp,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }

                                        Icon(
                                            imageVector = Icons.Default.CheckCircle,
                                            contentDescription = "Cart Assignment Locked",
                                            tint = cartThemeColor,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // 2. 🚗 Campus Cart Connected Route Location Timeline Card
                    item(key = "live_route_tracking_card") {
                        LiveRouteTrackingCard(
                            cartState = activeCartState,
                            cart1State = cart1State,
                            cart2State = cart2State,
                            isDriverAvailable = isDriverAvailable,
                            isDriverView = true
                        )
                    }

                    // 3. Lunch Break / Operating Status Section
                    item(key = "lunch_break_section") {
                        if (isOnLunchBreak) {
                            val minutes = lunchBreakRemainingSeconds / 60
                            val seconds = lunchBreakRemainingSeconds % 60
                            val timeRemainingFormatted = String.format(java.util.Locale.getDefault(), "%02d:%02d", minutes, seconds)

                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("lunch_break_active_card"),
                                shape = RoundedCornerShape(20.dp),
                                colors = CardDefaults.cardColors(containerColor = Color(0xFFFFFBEB)),
                                border = androidx.compose.foundation.BorderStroke(1.5.dp, Color(0xFFFDE68A)),
                                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                            ) {
                                Column(
                                    modifier = Modifier
                                        .padding(20.dp)
                                        .fillMaxWidth(),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Restaurant,
                                            contentDescription = "Lunch Break Active",
                                            tint = Color(0xFFD97706),
                                            modifier = Modifier.size(24.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = "Lunch Break",
                                            fontWeight = FontWeight.ExtraBold,
                                            fontSize = 18.sp,
                                            color = Color(0xFFB45309)
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = "Time Remaining",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = Color(0xFF92400E)
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = timeRemainingFormatted,
                                        fontSize = 38.sp,
                                        fontWeight = FontWeight.Black,
                                        color = Color(0xFFD97706),
                                        letterSpacing = 2.sp
                                    )
                                    Spacer(modifier = Modifier.height(12.dp))
                                    OutlinedButton(
                                        onClick = {
                                            repository.endLunchBreakEarly()
                                            scope.launch {
                                                snackbarHostState.showSnackbar("Lunch break ended early. Status set to Available.")
                                            }
                                        },
                                        shape = RoundedCornerShape(12.dp),
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFB45309)),
                                        modifier = Modifier.fillMaxWidth().height(40.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.CheckCircle,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp),
                                            tint = Color(0xFFB45309)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("End Break Early", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                    }
                                }
                            }
                        } else {
                            // Working Schedule Status Card (Authoritative Automatic Duty)
                            val isEffectivelyOnDuty = !manualDutyOverride && (effectiveDutyStatus == "ON_DUTY" || driverDutyState == "Available" || (isInsideCampus && schedule.dutyState == com.example.data.model.ScheduleDutyState.ON_DUTY))
                            val (cardBg, iconBg, statusColor) = when {
                                manualDutyOverride -> Triple(Color(0xFFFEF2F2), Color(0xFFDC2626), Color(0xFF991B1B))
                                isEffectivelyOnDuty -> Triple(Color(0xFFF0FDF4), Color(0xFF16A34A), Color(0xFF15803D))
                                else -> Triple(Color(0xFFFFFBEB), Color(0xFFD97706), Color(0xFFB45309))
                            }

                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(20.dp),
                                    colors = CardDefaults.cardColors(containerColor = cardBg),
                                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .padding(16.dp)
                                            .fillMaxWidth()
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(42.dp)
                                                    .clip(CircleShape)
                                                    .background(iconBg),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Icon(
                                                    imageVector = when {
                                                        manualDutyOverride -> Icons.Default.LocationOff
                                                        isEffectivelyOnDuty -> Icons.Default.GpsFixed
                                                        else -> Icons.Default.LocationOn
                                                    },
                                                    contentDescription = "Operating Status",
                                                    tint = Color.White,
                                                    modifier = Modifier.size(20.dp)
                                                )
                                            }
                                            Spacer(modifier = Modifier.width(14.dp))
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = when {
                                                        manualDutyOverride -> "Off Duty"
                                                        isOnLunchBreak -> "Lunch Break"
                                                        isEffectivelyOnDuty -> "On Duty"
                                                        !hasGpsLocation -> "Acquiring GPS..."
                                                        !isInsideCampus -> "Driver Not Available"
                                                        else -> "On Duty"
                                                    },
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 16.sp,
                                                    color = statusColor
                                                )
                                                Spacer(modifier = Modifier.height(2.dp))
                                                Text(
                                                    text = when {
                                                        manualDutyOverride -> "Manual off duty active"
                                                        isOnLunchBreak -> "Lunch break in progress"
                                                        isEffectivelyOnDuty -> "Inside IIIT Bhagalpur • Live broadcasting"
                                                        !hasGpsLocation -> "Acquiring satellite fix..."
                                                        !isInsideCampus -> "Outside campus boundary • Driver not available"
                                                        else -> "Operating on Campus"
                                                    },
                                                    fontSize = 12.sp,
                                                    fontWeight = FontWeight.Medium,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }

                                        Spacer(modifier = Modifier.height(10.dp))

                                        OutlinedButton(
                                            onClick = onOpenSettings,
                                            shape = RoundedCornerShape(10.dp),
                                            modifier = Modifier.fillMaxWidth().height(38.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Settings,
                                                contentDescription = null,
                                                modifier = Modifier.size(16.dp),
                                                tint = statusColor
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(
                                                text = if (manualDutyOverride) "Resume Duty" else "Service Status",
                                                color = statusColor,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 12.sp
                                            )
                                        }
                                    }
                                }

                                // Lunch Break Card: Once Per Calendar Day Control
                                if (isLunchBreakUsedToday) {
                                    // 🍱 Lunch Break Used State
                                    Card(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .testTag("lunch_break_used_card"),
                                        shape = RoundedCornerShape(16.dp),
                                        colors = CardDefaults.cardColors(containerColor = Color(0xFFF1F5F9)),
                                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFCBD5E1))
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .padding(14.dp)
                                                .fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.CheckCircle,
                                                contentDescription = "Lunch Break Used",
                                                tint = Color(0xFF64748B),
                                                modifier = Modifier.size(20.dp)
                                            )
                                            Spacer(modifier = Modifier.width(10.dp))
                                            Text(
                                                text = "Lunch Break • Used today",
                                                fontWeight = FontWeight.SemiBold,
                                                fontSize = 13.sp,
                                                color = Color(0xFF64748B)
                                            )
                                        }
                                    }
                                } else {
                                    // 🍱 Lunch Break Available Today State
                                    Card(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .testTag("lunch_break_available_card"),
                                        shape = RoundedCornerShape(16.dp),
                                        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFFBEB)),
                                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFFDE68A))
                                    ) {
                                        Column(
                                            modifier = Modifier
                                                .padding(14.dp)
                                                .fillMaxWidth()
                                        ) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.SpaceBetween
                                            ) {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Icon(
                                                        imageVector = Icons.Default.Restaurant,
                                                        contentDescription = null,
                                                        tint = Color(0xFFD97706),
                                                        modifier = Modifier.size(18.dp)
                                                    )
                                                    Spacer(modifier = Modifier.width(8.dp))
                                                    Text(
                                                        text = "Lunch Break",
                                                        fontWeight = FontWeight.Bold,
                                                        fontSize = 14.sp,
                                                        color = Color(0xFF92400E)
                                                    )
                                                }
                                                Surface(
                                                    shape = CircleShape,
                                                    color = Color(0xFFFEF3C7)
                                                ) {
                                                    Text(
                                                        text = "Available today",
                                                        fontSize = 11.sp,
                                                        fontWeight = FontWeight.SemiBold,
                                                        color = Color(0xFFB45309),
                                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                                    )
                                                }
                                            }

                                            Spacer(modifier = Modifier.height(10.dp))

                                            Button(
                                                onClick = { showLunchBreakConfirmDialog = true },
                                                enabled = canStartLunchBreak,
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .height(42.dp)
                                                    .testTag("start_lunch_break_button"),
                                                shape = RoundedCornerShape(10.dp),
                                                colors = ButtonDefaults.buttonColors(
                                                    containerColor = Color(0xFFF59E0B),
                                                    contentColor = Color.White,
                                                    disabledContainerColor = Color(0xFFE2E8F0),
                                                    disabledContentColor = Color(0xFF94A3B8)
                                                )
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Restaurant,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Text(
                                                    text = "Start Lunch Break (45 min)",
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 13.sp
                                                )
                                            }

                                            if (!canStartLunchBreak && !isLunchBreakUsedToday) {
                                                Spacer(modifier = Modifier.height(6.dp))
                                                Text(
                                                    text = if (activeAcceptedRequests.isNotEmpty()) "Finish active ride first" else "Set duty to Available first",
                                                    fontSize = 11.sp,
                                                    color = Color(0xFFB45309),
                                                    textAlign = TextAlign.Center,
                                                    modifier = Modifier.fillMaxWidth()
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // 4. Cart Status Summary Row
                    item(key = "status_summary_row") {
                        Surface(
                            shape = RoundedCornerShape(18.dp),
                            color = MaterialTheme.colorScheme.surface,
                            shadowElevation = 2.dp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("driver_status_summary")
                        ) {
                            Row(
                                modifier = Modifier.padding(16.dp),
                                horizontalArrangement = Arrangement.SpaceAround,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(
                                        text = "PENDING REQUESTS",
                                        fontSize = 10.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = if (isOnLunchBreak) "0" else "${pendingRequests.size}",
                                        fontWeight = FontWeight.ExtraBold,
                                        fontSize = 20.sp,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.testTag("driver_pending_count")
                                    )
                                }
                                Box(
                                    modifier = Modifier
                                        .width(1.dp)
                                        .height(28.dp)
                                        .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                                )
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(
                                        text = "ACTIVE ACCEPTED",
                                        fontSize = 10.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = if (isOnLunchBreak) "0" else "${activeAcceptedRequests.size}",
                                        fontWeight = FontWeight.ExtraBold,
                                        fontSize = 20.sp,
                                        color = Color(0xFF16A34A),
                                        modifier = Modifier.testTag("driver_active_count")
                                    )
                                }
                            }
                        }
                    }

                    // 5. Pickup Requests Section Header
                    item(key = "pickup_requests_header") {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Pickup Queue",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                            if (!isOnLunchBreak && pendingRequests.isNotEmpty()) {
                                Surface(
                                    shape = CircleShape,
                                    color = MaterialTheme.colorScheme.primaryContainer
                                ) {
                                    Text(
                                        text = "${pendingRequests.size} Pending",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                    )
                                }
                            }
                        }
                    }

                    // 6. Pickup Queue Items / States
                    if (isOnLunchBreak) {
                        item(key = "lunch_break_empty_queue") {
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = MaterialTheme.colorScheme.surface,
                                shadowElevation = 1.dp,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier.padding(20.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Timer,
                                        contentDescription = null,
                                        tint = Color(0xFFD97706),
                                        modifier = Modifier.size(36.dp)
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = "Lunch Break Active",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 15.sp,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = "Requests paused during break",
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    } else if (displayList.isEmpty()) {
                        item(key = "empty_pickup_queue") {
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = MaterialTheme.colorScheme.surface,
                                shadowElevation = 1.dp,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("driver_empty_queue")
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier.padding(24.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.CheckCircle,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                        modifier = Modifier.size(36.dp)
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = "No requests",
                                        fontWeight = FontWeight.Medium,
                                        fontSize = 14.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    } else {
                        items(displayList, key = { it.id }) { req ->
                            DriverRequestItemCard(
                                request = req,
                                onAccept = { repository.acceptRideRequest(req.id) }
                            )
                        }
                    }

                    // 7. Extra bottom spacing so the lowest element scrolls safely above navigation/gestures
                    item(key = "bottom_spacer") {
                        Spacer(modifier = Modifier.height(24.dp))
                    }
                }
            }
        }

        if (showLunchBreakConfirmDialog) {
            AlertDialog(
                onDismissRequest = { showLunchBreakConfirmDialog = false },
                icon = {
                    Icon(
                        imageVector = Icons.Default.Restaurant,
                        contentDescription = null,
                        tint = Color(0xFFD97706),
                        modifier = Modifier.size(32.dp)
                    )
                },
                title = {
                    Text(
                        text = "Start Lunch Break?",
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                },
                text = {
                    Text(
                        text = "You will become unavailable for ride requests for 45 minutes.\n\nNote: Lunch Break is permitted only once per calendar day.",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            showLunchBreakConfirmDialog = false
                            repository.triggerLunchBreak { result ->
                                if (result.isFailure) {
                                    scope.launch {
                                        snackbarHostState.showSnackbar(
                                            result.exceptionOrNull()?.message ?: "Today's lunch break has already been used."
                                        )
                                    }
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD97706)),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.testTag("confirm_start_lunch_break")
                    ) {
                        Text("Start Lunch Break", fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    OutlinedButton(
                        onClick = { showLunchBreakConfirmDialog = false },
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("Cancel")
                    }
                },
                containerColor = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(20.dp)
            )
        }

        if (!isOnLunchBreak) {
            IncomingDriverAlertOverlay(
                activeRequest = criticalAlertRequest,
                onAccept = { reqId ->
                    repository.acceptRideRequest(reqId)
                },
                onDecline = { reqId ->
                    repository.declineRideRequest(reqId)
                }
            )
        }
    }
}

@Composable
fun DriverRequestItemCard(
    request: RideRequest,
    onAccept: () -> Unit
) {
    val isFaculty = (request.requesterType == RequesterType.FACULTY)

    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .clip(CircleShape)
                            .background(if (isFaculty) MaterialTheme.colorScheme.primary else Color(0xFFEFF6FF)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (isFaculty) Icons.Default.School else Icons.Default.Person,
                            contentDescription = null,
                            tint = if (isFaculty) Color.White else Color(0xFF2563EB),
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = if (isFaculty) MaterialTheme.colorScheme.primary else Color(0xFFDBEAFE)
                        ) {
                            Text(
                                text = if (isFaculty) "Faculty Priority Request" else "Student Request",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isFaculty) Color.White else Color(0xFF1E40AF),
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = request.pickupLocationEnum.emoji,
                                fontSize = 14.sp
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = request.pickupLocationEnum.displayName,
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        if (!isFaculty) {
                            Spacer(modifier = Modifier.height(3.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Group,
                                    contentDescription = null,
                                    tint = if (request.studentsWaiting >= 5) Color(0xFFDC2626) else Color(0xFF2563EB),
                                    modifier = Modifier.size(13.dp)
                                )
                                Spacer(modifier = Modifier.width(3.dp))
                                Text(
                                    text = "${request.studentsWaiting} ${if (request.studentsWaiting == 1) "student" else "students"} waiting",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (request.studentsWaiting >= 5) Color(0xFFDC2626) else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                if (request.studentsWaiting >= 5) {
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Surface(
                                        shape = RoundedCornerShape(4.dp),
                                        color = Color(0xFFFEE2E2)
                                    ) {
                                        Text(
                                            text = "High Waiting",
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color(0xFFDC2626),
                                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Column(horizontalAlignment = Alignment.End) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = when (request.status) {
                            RideRequestStatus.ACCEPTED -> Color(0xFFDCFCE7)
                            RideRequestStatus.COMPLETED -> Color(0xFFF1F5F9)
                            else -> Color(0xFFFEF2F2)
                        }
                    ) {
                        Text(
                            text = request.status.label,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = when (request.status) {
                                RideRequestStatus.ACCEPTED -> Color(0xFF15803D)
                                RideRequestStatus.COMPLETED -> Color(0xFF64748B)
                                else -> Color(0xFF991B1B)
                            },
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = request.formattedTime,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (request.status == RideRequestStatus.PENDING) {
                Spacer(modifier = Modifier.height(14.dp))
                Button(
                    onClick = onAccept,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = "Accept",
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = "ACCEPT REQUEST", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
            } else if (request.status == RideRequestStatus.ACCEPTED) {
                Spacer(modifier = Modifier.height(12.dp))
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0xFFDCFCE7),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = Color(0xFF16A34A),
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Accepted • Golf Cart En Route",
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            color = Color(0xFF15803D)
                        )
                    }
                }
            }
        }
    }
}
