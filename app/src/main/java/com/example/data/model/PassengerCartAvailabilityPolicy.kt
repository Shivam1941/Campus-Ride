package com.example.data.model

/**
 * Passenger Cart Availability Policy
 *
 * Enforces strict separation between two independent domains:
 *
 * 1. PHYSICAL LOCATION DOMAIN:
 *    Can the app determine where the cart physically is?
 *    Governed strictly by:
 *    - Valid, non-null, non-zero coordinates
 *    - Fresh/unexpired GPS telemetry (!isLocationExpiredOrMissing)
 *    - Coordinates reside within campus geofence (!isOutsideCampus)
 *    Driver operational status ("Driver Not Available", "Lunch Break", "Off Duty")
 *    MUST NEVER suppress or invalidate physical location.
 *
 * 2. OPERATIONAL / RIDE AVAILABILITY DOMAIN:
 *    Is the cart currently in service and accepting passenger ride requests?
 *    Governed by:
 *    - Driver operational status (must be "Available", not "Lunch Break", "Off Duty", "Offline", etc.)
 *    - Active communication / online status (isDriverOnline && status != OFFLINE)
 *    - Cart is inside campus (!isOutsideCampus)
 *    - Has usable coordinates to navigate a ride (hasCoordinates)
 *    - Not busy on another active trip (!isTripActive && !driverStatus.contains("On Trip"))
 *    - Explicitly available flag (isAvailable)
 */
object PassengerCartAvailabilityPolicy {

    /**
     * Determines whether the cart's physical coordinates can be used for mapping,
     * route tracking, and landmark display.
     */
    fun isPhysicalLocationUsable(cartState: GolfCartState?): Boolean {
        if (cartState == null) return false
        if (!cartState.hasCoordinates) return false
        val lat = cartState.latitude ?: return false
        val lng = cartState.longitude ?: return false
        if (lat == 0.0 || lng == 0.0) return false
        if (cartState.isLocationExpiredOrMissing) return false
        if (cartState.isOutsideCampus) return false
        return true
    }

    /**
     * Determines whether the cart is actively offering ride service to passengers.
     */
    fun isRideServiceAvailable(cartState: GolfCartState?): Boolean {
        if (cartState == null) return false
        // A cart with missing coordinates cannot accept passenger ride requests
        if (!cartState.hasCoordinates) return false
        // A cart outside campus cannot accept campus passenger ride requests
        if (cartState.isOutsideCampus) return false
        // Must be explicitly enabled and not busy
        if (!cartState.isAvailable) return false
        if (cartState.isTripActive) return false
        if (cartState.status == GolfCartStatus.OFFLINE) return false

        // Operational driver status checks
        val status = cartState.driverStatus
        if (status.equals("Offline", ignoreCase = true)) return false
        if (status.equals("Off Duty", ignoreCase = true)) return false
        if (status.equals("Lunch Break", ignoreCase = true)) return false
        if (status.equals("Driver Not Available", ignoreCase = true)) return false
        if (status.equals("Outside Campus", ignoreCase = true)) return false
        if (status.equals("On Trip", ignoreCase = true)) return false
        if (status.equals("Busy", ignoreCase = true)) return false

        // Driver must be communicating / online
        if (!cartState.isDriverOnline) return false

        return true
    }

    /**
     * Determines whether any cart in the fleet is currently accepting ride requests.
     */
    fun isAnyRideServiceAvailable(
        cart1State: GolfCartState?,
        cart2State: GolfCartState?,
        legacyDriverAvailable: Boolean = false
    ): Boolean {
        if (isRideServiceAvailable(cart1State) || isRideServiceAvailable(cart2State)) {
            return true
        }
        // Fallback to legacy isDriverAvailable boolean only if at least one cart is inside campus
        return legacyDriverAvailable && (
            (cart1State != null && cart1State.isInsideCampus && !cart1State.isOutsideCampus) ||
            (cart2State != null && cart2State.isInsideCampus && !cart2State.isOutsideCampus)
        )
    }
}
