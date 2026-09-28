package com.example.smartcattle.data.websocket

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener

class WebSocketManager {
    private val client = OkHttpClient()
    private var webSocket: WebSocket? = null
    private var currentUrl: String? = null
    private var currentListener: WebSocketListener? = null
    private var isConnected = false
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val reconnectRunnable = Runnable { reconnect() }

    fun connect(url: String, listener: WebSocketListener) {
        currentUrl = url
        currentListener = listener
        connectInternal()
    }

    private fun connectInternal() {
        if (currentUrl == null || currentListener == null) return
        val request = Request.Builder().url(currentUrl!!).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                isConnected = true
                currentListener?.onOpen(webSocket, response)
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                currentListener?.onMessage(webSocket, text)
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                currentListener?.onClosing(webSocket, code, reason)
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                isConnected = false
                currentListener?.onClosed(webSocket, code, reason)
                scheduleReconnect()
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: okhttp3.Response?) {
                isConnected = false
                currentListener?.onFailure(webSocket, t, response)
                scheduleReconnect()
            }
        })
    }

    private fun scheduleReconnect() {
        handler.removeCallbacks(reconnectRunnable)
        handler.postDelayed(reconnectRunnable, 5000)
    }

    private fun reconnect() {
        if (!isConnected) {
            webSocket?.cancel()
            connectInternal()
        }
    }

    fun disconnect() {
        handler.removeCallbacks(reconnectRunnable)
        webSocket?.close(1000, "App closed")
        isConnected = false
    }
}
