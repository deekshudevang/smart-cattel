from pydantic import BaseModel, ConfigDict
from typing import Optional, List
from datetime import datetime

class CattleStepReadingCreate(BaseModel):
    cattle_id: str
    device_id: Optional[str] = None
    steps_today: int
    total_steps: int
    step_increment: int = 0
    step_sensor_status: str = "active"
    step_algorithm_version: str = "1.0"
    measured_at: Optional[datetime] = None
    is_simulated: bool = False

class CattleStepReadingResponse(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: int
    cattle_id: str
    device_id: Optional[str] = None
    steps_today: int
    total_steps: int
    step_increment: int
    step_sensor_status: str
    step_algorithm_version: str
    measured_at: datetime
    created_at: datetime
    is_simulated: bool = False

class CattleStepStatisticsResponse(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    cattle_id: str
    steps_today: int
    total_steps: int
    daily_average: float
    hourly_average: float
    peak_hour: Optional[int] = None
    activity_status: str
    step_sensor_status: str
    last_updated: Optional[datetime] = None

