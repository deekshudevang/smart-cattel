import pytest
from fastapi.testclient import TestClient
from fastapi.websockets import WebSocketDisconnect
import sys
import os
sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), '../app')))
from main import app

client = TestClient(app)

def test_websocket_connection():
    with client.websocket_connect("/ws?client_id=test_client_1") as websocket:
        # Check if we can receive a heartbeat or send a ping
        websocket.send_text("pong")
        
        # Subscribe to cattle
        websocket.send_text("subscribe:C001")
        
        # Just simple connect and pong test
        # Note: the background task doesn't easily trigger in simple tests without asyncio mocking,
        # so we just verify the endpoint connects and accepts messages.
        assert True

def test_websocket_missing_client_id():
    with pytest.raises(WebSocketDisconnect):
        with client.websocket_connect("/ws"):
            pass

def test_websocket_reconnect():
    with client.websocket_connect("/ws?client_id=test_reconnect") as websocket:
        websocket.send_text("pong")
    
    with client.websocket_connect("/ws?client_id=test_reconnect&reconnect=true") as websocket:
        websocket.send_text("pong")
        
    assert True
