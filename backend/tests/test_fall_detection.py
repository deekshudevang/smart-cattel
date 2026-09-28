import pytest
import math
from app.fall_detection import FallDetector

def test_ordinary_movement_not_fall():
    detector = FallDetector()
    device_id = "TEST-01"
    
    # Simulate normal walking
    # z is ~1.0 (gravity), x and y vary slightly
    result = {}
    for i in range(40):
        result = detector.process(device_id, 0.2, 0.2, 1.0, i)
        
    assert result["fall_detected"] == False
    assert result["activity_state"] in ["low activity", "normal activity"]
    assert result["peak_acceleration"] > 0
    assert result["activity_score"] >= 0

def test_fall_sequence():
    detector = FallDetector()
    device_id = "TEST-02"
    
    # 1. Normal activity (10 readings)
    for i in range(10):
        detector.process(device_id, 0.1, 0.1, 1.0, i)
        
    # 2. Pre-impact movement (5 readings)
    for i in range(10, 15):
        detector.process(device_id, 0.5, 0.5, 0.8, i)
        
    # 3. Impact (sudden acceleration) (1 reading)
    # High magnitude
    detector.process(device_id, 3.0, 3.0, 1.0, 15)
    
    # 4. Post-impact inactivity and orientation change
    # e.g., fell on side, so z ~ 0, x ~ 1
    result = {}
    for i in range(16, 30):
        result = detector.process(device_id, 1.0, 0.1, 0.1, i)
        
    # Should detect a fall because we had a sequence:
    # impact > 2.5, post-impact inactivity, and orientation change (z from 1.0 to 0.1)
    assert result["fall_detected"] == True
    assert result["fall_confidence"] > 0.0
    assert result["peak_acceleration"] >= math.sqrt(3**2 + 3**2 + 1**2) if result["peak_acceleration"] else True

def test_high_activity_not_fall():
    detector = FallDetector()
    device_id = "TEST-03"
    
    # Simulate running but no impact and no orientation change
    result = {}
    for i in range(40):
        # High variance but no single huge impact > 2.5
        x = 0.5 if i % 2 == 0 else -0.5
        z = 1.2 if i % 2 == 0 else 0.8
        result = detector.process(device_id, x, 0.1, z, i)
        
    assert result["fall_detected"] == False
    assert result["activity_state"] in ["high activity", "normal activity"]
