#ifndef GSM_MANAGER_H
#define GSM_MANAGER_H
#include <SoftwareSerial.h>
#include "../config.h"
#include "gps_manager.h"
inline SoftwareSerial uart(GPS_GSM_RX, GPS_GSM_TX);

inline void setupGSM() {
    uart.begin(9600);
}
inline void sendSMS(String msg) {
    uart.println("AT");
    uart.println("AT+CMGF=1");
    uart.println("AT+CMGS=\"+916361038158\"");
    uart.println(msg);
    uart.println("Loc: " + current_lat + "," + current_lon);
    uart.write(26);
}
#endif
