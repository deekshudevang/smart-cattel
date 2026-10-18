import pytest
from datetime import datetime, date, timedelta, timezone
from unittest.mock import MagicMock
import zoneinfo

from app.step_manager import AuthoritativeStepManager
from app.config import settings
from app.schemas.telemetry import TelemetryValidator, TelemetryPacket

def test_step_manager_normal_progression():
    AuthoritativeStepManager.reset_cache_for_testing()
    mock_db = MagicMock()
    mock_db.query.return_value.filter.return_value.order_by.return_value.first.return_value = None

    tz = zoneinfo.ZoneInfo(settings.TIMEZONE)
    base_time = datetime(2026, 10, 18, 10, 0, 0, tzinfo=tz)

    # First packet: lifetime = 100
    today_steps, life = AuthoritativeStepManager.process_step_reading(
        db=mock_db,
        cattle_id="COW-TEST-1",
        incoming_lifetime=100,
        boot_count=1,
        reading_dt=base_time
    )
    assert today_steps == 0
    assert life == 100

    # Second packet: lifetime = 125 (+25 steps)
    today_steps, life = AuthoritativeStepManager.process_step_reading(
        db=mock_db,
        cattle_id="COW-TEST-1",
        incoming_lifetime=125,
        boot_count=1,
        reading_dt=base_time + timedelta(seconds=10)
    )
    assert today_steps == 25
    assert life == 125

def test_step_manager_duplicate_packet():
    """Ensure duplicate packets or zero delta do NOT double-count steps."""
    AuthoritativeStepManager.reset_cache_for_testing()
    mock_db = MagicMock()
    mock_db.query.return_value.filter.return_value.order_by.return_value.first.return_value = None

    tz = zoneinfo.ZoneInfo(settings.TIMEZONE)
    base_time = datetime(2026, 10, 18, 10, 0, 0, tzinfo=tz)

    AuthoritativeStepManager.process_step_reading(
        db=mock_db,
        cattle_id="COW-TEST-1",
        incoming_lifetime=500,
        boot_count=1,
        reading_dt=base_time
    )

    # Exact duplicate packet arriving after reconnect
    today_steps, life = AuthoritativeStepManager.process_step_reading(
        db=mock_db,
        cattle_id="COW-TEST-1",
        incoming_lifetime=500,
        boot_count=1,
        reading_dt=base_time + timedelta(seconds=1)
    )
    assert today_steps == 0
    assert life == 500

def test_step_manager_day_rollover_at_midnight():
    """Safeguard 11: Daily step count resets at local midnight in configured timezone."""
    AuthoritativeStepManager.reset_cache_for_testing()
    mock_db = MagicMock()
    mock_db.query.return_value.filter.return_value.order_by.return_value.first.return_value = None

    tz = zoneinfo.ZoneInfo(settings.TIMEZONE)
    day1_time = datetime(2026, 10, 18, 23, 59, 50, tzinfo=tz)

    AuthoritativeStepManager.process_step_reading(
        db=mock_db,
        cattle_id="COW-TEST-1",
        incoming_lifetime=1000,
        boot_count=1,
        reading_dt=day1_time
    )

    # 50 steps before midnight
    today_steps, life = AuthoritativeStepManager.process_step_reading(
        db=mock_db,
        cattle_id="COW-TEST-1",
        incoming_lifetime=1050,
        boot_count=1,
        reading_dt=day1_time + timedelta(seconds=5)
    )
    assert today_steps == 50
    assert life == 1050

    # Cross midnight boundary (12:00:10 AM next day)
    day2_time = datetime(2026, 10, 19, 0, 0, 10, tzinfo=tz)
    today_steps, life = AuthoritativeStepManager.process_step_reading(
        db=mock_db,
        cattle_id="COW-TEST-1",
        incoming_lifetime=1060,
        boot_count=1,
        reading_dt=day2_time
    )
    # Rollover occurred: today_steps is now 10 (1060 - 1050), lifetime is 1060
    assert today_steps == 10
    assert life == 1060

def test_step_manager_counter_anomaly_unexpected_decrease():
    """
    Safeguard 11: If lifetime decreases unexpectedly (e.g. power failure between
    EEPROM checkpoints or device replacement), preserve steps_today and reconcile baseline.
    """
    AuthoritativeStepManager.reset_cache_for_testing()
    mock_db = MagicMock()
    mock_db.query.return_value.filter.return_value.order_by.return_value.first.return_value = None

    tz = zoneinfo.ZoneInfo(settings.TIMEZONE)
    t0 = datetime(2026, 10, 18, 12, 0, 0, tzinfo=tz)

    AuthoritativeStepManager.process_step_reading(
        db=mock_db,
        cattle_id="COW-TEST-1",
        incoming_lifetime=2000,
        boot_count=1,
        reading_dt=t0
    )

    # Cow walked 80 steps
    today_steps, _ = AuthoritativeStepManager.process_step_reading(
        db=mock_db,
        cattle_id="COW-TEST-1",
        incoming_lifetime=2080,
        boot_count=1,
        reading_dt=t0 + timedelta(seconds=30)
    )
    assert today_steps == 80

    # Device reboots, but last EEPROM save was at 2050 (lost 30 steps of RAM before reboot)
    # incoming_lifetime drops to 2050!
    today_steps, life = AuthoritativeStepManager.process_step_reading(
        db=mock_db,
        cattle_id="COW-TEST-1",
        incoming_lifetime=2050,
        boot_count=2, # boot count incremented
        reading_dt=t0 + timedelta(seconds=60)
    )
    # steps_today MUST be preserved at 80 (never negative, never reduced)
    assert today_steps == 80
    assert life == 2050

    # Further walking from new baseline 2050 -> 2060 adds to today_steps (80 + 10 = 90)
    today_steps, life = AuthoritativeStepManager.process_step_reading(
        db=mock_db,
        cattle_id="COW-TEST-1",
        incoming_lifetime=2060,
        boot_count=2,
        reading_dt=t0 + timedelta(seconds=90)
    )
    assert today_steps == 90
    assert life == 2060

def test_step_manager_counter_anomaly_implausibly_large_jump():
    """Safeguard 11: Implausibly large step jumps are clamped to protect data integrity."""
    AuthoritativeStepManager.reset_cache_for_testing()
    mock_db = MagicMock()
    mock_db.query.return_value.filter.return_value.order_by.return_value.first.return_value = None

    tz = zoneinfo.ZoneInfo(settings.TIMEZONE)
    t0 = datetime(2026, 10, 18, 12, 0, 0, tzinfo=tz)

    AuthoritativeStepManager.process_step_reading(
        db=mock_db,
        cattle_id="COW-TEST-1",
        incoming_lifetime=100,
        boot_count=1,
        reading_dt=t0
    )

    # Sudden corrupted jump of 100,000 steps
    today_steps, life = AuthoritativeStepManager.process_step_reading(
        db=mock_db,
        cattle_id="COW-TEST-1",
        incoming_lifetime=100100,
        boot_count=1,
        reading_dt=t0 + timedelta(seconds=5)
    )
    # Delta clamped to 0; steps_today remains 0, baseline reconciled to 100100
    assert today_steps == 0
    assert life == 100100

def test_telemetry_checksum_and_partial_validity():
    """Safeguard 12: Validate checksum and independent field nullability."""
    raw_packet = (
        '{"protocol_version":1,"cattle_id":"CATTLE-001","device_id":"SC-001",'
        '"boot_count":6,"sequence":1,"timestamp":1576,"steps_today":0,'
        '"step_count_lifetime":120,"heart_rate":82,"spo2":98,"temperature":38.5,'
        '"ph":6.8,"ldr":512,"ax":0.02,"ay":0.11,"az":0.98,"mems_valid":1,'
        '"hr_valid":1,"spo2_valid":1,"temp_valid":1,"ph_valid":1,"ldr_valid":1,'
        '"chk":32}'
    )

    # Calculate actual XOR checksum before ',"chk":'
    chk_idx = raw_packet.rfind(',"chk":')
    expected_chk = 0
    for char in raw_packet[:chk_idx]:
        expected_chk ^= ord(char)

    # Construct matching line
    line_with_valid_chk = raw_packet[:chk_idx] + f',"chk":{expected_chk}}}'

    import json
    data = json.loads(line_with_valid_chk)
    validator = TelemetryValidator()
    is_valid, packet, reason = validator.process(data, raw_line=line_with_valid_chk)
    assert is_valid is True
    assert packet.cattle_id == "CATTLE-001"
    assert packet.heart_rate == 82
    assert packet.temperature == 38.5
    assert packet.ph == 6.8
    assert packet.step_count_lifetime == 120

    # Line with corrupted checksum must fail validation
    corrupted_line = raw_packet[:chk_idx] + f',"chk":{expected_chk ^ 0xFF}}}'
    corrupted_data = json.loads(corrupted_line)
    is_valid, packet, reason = validator.process(corrupted_data, raw_line=corrupted_line)
    assert is_valid is False
    assert "checksum_mismatch" in reason

def test_telemetry_partial_field_invalidation():
    """Safeguard 12: Malformed individual field becomes null while preserving other fields."""
    data = {
        "cattle_id": "CATTLE-001",
        "device_id": "SC-001",
        "timestamp": 123456,
        "heart_rate": 9999, # Physiologically impossible -> null
        "spo2": 98,         # Valid
        "temperature": 150.0,# Impossible -> null
        "ph": 6.8,          # Valid
        "ldr": 400,         # Valid
        "acceleration_x": 0.05,
        "acceleration_y": 0.10,
        "acceleration_z": 0.98
    }
    validator = TelemetryValidator()
    is_valid, packet, reason = validator.process(data)
    assert is_valid is True
    assert packet.heart_rate is None
    assert packet.heart_rate_valid is False
    assert packet.spo2 == 98
    assert packet.spo2_valid is True
    assert packet.temperature is None
    assert packet.temperature_valid is False
    assert packet.ph == 6.8
    assert packet.ph_valid is True
