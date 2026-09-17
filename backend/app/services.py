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
            reading = models.SensorReading(**data)
            db.add(reading)
            db.commit()
            db.refresh(reading)
            return reading.id
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
        finally:
            db.close()

class AlertService:
    @staticmethod
    async def broadcast_alert(manager, cattle_id: str, data: dict, preds: dict):
        import json
        payload = json.dumps({
            "type": "sensor_update",
            "cattle_id": cattle_id,
            "data": data,
            "health": preds,
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
        reading_id = DatabaseService.save_reading(validated_data)
        preds = self.prediction_service.predict(validated_data)
        DatabaseService.save_prediction(reading_id, preds)
        await AlertService.broadcast_alert(manager, validated_data["cattle_id"], validated_data, preds)
        return {"reading_id": reading_id, "predictions": preds}
