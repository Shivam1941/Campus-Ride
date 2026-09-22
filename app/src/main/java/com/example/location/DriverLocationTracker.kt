package com.example.location

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationAvailability
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices

/**
 * Production-grade battery-optimized live location tracker for Campus Ride Drivers.
 *
 * Implements full Adaptive GPS Tracking with 5 dynamic modes:
 * - STATIONARY: Balanced power, 30s interval, 20m filter.
 * - LOW_SPEED: Balanced power, 8s interval, 5m filter.
 * - NORMAL_MOVEMENT: High accuracy, 4s interval, 5m filter.
 * - HIGH_SPEED: High accuracy, 2.5s interval, 4m filter.
 * - BACKGROUND: Balanced power, 15s interval, 15m filter.
 *
 * Employs hysteresis, debouncing, and GPS jitter rejection to avoid mode flapping
 * while preserving real-time responsiveness when the cart moves.
 */
class DriverLocationTracker(private val context: Context) {

    @Deprecated("Use TrackingConfig.TrackingMode for fine-grained modes")
    enum class MovementState {
        MOVING,
        STATIONARY
    }

    private val fusedLocationClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)

    private var fusedLocationCallback: LocationCallback? = null
    private var legacyLocationListener: LocationListener? = null
    private var backgroundThread: HandlerThread? = null
    private var isTracking = false

    // Adaptive mode state
    var currentTrackingMode: TrackingConfig.TrackingMode = TrackingConfig.TrackingMode.NORMAL_MOVEMENT
        private set

    val currentMovementState: MovementState
        get() = if (currentTrackingMode == TrackingConfig.TrackingMode.STATIONARY) MovementState.STATIONARY else MovementState.MOVING

    private var isAppBackgrounded = false
    private var stationaryAnchorLocation: Location? = null
    private var consecutiveStationarySamples = 0
    private var consecutiveFastSamples = 0
    private var lastEmittedLocation: Location? = null
    private var lastEmittedTimeMs = 0L

    fun isLocationPermissionGranted(): Boolean {
        val fineGranted = ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarseGranted = ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        return fineGranted || coarseGranted
    }

    /**
     * Informs the tracker whether the application UI is backgrounded or screen locked.
     * When backgrounded, tracker switches to BACKGROUND mode unless stationary.
     */
    fun setAppBackgrounded(backgrounded: Boolean) {
        if (isAppBackgrounded == backgrounded) return
        isAppBackgrounded = backgrounded
        Log.d(TAG, "App background state changed: isBackgrounded=$backgrounded")

        if (!isTracking) return
        val looper = backgroundThread?.looper ?: Looper.getMainLooper()
        val targetMode = if (backgrounded) {
            if (currentTrackingMode == TrackingConfig.TrackingMode.STATIONARY) {
                TrackingConfig.TrackingMode.STATIONARY
            } else {
                TrackingConfig.TrackingMode.BACKGROUND
            }
        } else {
            // Restore appropriate movement mode
            TrackingConfig.TrackingMode.NORMAL_MOVEMENT
        }

        if (targetMode != currentTrackingMode) {
            applyNewTrackingMode(targetMode, looper)
        }
    }

    @SuppressLint("MissingPermission")
    fun startTracking(
        onLocationUpdate: (Location) -> Unit,
        onDisabledOrError: () -> Unit
    ) {
        if (!isLocationPermissionGranted()) {
            Log.w(TAG, "Location permissions not granted, cannot start tracking")
            onDisabledOrError()
            return
        }

        if (isTracking) {
            stopTracking()
        }

        isTracking = true
        currentTrackingMode = TrackingConfig.TrackingMode.NORMAL_MOVEMENT
        TrackingConfig.Diagnostics.currentMode = currentTrackingMode
        consecutiveStationarySamples = 0
        consecutiveFastSamples = 0
        stationaryAnchorLocation = null
        lastEmittedLocation = null
        lastEmittedTimeMs = 0L

        Log.d(TAG, "Starting adaptive battery-efficient live tracking (mode=$currentTrackingMode)...")

        // Create dedicated HandlerThread for background location callbacks
        val thread = HandlerThread("DriverLocationTrackerThread", android.os.Process.THREAD_PRIORITY_MORE_FAVORABLE).apply {
            start()
        }
        backgroundThread = thread
        val backgroundLooper = thread.looper ?: Looper.getMainLooper()

        // 1. Initial immediate location check for fast UI readiness
        try {
            fusedLocationClient.lastLocation.addOnSuccessListener { lastLoc ->
                if (lastLoc != null) {
                    logGpsUpdate(lastLoc, "LAST_KNOWN_FUSED")
                    stationaryAnchorLocation = lastLoc
                    lastEmittedLocation = lastLoc
                    lastEmittedTimeMs = System.currentTimeMillis()
                    onLocationUpdate(lastLoc)
                }
            }.addOnFailureListener {
                Log.w(TAG, "Failed to obtain last known fused location", it)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Exception getting last location", e)
        }

        // 2. Setup callback with adaptive stationary detection and drift filtering
        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                for (rawLoc in result.locations) {
                    processIncomingLocation(rawLoc, backgroundLooper, onLocationUpdate)
                }
            }

            override fun onLocationAvailability(availability: LocationAvailability) {
                if (!availability.isLocationAvailable) {
                    Log.w(TAG, "Fused location reporting temporary unavailability")
                }
            }
        }
        fusedLocationCallback = callback

        // 3. Register initial request (NORMAL_MOVEMENT mode)
        val initialRequest = buildLocationRequest(currentTrackingMode)
        try {
            fusedLocationClient.requestLocationUpdates(
                initialRequest,
                callback,
                backgroundLooper
            ).addOnFailureListener { e ->
                Log.e(TAG, "FusedLocationProviderClient failed, activating LocationManager fallback", e)
                startLegacyFallback(backgroundLooper, onLocationUpdate, onDisabledOrError)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception requesting fused location updates", e)
            startLegacyFallback(backgroundLooper, onLocationUpdate, onDisabledOrError)
        }
    }

    /**
     * Filters GPS jitter, classifies motion state with hysteresis, and emits updates.
     */
    private fun processIncomingLocation(
        location: Location,
        backgroundLooper: Looper,
        onLocationUpdate: (Location) -> Unit
    ) {
        TrackingConfig.Diagnostics.gpsTicksCount.incrementAndGet()
        TrackingConfig.Diagnostics.lastGpsAccuracyM = location.accuracy
        val speedMps = if (location.hasSpeed()) location.speed else 0f
        TrackingConfig.Diagnostics.lastGpsSpeedKmh = speedMps * 3.6f

        val now = System.currentTimeMillis()

        // 1. Discard severely inaccurate GPS readings (> 40m) if we already have a fix
        if (location.accuracy > TrackingConfig.Motion.ACCURACY_DISCARD_THRESHOLD_M && lastEmittedLocation != null) {
            TrackingConfig.Diagnostics.gpsJitterFilteredCount.incrementAndGet()
            Log.d(TAG, "Discarding poor accuracy GPS point (${location.accuracy}m > ${TrackingConfig.Motion.ACCURACY_DISCARD_THRESHOLD_M}m)")
            return
        }

        if (stationaryAnchorLocation == null) {
            stationaryAnchorLocation = location
        }

        val anchor = stationaryAnchorLocation!!
        val distFromAnchor = location.distanceTo(anchor)

        // 2. Classify Motion and Evaluate State Transitions
        val candidateMode = determineCandidateMode(speedMps, distFromAnchor)

        if (candidateMode != currentTrackingMode) {
            evaluateStateTransition(candidateMode, location, backgroundLooper)
        } else {
            // Reset transition counters when steady in current mode
            consecutiveStationarySamples = 0
            consecutiveFastSamples = 0
            if (distFromAnchor >= TrackingConfig.Motion.STATIONARY_DISPLACEMENT_THRESHOLD_M) {
                stationaryAnchorLocation = location
            }
        }

        // 3. GPS Jitter & Displacement Gate
        val last = lastEmittedLocation
        if (last == null) {
            lastEmittedLocation = location
            lastEmittedTimeMs = now
            logGpsUpdate(location, "INITIAL_FIX")
            onLocationUpdate(location)
            return
        }

        val distFromLast = location.distanceTo(last)
        val timeSinceLast = now - lastEmittedTimeMs

        // Emitting rule:
        // - In STATIONARY: emit if moved >= MIN_EMIT_DISTANCE_M or keep-alive (30s) reached
        // - In MOVING: emit if moved >= MIN_EMIT_DISTANCE_M or speed > 0.5 m/s
        val shouldEmit = distFromLast >= TrackingConfig.Motion.MIN_EMIT_DISTANCE_M ||
                (currentTrackingMode != TrackingConfig.TrackingMode.STATIONARY && speedMps > 0.5f && distFromLast >= 1.5f) ||
                timeSinceLast >= TrackingConfig.Motion.MAX_STATIONARY_HEARTBEAT_EMIT_MS

        if (shouldEmit) {
            lastEmittedLocation = location
            lastEmittedTimeMs = now
            logGpsUpdate(location, "FUSED_${currentTrackingMode.name}")
            onLocationUpdate(location)
        } else {
            TrackingConfig.Diagnostics.gpsJitterFilteredCount.incrementAndGet()
            Log.v(TAG, "Suppressed jitter: dist=${distFromLast}m, dt=${timeSinceLast}ms, mode=$currentTrackingMode")
        }
    }

    private fun determineCandidateMode(speedMps: Float, distFromAnchor: Float): TrackingConfig.TrackingMode {
        if (isAppBackgrounded) {
            return if (speedMps < TrackingConfig.Motion.STATIONARY_SPEED_THRESHOLD_MPS &&
                distFromAnchor < TrackingConfig.Motion.STATIONARY_DISPLACEMENT_THRESHOLD_M) {
                TrackingConfig.TrackingMode.STATIONARY
            } else {
                TrackingConfig.TrackingMode.BACKGROUND
            }
        }

        return when {
            speedMps < TrackingConfig.Motion.STATIONARY_SPEED_THRESHOLD_MPS &&
                    distFromAnchor < TrackingConfig.Motion.STATIONARY_DISPLACEMENT_THRESHOLD_M -> {
                TrackingConfig.TrackingMode.STATIONARY
            }
            speedMps >= TrackingConfig.Motion.HIGH_SPEED_MIN_MPS -> {
                TrackingConfig.TrackingMode.HIGH_SPEED
            }
            speedMps < TrackingConfig.Motion.LOW_SPEED_MAX_MPS -> {
                TrackingConfig.TrackingMode.LOW_SPEED
            }
            else -> {
                TrackingConfig.TrackingMode.NORMAL_MOVEMENT
            }
        }
    }

    private fun evaluateStateTransition(
        candidateMode: TrackingConfig.TrackingMode,
        location: Location,
        backgroundLooper: Looper
    ) {
        when (candidateMode) {
            TrackingConfig.TrackingMode.STATIONARY -> {
                consecutiveStationarySamples++
                if (consecutiveStationarySamples >= TrackingConfig.Motion.SAMPLES_TO_STATIONARY) {
                    stationaryAnchorLocation = location
                    consecutiveStationarySamples = 0
                    Log.d(TAG, "MOTION TRANSITION: Cart parked or stationary. Switching to balanced-power mode ($candidateMode).")
                    applyNewTrackingMode(candidateMode, backgroundLooper)
                }
            }
            TrackingConfig.TrackingMode.HIGH_SPEED -> {
                consecutiveFastSamples++
                if (consecutiveFastSamples >= 2) {
                    stationaryAnchorLocation = location
                    consecutiveFastSamples = 0
                    Log.d(TAG, "MOTION TRANSITION: Cart moving rapidly. Switching to high-speed mode ($candidateMode).")
                    applyNewTrackingMode(candidateMode, backgroundLooper)
                }
            }
            else -> {
                // Immediate transition from STATIONARY to active movement modes (LOW_SPEED, NORMAL_MOVEMENT, BACKGROUND)
                stationaryAnchorLocation = location
                consecutiveStationarySamples = 0
                consecutiveFastSamples = 0
                Log.d(TAG, "MOTION TRANSITION: Cart movement detected. Switching mode to $candidateMode.")
                applyNewTrackingMode(candidateMode, backgroundLooper)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun applyNewTrackingMode(newMode: TrackingConfig.TrackingMode, backgroundLooper: Looper) {
        if (!isTracking || currentTrackingMode == newMode) return
        currentTrackingMode = newMode
        TrackingConfig.Diagnostics.currentMode = newMode

        val callback = fusedLocationCallback ?: return
        val looper = if (backgroundThread?.isAlive == true) backgroundLooper else Looper.getMainLooper()
        try {
            val newRequest = buildLocationRequest(newMode)
            fusedLocationClient.removeLocationUpdates(callback).addOnCompleteListener {
                if (!isTracking) return@addOnCompleteListener
                try {
                    fusedLocationClient.requestLocationUpdates(newRequest, callback, looper)
                    Log.d(TAG, "Successfully applied location mode: $newMode (interval=${newMode.intervalMs}ms, filter=${newMode.distanceFilterM}m)")
                } catch (e: Exception) {
                    Log.w(TAG, "Error applying reconfigured location request: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Exception during fused update reconfiguration: ${e.message}")
        }
    }

    private fun buildLocationRequest(mode: TrackingConfig.TrackingMode): LocationRequest {
        return LocationRequest.Builder(mode.priority, mode.intervalMs)
            .setMinUpdateIntervalMillis(mode.minIntervalMs)
            .setMinUpdateDistanceMeters(mode.distanceFilterM)
            .setMaxUpdateDelayMillis(mode.maxDelayMs)
            .setWaitForAccurateLocation(false)
            .build()
    }

    @SuppressLint("MissingPermission")
    private fun startLegacyFallback(
        backgroundLooper: Looper,
        onLocationUpdate: (Location) -> Unit,
        onDisabledOrError: () -> Unit
    ) {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        if (lm == null) {
            onDisabledOrError()
            return
        }

        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                processIncomingLocation(location, backgroundLooper, onLocationUpdate)
            }

            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {
                if (!lm.isProviderEnabled(LocationManager.GPS_PROVIDER) &&
                    !lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
                ) {
                    onDisabledOrError()
                }
            }
        }
        legacyLocationListener = listener

        try {
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                lm.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    currentTrackingMode.intervalMs,
                    currentTrackingMode.distanceFilterM,
                    listener,
                    backgroundLooper
                )
            } else if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                lm.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    TrackingConfig.TrackingMode.STATIONARY.intervalMs,
                    TrackingConfig.TrackingMode.STATIONARY.distanceFilterM,
                    listener,
                    backgroundLooper
                )
            } else {
                onDisabledOrError()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start legacy LocationManager updates", e)
            onDisabledOrError()
        }
    }

    fun stopTracking() {
        isTracking = false
        fusedLocationCallback?.let {
            try {
                fusedLocationClient.removeLocationUpdates(it)
            } catch (e: Exception) {
                Log.w(TAG, "Error removing fused updates", e)
            }
            fusedLocationCallback = null
        }
        legacyLocationListener?.let {
            try {
                val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
                lm?.removeUpdates(it)
            } catch (e: Exception) {
                Log.w(TAG, "Error removing legacy updates", e)
            }
            legacyLocationListener = null
        }
        backgroundThread?.let {
            try {
                it.quitSafely()
            } catch (e: Exception) {
                Log.w(TAG, "Error quitting background thread", e)
            }
            backgroundThread = null
        }
        stationaryAnchorLocation = null
        lastEmittedLocation = null
        Log.d(TAG, "Driver location tracking stopped.")
    }

    private fun logGpsUpdate(location: Location, source: String) {
        Log.d(
            "DRIVER_GPS",
            "LOCATION_TICK [$source]: Lat=${location.latitude}, Lng=${location.longitude}, Acc=${location.accuracy}m, Spd=${location.speed}m/s, Mode=$currentTrackingMode"
        )
    }

    companion object {
        private const val TAG = "DriverLocationTracker"
    }
}
