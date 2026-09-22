package com.example.smartcattle.data.repository

import com.example.smartcattle.data.api.CattleApi
import com.example.smartcattle.data.api.toAlertRow
import com.example.smartcattle.data.api.toHistoryRow
import com.example.smartcattle.data.api.toSensorData
import com.example.smartcattle.data.model.AlertRow
import com.example.smartcattle.data.model.HistoryRow
import com.example.smartcattle.data.model.SensorData
import com.example.smartcattle.data.websocket.WebSocketManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.Response

class CattleRepository(private val api: CattleApi, private val wsManager: WebSocketManager) {

    private val _liveData = MutableStateFlow<String?>(null)
    val liveData: StateFlow<String?> = _liveData

    /**
     * Connect WebSocket for live sensor streaming.
     * @param serverIp  Your PC's LAN IP (e.g. "192.168.1.5"). Do NOT use 10.0.2.2 on a real phone.
     * @param cattleId  e.g. "CATTLE-001"
     */
    fun startListening(serverIp: String, cattleId: String = "CATTLE-001") {
        val wsUrl = "ws://$serverIp:8000/ws/cattle/$cattleId"
        val listener = object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                _liveData.value = text
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                // Reconnect on failure after a short delay (handled by callers via retry)
                _liveData.value = null
            }
        }
        wsManager.connect(wsUrl, listener)
    }

    // ── REST helpers ──

    suspend fun fetchLatest(cattleId: String): SensorData? = try {
        api.getLatest(cattleId).toSensorData()
    } catch (e: Exception) {
        null
    }

    suspend fun fetchHistory(cattleId: String, limit: Int = 30): List<HistoryRow> = try {
        api.getHistory(cattleId, limit).map { it.toHistoryRow() }
    } catch (e: Exception) {
        emptyList()
    }

    suspend fun fetchAlerts(): List<AlertRow> = try {
        api.getAlerts().map { it.toAlertRow() }
    } catch (e: Exception) {
        emptyList()
    }
}
