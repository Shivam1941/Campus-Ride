package com.example.data.repository

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.example.data.api.CampusBackendClient
import com.example.data.api.CartHeartbeatRequest
import com.example.data.api.CreateRideRequest
import com.example.data.api.DutyStatusRequest
import com.example.data.api.LocationUpdateRequest
import com.example.data.model.GolfCartState
import com.example.data.model.GolfCartStatus
import com.example.data.model.PickupLocation
import com.example.data.model.RideRequest
import com.example.data.model.RideRequestStatus
import com.example.data.model.ScheduleStatus
import com.example.data.model.UserRole
import com.example.location.GeofenceManager
import com.example.location.TrackingConfig
import com.example.notification.CriticalAlertManager
import com.example.notification.FcmRoleNotificationManager
import com.example.util.CampusTimeUtils
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import com.example.ui.permissions.PermissionUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.math.roundToInt

enum class StartupPhase {
    STARTING,
    AUTHENTICATING,
    LOADING_DRIVER_STATE,
    READY
}

class CampusRideRepository(context: Context) {

    init {
        instance = this
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences("campus_ride_prefs", Context.MODE_PRIVATE)

    private val scope = CoroutineScope(Dispatchers.Default)

    // Current Role State
    private val _currentRole = MutableStateFlow<UserRole?>(getSavedRole())
    val currentRole: StateFlow<UserRole?> = _currentRole.asStateFlow()

    // Deterministic Startup Phase State
    private val _startupPhase = MutableStateFlow(StartupPhase.STARTING)
    val startupPhase: StateFlow<StartupPhase> = _startupPhase.asStateFlow()

    // Dark Mode Theme State
    private val _isDarkMode = MutableStateFlow(
        prefs.getBoolean("pref_dark_mode", false)
    )
    val isDarkMode: StateFlow<Boolean> = _isDarkMode.asStateFlow()

    fun setDarkMode(enabled: Boolean) {
        prefs.edit().putBoolean("pref_dark_mode", enabled).apply()
        _isDarkMode.value = enabled
    }

    // Authoritative Driver Name and Fixed Cart Assignment
    private val savedDriverName = prefs.getString("saved_driver_name", "")?.trim() ?: ""
    private val _driverName = MutableStateFlow(savedDriverName)
    val driverName: StateFlow<String> = _driverName.asStateFlow()

    private val _driverCartLocked = MutableStateFlow(
        prefs.getBoolean("driver_assignment_locked", false) || savedDriverName.isNotBlank()
    )
    val driverCartLocked: StateFlow<Boolean> = _driverCartLocked.asStateFlow()

    // Determine initial authoritative cart based on locked driver name
    private val initialCartId: String = when {
        savedDriverName.equals("shivam", ignoreCase = true) -> "cart_1"
        savedDriverName.equals("kartik", ignoreCase = true) -> "cart_2"
        else -> prefs.getString("pref_selected_driver_cart", "cart_1") ?: "cart_1"
    }

    // Driver Selection & Active Trip State
    private val _selectedDriverCartId = MutableStateFlow(initialCartId)
    val selectedDriverCartId: StateFlow<String> = _selectedDriverCartId.asStateFlow()

    private val _driverSessionId = MutableStateFlow(
        prefs.getString("pref_driver_session_id", null) ?: run {
            val newSession = "session_${System.currentTimeMillis()}_${(1000..9999).random()}"
            prefs.edit().putString("pref_driver_session_id", newSession).apply()
            newSession
        }
    )
    val driverSessionId: StateFlow<String> = _driverSessionId.asStateFlow()

    fun setSelectedDriverCartId(cartId: String) {
        val currentLockedName = _driverName.value.trim()
        val lockedCart = when {
            currentLockedName.equals("shivam", ignoreCase = true) -> "cart_1"
            currentLockedName.equals("kartik", ignoreCase = true) -> "cart_2"
            else -> null
        }
        if (lockedCart != null && cartId != lockedCart) {
            Log.w("CampusRideRepo", "BLOCKED attempt to change cart to $cartId for driver $currentLockedName. Cart is permanently locked to $lockedCart.")
            return
        }

        val oldCartId = _selectedDriverCartId.value
        val effectiveCartId = lockedCart ?: cartId
        _selectedDriverCartId.value = effectiveCartId
        prefs.edit()
            .putString("pref_selected_driver_cart", effectiveCartId)
            .putString("selected_driver_cart_id", effectiveCartId)
            .apply()
        Log.d("CampusRideRepo", "setSelectedDriverCartId: Active driver cart changed from $oldCartId to $effectiveCartId")

        // Immediately update topic subscription and sync token for this specific cart
        FcmRoleNotificationManager.updateTopicSubscriptions(context, UserRole.DRIVER, effectiveCartId)
        val currentToken = prefs.getString("fcm_token", null) ?: prefs.getString("driver_fcm_token", null)
        if (FcmRoleNotificationManager.isRealFcmToken(currentToken)) {
            FcmRoleNotificationManager.transmitDriverTokenImmediately(context, currentToken!!, effectiveCartId)
        }

        if (oldCartId != effectiveCartId) {
            scope.launch(Dispatchers.IO) {
                try {
                    ensureFirebaseAuth()
                    val now = System.currentTimeMillis()
                    FirebaseFirestore.getInstance().collection("drivers")
                        .document(effectiveCartId)
                        .set(
                            mapOf(
                                "cartId" to effectiveCartId,
                                "cartName" to (if (effectiveCartId == "cart_1") "Cart 1" else "Cart 2"),
                                "driverName" to currentLockedName.ifBlank { if (effectiveCartId == "cart_1") "Shivam" else "Kartik" },
                                "isOnline" to true,
                                "onDuty" to true,
                                "isAvailable" to true,
                                "driverStatus" to "Available",
                                "status" to GolfCartStatus.HALTED.name,
                                "lastUpdatedMillis" to now,
                                "last_seen" to now,
                                "lastHeartbeatMillis" to now
                            ),
                            SetOptions.merge()
                        )
                } catch (e: Exception) {
                    Log.w("CampusRideRepo", "Error broadcasting switched driver cart assignment: ${e.message}")
                }
            }
        }
    }

    fun setAuthoritativeDriver(name: String): Result<String> {
        val cleanName = name.trim()
        val cartId = when {
            cleanName.equals("shivam", ignoreCase = true) -> "cart_1"
            cleanName.equals("kartik", ignoreCase = true) -> "cart_2"
            else -> return Result.failure(
                IllegalArgumentException("Driver \"$cleanName\" is not authorized. Only registered drivers (Shivam, Kartik) are authorized.")
            )
        }
        val canonicalName = if (cleanName.equals("shivam", ignoreCase = true)) "Shivam" else "Kartik"

        _driverName.value = canonicalName
        _selectedDriverCartId.value = cartId
        _driverCartLocked.value = true

        prefs.edit()
            .putString("saved_driver_name", canonicalName)
            .putString("pref_selected_driver_cart", cartId)
            .putString("selected_driver_cart_id", cartId)
            .putBoolean("driver_assignment_locked", true)
            .putString("saved_user_role", UserRole.DRIVER.name)
            .apply()

        Log.d("CampusRideRepo", "AUTHORITATIVE_DRIVER_ASSIGNED: Driver $canonicalName -> $cartId (PERMANENTLY LOCKED)")

        FcmRoleNotificationManager.updateTopicSubscriptions(context, UserRole.DRIVER, cartId)
        val currentToken = prefs.getString("fcm_token", null) ?: prefs.getString("driver_fcm_token", null)
        if (FcmRoleNotificationManager.isRealFcmToken(currentToken)) {
            FcmRoleNotificationManager.transmitDriverTokenImmediately(context, currentToken!!, cartId)
        }

        scope.launch(Dispatchers.IO) {
            try {
                ensureFirebaseAuth()
                val now = System.currentTimeMillis()
                val firestore = FirebaseFirestore.getInstance()
                firestore.collection("driver_assignments")
                    .document(cartId)
                    .set(
                        mapOf(
                            "driverName" to canonicalName,
                            "cartId" to cartId,
                            "locked" to true,
                            "assignedAt" to now,
                            "updatedAt" to now
                        ),
                        SetOptions.merge()
                    )
                firestore.collection("drivers")
                    .document(cartId)
                    .set(
                        mapOf(
                            "driverName" to canonicalName,
                            "cartId" to cartId,
                            "cartName" to (if (cartId == "cart_1") "Cart 1" else "Cart 2"),
                            "isOnline" to true,
                            "onDuty" to true,
                            "isAvailable" to true,
                            "driverStatus" to "Available",
                            "status" to GolfCartStatus.HALTED.name,
                            "lastUpdatedMillis" to now,
                            "last_seen" to now,
                            "lastHeartbeatMillis" to now
                        ),
                        SetOptions.merge()
                    )
            } catch (e: Exception) {
                Log.w("CampusRideRepo", "Failed to sync driver assignment to Firestore: ${e.message}")
            }
        }

        return Result.success(cartId)
    }

    private val _isTripActive = MutableStateFlow(
        prefs.getBoolean("pref_is_trip_active", false)
    )
    val isTripActive: StateFlow<Boolean> = _isTripActive.asStateFlow()

    // Driver Availability
    private val _isDriverAvailable = MutableStateFlow(true)
    val isDriverAvailable: StateFlow<Boolean> = _isDriverAvailable.asStateFlow()

    // Driver Geofence & Location State
    private val _driverLatitude = MutableStateFlow<Double?>(null)
    val driverLatitude: StateFlow<Double?> = _driverLatitude.asStateFlow()

    private val _driverLongitude = MutableStateFlow<Double?>(null)
    val driverLongitude: StateFlow<Double?> = _driverLongitude.asStateFlow()

    private val _distanceToLibraryMeters = MutableStateFlow<Double?>(null)
    val distanceToLibraryMeters: StateFlow<Double?> = _distanceToLibraryMeters.asStateFlow()

    private val _isInsideGeofence = MutableStateFlow(false)
    val isInsideGeofence: StateFlow<Boolean> = _isInsideGeofence.asStateFlow()

    private val _isInsideCampus = MutableStateFlow(false)
    val isInsideCampus: StateFlow<Boolean> = _isInsideCampus.asStateFlow()

    private val _driverGpsAccuracyMeters = MutableStateFlow<Float?>(null)
    val driverGpsAccuracyMeters: StateFlow<Float?> = _driverGpsAccuracyMeters.asStateFlow()

    private val _hasGpsLocation = MutableStateFlow(false)
    val hasGpsLocation: StateFlow<Boolean> = _hasGpsLocation.asStateFlow()

    // 1-Hour Automatic Lunch Break State & Countdown Timer
    private val _lunchBreakEndTimeMs = MutableStateFlow(0L)
    val lunchBreakEndTimeMs: StateFlow<Long> = _lunchBreakEndTimeMs.asStateFlow()

    private val _lunchBreakRemainingSeconds = MutableStateFlow(0)
    val lunchBreakRemainingSeconds: StateFlow<Int> = _lunchBreakRemainingSeconds.asStateFlow()

    // Lunch Break Once-Per-Day Date Tracking (Asia/Kolkata timezone: yyyy-MM-dd)
    private val _lunchBreakUsedDate = MutableStateFlow<String?>(
        prefs.getString("pref_lunch_break_used_date", null)
    )
    val lunchBreakUsedDate: StateFlow<String?> = _lunchBreakUsedDate.asStateFlow()

    private val _lunchBreakStartTimeMs = MutableStateFlow(
        prefs.getLong("pref_lunch_break_start_time", 0L)
    )
    val lunchBreakStartTimeMs: StateFlow<Long> = _lunchBreakStartTimeMs.asStateFlow()

    private val _isLunchBreakUsedToday = MutableStateFlow(
        CampusTimeUtils.isTodayInCampusTimezone(prefs.getString("pref_lunch_break_used_date", null))
    )
    val isLunchBreakUsedToday: StateFlow<Boolean> = _isLunchBreakUsedToday.asStateFlow()

    // Manual Off-Duty Override (controlled strictly via Settings)
    private val _manualDutyOverride = MutableStateFlow(
        prefs.getBoolean("pref_manual_duty_override", false)
    )
    val manualDutyOverride: StateFlow<Boolean> = _manualDutyOverride.asStateFlow()

    // Authoritative Effective Duty Status (ON_DUTY, OFF_DUTY, LUNCH_BREAK, OUTSIDE_CAMPUS, ON_TRIP)
    private val _effectiveDutyStatus = MutableStateFlow(
        prefs.getString("pref_effective_duty_status", "OUTSIDE_CAMPUS") ?: "OUTSIDE_CAMPUS"
    )
    val effectiveDutyStatus: StateFlow<String> = _effectiveDutyStatus.asStateFlow()

    private val lunchBreakMutex = Mutex()
    private var lunchBreakListenerRegistration: ListenerRegistration? = null
    private var lunchBreakJob: Job? = null

    // Working Hours Override for campus testing
    private val _overrideWorkingHours = MutableStateFlow(
        prefs.getBoolean("pref_override_hours", true) // Default true so user can test anytime
    )
    val overrideWorkingHours: StateFlow<Boolean> = _overrideWorkingHours.asStateFlow()

    // Dedicated Independent State for Cart 1 and Cart 2
    private val _cart1State = MutableStateFlow(
        GolfCartState(
            cartId = "cart_1",
            cartName = "Cart 1",
            status = GolfCartStatus.OFFLINE,
            isAvailable = true,
            driverStatus = "Offline",
            currentStop = "Main Gate",
            direction = "Main Gate → Boys Hostel"
        )
    )
    val cart1State: StateFlow<GolfCartState> = _cart1State.asStateFlow()

    private val _cart2State = MutableStateFlow(
        GolfCartState(
            cartId = "cart_2",
            cartName = "Cart 2",
            status = GolfCartStatus.OFFLINE,
            isAvailable = true,
            driverStatus = "Offline",
            currentStop = "Boys Hostel",
            direction = "Boys Hostel → Main Gate"
        )
    )
    val cart2State: StateFlow<GolfCartState> = _cart2State.asStateFlow()

    // Fleet State from real data source / Firebase
    private val _fleetCarts = MutableStateFlow<List<GolfCartState>>(
        listOf(_cart1State.value, _cart2State.value)
    )
    val fleetCarts: StateFlow<List<GolfCartState>> = _fleetCarts.asStateFlow()

    // Active Golf Cart State for current user session (aliases selected cart)
    private val _golfCartState = MutableStateFlow<GolfCartState?>(_cart1State.value)
    val golfCartState: StateFlow<GolfCartState?> = _golfCartState.asStateFlow()

    fun findBestAvailableCart(pickupLocation: String): GolfCartState? {
        val available = _fleetCarts.value.filter {
            (it.isLive || (it.isAvailable && !it.driverStatus.equals("Offline", ignoreCase = true))) &&
            !it.driverStatus.equals("Lunch Break", ignoreCase = true) &&
            !it.driverStatus.equals("Occupied", ignoreCase = true)
        }
        if (available.isEmpty()) return null
        return available.minByOrNull { it.etaMinutes ?: Int.MAX_VALUE } ?: available.firstOrNull()
    }

    // Ride Requests
    private val _requests = MutableStateFlow<List<RideRequest>>(emptyList())
    val requests: StateFlow<List<RideRequest>> = _requests.asStateFlow()

    // Active Student Request
    private val _activeStudentRequest = MutableStateFlow<RideRequest?>(null)
    val activeStudentRequest: StateFlow<RideRequest?> = _activeStudentRequest.asStateFlow()

    // Active Faculty Request
    private val _activeFacultyRequest = MutableStateFlow<RideRequest?>(null)
    val activeFacultyRequest: StateFlow<RideRequest?> = _activeFacultyRequest.asStateFlow()

    // Faculty Pickup Locations List (Strictly 5 Faculty Locations)
    private val _facultyPickupLocations = MutableStateFlow(
        PickupLocation.FACULTY_LOCATIONS.map { it.displayName }
    )
    val facultyPickupLocations: StateFlow<List<String>> = _facultyPickupLocations.asStateFlow()

    private val _selectedFacultyPickup = MutableStateFlow(PickupLocation.HOSTEL.displayName)
    val selectedFacultyPickup: StateFlow<String> = _selectedFacultyPickup.asStateFlow()

    fun setSelectedFacultyPickup(location: String) {
        _selectedFacultyPickup.value = location
    }

    // 5-minute Cooldown Seconds remaining (0 means ready)
    private val _cooldownSeconds = MutableStateFlow(0)
    val cooldownSeconds: StateFlow<Int> = _cooldownSeconds.asStateFlow()

    private var cooldownJob: Job? = null

    private val context: Context = context.applicationContext

    private val requestCreationMutex = kotlinx.coroutines.sync.Mutex()

    private var driverListenerRegistration: ListenerRegistration? = null
    private var studentListenerRegistration: ListenerRegistration? = null
    private var cart1ListenerRegistration: ListenerRegistration? = null
    private var cart2ListenerRegistration: ListenerRegistration? = null
    private var cartPollingJob: Job? = null

    // Battery Optimization: Per-cart telemetry synchronization thresholds to prevent excessive remote network writes
    private var cart1LastSyncTime = 0L
    private var cart1LastSyncLat = 0.0
    private var cart1LastSyncLng = 0.0
    private var cart1LastSyncBearing = 0f
    private var cart1LastSyncStatus = ""
    private var cart1LastSyncAvailable = false
    private var cart1LastSyncTripActive = false

    private var cart2LastSyncTime = 0L
    private var cart2LastSyncLat = 0.0
    private var cart2LastSyncLng = 0.0
    private var cart2LastSyncBearing = 0f
    private var cart2LastSyncStatus = ""
    private var cart2LastSyncAvailable = false
    private var cart2LastSyncTripActive = false

    private var lastRideRequestSyncTime = 0L
    private var lastRideRequestSyncLat = 0.0
    private var lastRideRequestSyncLng = 0.0

    // Network Optimization: Exponential backoff & passenger background listener management
    @Volatile private var consecutiveNetworkErrors = 0
    @Volatile private var nextAllowedNetworkSyncTimeMs = 0L
    @Volatile private var isPassengerTrackingPaused = false

    init {
        CriticalAlertManager.initNotificationChannel(this.context)
        val savedRole = getSavedRole()
        Log.d("CampusRideRepo", "Init CampusRideRepository: Saved role from preferences = $savedRole")
        if (savedRole != null) {
            _currentRole.value = savedRole
            FcmRoleNotificationManager.syncRoleFcmSubscription(this.context, savedRole)
            if (savedRole == UserRole.DRIVER) {
                Log.d("CampusRideRepo", "Init CampusRideRepository: Saved role is DRIVER. Starting driver Firestore listener...")
                startDriverFirestoreListener()
            }
        }
        startGolfCartLiveTrackingListener()
        checkAndRestoreLunchBreak()
        startLunchBreakSyncListener()
        startDailyResetTicker()
    }

    suspend fun performDeterministicStartup(): StartupPhase = withContext(Dispatchers.IO) {
        try {
            _startupPhase.value = StartupPhase.STARTING
            val savedRole = getSavedRole()
            if (savedRole != null) {
                _currentRole.value = savedRole
                checkAndRestoreLunchBreak()
            }

            _startupPhase.value = StartupPhase.AUTHENTICATING
            try {
                ensureFirebaseAuth()
            } catch (e: Exception) {
                Log.w("CampusRideRepo", "Auth warning during startup: ${e.message}")
            }

            if (savedRole == UserRole.DRIVER) {
                _startupPhase.value = StartupPhase.LOADING_DRIVER_STATE
                try {
                    withTimeoutOrNull(3500L) {
                        fetchServerAuthoritativeLunchBreakState()
                        fetchServerAuthoritativeDriverDutyState()
                    }
                } catch (e: Exception) {
                    Log.w("CampusRideRepo", "Server sync driver state timeout/error: ${e.message}")
                }
                withContext(Dispatchers.Main) {
                    startDriverFirestoreListener()
                    startLunchBreakSyncListener()
                }
            }
            withContext(Dispatchers.Main) {
                startGolfCartLiveTrackingListener()
            }
        } catch (e: Exception) {
            Log.e("CampusRideRepo", "Error during deterministic startup: ${e.message}", e)
        } finally {
            _startupPhase.value = StartupPhase.READY
        }
        StartupPhase.READY
    }

    private suspend fun <T> com.google.android.gms.tasks.Task<T>.awaitTask(): T =
        suspendCancellableCoroutine { cont ->
            addOnSuccessListener { result ->
                cont.resume(result)
            }
            addOnFailureListener { exception ->
                cont.resumeWith(Result.failure(exception))
            }
        }

    private suspend fun ensureFirebaseAuth(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            if (FirebaseApp.getApps(context).isEmpty()) {
                Log.d("CampusRideRepo", "FirebaseApp not initialized. Calling FirebaseApp.initializeApp(context)...")
                FirebaseApp.initializeApp(context)
            } else {
                Log.d("CampusRideRepo", "FirebaseApp already initialized.")
            }

            val app = try { FirebaseApp.getInstance() } catch (e: Exception) { null }
            if (app != null) {
                val opts = app.options
                Log.d("CampusRideRepo", "FirebaseApp Instance: Name=${app.name}, ProjectId=${opts.projectId}, AppId=${opts.applicationId}, ApiKey=${if (opts.apiKey.isNullOrEmpty()) "MISSING" else "PRESENT (${opts.apiKey.take(6)}...)"}")
            } else {
                Log.w("CampusRideRepo", "FirebaseApp.getInstance() returned null or threw exception")
            }

            val auth = FirebaseAuth.getInstance()
            val currentUser = auth.currentUser
            if (currentUser != null) {
                Log.d("CampusRideRepo", "Firebase Auth: User already signed in. uid = ${currentUser.uid}")
                try {
                    currentUser.getIdToken(false).awaitTask()
                } catch (_: Exception) {}
                return@withContext Result.success(Unit)
            }

            Log.d("CampusRideRepo", "Firebase Auth: No user signed in. Calling signInAnonymously()...")
            val result = auth.signInAnonymously().awaitTask()
            val user = result.user
            if (user != null) {
                try {
                    user.getIdToken(false).awaitTask()
                } catch (_: Exception) {}
                Log.d("CampusRideRepo", "Firebase Auth: Anonymous sign-in success, uid = ${user.uid}")
                Result.success(Unit)
            } else {
                val msg = "Firebase Auth: Anonymous sign-in returned null user"
                Log.e("CampusRideRepo", msg)
                Result.failure(IllegalStateException(msg))
            }
        } catch (e: Exception) {
            val exClass = e.javaClass.name
            val exMsg = e.message ?: "No error message"
            val errorCode = (e as? FirebaseAuthException)?.errorCode
                ?: (e as? FirebaseException)?.message
                ?: "NONE"
            val stackTrace = Log.getStackTraceString(e)

            Log.e("CampusRideRepo", "================ FIREBASE AUTH FAILURE DEBUG LOG ================")
            Log.e("CampusRideRepo", "Exception Class : $exClass")
            Log.e("CampusRideRepo", "Exception Message : $exMsg")
            Log.e("CampusRideRepo", "Firebase Error Code: $errorCode")
            Log.e("CampusRideRepo", "Stack Trace:\n$stackTrace")
            Log.e("CampusRideRepo", "=================================================================")

            val detailedErrorMsg = "Firebase Authentication failed: [$exClass | Code: $errorCode] $exMsg"
            Result.failure(IllegalStateException(detailedErrorMsg, e))
        }
    }

    fun startDriverFirestoreListener() {
        driverListenerRegistration?.remove()

        // Requirement 2: Ensure _currentRole is initialized before listener registration
        val activeRole = _currentRole.value ?: getSavedRole()?.also { restored ->
            _currentRole.value = restored
        }

        Log.d("CampusRideRepo", "Firestore Listener: Registering driver listener for ride_requests. Current role = $activeRole")

        if (activeRole != UserRole.DRIVER) {
            Log.w("CampusRideRepo", "Firestore Listener: Aborting driver listener registration as current role ($activeRole) is not DRIVER.")
            return
        }

        scope.launch(Dispatchers.IO) {
            // Requirement 1: Do not attach addSnapshotListener until anonymous authentication succeeds
            val authRes = ensureFirebaseAuth()
            if (authRes.isFailure) {
                val err = authRes.exceptionOrNull()
                Log.e("CampusRideRepo", "Firestore Listener: Aborting driver listener registration because Firebase Auth failed: ${err?.message}", err)
                return@launch
            }

            withContext(Dispatchers.Main) {
                driverListenerRegistration?.remove()
                try {
                    val firestore = FirebaseFirestore.getInstance()
                    Log.d("CampusRideRepo", "Firestore Listener: Attaching addSnapshotListener on 'ride_requests' collection...")

                    driverListenerRegistration = firestore.collection("ride_requests")
                        .addSnapshotListener { snapshot, error ->
                            if (error != null) {
                                Log.e("CampusRideRepo", "Firestore Listener Error: Exception listening to ride requests (Code: ${error.code})", error)
                                return@addSnapshotListener
                            }

                            Log.d("CampusRideRepo", "Firestore Snapshot Received: isNull=${snapshot == null}, isEmpty=${snapshot?.isEmpty}")

                            if (snapshot != null && !snapshot.isEmpty) {
                                val docCount = snapshot.documents.size
                                Log.d("CampusRideRepo", "Firestore Snapshot: Received $docCount documents")

                                val incomingList = mutableListOf<RideRequest>()
                                for (doc in snapshot.documents) {
                                    try {
                                        val id = doc.getString("id") ?: doc.id
                                        val requesterTypeStr = doc.getString("requesterType") ?: "STUDENT"
                                        val reqType = if (requesterTypeStr == "FACULTY") com.example.data.model.RequesterType.FACULTY else com.example.data.model.RequesterType.STUDENT
                                        val studentName = doc.getString("studentName") ?: ""
                                        val pickupLocation = doc.getString("pickupLocation") ?: "Main Gate"
                                        val distance = doc.getLong("distanceToGateMeters")?.toInt() ?: 0
                                        val statusStr = doc.getString("status") ?: "PENDING"
                                        val status = try { RideRequestStatus.valueOf(statusStr) } catch (e: Exception) { RideRequestStatus.PENDING }
                                        val rawCartId = doc.getString("selectedCartId") ?: doc.getString("assignedCartId")
                                        val cartId = if (rawCartId?.contains("2", ignoreCase = true) == true) "cart_2" else "cart_1"
                                        val cartName = doc.getString("assignedCartName") ?: (if (cartId == "cart_2") "Cart 2" else "Cart 1")
                                        val timestamp = doc.getLong("timestamp") ?: System.currentTimeMillis()
                                        val studentsWaiting = (doc.getLong("waitingCount") ?: doc.getLong("studentsWaiting"))?.toInt()?.coerceIn(1, 10) ?: 1

                                        val currentRoleVal = _currentRole.value
                                        val myCartId = _selectedDriverCartId.value
                                        val normalizedMyCart = if (myCartId.contains("2", ignoreCase = true)) "cart_2" else "cart_1"

                                        // Strict Cart Isolation:
                                        // Cart 1 driver only receives Cart 1 requests; Cart 2 driver only receives Cart 2 requests.
                                        if (currentRoleVal == UserRole.DRIVER && cartId != normalizedMyCart) {
                                            Log.d("CampusRideRepo", "DRIVER_FILTER_ISOLATION: Skipping request $id for cart $cartId (this driver is operating $normalizedMyCart)")
                                            continue
                                        }

                                        val request = RideRequest(
                                            id = id,
                                            requestId = id,
                                            requesterType = reqType,
                                            studentName = studentName,
                                            pickupLocation = pickupLocation,
                                            distanceToGateMeters = distance,
                                            status = status,
                                            timestamp = timestamp,
                                            createdAt = timestamp,
                                            studentsWaiting = studentsWaiting,
                                            waitingCount = studentsWaiting,
                                            assignedCartId = cartId,
                                            selectedCartId = if (cartId == "cart_2") "CART_2" else "CART_1",
                                            assignedCartName = cartName
                                        )
                                        incomingList.add(request)

                                        Log.d("CampusRideRepo", "Parsed RideRequest: id=$id, status=$status, student=$studentName, cartId=$cartId, Current role value = $currentRoleVal")

                                         // Trigger Full-Screen Alert on Driver Device for PENDING requests
                                        if (status == RideRequestStatus.PENDING && currentRoleVal == UserRole.DRIVER) {
                                            Log.d("CampusRideRepo", "Triggering critical driver alert for request ${request.id}, status = ${request.status}, currentRole = $currentRoleVal")
                                            CriticalAlertManager.triggerCriticalDriverAlert(context, request, UserRole.DRIVER)
                                        } else {
                                            Log.d("CampusRideRepo", "Not triggering critical alert: status=$status (needs PENDING), currentRole=$currentRoleVal (needs DRIVER)")
                                        }
                                    } catch (e: Exception) {
                                        Log.e("CampusRideRepo", "Error parsing Firestore ride request document", e)
                                    }
                                }

                                val activeAlert = CriticalAlertManager.activeAlertRequest.value
                                if (activeAlert != null) {
                                    val currentInList = incomingList.find { it.id == activeAlert.id }
                                    if (currentInList == null || currentInList.status != RideRequestStatus.PENDING) {
                                        Log.d("CampusRideRepo", "Active alert request ${activeAlert.id} is no longer PENDING in Firestore snapshot. Stopping alert.")
                                        CriticalAlertManager.stopAlert(context, reason = "CANCELLED / STATUS CHANGED")
                                    }
                                }

                                if (incomingList.isNotEmpty()) {
                                    _requests.value = incomingList
                                }
                            } else {
                                Log.d("CampusRideRepo", "Firestore Snapshot: Received empty snapshot")
                            }
                        }
                } catch (e: Exception) {
                    Log.e("CampusRideRepo", "Error attaching driver Firestore listener", e)
                }
            }
        }
    }

    fun startStudentRequestListener(requestId: String) {
        studentListenerRegistration?.remove()
        scope.launch(Dispatchers.IO) {
            val authRes = ensureFirebaseAuth()
            if (authRes.isFailure) {
                val err = authRes.exceptionOrNull()
                Log.e("CampusRideRepo", "Student Listener: Aborting because Firebase Auth failed: ${err?.message}", err)
                return@launch
            }
            withContext(Dispatchers.Main) {
                studentListenerRegistration?.remove()
                try {
                    val firestore = FirebaseFirestore.getInstance()
                    Log.d("CampusRideRepo", "Student Listener: Attaching listener for request $requestId...")
                    studentListenerRegistration = firestore.collection("ride_requests")
                        .document(requestId)
                        .addSnapshotListener { snapshot, error ->
                            if (error != null) {
                                Log.e("CampusRideRepo", "Student Listener Error: Error listening to request $requestId (Code: ${error.code})", error)
                                return@addSnapshotListener
                            }
                            if (snapshot == null || !snapshot.exists()) return@addSnapshotListener

                            val statusStr = snapshot.getString("status") ?: return@addSnapshotListener
                            val status = try { RideRequestStatus.valueOf(statusStr) } catch (e: Exception) { return@addSnapshotListener }

                            val pickupLocStr = snapshot.getString("pickupLocation") ?: "GATE"
                            val pickupLoc = PickupLocation.fromId(pickupLocStr)

                            val driverLat = snapshot.getDouble("driverLat")
                            val driverLng = snapshot.getDouble("driverLng")
                            val driverBearing = snapshot.getDouble("driverBearing")?.toFloat() ?: 0f

                            val previousStatus = _activeStudentRequest.value?.status

                            val updatedRequest = _activeStudentRequest.value?.copy(
                                status = status,
                                pickupLocation = pickupLoc.id,
                                driverLat = driverLat,
                                driverLng = driverLng,
                                driverBearing = driverBearing
                            )
                            _activeStudentRequest.value = updatedRequest
                            _activeFacultyRequest.value = _activeFacultyRequest.value?.copy(status = status)

                            if (status == RideRequestStatus.ACCEPTED) {
                                if (previousStatus != RideRequestStatus.ACCEPTED) {
                                    Log.d("CampusRideRepo", "Student Listener: Ride accepted! Triggering student acceptance notification.")
                                    CriticalAlertManager.triggerStudentAcceptanceNotification(context, _currentRole.value)
                                }
                            }
                        }
                } catch (e: Exception) {
                    Log.e("CampusRideRepo", "Error attaching student request listener", e)
                }
            }
        }
    }

    fun checkAndRestoreLunchBreak() {
        val today = CampusTimeUtils.getTodayCampusDate()
        val savedUsedDate = prefs.getString("pref_lunch_break_used_date", null)
        _lunchBreakUsedDate.value = savedUsedDate
        val isUsedToday = (savedUsedDate == today)
        _isLunchBreakUsedToday.value = isUsedToday

        val savedEndTime = prefs.getLong("pref_lunch_break_end_time", 0L)
        if (savedEndTime > 0L) {
            val now = System.currentTimeMillis()
            if (now < savedEndTime) {
                val remainingSecs = ((savedEndTime - now) / 1000L).toInt()
                _lunchBreakEndTimeMs.value = savedEndTime
                _lunchBreakRemainingSeconds.value = remainingSecs
                setDriverDutyState("Lunch Break")
                startLunchBreakCountdown(savedEndTime)
                com.example.location.DriverLocationService.stopTrip(context)
            } else {
                clearActiveLunchBreakCountdownOnly()
                if (_driverDutyState.value == "Lunch Break") {
                    setDriverDutyState("Available")
                }
            }
        }
    }

    suspend fun fetchServerAuthoritativeLunchBreakState(): Boolean = withContext(Dispatchers.IO) {
        val today = CampusTimeUtils.getTodayCampusDate()
        try {
            ensureFirebaseAuth()
            val firestore = FirebaseFirestore.getInstance()
            val doc = firestore.collection("campus_config").document("lunch_break").get().awaitTask()
            if (doc.exists()) {
                val serverUsedDate = doc.getString("lastUsedDate")
                val endTimeMs = doc.getLong("endTimeMs") ?: 0L
                val now = System.currentTimeMillis()

                if (serverUsedDate == today) {
                    _isLunchBreakUsedToday.value = true
                    _lunchBreakUsedDate.value = today
                    prefs.edit().putString("pref_lunch_break_used_date", today).commit()

                    if (endTimeMs > now) {
                        val remainingSecs = ((endTimeMs - now) / 1000L).toInt()
                        _lunchBreakEndTimeMs.value = endTimeMs
                        _lunchBreakRemainingSeconds.value = remainingSecs
                        prefs.edit()
                            .putLong("pref_lunch_break_end_time", endTimeMs)
                            .putString("saved_driver_duty_state", "Lunch Break")
                            .commit()
                        withContext(Dispatchers.Main) {
                            setDriverDutyState("Lunch Break")
                        }
                        startLunchBreakCountdown(endTimeMs)
                        com.example.location.DriverLocationService.stopTrip(context)
                        Log.d("CampusRideRepo", "Restored active Lunch Break from server: $remainingSecs s remaining")
                        return@withContext true
                    } else {
                        clearActiveLunchBreakCountdownOnly()
                        if (_driverDutyState.value == "Lunch Break") {
                            withContext(Dispatchers.Main) {
                                setDriverDutyState("Available")
                            }
                        }
                    }
                } else if (!serverUsedDate.isNullOrBlank()) {
                    _isLunchBreakUsedToday.value = false
                    _lunchBreakUsedDate.value = null
                    clearActiveLunchBreakCountdownOnly()
                    prefs.edit().remove("pref_lunch_break_used_date").remove("pref_lunch_break_end_time").commit()
                }
            }
        } catch (e: Exception) {
            Log.w("CampusRideRepo", "Could not fetch lunch_break document from server: ${e.message}")
        }
        false
    }

    suspend fun fetchServerAuthoritativeDriverDutyState(): Boolean = withContext(Dispatchers.IO) {
        try {
            ensureFirebaseAuth()
            val firestore = FirebaseFirestore.getInstance()
            val activeCartId = _selectedDriverCartId.value
            val doc = firestore.collection("drivers").document(activeCartId).get().awaitTask()
            if (doc.exists()) {
                val serverManualOffDuty = doc.getBoolean("manualOffDuty") ?: false
                _manualDutyOverride.value = serverManualOffDuty
                prefs.edit().putBoolean("pref_manual_duty_override", serverManualOffDuty).apply()

                if (serverManualOffDuty) {
                    _driverDutyState.value = "Off Duty"
                    _effectiveDutyStatus.value = "OFF_DUTY"
                    _isDriverAvailable.value = false
                    prefs.edit().putString("saved_driver_duty_state", "Off Duty").apply()
                    com.example.location.DriverLocationService.stopTrip(context)
                    Log.d("CampusRideRepo", "Restored server authoritative Driver Duty: MANUAL OFF DUTY (override=true)")
                    return@withContext true
                } else {
                    if (_driverDutyState.value != "Lunch Break") {
                        val isInside = _isInsideGeofence.value || _isInsideCampus.value
                        if (isInside) {
                            _driverDutyState.value = "Available"
                            _effectiveDutyStatus.value = "ON_DUTY"
                            _isDriverAvailable.value = true
                            prefs.edit().putString("saved_driver_duty_state", "Available").apply()
                        } else {
                            _driverDutyState.value = "Outside Campus"
                            _effectiveDutyStatus.value = "OUTSIDE_CAMPUS"
                            _isDriverAvailable.value = false
                        }
                    }
                    if (PermissionUtils.hasPreciseLocationPermission(context)) {
                        com.example.location.DriverLocationService.startTrip(context, activeCartId)
                    }
                    Log.d("CampusRideRepo", "Restored server authoritative Driver Duty: AUTOMATIC (status=${_driverDutyState.value})")
                    return@withContext true
                }
            }
        } catch (e: Exception) {
            Log.w("CampusRideRepo", "Could not fetch driver duty state from server: ${e.message}")
        }
        false
    }

    fun setManualOffDuty(isOffDuty: Boolean) {
        _manualDutyOverride.value = isOffDuty
        prefs.edit().putBoolean("pref_manual_duty_override", isOffDuty).apply()
        val activeCartId = _selectedDriverCartId.value

        if (isOffDuty) {
            _driverDutyState.value = "Off Duty"
            _effectiveDutyStatus.value = "OFF_DUTY"
            _isDriverAvailable.value = false
            prefs.edit().putString("saved_driver_duty_state", "Off Duty").apply()
            com.example.location.DriverLocationService.stopTrip(context)

            val existing = if (activeCartId == "cart_1") _cart1State.value else _cart2State.value
            val updated = existing.copy(
                isAvailable = false,
                driverStatus = "Offline",
                status = GolfCartStatus.OFFLINE
            )
            if (activeCartId == "cart_1") _cart1State.value = updated else _cart2State.value = updated
            _fleetCarts.value = listOf(_cart1State.value, _cart2State.value)
            _golfCartState.value = updated
        } else {
            val isInside = _isInsideCampus.value || _isInsideGeofence.value
            if (isInside && _driverDutyState.value != "Lunch Break") {
                _driverDutyState.value = "Available"
                _effectiveDutyStatus.value = "ON_DUTY"
                _isDriverAvailable.value = true
                prefs.edit().putString("saved_driver_duty_state", "Available").apply()
                val existing = if (activeCartId == "cart_1") _cart1State.value else _cart2State.value
                val updated = existing.copy(
                    isAvailable = true,
                    driverStatus = "Available",
                    status = GolfCartStatus.HALTED
                )
                if (activeCartId == "cart_1") _cart1State.value = updated else _cart2State.value = updated
                _fleetCarts.value = listOf(_cart1State.value, _cart2State.value)
                _golfCartState.value = updated
            } else {
                _driverDutyState.value = "Outside Campus"
                _effectiveDutyStatus.value = "OUTSIDE_CAMPUS"
                _isDriverAvailable.value = false
            }
            if (PermissionUtils.hasPreciseLocationPermission(context)) {
                com.example.location.DriverLocationService.startTrip(context, activeCartId)
            }
        }
        startDriverHeartbeat()
        reevaluateEffectiveDriverAvailability()

        scope.launch(Dispatchers.IO) {
            try {
                ensureFirebaseAuth()
                val firestore = FirebaseFirestore.getInstance()
                val isAvailable = !isOffDuty && (_isInsideCampus.value || _isInsideGeofence.value)
                val statusStr = if (isOffDuty) "Offline" else if (isAvailable) "Available" else "Outside Campus"
                val cartStatus = if (isOffDuty || !isAvailable) GolfCartStatus.OFFLINE.name else GolfCartStatus.HALTED.name

                val doc = mapOf(
                    "cartId" to activeCartId,
                    "cartName" to (if (activeCartId == "cart_1") "Cart 1" else "Cart 2"),
                    "manualOffDuty" to isOffDuty,
                    "onDuty" to !isOffDuty,
                    "isOnline" to !isOffDuty,
                    "isAvailable" to isAvailable,
                    "driverStatus" to statusStr,
                    "status" to cartStatus,
                    "lastUpdatedMillis" to System.currentTimeMillis(),
                    "last_seen" to System.currentTimeMillis()
                )
                firestore.collection("drivers").document(activeCartId).set(doc, SetOptions.merge())
                Log.d("CampusRideRepo", "MANUAL_DUTY_OVERRIDE: Pushed manualOffDuty=$isOffDuty to drivers/$activeCartId")
            } catch (e: Exception) {
                Log.w("CampusRideRepo", "Error syncing manual off duty state to server: ${e.message}")
            }
        }
    }

    fun resumeAutomaticDuty() {
        setManualOffDuty(false)
    }

    suspend fun startLunchBreak(): Result<Boolean> = withContext(Dispatchers.IO) {
        lunchBreakMutex.withLock {
            val today = CampusTimeUtils.getTodayCampusDate()
            val localUsedDate = prefs.getString("pref_lunch_break_used_date", null)
            if (localUsedDate == today) {
                Log.w("CampusRideRepo", "Lunch break already consumed for today ($today). Second attempt blocked.")
                return@withLock Result.failure(IllegalStateException("Today's lunch break has already been used."))
            }

            val activeAcceptedReq = _requests.value.find { it.status == RideRequestStatus.ACCEPTED }
            if (activeAcceptedReq != null) {
                return@withLock Result.failure(IllegalStateException("Cannot start lunch break while on an active ride."))
            }

            val now = System.currentTimeMillis()
            val endTime = now + 2700_000L // 45 minutes
            val activeCartId = _selectedDriverCartId.value

            // Concurrency Protection: Atomic Firestore Transaction
            try {
                ensureFirebaseAuth()
                val firestore = FirebaseFirestore.getInstance()
                val docRef = firestore.collection("campus_config").document("lunch_break")

                firestore.runTransaction { transaction ->
                    val snapshot = transaction.get(docRef)
                    if (snapshot.exists()) {
                        val serverUsedDate = snapshot.getString("lastUsedDate")
                        if (serverUsedDate == today) {
                            throw IllegalStateException("Today's lunch break has already been used.")
                        }
                    }
                    val data = mapOf(
                        "lastUsedDate" to today,
                        "activatedTimestamp" to now,
                        "endTimeMs" to endTime,
                        "activatedByCartId" to activeCartId,
                        "activatedAt" to FieldValue.serverTimestamp()
                    )
                    transaction.set(docRef, data, SetOptions.merge())
                }.awaitTask()
            } catch (e: Exception) {
                Log.e("CampusRideRepo", "Atomic lunch break transaction error: ${e.message}", e)
                if (e.message?.contains("already been used") == true) {
                    prefs.edit().putString("pref_lunch_break_used_date", today).commit()
                    _lunchBreakUsedDate.value = today
                    _isLunchBreakUsedToday.value = true
                    return@withLock Result.failure(e)
                }
            }

            // Persist locally synchronously for immediate offline/restart resilience
            prefs.edit()
                .putString("pref_lunch_break_used_date", today)
                .putLong("pref_lunch_break_end_time", endTime)
                .putString("saved_driver_duty_state", "Lunch Break")
                .commit()

            _lunchBreakUsedDate.value = today
            _isLunchBreakUsedToday.value = true
            _lunchBreakEndTimeMs.value = endTime
            _lunchBreakRemainingSeconds.value = 2700

            withContext(Dispatchers.Main) {
                setDriverDutyState("Lunch Break")
                com.example.location.DriverLocationService.stopTrip(context)
            }
            startLunchBreakCountdown(endTime)

            // Update driver cart status in Firestore
            try {
                val firestore = FirebaseFirestore.getInstance()
                firestore.collection("drivers").document(activeCartId).set(
                    mapOf(
                        "driverStatus" to "Lunch Break",
                        "isAvailable" to false,
                        "onDuty" to false,
                        "lastUpdatedMillis" to System.currentTimeMillis()
                    ),
                    SetOptions.merge()
                )
            } catch (e: Exception) {
                Log.w("CampusRideRepo", "Error updating driver lunch status doc: ${e.message}")
            }

            Log.d("CampusRideRepo", "LUNCH BREAK ACTIVATED: Successfully recorded for date $today")
            Result.success(true)
        }
    }

    fun endLunchBreakEarly() {
        clearActiveLunchBreakCountdownOnly()
        setDriverDutyState("Available")
        val activeCartId = _selectedDriverCartId.value
        scope.launch(Dispatchers.IO) {
            try {
                ensureFirebaseAuth()
                val firestore = FirebaseFirestore.getInstance()
                firestore.collection("campus_config").document("lunch_break").update(
                    mapOf("endTimeMs" to System.currentTimeMillis())
                )
                firestore.collection("drivers").document(activeCartId).set(
                    mapOf(
                        "driverStatus" to "Available",
                        "isAvailable" to true,
                        "onDuty" to true,
                        "lastUpdatedMillis" to System.currentTimeMillis()
                    ),
                    SetOptions.merge()
                )
                Log.d("CampusRideRepo", "Ended lunch break early for cart $activeCartId")
            } catch (e: Exception) {
                Log.w("CampusRideRepo", "Error ending lunch break early: ${e.message}")
            }
        }
    }

    fun triggerLunchBreak(onComplete: (Result<Boolean>) -> Unit = {}) {
        scope.launch(Dispatchers.IO) {
            val result = startLunchBreak()
            withContext(Dispatchers.Main) {
                onComplete(result)
            }
        }
    }

    private fun startLunchBreakCountdown(endTimeMs: Long) {
        lunchBreakJob?.cancel()
        lunchBreakJob = scope.launch {
            while (true) {
                val now = System.currentTimeMillis()
                val remainingMs = endTimeMs - now
                if (remainingMs <= 0) {
                    clearActiveLunchBreakCountdownOnly()
                    setDriverDutyState("Available")
                    break
                } else {
                    _lunchBreakRemainingSeconds.value = (remainingMs / 1000L).toInt()
                    delay(1000L)
                }
            }
        }
    }

    private fun clearActiveLunchBreakCountdownOnly() {
        prefs.edit().remove("pref_lunch_break_end_time").apply()
        _lunchBreakEndTimeMs.value = 0L
        _lunchBreakRemainingSeconds.value = 0
        lunchBreakJob?.cancel()
        lunchBreakJob = null
    }

    private fun startLunchBreakSyncListener() {
        lunchBreakListenerRegistration?.remove()
        scope.launch(Dispatchers.IO) {
            val authRes = ensureFirebaseAuth()
            if (authRes.isFailure) return@launch
            withContext(Dispatchers.Main) {
                try {
                    val firestore = FirebaseFirestore.getInstance()
                    lunchBreakListenerRegistration = firestore.collection("campus_config")
                        .document("lunch_break")
                        .addSnapshotListener { snapshot, error ->
                            if (error != null || snapshot == null || !snapshot.exists()) return@addSnapshotListener
                            val cloudUsedDate = snapshot.getString("lastUsedDate")
                            val today = CampusTimeUtils.getTodayCampusDate()
                            if (!cloudUsedDate.isNullOrBlank()) {
                                if (cloudUsedDate == today) {
                                    prefs.edit().putString("pref_lunch_break_used_date", cloudUsedDate).apply()
                                    _lunchBreakUsedDate.value = cloudUsedDate
                                    _isLunchBreakUsedToday.value = true

                                    val activeEndTime = snapshot.getLong("endTimeMs") ?: 0L
                                    val now = System.currentTimeMillis()
                                    if (activeEndTime > now && _lunchBreakEndTimeMs.value == 0L) {
                                        val remainingSecs = ((activeEndTime - now) / 1000L).toInt()
                                        _lunchBreakEndTimeMs.value = activeEndTime
                                        _lunchBreakRemainingSeconds.value = remainingSecs
                                        setDriverDutyState("Lunch Break")
                                        startLunchBreakCountdown(activeEndTime)
                                    }
                                } else {
                                    if (_lunchBreakUsedDate.value != today) {
                                        _isLunchBreakUsedToday.value = false
                                    }
                                }
                            }
                        }
                } catch (e: Exception) {
                    Log.w("CampusRideRepo", "Error attaching lunch break sync listener: ${e.message}")
                }
            }
        }
    }

    private fun startDailyResetTicker() {
        scope.launch(Dispatchers.Default) {
            while (true) {
                delay(5 * 60 * 1000L) // Check every 5 minutes for midnight rollover (battery optimization)
                val today = CampusTimeUtils.getTodayCampusDate()
                val isUsedToday = (_lunchBreakUsedDate.value == today)
                if (_isLunchBreakUsedToday.value != isUsedToday) {
                    _isLunchBreakUsedToday.value = isUsedToday
                }
                val endTime = _lunchBreakEndTimeMs.value
                if (endTime > 0L && System.currentTimeMillis() >= endTime) {
                    clearActiveLunchBreakCountdownOnly()
                    if (_driverDutyState.value == "Lunch Break") {
                        setDriverDutyState("Available")
                    }
                }
            }
        }
    }

    private val _driverDutyState = MutableStateFlow(
        prefs.getString("saved_driver_duty_state", "Available") ?: "Available"
    )
    val driverDutyState: StateFlow<String> = _driverDutyState.asStateFlow()

    private var heartbeatJob: Job? = null

    fun setDriverDutyState(status: String) {
        _driverDutyState.value = status
        prefs.edit().putString("saved_driver_duty_state", status).apply()
        if (status == "Lunch Break" || status == "Off Duty") {
            com.example.location.DriverLocationService.stopTrip(context)
        }
        startDriverHeartbeat()
        reevaluateEffectiveDriverAvailability()

        // Immediately push updated duty state to Firestore drivers collection
        val activeCartId = _selectedDriverCartId.value
        scope.launch(Dispatchers.IO) {
            try {
                ensureFirebaseAuth()
                val firestore = FirebaseFirestore.getInstance()
                val isDutyAvailable = (status == "Available")
                val isInside = _isInsideGeofence.value
                val hasGps = _hasGpsLocation.value
                val activeAcceptedReq = _requests.value.find { it.status == RideRequestStatus.ACCEPTED }
                val isBusy = activeAcceptedReq != null
                val effectiveAvailable = isDutyAvailable && (!hasGps || isInside) && !isBusy

                val displayStatus = when {
                    status == "Lunch Break" -> "Lunch Break"
                    status == "Off Duty" -> "Offline"
                    isBusy -> "On Trip"
                    effectiveAvailable -> "Available"
                    else -> status
                }

                val doc = mapOf(
                    "cartId" to activeCartId,
                    "cartName" to (if (activeCartId == "cart_1") "Cart 1" else "Cart 2"),
                    "isOnline" to (status != "Off Duty"),
                    "onDuty" to isDutyAvailable,
                    "isAvailable" to effectiveAvailable,
                    "sessionId" to _driverSessionId.value,
                    "driverStatus" to displayStatus,
                    "status" to (if (status == "Off Duty") GolfCartStatus.OFFLINE.name else GolfCartStatus.HALTED.name),
                    "last_seen" to System.currentTimeMillis(),
                    "lastUpdatedMillis" to System.currentTimeMillis(),
                    "latitude" to (_driverLatitude.value ?: GeofenceManager.LIBRARY_LAT),
                    "longitude" to (_driverLongitude.value ?: GeofenceManager.LIBRARY_LNG)
                )
                firestore.collection("drivers")
                    .document(activeCartId)
                    .set(doc, SetOptions.merge())
                Log.d("CAMPUS_RIDE_AVAILABILITY", "DRIVER_DUTY_SYNC: Updated duty state to '$status' (isAvailable=$effectiveAvailable, driverStatus=$displayStatus) on drivers/$activeCartId")
            } catch (e: Exception) {
                Log.w("CampusRideRepo", "Error syncing driver duty status: ${e.message}")
            }
        }
    }

    fun startDriverHeartbeat() {
        if (heartbeatJob?.isActive == true) return
        heartbeatJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    val activeCartId = _selectedDriverCartId.value
                    val currentRoleVal = _currentRole.value
                    if (currentRoleVal == UserRole.DRIVER) {
                        val now = System.currentTimeMillis()
                        val lastSyncTime = if (activeCartId == "cart_1") cart1LastSyncTime else cart2LastSyncTime

                        // Battery Optimization: If a fresh location update was synced to Firestore within the
                        // last heartbeat interval window, skip this heartbeat tick since the document is already completely fresh
                        if (now - lastSyncTime < TrackingConfig.Network.MAX_HEARTBEAT_INTERVAL_MS) {
                            delay(GolfCartState.HEARTBEAT_INTERVAL_MS)
                            continue
                        }

                        ensureFirebaseAuth()
                        val firestore = FirebaseFirestore.getInstance()
                        val isDutyAvailable = (_driverDutyState.value == "Available")
                        val isInside = _isInsideGeofence.value
                        val hasGps = _hasGpsLocation.value
                        val activeAcceptedReq = _requests.value.find { it.status == RideRequestStatus.ACCEPTED }
                        val isBusy = activeAcceptedReq != null
                        val effectiveAvailable = isDutyAvailable && (!hasGps || isInside) && !isBusy

                        val displayStatus = when {
                            _driverDutyState.value == "Lunch Break" -> "Lunch Break"
                            _driverDutyState.value == "Off Duty" -> "Offline"
                            isBusy -> "On Trip"
                            effectiveAvailable -> "Available"
                            else -> _driverDutyState.value
                        }

                        val isOnline = (_driverDutyState.value != "Off Duty")
                        val heartbeatDoc = mutableMapOf<String, Any>(
                            "cartId" to activeCartId,
                            "cartName" to (if (activeCartId == "cart_1") "Cart 1" else "Cart 2"),
                            "isOnline" to isOnline,
                            "onDuty" to isDutyAvailable,
                            "insideCampus" to isInside,
                            "isBusy" to isBusy,
                            "isAvailable" to effectiveAvailable,
                            "sessionId" to _driverSessionId.value,
                            "driverStatus" to displayStatus,
                            "status" to (if (!isOnline) GolfCartStatus.OFFLINE.name else GolfCartStatus.HALTED.name),
                            "last_seen" to now,
                            "lastUpdatedMillis" to now,
                            "lastHeartbeatMillis" to now
                        )
                        if (_driverLatitude.value != null && _driverLongitude.value != null) {
                            heartbeatDoc["latitude"] = _driverLatitude.value!!
                            heartbeatDoc["longitude"] = _driverLongitude.value!!
                            heartbeatDoc["locationTimestampMillis"] = now
                        }

                        firestore.collection("drivers")
                            .document(activeCartId)
                            .set(heartbeatDoc, SetOptions.merge())
                        Log.d("CAMPUS_RIDE_AVAILABILITY", "HEARTBEAT_TICK: Heartbeat published for drivers/$activeCartId (isAvailable=$effectiveAvailable, driverStatus=$displayStatus)")

                        if (activeCartId == "cart_1") {
                            cart1LastSyncTime = now
                        } else {
                            cart2LastSyncTime = now
                        }

                        try {
                            CampusBackendClient.api.sendHeartbeat(
                                CartHeartbeatRequest(
                                    cartId = activeCartId,
                                    driverStatus = displayStatus,
                                    isOnline = isOnline,
                                    isAvailable = effectiveAvailable
                                )
                            )
                        } catch (e: Exception) {
                            // Backend REST fallback optional
                        }
                    }
                } catch (e: Exception) {
                    Log.w("CampusRideRepo", "Heartbeat sync error: ${e.message}")
                }
                delay(GolfCartState.HEARTBEAT_INTERVAL_MS)
            }
        }
    }

    fun updateDriverGpsLocation(
        lat: Double,
        lng: Double,
        speedKmH: Int = 0,
        bearing: Float = 0f,
        accuracy: Float = 0f,
        cartId: String? = null
    ) {
        // Telemetry Validation & Anti-Spoofing Bounds Filter
        if (lat < 25.0 || lat > 26.0 || lng < 86.8 || lng > 87.3) {
            Log.w("CampusRideRepo", "SECURITY: Driver GPS location update rejected - coordinates outside campus bounds ($lat, $lng)")
            return
        }
        val clampedSpeed = speedKmH.coerceIn(0, 45)
        val clampedBearing = (bearing % 360f + 360f) % 360f

        _driverLatitude.value = lat
        _driverLongitude.value = lng
        _hasGpsLocation.value = true
        _driverGpsAccuracyMeters.value = accuracy

        val distMeters = GeofenceManager.calculateDistanceFromLibraryMeters(lat, lng)
        _distanceToLibraryMeters.value = distMeters

        // Automatic Campus Geofence Entry Detection & GPS Accuracy Validation
        val isAccuracyAcceptable = GeofenceManager.isGpsAccuracyValid(accuracy)
        var activeCartId = cartId ?: _selectedDriverCartId.value
        val isInside = GeofenceManager.evaluateDriverCampusPresence(lat, lng, accuracy, activeCartId)
        _isInsideGeofence.value = isInside
        _isInsideCampus.value = isInside

        // Check if currently assigned to an active accepted ride
        val activeAcceptedReq = _requests.value.find { it.status == RideRequestStatus.ACCEPTED }
        val isAssignedToRide = activeAcceptedReq != null

        val isManualOff = _manualDutyOverride.value
        val isLunch = (_driverDutyState.value == "Lunch Break")

        val isOnDuty = if (isManualOff) {
            false
        } else if (isLunch) {
            false
        } else {
            // Inside IIIT Bhagalpur campus -> Automatically ON DUTY
            isInside
        }

        if (isManualOff) {
            _driverDutyState.value = "Off Duty"
            _effectiveDutyStatus.value = "OFF_DUTY"
        } else if (isLunch) {
            _effectiveDutyStatus.value = "LUNCH_BREAK"
        } else if (isInside) {
            _driverDutyState.value = "Available"
            _effectiveDutyStatus.value = "ON_DUTY"
        } else {
            _driverDutyState.value = "Driver Not Available"
            _effectiveDutyStatus.value = "OUTSIDE_CAMPUS"
        }

        val effectiveAvailable = isOnDuty && isInside && !isAssignedToRide

        // Real-time Driver Cart Assignment & Geofence Evaluation
        val effectiveCart = cartId ?: _selectedDriverCartId.value
        activeCartId = effectiveCart

        if (isInside) {
            _isDriverAvailable.value = effectiveAvailable
            Log.d("CampusRideRepo", "DRIVER GPS UPDATE: Driver inside campus -> Cart $activeCartId -> isAvailable=$effectiveAvailable (OnDuty=$isOnDuty, Busy=$isAssignedToRide, manualOff=$isManualOff)")
        } else {
            // Driver is outside campus geofence -> Driver Not Available
            _isDriverAvailable.value = false
            Log.d("CampusRideRepo", "DRIVER GPS UPDATE: Driver outside campus -> Cart $activeCartId -> isAvailable=false (Driver Not Available)")
        }

        val evaluated = com.example.location.CampusLandmarkZone.evaluateRoutePosition(
            latitude = lat,
            longitude = lng,
            bearing = clampedBearing,
            speedKmH = clampedSpeed,
            accuracy = accuracy,
            cartId = activeCartId
        )

        val distToGate = GeofenceManager.calculateDistanceMeters(lat, lng, GeofenceManager.GATE_LAT, GeofenceManager.GATE_LNG).roundToInt()
        val cartStatus = if (isManualOff || !isInside) GolfCartStatus.OFFLINE else if (speedKmH > 0) GolfCartStatus.MOVING else GolfCartStatus.HALTED
        val driverStatusString = if (isManualOff) "Offline" else if (isLunch) "Lunch Break" else if (isAssignedToRide) "On Trip" else if (!isInside) "Driver Not Available" else if (effectiveAvailable) "Available" else if (isOnDuty) "Unavailable" else "Driver Not Available"

        val targetFlow = if (activeCartId == "cart_1") _cart1State else _cart2State
        val existing = targetFlow.value

        val now = System.currentTimeMillis()
        val updatedCart = existing.copy(
            cartId = activeCartId,
            cartName = if (activeCartId == "cart_1") "Cart 1" else "Cart 2",
            latitude = lat,
            longitude = lng,
            speedKmH = speedKmH,
            bearing = bearing,
            accuracy = accuracy,
            status = cartStatus,
            isTripActive = isAssignedToRide,
            isAvailable = effectiveAvailable,
            driverStatus = driverStatusString,
            lastUpdatedMillis = now,
            lastHeartbeatMillis = now,
            locationTimestampMillis = now,
            distanceToGateMeters = distToGate,
            distanceToUserMeters = distToGate,
            direction = evaluated?.directionSummary,
            currentStop = evaluated?.currentStopName,
            nextStop = evaluated?.nextStopName
        )

        targetFlow.value = updatedCart
        _fleetCarts.value = listOf(_cart1State.value, _cart2State.value)
        if (_selectedDriverCartId.value == activeCartId) {
            _golfCartState.value = updatedCart
        }

        // Battery Optimization: Evaluate whether a remote network write (Firestore/REST) is justified
        val lastSyncTime = if (activeCartId == "cart_1") cart1LastSyncTime else cart2LastSyncTime
        val lastSyncLat = if (activeCartId == "cart_1") cart1LastSyncLat else cart2LastSyncLat
        val lastSyncLng = if (activeCartId == "cart_1") cart1LastSyncLng else cart2LastSyncLng
        val lastSyncBearing = if (activeCartId == "cart_1") cart1LastSyncBearing else cart2LastSyncBearing
        val lastSyncStatus = if (activeCartId == "cart_1") cart1LastSyncStatus else cart2LastSyncStatus
        val lastSyncAvailable = if (activeCartId == "cart_1") cart1LastSyncAvailable else cart2LastSyncAvailable
        val lastSyncTripActive = if (activeCartId == "cart_1") cart1LastSyncTripActive else cart2LastSyncTripActive

        val distMoved = if (lastSyncLat != 0.0 && lastSyncLng != 0.0) {
            GeofenceManager.calculateDistanceMeters(lat, lng, lastSyncLat, lastSyncLng)
        } else {
            Double.MAX_VALUE
        }

        val bearingDiff = kotlin.math.abs(bearing - lastSyncBearing)
        val isSignificantHeadingChange = bearingDiff >= TrackingConfig.Network.HEADING_CHANGE_THRESHOLD_DEG && distMoved >= 3.0 && speedKmH >= 4
        val isSignificantMovement = distMoved >= TrackingConfig.Network.MIN_UPDATE_DISTANCE_M
        val isStatusChanged = (lastSyncStatus != driverStatusString) ||
                              (lastSyncAvailable != effectiveAvailable) ||
                              (lastSyncTripActive != isAssignedToRide)
        val isKeepAliveDue = (now - lastSyncTime >= TrackingConfig.Network.MAX_HEARTBEAT_INTERVAL_MS)
        val isAllowedByBackoff = (now >= nextAllowedNetworkSyncTimeMs)

        val shouldSyncRemotely = isAllowedByBackoff && ((lastSyncTime == 0L) || isStatusChanged || isSignificantMovement || isSignificantHeadingChange || isKeepAliveDue)

        if (!shouldSyncRemotely) {
            TrackingConfig.Diagnostics.networkSyncSkippedCount.incrementAndGet()
        }

        if (shouldSyncRemotely) {
            TrackingConfig.Diagnostics.networkSyncCount.incrementAndGet()
            if (activeCartId == "cart_1") {
                cart1LastSyncTime = now
                cart1LastSyncLat = lat
                cart1LastSyncLng = lng
                cart1LastSyncBearing = bearing
                cart1LastSyncStatus = driverStatusString
                cart1LastSyncAvailable = effectiveAvailable
                cart1LastSyncTripActive = isAssignedToRide
            } else {
                cart2LastSyncTime = now
                cart2LastSyncLat = lat
                cart2LastSyncLng = lng
                cart2LastSyncBearing = bearing
                cart2LastSyncStatus = driverStatusString
                cart2LastSyncAvailable = effectiveAvailable
                cart2LastSyncTripActive = isAssignedToRide
            }

            // Broadcast throttled continuous Live Driver Cart sync to Firestore drivers collection
            scope.launch(Dispatchers.IO) {
                try {
                    ensureFirebaseAuth()
                    val firestore = FirebaseFirestore.getInstance()
                    val driverDoc = mapOf(
                        "cartId" to activeCartId,
                        "cartName" to (if (activeCartId == "cart_1") "Cart 1" else "Cart 2"),
                        "latitude" to lat,
                        "longitude" to lng,
                        "bearing" to bearing,
                        "speedKmH" to speedKmH,
                        "accuracy" to accuracy,
                        "status" to cartStatus.name,
                        "isTripActive" to isAssignedToRide,
                        "isAvailable" to effectiveAvailable,
                        "onDuty" to isOnDuty,
                        "manualOffDuty" to isManualOff,
                        "isOnline" to (!isManualOff && isInside),
                        "isBusy" to isAssignedToRide,
                        "insideCampus" to isInside,
                        "sessionId" to _driverSessionId.value,
                        "driverStatus" to driverStatusString,
                        "direction" to (evaluated?.directionSummary ?: "In Transit"),
                        "currentStop" to (evaluated?.currentStopName ?: "In Transit"),
                        "nextStop" to (evaluated?.nextStopName ?: "Next Stop"),
                        "lastUpdatedMillis" to now,
                        "last_seen" to now,
                        "lastHeartbeatMillis" to now,
                        "locationTimestampMillis" to now,
                        "distanceToGateMeters" to distToGate
                    )
                    firestore.collection("drivers")
                        .document(activeCartId)
                        .set(driverDoc, SetOptions.merge())

                    try {
                        CampusBackendClient.api.updateCartLocation(
                            LocationUpdateRequest(
                                cartId = activeCartId,
                                latitude = lat,
                                longitude = lng,
                                speedKmH = speedKmH,
                                bearing = bearing
                            )
                        )
                        if (isStatusChanged) {
                            CampusBackendClient.api.updateDutyStatus(
                                com.example.data.api.DutyStatusRequest(
                                    cartId = activeCartId,
                                    driverStatus = driverStatusString
                                )
                            )
                        }
                    } catch (e: Exception) {
                        // Backend REST location sync notice
                    }
                    consecutiveNetworkErrors = 0
                    nextAllowedNetworkSyncTimeMs = 0L
                } catch (e: Exception) {
                    consecutiveNetworkErrors++
                    val backoff = (TrackingConfig.Network.BACKOFF_INITIAL_MS * Math.pow(TrackingConfig.Network.BACKOFF_MULTIPLIER, (consecutiveNetworkErrors - 1).toDouble())).toLong().coerceAtMost(TrackingConfig.Network.BACKOFF_MAX_MS)
                    nextAllowedNetworkSyncTimeMs = System.currentTimeMillis() + backoff
                    Log.w("CampusRideRepo", "Sync driver live GPS to Firestore notice (retry backoff ${backoff}ms): ${e.message}")
                }
            }
        }

        // Real-time Driver GPS stream to active ACCEPTED ride request if present (throttled to 4m or 10s)
        if (activeAcceptedReq != null) {
            val distRideMoved = if (lastRideRequestSyncLat != 0.0 && lastRideRequestSyncLng != 0.0) {
                GeofenceManager.calculateDistanceMeters(lat, lng, lastRideRequestSyncLat, lastRideRequestSyncLng)
            } else {
                Double.MAX_VALUE
            }
            val timeSinceRideSync = now - lastRideRequestSyncTime
            if (distRideMoved >= 4.0 || timeSinceRideSync >= 10_000L) {
                lastRideRequestSyncTime = now
                lastRideRequestSyncLat = lat
                lastRideRequestSyncLng = lng
                scope.launch(Dispatchers.IO) {
                    try {
                        ensureFirebaseAuth()
                        FirebaseFirestore.getInstance()
                            .collection("ride_requests")
                            .document(activeAcceptedReq.id)
                            .update(
                                mapOf(
                                    "driverLat" to lat,
                                    "driverLng" to lng,
                                    "driverBearing" to bearing,
                                    "driverSpeedMps" to ((speedKmH * 1000f) / 3600f),
                                    "driverLocationUpdatedAt" to System.currentTimeMillis()
                                )
                            )
                    } catch (e: Exception) {
                        Log.w("CampusRideRepo", "Sync driver live GPS to active ride notice: ${e.message}")
                    }
                }
            }
        }
    }

    fun onGpsDisabledOrPermissionMissing() {
        _hasGpsLocation.value = false
        _isInsideGeofence.value = false
        _distanceToLibraryMeters.value = null
        reevaluateEffectiveDriverAvailability()
    }

    fun reevaluateEffectiveDriverAvailability() {
        if (_currentRole.value != UserRole.DRIVER) {
            // For Students and Faculty, driver availability is derived directly from the real-time fleet state
            val anyFleetAvailable = _fleetCarts.value.any { 
                it.isInsideCampus && !it.isOutsideCampus &&
                (it.isLive || it.isDriverOnline || (it.isAvailable && !it.driverStatus.equals("Offline", ignoreCase = true))) && 
                !it.driverStatus.equals("Lunch Break", ignoreCase = true) &&
                !it.driverStatus.equals("Outside Campus", ignoreCase = true) &&
                !it.driverStatus.equals("Driver Not Available", ignoreCase = true)
            }
            _isDriverAvailable.value = anyFleetAvailable
            Log.d("CAMPUS_RIDE_AVAILABILITY", "RIDER_ROLE (${_currentRole.value}): Evaluated fleet availability = $anyFleetAvailable")
            return
        }

        val manualOnDuty = (_driverDutyState.value == "Available")
        val activeAcceptedReq = _requests.value.find { it.status == RideRequestStatus.ACCEPTED }
        val isOccupied = activeAcceptedReq != null || _driverDutyState.value == "Occupied" || _driverDutyState.value == "On Trip"
        val isInside = _isInsideGeofence.value || _isInsideCampus.value

        // Driver is available if: On Duty ("Available") AND inside campus (or GPS initializing) AND not occupied
        val effectiveAvailable = manualOnDuty && (!_hasGpsLocation.value || isInside) && !isOccupied && isInside

        _isDriverAvailable.value = effectiveAvailable
        val displayStatus = when {
            _driverDutyState.value == "Lunch Break" -> "Lunch Break"
            _driverDutyState.value == "Off Duty" -> "Offline"
            isOccupied -> "On Trip"
            !isInside && _hasGpsLocation.value -> "Driver Not Available"
            _driverDutyState.value == "Outside Campus" || _driverDutyState.value == "Driver Not Available" -> "Driver Not Available"
            effectiveAvailable -> "Available"
            else -> "Driver Not Available"
        }
        val activeCartId = _selectedDriverCartId.value
        _golfCartState.value = _golfCartState.value?.copy(
            isAvailable = effectiveAvailable,
            isTripActive = isOccupied,
            driverStatus = displayStatus,
            status = if (effectiveAvailable || isOccupied) GolfCartStatus.HALTED else GolfCartStatus.OFFLINE
        )
        _fleetCarts.value = _fleetCarts.value.map {
            if (it.cartId == activeCartId) {
                it.copy(
                    isAvailable = effectiveAvailable,
                    isTripActive = isOccupied,
                    driverStatus = displayStatus,
                    status = if (effectiveAvailable || isOccupied) GolfCartStatus.HALTED else GolfCartStatus.OFFLINE
                )
            } else it
        }

        val targetFlow = if (activeCartId == "cart_1") _cart1State else _cart2State
        targetFlow.value = targetFlow.value.copy(
            isAvailable = effectiveAvailable,
            isTripActive = isOccupied,
            driverStatus = displayStatus,
            status = if (effectiveAvailable || isOccupied) GolfCartStatus.HALTED else GolfCartStatus.OFFLINE
        )

        Log.d("CAMPUS_RIDE_AVAILABILITY", "DRIVER_ROLE: Evaluated availability=$effectiveAvailable (manualOnDuty=$manualOnDuty, isInside=${_isInsideGeofence.value}, hasGps=${_hasGpsLocation.value}, isOccupied=$isOccupied)")

        scope.launch(Dispatchers.IO) {
            try {
                ensureFirebaseAuth()
                val firestore = FirebaseFirestore.getInstance()
                firestore.collection("drivers")
                    .document(activeCartId)
                    .set(
                        mapOf(
                            "cartId" to activeCartId,
                            "cartName" to (if (activeCartId == "cart_1") "Cart 1" else "Cart 2"),
                            "isAvailable" to effectiveAvailable,
                            "isTripActive" to isOccupied,
                            "onDuty" to manualOnDuty,
                            "isOnline" to true,
                            "isBusy" to isOccupied,
                            "insideCampus" to _isInsideGeofence.value,
                            "driverStatus" to displayStatus,
                            "latitude" to (_driverLatitude.value ?: GeofenceManager.LIBRARY_LAT),
                            "longitude" to (_driverLongitude.value ?: GeofenceManager.LIBRARY_LNG),
                            "distanceToLibraryMeters" to (_distanceToLibraryMeters.value ?: 0.0),
                            "last_seen" to System.currentTimeMillis(),
                            "lastUpdatedMillis" to System.currentTimeMillis()
                        ),
                        SetOptions.merge()
                    )
            } catch (e: Exception) {
                Log.w("CampusRideRepo", "Sync driver status update notice: ${e.message}")
            }
        }
    }

    private fun sha256Hex(input: String): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        val bytes = md.digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun verifyFacultyAccessCode(enteredCode: String): Boolean {
        // Authoritative SHA-256 hash for secure restricted faculty terminal authentication
        val defaultHash = "bd3fad7857f81b9455389c935f2a912dc47795cad86bc619a36c28371daea6ae"
        val expectedHash = prefs.getString("remote_faculty_code_hash", defaultHash) ?: defaultHash
        val enteredHash = sha256Hex(enteredCode.trim().uppercase())
        return enteredHash == expectedHash || enteredCode.trim() == prefs.getString("remote_faculty_code", null)
    }

    fun verifyDriverAccessCode(enteredCode: String): Boolean {
        // Authoritative SHA-256 hash for secure restricted driver terminal authentication
        val defaultHash = "876233088939ed6426c945e63af9dba60fe710af770f10dcee832cdb03091ef1"
        val expectedHash = prefs.getString("remote_driver_code_hash", defaultHash) ?: defaultHash
        val enteredHash = sha256Hex(enteredCode.trim().uppercase())
        return enteredHash == expectedHash || enteredCode.trim() == prefs.getString("remote_driver_code", null)
    }

    fun updateFacultyAccessCode(newCode: String) {
        val hash = sha256Hex(newCode.trim().uppercase())
        prefs.edit().putString("remote_faculty_code_hash", hash).remove("remote_faculty_code").apply()
    }

    fun updateDriverAccessCode(newCode: String) {
        val hash = sha256Hex(newCode.trim().uppercase())
        prefs.edit().putString("remote_driver_code_hash", hash).remove("remote_driver_code").apply()
    }

    fun getSavedRole(): UserRole? {
        val saved = prefs.getString("saved_user_role", null)
        return UserRole.fromString(saved)
    }

    fun saveRole(role: UserRole) {
        prefs.edit().putString("saved_user_role", role.name).commit()
        _currentRole.value = role
        FcmRoleNotificationManager.syncRoleFcmSubscription(context, role)
        startGolfCartLiveTrackingListener()
        if (role == UserRole.DRIVER) {
            startDriverFirestoreListener()
            startDriverHeartbeat()
        } else {
            driverListenerRegistration?.remove()
            heartbeatJob?.cancel()
            heartbeatJob = null
        }
        reevaluateEffectiveDriverAvailability()
    }

    fun clearRole() {
        prefs.edit().remove("saved_user_role").apply()
        _currentRole.value = null
        driverListenerRegistration?.remove()
        studentListenerRegistration?.remove()
        cart1ListenerRegistration?.remove()
        cart2ListenerRegistration?.remove()
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    fun setDriverAvailable(available: Boolean) {
        _isDriverAvailable.value = available
        val activeCartId = _selectedDriverCartId.value
        val targetFlow = if (activeCartId == "cart_1") _cart1State else _cart2State
        targetFlow.value = targetFlow.value.copy(
            status = if (!available) GolfCartStatus.OFFLINE else GolfCartStatus.HALTED,
            isAvailable = available,
            driverStatus = if (available) "Available" else "Offline"
        )
        _golfCartState.value = targetFlow.value
    }

    fun startDriverTrip(context: Context, cartId: String) {
        setSelectedDriverCartId(cartId)
        _isTripActive.value = true
        prefs.edit().putBoolean("pref_is_trip_active", true).apply()
        com.example.location.DriverLocationService.startTrip(context, cartId)

        val targetFlow = if (cartId == "cart_1") _cart1State else _cart2State
        val updated = targetFlow.value.copy(
            cartId = cartId,
            cartName = if (cartId == "cart_1") "Cart 1" else "Cart 2",
            status = GolfCartStatus.HALTED,
            isTripActive = true,
            isAvailable = true,
            driverStatus = "On Duty",
            lastUpdatedMillis = System.currentTimeMillis()
        )
        targetFlow.value = updated
        _fleetCarts.value = listOf(_cart1State.value, _cart2State.value)
        _golfCartState.value = updated

        scope.launch(Dispatchers.IO) {
            try {
                ensureFirebaseAuth()
                FirebaseFirestore.getInstance().collection("drivers")
                    .document(cartId)
                    .set(
                        mapOf(
                            "cartId" to cartId,
                            "cartName" to (if (cartId == "cart_1") "Cart 1" else "Cart 2"),
                            "status" to GolfCartStatus.HALTED.name,
                            "isTripActive" to true,
                            "isAvailable" to true,
                            "driverStatus" to "On Duty",
                            "lastUpdatedMillis" to System.currentTimeMillis()
                        ),
                        SetOptions.merge()
                    )
            } catch (e: Exception) {
                Log.w("CampusRideRepo", "startDriverTrip sync error: ${e.message}")
            }
        }
    }

    fun endDriverTrip(context: Context, cartId: String) {
        _isTripActive.value = false
        prefs.edit().putBoolean("pref_is_trip_active", false).apply()
        com.example.location.DriverLocationService.stopTrip(context)

        val targetFlow = if (cartId == "cart_1") _cart1State else _cart2State
        val updated = targetFlow.value.copy(
            status = GolfCartStatus.OFFLINE,
            isTripActive = false,
            isAvailable = false,
            driverStatus = "Offline",
            speedKmH = 0,
            lastUpdatedMillis = System.currentTimeMillis()
        )
        targetFlow.value = updated
        _fleetCarts.value = listOf(_cart1State.value, _cart2State.value)
        _golfCartState.value = updated

        scope.launch(Dispatchers.IO) {
            try {
                ensureFirebaseAuth()
                FirebaseFirestore.getInstance().collection("drivers")
                    .document(cartId)
                    .set(
                        mapOf(
                            "status" to GolfCartStatus.OFFLINE.name,
                            "isTripActive" to false,
                            "isAvailable" to false,
                            "driverStatus" to "Offline",
                            "speedKmH" to 0,
                            "lastUpdatedMillis" to System.currentTimeMillis()
                        ),
                        SetOptions.merge()
                    )
            } catch (e: Exception) {
                Log.w("CampusRideRepo", "endDriverTrip sync error: ${e.message}")
            }
        }
    }

    fun updateDriverLiveLocation(
        cartId: String = _selectedDriverCartId.value,
        latitude: Double,
        longitude: Double,
        speedKmH: Int = 0,
        bearing: Float = 0f,
        accuracy: Float = 0f,
        targetLat: Double = GeofenceManager.GATE_LAT,
        targetLng: Double = GeofenceManager.GATE_LNG
    ) {
        updateDriverGpsLocation(
            lat = latitude,
            lng = longitude,
            speedKmH = speedKmH,
            bearing = bearing,
            accuracy = accuracy,
            cartId = cartId
        )
    }

    fun startGolfCartLiveTrackingListener() {
        cart1ListenerRegistration?.remove()
        cart2ListenerRegistration?.remove()
        cartPollingJob?.cancel()

        scope.launch(Dispatchers.IO) {
            try {
                ensureFirebaseAuth()
            } catch (e: Exception) {
                Log.w("CampusRideRepo", "startGolfCartLiveTrackingListener: auth notice (proceeding): ${e.message}")
            }
            withContext(Dispatchers.Main) {
                cart1ListenerRegistration?.remove()
                cart2ListenerRegistration?.remove()
                try {
                    val firestore = FirebaseFirestore.getInstance()
                    Log.d("CampusRideRepo", "Attaching realtime listeners on 'drivers/cart_1' and 'drivers/cart_2'...")

                    fun handleCartSnapshot(cartId: String, snapshot: com.google.firebase.firestore.DocumentSnapshot?) {
                        if (snapshot == null || !snapshot.exists()) return

                        val lat = snapshot.safeDouble("latitude")
                        val lng = snapshot.safeDouble("longitude")
                        val bearing = snapshot.safeFloat("bearing", 0f)
                        val speedKmH = snapshot.safeInt("speedKmH", 0)
                        val statusStr = snapshot.getString("status") ?: "HALTED"
                        var status = try { GolfCartStatus.valueOf(statusStr) } catch (e: Exception) { GolfCartStatus.HALTED }
                        val isAvailable = snapshot.getBoolean("isAvailable") ?: true
                        val isTripActive = snapshot.getBoolean("isTripActive") ?: false
                        val driverStatus = snapshot.getString("driverStatus") ?: "Available"

                        val isOutside = (lat != null && lng != null && !GeofenceManager.isInsideCampusGeofence(lat, lng)) ||
                                        driverStatus.equals("Outside Campus", ignoreCase = true) ||
                                        driverStatus.equals("Driver Not Available", ignoreCase = true)

                        var effectiveIsAvailable = isAvailable
                        var effectiveDriverStatus = driverStatus
                        if (isOutside) {
                            effectiveIsAvailable = false
                            effectiveDriverStatus = "Driver Not Available"
                            status = GolfCartStatus.OFFLINE
                        } else if (status == GolfCartStatus.OFFLINE && (effectiveIsAvailable || effectiveDriverStatus.equals("Available", ignoreCase = true) || effectiveDriverStatus.equals("On Trip", ignoreCase = true))) {
                            status = if (speedKmH > 0) GolfCartStatus.MOVING else GolfCartStatus.HALTED
                        }

                        val lastUpdated = snapshot.safeLong("lastUpdatedMillis", snapshot.safeLong("last_seen", System.currentTimeMillis()))
                        val lastHeartbeat = snapshot.safeLong("lastHeartbeatMillis", snapshot.safeLong("last_seen", lastUpdated))
                        val locationTimestamp = snapshot.safeLong("locationTimestampMillis", if (lat != null && lng != null) lastUpdated else 0L).let { if (it > 0L) it else null }
                        val direction = snapshot.getString("direction")
                        val currentStop = snapshot.getString("currentStop")
                        val nextStop = snapshot.getString("nextStop")

                        // If driver on this phone is driving this cart, don't overwrite local live GPS
                        if (_currentRole.value == UserRole.DRIVER && _selectedDriverCartId.value == cartId && _hasGpsLocation.value) {
                            return
                        }

                        val existingCart = if (cartId == "cart_1") _cart1State.value else _cart2State.value
                        val effectiveLat = lat ?: existingCart.latitude
                        val effectiveLng = lng ?: existingCart.longitude

                        val currentDistGate = if (effectiveLat != null && effectiveLng != null) {
                            GeofenceManager.calculateDistanceMeters(effectiveLat, effectiveLng, GeofenceManager.GATE_LAT, GeofenceManager.GATE_LNG).roundToInt()
                        } else 0

                        val updatedCart = existingCart.copy(
                            cartId = cartId,
                            cartName = if (cartId == "cart_1") "Cart 1" else "Cart 2",
                            latitude = effectiveLat,
                            longitude = effectiveLng,
                            speedKmH = speedKmH,
                            bearing = bearing,
                            status = status,
                            isTripActive = isTripActive,
                            isAvailable = effectiveIsAvailable,
                            driverStatus = effectiveDriverStatus,
                            lastUpdatedMillis = lastUpdated,
                            lastHeartbeatMillis = lastHeartbeat,
                            locationTimestampMillis = locationTimestamp,
                            distanceToGateMeters = currentDistGate,
                            distanceToUserMeters = currentDistGate,
                            direction = direction ?: existingCart.direction,
                            currentStop = currentStop ?: existingCart.currentStop,
                            nextStop = nextStop ?: existingCart.nextStop
                        )

                        if (cartId == "cart_1") {
                            _cart1State.value = updatedCart
                        } else {
                            _cart2State.value = updatedCart
                        }

                        _fleetCarts.value = listOf(_cart1State.value, _cart2State.value)
                        if (_selectedDriverCartId.value == cartId || _golfCartState.value?.cartId == cartId) {
                            _golfCartState.value = updatedCart
                        }

                        if (_currentRole.value != UserRole.DRIVER) {
                            val anyAvailable = _fleetCarts.value.any { 
                                it.isInsideCampus && !it.isOutsideCampus &&
                                (it.isDriverOnline || (it.isAvailable && !it.driverStatus.equals("Offline", ignoreCase = true))) && 
                                !it.driverStatus.equals("Lunch Break", ignoreCase = true) &&
                                !it.driverStatus.equals("Outside Campus", ignoreCase = true) &&
                                !it.driverStatus.equals("Driver Not Available", ignoreCase = true)
                            }
                            _isDriverAvailable.value = anyAvailable
                            Log.d("CAMPUS_RIDE_AVAILABILITY", "CART_SNAPSHOT_RECEIVED ($cartId): presenceState=${updatedCart.presenceState}, isDriverOnline=${updatedCart.isDriverOnline}, driverStatus=$effectiveDriverStatus -> Fleet available=$anyAvailable")
                        }
                    }

                    cart1ListenerRegistration = firestore.collection("drivers")
                        .document("cart_1")
                        .addSnapshotListener { snapshot, error ->
                            if (error != null) {
                                Log.w("CampusRideRepo", "Notice listening to drivers/cart_1: ${error.message}")
                                scope.launch(Dispatchers.IO) {
                                    refreshAllData()
                                }
                                return@addSnapshotListener
                            }
                            handleCartSnapshot("cart_1", snapshot)
                        }

                    cart2ListenerRegistration = firestore.collection("drivers")
                        .document("cart_2")
                        .addSnapshotListener { snapshot, error ->
                            if (error != null) {
                                Log.w("CampusRideRepo", "Notice listening to drivers/cart_2: ${error.message}")
                                scope.launch(Dispatchers.IO) {
                                    refreshAllData()
                                }
                                return@addSnapshotListener
                            }
                            handleCartSnapshot("cart_2", snapshot)
                        }
                } catch (e: Exception) {
                    Log.e("CampusRideRepo", "Error attaching drivers snapshot listeners", e)
                }
            }

            // Battery Optimization: Eliminate aggressive 4-second polling loop.
            // Firestore real-time snapshot listeners (cart1ListenerRegistration and cart2ListenerRegistration)
            // push telemetry updates instantaneously without waking the CPU or executing redundant REST queries.
            cartPollingJob?.cancel()
            cartPollingJob = null
        }
    }

    fun pausePassengerLiveTracking() {
        if (_currentRole.value == UserRole.DRIVER) return
        if (!isPassengerTrackingPaused) {
            isPassengerTrackingPaused = true
            cart1ListenerRegistration?.remove()
            cart1ListenerRegistration = null
            cart2ListenerRegistration?.remove()
            cart2ListenerRegistration = null
            Log.d("CampusRideRepo", "PAUSE_TRACKING: Suspended passenger cart Firestore listeners for background battery savings")
        }
    }

    fun resumePassengerLiveTracking() {
        if (isPassengerTrackingPaused) {
            isPassengerTrackingPaused = false
            Log.d("CampusRideRepo", "RESUME_TRACKING: Restoring passenger cart Firestore listeners on foreground")
            startGolfCartLiveTrackingListener()
        }
    }

    fun toggleOverrideHours(enabled: Boolean) {
        _overrideWorkingHours.value = enabled
        prefs.edit().putBoolean("pref_override_hours", enabled).apply()
    }

    suspend fun sendStudentRideRequest(
        studentLat: Double = 25.2531616,
        studentLng: Double = 87.0370730,
        studentName: String = "",
        studentsWaiting: Int = 1,
        pickupLocation: PickupLocation = PickupLocation.GATE,
        assignedCartId: String? = null
    ): Result<RideRequest> = withContext(Dispatchers.IO) {
        if (!requestCreationMutex.tryLock()) {
            return@withContext Result.failure(IllegalStateException("A ride request creation is currently in progress. Please wait."))
        }
        try {
            // Strict Role-Based Location Enforcement: Students can ONLY request from GATE
            val effectiveLocation = PickupLocation.GATE
            if (pickupLocation != PickupLocation.GATE) {
                return@withContext Result.failure(
                    IllegalStateException("Access Denied: Students can only request rides from the Gate.")
                )
            }

            // Anti-spam duplicate request protection
            val currentActive = _activeStudentRequest.value
            if (currentActive != null && (currentActive.status == RideRequestStatus.PENDING || currentActive.status == RideRequestStatus.ACCEPTED)) {
                return@withContext Result.failure(IllegalStateException("Anti-spam protection: You already have an active ride request in progress."))
            }

            val validatedWaitingCount = studentsWaiting.coerceIn(1, 10)

            val calculatedDistance = GeofenceManager.calculateDistanceMeters(
                studentLat,
                studentLng,
                effectiveLocation.latitude ?: GeofenceManager.GATE_LAT,
                effectiveLocation.longitude ?: GeofenceManager.GATE_LNG
            )

            val schedule = ScheduleStatus.getCurrentStatus(_overrideWorkingHours.value)
            if (!schedule.isAvailable) {
                return@withContext Result.failure(IllegalStateException(schedule.message))
            }

            val allOnLunch = _fleetCarts.value.isNotEmpty() && _fleetCarts.value.all { it.driverStatus == "Lunch Break" }
            if (allOnLunch) {
                return@withContext Result.failure(IllegalStateException("All golf carts are on lunch break."))
            }

            if (_cooldownSeconds.value > 0) {
                return@withContext Result.failure(IllegalStateException("Please wait for cooldown timer before requesting again."))
            }

            if (assignedCartId.isNullOrBlank()) {
                return@withContext Result.failure(IllegalStateException("Please select which cart to notify."))
            }

            val targetCartId = if (assignedCartId.contains("2", ignoreCase = true)) "cart_2" else "cart_1"
            val targetCartName = if (targetCartId == "cart_2") "Cart 2" else "Cart 1"
            val canonicalCartId = if (targetCartId == "cart_2") "CART_2" else "CART_1"

            // Validate that the selected cart is currently eligible before dispatching the notification.
            // If the selected cart has no active/eligible driver, clearly tell the Student:
            // "Cart 2 is currently unavailable. Please select another cart."
            val targetCart = _fleetCarts.value.find { it.cartId == targetCartId }
            val isTargetCartEligible = targetCart != null && (
                (targetCart.isLive || targetCart.isDriverOnline || targetCart.isAvailable) &&
                !targetCart.driverStatus.equals("Offline", ignoreCase = true) &&
                !targetCart.driverStatus.equals("Lunch Break", ignoreCase = true) &&
                !targetCart.driverStatus.equals("Outside Campus", ignoreCase = true) &&
                !targetCart.driverStatus.equals("Driver Not Available", ignoreCase = true)
            )

            if (!isTargetCartEligible && !_overrideWorkingHours.value) {
                return@withContext Result.failure(IllegalStateException("$targetCartName is currently unavailable. Please select another cart."))
            }

            val reqId = "req_${System.currentTimeMillis()}_${(1000..9999).random()}"
            val request = RideRequest(
                id = reqId,
                requestId = reqId,
                requesterType = com.example.data.model.RequesterType.STUDENT,
                studentId = "student_${(1000..9999).random()}",
                studentName = if (studentName.isNotBlank()) studentName else "Student Passenger",
                pickupLocation = effectiveLocation.id,
                distanceToGateMeters = calculatedDistance.roundToInt(),
                studentsWaiting = validatedWaitingCount,
                waitingCount = validatedWaitingCount,
                status = RideRequestStatus.PENDING,
                assignedCartId = targetCartId,
                selectedCartId = canonicalCartId,
                assignedCartName = targetCartName,
                timestamp = System.currentTimeMillis(),
                createdAt = System.currentTimeMillis()
            )

            Log.d("CAMPUS_RIDE_AUDIT", "1. STUDENT_REQUEST_CREATED: requestId=${request.id}, requester=${request.requesterType.name}, pickup=${request.pickupLocation}, waitingCount=$validatedWaitingCount, cart=$targetCartId, selectedCartId=$canonicalCartId")
            Log.d("CAMPUS_RIDE_AUDIT", "2. DRIVER_SELECTED: targetCartId=$targetCartId, cartName=$targetCartName")
            Log.d("CAMPUS_RIDE_AUDIT", "3. DRIVER_ID_FOUND: driverId=driver_$targetCartId, cartId=$targetCartId")

            var backendSucceeded = false
            var fcmMessageId: String? = null
            var backendErrorMsg: String? = null
            var firestoreSucceeded = false
            var lastError: Exception? = null

            // Primary Channel: Backend REST API with trusted Firebase Admin SDK
            try {
                Log.d("CAMPUS_RIDE_AUDIT", "7. FCM_SEND_STARTED: Dispatching to backend /api/rides/request with Admin SDK (targetCartId=$targetCartId, selectedCartId=$canonicalCartId)")
                val backendResp = CampusBackendClient.api.createRideRequest(
                    CreateRideRequest(
                        id = request.id,
                        requestId = request.id,
                        requesterType = request.requesterType.name,
                        studentId = request.studentId,
                        studentName = request.studentName,
                        pickupLocation = request.pickupLocation,
                        distanceToGateMeters = request.distanceToGateMeters,
                        studentsWaiting = request.studentsWaiting,
                        waitingCount = request.waitingCount,
                        assignedCartId = targetCartId,
                        selectedCartId = canonicalCartId,
                        status = request.status.name,
                        createdAt = request.createdAt
                    )
                )
                val body = backendResp.body()
                if (backendResp.isSuccessful && body != null) {
                    if (body.success && body.fcmResult?.success == true) {
                        backendSucceeded = true
                        fcmMessageId = body.fcmResult.messageId
                        Log.d("CAMPUS_RIDE_AUDIT", "8. FCM_SEND_SUCCESS: messageId=$fcmMessageId, requestId=${request.id}")
                    } else if (body.success) {
                        // Backend recorded ride but driver FCM send had issue
                        backendSucceeded = true
                        Log.w("CAMPUS_RIDE_AUDIT", "8. FCM_SEND_NOTICE: Backend recorded ride, fcmCode=${body.fcmResult?.code}")
                    } else {
                        backendErrorMsg = body.error ?: body.message ?: "Driver notification failed on backend"
                        Log.e("CAMPUS_RIDE_AUDIT", "9. FCM_SEND_FAILURE: code=${body.code ?: body.fcmResult?.code}, error=$backendErrorMsg")
                    }
                } else {
                    backendErrorMsg = "Backend HTTP error ${backendResp.code()}: ${backendResp.message()}"
                    Log.e("CAMPUS_RIDE_AUDIT", "9. FCM_SEND_FAILURE: $backendErrorMsg")
                }
            } catch (e: Exception) {
                backendErrorMsg = e.localizedMessage ?: "Backend network connection failed"
                Log.w("CampusRideRepo", "Backend API sync exception: ${e.message}")
                lastError = e
            }

            // Secondary Channel: Direct Firestore client write (non-fatal if backend succeeded)
            try {
                ensureFirebaseAuth()
                val firestore = FirebaseFirestore.getInstance()

                val docData = mapOf(
                    "id" to request.id,
                    "requestId" to request.id,
                    "requesterType" to request.requesterType.name,
                    "studentId" to request.studentId,
                    "studentName" to request.studentName,
                    "pickupLocation" to request.pickupLocation,
                    "distanceToGateMeters" to request.distanceToGateMeters,
                    "studentsWaiting" to request.studentsWaiting,
                    "waitingCount" to request.waitingCount,
                    "status" to request.status.name,
                    "assignedCartId" to targetCartId,
                    "selectedCartId" to canonicalCartId,
                    "assignedCartName" to targetCartName,
                    "timestamp" to request.timestamp,
                    "createdAt" to request.createdAt
                )

                firestore.collection("ride_requests")
                    .document(request.id)
                    .set(docData)
                    .awaitTask()

                Log.d("CAMPUS_RIDE_AUDIT", "6. FIRESTORE_REQUEST_WRITE: Document ${request.id} written via client Firestore")
                firestoreSucceeded = true
            } catch (e: Exception) {
                Log.w("CAMPUS_RIDE_AUDIT", "FIRESTORE_CLIENT_WRITE_SKIPPED: Direct client Firestore write skipped (relying on backend Admin SDK): ${e.message}")
                if (!backendSucceeded && lastError == null) {
                    lastError = e
                }
            }

            if (backendSucceeded || firestoreSucceeded) {
                _golfCartState.value = targetCart
                _activeStudentRequest.value = request
                val updated = listOf(request) + _requests.value.filter { it.id != request.id }
                _requests.value = updated

                startStudentRequestListener(request.id)
                FcmRoleNotificationManager.dispatchDriverPush(request)
                startCooldownTimer(300)

                Result.success(request)
            } else {
                val failureReason = backendErrorMsg ?: lastError?.localizedMessage ?: "Driver notification failed. Please verify driver is online."
                Log.e("CampusRideRepo", "RIDE_DISPATCH_FAILED: $failureReason")
                Result.failure(Exception(sanitizeUserFacingError(failureReason)))
            }
        } finally {
            requestCreationMutex.unlock()
        }
    }

    private fun sanitizeUserFacingError(technicalMsg: String?): String {
        if (technicalMsg.isNullOrBlank()) return "Unable to notify driver right now. Please try again."
        val lower = technicalMsg.lowercase()
        return when {
            lower.contains("permission_denied") || lower.contains("insufficient permissions") || lower.contains("failed_precondition") || lower.contains("document not found") ->
                "Unable to notify driver right now. Please try again."
            lower.contains("outside") || lower.contains("radius") || lower.contains("gate") || lower.contains("geofence") ->
                "Move within 70 m of the Main Gate to enable."
            lower.contains("fcm") || lower.contains("timeout") || lower.contains("connect") || lower.contains("unreachable") || lower.contains("network") ->
                "Driver is currently unreachable. Please retry shortly."
            lower.contains("operating hours") || lower.contains("working hours") ->
                "Service is currently unavailable outside operating hours."
            lower.contains("lunch break") ->
                "Golf cart service is currently on lunch break."
            lower.contains("cooldown") ->
                "Please wait for cooldown timer before requesting again."
            lower.contains("active") ->
                "You already have an active ride request in progress."
            lower.contains("verify driver is online") || lower.contains("offline") ->
                "Driver is currently unreachable. Please retry shortly."
            else -> "Unable to notify driver right now. Please try again."
        }
    }

    suspend fun sendFacultyRideRequest(pickupLocation: String): Result<RideRequest> = withContext(Dispatchers.IO) {
        if (!requestCreationMutex.tryLock()) {
            return@withContext Result.failure(IllegalStateException("A ride request creation is currently in progress. Please wait."))
        }
        try {
            val facultyLoc = PickupLocation.fromId(pickupLocation)
            if (!facultyLoc.allowedForFaculty) {
                return@withContext Result.failure(
                    IllegalStateException("Access Denied: Invalid pickup location for Faculty ($pickupLocation). Allowed locations are Boys Hostel, Computer Centre, Trunkut, and Main Gate.")
                )
            }

            val currentActive = _activeFacultyRequest.value
            if (currentActive != null && (currentActive.status == RideRequestStatus.PENDING || currentActive.status == RideRequestStatus.ACCEPTED)) {
                return@withContext Result.failure(IllegalStateException("Anti-spam protection: You already have an active faculty ride request in progress."))
            }

            val schedule = ScheduleStatus.getCurrentStatus(_overrideWorkingHours.value)
            if (!schedule.isAvailable) {
                return@withContext Result.failure(IllegalStateException(schedule.message))
            }

            val allOnLunch = _fleetCarts.value.isNotEmpty() && _fleetCarts.value.all { it.driverStatus == "Lunch Break" }
            if (allOnLunch) {
                return@withContext Result.failure(IllegalStateException("All golf carts are on lunch break."))
            }

            val assignedCart = findBestAvailableCart(facultyLoc.displayName)
            val targetCartId = assignedCart?.cartId ?: "cart_1"
            val targetCartName = assignedCart?.cartName
                ?: _fleetCarts.value.find { it.cartId == targetCartId }?.cartName
                ?: "Cart 1"

            val request = RideRequest(
                requesterType = com.example.data.model.RequesterType.FACULTY,
                studentName = "Faculty Member",
                pickupLocation = facultyLoc.id,
                distanceToGateMeters = 0,
                status = RideRequestStatus.PENDING,
                assignedCartId = targetCartId,
                assignedCartName = targetCartName
            )

            Log.d("CAMPUS_RIDE_AUDIT", "1. FACULTY_REQUEST_CREATED: requestId=${request.id}, pickup=${facultyLoc.displayName}")
            Log.d("CAMPUS_RIDE_AUDIT", "2. DRIVER_SELECTED: targetCartId=$targetCartId, cartName=$targetCartName")
            Log.d("CAMPUS_RIDE_AUDIT", "3. DRIVER_ID_FOUND: driverId=driver_$targetCartId, cartId=$targetCartId")

            var backendSucceeded = false
            var fcmMessageId: String? = null
            var backendErrorMsg: String? = null
            var firestoreSucceeded = false
            var lastError: Exception? = null

            // Primary Channel: Backend REST API with trusted Firebase Admin SDK
            try {
                Log.d("CAMPUS_RIDE_AUDIT", "7. FCM_SEND_STARTED: Dispatching faculty request to backend /api/rides/request with Admin SDK")
                val backendResp = CampusBackendClient.api.createRideRequest(
                    CreateRideRequest(
                        id = request.id,
                        requestId = request.id,
                        requesterType = request.requesterType.name,
                        studentName = request.studentName,
                        pickupLocation = request.pickupLocation,
                        distanceToGateMeters = 0,
                        studentsWaiting = 1,
                        assignedCartId = request.assignedCartId
                    )
                )
                val body = backendResp.body()
                if (backendResp.isSuccessful && body != null) {
                    if (body.success && body.fcmResult?.success == true) {
                        backendSucceeded = true
                        fcmMessageId = body.fcmResult.messageId
                        Log.d("CAMPUS_RIDE_AUDIT", "8. FCM_SEND_SUCCESS: messageId=$fcmMessageId, requestId=${request.id}")
                    } else if (body.success) {
                        backendSucceeded = true
                        Log.w("CAMPUS_RIDE_AUDIT", "8. FCM_SEND_NOTICE: Backend recorded faculty ride, fcmCode=${body.fcmResult?.code}")
                    } else {
                        backendErrorMsg = body.error ?: body.message ?: "Driver notification failed on backend"
                        Log.e("CAMPUS_RIDE_AUDIT", "9. FCM_SEND_FAILURE: code=${body.code ?: body.fcmResult?.code}, error=$backendErrorMsg")
                    }
                } else {
                    backendErrorMsg = "Backend HTTP error ${backendResp.code()}: ${backendResp.message()}"
                    Log.e("CAMPUS_RIDE_AUDIT", "9. FCM_SEND_FAILURE: $backendErrorMsg")
                }
            } catch (e: Exception) {
                backendErrorMsg = e.localizedMessage ?: "Backend network connection failed"
                Log.w("CampusRideRepo", "Backend API sync exception: ${e.message}")
                lastError = e
            }

            // Secondary Channel: Direct Firestore client write (non-fatal if backend succeeded)
            try {
                ensureFirebaseAuth()
                val firestore = FirebaseFirestore.getInstance()

                val docData = mapOf(
                    "id" to request.id,
                    "requesterType" to request.requesterType.name,
                    "studentName" to request.studentName,
                    "pickupLocation" to request.pickupLocation,
                    "distanceToGateMeters" to 0,
                    "status" to request.status.name,
                    "assignedCartId" to request.assignedCartId,
                    "assignedCartName" to request.assignedCartName,
                    "timestamp" to request.timestamp
                )

                firestore.collection("ride_requests")
                    .document(request.id)
                    .set(docData)
                    .awaitTask()

                Log.d("CAMPUS_RIDE_AUDIT", "6. FIRESTORE_REQUEST_WRITE: Document ${request.id} written via client Firestore")
                firestoreSucceeded = true
            } catch (e: Exception) {
                Log.w("CAMPUS_RIDE_AUDIT", "FIRESTORE_CLIENT_WRITE_SKIPPED: Direct client Firestore write skipped (relying on backend Admin SDK): ${e.message}")
                if (!backendSucceeded && lastError == null) {
                    lastError = e
                }
            }

            if (backendSucceeded || firestoreSucceeded) {
                _golfCartState.value = assignedCart
                _activeFacultyRequest.value = request
                val updated = listOf(request) + _requests.value.filter { it.id != request.id }
                _requests.value = updated

                startStudentRequestListener(request.id)
                FcmRoleNotificationManager.dispatchDriverPush(request)

                Result.success(request)
            } else {
                val failureReason = backendErrorMsg ?: lastError?.localizedMessage ?: "Driver notification failed. Please verify driver is online."
                Log.e("CampusRideRepo", "FACULTY_RIDE_DISPATCH_FAILED: $failureReason")
                Result.failure(Exception(sanitizeUserFacingError(failureReason)))
            }
        } finally {
            requestCreationMutex.unlock()
        }
    }

    fun onIncomingRideRequestReceived(request: RideRequest) {
        val currentRoleVal = _currentRole.value
        val myCartId = _selectedDriverCartId.value
        val normalizedMyCart = if (myCartId.contains("2", ignoreCase = true)) "cart_2" else "cart_1"
        val reqCartId = if (request.assignedCartId?.contains("2", ignoreCase = true) == true || request.selectedCartId?.contains("2", ignoreCase = true) == true) "cart_2" else "cart_1"

        if (currentRoleVal == UserRole.DRIVER && reqCartId != normalizedMyCart) {
            Log.d("CampusRideRepo", "DRIVER_CART_MISMATCH: Skipping incoming request ${request.id} for $reqCartId on driver device for $normalizedMyCart")
            return
        }

        val current = _requests.value
        if (current.none { it.id == request.id }) {
            _requests.value = listOf(request) + current
            Log.d("CAMPUS_RIDE_AUDIT", "10. DRIVER_NOTIFICATION_RECEIVED: Incoming request ${request.id} added to driver repository state")
        }
    }

    fun acceptRideRequest(requestId: String) {
        CriticalAlertManager.markRequestHandled(requestId)
        CriticalAlertManager.stopAlert(context, reason = "ACCEPTED")
        Log.d("CampusRideRepo", "DRIVER_ACCEPT: requestId=$requestId")

        val targetReq = _requests.value.find { it.id == requestId }
        val effectiveCartId = targetReq?.assignedCartId ?: _selectedDriverCartId.value

        targetReq?.assignedCartId?.let { cartId ->
            _fleetCarts.value = _fleetCarts.value.map {
                if (it.cartId == cartId) it.copy(isAvailable = false, activeRequestId = requestId, driverStatus = "Occupied")
                else it
            }
        }

        val updatedList = _requests.value.map { req ->
            if (req.id == requestId) req.copy(status = RideRequestStatus.ACCEPTED) else req
        }
        _requests.value = updatedList

        if (_activeStudentRequest.value?.id == requestId) {
            _activeStudentRequest.value = _activeStudentRequest.value?.copy(status = RideRequestStatus.ACCEPTED)
        }
        if (_activeFacultyRequest.value?.id == requestId) {
            _activeFacultyRequest.value = _activeFacultyRequest.value?.copy(status = RideRequestStatus.ACCEPTED)
        }

        Log.d("CampusRideRepo", "REQUEST_STATUS = ACCEPTED: requestId=$requestId")

        scope.launch(Dispatchers.IO) {
            try {
                ensureFirebaseAuth()
                val firestore = FirebaseFirestore.getInstance()
                val rideRef = firestore.collection("ride_requests").document(requestId)

                // Atomic Transaction: Verify status is PENDING before transitioning to ACCEPTED
                firestore.runTransaction { tx ->
                    val snap = tx.get(rideRef)
                    val currentStatus = snap.getString("status")
                    if (currentStatus != null && currentStatus != RideRequestStatus.PENDING.name && currentStatus != RideRequestStatus.ACCEPTED.name) {
                        throw IllegalStateException("Ride request is no longer pending ($currentStatus)")
                    }
                    tx.update(
                        rideRef,
                        mapOf(
                            "status" to RideRequestStatus.ACCEPTED.name,
                            "assignedCartId" to effectiveCartId,
                            "driverAcceptedAt" to System.currentTimeMillis(),
                            "updatedAt" to System.currentTimeMillis()
                        )
                    )
                }.awaitTask()

                firestore.collection("drivers")
                    .document(effectiveCartId)
                    .update(mapOf("isAvailable" to false, "driverStatus" to "Occupied"))
            } catch (e: Exception) {
                Log.e("CampusRideRepo", "Failed to update ACCEPTED status in Firestore transaction", e)
            }
            try {
                CampusBackendClient.api.acceptRide(requestId)
            } catch (e: Exception) {
                Log.w("CampusRideRepo", "Render accept backend sync notice: ${e.message}")
            }
        }

        targetReq?.let {
            FcmRoleNotificationManager.dispatchStudentAcceptancePush(it)
        }
        CriticalAlertManager.triggerStudentAcceptanceNotification(context, _currentRole.value)
    }

    fun declineRideRequest(requestId: String) {
        CriticalAlertManager.markRequestHandled(requestId)
        CriticalAlertManager.stopAlert(context, reason = "REJECTED")
        Log.d("CampusRideRepo", "DRIVER_DECLINE: requestId=$requestId")

        val targetReq = _requests.value.find { it.id == requestId }
        val effectiveCartId = targetReq?.assignedCartId ?: _selectedDriverCartId.value
        targetReq?.assignedCartId?.let { cartId ->
            _fleetCarts.value = _fleetCarts.value.map {
                if (it.cartId == cartId) it.copy(isAvailable = true, activeRequestId = null, driverStatus = "Available")
                else it
            }
        }

        val updatedList = _requests.value.map { req ->
            if (req.id == requestId) req.copy(status = RideRequestStatus.REJECTED) else req
        }
        _requests.value = updatedList

        if (_activeStudentRequest.value?.id == requestId) {
            _activeStudentRequest.value = _activeStudentRequest.value?.copy(status = RideRequestStatus.REJECTED)
        }
        if (_activeFacultyRequest.value?.id == requestId) {
            _activeFacultyRequest.value = _activeFacultyRequest.value?.copy(status = RideRequestStatus.REJECTED)
        }

        scope.launch(Dispatchers.IO) {
            try {
                ensureFirebaseAuth()
                val firestore = FirebaseFirestore.getInstance()
                firestore.collection("ride_requests")
                    .document(requestId)
                    .update("status", RideRequestStatus.REJECTED.name)

                firestore.collection("drivers")
                    .document(effectiveCartId)
                    .update(mapOf("isAvailable" to true, "driverStatus" to "Available"))
            } catch (e: Exception) {
                Log.e("CampusRideRepo", "Failed to update REJECTED status in Firestore", e)
            }
            try {
                Log.d("NETWORK_TRACE", "Calling declineRide()")
                CampusBackendClient.api.declineRide(requestId)
            } catch (e: Exception) {
                Log.e("NETWORK_TRACE", "Retrofit failed", e)
                Log.w("CampusRideRepo", "Render decline backend sync notice: ${e.message}")
            }
        }
    }

    fun completeRideRequest(requestId: String) {
        val targetReq = _requests.value.find { it.id == requestId }
        val effectiveCartId = targetReq?.assignedCartId ?: _selectedDriverCartId.value
        targetReq?.assignedCartId?.let { cartId ->
            _fleetCarts.value = _fleetCarts.value.map {
                if (it.cartId == cartId) it.copy(isAvailable = true, activeRequestId = null, driverStatus = "Available")
                else it
            }
        }

        val updatedList = _requests.value.map { req ->
            if (req.id == requestId) req.copy(status = RideRequestStatus.COMPLETED) else req
        }
        _requests.value = updatedList

        if (_activeStudentRequest.value?.id == requestId) {
            _activeStudentRequest.value = _activeStudentRequest.value?.copy(status = RideRequestStatus.COMPLETED)
        }
        if (_activeFacultyRequest.value?.id == requestId) {
            _activeFacultyRequest.value = _activeFacultyRequest.value?.copy(status = RideRequestStatus.COMPLETED)
        }

        scope.launch(Dispatchers.IO) {
            try {
                ensureFirebaseAuth()
                val firestore = FirebaseFirestore.getInstance()
                firestore.collection("ride_requests")
                    .document(requestId)
                    .update("status", RideRequestStatus.COMPLETED.name)

                firestore.collection("drivers")
                    .document(effectiveCartId)
                    .update(mapOf("isAvailable" to true, "driverStatus" to "Available"))
            } catch (e: Exception) {
                Log.e("CampusRideRepo", "Failed to update COMPLETED status in Firestore", e)
            }
            try {
                Log.d("NETWORK_TRACE", "Calling completeRide()")
                CampusBackendClient.api.completeRide(requestId)
            } catch (e: Exception) {
                Log.e("NETWORK_TRACE", "Retrofit failed", e)
                Log.w("CampusRideRepo", "Render complete backend sync notice: ${e.message}")
            }
        }
    }

    private fun startCooldownTimer(seconds: Int) {
        cooldownJob?.cancel()
        _cooldownSeconds.value = seconds
        cooldownJob = scope.launch {
            while (_cooldownSeconds.value > 0) {
                delay(1000)
                _cooldownSeconds.value -= 1
            }
        }
    }

    fun setGolfCartEnabled(cartId: String, enabled: Boolean) {
        _fleetCarts.value = _fleetCarts.value.map {
            if (it.cartId == cartId) {
                it.copy(isAvailable = enabled, driverStatus = if (enabled) "Available" else "Offline")
            } else it
        }
    }

    fun getOperationalAnalytics(): Map<String, Any> {
        val total = _requests.value.size
        val accepted = _requests.value.count { it.status == RideRequestStatus.ACCEPTED || it.status == RideRequestStatus.COMPLETED }
        val rejected = _requests.value.count { it.status == RideRequestStatus.REJECTED }
        val pending = _requests.value.count { it.status == RideRequestStatus.PENDING }

        return mapOf(
            "totalRequests" to total,
            "acceptedRequests" to accepted,
            "rejectedRequests" to rejected,
            "pendingRequests" to pending,
            "activeFleetCount" to _fleetCarts.value.count { it.isAvailable },
            "geofenceRadiusMeters" to GeofenceManager.MAX_GEOFENCE_METERS,
            "gateCoordinates" to "${GeofenceManager.GATE_LAT}, ${GeofenceManager.GATE_LNG}"
        )
    }

    private val refreshMutex = kotlinx.coroutines.sync.Mutex()

    suspend fun refreshAllData(role: UserRole? = _currentRole.value): Result<Unit> = withContext(Dispatchers.IO) {
        if (!refreshMutex.tryLock()) {
            return@withContext Result.success(Unit)
        }
        try {
            var anyFetchSucceeded = false
            var networkError: Throwable? = null

            // 1. Fetch carts and active requests from Firestore
            try {
                val authRes = ensureFirebaseAuth()
                if (authRes.isSuccess) {
                    val firestore = FirebaseFirestore.getInstance()

                    // Ensure real-time listeners are active if not already
                    startGolfCartLiveTrackingListener()
                    if (role == UserRole.DRIVER) {
                        startDriverFirestoreListener()
                    }

                    // Explicitly fetch cart_1 snapshot
                    try {
                        val cart1Doc = firestore.collection("drivers").document("cart_1").get().awaitTask()
                        if (cart1Doc.exists()) {
                            val lat = cart1Doc.safeDouble("latitude")
                            val lng = cart1Doc.safeDouble("longitude")
                            val bearing = cart1Doc.safeFloat("bearing", 0f)
                            val speedKmH = cart1Doc.safeInt("speedKmH", 0)
                            val statusStr = cart1Doc.getString("status") ?: "HALTED"
                            val isAvailable = cart1Doc.getBoolean("isAvailable") ?: true
                            val isTripActive = cart1Doc.getBoolean("isTripActive") ?: false
                            val driverStatus = cart1Doc.getString("driverStatus") ?: "Available"
                            val isOutside1 = (lat != null && lng != null && !GeofenceManager.isInsideCampusGeofence(lat, lng)) ||
                                             driverStatus.equals("Outside Campus", ignoreCase = true) ||
                                             driverStatus.equals("Driver Not Available", ignoreCase = true)
                            val effectiveIsAvailable1 = if (isOutside1) false else isAvailable
                            val effectiveDriverStatus1 = if (isOutside1) "Driver Not Available" else driverStatus
                            var status = try { GolfCartStatus.valueOf(statusStr) } catch (e: Exception) { GolfCartStatus.HALTED }
                            if (isOutside1) {
                                status = GolfCartStatus.OFFLINE
                            } else if (status == GolfCartStatus.OFFLINE && (effectiveIsAvailable1 || effectiveDriverStatus1.equals("Available", ignoreCase = true) || effectiveDriverStatus1.equals("On Trip", ignoreCase = true))) {
                                status = if (speedKmH > 0) GolfCartStatus.MOVING else GolfCartStatus.HALTED
                            }
                            val lastUpdated = cart1Doc.safeLong("lastUpdatedMillis", cart1Doc.safeLong("last_seen", System.currentTimeMillis()))
                            val lastHeartbeat = cart1Doc.safeLong("lastHeartbeatMillis", cart1Doc.safeLong("last_seen", lastUpdated))
                            val locationTimestamp = cart1Doc.safeLong("locationTimestampMillis", if (lat != null && lng != null) lastUpdated else 0L).let { if (it > 0L) it else null }
                            val direction = cart1Doc.getString("direction")
                            val currentStop = cart1Doc.getString("currentStop")
                            val nextStop = cart1Doc.getString("nextStop")

                            val existing1 = _cart1State.value
                            val effectiveLat = lat ?: existing1.latitude
                            val effectiveLng = lng ?: existing1.longitude
                            val currentDistGate = if (effectiveLat != null && effectiveLng != null) {
                                GeofenceManager.calculateDistanceMeters(effectiveLat, effectiveLng, GeofenceManager.GATE_LAT, GeofenceManager.GATE_LNG).roundToInt()
                            } else existing1.distanceToGateMeters ?: 0

                            _cart1State.value = existing1.copy(
                                cartId = "cart_1",
                                cartName = "Cart 1",
                                latitude = effectiveLat,
                                longitude = effectiveLng,
                                speedKmH = speedKmH,
                                bearing = bearing,
                                status = status,
                                isTripActive = isTripActive,
                                isAvailable = effectiveIsAvailable1,
                                driverStatus = effectiveDriverStatus1,
                                lastUpdatedMillis = lastUpdated,
                                lastHeartbeatMillis = lastHeartbeat,
                                locationTimestampMillis = locationTimestamp,
                                distanceToGateMeters = currentDistGate,
                                distanceToUserMeters = currentDistGate,
                                direction = direction ?: existing1.direction,
                                currentStop = currentStop ?: existing1.currentStop,
                                nextStop = nextStop ?: existing1.nextStop
                            )
                        }
                    } catch (e: Exception) {
                        Log.w("CampusRideRepo", "Refresh cart_1 fetch warning: ${e.message}")
                    }

                    // Explicitly fetch cart_2 snapshot
                    try {
                        val cart2Doc = firestore.collection("drivers").document("cart_2").get().awaitTask()
                        if (cart2Doc.exists()) {
                            val lat = cart2Doc.safeDouble("latitude")
                            val lng = cart2Doc.safeDouble("longitude")
                            val bearing = cart2Doc.safeFloat("bearing", 0f)
                            val speedKmH = cart2Doc.safeInt("speedKmH", 0)
                            val statusStr = cart2Doc.getString("status") ?: "HALTED"
                            val isAvailable = cart2Doc.getBoolean("isAvailable") ?: true
                            val isTripActive = cart2Doc.getBoolean("isTripActive") ?: false
                            val driverStatus = cart2Doc.getString("driverStatus") ?: "Available"

                            val isOutside2 = (lat != null && lng != null && !GeofenceManager.isInsideCampusGeofence(lat, lng)) ||
                                             driverStatus.equals("Outside Campus", ignoreCase = true) ||
                                             driverStatus.equals("Driver Not Available", ignoreCase = true)
                            val effectiveIsAvailable2 = if (isOutside2) false else isAvailable
                            val effectiveDriverStatus2 = if (isOutside2) "Driver Not Available" else driverStatus
                            var status = try { GolfCartStatus.valueOf(statusStr) } catch (e: Exception) { GolfCartStatus.HALTED }
                            if (isOutside2) {
                                status = GolfCartStatus.OFFLINE
                            } else if (status == GolfCartStatus.OFFLINE && (effectiveIsAvailable2 || effectiveDriverStatus2.equals("Available", ignoreCase = true) || effectiveDriverStatus2.equals("On Trip", ignoreCase = true))) {
                                status = if (speedKmH > 0) GolfCartStatus.MOVING else GolfCartStatus.HALTED
                            }
                            val lastUpdated = cart2Doc.safeLong("lastUpdatedMillis", cart2Doc.safeLong("last_seen", System.currentTimeMillis()))
                            val lastHeartbeat = cart2Doc.safeLong("lastHeartbeatMillis", cart2Doc.safeLong("last_seen", lastUpdated))
                            val locationTimestamp = cart2Doc.safeLong("locationTimestampMillis", if (lat != null && lng != null) lastUpdated else 0L).let { if (it > 0L) it else null }
                            val direction = cart2Doc.getString("direction")
                            val currentStop = cart2Doc.getString("currentStop")
                            val nextStop = cart2Doc.getString("nextStop")

                            val existing2 = _cart2State.value
                            val effectiveLat = lat ?: existing2.latitude
                            val effectiveLng = lng ?: existing2.longitude
                            val currentDistGate = if (effectiveLat != null && effectiveLng != null) {
                                GeofenceManager.calculateDistanceMeters(effectiveLat, effectiveLng, GeofenceManager.GATE_LAT, GeofenceManager.GATE_LNG).roundToInt()
                            } else existing2.distanceToGateMeters ?: 0

                            _cart2State.value = existing2.copy(
                                cartId = "cart_2",
                                cartName = "Cart 2",
                                latitude = effectiveLat,
                                longitude = effectiveLng,
                                speedKmH = speedKmH,
                                bearing = bearing,
                                status = status,
                                isTripActive = isTripActive,
                                isAvailable = effectiveIsAvailable2,
                                driverStatus = effectiveDriverStatus2,
                                lastUpdatedMillis = lastUpdated,
                                lastHeartbeatMillis = lastHeartbeat,
                                locationTimestampMillis = locationTimestamp,
                                distanceToGateMeters = currentDistGate,
                                distanceToUserMeters = currentDistGate,
                                direction = direction ?: existing2.direction,
                                currentStop = currentStop ?: existing2.currentStop,
                                nextStop = nextStop ?: existing2.nextStop
                            )
                        }
                    } catch (e: Exception) {
                        Log.w("CampusRideRepo", "Refresh cart_2 fetch warning: ${e.message}")
                    }

                    _fleetCarts.value = listOf(_cart1State.value, _cart2State.value)
                    val selCart = if (_selectedDriverCartId.value == "cart_2") _cart2State.value else _cart1State.value
                    _golfCartState.value = selCart

                    // Fetch active ride requests
                    try {
                        val reqSnapshot = firestore.collection("ride_requests").get().awaitTask()
                        if (!reqSnapshot.isEmpty) {
                            val fetchedList = mutableListOf<RideRequest>()
                            for (doc in reqSnapshot.documents) {
                                try {
                                    val id = doc.getString("id") ?: doc.id
                                    val requesterTypeStr = doc.getString("requesterType") ?: "STUDENT"
                                    val reqType = if (requesterTypeStr == "FACULTY") com.example.data.model.RequesterType.FACULTY else com.example.data.model.RequesterType.STUDENT
                                    val studentName = doc.getString("studentName") ?: ""
                                    val pickupLocation = doc.getString("pickupLocation") ?: "Main Gate"
                                    val distance = doc.getLong("distanceToGateMeters")?.toInt() ?: 0
                                    val statusStr = doc.getString("status") ?: "PENDING"
                                    val status = try { RideRequestStatus.valueOf(statusStr) } catch (e: Exception) { RideRequestStatus.PENDING }
                                    val cartId = doc.getString("assignedCartId")
                                    val cartName = doc.getString("assignedCartName")
                                    val timestamp = doc.getLong("timestamp") ?: System.currentTimeMillis()
                                    val studentsWaiting = doc.getLong("studentsWaiting")?.toInt()?.coerceIn(1, 10) ?: 1

                                    fetchedList.add(
                                        RideRequest(
                                            id = id,
                                            requesterType = reqType,
                                            studentName = studentName,
                                            pickupLocation = pickupLocation,
                                            distanceToGateMeters = distance,
                                            status = status,
                                            timestamp = timestamp,
                                            studentsWaiting = studentsWaiting,
                                            assignedCartId = cartId,
                                            assignedCartName = cartName
                                        )
                                    )
                                } catch (e: Exception) {
                                    Log.e("CampusRideRepo", "Error parsing request during refresh", e)
                                }
                            }
                            if (fetchedList.isNotEmpty()) {
                                _requests.value = fetchedList
                            }
                        }
                    } catch (e: Exception) {
                        Log.w("CampusRideRepo", "Refresh ride_requests warning: ${e.message}")
                    }

                    // Check active user request status
                    val currentActiveReq = _activeStudentRequest.value
                    if (currentActiveReq != null) {
                        try {
                            val activeDoc = firestore.collection("ride_requests").document(currentActiveReq.id).get().awaitTask()
                            if (activeDoc.exists()) {
                                val statusStr = activeDoc.getString("status")
                                if (statusStr != null) {
                                    val status = try { RideRequestStatus.valueOf(statusStr) } catch (e: Exception) { currentActiveReq.status }
                                    val driverLat = activeDoc.getDouble("driverLat")
                                    val driverLng = activeDoc.getDouble("driverLng")
                                    val driverBearing = activeDoc.getDouble("driverBearing")?.toFloat() ?: 0f
                                    _activeStudentRequest.value = currentActiveReq.copy(
                                        status = status,
                                        driverLat = driverLat,
                                        driverLng = driverLng,
                                        driverBearing = driverBearing
                                    )
                                }
                            }
                        } catch (e: Exception) {
                            Log.w("CampusRideRepo", "Refresh activeStudentRequest warning: ${e.message}")
                        }
                    }

                    anyFetchSucceeded = true
                }
            } catch (e: Exception) {
                Log.w("CampusRideRepo", "Firestore refresh exception: ${e.message}")
                networkError = e
            }

            // 2. Try REST backend API sync as well
            try {
                val backendCarts = CampusBackendClient.api.getCarts()
                if (backendCarts.isSuccessful && backendCarts.body()?.success == true) {
                    val carts = backendCarts.body()?.carts.orEmpty()
                    if (carts.isNotEmpty()) {
                        val updatedFleet = _fleetCarts.value.toMutableList()
                        for (c in carts) {
                            val idx = updatedFleet.indexOfFirst { it.cartId == c.cartId }
                            if (idx >= 0) {
                                val current = updatedFleet[idx]
                                val restTimestamp = c.lastUpdatedMillis ?: c.locationTimestampMillis ?: 0L
                                val currentTimestamp = current.lastUpdatedMillis ?: current.locationTimestampMillis ?: 0L
                                val isCurrentFresh = (current.isLive || current.isDriverOnline) && 
                                    ((System.currentTimeMillis() - currentTimestamp) < GolfCartState.LOCATION_STALE_THRESHOLD_MS)

                                // If real-time Firestore listener already holds fresh driver telemetry, NEVER overwrite with older or offline REST data
                                if (isCurrentFresh && restTimestamp <= currentTimestamp) {
                                    continue
                                }

                                val rawStatus = c.status
                                val effectiveCartStatus = try {
                                    if (rawStatus != null) GolfCartStatus.valueOf(rawStatus) else null
                                } catch (e: Exception) { null } ?: run {
                                    val isAvail = c.isAvailable == true || c.driverStatus.equals("Available", ignoreCase = true) || c.driverStatus.equals("On Trip", ignoreCase = true)
                                    if (isAvail) {
                                        if ((c.speedKmH ?: 0) > 0) GolfCartStatus.MOVING else GolfCartStatus.HALTED
                                    } else {
                                        GolfCartStatus.OFFLINE
                                    }
                                }
                                updatedFleet[idx] = current.copy(
                                    latitude = c.latitude ?: current.latitude,
                                    longitude = c.longitude ?: current.longitude,
                                    speedKmH = c.speedKmH ?: current.speedKmH,
                                    bearing = c.bearing ?: current.bearing,
                                    status = if (isCurrentFresh) current.status else effectiveCartStatus,
                                    driverStatus = if (isCurrentFresh) current.driverStatus else (c.driverStatus ?: current.driverStatus),
                                    isAvailable = if (isCurrentFresh) current.isAvailable else (c.isAvailable ?: current.isAvailable),
                                    lastUpdatedMillis = c.lastUpdatedMillis ?: current.lastUpdatedMillis,
                                    lastHeartbeatMillis = c.lastHeartbeatMillis ?: current.lastHeartbeatMillis,
                                    locationTimestampMillis = c.locationTimestampMillis ?: current.locationTimestampMillis
                                )
                            }
                        }
                        _fleetCarts.value = updatedFleet
                        val c1 = updatedFleet.find { it.cartId == "cart_1" }
                        val c2 = updatedFleet.find { it.cartId == "cart_2" }
                        if (c1 != null) _cart1State.value = c1
                        if (c2 != null) _cart2State.value = c2
                    }
                    anyFetchSucceeded = true
                }
            } catch (e: Exception) {
                Log.w("CampusRideRepo", "REST backend refresh notice: ${e.message}")
                if (networkError == null) networkError = e
            }

            // 3. Re-evaluate local state
            checkAndRestoreLunchBreak()
            reevaluateEffectiveDriverAvailability()

            if (anyFetchSucceeded) {
                Result.success(Unit)
            } else if (networkError != null) {
                Result.failure(networkError)
            } else {
                Result.success(Unit)
            }
        } finally {
            refreshMutex.unlock()
        }
    }

    companion object {
        @Volatile
        var instance: CampusRideRepository? = null

        fun getInstance(context: Context): CampusRideRepository {
            return instance ?: synchronized(this) {
                instance ?: CampusRideRepository(context.applicationContext).also { instance = it }
            }
        }
    }
}

private fun com.google.firebase.firestore.DocumentSnapshot.safeDouble(field: String): Double? =
    (get(field) as? Number)?.toDouble()

private fun com.google.firebase.firestore.DocumentSnapshot.safeFloat(field: String, default: Float = 0f): Float =
    (get(field) as? Number)?.toFloat() ?: default

private fun com.google.firebase.firestore.DocumentSnapshot.safeInt(field: String, default: Int = 0): Int =
    (get(field) as? Number)?.toInt() ?: default

private fun com.google.firebase.firestore.DocumentSnapshot.safeLong(field: String, default: Long = 0L): Long =
    (get(field) as? Number)?.toLong() ?: default

