import os
import joblib

class HealthPredictor:
    def __init__(self):
        self.models = {}
        self.load_models()
        
    def load_models(self):
        model_names = ["spo2", "bpm", "mems", "temperature", "ldr", "ph"]
        base_path = os.path.join(os.path.dirname(__file__), "../models")
        
        for name in model_names:
            path = os.path.join(base_path, f"{name}.pkl")
            if os.path.exists(path):
                self.models[name] = joblib.load(path)
            else:
                print(f"Warning: Model {name}.pkl not found")

    def predict(self, sensor_data: dict):
        predictions = {}
        for key, value in sensor_data.items():
            if not isinstance(value, (int, float)):
                continue
                
            model_key = key
            if key == "mems_x":
                model_key = "mems"
                
            if model_key in self.models:
                try:
                    pred = self.models[model_key].predict([[value]])[0]
                    # Attempt to get probability if supported
                    if hasattr(self.models[model_key], "predict_proba"):
                        proba = self.models[model_key].predict_proba([[value]])[0]
                        confidence = round(float(max(proba)), 2)
                    else:
                        confidence = 0.85 # fallback
                except Exception:
                    pred = 1 # default normal
                    confidence = 0.5
                    
                status = "normal" if pred == 1 else "abnormal"
                reason = "Values are within healthy bounds" if status == "normal" else f"{key.capitalize()} detected as abnormal by the model"
                
                predictions[model_key] = {
                    "status": status,
                    "value": value,
                    "confidence": confidence,
                    "reason": reason
                }
                
        return predictions
