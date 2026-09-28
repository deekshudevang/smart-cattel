from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy.orm import Session
from typing import List

from database.database import get_db
from database import models
from schemas import cattle as schemas

router = APIRouter(prefix="/api/cattle", tags=["cattle"])

@router.post("/", response_model=schemas.Cattle)
def create_cattle(cattle: schemas.CattleCreate, db: Session = Depends(get_db)):
    db_cattle = db.query(models.Cattle).filter(models.Cattle.cattle_id == cattle.cattle_id).first()
    if db_cattle:
        raise HTTPException(status_code=400, detail="Cattle ID already registered")
    
    new_cattle = models.Cattle(**cattle.model_dump())
    db.add(new_cattle)
    db.commit()
    db.refresh(new_cattle)
    return new_cattle

@router.get("/", response_model=List[schemas.Cattle])
def read_all_cattle(skip: int = 0, limit: int = 100, db: Session = Depends(get_db)):
    cattle_list = db.query(models.Cattle).filter(models.Cattle.deleted == False).offset(skip).limit(limit).all()
    return cattle_list

@router.get("/{cattle_id}", response_model=schemas.Cattle)
def read_cattle(cattle_id: str, db: Session = Depends(get_db)):
    cattle = db.query(models.Cattle).filter(models.Cattle.cattle_id == cattle_id, models.Cattle.deleted == False).first()
    if cattle is None:
        raise HTTPException(status_code=404, detail="Cattle not found")
    return cattle

@router.put("/{cattle_id}", response_model=schemas.Cattle)
def update_cattle(cattle_id: str, cattle_update: schemas.CattleUpdate, db: Session = Depends(get_db)):
    db_cattle = db.query(models.Cattle).filter(models.Cattle.cattle_id == cattle_id, models.Cattle.deleted == False).first()
    if db_cattle is None:
        raise HTTPException(status_code=404, detail="Cattle not found")
    
    update_data = cattle_update.model_dump(exclude_unset=True)
    for key, value in update_data.items():
        setattr(db_cattle, key, value)
        
    db.commit()
    db.refresh(db_cattle)
    return db_cattle

@router.delete("/{cattle_id}")
def delete_cattle(cattle_id: str, db: Session = Depends(get_db)):
    db_cattle = db.query(models.Cattle).filter(models.Cattle.cattle_id == cattle_id, models.Cattle.deleted == False).first()
    if db_cattle is None:
        raise HTTPException(status_code=404, detail="Cattle not found")
    
    db_cattle.deleted = True
    db.commit()
    return {"ok": True}
