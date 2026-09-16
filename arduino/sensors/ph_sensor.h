#ifndef PH_SENSOR_H
#define PH_SENSOR_H
#include "../config.h"
inline String phData = "";
inline String current_ph = "0.0";

inline void setupPH() {
    PH_SERIAL.begin(9600);
}
inline void readPH() {
    while (PH_SERIAL.available()) {
        char c = PH_SERIAL.read();
        if (c != '\n' && c != '\r') {
            phData += c;
        } else {
            if (phData.length() > 0) {
                int p1 = phData.indexOf("PH:");
                int p2 = phData.indexOf(",");
                if (p1 != -1 && p2 != -1) {
                    current_ph = phData.substring(p1 + 3, p2);
                }
                phData = "";
            }
        }
    }
}
#endif
