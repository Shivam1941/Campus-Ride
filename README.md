# Campus Ride — IIIT Bhagalpur Executive Transport & Smart Mobility

An autonomous smart mobility and live campus transit tracking platform designed specifically for the IIIT Bhagalpur campus community. The application bridges students, faculty, and electric golf cart drivers into a single, cohesive, battery-optimized, and real-time transit ecosystem.

---

## 🌟 Key Features

### 1. Interactive Campus Route Network (OpenStreetMap)
- **High-Definition Offline-First Vector Mapping**: Fully integrated with OpenStreetMap (OSM) rendering campus geometry without proprietary map bloat.
- **Complete Campus Landmark Coverage**:
  - Main Gate (Origin / Dispatch Zone)
  - Trunket & Computer Centre (CC Building)
  - Academic Block & Hostel
  - **New Branches**: Girls Hostel Branch & Faculty Residence Branch.
- **Dynamic Route Geometry**: High-precision geodesic polyline interpolation for smooth vehicle orientation and route visualization.

### 2. Live Dynamic Route Progress
- **Real-Time GPS Projection**: Snaps cart coordinates to nearest route segments using precalculated vector math.
- **Dynamic Arrival ETAs & Progress Percentages**: Continuous distance remaining, segment progress bars, and directional guidance.
- **Live Status Badges**: Real-time indication of vehicle status (`MOVING`, `HALTED`, `ARRIVING`, `OFFLINE`).

### 3. Battery & Performance Optimized
- **Adaptive 5-Mode GPS Tracker**: Dynamically adjusts sampling frequency and distance filters based on velocity and screen power states:
  - `STATIONARY`: 30s interval, 20m filter.
  - `LOW_SPEED`: 8s interval, 5m filter.
  - `NORMAL_MOVEMENT`: 4s interval, 5m filter.
  - `HIGH_SPEED`: 2.5s interval, 4m filter.
  - `BACKGROUND`: 15s interval, 15m filter.
- **Smart Map Invalidation**: Bypasses continuous canvas redraws when vehicles are parked, reducing idle GPU consumption from 60+ FPS to 0 FPS.
- **Calculation Memoization**: Distance delta gating (2.0m threshold) prevents repetitive trigonometric recomputation during minor GPS jitter.
- **Passenger Background Network Suspension**: Automatically suspends continuous Firestore listeners when passengers minimize the app or lock the screen.

### 4. Tri-Portal Role-Based Architecture
- **Student Portal**: One-tap pickup requests from designated campus pickup zones, live cart tracking, and arrival notifications.
- **Faculty Portal**: Secure passcode-authenticated dispatch portal with direct campus destination selection and priority routing.
- **Driver Terminal**: Full-screen dispatch management, turn-by-turn route telemetry, passenger queue coordination, and automated heartbeat broadcasts.

### 5. Signature Brand Experience
- **Tesseract Dynamics Sacred Geometry Emblem**: Rotating celestial orbital rings framing the central medallion.
- **Synthesized Brand Sonic Logo**: Pure procedural multi-harmonic sound signature generated on-device with zero audio asset overhead.
- **Modern Jetpack Compose UI**: Clean, responsive Material 3 design system with light and dark mode support.

---

## 🛠 Tech Stack

- **Platform**: Native Android (Kotlin)
- **UI Toolkit**: Jetpack Compose, Material 3
- **Architecture**: MVVM / Single-Source-of-Truth Repository pattern with Kotlin Coroutines & StateFlow
- **Location & Sensors**: Google Play Services Fused Location Provider, Android Geofencing API
- **Maps**: OpenStreetMap (OSMDroid) + Custom Hardware-Accelerated Compose Canvas Overlay
- **Cloud & Sync**: Firebase Firestore, Firebase Cloud Messaging (FCM)
- **Build System**: Gradle Kotlin DSL (`build.gradle.kts`) with version catalogs

---

## 🚀 Getting Started

### Prerequisites
- Android Studio Ladybug / Meerkat or later
- JDK 17 / JDK 21 (bundled JBR recommended)
- Android SDK 34+ (target SDK: 36, min SDK: 24)

### Building from Source

1. **Clone the repository**:
   ```bash
   git clone https://github.com/Shivam1941/Campus-Ride.git
   cd Campus-Ride
   ```

2. **Configure Local Environment**:
   Ensure `local.properties` specifies your Android SDK directory:
   ```properties
   sdk.dir=/path/to/your/Android/Sdk
   ```

3. **Run Unit Tests**:
   ```bash
   ./gradlew testDebugUnitTest
   ```

4. **Build Debug APK**:
   ```bash
   ./gradlew assembleDebug
   ```
   The resulting APK will be located at:
   `app/build/outputs/apk/debug/app-debug.apk`

---

## 📄 License & Attribution

Designed & Developed for **IIIT Bhagalpur Smart Mobility** in partnership with **Tesseract Dynamics**.
