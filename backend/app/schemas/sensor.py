from pydantic import BaseModel
from typing import Optional

class SensorDataSchema(BaseModel):
    cattle_id: str
    spo2: Optional[int] = None
    bpm: Optional[int] = None
    temperature: Optional[float] = None
    humidity: Optional[float] = None
    mems_x: Optional[float] = None
    mems_y: Optional[float] = None
    mems_z: Optional[float] = None
    ph: Optional[float] = None
    ldr: Optional[int] = None
