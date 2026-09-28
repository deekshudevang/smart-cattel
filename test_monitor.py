import urllib.request
import json
import time
import sys

def check_status():
    try:
        resp = urllib.request.urlopen('http://localhost:8000/api/arduino/status', timeout=2)
        data = json.loads(resp.read().decode())
        print(f"🔌 Arduino Status: {data}")
    except Exception as e:
        print(f"🔌 Arduino Status Error: {e}")

def get_latest_data():
    try:
        resp = urllib.request.urlopen('http://localhost:8000/api/dashboard/summary', timeout=2)
        data = json.loads(resp.read().decode())
        if 'cows' in data and len(data['cows']) > 0:
            cow = data['cows'][0]
            print(f"📡 Latest Payload for {cow.get('id', 'Unknown')}:")
            print(f"   ❤️ BPM/SpO2: {cow.get('heart_rate')}/{cow.get('spo2')}")
            print(f"   🌡️ Temp/Hum: {cow.get('temperature')}/{cow.get('humidity')}")
            print(f"   🧪 pH: {cow.get('ph_level')}")
            print(f"   📈 Activity: {cow.get('activity_level')}")
        else:
            print("📡 No cattle data received yet.")
    except Exception as e:
        print(f"📡 Data Error: {e}")

if __name__ == "__main__":
    print("Starting Hardware Monitor...")
    for i in range(10):
        print(f"\n--- Check {i+1}/10 ---")
        check_status()
        get_latest_data()
        time.sleep(3)
