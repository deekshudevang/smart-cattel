# Smart Cattle Health Monitoring System

A complete real-time Smart Cattle Health Monitoring and Alert System.

## Architecture
- **Hardware**: Arduino Mega + MAX30102, ADXL345, DHT11, pH, LDR, GPS, GSM
- **Edge**: Modular C++ firmware sending JSON telemetry.
- **Backend**: FastAPI, SQLite, WebSockets, Python Serial service.
- **ML**: Scikit-Learn Random Forest models pickled and lazy-loaded.
- **Mobile**: Android Kotlin Jetpack Compose MVVM app.

## Setup
1. Upload `arduino/smart_cattle.ino` to Arduino Mega.
2. Run `python backend/app/main.py` (Ensure COM4 is connected).
3. Open Android Studio and build the `android/SmartCattle` app.

## Endpoints
- `GET /api/health`
- `GET /api/cattle/{id}/latest`
- `WS /ws/cattle/{id}`

*Built for academic prototype presentation.*
