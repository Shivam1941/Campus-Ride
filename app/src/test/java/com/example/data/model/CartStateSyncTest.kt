package com.example.data.model

import org.junit.Assert.*
import org.junit.Test

class CartStateSyncTest {

    @Test
    fun testClockSkewResilience_driverAheadDoesNotCauseStaleOrOffline() {
        val now = System.currentTimeMillis()
        // Driver phone clock is 60 seconds AHEAD of student phone clock
        val driverTimestampAhead = now + 60_000L

        val cartState = GolfCartState(
            cartId = "cart_1",
            cartName = "Cart 1",
            latitude = 25.2577810,
            longitude = 87.0418910,
            locationTimestampMillis = driverTimestampAhead,
            lastHeartbeatMillis = driverTimestampAhead,
            lastUpdatedMillis = driverTimestampAhead,
            localReceiptTimestampMillis = now,
            driverStatus = "Available",
            isAvailable = true,
            status = GolfCartStatus.HALTED
        )

        // Age should be bounded by local receipt age (close to 0 ms), not cross-device subtraction
        assertTrue("Location age should be small on immediate receipt despite clock skew", cartState.locationAgeMs < 5_000L)
        assertTrue("Heartbeat age should be small on immediate receipt despite clock skew", cartState.heartbeatAgeMs < 5_000L)
        assertTrue("Cart should be driver online", cartState.isDriverOnline)
        assertTrue("Location should be available", cartState.isLocationAvailable)
        assertEquals(CartPresenceState.ONLINE_LOCATION_AVAILABLE, cartState.presenceState)
        assertEquals("Live", cartState.presenceState.badgeText)
    }

    @Test
    fun testStationaryGpsIntervalTolerance_remainsLiveDuringStationaryGpsMode() {
        val now = System.currentTimeMillis()
        // Stationary GPS emits every 30-35s. Suppose last update was received 40 seconds ago.
        val pastTimestamp = now - 40_000L

        val stationaryCart = GolfCartState(
            cartId = "cart_2",
            cartName = "Cart 2",
            latitude = 25.2577810,
            longitude = 87.0418910,
            locationTimestampMillis = pastTimestamp,
            lastHeartbeatMillis = pastTimestamp,
            lastUpdatedMillis = pastTimestamp,
            localReceiptTimestampMillis = pastTimestamp,
            driverStatus = "Available",
            isAvailable = true,
            status = GolfCartStatus.HALTED
        )

        // In previous code with 45s threshold, slight network jitter caused false "Syncing GPS"
        // With 90s threshold, 40s stationary reading remains strictly AVAILABLE and LIVE
        assertTrue("Stationary cart at 40s must remain location available", stationaryCart.isLocationAvailable)
        assertFalse("Stationary cart at 40s must not be marked stale", stationaryCart.isLocationStale)
        assertTrue("Stationary cart at 40s must remain online", stationaryCart.isDriverOnline)
        assertEquals(CartPresenceState.ONLINE_LOCATION_AVAILABLE, stationaryCart.presenceState)
    }

    @Test
    fun testPhysicalCampusPresenceDecoupledFromDriverStatus() {
        // Physical coordinates at Boys Hostel (inside campus)
        val hostelCart = GolfCartState(
            cartId = "cart_1",
            latitude = 25.2577810,
            longitude = 87.0418910,
            driverStatus = "Driver Not Available",
            isAvailable = false,
            status = GolfCartStatus.HALTED
        )

        // Physical coordinates inside campus must NEVER report isOutsideCampus = true
        assertTrue("Cart physically at Hostel must be inside campus", hostelCart.isInsideCampus)
        assertFalse("Cart physically at Hostel must not be outside campus", hostelCart.isOutsideCampus)
    }

    @Test
    fun testCart1AndCart2Isolation() {
        val now = System.currentTimeMillis()
        val cart1 = GolfCartState(
            cartId = "cart_1",
            cartName = "Cart 1",
            latitude = 25.2531616,
            longitude = 87.0370730,
            driverStatus = "Available",
            isAvailable = true,
            status = GolfCartStatus.HALTED,
            lastUpdatedMillis = now
        )

        val cart2 = GolfCartState(
            cartId = "cart_2",
            cartName = "Cart 2",
            latitude = 25.2577810,
            longitude = 87.0418910,
            driverStatus = "Available",
            isAvailable = true,
            status = GolfCartStatus.HALTED,
            lastUpdatedMillis = now
        )

        assertEquals("Cart 1", cart1.displayCartLabel)
        assertEquals("Cart 2", cart2.displayCartLabel)
        assertNotEquals(cart1.latitude, cart2.latitude)
        assertNotEquals(cart1.cartId, cart2.cartId)
    }

    @Test
    fun testStaleAndOfflineTransitionAfterThresholds() {
        val now = System.currentTimeMillis()
        // Update from 100 seconds ago (> 90s stale threshold, < 120s heartbeat expiration)
        val staleTimestamp = now - 100_000L

        val staleCart = GolfCartState(
            cartId = "cart_1",
            latitude = 25.2577810,
            longitude = 87.0418910,
            locationTimestampMillis = staleTimestamp,
            lastHeartbeatMillis = staleTimestamp,
            lastUpdatedMillis = staleTimestamp,
            localReceiptTimestampMillis = staleTimestamp,
            driverStatus = "Available",
            isAvailable = true,
            status = GolfCartStatus.HALTED
        )

        assertFalse("Location should no longer be fresh at 100s", staleCart.isLocationAvailable)
        assertTrue("Location should be marked stale at 100s", staleCart.isLocationStale)
        assertTrue("Cart should still be considered online within 120s heartbeat expiration", staleCart.isDriverOnline)
        assertEquals(CartPresenceState.ONLINE_LOCATION_STALE, staleCart.presenceState)
        assertEquals("Stale GPS", staleCart.presenceState.badgeText)

        // Update from 150 seconds ago (> 120s heartbeat expiration)
        val expiredTimestamp = now - 150_000L
        val expiredCart = staleCart.copy(
            locationTimestampMillis = expiredTimestamp,
            lastHeartbeatMillis = expiredTimestamp,
            lastUpdatedMillis = expiredTimestamp,
            localReceiptTimestampMillis = expiredTimestamp,
            isAvailable = false,
            driverStatus = "Offline"
        )

        assertFalse("Expired cart should be offline", expiredCart.isDriverOnline)
        assertEquals(CartPresenceState.OFFLINE, expiredCart.presenceState)
    }

    @Test
    fun testClockSkewResilience_driverBehindDoesNotCauseStaleOrOffline() {
        val now = System.currentTimeMillis()
        // Driver phone clock is 60 seconds BEHIND student phone clock
        val driverTimestampBehind = now - 60_000L

        val cartState = GolfCartState(
            cartId = "cart_1",
            cartName = "Cart 1",
            latitude = 25.2577810,
            longitude = 87.0418910,
            locationTimestampMillis = driverTimestampBehind,
            lastHeartbeatMillis = driverTimestampBehind,
            lastUpdatedMillis = driverTimestampBehind,
            localReceiptTimestampMillis = now, // Received just now on student device
            driverStatus = "Available",
            isAvailable = true,
            status = GolfCartStatus.HALTED
        )

        // Age should be bounded by local receipt age (close to 0 ms), not cross-device clock skew
        assertTrue("Location age should be small on immediate receipt despite clock skew behind", cartState.locationAgeMs < 5_000L)
        assertTrue("Heartbeat age should be small on immediate receipt despite clock skew behind", cartState.heartbeatAgeMs < 5_000L)
        assertTrue("Cart should be driver online", cartState.isDriverOnline)
        assertTrue("Location should be available", cartState.isLocationAvailable)
        assertEquals(CartPresenceState.ONLINE_LOCATION_AVAILABLE, cartState.presenceState)
        assertEquals("Live", cartState.presenceState.badgeText)
    }
}
