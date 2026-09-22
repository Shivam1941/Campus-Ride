package com.example.location

import android.location.Location

// Production 70.0-meter Main Gate geofence rule is strictly enforced.
const val TEST_MODE_ALLOW_ANY_PICKUP_LOCATION = false

object GeofenceManager {
    // 1. MAIN GATE (Exact Google Maps Pin - Production Coordinates)
    const val GATE_LAT = 25.2531616
    const val GATE_LNG = 87.0370730
    const val MAX_GEOFENCE_METERS = 70.0 // Production Geofence Radius: 70 meters

    // 2. TRUNKUT (Exact Google Maps Pin)
    const val TRUNKUT_LAT = 25.2577186
    const val TRUNKUT_LNG = 87.0381730

    // 3. COMPUTER CENTRE (Exact Google Maps Pin)
    const val COMPUTER_CENTRE_LAT = 25.2590500
    const val COMPUTER_CENTRE_LNG = 87.0394730

    // 4. ACADEMIC BLOCK (Exact Google Maps Pin - https://maps.app.goo.gl/xrou6j7K4B9QmHid9)
    const val ACADEMIC_BLOCK_LAT = 25.2590750
    const val ACADEMIC_BLOCK_LNG = 87.0401610

    // 5. BOYS HOSTEL / HOSTEL (Exact Google Maps Pin)
    const val HOSTEL_LAT = 25.2577810
    const val HOSTEL_LNG = 87.0418910

    // 6. GIRLS HOSTEL
    const val GIRLS_HOSTEL_LAT = 25.26007663539964
    const val GIRLS_HOSTEL_LNG = 87.0393243817079

    // 7. FACULTY RESIDENCE
    const val FACULTY_RESIDENCE_LAT = 25.259257426319685
    const val FACULTY_RESIDENCE_LNG = 87.04141528824039

    // Driver Service Area Center Point (Computer Centre / Academic Hub, IIIT Bhagalpur)
    const val LIBRARY_LAT = 25.2590500
    const val LIBRARY_LNG = 87.0394730
    const val DRIVER_SERVICE_AREA_RADIUS_METERS = 1000.0 // 1 km radius

    // Campus Geofence Boundary for Automatic Driver Entry Detection
    const val CAMPUS_CENTER_LAT = 25.2590500
    const val CAMPUS_CENTER_LNG = 87.0394730
    const val CAMPUS_GEOFENCE_RADIUS_METERS = 1000.0 // 1 km radius for full campus zone
    const val CAMPUS_EXIT_RADIUS_METERS = 1150.0 // 1.15 km radius with 150m hysteresis band
    const val MAX_ACCEPTABLE_GPS_ACCURACY_METERS = 50.0 // Reject inaccurate fixes (>50m) to avoid false detections

    val isTestModeEnabled: Boolean get() = TEST_MODE_ALLOW_ANY_PICKUP_LOCATION

    // Stateful hysteresis and debounce tracking for Driver presence
    private class PresenceTracker {
        var consecutiveInsideCount = 0
        var consecutiveOutsideCount = 0
        var isDriverInsideTracked = false
        var isTrackerInitialized = false
    }

    private val defaultTracker = PresenceTracker()
    private val cartTrackers = java.util.concurrent.ConcurrentHashMap<String, PresenceTracker>()

    private fun getTracker(cartId: String?): PresenceTracker {
        return if (cartId.isNullOrBlank()) {
            defaultTracker
        } else {
            cartTrackers.computeIfAbsent(cartId) { PresenceTracker() }
        }
    }

    /**
     * Validates whether GPS fix accuracy is sufficient for reliable geofence detection.
     */
    fun isGpsAccuracyValid(accuracy: Float): Boolean {
        return accuracy >= 0f && accuracy <= MAX_ACCEPTABLE_GPS_ACCURACY_METERS
    }

    /**
     * Checks if a driver's GPS coordinate is within the campus geofence with valid accuracy.
     */
    fun isInsideCampusGeofence(
        driverLat: Double,
        driverLng: Double,
        accuracy: Float = 0f
    ): Boolean {
        if (accuracy > 0f && !isGpsAccuracyValid(accuracy)) {
            return false // Reject inaccurate GPS spikes
        }
        val distance = calculateDistanceMeters(driverLat, driverLng, CAMPUS_CENTER_LAT, CAMPUS_CENTER_LNG)
        return distance <= CAMPUS_GEOFENCE_RADIUS_METERS
    }

    /**
     * Evaluates driver campus presence using stable hysteresis and debounce filtering.
     * Prevents rapid toggling caused by GPS noise while stationary or near boundary.
     * Maintains independent debounce state per vehicle cartId.
     */
    @Synchronized
    fun evaluateDriverCampusPresence(
        driverLat: Double,
        driverLng: Double,
        accuracy: Float = 0f,
        cartId: String? = null
    ): Boolean {
        // Anti-spoofing / sanity bounds
        if (driverLat < 25.0 || driverLat > 26.0 || driverLng < 86.8 || driverLng > 87.3) {
            return false
        }

        val tracker = getTracker(cartId)

        // Inaccurate GPS fixes (> 50m) must not flip the presence state
        if (accuracy > MAX_ACCEPTABLE_GPS_ACCURACY_METERS) {
            return tracker.isDriverInsideTracked
        }

        val distance = calculateDistanceMeters(driverLat, driverLng, CAMPUS_CENTER_LAT, CAMPUS_CENTER_LNG)

        if (!tracker.isTrackerInitialized) {
            tracker.isDriverInsideTracked = (distance <= CAMPUS_GEOFENCE_RADIUS_METERS)
            tracker.consecutiveInsideCount = if (tracker.isDriverInsideTracked) 3 else 0
            tracker.consecutiveOutsideCount = if (tracker.isDriverInsideTracked) 0 else 3
            tracker.isTrackerInitialized = true
            return tracker.isDriverInsideTracked
        }

        if (tracker.isDriverInsideTracked) {
            // Driver is currently marked INSIDE campus.
            // Require 3 consecutive fixes outside the EXIT radius (1150m) to confirm departure.
            if (distance > CAMPUS_EXIT_RADIUS_METERS) {
                tracker.consecutiveOutsideCount++
                tracker.consecutiveInsideCount = 0
                if (tracker.consecutiveOutsideCount >= 3) {
                    tracker.isDriverInsideTracked = false
                }
            } else {
                tracker.consecutiveInsideCount++
                tracker.consecutiveOutsideCount = 0
            }
        } else {
            // Driver is currently marked OUTSIDE campus.
            // Require 2 consecutive fixes inside the ENTER radius (1000m) to confirm arrival.
            if (distance <= CAMPUS_GEOFENCE_RADIUS_METERS) {
                tracker.consecutiveInsideCount++
                tracker.consecutiveOutsideCount = 0
                if (tracker.consecutiveInsideCount >= 2) {
                    tracker.isDriverInsideTracked = true
                }
            } else {
                tracker.consecutiveOutsideCount++
                tracker.consecutiveInsideCount = 0
            }
        }
        return tracker.isDriverInsideTracked
    }

    @Synchronized
    fun setDriverPresence(inside: Boolean, cartId: String? = null) {
        val tracker = getTracker(cartId)
        tracker.isDriverInsideTracked = inside
        tracker.consecutiveInsideCount = if (inside) 3 else 0
        tracker.consecutiveOutsideCount = if (inside) 0 else 3
        tracker.isTrackerInitialized = true
    }

    @Synchronized
    fun isDriverCurrentlyInside(cartId: String? = null): Boolean = getTracker(cartId).isDriverInsideTracked

    /**
     * Calculates precise distance between two Lat/Lng points using mathematical Haversine formula
     */
    fun calculateDistanceMeters(
        lat1: Double,
        lng1: Double,
        lat2: Double = GATE_LAT,
        lng2: Double = GATE_LNG
    ): Double {
        val earthRadius = 6371000.0 // meters
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLng / 2) * Math.sin(dLng / 2)
        val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
        return earthRadius * c
    }

    /**
     * Calculates precise distance from Vikramshila Library center point (Driver Service Area Center)
     */
    fun calculateDistanceFromLibraryMeters(
        driverLat: Double,
        driverLng: Double
    ): Double {
        return calculateDistanceMeters(driverLat, driverLng, LIBRARY_LAT, LIBRARY_LNG)
    }

    /**
     * Validates whether a student is within the 70.0-meter Main Gate geofence radius.
     * When TEST_MODE_ALLOW_ANY_PICKUP_LOCATION is false (production), strictly enforces the 70-meter boundary.
     */
    fun isWithinGeofence(
        studentLat: Double,
        studentLng: Double
    ): Boolean {
        // REMOVE BEFORE PRODUCTION RELEASE: Temporary any-location ride request testing bypass
        if (TEST_MODE_ALLOW_ANY_PICKUP_LOCATION) {
            return true
        } else {
            // EXISTING 70-meter geofence rule PRESERVED INTACT
            val distance = calculateDistanceMeters(studentLat, studentLng, GATE_LAT, GATE_LNG)
            return distance <= MAX_GEOFENCE_METERS
        }
    }

    /**
     * Checks if driver's location is within 1000 meters of Vikramshila Library
     */
    fun isWithinDriverServiceArea(
        driverLat: Double,
        driverLng: Double
    ): Boolean {
        val distance = calculateDistanceFromLibraryMeters(driverLat, driverLng)
        return distance <= DRIVER_SERVICE_AREA_RADIUS_METERS
    }
}

