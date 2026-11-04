#ifndef HX711_READER_H
#define HX711_READER_H

#include <Arduino.h>
#include <EEPROM.h>
#include "filters.h"

#define EEPROM_WEIGHT_ADDR 48
#define WEIGHT_EEPROM_MAGIC 0x5754 // 'WT' (Weight Tracker)

struct WeightCalibration {
  long tare_offset;
  float cal_factor; // counts per kg
  int magic;
};

class HX711Reader {
public:
  byte dout_pin;
  byte sck_pin;

  WeightCalibration calibration;
  MovingAverageFilter<8> averager;

  long raw_value;
  float filtered_raw;
  float weight_kg;

  bool is_connected;
  bool is_valid;
  bool is_stable;
  bool is_calibrated;

  unsigned long last_ready_time;
  unsigned long last_read_time;
  unsigned long sample_interval_ms;

  // Stability detection window
  static const int STABILITY_WINDOW = 6;
  float recent_weights[STABILITY_WINDOW];
  int weight_idx;
  int weight_count;
  float stability_threshold_kg;

public:
  HX711Reader() :
    dout_pin(44),
    sck_pin(46),
    raw_value(0),
    filtered_raw(0.0f),
    weight_kg(NAN),
    is_connected(false),
    is_valid(false),
    is_stable(false),
    is_calibrated(false),
    last_ready_time(0),
    last_read_time(0),
    sample_interval_ms(100), // ~10Hz sampling (HX711 standard rate)
    weight_idx(0),
    weight_count(0),
    stability_threshold_kg(0.5f) {
  }

  void begin(byte dout, byte sck) {
    dout_pin = dout;
    sck_pin = sck;

    pinMode(sck_pin, OUTPUT);
    digitalWrite(sck_pin, LOW);
    pinMode(dout_pin, INPUT_PULLUP);

    loadCalibration();

    averager.reset();
    weight_idx = 0;
    weight_count = 0;
    last_ready_time = millis();
    last_read_time = millis();
  }

  void loadCalibration() {
    EEPROM.get(EEPROM_WEIGHT_ADDR, calibration);
    if (calibration.magic == WEIGHT_EEPROM_MAGIC && calibration.cal_factor > 0.0001f && !isnan(calibration.cal_factor)) {
      is_calibrated = true;
      Serial.println(F("[HX711] Loaded calibration from EEPROM"));
      Serial.print(F("[HX711] Tare Offset=")); Serial.print(calibration.tare_offset);
      Serial.print(F(" Cal Factor=")); Serial.println(calibration.cal_factor, 4);
    } else {
      // Default initial calibration (will be calibrated by user)
      calibration.tare_offset = 0;
      calibration.cal_factor = 21000.0f; // Typical initial factor for 200-500kg shear beam/load cell
      calibration.magic = WEIGHT_EEPROM_MAGIC;
      is_calibrated = false;
      Serial.println(F("[HX711] Uncalibrated (Default factor initialized)"));
    }
  }

  void saveCalibration() {
    calibration.magic = WEIGHT_EEPROM_MAGIC;
    EEPROM.put(EEPROM_WEIGHT_ADDR, calibration);
    Serial.println(F("[HX711] Calibration saved to EEPROM"));
  }

  bool isReady() {
    return (digitalRead(dout_pin) == LOW);
  }

  // Non-blocking readiness check (alias)
  bool is_ready() {
    return isReady();
  }

  // Non-blocking single reading of 24 bits + 1 pulse (Channel A, Gain 128)
  bool readRawNonBlocking(long &out_val) {
    if (!isReady()) {
      return false;
    }

    unsigned long value = 0;
    uint8_t data[3] = { 0 };

    // Pulse SCK to read 24 bits (MSB first)
    // Keep interrupts disabled only during pin toggling for accurate timing
    noInterrupts();
    for (int j = 2; j >= 0; --j) {
      for (char i = 7; i >= 0; --i) {
        digitalWrite(sck_pin, HIGH);
        delayMicroseconds(1);
        if (digitalRead(dout_pin)) {
          data[j] |= (1 << i);
        }
        digitalWrite(sck_pin, LOW);
        delayMicroseconds(1);
      }
    }

    // 25th pulse for Channel A, Gain 128
    digitalWrite(sck_pin, HIGH);
    delayMicroseconds(1);
    digitalWrite(sck_pin, LOW);
    delayMicroseconds(1);
    interrupts();

    // Reconstruct 24-bit value
    value = ((unsigned long)data[2] << 16) | ((unsigned long)data[1] << 8) | (unsigned long)data[0];

    // Sign extend negative 24-bit two's complement into 32-bit signed long
    if (value & 0x800000) {
      out_val = (long)(value | 0xFF000000);
    } else {
      out_val = (long)value;
    }

    // Check for electrical saturation or disconnected bus (e.g. constant 0x7FFFFF or 0xFFFFFF)
    if (value == 0x7FFFFF || value == 0xFFFFFF || value == 0x000000) {
      // Possible floating/disconnected lines
      return false;
    }

    return true;
  }

  void update(unsigned long now_ms) {
    if (now_ms - last_read_time < sample_interval_ms) {
      return;
    }

    long sample = 0;
    if (readRawNonBlocking(sample)) {
      last_ready_time = now_ms;
      last_read_time = now_ms;
      is_connected = true;

      raw_value = sample;
      filtered_raw = averager.process((float)raw_value);

      if (is_calibrated && calibration.cal_factor > 0.0001f) {
        float computed_weight = (filtered_raw - (float)calibration.tare_offset) / calibration.cal_factor;
        
        // Zero-clamp minor negative noise (-0.2kg to 0kg clamped to 0.0kg)
        if (computed_weight < 0.0f && computed_weight >= -0.5f) {
          computed_weight = 0.0f;
        }

        // Physiological cattle bounds: 0kg to 1500kg
        if (computed_weight >= 0.0f && computed_weight <= 1500.0f) {
          weight_kg = computed_weight;
          is_valid = true;
        } else {
          weight_kg = NAN;
          is_valid = false;
        }
      } else {
        weight_kg = NAN;
        is_valid = false;
      }

      // Update stability window
      if (is_valid && !isnan(weight_kg)) {
        recent_weights[weight_idx] = weight_kg;
        weight_idx = (weight_idx + 1) % STABILITY_WINDOW;
        if (weight_count < STABILITY_WINDOW) weight_count++;

        if (weight_count >= STABILITY_WINDOW) {
          float w_min = recent_weights[0];
          float w_max = recent_weights[0];
          for (int i = 1; i < STABILITY_WINDOW; ++i) {
            if (recent_weights[i] < w_min) w_min = recent_weights[i];
            if (recent_weights[i] > w_max) w_max = recent_weights[i];
          }
          is_stable = ((w_max - w_min) <= stability_threshold_kg);
        } else {
          is_stable = false;
        }
      } else {
        is_stable = false;
      }
    } else {
      // Check disconnection timeout (> 3000ms with no DOUT falling edge)
      if (now_ms - last_ready_time > 3000) {
        is_connected = false;
        is_valid = false;
        is_stable = false;
        weight_kg = NAN;
      }
    }
  }

  bool tare() {
    if (!is_connected) {
      Serial.println(F("[HX711] Tare failed: sensor disconnected"));
      return false;
    }
    calibration.tare_offset = (long)filtered_raw;
    saveCalibration();
    Serial.print(F("[HX711] Tared successfully! New Tare Offset="));
    Serial.println(calibration.tare_offset);
    weight_count = 0;
    return true;
  }

  bool calibrateWithReference(float reference_kg) {
    if (!is_connected) {
      Serial.println(F("[HX711] Calibration failed: sensor disconnected"));
      return false;
    }
    if (reference_kg <= 0.05f) {
      Serial.println(F("[HX711] Calibration failed: reference weight must be > 0.05 kg"));
      return false;
    }
    float raw_delta = filtered_raw - (float)calibration.tare_offset;
    if (abs(raw_delta) < 100.0f) {
      Serial.println(F("[HX711] Calibration failed: negligible signal delta from tare"));
      return false;
    }

    calibration.cal_factor = raw_delta / reference_kg;
    is_calibrated = true;
    saveCalibration();

    Serial.print(F("[HX711] Calibrated successfully! Ref="));
    Serial.print(reference_kg);
    Serial.print(F("kg, CalFactor="));
    Serial.println(calibration.cal_factor, 4);
    weight_count = 0;
    return true;
  }

  bool isValid() const { return is_valid; }
  bool isStable() const { return is_stable; }
  bool isCalibrated() const { return is_calibrated; }
  bool isConnected() const { return is_connected; }
  float getWeightKg() const { return weight_kg; }
  long getRawValue() const { return raw_value; }

  const char* getStatusString() const {
    if (!is_connected) return "DISCONNECTED";
    if (!is_calibrated) return "UNCALIBRATED";
    if (!is_valid) return "INVALID";
    if (is_stable) return "STABLE";
    return "UNSTABLE";
  }
};

#endif
