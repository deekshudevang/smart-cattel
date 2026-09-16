#ifndef SENSOR_MANAGER_H
#define SENSOR_MANAGER_H

#include <Arduino.h>

struct SensorData {
  String cattle_id;
  int spo2;
  int bpm;
  float temperature;
  float humidity;
  float mems_x;
  float mems_y;
  float mems_z;
  float ph;
  int ldr;
};

#endif
