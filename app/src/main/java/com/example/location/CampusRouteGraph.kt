package com.example.location

import com.example.data.model.GolfCartState
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.*

/**
 * Campus Route Graph Engine for IIIT Bhagalpur Campus Ride.
 *
 * Implements non-linear topological route tracking with:
 * 1. Main Route: Main Gate -> Trunket -> CC Building -> Academic Block -> [Junction] -> Boys Hostel
 * 2. Girls Hostel Branch: Computer Centre -> Girls Hostel
 * 3. Faculty Residence Branch: [Intermediate Junction] -> Faculty Residence
 *
 * Provides:
 * - Dynamic Passed / Current / Approaching checkpoints
 * - Accurate distance calculation along actual polyline geometry
 * - Bidirectional travel tracking (Forward & Reverse)
 * - Branch relevancy detection when approaching junctions
 * - Configurable thresholds for Arrival (40m) and Off-Route (75m)
 */
object CampusRouteGraph {

// Configurable thresholds linked to centralized TrackingConfig
    const val ARRIVAL_THRESHOLD_METERS = TrackingConfig.Route.ARRIVAL_THRESHOLD_M
    const val OFF_ROUTE_THRESHOLD_METERS = TrackingConfig.Route.OFF_ROUTE_THRESHOLD_M
    const val BRANCH_PROXIMITY_THRESHOLD_METERS = TrackingConfig.Route.BRANCH_PROXIMITY_THRESHOLD_M

    // Geographic Coordinates
    val MAIN_GATE_COORD = Pair(25.2531616, 87.0370730)
    val TRUNKET_COORD = Pair(25.2577186, 87.0381730)
    val COMPUTER_CENTRE_COORD = Pair(25.2590500, 87.0394730)
    val ACADEMIC_BLOCK_COORD = Pair(25.2590750, 87.0401610)
    // Intermediate junction between Academic Block and Boys Hostel for Faculty Residence road
    val FACULTY_JUNCTION_COORD = Pair(25.2588500, 87.0407500)
    val BOYS_HOSTEL_COORD = Pair(25.2577810, 87.0418910)

    val GIRLS_HOSTEL_COORD = Pair(25.26007663539964, 87.0393243817079)
    val FACULTY_RESIDENCE_COORD = Pair(25.259257426319685, 87.04141528824039)

    data class RouteNode(
        val id: String,
        val displayName: String,
        val latitude: Double,
        val longitude: Double,
        val isCheckpoint: Boolean = true
    )

    val NODE_MAIN_GATE = RouteNode("MAIN_GATE", "Main Gate", MAIN_GATE_COORD.first, MAIN_GATE_COORD.second)
    val NODE_TRUNKET = RouteNode("TRUNKET", "Trunket", TRUNKET_COORD.first, TRUNKET_COORD.second)
    val NODE_CC = RouteNode("COMPUTER_CENTRE", "CC Building", COMPUTER_CENTRE_COORD.first, COMPUTER_CENTRE_COORD.second)
    val NODE_ACADEMIC = RouteNode("ACADEMIC_BLOCK", "Academic Block", ACADEMIC_BLOCK_COORD.first, ACADEMIC_BLOCK_COORD.second)
    val NODE_FACULTY_JUNCTION = RouteNode("FACULTY_JUNCTION", "Faculty Junction", FACULTY_JUNCTION_COORD.first, FACULTY_JUNCTION_COORD.second, isCheckpoint = false)
    val NODE_BOYS_HOSTEL = RouteNode("BOYS_HOSTEL", "Boys Hostel", BOYS_HOSTEL_COORD.first, BOYS_HOSTEL_COORD.second)

    val NODE_GIRLS_HOSTEL = RouteNode("GIRLS_HOSTEL", "Girls Hostel", GIRLS_HOSTEL_COORD.first, GIRLS_HOSTEL_COORD.second)
    val NODE_FACULTY_RESIDENCE = RouteNode("FACULTY_RESIDENCE", "Faculty Residence", FACULTY_RESIDENCE_COORD.first, FACULTY_RESIDENCE_COORD.second)

    // Polyline sequences representing the road geometry
    val POLYLINE_MAIN_ROUTE = listOf(
        MAIN_GATE_COORD,
        TRUNKET_COORD,
        COMPUTER_CENTRE_COORD,
        ACADEMIC_BLOCK_COORD,
        FACULTY_JUNCTION_COORD,
        BOYS_HOSTEL_COORD
    )

    val CHECKPOINTS_MAIN_ROUTE = listOf(
        NODE_MAIN_GATE,
        NODE_TRUNKET,
        NODE_CC,
        NODE_ACADEMIC,
        NODE_BOYS_HOSTEL
    )

    val POLYLINE_GIRLS_HOSTEL_BRANCH = listOf(
        COMPUTER_CENTRE_COORD,
        GIRLS_HOSTEL_COORD
    )

    val POLYLINE_FACULTY_RESIDENCE_BRANCH = listOf(
        FACULTY_JUNCTION_COORD,
        FACULTY_RESIDENCE_COORD
    )

    enum class ActiveRouteType {
        MAIN_ROUTE,
        GIRLS_HOSTEL_BRANCH,
        FACULTY_RESIDENCE_BRANCH,
        OFF_ROUTE
    }

    enum class TravelDirection {
        FORWARD,   // Main Gate -> Hostel, CC -> Girls Hostel, Junction -> Faculty Residence
        REVERSE,   // Hostel -> Main Gate, Girls Hostel -> CC, Faculty Residence -> Junction
        UNKNOWN
    }

    data class CheckpointProgress(
        val node: RouteNode,
        val distanceMeters: Double,
        val formattedDistance: String
    )

    data class BranchProgress(
        val branchName: String,
        val destinationNode: RouteNode,
        val junctionNode: RouteNode,
        val distanceMeters: Double,
        val formattedDistance: String
    )

    data class LiveRouteProgress(
        val activeRouteType: ActiveRouteType,
        val direction: TravelDirection,
        val isOffRoute: Boolean,
        val isLocationAvailable: Boolean,
        val currentStop: RouteNode?,
        val passedStops: List<RouteNode>,
        val approachingStops: List<CheckpointProgress>,
        val availableBranches: List<BranchProgress>,
        val statusMessage: String
    )

    // Precomputed segment geometry for zero-trig projection performance
    data class PrecomputedSegment(
        val aLat: Double, val aLng: Double,
        val bLat: Double, val bLng: Double,
        val segLengthMeters: Double,
        val accumulatedStartMeters: Double,
        val vx: Double,
        val vy: Double,
        val lenSq: Double
    )

    private fun precomputePolyline(polyline: List<Pair<Double, Double>>): Pair<List<PrecomputedSegment>, Double> {
        val cosLat = cos(Math.toRadians(25.258))
        val segments = ArrayList<PrecomputedSegment>(polyline.size - 1)
        var accumulated = 0.0

        for (i in 0 until polyline.size - 1) {
            val a = polyline[i]
            val b = polyline[i + 1]
            val segLen = distanceMeters(a.first, a.second, b.first, b.second)
            val vx = (b.second - a.second) * 111320.0 * cosLat
            val vy = (b.first - a.first) * 110540.0
            val lenSq = vx * vx + vy * vy

            segments.add(
                PrecomputedSegment(
                    aLat = a.first, aLng = a.second,
                    bLat = b.first, bLng = b.second,
                    segLengthMeters = segLen,
                    accumulatedStartMeters = accumulated,
                    vx = vx, vy = vy, lenSq = lenSq
                )
            )
            accumulated += segLen
        }
        return Pair(segments, accumulated)
    }

    val PRECOMPUTED_MAIN_ROUTE: List<PrecomputedSegment>
    val TOTAL_LENGTH_MAIN_ROUTE: Double

    val PRECOMPUTED_GIRLS_HOSTEL_BRANCH: List<PrecomputedSegment>
    val TOTAL_LENGTH_GIRLS_HOSTEL_BRANCH: Double

    val PRECOMPUTED_FACULTY_RESIDENCE_BRANCH: List<PrecomputedSegment>
    val TOTAL_LENGTH_FACULTY_RESIDENCE_BRANCH: Double

    init {
        val (mainSegs, mainTotal) = precomputePolyline(POLYLINE_MAIN_ROUTE)
        PRECOMPUTED_MAIN_ROUTE = mainSegs
        TOTAL_LENGTH_MAIN_ROUTE = mainTotal

        val (ghSegs, ghTotal) = precomputePolyline(POLYLINE_GIRLS_HOSTEL_BRANCH)
        PRECOMPUTED_GIRLS_HOSTEL_BRANCH = ghSegs
        TOTAL_LENGTH_GIRLS_HOSTEL_BRANCH = ghTotal

        val (frSegs, frTotal) = precomputePolyline(POLYLINE_FACULTY_RESIDENCE_BRANCH)
        PRECOMPUTED_FACULTY_RESIDENCE_BRANCH = frSegs
        TOTAL_LENGTH_FACULTY_RESIDENCE_BRANCH = frTotal
    }

    // Direction tracking cache per cart
    private class CartTrackerState {
        var lastLat: Double? = null
        var lastLng: Double? = null
        var lastRouteProgress: Double = 0.0
        var direction: TravelDirection = TravelDirection.FORWARD
        var activeRouteType: ActiveRouteType = ActiveRouteType.MAIN_ROUTE

        // Evaluation Cache
        var cachedProgress: LiveRouteProgress? = null
        var lastEvaluatedLat: Double = 0.0
        var lastEvaluatedLng: Double = 0.0
        var lastDestination: String? = null
    }

    private val cartTrackers = ConcurrentHashMap<String, CartTrackerState>()

    private fun getTracker(cartId: String): CartTrackerState {
        return cartTrackers.getOrPut(cartId) { CartTrackerState() }
    }

    /**
     * Clear cached history for a cart.
     */
    fun clearCartHistory(cartId: String = "cart_1") {
        cartTrackers.remove(cartId)
    }

    /**
     * Main entry point to compute Live Route Progress for a golf cart.
     */
    fun evaluateLiveProgress(
        cartId: String,
        latitude: Double?,
        longitude: Double?,
        bearing: Float? = null,
        relativeMovement: String? = null,
        selectedDestination: String? = null,
        isOnline: Boolean = true
    ): LiveRouteProgress {
        if (!isOnline || latitude == null || longitude == null || latitude == 0.0 || longitude == 0.0) {
            return LiveRouteProgress(
                activeRouteType = ActiveRouteType.OFF_ROUTE,
                direction = TravelDirection.UNKNOWN,
                isOffRoute = false,
                isLocationAvailable = false,
                currentStop = null,
                passedStops = emptyList(),
                approachingStops = emptyList(),
                availableBranches = emptyList(),
                statusMessage = "LOCATION UNAVAILABLE"
            )
        }

        val tracker = getTracker(cartId)

        // Performance Optimization: Check evaluation cache
        // If coordinate delta is < MIN_RECALCULATION_DELTA_M (2.0m) and destination hasn't changed, reuse cached evaluation
        val cached = tracker.cachedProgress
        if (cached != null && !cached.isOffRoute && cached.isLocationAvailable) {
            val delta = distanceMeters(latitude, longitude, tracker.lastEvaluatedLat, tracker.lastEvaluatedLng)
            if (delta < TrackingConfig.Route.MIN_RECALCULATION_DELTA_M && selectedDestination == tracker.lastDestination) {
                TrackingConfig.Diagnostics.routeCalculationsCacheHits.incrementAndGet()
                return cached
            }
        }
        TrackingConfig.Diagnostics.routeCalculationsCount.incrementAndGet()

        // 1. Project onto all route segments to check minimum distance and active route
        val (mainProj, mainCrossDist) = projectPointOnPolyline(latitude, longitude, POLYLINE_MAIN_ROUTE)
        val (ghProj, ghCrossDist) = projectPointOnPolyline(latitude, longitude, POLYLINE_GIRLS_HOSTEL_BRANCH)
        val (frProj, frCrossDist) = projectPointOnPolyline(latitude, longitude, POLYLINE_FACULTY_RESIDENCE_BRANCH)

        val minCrossDist = minOf(mainCrossDist, ghCrossDist, frCrossDist)

        // Off-route check
        if (minCrossDist > OFF_ROUTE_THRESHOLD_METERS) {
            val offRouteProgress = LiveRouteProgress(
                activeRouteType = ActiveRouteType.OFF_ROUTE,
                direction = tracker.direction,
                isOffRoute = true,
                isLocationAvailable = true,
                currentStop = null,
                passedStops = emptyList(),
                approachingStops = emptyList(),
                availableBranches = emptyList(),
                statusMessage = "OFF ROUTE"
            )
            tracker.cachedProgress = offRouteProgress
            tracker.lastEvaluatedLat = latitude
            tracker.lastEvaluatedLng = longitude
            tracker.lastDestination = selectedDestination
            return offRouteProgress
        }

        // 2. Determine Active Route
        // Check distance to branch destinations to distinguish branch travel
        val distToGirlsHostel = distanceMeters(latitude, longitude, GIRLS_HOSTEL_COORD.first, GIRLS_HOSTEL_COORD.second)
        val distToFacultyResidence = distanceMeters(latitude, longitude, FACULTY_RESIDENCE_COORD.first, FACULTY_RESIDENCE_COORD.second)
        val distToCC = distanceMeters(latitude, longitude, COMPUTER_CENTRE_COORD.first, COMPUTER_CENTRE_COORD.second)
        val distToJunction = distanceMeters(latitude, longitude, FACULTY_JUNCTION_COORD.first, FACULTY_JUNCTION_COORD.second)

        val activeRouteType: ActiveRouteType = when {
            // Cart is clearly on Girls Hostel branch (closer to branch than main, or close to Girls Hostel)
            (ghCrossDist <= 28.0 && distToGirlsHostel < distToCC) ||
            (distToGirlsHostel <= ARRIVAL_THRESHOLD_METERS) -> ActiveRouteType.GIRLS_HOSTEL_BRANCH

            // Cart is clearly on Faculty Residence branch
            (frCrossDist <= 28.0 && distToFacultyResidence < distToJunction) ||
            (distToFacultyResidence <= ARRIVAL_THRESHOLD_METERS) -> ActiveRouteType.FACULTY_RESIDENCE_BRANCH

            // Closest polyline fallback
            ghCrossDist < mainCrossDist && ghCrossDist < frCrossDist && distToGirlsHostel < 90.0 -> ActiveRouteType.GIRLS_HOSTEL_BRANCH
            frCrossDist < mainCrossDist && frCrossDist < ghCrossDist && distToFacultyResidence < 90.0 -> ActiveRouteType.FACULTY_RESIDENCE_BRANCH
            else -> ActiveRouteType.MAIN_ROUTE
        }

        val routeChanged = activeRouteType != tracker.activeRouteType
        tracker.activeRouteType = activeRouteType

        // 3. Determine Direction on Active Route
        val currentProgress = when (activeRouteType) {
            ActiveRouteType.MAIN_ROUTE -> mainProj.distanceFromStartMeters
            ActiveRouteType.GIRLS_HOSTEL_BRANCH -> ghProj.distanceFromStartMeters
            ActiveRouteType.FACULTY_RESIDENCE_BRANCH -> frProj.distanceFromStartMeters
            ActiveRouteType.OFF_ROUTE -> 0.0
        }

        val determinedDirection = if (routeChanged) {
            // When turning onto a branch from the junction, the cart is traveling outward along the branch
            TravelDirection.FORWARD
        } else {
            val progressDelta = currentProgress - tracker.lastRouteProgress
            when {
                progressDelta > 4.0 -> TravelDirection.FORWARD
                progressDelta < -4.0 -> TravelDirection.REVERSE
                relativeMovement == "Moving Away" -> TravelDirection.FORWARD
                relativeMovement == "Coming Towards You" -> TravelDirection.REVERSE
                bearing != null && (bearing in 310.0f..360.0f || bearing in 0.0f..110.0f) -> TravelDirection.FORWARD
                bearing != null && bearing in 130.0f..270.0f -> TravelDirection.REVERSE
                else -> tracker.direction
            }
        }

        tracker.direction = determinedDirection
        tracker.lastRouteProgress = currentProgress
        tracker.lastLat = latitude
        tracker.lastLng = longitude

        val direction = tracker.direction

        // 4. Compute Passed, Current, and Approaching for Active Route
        val evaluatedResult = when (activeRouteType) {
            ActiveRouteType.MAIN_ROUTE -> {
                evaluateMainRouteProgress(
                    latitude = latitude,
                    longitude = longitude,
                    cartProgressAlongPolyline = mainProj.distanceFromStartMeters,
                    direction = direction
                )
            }
            ActiveRouteType.GIRLS_HOSTEL_BRANCH -> {
                evaluateGirlsHostelBranchProgress(
                    latitude = latitude,
                    longitude = longitude,
                    cartProgressAlongPolyline = ghProj.distanceFromStartMeters,
                    direction = direction
                )
            }
            ActiveRouteType.FACULTY_RESIDENCE_BRANCH -> {
                evaluateFacultyResidenceBranchProgress(
                    latitude = latitude,
                    longitude = longitude,
                    cartProgressAlongPolyline = frProj.distanceFromStartMeters,
                    direction = direction
                )
            }
            ActiveRouteType.OFF_ROUTE -> {
                LiveRouteProgress(
                    activeRouteType = ActiveRouteType.OFF_ROUTE,
                    direction = direction,
                    isOffRoute = true,
                    isLocationAvailable = true,
                    currentStop = null,
                    passedStops = emptyList(),
                    approachingStops = emptyList(),
                    availableBranches = emptyList(),
                    statusMessage = "OFF ROUTE"
                )
            }
        }

        tracker.cachedProgress = evaluatedResult
        tracker.lastEvaluatedLat = latitude
        tracker.lastEvaluatedLng = longitude
        tracker.lastDestination = selectedDestination
        return evaluatedResult
    }

    private fun evaluateMainRouteProgress(
        latitude: Double,
        longitude: Double,
        cartProgressAlongPolyline: Double,
        direction: TravelDirection
    ): LiveRouteProgress {
        // Compute polyline distance for each main checkpoint
        val checkpointDistances = CHECKPOINTS_MAIN_ROUTE.map { node ->
            val (proj, _) = projectPointOnPolyline(node.latitude, node.longitude, POLYLINE_MAIN_ROUTE)
            Pair(node, proj.distanceFromStartMeters)
        }

        // Check if cart is currently inside arrival radius of any checkpoint
        var currentStopNode: RouteNode? = null
        for (node in CHECKPOINTS_MAIN_ROUTE) {
            val dist = distanceMeters(latitude, longitude, node.latitude, node.longitude)
            if (dist <= ARRIVAL_THRESHOLD_METERS) {
                currentStopNode = node
                break
            }
        }

        val passedList = mutableListOf<RouteNode>()
        val approachingList = mutableListOf<CheckpointProgress>()

        // Order of checkpoints depending on direction
        val orderedCheckpoints = if (direction == TravelDirection.REVERSE) {
            checkpointDistances.reversed()
        } else {
            checkpointDistances
        }

        for ((node, stopPolylineDist) in orderedCheckpoints) {
            if (node == currentStopNode) {
                // Currently at this stop
                continue
            }

            val isBehindCart = if (direction == TravelDirection.REVERSE) {
                // When moving from Hostel (high dist) to Gate (low dist), passed stops have stopDist > cartDist
                stopPolylineDist > (cartProgressAlongPolyline + 15.0)
            } else {
                // When moving from Gate (low dist) to Hostel (high dist), passed stops have stopDist < cartDist
                stopPolylineDist < (cartProgressAlongPolyline - 15.0)
            }

            if (isBehindCart) {
                passedList.add(node)
            } else {
                // Ahead of cart along route
                val remainingDist = abs(stopPolylineDist - cartProgressAlongPolyline)
                approachingList.add(
                    CheckpointProgress(
                        node = node,
                        distanceMeters = remainingDist,
                        formattedDistance = formatDistance(remainingDist)
                    )
                )
            }
        }

        // Available Branches Check
        // Branches appear only when approaching or at their junction
        val availableBranches = mutableListOf<BranchProgress>()

        if (direction != TravelDirection.REVERSE) {
            // Forward trip (Main Gate -> Hostel):
            // Check Girls Hostel branch availability (Junction at CC Building)
            val distToCCAlongRoute = abs(checkpointDistances.first { it.first == NODE_CC }.second - cartProgressAlongPolyline)
            val directDistToCC = distanceMeters(latitude, longitude, COMPUTER_CENTRE_COORD.first, COMPUTER_CENTRE_COORD.second)

            // Show Girls Hostel branch when within proximity of CC and not yet passed Academic Block
            val academicDist = checkpointDistances.first { it.first == NODE_ACADEMIC }.second
            if (cartProgressAlongPolyline <= academicDist + 20.0 && directDistToCC <= BRANCH_PROXIMITY_THRESHOLD_METERS) {
                val branchLen = polylineLength(POLYLINE_GIRLS_HOSTEL_BRANCH)
                val totalBranchRouteDist = distToCCAlongRoute + branchLen
                availableBranches.add(
                    BranchProgress(
                        branchName = "Girls Hostel",
                        destinationNode = NODE_GIRLS_HOSTEL,
                        junctionNode = NODE_CC,
                        distanceMeters = totalBranchRouteDist,
                        formattedDistance = formatDistance(totalBranchRouteDist)
                    )
                )
            }

            // Check Faculty Residence branch availability (Junction at FACULTY_JUNCTION)
            val (junctionProj, _) = projectPointOnPolyline(FACULTY_JUNCTION_COORD.first, FACULTY_JUNCTION_COORD.second, POLYLINE_MAIN_ROUTE)
            val distToJunctionAlongRoute = abs(junctionProj.distanceFromStartMeters - cartProgressAlongPolyline)
            val directDistToJunction = distanceMeters(latitude, longitude, FACULTY_JUNCTION_COORD.first, FACULTY_JUNCTION_COORD.second)

            // Show Faculty Residence when past CC or approaching junction
            val ccDist = checkpointDistances.first { it.first == NODE_CC }.second
            if (cartProgressAlongPolyline >= ccDist - 30.0 && directDistToJunction <= BRANCH_PROXIMITY_THRESHOLD_METERS) {
                val branchLen = polylineLength(POLYLINE_FACULTY_RESIDENCE_BRANCH)
                val totalBranchRouteDist = distToJunctionAlongRoute + branchLen
                availableBranches.add(
                    BranchProgress(
                        branchName = "Faculty Residence",
                        destinationNode = NODE_FACULTY_RESIDENCE,
                        junctionNode = NODE_FACULTY_JUNCTION,
                        distanceMeters = totalBranchRouteDist,
                        formattedDistance = formatDistance(totalBranchRouteDist)
                    )
                )
            }
        }

        return LiveRouteProgress(
            activeRouteType = ActiveRouteType.MAIN_ROUTE,
            direction = direction,
            isOffRoute = false,
            isLocationAvailable = true,
            currentStop = currentStopNode,
            passedStops = passedList,
            approachingStops = approachingList,
            availableBranches = availableBranches,
            statusMessage = if (currentStopNode != null) "At ${currentStopNode.displayName}" else "En Route"
        )
    }

    private fun evaluateGirlsHostelBranchProgress(
        latitude: Double,
        longitude: Double,
        cartProgressAlongPolyline: Double,
        direction: TravelDirection
    ): LiveRouteProgress {
        val branchLength = polylineLength(POLYLINE_GIRLS_HOSTEL_BRANCH)
        val distToGirlsHostel = distanceMeters(latitude, longitude, GIRLS_HOSTEL_COORD.first, GIRLS_HOSTEL_COORD.second)
        val distToCC = distanceMeters(latitude, longitude, COMPUTER_CENTRE_COORD.first, COMPUTER_CENTRE_COORD.second)

        val isAtGirlsHostel = distToGirlsHostel <= ARRIVAL_THRESHOLD_METERS
        val isAtCC = distToCC <= ARRIVAL_THRESHOLD_METERS

        val currentStop = when {
            isAtGirlsHostel -> NODE_GIRLS_HOSTEL
            isAtCC -> NODE_CC
            else -> null
        }

        val passedList = mutableListOf<RouteNode>()
        val approachingList = mutableListOf<CheckpointProgress>()

        if (direction == TravelDirection.REVERSE) {
            // Returning from Girls Hostel toward CC Building
            if (isAtGirlsHostel) {
                // At Girls Hostel
            } else {
                passedList.add(NODE_GIRLS_HOSTEL)
            }
            if (!isAtCC) {
                val dist = cartProgressAlongPolyline
                approachingList.add(
                    CheckpointProgress(
                        node = NODE_CC,
                        distanceMeters = dist,
                        formattedDistance = formatDistance(dist)
                    )
                )
            }
        } else {
            // Heading towards Girls Hostel
            if (isAtCC) {
                // At CC Building
            } else {
                passedList.add(NODE_CC)
            }
            if (!isAtGirlsHostel) {
                val dist = maxOf(0.0, branchLength - cartProgressAlongPolyline)
                approachingList.add(
                    CheckpointProgress(
                        node = NODE_GIRLS_HOSTEL,
                        distanceMeters = dist,
                        formattedDistance = formatDistance(dist)
                    )
                )
            }
        }

        return LiveRouteProgress(
            activeRouteType = ActiveRouteType.GIRLS_HOSTEL_BRANCH,
            direction = direction,
            isOffRoute = false,
            isLocationAvailable = true,
            currentStop = currentStop,
            passedStops = passedList,
            approachingStops = approachingList,
            availableBranches = emptyList(),
            statusMessage = if (currentStop != null) "At ${currentStop.displayName}" else "Girls Hostel Branch"
        )
    }

    private fun evaluateFacultyResidenceBranchProgress(
        latitude: Double,
        longitude: Double,
        cartProgressAlongPolyline: Double,
        direction: TravelDirection
    ): LiveRouteProgress {
        val branchLength = polylineLength(POLYLINE_FACULTY_RESIDENCE_BRANCH)
        val distToFacultyResidence = distanceMeters(latitude, longitude, FACULTY_RESIDENCE_COORD.first, FACULTY_RESIDENCE_COORD.second)
        val distToJunction = distanceMeters(latitude, longitude, FACULTY_JUNCTION_COORD.first, FACULTY_JUNCTION_COORD.second)

        val isAtFacultyResidence = distToFacultyResidence <= ARRIVAL_THRESHOLD_METERS
        val isAtJunction = distToJunction <= ARRIVAL_THRESHOLD_METERS

        val currentStop = when {
            isAtFacultyResidence -> NODE_FACULTY_RESIDENCE
            else -> null
        }

        val passedList = mutableListOf<RouteNode>()
        val approachingList = mutableListOf<CheckpointProgress>()

        if (direction == TravelDirection.REVERSE) {
            // Returning from Faculty Residence towards main route
            if (!isAtFacultyResidence) {
                passedList.add(NODE_FACULTY_RESIDENCE)
            }
            val dist = cartProgressAlongPolyline
            approachingList.add(
                CheckpointProgress(
                    node = NODE_ACADEMIC,
                    distanceMeters = dist + 60.0,
                    formattedDistance = formatDistance(dist + 60.0)
                )
            )
        } else {
            // Heading towards Faculty Residence
            passedList.add(NODE_ACADEMIC)
            if (!isAtFacultyResidence) {
                val dist = maxOf(0.0, branchLength - cartProgressAlongPolyline)
                approachingList.add(
                    CheckpointProgress(
                        node = NODE_FACULTY_RESIDENCE,
                        distanceMeters = dist,
                        formattedDistance = formatDistance(dist)
                    )
                )
            }
        }

        return LiveRouteProgress(
            activeRouteType = ActiveRouteType.FACULTY_RESIDENCE_BRANCH,
            direction = direction,
            isOffRoute = false,
            isLocationAvailable = true,
            currentStop = currentStop,
            passedStops = passedList,
            approachingStops = approachingList,
            availableBranches = emptyList(),
            statusMessage = if (currentStop != null) "At ${currentStop.displayName}" else "Faculty Residence Branch"
        )
    }

    // --- Math & Polyline Utilities ---

    data class ProjectionResult(
        val segmentIndex: Int,
        val segmentProgress: Float,
        val distanceFromStartMeters: Double
    )

    /**
     * Projects a GPS coordinate onto a polyline using precomputed geometry when available.
     * Returns: Pair(ProjectionResult, crossTrackDistanceMeters)
     */
    fun projectPointOnPolyline(
        pLat: Double,
        pLng: Double,
        polyline: List<Pair<Double, Double>>
    ): Pair<ProjectionResult, Double> {
        val segments = when {
            polyline === POLYLINE_MAIN_ROUTE || polyline == POLYLINE_MAIN_ROUTE -> PRECOMPUTED_MAIN_ROUTE
            polyline === POLYLINE_GIRLS_HOSTEL_BRANCH || polyline == POLYLINE_GIRLS_HOSTEL_BRANCH -> PRECOMPUTED_GIRLS_HOSTEL_BRANCH
            polyline === POLYLINE_FACULTY_RESIDENCE_BRANCH || polyline == POLYLINE_FACULTY_RESIDENCE_BRANCH -> PRECOMPUTED_FACULTY_RESIDENCE_BRANCH
            else -> null
        }

        if (segments != null) {
            return projectPointOnPrecomputed(pLat, pLng, segments)
        }

        if (polyline.size < 2) {
            return Pair(ProjectionResult(0, 0f, 0.0), 0.0)
        }

        var bestCrossDist = Double.MAX_VALUE
        var bestSegment = 0
        var bestT = 0f
        var bestDistFromStart = 0.0

        var accumulatedDist = 0.0

        for (i in 0 until polyline.size - 1) {
            val a = polyline[i]
            val b = polyline[i + 1]

            val segLen = distanceMeters(a.first, a.second, b.first, b.second)
            val (t, crossDist) = projectPointOnSegmentMeters(pLat, pLng, a.first, a.second, b.first, b.second)

            if (crossDist < bestCrossDist) {
                bestCrossDist = crossDist
                bestSegment = i
                bestT = t
                bestDistFromStart = accumulatedDist + (t * segLen)
            }

            accumulatedDist += segLen
        }

        return Pair(ProjectionResult(bestSegment, bestT, bestDistFromStart), bestCrossDist)
    }

    private fun projectPointOnPrecomputed(
        pLat: Double,
        pLng: Double,
        segments: List<PrecomputedSegment>
    ): Pair<ProjectionResult, Double> {
        if (segments.isEmpty()) {
            return Pair(ProjectionResult(0, 0f, 0.0), 0.0)
        }

        val cosLat = cos(Math.toRadians(25.258))
        var bestCrossDist = Double.MAX_VALUE
        var bestSegment = 0
        var bestT = 0f
        var bestDistFromStart = 0.0

        for (i in segments.indices) {
            val seg = segments[i]
            val px = (pLng - seg.aLng) * 111320.0 * cosLat
            val py = (pLat - seg.aLat) * 110540.0

            val t = if (seg.lenSq < 1e-6) 0f else (((px * seg.vx + py * seg.vy) / seg.lenSq).toFloat()).coerceIn(0.0f, 1.0f)
            val projX = t * seg.vx
            val projY = t * seg.vy
            val crossDist = sqrt((px - projX) * (px - projX) + (py - projY) * (py - projY))

            if (crossDist < bestCrossDist) {
                bestCrossDist = crossDist
                bestSegment = i
                bestT = t
                bestDistFromStart = seg.accumulatedStartMeters + (t * seg.segLengthMeters)
            }
        }

        return Pair(ProjectionResult(bestSegment, bestT, bestDistFromStart), bestCrossDist)
    }

    private fun projectPointOnSegmentMeters(
        pLat: Double, pLng: Double,
        aLat: Double, aLng: Double,
        bLat: Double, bLng: Double
    ): Pair<Float, Double> {
        val cosLat = cos(Math.toRadians(25.258))
        val px = (pLng - aLng) * 111320.0 * cosLat
        val py = (pLat - aLat) * 110540.0

        val vx = (bLng - aLng) * 111320.0 * cosLat
        val vy = (bLat - aLat) * 110540.0

        val lenSq = vx * vx + vy * vy
        if (lenSq < 1e-6) {
            return Pair(0.0f, sqrt(px * px + py * py))
        }

        val t = ((px * vx + py * vy) / lenSq).toFloat().coerceIn(0.0f, 1.0f)
        val projX = t * vx
        val projY = t * vy
        val crossDist = sqrt((px - projX) * (px - projX) + (py - projY) * (py - projY))
        return Pair(t, crossDist)
    }

    fun polylineLength(polyline: List<Pair<Double, Double>>): Double {
        return when {
            polyline === POLYLINE_MAIN_ROUTE || polyline == POLYLINE_MAIN_ROUTE -> TOTAL_LENGTH_MAIN_ROUTE
            polyline === POLYLINE_GIRLS_HOSTEL_BRANCH || polyline == POLYLINE_GIRLS_HOSTEL_BRANCH -> TOTAL_LENGTH_GIRLS_HOSTEL_BRANCH
            polyline === POLYLINE_FACULTY_RESIDENCE_BRANCH || polyline == POLYLINE_FACULTY_RESIDENCE_BRANCH -> TOTAL_LENGTH_FACULTY_RESIDENCE_BRANCH
            else -> {
                var len = 0.0
                for (i in 0 until polyline.size - 1) {
                    len += distanceMeters(polyline[i].first, polyline[i].second, polyline[i + 1].first, polyline[i + 1].second)
                }
                len
            }
        }
    }

    fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2.0).pow(2.0) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2.0).pow(2.0)
        val c = 2.0 * atan2(sqrt(a), sqrt(1.0 - a))
        return 6371000.0 * c
    }

    fun formatDistance(meters: Double): String {
        return if (meters < 1000.0) {
            "${maxOf(10, meters.roundToInt())} m"
        } else {
            String.format(java.util.Locale.US, "%.1f km", meters / 1000.0)
        }
    }
}

/**
 * Policy governing whether a cart's telemetry should be provided to the route engine.
 *
 * Enforces separation between physical location and operational ride availability:
 * - If the cart has valid, non-expired coordinates inside campus, those coordinates
 *   MUST be provided to CampusRouteGraph regardless of driver operational status
 *   ("Driver Not Available", "Lunch Break", "Off Duty").
 * - If coordinates are missing, invalid (0.0, 0.0), expired (> 180s), or outside campus,
 *   coordinates are NOT provided (returns null).
 */
object RouteTrackingGatingPolicy {
    fun extractUsableRouteCoordinates(cartState: GolfCartState?): Pair<Double, Double>? {
        if (cartState == null) return null
        if (!cartState.hasCoordinates) return null
        val lat = cartState.latitude ?: return null
        val lng = cartState.longitude ?: return null
        if (lat == 0.0 || lng == 0.0) return null
        if (cartState.isLocationExpiredOrMissing) return null
        if (cartState.isOutsideCampus) return null
        return Pair(lat, lng)
    }

    fun isRouteTrackingAvailable(cartState: GolfCartState?): Boolean {
        return extractUsableRouteCoordinates(cartState) != null
    }
}

