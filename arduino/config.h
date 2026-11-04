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

// ==============================================================================
// Compile-time Pin Safeguards: Pins 0 & 1 (RX0/TX0) are strictly reserved
// for ATmega16U2/CH340 USB Serial, avrdude bootloader flashing, and PC comms.
// No sensor or module may use digital Pin 0 or Pin 1 on Arduino Mega 2560.
// ==============================================================================
#if defined(DHTPIN) && (DHTPIN == 0 || DHTPIN == 1)
  #error "CONFLICT: DHTPIN cannot be assigned to Pin 0 or 1! Reserved for USB Serial."
#endif
#if defined(HX711_DOUT_PIN) && (HX711_DOUT_PIN == 0 || HX711_DOUT_PIN == 1)
  #error "CONFLICT: HX711_DOUT_PIN cannot be assigned to Pin 0 or 1! Reserved for USB Serial."
#endif
#if defined(HX711_SCK_PIN) && (HX711_SCK_PIN == 0 || HX711_SCK_PIN == 1)
  #error "CONFLICT: HX711_SCK_PIN cannot be assigned to Pin 0 or 1! Reserved for USB Serial."
#endif
#if defined(GPS_GSM_RX) && (GPS_GSM_RX == 0 || GPS_GSM_RX == 1)
  #error "CONFLICT: GPS_GSM_RX cannot be assigned to Pin 0 or 1! Reserved for USB Serial."
#endif
#if defined(GPS_GSM_TX) && (GPS_GSM_TX == 0 || GPS_GSM_TX == 1)
  #error "CONFLICT: GPS_GSM_TX cannot be assigned to Pin 0 or 1! Reserved for USB Serial."
#endif

#endif
