from fastapi import APIRouter, Depends, HTTPException, Query, status
from sqlalchemy.orm import Session
from typing import List, Optional
import logging

from app.database.database import get_db
from app.database import models
from app.schemas import steps as schemas
from app.services import DatabaseService

logger = logging.getLogger("steps_router")
router = APIRouter(tags=["steps"])

# ── 1. Latest Step Reading ──

@router.get(
    "/api/v1/cattle/{cattle_id}/steps/latest",
    response_model=schemas.CattleStepReadingResponse
)
@router.get(
    "/api/cattle/{cattle_id}/steps/latest",
    response_model=schemas.CattleStepReadingResponse,
    include_in_schema=False
)
def get_latest_steps(
    cattle_id: str,
    include_simulated: bool = False,
    db: Session = Depends(get_db)
):
    """
    Get the most recent authenticated step reading for a specific cow.
    """
    cow = db.query(models.Cattle).filter(
        models.Cattle.cattle_id == cattle_id,
        models.Cattle.deleted == False
    ).first()
    if not cow:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail=f"Cattle with ID '{cattle_id}' not found or deleted"
        )

    reading = DatabaseService.get_latest_step_reading(cattle_id, include_simulated)
    if not reading:
        # Fallback to sensor_reading values if cattle_step_readings not yet written
        sr = db.query(models.SensorReading).filter(
            models.SensorReading.cattle_id == cattle_id
        ).order_by(models.SensorReading.timestamp.desc()).first()

        if sr and (sr.step_count_today is not None or sr.step_count_lifetime is not None):
            reading = DatabaseService.save_step_reading(
                cattle_id=cattle_id,
                device_id=sr.device_id,
                steps_today=sr.step_count_today or 0,
                total_steps=sr.step_count_lifetime or 0,
                step_increment=0,
                step_sensor_status="active",
                step_algorithm_version="1.0",
                measured_at=sr.timestamp,
                is_simulated=sr.is_simulated or False
            )
        else:
            raise HTTPException(
                status_code=status.HTTP_404_NOT_FOUND,
                detail=f"No step readings found for cattle '{cattle_id}'"
            )

    return reading


# ── 2. Step History ──

@router.get(
    "/api/v1/cattle/{cattle_id}/steps/history",
    response_model=List[schemas.CattleStepReadingResponse]
)
@router.get(
    "/api/cattle/{cattle_id}/steps/history",
    response_model=List[schemas.CattleStepReadingResponse],
    include_in_schema=False
)
def get_steps_history(
    cattle_id: str,
    limit: int = Query(default=50, ge=1, le=500),
    include_simulated: bool = False,
    db: Session = Depends(get_db)
):
    """
    Get chronological step history readings for a specific cow (most recent first).
    """
    cow = db.query(models.Cattle).filter(
        models.Cattle.cattle_id == cattle_id,
        models.Cattle.deleted == False
    ).first()
    if not cow:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail=f"Cattle with ID '{cattle_id}' not found or deleted"
        )

    readings = DatabaseService.get_step_history(cattle_id, limit, include_simulated)
    return readings


# ── 3. Step Statistics ──

@router.get(
    "/api/v1/cattle/{cattle_id}/steps/statistics",
    response_model=schemas.CattleStepStatisticsResponse
)
@router.get(
    "/api/cattle/{cattle_id}/steps/statistics",
    response_model=schemas.CattleStepStatisticsResponse,
    include_in_schema=False
)
def get_steps_statistics(
    cattle_id: str,
    include_simulated: bool = False,
    db: Session = Depends(get_db)
):
    """
    Get comprehensive step and activity statistics for a specific cow:
    today's steps, total lifetime steps, daily average, hourly average, activity status.
    """
    cow = db.query(models.Cattle).filter(
        models.Cattle.cattle_id == cattle_id,
        models.Cattle.deleted == False
    ).first()
    if not cow:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail=f"Cattle with ID '{cattle_id}' not found or deleted"
        )

    stats = DatabaseService.get_step_statistics(cattle_id, include_simulated)
    return stats

