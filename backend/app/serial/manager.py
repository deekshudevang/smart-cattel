import serial
import serial.tools.list_ports
import time
import json
import threading
import logging
from datetime import datetime
from typing import Optional, List, Dict, Any, Callable
from app.schemas.telemetry import TelemetryValidator

logger = logging.getLogger(__name__)

class SerialManager:
    def __init__(self, fallback_port: str = "COM6", baudrate: int = 9600):
        self.fallback_port = fallback_port
        self.baudrate = baudrate
        self.ser: Optional[serial.Serial] = None
        
        # Thread control
        self.running = False
        self._thread: Optional[threading.Thread] = None
        self._thread_lock = threading.Lock()
        
        # State tracking
        self.port: Optional[str] = None
        self.device_info: Optional[Dict[str, Any]] = None
        self.connected = False
        self.last_packet: Optional[datetime] = None
        self.packets_received = 0
        self.packets_rejected = 0
        self.reconnect_count = 0
        self.errors = 0
        
        self.validator = TelemetryValidator()
        self.on_data_callback: Optional[Callable[[Dict[str, Any]], None]] = None

    def list_ports(self) -> List[str]:
        """Returns a list of available COM ports."""
        return [p.device for p in serial.tools.list_ports.comports()]

    def _is_valid_arduino(self, p) -> bool:
        if not p.description or p.description == "n/a" or "Unknown" in p.description:
            return False
        
        # Check by common Arduino identifiers
        arduino_keywords = ["Arduino", "CH340", "CP210x", "FTDI", "Serial"]
        if any(kw in p.description for kw in arduino_keywords):
            return True
            
        if p.manufacturer and "Arduino" in p.manufacturer:
            return True
            
        return False

    def detect_arduino(self) -> Optional[serial.tools.list_ports_common.ListPortInfo]:
        """Automatically detect available Arduino serial ports, avoiding unknown devices."""
        ports = list(serial.tools.list_ports.comports())
        if not ports:
            return None
            
        # First try the configured fallback_port
        for p in ports:
            if p.device == self.fallback_port:
                if self._is_valid_arduino(p):
                    logger.info(f"Configured port {self.fallback_port} validated as Arduino.")
                    return p
                else:
                    logger.warning(f"Configured port {self.fallback_port} found but rejected (not Arduino).")
                    break # Don't return it, move to auto-detect
                    
        # Auto-detect Arduino
        for p in ports:
            if self._is_valid_arduino(p):
                logger.info(f"Auto-detected Arduino on: {p.device} ({p.description})")
                return p
                
        logger.warning("No valid Arduino found.")
        return None

    def connect(self) -> bool:
        """Attempt to connect to the Arduino."""
        if self.connected and self.ser and self.ser.is_open:
            return True

        if self.port and self.device_info:
            # We already have a validated port, try reconnecting to it if it still exists
            ports = list(serial.tools.list_ports.comports())
            found = False
            for p in ports:
                if p.device == self.port and p.vid == self.device_info.get("vid") and p.pid == self.device_info.get("pid"):
                    found = True
                    break
            if not found:
                # Need to re-detect
                self.port = None
                self.device_info = None

        if not self.port:
            port_info = self.detect_arduino()
            if not port_info:
                logger.warning("No Arduino devices found during connect.")
                return False
                
            self.port = port_info.device
            self.device_info = {
                "description": port_info.description,
                "hwid": port_info.hwid,
                "vid": port_info.vid,
                "pid": port_info.pid,
                "serial_number": port_info.serial_number,
                "manufacturer": port_info.manufacturer
            }

        logger.info(f"Connecting to {self.port} at {self.baudrate} baud...")
        try:
            self.ser = serial.Serial(self.port, self.baudrate, timeout=1)
            time.sleep(2)  # Wait for arduino reset
            self.connected = True
            logger.info(f"Connected successfully to {self.port}")
            return True
        except serial.SerialException as e:
            self.errors += 1
            if "Access is denied" in str(e):
                logger.error(f"Access denied to {self.port}. Is another program using it?")
            else:
                logger.error(f"Failed to connect to {self.port}: {e}")
            self.connected = False
            self.ser = None
            return False

    def disconnect(self):
        """Disconnect from the Arduino."""
        self.connected = False
        if self.ser and self.ser.is_open:
            try:
                self.ser.close()
            except Exception as e:
                logger.error(f"Error while closing serial port: {e}")
        self.ser = None
        logger.info(f"Disconnected from Arduino on {self.port}.")

    def reconnect(self):
        """Disconnect and reconnect."""
        logger.info(f"Attempting to reconnect to {self.port}...")
        self.reconnect_count += 1
        self.disconnect()
        time.sleep(2)
        self.connect()

    def start(self, callback: Callable[[Dict[str, Any]], None]):
        """Start the read loop in a background thread."""
        with self._thread_lock:
            if self.running and self._thread and self._thread.is_alive():
                logger.warning("SerialManager is already running. Preventing duplicate thread.")
                return

            self.on_data_callback = callback
            self.running = True
            self._thread = threading.Thread(target=self.read_loop, daemon=True, name="SerialManagerReader")
            self._thread.start()
            logger.info("SerialManager thread started.")

    def stop(self):
        """Gracefully stop during application shutdown."""
        logger.info("Stopping SerialManager...")
        self.running = False
        if self._thread:
            self._thread.join(timeout=3.0)
        self.disconnect()
        logger.info("SerialManager stopped.")

    def write(self, data: str):
        """Write data to the Arduino."""
        if self.ser and self.connected:
            try:
                if not data.endswith('\n'):
                    data += '\n'
                self.ser.write(data.encode('utf-8'))
            except serial.SerialTimeoutException:
                self.errors += 1
                logger.error("Timeout writing to serial.")
            except serial.SerialException as e:
                self.errors += 1
                logger.error(f"Failed to write to serial: {e}")
                self.reconnect()
            except Exception as e:
                self.errors += 1
                logger.error(f"Unexpected write error: {e}")

    def read_loop(self):
        """Background loop to read from the Arduino."""
        self.connect()
        while self.running:
            if not self.connected or not self.ser:
                time.sleep(2)
                self.reconnect()
                continue

            try:
                line = self.ser.readline().decode('utf-8', errors='ignore').strip()
                if not line:
                    continue

                if line.startswith('{'):
                    try:
                        data = json.loads(line)
                        if "bpm" in data:
                            # To be fully backwards compatible or fix up names
                            pass
                            
                        # Use TelemetryValidator for rigorous parsing and tracking
                        is_valid, packet, reason = self.validator.process(data)
                        
                        if is_valid and packet:
                            self.last_packet = datetime.now()
                            self.packets_received += 1
                            if self.on_data_callback:
                                # We pass the validated Pydantic model dictionary
                                self.on_data_callback(packet.model_dump())
                        else:
                            self.packets_rejected += 1
                            logger.warning(f"Packet rejected ({reason}): {line}")

                    except json.JSONDecodeError:
                        self.packets_rejected += 1
                        self.errors += 1
                        logger.warning(f"Invalid JSON received: {line}")
                else:
                    logger.debug(f"[{self.port}] {line}")

            except serial.SerialException as e:
                self.errors += 1
                logger.error(f"Serial connection lost: {e}")
                self.connected = False
            except Exception as e:
                self.errors += 1
                logger.error(f"Unexpected error in read loop: {e}")
                time.sleep(1)

    def get_status(self) -> Dict[str, Any]:
        """Return the current status of the serial manager."""
        return {
            "port": self.port,
            "device": self.device_info,
            "baud_rate": self.baudrate,
            "connected": self.connected,
            "last_packet": self.last_packet.isoformat() if self.last_packet else None,
            "packets_received": self.packets_received,
            "packets_rejected": self.packets_rejected,
            "reconnect_count": self.reconnect_count,
            "errors": self.errors
        }
