from pydantic import BaseModel
from typing import Optional, List
from datetime import datetime

class DeviceBase(BaseModel):
    serial_number: Optional[str] = None
    firmware_version: Optional[str] = None
    hardware_revision: Optional[str] = None
    installation_date: Optional[datetime] = None
    battery_level: Optional[float] = None
    last_maintenance: Optional[datetime] = None
    notes: Optional[str] = None
    cattle_id: Optional[str] = None
    status: Optional[str] = "active"

class DeviceCreate(DeviceBase):
    device_id: str

class DeviceUpdate(DeviceBase):
    pass

class Device(DeviceBase):
    id: int
    device_id: str
    created_at: datetime
    updated_at: datetime
    deleted: bool

    class Config:
        from_attributes = True
