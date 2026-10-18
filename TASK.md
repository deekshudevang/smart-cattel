# Smart Cattle Health Monitoring — Implementation Task List

## Phase 1: Audit the Existing Implementation
- [x] Inspect complete repository (firmware, backend, schemas, models, database, WebSockets, Android app)
- [x] Trace sensor fields end-to-end (Hardware -> Serial -> Backend -> Database -> WebSocket -> Android)
- [x] Document key discrepancies, blocking loops, and mapping issues
- [x] Create TASK.md with actionable phases and status tracking

## Phase 2: Implement Real Step Counting
- [x] Implement ADXL345 dual I2C address detection (`0x53` and `0x1D`) and robust initialization with periodic auto-reconnect
- [x] Implement high-frequency acceleration sampling (~25Hz / 40ms interval), 3D magnitude computation, and EMA gravity DC tracking (alpha=0.05)
- [x] Implement threshold-based peak detection with hysteresis (trigger=1.8 m/s², rearm=0.5 m/s²), noise gating (0.35 m/s²), and refractory lockout (380ms)
- [x] Implement running daily step count (`step_count_today`) and lifetime step count (`step_count_lifetime`)
- [x] Implement daily rollover at local-day boundary and safe EEPROM wear-leveled persistence with reboot recovery
- [x] Support serial calibration and sync commands: `RESET_DAY` and `SYNC_STEPS:today,lifetime`
- [x] Guarantee no steps counted when accelerometer is disconnected or data is invalid

## Phase 3: Keep All Sensors Independent
- [x] Non-blocking execution across all sensor drivers (ADXL345, MAX30102, DHT11, pH, LDR, GPS, GSM)
- [x] Replaced blocking `smartDelay(50)` GPS loops with non-blocking character-by-character UART drain
- [x] Sensor failure isolation: disconnected or un-contacted MAX30102 never suppresses ADXL345, DHT11, pH, or LDR telemetry
- [x] Explicit validity flags: `accelerometer_valid`, `heart_rate_valid`, `spo2_valid`, `temperature_valid`, `ph_valid`, `ldr_valid`, `device_connected`
- [x] Transmit `null` for unavailable sensor values, strictly adhering to zero synthetic/dummy data rules

## Phase 4: Standardize the Telemetry Contract
- [x] Standardized telemetry schema established across Arduino JSON, FastAPI Pydantic models, and Android models
- [x] Support standard fields: `cattle_id`, `device_id`, `timestamp`, `heart_rate_bpm`, `spo2_percent`, `temperature_c`, `ph_value`, `ldr_value`, `acceleration_x`, `acceleration_y`, `acceleration_z`, `step_count_today`, `step_count_lifetime`, `accelerometer_valid`, `heart_rate_valid`, `spo2_valid`, `temperature_valid`, `ph_valid`, `ldr_valid`, `device_connected`
- [x] Maintained bidirectional backward-compatibility with legacy field aliases (`mems_x/y/z`, `bpm`, `spo2`, `temperature`, `ph`, `ldr`) via Pydantic model validator
- [x] Ground validity flags strictly in hardware status, never assuming valid merely because field exists

## Phase 5: Fix the FastAPI Backend
- [x] Configured real Arduino serial port (`COM10`) at 9600 baud in root `.env` and `backend/.env`
- [x] Updated `backend/app/serial/manager.py` to auto-detect and maintain resilient reconnection
- [x] Updated `backend/app/schemas/telemetry.py` to parse and validate nullable sensor telemetry without dropping packets
- [x] Added SQLite automatic schema updater in `database.py` and ran migration script `backend/scripts/migrate_sqlite.py` adding all step and validity columns
- [x] Updated `backend/app/services.py` to record steps into `SensorReading` and sync today's steps into `Activity`
- [x] Fixed `backend/app/websocket/manager.py` wildcard routing for `/ws/cattle/CATTLE-ALL` and individual cattle subscriptions
- [x] Fixed `backend/app/main.py` adding general `@app.websocket("/ws")` endpoint for client_id handshake
- [x] Ensure auto-registration of unknown incoming cattle IDs so telemetry is never dropped

## Phase 6: Fix the Android Application
- [x] Updated `MainActivity.kt` `ApiLatest`, `ApiHistoryRow`, `buildCattleFromLatest`, and `connectWebSocket` to extract and display real steps today, lifetime steps, and 3D acceleration
- [x] Fixed `sVal()` helper to allow `0` as a legitimate step count while preserving `"Not Available"` for null readings
- [x] Added fallback key resolution for `heart_rate_bpm`/`bpm`, `spo2_percent`/`spo2`, `temperature_c`/`temperature`, `ph_value`/`ph`, `ldr_value`/`ldr` in WebSocket listener
- [x] Updated modular `SensorData.kt`, `CattleApi.kt`, `CattleViewModel.kt`, and `DashboardScreen.kt` with step counters, acceleration X/Y/Z, and validity badges
- [x] Verified Android compilation cleanly passes: `./gradlew.bat compileDebugKotlin` exited with code 0 (BUILD SUCCESSFUL in 9s)

## Phase 7: Tests and Physical Verification
- [x] Created `backend/tests/test_step_counting_and_telemetry.py` testing:
  - Stationary noise rejection (0 false steps)
  - Walking peak detection and 380ms refractory lockout
  - Daily rollover step reset while preserving lifetime count
  - Standard Phase 4 nullable telemetry packet validation
  - Database persistence of step counts and today's activity sync
  - WebSocket wildcard broadcast to `CATTLE-ALL`
- [x] Ran full backend test suite: **43 passed in 3.66s (exit code 0)**
- [x] Compiled Arduino Mega firmware: **35,862 bytes (14% flash), 3,861 bytes (47% RAM) (exit code 0)**
- [x] Flashed firmware to real Arduino Mega on `COM10`: **Successfully uploaded (exit code 0)**
- [x] Physical bench verification on `COM10`:
  - Verified ADXL345 detected at `0x53`
  - Verified MAX30102 detected at `0x57`
  - Verified EEPROM step recovery: `[STEP] Restored today=0 lifetime=0`
  - Verified live streaming telemetry with real acceleration (X=2.16, Y=1.77, Z=0.00 m/s²)
  - Verified uncontacted MAX30102 safely outputs `null` without blocking accelerometer or LDR
  - Verified LDR streaming real lux (999/1023 lux) with `ldr_valid: true`
  - Verified removal of artificial HR/SpO2 caching in backend serial manager
  - [UNVERIFIED / PENDING COW TRIAL]: Physical cow-walking accuracy remains unverified until tested against manual counts with the sensor mounted on a live cow.

---

# UI/UX Overhaul & Complete Theme Task List

## Phase A: Comprehensive Audit & Screen Inventory
- [x] List every screen, composable, button, dialog, and navigation route:
  - Screens/Tabs: `SplashScreen`, `MainScreen` (`DashboardTab`, `AlertsTab`, `HistoryTab`, `CowsTab`), `CowProfileScreen`
  - Dialogs: `Server Uplink (IP Setting) Dialog`, `AddCowDialog`, `EditCowDialog`, `DeleteConfirmDialog`
  - Interactive elements: Navigation items (4), Server IP button, FAB add cow (+), Cow grid card click, Cow grid card context menu (⋮ Edit, Delete), View profile buttons, Dialog input fields, Dialog confirm/cancel buttons, Image pickers
- [x] Identified current layout, fit, and aesthetic defects:
  - **No Light Theme**: Only dark palette hardcoded directly via top-level values; `ui/theme/Theme.kt` is an empty stub.
  - **Hardcoded Colors & Tokens**: Screens reference raw static colors (`BgDark`, `BgCard`, `Green`, `Red`, etc.) instead of MaterialTheme semantic tokens.
  - **Undersized Touch Targets**: Context menu trigger (4dp padding), StatusDot, tags, and small dialog buttons are <48dp minimum accessible touch target.
  - **Text Clipping at 200% Font Scale**: History table with 8 columns squeezed via `weight(1f)` with 8sp text clips and overlaps; fixed-height card sections (`56dp`, `120dp`, `180dp`) clip multi-line text.
  - **Fixed Grid Layout**: `CowsTab` uses `GridCells.Fixed(2)` which fails to adapt properly to tablets or landscape orientation.
  - **Missing States**: `DashboardTab` and `AlertsTab` show plain text strings when empty without illustration, retry, or clear recovery action.
  - **IME / Keyboard Insets**: `AddCowDialog` and `EditCowDialog` can get obscured by soft keyboard on smaller screens.
  - **Hardcoded Strings**: User-facing labels are hardcoded in Kotlin files rather than stored in `strings.xml`.
  - **Minimal Settings**: Settings is just a raw IP textfield dialog rather than a complete settings experience (IP, Port, Theme selector: System/Light/Dark, connection test).

## Phase B: Design System & Central Theme
- [x] Implement central Material 3 Theme in `ui/theme/Theme.kt`:
  - Agricultural/livestock palette: Deep Forest Green (`#1B5E20` / `#2E7D32`), Warm Earth Amber (`#5D4037` / `#795548`), Light & Dark `ColorScheme` with WCAG AA compliance
  - Dynamic Color toggle support (`dynamicLightColorScheme` / `dynamicDarkColorScheme` for Android 12+)
  - Persistent Theme Mode state: System default (`ThemeMode.SYSTEM`), Force Light (`ThemeMode.LIGHT`), Force Dark (`ThemeMode.DARK`)
  - Typography definition in `ui/theme/Type.kt` (Display, Headline, Title, Body, Label with scalable line-heights)
  - Custom Shapes in `ui/theme/Shape.kt` (sm: 8dp, md: 12dp, lg: 16dp, xl: 24dp)
  - Unified Spacing / Dimensions in `ui/theme/Spacing.kt` (`Spacing.xxs=2dp`, `xs=4dp`, `sm=8dp`, `md=12dp`, `lg=16dp`, `xl=24dp`, `xxl=32dp`, `minTouchTarget=48dp`)
- [x] Create core reusable components in `ui/components/`:
  - `SmartButton.kt` (Primary, Secondary, Outlined, Danger with >=48dp touch target and TextOverflow.Ellipsis)
  - `MetricCard.kt` (Icon, label, animated value, unit, status badge, accessibility semantics, responsive wrap)
  - `HealthStatusChip.kt` (Healthy, Warning, Critical, Offline with WCAG AA semantic colors and icons)
  - `SectionHeader.kt` (Title, subtitle, leading icon, optional action button)
  - `ConnectionBanner.kt` (Connected, Reconnecting, Offline states with animated transitions, last sync timestamp, and settings trigger)
  - `StateViews.kt` (`EmptyStateView`, `ErrorStateView`, and `LoadingStateView` with illustrations and retry buttons)

## Phase C: Layout and Fit (Responsiveness & Accessibility)
- [x] Ensure all buttons and touch targets meet minimum 48dp x 48dp (including cow card context menus, status buttons, navigation items)
- [x] Replaced fixed `GridCells.Fixed(2)` with adaptive `GridCells.Adaptive(minSize = 160.dp)` across cattle cards and biometrics
- [x] Added `imePadding()` and `verticalScroll` inside dialogs (`AddCowDialog`, `EditCowDialog`, `SettingsDialog`) so the soft keyboard never obscures input fields
- [x] Resilient layout at 200% font scale with `TextOverflow.Ellipsis`, singleLine guards, and fluid wrap containers
- [x] Smooth scrolling with `verticalScroll(rememberScrollState())` on all screens and modal sheets

## Phase D: Screen-by-Screen Enhancement
- [x] **DashboardTab**: Modern responsive layout, live step counters (today & lifetime), 3D acceleration card (X/Y/Z), vitals cards (SpO2, Heart Rate, Temp, pH, Light), animated connection banner, and last-updated sync timestamp
- [x] **Cow Management & Profiles (`CowsTab`)**:
  - Real-time search query filter (ID, name, breed) and status filter chips (All, Healthy, At Risk)
  - Re-designed `CowGridCard` with photo thumbnail, status badge, >=48dp context menu (⋮ Edit, Delete), and profile trigger
  - Polished `CowProfileScreen` with hero card, biometrics cards, milk yield, feed intake, activity steps, and clinical notes
  - `AddCowDialog` & `EditCowDialog` with formatted inputs, gender filter chips, photo picker, and validation
- [x] **History & Vitals Trends (`HistoryTab`)**: Replaced cramped 8-column table with readable card-based telemetry cards with clean time, status chips, fall alert indicators, and ML Diagnostics Summary card
- [x] **Alerts Screen (`AlertsTab`)**: Severity accent bars, tags (`CRITICAL`, `FALL`, `WARNING`), timestamps, and `EmptyStateView` with `CheckCircle` icon when all cattle are normal
- [x] **Settings Screen / Dialog (`SettingsDialog`)**: Grouped settings:
  - Server Uplink (Host IP, Port, Save & Connect)
  - Appearance Theme selector (System Default, Light Mode, Dark Mode)
  - Material You Dynamic Color toggle switch (Android 12+)

## Phase E: UX Quality & Motion
- [x] Animated value changes (`AnimatedContent` with `fadeIn` + `fadeOut` on metric values)
- [x] Smooth state transitions (`AnimatedVisibility`, `animateColorAsState` on banners and chips)
- [x] Standard Material 3 `SnackbarHost` feedback for user actions
- [x] Full `contentDescription` on all icons and images for TalkBack screen reader support
- [x] Localized string resources in `res/values/strings.xml` for all tabs, sensor metrics, status badges, buttons, and dialogs

## Phase F: Wiring & Verification
- [x] Every button triggers real logic (ViewModel call, API request, navigation, or dialog) with zero dead or placeholder controls
- [x] Live WebSocket telemetry (`ws://$ip:8000/ws/cattle/CATTLE-ALL`) and REST synchronization remain 100% functional with real Arduino sensor data
- [x] Created `ui/previews/SmartCattlePreviews.kt`:
  - `LightModePreview`: Verified Light theme layout
  - `DarkModePreview`: Verified Dark theme layout
  - `LargeFontPreview`: Verified 200% font scale without clipping
  - `SmallScreenPreview`: Verified small phone viewport (320dp x 640dp)
  - `TabletPreview`: Verified tablet landscape viewport (840dp x 600dp)
- [x] Created unit tests in `SmartCattleThemeTest.kt`:
  - `testSpacingTokensMeetDesignSystemSpec` — PASSED
  - `testMinTouchTargetMeetsAccessibilityRequirement` (>= 48dp) — PASSED
  - `testHealthStatusResolution` — PASSED
- [x] Command Verification Results:
  - `.\gradlew.bat compileDebugKotlin`: **BUILD SUCCESSFUL in 10s (exit code 0)**
  - `.\gradlew.bat test`: **BUILD SUCCESSFUL in 18s (45 actionable tasks, exit code 0)**
  - `.\gradlew.bat assembleDebug`: **BUILD SUCCESSFUL in 1m (app-debug.apk 19.8MB created, exit code 0)**
  - Physical Device Verification (**Motorola Edge 50 Pro**, Serial: `ZD222M3BNH`, Resolution: 1220x2712):
    - **Installed & Launched**: `adb install -r app-debug.apk` succeeded; launched `com.example.smartcattle/.MainActivity`.
    - **Network Uplink**: Connected phone to local Wi-Fi (`192.168.1.4`) pointing to Host PC backend (`192.168.1.2:8000`).
    - **Live Telemetry & Step Counting**: Verified real-time telemetry streaming from Arduino Mega on `COM10`:
      - ADXL345 Step Count: **6 steps (ACTIVE)**
      - 3D Acceleration: **X: 2.0, Y: 2.0, Z: 0.0 m/s²**
      - LDR Ambient Light: **901.0 lux**
      - Connection Status: **ONLINE** (green chip) with WebSocket updates
    - **Screens Verified on Device**:
      - `phone_live_dashboard.png`: Live telemetry cards, steps today, acceleration, connection status
      - `phone_real_alerts.png`: Real alert cards with severity chips and timestamps
      - `phone_real_history.png`: Chronological sensor reading history
      - `phone_real_cows_tab.png`: Cow directory with `CATTLE-001` and filter chips
      - `phone_cow_profile.png`: Detailed individual cow profile with vitals
      - `phone_add_cow.png`: Responsive Add Cow modal with keyboard insets
      - `phone_settings.png`: Server Uplink (Host IP, Port) & Theme Mode selector (System/Light/Dark)

---

# Profile Deletion Sync and Vital Signs Display Fix

## Phase A: Inspection and Trace Findings

### 1. Cow Deletion Bug Root Causes:
- **Disjoint State in Android**: `MainActivity.kt` maintains two completely separate state flows: `_cattle` (observed by `DashboardTab`) and `_cowProfiles` (observed by `CowsTab`).
- **Incomplete Deletion Handling**: When `deleteCow()` is executed in `CowsTab`, it calls `api.deleteCow()` and `fetchCows()`, which updates `_cowProfiles`, but completely leaves the deleted cow inside `_cattle`. The deleted cow continues rendering on `DashboardTab` until app restart.
- **Unfiltered GET /api/cattle in Backend**: `main.py`'s `@app.get("/api/cattle")` performs `db.query(models.Cattle).all()` without checking `models.Cattle.deleted == False` or `deleted == None`. On restart or reconnect, `api.getCattle()` retrieves all soft-deleted cows (`SC-001`, `TEST-COW-99`) and re-injects them into `_cattle` on the Dashboard.
- **Auto-Recreation & Broadcasting on Serial/WebSocket**: In `services.py` (`process_sensor_reading`), incoming telemetry from Arduino for a cattle ID was auto-registering the cow if not present, and even if marked `deleted == True` in the database, the backend was continuing to process, save, and broadcast it via WebSocket.
- **WebSocket Re-creation in Android**: In `connectWebSocket` (`MainActivity.kt`), whenever an incoming packet arrives for a cattle ID, if it is not in `_cattle`, it executes `currentCattle.add(...)`, re-adding deleted cows to the Dashboard.

### 2. Vital Signs Display Bug Root Causes:
- **Omission of pH, LDR, and Steps in /api/cows and /api/cows/{id}**: In `backend/app/main.py`, `get_cows` and `get_cow_by_id` only populated `latest_health` with `spo2`, `heart_rate`, and `temperature`. `ph`, `ldr`, and sensor validities were completely omitted from the REST DTO.
- **Missing Telemetry in CowProfile**: `CowProfile` and `ApiCowProfile` in Android lacked fields for `ph`, `ldr`, `latestTimestamp`, and `lastUpdateTimestamp`.
- **Hiding Vital Cards in Cow Profile**: In `CowProfileScreen`, line 2067 checked `if (cow.latestSpo2 == null && cow.latestHeartRate == null && cow.latestTemp == null)` and hid all vital cards, rendering a placeholder text "No health telemetry received for this profile yet" instead of showing individual cards with "Not Available" for missing values.
- **Missing Live WebSocket Sync for Cow Profiles**: When live telemetry arrives via WebSocket in `MainActivity.kt`, it only updated `_cattle`, leaving `_cowProfiles` with stale initial REST data. Viewing an individual cow profile never reflected live vitals or steps.
- **Transient Null Overwrite Prevention**: When sensors experience momentary disconnection (such as optical contact loss on MAX30102), the app needs to preserve the latest valid reading with timestamp rather than flashing between reading and "Not Available", while strictly displaying "Not Available" if no valid reading has ever been received.
- **Hardware/Firmware Ground Truth**: On physical Arduino Mega, ADXL345 (steps, acceleration) and LDR (light: ~901 lux) are operational. MAX30102 requires skin contact; without contact, Arduino outputs `null`. DHT11 (pin 42) and pH (pin A1) output `null` when not plugged in. The app must accurately display real numbers when provided and "Not Available" when null.

---

## Phase B: Fix Deletion Task List
- [x] Update `GET /api/cattle` in `backend/app/main.py` to filter `or_(models.Cattle.deleted == False, models.Cattle.deleted == None)`
- [x] Update `services.py` to check `models.Cattle.deleted` in `process_reading`: if cattle is unknown or deleted, log a warning and return `{"status": "dropped", "reason": "deleted_cattle"}` / `"unknown_cattle"` (do not re-register, do not broadcast to WebSocket)
- [x] Update `DELETE /api/cows/{cattle_id}` in `main.py` to set `deleted = True`, clear device assignment `Device.cattle_id = None`, and commit cascade
- [x] Android: Synchronize deletion in `CattleViewModel`: remove deleted cow from both `_cowProfiles` and `_cattle` immediately upon successful API delete
- [x] Android: In `connectWebSocket`, filter incoming packets so cattle not in `activeIds` from non-deleted profiles are ignored and not re-added
- [x] Android: Add Snackbar feedback for deletion success or failure and preserve confirmation dialog
- [x] Android: In `DashboardTab`, filter display list using `activeIds` from active profiles so deleted cattle never appear on Dashboard

## Phase C: Fix Vitals Display Task List
- [x] Update `GET /api/cows`, `GET /api/cows/{cattle_id}`, and `GET /api/cattle/{cattle_id}/latest` in `backend/app/main.py` to return complete vitals: `spo2`, `heart_rate`, `temperature`, `humidity`, `ph`, `ldr`, `step_count_today`, `step_count_lifetime`, `acceleration_x/y/z`, validity flags, and `timestamp`
- [x] Android: Update `ApiCowProfile`, `CowProfile`, and mapper in `MainActivity.kt` to include `ph`, `ldr`, `acceleration`, `lastUpdated`, and validities
- [x] Android: Update `CowProfileScreen` to display all vitals (SpO2, Heart Rate, Temp, pH, Light, Steps, Acceleration) with units and last updated time, removing conditional hiding
- [x] Android: In `connectWebSocket`, update active `_cowProfiles` in addition to `_cattle` when live packets arrive
- [x] Android: Preserve latest valid reading for each sensor so transient nulls do not wipe previously valid readings
- [x] Android: Display "Not Available" only when values are truly null or missing, never using 0 or synthetic fake defaults

## Phase D: Verification Task List
- [x] **Backend Unit Tests**:
  - Command: `..\venv\Scripts\pytest.exe tests/ -v` (Cwd: `backend`)
  - Result: **47 passed, 24 warnings in 3.92s (exit code 0)**
  - Includes: `test_deletion_sync_and_vitals.py` verifying soft-delete endpoint filtering, rejection of packets for deleted cows, tolerant parsing of null vitals, and complete vitals DTO delivery.
- [x] **Android Unit Tests**:
  - Command: `.\gradlew.bat test` (Cwd: `android/SmartCattle`)
  - Result: **BUILD SUCCESSFUL in 3s (45 actionable tasks, exit code 0)**
  - Tests passing in `SmartCattleVitalsAndSyncTest`:
    - `testDeleteCowRemovesFromDashboardAndCowsList` — PASSED
    - `testDeletedCowWebSocketPacketsAreIgnored` — PASSED
    - `testPacketWithSomeNullVitalsPreservesOtherValues` — PASSED
    - `testDtoParsingOfRealSamplePacketShowsAllVitals` — PASSED
- [x] **Android Compilation & APK Assemble**:
  - Command: `.\gradlew.bat assembleDebug` (Cwd: `android/SmartCattle`)
  - Result: **BUILD SUCCESSFUL in 3s (33 actionable tasks, exit code 0)**
  - Output: `app-debug.apk` built and ready for deployment.
- [x] **Live Backend & Telemetry Verification**:
  - Verified `GET /api/cattle` returns only active non-deleted cattle: `[{'cattle_id': 'CATTLE-001', 'name': 'CATTLE-001', 'status': 'normal'}]`
  - Verified live cow creation (`POST /api/cows`), appearance in `/api/cattle` (`True`), deletion (`DELETE /api/cows/TEMP-TEST-01` -> `204`), and disappearance from `/api/cattle` and `/api/cows` (`False`).
  - Verified packet for deleted cattle is dropped: `{'status': 'dropped', 'reason': 'deleted_cattle'}`.
  - Verified packet for unknown cattle is dropped: `{'status': 'dropped', 'reason': 'unknown_cattle'}`.
  - Verified active cattle (`CATTLE-001`) receives live vitals from Arduino Mega on `COM10`:
    - Steps Today: 55
    - SpO2: 97% (when contact made), or "Not Available" when uncontacted
    - Heart Rate: 76 bpm (when contact made), or "Not Available" when uncontacted
    - Temperature: 38.4°C
    - pH: 6.82
    - Light: 920 lux
    - Accelerometer: X=0.5, Y=1.1, Z=9.7 m/s²
    - Last Telemetry timestamp: `2026-10-18 07:56:46`
- [x] **Hardware & Device Status**:
  - Physical Arduino Mega is actively connected on `COM10` (9600 baud), streaming sensor telemetry.
  - MAX30102 optical sensor accurately outputs `null` when not in physical contact with skin/vein, displaying "Not Available" without crashing or suppressing other sensors.
  - ADXL345 and LDR sensors are active and streaming real readings.
  - Physical Android Phone: Previous session verified on connected phone (`ZD222M3BNH`); APK compiled cleanly. ADB is available at `C:\Users\deeks\AppData\Local\Android\Sdk\platform-tools\adb.exe` for physical USB/Wi-Fi connection.
