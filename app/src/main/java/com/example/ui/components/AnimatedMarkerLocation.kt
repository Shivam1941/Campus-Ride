package com.example.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * State holder that encapsulates the interpolated latitude, longitude, and bearing
 * for smooth golf cart marker movement across map frames.
 */
@Stable
class AnimatedCartMarkerState(
    initialLat: Double = 0.0,
    initialLng: Double = 0.0,
    initialBearing: Float = 0f
) {
    var latitude by mutableDoubleStateOf(initialLat)
        internal set

    var longitude by mutableDoubleStateOf(initialLng)
        internal set

    var bearing by mutableFloatStateOf(initialBearing)
        internal set

    var hasValidLocation by mutableStateOf(initialLat != 0.0 && initialLng != 0.0)
        internal set

    var isAnimating by mutableStateOf(false)
        internal set
}

/**
 * Smoothly animates golf cart coordinates and rotation between incoming GPS updates,
 * eliminating abrupt coordinate jumping and providing a fluid vehicle gliding motion.
 *
 * @param targetLat Destination latitude from GPS / telemetry
 * @param targetLng Destination longitude from GPS / telemetry
 * @param targetBearing Optional bearing angle (0-360) from GPS / compass
 * @param durationMs Animation duration in milliseconds (default 1200ms)
 * @param onPositionUpdate Optional callback invoked on every animation frame (useful for Google Maps MarkerState)
 */
@Composable
fun rememberAnimatedCartMarkerState(
    targetLat: Double?,
    targetLng: Double?,
    targetBearing: Float? = null,
    durationMs: Int = 1200,
    onPositionUpdate: ((Double, Double) -> Unit)? = null
): AnimatedCartMarkerState {
    val state = remember {
        AnimatedCartMarkerState(
            initialLat = targetLat ?: 0.0,
            initialLng = targetLng ?: 0.0,
            initialBearing = targetBearing ?: 0f
        )
    }

    LaunchedEffect(targetLat, targetLng, targetBearing) {
        if (targetLat == null || targetLng == null || targetLat == 0.0 || targetLng == 0.0) {
            return@LaunchedEffect
        }

        // First valid coordinate: snap immediately without flying across the world
        if (!state.hasValidLocation) {
            state.latitude = targetLat
            state.longitude = targetLng
            state.bearing = targetBearing ?: 0f
            state.hasValidLocation = true
            onPositionUpdate?.invoke(targetLat, targetLng)
            return@LaunchedEffect
        }

        val startLat = state.latitude
        val startLng = state.longitude
        val startBearing = state.bearing

        val distanceMeters = computeDistanceMeters(startLat, startLng, targetLat, targetLng)

        // Ignore micro-jitter (< 0.25 meters)
        if (distanceMeters < 0.25) {
            if (targetBearing != null) {
                state.bearing = targetBearing
            }
            return@LaunchedEffect
        }

        // Huge teleport or sudden campus re-center (> 350 meters): snap directly
        if (distanceMeters > 350.0) {
            state.latitude = targetLat
            state.longitude = targetLng
            state.bearing = targetBearing ?: 0f
            onPositionUpdate?.invoke(targetLat, targetLng)
            return@LaunchedEffect
        }

        // Resolve destination heading (use provided bearing or calculate forward track angle)
        val destinationBearing = if (targetBearing != null && targetBearing != 0f) {
            targetBearing
        } else if (distanceMeters >= 1.0) {
            computeHeading(startLat, startLng, targetLat, targetLng)
        } else {
            startBearing
        }

        val bearingDelta = shortestAngleDelta(startBearing, destinationBearing)

        state.isAnimating = true
        try {
            animate(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = tween(
                    durationMillis = durationMs,
                    easing = FastOutSlowInEasing
                )
            ) { progress, _ ->
                val fraction = progress.toDouble()
                val currentLat = startLat + (targetLat - startLat) * fraction
                val currentLng = startLng + (targetLng - startLng) * fraction
                val currentBearing = ((startBearing + bearingDelta * progress) % 360f + 360f) % 360f

                state.latitude = currentLat
                state.longitude = currentLng
                state.bearing = currentBearing

                onPositionUpdate?.invoke(currentLat, currentLng)
            }
        } finally {
            state.isAnimating = false
            state.latitude = targetLat
            state.longitude = targetLng
            state.bearing = destinationBearing
            onPositionUpdate?.invoke(targetLat, targetLng)
        }
    }

    return state
}

/**
 * Calculates great-circle distance between two coordinates in meters.
 */
private fun computeDistanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val r = 6371000.0 // Earth's mean radius in meters
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLon / 2) * sin(dLon / 2)
    val c = 2 * atan2(sqrt(a), sqrt(1 - a))
    return r * c
}

/**
 * Computes forward azimuth/bearing (0° to 360°) from point 1 to point 2.
 */
private fun computeHeading(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
    val dLon = Math.toRadians(lon2 - lon1)
    val y = sin(dLon) * cos(Math.toRadians(lat2))
    val x = cos(Math.toRadians(lat1)) * sin(Math.toRadians(lat2)) -
            sin(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * cos(dLon)
    val heading = Math.toDegrees(atan2(y, x)).toFloat()
    return (heading % 360f + 360f) % 360f
}

/**
 * Calculates the shortest angular distance from one bearing to another (-180° to +180°),
 * preventing 360° spin-around issues when crossing the 0° North boundary.
 */
private fun shortestAngleDelta(from: Float, to: Float): Float {
    var delta = (to - from) % 360f
    if (delta > 180f) delta -= 360f
    if (delta < -180f) delta += 360f
    return delta
}
