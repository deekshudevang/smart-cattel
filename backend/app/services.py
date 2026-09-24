from sqlalchemy.orm import Session
from database import models
from database.database import SessionLocal
import sys
import os

PROJECT_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
sys.path.insert(0, os.path.join(PROJECT_ROOT, "ml", "inference"))
from predictor import HealthPredictor

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
                bpm_status=preds.get("bpm", {}).get("status", "normal"),
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

class AlertService:
    @staticmethod
    async def broadcast_alert(manager, cattle_id: str, data: dict, preds: dict):
        import json
        overall = "abnormal" if any(v.get("status") == "abnormal" for v in preds.values()) else "normal"
        fall_detected = preds.get("mems", {}).get("status") == "abnormal"
        health_with_overall = dict(preds)
        health_with_overall["overall"] = overall
        health_with_overall["fall_detected"] = fall_detected
        payload = json.dumps({
            "type": "sensor_update",
            "cattle_id": cattle_id,
            "data": data,
            "health": health_with_overall,
        })
        try:
            await manager.broadcast(payload)
        except Exception as e:
            print(f"Failed to broadcast alert: {e}")

class ValidationService:
    @staticmethod
    def validate(data: dict) -> dict:
        if "cattle_id" not in data:
            data["cattle_id"] = "CATTLE-001"
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
        logger.info(
            f"[SENSOR IN] cattle={validated_data.get('cattle_id')} "
            f"bpm={validated_data.get('bpm')} spo2={validated_data.get('spo2')} "
            f"temp={validated_data.get('temperature')} ph={validated_data.get('ph')} "
            f"ldr={validated_data.get('ldr')} "
            f"mems=({validated_data.get('mems_x')},{validated_data.get('mems_y')},{validated_data.get('mems_z')})"
        )
        reading_id = DatabaseService.save_reading(validated_data)
        preds = self.prediction_service.predict(validated_data)
        DatabaseService.save_prediction(reading_id, preds)
        await AlertService.broadcast_alert(manager, validated_data["cattle_id"], validated_data, preds)
        logger.info(f"[SENSOR SAVED] reading_id={reading_id} overall={'abnormal' if any(v.get('status')=='abnormal' for v in preds.values()) else 'normal'}")
        return {"reading_id": reading_id, "predictions": preds}
