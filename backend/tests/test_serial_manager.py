import pytest
from unittest.mock import patch, MagicMock
from app.serial.manager import SerialManager
import serial

class MockPortInfo:
    def __init__(self, device, description, manufacturer="Arduino", vid=0x2341, pid=0x0043, serial_number="12345", hwid="USB VID:PID"):
        self.device = device
        self.description = description
        self.manufacturer = manufacturer
        self.vid = vid
        self.pid = pid
        self.serial_number = serial_number
        self.hwid = hwid

def test_detect_configured_arduino():
    manager = SerialManager(fallback_port="COM6")
    
    with patch('serial.tools.list_ports.comports') as mock_comports:
        mock_comports.return_value = [
            MockPortInfo("COM6", "Arduino Uno"),
            MockPortInfo("COM7", "Unknown Device")
        ]
        port_info = manager.detect_arduino()
        assert port_info is not None
        assert port_info.device == "COM6"

def test_reject_unrelated_configured_port():
    manager = SerialManager(fallback_port="COM6")
    
    with patch('serial.tools.list_ports.comports') as mock_comports:
        mock_comports.return_value = [
            MockPortInfo("COM6", "Unknown", manufacturer=None),
            MockPortInfo("COM7", "Arduino Uno")
        ]
        port_info = manager.detect_arduino()
        # Should reject COM6 because it's unknown, and auto-detect COM7
        assert port_info is not None
        assert port_info.device == "COM7"

def test_no_valid_arduino():
    manager = SerialManager(fallback_port="COM6")
    with patch('serial.tools.list_ports.comports') as mock_comports:
        mock_comports.return_value = [
            MockPortInfo("COM6", "Unknown", manufacturer=None)
        ]
        assert manager.detect_arduino() is None

def test_reconnect_to_validated_device():
    manager = SerialManager(fallback_port="COM6")
    manager.port = "COM6"
    manager.device_info = {"vid": 0x2341, "pid": 0x0043}
    
    with patch('serial.tools.list_ports.comports') as mock_comports:
        # Same device still there
        mock_comports.return_value = [
            MockPortInfo("COM6", "Arduino Uno", vid=0x2341, pid=0x0043)
        ]
        
        with patch('serial.Serial') as mock_serial:
            assert manager.connect() is True
            assert manager.port == "COM6"

def test_reconnect_device_changed():
    manager = SerialManager(fallback_port="COM6")
    manager.port = "COM6"
    manager.device_info = {"vid": 0x2341, "pid": 0x0043}
    
    with patch('serial.tools.list_ports.comports') as mock_comports:
        # COM6 is now something else, Arduino moved to COM7
        mock_comports.return_value = [
            MockPortInfo("COM6", "Unknown", vid=0x1111, pid=0x2222),
            MockPortInfo("COM7", "Arduino Uno", vid=0x2341, pid=0x0043)
        ]
        
        with patch('serial.Serial') as mock_serial:
            assert manager.connect() is True
            assert manager.port == "COM7"

def test_connect_access_denied():
    manager = SerialManager(fallback_port="COM6")
    
    with patch('serial.tools.list_ports.comports') as mock_comports:
        mock_comports.return_value = [
            MockPortInfo("COM6", "Arduino Uno")
        ]
        with patch('serial.Serial') as mock_serial:
            mock_serial.side_effect = serial.SerialException("Access is denied")
            assert manager.connect() is False
            assert manager.errors == 1

def test_duplicate_start():
    manager = SerialManager()
    manager.running = True
    
    mock_thread = MagicMock()
    mock_thread.is_alive.return_value = True
    manager._thread = mock_thread
    
    with patch('threading.Thread') as mock_thread_class:
        manager.start(lambda x: None)
        mock_thread_class.assert_not_called()

def test_graceful_shutdown():
    manager = SerialManager()
    manager.running = True
    mock_thread = MagicMock()
    manager._thread = mock_thread
    
    mock_ser = MagicMock()
    mock_ser.is_open = True
    manager.ser = mock_ser
    
    manager.stop()
    
    assert manager.running is False
    mock_thread.join.assert_called_once()
    mock_ser.close.assert_called_once()
    assert manager.ser is None
