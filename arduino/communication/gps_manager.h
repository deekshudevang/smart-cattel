#ifndef GPS_MANAGER_H
#define GPS_MANAGER_H
#include <TinyGPS++.h>
inline TinyGPSPlus gps;
inline String current_lat = "0.000000";
inline String current_lon = "0.000000";

inline void updateGPS() {
    if (gps.location.isValid()) {
        current_lat = String(gps.location.lat(), 6);
        current_lon = String(gps.location.lng(), 6);
    }
}
#endif
