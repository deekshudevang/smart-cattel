#ifndef MEMS_SENSOR_H
#define MEMS_SENSOR_H
#include <Adafruit_Sensor.h>
#include <Adafruit_ADXL345_U.h>

inline Adafruit_ADXL345_Unified accel = Adafruit_ADXL345_Unified(12345);
inline float current_mems_x = 0;
inline float current_mems_y = 0;
inline float current_mems_z = 0;

inline void setupMEMS() {
    if(!accel.begin()) {
        Serial.println("ADXL345 Not Found");
    } else {
        accel.setRange(ADXL345_RANGE_2_G);
    }
}
inline void readMEMS() {
    sensors_event_t event;
    accel.getEvent(&event);
    current_mems_x = event.acceleration.x;
    current_mems_y = event.acceleration.y;
    current_mems_z = event.acceleration.z;
}
#endif
