#ifndef PULSE_SENSOR_H
#define PULSE_SENSOR_H

#include <MAX3010x.h>
#include "../filters.h"

extern MAX30105 sensor;
extern int displayBPM;
extern int displaySpO2;

void setupPulseSensor();
void processPulseSensor();

#endif
