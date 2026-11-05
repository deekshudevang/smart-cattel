import pytest
import json
from fastapi.testclient import TestClient
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker

from app.main import app, get_db, sensor_service
from app.database import models
from app.database.database import Base
from app.schemas.telemetry import TelemetryValidator
from app.websocket.manager import ConnectionManager, manager as ws_manager

client = TestClient(app)

class MockWebSocket:
    def __init__(self):
        self.sent_messages = []
        self.accepted = False

    async def accept(self):
        self.accepted = True

    async def send_text(self, text: str):
        self.sent_messages.append(text)

@pytest.mark.asyncio
async def test_fresh_database_serial_ingest_to_rest_and_websocket():
    """
    Simulates a fresh database deployment:
    1. A raw Arduino serial telemetry packet with genuine XOR checksum is received.
    2. TelemetryValidator verifies checksum and parses into TelemetryPacket.
    3. sensor_service processes the reading on an empty database (auto-creating Cattle profile).
    4. Assert Cattle record is persisted in DB with valid fields.
    5. Assert GET /api/v1/cattle returns HTTP 200 with the newly created cow.
    6. Assert GET /api/v1/cows returns HTTP 200 with valid health telemetry.
    7. Assert WebSocket broadcast sends sensor_update to CATTLE-ALL subscriber.
    """
    # Set up a connected WebSocket client to CATTLE-ALL
    mock_ws = MockWebSocket()
    client_id = "test_android_app_fresh_db"
    await ws_manager.connect(mock_ws, client_id=client_id)
    ws_manager.subscribe(client_id, "CATTLE-ALL")

    # Construct genuine Arduino raw line matching arduino.ino format exactly
    test_cow_id = "COW-FRESH-999"
    prefix = (
        f'{{"protocol_version":1,"cattle_id":"{test_cow_id}","device_id":"SC-001",'
        f'"boot_count":1,"sequence":101,"timestamp":54321,"device_connected":true,'
        f'"step_count_today":42,"step_count_lifetime":850,'
        f'"accelerometer_valid":true,"acceleration_x":0.25,"acceleration_y":-0.10,"acceleration_z":9.78,'
        f'"mems_x":0.25,"mems_y":-0.10,"mems_z":9.78,"mems_valid":true,'
        f'"max_state":"NORMAL","heart_rate_valid":true,"bpm_valid":true,"heart_rate":76.0,'
        f'"heart_rate_bpm":76.0,"bpm":76.0,"bpm_quality":0.98,'
        f'"spo2_valid":true,"spo2_percent":97.0,"spo2":97.0,"spo2_quality":0.99,'
        f'"temperature_valid":true,"temperature_c":38.6,"temperature":38.6,'
        f'"humidity":62.0,"humidity_valid":true,"sensor_quality":1.0,"last_successful_read":54000,'
        f'"ph_valid":true,"ph_value":6.9,"ph":6.9,'
        f'"ldr_valid":true,"ldr_value":920,"ldr":920,'
        f'"gps_valid":false,"gps_lat":null,"gps_lon":null,"gps_satellites":null,'
        f'"weight_valid":true,"weight_stable":true,"weight_calibrated":true,'
        f'"weight_sensor_status":"stable","weight_raw":462000,"body_weight_kg":462.5,"weight_kg":462.5'
    )

    # Compute genuine Arduino ChecksumPrint XOR sum over prefix
    xor_sum = 0
    for b in prefix.encode("utf-8"):
        xor_sum ^= b

    raw_line = f'{prefix},"chk":{xor_sum}}}'

    # 1. Feed through real TelemetryValidator (Hop 2: Parser)
    validator = TelemetryValidator()
    data = json.loads(raw_line)
    is_valid, packet, reason = validator.process(data, raw_line=raw_line)
    assert is_valid is True, f"TelemetryValidator rejected packet: {reason}"
    assert packet is not None
    assert packet.cattle_id == test_cow_id

    # 2. Ingest through real sensor_service (Hop 3: Ingest, Hop 4: DB Write, Hop 5: WS Broadcast)
    pkt_dict = packet.model_dump()
    pkt_dict["source"] = "serial"
    pkt_dict["is_simulated"] = False
    pkt_dict["raw_packet"] = raw_line
    pkt_dict["checksum_valid"] = True

    result = await sensor_service.process_reading(pkt_dict, ws_manager)
    assert "reading_id" in result
    assert result["reading_id"] is not None

    # 3. Assert database row created with all required fields valid
    from app.database.database import SessionLocal
    db = SessionLocal()
    try:
        cow = db.query(models.Cattle).filter(models.Cattle.cattle_id == test_cow_id).first()
        assert cow is not None, f"Cattle row for {test_cow_id} was not created in database!"
        assert cow.cattle_id == test_cow_id
        assert cow.name == test_cow_id
        assert cow.deleted is False or cow.deleted == 0
        assert cow.status in ("normal", "healthy")

        reading = db.query(models.SensorReading).filter(models.SensorReading.id == result["reading_id"]).first()
        assert reading is not None
        assert reading.cattle_id == test_cow_id
        assert reading.heart_rate == 76
        assert reading.spo2 == 97
        assert reading.temperature == 38.6
        assert reading.ldr == 920
        assert reading.body_weight_kg == 462.5
    finally:
        db.close()

    # 4. Check REST endpoints: GET /api/v1/cattle and GET /api/cattle
    for endpoint in ("/api/v1/cattle", "/api/cattle"):
        resp = client.get(endpoint)
        assert resp.status_code == 200, f"{endpoint} returned status {resp.status_code}"
        cattle_list = resp.json()
        assert isinstance(cattle_list, list)
        matching = [c for c in cattle_list if c.get("cattle_id") == test_cow_id]
        assert len(matching) == 1, f"{test_cow_id} not found in {endpoint} response: {cattle_list}"
        c_item = matching[0]
        assert c_item["name"] == test_cow_id
        assert c_item["status"] in ("normal", "healthy")

    # 5. Check REST endpoints: GET /api/v1/cows and GET /api/cows
    for endpoint in ("/api/v1/cows", "/api/cows"):
        resp = client.get(endpoint)
        assert resp.status_code == 200, f"{endpoint} returned status {resp.status_code}"
        cows_list = resp.json()
        assert isinstance(cows_list, list)
        matching = [c for c in cows_list if c.get("cattle_id") == test_cow_id]
        assert len(matching) == 1, f"{test_cow_id} not found in {endpoint} response: {cows_list}"
        c_item = matching[0]
        latest_h = c_item.get("latest_health", {})
        assert latest_h.get("heart_rate") == 76
        assert latest_h.get("spo2") == 97
        assert latest_h.get("temperature") == 38.6
        assert latest_h.get("ldr") == 920
        assert latest_h.get("body_weight_kg") == 462.5

    # 6. Check WebSocket broadcast received
    assert len(mock_ws.sent_messages) > 0, "No WebSocket message was received by subscriber!"
    found_ws_event = False
    for msg in mock_ws.sent_messages:
        try:
            ws_json = json.loads(msg)
            if ws_json.get("event_type") == "sensor_update":
                payload = ws_json.get("payload", {})
                if payload.get("cattle_id") == test_cow_id:
                    found_ws_event = True
                    data_obj = payload.get("data", {})
                    assert data_obj.get("heart_rate") == 76.0
                    assert data_obj.get("spo2") == 97.0
                    assert data_obj.get("temperature") == 38.6
                    assert data_obj.get("ldr") == 920
                    assert data_obj.get("body_weight_kg") == 462.5
                    break
        except Exception:
            pass

    assert found_ws_event is True, f"Did not find matching WebSocket event for {test_cow_id} in {mock_ws.sent_messages}"

    # Cleanup test client
    ws_manager.disconnect(client_id)
