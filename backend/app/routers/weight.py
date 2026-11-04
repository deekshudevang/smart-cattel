from fastapi import APIRouter, Depends, HTTPException, Query, status
from sqlalchemy.orm import Session
from typing import List, Optional
from datetime import datetime, timezone
import logging

from app.database.database import get_db
from app.database import models
from app.schemas import cattle as schemas
from app.services import DatabaseService
from app.serial.manager import SerialManager

logger = logging.getLogger("weight_router")
router = APIRouter(tags=["weight"])
serial_manager = SerialManager()

# ── 1. Record Weight Measurement ──

@router.post(
    "/api/v1/sensors/weight",
    response_model=schemas.WeightReadingResponse,
    status_code=status.HTTP_201_CREATED
)
@router.post(
    "/api/sensors/weight",
    response_model=schemas.WeightReadingResponse,
    status_code=status.HTTP_201_CREATED,
    include_in_schema=False
)
def record_weight(
    payload: schemas.WeightReadingCreate,
    db: Session = Depends(get_db)
):
    """
    Record a confirmed weight measurement for a cow.
    Validates cattle existence, ensures weight is within physiological bounds (0.0 to 1500.0 kg),
    persists reading to cattle_weight_readings, and updates the cattle profile's current weight.
    """
    cow = db.query(models.Cattle).filter(
        models.Cattle.cattle_id == payload.cattle_id,
        models.Cattle.deleted == False
    ).first()
    if not cow:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail=f"Cattle with ID '{payload.cattle_id}' not found or deleted"
        )

    if payload.weight_kg < 0.0 or payload.weight_kg > 1500.0:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail="Weight reading out of valid range (0.0 to 1500.0 kg)"
        )

    reading = DatabaseService.save_weight_reading(
        cattle_id=payload.cattle_id,
        weight_kg=payload.weight_kg,
        device_id=payload.device_id,
        raw_value=payload.raw_value,
        is_stable=payload.is_stable if payload.is_stable is not None else True,
        is_calibrated=payload.is_calibrated if payload.is_calibrated is not None else True,
        measured_at=payload.measured_at,
        source=payload.source or "manual",
        is_simulated=payload.is_simulated or False
    )
    return reading


# ── 2. Latest Weight Reading ──

@router.get(
    "/api/v1/cattle/{cattle_id}/weight/latest",
    response_model=schemas.WeightReadingResponse
)
@router.get(
    "/api/cattle/{cattle_id}/weight/latest",
    response_model=schemas.WeightReadingResponse,
    include_in_schema=False
)
def get_latest_weight(
    cattle_id: str,
    db: Session = Depends(get_db)
):
    """
    Get the latest verified weight reading for a specific cow.
    """
    cow = db.query(models.Cattle).filter(
        models.Cattle.cattle_id == cattle_id,
        models.Cattle.deleted == False
    ).first()
    if not cow:
        raise HTTPException(status_code=404, detail="Cattle not found")

    latest = db.query(models.CattleWeightReading).filter(
        models.CattleWeightReading.cattle_id == cattle_id
    ).order_by(models.CattleWeightReading.measured_at.desc()).first()

    if not latest:
        # If no historical readings table record exists but cow has weight attribute
        if cow.weight is not None:
            now_utc = datetime.now(timezone.utc)
            # Create baseline record
            latest = DatabaseService.save_weight_reading(
                cattle_id=cattle_id,
                weight_kg=cow.weight,
                source="profile",
                is_stable=True,
                is_calibrated=True
            )
        else:
            raise HTTPException(status_code=404, detail="No weight readings found for this cattle")

    return latest


# ── 3. Weight History ──

@router.get(
    "/api/v1/cattle/{cattle_id}/weight/history",
    response_model=List[schemas.WeightReadingResponse]
)
@router.get(
    "/api/cattle/{cattle_id}/weight/history",
    response_model=List[schemas.WeightReadingResponse],
    include_in_schema=False
)
def get_weight_history(
    cattle_id: str,
    skip: int = Query(0, ge=0),
    limit: int = Query(100, ge=1, le=500),
    start_date: Optional[datetime] = None,
    end_date: Optional[datetime] = None,
    db: Session = Depends(get_db)
):
    """
    Get historical weight records for trend analytics and charts.
    Supports pagination and date-range filtering.
    """
    cow = db.query(models.Cattle).filter(
        models.Cattle.cattle_id == cattle_id,
        models.Cattle.deleted == False
    ).first()
    if not cow:
        raise HTTPException(status_code=404, detail="Cattle not found")

    query = db.query(models.CattleWeightReading).filter(
        models.CattleWeightReading.cattle_id == cattle_id
    )
    if start_date:
        query = query.filter(models.CattleWeightReading.measured_at >= start_date)
    if end_date:
        query = query.filter(models.CattleWeightReading.measured_at <= end_date)

    readings = query.order_by(models.CattleWeightReading.measured_at.desc()).offset(skip).limit(limit).all()
    return readings


# ── 4. Weight Statistics & Analytics ──

@router.get(
    "/api/v1/cattle/{cattle_id}/weight/statistics",
    response_model=schemas.WeightStatisticsResponse
)
@router.get(
    "/api/cattle/{cattle_id}/weight/statistics",
    response_model=schemas.WeightStatisticsResponse,
    include_in_schema=False
)
def get_weight_statistics(
    cattle_id: str,
    db: Session = Depends(get_db)
):
    """
    Compute weight difference, percentage gain/loss, 7-day and 30-day averages,
    and trending classification for cattle profile card.
    """
    cow = db.query(models.Cattle).filter(
        models.Cattle.cattle_id == cattle_id,
        models.Cattle.deleted == False
    ).first()
    if not cow:
        raise HTTPException(status_code=404, detail="Cattle not found")

    stats = DatabaseService.get_weight_statistics(cattle_id)
    return stats


# ── 5. Tare Scale Hardware Command ──

@router.post("/api/v1/sensors/weight/tare")
@router.post("/api/sensors/weight/tare", include_in_schema=False)
def tare_weight_scale(req: schemas.WeightTareRequest = None):
    """
    Send physical TARE command to the connected Arduino scale over serial.
    Zeros out the current platform weight.
    """
    dev_id = req.device_id if req else "SC-001"
    logger.info(f"[TARE] Triggering scale tare command for device {dev_id}")
    
    # Send serial command to Arduino
    serial_manager.write("TARE")
    return {
        "status": "command_sent",
        "action": "tare",
        "device_id": dev_id,
        "message": "Scale tare command dispatched to hardware"
    }


# ── 6. Calibrate Scale Hardware Command ──

@router.post("/api/v1/sensors/weight/calibrate")
@router.post("/api/sensors/weight/calibrate", include_in_schema=False)
def calibrate_weight_scale(req: schemas.WeightCalibrateRequest):
    """
    Send calibration command with known reference weight (in kg) to connected Arduino.
    Calculates calibration factor and persists to EEPROM.
    """
    if req.reference_weight_kg <= 0.0 or req.reference_weight_kg > 1500.0:
        raise HTTPException(
            status_code=400,
            detail="Reference weight must be greater than 0 and up to 1500 kg"
        )

    logger.info(f"[CALIBRATE] Triggering scale calibration with reference {req.reference_weight_kg} kg")
    
    # Send serial command to Arduino: "CAL_WEIGHT:<ref_kg>"
    serial_manager.write(f"CAL_WEIGHT:{req.reference_weight_kg:.2f}")
    return {
        "status": "command_sent",
        "action": "calibrate",
        "reference_weight_kg": req.reference_weight_kg,
        "device_id": req.device_id,
        "message": f"Scale calibration command with {req.reference_weight_kg} kg dispatched to hardware"
    }

