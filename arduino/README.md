# Arduino Firmware Setup & Required Libraries

This folder contains the firmware for the **Smart Cattle Health Monitoring System** designed for **Arduino Mega 2560** (or ESP32).

---

## 1. Required Arduino Libraries

Open the Arduino IDE on your laptop, navigate to **Sketch → Include Library → Manage Libraries...** (or press `Ctrl + Shift + I`), and install the following libraries:

| Library Name in Library Manager | Author | Purpose |
|---------------------------------|--------|---------|
| **SparkFun MAX3010x Pulse and Proximity Sensor Library** | **SparkFun Electronics** | SpO2 & Heart Rate (`MAX30105.h`) |
| **Adafruit ADXL345** | **Adafruit** | Cattle 3-axis step detection (`Adafruit_ADXL345_U.h`) |
| **Adafruit Unified Sensor** | **Adafruit** | Base sensor support |
| **DHT sensor library** | **Adafruit** | Ambient temperature & humidity (`DHT.h`) |
| **TinyGPSPlus** | **Mikal Hart** | GPS tracking (`TinyGPS++.h`) |
| **LiquidCrystal** | **Arduino** | 16x2 LCD display (Built-in) |

---

## 2. Common Compilation Fixes

### Error: `'I2C_SPEED_FAST' was not declared in this scope` or `'class MAX30105' has no member named 'setup'`
- **Cause**: An incompatible or conflicting MAX30105/MAX30102 library was installed instead of the official SparkFun library.
- **Fix**:
  1. Go to `C:\Users\<YourUsername>\Documents\Arduino\libraries\` and remove any folder named `MAX30105` or `MAX30102`.
  2. Open Arduino IDE → **Manage Libraries...** (`Ctrl + Shift + I`).
  3. Search for **`SparkFun MAX3010x`**.
  4. Install **SparkFun MAX3010x Pulse and Proximity Sensor Library** by **SparkFun Electronics**.

---

## 3. How to Upload Firmware

1. Connect the Arduino Mega 2560 to your laptop via USB cable.
2. In Arduino IDE:
   - Select **Tools → Board → Arduino Mega or Mega 2560**.
   - Select **Tools → Processor → ATmega2560 (Mega 2560)**.
   - Select **Tools → Port → COM...** (your Arduino port).
3. If the backend server (`START_BACKEND.bat`) is running, pause the serial reader or close other serial monitors so the COM port is not locked during upload.
4. Click the **Upload** arrow (or press `Ctrl + U`).
