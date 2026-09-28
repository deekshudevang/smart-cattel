from sqlalchemy.orm import Session
from database import models
from database.database import SessionLocal
import sys
import os

PROJECT_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
sys.path.insert(0, os.path.join(PROJECT_ROOT, "ml", "inference"))
from predictor import HealthPredictor
from fall_detection import fall_detector
from engine import HealthRiskEngine

class DatabaseService:
    @staticmethod
    def save_reading(data: dict) -> int:
        db = SessionLocal()
        try:
            valid_keys = models.SensorReading.__table__.columns.keys()
            filtered_data = {k: v for k, v in data.items() if k in valid_keys}
            reading = models.SensorReading(**filtered_data)
            db.add(reading)
            db.commit()
            db.refresh(reading)
            return reading.id
        except Exception:
            db.rollback()
            raise
        finally:
            db.close()

    @staticmethod
    def save_prediction(reading_id: int, preds: dict):
        db = SessionLocal()
        try:
            overall = "abnormal" if any(v.get("status") == "abnormal" for v in preds.values()) else "normal"
            prediction = models.Prediction(
                reading_id=reading_id,
                spo2_status=preds.get("spo2", {}).get("status", "normal"),
                heart_rate_status=preds.get("heart_rate", {}).get("status", "normal"),
                temperature_status=preds.get("temperature", {}).get("status", "normal"),
                mems_status=preds.get("mems", {}).get("status", "normal"),
                ph_status=preds.get("ph", {}).get("status", "normal"),
                ldr_status=preds.get("ldr", {}).get("status", "normal"),
                overall_status=overall,
            )
            db.add(prediction)
            db.commit()
        except Exception:
            db.rollback()
            raise
        finally:
            db.close()

    @staticmethod
    def save_health_assessment(cattle_id: str, assessment: dict):
        import json
        db = SessionLocal()
        try:
            ha = models.HealthAssessment(
                cattle_id=cattle_id,
                health_score=assessment.get("health_score"),
                risk_level=assessment.get("risk_level"),
                confidence=assessment.get("confidence"),
                factors=json.dumps(assessment.get("factors", []))
            )
            db.add(ha)
            db.commit()
        except Exception:
            db.rollback()
        finally:
            db.close()

class AlertService:
    @staticmethod
    async def broadcast_alert(manager, cattle_id: str, data: dict, preds: dict, assessment: dict = None):
        import json
        overall = "abnormal" if any(v.get("status") == "abnormal" for v in preds.values()) else "normal"
        fall_detected = preds.get("mems", {}).get("status") == "abnormal"
        health_with_overall = dict(preds)
        health_with_overall["overall"] = overall
        health_with_overall["fall_detected"] = fall_detected
        if assessment:
            health_with_overall["assessment"] = assessment
        payload = {
            "type": "sensor_update",
            "cattle_id": cattle_id,
            "data": data,
            "health": health_with_overall,
        }
        try:
            await manager.broadcast(payload)
        except Exception as e:
            print(f"Failed to broadcast alert: {e}")

    @staticmethod
    def create_db_alert(cattle_id: str, alert_type: str, parameter: str, value: str, severity: str = "Critical"):
        db = SessionLocal()
        try:
            alert = models.Alert(
                cattle_id=cattle_id,
                alert_type=alert_type,
                parameter=parameter,
                actual_value=value,
                severity=severity
            )
            db.add(alert)
            db.commit()
        except Exception:
            db.rollback()
        finally:
            db.close()

class ValidationService:
    _last_sequence = {}

    @classmethod
    def validate(cls, data: dict) -> dict:
        device_id = data.get("device_id", "SC-001")
        seq = data.get("sequence", 0)
        
        last_seq = cls._last_sequence.get(device_id, -1)
        if seq <= last_seq and seq != 0:
            raise ValueError(f"Stale sequence {seq}, expected > {last_seq}")
        cls._last_sequence[device_id] = seq

        data["cattle_id"] = device_id
        if "bpm" in data:
            data["heart_rate"] = data["bpm"]

        return data

class PredictionService:
    def __init__(self):
        self.predictor = HealthPredictor()

    def predict(self, data: dict) -> dict:
        return self.predictor.predict(data)

class SensorService:
    def __init__(self, prediction_service: PredictionService):
        self.prediction_service = prediction_service

    async def process_reading(self, data: dict, manager):
        validated_data = ValidationService.validate(data)
        import logging
        logger = logging.getLogger("sensor_service")
        
        # Fall detection processing
        cattle_id = validated_data.get('cattle_id')
        mems_x = validated_data.get('mems_x')
        mems_y = validated_data.get('mems_y')
        mems_z = validated_data.get('mems_z')
        timestamp = validated_data.get('timestamp', 0)
        
        fall_info = fall_detector.process(cattle_id, mems_x, mems_y, mems_z, timestamp)
        validated_data.update(fall_info)
        
        logger.info(
            f"[SENSOR IN] cattle={validated_data.get('cattle_id')} "
            f"heart_rate={validated_data.get('heart_rate')} spo2={validated_data.get('spo2')} "
            f"temp={validated_data.get('temperature')} ph={validated_data.get('ph')} "
            f"ldr={validated_data.get('ldr')} "
            f"mems=({validated_data.get('mems_x')},{validated_data.get('mems_y')},{validated_data.get('mems_z')}) "
            f"fall_detected={validated_data.get('fall_detected')}"
        )
        
        reading_id = DatabaseService.save_reading(validated_data)
        preds = self.prediction_service.predict(validated_data)
        
        # Override mems status if fall is detected
        if validated_data.get('fall_detected'):
            if "mems" not in preds:
                preds["mems"] = {}
            preds["mems"]["status"] = "abnormal"
            preds["mems"]["reason"] = f"Fall Detected! Confidence: {validated_data.get('fall_confidence')}"
            
            # Save critical alert to DB
            AlertService.create_db_alert(
                cattle_id=cattle_id,
                alert_type="Fall Detected",
                parameter="mems",
                value=f"Confidence: {validated_data.get('fall_confidence')}"
            )
            
        DatabaseService.save_prediction(reading_id, preds)
        
        # Engine evaluation
        ml_pred_for_engine = {"overall": {"status": "abnormal" if any(v.get('status') == 'abnormal' for v in preds.values()) else "normal"}}
        assessment = HealthRiskEngine.evaluate(validated_data, ml_pred_for_engine)
        DatabaseService.save_health_assessment(cattle_id, assessment)
        
        await AlertService.broadcast_alert(manager, validated_data["cattle_id"], validated_data, preds, assessment)
        logger.info(f"[SENSOR SAVED] reading_id={reading_id} overall={'abnormal' if any(v.get('status')=='abnormal' for v in preds.values()) else 'normal'} risk={assessment.get('risk_level')}")
        return {"reading_id": reading_id, "predictions": preds}
