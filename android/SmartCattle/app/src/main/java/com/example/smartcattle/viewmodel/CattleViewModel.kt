package com.example.smartcattle.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.smartcattle.data.api.ActivityResponse
import com.example.smartcattle.data.api.DashboardSummaryResponse
import com.example.smartcattle.data.api.FeedConsumptionResponse
import com.example.smartcattle.data.api.MilkProductionResponse
import com.example.smartcattle.data.model.AlertRow
import com.example.smartcattle.data.model.HealthStatus
import com.example.smartcattle.data.model.HistoryRow
import com.example.smartcattle.data.model.SensorData
import com.example.smartcattle.data.repository.CattleRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

class CattleViewModel(private val repository: CattleRepository) : ViewModel() {

    private val _latestData = MutableStateFlow<SensorData?>(null)
    val latestData: StateFlow<SensorData?> = _latestData

    private val _history = MutableStateFlow<List<HistoryRow>>(emptyList())
    val history: StateFlow<List<HistoryRow>> = _history

    private val _alerts = MutableStateFlow<List<AlertRow>>(emptyList())
    val alerts: StateFlow<List<AlertRow>> = _alerts

    private val _dashboardSummary = MutableStateFlow<DashboardSummaryResponse?>(null)
    val dashboardSummary: StateFlow<DashboardSummaryResponse?> = _dashboardSummary

    private val _milkProduction = MutableStateFlow<List<MilkProductionResponse>>(emptyList())
    val milkProduction: StateFlow<List<MilkProductionResponse>> = _milkProduction

    private val _feedConsumption = MutableStateFlow<List<FeedConsumptionResponse>>(emptyList())
    val feedConsumption: StateFlow<List<FeedConsumptionResponse>> = _feedConsumption

    private val _activity = MutableStateFlow<List<ActivityResponse>>(emptyList())
    val activity: StateFlow<List<ActivityResponse>> = _activity

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected

    /**
     * Call this with your PC's LAN IP when the user enters it in the settings screen.
     * Example: configureServer("192.168.1.5", "CATTLE-001")
     */
    fun configureServer(serverIp: String, cattleId: String = "CATTLE-001") {
        // Start WebSocket for live updates
        repository.startListening(serverIp, cattleId)
        _isConnected.value = true

        // Collect live WebSocket messages
        viewModelScope.launch {
            repository.liveData.collect { jsonString ->
                jsonString?.let { parseWebSocketMessage(it) }
                if (jsonString == null) _isConnected.value = false
            }
        }

        // Do NOT pre-populate latestData or history via REST to avoid showing stale/fake dataset values.
        // Wait strictly for physical Arduino WebSocket packets to arrive.
        viewModelScope.launch {
            _alerts.value = repository.fetchAlerts()
            _dashboardSummary.value = repository.fetchDashboardSummary()
            _milkProduction.value = repository.fetchMilkProduction(cattleId)
            _feedConsumption.value = repository.fetchFeedConsumption(cattleId)
            _activity.value = repository.fetchActivity(cattleId)
        }
    }

    // ── WebSocket JSON parsing ──
    // Backend broadcasts: { "type":"sensor_update", "cattle_id":"...", "data":{...}, "health":{...} }
    private fun parseWebSocketMessage(jsonString: String) {
        try {
            val root = JSONObject(jsonString)
            val type = root.optString("type")

            when (type) {
                "status" -> {
                    val statusStr = root.optString("status")
                    _isConnected.value = (statusStr == "online")
                }
                "sensor_update" -> {
                    val data = root.getJSONObject("data")
                    val health = root.getJSONObject("health")

                    fun healthStr(key: String): String =
                        health.optJSONObject(key)?.optString("status") ?: "unknown"

                    val fallDetected = root.optBoolean("fall_detected", false) ||
                            healthStr("mems") == "abnormal"

                    fun getIntOrNull(key: String): Int? = if (data.isNull(key)) null else data.optInt(key)
                    fun getFloatOrNull(key: String): Float? = if (data.isNull(key)) null else data.optDouble(key).toFloat()

                    _latestData.value = SensorData(
                        cattleId    = root.optString("cattle_id", "CATTLE-001"),
                        timestamp   = root.optString("timestamp", ""),
                        spo2        = getIntOrNull("spo2"),
                        bpm         = getIntOrNull("bpm"),
                        temperature = getFloatOrNull("temperature"),
                        humidity    = getFloatOrNull("humidity"),
                        memsX       = getFloatOrNull("mems_x"),
                        memsY       = getFloatOrNull("mems_y"),
                        memsZ       = getFloatOrNull("mems_z"),
                        ph          = getFloatOrNull("ph"),
                        ldr         = getIntOrNull("ldr"),
                        fallDetected = fallDetected,
                        health = HealthStatus(
                            spo2        = healthStr("spo2"),
                            bpm         = healthStr("bpm"),
                            temperature = healthStr("temperature"),
                            mems        = healthStr("mems"),
                            ph          = healthStr("ph"),
                            ldr         = healthStr("ldr"),
                            overall     = healthStr("overall")
                        )
                    )
                }
                "alert" -> {
                    // Optionally refresh alerts list on server push
                    viewModelScope.launch {
                        _alerts.value = repository.fetchAlerts()
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    fun fetchDashboardSummary() {
        viewModelScope.launch {
            _dashboardSummary.value = repository.fetchDashboardSummary()
        }
    }

    fun fetchProductivityData(cattleId: String) {
        viewModelScope.launch {
            _milkProduction.value = repository.fetchMilkProduction(cattleId)
            _feedConsumption.value = repository.fetchFeedConsumption(cattleId)
            _activity.value = repository.fetchActivity(cattleId)
        }
    }
}
