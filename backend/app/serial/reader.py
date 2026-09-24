import serial
import serial.tools.list_ports
import time
import json
import threading
from datetime import datetime

class SerialReader:
    def __init__(self, fallback_port="COM4", baudrate=9600):
        self.fallback_port = fallback_port
        self.baudrate = baudrate
        self.ser = None
        self.running = False
        self.on_data_callback = None
        
        self.port = None
        self.last_data_received = None
        self.status = "disconnected"

    def detect_arduino_port(self):
        ports = list(serial.tools.list_ports.comports())
        if not ports:
            return None
        
        print("\n[ARDUINO]")
        print("Available Ports:")
        arduino_port = None
        
        for p in ports:
            print(p.device)
            # Simple heuristic for Arduino (often CH340, CP210x, or 'Arduino' in description)
            if "Arduino" in p.description or "CH340" in p.description or "CP210x" in p.description or "Serial" in p.description:
                if not arduino_port:
                    arduino_port = p.device
        
        if arduino_port:
            print(f"\nArduino detected on: {arduino_port}")
            return arduino_port
            
        print(f"\nNo obvious Arduino found. Defaulting to first available or fallback.")
        return ports[0].device if ports else self.fallback_port

    def connect(self):
        while not self.ser and self.running:
            self.port = self.detect_arduino_port()
            if not self.port:
                print("Scanning serial ports...")
                time.sleep(2)
                continue
                
            print(f"Connecting to {self.port}...")
            try:
                self.ser = serial.Serial(self.port, self.baudrate, timeout=1)
                time.sleep(2) # Wait for arduino reset
                self.status = "connected"
                print(f"Connected successfully to {self.port}")
                print("Receiving sensor data...")
                self._print_status_box()
            except serial.SerialException:
                print(f"Failed to connect to {self.port}. Retrying in 2 seconds...")
                self.status = "disconnected"
                self.ser = None
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

    def _print_status_box(self):
        status_text = "CONNECTED" if self.status == "connected" else "DISCONNECTED"
        time_str = self.last_data_received.strftime("%H:%M:%S") if self.last_data_received else "Not Available"
        print("\n================================")
        print("ARDUINO CONNECTION")
        print("================================")
        print(f"Port: {self.port if self.port else 'None'}")
        print(f"Status: {status_text}")
        if status_text == "CONNECTED":
            print(f"Baud Rate: {self.baudrate}")
        print(f"Last Data Received: {time_str}")
        print("================================\n")

    def _format_terminal_data(self, data: dict):
        time_str = datetime.now().strftime("%H:%M:%S")
        print(f"\n[{self.port}] Arduino connected")
        print(f"\n[{time_str}] SENSOR DATA")
        
        hr = data.get("bpm", "N/A")
        spo2 = data.get("spo2", "N/A")
        temp = data.get("temperature", "N/A")
        ph = data.get("ph", "N/A")
        ldr = data.get("ldr", "N/A")
        mems_x = data.get("mems_x", "N/A")
        mems_y = data.get("mems_y", "N/A")
        mems_z = data.get("mems_z", "N/A")

        print(f"Heart Rate : {hr} BPM")
        print(f"SpO2       : {spo2} %")
        print(f"Temperature: {temp} C")
        print(f"pH         : {ph}")
        print(f"LDR        : {ldr}")
        print(f"MEMS X     : {mems_x}")
        print(f"MEMS Y     : {mems_y}")
        print(f"MEMS Z     : {mems_z}\n")

    def _read_loop(self):
        self.connect()
        while self.running:
            if not self.ser:
                self.connect()
                continue

            try:
                # readline() blocks up to `timeout` seconds (set to 1s in connect())
                # so we don't need in_waiting — that check caused missed/dropped data
                line = self.ser.readline().decode('utf-8', errors='ignore').strip()
                if not line:
                    continue  # timeout with no data — loop again
                if line.startswith('{'):
                    try:
                        data = json.loads(line)
                        self.last_data_received = datetime.now()
                        self._format_terminal_data(data)
                        if self.on_data_callback:
                            self.on_data_callback(data)
                    except json.JSONDecodeError:
                        print(f"[{self.port}] Invalid JSON: {line}")
                else:
                    # Print raw Arduino log lines
                    print(f"[{self.port}] {line}")
            except serial.SerialException:
                print(f"[{self.port}] Connection lost")
                self.status = "disconnected"
                self._print_status_box()
                self.ser = None
            except Exception as e:
                print(f"Serial Error: {e}")

    def send_alert(self, alert_json):
        if self.ser and self.status == "connected":
            try:
                self.ser.write((alert_json + "\n").encode())
            except:
                pass
                
    def get_status(self):
        return {
            "connected": self.status == "connected",
            "port": self.port,
            "baudRate": self.baudrate,
            "lastDataReceived": self.last_data_received.isoformat() if self.last_data_received else None,
            "status": self.status
        }
