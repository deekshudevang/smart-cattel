# Arduino Mega 2560 Power Budget, Pin Architecture & HX711 Weight Sensor Specification

## 1. Executive Summary & Root Cause Analysis

### 1.1 Root Cause of "Port Disappearing" & "cannot open port COM10"
When attempting firmware uploads or receiving telemetry on the Arduino Mega 2560, the host PC may encounter:
```text
Error: cannot open port \\.\COM10: The system cannot find the file specified.
Error: stk500v2_getsync() failed
```
This failure is caused by two distinct physical and software factors:

1. **Power Supply Collapse & USB Transceiver Drop (Physical Root Cause)**:
   - Standard USB 2.0 host ports have a strict hardware current limit of **500 mA** (USB 3.0 provides up to **900 mA**).
   - The onboard 5V linear voltage regulator on the Arduino Mega 2560 (typically AMS1117-5.0 or NCP1117) can dissipate at most ~800 mA before entering thermal or overcurrent foldback.
   - When the **SIM800L GSM module** transmits an RF burst or establishes an active network connection, it draws current pulses up to **2.0 A**.
   - Attempting to power the SIM800L, LCD backlight, Neo-6M GPS, MAX30102, DHT11, pH board, and HX711 load cell from the Mega's 5V rail or PC USB bus drops the $V_{CC}$ rail below **4.0 V**.
   - This voltage dip causes:
     - The **CH340 / ATmega16U2 USB-to-UART bridge** to drop off the Windows USB tree (device unplugged event), causing COM10 to vanish.
     - The ATmega2560 core to trigger a **Brown-Out Reset (`BORF`)**, halting firmware execution.

2. **COM Port Contention During Uploads (Software Root Cause)**:
   - Windows serial ports cannot be opened by two processes simultaneously.
   - If the FastAPI backend server (`uvicorn`) is actively reading telemetry on COM10, the Arduino IDE / `avrdude` uploader cannot open the port, resulting in `Access is denied` or `cannot open port COM10`.
   - The backend now includes a **Port Release Mechanism** (`POST /api/serial/pause`) to release the port for flashing without shutting down the server.

---

## 2. Arduino Mega 2560 5V Power Budget

Below is the measured and nominal current consumption of every sensor and peripheral in the Smart Cattle hardware system:

| Subsystem / Peripheral | Connected Pins | Operating Voltage | Continuous Current (Typ) | Peak Current (Max) | Power Source |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **Arduino Mega 2560** (ATmega2560 + CH340 + Status LEDs) | Core Board | 5.0 V | 70 mA | 90 mA | USB / External DC |
| **MAX30102** (Pulse Oximeter & Heart Rate) | SDA (20), SCL (21) | 3.3 V / 5.0 V | 8 mA | 20 mA | 3.3V / 5V Rail |
| **DHT11** (Temperature & Humidity) | Pin 42 | 5.0 V | 1.5 mA | 2.5 mA | 5V Rail |
| **Analog pH Sensor Board** | Pin A1 | 5.0 V | 8 mA | 15 mA | 5V Rail |
| **LDR Photoresistor Divider** | Pin A2 | 5.0 V | 1.5 mA | 5 mA | 5V Rail |
| **ADXL345** (3-Axis Accelerometer) | SDA (20), SCL (21) | 3.3 V | 0.15 mA | 0.2 mA | 3.3V Rail |
| **HX711 + 4-Cell Load Cell Bridge** | DOUT (44), SCK (46) | 5.0 V | 15 mA | 25 mA | 5V Rail |
| **16x2 HD44780 LCD** (with LED Backlight) | Pins 30, 32, 34, 36, 38, 40 | 5.0 V | 35 mA | 50 mA | 5V Rail |
| **Neo-6M GPS Module** | RX1 (19), TX1 (18) | 5.0 V | 50 mA | 80 mA | 5V Rail |
| **SIM800L GSM Module** | RX2 (17), TX2 (16) | 3.7 V – 4.4 V | 40 mA (idle) | **2000 mA (2.0 A)** | **External Buck Only** |
| **TOTAL (Steady State - GPS lock + Sensors active)** | - | - | **~230 mA** | **~290 mA** | Safe for USB 2.0 |
| **TOTAL (During GSM Transmission Burst)** | - | - | **~230 mA** | **~2300 mA (2.3 A)** | **EXCEEDS USB LIMIT** |

> [!CAUTION]
> **NEVER power the SIM800L GSM module from the Arduino Mega 5V or 3.3V header pins.** The onboard regulator and PC USB port cannot supply 2.0 A bursts. The voltage drop will cause the board to brown out and reset continuously.

---

## 3. Recommended External Power Supply Architecture

To guarantee 100% stable operation without COM port dropouts:

```
[ 12V 2A DC Wall Adapter ]
      │
      ├──────────────────────> [ DC Barrel Jack on Arduino Mega ] (Onboard regulator supplies Mega + sensors)
      │
      └──────> [ LM2596 / MP1584 Buck Converter ]
                    │  (Adjust output to 4.0V - 4.2V)
                    ├───(+)───> SIM800L VCC
                    ├───[1000µF Low-ESR Cap]───(Across VCC & GND)
                    └───(-)───> SIM800L GND ────┬────> Arduino Mega GND (Common Ground)
                                                │
                                                └────> Sensor Ground Bus
```

### Power Wiring Rules:
1. **Dedicated GSM Buck Converter**: Step down 9V/12V to **4.0 V - 4.2 V** directly into SIM800L `VCC`.
2. **Buffer Capacitor**: Solder a **1000 µF to 2200 µF (16V)** electrolytic capacitor directly across the SIM800L `VCC` and `GND` pins to absorb the 2A RF transmission pulses.
3. **Common Ground**: All ground pins (Arduino GND, Buck GND, Sensor GND, HX711 GND) **MUST** be connected together to form a common electrical reference.

---

## 4. Hardware Pin Allocation & Verification

### 4.1 Strict Protection of Pins 0 and 1 (RX0 / TX0)
On the Arduino Mega 2560, **Digital Pin 0 (RX0)** and **Digital Pin 1 (TX0)** are directly connected to the ATmega16U2 / CH340 USB transceiver. 

- **Rule**: Digital Pins 0 and 1 are **strictly reserved** for PC USB serial communications and `avrdude` bootloader flashing.
- **Verification**: The codebase includes compile-time checks in [`arduino/config.h`](file:///C:/Users/deeks/Downloads/smart%20cattle%20health%20monitoring/arduino/config.h) to reject any attempt to assign a sensor to Pin 0 or Pin 1.
- **Audit Findings**:
  - `DHTPIN` = Pin 42
  - `HX711_DOUT_PIN` = Pin 44
  - `HX711_SCK_PIN` = Pin 46
  - `LDR_PIN` = Pin A2
  - `PH_PIN` = Pin A1
  - `GPS_SERIAL` = Serial1 (Pins 19 RX, 18 TX)
  - `GSM_SERIAL` = Serial2 (Pins 17 RX, 16 TX)
  - `I2C Bus` = Pins 20 (SDA), 21 (SCL)
  - `LCD 16x2` = Pins 30, 32, 34, 36, 38, 40
  - **Result**: Zero sensor pins overlap with Pins 0 or 1.

---

## 5. HX711 Load Cell Integration & Weighing Platform

### 5.1 Platform Load Cell Configuration for Cattle
Full-grown cattle weigh between **400 kg and 800 kg** (bulls up to **1200 kg**). A single small load cell (e.g. 50 kg hobby cell) will mechanically shear under live animal loads.

**Recommended Platform Setup**:
- **4 x Shear Beam Load Cells** rated for **500 kg or 1000 kg each** (total capacity 2000 kg – 4000 kg).
- **4-Wire Summing Junction Box**: Combines the Wheatstone bridge outputs with onboard trimming potentiometers for corner-balancing.
- **Wiring to HX711 Module**:
  - `E+` / `RED`: Excitation Positive (+5V from HX711)
  - `E-` / `BLK`: Excitation Negative (GND from HX711)
  - `A+` / `GRN`: Signal Positive
  - `A-` / `WHT`: Signal Negative
- **HX711 Digital Pins to Mega 2560**:
  - `VCC`: +5V
  - `GND`: GND
  - `DT` (DOUT): Pin 44
  - `SCK`: Pin 46

### 5.2 Non-Blocking Firmware Driver
The firmware driver [`arduino/hx711_reader.h`](file:///C:/Users/deeks/Downloads/smart%20cattle%20health%20monitoring/arduino/hx711_reader.h) uses `is_ready()`:
```cpp
bool is_ready() {
    return (digitalRead(dout_pin) == LOW);
}
```
If the load cell is busy or disconnected, the call returns `false` immediately in `< 1 µs`, preventing the Arduino main loop from stalling.

---

## 6. Port Release Mechanism for Safe Firmware Uploads

To upload firmware from the Arduino IDE without closing the backend:

1. **Pause the Serial Reader**:
   Open a browser or terminal and run:
   ```bash
   curl -X POST http://localhost:8000/api/serial/pause
   ```
   *(Or navigate to `http://localhost:8000/api/serial/pause` in your web browser).*
   - The backend closes the COM port handle immediately and enters a paused state.
   - Log output: `[SERIAL PORT RELEASED] port=COM10 reader paused for firmware upload.`

2. **Upload from Arduino IDE**:
   - In Arduino IDE, select **Tools > Port > COM10** and click **Upload**.
   - The upload will succeed without port conflict.

3. **Resume the Serial Reader**:
   ```bash
   curl -X POST http://localhost:8000/api/serial/resume
   ```
   - The backend re-opens the port and resumes live telemetry streaming.

