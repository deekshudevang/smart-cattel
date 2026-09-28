# Configuration Guide

The Smart Cattle Health Monitoring backend requires specific environment variables to function correctly. We use `pydantic-settings` to manage and validate these.

## Required Environment Variables

You must create a `.env` file in the `backend/` directory or pass these variables through the environment:

- `SERIAL_PORT`: The COM port or TTY path where the Arduino is connected (e.g., `COM6` or `/dev/ttyUSB0`).
- `JWT_SECRET`: A strong secret key used for signing JWT access tokens.
- `SMS_PHONE_NUMBER`: Phone number for sending SMS alerts (e.g., Twilio).
- `ADMIN_USERNAME`: The username for the initial admin account.
- `ADMIN_PASSWORD`: The password for the initial admin account.

## Optional Variables (Defaults)

- `ENVIRONMENT`: "development" (or "production")
- `DATABASE_URL`: "sqlite:///./smart_cattle.db"
- `SERIAL_BAUDRATE`: 9600
- `HARDWARE_MODE`: "real" (or "simulation")
- `ALLOW_SIMULATION`: false
- `API_HOST`: "0.0.0.0"
- `API_PORT`: 8000
- `CORS_ORIGINS`: "*"
- `JWT_ALGORITHM`: "HS256"
- `ACCESS_TOKEN_EXPIRE_MINUTES`: 30

## Running the Application

1. Copy `.env.example` to `.env`.
2. Fill in the required variables in `.env`.
3. Run the application:
   ```bash
   uvicorn app.main:app --host 0.0.0.0 --port 8000 --reload
   ```

## Production Considerations

- Set `ENVIRONMENT=production`.
- Use a strong, randomly generated `JWT_SECRET`.
- Change `ADMIN_USERNAME` and `ADMIN_PASSWORD` from simple defaults.
- Limit `CORS_ORIGINS` to the exact URL of the frontend (e.g., `https://my-dashboard.com`).
- Disable `ALLOW_SIMULATION`.
