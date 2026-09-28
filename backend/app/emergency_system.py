from datetime import datetime, timezone, timedelta
from typing import Dict, Any, Optional
from sqlalchemy.orm import Session
from app.database.models import SmsLog, SensorReading

class EmergencySystem:
    _sms_cooldowns: Dict[str, datetime] = {}
    COOLDOWN_PERIOD = timedelta(minutes=60) # Cooldown per event type per cow

    @classmethod
    def process_critical_event(
        cls,
        db: Session,
        cattle_id: str,
        alert_type: str,
        severity: str,
        message: str
    ) -> None:
        """
        Emergency flow: CRITICAL EVENT -> confirm event -> obtain GPS -> construct SMS -> send SMS -> record attempt -> cooldown
        """
        if severity != "CRITICAL":
            return

        # Cooldown check
        event_key = f"{cattle_id}:{alert_type}"
        now = datetime.now(timezone.utc)
        last_sent = cls._sms_cooldowns.get(event_key)
        
        if last_sent and (now - last_sent) < cls.COOLDOWN_PERIOD:
            # Skip sending repeated SMS for the same ongoing event
            return

        # Obtain GPS
        latest_reading = db.query(SensorReading).filter(
            SensorReading.cattle_id == cattle_id,
            SensorReading.gps_valid == True
        ).order_by(SensorReading.timestamp.desc()).first()

        location_str = "Unknown location"
        if latest_reading and latest_reading.gps_lat and latest_reading.gps_lon:
            location_str = f"Lat: {latest_reading.gps_lat}, Lon: {latest_reading.gps_lon}"

        # Construct SMS
        sms_text = (
            f"URGENT ALARM - Cattle ID: {cattle_id}\n"
            f"Type: {alert_type}\n"
            f"Severity: {severity}\n"
            f"Time: {now.isoformat()}\n"
            f"Location: {location_str}\n"
            f"Details: {message}"
        )

        # Attempt to send SMS
        status, reason = cls._send_sms(sms_text)

        # Record attempt
        sms_log = SmsLog(
            cattle_id=cattle_id,
            alert_type=alert_type,
            severity=severity,
            timestamp=now,
            location=location_str,
            status=status,
            reason=reason
        )
        db.add(sms_log)
        db.commit()

        if status == "sent":
            cls._sms_cooldowns[event_key] = now

    @classmethod
    def _send_sms(cls, message: str) -> tuple[str, str]:
        """
        Dummy logic for sending SMS. Never hardcode the phone number here.
        Returns: (status, reason)
        status: 'attempted', 'sent', or 'failed'
        """
        # In a real system, this would fetch the target phone number from configuration or database
        # phone_number = get_emergency_contact()
        
        # Simulate SMS sending
        try:
            print(f"--- SENDING SMS --- \n{message}\n-------------------")
            return "sent", "SMS dispatched successfully"
        except Exception as e:
            return "failed", str(e)
