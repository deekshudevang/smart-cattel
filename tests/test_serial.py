import pytest
import json
import threading
import time
from unittest.mock import MagicMock, patch
from app.serial.manager import SerialManager
import serial

class MockPort:
    def __init__(self, device, description, hwid="", vid=None, pid=None, serial_number="", manufacturer=""):
        self.device = device
        self.description = description
        self.hwid = hwid
        self.vid = vid
        self.pid = pid
        self.serial_number = serial_number
        self.manufacturer = manufacturer

@pytest.fixture
def mock_serial():
    with patch("app.serial.manager.serial.Serial") as mock:
        mock_instance = MagicMock()
        mock_instance.is_open = True
        mock.return_value = mock_instance
        yield mock

@pytest.fixture
def mock_list_ports():
    with patch("app.serial.manager.serial.tools.list_ports.comports") as mock:
        yield mock

def test_port_detection(mock_list_ports):
    # Test valid Arduino port detection
    mock_list_ports.return_value = [
        MockPort("COM1", "Bluetooth link"),
        MockPort("COM3", "USB-SERIAL CH340"),
    ]
    manager = SerialManager(fallback_port="COM99")
    detected = manager.detect_arduino()
    assert detected is not None
    assert detected.device == "COM3"

    # Test fallback when no arduino found but ports exist
    mock_list_ports.return_value = [
        MockPort("COM1", "Bluetooth link"),
    ]
    detected = manager.detect_arduino()
    assert detected is None

    # Test fallback when no ports exist
    mock_list_ports.return_value = []
    detected = manager.detect_arduino()
    assert detected is None

def test_connection(mock_list_ports, mock_serial):
    mock_list_ports.return_value = [MockPort("COM3", "USB-SERIAL CH340")]
    manager = SerialManager()
    
    assert manager.connect() is True
    assert manager.connected is True
    assert manager.port == "COM3"
    assert manager.ser is not None

def test_connection_failure(mock_list_ports, mock_serial):
    mock_list_ports.return_value = [MockPort("COM3", "USB-SERIAL CH340")]
    mock_serial.side_effect = serial.SerialException("Access denied")
    
    manager = SerialManager()
    assert manager.connect() is False
    assert manager.connected is False
    assert manager.errors == 1

def test_disconnect(mock_list_ports, mock_serial):
    mock_list_ports.return_value = [MockPort("COM3", "USB-SERIAL CH340")]
    manager = SerialManager()
    manager.connect()
    
    manager.disconnect()
    assert manager.connected is False
    assert manager.ser is None

def test_reconnect(mock_list_ports, mock_serial):
    mock_list_ports.return_value = [MockPort("COM3", "USB-SERIAL CH340")]
    manager = SerialManager()
    manager.reconnect()
    
    assert manager.reconnect_count == 1
    assert manager.connected is True

def test_malformed_packets_and_nulls(mock_list_ports, mock_serial):
    mock_list_ports.return_value = [MockPort("COM3", "USB-SERIAL CH340")]
    
    packets = [
        b'{"invalid": json', 
        b'{"device_id": "SC-001"}',
        b'{"device_id": "SC-001", "sequence": 1, "timestamp": 1000, "bpm": null, "spo2": null, "temperature": null, "ph": null, "ldr": 0, "mems_x": 0, "mems_y": 0, "mems_z": 0}',
        b'{"device_id": "SC-001", "sequence": 2, "timestamp": 1000, "bpm": -10, "spo2": 150, "temperature": 100, "ph": 15, "ldr": 0, "mems_x": 0, "mems_y": 0, "mems_z": 0}',
    ]
    
    def mock_readline():
        if not hasattr(mock_readline, "idx"):
            mock_readline.idx = 0
        if mock_readline.idx < len(packets):
            res = packets[mock_readline.idx]
            mock_readline.idx += 1
            return res
        time.sleep(0.5)
        return b''
        
    mock_serial.return_value.readline.side_effect = mock_readline
    
    manager = SerialManager()
    callback_mock = MagicMock()
    with patch("app.serial.manager.time.sleep"):
        manager.start(callback_mock)
        time.sleep(1)
        
    manager.stop()
    assert manager.packets_rejected >= 1

def test_duplicate_connection(mock_list_ports, mock_serial):
    mock_list_ports.return_value = [MockPort("COM3", "USB-SERIAL CH340")]
    mock_serial.return_value.readline.side_effect = lambda: time.sleep(0.1) or b''
    
    manager = SerialManager()
    
    callback_mock = MagicMock()
    manager.start(callback_mock)
    first_thread = manager._thread
    
    # attempt to start again
    manager.start(callback_mock)
    second_thread = manager._thread
    
    # Should not create a new thread
    assert first_thread is second_thread
    
    manager.stop()
