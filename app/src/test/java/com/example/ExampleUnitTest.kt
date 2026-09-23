package com.example

import com.example.location.CampusLandmarkZone
import com.example.location.CampusLandmarkZone.Companion.CartDirection
import com.example.location.CampusLandmarkZone.Companion.RouteState
import com.example.location.CampusLandmarkZone.Companion.StopVisualState
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ExampleUnitTest {

    @Before
    fun setup() {
        CampusLandmarkZone.clearRollingHistory()
    }

    @Test
    fun testExactCoordinates_MatchAuthoritativeGoogleMaps() {
        // Stop 1: MAIN GATE
        assertEquals(25.2531616, CampusLandmarkZone.GATE.latitude, 0.0000001)
        assertEquals(87.0370730, CampusLandmarkZone.GATE.longitude, 0.0000001)
        assertEquals("Main Gate", CampusLandmarkZone.GATE.displayName)
        assertTrue(CampusLandmarkZone.GATE.fullAddress.contains("IIIT BHAGALPUR Main Gate"))

        // Stop 2: TRUNKET
        assertEquals(25.2577186, CampusLandmarkZone.TRUNKET.latitude, 0.0000001)
        assertEquals(87.0381730, CampusLandmarkZone.TRUNKET.longitude, 0.0000001)
        assertEquals("Trunket", CampusLandmarkZone.TRUNKET.displayName)
        assertTrue(CampusLandmarkZone.TRUNKET.fullAddress.contains("Trunket"))

        // Stop 3: COMPUTER CENTRE
        assertEquals(25.2590500, CampusLandmarkZone.COMPUTER_CENTRE.latitude, 0.0000001)
        assertEquals(87.0394730, CampusLandmarkZone.COMPUTER_CENTRE.longitude, 0.0000001)
        assertEquals("Computer Centre", CampusLandmarkZone.COMPUTER_CENTRE.displayName)
        assertTrue(CampusLandmarkZone.COMPUTER_CENTRE.fullAddress.contains("Computer Centre"))

        // Stop 4: HOSTEL
        assertEquals(25.2577810, CampusLandmarkZone.HOSTEL.latitude, 0.0000001)
        assertEquals(87.0418910, CampusLandmarkZone.HOSTEL.longitude, 0.0000001)
        assertEquals("Hostel", CampusLandmarkZone.HOSTEL.displayName)
        assertTrue(CampusLandmarkZone.HOSTEL.fullAddress.contains("Hostel"))
    }

    @Test
    fun testDriverAtBoysHostel_IdentifiedAsAtHostel() {
        // Exact Hostel Google Maps Coordinates
        val result = CampusLandmarkZone.evaluateRoutePosition(
            latitude = 25.2577810,
            longitude = 87.0418910,
            bearing = 0f,
            speedKmH = 0,
            accuracy = 8.0f
        )
        assertNotNull(result)
        assertEquals(CampusLandmarkZone.HOSTEL, result?.primaryLandmark)
        assertEquals(true, result?.isAtLandmark)
        assertEquals(RouteState.AT_HOSTEL, result?.routeState)
        assertEquals(RouteState.AT_HOSTEL.label, result?.driverDetailedLocation)
        assertEquals(3.0f, result?.routeProgressFloat ?: 0f, 0.01f)

        val hostelStop = result?.timelineStops?.find { it.landmark == CampusLandmarkZone.HOSTEL }
        assertNotNull(hostelStop)
        assertEquals(StopVisualState.AT_STOP, hostelStop?.visualState)
        assertEquals(true, hostelStop?.isAtThisStop)

        val gateStop = result?.timelineStops?.find { it.landmark == CampusLandmarkZone.GATE }
        assertEquals(StopVisualState.COMPLETED, gateStop?.visualState)
    }

    @Test
    fun testStationaryAtHostel_NoFluctuationUnderGpsJitter() {
        // Step 1: Initial location at Hostel
        val r1 = CampusLandmarkZone.evaluateRoutePosition(
            latitude = 25.2577810,
            longitude = 87.0418910,
            speedKmH = 0,
            accuracy = 10f,
            timestamp = 1000L
        )
        assertEquals(RouteState.AT_HOSTEL.label, r1?.driverDetailedLocation)

        // Jitter 1: +4 meters noise
        val r2 = CampusLandmarkZone.evaluateRoutePosition(
            latitude = 25.2578170, // ~4m north
            longitude = 87.0418910,
            speedKmH = 0,
            accuracy = 12f,
            timestamp = 3000L
        )
        assertEquals(RouteState.AT_HOSTEL.label, r2?.driverDetailedLocation)
        assertEquals(3.0f, r2?.routeProgressFloat ?: 0f, 0.01f)

        // Jitter 2: -6 meters noise
        val r3 = CampusLandmarkZone.evaluateRoutePosition(
            latitude = 25.2577270, // ~6m south
            longitude = 87.0418910,
            speedKmH = 0,
            accuracy = 15f,
            timestamp = 5000L
        )
        assertEquals(RouteState.AT_HOSTEL.label, r3?.driverDetailedLocation)

        // Jitter 3: +3 meters noise
        val r4 = CampusLandmarkZone.evaluateRoutePosition(
            latitude = 25.2578080,
            longitude = 87.0419200,
            speedKmH = 0,
            accuracy = 14f,
            timestamp = 7000L
        )
        assertEquals(RouteState.AT_HOSTEL.label, r4?.driverDetailedLocation)

        // Jitter 4: -5 meters noise
        val r5 = CampusLandmarkZone.evaluateRoutePosition(
            latitude = 25.2577360,
            longitude = 87.0418600,
            speedKmH = 0,
            accuracy = 18f,
            timestamp = 9000L
        )
        assertEquals(RouteState.AT_HOSTEL.label, r5?.driverDetailedLocation)
    }

    @Test
    fun testGpsAccuracyFilter_SuppressesLowQualityGpsReading() {
        // Established valid location
        val valid = CampusLandmarkZone.evaluateRoutePosition(
            latitude = 25.2577810,
            longitude = 87.0418910,
            accuracy = 8.0f,
            timestamp = 1000L
        )
        assertEquals(RouteState.AT_HOSTEL.label, valid?.driverDetailedLocation)

        // Low accuracy reading (e.g. 45m accuracy)
        val poorAccuracy = CampusLandmarkZone.evaluateRoutePosition(
            latitude = 25.2590500, // Jumped to Computer Centre falsely due to bad GPS fix
            longitude = 87.0394730,
            accuracy = 45.0f, // Poor accuracy > 30m
            timestamp = 3000L
        )
        // Should ignore the jump and retain the confirmed stable status
        assertEquals(RouteState.AT_HOSTEL.label, poorAccuracy?.driverDetailedLocation)
    }

    @Test
    fun testHysteresisLocksAtHostelUntilMeaningfulExitDistance() {
        // At Hostel
        CampusLandmarkZone.evaluateRoutePosition(
            latitude = 25.2577810,
            longitude = 87.0418910,
            speedKmH = 0,
            timestamp = 1000L
        )

        // Move 70m away from Hostel center (inside exit radius 90m)
        val insideExitRadius = CampusLandmarkZone.evaluateRoutePosition(
            latitude = 25.2577810,
            longitude = 87.0411960,
            speedKmH = 0,
            timestamp = 3000L
        )
        assertEquals(RouteState.AT_HOSTEL.label, insideExitRadius?.driverDetailedLocation)

        // Now move > 100m away with confirmed speed
        CampusLandmarkZone.evaluateRoutePosition(
            latitude = 25.258300,
            longitude = 87.040800,
            speedKmH = 12,
            timestamp = 5000L
        )
        CampusLandmarkZone.evaluateRoutePosition(
            latitude = 25.258500,
            longitude = 87.040400,
            speedKmH = 12,
            timestamp = 7000L
        )
        val movingAway = CampusLandmarkZone.evaluateRoutePosition(
            latitude = 25.258300,
            longitude = 87.040900,
            speedKmH = 12,
            timestamp = 9000L
        )
        assertEquals(RouteState.BETWEEN_COMPUTER_CENTRE_AND_HOSTEL.label, movingAway?.driverDetailedLocation)
    }

    @Test
    fun testStopHalfway_RemainsStableApproachingComputerCentreWithoutOscillation() {
        // Step 1: Start at Hostel
        CampusLandmarkZone.evaluateRoutePosition(
            latitude = 25.2577810,
            longitude = 87.0418910,
            speedKmH = 0,
            timestamp = 1000L
        )

        // Step 2: Drive towards Computer Centre
        CampusLandmarkZone.evaluateRoutePosition(latitude = 25.258000, longitude = 87.041500, speedKmH = 12, timestamp = 3000L)
        CampusLandmarkZone.evaluateRoutePosition(latitude = 25.258200, longitude = 87.041100, speedKmH = 12, timestamp = 5000L)

        // Step 3: Stop halfway between Computer Centre and Hostel (Speed = 0)
        val halfway1 = CampusLandmarkZone.evaluateRoutePosition(
            latitude = 25.258300,
            longitude = 87.040900,
            speedKmH = 0,
            timestamp = 9000L
        )
        assertEquals(RouteState.BETWEEN_COMPUTER_CENTRE_AND_HOSTEL.label, halfway1?.driverDetailedLocation)

        // Step 4: GPS noise occurs while stopped halfway (+/- 5m jitter)
        val noise1 = CampusLandmarkZone.evaluateRoutePosition(
            latitude = 25.258340,
            longitude = 87.040920,
            speedKmH = 0,
            timestamp = 11000L
        )
        assertEquals(RouteState.BETWEEN_COMPUTER_CENTRE_AND_HOSTEL.label, noise1?.driverDetailedLocation)

        val noise2 = CampusLandmarkZone.evaluateRoutePosition(
            latitude = 25.258260,
            longitude = 87.040880,
            speedKmH = 0,
            timestamp = 13000L
        )
        assertEquals(RouteState.BETWEEN_COMPUTER_CENTRE_AND_HOSTEL.label, noise2?.driverDetailedLocation)

        // Must NOT flap to "Crossed Hostel" or "Approaching Hostel"
        val hostelStop = noise2?.timelineStops?.find { it.landmark == CampusLandmarkZone.HOSTEL }
        val ccStop = noise2?.timelineStops?.find { it.landmark == CampusLandmarkZone.COMPUTER_CENTRE }
        assertEquals(StopVisualState.COMPLETED, hostelStop?.visualState)
        assertEquals(StopVisualState.NEXT_STOP, ccStop?.visualState)
    }

    @Test
    fun testArrivingAndCrossingComputerCentreTowardsTrunkut() {
        // Step 1: Moving from Hostel towards CC
        CampusLandmarkZone.evaluateRoutePosition(latitude = 25.258500, longitude = 87.040400, speedKmH = 12, timestamp = 1000L)
        CampusLandmarkZone.evaluateRoutePosition(latitude = 25.258800, longitude = 87.040000, speedKmH = 12, timestamp = 3000L)

        // Step 2: Enter Computer Centre arrival radius (enterRadius = 45m)
        val atCC = CampusLandmarkZone.evaluateRoutePosition(
            latitude = 25.2590500,
            longitude = 87.0394730,
            speedKmH = 0,
            timestamp = 5000L
        )
        assertEquals(RouteState.AT_COMPUTER_CENTRE.label, atCC?.driverDetailedLocation)
        assertEquals(true, atCC?.isAtLandmark)

        // Step 3: Depart Computer Centre heading towards Trunket (98m away > 60m approach threshold)
        val approachingTrunkut = CampusLandmarkZone.evaluateRoutePosition(
            latitude = 25.258400,
            longitude = 87.038800,
            speedKmH = 10,
            timestamp = 7000L
        )
        assertEquals(RouteState.BETWEEN_TRUNKET_AND_COMPUTER_CENTRE.label, approachingTrunkut?.driverDetailedLocation)
        val ccTimeline = approachingTrunkut?.timelineStops?.find { it.landmark == CampusLandmarkZone.COMPUTER_CENTRE }
        val trunkutTimeline = approachingTrunkut?.timelineStops?.find { it.landmark == CampusLandmarkZone.TRUNKET }
        assertEquals(StopVisualState.COMPLETED, ccTimeline?.visualState)
        assertEquals(StopVisualState.NEXT_STOP, trunkutTimeline?.visualState)

        // Step 4: Arrive inside Trunket arrival zone
        val atTrunkut = CampusLandmarkZone.evaluateRoutePosition(
            latitude = 25.2577186,
            longitude = 87.0381730,
            speedKmH = 0,
            timestamp = 9000L
        )
        assertEquals(RouteState.AT_TRUNKET.label, atTrunkut?.driverDetailedLocation)
        assertEquals(true, atTrunkut?.isAtLandmark)
    }

    @Test
    fun testDriverAtComputerCentre_IdentifiedAsAtComputerCentre() {
        // Exact Computer Centre Google Maps Coordinates
        val result = CampusLandmarkZone.evaluateRoutePosition(
            latitude = 25.2590500,
            longitude = 87.0394730,
            bearing = 180f,
            speedKmH = 0,
            accuracy = 8.0f
        )
        assertNotNull(result)
        assertEquals(CampusLandmarkZone.COMPUTER_CENTRE, result?.primaryLandmark)
        assertEquals(true, result?.isAtLandmark)
        assertEquals(RouteState.AT_COMPUTER_CENTRE.label, result?.driverDetailedLocation)
        assertEquals(2.0f, result?.routeProgressFloat ?: 0f, 0.01f)
    }

    @Test
    fun testDriverAtTrunkut_IdentifiedAsAtTrunkut() {
        // Exact Trunket Google Maps Coordinates
        val result = CampusLandmarkZone.evaluateRoutePosition(
            latitude = 25.2577186,
            longitude = 87.0381730,
            bearing = 180f,
            speedKmH = 0,
            accuracy = 8.0f
        )
        assertNotNull(result)
        assertEquals(CampusLandmarkZone.TRUNKET, result?.primaryLandmark)
        assertEquals(true, result?.isAtLandmark)
        assertEquals(RouteState.AT_TRUNKET.label, result?.driverDetailedLocation)
        assertEquals(1.0f, result?.routeProgressFloat ?: 0f, 0.01f)
    }

    @Test
    fun testDriverAtMainGate_IdentifiedAsAtMainGate() {
        // Exact Main Gate Google Maps Coordinates
        val result = CampusLandmarkZone.evaluateRoutePosition(
            latitude = 25.2531616,
            longitude = 87.0370730,
            bearing = 180f,
            speedKmH = 0,
            accuracy = 8.0f
        )
        assertNotNull(result)
        assertEquals(CampusLandmarkZone.GATE, result?.primaryLandmark)
        assertEquals(true, result?.isAtLandmark)
        assertEquals(true, result?.isAtGate)
        assertEquals(RouteState.AT_MAIN_GATE.label, result?.driverDetailedLocation)
        assertEquals(0.0f, result?.routeProgressFloat ?: 0f, 0.01f)
    }

    @Test
    fun testCampusTimeUtils_KolkataTimezoneCalculation() {
        // Epoch for 2026-08-22 18:30:00 UTC -> which is 2026-08-23 00:00:00 IST (+5:30)
        val epochNearMidnightUtc = 1787423400000L // 2026-08-22 18:30:00 GMT
        val kolkataDate = com.example.util.CampusTimeUtils.getTodayCampusDate(epochNearMidnightUtc)
        assertEquals("2026-08-23", kolkataDate)

        // Stored date matching today
        assertTrue(com.example.util.CampusTimeUtils.isTodayInCampusTimezone("2026-08-23", epochNearMidnightUtc))
        // Stored date from yesterday (consumed yesterday, reset today)
        assertFalse(com.example.util.CampusTimeUtils.isTodayInCampusTimezone("2026-08-22", epochNearMidnightUtc))
        // Null / empty stored date
        assertFalse(com.example.util.CampusTimeUtils.isTodayInCampusTimezone(null, epochNearMidnightUtc))
        assertFalse(com.example.util.CampusTimeUtils.isTodayInCampusTimezone("", epochNearMidnightUtc))
    }

    @Test
    fun testMainGateGeofence_ConstantsAndCoordinatesPreserved() {
        // Confirm Main Gate coordinates remain exactly as required
        assertEquals(25.2531616, com.example.location.GeofenceManager.GATE_LAT, 0.0000001)
        assertEquals(87.0370730, com.example.location.GeofenceManager.GATE_LNG, 0.0000001)
        // Confirm the 70.0m production geofence radius rule is intact
        assertEquals(70.0, com.example.location.GeofenceManager.MAX_GEOFENCE_METERS, 0.0001)
    }

    @Test
    fun testGeofenceValidation_TestModeVsProductionRule() {
        val gateLat = com.example.location.GeofenceManager.GATE_LAT
        val gateLng = com.example.location.GeofenceManager.GATE_LNG

        // Exact gate location (0m away)
        val distanceAtGate = com.example.location.GeofenceManager.calculateDistanceMeters(gateLat, gateLng, gateLat, gateLng)
        assertEquals(0.0, distanceAtGate, 0.01)
        assertTrue(distanceAtGate <= com.example.location.GeofenceManager.MAX_GEOFENCE_METERS)

        // Location 500m away (e.g. Boys Hostel)
        val hostelLat = 25.2577810
        val hostelLng = 87.0418910
        val distanceAtHostel = com.example.location.GeofenceManager.calculateDistanceMeters(hostelLat, hostelLng, gateLat, gateLng)
        assertTrue(distanceAtHostel > 70.0) // Confirmed outside 70m in production

        // In production mode, TEST_MODE_ALLOW_ANY_PICKUP_LOCATION is false:
        assertFalse(com.example.location.TEST_MODE_ALLOW_ANY_PICKUP_LOCATION)
        assertTrue(com.example.location.GeofenceManager.isWithinGeofence(gateLat, gateLng))
        assertFalse(com.example.location.GeofenceManager.isWithinGeofence(hostelLat, hostelLng))
    }

    @Test
    fun testCampusCartConfig_DriverPhoneNumbers() {
        // Driver 1 (Cart 1): 9572494687
        val cart1Phone = com.example.data.model.CampusCartConfig.getCartPhoneNumber("cart_1")
        assertEquals("+919572494687", cart1Phone)
        val cart1Display = com.example.data.model.CampusCartConfig.getCartDisplayNumber("cart_1")
        assertEquals("+91 95724 94687", cart1Display)

        // Driver 2 (Cart 2): 9336794056
        val cart2Phone = com.example.data.model.CampusCartConfig.getCartPhoneNumber("cart_2")
        assertEquals("+919336794056", cart2Phone)
        val cart2Display = com.example.data.model.CampusCartConfig.getCartDisplayNumber("cart_2")
        assertEquals("+91 93367 94056", cart2Display)
    }
}
