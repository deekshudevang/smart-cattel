import pytest
from app.schemas.telemetry import TelemetryValidator, TelemetryPacket

def get_valid_payload():
    return {
        "protocol_version": "1.0",
        "device_id": "cow-node-01",
        "cattle_id": "C-1234",
        "sequence": 100,
        "timestamp": 1695420000,
        "spo2": 98.5,
        "spo2_valid": True,
        "spo2_quality": 100,
        "bpm": 65.0,
        "bpm_valid": True,
        "bpm_quality": 95,
        "temperature": 38.5,
        "temperature_valid": True,
        "humidity": 45.0,
        "humidity_valid": True,
        "sensor_quality": 1.0,
        "last_successful_read": 1695420000,
        "mems_x": 0.01,
        "mems_y": 0.02,
        "mems_z": 9.81,
        "mems_valid": True,
        "ph": 6.5,
        "ph_valid": True,
        "ldr": 800.0,
        "ldr_valid": True,
        "gps_valid": True,
        "gps_lat": 40.7128,
        "gps_lon": -74.0060,
        "gps_satellites": 8
    }

def test_valid_packet():
    validator = TelemetryValidator()
    payload = get_valid_payload()
    is_valid, packet, reason = validator.process(payload)
    assert is_valid is True
    assert reason == "ok"
    assert isinstance(packet, TelemetryPacket)
    assert packet.spo2 == 98.5
    assert packet.sensor_quality == 1.0

def test_dht_health_fields():
    validator = TelemetryValidator()
    payload = get_valid_payload()
    payload["temperature"] = None
    payload["temperature_valid"] = False
    payload["humidity"] = None
    payload["humidity_valid"] = False
    payload["sensor_quality"] = 0.5
    
    is_valid, packet, reason = validator.process(payload)
    assert is_valid is True
    assert packet.temperature is None
    assert packet.temperature_valid is False
    assert packet.sensor_quality == 0.5
    assert packet.last_successful_read == 1695420000

def test_null_sensor():
    validator = TelemetryValidator()
    payload = get_valid_payload()
    payload["spo2"] = None  # Valid according to schema (Optional)
    payload["spo2_valid"] = False
    
    is_valid, packet, reason = validator.process(payload)
    assert is_valid is True
    assert packet.spo2 is None
    assert packet.spo2_valid is False

def test_missing_field():
    validator = TelemetryValidator()
    payload = get_valid_payload()
    del payload["sequence"]
    
    is_valid, packet, reason = validator.process(payload)
    assert is_valid is False
    assert "schema_error" in reason
    assert packet is None

def test_wrong_type():
    validator = TelemetryValidator()
    payload = get_valid_payload()
    payload["sequence"] = "not_an_int"
    
    is_valid, packet, reason = validator.process(payload)
    assert is_valid is False
    assert "schema_error" in reason

def test_duplicate_packet():
    validator = TelemetryValidator()
    payload = get_valid_payload()
    
    is_valid1, _, _ = validator.process(payload)
    assert is_valid1 is True
    
    # Send identical sequence
    is_valid2, packet2, reason2 = validator.process(payload)
    assert is_valid2 is False
    assert reason2 == "duplicate_sequence"

def test_invalid_sequence_out_of_order():
    validator = TelemetryValidator()
    payload = get_valid_payload()
    payload["sequence"] = 100
    
    is_valid1, _, _ = validator.process(payload)
    assert is_valid1 is True
    
    # Send older sequence
    payload2 = get_valid_payload()
    payload2["sequence"] = 99
    
    is_valid2, packet2, reason2 = validator.process(payload2)
    assert is_valid2 is False
    assert reason2 == "out_of_order_sequence"
