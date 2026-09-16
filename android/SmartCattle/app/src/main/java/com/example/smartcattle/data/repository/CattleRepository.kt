package com.example.smartcattle.data.repository

import com.example.smartcattle.data.api.CattleApi
import com.example.smartcattle.data.websocket.WebSocketManager
import okhttp3.WebSocketListener
import okhttp3.Response
import okhttp3.WebSocket
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class CattleRepository(private val api: CattleApi, private val wsManager: WebSocketManager) {
    
    private val _liveData = MutableStateFlow<String?>(null)
    val liveData: StateFlow<String?> = _liveData

    fun startListening(cattleId: String) {
        val listener = object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                _liveData.value = text
            }
        }
        wsManager.connect("ws://10.0.2.2:8000/ws/cattle/$cattleId", listener)
    }
}
