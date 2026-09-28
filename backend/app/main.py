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
from pydantic import BaseModel
from typing import Optional, List
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse
from sqlalchemy.orm import Session
from database.database import engine, SessionLocal, get_db
from database import models
from websocket.manager import manager
from config import settings
from services import SensorService, PredictionService
from auth import get_current_user, require_admin
from routers import analytics, calibration, cattle, devices
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
app.include_router(analytics.router)
app.include_router(calibration.router)
app.include_router(cattle.router)
app.include_router(devices.router)

# CORS
app.add_middleware(
    CORSMiddleware,
    allow_origins=settings.cors_origins_list,
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

@app.get("/health")
def prod_health_check():
    """Production health check endpoint for load balancers."""
    return {"status": "ok", "environment": settings.ENVIRONMENT, "database": "connected"}

class MilkProductionCreate(BaseModel):
    cattle_id: str
    quantity: float
    unit: str = "litres"
    notes: Optional[str] = None

class FeedConsumptionCreate(BaseModel):
    cattle_id: str
    quantity: float
    feed_type: Optional[str] = None
    unit: str = "kg"
    notes: Optional[str] = None

class ActivityCreate(BaseModel):
    cattle_id: str
    steps: int
    source: str = "manual"
    notes: Optional[str] = None

class CowCreate(BaseModel):
    cattle_id: str
    name: str
    photo: Optional[str] = None
    breed: Optional[str] = None
    age: Optional[str] = None
    gender: Optional[str] = None
    date_of_birth: Optional[str] = None
    weight: Optional[float] = None
    tag_id: Optional[str] = None
    lactation_stage: Optional[str] = None
    pregnancy_status: Optional[str] = None
    notes: Optional[str] = None
    medical_history: Optional[str] = None
    device_id: Optional[str] = None

class CowUpdate(BaseModel):
    name: Optional[str] = None
    photo: Optional[str] = None
    breed: Optional[str] = None
    age: Optional[str] = None
    gender: Optional[str] = None
    date_of_birth: Optional[str] = None
    weight: Optional[float] = None
    tag_id: Optional[str] = None
    lactation_stage: Optional[str] = None
    pregnancy_status: Optional[str] = None
    notes: Optional[str] = None
    medical_history: Optional[str] = None
    device_id: Optional[str] = None

from fastapi.security import OAuth2PasswordRequestForm
from backend.app.auth import create_access_token

@app.post("/token")
def login(form_data: OAuth2PasswordRequestForm = Depends()):
    # Authentication using configuration settings
    if form_data.username != settings.ADMIN_USERNAME or form_data.password != settings.ADMIN_PASSWORD:
        raise HTTPException(status_code=401, detail="Incorrect username or password")
    access_token = create_access_token(data={"sub": form_data.username, "role": "ADMIN"})
    return {"access_token": access_token, "token_type": "bearer"}

last_arduino_time = 0.0

async def _offline_monitor():
    while True:
        try:
            await asyncio.sleep(2)
            if time.time() - last_arduino_time > 5.0:
                await manager.broadcast({"type": "status", "status": "offline"})
            else:
                await manager.broadcast({"type": "status", "status": "online"})
        except asyncio.CancelledError:
            break
        except Exception as e:
            logger.error(f"Offline monitor error: {e}")

_main_loop: asyncio.AbstractEventLoop = None

async def _simulator_loop():
    logger.info("Starting simulator loop...")
    while True:
        try:
            await asyncio.sleep(5)
            # Pick a random cattle ID
            with database.SessionLocal() as db:
                cattle_list = db.query(models.Cattle).filter(models.Cattle.deleted == False).all()
                if not cattle_list:
                    continue
                cattle_ids = [c.cattle_id for c in cattle_list]
                
            cid = random.choice(cattle_ids)
            data = {
                "cattle_id": cid,
                "temperature": round(random.uniform(37.5, 39.5), 2),
                "heart_rate": random.randint(48, 84),
                "spo2": random.randint(95, 100),
                "mems_x": round(random.uniform(-1, 1), 2),
                "mems_y": round(random.uniform(-1, 1), 2),
                "mems_z": round(random.uniform(9, 10), 2),
                "ph": round(random.uniform(6.5, 7.5), 2),
                "ldr": random.randint(100, 1000)
            }
            # process reading
            await sensor_service.process_reading(data, manager)
            logger.info(f"[SIMULATOR] Sent data for {cid}")
        except asyncio.CancelledError:
            break
        except Exception as e:
            logger.error(f"Simulator error: {e}")


def handle_arduino_data(data: dict):
    global last_arduino_time, _main_loop
    last_arduino_time = time.time()
    if _main_loop is None:
        logger.error("Main event loop not set — cannot process Arduino data")
        return
    try:
        future = asyncio.run_coroutine_threadsafe(
            sensor_service.process_reading(data, manager),
            _main_loop
        )
        result = future.result(timeout=10)  # wait up to 10s

        preds = result["predictions"]
        a = "1" if preds.get("spo2", {}).get("status", "normal") == "normal" else "0"
        b = "1" if preds.get("heart_rate", {}).get("status", "normal") == "normal" else "0"
        c = "1" if preds.get("temperature", {}).get("status", "normal") == "normal" else "0"
        d = "1" if preds.get("mems", {}).get("status", "normal") == "normal" else "0"
        e = "1" if preds.get("ph", {}).get("status", "normal") == "normal" else "0"
        f = "1" if preds.get("ldr", {}).get("status", "normal") == "normal" else "0"

        if serial_reader:
            serial_reader.write(f"a{a}b{b}c{c}d{d}e{e}f{f}g")
        logger.info(f"[ARDUINO] {data.get('cattle_id', 'unknown')}: overall={'abnormal' if '0' in [a,b,c,d,e,f] else 'normal'}")
    except Exception as e:
        logger.error(f"Arduino data handling error: {e}")


serial_reader = None

def _try_arduino():
    global serial_reader
    try:
        sys.path.insert(0, os.path.join(os.path.dirname(__file__), "serial"))
        from manager import SerialManager as SR
        serial_reader = SR(fallback_port=settings.SERIAL_PORT, baudrate=settings.SERIAL_BAUDRATE)
        serial_reader.start(callback=handle_arduino_data)
        return True
    except Exception as e:
        logger.warning(f"Arduino not available ({e}).")
        return False

@app.on_event("startup")
async def startup_event():
    global _main_loop
    _main_loop = asyncio.get_event_loop()  # capture FastAPI's loop for thread-safe scheduling
    asyncio.create_task(manager.ping_clients())



    db = SessionLocal()
    try:
        # Patch any rows where deleted is NULL (inserted before the migration added the column)
        from sqlalchemy import text
        with engine.connect() as conn:
            conn.execute(text("UPDATE cattle SET deleted = 0 WHERE deleted IS NULL"))
            conn.commit()
    finally:
        db.close()

    asyncio.create_task(_offline_monitor())

    if settings.HARDWARE_MODE == "real":
        if _try_arduino():
            logger.info("=" * 50)
            logger.info(f"ARDUINO MODE: Reading real sensor data from {settings.SERIAL_PORT}")
            logger.info("=" * 50)
        else:
            logger.error("ARDUINO_CONNECTION_FAILED")
            # Do not start simulator in real mode
    elif settings.HARDWARE_MODE == "simulation":
        if settings.ALLOW_SIMULATION:
            logger.info("=" * 50)
            logger.info("SIMULATOR MODE: Generating mock sensor data")
            logger.info("=" * 50)
            asyncio.create_task(_simulator_loop())
        else:
            logger.warning("Simulation requested but ALLOW_SIMULATION is false.")
    else:
        logger.warning(f"Unknown HARDWARE_MODE: {settings.HARDWARE_MODE}")


@app.on_event("shutdown")
def shutdown_event():
    if serial_reader:
        serial_reader.stop()

@app.get("/api/health")
@limiter.limit("10/minute")
def health_check(request: Request):
    return {"status": "ok", "models_loaded": len(prediction_service.predictor.models)}

@app.get("/api/arduino/status")
def get_arduino_status():
    global serial_reader
    mode = settings.HARDWARE_MODE
    sim_enabled = settings.ALLOW_SIMULATION
    if serial_reader:
        status = serial_reader.get_status()
        return {
            "mode": mode,
            "connected": status["connected"],
            "port": status["port"],
            "device": status.get("device"),
            "last_data": status["last_packet"],
            "simulation_enabled": sim_enabled
        }
    return {
        "mode": mode,
        "connected": False,
        "port": None,
        "device": None,
        "last_data": None,
        "simulation_enabled": sim_enabled
    }


@app.post("/api/arduino/disconnect")
def arduino_disconnect():
    """Release COM port so Arduino IDE can upload a new sketch."""
    global serial_reader
    if serial_reader:
        serial_reader.stop()
        serial_reader = None
    return {"status": "disconnected", "message": "COM port released. Safe to upload sketch."}

@app.post("/api/arduino/reconnect")
def arduino_reconnect():
    """Re-attach to the Arduino after uploading a new sketch."""
    global serial_reader
    if serial_reader:
        return {"status": "already_connected"}
    success = _try_arduino()
    return {"status": "connected" if success else "failed"}

@app.post("/api/sensor/data")
async def receive_sensor_data(request: Request):
    data = await request.json()
    result = await sensor_service.process_reading(data, manager)
    return result

@app.get("/api/cattle")
def get_cattle(db: Session = Depends(get_db)):
    cattle_list = db.query(models.Cattle).all()
    return [{"cattle_id": c.cattle_id, "name": c.name, "status": c.status} for c in cattle_list]

@app.get("/api/cows")
def get_cows(db: Session = Depends(get_db)):
    from sqlalchemy import or_
    cattle_list = db.query(models.Cattle).filter(
        or_(models.Cattle.deleted == False, models.Cattle.deleted == None)
    ).all()
    results = []
    for c in cattle_list:
        reading = db.query(models.SensorReading).filter(models.SensorReading.cattle_id == c.cattle_id).order_by(models.SensorReading.timestamp.desc()).first()
        prediction = None
        if reading:
            prediction = db.query(models.Prediction).filter(models.Prediction.reading_id == reading.id).first()
        latest_milk = db.query(models.MilkProduction).filter(models.MilkProduction.cattle_id == c.cattle_id).order_by(models.MilkProduction.date.desc()).first()
        latest_feed = db.query(models.FeedConsumption).filter(models.FeedConsumption.cattle_id == c.cattle_id).order_by(models.FeedConsumption.date.desc()).first()
        latest_act = db.query(models.Activity).filter(models.Activity.cattle_id == c.cattle_id).order_by(models.Activity.date.desc()).first()
        active_alerts = db.query(models.Alert).filter(models.Alert.cattle_id == c.cattle_id, models.Alert.status == "active").count()
        device = db.query(models.Device).filter(models.Device.cattle_id == c.cattle_id).first()
        results.append({
            "cattle_id": c.cattle_id,
            "name": c.name,
            "photo": c.photo,
            "breed": c.breed,
            "age": c.age,
            "gender": c.gender,
            "date_of_birth": c.dob,
            "weight": c.weight,
            "tag_id": c.tag_id,
            "lactation_stage": c.lactation_stage,
            "pregnancy_status": c.pregnancy_status,
            "notes": c.notes,
            "medical_history": c.medical_history,
            "device_id": device.device_id if device else None,
            "status": c.status,
            "latest_health": {
                "timestamp": str(reading.timestamp) if reading else None,
                "spo2": reading.spo2 if reading else None,
                "heart_rate": reading.heart_rate if reading else None,
                "temperature": reading.temperature if reading else None,
                "overall_status": prediction.overall_status if prediction else None
            },
            "latest_milk": {
                "quantity": latest_milk.quantity if latest_milk else None,
                "unit": latest_milk.unit if latest_milk else None,
                "date": str(latest_milk.date) if latest_milk else None
            },
            "latest_feed": {
                "quantity": latest_feed.quantity if latest_feed else None,
                "unit": latest_feed.unit if latest_feed else None,
                "date": str(latest_feed.date) if latest_feed else None
            },
            "latest_activity": {
                "steps": latest_act.steps if latest_act else None,
                "date": str(latest_act.date) if latest_act else None
            },
            "active_alerts_count": active_alerts
        })
    return results

@app.get("/api/cows/{cattle_id}")
def get_cow(cattle_id: str, db: Session = Depends(get_db)):
    from sqlalchemy import or_
    c = db.query(models.Cattle).filter(
        models.Cattle.cattle_id == cattle_id,
        or_(models.Cattle.deleted == False, models.Cattle.deleted == None)
    ).first()
    if not c:
        raise HTTPException(status_code=404, detail="Cow not found")
    reading = db.query(models.SensorReading).filter(models.SensorReading.cattle_id == c.cattle_id).order_by(models.SensorReading.timestamp.desc()).first()
    prediction = None
    if reading:
        prediction = db.query(models.Prediction).filter(models.Prediction.reading_id == reading.id).first()
    latest_milk = db.query(models.MilkProduction).filter(models.MilkProduction.cattle_id == c.cattle_id).order_by(models.MilkProduction.date.desc()).first()
    latest_feed = db.query(models.FeedConsumption).filter(models.FeedConsumption.cattle_id == c.cattle_id).order_by(models.FeedConsumption.date.desc()).first()
    latest_act = db.query(models.Activity).filter(models.Activity.cattle_id == c.cattle_id).order_by(models.Activity.date.desc()).first()
    active_alerts = db.query(models.Alert).filter(models.Alert.cattle_id == c.cattle_id, models.Alert.status == "active").count()
    device = db.query(models.Device).filter(models.Device.cattle_id == c.cattle_id).first()
    return {
        "cattle_id": c.cattle_id,
        "name": c.name,
        "photo": c.photo,
        "breed": c.breed,
        "age": c.age,
        "gender": c.gender,
        "date_of_birth": c.dob,
        "weight": c.weight,
        "tag_id": c.tag_id,
        "lactation_stage": c.lactation_stage,
        "pregnancy_status": c.pregnancy_status,
        "notes": c.notes,
        "medical_history": c.medical_history,
        "device_id": device.device_id if device else None,
        "status": c.status,
        "latest_health": {
            "timestamp": str(reading.timestamp) if reading else None,
            "spo2": reading.spo2 if reading else None,
            "heart_rate": reading.heart_rate if reading else None,
            "temperature": reading.temperature if reading else None,
            "overall_status": prediction.overall_status if prediction else None
        },
        "latest_milk": {
            "quantity": latest_milk.quantity if latest_milk else None,
            "unit": latest_milk.unit if latest_milk else None,
            "date": str(latest_milk.date) if latest_milk else None
        },
        "latest_feed": {
            "quantity": latest_feed.quantity if latest_feed else None,
            "unit": latest_feed.unit if latest_feed else None,
            "date": str(latest_feed.date) if latest_feed else None
        },
        "latest_activity": {
            "steps": latest_act.steps if latest_act else None,
            "date": str(latest_act.date) if latest_act else None
        },
        "active_alerts_count": active_alerts
    }

@app.post("/api/cows", status_code=201)
def create_cow(data: CowCreate, db: Session = Depends(get_db)):
    existing = db.query(models.Cattle).filter(models.Cattle.cattle_id == data.cattle_id).first()
    if existing:
        raise HTTPException(status_code=409, detail=f"Cow with ID {data.cattle_id} already exists")
    cow = models.Cattle(
        cattle_id=data.cattle_id,
        name=data.name,
        photo=data.photo,
        breed=data.breed,
        age=data.age,
        gender=data.gender,
        dob=data.date_of_birth,
        weight=data.weight,
        tag_id=data.tag_id,
        lactation_stage=data.lactation_stage,
        pregnancy_status=data.pregnancy_status,
        notes=data.notes,
        medical_history=data.medical_history,
        status="normal",
        deleted=False
    )
    db.add(cow)
    if data.device_id:
        device = db.query(models.Device).filter(models.Device.device_id == data.device_id).first()
        if device:
            device.cattle_id = cow.cattle_id
        else:
            db.add(models.Device(device_id=data.device_id, cattle_id=cow.cattle_id, status="active"))

    db.commit()
    db.refresh(cow)
    return {
        "cattle_id": cow.cattle_id, "name": cow.name, "photo": cow.photo, "breed": cow.breed,
        "age": cow.age, "gender": cow.gender, "date_of_birth": cow.dob, "weight": cow.weight,
        "tag_id": cow.tag_id, "lactation_stage": cow.lactation_stage, "pregnancy_status": cow.pregnancy_status,
        "notes": cow.notes, "medical_history": cow.medical_history, "status": cow.status,
        "device_id": data.device_id,
        "latest_health": None, "latest_milk": None, "latest_feed": None,
        "latest_activity": None, "active_alerts_count": 0
    }

@app.put("/api/cows/{cattle_id}")
def update_cow(cattle_id: str, data: CowUpdate, db: Session = Depends(get_db)):
    from sqlalchemy import or_
    cow = db.query(models.Cattle).filter(
        models.Cattle.cattle_id == cattle_id,
        or_(models.Cattle.deleted == False, models.Cattle.deleted == None)
    ).first()
    if not cow:
        raise HTTPException(status_code=404, detail="Cow not found")
    if data.name is not None: cow.name = data.name
    if data.photo is not None: cow.photo = data.photo
    if data.breed is not None: cow.breed = data.breed
    if data.age is not None: cow.age = data.age
    if data.gender is not None: cow.gender = data.gender
    if data.date_of_birth is not None: cow.dob = data.date_of_birth
    if data.weight is not None: cow.weight = data.weight
    if data.tag_id is not None: cow.tag_id = data.tag_id
    if data.lactation_stage is not None: cow.lactation_stage = data.lactation_stage
    if data.pregnancy_status is not None: cow.pregnancy_status = data.pregnancy_status
    if data.notes is not None: cow.notes = data.notes
    if data.medical_history is not None: cow.medical_history = data.medical_history
    if data.device_id is not None:
        # Unassign from old device if exists
        old_device = db.query(models.Device).filter(models.Device.cattle_id == cow.cattle_id).first()
        if old_device and old_device.device_id != data.device_id:
            old_device.cattle_id = None
        # Assign to new device
        if data.device_id:
            new_device = db.query(models.Device).filter(models.Device.device_id == data.device_id).first()
            if new_device:
                new_device.cattle_id = cow.cattle_id
            else:
                db.add(models.Device(device_id=data.device_id, cattle_id=cow.cattle_id, status="active"))

    db.commit()
    db.refresh(cow)
    device = db.query(models.Device).filter(models.Device.cattle_id == cow.cattle_id).first()
    return {
        "cattle_id": cow.cattle_id, "name": cow.name, "photo": cow.photo, "breed": cow.breed,
        "age": cow.age, "gender": cow.gender, "date_of_birth": cow.dob, "weight": cow.weight,
        "tag_id": cow.tag_id, "lactation_stage": cow.lactation_stage, "pregnancy_status": cow.pregnancy_status,
        "notes": cow.notes, "medical_history": cow.medical_history, "status": cow.status,
        "device_id": device.device_id if device else None,
        "latest_health": None, "latest_milk": None, "latest_feed": None,
        "latest_activity": None, "active_alerts_count": 0
    }

@app.delete("/api/cows/{cattle_id}", status_code=204)
def delete_cow(cattle_id: str, db: Session = Depends(get_db)):
    from sqlalchemy import or_
    cow = db.query(models.Cattle).filter(
        models.Cattle.cattle_id == cattle_id,
        or_(models.Cattle.deleted == False, models.Cattle.deleted == None)
    ).first()
    if not cow:
        raise HTTPException(status_code=404, detail="Cow not found")
    cow.deleted = True  # soft delete - keeps all sensor/health/milk data intact
    db.commit()
    return None

@app.get("/api/cows/{cattle_id}")
def get_cow_by_id(cattle_id: str, db: Session = Depends(get_db)):
    from sqlalchemy import or_
    c = db.query(models.Cattle).filter(
        models.Cattle.cattle_id == cattle_id,
        or_(models.Cattle.deleted == False, models.Cattle.deleted == None)
    ).first()
    if not c:
        raise HTTPException(status_code=404, detail="Cow not found")
    reading = db.query(models.SensorReading).filter(models.SensorReading.cattle_id == c.cattle_id).order_by(models.SensorReading.timestamp.desc()).first()
    prediction = None
    if reading:
        prediction = db.query(models.Prediction).filter(models.Prediction.reading_id == reading.id).first()
    latest_milk = db.query(models.MilkProduction).filter(models.MilkProduction.cattle_id == c.cattle_id).order_by(models.MilkProduction.date.desc()).first()
    latest_feed = db.query(models.FeedConsumption).filter(models.FeedConsumption.cattle_id == c.cattle_id).order_by(models.FeedConsumption.date.desc()).first()
    latest_act = db.query(models.Activity).filter(models.Activity.cattle_id == c.cattle_id).order_by(models.Activity.date.desc()).first()
    active_alerts = db.query(models.Alert).filter(models.Alert.cattle_id == c.cattle_id, models.Alert.status == "active").all()
    return {
        "cattle_id": c.cattle_id,
        "name": c.name,
        "breed": c.breed,
        "age": c.age,
        "gender": c.gender,
        "dob": c.dob,
        "notes": c.notes,
        "status": c.status,
        "latest_health": {
            "timestamp": str(reading.timestamp) if reading else None,
            "spo2": reading.spo2 if reading else None,
            "heart_rate": reading.heart_rate if reading else None,
            "temperature": reading.temperature if reading else None,
            "humidity": reading.humidity if reading else None,
            "ph": reading.ph if reading else None,
            "overall_status": prediction.overall_status if prediction else None
        },
        "latest_milk": {
            "quantity": latest_milk.quantity if latest_milk else None,
            "unit": latest_milk.unit if latest_milk else None,
            "date": str(latest_milk.date) if latest_milk else None
        },
        "latest_feed": {
            "quantity": latest_feed.quantity if latest_feed else None,
            "unit": latest_feed.unit if latest_feed else None,
            "date": str(latest_feed.date) if latest_feed else None
        },
        "latest_activity": {
            "steps": latest_act.steps if latest_act else None,
            "date": str(latest_act.date) if latest_act else None
        },
        "active_alerts": [{"id": a.id, "alert_type": a.alert_type, "severity": a.severity, "timestamp": str(a.timestamp)} for a in active_alerts]
    }

@app.get("/api/cattle/{cattle_id}/latest")
def get_latest_reading(cattle_id: str, db: Session = Depends(get_db)):
    reading = db.query(models.SensorReading).filter(models.SensorReading.cattle_id == cattle_id).order_by(models.SensorReading.timestamp.desc()).first()
    if not reading:
        raise HTTPException(status_code=404, detail="No readings found")
    
    prediction = db.query(models.Prediction).filter(models.Prediction.reading_id == reading.id).first()
    
    fall_detected = (prediction.mems_status == "abnormal") if prediction else False
    return {
        "cattle_id": reading.cattle_id,
        "timestamp": str(reading.timestamp),
        "spo2": reading.spo2,
        "heart_rate": reading.heart_rate,
        "temperature": reading.temperature,
        "humidity": reading.humidity,
        "mems_x": reading.mems_x,
        "mems_y": reading.mems_y,
        "mems_z": reading.mems_z,
        "ph": reading.ph,
        "ldr": reading.ldr,
        "fall_detected": fall_detected,
        "health": {
            "spo2": prediction.spo2_status if prediction else "unknown",
            "heart_rate": prediction.heart_rate_status if prediction else "unknown",
            "temperature": prediction.temperature_status if prediction else "unknown",
            "mems": prediction.mems_status if prediction else "unknown",
            "ph": prediction.ph_status if prediction else "unknown",
            "ldr": prediction.ldr_status if prediction else "unknown",
            "overall": prediction.overall_status if prediction else "unknown",
        },
    }

@app.get("/api/cattle/{cattle_id}/history")
def get_history(cattle_id: str, limit: int = Query(default=20), db: Session = Depends(get_db)):
    readings = db.query(models.SensorReading).filter(models.SensorReading.cattle_id == cattle_id).order_by(models.SensorReading.timestamp.desc()).limit(limit).all()
    result = []
    for r in readings:
        prediction = db.query(models.Prediction).filter(models.Prediction.reading_id == r.id).first()
        fall_detected = (prediction.mems_status == "abnormal") if prediction else False
        result.append({
            "timestamp": str(r.timestamp),
            "spo2": r.spo2,
            "heart_rate": r.heart_rate,
            "temperature": r.temperature,
            "humidity": r.humidity,
            "mems_x": r.mems_x,
            "mems_y": r.mems_y,
            "mems_z": r.mems_z,
            "ph": r.ph,
            "ldr": r.ldr,
            "fall_detected": fall_detected,
            "health": {
                "spo2": prediction.spo2_status if prediction else "unknown",
                "heart_rate": prediction.heart_rate_status if prediction else "unknown",
                "temperature": prediction.temperature_status if prediction else "unknown",
                "mems": prediction.mems_status if prediction else "unknown",
                "ph": prediction.ph_status if prediction else "unknown",
                "ldr": prediction.ldr_status if prediction else "unknown",
                "overall": prediction.overall_status if prediction else "unknown",
            },
        })
    return result

@app.get("/api/alerts")
def get_alerts(db: Session = Depends(get_db)):
    abnormal = db.query(models.Prediction).filter(models.Prediction.overall_status == "abnormal").order_by(models.Prediction.timestamp.desc()).limit(20).all()
    alerts = []
    for p in abnormal:
        reading = db.query(models.SensorReading).filter(models.SensorReading.id == p.reading_id).first()
        cattle = db.query(models.Cattle).filter(models.Cattle.cattle_id == reading.cattle_id).first() if reading else None
        fall_detected = (p.mems_status == "abnormal")
        details = []
        if fall_detected: details.append(f"Fall Detected (X:{reading.mems_x or 0:.2f} Y:{reading.mems_y or 0:.2f} Z:{reading.mems_z or 0:.2f})" if reading else "Fall Detected")
        if p.spo2_status == "abnormal": details.append(f"SpO2 abnormal ({reading.spo2}%)" if reading else "SpO2 abnormal")
        if p.heart_rate_status == "abnormal": details.append(f"Heart Rate abnormal ({reading.heart_rate})" if reading else "Heart Rate abnormal")
        if p.temperature_status == "abnormal": details.append(f"Temp abnormal ({reading.temperature}°C)" if reading else "Temp abnormal")
        if p.ph_status == "abnormal": details.append(f"pH abnormal ({reading.ph})" if reading else "pH abnormal")
        if p.ldr_status == "abnormal": details.append(f"Light abnormal ({reading.ldr} lux)" if reading else "Light abnormal")
        alerts.append({
            "cattle_id": reading.cattle_id if reading else "unknown",
            "cattle_name": cattle.name if cattle else "Unknown",
            "timestamp": str(p.timestamp),
            "fall_detected": fall_detected,
            "details": details,
        })
    return alerts

@app.get("/api/dashboard/summary")
def get_dashboard_summary(db: Session = Depends(get_db)):
    from datetime import datetime
    cattle_list = db.query(models.Cattle).all()
    cattle_count = len(cattle_list)
    active_alerts = db.query(models.Alert).filter(models.Alert.status == "active").count()
    
    healthy_cows = 0
    attention_cows = 0
    
    heart_rate_sum = 0
    spo2_sum = 0
    temp_sum = 0
    heart_rate_count = 0
    spo2_count = 0
    temp_count = 0
    
    today = datetime.utcnow().date()
    total_milk = 0
    total_feed = 0
    total_steps = 0
    
    for c in cattle_list:
        if c.status == "normal":
            healthy_cows += 1
        else:
            attention_cows += 1
            
        reading = db.query(models.SensorReading).filter(models.SensorReading.cattle_id == c.cattle_id).order_by(models.SensorReading.timestamp.desc()).first()
        if reading:
            if reading.heart_rate is not None:
                heart_rate_sum += reading.heart_rate
                heart_rate_count += 1
            if reading.spo2 is not None:
                spo2_sum += reading.spo2
                spo2_count += 1
            if reading.temperature is not None:
                temp_sum += reading.temperature
                temp_count += 1
        
        milks = db.query(models.MilkProduction).filter(models.MilkProduction.cattle_id == c.cattle_id).all()
        total_milk += sum(m.quantity for m in milks if m.date.date() == today and m.quantity is not None)
        
        feeds = db.query(models.FeedConsumption).filter(models.FeedConsumption.cattle_id == c.cattle_id).all()
        total_feed += sum(f.quantity for f in feeds if f.date.date() == today and f.quantity is not None)
        
        acts = db.query(models.Activity).filter(models.Activity.cattle_id == c.cattle_id).all()
        total_steps += sum(a.steps for a in acts if a.date.date() == today and a.steps is not None)
        
    return {
        "total_cows": cattle_count,
        "healthy_cows": healthy_cows,
        "attention_cows": attention_cows,
        "average_heart_rate": round(heart_rate_sum / heart_rate_count, 1) if heart_rate_count > 0 else None,
        "average_spo2": round(spo2_sum / spo2_count, 1) if spo2_count > 0 else None,
        "average_temperature": round(temp_sum / temp_count, 1) if temp_count > 0 else None,
        "total_milk_today": round(total_milk, 2),
        "total_feed_today": round(total_feed, 2),
        "total_steps_today": total_steps,
        "active_alerts": active_alerts,
        "system_status": "Operational"
    }

@app.post("/api/milk")
def add_milk_production(data: MilkProductionCreate, db: Session = Depends(get_db)):
    record = models.MilkProduction(**data.dict())
    db.add(record)
    db.commit()
    db.refresh(record)
    return record

@app.get("/api/milk/{cattle_id}")
def get_milk_production(cattle_id: str, limit: int = 30, db: Session = Depends(get_db)):
    return db.query(models.MilkProduction).filter(models.MilkProduction.cattle_id == cattle_id).order_by(models.MilkProduction.date.desc()).limit(limit).all()

@app.post("/api/feed")
def add_feed_consumption(data: FeedConsumptionCreate, db: Session = Depends(get_db)):
    record = models.FeedConsumption(**data.dict())
    db.add(record)
    db.commit()
    db.refresh(record)
    return record

@app.get("/api/feed/{cattle_id}")
def get_feed_consumption(cattle_id: str, limit: int = 30, db: Session = Depends(get_db)):
    return db.query(models.FeedConsumption).filter(models.FeedConsumption.cattle_id == cattle_id).order_by(models.FeedConsumption.date.desc()).limit(limit).all()

@app.post("/api/activity")
def add_activity(data: ActivityCreate, db: Session = Depends(get_db)):
    record = models.Activity(**data.dict())
    db.add(record)
    db.commit()
    db.refresh(record)
    return record

@app.get("/api/activity/{cattle_id}")
def get_activity(cattle_id: str, limit: int = 30, db: Session = Depends(get_db)):
    return db.query(models.Activity).filter(models.Activity.cattle_id == cattle_id).order_by(models.Activity.date.desc()).limit(limit).all()

@app.get("/api/history/comparison")
def get_history_comparison(cattle_id: str, parameter: str, days: int = 7, db: Session = Depends(get_db)):
    return {"message": "Historical comparison for " + parameter, "data": []}

@app.websocket("/ws")
async def websocket_endpoint(websocket: WebSocket, client_id: str = Query(...), reconnect: bool = Query(False)):
    await manager.connect(websocket, client_id, reconnect)
    try:
        while True:
            data = await websocket.receive_text()
            if data == "pong" or "pong" in data:
                if client_id in manager.active_connections:
                    manager.active_connections[client_id].update_last_seen()
            elif data.startswith("subscribe:"):
                cattle_id = data.split(":")[1]
                manager.subscribe(client_id, cattle_id)
    except WebSocketDisconnect:
        manager.disconnect(client_id)

if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host=settings.API_HOST, port=settings.API_PORT)
