import serial
import time
import json
import threading

class SerialReader:
    def __init__(self, port="COM4", baudrate=9600):
        self.port = port
        self.baudrate = baudrate
        self.ser = None
        self.running = False
        self.on_data_callback = None

    def connect(self):
        while not self.ser and self.running:
            try:
                self.ser = serial.Serial(self.port, self.baudrate, timeout=1)
                time.sleep(2) # Wait for arduino reset
                print("Serial Connected")
            except serial.SerialException:
                print("Failed to connect. Retrying in 2 seconds...")
                time.sleep(2)

    def start(self, callback):
        self.on_data_callback = callback
        self.running = True
        self.thread = threading.Thread(target=self._read_loop, daemon=True)
        self.thread.start()

    def stop(self):
        self.running = False
        if self.ser:
            self.ser.close()

    def _read_loop(self):
        self.connect()
        while self.running:
            if not self.ser:
                self.connect()
                continue
            
            try:
                if self.ser.in_waiting > 0:
                    line = self.ser.readline().decode('utf-8', errors='ignore').strip()
                    if line:
                        if line.startswith('{'):
                            try:
                                data = json.loads(line)
                                if self.on_data_callback:
                                    self.on_data_callback(data)
                            except json.JSONDecodeError:
                                print(f"Invalid JSON: {line}")
                        else:
                            print(f"Unknown serial data: {line}")
            except serial.SerialException:
                print("Serial connection lost.")
                self.ser = None
            except Exception as e:
                print(f"Serial Error: {e}")

    def send_alert(self, alert_json):
        if self.ser:
            try:
                self.ser.write((alert_json + "\n").encode())
            except:
                pass
