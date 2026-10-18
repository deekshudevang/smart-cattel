import pytest
import time
from fastapi.testclient import TestClient
from datetime import datetime
from sqlalchemy.orm import Session

from app.main import app
from app.database.database import SessionLocal
from app.database import models
from app.schemas.telemetry import TelemetryPacket
from app.services import SensorService, PredictionService
from app.websocket.manager import manager

client = TestClient(app)

def test_delete_cow_soft_deletes_and_filters_from_endpoints():
    """Verify that deleting a cow sets deleted=True and excludes it from /api/cattle and /api/cows."""
    db: Session = SessionLocal()
    test_id = "TEST-DEL-COW-01"
    try:
        # Create or restore test cow
        cow = db.query(models.Cattle).filter(models.Cattle.cattle_id == test_id).first()
        if not cow:
            cow = models.Cattle(
                cattle_id=test_id,
                name="Daisy Delete Test",
                breed="Holstein",
                status="normal",
                deleted=False
            )
            db.add(cow)
        else:
            cow.deleted = False
        db.commit()

        # Verify cow appears in /api/cattle and /api/cows
        r_cattle = client.get("/api/cattle")
        assert r_cattle.status_code == 200
        cattle_ids = [c["cattle_id"] for c in r_cattle.json()]
        assert test_id in cattle_ids

        r_cows = client.get("/api/cows")
        assert r_cows.status_code == 200
        cow_ids = [c["cattle_id"] for c in r_cows.json()]
        assert test_id in cow_ids

        # Now delete via DELETE /api/cows/{cattle_id}
        r_delete = client.delete(f"/api/cows/{test_id}")
        assert r_delete.status_code == 204

        # Verify DB deleted flag is True
        db.expire_all()
        cow_after = db.query(models.Cattle).filter(models.Cattle.cattle_id == test_id).first()
        assert cow_after.deleted is True

        # Verify it disappears from GET /api/cattle
        r_cattle_after = client.get("/api/cattle")
        assert r_cattle_after.status_code == 200
        cattle_ids_after = [c["cattle_id"] for c in r_cattle_after.json()]
        assert test_id not in cattle_ids_after

        # Verify it disappears from GET /api/cows
        r_cows_after = client.get("/api/cows")
        assert r_cows_after.status_code == 200
        cow_ids_after = [c["cattle_id"] for c in r_cows_after.json()]
        assert test_id not in cow_ids_after

    finally:
        db.close()


@pytest.mark.asyncio
async def test_deleted_cow_packets_are_ignored():
    """Verify that incoming telemetry for a deleted cow is rejected by SensorService.process_reading."""
    db: Session = SessionLocal()
    test_id = "TEST-DEL-COW-02"
    try:
        cow = db.query(models.Cattle).filter(models.Cattle.cattle_id == test_id).first()
        if not cow:
            cow = models.Cattle(
                cattle_id=test_id,
                name="Ignored Deleted Cow",
                breed="Jersey",
                status="normal",
                deleted=True
            )
            db.add(cow)
        else:
            cow.deleted = True
        db.commit()

        sensor_service = SensorService(PredictionService())
        data = {
            "device_id": "DEV-02",
            "cattle_id": test_id,
            "timestamp": int(time.time()),
            "sequence": 1,
            "heart_rate": 72.0,
            "spo2": 98.0,
            "temperature": 38.5,
            "ph": 6.8,
            "ldr": 500.0,
            "step_count_today": 150,
            "step_count_lifetime": 1500,
            "mems_x": 0.0,
            "mems_y": 0.0,
            "mems_z": 9.8
        }

        result = await sensor_service.process_reading(data, manager)
        assert result.get("status") == "dropped"
        assert result.get("reason") == "deleted_cattle"

    finally:
        db.close()


def test_packet_with_null_vitals_preserves_other_values():
    """Verify packet with null SpO2/HR still parses and returns temperature, pH, light, and steps."""
    raw = {
        "cattle_id": "CATTLE-001",
        "device_id": "DEV-01",
        "timestamp": int(time.time()),
        "bpm": None,
        "spo2": None,
        "temperature": 38.6,
        "ph": 6.75,
        "ldr": 880.0,
        "step_count_today": 42,
        "step_count_lifetime": 1200,
        "acceleration_x": 1.2,
        "acceleration_y": -0.4,
        "acceleration_z": 9.8
    }

    packet = TelemetryPacket(**raw)
    assert packet.heart_rate_bpm is None
    assert packet.spo2_percent is None
    assert packet.temperature_c == 38.6
    assert packet.ph_value == 6.75
    assert packet.ldr_value == 880.0
    assert packet.step_count_today == 42
    assert packet.acceleration_x == 1.2


def test_cows_endpoint_returns_complete_vitals_dto():
    """Verify GET /api/cows/{id} returns all required vitals fields in latest_health."""
    db: Session = SessionLocal()
    test_id = "CATTLE-001"
    try:
        cow = db.query(models.Cattle).filter(models.Cattle.cattle_id == test_id).first()
        if not cow:
            cow = models.Cattle(cattle_id=test_id, name="Bessie", status="normal", deleted=False)
            db.add(cow)
            db.commit()

        # Add a test sensor reading with vitals
        reading = models.SensorReading(
            cattle_id=test_id,
            device_id="DEV-01",
            timestamp=datetime.utcnow(),
            heart_rate=76.0,
            spo2=97.0,
            temperature=38.4,
            ph=6.82,
            ldr=920.0,
            step_count_today=55,
            step_count_lifetime=2500,
            acceleration_x=0.5,
            acceleration_y=1.1,
            acceleration_z=9.7,
            accelerometer_valid=True,
            ldr_valid=True,
            device_connected=True
        )
        db.add(reading)
        db.commit()

        r = client.get(f"/api/cows/{test_id}")
        assert r.status_code == 200
        data = r.json()
        assert data["cattle_id"] == test_id
        health = data["latest_health"]
        assert health is not None
        assert health["heart_rate"] == 76.0
        assert health["spo2"] == 97.0
        assert health["temperature"] == 38.4
        assert health["ph"] == 6.82
        assert health["ldr"] == 920.0
        assert health["step_count_today"] == 55
        assert health["timestamp"] is not None

    finally:
        db.close()
