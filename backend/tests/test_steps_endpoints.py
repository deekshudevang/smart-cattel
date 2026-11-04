import pytest
from datetime import datetime, timezone
from fastapi.testclient import TestClient
from app.main import app
from app.database.database import SessionLocal
from app.database import models
from app.services import DatabaseService, SensorService, PredictionService
from app.step_manager import AuthoritativeStepManager

client = TestClient(app)

@pytest.fixture(autouse=True)
def setup_step_test():
    AuthoritativeStepManager.reset_cache_for_testing()
    db = SessionLocal()
    try:
        # Ensure test cattle exists
        cid = "TEST-STEPS-COW-01"
        cow = db.query(models.Cattle).filter(models.Cattle.cattle_id == cid).first()
        if not cow:
            cow = models.Cattle(cattle_id=cid, name="StepsTestCow", breed="Gir", age="4")
            db.add(cow)
            db.commit()
    finally:
        db.close()


def test_step_reading_persistence_and_latest_endpoint():
    """Verify that save_step_reading stores genuine records and /steps/latest returns them."""
    cid = "TEST-STEPS-COW-01"
    now_utc = datetime.now(timezone.utc)

    reading = DatabaseService.save_step_reading(
        cattle_id=cid,
        steps_today=150,
        total_steps=1250,
        step_increment=25,
        device_id="SC-001",
        step_sensor_status="active",
        step_algorithm_version="1.0",
        measured_at=now_utc
    )
    assert reading.id is not None
    assert reading.steps_today == 150
    assert reading.total_steps == 1250
    assert reading.step_increment == 25

    # Test v1 endpoint
    res_v1 = client.get(f"/api/v1/cattle/{cid}/steps/latest")
    assert res_v1.status_code == 200
    data_v1 = res_v1.json()
    assert data_v1["cattle_id"] == cid
    assert data_v1["steps_today"] == 150
    assert data_v1["total_steps"] == 1250
    assert data_v1["step_increment"] == 25
    assert data_v1["step_sensor_status"] == "active"

    # Test standard alias endpoint
    res_alias = client.get(f"/api/cattle/{cid}/steps/latest")
    assert res_alias.status_code == 200
    assert res_alias.json()["total_steps"] == 1250


def test_step_history_and_statistics_endpoints():
    """Verify step history and statistics endpoints return genuine database aggregations."""
    cid = "TEST-STEPS-COW-01"
    now_utc = datetime.now(timezone.utc)

    # Insert a sequence of step readings
    for i in range(1, 4):
        DatabaseService.save_step_reading(
            cattle_id=cid,
            steps_today=150 + i * 20,
            total_steps=1250 + i * 20,
            step_increment=20,
            device_id="SC-001",
            step_sensor_status="active",
            step_algorithm_version="1.0",
            measured_at=now_utc
        )

    # Test history
    res_hist = client.get(f"/api/v1/cattle/{cid}/steps/history?limit=10")
    assert res_hist.status_code == 200
    hist = res_hist.json()
    assert len(hist) >= 3
    assert hist[0]["steps_today"] >= hist[1]["steps_today"]

    # Test statistics
    res_stats = client.get(f"/api/v1/cattle/{cid}/steps/statistics")
    assert res_stats.status_code == 200
    stats = res_stats.json()
    assert stats["cattle_id"] == cid
    assert stats["steps_today"] >= 150
    assert stats["total_steps"] >= 1250
    assert stats["daily_average"] > 0
    assert stats["hourly_average"] > 0
    assert stats["step_sensor_status"] == "active"


def test_daily_reset_does_not_reset_cumulative_total_steps():
    """Verify that when a day rollover occurs, today's steps reset to 0 while total steps persist."""
    cid = "TEST-STEPS-COW-01"
    db = SessionLocal()
    try:
        # Simulate yesterday's reading
        yesterday_dt = datetime(2026, 11, 3, 22, 0, 0, tzinfo=timezone.utc)
        today_dt = datetime(2026, 11, 4, 1, 0, 0, tzinfo=timezone.utc)

        t1, l1 = AuthoritativeStepManager.process_step_reading(
            db=db,
            cattle_id=cid,
            incoming_lifetime=5000,
            boot_count=1,
            reading_dt=yesterday_dt
        )
        assert l1 == 5000

        # Step taken on the next calendar day
        t2, l2 = AuthoritativeStepManager.process_step_reading(
            db=db,
            cattle_id=cid,
            incoming_lifetime=5050,
            boot_count=1,
            reading_dt=today_dt
        )
        # Total steps must NOT decrease or reset
        assert l2 == 5050
        # Today's steps on the new day start counting from 0 + 50
        assert t2 == 50
    finally:
        db.close()


def test_duplicate_packets_do_not_double_count():
    """Verify that duplicate packets with unchanged lifetime do not increase step count."""
    cid = "TEST-STEPS-COW-01"
    db = SessionLocal()
    try:
        now_dt = datetime(2026, 11, 4, 10, 0, 0, tzinfo=timezone.utc)

        t1, l1 = AuthoritativeStepManager.process_step_reading(
            db=db,
            cattle_id=cid,
            incoming_lifetime=1000,
            boot_count=1,
            reading_dt=now_dt
        )

        # Duplicate packet arrives
        t2, l2 = AuthoritativeStepManager.process_step_reading(
            db=db,
            cattle_id=cid,
            incoming_lifetime=1000,
            boot_count=1,
            reading_dt=now_dt
        )
        assert l2 == l1
        assert t2 == t1
    finally:
        db.close()


def test_disconnected_accelerometer_gates_step_counting():
    """Verify that disconnected/invalid accelerometer readings do not fabricate steps."""
    cid = "TEST-STEPS-COW-01"
    db = SessionLocal()
    try:
        # Initial valid reading
        t1, l1 = AuthoritativeStepManager.process_step_reading(
            db=db,
            cattle_id=cid,
            incoming_lifetime=2000,
            boot_count=1
        )
        assert l1 == 2000

        # Disconnected accelerometer: incoming_lifetime is None
        t2, l2 = AuthoritativeStepManager.process_step_reading(
            db=db,
            cattle_id=cid,
            incoming_lifetime=None,
            boot_count=1
        )
        # Must retain known trusted state and NOT fabricate steps
        assert l2 == l1
        assert t2 == t1
    finally:
        db.close()
