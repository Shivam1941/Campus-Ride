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
    val nextStop: String? = null, // e.g. "Main Gate", "Computer Centre", "Hostel"
    val localReceiptTimestampMillis: Long = System.currentTimeMillis()
) {
    companion object {
        const val HEARTBEAT_INTERVAL_MS = 25_000L
        const val HEARTBEAT_EXPIRATION_MS = 120_000L       // 120s (allows 3-4 missed stationary heartbeat cycles)
        const val LOCATION_STALE_THRESHOLD_MS = 90_000L     // 90s (allows 2-3 missed stationary GPS cycles)
        const val LOCATION_EXPIRED_THRESHOLD_MS = 180_000L  // 180s (3 minutes)
    }

    val landmarkZone: String
        get() = com.example.location.CampusLandmarkZone.getCartLocationDescription(latitude, longitude)

    val localReceiptAgeMs: Long
        get() = (System.currentTimeMillis() - localReceiptTimestampMillis).coerceAtLeast(0L)

    val heartbeatAgeMs: Long
        get() {
            val ts = lastHeartbeatMillis ?: lastUpdatedMillis ?: return Long.MAX_VALUE
            val now = System.currentTimeMillis()
            if (now < ts) {
                // Remote clock is in the future (clock skew): rely on local elapsed time since receipt
                return localReceiptAgeMs
            }
            val rawAge = now - ts
            // If rawAge is relatively recent (< 5 min) and we recently received a live snapshot,
            // don't let clock skew between devices falsely trigger expiration
            return if (rawAge < 5 * 60_000L && localReceiptAgeMs < rawAge) {
                localReceiptAgeMs
            } else {
                rawAge
            }
        }

    val locationAgeMs: Long
        get() {
            val ts = locationTimestampMillis ?: lastUpdatedMillis ?: return Long.MAX_VALUE
            val now = System.currentTimeMillis()
            if (now < ts) {
                // Remote clock is in the future (clock skew): rely on local elapsed time since receipt
                return localReceiptAgeMs
            }
            val rawAge = now - ts
            // If rawAge is relatively recent (< 5 min) and we recently received a live snapshot,
            // don't let clock skew between devices falsely trigger expiration
            return if (rawAge < 5 * 60_000L && localReceiptAgeMs < rawAge) {
                localReceiptAgeMs
            } else {
                rawAge
            }
        }

    val hasCoordinates: Boolean
        get() = latitude != null && longitude != null && latitude != 0.0 && longitude != 0.0

    val isInsideCampus: Boolean
        get() {
            if (hasCoordinates) {
                return com.example.location.GeofenceManager.isInsideCampusGeofence(latitude!!, longitude!!)
            }
            if (driverStatus.equals("Outside Campus", ignoreCase = true)) return false
            return true
        }

    val isOutsideCampus: Boolean
        get() = !isInsideCampus

    val isDriverOnline: Boolean
        get() {
            if (driverStatus.equals("Offline", ignoreCase = true)) return false
            if (driverStatus.equals("Off Duty", ignoreCase = true)) return false
            if (hasCoordinates && !isInsideCampus) return false

            // Explicitly available or active trip indicates driver is online
            if (isAvailable || isTripActive) return true
            if (driverStatus.equals("Available", ignoreCase = true) ||
                driverStatus.equals("On Trip", ignoreCase = true) ||
                driverStatus.equals("On Duty", ignoreCase = true)) return true

            // If status is OFFLINE and not explicitly available/on-trip
            if (status == GolfCartStatus.OFFLINE && !isAvailable) {
                return heartbeatAgeMs < HEARTBEAT_EXPIRATION_MS
            }

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
            val now = System.currentTimeMillis()
            val diffSec = if (now >= updateTime) (now - updateTime) / 1000 else (localReceiptAgeMs / 1000)
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


