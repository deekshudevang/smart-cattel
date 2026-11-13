import pytest
import json
from datetime import datetime, timezone
from fastapi.testclient import TestClient

from app.main import app, get_db, sensor_service
from app.database import models
from app.database.database import SessionLocal
from app.thresholds import CATTLE_THRESHOLDS, evaluate_sensor_value, evaluate_telemetry_health
from app.websocket.manager import manager as ws_manager

client = TestClient(app)

class MockWebSocketClient:
    def __init__(self):
        self.messages = []
        
    async def accept(self):
        pass
        
    async def send_text(self, text: str):
        self.messages.append(json.loads(text))

@pytest.mark.asyncio
async def test_threshold_evaluation_normal_and_abnormal():
    # 1. Normal temperature (38.5°C)
    norm = evaluate_sensor_value("temperature", 38.5)
    assert norm is not None
    assert norm["status"] == "normal"
    assert norm["severity"] == "NORMAL"
    assert norm["direction"] == "NORMAL"

    # 2. High fever temperature (40.8°C) -> CRITICAL
    fever = evaluate_sensor_value("temperature", 40.8)
    assert fever is not None
    assert fever["status"] == "abnormal"
    assert fever["severity"] == "CRITICAL"
    assert fever["direction"] == "HIGH"
    assert "38.0°C - 39.3°C" in fever["expected_range"]
    assert len(fever["recommendation"]) > 0

    # 3. Low SpO2 (88%) -> CRITICAL
    hypoxemia = evaluate_sensor_value("spo2", 88.0)
    assert hypoxemia is not None
    assert hypoxemia["status"] == "abnormal"
    assert hypoxemia["severity"] == "CRITICAL"
    assert hypoxemia["direction"] == "LOW"

    # 4. None / missing sensor reading -> returns None, never zero or fake abnormal!
    missing = evaluate_sensor_value("temperature", None)
    assert missing is None

@pytest.mark.asyncio
async def test_abnormal_sensor_pipeline_and_websocket_broadcast():
    ws_client = MockWebSocketClient()
    client_id = "test_abnormal_ws_client"
    await ws_manager.connect(ws_client, client_id=client_id)
    ws_manager.subscribe(client_id, "CATTLE-ALL")

    cow_id = "COW-ABNORMAL-101"
    
    # 1. Ingest telemetry with abnormal temperature (40.6°C) and tachycardia (112 BPM)
    abnormal_packet = {
        "protocol_version": 1,
        "cattle_id": cow_id,
        "device_id": "DEV-ABN-01",
        "timestamp": int(datetime.now(timezone.utc).timestamp()),
        "heart_rate": 112.0,
        "heart_rate_valid": True,
        "bpm": 112.0,
        "bpm_valid": True,
        "spo2": 97.0,
        "spo2_valid": True,
        "temperature": 40.6,
        "temperature_valid": True,
        "ph": 6.8,
        "ph_valid": True,
        "ldr": 500,
        "ldr_valid": True,
        "mems_x": 0.1,
        "mems_y": 0.2,
        "mems_z": 9.8,
        "mems_valid": True,
        "fall_detected": False,
        "source": "serial",
        "is_simulated": False
    }

    result = await sensor_service.process_reading(abnormal_packet, ws_manager)
    assert "reading_id" in result
    preds = result["predictions"]
    assert preds["temperature"]["status"] == "abnormal"
    assert preds["heart_rate"]["status"] == "abnormal"
    assert preds["spo2"]["status"] == "normal"
    assert preds["overall"]["status"] == "abnormal"

    # Check WebSocket messages
    ws_messages = ws_client.messages
    assert len(ws_messages) > 0
    
    # Verify sensor_update envelope contains abnormal_events list
    sensor_updates = [m for m in ws_messages if m.get("event_type") == "sensor_update"]
    assert len(sensor_updates) > 0
    payload = sensor_updates[-1]["payload"]
    assert "abnormal_events" in payload
    abnormal_evts = payload["abnormal_events"]
    assert len(abnormal_evts) >= 2 # temperature and heart_rate
    
    temp_evt = next((e for e in abnormal_evts if e["sensor_key"] == "temperature"), None)
    assert temp_evt is not None
    assert temp_evt["cattle_id"] == cow_id
    assert temp_evt["status"] == "HIGH"
    assert "38.0°C - 39.3°C" in temp_evt["expected_range"]
    assert len(temp_evt["recommended_action"]) > 0

    # 2. Verify REST endpoints for alerts
    db = SessionLocal()
    try:
        # Verify alert in database
        db_alert = db.query(models.Alert).filter(models.Alert.cattle_id == cow_id, models.Alert.status == "active").first()
        assert db_alert is not None
        alert_id = db_alert.id

        # GET /api/alerts
        res = client.get(f"/api/alerts?cattle_id={cow_id}")
        assert res.status_code == 200
        alerts = res.json()
        assert len(alerts) > 0
        matching = [a for a in alerts if a["cattle_id"] == cow_id]
        assert len(matching) > 0
        assert "recommended_action" in matching[0]

        # PUT /api/alerts/{id}/read
        res_read = client.put(f"/api/alerts/{alert_id}/read")
        assert res_read.status_code == 200
        assert res_read.json()["alert_status"] == "read"

        # PUT /api/alerts/{id}/resolve
        res_resolve = client.put(f"/api/alerts/{alert_id}/resolve")
        assert res_resolve.status_code == 200
        assert res_resolve.json()["alert_status"] == "resolved"
    finally:
        db.close()
        ws_manager.disconnect(client_id)

def test_get_thresholds_endpoint():
    res = client.get("/api/thresholds")
    assert res.status_code == 200
    data = res.json()
    assert "temperature" in data
    assert "heart_rate" in data
    assert "spo2" in data
    assert "ph" in data
