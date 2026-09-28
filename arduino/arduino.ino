#include "config.h"
#include <DHT.h>
#include <Wire.h>
#include "filters.h"
#include <MAX30105.h>
#include <TinyGPS++.h>
#include <LiquidCrystal.h>
#include <SoftwareSerial.h>
#include <Adafruit_Sensor.h>
#include <Adafruit_ADXL345_U.h>
#include <EEPROM.h>
// DIAGNOSTIC BUILD — MAX30102 I2C scan + raw IR/RED output enabled
#define DEBUG_MAX30102 1 // Set to 1 to enable MAX30102 debug output

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

float temperature = 0;
float humidity = 0;
bool dht_valid = false;

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
  Serial.println("[I2C] Scanning...");
  int count = 0;
  for (byte addr = 1; addr < 127; addr++) {
    Wire.beginTransmission(addr);
    if (Wire.endTransmission() == 0) {
      Serial.print("[I2C] Found device at 0x");
      if (addr < 16) Serial.print("0");
      Serial.println(addr, HEX);
      count++;
    }
  }
  if (count == 0) {
    Serial.println("[I2C] No devices found — check SDA/SCL wiring");
  }
  Wire.beginTransmission(0x57);
  bool has_max = (Wire.endTransmission() == 0);
  if (has_max) {
    Serial.println("[MAX30102] Device detected: YES at 0x57");
  } else {
    Serial.println("[MAX30102] Device detected: NO");
    Serial.println("[MAX30102] Arduino Mega I2C: SDA=Pin20, SCL=Pin21");
    Serial.println("[MAX30102] Check VCC(3.3V/5V), GND, SDA, SCL connections");
  }
}

void setup() {
  Serial.begin(9600);
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

  if (accel.begin()) {
    adxl_valid = true;
    accel.setRange(ADXL345_RANGE_2_G);
    Serial.println("[ADXL345] Initialized");
  } else {
    adxl_valid = false;
    Serial.println("[ADXL345] Not found");
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
}

void loop() {
  // ---------- ADXL345 Read ----------
  if (adxl_valid) {
    sensors_event_t event;
    accel.getEvent(&event);
    xValue = event.acceleration.x;
    yValue = event.acceleration.y;
    zValue = event.acceleration.z;
  }

  // ---------- DHT11 Read ----------
  static unsigned long lastDhtRead = 0;
  if (millis() - lastDhtRead >= 2000) {
    temperature = dht.readTemperature();
    humidity = dht.readHumidity();
    dht_valid = !isnan(temperature) && !isnan(humidity);
    lastDhtRead = millis();
  }

  // ---------- LDR Read ----------
  ldrValue = analogRead(LDR_PIN);
  ldr_valid = true; // LDR is analog, usually valid

  // ---------- GPS Read ----------
  smartDelay(50);
  if (gps.location.isValid()) {
    LAT = String(gps.location.lat(), 6);
    LON = String(gps.location.lng(), 6);
  }

  // ---------- pH Read ----------
  ph_raw = analogRead(PH_PIN);
  float raw_voltage = ph_raw * (5.0 / 1023.0);
  
  // Smoothing/Filtering
  ph_voltage = averager_ph.process(raw_voltage);
  
  // Detect disconnected sensor (e.g., < 0.1V or > 4.9V)
  if (ph_voltage < 0.1 || ph_voltage > 4.9) {
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
                displayBPMValid = false;
                displaySpO2Valid = false;
                displayBPMQuality = quality;
                displaySpO2Quality = quality;
#if DEBUG_MAX30102
                Serial.println("[MAX30102] Signal invalid or noisy, values set to null");
#endif
              }
            }
          }
          stat_red.reset();
          stat_ir.reset();
        }
        crossed = false;
        last_heartbeat = crossed_time;
      }
      last_diff = current_diff;
    }
  }

  // ---------- Send Data Every 1.5 Seconds ----------
  if (millis() - last_data_sent > 1500) {
    last_data_sent = millis();

    // 1. Update LCD
    static bool show_page_1 = true;
    lcd_showing_waiting = false;
    lcd.clear();
    lcd.setCursor(0, 0);
    
    if (show_page_1) {
      lcd.print("S:");
      if (max_valid && finger_detected && displaySpO2 > 0) lcd.print(displaySpO2);
      else lcd.print("--");
      
      lcd.print(" B:");
      if (max_valid && finger_detected && displayBPM > 0) lcd.print(displayBPM);
      else lcd.print("--");
      
      lcd.print(" T:");
      if (dht_valid) lcd.print((int)temperature);
      else lcd.print("--");

      lcd.setCursor(0, 1);
      lcd.print("p:");
      if (ph_valid) lcd.print(ph_value, 1);
      else lcd.print("--");
      
      lcd.print(" L:");
      if (ldr_valid) {
        int dispLdr = ldrValue > 999 ? 999 : ldrValue;
        lcd.print(dispLdr);
      }
      else lcd.print("--");
      
      lcd.print(" X:");
      if (adxl_valid) lcd.print(xValue, 1);
      else lcd.print("--");
    } else {
      lcd.print("SpO2:");
      if (max_valid && finger_detected && displaySpO2 > 0) lcd.print(displaySpO2);
      else lcd.print("--");
      lcd.print("%");
      
      lcd.setCursor(0, 1);
      lcd.print("Temp:");
      if (dht_valid) lcd.print(temperature, 1);
      else lcd.print("--");
      lcd.print("C");
    }
    show_page_1 = !show_page_1;

    // 2. Build JSON
    static unsigned long sequence_counter = 0;
    sequence_counter++;

    String json = "{";
    json += "\"protocol_version\":1,";
    json += "\"device_id\":\"SC-001\",";
    json += "\"sequence\":" + String(sequence_counter) + ",";
    json += "\"timestamp\":" + String(millis()) + ",";
    
    // MAX30102 — null when no valid reading, never fake
    bool hr_ok   = max_valid && finger_detected && displayBPMValid;
    bool spo2_ok = max_valid && finger_detected && displaySpO2Valid;
    json += spo2_ok ? ("\"spo2\":" + String(displaySpO2) + ",") : "\"spo2\":null,";
    json += "\"spo2_valid\":" + String(spo2_ok ? "true" : "false") + ",";
    json += "\"spo2_quality\":" + String(displaySpO2Quality, 2) + ",";
    
    json += hr_ok   ? ("\"bpm\":"  + String(displayBPM)  + ",") : "\"bpm\":null,";
    json += "\"bpm_valid\":" + String(hr_ok ? "true" : "false") + ",";
    json += "\"bpm_quality\":" + String(displayBPMQuality, 2) + ",";


    // DHT11
    if (dht_valid) {
      json += "\"temperature\":" + String(temperature, 1) + ",";
      json += "\"temperature_valid\":true,";
      json += "\"humidity\":" + String(humidity, 1) + ",";
      json += "\"humidity_valid\":true,";
    } else {
      json += "\"temperature\":null,";
      json += "\"temperature_valid\":false,";
      json += "\"humidity\":null,";
      json += "\"humidity_valid\":false,";
    }

    // ADXL345
    if (adxl_valid) {
      json += "\"mems_x\":" + String(xValue, 1) + ",";
      json += "\"mems_y\":" + String(yValue, 1) + ",";
      json += "\"mems_z\":" + String(zValue, 1) + ",";
      json += "\"mems_valid\":true,";
    } else {
      json += "\"mems_x\":null,";
      json += "\"mems_y\":null,";
      json += "\"mems_z\":null,";
      json += "\"mems_valid\":false,";
    }

    // pH
    if (ph_valid) {
      json += "\"ph\":" + String(ph_value, 2) + ",";
      json += "\"ph_valid\":true,";
    } else {
      json += "\"ph\":null,";
      json += "\"ph_valid\":false,";
    }

    // LDR
    if (ldr_valid) {
      json += "\"ldr\":" + String(ldrValue) + ",";
      json += "\"ldr_valid\":true,";
    } else {
      json += "\"ldr\":null,";
      json += "\"ldr_valid\":false,";
    }
    
    // GPS
    json += "\"gps_valid\":" + String(gps.location.isValid() ? "true" : "false") + ",";
    json += "\"gps_lat\":" + LAT + ",";
    json += "\"gps_lon\":" + LON + ",";
    json += "\"gps_satellites\":" + String(gps.satellites.isValid() ? gps.satellites.value() : 0);
    
    json += "}";
    Serial.println(json);
  }

  // ---------- RECEIVE ML RESULT OR COMMANDS FROM PYTHON ----------
  if (Serial.available()) {
    String data = Serial.readStringUntil('\n');
    data.trim();
    
    if (data.startsWith("CAL_PH:")) {
      int commaIdx = data.indexOf(',');
      if (commaIdx != -1) {
        float v4 = data.substring(7, commaIdx).toFloat();
        float v7 = data.substring(commaIdx + 1).toFloat();
        if (v4 > 0 && v7 > 0 && v4 != v7) {
          ph_calibration.voltage4 = v4;
          ph_calibration.voltage7 = v7;
          ph_calibration.magic = 0x5048;
          EEPROM.put(EEPROM_PH_ADDR, ph_calibration);
          Serial.println("[pH] Calibration updated and saved to EEPROM");
        } else {
          Serial.println("[pH] Calibration failed: invalid voltages");
        }
      }
    } else if (data.indexOf("a") != -1 && data.indexOf("b") != -1) {
      int spo2Alert = data.substring(data.indexOf("a") + 1, data.indexOf("b")).toInt();
      int bpmAlert = data.substring(data.indexOf("b") + 1, data.indexOf("c")).toInt();
      int tempAlert = data.substring(data.indexOf("c") + 1, data.indexOf("d")).toInt();
      int memsAlert = data.substring(data.indexOf("d") + 1, data.indexOf("e")).toInt();
      int phAlert = data.substring(data.indexOf("e") + 1, data.indexOf("f")).toInt();
      int ldrAlert = data.substring(data.indexOf("f") + 1, data.indexOf("g")).toInt();

      if (spo2Alert == 0) {
        lcd.clear(); lcd.print("SpO2 Abnormal"); sendSMS("SpO2 Abnormal"); delay(2000);
      } else if (bpmAlert == 0) {
        lcd.clear(); lcd.print("BPM Abnormal"); sendSMS("BPM Abnormal"); delay(2000);
      } else if (tempAlert == 0) {
        lcd.clear(); lcd.print("Temp Abnormal"); sendSMS("Temp Abnormal"); delay(2000);
      } else if (memsAlert == 0) {
        lcd.clear(); lcd.print("Fall Detected"); sendSMS("Fall Detected"); delay(2000);
      } else if (phAlert == 0) {
        lcd.clear(); lcd.print("pH Abnormal"); sendSMS("ph Abnormal"); delay(2000);
      } else if (ldrAlert == 0) {
        lcd.clear(); lcd.print("Milk Adultration"); sendSMS("Milk mixed water"); delay(2000);
      }
    }
  }
}

static void smartDelay(unsigned long ms) {
  unsigned long start = millis();
  do {
    while (GPS_SERIAL.available())
      gps.encode(GPS_SERIAL.read());
  } while (millis() - start < ms);
}

void sendSMS(String msg) {
  if (millis() - last_sms_time < SMS_COOLDOWN && last_sms_time != 0) {
    Serial.println("{\"event\":\"sms_delivery\",\"status\":\"cooldown\",\"message\":\"Skipped due to cooldown\"}");
    return;
  }
  
  GSM_SERIAL.println("AT"); delay(1000);
  GSM_SERIAL.println("AT+CMGF=1"); delay(500);
  GSM_SERIAL.print("AT+CMGS=\"");
  GSM_SERIAL.print(SMS_PHONE_NUMBER);
  GSM_SERIAL.println("\""); delay(500);
  
  GSM_SERIAL.print("ALERT: ");
  GSM_SERIAL.println(msg);
  GSM_SERIAL.print("CATTLE ID: ");
  GSM_SERIAL.println(CATTLE_ID);
  
  if (gps.location.isValid()) {
    GSM_SERIAL.println("Location: https://maps.google.com/?q=" + LAT + "," + LON);
  } else {
    GSM_SERIAL.println("Location: GPS signal lost.");
  }
  
  GSM_SERIAL.write(26); delay(5000);
  
  last_sms_time = millis();
  
  // Record SMS delivery attempt
  Serial.print("{\"event\":\"sms_delivery\",\"status\":\"attempted\",\"message\":\"");
  Serial.print(msg);
  Serial.println("\"}");
}
