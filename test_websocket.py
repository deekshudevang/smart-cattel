import requests
import time
import random
import json

URL = "http://localhost:8000/api/sensor/data"
CATTLE_IDS = ["CATTLE-001", "CATTLE-002", "CATTLE-003"]

def generate_fake_data(cattle_id, is_abnormal=False):
    if is_abnormal:
        return {
            "cattle_id": cattle_id,
            "spo2": random.randint(80, 89),
            "bpm": random.randint(120, 160),
            "temperature": round(random.uniform(40.0, 42.0), 1),
            "humidity": round(random.uniform(40, 80), 1),
            "mems_x": round(random.uniform(5, 10), 2),
            "mems_y": round(random.uniform(5, 10), 2),
            "mems_z": round(random.uniform(2, 5), 2),
            "ph": round(random.uniform(4.0, 5.0), 1),
            "ldr": random.randint(10, 50),
        }
    else:
        return {
            "cattle_id": cattle_id,
            "spo2": random.randint(95, 99),
            "bpm": random.randint(60, 80),
            "temperature": round(random.uniform(37.5, 39.0), 1),
            "humidity": round(random.uniform(40, 80), 1),
            "mems_x": round(random.uniform(-1, 1), 2),
            "mems_y": round(random.uniform(-1, 1), 2),
            "mems_z": round(random.uniform(9, 11), 2),
            "ph": round(random.uniform(6.0, 7.5), 1),
            "ldr": random.randint(300, 800),
        }

print(f"Starting to send fake sensor data to {URL}...")
print("Press Ctrl+C to stop.")

iteration = 0
try:
    while True:
        iteration += 1
        # Make every 5th reading abnormal to see alerts
        is_abnormal = (iteration % 5 == 0)
        
        for cattle_id in CATTLE_IDS:
            data = generate_fake_data(cattle_id, is_abnormal=is_abnormal)
            try:
                response = requests.post(URL, json=data)
                if response.status_code == 200:
                    status = "ABNORMAL" if is_abnormal else "NORMAL"
                    print(f"[{status}] Sent data for {cattle_id}: {json.dumps(data)}")
                else:
                    print(f"Failed to send data. Status code: {response.status_code}")
            except requests.exceptions.RequestException as e:
                print(f"Error connecting to server: {e}")
                
        print("-" * 50)
        time.sleep(3) # Send updates every 3 seconds
except KeyboardInterrupt:
    print("\nStopped sending data.")
