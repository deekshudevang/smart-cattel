import pytest
from datetime import datetime, timezone, timedelta
from fastapi.testclient import TestClient
from sqlalchemy.orm import Session
from unittest.mock import MagicMock, patch

from app.main import app
from app.database.database import get_db, SessionLocal
from app.database import models
from app.schemas.telemetry import TelemetryPacket
from app.services import DatabaseService, ValidationService, SensorService, PredictionService
from app.serial.manager import SerialManager

client = TestClient(app)

@pytest.fixture
def db_session():
    db = SessionLocal()
    try:
        # Ensure a test cow exists
        cow = db.query(models.Cattle).filter(models.Cattle.cattle_id == "TEST-COW-01").first()
        if not cow:
            cow = models.Cattle(
                cattle_id="TEST-COW-01",
                name="Bessie Test",
                breed="Holstein",
                weight=450.0,
                status="normal",
                deleted=False
            )
            db.add(cow)
            db.commit()
        yield db
    finally:
        # Cleanup test readings and cow
        db.query(models.CattleWeightReading).filter(models.CattleWeightReading.cattle_id == "TEST-COW-01").delete()
        db.query(models.SensorReading).filter(models.SensorReading.cattle_id == "TEST-COW-01").delete()
        db.query(models.Cattle).filter(models.Cattle.cattle_id == "TEST-COW-01").delete()
        db.commit()
        db.close()


def test_weight_telemetry_packet_parsing():
    """Verify TelemetryPacket parses and reconciles weight fields and validity."""
    # 1. Normal valid weight
    pkt = TelemetryPacket(
        device_id="SC-001",
        timestamp=1700000000,
        body_weight_kg=512.4,
        weight_stable=True,
        weight_calibrated=True,
        weight_valid=True
    )
    assert pkt.body_weight_kg == 512.4
    assert pkt.weight_kg == 512.4
    assert pkt.weight_stable is True
    assert pkt.weight_calibrated is True
    assert pkt.weight_valid is True
    assert pkt.validity["weight"] is True
    assert pkt.validity_reasons["weight"] is None
    assert pkt.weight_sensor_status == "stable"

    # 2. Alias mapping from weight_kg
    pkt2 = TelemetryPacket(
        device_id="SC-001",
        timestamp=1700000000,
        weight_kg=480.0,
        weight_stable=False,
        weight_calibrated=True
    )
    assert pkt2.body_weight_kg == 480.0
    assert pkt2.weight_sensor_status == "unstable"

    # 3. Out-of-bounds weight (> 1500 kg)
    pkt3 = TelemetryPacket(
        device_id="SC-001",
        timestamp=1700000000,
        body_weight_kg=2500.0
    )
    assert pkt3.body_weight_kg is None
    assert pkt3.weight_valid is False
    assert pkt3.validity["weight"] is False
    assert pkt3.validity_reasons["weight"] == "Reading out of valid range"


def test_weight_database_persistence(db_session):
    """Verify weight fields persist in SensorReading table."""
    data = {
        "cattle_id": "TEST-COW-01",
        "device_id": "SC-001",
        "timestamp": int(datetime.now(timezone.utc).timestamp()),
        "body_weight_kg": 525.5,
        "weight_raw": 525500,
        "weight_stable": True,
        "weight_calibrated": True,
        "weight_valid": True,
        "weight_sensor_status": "stable"
    }
    reading_id = DatabaseService.save_reading(data)
    assert reading_id is not None

    db_reading = db_session.query(models.SensorReading).filter(models.SensorReading.id == reading_id).first()
    assert db_reading is not None
    assert db_reading.body_weight_kg == 525.5
    assert db_reading.weight_raw == 525500
    assert db_reading.weight_stable is True
    assert db_reading.weight_calibrated is True
    assert db_reading.weight_valid is True
    assert db_reading.weight_sensor_status == "stable"


def test_record_weight_endpoint(db_session):
    """Verify POST /api/v1/sensors/weight endpoint records reading and updates cow weight."""
    payload = {
        "cattle_id": "TEST-COW-01",
        "device_id": "SC-001",
        "weight_kg": 530.0,
        "raw_value": 530000,
        "is_stable": True,
        "is_calibrated": True,
        "source": "scale"
    }
    response = client.post("/api/v1/sensors/weight", json=payload)
    assert response.status_code == 201
    res_data = response.json()
    assert res_data["cattle_id"] == "TEST-COW-01"
    assert res_data["weight_kg"] == 530.0
    assert res_data["is_stable"] is True
    assert res_data["is_calibrated"] is True

    # Verify cow profile weight updated
    cow = db_session.query(models.Cattle).filter(models.Cattle.cattle_id == "TEST-COW-01").first()
    db_session.refresh(cow)
    assert cow.weight == 530.0


def test_record_weight_validation(db_session):
    """Verify rejecting weight out of physiological bounds or non-existent cattle."""
    # Negative weight
    res_neg = client.post("/api/v1/sensors/weight", json={
        "cattle_id": "TEST-COW-01",
        "weight_kg": -10.0
    })
    assert res_neg.status_code == 422

    # Implausible weight (> 1500 kg)
    res_huge = client.post("/api/v1/sensors/weight", json={
        "cattle_id": "TEST-COW-01",
        "weight_kg": 2000.0
    })
    assert res_huge.status_code == 422

    # Non-existent cattle
    res_404 = client.post("/api/v1/sensors/weight", json={
        "cattle_id": "NON-EXISTENT-COW",
        "weight_kg": 450.0
    })
    assert res_404.status_code == 404


def test_weight_history_and_statistics(db_session):
    """Verify history pagination and statistics trend calculations."""
    now = datetime.now(timezone.utc)
    # Record two readings: 500 kg 2 days ago, and 510 kg today (gain of 10 kg)
    DatabaseService.save_weight_reading(
        cattle_id="TEST-COW-01",
        weight_kg=500.0,
        measured_at=now - timedelta(days=2),
        is_stable=True,
        is_calibrated=True
    )
    DatabaseService.save_weight_reading(
        cattle_id="TEST-COW-01",
        weight_kg=510.0,
        measured_at=now,
        is_stable=True,
        is_calibrated=True
    )

    # 1. Latest
    res_latest = client.get("/api/v1/cattle/TEST-COW-01/weight/latest")
    assert res_latest.status_code == 200
    assert res_latest.json()["weight_kg"] == 510.0

    # 2. History
    res_hist = client.get("/api/v1/cattle/TEST-COW-01/weight/history?limit=10")
    assert res_hist.status_code == 200
    hist = res_hist.json()
    assert len(hist) == 2
    assert hist[0]["weight_kg"] == 510.0
    assert hist[1]["weight_kg"] == 500.0

    # 3. Statistics
    res_stats = client.get("/api/v1/cattle/TEST-COW-01/weight/statistics")
    assert res_stats.status_code == 200
    stats = res_stats.json()
    assert stats["current_weight_kg"] == 510.0
    assert stats["previous_weight_kg"] == 500.0
    assert stats["difference_kg"] == 10.0
    assert stats["percentage_change"] == 2.0
    assert stats["trend"] == "gain"
    assert stats["readings_count"] == 2


def test_weight_tare_and_calibrate_commands():
    """Verify hardware command triggers call serial manager write."""
    serial_mgr = SerialManager()
    with patch.object(serial_mgr, "write") as mock_write:
        # Tare
        res_tare = client.post("/api/v1/sensors/weight/tare", json={"device_id": "SC-001"})
        assert res_tare.status_code == 200
        mock_write.assert_called_with("TARE")

        # Calibrate
        res_cal = client.post("/api/v1/sensors/weight/calibrate", json={
            "reference_weight_kg": 50.0,
            "device_id": "SC-001"
        })
        assert res_cal.status_code == 200
        mock_write.assert_called_with("CAL_WEIGHT:50.00")


def test_cows_endpoint_returns_weight_in_latest_health(db_session):
    """Verify /api/cows and /api/cows/{id} include weight fields in latest_health."""
    res_list = client.get("/api/cows")
    assert res_list.status_code == 200
    cows = res_list.json()
    test_cow = next((c for c in cows if c["cattle_id"] == "TEST-COW-01"), None)
    assert test_cow is not None
    assert "body_weight_kg" in test_cow["latest_health"]
    assert "weight_stable" in test_cow["latest_health"]
    assert "weight_sensor_status" in test_cow["latest_health"]

    res_single = client.get("/api/cows/TEST-COW-01")
    assert res_single.status_code == 200
    cow_single = res_single.json()
    assert "body_weight_kg" in cow_single["latest_health"]

