from pydantic import BaseModel, field_validator
from typing import Optional

class SensorDataSchema(BaseModel):
    protocol_version: int = 1
    device_id: str
    sequence: int
    timestamp: int

    spo2: Optional[int] = None
    spo2_valid: bool = False
    spo2_quality: float = 0.0

    bpm: Optional[int] = None
    bpm_valid: bool = False
    bpm_quality: float = 0.0

    temperature: Optional[float] = None
    temperature_valid: bool = False

    humidity: Optional[float] = None
    humidity_valid: bool = False

    mems_x: Optional[float] = None
    mems_y: Optional[float] = None
    mems_z: Optional[float] = None
    mems_valid: bool = False
    
    acceleration_magnitude: Optional[float] = None
    activity_state: Optional[str] = None
    activity_score: Optional[float] = None
    fall_detected: bool = False
    fall_confidence: Optional[float] = None

    ph: Optional[float] = None
    ph_valid: bool = False

    @field_validator('ph')
    @classmethod
    def check_ph(cls, v):
        if v is not None and not (0.0 <= v <= 14.0):
            raise ValueError('pH must be between 0.0 and 14.0')
        return v

    ldr: Optional[int] = None
    ldr_valid: bool = False
