# Campus Ride — Complete System Architecture, Codebase Specification & Flaw Analysis

**Target Project:** Campus Ride (IIIT Bhagalpur Campus Electric Mobility Platform)  
**Target Audience:** AI Systems (ChatGPT / Claude / Gemini), Senior Android Engineers, Systems Architects  
**Purpose:** Provide an exhaustive, 360-degree, zero-ambiguity blueprint of the entire Campus Ride mobile application and cloud backend, documenting all components, data pipelines, lifecycle behaviors, data contracts, and existing architectural flaws so the system can be thoroughly understood and solved cleanly from scratch.

---

## Table of Contents
1. [Project Overview & Core Mission](#1-project-overview--core-mission)
2. [Hardware, Vehicles, Drivers & Institutional Context](#2-hardware-vehicles-drivers--institutional-context)
3. [Complete Tech Stack & Dependencies](#3-complete-tech-stack--dependencies)
4. [Full Codebase Directory & File Structure](#4-full-codebase-directory--file-structure)
5. [The Three User Roles & Screen-by-Screen Workflows](#5-the-three-user-roles--screen-by-screen-workflows)
   - [5.1 Driver Terminal](#51-driver-terminal)
   - [5.2 Student Portal](#52-student-portal)
   - [5.3 Faculty Portal](#53-faculty-portal)
6. [End-to-End Data Pipelines & Flowcharts](#6-end-to-end-data-pipelines--flowcharts)
   - [Pipeline A: Driver GPS Broadcast → Cloud → Passenger Screens](#pipeline-a-driver-gps-broadcast--cloud--passenger-screens)
   - [Pipeline B: Ride Request Dispatch & High-Priority Wake Overlay](#pipeline-b-ride-request-dispatch--high-priority-wake-overlay)
   - [Pipeline C: Geofencing, Presence & Automated Duty State Machine](#pipeline-c-geofencing-presence--automated-duty-state-machine)
7. [Comprehensive Data Models & Cloud Schemas](#7-comprehensive-data-models--cloud-schemas)
   - [7.1 GolfCartState Data Model](#71-golfcartstate-data-model)
   - [7.2 Cloud Firestore Schemas](#72-cloud-firestore-schemas)
   - [7.3 Node.js / Render Backend REST Contracts](#73-nodejs--render-backend-rest-contracts)
8. [Geospatial & Route Engine: CampusRouteGraph & GeofenceManager](#8-geospatial--route-engine-campusroutegraph--geofencemanager)
9. [Forensic Analysis: Root Causes of All Flaws & Regressions](#9-forensic-analysis-root-causes-of-all-flaws--regressions)
   - [Flaw 1: Driver Screen "Syncing GPS" & "LOCATION UNAVAILABLE" with Map Visible](#flaw-1-driver-screen-syncing-gps--location-unavailable-with-map-visible)
   - [Flaw 2: Student & Faculty Showing "Cart Not Available" & No Location Updates](#flaw-2-student--faculty-showing-cart-not-available--no-location-updates)
   - [Flaw 3: Cross-Device Clock Skew & The `localReceiptAgeMs` Fallacy](#flaw-3-cross-device-clock-skew--the-localreceiptagems-fallacy)
   - [Flaw 4: Concurrency, Disk Cache Dropping Live Firestore Snapshots](#flaw-4-concurrency-disk-cache-dropping-live-firestore-snapshots)
   - [Flaw 5: The Fragile 7-Boolean Availability Gate Cascade](#flaw-5-the-fragile-7-boolean-availability-gate-cascade)
   - [Flaw 6: Android OS Battery Optimizations & OEM Background Service Killing](#flaw-6-android-os-battery-optimizations--oem-background-service-killing)
10. [Clean-Slate Blueprint: How to Re-Architect & Solve From Scratch](#10-clean-slate-blueprint-how-to-re-architect--solve-from-scratch)

---

## 1. Project Overview & Core Mission

**Campus Ride** is a production Android application custom-engineered for **IIIT Bhagalpur (Indian Institute of Information Technology Bhagalpur, Bihar, India)**. The primary objective is to manage, monitor, and coordinate the campus electric golf cart transport fleet that ferries students, faculty members, and campus visitors across the academic, residential, and administrative zones of the campus.

### Core Problems Solved by the App:
1. **Unpredictable Cart Availability:** Previously, students waited at the campus gate without knowing if a golf cart was running, on lunch break, charging, or halted at the hostel.
2. **Geographic Isolation:** The main gate is located far from the student hostels and computer centre; students carrying heavy luggage or during extreme weather need reliable on-demand rides.
3. **Faculty Priority Dispatch:** Faculty members require prioritized dispatching to travel between academic blocks without being stuck behind large student queues.
4. **Driver Duty Accountability:** Drivers frequently forgot to clock in/out or went off campus without notification. The app uses automatic polygon geofencing to set duty status dynamically.

---

## 2. Hardware, Vehicles, Drivers & Institutional Context

The campus fleet consists of **two permanent electric golf carts**:

| Vehicle Identifier | Display Name | UI Accent Theme | Assigned Driver | In-Cart Dedicated SIM / Phone Number |
| :--- | :--- | :--- | :--- | :--- |
| `cart_1` | **Cart 1** | Emerald Green (`#16A34A`) | **Shivam** | **`+91 95724 94687`** |
| `cart_2` | **Cart 2** | Royal Blue / Sky (`#0284C7`) | **Kartik** | **`+91 93367 94056`** |

### Critical Hardware & Phone Number Rules:
- The phone numbers belong strictly to the **VEHICLE (the cart's dedicated in-dashboard device)**, NOT to the personal phone of whichever driver happens to be driving.
- Driver 1 is permanently locked to Cart 1. Driver 2 is permanently locked to Cart 2.
- The numbers cannot be changed by drivers from the UI; they are injected via `BuildConfig` and secured in `CampusCartConfig.kt`.

### Fixed Campus Route & Key Landmarks:
The campus transport operates along a bidirectional linear-branching spine:
$$\text{Main Gate (Entrance)} \longleftrightarrow \text{Trunkut (Midway Junction)} \longleftrightarrow \text{Computer Centre} \longleftrightarrow \text{Boys Hostel}$$

- **Main Gate**: Latitude `25.2531616`, Longitude `87.0370730`
- **Computer Centre**: Latitude `25.2565000`, Longitude `87.0400000`
- **Boys Hostel**: Latitude `25.2577810`, Longitude `87.0418910`
- **Library Reference**: Latitude `25.2550000`, Longitude `87.0390000`

---

## 3. Complete Tech Stack & Dependencies

### Android Client
- **Operating System Target:** Android 14 / 15 (API level 34–35), Minimum SDK: API 26 (Android 8.0 Oreo).
- **Programming Language:** Kotlin 2.0+ with Kotlin Coroutines & Flow.
- **UI Framework:** **Jetpack Compose** with Material 3 design tokens. Single-Activity architecture (`MainActivity.kt`) hosting a Compose `NavHost`.
- **State Management:** MVI / Clean Repository Pattern using `StateFlow`, `SharedFlow`, Coroutine `Mutex`, and `viewModelScope` / `applicationScope`.
- **Location Services:** Google Play Services `FusedLocationProviderClient` backed by Android framework `LocationManager` (GPS_PROVIDER & NETWORK_PROVIDER failover).
- **Background Processing:** Android `ForegroundService` with `foregroundServiceType="location"`, coupled with explicit wake locks (`PowerManager.PARTIAL_WAKE_LOCK`).
- **Mapping Engines (Dual-Layer):**
  1. *Primary Map:* Google Maps Android SDK via Compose (`com.google.maps.android:maps-compose:6.5.0`) with custom Canvas-drawn directional markers.
  2. *Fallback Map:* `CampusOpenStreetMapView.kt` — A fully custom, zero-dependency OpenStreetMap tile renderer built directly on Compose `Canvas`, utilizing memory LRU caching and local disk caching to guarantee map availability during Google Play Services outages.

### Cloud & Backend Stack
- **Database & Sync:** **Google Cloud Firestore** (Real-time snapshot listeners with low-latency document sync).
- **Push Notification Infrastructure:** **Firebase Cloud Messaging (FCM)** using high-priority data payloads that wake sleeping Android devices and launch full-screen incoming ride alarms.
- **REST Backend API:** Node.js Express server hosted on **Render** (`https://campus-ride-backend-df0n.onrender.com/`) backed by Firebase Admin SDK.
- **Serialization:** Moshi (`com.squareup.moshi:moshi-kotlin:1.15.1`) with OkHttp3 logging interceptors.

---

## 4. Full Codebase Directory & File Structure

```
campus-ride/app/src/main/java/com/example/
├── CampusApplication.kt                     # Application class; initializes Firebase, notification channels, sonic logo
├── MainActivity.kt                          # Single host activity; handles deep links, edge-to-edge, wake flags
│
├── data/
│   ├── api/
│   │   └── CampusBackendApi.kt              # Retrofit & Moshi definitions for Render Node.js backend
│   ├── model/
│   │   ├── CampusCartConfig.kt              # Fixed Cart 1 & Cart 2 telephone number provider
│   │   ├── GolfCartState.kt                 # Core state data class for cart location, status & presence
│   │   ├── RideRequest.kt                   # Ride request entity (PENDING, ACCEPTED, DECLINED, etc.)
│   │   ├── ScheduleStatus.kt                # Working hours & driver availability schedule calculator
│   │   └── UserRole.kt                      # Enum: STUDENT, DRIVER, FACULTY
│   └── repository/
│       └── CampusRideRepository.kt          # Monolithic single-source-of-truth repository (2,990+ lines)
│
├── location/
│   ├── CampusLandmarkZone.kt                # Geofence landmark zones, stop naming & ETA projection
│   ├── CampusRouteGraph.kt                  # Waypoint graph, directional projection & stop progress engine
│   ├── DriverLocationService.kt             # Android Foreground Service broadcasting GPS telemetry
│   ├── DriverLocationTracker.kt             # Sensor fusion & motion analyzer (Stationary vs Moving)
│   ├── GeofenceManager.kt                   # Campus polygon geofence evaluator & Haversine distance math
│   ├── PermissionUtils.kt                   # Android 14+ runtime permission checkers (Fine location, background)
│   └── TrackingConfig.kt                    # Network throttling, thresholds, backoff, and diagnostics
│
├── notification/
│   ├── CampusFirebaseMessagingService.kt    # FCM background receiver for ride alerts & duty triggers
│   ├── CriticalAlertManager.kt              # Custom multi-channel audio & vibration synthesizer
│   └── FcmRoleNotificationManager.kt        # Topic subscriber (e.g. "driver_cart_1", "role_student")
│
└── ui/
    ├── components/
    │   ├── CampusCartCard.kt                # Material 3 vehicle card showing live status, ETA, and call button
    │   ├── CampusGoogleMapView.kt           # Google Maps Compose view with custom cart markers
    │   ├── CampusOpenStreetMapView.kt       # Custom zero-dependency OSM tile renderer fallback
    │   ├── IncomingDriverAlertOverlay.kt    # Full-screen incoming ride dialog waking sleeping driver device
    │   └── LiveRouteTrackingCard.kt         # Live Route progress card: Passed, Approaching, and Reached stops
    │
    ├── driver/
    │   └── DriverDashboardScreen.kt         # Driver cockpit: GPS broadcasting, duty toggles, lunch break
    ├── faculty/
    │   └── FacultyDashboardScreen.kt        # Faculty priority dispatch dashboard & directory
    ├── student/
    │   └── StudentDashboardScreen.kt        # Student dashboard: Live tracking, ride request bottom sheet
    ├── navigation/
    │   ├── CampusAppNavigation.kt           # NavHost, role routing, notification intent redirection
    │   └── NavRoutes.kt                     # Route strings (SPLASH, ROLE_SELECTION, STUDENT_DASHBOARD, etc.)
    ├── permissions/
    │   ├── DriverNotificationSetupScreen.kt # High-visibility permission & battery optimization wizard
    │   └── PermissionOnboardingScreen.kt    # Step-by-step Android runtime permission onboarding
    ├── roleselection/
    │   └── RoleSelectionScreen.kt           # Visual role selector with passcode gate for Drivers & Faculty
    ├── settings/
    │   ├── DriverRingtoneSettingsScreen.kt  # Custom sonic ringtone selector for driver alerts
    │   ├── FcmDiagnosticsScreen.kt          # Deep FCM diagnostics, token inspector & cloud test panel
    │   └── SettingsScreen.kt                # App settings, dark mode toggle, and working hours override
    └── splash/
        └── SplashScreen.kt                  # Startup screen with cinematic canvas animation & sonic logo
```

---

## 5. The Three User Roles & Screen-by-Screen Workflows

```
                                +---------------------------+
                                |    RoleSelectionScreen    |
                                +-------------+-------------+
                                              |
                   +--------------------------+--------------------------+
                   |                          |                          |
                   v                          v                          v
        +--------------------+      +--------------------+      +--------------------+
        |   STUDENT PORTAL   |      |   FACULTY PORTAL   |      |  DRIVER TERMINAL   |
        |  (Instant Access)  |      | (Passcode: 112233) |      | (Passcode: 987654) |
        +--------------------+      +--------------------+      +--------------------+
```

### 5.1 Driver Terminal
1. **Authentication & Vehicle Lock:**
   - Driver selects "Driver" on `RoleSelectionScreen`, enters the driver security passcode (`987654`).
   - Driver selects their assigned identity: **Shivam (Cart 1)** or **Kartik (Cart 2)**.
   - Once selected, the assignment is stored in `SharedPreferences` (`saved_driver_cart_id`) and permanently locked.
2. **Foreground Location Service Activation:**
   - On dashboard entry, `DriverLocationService.startTrip(context, cartId)` is invoked.
   - Starts a foreground service with a sticky persistent notification: `"Campus Ride: Cart 1 (Broadcasting GPS)"`.
   - Acquires a `PARTIAL_WAKE_LOCK` so the CPU does not sleep when the screen turns off.
3. **Automated Geofence Duty Engine:**
   - As GPS coordinates arrive from the hardware, `GeofenceManager.isInsideCampusGeofence(lat, lng)` is tested against the IIIT Bhagalpur polygon boundary.
   - **Inside Campus:** Driver is automatically marked **"On Duty" / "Available"**.
   - **Outside Campus:** Driver is automatically flipped to **"Driver Not Available" / "Outside Campus"**, and cart status is set to `GolfCartStatus.OFFLINE`.
4. **Lunch Break Protocol:**
   - Driver can trigger a single 45-minute Lunch Break per day.
   - Syncs to Firestore: `driverStatus = "Lunch Break"`, `isAvailable = false`.
   - A daily reset ticker automatically restores the driver to "Available" at midnight or after the break duration expires.
5. **Incoming Ride Alert & Full-Screen Overlay:**
   - When a student or faculty member requests a ride, Firestore listener or FCM pushes the request.
   - `IncomingDriverAlertOverlay.kt` wakes the screen, vibrates aggressively, loops the high-priority sonic chime, and presents two buttons: **"ACCEPT RIDE"** or **"DECLINE"**.
   - Accepting assigns `assignedCartId = activeCartId` and puts the driver in **"On Trip"** status.

### 5.2 Student Portal
1. **Live Cart Tracking:**
   - Students see an interactive campus map (Google Maps or fallback OSM) displaying the real-time location, bearing, and speed of Cart 1 and Cart 2.
2. **Vehicle Information Card (`CampusCartCard.kt`):**
   - Displays live badges: `LIVE` (green pulsing), `STALE GPS` (amber), `SYNCING GPS` (cyan), or `OFFLINE` (gray).
   - Shows current location: e.g., `"Near Computer Centre"`, `"Between Trunkut & CC"`, or `"At Boys Hostel"`.
   - Direct Call Button: Triggers `Intent.ACTION_DIAL` dialing the fixed in-cart SIM (`+91-9572494687` for Cart 1).
3. **Live Route Progress Card (`LiveRouteTrackingCard.kt`):**
   - Displays the sequential route spine:
     - **Reached Stops:** Stops the cart has already traversed on this run.
     - **Current Stop:** Where the cart is right now (with ETA in minutes).
     - **Approaching Stops:** Upcoming stops along the active heading.
4. **Requesting a Ride:**
   - If a driver is online and inside campus, the student taps **"Request Ride"**.
   - Selects number of students waiting (1 to 10) and pickup location (default: Main Gate).
   - Firestore writes a document to `ride_requests` with status `PENDING`.
   - Real-time UI updates to: `"Request Pending..."` → `"Ride Accepted by Cart 1 (En Route, 300m away)"` → `"Cart Arrived at Gate"`.

### 5.3 Faculty Portal
- Accessible via Faculty passcode (`112233`).
- Offers an enhanced **Priority Dispatch** button that tags `requesterType = "FACULTY"`, bypassing standard student waiting queues.
- Dedicated directory for campus administration and emergency transport hotline.

---

## 6. End-to-End Data Pipelines & Flowcharts

### Pipeline A: Driver GPS Broadcast → Cloud → Passenger Screens

```
[Driver Phone Hardware GPS]
          │
          ▼
[FusedLocationProviderClient / LocationManager]
          │ (Every 1s - 4s)
          ▼
[DriverLocationService.kt] ──> [DriverLocationTracker.kt (Motion & Speed Analyzer)]
          │
          ▼
[CampusRideRepository.updateDriverGpsLocation(lat, lng, speed, bearing)]
          │
          ├────────────────────────┬────────────────────────┐
          │                        │                        │
          ▼                        ▼                        ▼
[GeofenceManager]         [CampusLandmarkZone]    [CampusRouteGraph]
Evaluates polygon bounds   Evaluates landmark      Evaluates directional
(Inside vs Outside)        & current stop name     spine & stop progress
          │                        │                        │
          └────────────────────────┴────────────────────────┘
                                   │
                                   ▼
        [Update Local State: _cart1State.value / _cart2State.value]
                                   │
                ┌──────────────────┴──────────────────┐
                │ Throttled Network Evaluation        │
                │ (Distance >= 3m, Heading >= 15°,    │
                │  or Keep-Alive Heartbeat >= 35s)    │
                └──────────────────┬──────────────────┘
                                   │
        ┌──────────────────────────┴──────────────────────────┐
        │                                                     │
        ▼                                                     ▼
[Cloud Firestore: SetOptions.merge()]             [Render Node.js Backend API]
Doc: drivers/cart_1 or drivers/cart_2            POST /api/carts/location
Fields: latitude, longitude, bearing,            (Secondary mirror & FCM relay)
speedKmH, status, driverStatus,
lastUpdatedMillis, lastHeartbeatMillis
        │
        ▼ (Real-time WebSocket / gRPC Push)
[Student / Faculty Device: Firestore addSnapshotListener]
        │
        ▼
[CampusRideRepository.handleCartSnapshot(cartId, snapshot)]
        │
        ▼
[Local Flow: _cart1State.value = updatedCart]
        │
        ├────────────────────────┬────────────────────────┐
        │                        │                        │
        ▼                        ▼                        ▼
[CampusGoogleMapView]   [LiveRouteTrackingCard]   [CampusCartCard]
Draws marker at CC      Evaluates route progress   Updates badge, ETA
with heading arrow      (Passed, Next Stop, ETA)   and phone dialer
```

---

### Pipeline B: Ride Request Dispatch & High-Priority Wake Overlay

```
[Student Device: StudentDashboardScreen.kt]
          │ (User taps "Request Cart")
          ▼
[CampusRideRepository.sendRideRequest(...)]
          │
          ├───────────────────────────────────────────────────┐
          │                                                   │
          ▼                                                   ▼
[Cloud Firestore: collection("ride_requests")]     [Render Node.js Backend API]
Doc: ride_requests/{uuid}                          POST /api/rides/request
Fields: studentName, pickupLocation,                          │
studentsWaiting, status = "PENDING",                          ▼
assignedCartId = "cart_1"                          [Firebase Admin SDK FCM Push]
          │                                        Topic: "driver_cart_1"
          │                                        Payload: { type: "RIDE_REQUEST",
          │                                                  requestId: uuid,
          │                                                  pickup: "Main Gate" }
          │                                                   │
          │                                                   ▼
          │                                  [Driver Phone: CampusFirebaseMessagingService]
          │                                                   │
          │                                                   ▼
          │                                  [CriticalAlertManager: Play Sonic Siren]
          │                                                   │
          └───────────────────┬───────────────────────────────┘
                              │
                              ▼
           [IncomingDriverAlertOverlay.kt (Full Screen Wake)]
                              │
               ┌──────────────┴──────────────┐
               │                             │
               ▼                             ▼
       [Driver Taps ACCEPT]          [Driver Taps DECLINE]
               │                             │
               ▼                             ▼
Firestore: status = "ACCEPTED"       Firestore: status = "DECLINED"
Driver marked: "On Trip"             Re-assigned to Cart 2 or Queue
Passenger UI: "Driver En Route"      Passenger UI: "Driver Busy, Re-routing"
```

---

## 7. Comprehensive Data Models & Cloud Schemas

### 7.1 GolfCartState Data Model
Located at `app/src/main/java/com/example/data/model/GolfCartState.kt`:

```kotlin
data class GolfCartState(
    val cartId: String? = null,                       // "cart_1" or "cart_2"
    val cartName: String? = null,                     // "Cart 1" or "Cart 2"
    val driverId: String? = null,                     // e.g. "driver_shivam"
    val tripId: String? = null,                       // Active accepted ride ID
    val isTripActive: Boolean = false,                // True if currently transporting passengers
    val latitude: Double? = null,                     // WGS84 Latitude
    val longitude: Double? = null,                    // WGS84 Longitude
    val speedKmH: Int? = null,                        // Speed in km/h (0 to 45)
    val bearing: Float? = null,                       // Compass heading (0.0° to 359.9°)
    val accuracy: Float? = null,                      // GPS horizontal accuracy in meters
    val status: GolfCartStatus? = null,               // MOVING, HALTED, OFFLINE
    val batteryLevel: Int? = null,                    // 0% to 100%
    val lastUpdatedMillis: Long? = null,              // Remote timestamp of last Firestore document write
    val lastHeartbeatMillis: Long? = null,            // Remote timestamp of last stationary keep-alive
    val locationTimestampMillis: Long? = null,         // Remote timestamp when GPS hardware generated coordinate
    val distanceToGateMeters: Int? = null,            // Haversine distance to Main Gate
    val distanceToUserMeters: Int? = null,            // Distance to passenger device
    val relativeMovement: String? = null,             // "Coming Towards You", "Moving Away", "Stationary"
    val etaMinutes: Int? = null,                      // Estimated arrival time
    val driverStatus: String? = null,                 // "Available", "On Trip", "Lunch Break", "Offline", "Driver Not Available"
    val isAvailable: Boolean = false,                 // Boolean flag for request eligibility
    val activeRequestId: String? = null,              // Linked ride request document ID
    val direction: String? = null,                    // e.g. "Main Gate → Boys Hostel"
    val currentStop: String? = null,                  // e.g. "Near Computer Centre"
    val nextStop: String? = null,                     // e.g. "Trunkut"
    val localReceiptTimestampMillis: Long = System.currentTimeMillis() // Local phone timestamp when received
)
```

#### Vital Computed Thresholds & Properties:
- `HEARTBEAT_INTERVAL_MS = 25_000L` (25 seconds)
- `LOCATION_STALE_THRESHOLD_MS = 90_000L` (90 seconds — grace period for stationary carts)
- `HEARTBEAT_EXPIRATION_MS = 120_000L` (120 seconds — cart declared offline if no ping received)
- `LOCATION_EXPIRED_THRESHOLD_MS = 180_000L` (180 seconds — coordinates purged from live route graph)

```kotlin
enum class CartPresenceState(val label: String, val badgeText: String) {
    ONLINE_LOCATION_AVAILABLE("Online • Live Location", "Live"),
    ONLINE_LOCATION_STALE("Online • Stale Location", "Stale GPS"),
    ONLINE_NO_LOCATION("Online • Location Pending", "Syncing GPS"),
    OFFLINE("Offline", "Offline"),
    NETWORK_ERROR("Network Error", "Offline")
}
```

---

### 7.2 Cloud Firestore Schemas

#### Collection: `drivers`
Documents: `drivers/cart_1` and `drivers/cart_2`

```json
{
  "cartId": "cart_1",
  "cartName": "Cart 1",
  "latitude": 25.2565000,
  "longitude": 87.0400000,
  "bearing": 182.5,
  "speedKmH": 12,
  "accuracy": 4.2,
  "status": "MOVING",
  "isTripActive": false,
  "isAvailable": true,
  "onDuty": true,
  "manualOffDuty": false,
  "isOnline": true,
  "isBusy": false,
  "insideCampus": true,
  "sessionId": "sess_1727245000000_cart_1",
  "driverStatus": "Available",
  "direction": "Boys Hostel → Main Gate",
  "currentStop": "Near Computer Centre",
  "nextStop": "Trunkut",
  "distanceToGateMeters": 350,
  "lastUpdatedMillis": 1727245100000,
  "lastHeartbeatMillis": 1727245100000,
  "locationTimestampMillis": 1727245100000,
  "last_seen": 1727245100000
}
```

#### Collection: `ride_requests`
Document: `ride_requests/{requestId}`

```json
{
  "id": "req_8f3b2190-7a8e-4b2a",
  "requesterType": "STUDENT",
  "studentName": "Aman Verma",
  "studentId": "2024CSB1002",
  "pickupLocation": "Main Gate",
  "dropoffLocation": "Boys Hostel",
  "studentsWaiting": 3,
  "distanceToGateMeters": 0,
  "assignedCartId": "cart_1",
  "assignedCartName": "Cart 1",
  "status": "ACCEPTED",
  "timestamp": 1727245050000,
  "driverLat": 25.2565000,
  "driverLng": 87.0400000,
  "driverBearing": 182.5
}
```

---

### 7.3 Node.js / Render Backend REST Contracts

Base URL: `https://campus-ride-backend-df0n.onrender.com/`

| HTTP Method | Endpoint | Purpose | Request Body |
| :--- | :--- | :--- | :--- |
| `GET` | `/api/health` | Ping & service availability | None |
| `GET` | `/api/carts` | Bulk fetch all cart states | None |
| `POST` | `/api/carts/location` | Driver pushes telemetry | `{ cartId, latitude, longitude, speedKmH, bearing }` |
| `POST` | `/api/carts/heartbeat`| Keep-alive heartbeat ping | `{ cartId, driverStatus, isOnline, isAvailable }` |
| `POST` | `/api/carts/duty-status`| Update duty status string | `{ cartId, driverStatus }` |
| `POST` | `/api/rides/request` | Submit new ride request | `{ requesterType, studentName, pickupLocation, studentsWaiting, assignedCartId }` |
| `POST` | `/api/rides/{id}/accept`| Driver accepts ride | None |
| `POST` | `/api/rides/{id}/decline`| Driver declines ride | `{ reason }` |

---

## 8. Geospatial & Route Engine: CampusRouteGraph & GeofenceManager

### 8.1 Campus Geofence Polygon (`GeofenceManager.kt`)
The physical perimeter of IIIT Bhagalpur is defined by an 8-vertex bounding polygon:

```kotlin
val CAMPUS_POLYGON = listOf(
    Pair(25.2510, 87.0340), // South-West Boundary
    Pair(25.2510, 87.0450), // South-East Boundary
    Pair(25.2600, 87.0450), // North-East Boundary
    Pair(25.2620, 87.0420), // Far North Point
    Pair(25.2620, 87.0360), // Far North-West Point
    Pair(25.2580, 87.0340), // Mid-West Boundary
    Pair(25.2540, 87.0335), // Gate Entry Perimeter
    Pair(25.2510, 87.0340)  // Closing Vertex
)
```
- **Ray-Casting Algorithm (`containsLocation`):** Tests if the driver's GPS coordinate is strictly inside this polygon.
- **Accuracy Bounds Check:** Rejects GPS fixes with horizontal accuracy > 50 meters to prevent erratic jumping across the campus perimeter.

### 8.2 Route Spine Graph (`CampusRouteGraph.kt`)
Computes sequential progress:
- **Snap to Segment:** Uses orthogonal projection to find the nearest point along the road network.
- **Directional Heading:** Evaluates if the cart vector points towards the Main Gate or towards the Boys Hostel.
- **Stop Detection:**
  - If distance to landmark $\le 25\text{m}$, the cart is flagged **"At Landmark"** (e.g. `At Computer Centre`).
  - If moving between landmarks, flagged **"Between X & Y"** (e.g. `Between Trunkut & Computer Centre`).
  - Upcoming stops along the active vector populate the **"Approaching Stops"** carousel with real-time ETA estimates.

---

## 9. Forensic Analysis: Root Causes of All Flaws & Regressions

This section details the exact technical root causes of why the system suffered from tracking failures, "Syncing GPS" freezes, and "Cart Not Available" errors.

---

### Flaw 1: Driver Screen "Syncing GPS" & "LOCATION UNAVAILABLE" with Map Visible

#### Phenomenon:
The driver's screen displayed the map showing the cart marker moving near Computer Centre. However, directly below the map, the cart tab displayed `Cart 1 (Syncing GPS)` (in cyan) instead of `Online • Live`, and the route card displayed:
```
LOCATION UNAVAILABLE
Waiting for cart location...
```

#### Forensic Code Path:
1. In `GolfCartState.kt`:
   ```kotlin
   val locationAgeMs: Long
       get() {
           val ts = locationTimestampMillis ?: lastUpdatedMillis ?: return Long.MAX_VALUE
           val now = System.currentTimeMillis()
           val rawAge = if (now >= ts) now - ts else 0L
           return maxOf(rawAge, localReceiptAgeMs)
       }
   ```
2. In `CampusRideRepository.updateDriverGpsLocation`:
   Every GPS fix updated `_cart1State.value = existing.copy(...)`.
   However, `localReceiptTimestampMillis` **was omitted from the copy statement!**
3. `localReceiptTimestampMillis` retained its default value from when the repository was first instantiated at app launch (e.g., 3 minutes ago).
4. `localReceiptAgeMs` was evaluated as `now - T_0 = 180,000ms`.
5. Because of `maxOf(rawAge, localReceiptAgeMs)`, `locationAgeMs` became `180,000ms`, completely ignoring the fresh `rawAge = 0ms`.
6. Since `locationAgeMs >= LOCATION_EXPIRED_THRESHOLD_MS (180,000ms)`:
   - `isLocationAvailable` returned `false`.
   - `presenceState` fell into `CartPresenceState.ONLINE_NO_LOCATION` (badge text: `"Syncing GPS"`).
   - `isLocationExpiredOrMissing` returned `true`.
7. In `LiveRouteTrackingCard.kt`:
   ```kotlin
   latitude = if (!isCartOutside && isCartOnline && displayCart?.hasCoordinates == true && !displayCart.isLocationExpiredOrMissing) displayCart.latitude else null
   ```
   Because `isLocationExpiredOrMissing` was `true`, `latitude = null` was passed into `CampusRouteGraph.evaluateLiveProgress(...)`.
8. `CampusRouteGraph` received `null` latitude and returned `statusMessage = "LOCATION UNAVAILABLE"`, while the map right above it rendered the coordinates because it only checked `hasCoordinates == true`.

---

### Flaw 2: Student & Faculty Showing "Cart Not Available" & No Location Updates

#### Phenomenon:
Even when Driver 1 was logged in, broadcasting GPS, and driving on campus, students and faculty opened their apps and saw:
- Cart status: `Offline` or `Driver Not Available`.
- Message: `🔴 Driver Not Available. Cart driver is currently outside campus or offline.`
- Map markers failed to appear or update.

#### Forensic Code Path:
There were two independent fatal bugs causing this:

**Fatal Bug A: The Cache Monotonic Filter Dropped Live Server Snapshots**  
In `CampusRideRepository.kt` (`handleCartSnapshot`):
```kotlin
val incomingBestTs = maxOf(incomingUpdated, incomingLocationTs)
val existingBestTs = maxOf(existingCart.lastUpdatedMillis ?: 0L, existingCart.locationTimestampMillis ?: 0L)

// Intended to ignore stale disk cache:
if (incomingBestTs > 0L && existingBestTs > 0L && incomingBestTs < existingBestTs) {
    Log.d("CampusRideRepo", "Ignoring out-of-order/stale snapshot for $cartId")
    return // <--- DROPPED LIVE SNAPSHOT!
}
```
- When the student app started, `refreshAllData()` ran and populated `existingBestTs` with the student phone's `System.currentTimeMillis()`.
- If the driver's phone clock was even 1 second behind the student's phone clock, the driver's timestamps (`incomingBestTs`) were strictly less than `existingBestTs`.
- `handleCartSnapshot` dropped **100% of incoming live server snapshots**. The student app never processed any driver location fixes.

**Fatal Bug B: `refreshAllData()` Injected False "Outside Campus" & "OFFLINE" States**  
In `CampusRideRepository.kt` (`refreshAllData`):
```kotlin
val isOutside1 = (lat != null && lng != null && !GeofenceManager.isInsideCampusGeofence(lat, lng)) ||
                 driverStatus.equals("Outside Campus", ignoreCase = true) ||
                 driverStatus.equals("Driver Not Available", ignoreCase = true)

if (isOutside1) {
    status = GolfCartStatus.OFFLINE
    effectiveIsAvailable1 = false
    effectiveDriverStatus1 = "Driver Not Available"
}
```
- If the Firestore document historically had `driverStatus = "Driver Not Available"` (e.g. before the driver reached campus), `refreshAllData` forced `isOutside1 = true` regardless of valid campus GPS coordinates.
- This immediately infected `_cart1State` with `status = OFFLINE`, causing the dashboard to declare the driver unavailable.

---

### Flaw 3: Cross-Device Clock Skew & The `localReceiptAgeMs` Fallacy

#### The Core Problem:
Different mobile phones in the real world have clock drift (often 5 to 60 seconds of difference).
- If the driver writes `System.currentTimeMillis()` into Firestore as `11:00:00`, and the student phone clock is at `11:00:30`:
  `now - ts = 30 seconds`.
- If the driver phone is 2 minutes behind:
  `now - ts = 120 seconds`.
  The student phone instantly marks the telemetry as expired (`HEARTBEAT_EXPIRATION_MS = 120s`), declaring the driver offline **the instant the packet arrives**.

#### Why `maxOf(rawAge, localReceiptAgeMs)` Failed:
Developers attempted to fix clock skew by introducing `localReceiptTimestampMillis` (the local phone time when the snapshot arrived). But combining them with `maxOf` meant:
$$\text{Age} = \max(\text{rawAge}, \text{localReceiptAge})$$
If `rawAge` was 120s due to clock skew, `maxOf(120s, 0s)` was **120s**, completely defeating the local receipt timestamp.

---

### Flaw 4: Concurrency, Disk Cache Dropping Live Firestore Snapshots

Firebase Firestore has offline disk persistence enabled by default. When a listener attaches:
1. It immediately emits a cached document from SQLite disk storage (`snapshot.metadata.isFromCache == true`).
2. Shortly after, the live network connection receives the latest server document (`snapshot.metadata.isFromCache == false`).

If naive monotonic timestamp checks compare timestamps across these transitions without inspecting `snapshot.metadata.isFromCache`, live updates get discarded if cached timestamps were ever written with local device timestamps.

---

### Flaw 5: The Fragile 7-Boolean Availability Gate Cascade

To display whether a cart is available, the codebase evaluated 7 intertwined, overlapping, and conflicting boolean expressions across multiple files:

1. `cartState.hasCoordinates`
2. `cartState.isInsideCampus`
3. `cartState.isOutsideCampus` (defined as `!isInsideCampus`)
4. `cartState.isDriverOnline`
5. `cartState.isLive`
6. `cartState.isLocationAvailable`
7. `cartState.isLocationExpiredOrMissing`

#### The Fragility:
In `StudentDashboardScreen.kt`:
```kotlin
val isC1Available = cart1State.isInsideCampus && !cart1State.isOutsideCampus &&
    (cart1State.isLive || cart1State.isDriverOnline ||
        (cart1State.isAvailable && !cart1State.driverStatus.equals("Offline") &&
         !cart1State.driverStatus.equals("Lunch Break") &&
         !cart1State.driverStatus.equals("Outside Campus") &&
         !cart1State.driverStatus.equals("Driver Not Available")))
```
If **any single sub-property** failed due to a missing timestamp, slight clock skew, or an intermediate status string, the entire cart was hidden or reported unavailable.

---

### Flaw 6: Android OS Battery Optimizations & OEM Background Service Killing

- On aggressive Android OEM skins (Xiaomi MIUI/HyperOS, Oppo ColorOS, Vivo Funtouch, Samsung OneUI):
  If an app does not hold an explicit battery exemption (`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`), the OS terminates background location broadcasting within 60 to 180 seconds after the screen turns off.
- The app requires:
  1. `android.permission.FOREGROUND_SERVICE_LOCATION`
  2. `android.permission.ACCESS_BACKGROUND_LOCATION`
  3. `PowerManager.PARTIAL_WAKE_LOCK` held by `DriverLocationService`
  4. Ongoing persistent notification with high importance.

---

## 10. Clean-Slate Blueprint: How to Re-Architect & Solve From Scratch

If you (or ChatGPT) are re-architecting this system from scratch, adhere to these **6 golden architectural principles**:

### Principle 1: Single Source of Truth with Unconditional Map Rendering
**Rule:** If a cart has valid latitude and longitude within campus bounds, the map **MUST ALWAYS draw the cart marker**. Never gate map coordinates behind duty status, driver availability, or expiration flags. The physical position of the vehicle is an objective reality independent of whether the driver is taking a break or available for rides.

### Principle 2: Eliminate Cross-Device Clock Skew via Server Timestamps
**Rule:** Never calculate data age using client-side `System.currentTimeMillis()` across different devices.
1. When the driver writes to Firestore, write:
   `"serverTimestamp": FieldValue.serverTimestamp()`
2. On student/faculty phones, evaluate freshness based on the interval between incoming snapshot events on the local device, or calculate skew by comparing Firestore server time against local device time.

### Principle 3: Simplify Presence into 3 Clear, Mutually Exclusive States
Replace the 7 fragile boolean flags with one clean state enum:

```kotlin
enum class FleetPresence {
    LIVE,    // Fresh coordinates received within 60s; cart moving or halted
    STALE,   // Stationary / delayed; coordinates 60s - 120s old
    OFFLINE  // No heartbeat for > 120s, or driver logged off
}
```

### Principle 4: Decouple Physical Geofencing from Driver Duty Status Strings
- `isInsideCampus` must be determined **strictly and exclusively** by `GeofenceManager.containsLocation(lat, lng)`.
- Never let a string like `driverStatus = "Driver Not Available"` override geographic coordinates. If `lat` and `lng` are at the Computer Centre, `isOutsideCampus` is `false`. Period.

### Principle 5: Streamlined Route Progress Pipeline
In `LiveRouteTrackingCard.kt`:
Pass `latitude` and `longitude` to `CampusRouteGraph` whenever coordinates exist and the vehicle is not offline. Do not suppress route calculation because of secondary status strings.

### Principle 6: Resilient Driver Foreground Service
- Keep `DriverLocationService` completely independent of Compose UI lifecycles.
- When started, it must run until explicitly stopped by the driver tapping "Off Duty".
- Hold a `PARTIAL_WAKE_LOCK` and broadcast coordinates every 2 to 4 seconds while moving, and every 30 seconds while stationary.

---

*This concludes the master architectural specification for the Campus Ride mobility platform.*
