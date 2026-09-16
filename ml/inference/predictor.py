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
            model_key = key
            if key == "mems_x": # using x for mems anomaly in phase 1
                model_key = "mems"
                
            if model_key in self.models:
                pred = self.models[model_key].predict([[value]])[0]
                predictions[key] = "normal" if pred == 1 else "abnormal" # Assuming 1 is normal
                
        return predictions
