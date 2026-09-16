#ifndef LCD_MANAGER_H
#define LCD_MANAGER_H
#include <LiquidCrystal.h>
inline LiquidCrystal lcd(30, 32, 34, 36, 38, 40);

inline void setupLCD() {
    lcd.begin(16, 2);
    lcd.print("Smart Cattle");
    delay(1000);
    lcd.clear();
}
inline void displayNormal(int spo2, int bpm, float temp, float x, String ph, int ldr) {
    lcd.clear();
    lcd.setCursor(0, 0);
    lcd.print("S:"); lcd.print(spo2); lcd.print("% B:"); lcd.print(bpm);
    lcd.setCursor(0, 1);
    lcd.print("T:"); lcd.print((int)temp); lcd.print(" X:"); lcd.print(x, 1);
}
inline void displayAlert(String msg) {
    lcd.clear();
    lcd.print(msg);
}
#endif
