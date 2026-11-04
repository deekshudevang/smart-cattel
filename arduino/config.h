#ifndef CONFIG_H
#define CONFIG_H

#define CATTLE_ID "CATTLE-001"
#define LDR_PIN A2
#define DHTPIN 42
#define DHTTYPE DHT11
#define PH_SERIAL Serial3
#define GPS_GSM_RX 51
#define GPS_GSM_TX 52
#define SMS_PHONE_NUMBER "+1234567890"

// HX711 Load Cell Pin Configuration (Arduino Mega 2560 safe GPIOs)
#define HX711_DOUT_PIN 44
#define HX711_SCK_PIN 46

#endif
