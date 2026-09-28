from sqlalchemy.orm import Session
from sqlalchemy import func
from datetime import datetime, timedelta
from typing import Dict, Any, Optional

from database import models

class BaselineEngine:
    WINDOWS = {
        "1_hour": timedelta(hours=1),
        "6_hours": timedelta(hours=6),
        "24_hours": timedelta(hours=24),
        "7_days": timedelta(days=7)
    }

    @staticmethod
    def _calculate_metrics(current: Optional[float], baseline: Optional[float]) -> Dict[str, Any]:
        if current is None or baseline is None:
            return {
                "baseline": None,
                "current_value": current,
                "difference": None,
                "percentage_deviation": None,
                "trend": None,
                "status": "insufficient_data"
            }
            
        difference = current - baseline
        percentage_deviation = (difference / baseline * 100) if baseline != 0 else 0.0
        
        trend = "stable"
        if percentage_deviation > 5:
            trend = "increasing"
        elif percentage_deviation < -5:
            trend = "decreasing"
            
        return {
            "baseline": round(baseline, 2),
            "current_value": round(current, 2),
            "difference": round(difference, 2),
            "percentage_deviation": round(percentage_deviation, 2),
            "trend": trend,
            "status": "ok"
        }

    @staticmethod
    def calculate_baselines(db: Session, cattle_id: str, window: str = "24_hours") -> Dict[str, Any]:
        if window not in BaselineEngine.WINDOWS:
            raise ValueError(f"Invalid window. Choose from {list(BaselineEngine.WINDOWS.keys())}")
            
        time_delta = BaselineEngine.WINDOWS[window]
        end_time = datetime.utcnow()
        start_time = end_time - time_delta
        
        results = {}
        
        # Sensor Readings (BPM, SpO2, Temperature)
        latest_sensor = db.query(models.SensorReading).filter(
            models.SensorReading.cattle_id == cattle_id
        ).order_by(models.SensorReading.timestamp.desc()).first()
        
        # Calculate baselines for sensors
        sensor_avg = db.query(
            func.avg(models.SensorReading.heart_rate).label("avg_bpm"),
            func.avg(models.SensorReading.spo2).label("avg_spo2"),
            func.avg(models.SensorReading.temperature).label("avg_temp")
        ).filter(
            models.SensorReading.cattle_id == cattle_id,
            models.SensorReading.timestamp >= start_time,
            models.SensorReading.timestamp <= end_time
        ).first()

        current_bpm = latest_sensor.heart_rate if latest_sensor else None
        current_spo2 = latest_sensor.spo2 if latest_sensor else None
        current_temp = latest_sensor.temperature if latest_sensor else None

        baseline_bpm = sensor_avg.avg_bpm if sensor_avg and sensor_avg.avg_bpm is not None else None
        baseline_spo2 = sensor_avg.avg_spo2 if sensor_avg and sensor_avg.avg_spo2 is not None else None
        baseline_temp = sensor_avg.avg_temp if sensor_avg and sensor_avg.avg_temp is not None else None

        results["bpm"] = BaselineEngine._calculate_metrics(current_bpm, baseline_bpm)
        results["spo2"] = BaselineEngine._calculate_metrics(current_spo2, baseline_spo2)
        results["temperature"] = BaselineEngine._calculate_metrics(current_temp, baseline_temp)
        
        # Activity (Steps)
        latest_activity = db.query(models.Activity).filter(
            models.Activity.cattle_id == cattle_id
        ).order_by(models.Activity.date.desc()).first()
        
        activity_avg = db.query(func.avg(models.Activity.steps)).filter(
            models.Activity.cattle_id == cattle_id,
            models.Activity.date >= start_time,
            models.Activity.date <= end_time
        ).scalar()
        
        current_steps = latest_activity.steps if latest_activity else None
        results["activity"] = BaselineEngine._calculate_metrics(current_steps, activity_avg)
        
        # Feed
        latest_feed = db.query(models.FeedConsumption).filter(
            models.FeedConsumption.cattle_id == cattle_id
        ).order_by(models.FeedConsumption.date.desc()).first()
        
        feed_avg = db.query(func.avg(models.FeedConsumption.quantity)).filter(
            models.FeedConsumption.cattle_id == cattle_id,
            models.FeedConsumption.date >= start_time,
            models.FeedConsumption.date <= end_time
        ).scalar()
        
        current_feed = latest_feed.quantity if latest_feed else None
        results["feed"] = BaselineEngine._calculate_metrics(current_feed, feed_avg)
        
        # Milk
        latest_milk = db.query(models.MilkProduction).filter(
            models.MilkProduction.cattle_id == cattle_id
        ).order_by(models.MilkProduction.date.desc()).first()
        
        milk_avg = db.query(func.avg(models.MilkProduction.quantity)).filter(
            models.MilkProduction.cattle_id == cattle_id,
            models.MilkProduction.date >= start_time,
            models.MilkProduction.date <= end_time
        ).scalar()
        
        current_milk = latest_milk.quantity if latest_milk else None
        results["milk"] = BaselineEngine._calculate_metrics(current_milk, milk_avg)
        
        return results
