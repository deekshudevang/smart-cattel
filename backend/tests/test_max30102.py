import pytest
from app.schemas.telemetry import TelemetryValidator, TelemetryPacket

def get_base_payload():
    return {
        "protocol_version": "1.0",
        "device_id": "cow-node-01",
        "cattle_id": "C-1234",
        "sequence": 1,
        "timestamp": 1695420000,
        "temperature": 38.5,
        "temperature_valid": True,
        "humidity": 45.0,
        "humidity_valid": True,
        "mems_x": 0.0,
        "mems_y": 0.0,
        "mems_z": 9.8,
        "mems_valid": True,
        "ph": 6.5,
        "ph_valid": True,
        "ldr": 800.0,
        "ldr_valid": True,
        "gps_valid": True
    }

def test_no_contact():
    validator = TelemetryValidator()
    payload = get_base_payload()
    payload.update({
        "max_state": "NO_CONTACT",
        "bpm": None,
        "bpm_valid": False,
        "bpm_quality": 0.0,
        "spo2": None,
        "spo2_valid": False,
        "spo2_quality": 0.0
    })
    is_valid, packet, reason = validator.process(payload)
    assert is_valid is True
    assert packet.max_state == "NO_CONTACT"
    assert packet.bpm is None
    assert packet.spo2 is None
    assert packet.bpm_valid is False
    assert packet.spo2_valid is False

def test_weak_signal():
    validator = TelemetryValidator()
    payload = get_base_payload()
    payload.update({
        "max_state": "LOW_SIGNAL",
        "bpm": None,
        "bpm_valid": False,
        "bpm_quality": 0.2,
        "spo2": None,
        "spo2_valid": False,
        "spo2_quality": 0.2
    })
    is_valid, packet, reason = validator.process(payload)
    assert is_valid is True
    assert packet.max_state == "LOW_SIGNAL"
    assert packet.bpm is None
    assert packet.spo2 is None

def test_valid_signal():
    validator = TelemetryValidator()
    payload = get_base_payload()
    payload.update({
        "max_state": "VALID",
        "bpm": 65.0,
        "bpm_valid": True,
        "bpm_quality": 0.9,
        "spo2": 98.0,
        "spo2_valid": True,
        "spo2_quality": 0.9
    })
    is_valid, packet, reason = validator.process(payload)
    assert is_valid is True
    assert packet.max_state == "VALID"
    assert packet.bpm == 65.0
    assert packet.spo2 == 98.0
    assert packet.bpm_valid is True
    assert packet.spo2_valid is True

def test_noisy_signal():
    validator = TelemetryValidator()
    payload = get_base_payload()
    payload.update({
        "max_state": "UNSTABLE",
        "bpm": None,
        "bpm_valid": False,
        "bpm_quality": 0.1,
        "spo2": None,
        "spo2_valid": False,
        "spo2_quality": 0.1
    })
    is_valid, packet, reason = validator.process(payload)
    assert is_valid is True
    assert packet.max_state == "UNSTABLE"
    assert packet.bpm is None

def test_impossible_bpm():
    validator = TelemetryValidator()
    payload = get_base_payload()
    payload.update({
        "max_state": "UNSTABLE",
        "bpm": None,  # Impossible BPM -> should be rejected by firmware and sent as None
        "bpm_valid": False,
        "bpm_quality": 0.5,
        "spo2": None,
        "spo2_valid": False,
        "spo2_quality": 0.5
    })
    is_valid, packet, reason = validator.process(payload)
    assert is_valid is True
    assert packet.max_state == "UNSTABLE"
    assert packet.bpm is None

def test_impossible_spo2():
    validator = TelemetryValidator()
    payload = get_base_payload()
    payload.update({
        "max_state": "UNSTABLE",
        "bpm": None,
        "bpm_valid": False,
        "bpm_quality": 0.8,
        "spo2": None, # Impossible SpO2 sent as None
        "spo2_valid": False,
        "spo2_quality": 0.8
    })
    is_valid, packet, reason = validator.process(payload)
    assert is_valid is True
    assert packet.max_state == "UNSTABLE"
    assert packet.spo2 is None
