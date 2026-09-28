from fastapi import APIRouter, Depends, Query
from sqlalchemy.orm import Session
from typing import Optional

from database.database import get_db
from analytics_service import AnalyticsService

router = APIRouter(prefix="/analytics", tags=["analytics"])

@router.get("/milk")
def get_milk_analytics(
    cattle_id: Optional[str] = None,
    days: int = Query(30, description="Number of days for historical data"),
    db: Session = Depends(get_db)
):
    return AnalyticsService.get_milk_analytics(db, cattle_id, days)

@router.get("/feed")
def get_feed_analytics(
    cattle_id: Optional[str] = None,
    days: int = Query(30, description="Number of days for historical data"),
    db: Session = Depends(get_db)
):
    return AnalyticsService.get_feed_analytics(db, cattle_id, days)

@router.get("/activity")
def get_activity_analytics(
    cattle_id: Optional[str] = None,
    days: int = Query(30, description="Number of days for historical data"),
    db: Session = Depends(get_db)
):
    return AnalyticsService.get_activity_analytics(db, cattle_id, days)

@router.get("/correlations/{cattle_id}")
def get_correlations(
    cattle_id: str,
    db: Session = Depends(get_db)
):
    return AnalyticsService.get_correlations(db, cattle_id)
