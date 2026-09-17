import pytest
from pydantic import ValidationError
from backend.app.schemas.sensor import SensorDataSchema

def test_telemetry_valid_payload():
    data = {
        "cattle_id": "CATTLE-001",
        "spo2": 98,
        "bpm": 75,
        "temperature": 38.5,
        "humidity": 45,
        "mems_x": 0.5,
        "mems_y": 0.1,
        "mems_z": 9.8,
        "ph": 7.2,
        "ldr": 400
    }
    reading = SensorDataSchema(**data)
    assert reading.cattle_id == "CATTLE-001"
    assert reading.spo2 == 98

def test_telemetry_missing_field():
    data = {
        "cattle_id": "CATTLE-001",
        # missing spo2
        "bpm": 75,
        "temperature": 38.5,
        "humidity": 45,
        "mems_x": 0.5,
        "mems_y": 0.1,
        "mems_z": 9.8,
        "ph": 7.2,
        "ldr": 400
    }
    with pytest.raises(ValidationError):
        SensorDataSchema(**data)

def test_telemetry_invalid_types():
    data = {
        "cattle_id": "CATTLE-001",
        "spo2": "invalid", # Should be int or float
        "bpm": 75,
        "temperature": 38.5,
        "humidity": 45,
        "mems_x": 0.5,
        "mems_y": 0.1,
        "mems_z": 9.8,
        "ph": 7.2,
        "ldr": 400
    }
    with pytest.raises(ValidationError):
        SensorDataSchema(**data)
