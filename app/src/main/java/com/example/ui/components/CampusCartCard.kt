package com.example.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.NearMe
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.GolfCartState
import com.example.data.model.PassengerCartAvailabilityPolicy
import com.example.data.model.GolfCartStatus
import com.example.location.CampusLandmarkZone

/**
 * Modern, high-visibility Material 3 Vehicle Card for Campus Club Carts.
 *
 * Provides instant clarity to students on:
 * 1. Cart Identity (CART 1 / CART 2 + "Campus Club Cart")
 * 2. Real GPS Live status (Pulsing live badge vs Updating vs Offline)
 * 3. Exact Current Location (e.g. Near Main Gate, Between Trunkut & CC, At Boys Hostel)
 * 4. Route approach subtitle (e.g. Approaching Computer Centre)
 * 5. Availability Status (Available / On Duty / Busy / Offline)
 * 6. Last updated time & estimated ETA
 */
@Composable
fun CampusCartCard(
    cartNumber: Int,
    cartState: GolfCartState,
    isSelected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier
) {
    val cartId = if (cartNumber == 1) "cart_1" else "cart_2"
    val cartTitle = if (cartNumber == 1) "CART 1" else "CART 2"

    // Real-time presence & freshness evaluation
    val isOutsideCampus = cartState.isOutsideCampus || !cartState.isInsideCampus
    val isLocationUsable = PassengerCartAvailabilityPolicy.isPhysicalLocationUsable(cartState)
    val isRideAvailable = PassengerCartAvailabilityPolicy.isRideServiceAvailable(cartState)
    val isLocationStale = cartState.isLocationStale && !isOutsideCampus
    val isLiveGps = isLocationUsable && cartState.isLocationAvailable
    val isNoLocationYet = !isOutsideCampus && !isLocationUsable && cartState.hasCoordinates
    val isPhysicalOffline = !isLocationUsable

    val routeResult = if (isLocationUsable) {
        CampusLandmarkZone.evaluateRoutePosition(
            latitude = cartState.latitude,
            longitude = cartState.longitude,
            bearing = cartState.bearing,
            relativeMovement = cartState.relativeMovement,
            speedKmH = cartState.speedKmH ?: 0,
            accuracy = cartState.accuracy ?: 0f,
            timestamp = cartState.locationTimestampMillis ?: cartState.lastUpdatedMillis ?: System.currentTimeMillis(),
            cartId = cartId
        )
    } else null

    // Exact student-facing location string
    val locationDisplay = when {
        isOutsideCampus -> "Outside campus boundary"
        !isLocationUsable -> "Location unavailable"
        isNoLocationYet -> "Location updating..."
        routeResult != null -> {
            val suffix = if (isLocationStale) " (Stale)" else ""
            when {
                routeResult.isAtGate -> "At Main Gate$suffix"
                routeResult.isAtLandmark -> "At ${routeResult.primaryLandmark.displayName}$suffix"
                routeResult.isBetween && routeResult.secondaryLandmark != null ->
                    "Between ${routeResult.primaryLandmark.displayName} & ${routeResult.secondaryLandmark.displayName}$suffix"
                else -> "Near ${routeResult.primaryLandmark.displayName}$suffix"
            }
        }
        cartState.currentStop != null -> "Near ${cartState.currentStop}" + (if (isLocationStale) " (Stale)" else "")
        else -> "Near ${cartState.landmarkZone}" + (if (isLocationStale) " (Stale)" else "")
    }

    // Approach / transit subtitle
    val approachDisplay = when {
        isOutsideCampus -> "Driver outside campus"
        !isLocationUsable -> null
        isNoLocationYet -> "Waiting for GPS lock"
        routeResult != null && !routeResult.isAtGate && !routeResult.isAtLandmark ->
            routeResult.driverDirectionSubtitle
        !cartState.direction.isNullOrBlank() ->
            cartState.direction
        else -> null
    }

    // Availability Classification
    val (availabilityText, availabilityColor, availabilityBg) = when {
        isOutsideCampus -> Triple("Driver Not Available", Color(0xFFDC2626), Color(0xFFFEF2F2))
        cartState.driverStatus.equals("Lunch Break", ignoreCase = true) -> Triple("Lunch Break", Color(0xFFD97706), Color(0xFFFEF3C7))
        cartState.driverStatus.equals("Driver Not Available", ignoreCase = true) -> Triple("Driver Not Available", Color(0xFFDC2626), Color(0xFFFEF2F2))
        cartState.driverStatus.equals("Off Duty", ignoreCase = true) -> Triple("Off Duty", Color(0xFF64748B), Color(0xFFF1F5F9))
        !isRideAvailable && !isLocationUsable -> Triple("Offline", Color(0xFF64748B), Color(0xFFF1F5F9))
        isLocationStale -> Triple("Online • Stale GPS", Color(0xFFD97706), Color(0xFFFEF3C7))
        cartState.isTripActive || cartState.activeRequestId != null || cartState.driverStatus?.contains("Busy", ignoreCase = true) == true ->
            Triple("Busy", Color(0xFFD97706), Color(0xFFFEF3C7))
        cartState.status == GolfCartStatus.MOVING || cartState.driverStatus?.contains("Duty", ignoreCase = true) == true ->
            Triple("On Duty", Color(0xFF0284C7), Color(0xFFE0F2FE))
        else -> Triple("Available", Color(0xFF16A34A), Color(0xFFDCFCE7))
    }

    // Relative Time String
    val locationAgeSec = (cartState.locationAgeMs / 1000).coerceAtLeast(0)
    val relativeTimeText = when {
        isOutsideCampus -> "Driver not inside campus"
        !isLocationUsable && !isRideAvailable -> {
            val lastSeenAge = cartState.heartbeatAgeMs
            if (lastSeenAge < Long.MAX_VALUE / 2) {
                val diffMin = (lastSeenAge / 60_000).coerceAtLeast(1)
                if (diffMin < 60) "Last seen $diffMin min ago" else "Last seen >1 hr ago"
            } else {
                "Currently offline"
            }
        }
        isLiveGps -> {
            if (locationAgeSec <= 1) "Updated 1 sec ago" else "Updated ${locationAgeSec} sec ago"
        }
        isLocationStale -> {
            "Stale GPS (${locationAgeSec}s ago)"
        }
        isNoLocationYet -> {
            "Driver active • GPS syncing"
        }
        else -> {
            "Updated ${locationAgeSec}s ago"
        }
    }

    // Live Indicator Pulsing Animation
    val infiniteTransition = rememberInfiniteTransition(label = "LivePulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "Alpha"
    )

    // Animated container and border colors based on active selection
    val borderColor by animateColorAsState(
        targetValue = if (isSelected) MaterialTheme.colorScheme.primary else Color(0xFFE2E8F0),
        label = "BorderColor"
    )
    val cardElevation = if (isSelected) 3.dp else 1.dp

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onSelect)
            .testTag("campus_cart_card_$cartId"),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.28f)
            } else {
                MaterialTheme.colorScheme.surface
            }
        ),
        border = BorderStroke(
            width = if (isSelected) 2.dp else 1.dp,
            color = borderColor
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = cardElevation)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // ── TOP HEADER ROW: Icon + Title + Live Badge ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f, fill = false)
                ) {
                    // Clean Cart Identity Badge (No vehicle or auto icons)
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .clip(CircleShape)
                            .background(
                                if (cartNumber == 1) Color(0xFFDCFCE7) else Color(0xFFEFF6FF)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "$cartNumber",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Black,
                            color = if (cartNumber == 1) Color(0xFF15803D) else Color(0xFF1D4ED8)
                        )
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = cartTitle,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                letterSpacing = 0.5.sp
                            )
                            if (isSelected) {
                                Spacer(modifier = Modifier.width(6.dp))
                                Surface(
                                    shape = CircleShape,
                                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                                ) {
                                    Text(
                                        text = "TRACKING",
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Black,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }
                        Text(
                            text = if (cartNumber == 1) "Emerald Green Line" else "Royal Blue Line",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // Live / Updating / Offline Status Badge
                when {
                    isLiveGps -> {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = Color(0xFFDCFCE7),
                            border = BorderStroke(1.dp, Color(0xFF86EFAC))
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFF16A34A).copy(alpha = pulseAlpha))
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "LIVE",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = Color(0xFF15803D),
                                    letterSpacing = 0.5.sp
                                )
                            }
                        }
                    }
                    isLocationStale -> {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = Color(0xFFFEF3C7),
                            border = BorderStroke(1.dp, Color(0xFFFCD34D))
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "⚠",
                                    fontSize = 10.sp
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "DELAYED",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFFB45309)
                                )
                            }
                        }
                    }
                    else -> {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = Color(0xFFF1F5F9),
                            border = BorderStroke(1.dp, Color(0xFFE2E8F0))
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFF94A3B8))
                                )
                                Spacer(modifier = Modifier.width(5.dp))
                                Text(
                                    text = "OFFLINE",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF64748B)
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // ── MIDDLE SECTION: Current Approximate Location & Approach ──
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = if (isSelected) MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)
                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = Icons.Default.LocationOn,
                            contentDescription = "Location",
                            tint = if (isPhysicalOffline) Color(0xFF94A3B8) else Color(0xFFDC2626),
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = locationDisplay,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isPhysicalOffline) Color(0xFF64748B) else MaterialTheme.colorScheme.onSurface
                        )
                    }

                    if (approachDisplay != null) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(start = 24.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.NearMe,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(13.dp)
                            )
                            Spacer(modifier = Modifier.width(5.dp))
                            Text(
                                text = approachDisplay,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // ── BOTTOM FOOTER: Availability Chip + Timestamp & ETA + Call Button ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Left: Availability Status Chip + ETA
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = availabilityBg,
                        border = BorderStroke(1.dp, availabilityColor.copy(alpha = 0.25f))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(availabilityColor)
                            )
                            Spacer(modifier = Modifier.width(5.dp))
                            Text(
                                text = availabilityText,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = availabilityColor
                            )
                        }
                    }

                    if (isLiveGps && cartState.etaMinutes != null && cartState.etaMinutes > 0) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = Color(0xFFEFF6FF),
                            border = BorderStroke(1.dp, Color(0xFFBFDBFE))
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.AccessTime,
                                    contentDescription = null,
                                    tint = Color(0xFF2563EB),
                                    modifier = Modifier.size(11.dp)
                                )
                                Spacer(modifier = Modifier.width(3.dp))
                                Text(
                                    text = "~${cartState.etaMinutes}m",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF1D4ED8)
                                )
                            }
                        }
                    }
                }

                // Right: Relative Time
                Text(
                    text = relativeTimeText,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color(0xFF64748B)
                )
            }
        }
    }
}
