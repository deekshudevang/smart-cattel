#ifndef DHT_SENSOR_H
#define DHT_SENSOR_H

#include <DHT.h>
#include "../config.h"

extern DHT dht;
extern float current_temperature;
extern float current_humidity;

void setupDHT();
void readDHT();

#endif
