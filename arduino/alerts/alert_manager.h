#ifndef ALERT_MANAGER_H
#define ALERT_MANAGER_H
#include <Arduino.h>
#include "../display/lcd_manager.h"
#include "../communication/gsm_manager.h"

inline void handleAlerts() {
    if (Serial.available()) {
        String data = Serial.readStringUntil('\n');
        // Simple JSON parse or fallback for phase 1. Assuming Phase 1 Python sends JSON.
        // For simplicity, we just look for keywords in the response.
        if (data.indexOf("abnormal") != -1) {
            String msg = "Health Alert!";
            if(data.indexOf("SpO2")!=-1) msg = "SpO2 Abnormal";
            else if(data.indexOf("BPM")!=-1) msg = "BPM Abnormal";
            else if(data.indexOf("temp")!=-1) msg = "Temp Abnormal";
            else if(data.indexOf("ph")!=-1) msg = "pH Abnormal";
            else if(data.indexOf("ldr")!=-1) msg = "Milk Adultration";
            else if(data.indexOf("mems")!=-1) msg = "Fall Detected";
            
            displayAlert(msg);
            sendSMS(msg);
        }
    }
}
#endif
