from pydantic import BaseModel
from typing import Optional

class PHCalibrationCreate(BaseModel):
    device_id: str
    buffer_1: float
    buffer_1_voltage: float
    buffer_2: float
    buffer_2_voltage: float
    performed_by: Optional[str] = None

class PHCalibrationResponse(BaseModel):
    id: int
    device_id: str
    sensor_type: str
    buffer_1: float
    buffer_1_voltage: float
    buffer_2: float
    buffer_2_voltage: float
    slope: float
    intercept: float
    performed_by: Optional[str] = None
    
    class Config:
        orm_mode = True
