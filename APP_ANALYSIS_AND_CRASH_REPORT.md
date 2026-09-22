# Campus Ride — System Analysis & Crash Root Cause Report

**Target Project:** Campus Ride (IIIT Bhagalpur Mobility System)  
**Author / Auditor:** Pair Programming Assistant (Google DeepMind)  
**Date of Audit:** September 21, 2026  
**Repository Path:** `c:\Workspace\Campus Ride\campus-ride`  

---

## Table of Contents
1. [Executive Summary](#1-executive-summary)
2. [Application Architecture & Tech Stack](#2-application-architecture--tech-stack)
3. [User Roles & Functional Workflows](#3-user-roles--functional-workflows)
4. [Recent Commits Under Investigation](#4-recent-commits-under-investigation)
5. [Forensic Root Cause Analysis of Continuous Crashes](#5-forensic-root-cause-analysis-of-continuous-crashes)
   - [Bug 1: Fatal Android OS Foreground Service Crash](#bug-1-fatal-android-os-foreground-service-crash)
   - [Bug 2: Fatal Compose UI Crash in Google Maps Markers](#bug-2-fatal-compose-ui-crash-in-google-maps-markers)
   - [Bug 3: Fatal Runtime ClassCastException on Firestore Snapshots](#bug-3-fatal-runtime-classcastexception-on-firestore-snapshots)
   - [Bug 4: Handler Crash on Terminated HandlerThread](#bug-4-handler-crash-on-terminated-handlerthread)
   - [Bug 5: Missing Build Tooling & Configuration Deficiencies](#bug-5-missing-build-tooling--configuration-deficiencies)
6. [Cloud Backend Status & Verification](#6-cloud-backend-status--verification)
7. [Step-by-Step Remediation Plan & Next Steps](#7-step-by-step-remediation-plan--next-steps)

---

## 1. Executive Summary

**Campus Ride** is an Android application built for **IIIT Bhagalpur** to provide real-time tracking, on-demand dispatching, and geofence monitoring for electric golf carts servicing students, faculty, and campus staff.

The system manages two permanent carts:
- **Cart 1**: Driven by Shivam (Dedicated In-Cart Mobile: `+91-9876543210`)
- **Cart 2**: Driven by Kartik (Dedicated In-Cart Mobile: `+91-9876543211`)

The active fixed route runs across key landmarks:
$$\text{Main Gate} \longleftrightarrow \text{Trunket} \longleftrightarrow \text{Computer Centre} \longleftrightarrow \text{Hostel (Boys Hostel)}$$

Recent code pushes introduced severe regressions in foreground services, Google Maps composition, and Firestore deserialization, rendering the app unusable due to continuous crash loops. This document delivers a full architectural overview, detailed diagnostics of all crashing bugs, and the precise roadmap to restore stability.

---

## 2. Application Architecture & Tech Stack

```
                                +---------------------------+
                                |    Android Client (App)   |
                                |  Jetpack Compose + Kotlin |
                                +-------------+-------------+
                                              |
                     +------------------------+------------------------+
                     |                                                 |
                     v                                                 v
        +--------------------------+                      +--------------------------+
        |     Location Engine      |                      |   CampusRideRepository   |
        | - DriverLocationService  |                      | - StateFlow & Coroutines |
        | - DriverLocationTracker  |                      | - GeofenceManager        |
        | - Fused / Legacy GPS     |                      | - DataStore / Prefs      |
        +------------+-------------+                      +------------+-------------+
                     |                                                 |
                     +------------------------+------------------------+
                                              |
                   +--------------------------+--------------------------+
                   |                                                     |
                   v                                                     v
    +------------------------------+                      +------------------------------+
    |       Google Firebase        |                      |       Node.js Backend        |
    | - Cloud Firestore (Realtime) |                      | - Render.com Express API     |
    | - Firebase Cloud Messaging   |                      | - Firebase Admin SDK         |
    | - Anonymous Authentication   |                      | - Push Dispatcher            |
    +------------------------------+                      +------------------------------+
```

### Core Technologies
- **UI Framework**: Jetpack Compose with Material 3 design system, Navigation Compose, Edge-to-Edge display, and custom canvas-based brand animations.
- **State Management**: Kotlin Coroutines (`Dispatchers.IO`, `Dispatchers.Main`), `StateFlow`, `SharedFlow`, and Mutex-guarded operations.
- **Maps & Geospatial**:
  - Primary: Google Maps Compose (`com.google.maps.android:maps-compose:6.5.0`) with custom vector pill markers and dynamic directional headings.
  - Secondary (Resilient Fallback): `CampusOpenStreetMapView`, an in-house custom canvas tile engine with LruCache and offline disk storage.
- **Location Subsystem**: Google Play Services `FusedLocationProviderClient` with seamless failover to Android `LocationManager` (GPS / Network providers).
- **Cloud Infrastructure**:
  - Google Cloud Firestore for sub-second telemetry synchronization between drivers and students.
  - Node.js Express REST API deployed on Render (`https://campus-ride-backend-df0n.onrender.com/`).
  - High-priority data-only FCM push alerts waking the device with custom full-screen overlays.

---

## 3. User Roles & Functional Workflows

| User Role | Core Capabilities | Primary Screens |
| :--- | :--- | :--- |
| **Student** | • View real-time GPS locations & animated headings of Carts 1 & 2.<br/>• Inspect driver availability, speed, distance to gate, and current stop.<br/>• Request rides with student count at Main Gate.<br/>• One-tap phone dialer to call in-cart driver phones. | `StudentDashboardScreen.kt`<br/>`CampusGoogleMapView.kt`<br/>`LiveRouteTrackingCard.kt` |
| **Driver** | • Automatic foreground location tracking with battery-optimized polling.<br/>• Automatic duty transition via campus boundary geofence (Inside = On Duty / Outside = Offline).<br/>• Single daily lunch break management (with midnight rollover ticker).<br/>• Full-screen waking incoming ride alarm with custom sonic chime and accept/decline action buttons. | `DriverDashboardScreen.kt`<br/>`DriverLocationService.kt`<br/>`IncomingDriverAlertOverlay.kt`<br/>`DriverNotificationSetupScreen.kt` |
| **Faculty** | • Priority cart dispatching.<br/>• Campus directory access and emergency assistance hotline. | `FacultyDashboardScreen.kt` |

---

## 4. Recent Commits Under Investigation

A forensic git review reveals the exact commits that introduced the regressions:

1. **Commit `abbba4491c`** — *`perf: optimize battery and network usage`*
   - Changed `GolfCartState.HEARTBEAT_INTERVAL_MS` from `8_000L` to `25_000L`.
   - Modified `DriverLocationService.kt`: Added early-return logic inside `onStartCommand()`.
   - Modified `DriverLocationTracker.kt`: Added `MovementState.MOVING` vs `STATIONARY` switching via `reconfigureFusedUpdates()`.
   - Modified `CampusRideRepository.kt`: Throttled remote network writes and cancelled the 4-second polling loop.
   - Modified `FcmRoleNotificationManager.kt`: Added Google Play Store package checks.

2. **Commit `9036c7959e`** — *`refactor: enhance ride requests and driver logic`*
   - Modified `CampusGoogleMapView.kt`: Added static stop markers and animated cart marker initialization using `BitmapDescriptorFactory`.
   - Disabled automatic FCM initialization in `AndroidManifest.xml` and `CampusApplication.kt`.
   - Added student metadata and cart assignment locking.

3. **Commit `adaeef643a`** — *`feat: implement maps integration and API security`*
   - Initial introduction of Google Maps SDK and `CampusOpenStreetMapView`.

---

## 5. Forensic Root Cause Analysis of Continuous Crashes

---

### Bug 1: Fatal Android OS Foreground Service Crash
* **Location:** `app/src/main/java/com/example/location/DriverLocationService.kt:80-92`
* **Exception:** `android.app.RemoteServiceException$ForegroundServiceDidNotStartInTimeException`
* **Trigger Condition:** Any time the Driver screen opens or recomposes while location tracking is enabled.

#### Detailed Mechanism:
In Android 8.0+ (API 26) through Android 14+ (API 34/Target SDK 36), calling `context.startForegroundService(intent)` establishes an OS-level contract: the target service must call `Service.startForeground(id, notification)` within 5 seconds.

In commit `abbba44`, the following optimization was added:
```kotlin
// DriverLocationService.kt: Lines 81-90
ACTION_START_TRIP -> {
    if (isManualOffDuty) {
        Log.d(TAG, "ACTION_START_TRIP called while driver is manually set to OFF DUTY; stopping service")
        stopTrackingAndSelf()
        return START_NOT_STICKY // FATAL: Never called startForeground()
    }

    // If already tracking active cart, avoid recreating notification or re-allocating tracker
    if (isTrackingActive && !cartChanged) {
        Log.d(TAG, "DriverLocationService is already active for cart $activeCartId; preserving continuous tracking")
        return START_STICKY // FATAL: Never called startForeground()
    }
    ...
}
```

Because `DriverDashboardScreen.kt` runs a `LaunchedEffect` that triggers `DriverLocationService.startTrip(context, selectedCartId)`:
1. `context.startForegroundService(intent)` is executed.
2. `onStartCommand` receives the intent.
3. Because `isTrackingActive` is already `true`, it hits `return START_STICKY` **without calling `startForeground(...)`**.
4. The OS-level 5-second countdown expires.
5. The Android system immediately kills the entire app process with a fatal `ForegroundServiceDidNotStartInTimeException`.

---

### Bug 2: Fatal Compose UI Crash in Google Maps Markers
* **Location:** `app/src/main/java/com/example/ui/components/CampusGoogleMapView.kt:286-301, 912-996`
* **Exception:** `java.lang.NullPointerException` or `java.lang.IllegalStateException: Not initialized` in `com.google.android.gms.maps.model.BitmapDescriptorFactory`
* **Trigger Condition:** Opening the Student Dashboard or Driver Dashboard where `CampusGoogleMapView` is embedded.

#### Detailed Mechanism:
In `CampusGoogleMapView.kt`, the composable `GoogleMapsInternalView` runs marker initialization directly in Compose `remember` blocks:
```kotlin
val gateMarkerIcon = remember { createStopMarkerBitmap(context, "Gate", 0xFF15803D.toInt()) }
val trunketMarkerIcon = remember { createStopMarkerBitmap(context, "Trunket", 0xFF2563EB.toInt()) }
val ccMarkerIcon = remember { createStopMarkerBitmap(context, "CC", 0xFF7C3AED.toInt()) }
val acadMarkerIcon = remember { createStopMarkerBitmap(context, "Acad", 0xFF0D9488.toInt()) }
val hostelMarkerIcon = remember { createStopMarkerBitmap(context, "Hostel", 0xFFD97706.toInt()) }
val studentMarkerIcon = remember { createStudentMarkerBitmap(context) }
val cart1MarkerIcon = remember(...) { createCartMarkerBitmap(...) }
val cart2MarkerIcon = remember(...) { createCartMarkerBitmap(...) }
```

All of these helper functions invoke:
```kotlin
return BitmapDescriptorFactory.fromBitmap(bitmap)
```

In the Google Maps SDK, `BitmapDescriptorFactory` relies on an internal IPC stub (`IBitmapDescriptorFactory`) that is only initialized after `MapsInitializer.initialize(context)` has completed or the underlying `MapView` has loaded. Because `MapsInitializer` is **never initialized anywhere in `CampusApplication.kt` or `MainActivity.kt`**, calling `BitmapDescriptorFactory.fromBitmap()` during the initial composition pass throws a fatal `NullPointerException`, crashing the Compose render pipeline immediately.

Furthermore, `initialEngine` defaults unconditionally to `MapEngine.GOOGLE_MAPS` without verifying if Google Play Services are available or if `MAPS_API_KEY` is present.

---

### Bug 3: Fatal Runtime ClassCastException on Firestore Snapshots
* **Location:** `app/src/main/java/com/example/data/repository/CampusRideRepository.kt:1881-1882`
* **Exception:** `java.lang.ClassCastException: java.lang.Long cannot be cast to java.lang.Double` (or vice versa)
* **Trigger Condition:** Receiving any real-time snapshot update from the `drivers/cart_1` or `drivers/cart_2` Firestore collections.

#### Detailed Mechanism:
In `CampusRideRepository.kt`:
```kotlin
val bearing = snapshot.getDouble("bearing")?.toFloat() ?: 0f
val speedKmH = snapshot.getLong("speedKmH")?.toInt() ?: 0
```

In the Google Cloud Firestore Android SDK, `snapshot.getDouble("bearing")` does not parse numeric types dynamically; it performs an unchecked cast: `(Double) get("bearing")`.
- When `server.js` or backend scripts write integers (e.g. `bearing: 0` or `bearing: 90`), Firestore stores them as `Long`.
- Attempting to deserialize a `Long` with `getDouble()` throws `java.lang.ClassCastException: java.lang.Long cannot be cast to java.lang.Double`.
- Similarly, if `speedKmH` is stored as a floating-point number, `getLong("speedKmH")` throws `ClassCastException: java.lang.Double cannot be cast to java.lang.Long`.
- Because this occurs inside the real-time snapshot listener on the main thread, the app crashes spontaneously in the background whenever a cart moves or updates status.

---

### Bug 4: Handler Crash on Terminated HandlerThread
* **Location:** `app/src/main/java/com/example/location/DriverLocationTracker.kt:226-244`
* **Exception:** `java.lang.IllegalStateException: Handler sending message to a Handler on a dead thread`
* **Trigger Condition:** Stopping or restarting tracking when adaptive movement transitions trigger.

#### Detailed Mechanism:
In `DriverLocationTracker.kt`, `startTracking()` launches a dedicated `HandlerThread("DriverLocationTrackerThread")`.
When `stopTracking()` is called, `backgroundThread.quitSafely()` is invoked. If an in-flight location update or listener reconfigures fused updates using the cached `backgroundLooper`, Android throws an `IllegalStateException` because messages are posted to a dead looper.

---

### Bug 5: Missing Build Tooling & Configuration Deficiencies
1. **Missing Gradle Wrapper**:
   Neither `gradlew`, `gradlew.bat`, nor `gradle/wrapper/gradle-wrapper.jar` exist in the repository. New clones fail to build from CLI unless the wrapper is generated.
2. **Missing `google-services.json`**:
   `app/google-services.json` is missing. Build succeeds only because of `missingGoogleServicesStrategy = MissingGoogleServicesStrategy.WARN`, but runtime Firebase initialization requires anonymous sign-in workarounds.
3. **Empty Maps API Key Placeholder**:
   `manifestPlaceholders["MAPS_API_KEY"]` defaults to an empty string `""` when `.env` is absent, preventing Google Maps tiles from authenticating.

---

## 6. Cloud Backend Status & Verification

A live diagnostic was performed against the production cloud backend:
- **Endpoint**: `https://campus-ride-backend-df0n.onrender.com/health`
- **HTTP Status**: `200 OK`
- **Response Payload**:
  ```json
  {
    "status": "UP",
    "database": "IN_MEMORY_MAPS",
    "firebaseAdminActive": true,
    "credentialMethod": "Environment Variable (FIREBASE_SERVICE_ACCOUNT)",
    "activeRides": 0,
    "registeredCarts": 2,
    "fcmTokensStored": 0,
    "uptimeSeconds": 7
  }
  ```
**Conclusion:** The cloud server and Firebase Admin bridge are fully functional, responsive, and ready to serve requests. The crashes are localized exclusively to the Android client.

---

## 7. Step-by-Step Remediation Plan & Next Steps

Follow this ordered plan to fix all continuous crashes and restore a stable build.

### Phase 1: Code Hotfixes (Immediate Crash Prevention)

#### 1. Fix `DriverLocationService.kt`
Always satisfy the foreground service contract regardless of internal state:
```kotlin
// In onStartCommand():
val started = startForegroundWithNotification(
    activeCartId,
    "🚐 Campus Ride: $cartLabel",
    "Monitoring IIIT Bhagalpur campus geofence..."
)

if (isManualOffDuty) {
    stopTrackingAndSelf()
    return START_NOT_STICKY
}

if (isTrackingActive && !cartChanged) {
    // Already tracking; notification was safely refreshed above
    return START_STICKY
}
```

#### 2. Fix `CampusGoogleMapView.kt` & `CampusApplication.kt`
- Add `MapsInitializer.initialize(context)` in `CampusApplication.onCreate()`.
- Add safety checks to automatically use `CampusOpenStreetMapView` when Google Play Services is missing or `MAPS_API_KEY` is not configured.
- Wrap `createStopMarkerBitmap()` and `createCartMarkerBitmap()` calls in try/catch or defer bitmap creation until `GoogleMap` has loaded.

#### 3. Fix `CampusRideRepository.kt` Document Deserialization
Convert rigid getters to type-safe numeric coercions:
```kotlin
val bearing = (snapshot.get("bearing") as? Number)?.toFloat() ?: 0f
val speedKmH = (snapshot.get("speedKmH") as? Number)?.toInt() ?: 0
val lat = (snapshot.get("latitude") as? Number)?.toDouble()
val lng = (snapshot.get("longitude") as? Number)?.toDouble()
```

#### 4. Fix `DriverLocationTracker.kt` HandlerThread Lifecycle
Check if `backgroundThread?.isAlive == true` before delegating to `backgroundLooper`. Fall back safely to `Looper.getMainLooper()` if the background thread has terminated.

---

### Phase 2: Build & Environment Restoration

1. **Generate Gradle Wrapper**:
   Run the following command to populate `gradlew`, `gradlew.bat`, and `gradle/wrapper/`:
   ```powershell
   gradle wrapper --gradle-version 8.11.1
   ```
2. **Environment Variables**:
   Create a local `.env` file from `.env.example` with valid `MAPS_API_KEY` credentials.

---

### Phase 3: Validation & Testing

1. Run unit tests:
   ```powershell
   ./gradlew testDebugUnitTest
   ```
2. Launch the app in an emulator or physical device.
3. Test the **Student Flow**: Open map, verify cart pins render without crash, toggle to OSM and back.
4. Test the **Driver Flow**: Select Driver role, verify foreground notification appears without `ForegroundServiceDidNotStartInTimeException`, verify GPS updates sync cleanly to Firestore.
