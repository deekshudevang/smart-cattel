#ifndef STEP_COUNTER_H
#define STEP_COUNTER_H

#include <Arduino.h>
#include <EEPROM.h>

#define EEPROM_STEP_ADDR 16
#define STEP_EEPROM_MAGIC 0x5354 // 'ST' (Step Tracker)

struct StepEEPROMData {
  uint32_t lifetime_steps;
  uint32_t today_steps;
  uint16_t day_identifier;
  int magic;
};

class CowStepCounter {
public:
  // Calibration thresholds for bovine gait
  // Acceleration in m/s^2 (1g ≈ 9.806 m/s^2)
  float step_threshold_high;          // Upper threshold for step peak detection (e.g. 1.8 m/s^2)
  float step_threshold_low;           // Lower threshold for hysteresis re-arming (e.g. 0.5 m/s^2)
  float noise_floor;                  // Noise suppression floor (e.g. 0.35 m/s^2)
  unsigned long min_step_interval_ms; // Refractory period: minimum time between steps (380ms)

  uint32_t steps_today;
  uint32_t steps_lifetime;
  bool step_detected_this_cycle;

private:
  float gravity_est;
  bool armed;
  unsigned long last_step_time;
  unsigned long last_eeprom_save_time;
  uint32_t last_saved_steps;
  uint16_t current_day_id;

public:
  CowStepCounter() :
    step_threshold_high(1.8f),
    step_threshold_low(0.5f),
    noise_floor(0.35f),
    min_step_interval_ms(380),
    steps_today(0),
    steps_lifetime(0),
    step_detected_this_cycle(false),
    gravity_est(9.806f),
    armed(true),
    last_step_time(0),
    last_eeprom_save_time(0),
    last_saved_steps(0),
    current_day_id(0) {}

  void begin() {
    loadFromEEPROM();
  }

  void loadFromEEPROM() {
    StepEEPROMData data;
    EEPROM.get(EEPROM_STEP_ADDR, data);
    if (data.magic == STEP_EEPROM_MAGIC) {
      steps_lifetime = data.lifetime_steps;
      steps_today = data.today_steps;
      current_day_id = data.day_identifier;
      last_saved_steps = steps_lifetime;
    } else {
      steps_lifetime = 0;
      steps_today = 0;
      current_day_id = 0;
      saveToEEPROM(true);
    }
  }

  void saveToEEPROM(bool force = false) {
    unsigned long now = millis();
    // Save to EEPROM if forced or (steps changed AND at least 10 seconds since last write)
    // This balances immediate reboot recovery with EEPROM write endurance.
    if (force || ((steps_lifetime != last_saved_steps) && (now - last_eeprom_save_time >= 10000))) {
      StepEEPROMData data;
      data.lifetime_steps = steps_lifetime;
      data.today_steps = steps_today;
      data.day_identifier = current_day_id;
      data.magic = STEP_EEPROM_MAGIC;
      EEPROM.put(EEPROM_STEP_ADDR, data);
      last_saved_steps = steps_lifetime;
      last_eeprom_save_time = now;
    }
  }

  void resetToday() {
    steps_today = 0;
    saveToEEPROM(true);
  }

  void syncCounts(uint32_t today, uint32_t lifetime) {
    steps_today = today;
    steps_lifetime = lifetime;
    saveToEEPROM(true);
  }

  // Process sample: ax, ay, az in m/s^2.
  // Returns true if a step is detected on this sample.
  bool processSample(float ax, float ay, float az, unsigned long now_ms) {
    step_detected_this_cycle = false;

    // 1. Calculate 3D acceleration magnitude
    float mag = sqrt(ax * ax + ay * ay + az * az);

    // 2. Continuous dynamic gravity estimation (exponential moving average, alpha = 0.05)
    gravity_est = 0.95f * gravity_est + 0.05f * mag;

    // 3. Dynamic acceleration with gravity component removed
    float dyn_accel = mag - gravity_est;

    // 4. Suppress sensor baseline noise
    if (abs(dyn_accel) < noise_floor) {
      dyn_accel = 0.0f;
    }

    // 5. Hysteresis peak detection with refractory lockout
    if (armed && dyn_accel > step_threshold_high) {
      if (now_ms - last_step_time >= min_step_interval_ms) {
        steps_today++;
        steps_lifetime++;
        step_detected_this_cycle = true;
        last_step_time = now_ms;
        armed = false; // De-arm until acceleration drops below hysteresis threshold
        saveToEEPROM(false);
      }
    } else if (!armed && dyn_accel < step_threshold_low) {
      armed = true; // Re-arm for the next stride
    }

    return step_detected_this_cycle;
  }
};

#endif
