from sqlalchemy import Column, Integer, String, Float, DateTime, ForeignKey, Boolean
from sqlalchemy.orm import relationship
from datetime import datetime
from .database import Base

class Cattle(Base):
    __tablename__ = "cattle"
    id = Column(Integer, primary_key=True, index=True)
    cattle_id = Column(String, unique=True, index=True)
    name = Column(String, nullable=True)
    breed = Column(String, nullable=True)
    age = Column(String, nullable=True)
    gender = Column(String, nullable=True)
    dob = Column(String, nullable=True)
    notes = Column(String, nullable=True)
    status = Column(String, default="normal")
    deleted = Column(Boolean, default=False)

class SensorReading(Base):
    __tablename__ = "sensor_readings"
    id = Column(Integer, primary_key=True, index=True)
    cattle_id = Column(String, index=True)
    timestamp = Column(DateTime, default=datetime.utcnow, index=True)
    spo2 = Column(Integer)
    bpm = Column(Integer)
    temperature = Column(Float)
    humidity = Column(Float)
    mems_x = Column(Float)
    mems_y = Column(Float)
    mems_z = Column(Float)
    ph = Column(Float)
    ldr = Column(Integer)

class Prediction(Base):
    __tablename__ = "predictions"
    id = Column(Integer, primary_key=True, index=True)
    reading_id = Column(Integer, index=True)
    timestamp = Column(DateTime, default=datetime.utcnow)
    spo2_status = Column(String)
    bpm_status = Column(String)
    temperature_status = Column(String)
    mems_status = Column(String)
    ph_status = Column(String)
    ldr_status = Column(String)
    overall_status = Column(String)

class MilkProduction(Base):
    __tablename__ = "milk_production"
    id = Column(Integer, primary_key=True, index=True)
    cattle_id = Column(String, index=True)
    date = Column(DateTime, default=datetime.utcnow, index=True)
    quantity = Column(Float)
    unit = Column(String, default="litres")
    notes = Column(String, nullable=True)

class FeedConsumption(Base):
    __tablename__ = "feed_consumption"
    id = Column(Integer, primary_key=True, index=True)
    cattle_id = Column(String, index=True)
    date = Column(DateTime, default=datetime.utcnow, index=True)
    quantity = Column(Float)
    feed_type = Column(String, nullable=True)
    unit = Column(String, default="kg")
    notes = Column(String, nullable=True)

class Activity(Base):
    __tablename__ = "activity"
    id = Column(Integer, primary_key=True, index=True)
    cattle_id = Column(String, index=True)
    date = Column(DateTime, default=datetime.utcnow, index=True)
    steps = Column(Integer)
    source = Column(String, default="sensor")  # "sensor" or "manual"
    notes = Column(String, nullable=True)

class Alert(Base):
    __tablename__ = "alerts"
    id = Column(Integer, primary_key=True, index=True)
    cattle_id = Column(String, index=True)
    alert_type = Column(String)
    parameter = Column(String)
    actual_value = Column(String, nullable=True)
    severity = Column(String, default="Warning")
    timestamp = Column(DateTime, default=datetime.utcnow, index=True)
    status = Column(String, default="active")
