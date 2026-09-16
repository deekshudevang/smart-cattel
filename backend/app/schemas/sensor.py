from pydantic import BaseModel
from typing import Optional

class SensorDataSchema(BaseModel):
    cattle_id: str
    spo2: int
    bpm: int
    temperature: float
    humidity: float
    mems_x: float
    mems_y: float
    mems_z: float
    ph: float
    ldr: int
