from pydantic import BaseModel
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
