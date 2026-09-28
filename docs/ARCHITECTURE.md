# Architecture Overview

This document outlines the architecture of the Smart Cattle Health Monitoring system.

## High-Level Components

1. **Hardware / IoT Layer**
   - Arduino-based hardware node with sensors (MAX30102, MLX90614, DHT11, ADXL335, pH sensor, LDR).
   - Transmits data via serial connection.

2. **Backend (FastAPI)**
   - Receives serial data from the hardware.
   - Saves `SensorReading` records to the SQLite database.
   - Runs predictions using the ML models (Random Forest) for SpO2, Heart Rate, Temp, Activity (MEMS), pH, and Light.
   - Exposes REST APIs for cow management, milk and feed records, alerts, and analytics.
   - Provides a WebSocket endpoint for real-time dashboard updates.

3. **Frontend (React)**
   - Dashboard displaying real-time metrics, historical trends, and cow profiles.
   - Interfaces via REST APIs and WebSockets.

## Data Flow

1. Hardware reads sensor data.
2. Serial manager in backend consumes data, parses it, and calls `SensorService`.
3. `SensorService` invokes `PredictionService` to get status (normal/abnormal).
4. Data is stored in DB.
5. If abnormal, an alert is triggered (and optionally an SMS is sent).
6. Data is broadcasted via WebSockets to connected UI clients.
7. Backend sends control signals back to Arduino (e.g., to turn on/off buzzers).

## Machine Learning Models

The `PredictionService` loads models from `ml/models/`. These models evaluate sensor values to determine if they are in normal ranges. Fall detection is primarily based on MEMS thresholding.

## Design Patterns

- **Service Layer Pattern**: Business logic (processing sensor data, creating alerts) is handled in `services/`.
- **Repository Pattern**: Extracted ORM DB queries (partially implemented in routes, could be further decoupled).
- **Dependency Injection**: FastAPI `Depends(get_db)` and OAuth2 injection.

