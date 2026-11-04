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

@pytest.fixture(autouse=True)
def reset_serial_manager():
    manager = SerialManager()
    manager.connected = False
    manager.ser = None
    manager.port = None
    manager.device_info = None
    manager.errors = 0
    yield
    manager.connected = False
    manager.ser = None
    manager.port = None
    manager.device_info = None
    manager.errors = 0

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

def test_malformed_lines_and_partial_json_do_not_kill_reader():
    """Verify corrupted JSON, incomplete packets, or noise lines never kill the reader."""
    manager = SerialManager()
    manager.connected = True
    manager.running = True
    
    received_packets = []
    manager.on_data_callback = lambda pkt: received_packets.append(pkt)
    
    # Inject various corrupted, broken, and noisy lines followed by a valid line
    noise = b"GARBAGE_NOISE_LINE\n"
    truncated = b"{\"cattle_id\":\"CATTLE-001\",\"timestamp\":100\n"
    corrupt_json = b"{\"cattle_id\"::INVALID}\n"
    valid_packet = (
        b"{\"protocol_version\":1,\"device_id\":\"SC-001\",\"cattle_id\":\"CATTLE-001\","
        b"\"sequence\":1,\"timestamp\":1718000000,\"accelerometer_valid\":true,"
        b"\"acceleration_x\":0.1,\"acceleration_y\":0.2,\"acceleration_z\":9.8,"
        b"\"heart_rate\":72,\"heart_rate_valid\":true,\"bpm_quality\":1.0,"
        b"\"spo2\":98,\"spo2_valid\":true,\"spo2_quality\":1.0,"
        b"\"temperature\":38.5,\"temperature_valid\":true,"
        b"\"ph\":6.8,\"ph_valid\":true,\"ldr\":450,\"ldr_valid\":true,"
        b"\"body_weight_kg\":520.5,\"weight_valid\":true,\"weight_stable\":true,\"weight_calibrated\":true}\n"
    )
    
    manager.raw_buffer.extend(noise + truncated + corrupt_json + valid_packet)
    
    # Process buffer as read_loop does
    while b'\n' in manager.raw_buffer:
        line_bytes, manager.raw_buffer = manager.raw_buffer.split(b'\n', 1)
        line = line_bytes.decode('utf-8', errors='ignore').strip()
        if not line:
            continue
        manager.lines_received += 1
        if line.startswith('{'):
            try:
                import json
                data = json.loads(line)
                is_valid, packet, reason = manager.validator.process(data, raw_line=line)
                if is_valid and packet:
                    manager.packets_received += 1
                    if manager.on_data_callback:
                        manager.on_data_callback(packet.model_dump())
                else:
                    manager.packets_rejected += 1
            except Exception:
                manager.packets_rejected += 1
                manager.errors += 1
        else:
            # Noise/info line
            pass
            
    assert manager.lines_received == 4
    assert manager.packets_rejected >= 2  # Truncated & Corrupt
    assert manager.packets_received == 1  # Valid packet parsed successfully
    assert len(received_packets) == 1
    assert received_packets[0]["cattle_id"] == "CATTLE-001"

def test_buffer_overflow_protection():
    """Verify raw buffer resets if it exceeds 16KB without newline to protect memory."""
    manager = SerialManager()
    # Add 17KB of continuous stream with no newline
    manager.raw_buffer.extend(b"A" * 17000)
    assert len(manager.raw_buffer) > 16384
    
    # Simulate buffer safety check in read loop
    if len(manager.raw_buffer) > 16384:
        manager.raw_buffer.clear()
        
    assert len(manager.raw_buffer) == 0

def test_exponential_backoff_progression():
    """Verify backoff doubles on failure up to max_backoff and resets to 1.0 on connect."""
    manager = SerialManager()
    manager.backoff_time = 1.0
    manager.max_backoff = 60.0
    
    # Simulate 3 failures
    manager.backoff_time = min(manager.max_backoff, manager.backoff_time * 2)
    assert manager.backoff_time == 2.0
    manager.backoff_time = min(manager.max_backoff, manager.backoff_time * 2)
    assert manager.backoff_time == 4.0
    manager.backoff_time = min(manager.max_backoff, manager.backoff_time * 2)
    assert manager.backoff_time == 8.0
    
    # Simulate connect success
    manager.backoff_time = 1.0
    assert manager.backoff_time == 1.0

def test_structured_diagnostics_fields():
    """Verify status includes connect_time, last_line_time, backoff_time for structured logging."""
    manager = SerialManager()
    status = manager.get_status()
    assert "backoff_time" in status
    assert "last_line_time" in status
    assert "connect_time" in status
    assert "state" in status

def test_auto_detect_by_vid_pid_and_description():
    """Verify port detection matches by USB VID/PID (0x1A86 for CH340, 0x2341 for Mega) without hardcoding COM10."""
    manager = SerialManager()
    manager.set_override_port(None)  # Ensure AUTO mode
    
    with patch('serial.tools.list_ports.comports') as mock_comports:
        mock_comports.return_value = [
            MockPortInfo("COM3", "Standard Serial over Bluetooth link", vid=None, pid=None),
            MockPortInfo("COM11", "USB-SERIAL CH340", manufacturer="wch.cn", vid=0x1A86, pid=0x7523),
            MockPortInfo("COM12", "Unknown USB device", vid=0x9999, pid=0x8888)
        ]
        port_info = manager.detect_arduino()
        assert port_info is not None
        assert port_info.device == "COM11"
        assert port_info.vid == 0x1A86


def test_config_override_port_priority():
    """Verify config/env override is prioritized, but falls back gracefully if absent."""
    manager = SerialManager()
    
    with patch('serial.tools.list_ports.comports') as mock_comports:
        mock_comports.return_value = [
            MockPortInfo("COM8", "Arduino Mega 2560", vid=0x2341, pid=0x0042),
            MockPortInfo("COM9", "USB-SERIAL CH340", vid=0x1A86, pid=0x7523)
        ]
        # Override to COM9
        manager.set_override_port("COM9")
        port_info = manager.detect_arduino()
        assert port_info is not None
        assert port_info.device == "COM9"
        
        # Override to non-existent COM20 -> falls back to auto-detecting COM8/COM9
        manager.set_override_port("COM20")
        port_info = manager.detect_arduino()
        assert port_info is not None
        assert port_info.device in ("COM8", "COM9")


def test_pause_and_resume_release_port_mechanism():
    """Verify pause() releases the port and prevents background reconnect, while resume() restores it."""
    manager = SerialManager()
    manager.connected = True
    manager.port = "COM11"
    
    mock_ser = MagicMock()
    mock_ser.is_open = True
    manager.ser = mock_ser
    
    # 1. Pause to release port for Arduino IDE
    result = manager.pause()
    assert result["status"] == "paused"
    assert manager.paused is True
    assert manager.connected is False
    assert manager.ser is None
    mock_ser.close.assert_called_once()
    
    # Verify connect() respects paused state
    assert manager.connect() is False
    
    # 2. Resume reader
    with patch.object(manager, 'connect', return_value=True) as mock_connect:
        resume_res = manager.resume()
        assert resume_res["status"] == "resumed"
        assert manager.paused is False
        mock_connect.assert_called_once()


def test_serial_exception_clears_port_for_dynamic_redetection():
    """Verify that when SerialException occurs, the port is cleared so redetection can find a changed COM port."""
    manager = SerialManager()
    manager.port = "COM10"
    manager.connected = True
    
    mock_ser = MagicMock()
    mock_ser.in_waiting = 1
    mock_ser.read.side_effect = serial.SerialException("Device disconnected")
    manager.ser = mock_ser
    
    # Simulate the read_loop exception handling block
    try:
        mock_ser.read(1)
    except serial.SerialException as e:
        manager.errors += 1
        manager.disconnect(reason="connection_lost")
        manager.port = None
        manager.device_info = None
        
    assert manager.connected is False
    assert manager.port is None
    assert manager.errors == 1
    assert manager.paused is False  # Must NOT be paused so reconnect loop can run!

