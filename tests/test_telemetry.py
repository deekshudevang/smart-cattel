import pytest
from pydantic import ValidationError
from backend.app.schemas.sensor import SensorDataSchema

def test_telemetry_valid_payload():
    data = {
        "protocol_version": 1,
        "device_id": "CATTLE-001",
        "sequence": 1,
        "timestamp": 12345678,
        "spo2": 98,
        "spo2_valid": True,
        "spo2_quality": 1.0,
        "bpm": 75,
        "bpm_valid": True,
        "bpm_quality": 1.0,
        "temperature": 38.5,
        "temperature_valid": True,
        "humidity": 45,
        "humidity_valid": True,
        "mems_x": 0.5,
        "mems_y": 0.1,
        "mems_z": 9.8,
        "mems_valid": True,
        "ph": 7.2,
        "ph_valid": True,
        "ldr": 400,
        "ldr_valid": True
    }
    reading = SensorDataSchema(**data)
    assert reading.device_id == "CATTLE-001"
    assert reading.spo2 == 98
    assert reading.bpm == 75

def test_telemetry_missing_field():
    data = {
        "protocol_version": 1,
        "device_id": "CATTLE-001",
        "sequence": 1,
        "timestamp": 12345678,
        # missing spo2
        "bpm": 75,
        "bpm_valid": True,
        "temperature": 38.5,
        "temperature_valid": True,
        "humidity": 45,
        "humidity_valid": True,
        "mems_valid": False,
        "ph_valid": False,
        "ldr_valid": False
    }
    reading = SensorDataSchema(**data)
    assert reading.spo2 is None

def test_telemetry_invalid_types():
    data = {
        "protocol_version": 1,
        "device_id": "CATTLE-001",
        "sequence": 1,
        "timestamp": 12345678,
        "spo2": "invalid", # Should be int or float
        "bpm": 75,
        "temperature": 38.5,
        "humidity": 45,
    }
    with pytest.raises(ValidationError):
        SensorDataSchema(**data)

def test_telemetry_ph_validation():
    data = {
        "protocol_version": 1,
        "device_id": "CATTLE-001",
        "sequence": 1,
        "timestamp": 12345678,
        "ph": 15.0, # out of range
        "ph_valid": True,
    }
    with pytest.raises(ValidationError):
        SensorDataSchema(**data)
        
    data["ph"] = -1.0
    with pytest.raises(ValidationError):
        SensorDataSchema(**data)
        
    data["ph"] = 7.0
    schema = SensorDataSchema(**data)
    assert schema.ph == 7.0
