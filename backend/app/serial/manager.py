import serial
import serial.tools.list_ports
import time
import json
import threading
import logging
from datetime import datetime
from typing import Optional, List, Dict, Any, Callable

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
        self.connected = False
        self.last_packet: Optional[datetime] = None
        self.packets_received = 0
        self.packets_rejected = 0
        self.reconnect_count = 0
        self.errors = 0
        
        self.on_data_callback: Optional[Callable[[Dict[str, Any]], None]] = None

    def list_ports(self) -> List[str]:
        """Returns a list of available COM ports."""
        return [p.device for p in serial.tools.list_ports.comports()]

    def detect_arduino(self) -> Optional[str]:
        """Automatically detect available Arduino serial ports."""
        ports = list(serial.tools.list_ports.comports())
        if not ports:
            return None
        
        for p in ports:
            # Simple heuristic for Arduino (CH340, CP210x, or 'Arduino' in description)
            if "Arduino" in p.description or "CH340" in p.description or "CP210x" in p.description or "Serial" in p.description:
                logger.info(f"Arduino detected on: {p.device}")
                return p.device
                
        logger.info(f"No obvious Arduino found. Defaulting to first available or fallback.")
        return ports[0].device if ports else self.fallback_port

    def connect(self) -> bool:
        """Attempt to connect to the Arduino."""
        if self.connected and self.ser and self.ser.is_open:
            return True

        self.port = self.detect_arduino()
        if not self.port:
            logger.warning("No serial ports found during connect.")
            return False

        logger.info(f"Connecting to {self.port} at {self.baudrate} baud...")
        try:
            self.ser = serial.Serial(self.port, self.baudrate, timeout=1)
            time.sleep(2)  # Wait for arduino reset
            self.connected = True
            logger.info(f"Connected successfully to {self.port}")
            return True
        except serial.SerialException as e:
            self.errors += 1
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
        logger.info("Disconnected from Arduino.")

    def reconnect(self):
        """Disconnect and reconnect."""
        logger.info("Attempting to reconnect...")
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

    def stop(self):
        """Gracefully stop during application shutdown."""
        logger.info("Stopping SerialManager...")
        self.running = False
        if self._thread:
            self._thread.join(timeout=3.0)
        self.disconnect()

    def write(self, data: str):
        """Write data to the Arduino."""
        if self.ser and self.connected:
            try:
                if not data.endswith('\n'):
                    data += '\n'
                self.ser.write(data.encode('utf-8'))
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
                self.reconnect()
                if not self.connected:
                    time.sleep(2)
                continue

            try:
                line = self.ser.readline().decode('utf-8', errors='ignore').strip()
                if not line:
                    continue

                if line.startswith('{'):
                    try:
                        data = json.loads(line)
                        if "bpm" in data:
                            data["heart_rate"] = data.pop("bpm")
                        self.last_packet = datetime.now()
                        self.packets_received += 1
                        if self.on_data_callback:
                            self.on_data_callback(data)
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
            "baud_rate": self.baudrate,
            "connected": self.connected,
            "last_packet": self.last_packet.isoformat() if self.last_packet else None,
            "packets_received": self.packets_received,
            "packets_rejected": self.packets_rejected,
            "reconnect_count": self.reconnect_count,
            "errors": self.errors
        }
