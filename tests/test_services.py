import pytest
import sys
import os

PROJECT_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
sys.path.insert(0, os.path.join(PROJECT_ROOT, "backend", "app"))

from services import ValidationService

def test_validation_adds_default_cattle_id():
    data = {"spo2": 95, "sequence": 100}
    validated = ValidationService.validate(data)
    assert validated["cattle_id"] == "SC-001"

def test_validation_keeps_existing_cattle_id():
    data = {"device_id": "CATTLE-123", "spo2": 95, "sequence": 101}
    validated = ValidationService.validate(data)
    assert validated["cattle_id"] == "CATTLE-123"

def test_validation_sequence_check():
    data = {"device_id": "SEQ-001", "sequence": 50}
    ValidationService.validate(data) # Should pass
    
    with pytest.raises(ValueError):
        ValidationService.validate({"device_id": "SEQ-001", "sequence": 40}) # Stale sequence
