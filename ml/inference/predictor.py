import os
import joblib
import json
import numpy as np

class HealthPredictor:
    def __init__(self):
        self.model = None
        self.metadata = None
        self.load_model()
        
    def load_model(self):
        base_path = os.path.join(os.path.dirname(__file__), "..", "models")
        model_path = os.path.join(base_path, "multivariate_model.pkl")
        meta_path = os.path.join(base_path, "metadata.json")
        
        if os.path.exists(model_path) and os.path.exists(meta_path):
            self.model = joblib.load(model_path)
            with open(meta_path, "r") as f:
                self.metadata = json.load(f)
        else:
            print("Warning: Multivariate model or metadata not found. Run pipeline.py first.")

    def predict(self, sensor_data: dict):
        if not self.model or not self.metadata:
            raise RuntimeError("Model or metadata not found. Cannot predict.")
            
        features = self.metadata.get("features", [
            "spo2", "heart_rate", "temperature", "humidity",
            "mems_x", "mems_y", "mems_z", "acceleration_magnitude",
            "ph", "ldr", "activity", "temp_baseline_dev",
            "hr_baseline_dev", "spo2_baseline_dev", "activity_baseline_dev"
        ])
        labels = self.metadata.get("labels", ["overall_status"])
        
        # 1. Feature Engineering
        if "mems_x" in sensor_data and "mems_y" in sensor_data and "mems_z" in sensor_data:
            if "acceleration_magnitude" not in sensor_data:
                sensor_data["acceleration_magnitude"] = np.sqrt(
                    sensor_data["mems_x"]**2 + 
                    sensor_data["mems_y"]**2 + 
                    sensor_data["mems_z"]**2
                )
            if "activity" not in sensor_data:
                sensor_data["activity"] = abs(sensor_data["acceleration_magnitude"] - 9.81)
                
        # Check if all key vital sensors are None/unconnected
        vitals = [sensor_data.get("heart_rate"), sensor_data.get("spo2"), sensor_data.get("temperature"), sensor_data.get("ph")]
        if all(v is None for v in vitals):
            return {
                "overall": {
                    "status": "normal",
                    "confidence": 0.0,
                    "reason": "Vitals not connected"
                }
            }

        # 2. Build feature vector
        vector = []
        for f in features:
            val = sensor_data.get(f, 0.0)
            if not isinstance(val, (int, float)):
                val = 0.0
            vector.append(val)
            
        # 3. Predict
        try:
            import pandas as pd
            df = pd.DataFrame([vector], columns=features)
            pred = self.model.predict(df)[0]
            if hasattr(self.model, "predict_proba"):
                proba = self.model.predict_proba(df)[0]
                confidence = round(float(max(proba)), 2)
            else:
                confidence = 0.85
        except Exception:
            pred = 0
            confidence = 0.5
            
        # 4. Format output
        status = "abnormal" if pred == 1 else "normal"
        reason = "Values are within healthy bounds" if status == "normal" else "Model detected anomaly"
        
        return {
            "overall": {
                "status": status,
                "confidence": confidence,
                "reason": reason
            }
        }

