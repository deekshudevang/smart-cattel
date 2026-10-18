import asyncio
import json
import sqlite3
import urllib.request
import websockets
from datetime import datetime

async def capture():
    uri = "ws://127.0.0.1:8000/ws/cattle/CATTLE-ALL?client_id=proof_collector"
    print(f"Connecting to {uri}...", flush=True)
    packets = []
    
    async with websockets.connect(uri) as ws:
        print("Connected to WebSocket successfully. Listening for packets...", flush=True)
        while len(packets) < 30:
            msg = await ws.recv()
            event = json.loads(msg)
            # Filter for sensor_update events for CATTLE-001
            if event.get("event_type") == "sensor_update" and event.get("cattle_id") == "CATTLE-001":
                payload = event.get("payload", {})
                sensor_data = payload.get("data", payload)
                packets.append({
                    "sequence": len(packets) + 1,
                    "event_timestamp": event.get("timestamp"),
                    "sensor_data": sensor_data,
                    "health": payload.get("health", {})
                })
                seq = len(packets)
                print(
                    f"[{seq:02d}/30] LDR={sensor_data.get('ldr_value') or sensor_data.get('ldr')} "
                    f"HR={sensor_data.get('heart_rate_bpm') or sensor_data.get('bpm')} "
                    f"SpO2={sensor_data.get('spo2_percent') or sensor_data.get('spo2')} "
                    f"Temp={sensor_data.get('temperature_c') or sensor_data.get('temperature')} "
                    f"pH={sensor_data.get('ph_value') or sensor_data.get('ph')} "
                    f"StepsToday={sensor_data.get('step_count_today')} "
                    f"Lifetime={sensor_data.get('step_count_lifetime')} "
                    f"Accel=({sensor_data.get('acceleration_x')},{sensor_data.get('acceleration_y')},{sensor_data.get('acceleration_z')})",
                    flush=True
                )

    # Check newest DB records in smart_cattle.db
    conn = sqlite3.connect("backend/smart_cattle.db")
    cursor = conn.cursor()
    cursor.execute("""
        SELECT id, cattle_id, timestamp, heart_rate, spo2, temperature, ph, ldr, mems_x, mems_y, mems_z, step_count_today, step_count_lifetime
        FROM sensor_readings
        WHERE cattle_id = 'CATTLE-001'
        ORDER BY id DESC LIMIT 5
    """)
    db_rows = cursor.fetchall()
    conn.close()

    # Check REST API /api/cattle/CATTLE-001/latest
    req = urllib.request.urlopen("http://127.0.0.1:8000/api/cattle/CATTLE-001/latest")
    api_latest = json.loads(req.read().decode())

    # Check REST API /api/cows/CATTLE-001
    req2 = urllib.request.urlopen("http://127.0.0.1:8000/api/cows/CATTLE-001")
    cow_profile = json.loads(req2.read().decode())

    out_file = "phase2_proof.json"
    with open(out_file, "w") as f:
        json.dump({
            "collected_at": datetime.now().isoformat(),
            "packet_count": len(packets),
            "packets": packets,
            "db_recent_rows": db_rows,
            "api_latest": api_latest,
            "api_cow_profile": cow_profile
        }, f, indent=2)

    print(f"Captured {len(packets)} live packets saved to {out_file}", flush=True)

if __name__ == "__main__":
    asyncio.run(capture())
