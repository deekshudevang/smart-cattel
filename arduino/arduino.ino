#include <DHT.h>
#include <Wire.h>
#include "filters.h"
#include <MAX3010x.h>
#include <TinyGPS++.h>
#include <LiquidCrystal.h>
#include <SoftwareSerial.h>
#include <Adafruit_Sensor.h>
#include <Adafruit_ADXL345_U.h>

//---------------- GPS + GSM ----------------
SoftwareSerial uart(51, 52);  // RX,TX
TinyGPSPlus gps;

String LAT = "0.000000";
String LON = "0.000000";
// ---------- pH UART Sensor ----------

#define PH_SERIAL Serial3
#define LDR_PIN A2
int ldrValue = 0;

String phData = "";
String phValue = "";

// ---------- DHT11 ----------
#define DHTPIN 42
#define DHTTYPE DHT11

DHT dht(DHTPIN, DHTTYPE);

float temperature = 0;

// Sensor (adjust to your sensor type)
MAX30105 sensor;
const auto kSamplingRate = sensor.SAMPLING_RATE_800SPS;
const float kSamplingFrequency = 400.0;

// Finger Detection Threshold and Cooldown
const unsigned long kFingerThreshold = 5000;
const unsigned int kFingerCooldownMs = 100;

// Edge Detection Threshold (decrease for MAX30100)
const float kEdgeThreshold = -800.0;

// Filters
const float kLowPassCutoff = 5.0;
const float kHighPassCutoff = 0.5;

// Averaging
const bool kEnableAveraging = false;
const int kAveragingSamples = 5;
const int kSampleThreshold = 5;

// RS, EN, D4, D5, D6, D7
LiquidCrystal lcd(30, 32, 34, 36, 38, 40);
Adafruit_ADXL345_Unified accel = Adafruit_ADXL345_Unified(12345);

float xValue = 0;
int displayBPM = 0;
int displaySpO2 = 0;

void setup() {
  Serial.begin(9600);
  PH_SERIAL.begin(9600);  // UART pH Sensor
  uart.begin(9600);
  Serial.println("Sensor initialized");
  if (sensor.begin() && sensor.setSamplingRate(kSamplingRate)) {
    lcd.begin(16, 2);
    lcd.print("MAX30102");
    lcd.setCursor(0, 1);
    lcd.print("Initializing");
    Serial.println("Sensor initialized");
    lcd.clear();
    lcd.print("Sensor Ready");
    delay(2000);
    lcd.clear();
  } else {
    Serial.println("Sensor not found");
    while (1)
      ;
  }
  if (!accel.begin()) {
    Serial.println("ADXL345 Not Found");
    while (1)
      ;
  }

  accel.setRange(ADXL345_RANGE_2_G);
  dht.begin();
}

// Filter Instances
LowPassFilter low_pass_filter_red(kLowPassCutoff, kSamplingFrequency);
LowPassFilter low_pass_filter_ir(kLowPassCutoff, kSamplingFrequency);
HighPassFilter high_pass_filter(kHighPassCutoff, kSamplingFrequency);
Differentiator differentiator(kSamplingFrequency);
MovingAverageFilter<kAveragingSamples> averager_bpm;
MovingAverageFilter<kAveragingSamples> averager_r;
MovingAverageFilter<kAveragingSamples> averager_spo2;

// Statistic for pulse oximetry
MinMaxAvgStatistic stat_red;
MinMaxAvgStatistic stat_ir;

// R value to SpO2 calibration factors
// See https://www.maximintegrated.com/en/design/technical-documents/app-notes/6/6845.html
float kSpO2_A = 1.5958422;
float kSpO2_B = -34.6596622;
float kSpO2_C = 112.6898759;

// Timestamp of the last heartbeat
long last_heartbeat = 0;

// Timestamp for finger detection
long finger_timestamp = 0;
bool finger_detected = false;


// Last diff to detect zero crossing
float last_diff = NAN;
bool crossed = false;
long crossed_time = 0;

void loop() {

  ldrValue = analogRead(LDR_PIN);
  auto sample = sensor.readSample(100);
  sensors_event_t event;
  accel.getEvent(&event);

  xValue = event.acceleration.x;
  temperature = dht.readTemperature();

  smartDelay(100);

  if (gps.location.isValid()) {
    LAT = String(gps.location.lat(), 6);
    LON = String(gps.location.lng(), 6);
  }

  if (isnan(temperature)) {
    temperature = 0;
  }

  float current_value_red = sample.red;
  float current_value_ir = sample.ir;


  // ---------- Read pH Sensor ----------
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
        }

        phData = "";
      }
    }
  }


  // Detect Finger using raw sensor value
  if (sample.red > kFingerThreshold) {
    if (millis() - finger_timestamp > kFingerCooldownMs) {
      finger_detected = true;
    }
  } else {
    // Reset values if the finger is removed
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

    // Statistics for pulse oximetry
    stat_red.process(current_value_red);
    stat_ir.process(current_value_ir);

    // Heart beat detection using value for red LED
    float current_value = high_pass_filter.process(current_value_red);
    float current_diff = differentiator.process(current_value);

    // Valid values?
    if (!isnan(current_diff) && !isnan(last_diff)) {

      // Detect Heartbeat - Zero-Crossing
      if (last_diff > 0 && current_diff < 0) {
        crossed = true;
        crossed_time = millis();
      }

      if (current_diff > 0) {
        crossed = false;
      }

      // Detect Heartbeat - Falling Edge Threshold
      if (crossed && current_diff < kEdgeThreshold) {
        if (last_heartbeat != 0 && crossed_time - last_heartbeat > 100) {
          // Show Results
          int bpm = 60000 / (crossed_time - last_heartbeat);
          float rred = (stat_red.maximum() - stat_red.minimum()) / stat_red.average();
          float rir = (stat_ir.maximum() - stat_ir.minimum()) / stat_ir.average();
          float r = rred / rir;
          float spo2 = kSpO2_A * r * r + kSpO2_B * r + kSpO2_C;
          if (spo2 > 100)
            spo2 = 100;

          if (spo2 < 0)
            spo2 = 0;

          if (bpm > 50 && bpm < 250) {
            lcd.clear();
            displayBPM = bpm;
            displaySpO2 = (int)spo2;
            lcd.setCursor(0, 0);
            lcd.print("S:");
            lcd.print(displaySpO2);
            lcd.print("% ");

            lcd.print("B:");
            lcd.print(displayBPM);
            lcd.print("");
            lcd.print("T:");
            lcd.print((int)temperature);
            lcd.setCursor(0, 1);

            lcd.print("X:");
            lcd.print(xValue, 1);
            lcd.print(" ");
            lcd.print(phValue);
            delay(2000);
            lcd.clear();
            lcd.setCursor(0, 0);
            lcd.print("LDR :");
            lcd.print(ldrValue);
            delay(2000);
            lcd.clear();
            lcd.setCursor(0, 0);
            lcd.print("LAT:");
            lcd.print(LAT.substring(0, 8));

            lcd.setCursor(0, 1);
            lcd.print("LON:");
            lcd.print(LON.substring(0, 8));

            delay(3000);

            // ---------- SEND DATA TO PYTHON ----------
            String tx =
              "a" + String(displaySpO2) + "b" + String(displayBPM) + "c" + String(temperature, 1) + "d" + String(xValue, 1) + "e" + phValue + "f" + String(ldrValue) + "g";

            Serial.println(tx);
            delay(2000);
          }
        }
        // Reset statistic
        stat_red.reset();
        stat_ir.reset();
      }

      crossed = false;
      last_heartbeat = crossed_time;
    }

    last_diff = current_diff;
  }


  // ---------- RECEIVE ML RESULT FROM PYTHON ----------
  if (Serial.available()) {
    String data = Serial.readStringUntil('\n');

    int spo2Alert = data.substring(data.indexOf("a") + 1, data.indexOf("b")).toInt();
    int bpmAlert = data.substring(data.indexOf("b") + 1, data.indexOf("c")).toInt();
    int tempAlert = data.substring(data.indexOf("c") + 1, data.indexOf("d")).toInt();
    int memsAlert = data.substring(data.indexOf("d") + 1, data.indexOf("e")).toInt();
    int phAlert = data.substring(data.indexOf("e") + 1, data.indexOf("f")).toInt();
    int ldrAlert = data.substring(data.indexOf("f") + 1, data.indexOf("g")).toInt();

    if (spo2Alert == 0) {
      lcd.clear();
      lcd.print("SpO2 Abnormal");
      sendSMS("SpO2 Abnormal");
      delay(2000);
    }

    if (bpmAlert == 0) {
      lcd.clear();
      lcd.print("BPM Abnormal");
      sendSMS("BPM Abnormal");
      delay(2000);
    }

    if (tempAlert == 0) {
      lcd.clear();
      lcd.print("Temp Abnormal");
      sendSMS("Temp Abnormal");
      delay(2000);
    }

    if (memsAlert == 0) {
      lcd.clear();
      lcd.print("Fall Detected");
      sendSMS("Fall Detected");
      delay(2000);
    }

    if (phAlert == 0) {
      lcd.clear();
      lcd.print("pH Abnormal");
      sendSMS("ph Abnormal");
      delay(2000);
    }

    if (ldrAlert == 0) {
      lcd.clear();
      lcd.print("Milk Adultration");
      sendSMS("Milk mixed water");
      delay(2000);
    }
  }
}
static void smartDelay(unsigned long ms) {
  unsigned long start = millis();

  do {
    while (uart.available())
      gps.encode(uart.read());

  } while (millis() - start < ms);
}
void sendSMS(String msg) {
  uart.println("AT");
  delay(1000);

  uart.println("AT+CMGF=1");
  delay(500);

  uart.println("AT+CMGS=\"+916361038158\"");
  delay(500);

  uart.println(msg);

  uart.println("https://maps.google.com/?q=" + LAT + "," + LON);

  uart.write(26);

  delay(5000);
}