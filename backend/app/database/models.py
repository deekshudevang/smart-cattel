from sqlalchemy import Column, Integer, String, Float, DateTime, ForeignKey, Boolean, Index
from sqlalchemy.orm import relationship
from datetime import datetime
from .database import Base

class BaseMixin:
    created_at = Column(DateTime, default=datetime.utcnow)
    updated_at = Column(DateTime, default=datetime.utcnow, onupdate=datetime.utcnow)
    deleted = Column(Boolean, default=False, index=True)

class User(Base, BaseMixin):
    __tablename__ = "users"
    id = Column(Integer, primary_key=True, index=True)
    username = Column(String, unique=True, index=True)
    email = Column(String, unique=True, index=True)
    hashed_password = Column(String)

class Device(Base, BaseMixin):
    __tablename__ = "devices"
    id = Column(Integer, primary_key=True, index=True)
    device_id = Column(String, unique=True, index=True)
    serial_number = Column(String, unique=True, nullable=True)
    firmware_version = Column(String, nullable=True)
    hardware_revision = Column(String, nullable=True)
    installation_date = Column(DateTime, nullable=True)
    battery_level = Column(Float, nullable=True)
    last_maintenance = Column(DateTime, nullable=True)
    notes = Column(String, nullable=True)
    cattle_id = Column(String, ForeignKey("cattle.cattle_id"), nullable=True)
    status = Column(String, default="active")
    
    cattle = relationship("Cattle", back_populates="devices")
    assignments = relationship("DeviceAssignment", back_populates="device")

class Cattle(Base, BaseMixin):
    __tablename__ = "cattle"
    id = Column(Integer, primary_key=True, index=True)
    cattle_id = Column(String, unique=True, index=True)
    name = Column(String, nullable=True)
    photo = Column(String, nullable=True)
    breed = Column(String, nullable=True)
    age = Column(String, nullable=True)
    gender = Column(String, nullable=True)
    dob = Column(String, nullable=True)
    weight = Column(Float, nullable=True)
    tag_id = Column(String, nullable=True, index=True)
    lactation_stage = Column(String, nullable=True)
    pregnancy_status = Column(String, nullable=True)
    notes = Column(String, nullable=True)
    medical_history = Column(String, nullable=True)
    status = Column(String, default="normal")

    devices = relationship("Device", back_populates="cattle")
    sensor_readings = relationship("SensorReading", back_populates="cattle")
    milk_production = relationship("MilkProduction", back_populates="cattle")
    feed_consumption = relationship("FeedConsumption", back_populates="cattle")
    activity = relationship("Activity", back_populates="cattle")
    alerts = relationship("Alert", back_populates="cattle")
    medical_records = relationship("MedicalRecord", back_populates="cattle")
    vaccinations = relationship("Vaccination", back_populates="cattle")
    health_assessments = relationship("HealthAssessment", back_populates="cattle")
    device_assignments = relationship("DeviceAssignment", back_populates="cattle")

class SensorReading(Base, BaseMixin):
    __tablename__ = "sensor_readings"
    id = Column(Integer, primary_key=True, index=True)
    cattle_id = Column(String, ForeignKey("cattle.cattle_id"), index=True)
    device_id = Column(String, ForeignKey("devices.device_id"), index=True, nullable=True)
    timestamp = Column(DateTime, default=datetime.utcnow, index=True)
    
    spo2 = Column(Integer)
    heart_rate = Column(Integer)
    temperature = Column(Float)
    humidity = Column(Float)
    
    mems_x = Column(Float)
    mems_y = Column(Float)
    mems_z = Column(Float)
    acceleration_magnitude = Column(Float, nullable=True)
    activity_state = Column(String, nullable=True)
    activity_score = Column(Float, nullable=True)
    fall_detected = Column(Boolean, default=False)
    fall_confidence = Column(Float, nullable=True)
    peak_acceleration = Column(Float, nullable=True)
    
    ph = Column(Float)
    ldr = Column(Integer)
    
    # GPS and GSM
    gps_valid = Column(Boolean, default=False)
    gps_lat = Column(Float, nullable=True)
    gps_lon = Column(Float, nullable=True)
    gps_satellites = Column(Integer, nullable=True)
    gsm_status = Column(String, nullable=True)
    
    cattle = relationship("Cattle", back_populates="sensor_readings")
    prediction = relationship("Prediction", back_populates="reading", uselist=False)

    __table_args__ = (
        Index('idx_cattle_timestamp', 'cattle_id', 'timestamp'),
    )

class Prediction(Base, BaseMixin):
    __tablename__ = "predictions"
    id = Column(Integer, primary_key=True, index=True)
    reading_id = Column(Integer, ForeignKey("sensor_readings.id"), index=True)
    timestamp = Column(DateTime, default=datetime.utcnow)
    
    spo2_status = Column(String)
    heart_rate_status = Column(String)
    temperature_status = Column(String)
    mems_status = Column(String)
    ph_status = Column(String)
    ldr_status = Column(String)
    overall_status = Column(String)
    
    reading = relationship("SensorReading", back_populates="prediction")

class HealthAssessment(Base, BaseMixin):
    __tablename__ = "health_assessments"
    id = Column(Integer, primary_key=True, index=True)
    cattle_id = Column(String, ForeignKey("cattle.cattle_id"), index=True)
    timestamp = Column(DateTime, default=datetime.utcnow, index=True)
    health_score = Column(Integer)
    risk_level = Column(String)
    confidence = Column(Float)
    factors = Column(String) # JSON array of contributing factors
    
    cattle = relationship("Cattle", back_populates="health_assessments")

class Alert(Base, BaseMixin):
    __tablename__ = "alerts"
    id = Column(Integer, primary_key=True, index=True)
    cattle_id = Column(String, ForeignKey("cattle.cattle_id"), index=True)
    device_id = Column(String, nullable=True, index=True)
    type = Column(String, index=True)
    severity = Column(String, default="INFO", index=True)
    message = Column(String)
    value = Column(Float, nullable=True)
    threshold = Column(Float, nullable=True)
    confidence = Column(Float, nullable=True)
    acknowledged_at = Column(DateTime, nullable=True)
    resolved_at = Column(DateTime, nullable=True)
    status = Column(String, default="active", index=True)
    
    cattle = relationship("Cattle", back_populates="alerts")

    __table_args__ = (
        Index('idx_alert_status_severity', 'status', 'severity'),
    )

class MilkProduction(Base, BaseMixin):
    __tablename__ = "milk_production"
    id = Column(Integer, primary_key=True, index=True)
    cattle_id = Column(String, ForeignKey("cattle.cattle_id"), index=True)
    date = Column(DateTime, default=datetime.utcnow, index=True)
    quantity = Column(Float)
    unit = Column(String, default="litres")
    notes = Column(String, nullable=True)
    
    cattle = relationship("Cattle", back_populates="milk_production")

class FeedConsumption(Base, BaseMixin):
    __tablename__ = "feed_consumption"
    id = Column(Integer, primary_key=True, index=True)
    cattle_id = Column(String, ForeignKey("cattle.cattle_id"), index=True)
    date = Column(DateTime, default=datetime.utcnow, index=True)
    quantity = Column(Float)
    feed_type = Column(String, nullable=True)
    unit = Column(String, default="kg")
    notes = Column(String, nullable=True)
    
    cattle = relationship("Cattle", back_populates="feed_consumption")

class Activity(Base, BaseMixin):
    __tablename__ = "activity"
    id = Column(Integer, primary_key=True, index=True)
    cattle_id = Column(String, ForeignKey("cattle.cattle_id"), index=True)
    date = Column(DateTime, default=datetime.utcnow, index=True)
    steps = Column(Integer)
    source = Column(String, default="sensor")
    notes = Column(String, nullable=True)
    
    cattle = relationship("Cattle", back_populates="activity")

class MedicalRecord(Base, BaseMixin):
    __tablename__ = "medical_records"
    id = Column(Integer, primary_key=True, index=True)
    cattle_id = Column(String, ForeignKey("cattle.cattle_id"), index=True)
    date = Column(DateTime, default=datetime.utcnow, index=True)
    diagnosis = Column(String)
    treatment = Column(String)
    veterinarian = Column(String, nullable=True)
    notes = Column(String, nullable=True)
    
    cattle = relationship("Cattle", back_populates="medical_records")

class Vaccination(Base, BaseMixin):
    __tablename__ = "vaccinations"
    id = Column(Integer, primary_key=True, index=True)
    cattle_id = Column(String, ForeignKey("cattle.cattle_id"), index=True)
    date = Column(DateTime, default=datetime.utcnow, index=True)
    vaccine_name = Column(String)
    next_due_date = Column(DateTime, nullable=True)
    administered_by = Column(String, nullable=True)
    
    cattle = relationship("Cattle", back_populates="vaccinations")

class SensorCalibration(Base, BaseMixin):
    __tablename__ = "sensor_calibrations"
    id = Column(Integer, primary_key=True, index=True)
    device_id = Column(String, ForeignKey("devices.device_id"), index=True)
    sensor_type = Column(String)
    calibration_date = Column(DateTime, default=datetime.utcnow)
    buffer_1 = Column(Float, nullable=True)
    buffer_1_voltage = Column(Float, nullable=True)
    buffer_2 = Column(Float, nullable=True)
    buffer_2_voltage = Column(Float, nullable=True)
    slope = Column(Float, nullable=True)
    intercept = Column(Float, nullable=True)
    calibration_data = Column(String, nullable=True) # Retained for backward compatibility or other sensors
    performed_by = Column(String, nullable=True)

class DeviceEvent(Base, BaseMixin):
    __tablename__ = "device_events"
    id = Column(Integer, primary_key=True, index=True)
    device_id = Column(String, ForeignKey("devices.device_id"), index=True)
    event_type = Column(String, index=True)
    event_data = Column(String)
    timestamp = Column(DateTime, default=datetime.utcnow, index=True)

class FallEvent(Base, BaseMixin):
    __tablename__ = "fall_events"
    id = Column(Integer, primary_key=True, index=True)
    cattle_id = Column(String, ForeignKey("cattle.cattle_id"), index=True)
    timestamp = Column(DateTime, default=datetime.utcnow, index=True)
    peak_acceleration = Column(Float)
    activity_score = Column(Float)
    fall_confidence = Column(Float)
    
    cattle = relationship("Cattle")

class DeviceAssignment(Base, BaseMixin):
    __tablename__ = "device_assignments"
    id = Column(Integer, primary_key=True, index=True)
    device_id = Column(String, ForeignKey("devices.device_id"), index=True)
    cattle_id = Column(String, ForeignKey("cattle.cattle_id"), index=True)
    assigned_at = Column(DateTime, default=datetime.utcnow, index=True)
    removed_at = Column(DateTime, nullable=True, index=True)
    status = Column(String, default="active", index=True)
    
    device = relationship("Device", back_populates="assignments")
    cattle = relationship("Cattle", back_populates="device_assignments")


class SmsLog(Base, BaseMixin):
    __tablename__ = "sms_logs"
    id = Column(Integer, primary_key=True, index=True)
    cattle_id = Column(String, ForeignKey("cattle.cattle_id"), index=True)
    alert_type = Column(String, index=True)
    severity = Column(String)
    timestamp = Column(DateTime, default=datetime.utcnow, index=True)
    location = Column(String, nullable=True)
    status = Column(String, index=True) # attempted, sent, failed
    reason = Column(String, nullable=True)
    
    cattle = relationship("Cattle")

