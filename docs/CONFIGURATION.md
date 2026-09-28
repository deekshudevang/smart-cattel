# Configuration Guide

## Environment Variables

The project uses a `.env` file at the root of the project to manage configuration.

### Backend & General
- `ENVIRONMENT`: Environment mode (e.g. `development`, `production`). Default: `development`.
- `DATABASE_URL`: Connection string for the database.
- `JWT_SECRET`: Secret key for JWT authentication.
- `CORS_ORIGINS`: Comma-separated list of allowed origins.

### Hardware & Communication
- `SERIAL_PORT`: The serial port for Arduino communication (e.g., `COM6` on Windows, `/dev/ttyUSB0` on Linux).
- `SERIAL_BAUDRATE`: Baud rate for serial communication (default: `9600`).
- `HARDWARE_MODE`: Mode of hardware operation (`arduino` etc.).
- `ALLOW_SIMULATION`: Boolean to allow simulation data if hardware is unavailable (`true` or `false`).
- `SMS_PHONE_NUMBER`: Phone number for SMS alerts.

Ensure you create a `.env` file based on `.env.example` before running the system.
