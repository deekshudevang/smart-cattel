import pytest
from datetime import datetime, timezone
from app.config import settings
from app.sms_service import (
    validate_phone_number,
    format_sms_message,
    MockSmsProvider,
    SmsAlertManager,
    get_sms_provider,
    Fast2SMSProvider
)
from app.thresholds import evaluate_telemetry_health
from database.database import SessionLocal, engine
from database import models

@pytest.fixture(autouse=True)
def setup_test_sms_env():
    # Ensure tables exist
    models.Base.metadata.create_all(bind=engine)
    # Configure test environment
    settings.SMS_ALERT_ENABLED = True
    settings.SMS_PROVIDER = "mock"
    settings.ALERT_PHONE_NUMBER = "+919876543210"
    SmsAlertManager.reset_state()
    yield
    SmsAlertManager.reset_state()


# ---------------------------------------------------------------------------
# 1. Phone Number Validation Tests
# ---------------------------------------------------------------------------

def test_phone_number_validation_international():
    is_valid, normalized, err = validate_phone_number("+919876543210")
    assert is_valid is True
    assert normalized == "+919876543210"
    assert err is None

    is_valid_us, normalized_us, _ = validate_phone_number("+14155552671")
    assert is_valid_us is True
    assert normalized_us == "+14155552671"

def test_phone_number_validation_indian_10_digit():
    is_valid, normalized, err = validate_phone_number("9876543210")
    assert is_valid is True
    assert normalized == "+919876543210"
    assert err is None

    # Formatted with spaces and hyphens
    is_valid_fmt, norm_fmt, _ = validate_phone_number("  98765-43210 ")
    assert is_valid_fmt is True
    assert norm_fmt == "+919876543210"

def test_phone_number_validation_placeholder():
    is_valid, _, err = validate_phone_number("+91XXXXXXXXXX")
    assert is_valid is False
    assert "template placeholder" in err.lower()

def test_phone_number_validation_invalid():
    is_valid, _, err = validate_phone_number("12345")
    assert is_valid is False
    assert err is not None

    is_valid_empty, _, _ = validate_phone_number("")
    assert is_valid_empty is False


# ---------------------------------------------------------------------------
# 2. SMS Formatting Tests
# ---------------------------------------------------------------------------

def test_sms_message_formatting_exact_structure():
    fixed_time = datetime(2026, 11, 13, 15, 30, 0, tzinfo=timezone.utc)
    msg = format_sms_message(
        cattle_id="CATTLE-001",
        sensor_name="Temperature",
        reading_value="40.5°C",
        severity="HIGH",
        timestamp=fixed_time
    )

    assert "SMART CATTLE ALERT!" in msg
    assert "Cow ID: CATTLE-001" in msg
    assert "Condition: ABNORMAL" in msg
    assert "Sensor: Temperature" in msg
    assert "Current Reading: 40.5°C" in msg
    assert "Severity: HIGH" in msg
    assert "Time: 2026-11-13 21:00:00 IST" in msg
    assert "Please check the animal immediately." in msg


# ---------------------------------------------------------------------------
# 3. Sensor Pipeline & Deduplication Tests
# ---------------------------------------------------------------------------

@pytest.mark.asyncio
async def test_normal_sensor_reading_produces_no_sms():
    telemetry = {
        "cattle_id": "CATTLE-001",
        "temperature": 38.6,
        "heart_rate": 70,
        "spo2": 98,
        "ph": 6.8,
        "is_simulated": False
    }
    preds = evaluate_telemetry_health(telemetry)
    db = SessionLocal()
    try:
        dispatched = await SmsAlertManager.process_telemetry_for_sms("CATTLE-001", telemetry, preds, db)
        assert len(dispatched) == 0
        assert len(MockSmsProvider.sent_messages) == 0
    finally:
        db.close()

@pytest.mark.asyncio
async def test_abnormal_temperature_triggers_sms():
    telemetry = {
        "cattle_id": "CATTLE-001",
        "temperature": 40.8, # Abnormal fever
        "heart_rate": 70,
        "spo2": 98,
        "is_simulated": False
    }
    preds = evaluate_telemetry_health(telemetry)
    db = SessionLocal()
    try:
        dispatched = await SmsAlertManager.process_telemetry_for_sms("CATTLE-001", telemetry, preds, db)
        assert len(dispatched) == 1
        assert dispatched[0]["sensor"] == "temperature"
        assert dispatched[0]["reading"] == "40.8°C"
        assert dispatched[0]["status"] == "sent"
        
        assert len(MockSmsProvider.sent_messages) == 1
        sent_sms = MockSmsProvider.sent_messages[0]
        assert sent_sms["to"] == "+919876543210"
        assert "Sensor: Temperature" in sent_sms["message"]
        assert "40.8°C" in sent_sms["message"]
        
        # Verify DB SmsLog entry
        log_entry = db.query(models.SmsLog).filter(models.SmsLog.cattle_id == "CATTLE-001").first()
        assert log_entry is not None
        assert log_entry.alert_type == "TEMPERATURE"
        assert log_entry.status == "sent"
        assert log_entry.phone_number == "+919876543210"
    finally:
        db.close()

@pytest.mark.asyncio
async def test_abnormal_heart_rate_and_spo2_triggers_sms():
    telemetry = {
        "cattle_id": "CATTLE-002",
        "temperature": 38.6,
        "heart_rate": 115, # Tachycardia
        "spo2": 88,        # Hypoxemia
        "is_simulated": False
    }
    preds = evaluate_telemetry_health(telemetry)
    db = SessionLocal()
    try:
        dispatched = await SmsAlertManager.process_telemetry_for_sms("CATTLE-002", telemetry, preds, db)
        # Both heart_rate and spo2 are abnormal: separate SMS alerts must be sent
        assert len(dispatched) == 2
        sensors = {d["sensor"] for d in dispatched}
        assert "heart_rate" in sensors
        assert "spo2" in sensors
        
        assert len(MockSmsProvider.sent_messages) == 2
    finally:
        db.close()

@pytest.mark.asyncio
async def test_repeated_abnormal_readings_are_deduplicated():
    """
    Subsequent packets with ongoing abnormal values must be suppressed.
    Only the initial abnormal detection emits an SMS.
    """
    telemetry = {
        "cattle_id": "CATTLE-001",
        "temperature": 40.5,
        "is_simulated": False
    }
    preds = evaluate_telemetry_health(telemetry)
    db = SessionLocal()
    try:
        # First reading: trigger SMS
        disp1 = await SmsAlertManager.process_telemetry_for_sms("CATTLE-001", telemetry, preds, db)
        assert len(disp1) == 1
        assert len(MockSmsProvider.sent_messages) == 1

        # Second reading: identical/ongoing abnormal temperature
        disp2 = await SmsAlertManager.process_telemetry_for_sms("CATTLE-001", telemetry, preds, db)
        assert len(disp2) == 0
        assert len(MockSmsProvider.sent_messages) == 1 # Still 1, no duplicate sent

        # Third reading: slight fluctuation (40.7°C), still abnormal
        telemetry["temperature"] = 40.7
        preds3 = evaluate_telemetry_health(telemetry)
        disp3 = await SmsAlertManager.process_telemetry_for_sms("CATTLE-001", telemetry, preds3, db)
        assert len(disp3) == 0
        assert len(MockSmsProvider.sent_messages) == 1 # Still 1
    finally:
        db.close()

@pytest.mark.asyncio
async def test_recovery_and_new_abnormal_triggers_new_sms():
    """
    When temperature recovers to normal and then spikes abnormal again,
    a new SMS alert must be sent.
    """
    db = SessionLocal()
    try:
        # 1. Abnormal spike
        t1 = {"cattle_id": "CATTLE-001", "temperature": 40.5, "is_simulated": False}
        p1 = evaluate_telemetry_health(t1)
        d1 = await SmsAlertManager.process_telemetry_for_sms("CATTLE-001", t1, p1, db)
        assert len(d1) == 1
        assert len(MockSmsProvider.sent_messages) == 1

        # 2. Recovery to normal range
        t2 = {"cattle_id": "CATTLE-001", "temperature": 38.6, "is_simulated": False}
        p2 = evaluate_telemetry_health(t2)
        d2 = await SmsAlertManager.process_telemetry_for_sms("CATTLE-001", t2, p2, db)
        assert len(d2) == 0
        assert len(MockSmsProvider.sent_messages) == 1
        # Assert state transitioned to normal
        state = SmsAlertManager.get_state("CATTLE-001", "temperature")
        assert state["state"] == "normal"

        # 3. New abnormal fever event
        t3 = {"cattle_id": "CATTLE-001", "temperature": 41.2, "is_simulated": False}
        p3 = evaluate_telemetry_health(t3)
        d3 = await SmsAlertManager.process_telemetry_for_sms("CATTLE-001", t3, p3, db)
        assert len(d3) == 1
        assert len(MockSmsProvider.sent_messages) == 2
        assert "41.2°C" in MockSmsProvider.sent_messages[1]["message"]
    finally:
        db.close()

@pytest.mark.asyncio
async def test_simulated_readings_never_trigger_sms():
    telemetry = {
        "cattle_id": "CATTLE-001",
        "temperature": 42.0,
        "is_simulated": True # Simulated flag active
    }
    preds = evaluate_telemetry_health(telemetry)
    db = SessionLocal()
    try:
        dispatched = await SmsAlertManager.process_telemetry_for_sms("CATTLE-001", telemetry, preds, db)
        assert len(dispatched) == 0
        assert len(MockSmsProvider.sent_messages) == 0
    finally:
        db.close()

@pytest.mark.asyncio
async def test_null_sensor_readings_never_trigger_sms():
    telemetry = {
        "cattle_id": "CATTLE-001",
        "temperature": None,
        "heart_rate": None,
        "spo2": None,
        "is_simulated": False
    }
    preds = evaluate_telemetry_health(telemetry)
    db = SessionLocal()
    try:
        dispatched = await SmsAlertManager.process_telemetry_for_sms("CATTLE-001", telemetry, preds, db)
        assert len(dispatched) == 0
        assert len(MockSmsProvider.sent_messages) == 0
    finally:
        db.close()


# ---------------------------------------------------------------------------
# 4. REST Endpoint Diagnostics Tests
# ---------------------------------------------------------------------------

def test_sms_config_and_logs_endpoints():
    from fastapi.testclient import TestClient
    from app.main import app

    client = TestClient(app)
    
    # 1. Config endpoint
    res_cfg = client.get("/api/sms/config")
    assert res_cfg.status_code == 200
    cfg = res_cfg.json()
    assert cfg["sms_enabled"] is True
    assert cfg["phone_configured"] is True
    assert "******" in cfg["phone_number_masked"] # Privacy masking
    
    # 2. Test SMS endpoint
    res_test = client.post("/api/sms/test")
    assert res_test.status_code == 200
    test_result = res_test.json()
    assert test_result["success"] is True
    assert test_result["status"] == "sent"
    
    # 3. Logs endpoint
    res_logs = client.get("/api/sms/logs?limit=5")
    assert res_logs.status_code == 200
    logs = res_logs.json()
    assert len(logs) >= 1
    assert logs[0]["alert_type"] == "TEST"
