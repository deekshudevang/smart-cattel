#include "config.h"
#include <DHT.h>
#include <Wire.h>
#include "filters.h"
#include "step_counter.h"
#include <MAX30105.h>
#include <TinyGPS++.h>
#include <LiquidCrystal.h>
#include <SoftwareSerial.h>
#include <Adafruit_Sensor.h>
#include <Adafruit_ADXL345_U.h>
#include <EEPROM.h>
#include "hx711_reader.h"
HX711Reader load_cell;

CowStepCounter step_counter;
// DIAGNOSTIC BUILD — MAX30102 I2C scan enabled; continuous raw print disabled to prevent UART TX saturation
#define DEBUG_MAX30102 0 // Set to 1 only for dedicated bench debug

// ---------- Boot / Reset Reason Diagnostics ----------
#if defined(ESP32)
#include <esp_system.h>
void printBootReason() {
  esp_reset_reason_t reason = esp_reset_reason();
  Serial.print(F("[BOOT] ESP32 Reset Reason: "));
  switch (reason) {
    case ESP_RST_POWERON:   Serial.println(F("POWER_ON (Normal startup)")); break;
    case ESP_RST_EXT:       Serial.println(F("EXTERNAL_PIN (Manual/DTR reset)")); break;
    case ESP_RST_SW:        Serial.println(F("SOFTWARE_RESET")); break;
    case ESP_RST_PANIC:     Serial.println(F("CRASH_PANIC (Fatal exception!)")); break;
    case ESP_RST_INT_WDT:   Serial.println(F("INTERRUPT_WATCHDOG")); break;
    case ESP_RST_TASK_WDT:  Serial.println(F("TASK_WATCHDOG")); break;
    case ESP_RST_WDT:       Serial.println(F("WATCHDOG_RESET")); break;
    case ESP_RST_DEEPSLEEP: Serial.println(F("DEEP_SLEEP")); break;
    case ESP_RST_BROWNOUT:  Serial.println(F("BROWNOUT (Power supply unstable - check 5V/3.3V rail!)")); break;
    case ESP_RST_SDIO:      Serial.println(F("SDIO_RESET")); break;
    default:                Serial.println(F("UNKNOWN")); break;
  }
}
#elif defined(__AVR__)
#include <avr/wdt.h>
void printBootReason() {
  uint8_t mcusr = MCUSR;
  MCUSR = 0; // Clear reset flags for next boot cycle
  Serial.print(F("[BOOT] AVR Reset Reason (MCUSR=0x"));
  Serial.print(mcusr, HEX);
  Serial.print(F("): "));
  if (mcusr & (1 << PORF))  Serial.print(F("Power-on Reset (PORF) "));
  if (mcusr & (1 << EXTRF)) Serial.print(F("External Reset Pin/DTR (EXTRF) "));
  if (mcusr & (1 << BORF))  Serial.print(F("Brown-out Reset (BORF - Power supply voltage dipped!) "));
  if (mcusr & (1 << WDRF))  Serial.print(F("Watchdog Timer Reset (WDRF) "));
  if (mcusr == 0)           Serial.print(F("Normal / Software cleared"));
  Serial.println();
}
#else
void printBootReason() {
  Serial.println(F("[BOOT] Reset Reason: Standard power startup"));
}
#endif

//---------------- GPS + GSM (Using Mega Hardware Serials) ----------------

#define GPS_SERIAL Serial1 // Pins 19 (RX1), 18 (TX1)
#define GSM_SERIAL Serial2 // Pins 17 (RX2), 16 (TX2)
TinyGPSPlus gps;

String LAT = "0.000000";
String LON = "0.000000";
unsigned long last_sms_time = 0;
const unsigned long SMS_COOLDOWN = 60000; // 60 seconds cooldown

// ---------- pH Analog Sensor ----------
#define PH_PIN A1
#define EEPROM_PH_ADDR 0

struct PHCalibration {
  float voltage4;
  float voltage7;
  int magic; // 0x5048 (PH)
};

PHCalibration ph_calibration;
MovingAverageFilter<10> averager_ph;

int ph_raw = 0;
float ph_voltage = 0.0;
float ph_value = 0.0;
bool ph_valid = false;

// ---------- LDR ----------
#define LDR_PIN A2
int ldrValue = 0;
bool ldr_valid = false;

// ---------- DHT11 ----------
#define DHTPIN 42
#define DHTTYPE DHT11

DHT dht(DHTPIN, DHTTYPE);

float temperature = NAN;
float humidity = NAN;
bool dht_valid = false;
float dht_sensor_quality = 1.0;
unsigned long dht_last_successful_read = 0;
int dht_consecutive_failures = 0;

// ---------- MAX30102 ----------
MAX30105 particleSensor;
const float kSamplingFrequency = 400.0;
bool max_valid = false;

// Finger Detection Threshold and Cooldown
// Raised from 5000; lower to 3000 if still no contact detected
const unsigned long kFingerThreshold = 10000;
const unsigned int kFingerCooldownMs = 100;

// Edge Detection Threshold
const float kEdgeThreshold = -800.0;

// Filters
const float kLowPassCutoff = 5.0;
const float kHighPassCutoff = 0.5;

// Averaging
const int kAveragingSamples = 5;

// ---------- ADXL345 ----------
Adafruit_ADXL345_Unified accel = Adafruit_ADXL345_Unified(12345);
bool adxl_valid = false;
float xValue = 0;
float yValue = 0;
float zValue = 0;

// ---------- LCD ----------
// RS, EN, D4, D5, D6, D7
LiquidCrystal lcd(30, 32, 34, 36, 38, 40);
bool lcd_showing_waiting = false;

int displayBPM = 0;
int displaySpO2 = 0;
float displayBPMQuality = 0.0;
float displaySpO2Quality = 0.0;
bool displayBPMValid = false;
bool displaySpO2Valid = false;
String displayState = "NO_SENSOR";

unsigned long last_data_sent = 0;

// Filter Instances

LowPassFilter low_pass_filter_red(kLowPassCutoff, kSamplingFrequency);
LowPassFilter low_pass_filter_ir(kLowPassCutoff, kSamplingFrequency);
HighPassFilter high_pass_filter(kHighPassCutoff, kSamplingFrequency);
Differentiator differentiator(kSamplingFrequency);
MovingAverageFilter<kAveragingSamples> averager_bpm;
MovingAverageFilter<kAveragingSamples> averager_r;
MovingAverageFilter<kAveragingSamples> averager_spo2;

MinMaxAvgStatistic stat_red;
MinMaxAvgStatistic stat_ir;

float kSpO2_A = 1.5958422;
float kSpO2_B = -34.6596622;
float kSpO2_C = 112.6898759;

long last_heartbeat = 0;
long finger_timestamp = 0;
bool finger_detected = false;

float last_diff = NAN;
bool crossed = false;
long crossed_time = 0;

void i2c_scan() {
  Serial.println("[I2C] Probing sensors (0x57 MAX30102, 0x53/0x1D ADXL345)...");
  Wire.beginTransmission(0x57);
  bool has_max = (Wire.endTransmission() == 0);
  if (has_max) {
    Serial.println("[MAX30102] Device detected: YES at 0x57");
  } else {
    Serial.println("[MAX30102] Device detected: NO at 0x57");
  }

  Wire.beginTransmission(0x53);
  bool has_adxl53 = (Wire.endTransmission() == 0);
  Wire.beginTransmission(0x1D);
  bool has_adxl1d = (Wire.endTransmission() == 0);
  if (has_adxl53) {
    Serial.println("[ADXL345] Device detected: YES at 0x53");
  } else if (has_adxl1d) {
    Serial.println("[ADXL345] Device detected: YES at 0x1D");
  } else {
    Serial.println("[ADXL345] Device detected: NO at 0x53 or 0x1D");
  }
}

// ---------- Non-blocking Serial Checksum Print ----------
class ChecksumPrint : public Print {
public:
  Print &target;
  uint8_t checksum;
  ChecksumPrint(Print &p) : target(p), checksum(0) {}
  size_t write(uint8_t c) override {
    checksum ^= c;
    return target.write(c);
  }
};

// ---------- Non-blocking GSM SMS State Machine ----------
enum GsmState {
  GSM_IDLE,
  GSM_SEND_AT,
  GSM_WAIT_AT,
  GSM_SEND_CMGF,
  GSM_WAIT_CMGF,
  GSM_SEND_CMGS,
  GSM_WAIT_CMGS,
  GSM_SEND_BODY,
  GSM_WAIT_BODY,
  GSM_SEND_CTRLZ,
  GSM_WAIT_SEND
};

GsmState gsm_state = GSM_IDLE;
unsigned long gsm_state_start = 0;
String gsm_pending_msg = "";

void queueSMS(const String &msg) {
  if (millis() - last_sms_time < SMS_COOLDOWN && last_sms_time != 0) {
    Serial.println(F("{\"event\":\"sms_delivery\",\"status\":\"cooldown\",\"message\":\"Skipped due to cooldown\"}"));
    return;
  }
  if (gsm_state != GSM_IDLE) {
    return; // Already busy sending an SMS
  }
  gsm_pending_msg = msg;
  gsm_state = GSM_SEND_AT;
  gsm_state_start = millis();
}

void updateGSM(unsigned long now_ms) {
  switch (gsm_state) {
    case GSM_IDLE:
      break;
    case GSM_SEND_AT:
      GSM_SERIAL.println("AT");
      gsm_state = GSM_WAIT_AT;
      gsm_state_start = now_ms;
      break;
    case GSM_WAIT_AT:
      if (now_ms - gsm_state_start >= 1000) {
        gsm_state = GSM_SEND_CMGF;
      }
      break;
    case GSM_SEND_CMGF:
      GSM_SERIAL.println("AT+CMGF=1");
      gsm_state = GSM_WAIT_CMGF;
      gsm_state_start = now_ms;
      break;
    case GSM_WAIT_CMGF:
      if (now_ms - gsm_state_start >= 500) {
        gsm_state = GSM_SEND_CMGS;
      }
      break;
    case GSM_SEND_CMGS:
      GSM_SERIAL.print("AT+CMGS=\"");
      GSM_SERIAL.print(SMS_PHONE_NUMBER);
      GSM_SERIAL.println("\"");
      gsm_state = GSM_WAIT_CMGS;
      gsm_state_start = now_ms;
      break;
    case GSM_WAIT_CMGS:
      if (now_ms - gsm_state_start >= 500) {
        gsm_state = GSM_SEND_BODY;
      }
      break;
    case GSM_SEND_BODY:
      GSM_SERIAL.print("ALERT: ");
      GSM_SERIAL.println(gsm_pending_msg);
      GSM_SERIAL.print("CATTLE ID: ");
      GSM_SERIAL.println(CATTLE_ID);
      if (gps.location.isValid()) {
        GSM_SERIAL.println("Location: https://maps.google.com/?q=" + LAT + "," + LON);
      } else {
        GSM_SERIAL.println("Location: GPS signal lost.");
      }
      gsm_state = GSM_WAIT_BODY;
      gsm_state_start = now_ms;
      break;
    case GSM_WAIT_BODY:
      if (now_ms - gsm_state_start >= 300) {
        gsm_state = GSM_SEND_CTRLZ;
      }
      break;
    case GSM_SEND_CTRLZ:
      GSM_SERIAL.write(26); // ASCII SUB / Ctrl+Z
      gsm_state = GSM_WAIT_SEND;
      gsm_state_start = now_ms;
      break;
    case GSM_WAIT_SEND:
      if (now_ms - gsm_state_start >= 3000) {
        last_sms_time = now_ms;
        Serial.print(F("{\"event\":\"sms_delivery\",\"status\":\"attempted\",\"message\":\""));
        Serial.print(gsm_pending_msg);
        Serial.println(F("\"}"));
        gsm_pending_msg = "";
        gsm_state = GSM_IDLE;
      }
      break;
  }
}

// ---------- Non-blocking Alert LCD State ----------
String lcd_alert_msg = "";
unsigned long lcd_alert_until = 0;

void triggerAlertDisplay(const String &msg) {
  lcd_alert_msg = msg;
  lcd_alert_until = millis() + 3000;
  queueSMS(msg);
}

void setup() {
  Serial.begin(9600);
  Serial.setTimeout(50); // Fast non-blocking timeout for readStringUntil to prevent loop freezes
  printBootReason();
  GPS_SERIAL.begin(9600);
  GSM_SERIAL.begin(9600);

  lcd.begin(16, 2);
  lcd.clear();
  lcd.setCursor(0, 0);
  lcd.print("SMART CATTLE");
  lcd.setCursor(0, 1);
  lcd.print("WAITING DATA...");
  lcd_showing_waiting = true;

  Wire.begin();
  Wire.setWireTimeout(25000, true);
  Serial.println("[MAX30102] Initializing...");
  i2c_scan();

  if (!particleSensor.begin(Wire, I2C_SPEED_FAST)) {
    max_valid = false;
    Serial.println("[MAX30102] FAILED TO INITIALIZE — HR/SpO2 will be null");
    Serial.println("[MAX30102] Fix wiring, then re-upload");
  } else {
    byte ledBrightness = 60;
    byte sampleAverage = 4;
    byte ledMode = 2;       // 2 = Red + IR
    int sampleRate = 400;
    int pulseWidth = 411;
    int adcRange = 16384;
    particleSensor.setup(ledBrightness, sampleAverage, ledMode, sampleRate, pulseWidth, adcRange);
    particleSensor.setPulseAmplitudeRed(0x1F);
    particleSensor.setPulseAmplitudeIR(0x1F);
    max_valid = true;
    Serial.println("[MAX30102] INITIALIZED OK");
    Serial.print("[MAX30102 CONFIG] LED="); Serial.print(ledBrightness);
    Serial.print(" Rate="); Serial.print(sampleRate);
    Serial.print(" PW="); Serial.print(pulseWidth);
    Serial.print(" ADC="); Serial.println(adcRange);
  }

  step_counter.begin();
  Serial.print(F("[STEP] Restored today=")); Serial.print(step_counter.steps_today);
  Serial.print(F(" lifetime=")); Serial.println(step_counter.steps_lifetime);

  if (accel.begin(0x53)) {
    adxl_valid = true;
    accel.setRange(ADXL345_RANGE_2_G);
    Serial.println(F("[ADXL345] Initialized at 0x53"));
  } else if (accel.begin(0x1D)) {
    adxl_valid = true;
    accel.setRange(ADXL345_RANGE_2_G);
    Serial.println(F("[ADXL345] Initialized at 0x1D"));
  } else {
    adxl_valid = false;
    Serial.println(F("[ADXL345] Not found at 0x53 or 0x1D (will re-probe)"));
  }

  dht.begin();
  Serial.println("[DHT11] Initialized");
  Serial.println("[LDR] Initialized");
  EEPROM.get(EEPROM_PH_ADDR, ph_calibration);
  if (ph_calibration.magic != 0x5048) {
    ph_calibration.voltage4 = 3.0;
    ph_calibration.voltage7 = 2.5;
    ph_calibration.magic = 0x5048;
    EEPROM.put(EEPROM_PH_ADDR, ph_calibration);
    Serial.println("[pH] Loaded default calibration");
  } else {
    Serial.println("[pH] Loaded calibration from EEPROM");
  }
  averager_ph.reset();
  Serial.println("[pH] Initialized");
  Serial.println("[GPS] Initialized");
  Serial.println("[GSM] Initialized");
  load_cell.begin(HX711_DOUT_PIN, HX711_SCK_PIN);
  Serial.println("[HX711] Initialized");
}

void loop() {
  // Update non-blocking GSM SMS transmission
  updateGSM(millis());
  load_cell.update(millis());

  // ---------- ADXL345 Continuous Sampling (~25Hz / 40ms) & Auto-Reconnection ----------
  static unsigned long last_adxl_probe = 0;
  if (!adxl_valid && millis() - last_adxl_probe >= 4000) {
    last_adxl_probe = millis();
    if (accel.begin(0x53)) {
      adxl_valid = true;
      accel.setRange(ADXL345_RANGE_2_G);
      Serial.println(F("[ADXL345] Reconnected at 0x53"));
    } else if (accel.begin(0x1D)) {
      adxl_valid = true;
      accel.setRange(ADXL345_RANGE_2_G);
      Serial.println(F("[ADXL345] Reconnected at 0x1D"));
    }
  }

  static unsigned long last_accel_sample = 0;
  if (millis() - last_accel_sample >= 40) {
    last_accel_sample = millis();
    if (adxl_valid) {
      sensors_event_t event;
      accel.getEvent(&event);
      xValue = event.acceleration.x;
      yValue = event.acceleration.y;
      zValue = event.acceleration.z; // Capture real Z-axis acceleration
      if (step_counter.processSample(xValue, yValue, zValue, millis())) {
        Serial.print(F("[STEP] Step detected! Ax="));
        Serial.print(xValue, 2);
        Serial.print(F(" Ay="));
        Serial.print(yValue, 2);
        Serial.print(F(" Az="));
        Serial.print(zValue, 2);
        Serial.print(F(" Today="));
        Serial.print(step_counter.steps_today);
        Serial.print(F(" Lifetime="));
        Serial.println(step_counter.steps_lifetime);
      }
    }
  }

  // ---------- DHT11 Read ----------
  static unsigned long lastDhtRead = 0;
  if (millis() - lastDhtRead >= 2000) {
    float temp = dht.readTemperature();
    float hum = dht.readHumidity();
    
    if (isnan(temp) || isnan(hum)) {
      dht_valid = false;
      dht_consecutive_failures++;
      dht_sensor_quality = 0.0;
      temperature = NAN;
      humidity = NAN;
    } else {
      if (temp >= -20.0 && temp <= 60.0 && hum >= 0.0 && hum <= 100.0) {
        dht_valid = true;
        dht_consecutive_failures = 0;
        dht_sensor_quality = 1.0;
        dht_last_successful_read = millis();
        temperature = temp;
        humidity = hum;
      } else {
        dht_valid = false;
        dht_consecutive_failures++;
        dht_sensor_quality = 0.5; // Out of bounds but responding
        temperature = NAN;
        humidity = NAN;
      }
    }
    lastDhtRead = millis();
  }

  // ---------- LDR Read ----------
  ldrValue = analogRead(LDR_PIN);
  ldr_valid = true; // LDR is analog, usually valid

  // ---------- GPS Read (100% Non-Blocking) ----------
  while (GPS_SERIAL.available()) {
    gps.encode(GPS_SERIAL.read());
  }
  if (gps.location.isValid()) {
    LAT = String(gps.location.lat(), 6);
    LON = String(gps.location.lng(), 6);
  }

  // ---------- pH Read (with floating pin detection) ----------
  pinMode(PH_PIN, INPUT_PULLUP);
  delayMicroseconds(20);
  int ph_pullup = analogRead(PH_PIN);
  pinMode(PH_PIN, INPUT);
  delayMicroseconds(20);
  ph_raw = analogRead(PH_PIN);
  
  // If pin is floating (no probe attached), internal pullup charges it to ~1023
  bool ph_floating = (ph_pullup >= 1010 && abs(ph_pullup - ph_raw) > 25);
  float raw_voltage = ph_raw * (5.0 / 1023.0);
  
  // Smoothing/Filtering
  ph_voltage = averager_ph.process(raw_voltage);
  
  // Detect disconnected or floating sensor (e.g. floating pin or < 0.2V or > 4.8V)
  if (ph_floating || ph_voltage < 0.2 || ph_voltage > 4.8) {
    ph_valid = false;
    ph_value = NAN;
  } else {
    // 2-point calibration formula
    float diff = ph_calibration.voltage7 - ph_calibration.voltage4;
    if (abs(diff) < 0.01) diff = -0.5; // Failsafe against division by zero
    float slope = (7.0 - 4.0) / diff;
    float intercept = 7.0 - slope * ph_calibration.voltage7;
    ph_value = slope * ph_voltage + intercept;
    
    // Validate range
    if (ph_value >= 0.0 && ph_value <= 14.0) {
      ph_valid = true;
    } else {
      ph_valid = false;
    }
  }


  // ---------- MAX30102 Read ----------
  static unsigned long last_raw_print = 0;
  if (max_valid) {
    particleSensor.check();
    float current_value_red = particleSensor.getRed();
    float current_value_ir  = particleSensor.getIR();

    // 4. Contact/finger/probe detection
    bool has_contact = (current_value_ir > kFingerThreshold && current_value_red > kFingerThreshold);
    
    if (!has_contact) {
        displayState = "NO_CONTACT";
    } else if (displayState == "NO_SENSOR" || displayState == "NO_CONTACT") {
        displayState = "PROCESSING";
    }

#if DEBUG_MAX30102
    if (millis() - last_raw_print >= 2000) {
      last_raw_print = millis();
      Serial.print("[MAX30102] IR=");
      Serial.print((long)current_value_ir);
      Serial.print(" RED=");
      Serial.println((long)current_value_red);
      Serial.print("[MAX30102 CONTACT] Contact=");
      Serial.println(has_contact ? "YES" : "NO (place sensor on skin/vein)");
    }
#endif

    if (has_contact) {
      if (millis() - finger_timestamp > kFingerCooldownMs) {
        finger_detected = true;
      }
    } else {
      differentiator.reset();
      averager_bpm.reset();
      averager_r.reset();
      averager_spo2.reset();
      low_pass_filter_red.reset();
      low_pass_filter_ir.reset();
      high_pass_filter.reset();
      stat_red.reset();
      stat_ir.reset();
      finger_detected = false;
      finger_timestamp = millis();
      displayBPM = 0;
      displaySpO2 = 0;
      displayBPMValid = false;
      displaySpO2Valid = false;
      displayBPMQuality = 0.0;
      displaySpO2Quality = 0.0;
    }

    if (finger_detected) {
      // 5. Noise filtering
      current_value_red = low_pass_filter_red.process(current_value_red);
      current_value_ir  = low_pass_filter_ir.process(current_value_ir);
      stat_red.process(current_value_red);
      stat_ir.process(current_value_ir);

      float current_value = high_pass_filter.process(current_value_red);
      float current_diff  = differentiator.process(current_value);

      if (!isnan(current_diff) && !isnan(last_diff)) {
        if (last_diff > 0 && current_diff < 0) {
          crossed = true;
          crossed_time = millis();
        }
        if (current_diff > 0) {
          crossed = false;
        }
        // 6. Beat detection
        if (crossed && current_diff < kEdgeThreshold) {
          if (last_heartbeat != 0 && crossed_time - last_heartbeat > 100) {
            // 7. BPM calculation
            int bpm = 60000 / (crossed_time - last_heartbeat);
            
            // 2. IR signal quality & 3. RED signal quality
            float red_ac = stat_red.maximum() - stat_red.minimum();
            float red_dc = stat_red.average();
            float ir_ac  = stat_ir.maximum()  - stat_ir.minimum();
            float ir_dc  = stat_ir.average();
            
            // Avoid division by zero
            if (red_dc > 0.1 && ir_dc > 0.1) {
              float rred = red_ac / red_dc;
              float rir  = ir_ac  / ir_dc;
              float r    = rred / rir;
              
              // 8. SpO₂ calculation
              float spo2 = kSpO2_A * r * r + kSpO2_B * r + kSpO2_C;

              if (spo2 > 100) spo2 = 100;
              if (spo2 < 0)   spo2 = 0;

              // 11. Signal quality score
              // Calculate basic signal quality based on AC amplitude (should be prominent but not clipped)
              float quality = 1.0;
              if (red_ac < 100 || ir_ac < 100) quality -= 0.5; // noisy/weak signal
              if (red_ac > 30000 || ir_ac > 30000) quality -= 0.5; // clipping
              if (quality < 0.0) quality = 0.0;

              // 9. BPM validity & 10. SpO₂ validity
              bool is_bpm_valid = (bpm > 20 && bpm < 300) && (quality > 0.3);
              bool is_spo2_valid = (spo2 > 50 && spo2 <= 100) && (quality > 0.3);

              if (is_bpm_valid && is_spo2_valid) {
                displayState = "VALID";
                displayBPM  = bpm;
                displaySpO2 = (int)spo2;
                displayBPMValid = true;
                displaySpO2Valid = true;
                displayBPMQuality = quality;
                displaySpO2Quality = quality;
                
#if DEBUG_MAX30102
                Serial.print("[HR] Valid pulse BPM=");
                Serial.print(displayBPM);
                Serial.print(" Q=");
                Serial.println(quality);
                Serial.print("[SpO2] Valid SpO2=");
                Serial.print(displaySpO2);
                Serial.print("% Q=");
                Serial.println(quality);
#endif
              } else {
                if (red_ac < 100 || ir_ac < 100) {
                  displayState = "LOW_SIGNAL";
                } else {
                  displayState = "UNSTABLE";
                }
                // Keep the last valid values instead of setting to false on single noisy peak
                displayBPMQuality = quality;
                displaySpO2Quality = quality;
#if DEBUG_MAX30102
                Serial.println("[MAX30102] Signal invalid or noisy, retaining previous values");
#endif
              }
            }
          }
          stat_red.reset();
          stat_ir.reset();
          crossed = false;
          last_heartbeat = crossed_time;
        }
      }
      last_diff = current_diff;
    }
  }

  // ---------- Send Data Every 1.5 Seconds ----------
  if (millis() - last_data_sent > 1500) {
    last_data_sent = millis();

    // 1. Update LCD
    static int lcd_page = 0;
    lcd_showing_waiting = false;
    lcd.clear();
    lcd.setCursor(0, 0);

    // Physiological validity checks
    bool accel_ok = adxl_valid;
    bool hr_ok    = max_valid && finger_detected && displayBPMValid && (displayBPM >= 30 && displayBPM <= 220) && (millis() - last_heartbeat < 3000);
    bool spo2_ok  = max_valid && finger_detected && displaySpO2Valid && (displaySpO2 >= 50 && displaySpO2 <= 100) && (millis() - last_heartbeat < 3000);
    bool temp_ok  = dht_valid && (!isnan(temperature)) && (temperature >= -10.0f && temperature <= 60.0f);
    bool ph_ok    = ph_valid && (ph_value >= 0.0f && ph_value <= 14.0f);
    bool ldr_ok   = ldr_valid && (ldrValue >= 0 && ldrValue <= 1023);
    bool weight_ok = load_cell.isValid();

    if (millis() < lcd_alert_until && lcd_alert_msg.length() > 0) {
      lcd.print(F("! HEALTH ALERT !"));
      lcd.setCursor(0, 1);
      lcd.print(lcd_alert_msg);
    } else if (lcd_page == 0) {
      // Page 0: Vitals
      lcd.print(F("S:"));
      if (spo2_ok && displaySpO2 > 0) lcd.print(displaySpO2);
      else lcd.print(F("N/A"));
      
      lcd.print(F(" B:"));
      if (hr_ok && displayBPM > 0) lcd.print(displayBPM);
      else lcd.print(F("N/A"));
      
      lcd.print(F(" T:"));
      if (temp_ok) lcd.print((int)temperature);
      else lcd.print(F("N/A"));

      lcd.setCursor(0, 1);
      lcd.print(F("ST:"));
      lcd.print(step_counter.steps_today);
      
      lcd.print(F(" p:"));
      if (ph_ok) lcd.print(ph_value, 1);
      else lcd.print(F("N/A"));
      
      lcd.print(F(" L:"));
      if (ldr_ok) {
        int dispLdr = ldrValue > 999 ? 999 : ldrValue;
        lcd.print(dispLdr);
      }
      else lcd.print(F("N/A"));
      lcd_page = 1;
    } else if (lcd_page == 1) {
      // Page 1: Weight & Scale Status
      lcd.print(F("WT:"));
      if (weight_ok && !isnan(load_cell.getWeightKg())) {
        lcd.print(load_cell.getWeightKg(), 1);
        lcd.print(F("kg "));
        lcd.print(load_cell.isStable() ? F("STB") : F("UNS"));
      } else {
        lcd.print(F("N/A "));
        lcd.print(load_cell.getStatusString());
      }

      lcd.setCursor(0, 1);
      lcd.print(F("Cal:"));
      lcd.print(load_cell.isCalibrated() ? F("YES") : F("NO"));
      lcd.print(F(" Raw:"));
      lcd.print(load_cell.getRawValue());
      lcd_page = 2;
    } else {
      // Page 2: Movement
      lcd.print(F("Steps:"));
      lcd.print(step_counter.steps_today);
      lcd.print(F(" Tot:"));
      lcd.print(step_counter.steps_lifetime);
      
      lcd.setCursor(0, 1);
      lcd.print(F("X:"));
      if (accel_ok) lcd.print(xValue, 1);
      else lcd.print(F("N/A"));
      lcd.print(F(" Y:"));
      if (accel_ok) lcd.print(yValue, 1);
      else lcd.print(F("N/A"));
      lcd.print(F(" Z:"));
      if (accel_ok) lcd.print(zValue, 1);
      else lcd.print(F("N/A"));
      lcd_page = 0;
    }

    // 2. Build Standardized JSON Telemetry with Checksum
    static unsigned long sequence_counter = 0;
    sequence_counter++;

    if (!max_valid) displayState = "NO_SENSOR";

    ChecksumPrint cp(Serial);
    cp.print(F("{\"protocol_version\":1,"));
    cp.print(F("\"cattle_id\":\"")); cp.print(CATTLE_ID); cp.print(F("\","));
    cp.print(F("\"device_id\":\"SC-001\","));
    cp.print(F("\"boot_count\":")); cp.print(step_counter.boot_count); cp.print(F(","));
    cp.print(F("\"sequence\":")); cp.print(sequence_counter); cp.print(F(","));
    cp.print(F("\"timestamp\":")); cp.print(millis()); cp.print(F(","));
    cp.print(F("\"device_connected\":true,"));

    // Steps (today & lifetime)
    cp.print(F("\"step_count_today\":")); cp.print(step_counter.steps_today); cp.print(F(","));
    cp.print(F("\"step_count_lifetime\":")); cp.print(step_counter.steps_lifetime); cp.print(F(","));

    // Accelerometer / MEMS (ADXL345)
    cp.print(F("\"accelerometer_valid\":")); cp.print(accel_ok ? F("true,") : F("false,"));
    if (accel_ok) {
      cp.print(F("\"acceleration_x\":")); cp.print(xValue, 2); cp.print(F(","));
      cp.print(F("\"acceleration_y\":")); cp.print(yValue, 2); cp.print(F(","));
      cp.print(F("\"acceleration_z\":")); cp.print(zValue, 2); cp.print(F(","));
      // legacy aliases
      cp.print(F("\"mems_x\":")); cp.print(xValue, 2); cp.print(F(","));
      cp.print(F("\"mems_y\":")); cp.print(yValue, 2); cp.print(F(","));
      cp.print(F("\"mems_z\":")); cp.print(zValue, 2); cp.print(F(","));
      cp.print(F("\"mems_valid\":true,"));
    } else {
      cp.print(F("\"acceleration_x\":null,"));
      cp.print(F("\"acceleration_y\":null,"));
      cp.print(F("\"acceleration_z\":null,"));
      cp.print(F("\"mems_x\":null,"));
      cp.print(F("\"mems_y\":null,"));
      cp.print(F("\"mems_z\":null,"));
      cp.print(F("\"mems_valid\":false,"));
    }

    // Heart Rate & SpO2 (MAX30102)
    cp.print(F("\"max_state\":\"")); cp.print(displayState); cp.print(F("\","));
    cp.print(F("\"heart_rate_valid\":")); cp.print(hr_ok ? F("true,") : F("false,"));
    cp.print(F("\"bpm_valid\":")); cp.print(hr_ok ? F("true,") : F("false,"));
    if (hr_ok) {
      cp.print(F("\"heart_rate\":")); cp.print(displayBPM); cp.print(F(","));
      cp.print(F("\"heart_rate_bpm\":")); cp.print(displayBPM); cp.print(F(","));
      cp.print(F("\"bpm\":")); cp.print(displayBPM); cp.print(F(","));
    } else {
      cp.print(F("\"heart_rate\":null,"));
      cp.print(F("\"heart_rate_bpm\":null,"));
      cp.print(F("\"bpm\":null,"));
    }
    cp.print(F("\"bpm_quality\":")); cp.print(displayBPMQuality, 2); cp.print(F(","));

    cp.print(F("\"spo2_valid\":")); cp.print(spo2_ok ? F("true,") : F("false,"));
    if (spo2_ok) {
      cp.print(F("\"spo2_percent\":")); cp.print(displaySpO2); cp.print(F(","));
      cp.print(F("\"spo2\":")); cp.print(displaySpO2); cp.print(F(","));
    } else {
      cp.print(F("\"spo2_percent\":null,"));
      cp.print(F("\"spo2\":null,"));
    }
    cp.print(F("\"spo2_quality\":")); cp.print(displaySpO2Quality, 2); cp.print(F(","));

    // Temperature & Humidity (DHT11)
    cp.print(F("\"temperature_valid\":")); cp.print(temp_ok ? F("true,") : F("false,"));
    if (temp_ok) {
      cp.print(F("\"temperature_c\":")); cp.print(temperature, 1); cp.print(F(","));
      cp.print(F("\"temperature\":")); cp.print(temperature, 1); cp.print(F(","));
      cp.print(F("\"humidity\":")); cp.print(humidity, 1); cp.print(F(","));
      cp.print(F("\"humidity_valid\":true,"));
    } else {
      cp.print(F("\"temperature_c\":null,"));
      cp.print(F("\"temperature\":null,"));
      cp.print(F("\"humidity\":null,"));
      cp.print(F("\"humidity_valid\":false,"));
    }
    cp.print(F("\"sensor_quality\":")); cp.print(dht_sensor_quality, 2); cp.print(F(","));
    cp.print(F("\"last_successful_read\":")); cp.print(dht_last_successful_read); cp.print(F(","));

    // pH Sensor
    cp.print(F("\"ph_valid\":")); cp.print(ph_ok ? F("true,") : F("false,"));
    if (ph_ok) {
      cp.print(F("\"ph_value\":")); cp.print(ph_value, 2); cp.print(F(","));
      cp.print(F("\"ph\":")); cp.print(ph_value, 2); cp.print(F(","));
    } else {
      cp.print(F("\"ph_value\":null,"));
      cp.print(F("\"ph\":null,"));
    }

    // LDR Sensor
    cp.print(F("\"ldr_valid\":")); cp.print(ldr_ok ? F("true,") : F("false,"));
    if (ldr_ok) {
      cp.print(F("\"ldr_value\":")); cp.print(ldrValue); cp.print(F(","));
      cp.print(F("\"ldr\":")); cp.print(ldrValue); cp.print(F(","));
    } else {
      cp.print(F("\"ldr_value\":null,"));
      cp.print(F("\"ldr\":null,"));
    }
    
    // GPS
    bool gps_loc_valid = gps.location.isValid();
    cp.print(F("\"gps_valid\":")); cp.print(gps_loc_valid ? F("true,") : F("false,"));
    if (gps_loc_valid) {
      cp.print(F("\"gps_lat\":")); cp.print(LAT); cp.print(F(","));
      cp.print(F("\"gps_lon\":")); cp.print(LON); cp.print(F(","));
    } else {
      cp.print(F("\"gps_lat\":null,"));
      cp.print(F("\"gps_lon\":null,"));
    }
    if (gps.satellites.isValid()) {
      cp.print(F("\"gps_satellites\":")); cp.print(gps.satellites.value());
    } else {
      cp.print(F("\"gps_satellites\":null"));
    }

    // Weight Sensor (Load Cell + HX711)
    weight_ok = load_cell.isValid();
    cp.print(F(",\"weight_valid\":")); cp.print(weight_ok ? F("true,") : F("false,"));
    cp.print(F("\"weight_stable\":")); cp.print(load_cell.isStable() ? F("true,") : F("false,"));
    cp.print(F("\"weight_calibrated\":")); cp.print(load_cell.isCalibrated() ? F("true,") : F("false,"));
    cp.print(F("\"weight_sensor_status\":\"")); cp.print(load_cell.getStatusString()); cp.print(F("\","));
    if (load_cell.isConnected()) {
      cp.print(F("\"weight_raw\":")); cp.print(load_cell.getRawValue()); cp.print(F(","));
    } else {
      cp.print(F("\"weight_raw\":null,"));
    }
    if (weight_ok && !isnan(load_cell.getWeightKg())) {
      cp.print(F("\"body_weight_kg\":")); cp.print(load_cell.getWeightKg(), 2); cp.print(F(","));
      cp.print(F("\"weight_kg\":")); cp.print(load_cell.getWeightKg(), 2);
    } else {
      cp.print(F("\"body_weight_kg\":null,"));
      cp.print(F("\"weight_kg\":null"));
    }
    
    // Checksum of all preceding bytes
    Serial.print(F(",\"chk\":"));
    Serial.print(cp.checksum);
    Serial.println(F("}"));
  }

  // ---------- RECEIVE ML RESULT OR COMMANDS FROM PYTHON ----------
  if (Serial.available()) {
    String data = Serial.readStringUntil('\n');
    data.trim();
    
    if (data.startsWith("RESET_DAY")) {
      step_counter.resetToday();
      Serial.println(F("{\"event\":\"reset_day\",\"status\":\"ok\"}"));
    } else if (data.startsWith("SYNC_STEPS:")) {
      int comma = data.indexOf(',');
      if (comma != -1) {
        uint32_t t_steps = data.substring(11, comma).toInt();
        uint32_t l_steps = data.substring(comma + 1).toInt();
        step_counter.syncCounts(t_steps, l_steps);
        Serial.println(F("{\"event\":\"sync_steps\",\"status\":\"ok\"}"));
      }
    } else if (data.startsWith("CAL_PH:")) {
      int commaIdx = data.indexOf(',');
      if (commaIdx != -1) {
        float v4 = data.substring(7, commaIdx).toFloat();
        float v7 = data.substring(commaIdx + 1).toFloat();
        if (v4 > 0 && v7 > 0 && v4 != v7) {
          ph_calibration.voltage4 = v4;
          ph_calibration.voltage7 = v7;
          ph_calibration.magic = 0x5048;
          EEPROM.put(EEPROM_PH_ADDR, ph_calibration);
          Serial.println(F("[pH] Calibration updated and saved to EEPROM"));
        } else {
          Serial.println(F("[pH] Calibration failed: invalid voltages"));
        }
      }
    } else if (data.startsWith("TARE_WEIGHT") || data.equals("TARE")) {
      if (load_cell.tare()) {
        Serial.println(F("{\"event\":\"weight_tare\",\"status\":\"ok\"}"));
      } else {
        Serial.println(F("{\"event\":\"weight_tare\",\"status\":\"error\",\"message\":\"sensor_disconnected\"}"));
      }
    } else if (data.startsWith("CAL_WEIGHT:")) {
      float ref_kg = data.substring(11).toFloat();
      if (load_cell.calibrateWithReference(ref_kg)) {
        Serial.println(F("{\"event\":\"weight_calibrate\",\"status\":\"ok\"}"));
      } else {
        Serial.println(F("{\"event\":\"weight_calibrate\",\"status\":\"error\",\"message\":\"calibration_failed\"}"));
      }
    } else if (data.startsWith("SET_WEIGHT_CAL:")) {
      int comma = data.indexOf(',');
      if (comma != -1) {
        long offset = data.substring(15, comma).toInt();
        float factor = data.substring(comma + 1).toFloat();
        if (factor > 0.0001f) {
          load_cell.calibration.tare_offset = offset;
          load_cell.calibration.cal_factor = factor;
          load_cell.is_calibrated = true;
          load_cell.saveCalibration();
          Serial.println(F("{\"event\":\"set_weight_cal\",\"status\":\"ok\"}"));
        }
      }
    } else if (data.indexOf("a") != -1 && data.indexOf("b") != -1) {
      int spo2Alert = data.substring(data.indexOf("a") + 1, data.indexOf("b")).toInt();
      int bpmAlert  = data.substring(data.indexOf("b") + 1, data.indexOf("c")).toInt();
      int tempAlert = data.substring(data.indexOf("c") + 1, data.indexOf("d")).toInt();
      int memsAlert = data.substring(data.indexOf("d") + 1, data.indexOf("e")).toInt();
      int phAlert   = data.substring(data.indexOf("e") + 1, data.indexOf("f")).toInt();
      int ldrAlert  = data.substring(data.indexOf("f") + 1, data.indexOf("g")).toInt();

      if (spo2Alert == 0) {
        triggerAlertDisplay(F("SpO2 Abnormal"));
      } else if (bpmAlert == 0) {
        triggerAlertDisplay(F("BPM Abnormal"));
      } else if (tempAlert == 0) {
        triggerAlertDisplay(F("Temp Abnormal"));
      } else if (memsAlert == 0) {
        triggerAlertDisplay(F("Fall Detected"));
      } else if (phAlert == 0) {
        triggerAlertDisplay(F("pH Abnormal"));
      } else if (ldrAlert == 0) {
        triggerAlertDisplay(F("Milk Adulteration"));
      }
    }
  }
}
