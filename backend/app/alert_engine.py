from datetime import datetime, timezone, timedelta
from typing import Dict, Any, Optional
import json

class AlertType:
    SPO2 = "SPO2"
    HEART_RATE = "HEART_RATE"
    TEMPERATURE = "TEMPERATURE"
    PH = "PH"
    ACTIVITY = "ACTIVITY"
    FALL = "FALL"
    FEED = "FEED"
    MILK = "MILK"
    DEVICE_OFFLINE = "DEVICE_OFFLINE"
    SENSOR_FAILURE = "SENSOR_FAILURE"
    HEALTH_RISK = "HEALTH_RISK"

class AlertSeverity:
    INFO = "INFO"
    WARNING = "WARNING"
    CRITICAL = "CRITICAL"

class AlertLifecycle:
    DETECTED = "DETECTED"
    ACTIVE = "ACTIVE"
    ACKNOWLEDGED = "ACKNOWLEDGED"
    RESOLVED = "RESOLVED"

class AlertService:
    # State tracking: { "cattle_id:alert_type": { "last_alert_time": datetime, "status": "ACTIVE" } }
    _active_alerts: Dict[str, Dict[str, Any]] = {}
    
    # Configuration
    COOLDOWN_PERIODS = {
        AlertSeverity.INFO: timedelta(minutes=60),
        AlertSeverity.WARNING: timedelta(minutes=30),
        AlertSeverity.CRITICAL: timedelta(minutes=5),
    }

    @classmethod
    def process_alert(cls, 
        cattle_id: str,
        device_id: str,
        alert_type: str,
        severity: str,
        message: str,
        value: Any,
        confidence: float,
        threshold: Optional[Any] = None
    ) -> Optional[Dict[str, Any]]:
        """
        Process an alert, apply deduplication/cooldown.
        Returns the alert payload if it should be emitted, otherwise None.
        """
        now = datetime.now(timezone.utc)
        alert_key = f"{cattle_id}:{alert_type}"
        
        # Check cooldown
        if alert_key in cls._active_alerts:
            last_alert = cls._active_alerts[alert_key]
            last_time = last_alert.get("timestamp")
            
            # If still active, check cooldown
            if last_alert.get("status") in [AlertLifecycle.DETECTED, AlertLifecycle.ACTIVE]:
                # If severity worsened, we might bypass cooldown, but for simplicity we rely on cooldowns
                current_severity = last_alert.get("severity")
                # bypass cooldown if upgrading to CRITICAL from non-critical
                bypass_cooldown = severity == AlertSeverity.CRITICAL and current_severity != AlertSeverity.CRITICAL
                
                if not bypass_cooldown:
                    cooldown = cls.COOLDOWN_PERIODS.get(severity, timedelta(minutes=15))
                    if last_time and (now - last_time) < cooldown:
                        # Deduplicate: Ignore alert as it's in cooldown
                        return None
                    
        # Generate new alert payload
        alert_payload = {
            "cattle_id": cattle_id,
            "device_id": device_id,
            "type": alert_type,
            "severity": severity,
            "message": message,
            "value": value,
            "threshold": threshold,
            "confidence": confidence,
            "timestamp": now.isoformat(),
            "status": AlertLifecycle.DETECTED
        }
        
        cls._active_alerts[alert_key] = {
            "timestamp": now,
            "status": AlertLifecycle.DETECTED,
            "severity": severity,
            "payload": alert_payload
        }
        
        return alert_payload

    @classmethod
    async def process_alert_async(cls, 
        cattle_id: str,
        device_id: str,
        alert_type: str,
        severity: str,
        message: str,
        value: Any,
        confidence: float,
        threshold: Optional[Any] = None,
        websocket_manager = None
    ) -> Optional[Dict[str, Any]]:
        """
        Processes an alert, handling deduplication, and broadcasts CRITICAL alerts.
        """
        alert_payload = cls.process_alert(
            cattle_id=cattle_id,
            device_id=device_id,
            alert_type=alert_type,
            severity=severity,
            message=message,
            value=value,
            confidence=confidence,
            threshold=threshold
        )
        
        if alert_payload:
            # Upgrade state to active
            alert_key = f"{cattle_id}:{alert_type}"
            if alert_key in cls._active_alerts:
                cls._active_alerts[alert_key]["status"] = AlertLifecycle.ACTIVE
                alert_payload["status"] = AlertLifecycle.ACTIVE

            if alert_payload["severity"] == AlertSeverity.CRITICAL and websocket_manager:
                try:
                    await websocket_manager.broadcast({
                        "type": "critical_alert",
                        "alert": alert_payload
                    })
                except Exception as e:
                    print(f"Broadcast failed: {e}")
                
        return alert_payload

    @classmethod
    def resolve_alert(cls, cattle_id: str, alert_type: str):
        alert_key = f"{cattle_id}:{alert_type}"
        if alert_key in cls._active_alerts:
            cls._active_alerts[alert_key]["status"] = AlertLifecycle.RESOLVED
            cls._active_alerts[alert_key]["timestamp"] = datetime.now(timezone.utc)
