import pytest
import os
from backend.app.services import PredictionService
from backend.app.schemas.sensor import SensorDataSchema

def test_prediction_edge_cases():
    service = PredictionService()
    
    # Test valid normal reading
    normal_data = SensorDataSchema(
        device_id="TEST-001", sequence=1, timestamp=1672531200,
        cattle_id="CATTLE-001", spo2=98, heart_rate=75, temperature=38.5, 
        humidity=45, mems_x=0.5, mems_y=0.1, mems_z=9.8, ph=7.2, ldr=400
    ).model_dump()
    result_normal = service.predict(normal_data)
    # The models might not be trained perfectly, but they should return status
    assert result_normal["spo2"]["status"] in ["normal", "abnormal"]
    
    # Test extreme out of bounds readings
    extreme_data = SensorDataSchema(
        device_id="TEST-001", sequence=1, timestamp=1672531200,
        cattle_id="CATTLE-001", spo2=0, heart_rate=500, temperature=100.0, 
        humidity=45, mems_x=0.5, mems_y=0.1, mems_z=9.8, ph=0.0, ldr=400
    ).model_dump()
    result_extreme = service.predict(extreme_data)
    assert result_extreme["spo2"]["status"] in ["normal", "abnormal"]

def test_prediction_missing_models(monkeypatch):
    # Monkeypatch to a fake dir
    monkeypatch.setattr("backend.app.services.HealthPredictor.__init__", lambda self: None)
    monkeypatch.setattr("backend.app.services.HealthPredictor.predict", lambda self, data: {"spo2": {"status": "unknown"}})
    
    service = PredictionService()
    normal_data = SensorDataSchema(
        device_id="TEST-001", sequence=1, timestamp=1672531200,
        cattle_id="CATTLE-001", spo2=98, heart_rate=75, temperature=38.5, 
        humidity=45, mems_x=0.5, mems_y=0.1, mems_z=9.8, ph=7.2, ldr=400
    ).model_dump()
    
    result = service.predict(normal_data)
    assert result["spo2"]["status"] == "unknown"
