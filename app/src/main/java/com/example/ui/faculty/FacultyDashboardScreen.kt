package com.example.ui.faculty

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.PhoneInTalk
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.CampusCartConfig
import com.example.data.model.GolfCartStatus
import com.example.data.model.PickupLocation
import com.example.data.model.RideRequestStatus
import com.example.data.model.ScheduleStatus
import com.example.data.model.UserRole
import com.example.data.repository.CampusRideRepository
import com.example.ui.components.LiveRouteTrackingCard
import com.example.ui.components.CampusPullToRefreshBox
import com.example.ui.theme.CampusTheme
import com.example.util.CartPhoneDialer
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FacultyDashboardScreen(
    repository: CampusRideRepository,
    onOpenSettings: () -> Unit
) {
    val statusColors = CampusTheme.statusColors
    val context = LocalContext.current
    var showCartCallDialog by remember { mutableStateOf(false) }
    val selectedLocation by repository.selectedFacultyPickup.collectAsState()
    val availableLocations by repository.facultyPickupLocations.collectAsState()
    val isDriverAvailable by repository.isDriverAvailable.collectAsState()
    val activeRequest by repository.activeFacultyRequest.collectAsState()
    val cartState by repository.golfCartState.collectAsState()
    val cart1State by repository.cart1State.collectAsState()
    val cart2State by repository.cart2State.collectAsState()
    val overrideHours by repository.overrideWorkingHours.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    var isRefreshing by remember { mutableStateOf(false) }
    var isSendingRequest by remember { mutableStateOf(false) }

    val isC1Available = cart1State.isInsideCampus && !cart1State.isOutsideCampus &&
            (cart1State.isLive || cart1State.isDriverOnline ||
                    (cart1State.isAvailable && !cart1State.driverStatus.equals("Offline", ignoreCase = true) &&
                            !cart1State.driverStatus.equals("Lunch Break", ignoreCase = true) &&
                            !cart1State.driverStatus.equals("Outside Campus", ignoreCase = true) &&
                            !cart1State.driverStatus.equals("Driver Not Available", ignoreCase = true)))

    val isC2Available = cart2State.isInsideCampus && !cart2State.isOutsideCampus &&
            (cart2State.isLive || cart2State.isDriverOnline ||
                    (cart2State.isAvailable && !cart2State.driverStatus.equals("Offline", ignoreCase = true) &&
                            !cart2State.driverStatus.equals("Lunch Break", ignoreCase = true) &&
                            !cart2State.driverStatus.equals("Outside Campus", ignoreCase = true) &&
                            !cart2State.driverStatus.equals("Driver Not Available", ignoreCase = true)))

    val isAnyDriverAvailable = isC1Available || isC2Available ||
            (isDriverAvailable && (cart1State.isInsideCampus || cart2State.isInsideCampus))

    val scheduleStatus = ScheduleStatus.getCurrentStatus(overrideHours, isAnyDriverAvailable)

    val hasActiveRequest = activeRequest != null && (activeRequest?.status == RideRequestStatus.PENDING || activeRequest?.status == RideRequestStatus.ACCEPTED)

    val canRequest = ((scheduleStatus.isAvailable && isAnyDriverAvailable) || overrideHours) && !isSendingRequest && !hasActiveRequest

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.primaryContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Star,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "Faculty Portal",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 17.sp,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Surface(
                                    shape = CircleShape,
                                    color = MaterialTheme.colorScheme.primaryContainer
                                ) {
                                    Text(
                                        text = "Priority",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                            Text(
                                text = "IIIT Bhagalpur Executive Transport",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                actions = {
                    IconButton(
                        onClick = { showCartCallDialog = true },
                        modifier = Modifier.testTag("faculty_cart_call_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.PhoneInTalk,
                            contentDescription = "Cart Contact",
                            tint = Color(0xFFDC2626)
                        )
                    }
                    IconButton(onClick = onOpenSettings) {
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
                            val result = repository.refreshAllData(UserRole.FACULTY)
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
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .verticalScroll(scrollState)
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
            // Priority Faculty Notice Banner
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Star,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "Priority Pickup Protocol",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Text(
                            text = "Faculty requests bypass student distance checks and dispatch golf carts directly.",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                            lineHeight = 16.sp
                        )
                    }
                }
            }

            // Schedule Notice Banner if off-duty
            if (!scheduleStatus.isAvailable) {
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = statusColors.dangerContainer,
                    border = androidx.compose.foundation.BorderStroke(1.dp, statusColors.dangerBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = null,
                            tint = statusColors.dangerDot
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = scheduleStatus.message,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = statusColors.onDangerContainer
                        )
                    }
                }
            }

            // Live Campus Cart Route Status Card
            LiveRouteTrackingCard(
                cartState = cartState,
                cart1State = cart1State,
                cart2State = cart2State,
                isDriverAvailable = isAnyDriverAvailable,
                facultySelectedLocation = selectedLocation
            )

            // Select Pickup Location Header & Selector
            Text(
                text = "Select Pickup Location",
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                availableLocations.forEach { locName ->
                    val isSelected = (locName == selectedLocation)
                    val locObj = PickupLocation.fromId(locName)

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .clickable { repository.setSelectedFacultyPickup(locName) },
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
                        ),
                        border = if (isSelected) androidx.compose.foundation.BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null,
                        elevation = CardDefaults.cardElevation(defaultElevation = if (isSelected) 2.dp else 1.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .padding(16.dp)
                                .fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (isSelected) Icons.Default.RadioButtonChecked else Icons.Default.RadioButtonUnchecked,
                                contentDescription = null,
                                tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.width(14.dp))
                            Text(
                                text = locObj.emoji,
                                fontSize = 18.sp
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = locObj.displayName,
                                fontSize = 15.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }

            // Active Faculty Request Status Card
            AnimatedVisibility(
                visible = activeRequest != null,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                activeRequest?.let { req ->
                    Card(
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = when (req.status) {
                                RideRequestStatus.ACCEPTED -> statusColors.infoContainer
                                RideRequestStatus.COMPLETED -> statusColors.successContainer
                                else -> statusColors.warningContainer
                            }
                        ),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            when (req.status) {
                                RideRequestStatus.ACCEPTED -> statusColors.infoBorder
                                RideRequestStatus.COMPLETED -> statusColors.successBorder
                                else -> statusColors.warningBorder
                            }
                        ),
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(18.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(42.dp)
                                    .clip(CircleShape)
                                    .background(
                                        when (req.status) {
                                            RideRequestStatus.ACCEPTED -> statusColors.infoDot
                                            RideRequestStatus.COMPLETED -> statusColors.successDot
                                            else -> statusColors.warningDot
                                        }
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = if (req.status == RideRequestStatus.ACCEPTED) Icons.Default.CheckCircle else Icons.Default.NotificationsActive,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(14.dp))
                            Column {
                                Text(
                                    text = req.status.label,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 16.sp,
                                    color = when (req.status) {
                                        RideRequestStatus.ACCEPTED -> statusColors.onInfoContainer
                                        RideRequestStatus.COMPLETED -> statusColors.onSuccessContainer
                                        else -> statusColors.onWarningContainer
                                    }
                                )
                                Text(
                                    text = "Pickup location: ${req.pickupLocation}",
                                    fontSize = 12.sp,
                                    color = when (req.status) {
                                        RideRequestStatus.ACCEPTED -> statusColors.onInfoContainer.copy(alpha = 0.85f)
                                        RideRequestStatus.COMPLETED -> statusColors.onSuccessContainer.copy(alpha = 0.85f)
                                        else -> statusColors.onWarningContainer.copy(alpha = 0.85f)
                                    }
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Faculty Request CTA
            Button(
                onClick = {
                    scope.launch {
                        isSendingRequest = true
                        try {
                            val result = repository.sendFacultyRideRequest(selectedLocation)
                            result.onSuccess {
                                snackbarHostState.showSnackbar("Driver has been successfully notified.")
                            }.onFailure { ex ->
                                snackbarHostState.showSnackbar(ex.message ?: "Failed to send request")
                            }
                        } finally {
                            isSendingRequest = false
                        }
                    }
                },
                enabled = canRequest,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                ),
                elevation = ButtonDefaults.buttonElevation(defaultElevation = 4.dp)
            ) {
                if (isSendingRequest) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.5.dp
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "Sending Request...",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.NotificationsActive,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = when {
                            hasActiveRequest -> "Request Pending"
                            !isAnyDriverAvailable && !overrideHours -> "Driver Not Available"
                            else -> "Request Priority Pickup"
                        },
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
        }
    }

    if (showCartCallDialog) {
        AlertDialog(
            onDismissRequest = { showCartCallDialog = false },
            icon = {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFFEE2E2)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.PhoneInTalk,
                        contentDescription = null,
                        tint = Color(0xFFDC2626),
                        modifier = Modifier.size(24.dp)
                    )
                }
            },
            title = {
                Text(
                    text = "Contact Campus Cart",
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Select a cart to open the system dialer with its fixed in-cart telephone number:",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Surface(
                        onClick = {
                            showCartCallDialog = false
                            CartPhoneDialer.dialCart(context, "cart_1")
                        },
                        shape = RoundedCornerShape(12.dp),
                        color = Color(0xFFF0FDF4),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF86EFAC)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFFDC2626)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.PhoneInTalk,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Cart 1 (Fixed Phone)",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp,
                                    color = Color(0xFF166534)
                                )
                                Text(
                                    text = CampusCartConfig.getCartDisplayNumber("cart_1"),
                                    fontSize = 12.sp,
                                    color = Color(0xFF16A34A)
                                )
                            }
                        }
                    }
                    Surface(
                        onClick = {
                            showCartCallDialog = false
                            CartPhoneDialer.dialCart(context, "cart_2")
                        },
                        shape = RoundedCornerShape(12.dp),
                        color = Color(0xFFEFF6FF),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF93C5FD)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFFDC2626)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.PhoneInTalk,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Cart 2 (Fixed Phone)",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp,
                                    color = Color(0xFF1E3A8A)
                                )
                                Text(
                                    text = CampusCartConfig.getCartDisplayNumber("cart_2"),
                                    fontSize = 12.sp,
                                    color = Color(0xFF2563EB)
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showCartCallDialog = false }) {
                    Text("Close", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            shape = RoundedCornerShape(20.dp),
            containerColor = MaterialTheme.colorScheme.surface
        )
    }
}
