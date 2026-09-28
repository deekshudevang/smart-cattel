from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy.orm import Session
from typing import List

from database.database import get_db
from database import models
from schemas import devices as schemas

router = APIRouter(prefix="/api/devices", tags=["devices"])

@router.post("/", response_model=schemas.Device)
def create_device(device: schemas.DeviceCreate, db: Session = Depends(get_db)):
    db_device = db.query(models.Device).filter(models.Device.device_id == device.device_id).first()
    if db_device:
        raise HTTPException(status_code=400, detail="Device ID already registered")
    
    new_device = models.Device(**device.model_dump())
    db.add(new_device)
    db.commit()
    db.refresh(new_device)
    return new_device

@router.get("/", response_model=List[schemas.Device])
def read_all_devices(skip: int = 0, limit: int = 100, db: Session = Depends(get_db)):
    devices = db.query(models.Device).filter(models.Device.deleted == False).offset(skip).limit(limit).all()
    return devices

@router.get("/{device_id}", response_model=schemas.Device)
def read_device(device_id: str, db: Session = Depends(get_db)):
    device = db.query(models.Device).filter(models.Device.device_id == device_id, models.Device.deleted == False).first()
    if device is None:
        raise HTTPException(status_code=404, detail="Device not found")
    return device

@router.put("/{device_id}", response_model=schemas.Device)
def update_device(device_id: str, device_update: schemas.DeviceUpdate, db: Session = Depends(get_db)):
    db_device = db.query(models.Device).filter(models.Device.device_id == device_id, models.Device.deleted == False).first()
    if db_device is None:
        raise HTTPException(status_code=404, detail="Device not found")
    
    update_data = device_update.model_dump(exclude_unset=True)
    for key, value in update_data.items():
        setattr(db_device, key, value)
        
    db.commit()
    db.refresh(db_device)
    return db_device

@router.delete("/{device_id}")
def delete_device(device_id: str, db: Session = Depends(get_db)):
    db_device = db.query(models.Device).filter(models.Device.device_id == device_id, models.Device.deleted == False).first()
    if db_device is None:
        raise HTTPException(status_code=404, detail="Device not found")
    
    db_device.deleted = True
    db.commit()
    return {"ok": True}
