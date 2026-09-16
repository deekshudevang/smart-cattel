import os
import pandas as pd
import joblib
from sklearn.ensemble import RandomForestClassifier

def train_and_save():
    dataset_path = "../../data.xlsx"
    if not os.path.exists(dataset_path):
        print("Dataset not found")
        return

    data = pd.read_excel(dataset_path, engine="openpyxl")
    data.columns = data.columns.str.strip()
    for col in data.columns:
        data[col] = pd.to_numeric(data[col], errors='coerce')
    data = data.dropna()

    features = {
        "spo2": data["spo2"],
        "bpm": data["bpm"],
        "mems": data["x"],
        "temperature": data["temp"],
        "ldr": data["ldr"],
        "ph": data["ph"]
    }
    labels = {
        "spo2": data["spo2_label"].astype(int),
        "bpm": data["bpm_label"].astype(int),
        "mems": data["x_label"].astype(int),
        "temperature": data["temp_label"].astype(int),
        "ldr": data["ldr_lable"].astype(int),
        "ph": data["ph_lable"].astype(int)
    }

    os.makedirs("../models", exist_ok=True)
    
    for name in features.keys():
        model = RandomForestClassifier(n_estimators=100, random_state=42)
        model.fit(features[name].values.reshape(-1, 1), labels[name])
        joblib.dump(model, f"../models/{name}.pkl")
        print(f"Saved {name}.pkl")

if __name__ == "__main__":
    train_and_save()
