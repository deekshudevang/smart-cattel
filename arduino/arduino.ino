#include <DHT.h>
#include <Wire.h>
#include "filters.h"
#include <MAX3010x.h>
#include <TinyGPS++.h>
#include <LiquidCrystal.h>
#include <SoftwareSerial.h>
#include <Adafruit_Sensor.h>
#include <Adafruit_ADXL345_U.h>

//---------------- GPS + GSM (Using Mega Hardware Serials) ----------------
#define GPS_SERIAL Serial1 // Pins 19 (RX1), 18 (TX1)
#define GSM_SERIAL Serial2 // Pins 17 (RX2), 16 (TX2)
TinyGPSPlus gps;

String LAT = "0.000000";
String LON = "0.000000";

// ---------- pH UART Sensor ----------
#define PH_SERIAL Serial3
#define LDR_PIN A2
int ldrValue = 0;
bool ldr_valid = false;

String phData = "";
String phValue = "";
bool ph_valid = false;

// ---------- DHT11 ----------
#define DHTPIN 42
#define DHTTYPE DHT11

DHT dht(DHTPIN, DHTTYPE);

float temperature = 0;
float humidity = 0;
bool dht_valid = false;

// ---------- MAX30102 ----------
MAX30105 sensor;
const auto kSamplingRate = sensor.SAMPLING_RATE_800SPS;
const float kSamplingFrequency = 400.0;
bool max_valid = false;

// Finger Detection Threshold and Cooldown
const unsigned long kFingerThreshold = 5000;
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

void setup() {
  Serial.begin(9600);
  PH_SERIAL.begin(9600);
  GPS_SERIAL.begin(9600);
  GSM_SERIAL.begin(9600);
  
  lcd.begin(16, 2);
  lcd.clear();
  lcd.setCursor(0, 0);
  lcd.print("SMART CATTLE");
  lcd.setCursor(0, 1);
  lcd.print("WAITING DATA...");
  lcd_showing_waiting = true;

  if (sensor.begin() && sensor.setSamplingRate(kSamplingRate)) {
    max_valid = true;
    Serial.println("MAX30102 initialized");
  } else {
    max_valid = false;
    Serial.println("MAX30102 not found");
  }
  
  if (accel.begin()) {
    adxl_valid = true;
    accel.setRange(ADXL345_RANGE_2_G);
    Serial.println("ADXL345 initialized");
  } else {
    adxl_valid = false;
    Serial.println("ADXL345 not found");
  }

  dht.begin();
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
  temperature = dht.readTemperature();
  humidity = dht.readHumidity();
  dht_valid = !isnan(temperature) && !isnan(humidity);

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
  while (PH_SERIAL.available()) {
    char c = PH_SERIAL.read();
    Serial.write(c);
    if (c != '\n' && c != '\r') {
      phData += c;
    } else {
      if (phData.length() > 0) {
        int p1 = phData.indexOf("PH:");
        int p2 = phData.indexOf(",");
        if (p1 != -1 && p2 != -1) {
          phValue = phData.substring(p1 + 3, p2);
          ph_valid = true;
        }
        phData = "";
      }
    }
  }

  // ---------- MAX30102 Read ----------
  if (max_valid) {
    auto sample = sensor.readSample(50);
    float current_value_red = sample.red;
    float current_value_ir = sample.ir;

    if (sample.red > kFingerThreshold) {
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
    }

    if (finger_detected) {
      current_value_red = low_pass_filter_red.process(current_value_red);
      current_value_ir = low_pass_filter_ir.process(current_value_ir);
      stat_red.process(current_value_red);
      stat_ir.process(current_value_ir);

      float current_value = high_pass_filter.process(current_value_red);
      float current_diff = differentiator.process(current_value);

      if (!isnan(current_diff) && !isnan(last_diff)) {
        if (last_diff > 0 && current_diff < 0) {
          crossed = true;
          crossed_time = millis();
        }
        if (current_diff > 0) {
          crossed = false;
        }
        if (crossed && current_diff < kEdgeThreshold) {
          if (last_heartbeat != 0 && crossed_time - last_heartbeat > 100) {
            int bpm = 60000 / (crossed_time - last_heartbeat);
            float rred = (stat_red.maximum() - stat_red.minimum()) / stat_red.average();
            float rir = (stat_ir.maximum() - stat_ir.minimum()) / stat_ir.average();
            float r = rred / rir;
            float spo2 = kSpO2_A * r * r + kSpO2_B * r + kSpO2_C;
            
            if (spo2 > 100) spo2 = 100;
            if (spo2 < 0) spo2 = 0;

            if (bpm > 50 && bpm < 250) {
              displayBPM = bpm;
              displaySpO2 = (int)spo2;
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
    lcd_showing_waiting = false;
    lcd.clear();
    lcd.setCursor(0, 0);
    
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
    if (ph_valid && phValue.length() > 0) lcd.print(phValue);
    else lcd.print("--");
    
    lcd.print(" L:");
    if (ldr_valid) lcd.print(ldrValue);
    else lcd.print("--");
    
    lcd.print(" X:");
    if (adxl_valid) lcd.print(xValue, 1);
    else lcd.print("--");

    // 2. Build JSON
    String json = "{";
    json += "\"cattle_id\":\"CATTLE-001\",";
    
    // MAX30102
    if (max_valid && finger_detected && displaySpO2 > 0 && displayBPM > 0) {
      json += "\"spo2\":" + String(displaySpO2) + ",";
      json += "\"bpm\":" + String(displayBPM) + ",";
      json += "\"spo2_valid\":true,";
    } else {
      json += "\"spo2\":null,";
      json += "\"bpm\":null,";
      json += "\"spo2_valid\":false,";
    }

    // DHT11
    if (dht_valid) {
      json += "\"temperature\":" + String(temperature, 1) + ",";
      json += "\"humidity\":" + String(humidity, 1) + ",";
      json += "\"temperature_valid\":true,";
    } else {
      json += "\"temperature\":null,";
      json += "\"humidity\":null,";
      json += "\"temperature_valid\":false,";
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
    if (ph_valid && phValue.length() > 0) {
      json += "\"ph\":" + phValue + ",";
      json += "\"ph_valid\":true,";
    } else {
      json += "\"ph\":null,";
      json += "\"ph_valid\":false,";
    }

    // LDR
    if (ldr_valid) {
      json += "\"ldr\":" + String(ldrValue);
      json += ",\"ldr_valid\":true";
    } else {
      json += "\"ldr\":null";
      json += ",\"ldr_valid\":false";
    }
    
    json += "}";
    Serial.println(json);
  }

  // ---------- RECEIVE ML RESULT FROM PYTHON ----------
  if (Serial.available()) {
    String data = Serial.readStringUntil('\n');
    
    if (data.indexOf("a") != -1 && data.indexOf("b") != -1) {
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
  GSM_SERIAL.println("AT"); delay(1000);
  GSM_SERIAL.println("AT+CMGF=1"); delay(500);
  GSM_SERIAL.println("AT+CMGS=\"+916361038158\""); delay(500);
  GSM_SERIAL.println(msg);
  GSM_SERIAL.println("https://maps.google.com/?q=" + LAT + "," + LON);
  GSM_SERIAL.write(26); delay(5000);
}