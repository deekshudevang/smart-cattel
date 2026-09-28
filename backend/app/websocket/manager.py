import asyncio
import json
import uuid
import logging
from datetime import datetime, timezone
from typing import Dict, Set, Optional, Any
from fastapi import WebSocket, WebSocketDisconnect
from pydantic import ValidationError
from schemas.websocket import WebSocketEvent, EventType

logger = logging.getLogger(__name__)

class Connection:
    def __init__(self, websocket: WebSocket, client_id: str):
        self.websocket = websocket
        self.client_id = client_id
        self.last_seen = datetime.now(timezone.utc)
        self.connected_at = self.last_seen
        self.cattle_ids: Set[str] = set()

    def update_last_seen(self):
        self.last_seen = datetime.now(timezone.utc)

    @property
    def is_offline(self) -> bool:
        # Offline if no heartbeat for 60 seconds
        return (datetime.now(timezone.utc) - self.last_seen).total_seconds() > 60

class ConnectionManager:
    def __init__(self):
        # Maps client_id -> Connection
        self.active_connections: Dict[str, Connection] = {}
        # Keeps track of sent event IDs to avoid broadcasting duplicates if triggered multiple times
        self.sent_events: Set[str] = set()
        self.max_history = 1000

    async def connect(self, websocket: WebSocket, client_id: str, reconnect: bool = False):
        await websocket.accept()
        if client_id in self.active_connections:
            # Reconnect: close old connection
            old_conn = self.active_connections[client_id]
            try:
                await old_conn.websocket.close()
            except:
                pass
            
        conn = Connection(websocket, client_id)
        self.active_connections[client_id] = conn
        logger.info(f"Client {client_id} connected (reconnect={reconnect})")

    def disconnect(self, client_id: str):
        if client_id in self.active_connections:
            del self.active_connections[client_id]
            logger.info(f"Client {client_id} disconnected")

    def subscribe(self, client_id: str, cattle_id: str):
        if client_id in self.active_connections:
            self.active_connections[client_id].cattle_ids.add(cattle_id)

    async def ping_clients(self):
        while True:
            await asyncio.sleep(30)
            dead_clients = []
            for client_id, conn in self.active_connections.items():
                if conn.is_offline:
                    dead_clients.append(client_id)
                else:
                    try:
                        ping_event = WebSocketEvent(
                            event_id=str(uuid.uuid4()),
                            event_type="heartbeat",
                            timestamp=datetime.now(timezone.utc),
                            payload={"message": "ping"}
                        )
                        await conn.websocket.send_text(ping_event.model_dump_json())
                    except:
                        dead_clients.append(client_id)
            
            for cid in dead_clients:
                self.disconnect(cid)

    def format_event(self, event_type: EventType, payload: dict, cattle_id: Optional[str] = None, device_id: Optional[str] = None) -> WebSocketEvent:
        return WebSocketEvent(
            event_id=str(uuid.uuid4()),
            event_type=event_type,
            cattle_id=cattle_id,
            device_id=device_id,
            timestamp=datetime.now(timezone.utc),
            payload=payload
        )

    async def broadcast(self, event: WebSocketEvent):
        # Prevent duplicate events
        if event.event_id in self.sent_events:
            return
        
        self.sent_events.add(event.event_id)
        if len(self.sent_events) > self.max_history:
            # Very simple cleanup: just clear it. In prod, use OrderedDict/deque
            self.sent_events.clear()

        message_str = event.model_dump_json()
        dead_clients = []
        
        for client_id, conn in self.active_connections.items():
            # If event is specific to a cattle_id, only send if client is subscribed or subscribed to all (no cattle_ids)
            if event.cattle_id and conn.cattle_ids and event.cattle_id not in conn.cattle_ids:
                continue
                
            try:
                await conn.websocket.send_text(message_str)
            except Exception:
                dead_clients.append(client_id)
        
        for cid in dead_clients:
            self.disconnect(cid)

manager = ConnectionManager()


