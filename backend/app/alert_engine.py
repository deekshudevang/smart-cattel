from datetime import datetime
from database import models
from database.database import SessionLocal
import json

class AlertEngine:
    ALERT_TYPES = [
        "TEMPERATURE", "HEART_RATE", "SPO2", "FALL", "PH", 
        "ACTIVITY", "FEED", "MILK", "DEVICE_OFFLINE", 
        "SENSOR_FAILURE", "HEALTH_RISK"
    ]
    
    SEVERITIES = ["INFO", "WARNING", "CRITICAL"]
    STATUSES = ["active", "acknowledged", "resolved"]

    @staticmethod
    async def create_alert(
        manager,
        cattle_id: str,
        alert_type: str,
        severity: str,
        message: str,
        device_id: str = None,
        value: float = None,
        threshold: float = None,
        confidence: float = None
    ):
        if alert_type not in AlertEngine.ALERT_TYPES:
            raise ValueError(f"Invalid alert type: {alert_type}")
        if severity not in AlertEngine.SEVERITIES:
            raise ValueError(f"Invalid severity: {severity}")

        db = SessionLocal()
        try:
            # Prevent duplicate active alerts for the same condition
            existing_alert = db.query(models.Alert).filter(
                models.Alert.cattle_id == cattle_id,
                models.Alert.type == alert_type,
                models.Alert.status == "active"
            ).first()

            if existing_alert:
                return existing_alert

            alert = models.Alert(
                cattle_id=cattle_id,
                device_id=device_id,
                type=alert_type,
                severity=severity,
                message=message,
                value=value,
                threshold=threshold,
                confidence=confidence,
                status="active"
            )
            db.add(alert)
            db.commit()
            db.refresh(alert)
            
            # Send alert through WebSocket
            alert_payload = {
                "id": alert.id,
                "cattle_id": alert.cattle_id,
                "device_id": alert.device_id,
                "type": alert.type,
                "severity": alert.severity,
                "message": alert.message,
                "value": alert.value,
                "threshold": alert.threshold,
                "confidence": alert.confidence,
                "created_at": alert.created_at.isoformat() if alert.created_at else None,
                "status": alert.status
            }
            
            payload = {
                "type": "new_alert",
                "alert": alert_payload
            }
            try:
                await manager.broadcast(payload)
            except Exception as e:
                print(f"Failed to broadcast alert: {e}")
            
            return alert
        except Exception as e:
            db.rollback()
            raise e
        finally:
            db.close()

    @staticmethod
    def acknowledge_alert(alert_id: int):
        db = SessionLocal()
        try:
            alert = db.query(models.Alert).filter(models.Alert.id == alert_id).first()
            if alert and alert.status == "active":
                alert.status = "acknowledged"
                alert.acknowledged_at = datetime.utcnow()
                db.commit()
                db.refresh(alert)
            return alert
        except Exception as e:
            db.rollback()
            raise e
        finally:
            db.close()

    @staticmethod
    def resolve_alert(alert_id: int):
        db = SessionLocal()
        try:
            alert = db.query(models.Alert).filter(models.Alert.id == alert_id).first()
            if alert and alert.status in ["active", "acknowledged"]:
                alert.status = "resolved"
                alert.resolved_at = datetime.utcnow()
                db.commit()
                db.refresh(alert)
            return alert
        except Exception as e:
            db.rollback()
            raise e
        finally:
            db.close()

    @staticmethod
    def get_active_alerts(cattle_id: str = None):
        db = SessionLocal()
        try:
            query = db.query(models.Alert).filter(models.Alert.status.in_(["active", "acknowledged"]))
            if cattle_id:
                query = query.filter(models.Alert.cattle_id == cattle_id)
            return query.all()
        finally:
            db.close()
