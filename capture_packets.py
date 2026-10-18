import asyncio
import json
import websockets

async def capture():
    uri = "ws://localhost:8000/ws/cattle/CATTLE-ALL"
    packets = []
    print("Connecting to", uri)
    async with websockets.connect(uri) as ws:
        print("Connected! Collecting 30 packets...")
        while len(packets) < 30:
            msg = await ws.recv()
            data = json.loads(msg)
            # Only care about sensor_update events
            event_type = data.get("event") or data.get("type")
            if event_type == "sensor_update":
                packets.append(data)
                payload = data.get("payload", {}).get("data", {}) if "payload" in data else data.get("data", {})
                print(
                    f"Packet {len(packets):2d}: "
                    f"LDR={payload.get('ldr')} "
                    f"Accel=({payload.get('acceleration_x')}, {payload.get('acceleration_y')}, {payload.get('acceleration_z')}) "
                    f"Steps={payload.get('step_count_today')} "
                    f"HR={payload.get('heart_rate')} "
                    f"SpO2={payload.get('spo2')} "
                    f"Temp={payload.get('temperature')} "
                    f"pH={payload.get('ph')}"
                )
            else:
                print("Control msg:", event_type)
    
    with open("30_live_packets.json", "w") as f:
        json.dump(packets, f, indent=2)
    print("Saved 30 packets to 30_live_packets.json")

if __name__ == "__main__":
    asyncio.run(capture())
