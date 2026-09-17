import pytest
import asyncio
from backend.app.websocket.manager import ConnectionManager

class MockWebSocket:
    def __init__(self):
        self.sent_messages = []
        self.accepted = False

    async def accept(self):
        self.accepted = True

    async def send_text(self, data):
        self.sent_messages.append(data)

@pytest.mark.asyncio
async def test_websocket_connect_disconnect():
    manager = ConnectionManager()
    ws = MockWebSocket()
    
    await manager.connect(ws)
    assert ws.accepted
    assert ws in manager.active_connections
    
    manager.disconnect(ws)
    assert ws not in manager.active_connections

@pytest.mark.asyncio
async def test_websocket_broadcast():
    manager = ConnectionManager()
    ws1 = MockWebSocket()
    ws2 = MockWebSocket()
    
    await manager.connect(ws1)
    await manager.connect(ws2)
    
    data = '{"alert": "high temp"}'
    await manager.broadcast(data)
    
    assert len(ws1.sent_messages) == 1
    assert ws1.sent_messages[0] == data
    assert len(ws2.sent_messages) == 1
    assert ws2.sent_messages[0] == data
