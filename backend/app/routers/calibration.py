from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy.orm import Session
from datetime import datetime
from database.database import get_db
from database import models
from schemas.calibration import PHCalibrationCreate, PHCalibrationResponse

router = APIRouter(prefix="/api/calibration", tags=["calibration"])

@router.post("/ph", response_model=PHCalibrationResponse, status_code=status.HTTP_201_CREATED)
def add_ph_calibration(data: PHCalibrationCreate, db: Session = Depends(get_db)):
    # Calculate slope and intercept
    if data.buffer_2_voltage == data.buffer_1_voltage:
        raise HTTPException(status_code=400, detail="Voltages must be different for calibration")
    
    slope = (data.buffer_2 - data.buffer_1) / (data.buffer_2_voltage - data.buffer_1_voltage)
    intercept = data.buffer_1 - (slope * data.buffer_1_voltage)
    
    # Store calibration
    calibration = models.SensorCalibration(
        device_id=data.device_id,
        sensor_type="ph",
        calibration_date=datetime.utcnow(),
        buffer_1=data.buffer_1,
        buffer_1_voltage=data.buffer_1_voltage,
        buffer_2=data.buffer_2,
        buffer_2_voltage=data.buffer_2_voltage,
        slope=slope,
        intercept=intercept,
        performed_by=data.performed_by
    )
    db.add(calibration)
    db.commit()
    db.refresh(calibration)
    return calibration

@router.get("/ph/{device_id}", response_model=PHCalibrationResponse)
def get_latest_ph_calibration(device_id: str, db: Session = Depends(get_db)):
    calibration = db.query(models.SensorCalibration).filter(
        models.SensorCalibration.device_id == device_id,
        models.SensorCalibration.sensor_type == "ph"
    ).order_by(models.SensorCalibration.calibration_date.desc()).first()
    
    if not calibration:
        raise HTTPException(status_code=404, detail="No pH calibration found for this device")
        
    return calibration
