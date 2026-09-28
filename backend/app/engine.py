import json

class HealthRiskEngine:
    @staticmethod
    def evaluate(
        sensor_data: dict,
        ml_prediction: dict,
        cow_baseline: dict = None,
        activity_data: dict = None,
        feed_data: dict = None,
        milk_production: dict = None
    ) -> dict:
        """
        Evaluates the health risk of cattle based on various data sources.
        Does NOT make medical diagnoses.
        """
        if cow_baseline is None:
            cow_baseline = {
                "temperature": (38.0, 39.5),
                "heart_rate": (40, 80),
                "spo2": (95, 100)
            }
            
        factors = []
        score = 100
        
        # 1. Sensor Measurement vs Baseline
        temp = sensor_data.get("temperature")
        if temp:
            min_t, max_t = cow_baseline.get("temperature", (38.0, 39.5))
            if temp > max_t:
                factors.append("temperature above baseline")
                score -= 10
            elif temp < min_t:
                factors.append("temperature below baseline")
                score -= 10
                
        hr = sensor_data.get("heart_rate")
        if hr:
            min_hr, max_hr = cow_baseline.get("heart_rate", (40, 80))
            if hr > max_hr:
                factors.append("heart rate above baseline")
                score -= 5
            elif hr < min_hr:
                factors.append("heart rate below baseline")
                score -= 5
                
        # 2. Fall Events (Anomaly)
        if sensor_data.get("fall_detected"):
            factors.append("fall event detected")
            score -= 30
            
        # 3. ML Model Prediction
        overall_status = ml_prediction.get("overall", {}).get("status", "normal")
        model_confidence = ml_prediction.get("overall", {}).get("confidence", 0.5)
        
        if overall_status == "abnormal":
            factors.append("model prediction abnormal")
            score -= 20
            
        # 4. Activity, Feed, Milk (Historical/DB Context)
        if activity_data:
            steps = activity_data.get("steps", 0)
            if steps < 1000:
                factors.append("activity decreased")
                score -= 10
                
        if feed_data:
            feed = feed_data.get("quantity", 0)
            if feed < 10:
                factors.append("feed consumption low")
                score -= 5
                
        if milk_production:
            milk = milk_production.get("quantity", 0)
            if milk < 15:
                factors.append("milk production decreased")
                score -= 5
                
        # Cap score
        score = max(0, min(100, score))
        
        # Risk level mapping
        if score >= 80:
            risk_level = "normal"
        elif score >= 60:
            risk_level = "watch"
        elif score >= 40:
            risk_level = "warning"
        else:
            risk_level = "critical"
            
        return {
            "health_score": score,
            "risk_level": risk_level,
            "confidence": model_confidence,
            "factors": factors
        }
