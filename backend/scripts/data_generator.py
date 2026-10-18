import asyncio
import httpx
import random
import time
from datetime import datetime

API_URL = "http://127.0.0.1:8000/api/sensor/data"
CATTLE_IDS = ["C-001", "C-002", "C-003"]
DEVICE_IDS = ["DEV-101", "DEV-102", "DEV-103"]

async def send_data():
    async with httpx.AsyncClient() as client:
        while True:
            for idx in range(len(CATTLE_IDS)):
                data = {
                    "cattle_id": CATTLE_IDS[idx],
                    "device_id": DEVICE_IDS[idx],
                    "heart_rate": random.uniform(60, 90),
                    "spo2": random.uniform(90, 100),
                    "temperature": random.uniform(37.5, 39.5),
                    "ph": random.uniform(5.5, 7.0),
                    "ldr": random.uniform(100, 1000),
                    "mems_x": random.uniform(-1, 1),
                    "mems_y": random.uniform(-1, 1),
                    "mems_z": random.uniform(9, 10),
                    "timestamp": time.time()
                }
                
                try:
                    response = await client.post(API_URL, json=data)
                    print(f"Sent data for {CATTLE_IDS[idx]}: {response.status_code}")
                except Exception as e:
                    print(f"Failed to send data: {e}")
            
            await asyncio.sleep(5)

if __name__ == "__main__":
    print("Starting smart cattle data generator...")
    asyncio.run(send_data())
