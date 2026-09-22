import numpy as np
import pandas as pd
from sklearn.ensemble import RandomForestClassifier
import serial
import time
import warnings
import os
import json

warnings.filterwarnings("ignore")

# ---------------- SERIAL ----------------
ser = serial.Serial("COM4", 9600, timeout=1)
time.sleep(3)
ser.reset_input_buffer()

print("Serial Connected")

# ---------------- DATASET ----------------
dataset_path = "data.xlsx"

if not os.path.exists(dataset_path):
    print(f"Dataset not found at {dataset_path}")
    exit()

data = pd.read_excel(dataset_path, engine="openpyxl")
data.columns = data.columns.str.strip()

for col in data.columns:
    data[col] = pd.to_numeric(data[col], errors='coerce')

data = data.dropna()

print("Dataset Loaded")

# -------------------------------------------------
# FEATURES
# -------------------------------------------------
feature_spo2 = data["spo2"]
feature_bpm = data["bpm"]
feature_x = data["x"]
feature_temp = data["temp"]
feature_ldr = data["ldr"]
feature_ph = data["ph"]

# -------------------------------------------------
# LABELS
# -------------------------------------------------
label_spo2 = data["spo2_label"].astype(int)
label_bpm = data["bpm_label"].astype(int)
label_x = data["x_label"].astype(int)
label_temp = data["temp_label"].astype(int)
# Note: kept the original typos ('lable') to match the excel sheet columns
label_ldr = data["ldr_lable"].astype(int)
label_ph = data["ph_lable"].astype(int)

# -------------------------------------------------
# TRAIN FUNCTION
# -------------------------------------------------
def train_model(X, Y):
    model = RandomForestClassifier(
        n_estimators=100,
        random_state=42)
    model.fit(X.values.reshape(-1, 1), Y)
    return model

# -------------------------------------------------
# TRAIN
# -------------------------------------------------
model_spo2 = train_model(feature_spo2, label_spo2)
model_bpm = train_model(feature_bpm, label_bpm)
model_x = train_model(feature_x, label_x)
model_temp = train_model(feature_temp, label_temp)
model_ldr = train_model(feature_ldr, label_ldr)
model_ph = train_model(feature_ph, label_ph)

print("Models Trained Successfully")
print("Waiting for Arduino Data...\n")

# ==================================================
# LOOP
# ==================================================
while True:
    try:
        if ser.in_waiting > 0:
            line = ser.readline().decode('utf-8', errors='ignore').strip()
            if not line:
                continue

            print("RAW :", line)

            # Updated to handle JSON format sent by the current Arduino code
            if line.startswith("{") and line.endswith("}"):
                try:
                    sensor_data = json.loads(line)
                    spo2 = float(sensor_data.get("spo2", 0))
                    bpm = float(sensor_data.get("bpm", 0))
                    temp = float(sensor_data.get("temperature", 0))
                    x = float(sensor_data.get("mems_x", 0))
                    ph = float(sensor_data.get("ph", 0))
                    ldr = float(sensor_data.get("ldr", 0))
                    
                    print("\n===============================")
                    print("SMART CATTLE HEALTH MONITOR")
                    print("===============================")
                    print("SpO2 :", spo2)
                    print("BPM  :", bpm)
                    print("TEMP :", temp)
                    print("X    :", x)
                    print("PH   :", ph)
                    print("LDR  :", ldr)

                    # ---------------------------------
                    # PREDICTION
                    # ---------------------------------
                    spo2_pred = model_spo2.predict([[spo2]])[0]
                    bpm_pred = model_bpm.predict([[bpm]])[0]
                    temp_pred = model_temp.predict([[temp]])[0]
                    x_pred = model_x.predict([[x]])[0]
                    ph_pred = model_ph.predict([[ph]])[0]
                    ldr_pred = model_ldr.predict([[ldr]])[0]

                    print("\n------ Prediction ------")
                    print("SpO2 :", spo2_pred)
                    print("BPM  :", bpm_pred)
                    print("TEMP :", temp_pred)
                    print("X    :", x_pred)
                    print("PH   :", ph_pred)
                    print("LDR  :", ldr_pred)
                    print("===============================\n")

                    # ---------------------------------
                    # SEND TO ARDUINO
                    # ---------------------------------
                    # Format: a1b1c1d1e1f1g
                    reply = (
                        "a" + str(spo2_pred) +
                        "b" + str(bpm_pred) +
                        "c" + str(temp_pred) +
                        "d" + str(x_pred) +
                        "e" + str(ph_pred) +
                        "f" + str(ldr_pred) +
                        "g"
                    )

                    ser.write((reply + "\n").encode())
                    print("Sent :", reply)

                except json.JSONDecodeError:
                    print("Error parsing JSON:", line)
            else:
                print("Format Error (Not JSON) :", line)

    except Exception as e:
        print("Exception occurred:", e)
        time.sleep(1)
