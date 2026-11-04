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
    // Backend broadcasts WebSocketEvent: { event_id, event_type, payload: { type:"sensor_update", cattle_id:"...", data:{...}, health:{...} } }
    private fun parseWebSocketMessage(jsonString: String) {
        try {
            val rawRoot = JSONObject(jsonString)
            val root = rawRoot.optJSONObject("payload") ?: rawRoot
            val type = root.optString("type").ifEmpty { rawRoot.optString("event_type") }

            when (type) {
                "status" -> {
                    val statusStr = root.optString("status")
                    _isConnected.value = (statusStr == "online")
                }
                "sensor_update" -> {
                    val data = root.getJSONObject("data")
                    val health = root.optJSONObject("health") ?: JSONObject()

                    fun healthStr(key: String): String =
                        health.optJSONObject(key)?.optString("status") ?: "unknown"

                    val fallDetected = root.optBoolean("fall_detected", false) ||
                            health.optBoolean("fall_detected", false) ||
                            healthStr("mems") == "abnormal"

                    fun getIntOrNull(key: String): Int? = if (data.isNull(key)) null else data.optInt(key)
                    fun getFloatOrNull(key: String): Float? = if (data.isNull(key)) null else data.optDouble(key).toFloat()
                    fun getBooleanOrTrue(key: String): Boolean = if (data.isNull(key)) true else data.optBoolean(key, true)

                    val stepsToday = when {
                        !data.isNull("step_count_today") -> data.optInt("step_count_today")
                        !data.isNull("steps_today") -> data.optInt("steps_today")
                        else -> null
                    }
                    val stepsLifetime = when {
                        !data.isNull("step_count_lifetime") -> data.optInt("step_count_lifetime")
                        !data.isNull("steps_lifetime") -> data.optInt("steps_lifetime")
                        else -> null
                    }
                    val accelX = when {
                        !data.isNull("acceleration_x") -> data.optDouble("acceleration_x").toFloat()
                        !data.isNull("mems_x") -> data.optDouble("mems_x").toFloat()
                        else -> null
                    }
                    val accelY = when {
                        !data.isNull("acceleration_y") -> data.optDouble("acceleration_y").toFloat()
                        !data.isNull("mems_y") -> data.optDouble("mems_y").toFloat()
                        else -> null
                    }
                    val accelZ = when {
                        !data.isNull("acceleration_z") -> data.optDouble("acceleration_z").toFloat()
                        !data.isNull("mems_z") -> data.optDouble("mems_z").toFloat()
                        else -> null
                    }

                    val parsedSpo2 = getIntOrNull("spo2") ?: getIntOrNull("spo2_percent")
                    val parsedHr = getIntOrNull("heart_rate") ?: getIntOrNull("bpm") ?: getIntOrNull("heart_rate_bpm")
                    val parsedTemp = getFloatOrNull("temperature") ?: getFloatOrNull("temperature_c")
                    val parsedPh = getFloatOrNull("ph") ?: getFloatOrNull("ph_value")
                    val parsedLdr = getIntOrNull("ldr") ?: getIntOrNull("ldr_value")
                    val parsedWeight = getFloatOrNull("body_weight_kg") ?: getFloatOrNull("weight_kg") ?: getFloatOrNull("weight")
                    val weightRaw = getIntOrNull("weight_raw")
                    val weightStable = data.optBoolean("weight_stable", false)
                    val weightCalibrated = data.optBoolean("weight_calibrated", false)
                    val weightValid = getBooleanOrTrue("weight_valid")
                    val weightStatus = data.optString("weight_sensor_status", "active")

                    val sensorModel = SensorData(
                        cattleId    = root.optString("cattle_id", rawRoot.optString("cattle_id", "CATTLE-001")),
                        timestamp   = root.optString("timestamp", rawRoot.optString("timestamp", "")),
                        spo2        = parsedSpo2,
                        heart_rate  = parsedHr,
                        temperature = parsedTemp,
                        humidity    = getFloatOrNull("humidity"),
                        memsX       = accelX,
                        memsY       = accelY,
                        memsZ       = accelZ,
                        ph          = parsedPh,
                        ldr         = parsedLdr,
                        fallDetected = fallDetected,
                        health = HealthStatus(
                            spo2        = healthStr("spo2"),
                            heart_rate  = healthStr("heart_rate"),
                            temperature = healthStr("temperature"),
                            mems        = healthStr("mems"),
                            ph          = healthStr("ph"),
                            ldr         = healthStr("ldr"),
                            overall     = health.optString("overall", "normal")
                        ),
                        stepCountToday     = stepsToday,
                        stepCountLifetime  = stepsLifetime,
                        accelerationX      = accelX,
                        accelerationY      = accelY,
                        accelerationZ      = accelZ,
                        accelerometerValid = getBooleanOrTrue("accelerometer_valid"),
                        heartRateValid     = getBooleanOrTrue("heart_rate_valid"),
                        spo2Valid          = getBooleanOrTrue("spo2_valid"),
                        temperatureValid   = getBooleanOrTrue("temperature_valid"),
                        phValid            = getBooleanOrTrue("ph_valid"),
                        ldrValid           = getBooleanOrTrue("ldr_valid"),
                        deviceConnected    = getBooleanOrTrue("device_connected"),
                        bodyWeightKg       = parsedWeight,
                        weightRaw          = weightRaw,
                        weightStable       = weightStable,
                        weightCalibrated   = weightCalibrated,
                        weightValid        = weightValid,
                        weightSensorStatus = weightStatus
                    )
                    _latestData.value = sensorModel
                    android.util.Log.i("SmartCattle", "[CattleViewModel WS RAW] $jsonString")
                    android.util.Log.i("SmartCattle", "[CattleViewModel WS PARSED] cid=${sensorModel.cattleId} hr=${sensorModel.heart_rate} spo2=${sensorModel.spo2} temp=${sensorModel.temperature}")
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
