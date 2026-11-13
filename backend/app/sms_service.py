import os
import re
import json
import logging
import urllib.request
import urllib.error
import urllib.parse
from uuid import uuid4
from datetime import datetime, timezone, timedelta
from typing import Dict, Any, Optional, Tuple, List
from sqlalchemy.orm import Session
from app.config import settings

logger = logging.getLogger("sms_service")

# ---------------------------------------------------------------------------
# 1. Phone Number Validation & Normalization
# ---------------------------------------------------------------------------

def validate_phone_number(phone: Optional[str]) -> Tuple[bool, str, Optional[str]]:
    """
    Validates and normalizes recipient phone numbers.
    Supports international E.164 format (+91XXXXXXXXXX, +1XXXXXXXXXX)
    and 10-digit Indian numbers (normalizes to +91XXXXXXXXXX).
    
    Returns:
        (is_valid: bool, normalized_phone: str, error_message: Optional[str])
    """
    raw = str(phone or "").strip()
    if not raw:
        return False, "", "ALERT_PHONE_NUMBER is not configured. Please set ALERT_PHONE_NUMBER in config.py or .env."

    # Check for unconfigured placeholders containing 'X'
    if "X" in raw.upper():
        return False, raw, f"ALERT_PHONE_NUMBER '{raw}' is a template placeholder. Please enter a valid phone number."

    # Remove formatting characters (spaces, dashes, parentheses, dots)
    cleaned = re.sub(r"[\s\-\(\)\.]", "", raw)

    # International E.164 format (+ followed by 8 to 15 digits)
    if cleaned.startswith("+"):
        if re.match(r"^\+[1-9]\d{7,14}$", cleaned):
            return True, cleaned, None
        return False, cleaned, f"Invalid international E.164 phone number format: '{raw}'"

    # 10-digit Indian mobile number (begins with 6, 7, 8, 9)
    if re.match(r"^[6-9]\d{9}$", cleaned):
        normalized = f"+91{cleaned}"
        return True, normalized, None

    # Generic digits with country code without plus sign (e.g. 919876543210)
    if re.match(r"^\d{11,15}$", cleaned):
        normalized = f"+{cleaned}"
        return True, normalized, None

    return False, cleaned, f"Invalid phone number format: '{raw}'. Expected E.164 (+91XXXXXXXXXX) or 10-digit Indian number."


# ---------------------------------------------------------------------------
# 2. SMS Message Formatting
# ---------------------------------------------------------------------------

def format_sms_message(
    cattle_id: str,
    sensor_name: str,
    reading_value: str,
    severity: str,
    timestamp: Optional[datetime] = None
) -> str:
    """
    Formats the SMS alert payload according to the project specification:
    
    SMART CATTLE ALERT!
    
    Cow ID: CATTLE-001
    
    Condition: ABNORMAL
    
    Sensor: Temperature
    
    Current Reading: 40.5°C
    
    Severity: HIGH
    
    Time: 2026-11-13 20:55:00 IST
    
    Please check the animal immediately.
    """
    if timestamp is None:
        timestamp = datetime.now(timezone.utc)
        
    # Format time in IST (UTC+5:30) or local
    ist_tz = timezone(timedelta(hours=5, minutes=30))
    if timestamp.tzinfo is None:
        ist_time = timestamp.replace(tzinfo=timezone.utc).astimezone(ist_tz)
    else:
        ist_time = timestamp.astimezone(ist_tz)
    time_str = ist_time.strftime("%Y-%m-%d %H:%M:%S IST")

    # Map severity to human-readable high/critical
    sev_str = str(severity).upper()
    if sev_str in ("CRITICAL", "HIGH"):
        display_sev = "HIGH"
    elif sev_str == "WARNING":
        display_sev = "WARNING"
    else:
        display_sev = sev_str

    msg = (
        f"SMART CATTLE ALERT!\n\n"
        f"Cow ID: {cattle_id}\n\n"
        f"Condition: ABNORMAL\n\n"
        f"Sensor: {sensor_name}\n\n"
        f"Current Reading: {reading_value}\n\n"
        f"Severity: {display_sev}\n\n"
        f"Time: {time_str}\n\n"
        f"Please check the animal immediately."
    )
    return msg


# ---------------------------------------------------------------------------
# 3. SMS Provider Implementations
# ---------------------------------------------------------------------------

class BaseSmsProvider:
    name: str = "base"
    
    def send_sms(self, to_phone: str, message: str) -> Tuple[bool, str, Optional[str]]:
        """
        Sends SMS to the given phone number.
        Returns: (success: bool, status: str, message_id_or_reason: Optional[str])
        """
        raise NotImplementedError


class Fast2SMSProvider(BaseSmsProvider):
    name: str = "fast2sms"
    URL: str = "https://www.fast2sms.com/dev/bulkV2"

    def __init__(self, api_key: Optional[str] = None):
        self.api_key = api_key or getattr(settings, "FAST2SMS_API_KEY", "")

    def send_sms(self, to_phone: str, message: str) -> Tuple[bool, str, Optional[str]]:
        if not self.api_key:
            return False, "failed", "FAST2SMS_API_KEY is not configured in .env or environment variables."

        # Fast2SMS Quick Route expects 10-digit Indian numbers without country prefix
        clean_num = to_phone.replace("+91", "").replace("+", "").strip()
        if len(clean_num) != 10 or not clean_num.isdigit():
            # If not 10 digits, attempt with original stripped number
            clean_num = clean_num[-10:] if len(clean_num) > 10 else clean_num

        payload = {
            "route": "q",
            "message": message,
            "language": "english",
            "flash": 0,
            "numbers": clean_num
        }

        data = json.dumps(payload).encode("utf-8")
        req = urllib.request.Request(
            self.URL,
            data=data,
            headers={
                "authorization": self.api_key,
                "Content-Type": "application/json",
                "User-Agent": "SmartCattle/1.0"
            },
            method="POST"
        )

        # Retry logic: up to 2 attempts
        for attempt in range(1, 3):
            try:
                with urllib.request.urlopen(req, timeout=10.0) as response:
                    body = response.read().decode("utf-8")
                    res_json = json.loads(body)
                    if res_json.get("return") is True:
                        req_id = res_json.get("request_id", str(uuid4().hex[:10]))
                        logger.info(f"[SMS Fast2SMS] Delivered successfully to {to_phone} (req_id={req_id})")
                        return True, "sent", req_id
                    else:
                        err_msg = res_json.get("message", ["Unknown Fast2SMS error"])
                        if isinstance(err_msg, list):
                            err_msg = "; ".join(str(m) for m in err_msg)
                        return False, "failed", f"Fast2SMS error: {err_msg}"
            except urllib.error.HTTPError as e:
                err_detail = e.read().decode("utf-8", errors="ignore")
                logger.warning(f"[SMS Fast2SMS Attempt {attempt}] HTTP {e.code}: {err_detail}")
                if attempt == 2:
                    return False, "failed", f"Fast2SMS HTTP {e.code}: {err_detail}"
            except Exception as e:
                logger.warning(f"[SMS Fast2SMS Attempt {attempt}] Network/Timeout: {e}")
                if attempt == 2:
                    return False, "failed", f"Fast2SMS connection error: {e}"

        return False, "failed", "Fast2SMS exhausted retry attempts."


class TwilioProvider(BaseSmsProvider):
    name: str = "twilio"

    def __init__(self, account_sid: Optional[str] = None, auth_token: Optional[str] = None, from_number: Optional[str] = None):
        self.account_sid = account_sid or getattr(settings, "TWILIO_ACCOUNT_SID", "")
        self.auth_token = auth_token or getattr(settings, "TWILIO_AUTH_TOKEN", "")
        self.from_number = from_number or getattr(settings, "TWILIO_FROM_NUMBER", "")

    def send_sms(self, to_phone: str, message: str) -> Tuple[bool, str, Optional[str]]:
        if not (self.account_sid and self.auth_token and self.from_number):
            return False, "failed", "Twilio credentials (TWILIO_ACCOUNT_SID, TWILIO_AUTH_TOKEN, TWILIO_FROM_NUMBER) not fully configured."

        url = f"https://api.twilio.com/2010-04-01/Accounts/{self.account_sid}/Messages.json"
        form_data = urllib.parse.urlencode({
            "From": self.from_number,
            "To": to_phone,
            "Body": message
        }).encode("utf-8")

        import base64
        auth_str = f"{self.account_sid}:{self.auth_token}"
        auth_header = "Basic " + base64.b64encode(auth_str.encode("utf-8")).decode("ascii")

        req = urllib.request.Request(
            url,
            data=form_data,
            headers={
                "Authorization": auth_header,
                "Content-Type": "application/x-www-form-urlencoded",
                "User-Agent": "SmartCattle/1.0"
            },
            method="POST"
        )

        for attempt in range(1, 3):
            try:
                with urllib.request.urlopen(req, timeout=10.0) as response:
                    res_json = json.loads(response.read().decode("utf-8"))
                    sid = res_json.get("sid", "unknown")
                    status = res_json.get("status", "sent")
                    logger.info(f"[SMS Twilio] Message queued/sent: sid={sid} status={status}")
                    return True, "sent", sid
            except urllib.error.HTTPError as e:
                err_body = e.read().decode("utf-8", errors="ignore")
                logger.warning(f"[SMS Twilio Attempt {attempt}] HTTP {e.code}: {err_body}")
                if attempt == 2:
                    return False, "failed", f"Twilio HTTP {e.code}: {err_body}"
            except Exception as e:
                logger.warning(f"[SMS Twilio Attempt {attempt}] Network error: {e}")
                if attempt == 2:
                    return False, "failed", f"Twilio connection error: {e}"

        return False, "failed", "Twilio exhausted retry attempts."


class MSG91Provider(BaseSmsProvider):
    name: str = "msg91"
    URL: str = "https://control.msg91.com/api/v5/flow/"

    def __init__(self, auth_key: Optional[str] = None, sender_id: Optional[str] = None):
        self.auth_key = auth_key or getattr(settings, "MSG91_AUTH_KEY", "")
        self.sender_id = sender_id or getattr(settings, "MSG91_SENDER_ID", "SMTCAT")

    def send_sms(self, to_phone: str, message: str) -> Tuple[bool, str, Optional[str]]:
        if not self.auth_key:
            return False, "failed", "MSG91_AUTH_KEY is not configured."

        clean_num = to_phone.replace("+", "").strip()
        payload = {
            "template_id": getattr(settings, "MSG91_TEMPLATE_ID", ""),
            "sender": self.sender_id,
            "short_url": "0",
            "mobiles": clean_num,
            "message": message
        }

        data = json.dumps(payload).encode("utf-8")
        req = urllib.request.Request(
            self.URL,
            data=data,
            headers={
                "authkey": self.auth_key,
                "Content-Type": "application/json",
                "User-Agent": "SmartCattle/1.0"
            },
            method="POST"
        )

        try:
            with urllib.request.urlopen(req, timeout=10.0) as response:
                res_json = json.loads(response.read().decode("utf-8"))
                if res_json.get("type") == "success":
                    msg_id = res_json.get("message", "success")
                    return True, "sent", str(msg_id)
                return False, "failed", f"MSG91 error: {res_json}"
        except Exception as e:
            return False, "failed", f"MSG91 connection error: {e}"


class MockSmsProvider(BaseSmsProvider):
    name: str = "mock"
    sent_messages: List[Dict[str, Any]] = []

    def send_sms(self, to_phone: str, message: str) -> Tuple[bool, str, Optional[str]]:
        msg_id = f"mock-{uuid4().hex[:8]}"
        record = {
            "id": msg_id,
            "to": to_phone,
            "message": message,
            "timestamp": datetime.now(timezone.utc).isoformat(),
            "status": "sent"
        }
        self.sent_messages.append(record)
        logger.info(f"[SMS MOCK PROVIDER] Dispatched SMS to {to_phone}:\n{message}")
        return True, "sent", msg_id

    @classmethod
    def clear(cls):
        cls.sent_messages.clear()


# ---------------------------------------------------------------------------
# 4. Provider Factory
# ---------------------------------------------------------------------------

def get_sms_provider() -> BaseSmsProvider:
    provider_name = getattr(settings, "SMS_PROVIDER", "fast2sms").lower().strip()
    
    if provider_name == "mock":
        return MockSmsProvider()
    elif provider_name == "twilio":
        return TwilioProvider()
    elif provider_name == "msg91":
        return MSG91Provider()
    elif provider_name == "fast2sms":
        # If API key is not set and we are in test/dev environment, fallback gracefully to mock
        api_key = getattr(settings, "FAST2SMS_API_KEY", "")
        if not api_key and getattr(settings, "ENVIRONMENT", "").lower() in ("test", "development"):
            logger.debug("[SMS Provider] FAST2SMS_API_KEY empty in test/dev, using MockSmsProvider")
            return MockSmsProvider()
        return Fast2SMSProvider()
    
    logger.warning(f"Unknown SMS_PROVIDER '{provider_name}', defaulting to MockSmsProvider")
    return MockSmsProvider()


# ---------------------------------------------------------------------------
# 5. Deduplication State Machine & Alert Pipeline Manager
# ---------------------------------------------------------------------------

class SmsAlertManager:
    """
    Manages SMS notification deduplication and delivery.
    Rules:
    - Send one SMS when a new abnormal condition begins.
    - Suppress repeat SMS for unchanged ongoing abnormal conditions.
    - If a sensor recovers to normal and becomes abnormal again, trigger a new SMS.
    - Send separate alerts for different abnormal parameters.
    - Never send SMS for null/missing sensor readings or simulated data.
    """
    _state: Dict[str, Dict[str, Any]] = {}
    
    @classmethod
    def reset_state(cls):
        """Reset state tracking (useful for unit tests)."""
        cls._state.clear()
        MockSmsProvider.clear()

    @classmethod
    def get_state(cls, cattle_id: str, sensor_key: str) -> Optional[Dict[str, Any]]:
        return cls._state.get(f"{cattle_id}:{sensor_key}")

    @classmethod
    async def process_telemetry_for_sms(
        cls,
        cattle_id: str,
        telemetry: Dict[str, Any],
        predictions: Dict[str, Any],
        db: Optional[Session] = None
    ) -> List[Dict[str, Any]]:
        """
        Main hook into the sensor pipeline.
        Evaluates predictions against the deduplication state machine
        and sends SMS alerts for newly triggered abnormal conditions.
        """
        # Guard: Check if SMS alerts are globally enabled
        if not getattr(settings, "SMS_ALERT_ENABLED", True):
            logger.debug("[SMS Pipeline] SMS alerts disabled in configuration.")
            return []

        # Guard: Never send SMS for simulated / mock hardware readings
        if telemetry.get("is_simulated", False):
            logger.debug(f"[SMS Pipeline] Dropping SMS for simulated packet on {cattle_id}")
            return []

        # Validate recipient phone number
        target_phone = getattr(settings, "target_phone_number", None) or getattr(settings, "ALERT_PHONE_NUMBER", None)
        is_valid, normalized_phone, err_msg = validate_phone_number(target_phone)
        if not is_valid:
            logger.warning(f"[SMS CONFIG ERROR] {err_msg}")
            # If phone number is invalid, record failed attempt in DB if session provided
            if db:
                from app.database import models
                try:
                    for s_key, pred in predictions.items():
                        if pred.get("status") == "abnormal" and telemetry.get(s_key) is not None:
                            log_entry = models.SmsLog(
                                cattle_id=cattle_id,
                                alert_type=s_key.upper(),
                                severity=pred.get("severity", "CRITICAL"),
                                timestamp=datetime.now(timezone.utc),
                                status="failed",
                                reason=err_msg,
                                phone_number=target_phone or "UNSET",
                                message=None,
                                provider=getattr(settings, "SMS_PROVIDER", "unknown")
                            )
                            db.add(log_entry)
                    db.commit()
                except Exception as ex:
                    db.rollback()
                    logger.error(f"Failed to record invalid phone SmsLog: {ex}")
            return []

        dispatched_alerts = []

        # Define evaluated sensors and formatting
        sensors_to_check = [
            ("temperature", "Temperature", "°C", "{:.1f}°C"),
            ("heart_rate", "Heart Rate", " BPM", "{:d} BPM"),
            ("spo2", "SpO2", "%", "{:d}%"),
            ("ph", "pH Level", "", "{:.2f}"),
            ("mems", "Activity / MEMS Sensor", "", "Fall Detected"),
            ("body_weight_kg", "Body Weight", " kg", "{:.1f} kg")
        ]

        now = datetime.now(timezone.utc)

        for sensor_key, display_name, unit, fmt in sensors_to_check:
            # Extract actual sensor reading
            val = telemetry.get(sensor_key)
            if sensor_key == "mems":
                # Special check for fall detection
                if telemetry.get("fall_detected") or (telemetry.get("peak_acceleration") and telemetry.get("peak_acceleration") > 2.5):
                    val = telemetry.get("peak_acceleration", 2.5)
                else:
                    val = None

            # Skip null / missing readings
            if val is None:
                continue

            # Look up prediction status
            pred = predictions.get(sensor_key, {})
            # If fall detected, mems prediction is abnormal
            if sensor_key == "mems" and telemetry.get("fall_detected"):
                pred = {"status": "abnormal", "severity": "CRITICAL", "reason": "Fall Detected"}

            status = pred.get("status", "normal")
            state_key = f"{cattle_id}:{sensor_key}"
            prev_state_info = cls._state.get(state_key, {"state": "normal"})
            prev_state = prev_state_info.get("state", "normal")

            if status == "normal":
                # Sensor is currently in normal physiological range
                if prev_state == "abnormal":
                    # Sensor recovered! Transition state so next abnormal condition re-triggers SMS
                    cls._state[state_key] = {
                        "state": "normal",
                        "recovered_at": now,
                        "last_value": val
                    }
                    logger.info(f"[SMS Pipeline] {cattle_id} {sensor_key} returned to NORMAL ({val}). Resetting alert trigger.")
                continue

            elif status == "abnormal":
                severity = pred.get("severity", "HIGH")
                
                # Check deduplication
                if prev_state == "abnormal":
                    # Ongoing abnormal condition: suppress duplicate SMS
                    logger.debug(f"[SMS DEDUPLICATION] Suppressed repeat SMS for {cattle_id} {sensor_key}. Current: {val}")
                    continue

                # NEW ABNORMAL EVENT!
                event_id = f"EVT-{cattle_id}-{sensor_key[:4].upper()}-{uuid4().hex[:8]}"

                # Format current reading value
                try:
                    if "{:d}" in fmt:
                        formatted_val = fmt.format(int(val))
                    elif "{:.1f}" in fmt or "{:.2f}" in fmt:
                        formatted_val = fmt.format(float(val))
                    else:
                        formatted_val = f"{val}{unit}"
                except Exception:
                    formatted_val = f"{val}{unit}"

                if sensor_key == "mems" and telemetry.get("fall_detected"):
                    formatted_val = f"Fall Detected ({val:.2f}g)" if isinstance(val, (int, float)) else "Fall Detected"

                # Construct formatted SMS
                sms_text = format_sms_message(
                    cattle_id=cattle_id,
                    sensor_name=display_name,
                    reading_value=formatted_val,
                    severity=severity,
                    timestamp=now
                )

                # Send via configured SMS provider
                provider = get_sms_provider()
                success, send_status, result_or_id = provider.send_sms(normalized_phone, sms_text)

                # Update deduplication state
                cls._state[state_key] = {
                    "state": "abnormal",
                    "triggered_at": now,
                    "event_id": event_id,
                    "last_value": val,
                    "severity": severity,
                    "delivery_status": send_status
                }

                # Persist to database if Session provided
                if db:
                    from app.database import models
                    try:
                        # Extract GPS coordinates if available
                        loc_str = None
                        if telemetry.get("gps_lat") and telemetry.get("gps_lon"):
                            loc_str = f"Lat: {telemetry.get('gps_lat')}, Lon: {telemetry.get('gps_lon')}"

                        sms_log = models.SmsLog(
                            cattle_id=cattle_id,
                            alert_type=sensor_key.upper(),
                            severity=severity,
                            timestamp=now,
                            location=loc_str,
                            status=send_status,
                            reason=result_or_id,
                            phone_number=normalized_phone,
                            message=sms_text,
                            provider=provider.name,
                            event_id=event_id
                        )
                        db.add(sms_log)
                        db.commit()
                    except Exception as db_err:
                        db.rollback()
                        logger.error(f"[SMS Pipeline] Failed to persist SmsLog: {db_err}")

                alert_record = {
                    "event_id": event_id,
                    "cattle_id": cattle_id,
                    "sensor": sensor_key,
                    "reading": formatted_val,
                    "severity": severity,
                    "status": send_status,
                    "provider": provider.name,
                    "message": sms_text
                }
                dispatched_alerts.append(alert_record)
                logger.info(f"[SMS DISPATCHED] {event_id} -> {normalized_phone} | {display_name}: {formatted_val} ({severity})")

        return dispatched_alerts


sms_service = SmsAlertManager

