import pytest
from app.schemas.telemetry import TelemetryPacket, TelemetryValidator
from app.services import ValidationService, AuthoritativeStepManager

def test_valid_heart_rate_and_spo2_packet():
    """Packet with valid HR (68 bpm) and SpO2 (98%) populates canonical fields cleanly."""
    raw_payload = {
        "device_id": "SC-001",
        "cattle_id": "CATTLE-001",
        "sequence": 101,
        "timestamp": 1729250000,
        "bpm": 68.0,
        "bpm_valid": True,
        "bpm_quality": 98,
        "spo2": 98.0,
        "spo2_valid": True,
        "spo2_quality": 95,
        "temperature": 38.6,
        "temperature_valid": True,
        "ldr": 950.0,
        "ldr_valid": True,
        "step_count_today": 42,
        "step_count_lifetime": 1500,
        "accelerometer_valid": True,
    }
    
    # 1. TelemetryPacket model validation
    packet = TelemetryPacket(**raw_payload)
    assert packet.heart_rate == 68.0
    assert packet.heart_rate_valid is True
    assert packet.spo2 == 98.0
    assert packet.spo2_valid is True
    assert packet.validity_reasons.get("heart_rate") is None
    assert packet.validity_reasons.get("spo2") is None

    # 2. ValidationService alias reconciliation
    data_dict = raw_payload.copy()
    validated = ValidationService.validate(data_dict)
    assert validated["heart_rate"] == 68.0
    assert validated["bpm"] == 68.0
    assert validated["spo2"] == 98.0
    assert validated["spo2_percent"] == 98.0


def test_out_of_range_spo2_marks_invalid_with_reason():
    """SpO2 of 24% (<30%) is out-of-range, resulting in spo2=None, spo2_valid=False, and reason."""
    raw_payload = {
        "device_id": "SC-001",
        "cattle_id": "CATTLE-001",
        "sequence": 102,
        "timestamp": 1729250005,
        "bpm": 72.0,
        "bpm_valid": True,
        "spo2": 24.0,  # Below physiological range 30..100
        "spo2_valid": True,
        "temperature": 38.5,
        "temperature_valid": True,
        "ldr": 800.0,
        "ldr_valid": True,
    }

    packet = TelemetryPacket(**raw_payload)
    # SpO2 should be nullified and marked invalid with explicit reason
    assert packet.spo2 is None
    assert packet.spo2_valid is False
    assert packet.validity_reasons["spo2"] == "Reading out of valid range"

    # Other sensors remain valid
    assert packet.heart_rate == 72.0
    assert packet.heart_rate_valid is True
    assert packet.temperature == 38.5
    assert packet.temperature_valid is True
    assert packet.ldr == 800.0
    assert packet.ldr_valid is True


def test_out_of_range_heart_rate_marks_invalid_with_reason():
    """Heart rate of 350 bpm (>300 bpm) is out of range, resulting in heart_rate=None, hr_valid=False."""
    raw_payload = {
        "device_id": "SC-001",
        "cattle_id": "CATTLE-001",
        "sequence": 103,
        "timestamp": 1729250010,
        "heart_rate": 350.0,  # Outside physiological range 20..300
        "heart_rate_valid": True,
        "spo2": 95.0,
        "spo2_valid": True,
    }

    packet = TelemetryPacket(**raw_payload)
    assert packet.heart_rate is None
    assert packet.heart_rate_valid is False
    assert packet.validity_reasons["heart_rate"] == "Reading out of valid range"
    assert packet.spo2 == 95.0
    assert packet.spo2_valid is True


def test_spo2_74_percent_accepted_in_physiological_range():
    """Verify that SpO2 of 74% is within 30..100% and is NOT rejected by range check."""
    raw_payload = {
        "device_id": "SC-001",
        "cattle_id": "CATTLE-001",
        "sequence": 104,
        "timestamp": 1729250015,
        "bpm": 53.0,
        "bpm_valid": True,
        "spo2": 74.0,
        "spo2_valid": True,
    }

    packet = TelemetryPacket(**raw_payload)
    assert packet.spo2 == 74.0
    assert packet.spo2_valid is True
    assert packet.heart_rate == 53.0
    assert packet.heart_rate_valid is True
    assert packet.validity_reasons.get("spo2") is None


def test_steps_not_counted_when_accelerometer_invalid():
    """Verify that step counts do not increment when accelerometer_valid is False."""
    from app.database.database import SessionLocal
    from app.database import models

    db = SessionLocal()
    try:
        cid = "TEST-COW-STEPS-01"
        cow = db.query(models.Cattle).filter(models.Cattle.cattle_id == cid).first()
        if not cow:
            cow = models.Cattle(cattle_id=cid, name="StepsTestCow", breed="Holstein", age=3)
            db.add(cow)
            db.commit()

        # Step reading with valid accelerometer
        today1, life1 = AuthoritativeStepManager.process_step_reading(
            db=db,
            cattle_id=cid,
            incoming_lifetime=100,
            boot_count=1
        )
        assert life1 == 100

        # Step reading with incoming_lifetime=None (which occurs when accelerometer_valid is False)
        today2, life2 = AuthoritativeStepManager.process_step_reading(
            db=db,
            cattle_id=cid,
            incoming_lifetime=None,
            boot_count=1
        )
        # Lifetime and today must not increment when incoming reading is gated/None
        assert life2 == life1
        assert today2 == today1
    finally:
        db.close()


def test_alerts_endpoint_returns_timezone_aware_real_datetime():
    """Verify that /api/alerts returns ISO-8601 timestamps with timezone information."""
    from fastapi.testclient import TestClient
    from app.main import app
    from app.database.database import SessionLocal
    from app.database import models
    from datetime import datetime, timezone

    client = TestClient(app)
    db = SessionLocal()
    try:
        cid = "ALERT-TEST-COW"
        cow = db.query(models.Cattle).filter(models.Cattle.cattle_id == cid).first()
        if not cow:
            cow = models.Cattle(cattle_id=cid, name="AlertCow", breed="Jersey", age=2)
            db.add(cow)
            db.commit()

        reading = models.SensorReading(
            cattle_id=cid,
            heart_rate=140,
            spo2=80.0,
            temperature=41.5,
            timestamp=datetime.now(timezone.utc)
        )
        db.add(reading)
        db.commit()

        pred = models.Prediction(
            reading_id=reading.id,
            overall_status="abnormal",
            heart_rate_status="abnormal",
            spo2_status="abnormal",
            temperature_status="abnormal",
            timestamp=datetime.now(timezone.utc)
        )
        db.add(pred)
        db.commit()

        res = client.get("/api/alerts")
        assert res.status_code == 200
        alerts = res.json()
        assert len(alerts) > 0

        # Find our created alert
        alert = next((a for a in alerts if a["cattle_id"] == cid), alerts[0])
        ts = alert.get("timestamp")
        assert ts is not None
        # Must be ISO-8601 formatted with timezone or T delimiter
        assert "T" in ts
        assert len(ts) >= 19
    finally:
        db.close()
