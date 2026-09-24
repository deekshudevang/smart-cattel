import requests
import time
import json
import serial
import threading
import sys

URL = "http://localhost:8000/api/sensor/data"
PORT = "COM3"  # Default port, change if necessary
BAUDRATE = 9600

def read_from_serial():
    print(f"Connecting to Arduino on {PORT}...")
    try:
        ser = serial.Serial(PORT, BAUDRATE, timeout=1)
        time.sleep(2) # Wait for arduino reset
        print(f"Connected to {PORT}. Waiting for data...")
    except serial.SerialException as e:
        print(f"Failed to connect to Arduino on {PORT}: {e}")
        print("Please check your connection and port name.")
        sys.exit(1)

    try:
        while True:
            if ser.in_waiting > 0:
                line = ser.readline().decode('utf-8', errors='ignore').strip()
                if line:
                    if line.startswith('{'):
                        try:
                            data = json.loads(line)
                            
                            # Ensure cattle_id exists, fallback to default if not provided by arduino
                            if "cattle_id" not in data:
                                data["cattle_id"] = "CATTLE-001"
                                
                            try:
                                response = requests.post(URL, json=data)
                                if response.status_code == 200:
                                    print(f"[ARDUINO] Sent data: {json.dumps(data)}")
                                else:
                                    print(f"Failed to send data. Status code: {response.status_code}")
                            except requests.exceptions.RequestException as e:
                                print(f"Error connecting to server: {e}")
                                
                        except json.JSONDecodeError:
                            print(f"Invalid JSON from Arduino: {line}")
                    else:
                        print(f"Unknown serial data: {line}")
            time.sleep(0.1)
    except KeyboardInterrupt:
        print("\nStopped reading from serial.")
    finally:
        ser.close()

if __name__ == "__main__":
    print(f"Starting hardware data bridge to {URL}...")
    read_from_serial()
