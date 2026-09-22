#include "config.h"
#include "filters.h"
#include "sensors/sensor_data.h"
#include "sensors/pulse_sensor.h"
#include "sensors/dht_sensor.h"
#include "sensors/mems_sensor.h"
#include "sensors/ph_sensor.h"
#include "sensors/ldr_sensor.h"
#include "display/lcd_manager.h"
#include "communication/gps_manager.h"
#include "communication/gsm_manager.h"
#include "communication/serial_manager.h"
#include "alerts/alert_manager.h"

// Pulse Oximeter globals from original code
MAX30105 sensor;
const auto kSamplingRate = sensor.SAMPLING_RATE_800SPS;
const float kSamplingFrequency = 400.0;
const unsigned long kFingerThreshold = 5000;
const unsigned int kFingerCooldownMs = 100;
const float kEdgeThreshold = -800.0;
const float kLowPassCutoff = 5.0;
const float kHighPassCutoff = 0.5;

LowPassFilter low_pass_filter_red(kLowPassCutoff, kSamplingFrequency);
LowPassFilter low_pass_filter_ir(kLowPassCutoff, kSamplingFrequency);
HighPassFilter high_pass_filter(kHighPassCutoff, kSamplingFrequency);
Differentiator differentiator(kSamplingFrequency);

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

int displayBPM = 0;
int displaySpO2 = 0;

static void smartDelay(unsigned long ms) {
    unsigned long start = millis();
    do {
        while (uart.available())
            gps.encode(uart.read());
    } while (millis() - start < ms);
}

void setup() {
    Serial.begin(9600);
    setupPH();
    setupGSM();
    setupLCD();
    setupMEMS();
    setupDHT();

    if (sensor.begin() && sensor.setSamplingRate(kSamplingRate)) {
        lcd.print("Sensor Ready");
        delay(1000);
        lcd.clear();
    } else {
        Serial.println("Sensor not found");
        while (1);
    }
}

void loop() {
    readLDR();
    readMEMS();
    readDHT();
    
    
    // Read MAX30102
    auto sample = sensor.readSample(100);
    smartDelay(100);
    updateGPS();

    readPH();

    float current_value_red = sample.red;
    float current_value_ir = sample.ir;

    if (sample.red > kFingerThreshold) {
        if (millis() - finger_timestamp > kFingerCooldownMs) {
            finger_detected = true;
        }
    } else {
        differentiator.reset();
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
            if (current_diff > 0) crossed = false;

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
                        
                        displayNormal(displaySpO2, displayBPM, 0.0, current_mems_x, current_ph, current_ldr);
                        
                        // Send JSON Data
                        SensorData sd;
                        sd.cattle_id = CATTLE_ID;
                        sd.spo2 = displaySpO2;
                        sd.bpm = displayBPM;
                        sd.temperature = current_temperature;
                        sd.humidity = current_humidity;
                        sd.mems_x = current_mems_x;
                        sd.mems_y = current_mems_y;
                        sd.mems_z = current_mems_z;
                        sd.ph = current_ph.toFloat();
                        sd.ldr = current_ldr;
                        
                        sendSensorDataJSON(sd);
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
    
    // Check for incoming alerts from Python (JSON expected in Phase 1)
    handleAlerts();
}
