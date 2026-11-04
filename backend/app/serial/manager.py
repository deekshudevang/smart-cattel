import serial
import serial.tools.list_ports
import time
import json
import threading
import logging
from datetime import datetime
from typing import Optional, List, Dict, Any, Callable
from enum import Enum
from app.schemas.telemetry import TelemetryValidator

class ConnectionState(str, Enum):
    DISCONNECTED = "DISCONNECTED"
    DETECTED = "DETECTED"
    CONNECTING = "CONNECTING"
    CONNECTED = "CONNECTED"
    ERROR = "ERROR"

logger = logging.getLogger(__name__)

class SerialManager:
    _instance = None
    _init_lock = threading.Lock()

    def __new__(cls, *args, **kwargs):
        if cls._instance is None:
            with cls._init_lock:
                if cls._instance is None:
                    cls._instance = super(SerialManager, cls).__new__(cls)
                    cls._instance._initialized = False
        return cls._instance

    def __init__(self, fallback_port: str = "COM13", baudrate: int = 9600):
        if getattr(self, '_initialized', False):
            return
            
        self._initialized = True
        self.fallback_port = fallback_port
        self.baudrate = baudrate
        self.ser: Optional[serial.Serial] = None
        
        # Thread control
        self.running = False
        self.paused = False
        self._thread: Optional[threading.Thread] = None
        self._thread_lock = threading.Lock()
        
        # State tracking
        self.port: Optional[str] = None
        self.device_info: Optional[Dict[str, Any]] = None
        self.connected = False
        self.connect_time: Optional[datetime] = None
        self.last_line_time: Optional[datetime] = None
        self.last_packet: Optional[datetime] = None
        self.packets_received = 0
        self.packets_rejected = 0
        self.reconnect_count = 0
        self.errors = 0
        self.last_error: Optional[str] = None
        
        self.state = ConnectionState.DISCONNECTED
        self.backoff_time = 1.0
        self.max_backoff = 60.0
        
        # Diagnostics
        self.bytes_received = 0
        self.lines_received = 0
        self.last_diag_time = 0.0
        self.raw_buffer = bytearray()
        
        self.validator = TelemetryValidator()
        self.on_data_callback: Optional[Callable[[Dict[str, Any]], None]] = None

    def list_ports(self) -> List[str]:
        """Returns a list of available COM ports."""
        return [p.device for p in serial.tools.list_ports.comports()]

    def _is_valid_arduino(self, p) -> bool:
        if not p.description or p.description == "n/a" or "Unknown" in p.description:
            return False
        
        if "Bluetooth" in p.description:
            return False
            
        # Check by common Arduino identifiers
        arduino_keywords = ["Arduino", "CH340", "CP210x", "FTDI", "USB Serial"]
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
            
        # First check the fallback port but ONLY if it is valid
        for p in ports:
            if p.device == self.fallback_port:
                if self._is_valid_arduino(p):
                    logger.info(f"Configured port {self.fallback_port} validated as Arduino.")
                    self.state = ConnectionState.DETECTED
                    return p
                    
        # Auto-detect Arduino
        for p in ports:
            if self._is_valid_arduino(p):
                logger.info(f"Auto-detected Arduino on: {p.device} ({p.description})")
                self.state = ConnectionState.DETECTED
                return p
                
        logger.warning("No valid Arduino found.")
        return None

    def connect(self) -> bool:
        """Attempt to connect to the Arduino."""
        if self.connected and self.ser and self.ser.is_open:
            self.state = ConnectionState.CONNECTED
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
                self.state = ConnectionState.DISCONNECTED

        if not self.port:
            port_info = self.detect_arduino()
            if not port_info:
                logger.warning("No Arduino devices found during connect.")
                self.state = ConnectionState.DISCONNECTED
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
        self.state = ConnectionState.CONNECTING
        try:
            self.ser = serial.Serial(self.port, self.baudrate, timeout=1)
            # Avoid toggling DTR aggressively to prevent resetting the MCU on every connect
            try:
                self.ser.dtr = True
                self.ser.rts = True
            except Exception:
                pass
            
            # Wait briefly for serial stream to settle, checking if we're shutting down
            for _ in range(4):
                if self._thread is not None and not self.running:
                    self.ser.close()
                    self.ser = None
                    self.state = ConnectionState.DISCONNECTED
                    return False
                time.sleep(0.05)
                
            self.connected = True
            self.paused = False
            self.last_error = None
            self.state = ConnectionState.CONNECTED
            self.backoff_time = 1.0  # Reset backoff on successful connect
            self.connect_time = datetime.now()
            logger.info(
                f"[SERIAL CONNECTED] timestamp={self.connect_time.isoformat()} "
                f"port={self.port} baud={self.baudrate} "
                f"hwid={self.device_info.get('hwid') if self.device_info else 'n/a'}"
            )
            return True
        except serial.SerialException as e:
            self.errors += 1
            error_str = str(e)
            if "Access is denied" in error_str or "PermissionError" in error_str:
                self.last_error = "SERIAL_PORT_IN_USE"
                logger.error(f"[SERIAL ERROR] timestamp={datetime.now().isoformat()} Access denied to {self.port}. Is another program using it?")
            else:
                self.last_error = "CONNECTION_FAILED"
                logger.error(f"[SERIAL ERROR] timestamp={datetime.now().isoformat()} Failed to connect to {self.port}: {e}")
            self.connected = False
            self.ser = None
            self.state = ConnectionState.ERROR
            return False

    def disconnect(self, reason: str = "normal"):
        """Disconnect from the Arduino."""
        was_connected = self.connected
        self.connected = False
        self.paused = True
        self.state = ConnectionState.DISCONNECTED
        if self.ser and self.ser.is_open:
            try:
                self.ser.close()
            except Exception as e:
                logger.error(f"[SERIAL CLOSE ERROR] timestamp={datetime.now().isoformat()} Error closing serial port: {e}")
        self.ser = None
        now = datetime.now()
        duration_s = (now - self.connect_time).total_seconds() if self.connect_time and was_connected else 0.0
        self.connect_time = None
        if self.port:
            logger.info(
                f"[SERIAL DISCONNECTED] timestamp={now.isoformat()} "
                f"port={self.port} reason={reason} duration_s={duration_s:.1f} reconnects={self.reconnect_count}"
            )

    def reconnect(self):
        """Disconnect and reconnect with exponential backoff."""
        now = datetime.now()
        logger.info(f"[SERIAL RECONNECT] timestamp={now.isoformat()} port={self.port} attempt={self.reconnect_count + 1} backoff={self.backoff_time}s")
        self.reconnect_count += 1
        self.disconnect(reason="reconnect")
        
        # Sleep for 1 second before attempting re-connect
        for _ in range(5):
            if not getattr(self, 'running', True):
                return
            time.sleep(0.2)
            
        if getattr(self, 'running', True):
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
        self.disconnect(reason="shutdown")
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
                logger.error("[SERIAL WRITE TIMEOUT] Timeout writing to serial.")
            except serial.SerialException as e:
                self.errors += 1
                logger.error(f"[SERIAL WRITE ERROR] Failed to write to serial: {e}")
                self.reconnect()
            except Exception as e:
                self.errors += 1
                logger.error(f"[SERIAL WRITE UNEXPECTED] Unexpected write error: {e}")

    def read_loop(self):
        """Background loop to read from the Arduino."""
        if not getattr(self, 'paused', False):
            self.connect()
            
        while getattr(self, 'running', True):
            if getattr(self, 'paused', False):
                time.sleep(1)
                continue
                
            if not self.connected or not self.ser:
                logger.info(f"[SERIAL WAIT RECONNECT] timestamp={datetime.now().isoformat()} Waiting {self.backoff_time}s (State: {self.state.value})...")
                sleep_time = self.backoff_time
                slept = 0.0
                while slept < sleep_time:
                    if not getattr(self, 'running', True) or getattr(self, 'paused', False):
                        break
                    time.sleep(0.5)
                    slept += 0.5
                
                self.backoff_time = min(self.max_backoff, self.backoff_time * 2)
                
                if getattr(self, 'running', True) and not getattr(self, 'paused', False):
                    self.reconnect()
                continue

            try:
                # Read any available bytes instead of blocking on readline indefinitely
                waiting = self.ser.in_waiting
                if waiting > 0:
                    raw_bytes = self.ser.read(waiting)
                    self.bytes_received += len(raw_bytes)
                    self.raw_buffer.extend(raw_bytes)
                else:
                    time.sleep(0.05)

                now = time.time()
                if now - self.last_diag_time > 5.0:
                    last_time_str = self.last_line_time.isoformat() if self.last_line_time else "never"
                    logger.info(
                        f"[SERIAL HEARTBEAT] timestamp={datetime.now().isoformat()} "
                        f"port={self.port} last_rx_line={last_time_str} "
                        f"lines={self.lines_received} bytes={self.bytes_received} "
                        f"rx_ok={self.packets_received} rx_reject={self.packets_rejected} "
                        f"buf_len={len(self.raw_buffer)}"
                    )
                    self.last_diag_time = now

                # Protect against unbounded buffer growth if no newline received
                if len(self.raw_buffer) > 16384:
                    logger.warning(f"[SERIAL BUFFER OVERFLOW] raw_buffer exceeded 16KB ({len(self.raw_buffer)} bytes) without newline. Resetting buffer.")
                    self.raw_buffer.clear()

                while b'\n' in self.raw_buffer:
                    line_bytes, self.raw_buffer = self.raw_buffer.split(b'\n', 1)
                    line = line_bytes.decode('utf-8', errors='ignore').strip()
                    
                    if not line:
                        continue

                    self.lines_received += 1
                    self.last_line_time = datetime.now()

                    if line.startswith('{'):
                        logger.debug(f"[SERIAL RAW]\n{line}")
                        try:
                            data = json.loads(line)
                            
                            # Use TelemetryValidator for rigorous parsing, checksum, and tracking
                            is_valid, packet, reason = self.validator.process(data, raw_line=line)
                            
                            if is_valid and packet:
                                self.last_packet = datetime.now()
                                self.packets_received += 1
                                if self.on_data_callback:
                                    pkt_dict = packet.model_dump()
                                    pkt_dict["source"] = "serial"
                                    pkt_dict["is_simulated"] = False
                                    pkt_dict["raw_packet"] = line
                                    pkt_dict["checksum_valid"] = True
                                    try:
                                        self.on_data_callback(pkt_dict)
                                    except Exception as cb_err:
                                        logger.error(f"[SERIAL CALLBACK ERROR] timestamp={datetime.now().isoformat()} error={cb_err}", exc_info=True)
                            else:
                                self.packets_rejected += 1
                                logger.warning(f"[SERIAL PARSE REJECTED] timestamp={datetime.now().isoformat()} reason={reason} line={line[:100]}")

                        except json.JSONDecodeError as jde:
                            self.packets_rejected += 1
                            self.errors += 1
                            logger.warning(f"[SERIAL PARSE ERROR] timestamp={datetime.now().isoformat()} error={jde} line={line[:100]}")
                        except Exception as parse_err:
                            self.packets_rejected += 1
                            self.errors += 1
                            logger.error(f"[SERIAL PARSE EXCEPTION] timestamp={datetime.now().isoformat()} error={parse_err} line={line[:100]}", exc_info=True)
                    else:
                        logger.info(f"[SERIAL BOOT/INFO] timestamp={datetime.now().isoformat()} [{self.port}] {line}")

            except serial.SerialException as e:
                self.errors += 1
                logger.error(f"[SERIAL ERROR] timestamp={datetime.now().isoformat()} Connection lost on {self.port}: {e}")
                self.connected = False
            except Exception as e:
                self.errors += 1
                logger.error(f"[SERIAL UNEXPECTED] timestamp={datetime.now().isoformat()} Loop error: {e}", exc_info=True)
                time.sleep(1)

    def get_status(self) -> Dict[str, Any]:
        """Return the current status of the serial manager."""
        return {
            "state": self.state.value,
            "port": self.port,
            "device": self.device_info,
            "baud_rate": self.baudrate,
            "connected": self.connected,
            "paused": self.paused,
            "last_packet": self.last_packet.isoformat() if self.last_packet else None,
            "last_line_time": self.last_line_time.isoformat() if self.last_line_time else None,
            "connect_time": self.connect_time.isoformat() if self.connect_time else None,
            "backoff_time": self.backoff_time,
            "packets_received": self.packets_received,
            "packets_rejected": self.packets_rejected,
            "reconnect_count": self.reconnect_count,
            "errors": self.errors,
            "error_detail": self.last_error
        }
