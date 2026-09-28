import pytest
from app.engine import HealthRiskEngine

def test_engine_normal():
    sensor_data = {"temperature": 39.0, "heart_rate": 60}
    ml_pred = {"overall": {"status": "normal", "confidence": 0.9}}
    cow_baseline = {
        "temperature": {"status": "ok", "percentage_deviation": 0, "current_value": 39.0, "baseline": 39.0},
        "bpm": {"status": "ok", "percentage_deviation": 0, "current_value": 60, "baseline": 60}
    }
    
    result = HealthRiskEngine.evaluate(sensor_data, ml_pred, cow_baseline=cow_baseline)
    assert result["health_score"] == 100
    assert result["risk_level"] == "NORMAL"
    assert result["confidence"] == 1.0
    assert len(result["factors"]) == 0

def test_engine_fall_event():
    sensor_data = {"temperature": 39.0, "heart_rate": 60, "fall_detected": True}
    ml_pred = {"overall": {"status": "normal", "confidence": 0.9}}
    
    result = HealthRiskEngine.evaluate(sensor_data, ml_pred)
    assert result["health_score"] == 70
    assert result["risk_level"] == "WATCH"
    assert len(result["factors"]) == 1
    factor = result["factors"][0]
    assert factor["factor"] == "fall_event"
    assert factor["observed"] is True
    assert factor["baseline"] is False

def test_engine_ml_anomaly():
    sensor_data = {"temperature": 39.0, "heart_rate": 60}
    ml_pred = {"overall": {"status": "abnormal", "confidence": 0.6}}
    
    result = HealthRiskEngine.evaluate(sensor_data, ml_pred)
    assert result["health_score"] == 80
    assert result["risk_level"] == "NORMAL"
    assert result["confidence"] == 0.6
    assert len(result["factors"]) == 1
    assert result["factors"][0]["factor"] == "ml_prediction"

def test_engine_device_status_and_quality():
    sensor_data = {"temperature": 39.0, "heart_rate": 60}
    ml_pred = {"overall": {"status": "normal", "confidence": 0.9}}
    device_status = {"battery_level": 5}
    sensor_validity = {"temp": True, "bpm": False, "spo2": False}
    signal_quality = {"temp": 40, "bpm": 40}
    
    result = HealthRiskEngine.evaluate(
        sensor_data, ml_pred, 
        device_status=device_status, 
        sensor_validity=sensor_validity, 
        signal_quality=signal_quality
    )
    
    assert result["health_score"] < 100
    assert result["confidence"] < 1.0
    assert len(result["factors"]) == 3
