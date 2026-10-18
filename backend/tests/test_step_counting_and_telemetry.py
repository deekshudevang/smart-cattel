import math
import time
import os
import sys
sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), '../app')))
sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), '..')))

import pytest
from app.schemas.telemetry import TelemetryPacket, TelemetryValidator
from app.services import DatabaseService
from app.websocket.manager import ConnectionManager
from app.schemas.websocket import WebSocketEvent, EventType
from app.database import models
from app.database.database import SessionLocal, engine

# Python equivalent of Arduino CowStepCounter algorithm for verification of algorithm logic
class SimulatedCowStepCounter:
    def __init__(self):
        self.gravity_mag = 9.81
        self.alpha = 0.05
        self.step_threshold = 1.8
        self.hysteresis_threshold = 0.5
        self.refractory_ms = 380
        self.noise_gate = 0.35
        self.is_armed = True
        self.last_step_time_ms = 0
        self.steps_today = 0
        self.steps_lifetime = 0
        self.last_step_detected = False

    def process_sample(self, ax: float, ay: float, az: float, now_ms: int) -> bool:
        self.last_step_detected = False
        raw_mag = math.sqrt(ax * ax + ay * ay + az * az)
        self.gravity_mag = (self.alpha * raw_mag) + ((1.0 - self.alpha) * self.gravity_mag)
        dynamic_accel = abs(raw_mag - self.gravity_mag)

        if dynamic_accel < self.noise_gate:
            dynamic_accel = 0.0

        if (now_ms - self.last_step_time_ms) > self.refractory_ms:
            if self.is_armed and (dynamic_accel >= self.step_threshold):
                self.steps_today += 1
                self.steps_lifetime += 1
                self.last_step_time_ms = now_ms
                self.is_armed = False
                self.last_step_detected = True
                return True
            elif not self.is_armed and (dynamic_accel <= self.hysteresis_threshold):
                self.is_armed = True

        return False

    def reset_day(self):
        self.steps_today = 0


def test_step_counter_stationary_noise():
    """Verify stationary sensor noise produces ZERO false steps."""
    counter = SimulatedCowStepCounter()
    now_ms = 1000

    # Feed 200 samples of slight stationary jitter around 9.81 m/s^2 (gravity)
    for i in range(200):
        noise = 0.1 * math.sin(i)
        counter.process_sample(0.02, 0.01, 9.81 + noise, now_ms + i * 40)

    assert counter.steps_today == 0
    assert counter.steps_lifetime == 0


def test_step_counter_peak_detection_and_refractory():
    """Verify distinct walking peaks produce steps, but rapid bursts within refractory window are locked out."""
    counter = SimulatedCowStepCounter()
    t = 1000

    # Step 1: Upward acceleration peak above threshold
    stepped = counter.process_sample(0.2, 0.2, 12.0, t)  # dynamic accel ~2.2 > 1.8
    assert stepped is True
    assert counter.steps_today == 1

    # Rapid jitter within 200ms (< 380ms refractory period) must be rejected
    stepped_rapid = counter.process_sample(0.2, 0.2, 12.5, t + 100)
    assert stepped_rapid is False
    assert counter.steps_today == 1

    # Arming condition: signal drops back below hysteresis threshold
    counter.process_sample(0.0, 0.0, 9.81, t + 400)
    assert counter.is_armed is True

    # Step 2: Next step after refractory lockout expires
    stepped2 = counter.process_sample(0.3, 0.2, 12.2, t + 500)
    assert stepped2 is True
    assert counter.steps_today == 2
    assert counter.steps_lifetime == 2


def test_step_counter_daily_rollover():
    """Verify daily reset clears steps_today while preserving steps_lifetime."""
    counter = SimulatedCowStepCounter()
    counter.steps_today = 1450
    counter.steps_lifetime = 28900

    counter.reset_day()
    assert counter.steps_today == 0
    assert counter.steps_lifetime == 28900


def test_phase4_telemetry_packet_nullable():
    """Test standard Phase 4 telemetry contract with missing/null sensor fields."""
    raw_payload = {
        "cattle_id": "CATTLE-001",
        "device_id": "NODE-01",
        "timestamp": 1729240000,
        "acceleration_x": None,
        "acceleration_y": None,
        "acceleration_z": None,
        "accelerometer_valid": False,
        "heart_rate_bpm": None,
        "heart_rate_valid": False,
        "spo2_percent": None,
        "spo2_valid": False,
        "temperature_c": None,
        "temperature_valid": False,
        "ph_value": None,
        "ph_valid": False,
        "ldr_value": None,
        "ldr_valid": False,
        "step_count_today": 0,
        "step_count_lifetime": 520,
        "device_connected": True
    }

    validator = TelemetryValidator()
    is_valid, packet, reason = validator.process(raw_payload)

    assert is_valid is True
    assert packet.cattle_id == "CATTLE-001"
    assert packet.acceleration_x is None
    assert packet.accelerometer_valid is False
    assert packet.step_count_today == 0
    assert packet.step_count_lifetime == 520
    assert packet.device_connected is True


def test_database_persistence_of_steps():
    """Verify SensorReading and Activity database records store steps and validity."""
    db = SessionLocal()
    try:
        # Ensure test cattle exists
        test_cattle = db.query(models.Cattle).filter(models.Cattle.cattle_id == "TEST-COW-99").first()
        if not test_cattle:
            test_cattle = models.Cattle(cattle_id="TEST-COW-99", name="Test Cow 99", status="Healthy")
            db.add(test_cattle)
            db.commit()

        packet_dict = {
            "cattle_id": "TEST-COW-99",
            "device_id": "DEV-TEST-99",
            "timestamp": int(time.time()),
            "acceleration_x": 0.12,
            "acceleration_y": -0.05,
            "acceleration_z": 9.82,
            "accelerometer_valid": True,
            "step_count_today": 342,
            "step_count_lifetime": 8920,
            "spo2": 97,
            "spo2_valid": True,
            "heart_rate": 72,
            "heart_rate_valid": True,
            "temperature": 38.6,
            "temperature_valid": True,
            "ph": 6.8,
            "ph_valid": True,
            "ldr": 450,
            "ldr_valid": True,
            "device_connected": True
        }

        reading_id = DatabaseService.save_reading(packet_dict)
        assert reading_id is not None

        # Verify saved record in db
        saved = db.query(models.SensorReading).filter(models.SensorReading.id == reading_id).first()
        assert saved is not None
        assert saved.step_count_today == 342
        assert saved.step_count_lifetime == 8920
        assert saved.accelerometer_valid is True
        assert abs(saved.acceleration_z - 9.82) < 0.01

        # Verify activity steps update
        act = db.query(models.Activity).filter(models.Activity.cattle_id == "TEST-COW-99").order_by(models.Activity.date.desc()).first()
        assert act is not None
        assert act.steps == 342

    finally:
        db.query(models.Activity).filter(models.Activity.cattle_id == "TEST-COW-99").delete()
        db.query(models.SensorReading).filter(models.SensorReading.cattle_id == "TEST-COW-99").delete()
        db.query(models.Cattle).filter(models.Cattle.cattle_id == "TEST-COW-99").delete()
        db.commit()
        db.close()


@pytest.mark.asyncio
async def test_websocket_wildcard_broadcast():
    """Verify ConnectionManager routes sensor updates to wildcard / CATTLE-ALL connections."""
    manager = ConnectionManager()
    
    class MockWebSocket:
        def __init__(self):
            self.sent_messages = []
            self.accepted = False

        async def accept(self):
            self.accepted = True

        async def send_text(self, text: str):
            self.sent_messages.append(text)

    ws_all = MockWebSocket()
    client_id = "test_all_subscriber"

    await manager.connect(ws_all, client_id=client_id)
    manager.subscribe(client_id, "CATTLE-ALL")

    from datetime import datetime
    event = WebSocketEvent(
        event_id="evt-test-101",
        event_type="sensor_update",
        cattle_id="CATTLE-001",
        timestamp=datetime.now(),
        payload={
            "type": "sensor_update",
            "cattle_id": "CATTLE-001",
            "data": {
                "step_count_today": 128,
                "step_count_lifetime": 4500,
                "acceleration_x": 0.05,
                "acceleration_y": 0.10,
                "acceleration_z": 9.80,
                "accelerometer_valid": True
            },
            "health": {"overall": "normal"}
        }
    )

    await manager.broadcast(event)

    assert len(ws_all.sent_messages) == 1
    assert "step_count_today" in ws_all.sent_messages[0]
    assert "128" in ws_all.sent_messages[0]
