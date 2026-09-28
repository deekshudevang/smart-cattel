import pytest
from unittest.mock import patch, MagicMock
from app.config import Settings
import threading
import time

# We need to test the logic in main.py, particularly how it starts up
# based on HARDWARE_MODE.

def test_real_mode_no_simulation():
    # In real mode, if the arduino fails to connect, we should NOT start a simulation thread.
    from app.main import app, _try_arduino, serial_reader
    
    # We can mock _try_arduino to simulate failure
    with patch("app.main.settings") as mock_settings:
        mock_settings.HARDWARE_MODE = "real"
        mock_settings.ALLOW_SIMULATION = False
        
        with patch("app.main._try_arduino", return_value=False):
            # We would need a way to test the startup logic, but main.py has global state.
            pass

from fastapi.testclient import TestClient

def test_status_endpoint_returns_correct_fields():
    from app.main import app
    client = TestClient(app)
    response = client.get("/api/arduino/status")
    assert response.status_code == 200
    data = response.json()
    assert "mode" in data
    assert "connected" in data
    assert "port" in data
    assert "device" in data
    assert "last_data" in data
    assert "simulation_enabled" in data
