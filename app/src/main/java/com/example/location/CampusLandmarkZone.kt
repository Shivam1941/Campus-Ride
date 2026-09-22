package com.example.location

import android.util.Log
import java.util.ArrayDeque
import kotlin.math.*

/**
 * Robust Campus Landmark Zones, connected route model, and GPS Noise Filter for IIIT Bhagalpur.
 *
 * Canonical Fixed Route:
 * MAIN GATE (0.0) -> TRUNKUT (1.0) -> COMPUTER CENTRE (2.0) -> HOSTEL (3.0)
 *
 * Stabilization & Anti-Flapping Engine:
 * 1. GPS Accuracy Filtering: Drops samples with accuracy > 30m to prevent jumpy transitions.
 * 2. Stationary Cart Protection: When speed < 2.0 km/h and displacement < 12m, locks state completely.
 * 3. Hysteresis on All Stops: Separate Enter (arrival) and Exit (departure) radii.
 * 4. Polyline Orthogonal Route Projection: Calculates continuous mathematical progress [0.0..3.0].
 * 5. State Machine & Direction Latching: Requires consistent multi-sample trend before changing direction.
 * 6. Multi-Sample Debouncing: Requires 3 consecutive confirmed samples to transition route state.
 * 7. Clean Stop Visualization: Prevents flapping between Approaching, At Stop, and Completed.
 */
enum class CampusLandmarkZone(
    val id: String,
    val displayName: String,
    val fullAddress: String,
    val emoji: String,
    val latitude: Double,
    val longitude: Double,
    val enterRadiusMeters: Double,
    val exitRadiusMeters: Double,
    val isVerifiedCoordinate: Boolean
) {
    GATE(
        id = "MAIN_GATE",
        displayName = "Main Gate",
        fullAddress = "IIIT BHAGALPUR Main Gate, Bhagalpur, Bihar 813210",
        emoji = "🚪",
        latitude = 25.2531616,
        longitude = 87.0370730,
        enterRadiusMeters = 55.0,
        exitRadiusMeters = 80.0,
        isVerifiedCoordinate = true
    ),
    TRUNKET(
        id = "TRUNKET",
        displayName = "Trunket",
        fullAddress = "Trunket, IIIT Bhagalpur Campus, Sabour, Bihar 813210",
        emoji = "📍",
        latitude = 25.2577186,
        longitude = 87.0381730,
        enterRadiusMeters = 45.0,
        exitRadiusMeters = 70.0,
        isVerifiedCoordinate = true
    ),
    COMPUTER_CENTRE(
        id = "COMPUTER_CENTRE",
        displayName = "Computer Centre",
        fullAddress = "Computer Centre, IIIT Bhagalpur Campus, Sabour, Bihar 813210",
        emoji = "💻",
        latitude = 25.2590500,
        longitude = 87.0394730,
        enterRadiusMeters = 45.0,
        exitRadiusMeters = 70.0,
        isVerifiedCoordinate = true
    ),
    ACADEMIC_BLOCK(
        id = "ACADEMIC_BLOCK",
        displayName = "Academic Block",
        fullAddress = "Academic Block, IIIT Bhagalpur Campus, Sabour, Bihar 813210",
        emoji = "🏛️",
        latitude = 25.2590750,
        longitude = 87.0401610,
        enterRadiusMeters = 28.0,
        exitRadiusMeters = 34.0,
        isVerifiedCoordinate = true
    ),
    FACULTY_RESIDENCE(
        id = "FACULTY_RESIDENCE",
        displayName = "Faculty Residence",
        fullAddress = "Faculty Residence, IIIT Bhagalpur Campus, Sabour, Bihar 813210",
        emoji = "🏡",
        latitude = 25.259257426319685,
        longitude = 87.04141528824039,
        enterRadiusMeters = 45.0,
        exitRadiusMeters = 70.0,
        isVerifiedCoordinate = true
    ),
    GIRLS_HOSTEL(
        id = "GIRLS_HOSTEL",
        displayName = "Girls Hostel",
        fullAddress = "Girls Hostel, IIIT Bhagalpur Campus, Sabour, Bihar 813210",
        emoji = "🏢",
        latitude = 25.26007663539964,
        longitude = 87.0393243817079,
        enterRadiusMeters = 45.0,
        exitRadiusMeters = 70.0,
        isVerifiedCoordinate = true
    ),
    HOSTEL(
        id = "HOSTEL",
        displayName = "Hostel",
        fullAddress = "Hostel, IIIT Bhagalpur Campus, Sabour, Bihar 813210",
        emoji = "🏠",
        latitude = 25.2577810,
        longitude = 87.0418910,
        enterRadiusMeters = 65.0,
        exitRadiusMeters = 90.0,
        isVerifiedCoordinate = true
    );

    val arrivalRadiusMeters: Double get() = enterRadiusMeters

    companion object {
        private const val TAG = "CampusGpsFilter"

        // Tuned Thresholds
        const val MAX_ACCEPTED_ACCURACY_METERS = 30.0f
        const val MIN_STATIONARY_DISPLACEMENT_METERS = 12.0 // Displacements under 12m with low speed are stationary jitter
        const val MIN_SPEED_THRESHOLD_KMH = 2.0
        const val REQUIRED_CONFIRMATION_SAMPLES = 3 // Consecutive samples required to confirm state changes
        const val MIN_PROGRESS_DIRECTION_DELTA = 0.06f // Progress delta along route required to switch direction

        // Compatibility alias
        val TRUNKUT: CampusLandmarkZone get() = TRUNKET

        // Canonical Ordered Route Sequence: Main Gate (0) -> Trunket (1) -> Computer Centre (2) -> Hostel (3)
        val ROUTE_SEQUENCE = listOf(GATE, TRUNKET, COMPUTER_CENTRE, HOSTEL)

        enum class RouteState(val label: String) {
            AT_MAIN_GATE("AT MAIN GATE"),
            BETWEEN_MAIN_GATE_AND_TRUNKET("BETWEEN MAIN GATE AND TRUNKET"),
            APPROACHING_TRUNKET("APPROACHING TRUNKET"),
            AT_TRUNKET("AT TRUNKET"),
            BETWEEN_TRUNKET_AND_COMPUTER_CENTRE("BETWEEN TRUNKET AND COMPUTER CENTRE"),
            APPROACHING_COMPUTER_CENTRE("APPROACHING COMPUTER CENTRE"),
            AT_COMPUTER_CENTRE("AT COMPUTER CENTRE"),
            BETWEEN_COMPUTER_CENTRE_AND_HOSTEL("BETWEEN COMPUTER CENTRE AND HOSTEL"),
            APPROACHING_HOSTEL("APPROACHING HOSTEL"),
            AT_HOSTEL("AT HOSTEL")
        }

        enum class StopVisualState {
            COMPLETED,      // Passed/Crossed in current trip direction
            AT_STOP,        // Currently stopped inside arrival radius
            NEXT_STOP,      // Approaching next stop
            FUTURE_STOP     // Upcoming later on the route
        }

        data class StopTimelineInfo(
            val landmark: CampusLandmarkZone,
            val visualState: StopVisualState,
            val statusLabel: String,
            val isAtThisStop: Boolean
        )

        enum class CartDirection {
            TOWARD_GATE,
            TOWARD_HOSTEL,
            STATIONARY,
            UNKNOWN
        }

        data class RoutePositionResult(
            val routeState: RouteState = RouteState.AT_MAIN_GATE,
            val primaryLandmark: CampusLandmarkZone,
            val secondaryLandmark: CampusLandmarkZone?,
            val isAtLandmark: Boolean,
            val isBetween: Boolean,
            val movingDirection: CartDirection,
            val isAtGate: Boolean,
            val routeProgressFloat: Float, // 0.0f (Gate) -> 1.0f (Trunket) -> 2.0f (Computer Centre) -> 3.0f (Hostel)
            val driverDetailedLocation: String,
            val driverDirectionSubtitle: String,
            val studentPrimaryText: String,
            val studentSubtitleText: String,
            val timelineStops: List<StopTimelineInfo>
        ) {
            val currentStopName: String
                get() = routeState.label

            val nextStopName: String
                get() = when {
                    isAtGate -> "Trunket"
                    primaryLandmark == HOSTEL && movingDirection == CartDirection.TOWARD_GATE -> "Computer Centre"
                    primaryLandmark == HOSTEL -> "Hostel"
                    primaryLandmark == COMPUTER_CENTRE && movingDirection == CartDirection.TOWARD_GATE -> "Trunket"
                    primaryLandmark == COMPUTER_CENTRE -> "Boys Hostel"
                    primaryLandmark == TRUNKUT && movingDirection == CartDirection.TOWARD_GATE -> "Main Gate"
                    primaryLandmark == TRUNKUT -> "Computer Centre"
                    movingDirection == CartDirection.TOWARD_GATE -> "Main Gate"
                    else -> "Boys Hostel"
                }

            val directionSummary: String
                get() = when (movingDirection) {
                    CartDirection.TOWARD_GATE -> when {
                        routeProgressFloat >= 2.0f -> "Boys Hostel → Main Gate"
                        routeProgressFloat >= 1.0f -> "Computer Centre → Main Gate"
                        else -> "Trunkut → Main Gate"
                    }
                    CartDirection.TOWARD_HOSTEL -> when {
                        routeProgressFloat <= 1.0f -> "Main Gate → Trunkut"
                        routeProgressFloat <= 2.0f -> "Trunkut → Computer Centre"
                        else -> "Computer Centre → Boys Hostel"
                    }
                    CartDirection.STATIONARY -> "Stationary ($currentStopName)"
                    else -> "In Transit"
                }

            val formattedDirection: String
                get() = when (movingDirection) {
                    CartDirection.TOWARD_GATE -> "→ Main Gate"
                    CartDirection.TOWARD_HOSTEL -> "→ Boys Hostel"
                    CartDirection.STATIONARY -> "• Stationary"
                    else -> "• In Transit"
                }

            fun getFacultySubtitle(facultyPickupDisplayName: String): String {
                return when {
                    isAtGate && facultyPickupDisplayName.contains("Gate", ignoreCase = true) -> "✓ Arrived at Main Gate"
                    isAtLandmark && primaryLandmark.displayName.equals(facultyPickupDisplayName, ignoreCase = true) -> "✓ At $facultyPickupDisplayName"
                    movingDirection == CartDirection.TOWARD_GATE && facultyPickupDisplayName.contains("Gate", ignoreCase = true) -> "Coming to Main Gate"
                    else -> "Coming to $facultyPickupDisplayName"
                }
            }
        }

        /**
         * Rolling GPS sample for trend smoothing and progress tracking.
         */
        data class GpsSample(
            val timestamp: Long,
            val lat: Double,
            val lng: Double,
            val accuracy: Float,
            val speedKmH: Int,
            val progress: Float,
            val distGate: Double,
            val distTrunkut: Double,
            val distCC: Double,
            val distHostel: Double
        )

        /**
         * State container for an individual cart to isolate GPS filter, smoothing, and hysteresis.
         */
        class CartRouteEvaluator(val cartId: String) {
            private val sampleWindow = ArrayDeque<GpsSample>()
            private var anchorLat: Double? = null
            private var anchorLng: Double? = null
            private var currentConfirmedStop: CampusLandmarkZone? = null
            private var confirmedDirection: CartDirection = CartDirection.TOWARD_HOSTEL
            private var confirmedRouteResult: RoutePositionResult? = null
            private var candidateRouteResult: RoutePositionResult? = null
            private var candidateConfirmationCount = 0

            @Synchronized
            fun clearHistory() {
                sampleWindow.clear()
                anchorLat = null
                anchorLng = null
                currentConfirmedStop = null
                confirmedDirection = CartDirection.TOWARD_HOSTEL
                confirmedRouteResult = null
                candidateRouteResult = null
                candidateConfirmationCount = 0
            }

            @Synchronized
            fun evaluate(
                latitude: Double?,
                longitude: Double?,
                bearing: Float? = null,
                relativeMovement: String? = null,
                speedKmH: Int = 0,
                accuracy: Float = 0f,
                timestamp: Long = System.currentTimeMillis()
            ): RoutePositionResult? {
                if (latitude == null || longitude == null || (latitude == 0.0 && longitude == 0.0)) {
                    return confirmedRouteResult
                }

                // 1. GPS Accuracy Guard: Drop poor accuracy readings (> 30m)
                val isAccuracyAcceptable = accuracy <= 0f || accuracy <= MAX_ACCEPTED_ACCURACY_METERS
                if (!isAccuracyAcceptable) {
                    Log.d(TAG, "Cart $cartId: Suppressed poor GPS accuracy (${accuracy}m > ${MAX_ACCEPTED_ACCURACY_METERS}m)")
                    return confirmedRouteResult
                }

                val distGate = GeofenceManager.calculateDistanceMeters(latitude, longitude, GATE.latitude, GATE.longitude)
                val distTrunkut = GeofenceManager.calculateDistanceMeters(latitude, longitude, TRUNKUT.latitude, TRUNKUT.longitude)
                val distCC = GeofenceManager.calculateDistanceMeters(latitude, longitude, COMPUTER_CENTRE.latitude, COMPUTER_CENTRE.longitude)
                val distAcad = GeofenceManager.calculateDistanceMeters(latitude, longitude, ACADEMIC_BLOCK.latitude, ACADEMIC_BLOCK.longitude)
                val distFacultyResidence = GeofenceManager.calculateDistanceMeters(latitude, longitude, FACULTY_RESIDENCE.latitude, FACULTY_RESIDENCE.longitude)
                val distGirlsHostel = GeofenceManager.calculateDistanceMeters(latitude, longitude, GIRLS_HOSTEL.latitude, GIRLS_HOSTEL.longitude)
                val distHostel = GeofenceManager.calculateDistanceMeters(latitude, longitude, HOSTEL.latitude, HOSTEL.longitude)

                // Sanity check: If completely outside Bhagalpur campus (> 2km from both ends), keep existing
                if (distGate > 2000.0 && distHostel > 2000.0) {
                    return confirmedRouteResult
                }

                // 2. Continuous Polyline Route Progress Computation [0.0..3.0]
                val projectedProgress = calculatePolylineRouteProgress(latitude, longitude)

                // 3. Stationary Detection & Anchor Tracking
                if (anchorLat == null || anchorLng == null) {
                    anchorLat = latitude
                    anchorLng = longitude
                }

                val displacementFromAnchor = GeofenceManager.calculateDistanceMeters(latitude, longitude, anchorLat!!, anchorLng!!)
                val isSpeedLow = speedKmH < MIN_SPEED_THRESHOLD_KMH
                val isDisplacementLow = displacementFromAnchor < MIN_STATIONARY_DISPLACEMENT_METERS
                val isStationary = isSpeedLow && isDisplacementLow

                if (!isStationary) {
                    // Update anchor when genuine movement occurs
                    anchorLat = latitude
                    anchorLng = longitude
                }

                // Add to rolling sample window (keep last 6 samples)
                if (sampleWindow.size >= 6) {
                    sampleWindow.removeFirst()
                }
                sampleWindow.addLast(
                    GpsSample(
                        timestamp = timestamp,
                        lat = latitude,
                        lng = longitude,
                        accuracy = accuracy,
                        speedKmH = speedKmH,
                        progress = projectedProgress,
                        distGate = distGate,
                        distTrunkut = distTrunkut,
                        distCC = distCC,
                        distHostel = distHostel
                    )
                )

                // 4. Direction Determination with Multi-Sample Hysteresis
                val effectiveDirection: CartDirection = if (isStationary) {
                    // Keep existing confirmed direction when cart is stationary
                    confirmedDirection
                } else {
                    val oldestSample = sampleWindow.firstOrNull()
                    val progressDelta = if (oldestSample != null) projectedProgress - oldestSample.progress else 0.0f
                    
                    val determined = when {
                        progressDelta >= MIN_PROGRESS_DIRECTION_DELTA -> CartDirection.TOWARD_HOSTEL
                        progressDelta <= -MIN_PROGRESS_DIRECTION_DELTA -> CartDirection.TOWARD_GATE
                        relativeMovement == "Coming Towards You" -> CartDirection.TOWARD_GATE
                        relativeMovement == "Moving Away" -> CartDirection.TOWARD_HOSTEL
                        bearing != null && bearing in 120.0f..240.0f -> CartDirection.TOWARD_GATE
                        bearing != null && (bearing in 300.0f..360.0f || bearing in 0.0f..60.0f) -> CartDirection.TOWARD_HOSTEL
                        else -> confirmedDirection
                    }
                    confirmedDirection = determined
                    determined
                }

                // 5. Landmark Hysteresis Evaluation (Enter Radius vs Exit Radius)
                val activeStop = currentConfirmedStop
                if (activeStop != null) {
                    val distToActiveStop = when (activeStop) {
                        GATE -> distGate
                        TRUNKET, TRUNKUT -> distTrunkut
                        COMPUTER_CENTRE -> distCC
                        ACADEMIC_BLOCK -> distAcad
                        FACULTY_RESIDENCE -> distFacultyResidence
                        GIRLS_HOSTEL -> distGirlsHostel
                        HOSTEL -> distHostel
                    }

                    // If still within exit radius or stationary, stay locked at this stop
                    if (distToActiveStop <= activeStop.exitRadiusMeters || isStationary) {
                        val stableResult = buildAtStopResult(activeStop, effectiveDirection)
                        confirmedRouteResult = stableResult
                        candidateRouteResult = null
                        candidateConfirmationCount = 0
                        logState(latitude, longitude, accuracy, speedKmH, projectedProgress, stableResult.driverDetailedLocation, "LOCKED_AT_STOP")
                        return stableResult
                    } else {
                        // Departed the stop beyond exit hysteresis radius
                        currentConfirmedStop = null
                        confirmedRouteResult = null
                        candidateRouteResult = null
                        candidateConfirmationCount = 0
                    }
                }

                // Check if newly entering any stop's enter radius
                val newlyEnteredStop = when {
                    distGirlsHostel <= GIRLS_HOSTEL.enterRadiusMeters -> GIRLS_HOSTEL
                    distFacultyResidence <= FACULTY_RESIDENCE.enterRadiusMeters -> FACULTY_RESIDENCE
                    distHostel <= HOSTEL.enterRadiusMeters -> HOSTEL
                    distGate <= GATE.enterRadiusMeters -> GATE
                    distAcad <= ACADEMIC_BLOCK.enterRadiusMeters -> ACADEMIC_BLOCK
                    distCC <= COMPUTER_CENTRE.enterRadiusMeters -> COMPUTER_CENTRE
                    distTrunkut <= TRUNKUT.enterRadiusMeters -> TRUNKUT
                    else -> null
                }

                if (newlyEnteredStop != null) {
                    currentConfirmedStop = newlyEnteredStop
                    val atStopResult = buildAtStopResult(newlyEnteredStop, effectiveDirection)
                    confirmedRouteResult = atStopResult
                    candidateRouteResult = null
                    candidateConfirmationCount = 0
                    logState(latitude, longitude, accuracy, speedKmH, projectedProgress, atStopResult.driverDetailedLocation, "NEW_ARRIVAL_STOP")
                    return atStopResult
                }

                // 6. In-Transit Stop State Evaluation along Ordered Route
                val rawCandidate = evaluateInTransitState(
                    progress = projectedProgress,
                    distGate = distGate,
                    distTrunkut = distTrunkut,
                    distCC = distCC,
                    distHostel = distHostel,
                    direction = effectiveDirection,
                    isStationary = isStationary
                )

                // 7. Multi-Sample State Debouncing to Prevent Flapping
                val currentConfirmed = confirmedRouteResult
                if (currentConfirmed == null) {
                    confirmedRouteResult = rawCandidate
                    return rawCandidate
                }

                // If candidate matches confirmed state, reset counter and smoothly interpolate progress
                if (rawCandidate.driverDetailedLocation == currentConfirmed.driverDetailedLocation) {
                    candidateRouteResult = null
                    candidateConfirmationCount = 0
                    val smoothedProgress = (currentConfirmed.routeProgressFloat * 0.6f + rawCandidate.routeProgressFloat * 0.4f)
                    confirmedRouteResult = rawCandidate.copy(routeProgressFloat = smoothedProgress)
                    return confirmedRouteResult
                }

                // If candidate is trying to change the state, require multiple consecutive confirmations
                if (candidateRouteResult?.driverDetailedLocation == rawCandidate.driverDetailedLocation) {
                    candidateConfirmationCount++
                    val requiredSamples = if (isStationary) REQUIRED_CONFIRMATION_SAMPLES + 1 else REQUIRED_CONFIRMATION_SAMPLES
                    if (candidateConfirmationCount >= requiredSamples) {
                        confirmedRouteResult = rawCandidate
                        candidateRouteResult = null
                        candidateConfirmationCount = 0
                        logState(latitude, longitude, accuracy, speedKmH, projectedProgress, rawCandidate.driverDetailedLocation, "TRANSITION_CONFIRMED")
                    }
                } else {
                    candidateRouteResult = rawCandidate
                    candidateConfirmationCount = 1
                }

                return confirmedRouteResult
            }

            private fun logState(
                lat: Double,
                lng: Double,
                accuracy: Float,
                speed: Int,
                progress: Float,
                stateText: String,
                event: String
            ) {
                Log.d(
                    TAG,
                    "GPS[cart=$cartId, event=$event]: lat=$lat, lng=$lng, acc=${accuracy}m, speed=${speed}km/h | Route progress=${(progress * 100 / 3.0f).roundToInt()}% ($progress) | State: $stateText | Dir=$confirmedDirection"
                )
            }
        }

        /**
         * Computes the orthogonal projection of a GPS point onto the 3 canonical segments of the campus route:
         * Segment 0: Gate (0.0) -> Trunkut (1.0)
         * Segment 1: Trunkut (1.0) -> Computer Centre (2.0)
         * Segment 2: Computer Centre (2.0) -> Hostel (3.0)
         *
         * Returns a continuous float progress value in [0.0f..3.0f].
         */
        fun calculatePolylineRouteProgress(latitude: Double, longitude: Double): Float {
            val p0 = GATE
            val p1 = TRUNKUT
            val p2 = COMPUTER_CENTRE
            val p3 = HOSTEL

            // Project onto Segment 0 (Gate -> Trunkut)
            val (proj0, distSq0) = projectPointOnSegment(latitude, longitude, p0.latitude, p0.longitude, p1.latitude, p1.longitude)
            // Project onto Segment 1 (Trunkut -> CC)
            val (proj1, distSq1) = projectPointOnSegment(latitude, longitude, p1.latitude, p1.longitude, p2.latitude, p2.longitude)
            // Project onto Segment 2 (CC -> Hostel)
            val (proj2, distSq2) = projectPointOnSegment(latitude, longitude, p2.latitude, p2.longitude, p3.latitude, p3.longitude)

            return when {
                distSq0 <= distSq1 && distSq0 <= distSq2 -> (0.0f + proj0).coerceIn(0.0f, 1.0f)
                distSq1 <= distSq0 && distSq1 <= distSq2 -> (1.0f + proj1).coerceIn(1.0f, 2.0f)
                else -> (2.0f + proj2).coerceIn(2.0f, 3.0f)
            }
        }

        private fun projectPointOnSegment(
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
                return Pair(0.0f, px * px + py * py)
            }

            val t = ((px * vx + py * vy) / lenSq).toFloat().coerceIn(0.0f, 1.0f)
            val projX = t * vx
            val projY = t * vy
            val distSq = (px - projX) * (px - projX) + (py - projY) * (py - projY)
            return Pair(t, distSq)
        }

        private val evaluators = java.util.concurrent.ConcurrentHashMap<String, CartRouteEvaluator>()

        private fun getEvaluator(cartId: String): CartRouteEvaluator {
            return evaluators.getOrPut(cartId) { CartRouteEvaluator(cartId) }
        }

        fun clearRollingHistory(cartId: String = "cart_1") {
            evaluators[cartId]?.clearHistory()
        }

        fun evaluateRoutePosition(
            latitude: Double?,
            longitude: Double?,
            bearing: Float? = null,
            relativeMovement: String? = null,
            speedKmH: Int = 0,
            accuracy: Float = 0f,
            timestamp: Long = System.currentTimeMillis(),
            cartId: String = "cart_1"
        ): RoutePositionResult? {
            return getEvaluator(cartId).evaluate(
                latitude = latitude,
                longitude = longitude,
                bearing = bearing,
                relativeMovement = relativeMovement,
                speedKmH = speedKmH,
                accuracy = accuracy,
                timestamp = timestamp
            )
        }

        private fun buildAtStopResult(stop: CampusLandmarkZone, direction: CartDirection): RoutePositionResult {
            val isHeadingGate = direction == CartDirection.TOWARD_GATE

            return when (stop) {
                GATE -> {
                    val stops = listOf(
                        StopTimelineInfo(GATE, StopVisualState.AT_STOP, "At Gate", isAtThisStop = true),
                        StopTimelineInfo(TRUNKET, StopVisualState.FUTURE_STOP, "", isAtThisStop = false),
                        StopTimelineInfo(COMPUTER_CENTRE, StopVisualState.FUTURE_STOP, "", isAtThisStop = false),
                        StopTimelineInfo(HOSTEL, StopVisualState.FUTURE_STOP, "", isAtThisStop = false)
                    )
                    RoutePositionResult(
                        routeState = RouteState.AT_MAIN_GATE,
                        primaryLandmark = GATE,
                        secondaryLandmark = null,
                        isAtLandmark = true,
                        isBetween = false,
                        movingDirection = CartDirection.TOWARD_GATE,
                        isAtGate = true,
                        routeProgressFloat = 0.0f,
                        driverDetailedLocation = RouteState.AT_MAIN_GATE.label,
                        driverDirectionSubtitle = "Arrived at Gate",
                        studentPrimaryText = "At Main Gate",
                        studentSubtitleText = "✓ Arrived at Gate",
                        timelineStops = stops
                    )
                }
                HOSTEL -> {
                    val stops = listOf(
                        StopTimelineInfo(GATE, StopVisualState.COMPLETED, "", isAtThisStop = false),
                        StopTimelineInfo(TRUNKET, StopVisualState.COMPLETED, "", isAtThisStop = false),
                        StopTimelineInfo(COMPUTER_CENTRE, StopVisualState.COMPLETED, "", isAtThisStop = false),
                        StopTimelineInfo(HOSTEL, StopVisualState.AT_STOP, "At Hostel", isAtThisStop = true)
                    )
                    val nextApproach = if (isHeadingGate) "Approaching Computer Centre" else "At Hostel"
                    RoutePositionResult(
                        routeState = RouteState.AT_HOSTEL,
                        primaryLandmark = HOSTEL,
                        secondaryLandmark = null,
                        isAtLandmark = true,
                        isBetween = false,
                        movingDirection = direction,
                        isAtGate = false,
                        routeProgressFloat = 3.0f,
                        driverDetailedLocation = RouteState.AT_HOSTEL.label,
                        driverDirectionSubtitle = nextApproach,
                        studentPrimaryText = "At Hostel",
                        studentSubtitleText = nextApproach,
                        timelineStops = stops
                    )
                }
                TRUNKET -> {
                    val nextApproach = if (isHeadingGate) "Approaching Main Gate" else "Approaching Computer Centre"
                    val stops = listOf(
                        StopTimelineInfo(GATE, if (isHeadingGate) StopVisualState.NEXT_STOP else StopVisualState.COMPLETED, if (isHeadingGate) "Approaching" else "", isAtThisStop = false),
                        StopTimelineInfo(TRUNKET, StopVisualState.AT_STOP, "At Trunket", isAtThisStop = true),
                        StopTimelineInfo(COMPUTER_CENTRE, if (isHeadingGate) StopVisualState.COMPLETED else StopVisualState.NEXT_STOP, if (isHeadingGate) "" else "Approaching", isAtThisStop = false),
                        StopTimelineInfo(HOSTEL, StopVisualState.FUTURE_STOP, "", isAtThisStop = false)
                    )
                    RoutePositionResult(
                        routeState = RouteState.AT_TRUNKET,
                        primaryLandmark = TRUNKET,
                        secondaryLandmark = null,
                        isAtLandmark = true,
                        isBetween = false,
                        movingDirection = direction,
                        isAtGate = false,
                        routeProgressFloat = 1.0f,
                        driverDetailedLocation = RouteState.AT_TRUNKET.label,
                        driverDirectionSubtitle = nextApproach,
                        studentPrimaryText = "At Trunket",
                        studentSubtitleText = nextApproach,
                        timelineStops = stops
                    )
                }
                COMPUTER_CENTRE -> {
                    val nextApproach = if (isHeadingGate) "Approaching Trunket" else "Approaching Hostel"
                    val stops = listOf(
                        StopTimelineInfo(GATE, if (isHeadingGate) StopVisualState.FUTURE_STOP else StopVisualState.COMPLETED, "", isAtThisStop = false),
                        StopTimelineInfo(TRUNKET, if (isHeadingGate) StopVisualState.NEXT_STOP else StopVisualState.COMPLETED, if (isHeadingGate) "Approaching" else "", isAtThisStop = false),
                        StopTimelineInfo(COMPUTER_CENTRE, StopVisualState.AT_STOP, "At Computer Centre", isAtThisStop = true),
                        StopTimelineInfo(HOSTEL, if (isHeadingGate) StopVisualState.COMPLETED else StopVisualState.NEXT_STOP, if (isHeadingGate) "" else "Approaching", isAtThisStop = false)
                    )
                    RoutePositionResult(
                        routeState = RouteState.AT_COMPUTER_CENTRE,
                        primaryLandmark = COMPUTER_CENTRE,
                        secondaryLandmark = null,
                        isAtLandmark = true,
                        isBetween = false,
                        movingDirection = direction,
                        isAtGate = false,
                        routeProgressFloat = 2.0f,
                        driverDetailedLocation = RouteState.AT_COMPUTER_CENTRE.label,
                        driverDirectionSubtitle = nextApproach,
                        studentPrimaryText = "At Computer Centre",
                        studentSubtitleText = nextApproach,
                        timelineStops = stops
                    )
                }
                ACADEMIC_BLOCK -> {
                    val nextApproach = if (isHeadingGate) "Approaching Computer Centre" else "Approaching Hostel"
                    val stops = listOf(
                        StopTimelineInfo(GATE, if (isHeadingGate) StopVisualState.FUTURE_STOP else StopVisualState.COMPLETED, "", isAtThisStop = false),
                        StopTimelineInfo(TRUNKET, StopVisualState.COMPLETED, "", isAtThisStop = false),
                        StopTimelineInfo(COMPUTER_CENTRE, if (isHeadingGate) StopVisualState.NEXT_STOP else StopVisualState.COMPLETED, "", isAtThisStop = false),
                        StopTimelineInfo(HOSTEL, if (isHeadingGate) StopVisualState.COMPLETED else StopVisualState.NEXT_STOP, if (isHeadingGate) "" else "Approaching", isAtThisStop = false)
                    )
                    RoutePositionResult(
                        routeState = RouteState.AT_COMPUTER_CENTRE,
                        primaryLandmark = ACADEMIC_BLOCK,
                        secondaryLandmark = COMPUTER_CENTRE,
                        isAtLandmark = true,
                        isBetween = false,
                        movingDirection = direction,
                        isAtGate = false,
                        routeProgressFloat = 2.5f,
                        driverDetailedLocation = "AT ACADEMIC BLOCK",
                        driverDirectionSubtitle = nextApproach,
                        studentPrimaryText = "At Academic Block",
                        studentSubtitleText = nextApproach,
                        timelineStops = stops
                    )
                }
                GIRLS_HOSTEL -> {
                    val nextApproach = "Approaching Computer Centre"
                    val stops = listOf(
                        StopTimelineInfo(GATE, StopVisualState.COMPLETED, "", isAtThisStop = false),
                        StopTimelineInfo(TRUNKET, StopVisualState.COMPLETED, "", isAtThisStop = false),
                        StopTimelineInfo(COMPUTER_CENTRE, StopVisualState.COMPLETED, "", isAtThisStop = false),
                        StopTimelineInfo(HOSTEL, StopVisualState.FUTURE_STOP, "", isAtThisStop = false)
                    )
                    RoutePositionResult(
                        routeState = RouteState.AT_COMPUTER_CENTRE,
                        primaryLandmark = GIRLS_HOSTEL,
                        secondaryLandmark = COMPUTER_CENTRE,
                        isAtLandmark = true,
                        isBetween = false,
                        movingDirection = direction,
                        isAtGate = false,
                        routeProgressFloat = 2.0f,
                        driverDetailedLocation = "AT GIRLS HOSTEL",
                        driverDirectionSubtitle = "At Girls Hostel",
                        studentPrimaryText = "At Girls Hostel",
                        studentSubtitleText = nextApproach,
                        timelineStops = stops
                    )
                }
                FACULTY_RESIDENCE -> {
                    val nextApproach = "Approaching Academic Block"
                    val stops = listOf(
                        StopTimelineInfo(GATE, StopVisualState.COMPLETED, "", isAtThisStop = false),
                        StopTimelineInfo(TRUNKET, StopVisualState.COMPLETED, "", isAtThisStop = false),
                        StopTimelineInfo(COMPUTER_CENTRE, StopVisualState.COMPLETED, "", isAtThisStop = false),
                        StopTimelineInfo(HOSTEL, StopVisualState.FUTURE_STOP, "", isAtThisStop = false)
                    )
                    RoutePositionResult(
                        routeState = RouteState.AT_HOSTEL,
                        primaryLandmark = FACULTY_RESIDENCE,
                        secondaryLandmark = ACADEMIC_BLOCK,
                        isAtLandmark = true,
                        isBetween = false,
                        movingDirection = direction,
                        isAtGate = false,
                        routeProgressFloat = 2.8f,
                        driverDetailedLocation = "AT FACULTY RESIDENCE",
                        driverDirectionSubtitle = "At Faculty Residence",
                        studentPrimaryText = "At Faculty Residence",
                        studentSubtitleText = nextApproach,
                        timelineStops = stops
                    )
                }
            }
        }

        /**
         * Evaluates In-Transit Route State based on monotonic route progress and confirmed trip direction.
         * Produces the official 10 discrete states with debounced transitions:
         * AT MAIN GATE, BETWEEN MAIN GATE AND TRUNKET, APPROACHING TRUNKET, AT TRUNKET,
         * BETWEEN TRUNKET AND COMPUTER CENTRE, APPROACHING COMPUTER CENTRE, AT COMPUTER CENTRE,
         * BETWEEN COMPUTER CENTRE AND HOSTEL, APPROACHING HOSTEL, AT HOSTEL.
         */
        private fun evaluateInTransitState(
            progress: Float,
            distGate: Double,
            distTrunkut: Double,
            distCC: Double,
            distHostel: Double,
            direction: CartDirection,
            isStationary: Boolean
        ): RoutePositionResult {
            val isHeadingGate = direction == CartDirection.TOWARD_GATE

            return if (isHeadingGate) {
                // TRIP TOWARD MAIN GATE (3.0 -> 0.0)
                when {
                    // Segment 2: Between Hostel and Computer Centre (progress in 2.0..3.0)
                    progress >= 2.0f -> {
                        val isApproaching = distCC <= 60.0
                        val routeState = if (isApproaching) RouteState.APPROACHING_COMPUTER_CENTRE else RouteState.BETWEEN_COMPUTER_CENTRE_AND_HOSTEL
                        val stops = listOf(
                            StopTimelineInfo(GATE, StopVisualState.FUTURE_STOP, "", isAtThisStop = false),
                            StopTimelineInfo(TRUNKET, StopVisualState.FUTURE_STOP, "", isAtThisStop = false),
                            StopTimelineInfo(COMPUTER_CENTRE, StopVisualState.NEXT_STOP, "Approaching", isAtThisStop = false),
                            StopTimelineInfo(HOSTEL, StopVisualState.COMPLETED, "", isAtThisStop = false)
                        )
                        RoutePositionResult(
                            routeState = routeState,
                            primaryLandmark = COMPUTER_CENTRE,
                            secondaryLandmark = HOSTEL,
                            isAtLandmark = false,
                            isBetween = true,
                            movingDirection = CartDirection.TOWARD_GATE,
                            isAtGate = false,
                            routeProgressFloat = progress,
                            driverDetailedLocation = routeState.label,
                            driverDirectionSubtitle = if (isApproaching) "Approaching Computer Centre" else "Between CC & Hostel",
                            studentPrimaryText = if (isApproaching) "Approaching Computer Centre" else "Between Computer Centre & Hostel",
                            studentSubtitleText = "Heading toward Main Gate",
                            timelineStops = stops
                        )
                    }
                    // Segment 1: Between Computer Centre and Trunket (progress in 1.0..2.0)
                    progress >= 1.0f -> {
                        val isApproaching = distTrunkut <= 60.0
                        val routeState = if (isApproaching) RouteState.APPROACHING_TRUNKET else RouteState.BETWEEN_TRUNKET_AND_COMPUTER_CENTRE
                        val stops = listOf(
                            StopTimelineInfo(GATE, StopVisualState.FUTURE_STOP, "", isAtThisStop = false),
                            StopTimelineInfo(TRUNKET, StopVisualState.NEXT_STOP, "Approaching", isAtThisStop = false),
                            StopTimelineInfo(COMPUTER_CENTRE, StopVisualState.COMPLETED, "", isAtThisStop = false),
                            StopTimelineInfo(HOSTEL, StopVisualState.COMPLETED, "", isAtThisStop = false)
                        )
                        RoutePositionResult(
                            routeState = routeState,
                            primaryLandmark = TRUNKET,
                            secondaryLandmark = COMPUTER_CENTRE,
                            isAtLandmark = false,
                            isBetween = true,
                            movingDirection = CartDirection.TOWARD_GATE,
                            isAtGate = false,
                            routeProgressFloat = progress,
                            driverDetailedLocation = routeState.label,
                            driverDirectionSubtitle = if (isApproaching) "Approaching Trunket" else "Between Trunket & CC",
                            studentPrimaryText = if (isApproaching) "Approaching Trunket" else "Between Trunket & Computer Centre",
                            studentSubtitleText = "Heading toward Main Gate",
                            timelineStops = stops
                        )
                    }
                    // Segment 0: Between Trunket and Gate (progress in 0.0..1.0)
                    else -> {
                        val routeState = RouteState.BETWEEN_MAIN_GATE_AND_TRUNKET
                        val studentSub = if (distGate <= 60.0) "Arriving at Gate" else "Approaching Main Gate"
                        val stops = listOf(
                            StopTimelineInfo(GATE, StopVisualState.NEXT_STOP, "Approaching", isAtThisStop = false),
                            StopTimelineInfo(TRUNKET, StopVisualState.COMPLETED, "", isAtThisStop = false),
                            StopTimelineInfo(COMPUTER_CENTRE, StopVisualState.COMPLETED, "", isAtThisStop = false),
                            StopTimelineInfo(HOSTEL, StopVisualState.COMPLETED, "", isAtThisStop = false)
                        )
                        RoutePositionResult(
                            routeState = routeState,
                            primaryLandmark = GATE,
                            secondaryLandmark = TRUNKET,
                            isAtLandmark = false,
                            isBetween = true,
                            movingDirection = CartDirection.TOWARD_GATE,
                            isAtGate = false,
                            routeProgressFloat = progress,
                            driverDetailedLocation = routeState.label,
                            driverDirectionSubtitle = studentSub,
                            studentPrimaryText = if (distGate <= 60.0) "Approaching Main Gate" else "Between Main Gate & Trunket",
                            studentSubtitleText = studentSub,
                            timelineStops = stops
                        )
                    }
                }
            } else {
                // TRIP TOWARD HOSTEL (0.0 -> 3.0)
                when {
                    // Segment 0: Between Gate and Trunket (progress in 0.0..1.0)
                    progress <= 1.0f -> {
                        val isApproaching = distTrunkut <= 60.0
                        val routeState = if (isApproaching) RouteState.APPROACHING_TRUNKET else RouteState.BETWEEN_MAIN_GATE_AND_TRUNKET
                        val stops = listOf(
                            StopTimelineInfo(GATE, StopVisualState.COMPLETED, "", isAtThisStop = false),
                            StopTimelineInfo(TRUNKET, StopVisualState.NEXT_STOP, "Approaching", isAtThisStop = false),
                            StopTimelineInfo(COMPUTER_CENTRE, StopVisualState.FUTURE_STOP, "", isAtThisStop = false),
                            StopTimelineInfo(HOSTEL, StopVisualState.FUTURE_STOP, "", isAtThisStop = false)
                        )
                        RoutePositionResult(
                            routeState = routeState,
                            primaryLandmark = GATE,
                            secondaryLandmark = TRUNKET,
                            isAtLandmark = false,
                            isBetween = true,
                            movingDirection = CartDirection.TOWARD_HOSTEL,
                            isAtGate = false,
                            routeProgressFloat = progress,
                            driverDetailedLocation = routeState.label,
                            driverDirectionSubtitle = if (isApproaching) "Approaching Trunket" else "Between Gate & Trunket",
                            studentPrimaryText = if (isApproaching) "Approaching Trunket" else "Between Main Gate & Trunket",
                            studentSubtitleText = "Heading toward Hostel",
                            timelineStops = stops
                        )
                    }
                    // Segment 1: Between Trunket and Computer Centre (progress in 1.0..2.0)
                    progress <= 2.0f -> {
                        val isApproaching = distCC <= 60.0
                        val routeState = if (isApproaching) RouteState.APPROACHING_COMPUTER_CENTRE else RouteState.BETWEEN_TRUNKET_AND_COMPUTER_CENTRE
                        val stops = listOf(
                            StopTimelineInfo(GATE, StopVisualState.COMPLETED, "", isAtThisStop = false),
                            StopTimelineInfo(TRUNKET, StopVisualState.COMPLETED, "", isAtThisStop = false),
                            StopTimelineInfo(COMPUTER_CENTRE, StopVisualState.NEXT_STOP, "Approaching", isAtThisStop = false),
                            StopTimelineInfo(HOSTEL, StopVisualState.FUTURE_STOP, "", isAtThisStop = false)
                        )
                        RoutePositionResult(
                            routeState = routeState,
                            primaryLandmark = TRUNKET,
                            secondaryLandmark = COMPUTER_CENTRE,
                            isAtLandmark = false,
                            isBetween = true,
                            movingDirection = CartDirection.TOWARD_HOSTEL,
                            isAtGate = false,
                            routeProgressFloat = progress,
                            driverDetailedLocation = routeState.label,
                            driverDirectionSubtitle = if (isApproaching) "Approaching Computer Centre" else "Between Trunket & CC",
                            studentPrimaryText = if (isApproaching) "Approaching Computer Centre" else "Between Trunket & Computer Centre",
                            studentSubtitleText = "Heading toward Hostel",
                            timelineStops = stops
                        )
                    }
                    // Segment 2: Between Computer Centre and Hostel (progress in 2.0..3.0)
                    else -> {
                        val isApproaching = distHostel <= 60.0
                        val routeState = if (isApproaching) RouteState.APPROACHING_HOSTEL else RouteState.BETWEEN_COMPUTER_CENTRE_AND_HOSTEL
                        val stops = listOf(
                            StopTimelineInfo(GATE, StopVisualState.COMPLETED, "", isAtThisStop = false),
                            StopTimelineInfo(TRUNKET, StopVisualState.COMPLETED, "", isAtThisStop = false),
                            StopTimelineInfo(COMPUTER_CENTRE, StopVisualState.COMPLETED, "", isAtThisStop = false),
                            StopTimelineInfo(HOSTEL, StopVisualState.NEXT_STOP, "Approaching", isAtThisStop = false)
                        )
                        RoutePositionResult(
                            routeState = routeState,
                            primaryLandmark = COMPUTER_CENTRE,
                            secondaryLandmark = HOSTEL,
                            isAtLandmark = false,
                            isBetween = true,
                            movingDirection = CartDirection.TOWARD_HOSTEL,
                            isAtGate = false,
                            routeProgressFloat = progress,
                            driverDetailedLocation = routeState.label,
                            driverDirectionSubtitle = if (isApproaching) "Approaching Hostel" else "Between CC & Hostel",
                            studentPrimaryText = if (isApproaching) "Approaching Hostel" else "Between Computer Centre & Hostel",
                            studentSubtitleText = "Heading toward Hostel",
                            timelineStops = stops
                        )
                    }
                }
            }
        }

        fun getCartLocationDescription(latitude: Double?, longitude: Double?, cartId: String = "cart_1"): String {
            val result = evaluateRoutePosition(latitude = latitude, longitude = longitude, cartId = cartId)
            return result?.driverDetailedLocation ?: "Location updating…"
        }

        fun getStudentFacingDriverLocation(latitude: Double?, longitude: Double?, cartId: String = "cart_1"): String {
            val result = evaluateRoutePosition(latitude = latitude, longitude = longitude, cartId = cartId) ?: return "Driver location updating…"
            return if (result.isAtGate) {
                "Driver has arrived at Gate"
            } else {
                "Driver is ${result.driverDetailedLocation}"
            }
        }
    }
}

