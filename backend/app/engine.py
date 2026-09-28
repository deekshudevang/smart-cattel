import json
from datetime import datetime, timezone

class HealthRiskEngine:
    @staticmethod
    def evaluate(
        sensor_data: dict,
        ml_prediction: dict,
        cow_baseline: dict = None,
        activity_data: dict = None,
        feed_data: dict = None,
        milk_production: dict = None,
        device_status: dict = None,
        sensor_validity: dict = None,
        signal_quality: dict = None
    ) -> dict:
        """
        Evaluates the health risk of cattle based on various data sources.
        Does NOT make medical diagnoses.
        """
        factors = []
        score = 100
        confidence = 1.0
        
        def add_factor(factor: str, observed, baseline, reason: str, penalty: int):
            nonlocal score
            factors.append({
                "factor": factor,
                "observed": observed,
                "baseline": baseline,
                "reason": reason
            })
            score -= penalty

        # 1. Sensor Measurement vs Baseline
        if cow_baseline:
            # Check temperature baseline deviation
            temp_baseline = cow_baseline.get("temperature", {})
            if temp_baseline.get("status") == "ok":
                dev = temp_baseline.get("percentage_deviation", 0)
                if dev > 5:
                    add_factor("temperature", temp_baseline.get("current_value"), temp_baseline.get("baseline"), "Above individual baseline", 10)
                elif dev < -5:
                    add_factor("temperature", temp_baseline.get("current_value"), temp_baseline.get("baseline"), "Below individual baseline", 10)
            
            # Check BPM baseline deviation
            bpm_baseline = cow_baseline.get("bpm", {})
            if bpm_baseline.get("status") == "ok":
                dev = bpm_baseline.get("percentage_deviation", 0)
                if dev > 10:
                    add_factor("heart_rate", bpm_baseline.get("current_value"), bpm_baseline.get("baseline"), "Above individual baseline", 5)
                elif dev < -10:
                    add_factor("heart_rate", bpm_baseline.get("current_value"), bpm_baseline.get("baseline"), "Below individual baseline", 5)
                    
            # Check SpO2 baseline deviation
            spo2_baseline = cow_baseline.get("spo2", {})
            if spo2_baseline.get("status") == "ok":
                dev = spo2_baseline.get("percentage_deviation", 0)
                if dev < -2:
                    add_factor("spo2", spo2_baseline.get("current_value"), spo2_baseline.get("baseline"), "Below individual baseline", 10)
                    
            # Check activity baseline deviation
            activity_baseline = cow_baseline.get("activity", {})
            if activity_baseline.get("status") == "ok":
                dev = activity_baseline.get("percentage_deviation", 0)
                if dev < -20:
                    add_factor("activity", activity_baseline.get("current_value"), activity_baseline.get("baseline"), "Significantly below baseline", 10)
                    
            # Check feed baseline deviation
            feed_baseline = cow_baseline.get("feed", {})
            if feed_baseline.get("status") == "ok":
                dev = feed_baseline.get("percentage_deviation", 0)
                if dev < -10:
                    add_factor("feed", feed_baseline.get("current_value"), feed_baseline.get("baseline"), "Below individual baseline", 5)
                    
            # Check milk baseline deviation
            milk_baseline = cow_baseline.get("milk", {})
            if milk_baseline.get("status") == "ok":
                dev = milk_baseline.get("percentage_deviation", 0)
                if dev < -10:
                    add_factor("milk", milk_baseline.get("current_value"), milk_baseline.get("baseline"), "Below individual baseline", 5)
        else:
            # Fallback to generic thresholds if no cow baseline is provided
            temp = sensor_data.get("temperature")
            if temp:
                if temp > 39.5:
                    add_factor("temperature", temp, 39.0, "Above generic baseline", 10)
                elif temp < 38.0:
                    add_factor("temperature", temp, 39.0, "Below generic baseline", 10)
                    
            hr = sensor_data.get("heart_rate")
            if hr:
                if hr > 80:
                    add_factor("heart_rate", hr, 60, "Above generic baseline", 5)
                elif hr < 40:
                    add_factor("heart_rate", hr, 60, "Below generic baseline", 5)
                    
            if activity_data:
                steps = activity_data.get("steps", 0)
                if steps < 1000:
                    add_factor("activity", steps, 1000, "Activity decreased", 10)
                    
            if feed_data:
                feed = feed_data.get("quantity", 0)
                if feed < 10:
                    add_factor("feed", feed, 10, "Feed consumption low", 5)
                    
            if milk_production:
                milk = milk_production.get("quantity", 0)
                if milk < 15:
                    add_factor("milk", milk, 15, "Milk production decreased", 5)
                
        # 2. Fall Events (Anomaly)
        if sensor_data.get("fall_detected"):
            add_factor("fall_event", True, False, "Fall event detected", 30)
            
        # 3. ML Model Prediction
        overall_status = ml_prediction.get("overall", {}).get("status", "normal")
        model_confidence = ml_prediction.get("overall", {}).get("confidence", 0.8)
        
        if overall_status == "abnormal":
            add_factor("ml_prediction", "abnormal", "normal", "Model flagged anomaly", 20)
            confidence = min(confidence, model_confidence)

        # 4. Device Status
        if device_status:
            battery = device_status.get("battery_level", 100)
            if battery < 10:
                add_factor("device_status", battery, 100, "Low battery", 5)
                confidence *= 0.9

        # 5. Sensor validity and signal quality
        if sensor_validity:
            valid_sensors = sum(1 for v in sensor_validity.values() if v)
            total_sensors = len(sensor_validity)
            if total_sensors > 0 and (valid_sensors / total_sensors) < 0.5:
                add_factor("sensor_validity", valid_sensors, total_sensors, "Many invalid readings", 10)
                confidence *= 0.7

        if signal_quality:
            avg_quality = sum(signal_quality.values()) / max(1, len(signal_quality))
            if avg_quality < 50:
                add_factor("signal_quality", avg_quality, 100, "Poor signal quality", 10)
                confidence *= 0.8
                
        # Cap score
        score = max(0, min(100, score))
        
        # Risk level mapping
        if score >= 80:
            risk_level = "NORMAL"
        elif score >= 60:
            risk_level = "WATCH"
        elif score >= 40:
            risk_level = "WARNING"
        else:
            risk_level = "CRITICAL"
            
        return {
            "health_score": score,
            "risk_level": risk_level,
            "confidence": round(confidence, 2),
            "factors": factors,
            "timestamp": datetime.now(timezone.utc).isoformat()
        }
