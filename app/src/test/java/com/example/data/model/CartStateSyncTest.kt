package com.example.data.model

import com.example.data.repository.FirestoreSnapshotIngestionPolicy
import com.example.location.CampusRouteGraph
import com.example.location.RouteTrackingGatingPolicy
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

    @Test
    fun testCaseA_freshGpsUpdateDoesNotExpireDueToOldRepositoryInstantiationTime() {
        val now = System.currentTimeMillis()
        val repoLaunchTime = now - 600_000L // App opened 10 minutes ago

        // Initial state at launch
        val initialState = GolfCartState(
            cartId = "cart_1",
            cartName = "Cart 1",
            status = GolfCartStatus.OFFLINE,
            localReceiptTimestampMillis = repoLaunchTime
        )

        // GPS fix arrives now
        val freshGpsState = initialState.copy(
            latitude = 25.2565000,
            longitude = 87.0400000,
            speedKmH = 10,
            status = GolfCartStatus.MOVING,
            driverStatus = "Available",
            isAvailable = true,
            locationTimestampMillis = now,
            lastUpdatedMillis = now,
            lastHeartbeatMillis = now,
            localReceiptTimestampMillis = now // Updated with current fix time
        )

        assertTrue("Location age must be immediate (< 1s)", freshGpsState.locationAgeMs < 1_000L)
        assertTrue("Heartbeat age must be immediate (< 1s)", freshGpsState.heartbeatAgeMs < 1_000L)
        assertTrue("Location must be available", freshGpsState.isLocationAvailable)
        assertFalse("Location must not be expired", freshGpsState.isLocationExpiredOrMissing)
        assertEquals(CartPresenceState.ONLINE_LOCATION_AVAILABLE, freshGpsState.presenceState)
        assertNotEquals("Syncing GPS", freshGpsState.presenceState.badgeText)
        assertEquals("Live", freshGpsState.presenceState.badgeText)
    }

    @Test
    fun testCaseB_firestoreSnapshotRefreshesLocalReceiptTimestamp() {
        val now = System.currentTimeMillis()
        val initialOldTime = now - 900_000L // 15 minutes ago

        val oldState = GolfCartState(
            cartId = "cart_1",
            cartName = "Cart 1",
            localReceiptTimestampMillis = initialOldTime
        )

        // Firestore snapshot arrives at student device at 'now'
        val snapshotState = oldState.copy(
            latitude = 25.2531616,
            longitude = 87.0370730,
            status = GolfCartStatus.HALTED,
            driverStatus = "Available",
            isAvailable = true,
            locationTimestampMillis = now,
            lastUpdatedMillis = now,
            lastHeartbeatMillis = now,
            localReceiptTimestampMillis = now // Refreshed on snapshot receipt
        )

        assertTrue("Local receipt age must be refreshed to near 0", snapshotState.localReceiptAgeMs < 1_000L)
        assertTrue("Location age must reflect fresh receipt", snapshotState.locationAgeMs < 2_000L)
        assertEquals(CartPresenceState.ONLINE_LOCATION_AVAILABLE, snapshotState.presenceState)
    }

    @Test
    fun testCaseC_freshCoordinateWithHistoricalDriverNotAvailableStatusRetainsCoordinatesAndIsInsideCampus() {
        val now = System.currentTimeMillis()
        // Driver had "Driver Not Available" before entering campus, but has now entered Boys Hostel
        val cartWithHistoricalStatus = GolfCartState(
            cartId = "cart_1",
            cartName = "Cart 1",
            latitude = 25.2577810, // Boys Hostel
            longitude = 87.0418910,
            speedKmH = 0,
            bearing = 0f,
            status = GolfCartStatus.HALTED,
            driverStatus = "Driver Not Available", // Historical string from Firestore
            isAvailable = false,
            locationTimestampMillis = now,
            lastUpdatedMillis = now,
            lastHeartbeatMillis = now,
            localReceiptTimestampMillis = now
        )

        // Coordinates must be retained
        assertTrue("Coordinates must be present", cartWithHistoricalStatus.hasCoordinates)
        assertEquals(25.2577810, cartWithHistoricalStatus.latitude!!, 0.000001)
        assertEquals(87.0418910, cartWithHistoricalStatus.longitude!!, 0.000001)

        // Physical coordinates inside campus must NEVER be classified as outside campus
        assertTrue("Must be classified physically inside campus", cartWithHistoricalStatus.isInsideCampus)
        assertFalse("Must not be classified as outside campus", cartWithHistoricalStatus.isOutsideCampus)
    }

    @Test
    fun testCaseD_genuinelyStaleAndOfflineDataBehavesDeterministically() {
        val now = System.currentTimeMillis()

        // 1. Fresh (30s ago): LIVE
        val fresh = GolfCartState(
            cartId = "cart_1",
            latitude = 25.2565000,
            longitude = 87.0400000,
            locationTimestampMillis = now - 30_000L,
            lastHeartbeatMillis = now - 30_000L,
            lastUpdatedMillis = now - 30_000L,
            localReceiptTimestampMillis = now - 30_000L,
            driverStatus = "Available",
            isAvailable = true,
            status = GolfCartStatus.HALTED
        )
        assertTrue(fresh.isLocationAvailable)
        assertFalse(fresh.isLocationStale)
        assertEquals(CartPresenceState.ONLINE_LOCATION_AVAILABLE, fresh.presenceState)

        // 2. Stale (100s ago > 90s, < 120s): STALE GPS
        val stale = fresh.copy(
            locationTimestampMillis = now - 100_000L,
            lastHeartbeatMillis = now - 100_000L,
            lastUpdatedMillis = now - 100_000L,
            localReceiptTimestampMillis = now - 100_000L
        )
        assertFalse(stale.isLocationAvailable)
        assertTrue(stale.isLocationStale)
        assertEquals(CartPresenceState.ONLINE_LOCATION_STALE, stale.presenceState)
        assertEquals("Stale GPS", stale.presenceState.badgeText)

        // 3. Expired / Offline (200s ago > 120s, > 180s): OFFLINE
        val expired = fresh.copy(
            locationTimestampMillis = now - 200_000L,
            lastHeartbeatMillis = now - 200_000L,
            lastUpdatedMillis = now - 200_000L,
            localReceiptTimestampMillis = now - 200_000L,
            isAvailable = false,
            driverStatus = "Offline"
        )
        assertTrue(expired.isLocationExpiredOrMissing)
        assertFalse(expired.isDriverOnline)
        assertEquals(CartPresenceState.OFFLINE, expired.presenceState)
    }

    @Test
    fun testFix2_CaseA_liveSnapshotProcessedDespiteDriverClockBehindPassengerClock() {
        val now = System.currentTimeMillis()
        // Driver phone clock is 2 minutes BEHIND passenger clock
        val driverClockBehind = now - 120_000L
        val existingBestTs = now - 10_000L

        // A LIVE Firestore snapshot arrives from the network (isFromDiskCache = false)
        val shouldProcess = FirestoreSnapshotIngestionPolicy.shouldProcessSnapshot(
            isFromDiskCache = false,
            hasLiveServerData = true,
            incomingBestTs = driverClockBehind,
            existingBestTs = existingBestTs
        )

        assertTrue("Live server snapshots must NEVER be discarded due to client clock differences", shouldProcess)
    }

    @Test
    fun testFix2_CaseB_cachedSnapshotOlderThanLiveServerSnapshotDoesNotRegressState() {
        val now = System.currentTimeMillis()
        val liveServerTs = now - 5_000L
        val oldCachedTs = now - 300_000L // 5 minutes old cached data

        // Live server snapshot has already been processed in this session
        val shouldProcessCached = FirestoreSnapshotIngestionPolicy.shouldProcessSnapshot(
            isFromDiskCache = true,
            hasLiveServerData = true,
            incomingBestTs = oldCachedTs,
            existingBestTs = liveServerTs
        )

        assertFalse("Stale cached snapshot must NOT overwrite active live server data", shouldProcessCached)
    }

    @Test
    fun testFix2_CaseC_liveSnapshotWithOlderTimestampStillProcessed() {
        val now = System.currentTimeMillis()
        // Out-of-order client timestamps due to driver clock adjustment or multi-device write
        val olderClientTs = now - 30_000L
        val existingTs = now - 10_000L

        // Realtime server stream delivers snapshot: isFromDiskCache = false
        val shouldProcess = FirestoreSnapshotIngestionPolicy.shouldProcessSnapshot(
            isFromDiskCache = false,
            hasLiveServerData = true,
            incomingBestTs = olderClientTs,
            existingBestTs = existingTs
        )

        assertTrue("Live server updates must always be processed on arrival", shouldProcess)
    }

    @Test
    fun testFix2_CaseD_validInCampusCoordinatesWithHistoricalDriverNotAvailable() {
        val now = System.currentTimeMillis()
        val initialCart = GolfCartState(
            cartId = "cart_1",
            cartName = "Cart 1",
            status = GolfCartStatus.OFFLINE
        )

        // Incoming snapshot: coordinates inside campus (Boys Hostel), but historical status says "Driver Not Available" / "OFFLINE"
        val resolved = FirestoreSnapshotIngestionPolicy.resolveEffectiveCartState(
            existingCart = initialCart,
            cartId = "cart_1",
            cartName = "Cart 1",
            lat = 25.2577810, // Boys Hostel (inside campus)
            lng = 87.0418910,
            speedKmH = 0,
            bearing = 0f,
            rawStatusStr = "OFFLINE",
            isAvailable = false,
            isTripActive = false,
            driverStatus = "Driver Not Available",
            incomingUpdated = now,
            incomingHeartbeat = now,
            incomingLocationTs = now,
            direction = null,
            currentStop = null,
            nextStop = null,
            localReceiptTime = now
        )

        assertTrue("Coordinates must be present", resolved.hasCoordinates)
        assertEquals(25.2577810, resolved.latitude!!, 0.000001)
        assertEquals(87.0418910, resolved.longitude!!, 0.000001)
        assertTrue("Cart inside campus geofence must have isInsideCampus = true", resolved.isInsideCampus)
        assertFalse("Cart inside campus geofence must have isOutsideCampus = false", resolved.isOutsideCampus)
        // Physical motion status should be HALTED (not OFFLINE) because valid telemetry is active inside campus
        assertNotEquals(GolfCartStatus.OFFLINE, resolved.status)
        assertEquals(GolfCartStatus.HALTED, resolved.status)
        // Ride availability remains separate concept
        assertEquals("Driver Not Available", resolved.driverStatus)
        assertFalse("Operational availability remains false", resolved.isAvailable)
    }

    @Test
    fun testFix2_CaseE_validOutsideCampusCoordinatesCorrectlyDetected() {
        val now = System.currentTimeMillis()
        val initialCart = GolfCartState(
            cartId = "cart_1",
            cartName = "Cart 1",
            status = GolfCartStatus.HALTED
        )

        // Coordinates genuinely outside campus (e.g. 25.2700000, 87.0600000)
        val resolved = FirestoreSnapshotIngestionPolicy.resolveEffectiveCartState(
            existingCart = initialCart,
            cartId = "cart_1",
            cartName = "Cart 1",
            lat = 25.2700000,
            lng = 87.0600000,
            speedKmH = 0,
            bearing = 0f,
            rawStatusStr = "HALTED",
            isAvailable = true,
            isTripActive = false,
            driverStatus = "Available",
            incomingUpdated = now,
            incomingHeartbeat = now,
            incomingLocationTs = now,
            direction = null,
            currentStop = null,
            nextStop = null,
            localReceiptTime = now
        )

        assertFalse("Outside-campus cart must not report isInsideCampus", resolved.isInsideCampus)
        assertTrue("Outside-campus cart must report isOutsideCampus = true", resolved.isOutsideCampus)
        assertEquals("Driver Not Available", resolved.driverStatus)
        assertFalse("Outside-campus cart must not be available for rides", resolved.isAvailable)
        assertEquals(GolfCartStatus.OFFLINE, resolved.status)
    }

    @Test
    fun testFix2_CaseF_missingInvalidCoordinatesWithGenuinelyOfflineData() {
        val now = System.currentTimeMillis()
        val initialCart = GolfCartState(
            cartId = "cart_1",
            cartName = "Cart 1",
            status = GolfCartStatus.OFFLINE
        )

        // Snapshot has null coordinates and offline status
        val resolved = FirestoreSnapshotIngestionPolicy.resolveEffectiveCartState(
            existingCart = initialCart,
            cartId = "cart_1",
            cartName = "Cart 1",
            lat = null,
            lng = null,
            speedKmH = 0,
            bearing = 0f,
            rawStatusStr = "OFFLINE",
            isAvailable = false,
            isTripActive = false,
            driverStatus = "Offline",
            incomingUpdated = now - 300_000L,
            incomingHeartbeat = now - 300_000L,
            incomingLocationTs = 0L,
            direction = null,
            currentStop = null,
            nextStop = null,
            localReceiptTime = now
        )

        assertFalse("Missing coordinates must report hasCoordinates = false", resolved.hasCoordinates)
        assertTrue("Missing coordinates must report isLocationExpiredOrMissing = true", resolved.isLocationExpiredOrMissing)
        assertFalse("Offline cart must report isDriverOnline = false", resolved.isDriverOnline)
        assertEquals(CartPresenceState.OFFLINE, resolved.presenceState)
    }

    @Test
    fun testFix2_CaseG_processedSnapshotRefreshesLocalReceiptTimestamp() {
        val oldReceiptTime = System.currentTimeMillis() - 600_000L // 10 minutes ago
        val initialCart = GolfCartState(
            cartId = "cart_1",
            localReceiptTimestampMillis = oldReceiptTime
        )

        val receiptNow = System.currentTimeMillis()
        val resolved = FirestoreSnapshotIngestionPolicy.resolveEffectiveCartState(
            existingCart = initialCart,
            cartId = "cart_1",
            cartName = "Cart 1",
            lat = 25.2577810,
            lng = 87.0418910,
            speedKmH = 0,
            bearing = 0f,
            rawStatusStr = "HALTED",
            isAvailable = true,
            isTripActive = false,
            driverStatus = "Available",
            incomingUpdated = receiptNow,
            incomingHeartbeat = receiptNow,
            incomingLocationTs = receiptNow,
            direction = null,
            currentStop = null,
            nextStop = null,
            localReceiptTime = receiptNow
        )

        assertEquals("localReceiptTimestampMillis must represent receipt time on this device", receiptNow, resolved.localReceiptTimestampMillis)
        assertTrue("localReceiptAgeMs must be near 0", resolved.localReceiptAgeMs < 1000L)
    }

    // =========================================================================
    // FIX #3: ROUTE PROGRESS / LOCATION GATING TESTS
    // =========================================================================

    @Test
    fun testFix3_CaseA_validCoordinatesWithAvailableStatusReceivesRouteCalculation() {
        val now = System.currentTimeMillis()
        val cartState = GolfCartState(
            cartId = "cart_1",
            cartName = "Cart 1",
            latitude = 25.2577810, // Boys Hostel
            longitude = 87.0418910,
            driverStatus = "Available",
            isAvailable = true,
            status = GolfCartStatus.HALTED,
            lastUpdatedMillis = now,
            locationTimestampMillis = now,
            lastHeartbeatMillis = now,
            localReceiptTimestampMillis = now
        )

        val usableCoords = RouteTrackingGatingPolicy.extractUsableRouteCoordinates(cartState)
        assertNotNull("Case A: Usable coordinates must not be null for valid in-campus coordinates", usableCoords)
        assertEquals(25.2577810, usableCoords!!.first, 0.0001)
        assertEquals(87.0418910, usableCoords.second, 0.0001)

        val progress = CampusRouteGraph.evaluateLiveProgress(
            cartId = "cart_1",
            latitude = usableCoords.first,
            longitude = usableCoords.second,
            isOnline = true
        )

        assertTrue("Case A: Route calculation must report location available", progress.isLocationAvailable)
        assertNotNull("Case A: Current stop at Boys Hostel must be identified", progress.currentStop)
        assertEquals("Boys Hostel", progress.currentStop?.displayName)
        assertNotEquals("LOCATION UNAVAILABLE", progress.statusMessage)
    }

    @Test
    fun testFix3_CaseB_validCoordinatesWithHistoricalDriverNotAvailableStillReceivesRouteCalculation() {
        val now = System.currentTimeMillis()
        // Driver is marked "Driver Not Available" (e.g. not accepting passenger requests),
        // but cart is physically inside campus with fresh GPS coordinates.
        val cartState = GolfCartState(
            cartId = "cart_1",
            cartName = "Cart 1",
            latitude = 25.2531616, // Main Gate
            longitude = 87.0370730,
            driverStatus = "Driver Not Available",
            isAvailable = false,
            status = GolfCartStatus.HALTED,
            lastUpdatedMillis = now,
            locationTimestampMillis = now,
            lastHeartbeatMillis = now,
            localReceiptTimestampMillis = now
        )

        val usableCoords = RouteTrackingGatingPolicy.extractUsableRouteCoordinates(cartState)
        assertNotNull("Case B: Physical coordinates must NOT be suppressed by 'Driver Not Available'", usableCoords)
        assertEquals(25.2531616, usableCoords!!.first, 0.0001)
        assertEquals(87.0370730, usableCoords.second, 0.0001)

        val progress = CampusRouteGraph.evaluateLiveProgress(
            cartId = "cart_1",
            latitude = usableCoords.first,
            longitude = usableCoords.second,
            isOnline = true
        )

        assertTrue("Case B: Route progress must have location available", progress.isLocationAvailable)
        assertNotNull("Case B: Current stop at Main Gate must be identified", progress.currentStop)
        assertEquals("Main Gate", progress.currentStop?.displayName)
        assertNotEquals("LOCATION UNAVAILABLE", progress.statusMessage)
    }

    @Test
    fun testFix3_CaseC_validCoordinatesWithLunchBreakStillReceivesRouteCalculation() {
        val now = System.currentTimeMillis()
        // Driver is on "Lunch Break" (operational break),
        // but cart is parked at Academic Block with fresh coordinates.
        val cartState = GolfCartState(
            cartId = "cart_1",
            cartName = "Cart 1",
            latitude = 25.2590750, // Academic Block
            longitude = 87.0401610,
            driverStatus = "Lunch Break",
            isAvailable = false,
            status = GolfCartStatus.HALTED,
            lastUpdatedMillis = now,
            locationTimestampMillis = now,
            lastHeartbeatMillis = now,
            localReceiptTimestampMillis = now
        )

        val usableCoords = RouteTrackingGatingPolicy.extractUsableRouteCoordinates(cartState)
        assertNotNull("Case C: Physical coordinates must NOT be suppressed by 'Lunch Break'", usableCoords)
        assertEquals(25.2590750, usableCoords!!.first, 0.0001)
        assertEquals(87.0401610, usableCoords.second, 0.0001)

        val progress = CampusRouteGraph.evaluateLiveProgress(
            cartId = "cart_1",
            latitude = usableCoords.first,
            longitude = usableCoords.second,
            isOnline = true
        )

        assertTrue("Case C: Route progress must have location available", progress.isLocationAvailable)
        assertNotNull("Case C: Current stop at Academic Block must be identified", progress.currentStop)
        assertEquals("Academic Block", progress.currentStop?.displayName)
        assertNotEquals("LOCATION UNAVAILABLE", progress.statusMessage)
    }

    @Test
    fun testFix3_CaseD_missingCoordinatesReportsLocationUnavailable() {
        val now = System.currentTimeMillis()
        // Cart has no coordinates
        val cartState = GolfCartState(
            cartId = "cart_1",
            cartName = "Cart 1",
            latitude = null,
            longitude = null,
            driverStatus = "Available",
            isAvailable = true,
            status = GolfCartStatus.HALTED,
            lastUpdatedMillis = now,
            localReceiptTimestampMillis = now
        )

        val usableCoords = RouteTrackingGatingPolicy.extractUsableRouteCoordinates(cartState)
        assertNull("Case D: Missing coordinates must return null usable coordinates", usableCoords)

        val isOnline = RouteTrackingGatingPolicy.isRouteTrackingAvailable(cartState)
        assertFalse("Case D: Route tracking must not be active when coordinates are missing", isOnline)

        val progress = CampusRouteGraph.evaluateLiveProgress(
            cartId = "cart_1",
            latitude = usableCoords?.first,
            longitude = usableCoords?.second,
            isOnline = isOnline
        )

        assertFalse("Case D: Route progress must report location unavailable", progress.isLocationAvailable)
        assertEquals("LOCATION UNAVAILABLE", progress.statusMessage)
    }

    @Test
    fun testFix3_CaseE_invalidCoordinatesDoesNotAttemptRouteCalculation() {
        val now = System.currentTimeMillis()
        // Cart has invalid (0.0, 0.0) coordinates
        val cartState = GolfCartState(
            cartId = "cart_1",
            cartName = "Cart 1",
            latitude = 0.0,
            longitude = 0.0,
            driverStatus = "Available",
            isAvailable = true,
            status = GolfCartStatus.HALTED,
            lastUpdatedMillis = now,
            localReceiptTimestampMillis = now
        )

        val usableCoords = RouteTrackingGatingPolicy.extractUsableRouteCoordinates(cartState)
        assertNull("Case E: Invalid (0.0, 0.0) coordinates must return null usable coordinates", usableCoords)

        val isOnline = RouteTrackingGatingPolicy.isRouteTrackingAvailable(cartState)
        assertFalse("Case E: Route tracking must not be active for invalid coordinates", isOnline)

        val progress = CampusRouteGraph.evaluateLiveProgress(
            cartId = "cart_1",
            latitude = usableCoords?.first,
            longitude = usableCoords?.second,
            isOnline = isOnline
        )

        assertFalse("Case E: Progress must report location unavailable for invalid coordinates", progress.isLocationAvailable)
        assertEquals("LOCATION UNAVAILABLE", progress.statusMessage)
    }

    @Test
    fun testFix3_CaseF_genuinelyOfflineCartDoesNotProduceRouteCoordinates() {
        val now = System.currentTimeMillis()
        // Cart updated 10 minutes ago (> 180s expiration threshold)
        val expiredTime = now - 600_000L
        val cartState = GolfCartState(
            cartId = "cart_1",
            cartName = "Cart 1",
            latitude = 25.2577810,
            longitude = 87.0418910,
            driverStatus = "Offline",
            isAvailable = false,
            status = GolfCartStatus.OFFLINE,
            lastUpdatedMillis = expiredTime,
            locationTimestampMillis = expiredTime,
            lastHeartbeatMillis = expiredTime,
            localReceiptTimestampMillis = expiredTime
        )

        assertTrue("Cart with 10min old location must be expired", cartState.isLocationExpiredOrMissing)

        val usableCoords = RouteTrackingGatingPolicy.extractUsableRouteCoordinates(cartState)
        assertNull("Case F: Genuinely expired/offline cart must not produce fake coordinates", usableCoords)

        val progress = CampusRouteGraph.evaluateLiveProgress(
            cartId = "cart_1",
            latitude = usableCoords?.first,
            longitude = usableCoords?.second,
            isOnline = RouteTrackingGatingPolicy.isRouteTrackingAvailable(cartState)
        )

        assertFalse("Case F: Genuinely offline cart must report location unavailable", progress.isLocationAvailable)
        assertEquals("LOCATION UNAVAILABLE", progress.statusMessage)
    }

    // =========================================================================
    // FIX #4: PASSENGER CART AVAILABILITY CASCADE TESTS (CASES A - H)
    // =========================================================================

    @Test
    fun testFix4_CaseA_validGpsWithAvailableStatus() {
        val now = System.currentTimeMillis()
        val cart = GolfCartState(
            cartId = "cart_1",
            cartName = "Cart 1",
            latitude = 25.2577810, // Boys Hostel (inside campus)
            longitude = 87.0418910,
            driverStatus = "Available",
            isAvailable = true,
            status = GolfCartStatus.HALTED,
            lastUpdatedMillis = now,
            locationTimestampMillis = now,
            lastHeartbeatMillis = now,
            localReceiptTimestampMillis = now
        )

        assertTrue("Case A: Physical location must be usable", PassengerCartAvailabilityPolicy.isPhysicalLocationUsable(cart))
        assertTrue("Case A: Ride service must be available", PassengerCartAvailabilityPolicy.isRideServiceAvailable(cart))
    }

    @Test
    fun testFix4_CaseB_validGpsWithDriverNotAvailable() {
        val now = System.currentTimeMillis()
        val cart = GolfCartState(
            cartId = "cart_1",
            cartName = "Cart 1",
            latitude = 25.2577810, // Inside campus
            longitude = 87.0418910,
            driverStatus = "Driver Not Available",
            isAvailable = false,
            status = GolfCartStatus.HALTED,
            lastUpdatedMillis = now,
            locationTimestampMillis = now,
            lastHeartbeatMillis = now,
            localReceiptTimestampMillis = now
        )

        assertTrue("Case B: Physical location MUST remain usable despite Driver Not Available", PassengerCartAvailabilityPolicy.isPhysicalLocationUsable(cart))
        assertFalse("Case B: Ride service must be unavailable for Driver Not Available", PassengerCartAvailabilityPolicy.isRideServiceAvailable(cart))
    }

    @Test
    fun testFix4_CaseC_validGpsWithLunchBreak() {
        val now = System.currentTimeMillis()
        val cart = GolfCartState(
            cartId = "cart_1",
            cartName = "Cart 1",
            latitude = 25.2590750, // Academic Block (inside campus)
            longitude = 87.0401610,
            driverStatus = "Lunch Break",
            isAvailable = false,
            status = GolfCartStatus.HALTED,
            lastUpdatedMillis = now,
            locationTimestampMillis = now,
            lastHeartbeatMillis = now,
            localReceiptTimestampMillis = now
        )

        assertTrue("Case C: Physical location MUST remain usable during Lunch Break", PassengerCartAvailabilityPolicy.isPhysicalLocationUsable(cart))
        assertFalse("Case C: Ride service must be unavailable during Lunch Break", PassengerCartAvailabilityPolicy.isRideServiceAvailable(cart))
    }

    @Test
    fun testFix4_CaseD_validGpsWithOffDuty() {
        val now = System.currentTimeMillis()
        val cart = GolfCartState(
            cartId = "cart_1",
            cartName = "Cart 1",
            latitude = 25.2531616, // Main Gate (inside campus)
            longitude = 87.0370730,
            driverStatus = "Off Duty",
            isAvailable = false,
            status = GolfCartStatus.HALTED,
            lastUpdatedMillis = now,
            locationTimestampMillis = now,
            lastHeartbeatMillis = now,
            localReceiptTimestampMillis = now
        )

        assertTrue("Case D: Physical location MUST remain usable when driver is Off Duty", PassengerCartAvailabilityPolicy.isPhysicalLocationUsable(cart))
        assertFalse("Case D: Ride service must be unavailable when driver is Off Duty", PassengerCartAvailabilityPolicy.isRideServiceAvailable(cart))
    }

    @Test
    fun testFix4_CaseE_missingCoordinatesWithAvailableStatus() {
        val now = System.currentTimeMillis()
        val cart = GolfCartState(
            cartId = "cart_1",
            cartName = "Cart 1",
            latitude = null,
            longitude = null,
            driverStatus = "Available",
            isAvailable = true,
            status = GolfCartStatus.HALTED,
            lastUpdatedMillis = now,
            lastHeartbeatMillis = now,
            localReceiptTimestampMillis = now
        )

        assertFalse("Case E: Physical location must be unusable without coordinates", PassengerCartAvailabilityPolicy.isPhysicalLocationUsable(cart))
        assertFalse("Case E: Ride availability must NOT become true merely because driver says Available", PassengerCartAvailabilityPolicy.isRideServiceAvailable(cart))
    }

    @Test
    fun testFix4_CaseF_expiredGpsWithAvailableStatus() {
        val now = System.currentTimeMillis()
        val expiredTime = now - 600_000L // 10 minutes ago
        val cart = GolfCartState(
            cartId = "cart_1",
            cartName = "Cart 1",
            latitude = 25.2577810,
            longitude = 87.0418910,
            driverStatus = "Available",
            isAvailable = true,
            status = GolfCartStatus.HALTED,
            lastUpdatedMillis = now,
            locationTimestampMillis = expiredTime,
            lastHeartbeatMillis = now,
            localReceiptTimestampMillis = now
        )

        assertFalse("Case F: Physical location must be unusable when GPS is expired (> 180s)", PassengerCartAvailabilityPolicy.isPhysicalLocationUsable(cart))
        assertTrue("Case F: Ride availability follows operational state only (driver is actively on duty & Available)", PassengerCartAvailabilityPolicy.isRideServiceAvailable(cart))
    }

    @Test
    fun testFix4_CaseG_outsideCampusCoordinatesWithAvailableStatus() {
        val now = System.currentTimeMillis()
        val cart = GolfCartState(
            cartId = "cart_1",
            cartName = "Cart 1",
            latitude = 25.1000000, // Outside campus geofence
            longitude = 87.2000000,
            driverStatus = "Available",
            isAvailable = true,
            status = GolfCartStatus.HALTED,
            lastUpdatedMillis = now,
            locationTimestampMillis = now,
            lastHeartbeatMillis = now,
            localReceiptTimestampMillis = now
        )

        assertTrue("Cart must be detected as outside campus", cart.isOutsideCampus)
        assertFalse("Case G: Physical location must not be usable when cart is outside campus", PassengerCartAvailabilityPolicy.isPhysicalLocationUsable(cart))
        assertFalse("Case G: Ride availability remains governed by operational state (outside campus cart cannot accept campus rides)", PassengerCartAvailabilityPolicy.isRideServiceAvailable(cart))
    }

    @Test
    fun testFix4_CaseH_validGpsWithOperationallyUnavailable() {
        val now = System.currentTimeMillis()
        val cart = GolfCartState(
            cartId = "cart_1",
            cartName = "Cart 1",
            latitude = 25.2577810, // Inside campus
            longitude = 87.0418910,
            driverStatus = "On Trip",
            isAvailable = false,
            isTripActive = true,
            status = GolfCartStatus.MOVING,
            lastUpdatedMillis = now,
            locationTimestampMillis = now,
            lastHeartbeatMillis = now,
            localReceiptTimestampMillis = now
        )

        assertTrue("Case H: Physical location MUST remain usable when cart is busy on a trip", PassengerCartAvailabilityPolicy.isPhysicalLocationUsable(cart))
        assertFalse("Case H: Ride service must be unavailable when cart is busy on a trip", PassengerCartAvailabilityPolicy.isRideServiceAvailable(cart))
    }
}
