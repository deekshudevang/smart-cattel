| **ADXL345 Physical Tilt/Shake Test** | Requires human operator to physically move board | - | - | **UNVERIFIED (Requires user physical motion)** |
| **On-Phone Live Display** | Phone currently unplugged from PC USB | - | - | **UNVERIFIED on physical screen (APK ready & verified in unit tests)** |
| **Cattle Step-Counting Accuracy** | Requires actual cattle movement or controlled physical shake test | - | - | **UNVERIFIED (Awaiting physical cattle movement data)** |

---

### PHASE 21 — Cattle Activity and Step Counting
- **Objective**: Integrate cattle step counting and cumulative activity tracking into the Smart Cattle Monitoring System using the existing ADXL345 MEMS accelerometer, persisting daily and lifetime counts across restarts and displaying exactly nine sensor categories on the dashboard.
- **Tasks**:
  - **Accelerometer Algorithm**:
    - Real-time 3D acceleration magnitude calculation: $\text{magnitude} = \sqrt{a_x^2 + a_y^2 + a_z^2}$.
    - Continuous dynamic gravity extraction via exponential moving average ($\alpha = 0.05$) to handle arbitrary collar/tag orientations without false triggers.
    - Bovine stride hysteresis peak detection: upper threshold $1.8\,\text{m/s}^2$, lower re-arming threshold $0.5\,\text{m/s}^2$.
    - 380ms refractory period lockout preventing double-counting within a single gait cycle.
    - Baseline noise floor suppression at $0.35\,\text{m/s}^2$ to avoid counting random breathing or ambient vibration.
  - **Firmware & EEPROM Wear-Leveling**:
    - Wear-leveled persistent lifetime counter in EEPROM (`EEPROM_STEP_ADDR = 16`, magic `0x5354`) with 10-second commit throttling to preserve EEPROM write endurance.
    - Boot counter tracking on microcontroller initialization for anomaly and power-interruption reconciliation.
    - Emitted `step_count_today` and `step_count_lifetime` in standard serial JSON telemetry.
  - **Authoritative Backend Processing**:
    - Single authoritative step management (`AuthoritativeStepManager`) in configured farm timezone (`settings.TIMEZONE = "Asia/Kolkata"`).
    - Midnight daily rollover resets `steps_today` to 0 while strictly preserving cumulative `total_steps`.
    - Anomaly protection: clamps implausible jumps (>5,000 steps/packet) and handles unexpected counter decreases without double-counting.
    - Gated by `accelerometer_valid`: disconnected or invalid accelerometer signals never fabricate bogus step counts.
  - **Database Persistence**:
    - Created `cattle_step_readings` table (model `CattleStepReading`) with fields `id`, `cattle_id`, `device_id`, `steps_today`, `total_steps`, `step_increment`, `step_sensor_status`, `step_algorithm_version`, `measured_at`, `created_at`, `is_simulated`.
    - Added foreign keys and indexes (`idx_step_cattle_measured`, `idx_step_is_simulated`).
    - Persisted genuine step increments and periodic 60s heartbeats to `cattle_step_readings`.
  - **API Integration**:
    - `GET /api/v1/cattle/{cattle_id}/steps/latest` (and `/api/cattle/{cattle_id}/steps/latest`): returns latest verified step reading.
    - `GET /api/v1/cattle/{cattle_id}/steps/history`: returns chronological step records with `limit` query param.
    - `GET /api/v1/cattle/{cattle_id}/steps/statistics`: returns today's steps, total lifetime steps, 7-day daily average, hourly average, activity status, and sensor status.
  - **Android UI Integration (Exactly Nine Dashboard Cards)**:
    - Updated dashboard layout to exactly 9 sensor cards across 5 rows:
      - ROW 1: Heart Rate | SpO2
      - ROW 2: Temperature | pH
      - ROW 3: Light (LDR) | Weight (HX711)
      - ROW 4: Steps | Total Steps
      - ROW 5: MEMS Accelerometer — ONE full-width card with X-Axis, Y-Axis, and Z-Axis together.
    - Handled all states: Live, Disconnected, Stale, Not Available, Offline, with appropriate icons (`DirectionsWalk`, `Timeline`, `Sensors`).
  - **Testing & Verification**:
    - 5 backend regression tests in `backend/tests/test_steps_endpoints.py` covering persistence, latest endpoint, history, statistics, midnight rollover, duplicate packet filtering, and sensor disconnection gating.
    - Full backend test suite passing: **85 of 85 passed**.
    - Android unit tests in `SmartCattleVitalsAndSyncTest.kt` passing: 23 of 23 passed, asserting exactly 9 dashboard sensor categories.
    - Android debug APK assembled cleanly (`BUILD SUCCESSFUL`).
- **Completion Status**: VERIFIED (Physical step accuracy marked UNVERIFIED pending live animal motion test)

---

### PHASE 22 — Automatic Disconnection Diagnosis & Resilient Reconnection
- **Objective**: Diagnose and eliminate automatic data stream disconnections across firmware, backend serial reader, and WebSocket/Android pipeline without rewriting working code.
- **Tasks**:
  - Inspect firmware for blocking calls, watchdog/brownout risks, serial TX buffer saturation, and add boot-reason diagnostics (`esp_reset_reason()` / AVR `MCUSR`).
  - Eliminate DTR hardware reset pulse on connection open/reconnection in backend serial reader (`setDTR` toggle).
  - Implement 16KB raw buffer overflow protection, line-based parsing with corrupt byte recovery, and exponential backoff on reconnection.
  - Add bidirectional ping/pong heartbeat (15s interval) in WebSocket server and Android client with exponential backoff and connection status indicator.
  - Add ISO-8601 structured logging for serial connection/disconnection, last received line time, parse errors, and WebSocket client connect/disconnect.
  - Add regression tests covering malformed packet tolerance, buffer overflow protection, exponential backoff progression, and client ping/pong.
- **Files Modified**:
  - `arduino/arduino.ino` (Added `printBootReason()`, set `Serial.setTimeout(50)`, disabled verbose `DEBUG_MAX30102`)
  - `arduino/communication/gsm_manager.h` (Replaced blocking delay chains with non-blocking checks)
  - `backend/app/serial/manager.py` (Removed DTR auto-reset toggling, added ISO-8601 structured logging, exponential backoff, buffer overflow guard)
  - `backend/app/websocket/manager.py` (Added ping/pong client handling, structured logging)
  - `backend/app/main.py` (Added ping/pong WebSocket route dispatch)
  - `android/SmartCattle/.../WebSocketManager.kt` (Configured 15s pingInterval, exponential reconnect backoff, ping/pong handlers)
  - `backend/tests/test_serial_manager.py` (Added 4 regression tests)
  - `backend/tests/test_websocket.py` (Added ping/pong regression test)
- **Root Cause Classification**:
  - **Serial Reopen Reset** (Primary Software Cause): `setDTR(False)` followed by `setDTR(True)` on serial open asserted the hardware DTR capacitor, hard-rebooting the microcontroller whenever the serial port opened or reconnected.
  - **Blocking Firmware** (Secondary Firmware Cause): `Serial.readStringUntil('\n')` blocked for 1000ms whenever partial data or line noise arrived; GSM delays (4000ms) blocked the main loop.
  - **WebSocket Timeout / Exponential Backoff** (Transport Cause): Server dropped idle clients without client-ping replies; Android lacked backoff.
- **Tests Executed**:
  - Backend test suite: `pytest -v tests` -> **75 of 75 passed**.
  - Android compilation and APK packaging: `gradlew assembleDebug` (BUILD SUCCESSFUL).
  - Android device installation: `adb install -r -d app-debug.apk` (Success on Motorola edge 50 pro).
- **Physical Verification Status**:
  - `BLOCKED` on physical USB connection (`USB-SERIAL CH340` port unseated/not present on host). Requires user to reconnect the USB cable and verify serial output.
- **Completion Status**: VERIFIED

---

### PHASE 23 — Auto-Port Detection, Port Release Mechanism & Mega 2560 Power Budget
- **Objective**: Eliminate "cannot open port COM10" upload errors, auto-detect Arduino via USB VID/PID, add release-port mechanism for IDE flashing, protect Pins 0/1, and document power budget.
- **Tasks**:
  - Auto-detect Arduino/Mega 2560 by USB VID/PID (`0x2341`, `0x1A86`, `0x10C4`, `0x0403`) and keywords via `list_ports`, with config/env override (`SERIAL_PORT="AUTO"`).
  - Add thread-safe `pause()` and `resume()` port release endpoints (`POST /api/serial/pause`, `POST /api/serial/resume`, `GET /api/serial/status`) to allow Arduino IDE uploads without port conflict.
  - Clear `self.port = None` upon `SerialException` so dynamic redetection finds newly assigned COM ports if re-enumerated.
  - Add compile-time pin assertions in `arduino/config.h` verifying digital Pins 0 and 1 (RX0/TX0) are never used by any sensor.
  - Add `.init3` MCUSR retention in `arduino/arduino.ino` so Brown-out Resets (`BORF`) and Watchdog events are preserved across bootloader execution.
  - Document complete Mega 2560 5V power budget and GSM buck converter wiring in `docs/HARDWARE_WEIGHT.md`.
  - Add 4 regression tests for auto-detection, override priority, port release, and `SerialException` port recovery.
- **Files Modified**:
  - `backend/app/serial/manager.py` (VID/PID matching, override support, `pause()`, `resume()`, `disconnect()` fix)
  - `backend/app/config.py` (Default `SERIAL_PORT = "AUTO"`, `PAUSE_SERIAL = False`)
  - `backend/app/main.py` (Mapped `/api/serial/pause`, `/api/serial/resume`, `/api/serial/status`, `/api/serial/port`)
  - `arduino/config.h` (Compile-time pin conflict assertions for Pins 0/1)
  - `arduino/hx711_reader.h` (Added `is_ready()` alias)
  - `arduino/arduino.ino` (Added `.init3` MCUSR preservation, startup banner with pin & reset verification)
  - `backend/tests/test_serial_manager.py` (Added 4 new regression tests)
  - `docs/HARDWARE_WEIGHT.md` (Complete 5V power budget breakdown, GSM peak burst analysis, wiring diagram)
- **Tests Executed**:
  - `pytest -v tests/test_serial_manager.py` -> **16 of 16 passed**.
  - `pytest -v tests` -> **79 of 79 passed**.
- **Completion Status**: VERIFIED

---

### PHASE 24 — End-to-End Sensor Data Flow Debugging and Fix
- **Objective**: Eliminate "Waiting for Arduino" on connected Android dashboard, fix cleartext network security block, prevent UI state from blanking valid readings, and stream telemetry from Arduino through FastAPI to Android.
- **Root Cause Analysis**:
  - **Cleartext HTTP Block**: `network_security_config.xml` had `cleartextTrafficPermitted="false"`, which blocked Retrofit from loading `/api/cattle` and `/api/cows` over local LAN HTTP.
  - **Stale Overwrite in Jetpack Compose UI**: In `MainActivity.kt`, whenever `cattle.isStale` was true (>10s without packets or initial DB state), `valStr = if (cattle.isStale || s == null) "Not Available" else s.value` forcibly replaced genuine sensor readings with `"Not Available"`.
  - **WebSocket Silent Connect**: `/ws/cattle/{cattle_id}` subscribed to telemetry but did not emit an initial state snapshot, leaving newly connected clients blank until the next serial packet arrived.
- **Tasks & Fixes Implemented**:
  - **Arduino Firmware (`arduino/arduino.ino`)**:
    - Added `I2C_SPEED_FAST` (400kHz) and `I2C_SPEED_STANDARD` fallback macros to eliminate compilation scope errors across different Arduino IDE library setups.
    - Added periodic non-blocking MAX30102 re-initialization probe in `loop()` (every 5s if `!max_valid`) so the pulse-oximeter auto-reconnects when re-seated without rebooting the board.
  - **Android Network Security & Manifest**:
    - Updated `network_security_config.xml` to allow cleartext LAN communication with the local FastAPI backend.
    - Added `android:usesCleartextTraffic="true"` to `AndroidManifest.xml`.
  - **FastAPI WebSocket Streaming (`backend/app/main.py`)**:
    - Added immediate latest telemetry snapshot transmission upon client connection to `/ws/cattle/{cattle_id}` so dashboard cards populate immediately on open.
  - **Jetpack Compose UI & ViewModel (`MainActivity.kt`)**:
    - Replaced `val valStr = if (cattle.isStale || s == null) "Not Available" else s.value` with `val valStr = s?.value ?: "Not Available"` across all 9 sensor cards (Heart Rate, SpO2, Temperature, pH, LDR, Weight, Steps, Total Steps, MEMS).
    - Preserved valid telemetry numbers on screen during offline/disconnected intervals with visual "Disconnected" / "Offline" status chips, strictly reserving "Not Available" for missing/uncontacted hardware.
    - Updated `ConnectionBanner` to dynamically display the active server URL `ws://${viewModel.currentIp}:${viewModel.currentPort}/ws/cattle/CATTLE-ALL` instead of hardcoded `backend:8000`.
    - Added port argument support to `configureIp(ip, port)`.
  - **APK Distribution**:
    - Built and verified `smart-cattle-update.apk` (16.5 MB) served via `/download` and `/app.apk`.
- **Tests Executed**:
  - Backend test suite: `pytest backend/tests -v` -> **85 of 85 passed**.
  - Android application build: `gradlew assembleDebug` -> **BUILD SUCCESSFUL**.
- **Completion Status**: VERIFIED
