package com.example.data.model

enum class GolfCartStatus(val label: String) {
    MOVING("Moving"),
    HALTED("Halted"),
    OFFLINE("Offline")
}

enum class CartPresenceState(val label: String, val badgeText: String) {
    ONLINE_LOCATION_AVAILABLE("Online • Live Location", "Live"),
    ONLINE_LOCATION_STALE("Online • Stale Location", "Stale GPS"),
    ONLINE_NO_LOCATION("Online • Location Pending", "Syncing GPS"),
    OFFLINE("Offline", "Offline"),
    NETWORK_ERROR("Network Error", "Offline");

    val isLocationAvailable: Boolean
        get() = this == ONLINE_LOCATION_AVAILABLE
}

data class GolfCartState(
    val cartId: String? = null,
    val cartName: String? = null,
    val driverId: String? = null,
    val tripId: String? = null,
    val isTripActive: Boolean = false,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val speedKmH: Int? = null,
    val bearing: Float? = null,
    val accuracy: Float? = null,
    val status: GolfCartStatus? = null,
    val batteryLevel: Int? = null,
    val lastUpdatedMillis: Long? = null,
    val lastHeartbeatMillis: Long? = null,
    val locationTimestampMillis: Long? = null,
    val distanceToGateMeters: Int? = null,
    val distanceToUserMeters: Int? = null,
    val relativeMovement: String? = null, // "Coming Towards You", "Moving Away", "Stationary"
    val etaMinutes: Int? = null,
    val driverStatus: String? = null,
    val isAvailable: Boolean = false,
    val activeRequestId: String? = null,
    val direction: String? = null, // e.g. "Trunkut → Main Gate", "Computer Centre → Hostel"
    val currentStop: String? = null, // e.g. "Trunkut", "Main Gate", "Near Computer Centre"
    val nextStop: String? = null // e.g. "Main Gate", "Computer Centre", "Hostel"
) {
    companion object {
        const val HEARTBEAT_INTERVAL_MS = 25_000L
        const val HEARTBEAT_EXPIRATION_MS = 60_000L
        const val LOCATION_STALE_THRESHOLD_MS = 45_000L
        const val LOCATION_EXPIRED_THRESHOLD_MS = 120_000L
    }

    val landmarkZone: String
        get() = com.example.location.CampusLandmarkZone.getCartLocationDescription(latitude, longitude)

    val heartbeatAgeMs: Long
        get() = lastHeartbeatMillis?.let { kotlin.math.abs(System.currentTimeMillis() - it) }
            ?: lastUpdatedMillis?.let { kotlin.math.abs(System.currentTimeMillis() - it) }
            ?: Long.MAX_VALUE

    val locationAgeMs: Long
        get() = locationTimestampMillis?.let { kotlin.math.abs(System.currentTimeMillis() - it) }
            ?: lastUpdatedMillis?.let { kotlin.math.abs(System.currentTimeMillis() - it) }
            ?: Long.MAX_VALUE

    val hasCoordinates: Boolean
        get() = latitude != null && longitude != null && latitude != 0.0 && longitude != 0.0

    val isInsideCampus: Boolean
        get() {
            if (driverStatus.equals("Outside Campus", ignoreCase = true)) return false
            if (driverStatus.equals("Driver Not Available", ignoreCase = true)) return false
            if (!hasCoordinates) return true
            return com.example.location.GeofenceManager.isInsideCampusGeofence(latitude!!, longitude!!)
        }

    val isOutsideCampus: Boolean
        get() = !isInsideCampus || driverStatus.equals("Outside Campus", ignoreCase = true) || driverStatus.equals("Driver Not Available", ignoreCase = true)

    val isDriverOnline: Boolean
        get() {
            if (driverStatus.equals("Offline", ignoreCase = true)) return false
            if (driverStatus.equals("Off Duty", ignoreCase = true)) return false
            if (driverStatus.equals("Outside Campus", ignoreCase = true)) return false
            if (driverStatus.equals("Driver Not Available", ignoreCase = true)) return false
            if (hasCoordinates && !isInsideCampus) return false

            // Explicitly available or active trip indicates driver is online
            if (isAvailable || isTripActive) return true
            if (driverStatus.equals("Available", ignoreCase = true) || driverStatus.equals("On Trip", ignoreCase = true)) return true

            // If status is OFFLINE and not explicitly available/on-trip
            if (status == GolfCartStatus.OFFLINE) return false

            // Active heartbeat within threshold or moving/halted status
            return heartbeatAgeMs < HEARTBEAT_EXPIRATION_MS
        }

    val isLocationAvailable: Boolean
        get() = hasCoordinates && locationAgeMs <= LOCATION_STALE_THRESHOLD_MS

    val isLocationStale: Boolean
        get() = hasCoordinates && locationAgeMs in (LOCATION_STALE_THRESHOLD_MS + 1)..LOCATION_EXPIRED_THRESHOLD_MS

    val isLocationExpiredOrMissing: Boolean
        get() = !hasCoordinates || locationAgeMs > LOCATION_EXPIRED_THRESHOLD_MS

    val presenceState: CartPresenceState
        get() {
            if (!isDriverOnline) return CartPresenceState.OFFLINE
            return when {
                isLocationAvailable -> CartPresenceState.ONLINE_LOCATION_AVAILABLE
                isLocationStale -> CartPresenceState.ONLINE_LOCATION_STALE
                else -> CartPresenceState.ONLINE_NO_LOCATION
            }
        }

    /**
     * Determines whether the cart is actively broadcasting fresh GPS coordinates.
     */
    val isLive: Boolean
        get() = isDriverOnline && isLocationAvailable && isInsideCampus

    val effectiveAvailabilityLabel: String
        get() = when {
            isOutsideCampus -> "Driver Not Available"
            driverStatus.equals("Lunch Break", ignoreCase = true) -> "Lunch Break"
            driverStatus.equals("Offline", ignoreCase = true) || status == GolfCartStatus.OFFLINE -> "Offline"
            isTripActive || driverStatus.equals("On Trip", ignoreCase = true) -> "Busy"
            else -> "Available"
        }

    val isGpsFresh: Boolean
        get() = isLocationAvailable

    val isGpsTemporarilyUnavailable: Boolean
        get() = isDriverOnline && isLocationStale

    val isLocationDelayed: Boolean
        get() = isLocationStale

    /**
     * Formats the last updated timestamp into human readable relative string.
     */
    val lastUpdatedFormatted: String
        get() {
            val updateTime = locationTimestampMillis ?: lastUpdatedMillis ?: return "No GPS signal"
            val diffSec = (System.currentTimeMillis() - updateTime) / 1000
            return when {
                diffSec < 5 -> "Updated just now"
                diffSec < 60 -> "Updated ${diffSec}s ago"
                diffSec < 3600 -> "Last updated ${diffSec / 60}m ago"
                else -> "Last updated >1h ago"
            }
        }

    val displayCartLabel: String
        get() = when (cartId) {
            "cart_1" -> "Cart 1"
            "cart_2" -> "Cart 2"
            else -> cartName ?: "Campus Cart"
        }

    val displayCurrentLocation: String
        get() = currentStop ?: landmarkZone

    val displayDirection: String
        get() = direction ?: when {
            landmarkZone.contains("Hostel", ignoreCase = true) -> "Boys Hostel → Main Gate"
            landmarkZone.contains("Gate", ignoreCase = true) -> "Main Gate → Boys Hostel"
            else -> "In Transit"
        }

    val displayNextStop: String
        get() = nextStop ?: when {
            landmarkZone.contains("Hostel", ignoreCase = true) -> "Computer Centre"
            landmarkZone.contains("Computer", ignoreCase = true) -> "Trunkut"
            landmarkZone.contains("Trunkut", ignoreCase = true) -> "Main Gate"
            else -> "Next Stop"
        }

    /**
     * Fixed phone number belonging to this specific cart (not the driver).
     */
    val cartPhoneNumber: String
        get() = CampusCartConfig.getCartPhoneNumber(cartId)

    /**
     * Formatted display contact number for this cart.
     */
    val cartDisplayPhoneNumber: String
        get() = CampusCartConfig.getCartDisplayNumber(cartId)
}


