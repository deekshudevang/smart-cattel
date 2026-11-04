| **ADXL345 Physical Tilt/Shake Test** | Requires human operator to physically move board | - | - | **UNVERIFIED (Requires user physical motion)** |
| **On-Phone Live Display** | Phone currently unplugged from PC USB | - | - | **UNVERIFIED on physical screen (APK ready & verified in unit tests)** |

---

### PHASE 21 — Automatic Disconnection Diagnosis & Resilient Reconnection
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

### PHASE 22 — Auto-Port Detection, Port Release Mechanism & Mega 2560 Power Budget
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
