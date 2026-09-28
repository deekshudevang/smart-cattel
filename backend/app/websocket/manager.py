import asyncio
import json
from datetime import datetime, timezone
from typing import Dict, Set
from fastapi import WebSocket, WebSocketDisconnect

class ConnectionManager:
    def __init__(self):
        self.active_connections: Set[WebSocket] = set()

    async def connect(self, websocket: WebSocket):
        await websocket.accept()
        self.active_connections.add(websocket)

    def disconnect(self, websocket: WebSocket):
        if websocket in self.active_connections:
            self.active_connections.remove(websocket)

    def format_event(self, event_type: str, cattle_id: str, device_id: str, payload: dict) -> dict:
        return {
            "event_type": event_type,
            "timestamp": datetime.now(timezone.utc).isoformat(),
            "cattle_id": cattle_id,
            "device_id": device_id,
            "payload": payload
        }

    async def broadcast(self, message: dict):
        # We ensure duplicate events (same exact event dict) are handled by the caller,
        # or we serialize to string and broadcast.
        message_str = json.dumps(message)
        dead_connections = set()
        for connection in self.active_connections:
            try:
                await connection.send_text(message_str)
            except Exception:
                dead_connections.add(connection)
        
        for dead in dead_connections:
            self.disconnect(dead)

    async def send_personal_message(self, message: str, websocket: WebSocket):
        try:
            await websocket.send_text(message)
        except Exception:
            self.disconnect(websocket)

manager = ConnectionManager()
