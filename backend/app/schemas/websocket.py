from pydantic import BaseModel, Field
from typing import Dict, Any, Literal, Optional
from datetime import datetime

EventType = Literal[
    "sensor_update",
    "health_update",
    "alert",
    "device_status",
    "fall_detected",
    "milk_update",
    "feed_update",
    "activity_update",
    "heartbeat",
    "pong"
]

class WebSocketEvent(BaseModel):
    event_id: str = Field(..., description="Unique identifier to prevent duplicate events")
    event_type: EventType
    cattle_id: Optional[str] = None
    device_id: Optional[str] = None
    timestamp: datetime
    payload: Dict[str, Any]

class ConnectionRequest(BaseModel):
    client_id: str
    reconnect: bool = False
