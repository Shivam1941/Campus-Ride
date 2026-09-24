package com.example.location

import com.google.android.gms.location.Priority
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Centralized Battery & Performance Optimization Configuration for Campus Ride Live Tracking.
 *
 * Implements fine-grained Adaptive GPS Tracking Modes:
 * 1. STATIONARY: Cart parked/resting (speed < 0.6 m/s for >= 4 samples). Low power, 30s interval, 20m filter.
 * 2. LOW_SPEED: Cart maneuvering or crawling (0.6 - 2.0 m/s = 2.1 - 7.2 km/h). 8s interval, 5m filter.
 * 3. NORMAL_MOVEMENT: Standard campus driving (2.0 - 5.0 m/s = 7.2 - 18.0 km/h). 4s interval, 5m filter, high accuracy.
 * 4. HIGH_SPEED: Rapid transit (> 5.0 m/s = > 18.0 km/h). 2.5s interval, 4m filter, high accuracy.
 * 5. BACKGROUND: App screen locked / minimized while driver service runs. 15s interval, 15m filter.
 * 6. OFFLINE: Exponential backoff for network sync.
 */
object TrackingConfig {

    enum class TrackingMode(
        val intervalMs: Long,
        val minIntervalMs: Long,
        val distanceFilterM: Float,
        val priority: Int,
        val maxDelayMs: Long
    ) {
        STATIONARY(
            intervalMs = 30_000L,
            minIntervalMs = 15_000L,
            distanceFilterM = 20.0f,
            priority = Priority.PRIORITY_BALANCED_POWER_ACCURACY,
            maxDelayMs = 35_000L
        ),
        LOW_SPEED(
            intervalMs = 8_000L,
            minIntervalMs = 4_000L,
            distanceFilterM = 5.0f,
            priority = Priority.PRIORITY_BALANCED_POWER_ACCURACY,
            maxDelayMs = 10_000L
        ),
        NORMAL_MOVEMENT(
            intervalMs = 4_000L,
            minIntervalMs = 2_000L,
            distanceFilterM = 5.0f,
            priority = Priority.PRIORITY_HIGH_ACCURACY,
            maxDelayMs = 5_000L
        ),
        HIGH_SPEED(
            intervalMs = 2_500L,
            minIntervalMs = 1_500L,
            distanceFilterM = 4.0f,
            priority = Priority.PRIORITY_HIGH_ACCURACY,
            maxDelayMs = 3_000L
        ),
        BACKGROUND(
            intervalMs = 15_000L,
            minIntervalMs = 10_000L,
            distanceFilterM = 15.0f,
            priority = Priority.PRIORITY_BALANCED_POWER_ACCURACY,
            maxDelayMs = 20_000L
        )
    }

    object Motion {
        // Speed thresholds (m/s)
        const val STATIONARY_SPEED_THRESHOLD_MPS = 0.6f      // ~2.1 km/h
        const val LOW_SPEED_MAX_MPS = 2.0f                   // ~7.2 km/h
        const val HIGH_SPEED_MIN_MPS = 5.0f                  // ~18.0 km/h

        // Displacement gate for stationary detection
        const val STATIONARY_DISPLACEMENT_THRESHOLD_M = 8.0f // must stay within 8m
        const val SAMPLES_TO_STATIONARY = 4                  // ~16-24s continuous stationary readings

        // GPS noise filtering
        const val ACCURACY_DISCARD_THRESHOLD_M = 40.0f       // ignore fixes worse than 40m
        const val MIN_EMIT_DISTANCE_M = 2.5f                 // minimum movement to emit new location
        const val MAX_STATIONARY_HEARTBEAT_EMIT_MS = 30_000L // maintain online presence every 30s
    }

    object Network {
        const val MIN_UPDATE_DISTANCE_M = 5.0                // send location to server if moved >= 5m
        const val HEADING_CHANGE_THRESHOLD_DEG = 25f         // send if turn >= 25 deg at speed
        const val MAX_HEARTBEAT_INTERVAL_MS = 35_000L        // keep-alive heartbeat threshold
        const val STALE_LOCATION_THRESHOLD_MS = 90_000L      // UI treats > 90s as stale (allows 2-3 stationary GPS cycles)

        // Exponential backoff configuration
        const val BACKOFF_INITIAL_MS = 5_000L
        const val BACKOFF_MAX_MS = 60_000L
        const val BACKOFF_MULTIPLIER = 2.0
    }

    object Route {
        const val MIN_RECALCULATION_DELTA_M = 2.0            // skip route graph re-eval if moved < 2m
        const val ARRIVAL_THRESHOLD_M = 40.0                 // distance to mark "CURRENT / At Stop"
        const val OFF_ROUTE_THRESHOLD_M = 75.0               // cross-track distance for off-route
        const val BRANCH_PROXIMITY_THRESHOLD_M = 180.0       // proximity to show approaching branch
    }

    object MapView {
        const val MIN_AUTO_FOLLOW_DISTANCE_M = 25.0          // avoid micro-panning if cart moved < 25m
        const val RADAR_PULSE_ONLY_WHEN_MOVING = true        // disable 60fps pulse animation when stationary
    }

    /**
     * Development-only diagnostics counter for measuring tracking efficiency.
     */
    object Diagnostics {
        val gpsTicksCount = AtomicInteger(0)
        val gpsJitterFilteredCount = AtomicInteger(0)
        val networkSyncCount = AtomicInteger(0)
        val networkSyncSkippedCount = AtomicInteger(0)
        val routeCalculationsCount = AtomicInteger(0)
        val routeCalculationsCacheHits = AtomicInteger(0)
        val mapCanvasDrawCount = AtomicInteger(0)

        @Volatile
        var currentMode: TrackingMode = TrackingMode.STATIONARY

        @Volatile
        var lastGpsSpeedKmh: Float = 0f

        @Volatile
        var lastGpsAccuracyM: Float = 0f

        val startTimeMs = AtomicLong(System.currentTimeMillis())

        fun reset() {
            gpsTicksCount.set(0)
            gpsJitterFilteredCount.set(0)
            networkSyncCount.set(0)
            networkSyncSkippedCount.set(0)
            routeCalculationsCount.set(0)
            routeCalculationsCacheHits.set(0)
            mapCanvasDrawCount.set(0)
            startTimeMs.set(System.currentTimeMillis())
        }

        fun getSummary(): String {
            val elapsedSec = maxOf(1L, (System.currentTimeMillis() - startTimeMs.get()) / 1000L)
            val gpsPerMin = (gpsTicksCount.get() * 60f) / elapsedSec
            val netPerMin = (networkSyncCount.get() * 60f) / elapsedSec
            val routePerMin = (routeCalculationsCount.get() * 60f) / elapsedSec

            return "DIAGNOSTICS (${elapsedSec}s elapsed): " +
                    "Mode=${currentMode}, " +
                    "GPS=${gpsTicksCount.get()} (${String.format("%.1f", gpsPerMin)}/min), " +
                    "JitterFiltered=${gpsJitterFilteredCount.get()}, " +
                    "NetWrites=${networkSyncCount.get()} (${String.format("%.1f", netPerMin)}/min, skipped=${networkSyncSkippedCount.get()}), " +
                    "RouteCalcs=${routeCalculationsCount.get()} (hits=${routeCalculationsCacheHits.get()}, ${String.format("%.1f", routePerMin)}/min), " +
                    "CanvasDraws=${mapCanvasDrawCount.get()}"
        }
    }
}
