#ifndef LDR_SENSOR_H
#define LDR_SENSOR_H
#include "../config.h"
inline int current_ldr = 0;
inline void readLDR() {
    current_ldr = analogRead(LDR_PIN);
}
#endif
