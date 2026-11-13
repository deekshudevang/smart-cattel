import sys
import os
import json
import uuid
import asyncio
import threading
import time
import random
import logging
from datetime import datetime, timezone

PROJECT_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, PROJECT_ROOT)

from fastapi import FastAPI, WebSocket, WebSocketDisconnect, Depends, Query, HTTPException, Request
from pydantic import BaseModel
from typing import Optional, List
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse, FileResponse, HTMLResponse
from sqlalchemy.orm import Session
from app.database.database import engine, SessionLocal, get_db
from app.database import models
from websocket.manager import manager
from app.config import settings
from services import SensorService, PredictionService
from schemas.websocket import WebSocketEvent
import redis.asyncio as redis_async
from auth import get_current_user, require_admin
from routers import analytics, calibration, cattle, devices, weight, steps
from app.serial.manager import SerialManager

serial_manager = SerialManager()
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
app.include_router(weight.router)
app.include_router(steps.router)

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

APK_PATH = os.path.join(PROJECT_ROOT, "smart-cattle-update.apk")
if not os.path.exists(APK_PATH):
    APK_PATH = os.path.join(PROJECT_ROOT, "android", "SmartCattle", "app", "build", "outputs", "apk", "debug", "app-debug.apk")

@app.get("/app.apk")
@app.get("/download/app")
@app.get("/download")
def download_app_apk():
    """Direct 1-tap download of the updated Smart Cattle Android APK."""
    if os.path.exists(APK_PATH):
        return FileResponse(
            path=APK_PATH,
            filename="smart-cattle-update.apk",
            media_type="application/vnd.android.package-archive"
        )
    raise HTTPException(status_code=404, detail="APK build not found. Please build with assembleDebug.")

@app.get("/", response_class=HTMLResponse)
def index_download_page():
    """Clean mobile download landing page for installing the app."""
    return """<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Smart Cattle App - Download & Install</title>
    <style>
        body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; background: #0f172a; color: #f8fafc; display: flex; flex-direction: column; align-items: center; justify-content: center; min-height: 100vh; margin: 0; padding: 20px; box-sizing: border-box; text-align: center; }
        .card { background: #1e293b; border: 1px solid #334155; border-radius: 16px; padding: 32px 24px; max-width: 440px; width: 100%; box-shadow: 0 10px 25px rgba(0,0,0,0.3); }
        .badge { display: inline-block; background: #065f46; color: #34d399; padding: 4px 12px; border-radius: 9999px; font-size: 12px; font-weight: 600; text-transform: uppercase; margin-bottom: 16px; }
        h1 { margin: 0 0 8px; font-size: 24px; }
        p { color: #94a3b8; font-size: 14px; margin: 0 0 24px; line-height: 1.5; }
        .features { text-align: left; background: #0f172a; border-radius: 12px; padding: 14px 16px; margin-bottom: 24px; font-size: 13px; color: #cbd5e1; }
        .features li { margin-bottom: 6px; }
        .features li:last-child { margin-bottom: 0; }
        .btn { display: block; background: #2563eb; color: #ffffff; text-decoration: none; font-weight: 600; font-size: 16px; padding: 14px 20px; border-radius: 10px; transition: background 0.2s; }
        .btn:hover { background: #1d4ed8; }
        .note { margin-top: 16px; font-size: 12px; color: #64748b; }
    </style>
</head>
<body>
    <div class="card">
        <div class="badge">Latest Build Ready</div>
        <h1>Smart Cattle Monitor</h1>
        <p>Real-time livestock health, weight & step analytics.</p>
        <div class="features">
            <ul style="padding-left: 20px; margin: 0;">
                <li>Steps & Total Steps Dashboard Cards</li>
                <li>Live HX711 Weight Tracking & Tare</li>
                <li>SpO2, Heart Rate, Temp, pH & Light</li>
                <li>Automatic Reconnect with Heartbeat</li>
            </ul>
        </div>
        <a href="/app.apk" class="btn">Download & Install App (APK)</a>
        <div class="note">Open the downloaded file on your phone to complete installation.</div>
    </div>
</body>
</html>"""

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
from app.auth import create_access_token

@app.post("/token")
def login(form_data: OAuth2PasswordRequestForm = Depends()):
    # Authentication using configuration settings
    if form_data.username != settings.ADMIN_USERNAME or form_data.password != settings.ADMIN_PASSWORD:
        raise HTTPException(status_code=401, detail="Incorrect username or password")
    access_token = create_access_token(data={"sub": form_data.username, "role": "ADMIN"})
    return {"access_token": access_token, "token_type": "bearer"}



async def redis_listener():
    retry_delay = 5
    while True:
        try:
            r = redis_async.from_url(settings.REDIS_URL)
            pubsub = r.pubsub()
            await pubsub.subscribe("telemetry_broadcast")
            logger.info("Subscribed to Redis telemetry_broadcast")
            async for message in pubsub.listen():
                if message["type"] == "message":
                    data_str = message["data"].decode("utf-8")
                    try:
                        event = WebSocketEvent.model_validate_json(data_str)
                        await manager.broadcast(event)
                    except Exception as e:
                        logger.error(f"Error broadcasting redis message: {e}")
            # If listen loop exits, reconnect
            await asyncio.sleep(retry_delay)
        except asyncio.CancelledError:
            break
        except Exception as e:
            logger.debug(f"Redis not available, retrying in {retry_delay}s... ({e})")
            await asyncio.sleep(retry_delay)

@app.on_event("startup")
async def startup_event():
    import socket
    def get_lan_ip():
        try:
            s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            s.connect(("8.8.8.8", 80))
            ip = s.getsockname()[0]
            s.close()
            return ip
        except Exception:
            return "127.0.0.1"
    lan_ip = get_lan_ip()
    logger.info("=" * 60)
    logger.info(" SMART CATTLE BACKEND RUNNING ON 0.0.0.0:8000")
    logger.info(" Local URL:     http://localhost:8000")
    logger.info(f" LAN IP:        {lan_ip}")
    logger.info(f" Android API:   http://{lan_ip}:8000/")
    logger.info(f" WebSocket:     ws://{lan_ip}:8000/ws/cattle/CATTLE-ALL")
    logger.info(f" Health Check:  http://{lan_ip}:8000/health")
    logger.info("=" * 60)
    asyncio.create_task(manager.ping_clients())
    # asyncio.create_task(redis_listener())

    loop = asyncio.get_running_loop()
    def on_serial_data(data):
        future = asyncio.run_coroutine_threadsafe(sensor_service.process_reading(data, manager), loop)
        def handle_future(f):
            try:
                f.result()
            except Exception as e:
                logger.error(f"[SERIAL COROUTINE ERROR] Failed in process_reading: {e}", exc_info=True)
        future.add_done_callback(handle_future)
        
    serial_manager.start(callback=on_serial_data)

    db = SessionLocal()
    try:
        # Patch any rows where deleted is NULL (inserted before the migration added the column)
        from sqlalchemy import text
        with engine.connect() as conn:
            conn.execute(text("UPDATE cattle SET deleted = 0 WHERE deleted IS NULL"))
            conn.commit()

        # Ensure default CATTLE-001 profile exists on fresh deployment
        default_cow = db.query(models.Cattle).filter(models.Cattle.cattle_id == "CATTLE-001").first()
        if not default_cow:
            logger.info("[STARTUP] Seeding default CATTLE-001 profile for new deployment...")
            db.add(models.Cattle(
                cattle_id="CATTLE-001",
                name="CATTLE-001",
                gender="Female",
                status="normal",
                deleted=False
            ))
            db.commit()
    except Exception as e:
        logger.warning(f"[STARTUP] Error verifying default cattle profile: {e}")
    finally:
        db.close()


@app.on_event("shutdown")
def shutdown_event():
    pass

@app.get("/api/health")
@limiter.limit("10/minute")
def health_check(request: Request):
    return {"status": "ok", "models_loaded": len(prediction_service.predictor.models)}

@app.get("/api/diagnostics/hardware")
def hardware_diagnostics():
    status = serial_manager.get_status()
    is_busy = status.get("error_detail") == "SERIAL_PORT_IN_USE"
    status_message = "Serial port busy" if is_busy else ("Connected" if status.get("connected") else "Disconnected")
    return {
        "serial_connected": status.get("connected", False),
        "port": status.get("port"),
        "device": status.get("device"),
        "last_packet": status.get("last_packet"),
        "packets_received": status.get("packets_received", 0),
        "packets_rejected": status.get("packets_rejected", 0),
        "reconnect_count": status.get("reconnect_count", 0),
        "errors": status.get("errors", 0),
        "last_packet_timestamp": status.get("last_packet"),
        "serial_busy": is_busy,
        "status_message": status_message
    }

@app.get("/api/arduino/status")
def get_arduino_status():
    status = serial_manager.get_status()
    is_busy = status.get("error_detail") == "SERIAL_PORT_IN_USE"
    status_message = "Serial port busy" if is_busy else ("Connected" if status.get("connected") else "Disconnected")
    return {
        "mode": "direct",
        "connected": status.get("connected", False),
        "port": status.get("port"),
        "device": status.get("device"),
        "last_data": status.get("last_packet"),
        "simulation_enabled": False,
        "error": status.get("error_detail"),
        "serial_busy": is_busy,
        "status_message": status_message
    }

@app.post("/api/arduino/disconnect")
@app.get("/api/arduino/disconnect")
def arduino_disconnect():
    serial_manager.disconnect()
    return {"status": "success", "message": "Disconnected from Arduino"}

@app.post("/api/arduino/release")
@app.get("/api/arduino/release")
@app.post("/api/serial/pause")
@app.get("/api/serial/pause")
@app.post("/api/serial/release")
@app.get("/api/serial/release")
def release_serial_port():
    """Release the serial port so Arduino IDE can flash firmware without COM port conflict."""
    return serial_manager.pause()

@app.post("/api/arduino/reconnect")
@app.get("/api/arduino/reconnect")
@app.post("/api/serial/resume")
@app.get("/api/serial/resume")
def resume_serial_port():
    """Resume the serial reader after firmware upload completes."""
    return serial_manager.resume()

@app.get("/api/serial/status")
def get_serial_status():
    """Get real-time connection, port, and pause state of the serial reader."""
    return serial_manager.get_status()

@app.post("/api/serial/port")
def set_serial_port_override(payload: dict):
    """Override the target serial port dynamically (e.g. {'port': 'COM11'} or {'port': 'AUTO'})."""
    port = payload.get("port")
    serial_manager.set_override_port(port)
    return {"status": "success", "override_port": serial_manager.override_port or "AUTO"}

@app.post("/api/sensor/data")
async def receive_sensor_data(request: Request):
    if not settings.ALLOW_SIMULATION:
        raise HTTPException(
            status_code=403,
            detail="Simulation and HTTP sensor ingestion disabled. Real hardware readings only via serial."
        )
    data = await request.json()
    data["source"] = "simulator"
    data["is_simulated"] = True
    result = await sensor_service.process_reading(data, manager)
    return result

@app.get("/api/cattle")
@app.get("/api/v1/cattle")
def get_cattle(db: Session = Depends(get_db)):
    from sqlalchemy import or_
    cattle_list = db.query(models.Cattle).filter(
        or_(models.Cattle.deleted == False, models.Cattle.deleted == None)
    ).all()
    return [{
        "id": c.id,
        "cattle_id": c.cattle_id,
        "name": c.name or c.cattle_id,
        "status": c.status or "normal",
        "gender": c.gender,
        "breed": c.breed,
        "age": c.age,
        "dob": c.dob,
        "weight": c.weight,
        "tag_id": c.tag_id,
        "created_at": c.created_at,
        "updated_at": c.updated_at,
        "deleted": bool(c.deleted)
    } for c in cattle_list]

@app.get("/api/cows")
@app.get("/api/v1/cows")
def get_cows(include_simulated: bool = False, db: Session = Depends(get_db)):
    from sqlalchemy import or_
    cattle_list = db.query(models.Cattle).filter(
        or_(models.Cattle.deleted == False, models.Cattle.deleted == None)
    ).all()
    results = []
    for c in cattle_list:
        reading_q = db.query(models.SensorReading).filter(models.SensorReading.cattle_id == c.cattle_id)
        if not include_simulated:
            reading_q = reading_q.filter(models.SensorReading.is_simulated == False)
        reading = reading_q.order_by(models.SensorReading.timestamp.desc()).first()
        prediction = None
        if reading:
            prediction = db.query(models.Prediction).filter(models.Prediction.reading_id == reading.id).first()
        latest_milk = db.query(models.MilkProduction).filter(models.MilkProduction.cattle_id == c.cattle_id).order_by(models.MilkProduction.date.desc()).first()
        latest_feed = db.query(models.FeedConsumption).filter(models.FeedConsumption.cattle_id == c.cattle_id).order_by(models.FeedConsumption.date.desc()).first()
        latest_act = db.query(models.Activity).filter(models.Activity.cattle_id == c.cattle_id).order_by(models.Activity.date.desc()).first()
        active_alerts = db.query(models.Alert).filter(models.Alert.cattle_id == c.cattle_id, models.Alert.status == "active").count()
        device = db.query(models.Device).filter(models.Device.cattle_id == c.cattle_id).first()
        latest_step_record = db.query(models.CattleStepReading).filter(
            models.CattleStepReading.cattle_id == c.cattle_id
        ).order_by(models.CattleStepReading.measured_at.desc()).first()

        step_today_val = reading.step_count_today if (reading and reading.step_count_today is not None) else (
            latest_step_record.steps_today if latest_step_record else (latest_act.steps if latest_act else None)
        )
        step_life_val = reading.step_count_lifetime if (reading and reading.step_count_lifetime is not None) else (
            latest_step_record.total_steps if latest_step_record else None
        )

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
                "humidity": reading.humidity if reading else None,
                "ph": reading.ph if reading else None,
                "ldr": reading.ldr if reading else None,
                "body_weight_kg": reading.body_weight_kg if (reading and reading.body_weight_kg is not None) else (c.weight if c else None),
                "weight_raw": reading.weight_raw if reading else None,
                "weight_stable": reading.weight_stable if reading else False,
                "weight_calibrated": reading.weight_calibrated if reading else False,
                "weight_valid": reading.weight_valid if reading else (True if c and c.weight else False),
                "weight_sensor_status": reading.weight_sensor_status if reading else ("stable" if c and c.weight else "disconnected"),
                "step_count_today": step_today_val,
                "step_count_lifetime": step_life_val,
                "acceleration_x": reading.acceleration_x if reading else None,
                "acceleration_y": reading.acceleration_y if reading else None,
                "acceleration_z": reading.acceleration_z if reading else None,
                "accelerometer_valid": reading.accelerometer_valid if reading else False,
                "heart_rate_valid": reading.heart_rate_valid if reading else False,
                "spo2_valid": reading.spo2_valid if reading else False,
                "temperature_valid": reading.temperature_valid if reading else False,
                "ph_valid": reading.ph_valid if reading else False,
                "ldr_valid": reading.ldr_valid if reading else False,
                "source": reading.source if reading else None,
                "is_simulated": reading.is_simulated if reading else False,
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
def get_cow(cattle_id: str, include_simulated: bool = False, db: Session = Depends(get_db)):
    from sqlalchemy import or_
    c = db.query(models.Cattle).filter(
        models.Cattle.cattle_id == cattle_id,
        or_(models.Cattle.deleted == False, models.Cattle.deleted == None)
    ).first()
    if not c:
        raise HTTPException(status_code=404, detail="Cow not found")
    reading_q = db.query(models.SensorReading).filter(models.SensorReading.cattle_id == c.cattle_id)
    if not include_simulated:
        reading_q = reading_q.filter(models.SensorReading.is_simulated == False)
    reading = reading_q.order_by(models.SensorReading.timestamp.desc()).first()
    prediction = None
    if reading:
        prediction = db.query(models.Prediction).filter(models.Prediction.reading_id == reading.id).first()
    latest_milk = db.query(models.MilkProduction).filter(models.MilkProduction.cattle_id == c.cattle_id).order_by(models.MilkProduction.date.desc()).first()
    latest_feed = db.query(models.FeedConsumption).filter(models.FeedConsumption.cattle_id == c.cattle_id).order_by(models.FeedConsumption.date.desc()).first()
    latest_act = db.query(models.Activity).filter(models.Activity.cattle_id == c.cattle_id).order_by(models.Activity.date.desc()).first()
    active_alerts = db.query(models.Alert).filter(models.Alert.cattle_id == c.cattle_id, models.Alert.status == "active").count()
    device = db.query(models.Device).filter(models.Device.cattle_id == c.cattle_id).first()
    latest_step_record = db.query(models.CattleStepReading).filter(
        models.CattleStepReading.cattle_id == c.cattle_id
    ).order_by(models.CattleStepReading.measured_at.desc()).first()

    step_today_val = reading.step_count_today if (reading and reading.step_count_today is not None) else (
        latest_step_record.steps_today if latest_step_record else (latest_act.steps if latest_act else None)
    )
    step_life_val = reading.step_count_lifetime if (reading and reading.step_count_lifetime is not None) else (
        latest_step_record.total_steps if latest_step_record else None
    )

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
            "humidity": reading.humidity if reading else None,
            "ph": reading.ph if reading else None,
            "ldr": reading.ldr if reading else None,
            "body_weight_kg": reading.body_weight_kg if (reading and reading.body_weight_kg is not None) else (c.weight if c else None),
            "weight_raw": reading.weight_raw if reading else None,
            "weight_stable": reading.weight_stable if reading else False,
            "weight_calibrated": reading.weight_calibrated if reading else False,
            "weight_valid": reading.weight_valid if reading else (True if c and c.weight else False),
            "weight_sensor_status": reading.weight_sensor_status if reading else ("stable" if c and c.weight else "disconnected"),
            "step_count_today": step_today_val,
            "step_count_lifetime": step_life_val,
            "acceleration_x": reading.acceleration_x if reading else None,
            "acceleration_y": reading.acceleration_y if reading else None,
            "acceleration_z": reading.acceleration_z if reading else None,
            "accelerometer_valid": reading.accelerometer_valid if reading else False,
            "heart_rate_valid": reading.heart_rate_valid if reading else False,
            "spo2_valid": reading.spo2_valid if reading else False,
            "temperature_valid": reading.temperature_valid if reading else False,
            "ph_valid": reading.ph_valid if reading else False,
            "ldr_valid": reading.ldr_valid if reading else False,
            "source": reading.source if reading else None,
            "is_simulated": reading.is_simulated if reading else False,
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
    # Unassign any active device assignments
    db.query(models.Device).filter(models.Device.cattle_id == cattle_id).update({"cattle_id": None})
    db.commit()
    logger.info(f"[DELETE COW] Soft-deleted cow and unassigned devices for: {cattle_id}")
    return None

@app.get("/api/cattle/{cattle_id}/latest")
def get_latest_reading(cattle_id: str, include_simulated: bool = False, db: Session = Depends(get_db)):
    reading_q = db.query(models.SensorReading).filter(models.SensorReading.cattle_id == cattle_id)
    if not include_simulated:
        reading_q = reading_q.filter(models.SensorReading.is_simulated == False)
    reading = reading_q.order_by(models.SensorReading.timestamp.desc()).first()
    if not reading:
        raise HTTPException(status_code=404, detail="No readings found")
    
    prediction = db.query(models.Prediction).filter(models.Prediction.reading_id == reading.id).first()
    
    fall_detected = (prediction.mems_status == "abnormal") if prediction else False
    latest_step_rec = db.query(models.CattleStepReading).filter(
        models.CattleStepReading.cattle_id == cattle_id
    ).order_by(models.CattleStepReading.measured_at.desc()).first()

    step_today = reading.step_count_today if reading.step_count_today is not None else (
        latest_step_rec.steps_today if latest_step_rec else None
    )
    step_lifetime = reading.step_count_lifetime if reading.step_count_lifetime is not None else (
        latest_step_rec.total_steps if latest_step_rec else None
    )

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
        "acceleration_x": reading.acceleration_x,
        "acceleration_y": reading.acceleration_y,
        "acceleration_z": reading.acceleration_z,
        "step_count_today": step_today,
        "step_count_lifetime": step_lifetime,
        "body_weight_kg": reading.body_weight_kg,
        "weight_raw": reading.weight_raw,
        "weight_stable": reading.weight_stable,
        "weight_calibrated": reading.weight_calibrated,
        "weight_valid": reading.weight_valid,
        "weight_sensor_status": reading.weight_sensor_status,
        "accelerometer_valid": reading.accelerometer_valid,
        "heart_rate_valid": reading.heart_rate_valid,
        "spo2_valid": reading.spo2_valid,
        "temperature_valid": reading.temperature_valid,
        "ph_valid": reading.ph_valid,
        "ldr_valid": reading.ldr_valid,
        "device_connected": reading.device_connected,
        "ph": reading.ph,
        "ldr": reading.ldr,
        "source": reading.source,
        "is_simulated": reading.is_simulated,
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
def get_history(cattle_id: str, limit: int = Query(default=20), include_simulated: bool = False, db: Session = Depends(get_db)):
    readings_q = db.query(models.SensorReading).filter(models.SensorReading.cattle_id == cattle_id)
    if not include_simulated:
        readings_q = readings_q.filter(models.SensorReading.is_simulated == False)
    readings = readings_q.order_by(models.SensorReading.timestamp.desc()).limit(limit).all()
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
            "body_weight_kg": r.body_weight_kg,
            "weight_stable": r.weight_stable,
            "step_count_today": r.step_count_today,
            "step_count_lifetime": r.step_count_lifetime,
            "source": r.source,
            "is_simulated": r.is_simulated,
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

class AlertCreateRequest(BaseModel):
    cattle_id: str
    type: str
    severity: str = "WARNING"
    message: str
    value: Optional[float] = None
    threshold: Optional[str] = None
    device_id: Optional[str] = None

@app.get("/api/thresholds")
def get_thresholds():
    from thresholds import CATTLE_THRESHOLDS
    return CATTLE_THRESHOLDS

@app.get("/api/alerts")
def get_alerts(
    status: Optional[str] = None,
    cattle_id: Optional[str] = None,
    severity: Optional[str] = None,
    limit: int = 50,
    db: Session = Depends(get_db)
):
    query = db.query(models.Alert)
    if status:
        query = query.filter(models.Alert.status.ilike(status.strip()))
    if cattle_id:
        query = query.filter(models.Alert.cattle_id == cattle_id.strip().upper())
    if severity:
        query = query.filter(models.Alert.severity.ilike(severity.strip()))
        
    db_alerts = query.order_by(models.Alert.created_at.desc()).limit(limit).all()
    
    if db_alerts:
        from thresholds import CATTLE_THRESHOLDS
        results = []
        for a in db_alerts:
            cow = db.query(models.Cattle).filter(models.Cattle.cattle_id == a.cattle_id).first()
            cow_name = cow.name if cow and cow.name else a.cattle_id
            
            ts = a.created_at or datetime.now(timezone.utc)
            if getattr(ts, 'tzinfo', None) is None:
                ts_utc = ts.replace(tzinfo=timezone.utc)
            else:
                ts_utc = ts
            ts_iso = ts_utc.isoformat()
            
            # Recommendation lookup
            sensor_key = (a.type or "").lower()
            rec = "Inspect animal and provide supportive veterinary care."
            if sensor_key in CATTLE_THRESHOLDS:
                rec = CATTLE_THRESHOLDS[sensor_key].get("recommendation_high") or CATTLE_THRESHOLDS[sensor_key].get("recommendation_low") or rec
            elif a.type == "FALL":
                rec = CATTLE_THRESHOLDS.get("mems", {}).get("recommendation_fall", rec)
                
            results.append({
                "id": a.id,
                "cattle_id": a.cattle_id or "unknown",
                "cattle_name": cow_name,
                "timestamp": ts_iso,
                "fall_detected": (a.type == "FALL"),
                "details": [a.message] if a.message else [f"{a.type} abnormal"],
                "type": a.type,
                "sensor_type": a.type,
                "severity": a.severity or "WARNING",
                "status": a.status or "active",
                "value": a.value,
                "threshold": a.threshold,
                "recommended_action": rec
            })
        return results

    # Fallback to models.Prediction for environments/tests where models.Alert has not been populated
    abnormal = db.query(models.Prediction).filter(models.Prediction.overall_status == "abnormal").order_by(models.Prediction.timestamp.desc()).limit(limit).all()
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
        ts = p.timestamp
        if ts is not None:
            if getattr(ts, 'tzinfo', None) is None:
                ts_utc = ts.replace(tzinfo=timezone.utc)
            else:
                ts_utc = ts
        else:
            ts_utc = datetime.now(timezone.utc)
        ts_iso = ts_utc.isoformat()

        alerts.append({
            "id": p.id,
            "cattle_id": reading.cattle_id if reading else "unknown",
            "cattle_name": cattle.name if cattle else "Unknown",
            "timestamp": ts_iso,
            "fall_detected": fall_detected,
            "details": details,
            "type": "FALL" if fall_detected else "SENSOR_ABNORMAL",
            "sensor_type": "FALL" if fall_detected else "SENSOR",
            "severity": "CRITICAL" if fall_detected else "WARNING",
            "status": "active",
            "value": None,
            "threshold": "Normal Range",
            "recommended_action": "Check cow immediately for injury or abnormal vitals."
        })
    return alerts

@app.post("/api/alerts", status_code=201)
async def create_alert(req: AlertCreateRequest, db: Session = Depends(get_db)):
    cid = req.cattle_id.strip().upper()
    alert = models.Alert(
        cattle_id=cid,
        device_id=req.device_id or "MANUAL",
        type=req.type.upper(),
        severity=req.severity.upper(),
        message=req.message,
        value=req.value,
        threshold=req.threshold,
        status="active"
    )
    db.add(alert)
    db.commit()
    db.refresh(alert)
    
    # Broadcast over WebSocket
    try:
        cow = db.query(models.Cattle).filter(models.Cattle.cattle_id == cid).first()
        cow_name = cow.name if cow and cow.name else cid
        now_utc = datetime.now(timezone.utc)
        evt_payload = {
            "event_id": f"EVT-{cid}-{req.type.upper()}-{int(now_utc.timestamp()*1000)}",
            "cattle_id": cid,
            "cattle_name": cow_name,
            "sensor_key": req.type.lower(),
            "sensor_name": req.type.title(),
            "current_value": str(req.value) if req.value is not None else "Abnormal",
            "raw_value": req.value,
            "status": "ABNORMAL",
            "expected_range": req.threshold or "Normal bounds",
            "timestamp": now_utc.isoformat(),
            "severity": req.severity.upper(),
            "recommended_action": req.message
        }
        from app.websocket.manager import manager as ws_mgr
        ws_evt = ws_mgr.format_event(event_type="alert", payload=evt_payload, cattle_id=cid)
        await ws_mgr.broadcast(ws_evt)
    except Exception as e:
        logger.warning(f"Failed to broadcast manually created alert: {e}")
        
    return {
        "id": alert.id,
        "cattle_id": alert.cattle_id,
        "type": alert.type,
        "severity": alert.severity,
        "message": alert.message,
        "status": alert.status
    }

@app.put("/api/alerts/{alert_id}/read")
def mark_alert_read(alert_id: int, db: Session = Depends(get_db)):
    alert = db.query(models.Alert).filter(models.Alert.id == alert_id).first()
    if not alert:
        raise HTTPException(status_code=404, detail="Alert not found")
    alert.status = "read"
    alert.acknowledged_at = datetime.utcnow()
    db.commit()
    return {"status": "ok", "alert_id": alert_id, "alert_status": "read"}

@app.put("/api/alerts/{alert_id}/resolve")
def mark_alert_resolved(alert_id: int, db: Session = Depends(get_db)):
    alert = db.query(models.Alert).filter(models.Alert.id == alert_id).first()
    if not alert:
        raise HTTPException(status_code=404, detail="Alert not found")
    alert.status = "resolved"
    alert.resolved_at = datetime.utcnow()
    db.commit()
    return {"status": "ok", "alert_id": alert_id, "alert_status": "resolved"}

@app.get("/api/sms/config")
def get_sms_config():
    from app.sms_service import validate_phone_number
    target = getattr(settings, "target_phone_number", None) or getattr(settings, "ALERT_PHONE_NUMBER", None)
    is_valid, normalized, err = validate_phone_number(target)
    
    # Mask phone for privacy in logs/APIs
    masked = None
    if normalized and len(normalized) >= 8:
        masked = normalized[:3] + "******" + normalized[-4:]
    elif target:
        masked = target[:2] + "****" if len(target) > 2 else "****"
        
    return {
        "sms_enabled": getattr(settings, "SMS_ALERT_ENABLED", True),
        "phone_configured": is_valid,
        "phone_number_masked": masked,
        "phone_validation_error": err,
        "provider": getattr(settings, "SMS_PROVIDER", "fast2sms")
    }

@app.get("/api/sms/logs")
def get_sms_logs(limit: int = 50, db: Session = Depends(get_db)):
    logs = db.query(models.SmsLog).order_by(models.SmsLog.timestamp.desc()).limit(limit).all()
    return [
        {
            "id": l.id,
            "cattle_id": l.cattle_id,
            "alert_type": l.alert_type,
            "severity": l.severity,
            "timestamp": l.timestamp.isoformat() if l.timestamp else None,
            "location": l.location,
            "status": l.status,
            "reason": l.reason,
            "phone_number": l.phone_number,
            "message": l.message,
            "provider": l.provider,
            "event_id": l.event_id
        }
        for l in logs
    ]

@app.post("/api/sms/test")
def send_test_sms(db: Session = Depends(get_db)):
    from app.sms_service import validate_phone_number, get_sms_provider, format_sms_message
    target = getattr(settings, "target_phone_number", None) or getattr(settings, "ALERT_PHONE_NUMBER", None)
    is_valid, normalized, err = validate_phone_number(target)
    if not is_valid:
        raise HTTPException(status_code=400, detail=f"Cannot send test SMS: {err}")

    test_msg = format_sms_message(
        cattle_id="TEST-COW",
        sensor_name="Temperature",
        reading_value="40.5°C",
        severity="HIGH"
    )
    provider = get_sms_provider()
    success, status, reason = provider.send_sms(normalized, test_msg)
    
    sms_log = models.SmsLog(
        cattle_id="TEST-COW",
        alert_type="TEST",
        severity="HIGH",
        timestamp=datetime.utcnow(),
        status=status,
        reason=reason,
        phone_number=normalized,
        message=test_msg,
        provider=provider.name,
        event_id="TEST-MANUAL"
    )
    db.add(sms_log)
    db.commit()
    
    return {
        "success": success,
        "status": status,
        "provider": provider.name,
        "recipient": normalized,
        "detail": reason
    }

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
            
        reading = db.query(models.SensorReading).filter(
            models.SensorReading.cattle_id == c.cattle_id,
            models.SensorReading.is_simulated == False
        ).order_by(models.SensorReading.timestamp.desc()).first()
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
async def general_websocket_endpoint(websocket: WebSocket, client_id: str = Query(None), reconnect: bool = Query(False)):
    if not client_id:
        await websocket.close(code=1008)
        return
    await manager.connect(websocket, client_id, reconnect)
    manager.subscribe(client_id, "CATTLE-ALL")
    try:
        while True:
            data = await websocket.receive_text()
            if "ping" in data:
                if client_id in manager.active_connections:
                    manager.active_connections[client_id].update_last_seen()
                pong_event = WebSocketEvent(
                    event_id=str(uuid.uuid4()),
                    event_type="heartbeat",
                    timestamp=datetime.now(timezone.utc),
                    payload={"message": "pong"}
                )
                await websocket.send_text(pong_event.model_dump_json())
            elif "pong" in data:
                if client_id in manager.active_connections:
                    manager.active_connections[client_id].update_last_seen()
            elif data.startswith("subscribe:"):
                new_cattle = data.split(":")[1]
                manager.subscribe(client_id, new_cattle)
    except WebSocketDisconnect:
        manager.disconnect(client_id, reason="client_disconnect")

@app.websocket("/ws/cattle/{cattle_id}")
async def websocket_endpoint(websocket: WebSocket, cattle_id: str, client_id: str = Query(None), reconnect: bool = Query(False)):
    # Use cattle_id as client_id if not provided
    actual_client_id = client_id or f"client_{cattle_id}_{int(time.time())}"
    await manager.connect(websocket, actual_client_id, reconnect)
    manager.subscribe(actual_client_id, cattle_id)
    
    # Send immediate latest telemetry snapshot upon connection
    try:
        target_ids = ["CATTLE-001"] if cattle_id in ("CATTLE-ALL", "ALL", "*") else [cattle_id]
        db = SessionLocal()
        try:
            for cid in target_ids:
                reading = db.query(models.SensorReading).filter(
                    models.SensorReading.cattle_id == cid,
                    models.SensorReading.is_simulated == False
                ).order_by(models.SensorReading.timestamp.desc()).first()
                if reading:
                    data = {
                        "cattle_id": cid,
                        "timestamp": reading.timestamp.isoformat() if reading.timestamp else "",
                        "spo2": reading.spo2,
                        "heart_rate": reading.heart_rate,
                        "bpm": reading.heart_rate,
                        "temperature": reading.temperature,
                        "humidity": reading.humidity,
                        "ph": reading.ph,
                        "ldr": reading.ldr,
                        "acceleration_x": reading.acceleration_x,
                        "acceleration_y": reading.acceleration_y,
                        "acceleration_z": reading.acceleration_z,
                        "mems_x": reading.acceleration_x,
                        "mems_y": reading.acceleration_y,
                        "mems_z": reading.acceleration_z,
                        "step_count_today": reading.step_count_today,
                        "step_count_lifetime": reading.step_count_lifetime,
                        "body_weight_kg": reading.body_weight_kg,
                        "weight_raw": reading.weight_raw,
                        "weight_stable": reading.weight_stable,
                        "weight_calibrated": reading.weight_calibrated,
                        "weight_sensor_status": reading.weight_sensor_status,
                        "weight_valid": reading.weight_valid,
                        "accelerometer_valid": reading.accelerometer_valid,
                        "heart_rate_valid": reading.heart_rate_valid,
                        "spo2_valid": reading.spo2_valid,
                        "temperature_valid": reading.temperature_valid,
                        "ph_valid": reading.ph_valid,
                        "ldr_valid": reading.ldr_valid,
                        "device_connected": True,
                        "source": "serial",
                        "is_simulated": False
                    }
                    health = {
                        "spo2": "normal",
                        "heart_rate": "normal",
                        "temperature": "normal",
                        "mems": "normal",
                        "ph": "normal",
                        "ldr": "normal",
                        "overall": "normal",
                        "fall_detected": reading.fall_detected or False
                    }
                    payload = {
                        "type": "sensor_update",
                        "cattle_id": cid,
                        "data": data,
                        "health": health,
                        "validity": {},
                        "validity_reasons": {},
                        "source": "serial",
                        "is_simulated": False
                    }
                    ws_event = manager.format_event(
                        event_type="sensor_update",
                        payload=payload,
                        cattle_id=cid
                    )
                    await websocket.send_text(ws_event.model_dump_json())
        finally:
            db.close()
    except Exception as e:
        logger.warning(f"Failed to send initial websocket telemetry snapshot: {e}")
    try:
        while True:
            data = await websocket.receive_text()
            if "ping" in data:
                if actual_client_id in manager.active_connections:
                    manager.active_connections[actual_client_id].update_last_seen()
                pong_event = WebSocketEvent(
                    event_id=str(uuid.uuid4()),
                    event_type="heartbeat",
                    timestamp=datetime.now(timezone.utc),
                    payload={"message": "pong"}
                )
                await websocket.send_text(pong_event.model_dump_json())
            elif "pong" in data:
                if actual_client_id in manager.active_connections:
                    manager.active_connections[actual_client_id].update_last_seen()
            elif data.startswith("subscribe:"):
                new_cattle = data.split(":")[1]
                manager.subscribe(actual_client_id, new_cattle)
    except WebSocketDisconnect:
        manager.disconnect(actual_client_id, reason="client_disconnect")

@app.websocket("/ws/dashboard")
async def dashboard_websocket_endpoint(websocket: WebSocket, client_id: str = Query(None), reconnect: bool = Query(False)):
    actual_client_id = client_id or f"dashboard_{int(time.time())}"
    await manager.connect(websocket, actual_client_id, reconnect)
    # By not subscribing to any specific cattle_id, this connection will receive all events
    try:
        while True:
            data = await websocket.receive_text()
            if "ping" in data:
                if actual_client_id in manager.active_connections:
                    manager.active_connections[actual_client_id].update_last_seen()
                pong_event = WebSocketEvent(
                    event_id=str(uuid.uuid4()),
                    event_type="heartbeat",
                    timestamp=datetime.now(timezone.utc),
                    payload={"message": "pong"}
                )
                await websocket.send_text(pong_event.model_dump_json())
            elif "pong" in data:
                if actual_client_id in manager.active_connections:
                    manager.active_connections[actual_client_id].update_last_seen()
    except WebSocketDisconnect:
        manager.disconnect(actual_client_id, reason="client_disconnect")

if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host=settings.API_HOST, port=settings.API_PORT)
