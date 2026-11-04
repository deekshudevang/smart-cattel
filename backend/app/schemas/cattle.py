from pydantic import BaseModel, Field
from typing import Optional, List
from datetime import datetime

class CattleBase(BaseModel):
    name: Optional[str] = None
    photo: Optional[str] = None
    breed: Optional[str] = None
    gender: Optional[str] = None
    dob: Optional[str] = None
    weight: Optional[float] = None
    tag_id: Optional[str] = None
    lactation_stage: Optional[str] = None
    pregnancy_status: Optional[str] = None
    notes: Optional[str] = None
    medical_history: Optional[str] = None
    status: Optional[str] = "normal"

class CattleCreate(CattleBase):
    cattle_id: str

class CattleUpdate(CattleBase):
    pass

class Cattle(CattleBase):
    id: int
    cattle_id: str
    created_at: datetime
    updated_at: datetime
    deleted: bool

    class Config:
        from_attributes = True

# ── Weight Monitoring Schemas ──

class WeightReadingCreate(BaseModel):
    cattle_id: str
    device_id: Optional[str] = None
    weight_kg: float = Field(..., ge=0.0, le=1500.0, description="Measured body weight in kg")
    raw_value: Optional[int] = None
    is_stable: Optional[bool] = True
    is_calibrated: Optional[bool] = True
    measured_at: Optional[datetime] = None
    source: Optional[str] = "manual"
    is_simulated: Optional[bool] = False

class WeightReadingResponse(BaseModel):
    id: int
    cattle_id: str
    device_id: Optional[str] = None
    weight_kg: float
    raw_value: Optional[int] = None
    is_stable: bool
    is_calibrated: bool
    measured_at: datetime
    created_at: datetime
    source: str
    is_simulated: bool

    class Config:
        from_attributes = True

class WeightStatisticsResponse(BaseModel):
    cattle_id: str
    current_weight_kg: Optional[float] = None
    previous_weight_kg: Optional[float] = None
    difference_kg: Optional[float] = None
    percentage_change: Optional[float] = None
    trend: str = "stable"  # "gain", "loss", "stable", "unknown"
    average_7d: Optional[float] = None
    average_30d: Optional[float] = None
    last_measured_at: Optional[datetime] = None
    is_stable: bool = False
    is_calibrated: bool = False
    readings_count: int = 0

class WeightTareRequest(BaseModel):
    device_id: Optional[str] = "SC-001"

class WeightCalibrateRequest(BaseModel):
    reference_weight_kg: float = Field(..., gt=0.0, le=1500.0)
    device_id: Optional[str] = "SC-001"
