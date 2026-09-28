# Serial Manager

The `SerialManager` is responsible for establishing, maintaining, and recovering the connection to the Arduino hardware node. It runs a dedicated background thread to read JSON telemetry data and parse it before sending it to the rest of the application.

## Key Features

1. **Robust Detection**
   - It respects the `fallback_port` (e.g., `COM6`) if it is configured and physically validated to be an Arduino.
   - It rejects generic, unknown, or unrelated devices on the configured port.
   - If the configured port fails validation or is unavailable, it automatically searches the USB bus for an Arduino by inspecting the description, manufacturer, VID, and PID.
   
2. **Device State Tracking**
   - Once a device is validated, its USB Vendor ID (VID) and Product ID (PID) are recorded.
   - If the connection drops, it will only reconnect to a port matching the validated hardware signatures, preventing it from attaching to the wrong serial device if COM ports shift.

3. **Fault Tolerance**
   - Handles "Access is denied" errors (e.g. when another program is using the COM port).
   - Handles USB disconnects and automatic reconnects gracefully.
   - Properly terminates the background thread on application shutdown to prevent orphan threads.
   
4. **Thread Safety**
   - Uses `threading.Lock` to ensure only one reader thread is spawned at a time.
   
5. **Observability**
   - Provides a comprehensive `get_status()` method to expose:
     - Connected port and device metadata
     - Last packet time
     - Number of received and rejected (malformed) packets
     - Reconnect counts and error counts

## Usage

```python
manager = SerialManager(fallback_port="COM6", baudrate=9600)
manager.start(callback=my_data_handler)

# ... later ...
manager.stop()
```
