package com.example.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.NearMe
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.data.model.GolfCartState
import com.example.data.model.GolfCartStatus
import com.example.location.CampusLandmarkZone
import com.example.location.CampusLandmarkZone.Companion.RoutePositionResult
import com.example.location.CampusLandmarkZone.Companion.StopVisualState
import com.example.location.CampusRouteGraph
import com.example.ui.theme.CampusTheme
import java.util.Locale

/**
 * Production Live Campus Cart Tracking Card matching the exact specification:
 * 1. Title & Live Status Indicator (LIVE, STALE, OFFLINE)
 * 2. Embedded Google Map with fixed campus polyline and real-time validated cart GPS
 * 3. Cart Header (Cart 1 / Cart 2) with direct dial action
 * 4. Stop Progress (Main Gate ✓, Trunket ✓, Computer Centre →, Hostel)
 * 5. Current route status & relative time (e.g. "Updated 3 sec ago")
 * 6. Driver GPS telemetry (Accuracy, Coordinates, Speed) when isDriverView = true
 */
@Composable
fun LiveRouteTrackingCard(
    cartState: GolfCartState?,
    cart1State: GolfCartState? = null,
    cart2State: GolfCartState? = null,
    isDriverAvailable: Boolean,
    facultySelectedLocation: String? = null,
    studentLatitude: Double? = null,
    studentLongitude: Double? = null,
    isDriverView: Boolean = false,
    modifier: Modifier = Modifier
) {
    val statusColors = CampusTheme.statusColors
    val effectiveCart1 = cart1State ?: if (cartState?.cartId == "cart_1") cartState else null
    val effectiveCart2 = cart2State ?: if (cartState?.cartId == "cart_2") cartState else null
    val context = LocalContext.current

    var selectedCartId by remember {
        mutableStateOf(
            cartState?.cartId ?: if (effectiveCart2?.isLive == true && effectiveCart1?.isLive != true) "cart_2" else "cart_1"
        )
    }

    LaunchedEffect(cartState?.cartId) {
        if (cartState?.cartId != null) {
            selectedCartId = cartState.cartId
        }
    }

    val displayCart = when (selectedCartId) {
        "cart_2" -> effectiveCart2 ?: cartState
        else -> effectiveCart1 ?: cartState
    }

    val cart1Presence = effectiveCart1?.presenceState ?: com.example.data.model.CartPresenceState.OFFLINE
    val cart2Presence = effectiveCart2?.presenceState ?: com.example.data.model.CartPresenceState.OFFLINE
    val activePresence = displayCart?.presenceState ?: com.example.data.model.CartPresenceState.OFFLINE

    val liveCartCount = (if (cart1Presence.isLocationAvailable) 1 else 0) + (if (cart2Presence.isLocationAvailable) 1 else 0)
    var isFullscreenMapOpen by remember { mutableStateOf(false) }

    val isCartOutside = displayCart?.isOutsideCampus == true || displayCart?.isInsideCampus == false
    val isCartOnline = !isCartOutside && (displayCart?.isDriverOnline == true || isDriverAvailable)
    val isCartOffline = isCartOutside || !isCartOnline || activePresence == com.example.data.model.CartPresenceState.OFFLINE
    val isLocationFresh = !isCartOutside && activePresence == com.example.data.model.CartPresenceState.ONLINE_LOCATION_AVAILABLE
    val isLocationStale = !isCartOutside && activePresence == com.example.data.model.CartPresenceState.ONLINE_LOCATION_STALE
    val isNoLocationYet = !isCartOutside && isCartOnline && activePresence == com.example.data.model.CartPresenceState.ONLINE_NO_LOCATION

    val routeResult: RoutePositionResult? = remember(
        displayCart?.latitude,
        displayCart?.longitude,
        displayCart?.bearing,
        displayCart?.speedKmH,
        displayCart?.cartId,
        isCartOutside,
        isCartOnline
    ) {
        if (!isCartOutside && isCartOnline && displayCart?.hasCoordinates == true && !displayCart.isLocationExpiredOrMissing) {
            CampusLandmarkZone.evaluateRoutePosition(
                latitude = displayCart.latitude,
                longitude = displayCart.longitude,
                bearing = displayCart.bearing,
                relativeMovement = displayCart.relativeMovement,
                speedKmH = displayCart.speedKmH ?: 0,
                accuracy = displayCart.accuracy ?: 0f,
                timestamp = displayCart.locationTimestampMillis ?: displayCart.lastUpdatedMillis ?: System.currentTimeMillis(),
                cartId = displayCart.cartId ?: "cart_1"
            )
        } else null
    }

    val liveRouteProgress = remember(
        displayCart?.cartId,
        selectedCartId,
        displayCart?.latitude,
        displayCart?.longitude,
        displayCart?.bearing,
        displayCart?.relativeMovement,
        facultySelectedLocation,
        isCartOffline,
        isCartOutside,
        isCartOnline
    ) {
        CampusRouteGraph.evaluateLiveProgress(
            cartId = displayCart?.cartId ?: selectedCartId,
            latitude = if (!isCartOutside && isCartOnline && displayCart?.hasCoordinates == true && !displayCart.isLocationExpiredOrMissing) displayCart.latitude else null,
            longitude = if (!isCartOutside && isCartOnline && displayCart?.hasCoordinates == true && !displayCart.isLocationExpiredOrMissing) displayCart.longitude else null,
            bearing = displayCart?.bearing,
            relativeMovement = displayCart?.relativeMovement,
            selectedDestination = facultySelectedLocation,
            isOnline = !isCartOffline
        )
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("live_route_tracking_card"),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth()
        ) {
            // 1. Header: LIVE CART TRACKING + Status Badge (LIVE / STALE / OFFLINE)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "LIVE CART TRACKING",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 1.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    when {
                        liveCartCount >= 2 -> {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = statusColors.successContainer,
                                border = androidx.compose.foundation.BorderStroke(1.dp, statusColors.successBorder)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(7.dp)
                                            .clip(CircleShape)
                                            .background(statusColors.successDot)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "2 CARTS LIVE",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = statusColors.onSuccessContainer,
                                        letterSpacing = 0.5.sp
                                    )
                                }
                            }
                        }
                        liveCartCount == 1 -> {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = statusColors.successContainer,
                                border = androidx.compose.foundation.BorderStroke(1.dp, statusColors.successBorder)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(7.dp)
                                            .clip(CircleShape)
                                            .background(statusColors.successDot)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "1 CART LIVE",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = statusColors.onSuccessContainer,
                                        letterSpacing = 0.5.sp
                                    )
                                }
                            }
                        }
                        isLocationFresh -> {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = statusColors.successContainer,
                                border = androidx.compose.foundation.BorderStroke(1.dp, statusColors.successBorder)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(7.dp)
                                            .clip(CircleShape)
                                            .background(statusColors.successDot)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "LIVE",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = statusColors.onSuccessContainer,
                                        letterSpacing = 0.5.sp
                                    )
                                }
                            }
                        }
                        isLocationStale -> {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = statusColors.warningContainer,
                                border = androidx.compose.foundation.BorderStroke(1.dp, statusColors.warningBorder)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(7.dp)
                                            .clip(CircleShape)
                                            .background(statusColors.warningDot)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "STALE",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = statusColors.onWarningContainer,
                                        letterSpacing = 0.5.sp
                                    )
                                }
                            }
                        }
                        isNoLocationYet -> {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = statusColors.infoContainer,
                                border = androidx.compose.foundation.BorderStroke(1.dp, statusColors.infoBorder)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(7.dp)
                                            .clip(CircleShape)
                                            .background(statusColors.infoDot)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "ONLINE",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = statusColors.onInfoContainer,
                                        letterSpacing = 0.5.sp
                                    )
                                }
                            }
                        }
                        else -> {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = statusColors.neutralContainer,
                                border = androidx.compose.foundation.BorderStroke(1.dp, statusColors.neutralBorder)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(7.dp)
                                            .clip(CircleShape)
                                            .background(statusColors.neutralDot)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "OFFLINE",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = statusColors.onNeutralContainer
                                    )
                                }
                            }
                        }
                    }

                    // Expand Map Button
                    IconButton(
                        onClick = { isFullscreenMapOpen = true },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Fullscreen,
                            contentDescription = "Expand Fullscreen Google Map",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 2. Embedded Free OpenStreetMap / Google Map Component (Renders BOTH Carts!)
            CampusGoogleMapView(
                cartState = displayCart,
                cart1State = effectiveCart1,
                cart2State = effectiveCart2,
                studentLatitude = studentLatitude,
                studentLongitude = studentLongitude,
                isDriverView = isDriverView,
                selectedDestination = facultySelectedLocation,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(240.dp)
            )

            // Fullscreen Map Dialog
            if (isFullscreenMapOpen) {
                Dialog(
                    onDismissRequest = { isFullscreenMapOpen = false },
                    properties = DialogProperties(usePlatformDefaultWidth = false)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.background)
                    ) {
                        CampusGoogleMapView(
                            cartState = displayCart,
                            cart1State = effectiveCart1,
                            cart2State = effectiveCart2,
                            studentLatitude = studentLatitude,
                            studentLongitude = studentLongitude,
                            isDriverView = isDriverView,
                            selectedDestination = facultySelectedLocation,
                            modifier = Modifier.fillMaxSize()
                        )

                        // Close Button in Top-Left
                        Surface(
                            onClick = { isFullscreenMapOpen = false },
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
                            shadowElevation = 6.dp,
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .padding(start = 16.dp, top = 40.dp)
                                .size(40.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Close Fullscreen Map",
                                    tint = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Cart Selector Pills (When multiple carts are present)
            if (effectiveCart1 != null && effectiveCart2 != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val isC1Selected = selectedCartId == "cart_1"
                    Surface(
                        onClick = { selectedCartId = "cart_1" },
                        shape = RoundedCornerShape(10.dp),
                        color = if (isC1Selected) statusColors.successContainer else statusColors.surfaceLevel2,
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            if (isC1Selected) statusColors.successBorder else MaterialTheme.colorScheme.outline
                        ),
                        modifier = Modifier.weight(1f)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(if (cart1Presence.isLocationAvailable) statusColors.successDot else statusColors.neutralDot)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Cart 1 (${cart1Presence.badgeText})",
                                fontSize = 12.sp,
                                fontWeight = if (isC1Selected) FontWeight.Bold else FontWeight.Medium,
                                color = if (isC1Selected) statusColors.onSuccessContainer else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    val isC2Selected = selectedCartId == "cart_2"
                    Surface(
                        onClick = { selectedCartId = "cart_2" },
                        shape = RoundedCornerShape(10.dp),
                        color = if (isC2Selected) statusColors.infoContainer else statusColors.surfaceLevel2,
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            if (isC2Selected) statusColors.infoBorder else MaterialTheme.colorScheme.outline
                        ),
                        modifier = Modifier.weight(1f)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(if (cart2Presence.isLocationAvailable) statusColors.infoDot else statusColors.neutralDot)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Cart 2 (${cart2Presence.badgeText})",
                                fontSize = 12.sp,
                                fontWeight = if (isC2Selected) FontWeight.Bold else FontWeight.Medium,
                                color = if (isC2Selected) statusColors.onInfoContainer else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))
            }

            // 3. Cart Header Label with Concise Location/Status
            val currentCartNumber = if (displayCart?.cartId == "cart_2" || selectedCartId == "cart_2") 2 else 1
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = displayCart?.displayCartLabel ?: "Cart $currentCartNumber",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                val statusText = when {
                    isCartOutside -> "Driver Not Available"
                    isCartOffline -> "Offline"
                    liveRouteProgress.isOffRoute -> "Off Route"
                    liveRouteProgress.currentStop != null -> "At ${liveRouteProgress.currentStop.displayName}"
                    liveRouteProgress.approachingStops.isNotEmpty() -> "En route to ${liveRouteProgress.approachingStops.first().node.displayName}"
                    else -> "En Route"
                }
                val (pillBg, pillText) = when {
                    isCartOutside -> statusColors.dangerContainer to statusColors.onDangerContainer
                    isCartOffline -> statusColors.neutralContainer to statusColors.onNeutralContainer
                    liveRouteProgress.isOffRoute -> statusColors.warningContainer to statusColors.onWarningContainer
                    liveRouteProgress.currentStop != null -> statusColors.successContainer to statusColors.onSuccessContainer
                    else -> statusColors.infoContainer to statusColors.onInfoContainer
                }
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = pillBg
                ) {
                    Text(
                        text = statusText,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = pillText,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // 4. Dynamic Live Route Progress Section
            LiveRouteProgressSection(
                progress = liveRouteProgress,
                isLive = isLocationFresh
            )
        }
    }
}

/**
 * Dynamic Live Route Progress Section:
 * Replaces old static checklist with:
 * - PASSED: checkpoints passed in current travel direction (✓)
 * - CURRENT: currently at stop within 40m threshold (● with "At Stop" pill)
 * - APPROACHING: upcoming checkpoints on active route with accurate polyline distance (→)
 * - AVAILABLE BRANCHES: available branch destinations when approaching junctions (↳)
 */
@Composable
private fun LiveRouteProgressSection(
    progress: CampusRouteGraph.LiveRouteProgress,
    isLive: Boolean
) {
    val statusColors = CampusTheme.statusColors

    when {
        !progress.isLocationAvailable -> {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = statusColors.neutralContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "LOCATION UNAVAILABLE",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = statusColors.onNeutralContainer,
                        letterSpacing = 0.5.sp
                    )
                    Text(
                        text = "Waiting for cart location...",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        progress.isOffRoute -> {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = statusColors.warningContainer,
                border = androidx.compose.foundation.BorderStroke(1.dp, statusColors.warningBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "OFF ROUTE",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = statusColors.onWarningContainer,
                        letterSpacing = 0.5.sp
                    )
                    Text(
                        text = "Cart is outside the active route",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        else -> {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // 1. PASSED SECTION
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "PASSED",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 0.8.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    if (progress.passedStops.isEmpty()) {
                        Text(
                            text = "No locations passed yet",
                            fontSize = 13.sp,
                            fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            modifier = Modifier.padding(start = 4.dp, top = 2.dp)
                        )
                    } else {
                        progress.passedStops.forEach { node ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "✓",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = statusColors.successDot,
                                    modifier = Modifier.width(22.dp)
                                )
                                Text(
                                    text = node.displayName,
                                    fontSize = 13.5.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }

                // 2. CURRENT SECTION (when stopped within 40m arrival threshold)
                if (progress.currentStop != null) {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        thickness = 0.8.dp
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "CURRENT",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 0.8.sp,
                            color = statusColors.onSuccessContainer
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "●",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = statusColors.successDot,
                                    modifier = Modifier.width(22.dp)
                                )
                                Text(
                                    text = progress.currentStop.displayName,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = statusColors.onSuccessContainer
                                )
                            }
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = statusColors.successContainer,
                                border = androidx.compose.foundation.BorderStroke(1.dp, statusColors.successBorder)
                            ) {
                                Text(
                                    text = "At Stop",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = statusColors.onSuccessContainer,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                }

                // 3. APPROACHING SECTION (with accurate route distances)
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    thickness = 0.8.dp
                )
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "APPROACHING",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 0.8.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    if (progress.approachingStops.isEmpty()) {
                        Text(
                            text = "No upcoming locations",
                            fontSize = 13.sp,
                            fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            modifier = Modifier.padding(start = 4.dp, top = 2.dp)
                        )
                    } else {
                        progress.approachingStops.forEachIndexed { index, checkpoint ->
                            val isImmediateNext = index == 0
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = "→",
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isImmediateNext) statusColors.infoDot else statusColors.neutralDot,
                                        modifier = Modifier.width(22.dp)
                                    )
                                    Text(
                                        text = checkpoint.node.displayName,
                                        fontSize = 13.5.sp,
                                        fontWeight = if (isImmediateNext) FontWeight.Bold else FontWeight.Medium,
                                        color = if (isImmediateNext) statusColors.onInfoContainer else MaterialTheme.colorScheme.onSurface
                                    )
                                }
                                Text(
                                    text = checkpoint.formattedDistance,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                // 4. AVAILABLE BRANCHES (shown when approaching junction)
                if (progress.availableBranches.isNotEmpty()) {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        thickness = 0.8.dp
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "AVAILABLE BRANCHES",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 0.8.sp,
                            color = MaterialTheme.colorScheme.secondary
                        )

                        progress.availableBranches.forEach { branch ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = "↳",
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.secondary,
                                        modifier = Modifier.width(22.dp)
                                    )
                                    Text(
                                        text = branch.branchName,
                                        fontSize = 13.5.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                                Text(
                                    text = branch.formattedDistance,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
