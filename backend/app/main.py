import sys
import os
import json
import asyncio
import threading
import time
import random

# Fix paths so imports work from project root
PROJECT_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, PROJECT_ROOT)

from fastapi import FastAPI, WebSocket, WebSocketDisconnect, Depends, Query
from fastapi.middleware.cors import CORSMiddleware
from sqlalchemy.orm import Session
from database.database import engine, SessionLocal, get_db, Base
from database import models
from websocket.manager import manager

# ML predictor — load directly since ml/ is at project root
sys.path.insert(0, os.path.join(PROJECT_ROOT, "ml", "inference"))
from predictor import HealthPredictor

# Create tables
models.Base.metadata.create_all(bind=engine)

app = FastAPI(title="Smart Cattle Health Monitoring API")

# Allow Android app to connect
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
)

predictor = HealthPredictor()

# ── Sensor data simulator (replaces Arduino when hardware is absent) ──

def generate_fake_reading(cattle_id: str) -> dict:
    return {
        "cattle_id": cattle_id,
        "spo2": random.randint(90, 99),
        "bpm": random.randint(60, 120),
        "temperature": round(random.uniform(37.5, 40.0), 1),
        "humidity": round(random.uniform(40, 80), 1),
        "mems_x": round(random.uniform(-2, 2), 2),
        "mems_y": round(random.uniform(-2, 2), 2),
        "mems_z": round(random.uniform(8, 12), 2),
        "ph": round(random.uniform(5.5, 8.0), 1),
        "ldr": random.randint(100, 900),
    }

def save_reading_to_db(data: dict):
    db = SessionLocal()
    try:
        reading = models.SensorReading(
            cattle_id=data.get("cattle_id"),
            spo2=data.get("spo2"),
            bpm=data.get("bpm"),
            temperature=data.get("temperature"),
            humidity=data.get("humidity"),
            mems_x=data.get("mems_x"),
            mems_y=data.get("mems_y"),
            mems_z=data.get("mems_z"),
            ph=data.get("ph"),
            ldr=data.get("ldr"),
        )
        db.add(reading)
        db.commit()
        db.refresh(reading)
        return reading.id
    finally:
        db.close()

def save_prediction_to_db(reading_id, preds):
    db = SessionLocal()
    try:
        overall = "abnormal" if any(v == "abnormal" for v in preds.values()) else "normal"
        prediction = models.Prediction(
            reading_id=reading_id,
            spo2_status=preds.get("spo2", "normal"),
            bpm_status=preds.get("bpm", "normal"),
            temperature_status=preds.get("temperature", "normal"),
            mems_status=preds.get("mems", "normal"),
            ph_status=preds.get("ph", "normal"),
            ldr_status=preds.get("ldr", "normal"),
            overall_status=overall,
        )
        db.add(prediction)
        db.commit()
    finally:
        db.close()

def process_reading(data: dict):
    reading_id = save_reading_to_db(data)
    preds = predictor.predict(data)
    save_prediction_to_db(reading_id, preds)
    return {"reading_id": reading_id, "predictions": preds}

# Background simulator thread
simulator_running = False

def _simulator_loop():
    cattle_ids = ["CATTLE-001", "CATTLE-002", "CATTLE-003"]
    while simulator_running:
        for cid in cattle_ids:
            data = generate_fake_reading(cid)
            result = process_reading(data)
            payload = json.dumps({
                "type": "sensor_update",
                "cattle_id": cid,
                "data": data,
                "health": result["predictions"],
            })
            try:
                loop = asyncio.new_event_loop()
                loop.run_until_complete(manager.broadcast(payload))
                loop.close()
            except Exception:
                pass
        time.sleep(5)

# Arduino serial callback — processes real hardware data
def handle_arduino_data(data: dict):
    """Called by SerialReader when real Arduino data arrives."""
    if "cattle_id" not in data:
        data["cattle_id"] = "CATTLE-001"  # Default for single-cattle setups
    
    result = process_reading(data)
    preds = result["predictions"]
    
    # Broadcast to WebSocket clients
    payload = json.dumps({
        "type": "sensor_update",
        "cattle_id": data["cattle_id"],
        "data": data,
        "health": preds,
    })
    try:
        loop = asyncio.new_event_loop()
        loop.run_until_complete(manager.broadcast(payload))
        loop.close()
    except Exception:
        pass
    
    # Send prediction back to Arduino: a{0/1}b{0/1}c{0/1}d{0/1}e{0/1}f{0/1}g
    a = "1" if preds.get("spo2") == "normal" else "0"
    b = "1" if preds.get("bpm") == "normal" else "0"
    c = "1" if preds.get("temperature") == "normal" else "0"
    d = "1" if preds.get("mems") == "normal" else "0"
    e = "1" if preds.get("ph") == "normal" else "0"
    f = "1" if preds.get("ldr") == "normal" else "0"
    serial_reader.send_alert(f"a{a}b{b}c{c}d{d}e{e}f{f}g")
    print(f"[ARDUINO] {data['cattle_id']}: overall={'abnormal' if '0' in [a,b,c,d,e,f] else 'normal'}")

# ── Lifecycle ──

serial_reader = None

def _try_arduino():
    """Try to connect to Arduino on COM4. Returns True if successful."""
    global serial_reader
    try:
        from serial_mod.reader import SerialReader
        serial_reader = SerialReader(port="COM4")
        serial_reader.start(callback=handle_arduino_data)
        return True
    except Exception:
        pass
    
    # Try direct import
    try:
        sys.path.insert(0, os.path.join(os.path.dirname(__file__), "serial"))
        from reader import SerialReader as SR
        serial_reader = SR(port="COM4")
        serial_reader.start(callback=handle_arduino_data)
        return True
    except Exception as e:
        print(f"Arduino not available ({e}). Using simulator.")
        return False

@app.on_event("startup")
def startup_event():
    global simulator_running

    # Seed cattle entries if empty
    db = SessionLocal()
    if db.query(models.Cattle).count() == 0:
        for cid, name in [("CATTLE-001", "Lakshmi"), ("CATTLE-002", "Ganga"), ("CATTLE-003", "Nandi")]:
            db.add(models.Cattle(cattle_id=cid, name=name))
        db.commit()
    db.close()

    # Try Arduino first, fall back to simulator
    if _try_arduino():
        print("=" * 50)
        print("ARDUINO MODE: Reading real sensor data from COM4")
        print("Data visible at: http://localhost:8000/docs")
        print("=" * 50)
    else:
        simulator_running = True
        threading.Thread(target=_simulator_loop, daemon=True).start()
        print("=" * 50)
        print("SIMULATOR MODE: Generating fake sensor data every 5s")
        print("Plug in Arduino on COM4 and restart to use real data")
        print("Data visible at: http://localhost:8000/docs")
        print("=" * 50)

@app.on_event("shutdown")
def shutdown_event():
    global simulator_running
    simulator_running = False
    if serial_reader:
        serial_reader.stop()

# ── REST Endpoints ──

@app.get("/api/health")
def health_check():
    return {"status": "ok", "models_loaded": len(predictor.models)}

@app.get("/api/cattle")
def get_cattle(db: Session = Depends(get_db)):
    cattle_list = db.query(models.Cattle).all()
    return [{"cattle_id": c.cattle_id, "name": c.name, "status": c.status} for c in cattle_list]

@app.get("/api/cattle/{cattle_id}/latest")
def get_latest_reading(cattle_id: str, db: Session = Depends(get_db)):
    reading = (
        db.query(models.SensorReading)
        .filter(models.SensorReading.cattle_id == cattle_id)
        .order_by(models.SensorReading.timestamp.desc())
        .first()
    )
    if not reading:
        return {"error": "No readings found"}
    
    prediction = (
        db.query(models.Prediction)
        .filter(models.Prediction.reading_id == reading.id)
        .first()
    )
    
    return {
        "cattle_id": reading.cattle_id,
        "timestamp": str(reading.timestamp),
        "spo2": reading.spo2,
        "bpm": reading.bpm,
        "temperature": reading.temperature,
        "humidity": reading.humidity,
        "mems_x": reading.mems_x,
        "mems_y": reading.mems_y,
        "mems_z": reading.mems_z,
        "ph": reading.ph,
        "ldr": reading.ldr,
        "health": {
            "spo2": prediction.spo2_status if prediction else "unknown",
            "bpm": prediction.bpm_status if prediction else "unknown",
            "temperature": prediction.temperature_status if prediction else "unknown",
            "mems": prediction.mems_status if prediction else "unknown",
            "ph": prediction.ph_status if prediction else "unknown",
            "ldr": prediction.ldr_status if prediction else "unknown",
            "overall": prediction.overall_status if prediction else "unknown",
        },
    }

@app.get("/api/cattle/{cattle_id}/history")
def get_history(cattle_id: str, limit: int = Query(default=20), db: Session = Depends(get_db)):
    readings = (
        db.query(models.SensorReading)
        .filter(models.SensorReading.cattle_id == cattle_id)
        .order_by(models.SensorReading.timestamp.desc())
        .limit(limit)
        .all()
    )
    return [
        {
            "timestamp": str(r.timestamp),
            "spo2": r.spo2,
            "bpm": r.bpm,
            "temperature": r.temperature,
            "ph": r.ph,
            "ldr": r.ldr,
        }
        for r in readings
    ]

@app.get("/api/alerts")
def get_alerts(db: Session = Depends(get_db)):
    abnormal = (
        db.query(models.Prediction)
        .filter(models.Prediction.overall_status == "abnormal")
        .order_by(models.Prediction.timestamp.desc())
        .limit(20)
        .all()
    )
    alerts = []
    for p in abnormal:
        reading = db.query(models.SensorReading).filter(models.SensorReading.id == p.reading_id).first()
        cattle = db.query(models.Cattle).filter(models.Cattle.cattle_id == reading.cattle_id).first() if reading else None
        details = []
        if p.spo2_status == "abnormal": details.append(f"SpO2 abnormal ({reading.spo2}%)" if reading else "SpO2 abnormal")
        if p.bpm_status == "abnormal": details.append(f"BPM abnormal ({reading.bpm})" if reading else "BPM abnormal")
        if p.temperature_status == "abnormal": details.append(f"Temp abnormal ({reading.temperature}°C)" if reading else "Temp abnormal")
        if p.ph_status == "abnormal": details.append(f"pH abnormal ({reading.ph})" if reading else "pH abnormal")
        alerts.append({
            "cattle_id": reading.cattle_id if reading else "unknown",
            "cattle_name": cattle.name if cattle else "Unknown",
            "timestamp": str(p.timestamp),
            "details": details,
        })
    return alerts

# ── WebSocket ──

@app.websocket("/ws/cattle/{cattle_id}")
async def websocket_endpoint(websocket: WebSocket, cattle_id: str):
    await manager.connect(websocket)
    try:
        while True:
            await websocket.receive_text()
    except WebSocketDisconnect:
        manager.disconnect(websocket)

if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=8000)
