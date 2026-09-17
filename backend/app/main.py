import sys
import os
import json
import asyncio
import threading
import time
import random
import logging

PROJECT_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, PROJECT_ROOT)

from fastapi import FastAPI, WebSocket, WebSocketDisconnect, Depends, Query, HTTPException, Request
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse
from sqlalchemy.orm import Session
from database.database import engine, SessionLocal, get_db
from database import models
from websocket.manager import manager
from .config import settings
from .services import SensorService, PredictionService
from .auth import get_current_user, require_admin
from slowapi import Limiter, _rate_limit_exceeded_handler
from slowapi.util import get_remote_address
from slowapi.errors import RateLimitExceeded

# Configure logging
logging.basicConfig(level=logging.INFO, format="%(asctime)s - %(name)s - %(levelname)s - %(message)s")
logger = logging.getLogger(__name__)

# Initialize rate limiter
limiter = Limiter(key_func=get_remote_address)

models.Base.metadata.create_all(bind=engine)

app = FastAPI(title="Smart Cattle Health Monitoring API")
app.state.limiter = limiter
app.add_exception_handler(RateLimitExceeded, _rate_limit_exceeded_handler)

# CORS
app.add_middleware(
    CORSMiddleware,
    allow_origins=settings.cors_origins,
    allow_methods=["*"],
    allow_headers=["*"],
)

prediction_service = PredictionService()
sensor_service = SensorService(prediction_service)

# Global error handler
@app.exception_handler(Exception)
async def global_exception_handler(request: Request, exc: Exception):
    logger.error(f"Unhandled exception: {exc}", exc_info=True)
    return JSONResponse(status_code=500, content={"message": "Internal server error"})

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

simulator_running = False

def _simulator_loop():
    cattle_ids = ["CATTLE-001", "CATTLE-002", "CATTLE-003"]
    while simulator_running:
        for cid in cattle_ids:
            try:
                data = generate_fake_reading(cid)
                loop = asyncio.new_event_loop()
                loop.run_until_complete(sensor_service.process_reading(data, manager))
                loop.close()
            except Exception as e:
                logger.error(f"Simulator error for {cid}: {e}")
        time.sleep(5)

def handle_arduino_data(data: dict):
    try:
        loop = asyncio.new_event_loop()
        result = loop.run_until_complete(sensor_service.process_reading(data, manager))
        loop.close()
        
        preds = result["predictions"]
        a = "1" if preds.get("spo2", {}).get("status") == "normal" else "0"
        b = "1" if preds.get("bpm", {}).get("status") == "normal" else "0"
        c = "1" if preds.get("temperature", {}).get("status") == "normal" else "0"
        d = "1" if preds.get("mems", {}).get("status") == "normal" else "0"
        e = "1" if preds.get("ph", {}).get("status") == "normal" else "0"
        f = "1" if preds.get("ldr", {}).get("status") == "normal" else "0"
        
        if serial_reader:
            serial_reader.send_alert(f"a{a}b{b}c{c}d{d}e{e}f{f}g")
        logger.info(f"[ARDUINO] {data.get('cattle_id', 'unknown')}: overall={'abnormal' if '0' in [a,b,c,d,e,f] else 'normal'}")
    except Exception as e:
        logger.error(f"Arduino data handling error: {e}")

serial_reader = None

def _try_arduino():
    global serial_reader
    try:
        sys.path.insert(0, os.path.join(os.path.dirname(__file__), "serial"))
        from reader import SerialReader as SR
        serial_reader = SR(port=settings.SERIAL_PORT)
        serial_reader.start(callback=handle_arduino_data)
        return True
    except Exception as e:
        logger.warning(f"Arduino not available ({e}). Using simulator.")
        return False

@app.on_event("startup")
def startup_event():
    global simulator_running
    db = SessionLocal()
    try:
        if db.query(models.Cattle).count() == 0:
            for cid, name in [("CATTLE-001", "Lakshmi"), ("CATTLE-002", "Ganga"), ("CATTLE-003", "Nandi")]:
                db.add(models.Cattle(cattle_id=cid, name=name))
            db.commit()
    finally:
        db.close()

    if _try_arduino():
        logger.info("=" * 50)
        logger.info(f"ARDUINO MODE: Reading real sensor data from {settings.SERIAL_PORT}")
        logger.info("=" * 50)
    else:
        simulator_running = True
        threading.Thread(target=_simulator_loop, daemon=True).start()
        logger.info("=" * 50)
        logger.info("SIMULATOR MODE: Generating fake sensor data")
        logger.info("=" * 50)

@app.on_event("shutdown")
def shutdown_event():
    global simulator_running
    simulator_running = False
    if serial_reader:
        serial_reader.stop()

@app.get("/api/health")
@limiter.limit("10/minute")
def health_check(request: Request):
    return {"status": "ok", "models_loaded": len(prediction_service.predictor.models)}

@app.get("/api/cattle")
def get_cattle(db: Session = Depends(get_db), current_user: dict = Depends(get_current_user)):
    cattle_list = db.query(models.Cattle).all()
    return [{"cattle_id": c.cattle_id, "name": c.name, "status": c.status} for c in cattle_list]

@app.get("/api/cattle/{cattle_id}/latest")
def get_latest_reading(cattle_id: str, db: Session = Depends(get_db), current_user: dict = Depends(get_current_user)):
    reading = db.query(models.SensorReading).filter(models.SensorReading.cattle_id == cattle_id).order_by(models.SensorReading.timestamp.desc()).first()
    if not reading:
        raise HTTPException(status_code=404, detail="No readings found")
    
    prediction = db.query(models.Prediction).filter(models.Prediction.reading_id == reading.id).first()
    
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
def get_history(cattle_id: str, limit: int = Query(default=20), db: Session = Depends(get_db), current_user: dict = Depends(get_current_user)):
    readings = db.query(models.SensorReading).filter(models.SensorReading.cattle_id == cattle_id).order_by(models.SensorReading.timestamp.desc()).limit(limit).all()
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
def get_alerts(db: Session = Depends(get_db), current_user: dict = Depends(get_current_user)):
    abnormal = db.query(models.Prediction).filter(models.Prediction.overall_status == "abnormal").order_by(models.Prediction.timestamp.desc()).limit(20).all()
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
    uvicorn.run(app, host=settings.API_HOST, port=settings.API_PORT)
