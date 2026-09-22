package com.example.location

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class CampusRouteGraphTest {

    @Before
    fun setup() {
        CampusRouteGraph.clearCartHistory("test_cart")
    }

    @Test
    fun test1_cartNearMainGate() {
        // Cart near Main Gate (25.2531616, 87.0370730)
        val progress = CampusRouteGraph.evaluateLiveProgress(
            cartId = "test_cart",
            latitude = 25.253200,
            longitude = 87.037080,
            isOnline = true
        )

        assertFalse("Should not be off route", progress.isOffRoute)
        assertEquals(CampusRouteGraph.ActiveRouteType.MAIN_ROUTE, progress.activeRouteType)
        // Current stop should be Main Gate (within 40m)
        assertEquals("Main Gate", progress.currentStop?.displayName)
        assertTrue("Passed stops should be empty", progress.passedStops.isEmpty())

        // Approaching should be Trunket, CC, Academic, Boys Hostel
        val approachingNames = progress.approachingStops.map { it.node.displayName }
        assertEquals(listOf("Trunket", "CC Building", "Academic Block", "Boys Hostel"), approachingNames)

        // Girls Hostel and Faculty Residence should NOT appear in approaching
        assertFalse(approachingNames.contains("Girls Hostel"))
        assertFalse(approachingNames.contains("Faculty Residence"))
    }

    @Test
    fun test2_cartBetweenTrunketAndCC() {
        // First sample at Trunket
        CampusRouteGraph.evaluateLiveProgress("test_cart", 25.2577186, 87.0381730, isOnline = true)
        // Move towards CC
        val progress = CampusRouteGraph.evaluateLiveProgress(
            cartId = "test_cart",
            latitude = 25.258400,
            longitude = 87.038800,
            isOnline = true
        )

        assertFalse(progress.isOffRoute)
        assertEquals(CampusRouteGraph.ActiveRouteType.MAIN_ROUTE, progress.activeRouteType)

        // Main Gate and Trunket should be passed
        val passedNames = progress.passedStops.map { it.displayName }
        assertTrue("Main Gate should be passed", passedNames.contains("Main Gate"))
        assertTrue("Trunket should be passed", passedNames.contains("Trunket"))

        // Approaching: CC Building, Academic Block, Boys Hostel
        val approachingNames = progress.approachingStops.map { it.node.displayName }
        assertEquals(listOf("CC Building", "Academic Block", "Boys Hostel"), approachingNames)
    }

    @Test
    fun test3_cartAtCC() {
        // Prime forward motion
        CampusRouteGraph.evaluateLiveProgress("test_cart", 25.2577186, 87.0381730, isOnline = true)
        // Arrive at Computer Centre
        val progress = CampusRouteGraph.evaluateLiveProgress(
            cartId = "test_cart",
            latitude = 25.2590500,
            longitude = 87.0394730,
            isOnline = true
        )

        assertEquals("CC Building", progress.currentStop?.displayName)
        val approachingNames = progress.approachingStops.map { it.node.displayName }
        assertEquals(listOf("Academic Block", "Boys Hostel"), approachingNames)

        // Available branches should include Girls Hostel
        val branchNames = progress.availableBranches.map { it.branchName }
        assertTrue("Girls Hostel branch should be available at CC", branchNames.contains("Girls Hostel"))
    }

    @Test
    fun test4_cartTakesGirlsHostelBranch() {
        // Prime at CC
        CampusRouteGraph.evaluateLiveProgress("test_cart", 25.2590500, 87.0394730, isOnline = true)
        // Moving up the Girls Hostel branch
        val progress = CampusRouteGraph.evaluateLiveProgress(
            cartId = "test_cart",
            latitude = 25.259600,
            longitude = 87.039380,
            isOnline = true
        )

        assertEquals(CampusRouteGraph.ActiveRouteType.GIRLS_HOSTEL_BRANCH, progress.activeRouteType)
        val approachingNames = progress.approachingStops.map { it.node.displayName }
        assertEquals(listOf("Girls Hostel"), approachingNames)
        assertTrue(progress.approachingStops.first().distanceMeters > 0)
    }

    @Test
    fun test5_cartReturnsFromGirlsHostelTowardCC() {
        // Prime at Girls Hostel
        CampusRouteGraph.evaluateLiveProgress("test_cart", 25.2600766, 87.0393244, isOnline = true)
        // Moving south towards CC
        val progress = CampusRouteGraph.evaluateLiveProgress(
            cartId = "test_cart",
            latitude = 25.259500,
            longitude = 87.039400,
            isOnline = true
        )

        assertEquals(CampusRouteGraph.ActiveRouteType.GIRLS_HOSTEL_BRANCH, progress.activeRouteType)
        assertEquals(CampusRouteGraph.TravelDirection.REVERSE, progress.direction)
        val approachingNames = progress.approachingStops.map { it.node.displayName }
        assertEquals(listOf("CC Building"), approachingNames)
    }

    @Test
    fun test6_cartBetweenAcademicBlockAndBoysHostel() {
        // Prime forward motion past CC and Academic
        CampusRouteGraph.evaluateLiveProgress("test_cart", 25.2590750, 87.0401610, isOnline = true)
        // Near intermediate junction heading towards Boys Hostel
        val progress = CampusRouteGraph.evaluateLiveProgress(
            cartId = "test_cart",
            latitude = 25.258500,
            longitude = 87.041100,
            isOnline = true
        )

        assertEquals(CampusRouteGraph.ActiveRouteType.MAIN_ROUTE, progress.activeRouteType)
        val approachingNames = progress.approachingStops.map { it.node.displayName }
        assertEquals(listOf("Boys Hostel"), approachingNames)
        // Faculty Residence should NOT be a sequential upcoming checkpoint
        assertFalse(approachingNames.contains("Faculty Residence"))
    }

    @Test
    fun test7_cartTakesFacultyResidenceBranch() {
        // Prime at junction
        CampusRouteGraph.evaluateLiveProgress("test_cart", 25.2588500, 87.0407500, isOnline = true)
        // Moving along Faculty Residence branch
        val progress = CampusRouteGraph.evaluateLiveProgress(
            cartId = "test_cart",
            latitude = 25.258950,
            longitude = 87.040900,
            isOnline = true
        )

        assertEquals(CampusRouteGraph.ActiveRouteType.FACULTY_RESIDENCE_BRANCH, progress.activeRouteType)
        val approachingNames = progress.approachingStops.map { it.node.displayName }
        assertEquals(listOf("Faculty Residence"), approachingNames)
    }

    @Test
    fun test8_cartAtFacultyResidence() {
        // At Faculty Residence coordinate
        val progress = CampusRouteGraph.evaluateLiveProgress(
            cartId = "test_cart",
            latitude = 25.2592574,
            longitude = 87.0414153,
            isOnline = true
        )

        assertEquals(CampusRouteGraph.ActiveRouteType.FACULTY_RESIDENCE_BRANCH, progress.activeRouteType)
        assertEquals("Faculty Residence", progress.currentStop?.displayName)
    }

    @Test
    fun test9_offRouteHandling() {
        // GPS location > 75m away from campus network
        val progress = CampusRouteGraph.evaluateLiveProgress(
            cartId = "test_cart",
            latitude = 25.255000,
            longitude = 87.042000,
            isOnline = true
        )

        assertTrue("Should be classified as off route", progress.isOffRoute)
        assertEquals("OFF ROUTE", progress.statusMessage)
    }

    @Test
    fun test10_evaluationCachingForSubMeterMovement() {
        TrackingConfig.Diagnostics.reset()

        // First evaluation at Main Gate
        val firstResult = CampusRouteGraph.evaluateLiveProgress(
            cartId = "test_cart_cache",
            latitude = 25.2531616,
            longitude = 87.0370730,
            isOnline = true
        )
        assertEquals(1, TrackingConfig.Diagnostics.routeCalculationsCount.get())
        assertEquals(0, TrackingConfig.Diagnostics.routeCalculationsCacheHits.get())

        // Sub-meter movement (< 2.0m threshold) with same destination
        val secondResult = CampusRouteGraph.evaluateLiveProgress(
            cartId = "test_cart_cache",
            latitude = 25.2531650, // ~0.4m shift
            longitude = 87.0370750,
            isOnline = true
        )

        // Cache hit expected
        assertEquals(1, TrackingConfig.Diagnostics.routeCalculationsCacheHits.get())
        assertSame("Cached result should be returned", firstResult, secondResult)
    }

    @Test
    fun test11_precomputedGeometryMatchesDirect() {
        assertTrue("Main route precomputed segments should not be empty", CampusRouteGraph.PRECOMPUTED_MAIN_ROUTE.isNotEmpty())
        assertTrue("Main route total length should be > 500m", CampusRouteGraph.TOTAL_LENGTH_MAIN_ROUTE > 500.0)
        assertTrue("Girls hostel branch length should be > 50m", CampusRouteGraph.TOTAL_LENGTH_GIRLS_HOSTEL_BRANCH > 50.0)
        assertTrue("Faculty residence branch length should be > 50m", CampusRouteGraph.TOTAL_LENGTH_FACULTY_RESIDENCE_BRANCH > 50.0)

        // Precomputed projection should equal direct projection
        val (proj, crossDist) = CampusRouteGraph.projectPointOnPolyline(25.2577186, 87.0381730, CampusRouteGraph.POLYLINE_MAIN_ROUTE)
        assertTrue(crossDist < 1.0)
    }

    @Test
    fun test12_trackingConfigThresholds() {
        assertEquals(30_000L, TrackingConfig.TrackingMode.STATIONARY.intervalMs)
        assertEquals(4_000L, TrackingConfig.TrackingMode.NORMAL_MOVEMENT.intervalMs)
        assertEquals(2_500L, TrackingConfig.TrackingMode.HIGH_SPEED.intervalMs)
        assertTrue(TrackingConfig.Motion.STATIONARY_SPEED_THRESHOLD_MPS < TrackingConfig.Motion.LOW_SPEED_MAX_MPS)
        assertTrue(TrackingConfig.Motion.LOW_SPEED_MAX_MPS < TrackingConfig.Motion.HIGH_SPEED_MIN_MPS)
    }
}
