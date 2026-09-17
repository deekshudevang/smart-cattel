#ifndef SERIAL_MANAGER_H
#define SERIAL_MANAGER_H
#include <Arduino.h>
#include "../sensors/sensor_data.h"
#include "gps_manager.h"

inline void sendSensorDataJSON(SensorData data) {
    String json = "{";
    json += "\"cattle_id\":\"" + data.cattle_id + "\",";
    json += "\"timestamp\":" + String(millis()) + ","; // Mocking unix timestamp with millis for now
    json += "\"spo2\":" + String(data.spo2) + ",";
    json += "\"bpm\":" + String(data.bpm) + ",";
    json += "\"temperature\":" + String(data.temperature, 1) + ",";
    json += "\"humidity\":" + String(data.humidity, 1) + ",";
    json += "\"mems_x\":" + String(data.mems_x, 2) + ",";
    json += "\"mems_y\":" + String(data.mems_y, 2) + ",";
    json += "\"mems_z\":" + String(data.mems_z, 2) + ",";
    json += "\"ph\":" + String(data.ph) + ",";
    json += "\"ldr\":" + String(data.ldr) + ",";
    json += "\"gps\":{";
    json += "\"lat\":" + current_lat + ",";
    json += "\"lng\":" + current_lon;
    json += "}";
    json += "}";
    Serial.println(json);
}
#endif
