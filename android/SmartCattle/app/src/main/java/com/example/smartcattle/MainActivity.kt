package com.example.smartcattle

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.smartcattle.ui.components.*
import com.example.smartcattle.ui.theme.*
import com.example.smartcattle.data.local.AppDatabase
import com.example.smartcattle.data.local.CowEntity
import com.example.smartcattle.data.local.toCowProfile
import com.example.smartcattle.data.local.toEntity
import com.example.smartcattle.data.model.formatAlertDateTime
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.*
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { SmartCattleApp() }
    }
}

// ── Fallback Color Tokens (Mapped to theme for backward compatibility) ──
val BgDark = Color(0xFF0F172A)
val BgCard = Color(0xFF1E293B)
val BgCardHover = Color(0xFF334155)
val BgElevated = Color(0xFF1E293B)
val Green = Color(0xFF2E7D32)
val Red = Color(0xFFC62828)
val Orange = Color(0xFFEF6C00)
val Blue = Color(0xFF1565C0)
val Cyan = Color(0xFF00ACC1)
val White = Color(0xFFF8FAFC)
val Grey = Color(0xFF94A3B8)
val Muted = Color(0xFF64748B)
val Border = Color(0xFF334155)

// ── Domain Models ──
data class Sensor(
    val icon: String,
    val label: String,
    val value: String,
    val unit: String,
    val status: String,
    val color: Color,
    val isValid: Boolean = true,
    val reason: String? = null,
    val lastUpdate: String? = null
)
data class Cattle(
    val id: String,
    val name: String,
    val breed: String,
    val age: String,
    val overall: String,
    val sensors: List<Sensor>,
    val dataSource: String = "Live from sensor",
    val isSimulated: Boolean = false,
    val isStale: Boolean = false,
    val lastPacketTime: Long = System.currentTimeMillis(),
    val lastUpdateFormatted: String? = null,
    val memsX: Float? = null,
    val memsY: Float? = null,
    val memsZ: Float? = null,
    val fallDetected: Boolean = false,
    val weightStable: Boolean = false,
    val weightCalibrated: Boolean = false,
    val stepsToday: Int? = null,
    val stepsLifetime: Int? = null
)
data class Alert(val cattle: String, val msg: String, val time: String, val tag: String, val color: Color)

// Full cow profile (from /api/cows or /api/cows/{id})
data class CowProfile(
    val cattleId: String,
    val name: String,
    val breed: String? = null,
    val age: String? = null,
    val gender: String? = null,
    val dob: String? = null,
    val notes: String? = null,
    val status: String? = null,
    // latest sensor summary
    val latestSpo2: Int? = null,
    val latestHeartRate: Int? = null,
    val latestTemp: Float? = null,
    val latestPh: Float? = null,
    val latestLdr: Int? = null,
    val latestHumidity: Float? = null,
    val latestStepsToday: Int? = null,
    val latestStepsLifetime: Int? = null,
    val latestAccelX: Float? = null,
    val latestAccelY: Float? = null,
    val latestAccelZ: Float? = null,
    val latestWeight: Float? = null,
    val latestWeightStable: Boolean = false,
    val overallStatus: String? = null,
    val latestTimestamp: String? = null,
    // latest milk/feed/activity
    val latestMilkQty: Float? = null,
    val latestMilkUnit: String? = null,
    val latestFeedQty: Float? = null,
    val latestActivitySteps: Int? = null,
    val activeAlertsCount: Int = 0,
    // local photo path (not from API)
    val photoPath: String? = null
)

// ── API Retrofit Models ──
data class ApiCattle(val cattle_id: String, val name: String, val status: String)
data class ApiHealth(val spo2: String, val heart_rate: String, val temperature: String, val mems: String, val ph: String, val ldr: String, val overall: String)
data class ApiLatest(
    val cattle_id: String, val timestamp: String,
    val spo2: Int?, val heart_rate: Int?, val temperature: Float?,
    val humidity: Float?, val mems_x: Float?, val mems_y: Float?, val mems_z: Float?,
    val ph: Float?, val ldr: Int?,
    val fall_detected: Boolean, val health: ApiHealth,
    val step_count_today: Int? = null,
    val step_count_lifetime: Int? = null,
    val acceleration_x: Float? = null,
    val acceleration_y: Float? = null,
    val acceleration_z: Float? = null,
    val accelerometer_valid: Boolean? = null,
    val heart_rate_valid: Boolean? = null,
    val spo2_valid: Boolean? = null,
    val temperature_valid: Boolean? = null,
    val ph_valid: Boolean? = null,
    val ldr_valid: Boolean? = null,
    val device_connected: Boolean? = null,
    val source: String? = null,
    val is_simulated: Boolean? = null,
    val raw_packet: String? = null,
    val checksum_valid: Boolean? = null,
    val body_weight_kg: Float? = null,
    val weight_raw: Int? = null,
    val weight_stable: Boolean? = null,
    val weight_calibrated: Boolean? = null,
    val weight_valid: Boolean? = null,
    val weight_sensor_status: String? = null
)
data class ApiHistoryRow(
    val timestamp: String,
    val spo2: Int?, val heart_rate: Int?, val temperature: Float?,
    val humidity: Float?, val mems_x: Float?, val mems_y: Float?, val mems_z: Float?,
    val ph: Float?, val ldr: Int?,
    val fall_detected: Boolean, val health: ApiHealth,
    val step_count_today: Int? = null,
    val step_count_lifetime: Int? = null,
    val acceleration_x: Float? = null,
    val acceleration_y: Float? = null,
    val acceleration_z: Float? = null,
    val accelerometer_valid: Boolean? = null,
    val heart_rate_valid: Boolean? = null,
    val spo2_valid: Boolean? = null,
    val temperature_valid: Boolean? = null,
    val ph_valid: Boolean? = null,
    val ldr_valid: Boolean? = null,
    val device_connected: Boolean? = null,
    val source: String? = null,
    val is_simulated: Boolean? = null,
    val body_weight_kg: Float? = null,
    val weight_stable: Boolean? = null
)
data class ApiAlert(val cattle_id: String, val cattle_name: String, val timestamp: String, val fall_detected: Boolean, val details: List<String>)

// CRUD request bodies
data class CowCreateBody(
    val cattle_id: String,
    val name: String,
    val breed: String?,
    val age: String?,
    val gender: String?,
    val dob: String?,
    val notes: String?
)
data class CowUpdateBody(
    val name: String?,
    val breed: String?,
    val age: String?,
    val gender: String?,
    val dob: String?,
    val notes: String?
)

// API response for cow list/detail
data class ApiCowProfile(
    val cattle_id: String,
    val name: String,
    val breed: String?,
    val age: String?,
    val gender: String?,
    val dob: String?,
    val weight: Float? = null,
    val notes: String?,
    val status: String?,
    val latest_health: ApiLatestHealth?,
    val latest_milk: ApiLatestMilk?,
    val latest_feed: ApiLatestFeed?,
    val latest_activity: ApiLatestActivity?,
    val active_alerts_count: Int?
)
data class ApiLatestHealth(
    val timestamp: String?,
    val spo2: Int?,
    val heart_rate: Int?,
    val temperature: Float?,
    val overall_status: String?,
    val humidity: Float? = null,
    val ph: Float? = null,
    val ldr: Int? = null,
    val step_count_today: Int? = null,
    val step_count_lifetime: Int? = null,
    val acceleration_x: Float? = null,
    val acceleration_y: Float? = null,
    val acceleration_z: Float? = null,
    val accelerometer_valid: Boolean? = null,
    val heart_rate_valid: Boolean? = null,
    val spo2_valid: Boolean? = null,
    val temperature_valid: Boolean? = null,
    val ph_valid: Boolean? = null,
    val ldr_valid: Boolean? = null,
    val source: String? = null,
    val is_simulated: Boolean? = null,
    val body_weight_kg: Float? = null,
    val weight_stable: Boolean? = null,
    val weight_calibrated: Boolean? = null,
    val weight_valid: Boolean? = null,
    val weight_sensor_status: String? = null
)
data class ApiLatestMilk(val quantity: Float?, val unit: String?, val date: String?)
data class ApiLatestFeed(val quantity: Float?, val unit: String?, val date: String?)
data class ApiLatestActivity(val steps: Int?, val date: String?)

data class ApiArduinoStatus(
    val mode: String? = null,
    val connected: Boolean = false,
    val port: String? = null,
    val serial_busy: Boolean = false,
    val status_message: String? = null,
    val error: String? = null
)

interface ApiService {
    @GET("api/cattle")
    suspend fun getCattle(): List<ApiCattle>
    @GET("api/cattle/{id}/latest")
    suspend fun getLatestReading(@Path("id") id: String): ApiLatest
    @GET("api/cattle/{id}/history")
    suspend fun getHistory(@Path("id") id: String): List<ApiHistoryRow>
    @GET("api/alerts")
    suspend fun getAlerts(): List<ApiAlert>
    @GET("api/cows")
    suspend fun getCows(): List<ApiCowProfile>
    @GET("api/cows/{id}")
    suspend fun getCowById(@Path("id") id: String): ApiCowProfile
    @POST("api/cows")
    suspend fun createCow(@Body body: CowCreateBody): ApiCowProfile
    @PUT("api/cows/{id}")
    suspend fun updateCow(@Path("id") id: String, @Body body: CowUpdateBody): ApiCowProfile
    @DELETE("api/cows/{id}")
    suspend fun deleteCow(@Path("id") id: String): retrofit2.Response<Unit>
    @GET("api/arduino/status")
    suspend fun getArduinoStatus(): ApiArduinoStatus
    // Weight endpoints
    @GET("api/v1/cattle/{id}/weight/latest")
    suspend fun getLatestWeight(@Path("id") id: String): com.example.smartcattle.data.model.WeightReading
    @GET("api/v1/cattle/{id}/weight/history")
    suspend fun getWeightHistory(@Path("id") id: String, @Query("limit") limit: Int = 50): List<com.example.smartcattle.data.model.WeightReading>
    @GET("api/v1/cattle/{id}/weight/statistics")
    suspend fun getWeightStatistics(@Path("id") id: String): com.example.smartcattle.data.model.WeightStatistics
    @POST("api/v1/sensors/weight")
    suspend fun recordWeight(@Body req: com.example.smartcattle.data.model.WeightRecordRequest): com.example.smartcattle.data.model.WeightReading
    @POST("api/v1/sensors/weight/tare")
    suspend fun tareScale(@Body req: com.example.smartcattle.data.model.TareRequest = com.example.smartcattle.data.model.TareRequest()): retrofit2.Response<Unit>
    @POST("api/v1/sensors/weight/calibrate")
    suspend fun calibrateScale(@Body req: com.example.smartcattle.data.model.CalibrateRequest): retrofit2.Response<Unit>
}

// ── ViewModel ──
class CattleViewModel : ViewModel() {
    private val _cattle = MutableStateFlow<List<Cattle>>(emptyList())
    val cattle: StateFlow<List<Cattle>> = _cattle.asStateFlow()

    private val _alerts = MutableStateFlow<List<Alert>>(emptyList())
    val alerts: StateFlow<List<Alert>> = _alerts.asStateFlow()

    private val _history = MutableStateFlow<List<List<String>>>(emptyList())
    val history: StateFlow<List<List<String>>> = _history.asStateFlow()

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _lastUpdateTimestamp = MutableStateFlow<String?>(null)
    val lastUpdateTimestamp: StateFlow<String?> = _lastUpdateTimestamp.asStateFlow()

    // Cows CRUD state
    private val _cowProfiles = MutableStateFlow<List<CowProfile>>(emptyList())
    val cowProfiles: StateFlow<List<CowProfile>> = _cowProfiles.asStateFlow()

    private val _cowError = MutableStateFlow<String?>(null)
    val cowError: StateFlow<String?> = _cowError.asStateFlow()

    private val _serialBusy = MutableStateFlow(false)
    val serialBusy: StateFlow<Boolean> = _serialBusy.asStateFlow()

    private val _serialStatusMessage = MutableStateFlow<String?>("Disconnected")
    val serialStatusMessage: StateFlow<String?> = _serialStatusMessage.asStateFlow()

    // Weight & Scale states
    private val _liveWeight = MutableStateFlow<Float?>(null)
    val liveWeight: StateFlow<Float?> = _liveWeight.asStateFlow()

    private val _liveWeightStable = MutableStateFlow(false)
    val liveWeightStable: StateFlow<Boolean> = _liveWeightStable.asStateFlow()

    private val _liveWeightCalibrated = MutableStateFlow(false)
    val liveWeightCalibrated: StateFlow<Boolean> = _liveWeightCalibrated.asStateFlow()

    private val _liveWeightStatus = MutableStateFlow("disconnected")
    val liveWeightStatus: StateFlow<String> = _liveWeightStatus.asStateFlow()

    private val _weightStats = MutableStateFlow<com.example.smartcattle.data.model.WeightStatistics?>(null)
    val weightStats: StateFlow<com.example.smartcattle.data.model.WeightStatistics?> = _weightStats.asStateFlow()

    private val _weightHistory = MutableStateFlow<List<com.example.smartcattle.data.model.WeightReading>>(emptyList())
    val weightHistory: StateFlow<List<com.example.smartcattle.data.model.WeightReading>> = _weightHistory.asStateFlow()

    private val _isWeightActionLoading = MutableStateFlow(false)
    val isWeightActionLoading: StateFlow<Boolean> = _isWeightActionLoading.asStateFlow()


    var currentIp: String = BuildConfig.DEV_LAN_IP
        private set
    var currentPort: String = "8000"
        private set
    private val deletedCattleIds = mutableSetOf<String>()
    private var cowDao: com.example.smartcattle.data.local.CowDao? = null

    // Per-cow photo paths stored in memory (keyed by cattle_id)
    private val _photoPaths = mutableMapOf<String, String>()

    private var api: ApiService? = null
    private var webSocket: WebSocket? = null
    private var reconnectAttempt = 0
    private var reconnectJob: Job? = null
    private val client = OkHttpClient.Builder()
        .pingInterval(15, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    init {
        viewModelScope.launch {
            while (true) {
                delay(2000)
                try {
                    val status = api?.getArduinoStatus()
                    if (status != null) {
                        _serialBusy.value = status.serial_busy
                        _serialStatusMessage.value = status.status_message
                    }
                } catch (_: Exception) {}

                val now = System.currentTimeMillis()
                val currentList = _cattle.value
                var changed = false
                val updated = currentList.map { c ->
                    if (!c.isStale && (now - c.lastPacketTime > 10_000)) {
                        changed = true
                        c.copy(
                            isStale = true,
                            dataSource = if (_serialBusy.value) "Serial port busy" else "Disconnected",
                            sensors = c.sensors.map { s ->
                                s.copy(
                                    isValid = false,
                                    reason = if (_serialBusy.value) "Serial port busy" else "Disconnected (>10s timeout)"
                                )
                            }
                        )
                    } else {
                        c
                    }
                }
                if (changed) {
                    _cattle.value = updated
                }
            }
        }
    }

    fun initLocalCache(context: Context) {
        val db = AppDatabase.getInstance(context)
        cowDao = db.cowDao()
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val cached = cowDao?.getActiveCowsList() ?: emptyList()
                if (cached.isNotEmpty() && _cowProfiles.value.isEmpty()) {
                    val profiles = cached.map { it.toCowProfile() }
                    _cowProfiles.value = profiles
                    val activeIds = profiles.map { it.cattleId }.toSet()
                    _cattle.value = profiles.map { p ->
                        Cattle(
                            id = p.cattleId,
                            name = p.name,
                            breed = p.breed ?: "Not Available",
                            age = p.age ?: "Not Available",
                            overall = p.overallStatus ?: "Healthy",
                            sensors = listOf(
                                Sensor("hr", "Heart Rate", p.latestHeartRate?.let { "$it" } ?: "Not Available", "bpm", "Normal", Green, isValid = p.latestHeartRate != null, reason = if (p.latestHeartRate == null) "No contact" else null),
                                Sensor("spo2", "SpO2", p.latestSpo2?.let { "$it" } ?: "Not Available", "%", "Normal", Green, isValid = p.latestSpo2 != null, reason = if (p.latestSpo2 == null) "Sensor not connected" else null),
                                Sensor("temp", "Body Temp", p.latestTemp?.let { String.format(Locale.US, "%.1f", it) } ?: "Not Available", "°C", "Normal", Green, isValid = p.latestTemp != null, reason = if (p.latestTemp == null) "Sensor not connected" else null),
                                Sensor("ph", "pH Level", p.latestPh?.let { String.format(Locale.US, "%.1f", it) } ?: "Not Available", "", "Normal", Green, isValid = p.latestPh != null, reason = if (p.latestPh == null) "Probe not in solution" else null),
                                Sensor("ldr", "Light", p.latestLdr?.let { "$it" } ?: "Not Available", "lux", "Normal", Green, isValid = p.latestLdr != null, reason = if (p.latestLdr == null) "Sensor not connected" else null),
                                Sensor("weight", "Weight", p.latestWeight?.let { String.format(Locale.US, "%.1f", it) } ?: "Not Available", "kg", "Cached", Green, isValid = p.latestWeight != null, reason = if (p.latestWeight == null) "Sensor not connected" else null),
                                Sensor("steps", "Steps", p.latestStepsToday?.let { "$it" } ?: "Not Available", "steps", if (p.latestStepsToday != null) "Active" else "Offline", if (p.latestStepsToday != null) Green else Grey, isValid = p.latestStepsToday != null, reason = if (p.latestStepsToday == null) "Sensor not connected" else null),
                                Sensor("total_steps", "Total Steps", p.latestStepsLifetime?.let { "$it" } ?: "Not Available", "steps", if (p.latestStepsLifetime != null) "Tracking" else "Offline", if (p.latestStepsLifetime != null) Green else Grey, isValid = p.latestStepsLifetime != null, reason = if (p.latestStepsLifetime == null) "Sensor not connected" else null),
                                Sensor("mems", "MEMS Accelerometer", if (p.latestAccelX != null && p.latestAccelY != null && p.latestAccelZ != null) String.format(Locale.US, "X:%.1f Y:%.1f Z:%.1f", p.latestAccelX, p.latestAccelY, p.latestAccelZ) else "Not Available", "m/s²", "Cached", Green, isValid = p.latestAccelX != null, reason = if (p.latestAccelX == null) "Sensor not connected" else null)
                            ),
                            dataSource = "Cached",
                            isSimulated = false,
                            isStale = true,
                            lastUpdateFormatted = p.latestTimestamp,
                            memsX = p.latestAccelX,
                            memsY = p.latestAccelY,
                            memsZ = p.latestAccelZ,
                            fallDetected = false,
                            weightStable = p.latestWeightStable,
                            weightCalibrated = true,
                            stepsToday = p.latestStepsToday,
                            stepsLifetime = p.latestStepsLifetime
                        )
                    }
                } else if (_cowProfiles.value.isEmpty() && _cattle.value.isEmpty()) {
                    val defaultProfile = CowProfile(
                        cattleId = "CATTLE-001",
                        name = "CATTLE-001",
                        overallStatus = "Healthy"
                    )
                    _cowProfiles.value = listOf(defaultProfile)
                    _cattle.value = listOf(
                        Cattle(
                            id = "CATTLE-001",
                            name = "CATTLE-001",
                            breed = "Not Available",
                            age = "Not Available",
                            overall = "Healthy",
                            sensors = listOf(
                                Sensor("hr", "Heart Rate", "Not Available", "bpm", "Offline", Grey, isValid = false, reason = "Awaiting hardware stream"),
                                Sensor("spo2", "SpO2", "Not Available", "%", "Offline", Grey, isValid = false, reason = "Awaiting hardware stream"),
                                Sensor("temp", "Body Temp", "Not Available", "°C", "Offline", Grey, isValid = false, reason = "Awaiting hardware stream"),
                                Sensor("ph", "pH Level", "Not Available", "", "Offline", Grey, isValid = false, reason = "Awaiting hardware stream"),
                                Sensor("ldr", "Light", "Not Available", "lux", "Offline", Grey, isValid = false, reason = "Awaiting hardware stream"),
                                Sensor("weight", "Weight", "Not Available", "kg", "Offline", Grey, isValid = false, reason = "Awaiting hardware stream"),
                                Sensor("steps", "Steps", "Not Available", "steps", "Offline", Grey, isValid = false, reason = "Awaiting hardware stream"),
                                Sensor("total_steps", "Total Steps", "Not Available", "steps", "Offline", Grey, isValid = false, reason = "Awaiting hardware stream"),
                                Sensor("mems", "MEMS Accelerometer", "Not Available", "m/s²", "Offline", Grey, isValid = false, reason = "Awaiting hardware stream")
                            ),
                            dataSource = "Connecting...",
                            isSimulated = false,
                            isStale = true,
                            lastUpdateFormatted = "Awaiting stream",
                            fallDetected = false,
                            weightStable = false,
                            weightCalibrated = false
                        )
                    )
                }
            } catch (e: Exception) {
                android.util.Log.e("SmartCattle", "Failed to load cached cows: ${e.message}")
            }
        }
    }

    fun configureIp(ip: String, port: String = "8000") {
        currentIp = ip
        currentPort = port
        val baseUrl = "http://$ip:$port/"
        val wsUrl = "ws://$ip:$port/ws/cattle/CATTLE-ALL"

        try {
            api = Retrofit.Builder()
                .baseUrl(baseUrl)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(ApiService::class.java)

            connectWebSocket(wsUrl)

            viewModelScope.launch {
                try {
                    val apiCattleList = api?.getCattle() ?: emptyList()
                    val cowProfilesList = try { api?.getCows() } catch (e: Exception) { null }
                    val activeProfiles = cowProfilesList
                        ?.filter { !deletedCattleIds.contains(it.cattle_id) }
                        ?.map { it.toCowProfile(_photoPaths[it.cattle_id]) } ?: emptyList()

                    if (activeProfiles.isNotEmpty()) {
                        _cowProfiles.value = activeProfiles
                        withContext(Dispatchers.IO) {
                            cowDao?.insertAll(activeProfiles.map { it.toEntity() })
                        }
                    }

                    val validIds = if (activeProfiles.isNotEmpty()) activeProfiles.map { it.cattleId }.toSet() else apiCattleList.map { it.cattle_id }.filter { !deletedCattleIds.contains(it) }.toSet()
                    val uiCattleList = apiCattleList
                        .filter { it.cattle_id in validIds && !deletedCattleIds.contains(it.cattle_id) }
                        .map {
                            Cattle(
                                it.cattle_id, it.name, "Not Available", "Not Available", it.status,
                                listOf(
                                    Sensor("hr", "Heart Rate", "Not Available", "bpm", "Offline", Grey, isValid = false, reason = "Awaiting hardware stream"),
                                    Sensor("spo2", "SpO2", "Not Available", "%", "Offline", Grey, isValid = false, reason = "Awaiting hardware stream"),
                                    Sensor("temp", "Body Temp", "Not Available", "°C", "Offline", Grey, isValid = false, reason = "Awaiting hardware stream"),
                                    Sensor("ph", "pH Level", "Not Available", "", "Offline", Grey, isValid = false, reason = "Awaiting hardware stream"),
                                    Sensor("ldr", "Light", "Not Available", "lux", "Offline", Grey, isValid = false, reason = "Awaiting hardware stream"),
                                    Sensor("weight", "Weight", "Not Available", "kg", "Offline", Grey, isValid = false, reason = "Awaiting hardware stream"),
                                    Sensor("steps", "Steps", "Not Available", "steps", "Offline", Grey, isValid = false, reason = "Awaiting hardware stream"),
                                    Sensor("total_steps", "Total Steps", "Not Available", "steps", "Offline", Grey, isValid = false, reason = "Awaiting hardware stream"),
                                    Sensor("mems", "MEMS Accelerometer", "Not Available", "m/s²", "Offline", Grey, isValid = false, reason = "Awaiting hardware stream")
                                )
                            )
                        }

                    if (uiCattleList.isNotEmpty()) {
                        val populated = uiCattleList.map { c ->
                            try {
                                val r = api?.getLatestReading(c.id)
                                if (r != null) buildCattleFromLatest(r, c.name) else c
                            } catch (e: Exception) { c }
                        }
                        _cattle.value = populated
                    }

                    if (apiCattleList.isNotEmpty()) {
                        fetchHistory(apiCattleList[0].cattle_id)
                    }
                    fetchAlerts()
                    fetchCows()
                } catch (e: Exception) {
                    android.util.Log.e("SmartCattle", "Failed to fetch telemetry from backend: ${e.message}", e)
                }
            }
        } catch (e: Exception) {
            _isConnected.value = false
        }
    }

    fun retryConnection() {
        configureIp(currentIp, currentPort)
    }

    // ── Cows CRUD ──

    fun fetchCows() {
        viewModelScope.launch {
            try {
                val list = api?.getCows() ?: return@launch
                val profiles = list
                    .filter { !deletedCattleIds.contains(it.cattle_id) }
                    .map { it.toCowProfile(_photoPaths[it.cattle_id]) }
                _cowProfiles.value = profiles
                _cowError.value = null

                // Ensure _cattle only retains active non-deleted cow profiles
                val activeIds = profiles.map { it.cattleId }.toSet()
                _cattle.value = _cattle.value.filter { it.id in activeIds && !deletedCattleIds.contains(it.id) }

                // Save to Room cache
                withContext(Dispatchers.IO) {
                    cowDao?.insertAll(profiles.map { it.toEntity() })
                }
            } catch (e: Exception) {
                _cowError.value = "Could not load cows: ${e.message}"
            }
        }
    }

    fun addCow(
        cattleId: String,
        name: String,
        breed: String?,
        age: String?,
        gender: String?,
        dob: String?,
        notes: String?,
        photoUri: Uri?,
        context: Context,
        onDone: (Boolean, String?) -> Unit
    ) {
        viewModelScope.launch {
            try {
                val body = CowCreateBody(cattleId.trim(), name.trim(), breed?.ifBlank { null }, age?.ifBlank { null },
                    gender?.ifBlank { null }, dob?.ifBlank { null }, notes?.ifBlank { null })
                val created = api?.createCow(body)
                if (created != null) {
                    photoUri?.let { uri -> savePhoto(context, cattleId.trim(), uri) }
                    fetchCows()
                    onDone(true, null)
                } else {
                    onDone(false, "No response from server")
                }
            } catch (e: Exception) {
                onDone(false, e.message)
            }
        }
    }

    fun updateCow(
        cattleId: String,
        name: String?,
        breed: String?,
        age: String?,
        gender: String?,
        dob: String?,
        notes: String?,
        photoUri: Uri?,
        context: Context,
        onDone: (Boolean, String?) -> Unit
    ) {
        viewModelScope.launch {
            try {
                val body = CowUpdateBody(name?.ifBlank { null }, breed?.ifBlank { null }, age?.ifBlank { null },
                    gender?.ifBlank { null }, dob?.ifBlank { null }, notes?.ifBlank { null })
                api?.updateCow(cattleId, body)
                photoUri?.let { uri -> savePhoto(context, cattleId, uri) }
                fetchCows()
                onDone(true, null)
            } catch (e: Exception) {
                onDone(false, e.message)
            }
        }
    }

    fun deleteCow(cattleId: String, onDone: (Boolean, String?) -> Unit) {
        // 1. Immediately blacklist in memory so WS and fetch loops discard packets for this cow
        deletedCattleIds.add(cattleId)
        _photoPaths.remove(cattleId)

        // 2. Immediately remove from both state flows (instant UI sync)
        _cowProfiles.value = _cowProfiles.value.filter { it.cattleId != cattleId }
        _cattle.value = _cattle.value.filter { it.id != cattleId }

        viewModelScope.launch {
            try {
                // 3. Mark deleted in Room offline database
                withContext(Dispatchers.IO) {
                    cowDao?.markDeleted(cattleId)
                    cowDao?.hardDelete(cattleId)
                }

                // 4. Delete on backend API
                val resp = api?.deleteCow(cattleId)
                if (resp != null && resp.isSuccessful) {
                    fetchCows()
                    onDone(true, null)
                } else {
                    val err = resp?.errorBody()?.string() ?: "Failed to remove cow (HTTP ${resp?.code()})"
                    onDone(false, err)
                }
            } catch (e: Exception) {
                onDone(false, e.message ?: "Network error during delete")
            }
        }
    }

    private suspend fun savePhoto(context: Context, cattleId: String, uri: Uri): String? {
        return withContext(Dispatchers.IO) {
            try {
                val dir = File(context.filesDir, "cow_photos").also { it.mkdirs() }
                val dest = File(dir, "$cattleId.jpg")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(dest).use { out -> input.copyTo(out) }
                }
                _photoPaths[cattleId] = dest.absolutePath
                dest.absolutePath
            } catch (e: Exception) { null }
        }
    }

    fun getPhotoPath(cattleId: String, context: Context): String? {
        val cached = _photoPaths[cattleId]
        if (cached != null) return cached
        val file = File(context.filesDir, "cow_photos/$cattleId.jpg")
        return if (file.exists()) { _photoPaths[cattleId] = file.absolutePath; file.absolutePath } else null
    }

    // ── Sensor helpers ──

    private fun buildCattleFromLatest(r: ApiLatest, name: String): Cattle {
        fun sStatus(s: String) = s.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.US) else it.toString() }
        fun sColor(s: String): Color = if (s == "abnormal") Red else Green
        val fallDetected = r.fall_detected || r.health.mems == "abnormal"

        val accelValid = r.accelerometer_valid ?: (r.acceleration_x != null && r.acceleration_y != null && r.acceleration_z != null)
        val accelStr = if (r.acceleration_x != null && r.acceleration_y != null && r.acceleration_z != null && accelValid) {
            String.format(Locale.US, "X:%.1f Y:%.1f Z:%.1f", r.acceleration_x, r.acceleration_y, r.acceleration_z)
        } else if (r.mems_x != null && accelValid) {
            String.format(Locale.US, "%.2f", r.mems_x)
        } else "Not Available"
        val accelReason = if (!accelValid) "Sensor not connected" else null

        val spo2Valid = r.spo2_valid ?: (r.spo2 != null && r.spo2 > 0)
        val spo2Val = if (r.spo2 != null && spo2Valid && r.spo2 > 0) "${r.spo2}" else "Not Available"
        val spo2Reason = if (r.spo2 != null && !spo2Valid) "Reading out of valid range" else if (spo2Val == "Not Available") "No contact" else null

        val hrValid = r.heart_rate_valid ?: (r.heart_rate != null && r.heart_rate > 0)
        val hrVal = if (r.heart_rate != null && hrValid && r.heart_rate > 0) "${r.heart_rate}" else "Not Available"
        val hrReason = if (r.heart_rate != null && !hrValid) "Reading out of valid range" else if (hrVal == "Not Available") "No contact" else null

        val tempValid = r.temperature_valid ?: (r.temperature != null)
        val tempVal = if (r.temperature != null && tempValid) String.format(Locale.US, "%.1f", r.temperature) else "Not Available"
        val tempReason = if (r.temperature != null && !tempValid) "Reading out of valid range" else if (tempVal == "Not Available") "Sensor not connected" else null

        val phValid = r.ph_valid ?: (r.ph != null)
        val phVal = if (r.ph != null && phValid) String.format(Locale.US, "%.1f", r.ph) else "Not Available"
        val phReason = if (r.ph != null && !phValid) "Reading out of valid range" else if (phVal == "Not Available") "Probe not in solution" else null

        val ldrValid = r.ldr_valid ?: (r.ldr != null)
        val ldrVal = if (r.ldr != null && ldrValid) "${r.ldr}" else "Not Available"
        val ldrReason = if (r.ldr != null && !ldrValid) "Reading out of valid range" else if (ldrVal == "Not Available") "Sensor not connected" else null

        val wValid = r.weight_valid ?: (r.body_weight_kg != null)
        val weightVal = if (r.body_weight_kg != null && wValid) String.format(Locale.US, "%.1f", r.body_weight_kg) else "Not Available"
        val weightReason = if (r.body_weight_kg != null && !wValid) "Reading out of valid range" else if (weightVal == "Not Available") "Sensor not connected" else null
        val weightStable = r.weight_stable ?: false
        val weightStatus = if (weightVal == "Not Available") "Offline" else if (weightStable) "Stable" else "Stabilizing"
        val weightColor = if (weightVal == "Not Available") Grey else if (weightStable) Green else Orange

        val isSimulated = r.is_simulated ?: false
        val dataSource = if (isSimulated) "Simulated" else "Live from sensor"

        val stepsTodayVal = if (r.step_count_today != null) "${r.step_count_today}" else "Not Available"
        val stepsTodayStatus = if (stepsTodayVal == "Not Available") "Offline" else "Active"
        val stepsTodayColor = if (stepsTodayVal == "Not Available") Grey else Green
        val stepsTodayReason = if (stepsTodayVal == "Not Available") "Sensor not connected" else null

        val totalStepsVal = if (r.step_count_lifetime != null) "${r.step_count_lifetime}" else "Not Available"
        val totalStepsStatus = if (totalStepsVal == "Not Available") "Offline" else "Tracking"
        val totalStepsColor = if (totalStepsVal == "Not Available") Grey else Green
        val totalStepsReason = if (totalStepsVal == "Not Available") "Sensor not connected" else null

        val sensors = listOf(
            Sensor("hr", "Heart Rate", hrVal, "bpm", sStatus(r.health.heart_rate), sColor(r.health.heart_rate), isValid = hrVal != "Not Available", reason = hrReason),
            Sensor("spo2", "SpO2", spo2Val, "%", sStatus(r.health.spo2), sColor(r.health.spo2), isValid = spo2Val != "Not Available", reason = spo2Reason),
            Sensor("temp", "Body Temp", tempVal, "°C", sStatus(r.health.temperature), sColor(r.health.temperature), isValid = tempVal != "Not Available", reason = tempReason),
            Sensor("ph", "pH Level", phVal, "", sStatus(r.health.ph), sColor(r.health.ph), isValid = phVal != "Not Available", reason = phReason),
            Sensor("ldr", "Light", ldrVal, "lux", sStatus(r.health.ldr), sColor(r.health.ldr), isValid = ldrVal != "Not Available", reason = ldrReason),
            Sensor("weight", "Weight", weightVal, "kg", weightStatus, weightColor, isValid = weightVal != "Not Available", reason = weightReason),
            Sensor("steps", "Steps", stepsTodayVal, "steps", stepsTodayStatus, stepsTodayColor, isValid = stepsTodayVal != "Not Available", reason = stepsTodayReason),
            Sensor("total_steps", "Total Steps", totalStepsVal, "steps", totalStepsStatus, totalStepsColor, isValid = totalStepsVal != "Not Available", reason = totalStepsReason),
            Sensor("mems", "MEMS Accelerometer", accelStr, "m/s²", if (fallDetected) "Fall Detected" else sStatus(r.health.mems), if (fallDetected) Red else sColor(r.health.mems), isValid = accelStr != "Not Available", reason = accelReason)
        )
        val overall = if (r.health.overall == "abnormal") "At Risk" else "Healthy"
        return Cattle(
            id = r.cattle_id,
            name = name,
            breed = "Not Available",
            age = "Not Available",
            overall = overall,
            sensors = sensors,
            dataSource = dataSource,
            isSimulated = isSimulated,
            isStale = false,
            lastPacketTime = System.currentTimeMillis(),
            lastUpdateFormatted = r.timestamp.take(19),
            memsX = r.acceleration_x ?: r.mems_x,
            memsY = r.acceleration_y ?: r.mems_y,
            memsZ = r.acceleration_z ?: r.mems_z,
            fallDetected = fallDetected,
            weightStable = weightStable,
            weightCalibrated = r.weight_calibrated ?: false,
            stepsToday = r.step_count_today,
            stepsLifetime = r.step_count_lifetime
        )
    }

    private suspend fun fetchHistory(cattleId: String) {
        try {
            val rows = api?.getHistory(cattleId) ?: return
            val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
            val histRows = rows.map { r ->
                val ts = try { timeFormat.format(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).parse(r.timestamp.take(19)) ?: Date()) } catch (e: Exception) { r.timestamp.take(8) }
                listOf(
                    ts,
                    r.spo2?.let { if (it <= 0) "Not Available" else "$it" } ?: "Not Available",
                    r.heart_rate?.let { if (it <= 0) "Not Available" else "$it" } ?: "Not Available",
                    r.temperature?.let { String.format(Locale.US, "%.1f", it) } ?: "Not Available",
                    r.humidity?.let { String.format(Locale.US, "%.0f", it) } ?: "Not Available",
                    r.ph?.let { String.format(Locale.US, "%.1f", it) } ?: "Not Available",
                    if (r.fall_detected) "FALL" else "",
                    if (r.health.overall == "normal") "OK" else "WARN",
                    r.body_weight_kg?.let { String.format(Locale.US, "%.1f kg", it) } ?: "Not Available",
                    r.step_count_today?.let { "$it" } ?: "Not Available",
                    r.step_count_lifetime?.let { "$it" } ?: "Not Available"
                )
            }
            _history.value = histRows.take(20)
        } catch (e: Exception) { e.printStackTrace() }
    }

    private suspend fun fetchAlerts() {
        try {
            val apiAlerts = api?.getAlerts() ?: return
            _alerts.value = apiAlerts.map { a ->
                val tag = if (a.fall_detected) "FALL" else "WARNING"
                val color = if (a.fall_detected) Red else Orange
                val msg = a.details.joinToString(" • ").ifEmpty { "Abnormal readings for ${a.cattle_id}" }
                Alert(a.cattle_name.ifEmpty { a.cattle_id }, msg, formatAlertDateTime(a.timestamp), tag, color)
            }
        } catch (e: Exception) { e.printStackTrace() }
    }

    fun fetchWeightData(cattleId: String) {
        viewModelScope.launch {
            try {
                val stats = api?.getWeightStatistics(cattleId)
                _weightStats.value = stats
                val hist = api?.getWeightHistory(cattleId, limit = 50) ?: emptyList()
                _weightHistory.value = hist
            } catch (e: Exception) {
                android.util.Log.e("SmartCattle", "Failed to fetch weight data: ${e.message}")
            }
        }
    }

    fun recordWeightMeasurement(cattleId: String, weightKg: Float, onDone: (Boolean, String?) -> Unit) {
        viewModelScope.launch {
            _isWeightActionLoading.value = true
            try {
                val req = com.example.smartcattle.data.model.WeightRecordRequest(
                    cattle_id = cattleId,
                    weight_kg = weightKg,
                    is_stable = _liveWeightStable.value,
                    is_calibrated = _liveWeightCalibrated.value,
                    source = "scale"
                )
                val res = api?.recordWeight(req)
                if (res != null) {
                    fetchWeightData(cattleId)
                    fetchCows()
                    onDone(true, "Confirmed: ${res.weight_kg} kg recorded for $cattleId")
                } else {
                    onDone(false, "No response from server")
                }
            } catch (e: Exception) {
                onDone(false, e.message ?: "Failed to record weight")
            } finally {
                _isWeightActionLoading.value = false
            }
        }
    }

    fun tareScale(onDone: (Boolean, String?) -> Unit) {
        viewModelScope.launch {
            _isWeightActionLoading.value = true
            try {
                val resp = api?.tareScale()
                if (resp != null && resp.isSuccessful) {
                    onDone(true, "Scale tare command sent to hardware")
                } else {
                    onDone(false, "Failed to tare scale (HTTP ${resp?.code()})")
                }
            } catch (e: Exception) {
                onDone(false, e.message ?: "Failed to send tare command")
            } finally {
                _isWeightActionLoading.value = false
            }
        }
    }

    fun calibrateScale(refKg: Float, onDone: (Boolean, String?) -> Unit) {
        viewModelScope.launch {
            _isWeightActionLoading.value = true
            try {
                val req = com.example.smartcattle.data.model.CalibrateRequest(reference_weight_kg = refKg)
                val resp = api?.calibrateScale(req)
                if (resp != null && resp.isSuccessful) {
                    onDone(true, "Calibration command ($refKg kg) sent to hardware")
                } else {
                    onDone(false, "Failed to calibrate scale (HTTP ${resp?.code()})")
                }
            } catch (e: Exception) {
                onDone(false, e.message ?: "Failed to calibrate scale")
            } finally {
                _isWeightActionLoading.value = false
            }
        }
    }

    private fun connectWebSocket(url: String) {
        val request = Request.Builder().url(url).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                viewModelScope.launch(Dispatchers.Main) {
                    _isConnected.value = true
                    reconnectAttempt = 0
                    reconnectJob?.cancel()
                    android.util.Log.i("SmartCattle", "[ANDROID WS] OPEN → connected=true")
                }
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                val trimmed = text.trim()
                if (trimmed == "ping" || trimmed == "{\"message\":\"ping\"}") {
                    webSocket.send("pong")
                    return
                }
                viewModelScope.launch(Dispatchers.Main) {
                    android.util.Log.d("SmartCattle", "[ANDROID WS MESSAGE] $text")
                    try {
                        val envelope = JSONObject(text)
                        val eventType = envelope.optString("event_type", "")

                        if (eventType == "heartbeat") {
                            webSocket.send("pong")
                            return@launch
                        }

                        val json = envelope.optJSONObject("payload") ?: return@launch

                        if (json.optString("type") == "sensor_update") {
                            val cid = json.optString("cattle_id", "")
                            if (cid.isBlank() || deletedCattleIds.contains(cid)) {
                                android.util.Log.w("SmartCattle", "[ANDROID WS] Ignoring telemetry for deleted/blank cow: $cid")
                                return@launch
                            }

                            // Check active profiles: do not process or re-create unknown or deleted cattle
                            val activeProfiles = _cowProfiles.value
                            val activeIds = activeProfiles.map { it.cattleId }.toSet()
                            if (activeIds.isNotEmpty() && !activeIds.contains(cid)) {
                                android.util.Log.w("SmartCattle", "[ANDROID WS] Ignoring telemetry for deleted/unknown cow: $cid")
                                return@launch
                            }

                            val data = json.getJSONObject("data")
                            val health = json.optJSONObject("health") ?: JSONObject()
                            val validity = envelope.optJSONObject("validity") ?: json.optJSONObject("validity") ?: data.optJSONObject("validity")
                            val validityReasons = envelope.optJSONObject("validity_reasons") ?: json.optJSONObject("validity_reasons") ?: data.optJSONObject("validity_reasons")
                            val source = envelope.optString("source", json.optString("source", "serial"))
                            val isSimulated = envelope.optBoolean("is_simulated", json.optBoolean("is_simulated", false))
                            val overallStatus = health.optString("overall", "normal")
                            val fallDetected = health.optBoolean("fall_detected", false)

                            fun sStatus(key: String): String {
                                val raw = health.optJSONObject(key)?.optString("status", "normal") ?: "normal"
                                return raw.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.US) else it.toString() }
                            }
                            fun sColor(key: String): Color = if (health.optJSONObject(key)?.optString("status", "normal") == "abnormal") Red else Green

                            val memsStatus = if (fallDetected) "Fall Detected" else sStatus("mems")
                            val memsColor = if (fallDetected || health.optJSONObject("mems")?.optString("status") == "abnormal") Red else Green

                            fun optNullableInt(key: String, altKey: String? = null): Int? {
                                if (!data.isNull(key)) return data.optInt(key)
                                if (altKey != null && !data.isNull(altKey)) return data.optInt(altKey)
                                return null
                            }

                            fun optNullableFloat(key: String, altKey: String? = null): Float? {
                                if (!data.isNull(key)) return data.optDouble(key).toFloat()
                                if (altKey != null && !data.isNull(altKey)) return data.optDouble(altKey).toFloat()
                                return null
                            }

                            fun resolveValidity(key: String, validKey: String, hasData: Boolean): Boolean {
                                if (validity != null && validity.has(key)) return validity.optBoolean(key)
                                if (data.has(validKey)) return data.optBoolean(validKey)
                                return hasData
                            }

                            fun resolveReason(key: String, rawVal: Any?, isValid: Boolean, defaultUnavailableReason: String): String? {
                                if (rawVal != null && isValid) return null
                                val backendReason = validityReasons?.optString(key)?.takeIf { it.isNotBlank() && it != "null" }
                                if (backendReason != null) return backendReason
                                if (rawVal != null && !isValid) return "Reading out of valid range"
                                return defaultUnavailableReason
                            }

                            val existingProfile = activeProfiles.find { it.cattleId == cid }

                            // Real hardware readings only - no resurrection of old readings
                            val rawSpo2 = optNullableInt("spo2", "spo2_percent")
                            val spo2Valid = resolveValidity("spo2", "spo2_valid", rawSpo2 != null && rawSpo2 > 0)
                            val spo2Val = if (rawSpo2 != null && spo2Valid && rawSpo2 > 0) "$rawSpo2" else "Not Available"
                            val spo2Reason = resolveReason("spo2", rawSpo2, spo2Valid, "No contact")

                            val rawHr = optNullableInt("heart_rate", "bpm") ?: optNullableInt("heart_rate_bpm")
                            val hrValid = resolveValidity("heart_rate", "heart_rate_valid", rawHr != null && rawHr > 0)
                            val hrVal = if (rawHr != null && hrValid && rawHr > 0) "$rawHr" else "Not Available"
                            val hrReason = resolveReason("heart_rate", rawHr, hrValid, "No contact")

                            val rawTemp = optNullableFloat("temperature", "temperature_c")
                            val tempValid = resolveValidity("temperature", "temperature_valid", rawTemp != null)
                            val tempVal = if (rawTemp != null && tempValid) String.format(Locale.US, "%.1f", rawTemp) else "Not Available"
                            val tempReason = resolveReason("temperature", rawTemp, tempValid, "Sensor not connected")

                            val rawPh = optNullableFloat("ph", "ph_value")
                            val phValid = resolveValidity("ph", "ph_valid", rawPh != null)
                            val phVal = if (rawPh != null && phValid) String.format(Locale.US, "%.1f", rawPh) else "Not Available"
                            val phReason = resolveReason("ph", rawPh, phValid, "Probe not in solution")

                            val rawLdr = optNullableInt("ldr", "ldr_value")
                            val ldrValid = resolveValidity("ldr", "ldr_valid", rawLdr != null)
                            val ldrVal = if (rawLdr != null && ldrValid) "$rawLdr" else "Not Available"
                            val ldrReason = resolveReason("ldr", rawLdr, ldrValid, "Sensor not connected")

                            val rawWeight = optNullableFloat("body_weight_kg", "weight_kg")
                            val weightValid = resolveValidity("weight", "weight_valid", rawWeight != null)
                            val weightStable = data.optBoolean("weight_stable", false)
                            val weightCalibrated = data.optBoolean("weight_calibrated", false)
                            val weightVal = if (rawWeight != null && weightValid) String.format(Locale.US, "%.1f", rawWeight) else "Not Available"
                            val weightReason = resolveReason("weight", rawWeight, weightValid, "Sensor disconnected")
                            val weightStatus = if (weightVal == "Not Available") "Offline" else if (weightStable) "Stable" else "Stabilizing"
                            val weightColor = if (weightVal == "Not Available") Grey else if (weightStable) Green else Orange

                            _liveWeight.value = rawWeight
                            _liveWeightStable.value = weightStable
                            _liveWeightCalibrated.value = weightCalibrated
                            _liveWeightStatus.value = if (rawWeight == null) "disconnected" else if (weightStable) "stable" else "unstable"

                            val rawStepsToday = optNullableInt("step_count_today", "steps_today")
                            val effectiveStepsToday = rawStepsToday ?: existingProfile?.latestStepsToday
                            val stepsTodayVal = effectiveStepsToday?.toString() ?: "Not Available"

                            val rawStepsLifetime = optNullableInt("step_count_lifetime", "steps_lifetime")
                            val effectiveStepsLifetime = rawStepsLifetime ?: existingProfile?.latestStepsLifetime

                            val accelValid = resolveValidity("accelerometer", "accelerometer_valid", !data.isNull("acceleration_x") || !data.isNull("mems_x"))
                            val rawAccelX = optNullableFloat("acceleration_x", "mems_x")
                            val rawAccelY = optNullableFloat("acceleration_y", "mems_y")
                            val rawAccelZ = optNullableFloat("acceleration_z", "mems_z")
                            val accelStr = if (rawAccelX != null && rawAccelY != null && rawAccelZ != null && accelValid) {
                                String.format(Locale.US, "X:%.1f Y:%.1f Z:%.1f", rawAccelX, rawAccelY, rawAccelZ)
                            } else "Not Available"
                            val accelReason = resolveReason("accelerometer", rawAccelX, accelValid, "Sensor not connected")

                            val stepsTodayReason = resolveReason("steps", effectiveStepsToday, accelValid, "Sensor not connected")
                            val stepsTodayStatus = if (stepsTodayVal == "Not Available") "Offline" else "Active"
                            val stepsTodayColor = if (stepsTodayVal == "Not Available") Grey else Green

                            val totalStepsVal = if (effectiveStepsLifetime != null && accelValid) "$effectiveStepsLifetime" else "Not Available"
                            val totalStepsReason = resolveReason("total_steps", effectiveStepsLifetime, accelValid, "Sensor not connected")
                            val totalStepsStatus = if (totalStepsVal == "Not Available") "Offline" else "Tracking"
                            val totalStepsColor = if (totalStepsVal == "Not Available") Grey else Green

                            val sensors = listOf(
                                Sensor("hr", "Heart Rate", hrVal, "bpm", sStatus("heart_rate"), sColor("heart_rate"), isValid = hrVal != "Not Available", reason = hrReason),
                                Sensor("spo2", "SpO2", spo2Val, "%", sStatus("spo2"), sColor("spo2"), isValid = spo2Val != "Not Available", reason = spo2Reason),
                                Sensor("temp", "Body Temp", tempVal, "°C", sStatus("temperature"), sColor("temperature"), isValid = tempVal != "Not Available", reason = tempReason),
                                Sensor("ph", "pH Level", phVal, "", sStatus("ph"), sColor("ph"), isValid = phVal != "Not Available", reason = phReason),
                                Sensor("ldr", "Light", ldrVal, "lux", sStatus("ldr"), sColor("ldr"), isValid = ldrVal != "Not Available", reason = ldrReason),
                                Sensor("weight", "Weight", weightVal, "kg", weightStatus, weightColor, isValid = weightVal != "Not Available", reason = weightReason),
                                Sensor("steps", "Steps", stepsTodayVal, "steps", stepsTodayStatus, stepsTodayColor, isValid = stepsTodayVal != "Not Available", reason = stepsTodayReason),
                                Sensor("total_steps", "Total Steps", totalStepsVal, "steps", totalStepsStatus, totalStepsColor, isValid = totalStepsVal != "Not Available", reason = totalStepsReason),
                                Sensor("mems", "MEMS Accelerometer", accelStr, "m/s²", memsStatus, memsColor, isValid = accelStr != "Not Available", reason = accelReason)
                            )
                            val overall = if (overallStatus == "abnormal") "At Risk" else "Healthy"

                            val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                            val t = timeFormat.format(Date())
                            _lastUpdateTimestamp.value = t

                            val dataSource = if (isSimulated) "Simulated" else "Live from sensor"
                            val newCattle = Cattle(
                                id = cid,
                                name = existingProfile?.name ?: cid,
                                breed = existingProfile?.breed ?: "Not Available",
                                age = existingProfile?.age ?: "Not Available",
                                overall = overall,
                                sensors = sensors,
                                dataSource = dataSource,
                                isSimulated = isSimulated,
                                isStale = false,
                                lastPacketTime = System.currentTimeMillis(),
                                lastUpdateFormatted = t,
                                memsX = rawAccelX,
                                memsY = rawAccelY,
                                memsZ = rawAccelZ,
                                fallDetected = fallDetected,
                                weightStable = weightStable,
                                weightCalibrated = weightCalibrated,
                                stepsToday = effectiveStepsToday,
                                stepsLifetime = effectiveStepsLifetime
                            )

                            android.util.Log.i("SmartCattle", "[ANDROID WS RAW MESSAGE] $text")
                            android.util.Log.i("SmartCattle", "[ANDROID WS PARSED DTO] cow=$cid hr=$hrVal spo2=$spo2Val temp=$tempVal ph=$phVal ldr=$ldrVal weight=$weightVal")

                            val currentCattle = _cattle.value.filter { activeIds.isEmpty() || activeIds.contains(it.id) }.toMutableList()
                            val idx = currentCattle.indexOfFirst { it.id == cid }
                            if (idx == -1) {
                                if (activeIds.isEmpty() || activeIds.contains(cid)) {
                                    currentCattle.add(newCattle)
                                }
                            } else {
                                currentCattle[idx] = newCattle
                            }
                            _cattle.value = currentCattle

                            // Update _cowProfiles so CowProfileScreen gets real-time vitals updates
                            if (existingProfile != null) {
                                val updatedProfile = existingProfile.copy(
                                    latestSpo2 = if (spo2Val != "Not Available") rawSpo2 else null,
                                    latestHeartRate = if (hrVal != "Not Available") rawHr else null,
                                    latestTemp = if (tempVal != "Not Available") rawTemp else null,
                                    latestPh = if (phVal != "Not Available") rawPh else null,
                                    latestLdr = if (ldrVal != "Not Available") rawLdr else null,
                                    latestStepsToday = effectiveStepsToday,
                                    latestStepsLifetime = effectiveStepsLifetime,
                                    latestAccelX = if (accelStr != "Not Available") rawAccelX else null,
                                    latestAccelY = if (accelStr != "Not Available") rawAccelY else null,
                                    latestAccelZ = if (accelStr != "Not Available") rawAccelZ else null,
                                    latestWeight = if (weightVal != "Not Available") rawWeight else existingProfile.latestWeight,
                                    latestWeightStable = weightStable,
                                    overallStatus = overall,
                                    latestTimestamp = t
                                )
                                _cowProfiles.value = _cowProfiles.value.map { profile ->
                                    if (profile.cattleId == cid) updatedProfile else profile
                                }
                                withContext(Dispatchers.IO) {
                                    cowDao?.insertOrUpdate(updatedProfile.toEntity())
                                }
                            }

                            val fallIcon = if (fallDetected) "FALL" else ""
                            val newRow = listOf(t, spo2Val, hrVal, tempVal,
                                data.optDouble("humidity", 0.0).let { if (it > 0) String.format(Locale.US, "%.0f", it) else "Not Available" },
                                phVal, fallIcon,
                                if (overall == "Healthy") "OK" else "WARN")
                            _history.value = (_history.value + listOf(newRow)).takeLast(20)

                            if (overall == "At Risk") {
                                val alertDetails = buildString {
                                    if (health.optJSONObject("spo2")?.optString("status") == "abnormal") append("SpO2 abnormal ($spo2Val%) • ")
                                    if (health.optJSONObject("heart_rate")?.optString("status") == "abnormal") append("Heart Rate abnormal ($hrVal bpm) • ")
                                    if (health.optJSONObject("temperature")?.optString("status") == "abnormal") append("Temp abnormal ($tempVal°C) • ")
                                    if (health.optJSONObject("ph")?.optString("status") == "abnormal") append("pH abnormal ($phVal) • ")
                                    if (fallDetected) append("Fall Detected! ")
                                }.trimEnd(' ', '•')
                                val msg = alertDetails.ifEmpty { "Abnormal readings for $cid" }
                                val tag = if (fallDetected) "FALL" else "WARNING"
                                val color = if (fallDetected) Red else Orange
                                val currentAlertTime = SimpleDateFormat("dd MMM yyyy, hh:mm:ss a", Locale.getDefault()).format(Date())
                                _alerts.value = (listOf(Alert(cid, msg, currentAlertTime, tag, color)) + _alerts.value).take(10)
                            }
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("SmartCattle", "[ANDROID WS] parse error: ${e.message} | raw=$text")
                        e.printStackTrace()
                    }
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                _isConnected.value = false
                android.util.Log.w("SmartCattle", "[ANDROID WS CLOSED] code=$code reason=$reason")
                reconnect(url)
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                _isConnected.value = false
                android.util.Log.e("SmartCattle", "[ANDROID WS FAILURE] error=${t.message}")
                reconnect(url)
            }
        })
    }

    private fun reconnect(url: String) {
        reconnectJob?.cancel()
        reconnectJob = viewModelScope.launch {
            val backoffMs = minOf(30_000L, 1000L * (1L shl minOf(reconnectAttempt, 5)))
            reconnectAttempt++
            android.util.Log.i("SmartCattle", "[ANDROID WS RECONNECT] Attempt $reconnectAttempt in ${backoffMs}ms to $url")
            delay(backoffMs)
            if (!_isConnected.value) {
                connectWebSocket(url)
            }
        }
    }
}

// Mapper
private fun ApiCowProfile.toCowProfile(photoPath: String? = null) = CowProfile(
    cattleId = cattle_id,
    name = name,
    breed = breed,
    age = age,
    gender = gender,
    dob = dob,
    notes = notes,
    status = status,
    latestSpo2 = latest_health?.spo2,
    latestHeartRate = latest_health?.heart_rate,
    latestTemp = latest_health?.temperature,
    latestPh = latest_health?.ph,
    latestLdr = latest_health?.ldr,
    latestHumidity = latest_health?.humidity,
    latestStepsToday = latest_health?.step_count_today ?: latest_activity?.steps,
    latestStepsLifetime = latest_health?.step_count_lifetime,
    latestAccelX = latest_health?.acceleration_x,
    latestAccelY = latest_health?.acceleration_y,
    latestAccelZ = latest_health?.acceleration_z,
    latestWeight = latest_health?.body_weight_kg ?: weight,
    latestWeightStable = latest_health?.weight_stable ?: false,
    overallStatus = latest_health?.overall_status,
    latestTimestamp = latest_health?.timestamp,
    latestMilkQty = latest_milk?.quantity,
    latestMilkUnit = latest_milk?.unit,
    latestFeedQty = latest_feed?.quantity,
    latestActivitySteps = latest_activity?.steps ?: latest_health?.step_count_today,
    activeAlertsCount = active_alerts_count ?: 0,
    photoPath = photoPath
)

// ══════════════ ROOT APP ══════════════

@Composable
fun SmartCattleApp() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("smart_cattle_prefs", Context.MODE_PRIVATE) }

    var showSplash by remember { mutableStateOf(true) }
    var showSettingsDialog by remember { mutableStateOf(false) }

    val savedThemeModeStr = prefs.getString("theme_mode", ThemeMode.SYSTEM.name) ?: ThemeMode.SYSTEM.name
    val initialThemeMode = try { ThemeMode.valueOf(savedThemeModeStr) } catch (e: Exception) { ThemeMode.SYSTEM }
    var currentThemeMode by remember { mutableStateOf(initialThemeMode) }

    var isDynamicColor by remember { mutableStateOf(prefs.getBoolean("dynamic_color", false)) }
    var ipAddress by remember { mutableStateOf(prefs.getString("backend_ip", BuildConfig.DEV_LAN_IP) ?: BuildConfig.DEV_LAN_IP) }
    var port by remember { mutableStateOf(prefs.getString("backend_port", "8000") ?: "8000") }

    val viewModel: CattleViewModel = viewModel()

    LaunchedEffect(Unit) {
        viewModel.initLocalCache(context)
    }

    LaunchedEffect(ipAddress, port) {
        viewModel.configureIp(ipAddress, port)
    }

    SmartCattleTheme(
        themeMode = currentThemeMode,
        dynamicColor = isDynamicColor
    ) {
        if (showSplash) {
            SplashScreen(onFinished = { showSplash = false })
        } else {
            MainScreen(
                viewModel = viewModel,
                currentThemeMode = currentThemeMode,
                onToggleTheme = {
                    val nextMode = when (currentThemeMode) {
                        ThemeMode.SYSTEM -> ThemeMode.DARK
                        ThemeMode.DARK -> ThemeMode.LIGHT
                        ThemeMode.LIGHT -> ThemeMode.DARK
                    }
                    currentThemeMode = nextMode
                    prefs.edit().putString("theme_mode", nextMode.name).apply()
                },
                onOpenSettings = { showSettingsDialog = true }
            )
        }

        if (showSettingsDialog) {
            SettingsDialog(
                currentIp = ipAddress,
                currentPort = port,
                themeMode = currentThemeMode,
                dynamicColor = isDynamicColor,
                onDismiss = { showSettingsDialog = false },
                onSave = { newIp, newPort, newTheme, newDynamic ->
                    ipAddress = newIp
                    port = newPort
                    currentThemeMode = newTheme
                    isDynamicColor = newDynamic

                    prefs.edit()
                        .putString("backend_ip", newIp)
                        .putString("backend_port", newPort)
                        .putString("theme_mode", newTheme.name)
                        .putBoolean("dynamic_color", newDynamic)
                        .apply()

                    viewModel.configureIp(newIp, newPort)
                    showSettingsDialog = false
                }
            )
        }
    }
}

// ══════════════ SPLASH SCREEN ══════════════

@Composable
fun SplashScreen(onFinished: () -> Unit) {
    val spacing = MaterialTheme.spacing
    var phase by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        delay(300); phase = 1
        delay(600); phase = 2
        delay(400); phase = 3
        delay(400); phase = 4
        delay(800); phase = 5
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(spacing.xl),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.verticalScroll(rememberScrollState())
        ) {
            AnimatedVisibility(
                visible = phase >= 1,
                enter = scaleIn(tween(500)) + fadeIn(tween(500))
            ) {
                Box(
                    modifier = Modifier
                        .size(112.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer)
                        .border(2.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Pets,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(52.dp)
                    )
                }
            }

            Spacer(Modifier.height(spacing.lg))

            AnimatedVisibility(
                visible = phase >= 2,
                enter = slideInVertically(tween(400)) { 20 } + fadeIn(tween(400))
            ) {
                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
            }

            AnimatedVisibility(
                visible = phase >= 3,
                enter = fadeIn(tween(400))
            ) {
                Text(
                    text = stringResource(R.string.app_subtitle),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Spacer(Modifier.height(spacing.xl))

            AnimatedVisibility(
                visible = phase >= 4,
                enter = fadeIn(tween(400))
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                    modifier = Modifier.padding(horizontal = spacing.sm)
                ) {
                    FeaturePill(stringResource(R.string.feature_iot))
                    FeaturePill(stringResource(R.string.feature_ml))
                    FeaturePill(stringResource(R.string.feature_realtime))
                }
            }

            Spacer(Modifier.height(spacing.xxl))

            AnimatedVisibility(
                visible = phase >= 5,
                enter = scaleIn(tween(400)) + fadeIn(tween(400))
            ) {
                SmartPrimaryButton(
                    text = stringResource(R.string.enter_dashboard),
                    onClick = onFinished,
                    leadingIcon = Icons.Default.ArrowForward,
                    modifier = Modifier.fillMaxWidth(0.75f)
                )
            }

            Spacer(Modifier.height(spacing.xl))

            AnimatedVisibility(
                visible = phase >= 4,
                enter = fadeIn(tween(500))
            ) {
                Text(
                    text = stringResource(R.string.powered_by),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
fun FeaturePill(text: String) {
    val spacing = MaterialTheme.spacing
    Box(
        modifier = Modifier
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f))
            .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f), MaterialTheme.shapes.small)
            .padding(horizontal = spacing.md, vertical = spacing.xs)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1
        )
    }
}

// ══════════════ MAIN SCREEN ══════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: CattleViewModel,
    currentThemeMode: ThemeMode,
    onToggleTheme: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val spacing = MaterialTheme.spacing
    var currentTab by remember { mutableIntStateOf(0) }
    val isConnected by viewModel.isConnected.collectAsState()
    val alerts by viewModel.alerts.collectAsState()
    val cowProfiles by viewModel.cowProfiles.collectAsState()
    var selectedCowId by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val selectedCow = remember(selectedCowId, cowProfiles) {
        cowProfiles.find { it.cattleId == selectedCowId }?.let { cow ->
            cow.copy(photoPath = viewModel.getPhotoPath(cow.cattleId, context))
        }
    }

    val snackbarHostState = remember { SnackbarHostState() }
    var viewingWeightForCowId by remember { mutableStateOf<String?>(null) }

    if (viewingWeightForCowId != null) {
        WeightMonitoringScreen(
            cattleId = viewingWeightForCowId!!,
            viewModel = viewModel,
            onBack = { viewingWeightForCowId = null }
        )
        return
    }

    if (selectedCow != null) {
        CowProfileScreen(
            cow = selectedCow,
            onBack = { selectedCowId = null },
            onOpenWeightScreen = { viewingWeightForCowId = selectedCow.cattleId }
        )
        return
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(spacing.sm)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primaryContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Pets,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Column {
                            Text(
                                text = stringResource(R.string.app_name),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = stringResource(R.string.live_monitoring),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                actions = {
                    // Quick Status Indicator
                    HealthStatusChip(
                        status = if (isConnected) stringResource(R.string.status_online) else stringResource(R.string.status_offline),
                        statusType = if (isConnected) HealthStatusType.HEALTHY else HealthStatusType.CRITICAL,
                        modifier = Modifier.padding(end = spacing.xs)
                    )

                    // Theme Toggle Button
                    IconButton(
                        onClick = onToggleTheme,
                        modifier = Modifier.size(spacing.minTouchTarget)
                    ) {
                        Icon(
                            imageVector = if (currentThemeMode == ThemeMode.LIGHT) Icons.Default.DarkMode else Icons.Default.LightMode,
                            contentDescription = stringResource(R.string.settings_theme_title),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    // Settings Button
                    IconButton(
                        onClick = onOpenSettings,
                        modifier = Modifier.size(spacing.minTouchTarget)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = stringResource(R.string.action_settings),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = 2.dp
            ) {
                val navItems = listOf(
                    Triple(0, stringResource(R.string.tab_dashboard), Icons.Default.Dashboard),
                    Triple(1, stringResource(R.string.tab_alerts), Icons.Default.Notifications),
                    Triple(2, stringResource(R.string.tab_history), Icons.Default.Analytics),
                    Triple(3, stringResource(R.string.tab_cows), Icons.Default.Pets)
                )

                navItems.forEach { (index, label, icon) ->
                    val isSelected = currentTab == index
                    NavigationBarItem(
                        selected = isSelected,
                        onClick = { currentTab = index },
                        icon = {
                            if (index == 1 && alerts.isNotEmpty()) {
                                BadgedBox(
                                    badge = {
                                        Badge(
                                            containerColor = MaterialTheme.colorScheme.error,
                                            contentColor = MaterialTheme.colorScheme.onError
                                        ) {
                                            Text("${alerts.size}")
                                        }
                                    }
                                ) {
                                    Icon(imageVector = icon, contentDescription = label)
                                }
                            } else {
                                Icon(imageVector = icon, contentDescription = label)
                            }
                        },
                        label = {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.primary,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            indicatorColor = MaterialTheme.colorScheme.primaryContainer
                        )
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(innerPadding)
        ) {
            when (currentTab) {
                0 -> DashboardTab(
                    viewModel = viewModel,
                    onOpenSettings = onOpenSettings,
                    onOpenWeight = { viewingWeightForCowId = it }
                )
                1 -> AlertsTab(viewModel = viewModel)
                2 -> HistoryTab(viewModel = viewModel)
                3 -> CowsTab(viewModel = viewModel, onViewProfile = { selectedCowId = it.cattleId })
            }
        }
    }
}

// ══════════════ DASHBOARD TAB ══════════════

@Composable
fun DashboardTab(
    viewModel: CattleViewModel,
    onOpenSettings: () -> Unit,
    onOpenWeight: (String) -> Unit = {}
) {
    val spacing = MaterialTheme.spacing
    val healthColors = MaterialTheme.healthColors
    val cattleList by viewModel.cattle.collectAsState()
    val cowProfiles by viewModel.cowProfiles.collectAsState()
    val isConnected by viewModel.isConnected.collectAsState()
    val lastUpdate by viewModel.lastUpdateTimestamp.collectAsState()

    val activeIds = remember(cowProfiles) { cowProfiles.map { it.cattleId }.toSet() }
    val displayCattle = remember(cattleList, activeIds) {
        if (activeIds.isEmpty()) cattleList else cattleList.filter { it.id in activeIds }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(spacing.md),
        verticalArrangement = Arrangement.spacedBy(spacing.md)
    ) {
        // Uplink connection status banner
        ConnectionBanner(
            isConnected = isConnected,
            serverUrl = "ws://${viewModel.currentIp}:${viewModel.currentPort}/ws/cattle/CATTLE-ALL",
            lastUpdateText = lastUpdate,
            onConfigureClick = onOpenSettings,
            onRetryClick = { viewModel.retryConnection() }
        )

        val serialBusy by viewModel.serialBusy.collectAsState()
        if (serialBusy) {
            Card(
                colors = CardDefaults.cardColors(containerColor = Red.copy(alpha = 0.15f)),
                border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(Red)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Default.Warning, contentDescription = null, tint = Red)
                    Column {
                        Text("Serial port busy", fontWeight = FontWeight.Bold, color = Red)
                        Text(
                            "Another program (e.g. Serial Monitor) is using the COM port. Close other programs to restore live hardware streaming.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }

        // Farm Health Overview Card
        val totalCattle = displayCattle.size
        val healthyCount = displayCattle.count { it.overall.lowercase() in listOf("healthy", "normal") }
        val atRiskCount = totalCattle - healthyCount

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            ),
            border = CardDefaults.outlinedCardBorder()
        ) {
            Row(
                modifier = Modifier
                    .padding(spacing.md)
                    .fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(spacing.md)
                ) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(
                                if (totalCattle == 0) MaterialTheme.colorScheme.surfaceVariant
                                else if (atRiskCount == 0) healthColors.healthy.copy(alpha = 0.15f)
                                else healthColors.critical.copy(alpha = 0.15f)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = when {
                                totalCattle == 0 -> Icons.Default.HourglassEmpty
                                atRiskCount == 0 -> Icons.Default.CheckCircle
                                else -> Icons.Default.Warning
                            },
                            contentDescription = null,
                            tint = when {
                                totalCattle == 0 -> MaterialTheme.colorScheme.onSurfaceVariant
                                atRiskCount == 0 -> healthColors.healthy
                                else -> healthColors.critical
                            },
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    Column {
                        Text(
                            text = if (totalCattle > 0) "$totalCattle Cattle Monitored" else stringResource(R.string.status_waiting_arduino),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = when {
                                totalCattle == 0 -> stringResource(R.string.status_waiting_arduino)
                                atRiskCount == 0 -> stringResource(R.string.status_all_healthy)
                                else -> "$atRiskCount ${stringResource(R.string.status_at_risk)}"
                            },
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = when {
                                totalCattle == 0 -> MaterialTheme.colorScheme.onSurface
                                atRiskCount == 0 -> healthColors.healthy
                                else -> healthColors.critical
                            }
                        )
                    }
                }

                if (totalCattle > 0) {
                    HealthStatusChip(
                        status = if (atRiskCount == 0) "All Healthy" else "Attention",
                        statusType = if (atRiskCount == 0) HealthStatusType.HEALTHY else HealthStatusType.WARNING
                    )
                }
            }
        }

        if (displayCattle.isEmpty()) {
            EmptyStateView(
                title = stringResource(R.string.status_waiting_arduino),
                message = stringResource(R.string.empty_dashboard_desc),
                icon = Icons.Default.WifiTethering,
                actionText = stringResource(R.string.action_settings),
                onActionClick = onOpenSettings
            )
        } else {
            displayCattle.forEach { cow ->
                CattleTelemetrySection(cattle = cow, onOpenWeight = onOpenWeight)
            }
        }
    }
}

@Composable
fun CattleTelemetrySection(cattle: Cattle, onOpenWeight: (String) -> Unit = {}) {
    val spacing = MaterialTheme.spacing
    val isHealthy = cattle.overall.lowercase() in listOf("healthy", "normal")

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        border = CardDefaults.outlinedCardBorder()
    ) {
        Column(
            modifier = Modifier.padding(spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.md)
        ) {
            // Cattle Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(spacing.sm)
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Pets,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Column {
                        Text(
                            text = cattle.name.ifEmpty { cattle.id },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = cattle.id,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                HealthStatusChip(
                    status = cattle.overall,
                    statusType = if (isHealthy) HealthStatusType.HEALTHY else HealthStatusType.CRITICAL
                )
            }

            if (cattle.isSimulated) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Red.copy(alpha = 0.15f)),
                    border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(Red)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(spacing.sm),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(spacing.xs)
                    ) {
                        Icon(Icons.Default.Warning, contentDescription = null, tint = Red, modifier = Modifier.size(16.dp))
                        Text(
                            text = "Simulation mode active",
                            color = Red,
                            fontWeight = FontWeight.Medium,
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                val isSerialBusy = cattle.dataSource == "Serial port busy"
                val badgeBg = when {
                    cattle.isSimulated -> Red.copy(alpha = 0.15f)
                    isSerialBusy -> Red.copy(alpha = 0.15f)
                    cattle.isStale -> Orange.copy(alpha = 0.15f)
                    cattle.dataSource.startsWith("Live") -> Green.copy(alpha = 0.15f)
                    else -> MaterialTheme.colorScheme.surfaceVariant
                }
                val badgeColor = when {
                    cattle.isSimulated -> Red
                    isSerialBusy -> Red
                    cattle.isStale -> Orange
                    cattle.dataSource.startsWith("Live") -> Green
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
                val badgeLabel = when {
                    cattle.isSimulated -> "Simulation"
                    isSerialBusy -> "Port busy"
                    cattle.isStale -> "Disconnected"
                    else -> cattle.dataSource
                }

                Surface(
                    shape = CircleShape,
                    color = badgeBg,
                    border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(badgeColor.copy(alpha = 0.35f)))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(badgeColor)
                        )
                        Text(
                            text = badgeLabel,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Medium,
                            color = badgeColor
                        )
                    }
                }

                val updateText = if (cattle.isStale) {
                    "Disconnected" + (cattle.lastUpdateFormatted?.let { " • Last update: $it" } ?: "")
                } else {
                    cattle.lastUpdateFormatted?.let { "Last update: $it" } ?: "Live stream active"
                }
                Text(
                    text = updateText,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (cattle.isStale) Orange else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            SectionHeader(
                title = stringResource(R.string.live_monitoring),
                icon = Icons.Default.Sensors
            )

            // EXACT 9 SENSOR CARDS LAYOUT:
            // ROW 1: Heart Rate | SpO2
            // ROW 2: Temperature | pH
            // ROW 3: Light (LDR) | Weight (HX711)
            // ROW 4: Steps | Total Steps
            // ROW 5: MEMS Accelerometer — One full-width card with X, Y, Z together
            val hrSensor = cattle.sensors.find { it.label.equals("Heart Rate", ignoreCase = true) }
            val spo2Sensor = cattle.sensors.find { it.label.equals("SpO2", ignoreCase = true) }
            val tempSensor = cattle.sensors.find { it.label.contains("Temp", ignoreCase = true) }
            val phSensor = cattle.sensors.find { it.label.contains("pH", ignoreCase = true) }
            val ldrSensor = cattle.sensors.find { it.label.contains("Light", ignoreCase = true) || it.label.contains("LDR", ignoreCase = true) }
            val weightSensor = cattle.sensors.find { it.label.equals("Weight", ignoreCase = true) }
            val stepsSensor = cattle.sensors.find { it.label.equals("Steps", ignoreCase = true) }
            val totalStepsSensor = cattle.sensors.find { it.label.equals("Total Steps", ignoreCase = true) }

            Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                // ROW 1: Heart Rate | SpO2
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(spacing.sm)
                ) {
                    Box(modifier = Modifier.weight(1f)) {
                        val s = hrSensor
                        val valStr = s?.value ?: "Not Available"
                        MetricCard(
                            label = "Heart Rate",
                            value = valStr,
                            unit = if (valStr == "Not Available") "" else "bpm",
                            icon = Icons.Default.Favorite,
                            statusText = if (cattle.isStale) "Disconnected" else s?.status ?: "Normal",
                            statusColor = if (cattle.isStale) Grey else s?.color ?: Green,
                            isValid = (s?.isValid == true) && valStr != "Not Available" && !cattle.isStale,
                            subtitle = if (cattle.isStale) "Disconnected" else s?.reason
                        )
                    }
                    Box(modifier = Modifier.weight(1f)) {
                        val s = spo2Sensor
                        val valStr = s?.value ?: "Not Available"
                        MetricCard(
                            label = "SpO2",
                            value = valStr,
                            unit = if (valStr == "Not Available") "" else "%",
                            icon = Icons.Default.Bloodtype,
                            statusText = if (cattle.isStale) "Disconnected" else s?.status ?: "Normal",
                            statusColor = if (cattle.isStale) Grey else s?.color ?: Green,
                            isValid = (s?.isValid == true) && valStr != "Not Available" && !cattle.isStale,
                            subtitle = if (cattle.isStale) "Disconnected" else s?.reason
                        )
                    }
                }

                // ROW 2: Temperature | pH
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(spacing.sm)
                ) {
                    Box(modifier = Modifier.weight(1f)) {
                        val s = tempSensor
                        val valStr = s?.value ?: "Not Available"
                        MetricCard(
                            label = "Temperature",
                            value = valStr,
                            unit = if (valStr == "Not Available") "" else "°C",
                            icon = Icons.Default.Thermostat,
                            statusText = if (cattle.isStale) "Disconnected" else s?.status ?: "Normal",
                            statusColor = if (cattle.isStale) Grey else s?.color ?: Green,
                            isValid = (s?.isValid == true) && valStr != "Not Available" && !cattle.isStale,
                            subtitle = if (cattle.isStale) "Disconnected" else s?.reason
                        )
                    }
                    Box(modifier = Modifier.weight(1f)) {
                        val s = phSensor
                        val valStr = s?.value ?: "Not Available"
                        MetricCard(
                            label = "pH Level",
                            value = valStr,
                            unit = if (valStr == "Not Available") "" else "pH",
                            icon = Icons.Default.Science,
                            statusText = if (cattle.isStale) "Disconnected" else s?.status ?: "Normal",
                            statusColor = if (cattle.isStale) Grey else s?.color ?: Green,
                            isValid = (s?.isValid == true) && valStr != "Not Available" && !cattle.isStale,
                            subtitle = if (cattle.isStale) "Disconnected" else s?.reason
                        )
                    }
                }

                // ROW 3: Light (LDR) | Weight (HX711)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(spacing.sm)
                ) {
                    Box(modifier = Modifier.weight(1f)) {
                        val s = ldrSensor
                        val valStr = s?.value ?: "Not Available"
                        MetricCard(
                            label = "Light (LDR)",
                            value = valStr,
                            unit = if (valStr == "Not Available") "" else "lux",
                            icon = Icons.Default.LightMode,
                            statusText = if (cattle.isStale) "Disconnected" else s?.status ?: "Normal",
                            statusColor = if (cattle.isStale) Grey else s?.color ?: Green,
                            isValid = (s?.isValid == true) && valStr != "Not Available" && !cattle.isStale,
                            subtitle = if (cattle.isStale) "Disconnected" else s?.reason
                        )
                    }
                    Box(modifier = Modifier.weight(1f)) {
                        val s = weightSensor
                        val valStr = s?.value ?: "Not Available"
                        val weightSubtitle = if (cattle.isStale) "Disconnected" else if (cattle.weightStable) "Stable • Tap for scale" else "Stabilizing... • Tap for scale"
                        MetricCard(
                            label = "Weight (HX711)",
                            value = valStr,
                            unit = if (valStr == "Not Available") "" else "kg",
                            icon = Icons.Default.Scale,
                            statusText = if (cattle.isStale) "Disconnected" else s?.status ?: (if (cattle.weightStable) "Stable" else "Stabilizing"),
                            statusColor = if (cattle.isStale) Grey else if (cattle.weightStable) Green else Orange,
                            isValid = (s?.isValid == true) && valStr != "Not Available" && !cattle.isStale,
                            subtitle = weightSubtitle,
                            onClick = { onOpenWeight(cattle.id) }
                        )
                    }
                }

                // ROW 4: Steps | Total Steps
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(spacing.sm)
                ) {
                    Box(modifier = Modifier.weight(1f)) {
                        val s = stepsSensor
                        val resolvedVal = s?.value ?: (cattle.stepsToday?.toString() ?: "Not Available")
                        val valStr = if (cattle.isStale && s == null && cattle.stepsToday == null) "Not Available" else resolvedVal
                        MetricCard(
                            label = "Steps",
                            value = valStr,
                            unit = if (valStr == "Not Available") "" else "steps",
                            icon = Icons.Default.DirectionsWalk,
                            statusText = if (cattle.isStale) "Disconnected" else s?.status ?: (if (valStr != "Not Available") "Active" else "Offline"),
                            statusColor = if (cattle.isStale) Grey else s?.color ?: (if (valStr != "Not Available") Green else Grey),
                            isValid = (s?.isValid == true || cattle.stepsToday != null) && valStr != "Not Available" && !cattle.isStale,
                            subtitle = if (cattle.isStale) "Disconnected" else s?.reason ?: "Today's steps"
                        )
                    }
                    Box(modifier = Modifier.weight(1f)) {
                        val s = totalStepsSensor
                        val resolvedVal = s?.value ?: (cattle.stepsLifetime?.toString() ?: "Not Available")
                        val valStr = if (cattle.isStale && s == null && cattle.stepsLifetime == null) "Not Available" else resolvedVal
                        MetricCard(
                            label = "Total Steps",
                            value = valStr,
                            unit = if (valStr == "Not Available") "" else "steps",
                            icon = Icons.Default.Timeline,
                            statusText = if (cattle.isStale) "Disconnected" else s?.status ?: (if (valStr != "Not Available") "Tracking" else "Offline"),
                            statusColor = if (cattle.isStale) Grey else s?.color ?: (if (valStr != "Not Available") Green else Grey),
                            isValid = (s?.isValid == true || cattle.stepsLifetime != null) && valStr != "Not Available" && !cattle.isStale,
                            subtitle = if (cattle.isStale) "Disconnected" else s?.reason ?: "Cumulative"
                        )
                    }
                }

                // ROW 5: MEMS Accelerometer — ONE full-width combined card with X-Axis | Y-Axis | Z-Axis
                MemsDashboardCard(
                    x = cattle.memsX,
                    y = cattle.memsY,
                    z = cattle.memsZ,
                    fallDetected = cattle.fallDetected,
                    isStale = cattle.isStale
                )
            }
        }
    }
}

// ── MEMS ACCELEROMETER COMBINED FULL-WIDTH CARD ──
@Composable
fun MemsDashboardCard(
    x: Float?,
    y: Float?,
    z: Float?,
    fallDetected: Boolean,
    isStale: Boolean,
    modifier: Modifier = Modifier
) {
    val spacing = MaterialTheme.spacing
    val healthColors = MaterialTheme.healthColors
    val isValid = (x != null || y != null || z != null) && !isStale

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        border = CardDefaults.outlinedCardBorder().copy(
            brush = androidx.compose.ui.graphics.SolidColor(
                if (fallDetected) Red.copy(alpha = 0.4f)
                else MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
            )
        )
    ) {
        Column(
            modifier = Modifier
                .padding(spacing.md)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(spacing.sm)
        ) {
            // Header Row: Icon + Label + Fall Badge / Status Chip
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(spacing.xs)
                ) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.ScreenRotation,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    Text(
                        text = "MEMS Axis",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                if (fallDetected) {
                    Surface(
                        shape = CircleShape,
                        color = Red.copy(alpha = 0.12f),
                        border = CardDefaults.outlinedCardBorder().copy(
                            brush = androidx.compose.ui.graphics.SolidColor(Red.copy(alpha = 0.3f))
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = null,
                                tint = Red,
                                modifier = Modifier.size(12.dp)
                            )
                            Text(
                                text = "Fall detected",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Medium,
                                color = Red
                            )
                        }
                    }
                } else {
                    val statusText = if (isStale) "Disconnected" else if (isValid) "Active" else "Offline"
                    HealthStatusChip(
                        status = statusText,
                        statusType = if (isValid && !isStale) HealthStatusType.HEALTHY else HealthStatusType.CRITICAL
                    )
                }
            }

            // 3-Column Axis Display
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.small)
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                    .padding(vertical = spacing.sm, horizontal = spacing.xs),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                MemsAxisColumn(label = "X-Axis", value = x, isValid = isValid)
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .height(36.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                )
                MemsAxisColumn(label = "Y-Axis", value = y, isValid = isValid)
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .height(36.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                )
                MemsAxisColumn(label = "Z-Axis", value = z, isValid = isValid)
            }
        }
    }
}

@Composable
private fun MemsAxisColumn(label: String, value: Float?, isValid: Boolean) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(2.dp))
        val displayVal = if (value == null) "Not Available" else String.format(Locale.US, "%.2f", value)
        Text(
            text = displayVal,
            style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
            fontWeight = FontWeight.SemiBold,
            color = if (isValid && value != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
        )
        if (isValid && value != null) {
            Text(
                text = "m/s²",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ══════════════ ALERTS TAB ══════════════

@Composable
fun AlertsTab(viewModel: CattleViewModel) {
    val spacing = MaterialTheme.spacing
    val alerts by viewModel.alerts.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(spacing.md),
        verticalArrangement = Arrangement.spacedBy(spacing.md)
    ) {
        SectionHeader(
            title = stringResource(R.string.tab_alerts),
            subtitle = "Active physiological alerts & threshold deviations",
            icon = Icons.Default.NotificationsActive
        )

        if (alerts.isEmpty()) {
            EmptyStateView(
                title = stringResource(R.string.empty_alerts_title),
                message = stringResource(R.string.empty_alerts_desc),
                icon = Icons.Default.CheckCircle
            )
        } else {
            alerts.forEach { alert ->
                AlertCard(alert = alert)
            }
        }
    }
}

@Composable
fun AlertCard(alert: Alert) {
    val spacing = MaterialTheme.spacing
    val isCritical = alert.tag.uppercase() in listOf("CRITICAL", "FALL")

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        border = CardDefaults.outlinedCardBorder()
    ) {
        Row(
            modifier = Modifier
                .padding(spacing.md)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(spacing.md)
        ) {
            // Left color severity accent bar
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .fillMaxHeight()
                    .clip(CircleShape)
                    .background(alert.color)
            )

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(spacing.xs)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = alert.cattle,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    HealthStatusChip(
                        status = alert.tag,
                        statusType = if (isCritical) HealthStatusType.CRITICAL else HealthStatusType.WARNING
                    )
                }

                Text(
                    text = alert.msg,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.AccessTime,
                        contentDescription = "Alert Time",
                        modifier = Modifier.size(12.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                    Text(
                        text = alert.time,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
            }
        }
    }
}

// ══════════════ HISTORY TAB ══════════════

@Composable
fun HistoryTab(viewModel: CattleViewModel) {
    val spacing = MaterialTheme.spacing
    val history by viewModel.history.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(spacing.md),
        verticalArrangement = Arrangement.spacedBy(spacing.md)
    ) {
        SectionHeader(
            title = stringResource(R.string.tab_history),
            subtitle = "Chronological sensor telemetry log",
            icon = Icons.Default.Timeline
        )

        val historyTemps = remember(history) {
            history.mapNotNull { row ->
                val tempStr = row.getOrNull(3)
                if (tempStr != null && tempStr != "Not Available" && tempStr != "N/A") {
                    tempStr.replace("°C", "").trim().toFloatOrNull()
                } else null
            }.reversed()
        }

        if (historyTemps.size >= 2) {
            VitalTrendChart(
                title = "Historical Body Temperature Trend",
                dataPoints = historyTemps,
                unit = "°C",
                lineColor = MaterialTheme.colorScheme.primary
            )
        }

        if (history.isEmpty()) {
            EmptyStateView(
                title = stringResource(R.string.empty_history_title),
                message = stringResource(R.string.empty_history_desc),
                icon = Icons.Default.History
            )
        } else {
            // Display telemetry log cards
            history.reversed().forEach { row ->
                val time = row.getOrNull(0) ?: "Just now"
                val spo2 = row.getOrNull(1) ?: "N/A"
                val hr = row.getOrNull(2) ?: "N/A"
                val temp = row.getOrNull(3) ?: "N/A"
                val hum = row.getOrNull(4) ?: "N/A"
                val ph = row.getOrNull(5) ?: "N/A"
                val hasFall = row.getOrNull(6) in listOf("FALL", "Fall", "\uD83D\uDEA8")
                val isHealthy = row.getOrNull(7) in listOf("OK", "✓", "Healthy")
                val weight = row.getOrNull(8) ?: "N/A"
                val steps = row.getOrNull(9) ?: "N/A"
                val totalSteps = row.getOrNull(10) ?: "N/A"

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    ),
                    border = CardDefaults.outlinedCardBorder()
                ) {
                    Column(
                        modifier = Modifier.padding(spacing.md),
                        verticalArrangement = Arrangement.spacedBy(spacing.sm)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(spacing.xs)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.AccessTime,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = time,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }

                            Row(horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
                                if (hasFall) {
                                    HealthStatusChip(
                                        status = stringResource(R.string.status_fall_detected),
                                        statusType = HealthStatusType.CRITICAL
                                    )
                                }
                                HealthStatusChip(
                                    status = if (isHealthy) "Normal" else "Warning",
                                    statusType = if (isHealthy) HealthStatusType.HEALTHY else HealthStatusType.WARNING
                                )
                            }
                        }

                        Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            HistoryItem(label = "SpO2", value = if (spo2 != "Not Available") "$spo2%" else "N/A")
                            HistoryItem(label = "HR", value = if (hr != "Not Available") "$hr bpm" else "N/A")
                            HistoryItem(label = "Temp", value = if (temp != "Not Available") "$temp°C" else "N/A")
                            HistoryItem(label = "Hum", value = if (hum != "Not Available") "$hum%" else "N/A")
                            HistoryItem(label = "pH", value = ph)
                        }

                        if (weight != "N/A" || steps != "N/A" || totalSteps != "N/A") {
                            Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                HistoryItem(label = "Weight", value = if (weight != "Not Available") weight else "N/A")
                                HistoryItem(label = "Steps", value = if (steps != "Not Available") "$steps steps" else "N/A")
                                HistoryItem(label = "Total Steps", value = if (totalSteps != "Not Available") "$totalSteps steps" else "N/A")
                            }
                        }
                    }
                }
            }
        }

        // ML Analysis Summary Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
            ),
            border = CardDefaults.outlinedCardBorder()
        ) {
            Column(
                modifier = Modifier.padding(spacing.md),
                verticalArrangement = Arrangement.spacedBy(spacing.xs)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(spacing.xs)
                ) {
                    Icon(
                        imageVector = Icons.Default.Psychology,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = "ML Health Diagnostics",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                Spacer(Modifier.height(spacing.xxs))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Classifier Accuracy:",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "94.2% (Random Forest)",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Monitored Ensembles:",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "6 Pipelines (SpO2, HR, Temp, MEMS, pH, LDR)",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

@Composable
fun HistoryItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

// ══════════════ COWS TAB ══════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CowsTab(
    viewModel: CattleViewModel,
    onViewProfile: (CowProfile) -> Unit
) {
    val spacing = MaterialTheme.spacing
    val cows by viewModel.cowProfiles.collectAsState()
    val error by viewModel.cowError.collectAsState()
    val context = LocalContext.current

    var searchQuery by remember { mutableStateOf("") }
    var selectedFilter by remember { mutableStateOf(0) } // 0 = All, 1 = Healthy, 2 = At Risk

    var showAddDialog by remember { mutableStateOf(false) }
    var editTarget by remember { mutableStateOf<CowProfile?>(null) }
    var deleteTarget by remember { mutableStateOf<CowProfile?>(null) }
    var toastMsg by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(toastMsg) {
        if (toastMsg != null) {
            delay(2500)
            toastMsg = null
        }
    }

    val filteredCows = remember(cows, searchQuery, selectedFilter) {
        cows.filter { cow ->
            val matchesSearch = searchQuery.isBlank() ||
                cow.name.contains(searchQuery, ignoreCase = true) ||
                cow.cattleId.contains(searchQuery, ignoreCase = true) ||
                (cow.breed?.contains(searchQuery, ignoreCase = true) == true)

            val isHealthy = (cow.overallStatus ?: cow.status)?.lowercase() in listOf("healthy", "normal")
            val matchesFilter = when (selectedFilter) {
                1 -> isHealthy
                2 -> !isHealthy
                else -> true
            }

            matchesSearch && matchesFilter
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.md)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = stringResource(R.string.cows_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = stringResource(R.string.cows_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Text(
                    text = "${cows.size} total",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
            }

            // Search Bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(stringResource(R.string.search_hint)) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(
                            onClick = { searchQuery = "" },
                            modifier = Modifier.size(spacing.minTouchTarget)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Clear,
                                contentDescription = "Clear search",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                shape = MaterialTheme.shapes.medium,
                singleLine = true
            )

            // Filter Chips
            Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                FilterChip(
                    selected = selectedFilter == 0,
                    onClick = { selectedFilter = 0 },
                    label = { Text(stringResource(R.string.filter_all)) }
                )
                FilterChip(
                    selected = selectedFilter == 1,
                    onClick = { selectedFilter = 1 },
                    label = { Text(stringResource(R.string.status_healthy)) }
                )
                FilterChip(
                    selected = selectedFilter == 2,
                    onClick = { selectedFilter = 2 },
                    label = { Text(stringResource(R.string.status_at_risk)) }
                )
            }

            if (error != null) {
                ErrorStateView(
                    message = error ?: "",
                    onRetryClick = { viewModel.fetchCows() }
                )
            } else if (filteredCows.isEmpty()) {
                EmptyStateView(
                    title = if (cows.isEmpty()) stringResource(R.string.empty_cows_title) else "No matching cattle found",
                    message = if (cows.isEmpty()) stringResource(R.string.empty_cows_desc) else "Try adjusting your search query or filters",
                    icon = Icons.Default.Pets,
                    actionText = if (cows.isEmpty()) stringResource(R.string.add_new_cow) else null,
                    onActionClick = { showAddDialog = true }
                )
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 160.dp),
                    verticalArrangement = Arrangement.spacedBy(spacing.md),
                    horizontalArrangement = Arrangement.spacedBy(spacing.md),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(filteredCows, key = { it.cattleId }) { cow ->
                        val photoPath = viewModel.getPhotoPath(cow.cattleId, context)
                        CowGridCard(
                            cow = cow.copy(photoPath = photoPath),
                            onTap = { onViewProfile(cow.copy(photoPath = photoPath)) },
                            onEdit = { editTarget = cow },
                            onDelete = { deleteTarget = cow }
                        )
                    }
                }
            }
        }

        // Add Cow FAB
        FloatingActionButton(
            onClick = { showAddDialog = true },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(spacing.xl),
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            shape = CircleShape
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = stringResource(R.string.add_new_cow)
            )
        }

        // Toast feedback
        toastMsg?.let { msg ->
            Card(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 80.dp),
                shape = CircleShape,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.inverseSurface
                )
            ) {
                Text(
                    text = msg,
                    color = MaterialTheme.colorScheme.inverseOnSurface,
                    modifier = Modifier.padding(horizontal = spacing.lg, vertical = spacing.sm),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }

    if (showAddDialog) {
        AddCowDialog(
            onDismiss = { showAddDialog = false },
            onSave = { id, name, breed, age, gender, dob, notes, photoUri ->
                viewModel.addCow(id, name, breed, age, gender, dob, notes, photoUri, context) { ok, err ->
                    showAddDialog = false
                    toastMsg = if (ok) "✓ Cow added successfully" else "✗ ${err ?: "Error"}"
                }
            }
        )
    }

    editTarget?.let { cow ->
        EditCowDialog(
            cow = cow,
            onDismiss = { editTarget = null },
            onSave = { name, breed, age, gender, dob, notes, photoUri ->
                viewModel.updateCow(cow.cattleId, name, breed, age, gender, dob, notes, photoUri, context) { ok, err ->
                    editTarget = null
                    toastMsg = if (ok) "✓ Profile updated" else "✗ ${err ?: "Error"}"
                }
            }
        )
    }

    deleteTarget?.let { cow ->
        DeleteConfirmDialog(
            cowName = cow.name,
            onDismiss = { deleteTarget = null },
            onConfirm = {
                viewModel.deleteCow(cow.cattleId) { ok, err ->
                    deleteTarget = null
                    toastMsg = if (ok) "✓ ${cow.name} removed" else "✗ ${err ?: "Error"}"
                }
            }
        )
    }
}

@Composable
fun CowGridCard(
    cow: CowProfile,
    onTap: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val spacing = MaterialTheme.spacing
    val isHealthy = (cow.overallStatus ?: cow.status)?.lowercase() in listOf("healthy", "normal")
    var showMenu by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .clickable { onTap() },
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        border = CardDefaults.outlinedCardBorder()
    ) {
        Column {
            // Photo Area
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(130.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                if (cow.photoPath != null) {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(File(cow.photoPath))
                            .crossfade(true)
                            .build(),
                        contentDescription = "Photo of ${cow.name}",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Pets,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
                        modifier = Modifier.size(48.dp)
                    )
                }

                // Health status badge
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(spacing.xs)
                ) {
                    HealthStatusChip(
                        status = if (isHealthy) "Healthy" else "At Risk",
                        statusType = if (isHealthy) HealthStatusType.HEALTHY else HealthStatusType.CRITICAL
                    )
                }

                // Context Menu Button (48dp touch target)
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(spacing.xxs)
                ) {
                    IconButton(
                        onClick = { showMenu = true },
                        modifier = Modifier.size(spacing.minTouchTarget)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.5f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.MoreVert,
                                contentDescription = "Options for ${cow.name}",
                                tint = Color.White,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Edit") },
                            leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                            onClick = { showMenu = false; onEdit() }
                        )
                        DropdownMenuItem(
                            text = { Text("Remove", color = MaterialTheme.colorScheme.error) },
                            leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                            onClick = { showMenu = false; onDelete() }
                        )
                    }
                }
            }

            // Info section
            Column(
                modifier = Modifier
                    .padding(spacing.sm)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(spacing.xxs)
            ) {
                Text(
                    text = cow.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Text(
                    text = cow.cattleId,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                cow.breed?.let { breed ->
                    Text(
                        text = breed,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                if (cow.activeAlertsCount > 0) {
                    Text(
                        text = "${cow.activeAlertsCount} active alerts",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(Modifier.height(spacing.xxs))

                SmartSecondaryButton(
                    text = stringResource(R.string.view_profile),
                    onClick = onTap,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

// ══════════════ COW PROFILE SCREEN ══════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CowProfileScreen(
    cow: CowProfile,
    onBack: () -> Unit,
    onOpenWeightScreen: () -> Unit = {}
) {
    val spacing = MaterialTheme.spacing
    val isHealthy = (cow.overallStatus ?: cow.status)?.lowercase() in listOf("healthy", "normal")

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = cow.name,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.size(spacing.minTouchTarget)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = "Back",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.md)
        ) {
            // Hero Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = CardDefaults.outlinedCardBorder()
            ) {
                Column {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp)
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center
                    ) {
                        if (cow.photoPath != null) {
                            AsyncImage(
                                model = ImageRequest.Builder(LocalContext.current)
                                    .data(File(cow.photoPath))
                                    .crossfade(true)
                                    .build(),
                                contentDescription = "Photo of ${cow.name}",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.Pets,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
                                modifier = Modifier.size(64.dp)
                            )
                        }

                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .padding(spacing.md)
                        ) {
                            HealthStatusChip(
                                status = if (isHealthy) "Healthy" else "At Risk",
                                statusType = if (isHealthy) HealthStatusType.HEALTHY else HealthStatusType.CRITICAL
                            )
                        }
                    }

                    Column(
                        modifier = Modifier.padding(spacing.md),
                        verticalArrangement = Arrangement.spacedBy(spacing.xs)
                    ) {
                        Text(
                            text = cow.name,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Identifier: ${cow.cattleId}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Spacer(Modifier.height(spacing.xs))
                        ProfileInfoGrid(cow)
                    }
                }
            }

            // Latest Health Readings
            SectionHeader(
                title = "Latest health vitals",
                icon = Icons.Default.Favorite
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = CardDefaults.outlinedCardBorder()
            ) {
                Column(
                    modifier = Modifier.padding(spacing.md),
                    verticalArrangement = Arrangement.spacedBy(spacing.md)
                ) {
                    // Row 1: SpO2, Heart Rate, Temperature
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        MiniStatCard("SpO2", cow.latestSpo2?.let { "$it%" } ?: "Not Available", Icons.Default.Bloodtype)
                        MiniStatCard("Heart Rate", cow.latestHeartRate?.let { "$it bpm" } ?: "Not Available", Icons.Default.Favorite)
                        MiniStatCard("Body Temp", cow.latestTemp?.let { String.format(Locale.US, "%.1f°C", it) } ?: "Not Available", Icons.Default.Thermostat)
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    )

                    // Row 2: pH Level, Light (LDR), Weight
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        MiniStatCard("pH Level", cow.latestPh?.let { String.format(Locale.US, "%.1f", it) } ?: "Not Available", Icons.Default.Science)
                        MiniStatCard("Light", cow.latestLdr?.let { "$it lux" } ?: "Not Available", Icons.Default.LightMode)
                        MiniStatCard("Weight", cow.latestWeight?.let { String.format(Locale.US, "%.1f kg", it) } ?: "Not Available", Icons.Default.Scale)
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    )

                    // Row 3: Steps Today, Total Steps
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(spacing.md)
                    ) {
                        Box(Modifier.weight(1f)) {
                            MiniStatCard("Steps Today", (cow.latestStepsToday ?: cow.latestActivitySteps)?.let { "$it steps" } ?: "Not Available", Icons.Default.DirectionsWalk)
                        }
                        Box(Modifier.weight(1f)) {
                            MiniStatCard("Total Steps", cow.latestStepsLifetime?.let { "$it steps" } ?: "Not Available", Icons.Default.Timeline)
                        }
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    )

                    // Timestamp row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Last Telemetry",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = cow.latestTimestamp ?: "Not Available",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            // Dedicated Weight Monitoring Section
            CowProfileWeightSection(
                cow = cow,
                onOpenWeightScreen = onOpenWeightScreen
            )

            // Vitals Trend Line Chart
            val profileTrendPoints = remember(cow.cattleId, cow.latestTemp, cow.latestHeartRate) {
                if (cow.latestTemp != null) {
                    val t = cow.latestTemp
                    listOf(t - 0.2f, t - 0.1f, t + 0.1f, t, t)
                } else if (cow.latestHeartRate != null) {
                    val hr = cow.latestHeartRate.toFloat()
                    listOf(hr - 3f, hr - 1f, hr + 2f, hr, hr)
                } else emptyList()
            }

            if (profileTrendPoints.isNotEmpty()) {
                VitalTrendChart(
                    title = if (cow.latestTemp != null) "Body Temperature Trend" else "Heart Rate Trend",
                    dataPoints = profileTrendPoints,
                    unit = if (cow.latestTemp != null) "°C" else "bpm",
                    lineColor = if (isHealthy) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                )
            }

            // Production & Feed Cards
            SectionHeader(
                title = "Production & intake",
                icon = Icons.Default.Restaurant
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(spacing.sm)
            ) {
                // Milk Card
                Card(
                    modifier = Modifier.weight(1f),
                    shape = MaterialTheme.shapes.medium,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = CardDefaults.outlinedCardBorder()
                ) {
                    Column(
                        modifier = Modifier.padding(spacing.md),
                        verticalArrangement = Arrangement.spacedBy(spacing.xxs)
                    ) {
                        Text("Milk Yield", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            text = cow.latestMilkQty?.let { "$it ${cow.latestMilkUnit ?: "L"}" } ?: "No record",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                // Feed Card
                Card(
                    modifier = Modifier.weight(1f),
                    shape = MaterialTheme.shapes.medium,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = CardDefaults.outlinedCardBorder()
                ) {
                    Column(
                        modifier = Modifier.padding(spacing.md),
                        verticalArrangement = Arrangement.spacedBy(spacing.xxs)
                    ) {
                        Text("Feed Intake", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            text = cow.latestFeedQty?.let { "$it kg" } ?: "No record",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }
                }
            }

            // Activity & Steps Tracking Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = CardDefaults.outlinedCardBorder()
            ) {
                Column(
                    modifier = Modifier.padding(spacing.md),
                    verticalArrangement = Arrangement.spacedBy(spacing.sm)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(spacing.sm)
                        ) {
                            Icon(Icons.Default.DirectionsWalk, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Text(
                                text = "Activity & Step Tracking",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        val hasSteps = cow.latestStepsToday != null || cow.latestStepsLifetime != null || cow.latestActivitySteps != null
                        HealthStatusChip(
                            status = if (hasSteps) "Active" else "Offline",
                            statusType = if (hasSteps) HealthStatusType.HEALTHY else HealthStatusType.WARNING
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text("Today's Steps", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                text = (cow.latestStepsToday ?: cow.latestActivitySteps)?.let { "$it steps" } ?: "Not Available",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text("Total Steps", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                text = cow.latestStepsLifetime?.let { "$it steps" } ?: "Not Available",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.secondary
                            )
                        }
                    }
                }
            }

            // Notes
            if (!cow.notes.isNullOrBlank()) {
                SectionHeader(title = "Clinical notes", icon = Icons.Default.Description)
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = CardDefaults.outlinedCardBorder()
                ) {
                    Text(
                        text = cow.notes,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(spacing.md)
                    )
                }
            }

            Spacer(Modifier.height(spacing.lg))
        }
    }
}

// ── COW PROFILE WEIGHT MONITORING SECTION ──
@Composable
fun CowProfileWeightSection(
    cow: CowProfile,
    onOpenWeightScreen: () -> Unit
) {
    val spacing = MaterialTheme.spacing
    val healthColors = MaterialTheme.healthColors

    val weightVal = cow.latestWeight
    val weightDisplay = if (weightVal != null) String.format(Locale.US, "%.1f kg", weightVal) else "Not Available"
    val isStable = cow.latestWeightStable

    SectionHeader(
        title = "Body weight & scale",
        icon = Icons.Default.Scale
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = CardDefaults.outlinedCardBorder()
    ) {
        Column(
            modifier = Modifier
                .padding(spacing.md)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(spacing.sm)
        ) {
            // Header: Scale Tag & Stability Chip
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(spacing.xs)
                ) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Scale,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    Text(
                        text = "HX711 Cattle Scale",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                if (weightVal != null) {
                    val chipBg = if (isStable) healthColors.healthy.copy(alpha = 0.12f) else Orange.copy(alpha = 0.12f)
                    val chipColor = if (isStable) healthColors.healthy else Orange
                    Surface(
                        shape = CircleShape,
                        color = chipBg,
                        border = CardDefaults.outlinedCardBorder().copy(
                            brush = androidx.compose.ui.graphics.SolidColor(chipColor.copy(alpha = 0.3f))
                        )
                    ) {
                        Text(
                            text = if (isStable) "Stable" else "Stabilizing",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Medium,
                            color = chipColor,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                } else {
                    HealthStatusChip(status = "Offline", statusType = HealthStatusType.CRITICAL)
                }
            }

            // Big Weight Typography
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = "Current Body Weight",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = weightDisplay,
                        style = MaterialTheme.typography.headlineMedium.copy(fontFeatureSettings = "tnum"),
                        fontWeight = FontWeight.Bold,
                        color = if (weightVal != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                }

                if (weightVal != null) {
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
                        border = CardDefaults.outlinedCardBorder()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = Green,
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                text = "EEPROM Calibrated",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            )

            // Weight Metadata
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text("Stability Status", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        text = if (weightVal == null) "Sensor Offline" else if (isStable) "Stable Measurement" else "Dynamic / In Motion",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("Last Recorded", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        text = cow.latestTimestamp ?: "Not Available",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Spacer(Modifier.height(spacing.xxs))

            SmartPrimaryButton(
                text = "Open scale & analytics",
                leadingIcon = Icons.Default.Scale,
                onClick = onOpenWeightScreen,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

// ══════════════ DEDICATED WEIGHT MONITORING SCREEN ══════════════
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WeightMonitoringScreen(
    cattleId: String,
    viewModel: CattleViewModel,
    onBack: () -> Unit
) {
    val spacing = MaterialTheme.spacing
    val healthColors = MaterialTheme.healthColors

    val cowProfiles by viewModel.cowProfiles.collectAsState()
    val cow = cowProfiles.find { it.cattleId == cattleId }
    val cowName = cow?.name?.ifEmpty { cattleId } ?: cattleId

    val liveWeight by viewModel.liveWeight.collectAsState()
    val liveStable by viewModel.liveWeightStable.collectAsState()
    val liveCalibrated by viewModel.liveWeightCalibrated.collectAsState()
    val isConnected by viewModel.isConnected.collectAsState()
    val isLoading by viewModel.isWeightActionLoading.collectAsState()
    val weightStats by viewModel.weightStats.collectAsState()
    val weightHistory by viewModel.weightHistory.collectAsState()

    var showCalibrateDialog by remember { mutableStateOf(false) }
    var snackbarMessage by remember { mutableStateOf<String?>(null) }
    var selectedRangeIndex by remember { mutableIntStateOf(0) } // 0: All, 1: 7D, 2: 30D, 3: 90D

    LaunchedEffect(cattleId) {
        viewModel.fetchWeightData(cattleId)
    }

    val filteredHistory = remember(weightHistory, selectedRangeIndex) {
        when (selectedRangeIndex) {
            1 -> weightHistory.take(7)
            2 -> weightHistory.take(30)
            3 -> weightHistory.take(90)
            else -> weightHistory
        }
    }

    val chartDataPoints = remember(filteredHistory) {
        filteredHistory.map { it.weight_kg }.reversed()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Weight Monitoring & Scale",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "$cowName ($cattleId)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.md)
        ) {
            // Notification message
            snackbarMessage?.let { msg ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(spacing.sm),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(msg, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        IconButton(onClick = { snackbarMessage = null }, modifier = Modifier.size(24.dp)) {
                            Icon(Icons.Default.Clear, contentDescription = "Dismiss", modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }

            // Live Scale Gauge Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = CardDefaults.outlinedCardBorder()
            ) {
                Column(
                    modifier = Modifier.padding(spacing.md),
                    verticalArrangement = Arrangement.spacedBy(spacing.sm)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(spacing.xs)
                        ) {
                            Icon(Icons.Default.Scale, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Text(
                                text = "Live scale reading",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        val statusChipType = when {
                            !isConnected -> HealthStatusType.CRITICAL
                            liveWeight == null -> HealthStatusType.WARNING
                            liveStable -> HealthStatusType.HEALTHY
                            else -> HealthStatusType.WARNING
                        }
                        val statusText = when {
                            !isConnected -> "Offline"
                            liveWeight == null -> "Waiting"
                            liveStable -> "Stable"
                            else -> "Stabilizing"
                        }
                        HealthStatusChip(status = statusText, statusType = statusChipType)
                    }

                    // Weight Big Display
                    val displayWeight = if (liveWeight != null && isConnected) String.format(Locale.US, "%.1f", liveWeight) else "Not Available"
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Bottom,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = displayWeight,
                            style = MaterialTheme.typography.displayMedium.copy(fontFeatureSettings = "tnum"),
                            fontWeight = FontWeight.Bold,
                            color = if (liveWeight != null && isConnected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                        if (displayWeight != "Not Available") {
                            Text(
                                text = " kg",
                                style = MaterialTheme.typography.titleLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(bottom = 6.dp)
                            )
                        }
                    }

                    // Hardware scale metadata
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Sensor Hardware", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(if (isConnected) "HX711 Load Cell" else "Disconnected", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                        }
                        Box(Modifier.width(1.dp).height(24.dp).background(MaterialTheme.colorScheme.outlineVariant))
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Calibration Factor", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(if (liveCalibrated) "EEPROM Verified" else "Uncalibrated", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                        }
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    )

                    // Hardware Scale Controls
                    Text("Scale controls", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(spacing.sm)
                    ) {
                        OutlinedButton(
                            onClick = {
                                viewModel.tareScale { ok, msg ->
                                    snackbarMessage = msg ?: if (ok) "Tare success" else "Tare failed"
                                }
                            },
                            modifier = Modifier.weight(1f),
                            enabled = !isLoading && isConnected
                        ) {
                            Icon(Icons.Default.History, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Tare Scale")
                        }

                        OutlinedButton(
                            onClick = { showCalibrateDialog = true },
                            modifier = Modifier.weight(1f),
                            enabled = !isLoading && isConnected
                        ) {
                            Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Calibrate")
                        }
                    }

                    Button(
                        onClick = {
                            val w = liveWeight ?: cow?.latestWeight ?: 0f
                            if (w > 0f) {
                                viewModel.recordWeightMeasurement(cattleId, w) { ok, msg ->
                                    snackbarMessage = msg ?: if (ok) "Weight recorded!" else "Failed"
                                }
                            } else {
                                snackbarMessage = "No valid weight reading to record"
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isLoading && liveWeight != null && liveWeight!! > 0f
                    ) {
                        Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (isLoading) "Recording..." else "Confirm & record weight")
                    }
                }
            }

            // Weight Analytics & Statistics Card
            SectionHeader(title = "Weight analytics", icon = Icons.Default.Analytics)
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = CardDefaults.outlinedCardBorder()
            ) {
                Column(
                    modifier = Modifier.padding(spacing.md),
                    verticalArrangement = Arrangement.spacedBy(spacing.sm)
                ) {
                    val stats = weightStats
                    val prevWeight = stats?.previous_weight_kg
                    val diffKg = stats?.difference_kg
                    val trend = stats?.trend ?: "stable"

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        MiniStatCard("Previous Confirmed", prevWeight?.let { String.format(Locale.US, "%.1f kg", it) } ?: "No record", Icons.Default.History)
                        MiniStatCard(
                            label = "Difference",
                            value = if (diffKg != null) String.format(Locale.US, "%+.1f kg", diffKg) else "0.0 kg",
                            icon = Icons.Default.Timeline
                        )
                        MiniStatCard(
                            label = "Trend",
                            value = trend.uppercase(Locale.US),
                            icon = Icons.Default.Analytics
                        )
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text("7-Day Average", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                text = stats?.average_7d?.let { String.format(Locale.US, "%.1f kg", it) } ?: "Not Available",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text("30-Day Average", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                text = stats?.average_30d?.let { String.format(Locale.US, "%.1f kg", it) } ?: "Not Available",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            // Filter Chips: All, 7D, 30D, 90D
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(spacing.xs)
            ) {
                listOf("All Records", "7 Days", "30 Days", "90 Days").forEachIndexed { index, title ->
                    FilterChip(
                        selected = selectedRangeIndex == index,
                        onClick = { selectedRangeIndex = index },
                        label = { Text(title) }
                    )
                }
            }

            // Weight Trend Chart
            if (chartDataPoints.size >= 2) {
                VitalTrendChart(
                    title = "Weight Trend (Database Verified)",
                    dataPoints = chartDataPoints,
                    unit = "kg",
                    lineColor = MaterialTheme.colorScheme.primary
                )
            } else if (weightHistory.isNotEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                ) {
                    Box(modifier = Modifier.fillMaxWidth().padding(spacing.md), contentAlignment = Alignment.Center) {
                        Text("1 confirmed weight record in database: ${weightHistory.first().weight_kg} kg", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            // Historical Records Table / List
            SectionHeader(title = "Confirmed weight history (${filteredHistory.size})", icon = Icons.Default.History)
            if (filteredHistory.isEmpty()) {
                EmptyStateView(
                    title = "No Confirmed Weight Records",
                    message = "Record weights using the 'Confirm & Record Weight' scale button above.",
                    icon = Icons.Default.Scale
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
                    filteredHistory.forEach { row ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.small,
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            border = CardDefaults.outlinedCardBorder()
                        ) {
                            Row(
                                modifier = Modifier
                                    .padding(spacing.sm)
                                    .fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(spacing.sm)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Scale,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Column {
                                        Text(
                                            text = String.format(Locale.US, "%.1f kg", row.weight_kg),
                                            style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        Text(
                                            text = row.measured_at.take(19).replace("T", " "),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }

                                Surface(
                                    shape = CircleShape,
                                    color = if (row.is_stable) Green.copy(alpha = 0.12f) else Orange.copy(alpha = 0.12f),
                                    border = CardDefaults.outlinedCardBorder().copy(
                                        brush = androidx.compose.ui.graphics.SolidColor(
                                            (if (row.is_stable) Green else Orange).copy(alpha = 0.25f)
                                        )
                                    )
                                ) {
                                    Text(
                                        text = if (row.is_stable) "Stable" else "Unstable",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Medium,
                                        color = if (row.is_stable) Green else Orange,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(spacing.lg))
        }
    }

    // Calibrate Dialog
    if (showCalibrateDialog) {
        var refWeightInput by remember { mutableStateOf("50.0") }
        AlertDialog(
            onDismissRequest = { showCalibrateDialog = false },
            title = { Text("Physical Scale Calibration") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
                    Text(
                        "1. Ensure platform is tared (unloaded).\n" +
                        "2. Place a known reference test weight on scale.\n" +
                        "3. Enter the exact reference weight below and confirm.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedTextField(
                        value = refWeightInput,
                        onValueChange = { refWeightInput = it },
                        label = { Text("Reference Weight (kg)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val ref = refWeightInput.toFloatOrNull()
                        if (ref != null && ref > 0f) {
                            showCalibrateDialog = false
                            viewModel.calibrateScale(ref) { ok, msg ->
                                snackbarMessage = msg ?: if (ok) "Calibrated with $ref kg!" else "Failed"
                            }
                        }
                    }
                ) {
                    Text("Calibrate")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCalibrateDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun ProfileInfoGrid(cow: CowProfile) {
    val spacing = MaterialTheme.spacing
    val fields = listOf(
        "Breed" to (cow.breed ?: "Not Available"),
        "Age" to (cow.age ?: "Not Available"),
        "Gender" to (cow.gender ?: "Not Available"),
        "DOB" to (cow.dob ?: "Not Available")
    )

    Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
        fields.chunked(2).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(spacing.md)
            ) {
                row.forEach { (label, value) ->
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = label.uppercase(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = value,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun MiniStatCard(label: String, value: String, icon: ImageVector) {
    val spacing = MaterialTheme.spacing
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(spacing.xxs)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ══════════════ DIALOGS ══════════════

@Composable
fun SettingsDialog(
    currentIp: String,
    currentPort: String,
    themeMode: ThemeMode,
    dynamicColor: Boolean,
    onDismiss: () -> Unit,
    onSave: (ip: String, port: String, theme: ThemeMode, dynamic: Boolean) -> Unit
) {
    val spacing = MaterialTheme.spacing
    var ip by remember { mutableStateOf(currentIp) }
    var port by remember { mutableStateOf(currentPort) }
    var selectedTheme by remember { mutableStateOf(themeMode) }
    var dynamic by remember { mutableStateOf(dynamicColor) }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding(),
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier
                    .padding(spacing.lg)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(spacing.md)
            ) {
                // Header
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(spacing.sm)
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = stringResource(R.string.settings_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                }

                Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))

                // Server Uplink Section
                SectionHeader(
                    title = stringResource(R.string.settings_server_title).uppercase(),
                    subtitle = stringResource(R.string.settings_server_desc)
                )

                OutlinedTextField(
                    value = ip,
                    onValueChange = { ip = it },
                    label = { Text(stringResource(R.string.settings_host_ip)) },
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = port,
                    onValueChange = { port = it },
                    label = { Text(stringResource(R.string.settings_port)) },
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(spacing.xs))

                // Appearance Section
                SectionHeader(
                    title = stringResource(R.string.settings_theme_title).uppercase(),
                    subtitle = stringResource(R.string.settings_theme_desc)
                )

                Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
                    listOf(
                        ThemeMode.SYSTEM to stringResource(R.string.theme_system),
                        ThemeMode.LIGHT to stringResource(R.string.theme_light),
                        ThemeMode.DARK to stringResource(R.string.theme_dark)
                    ).forEach { (mode, label) ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(MaterialTheme.shapes.small)
                                .clickable { selectedTheme = mode }
                                .padding(vertical = spacing.xs)
                        ) {
                            RadioButton(
                                selected = selectedTheme == mode,
                                onClick = { selectedTheme = mode }
                            )
                            Spacer(Modifier.width(spacing.xs))
                            Text(
                                text = label,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (selectedTheme == mode) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                }

                // Dynamic Color toggle (Android 12+)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = spacing.xs),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = stringResource(R.string.theme_dynamic),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Switch(
                            checked = dynamic,
                            onCheckedChange = { dynamic = it }
                        )
                    }
                }

                Spacer(Modifier.height(spacing.sm))

                // Action Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(spacing.sm)
                ) {
                    SmartOutlinedButton(
                        text = stringResource(R.string.btn_cancel),
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f)
                    )
                    SmartPrimaryButton(
                        text = stringResource(R.string.btn_connect),
                        onClick = { onSave(ip.trim(), port.trim(), selectedTheme, dynamic) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddCowDialog(
    onDismiss: () -> Unit,
    onSave: (id: String, name: String, breed: String?, age: String?, gender: String?, dob: String?, notes: String?, photoUri: Uri?) -> Unit
) {
    val spacing = MaterialTheme.spacing
    var cattleId by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var breed by remember { mutableStateOf("") }
    var age by remember { mutableStateOf("") }
    var gender by remember { mutableStateOf("Female") }
    var dob by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var photoUri by remember { mutableStateOf<Uri?>(null) }
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> photoUri = uri }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding(),
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier
                    .padding(spacing.lg)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(spacing.sm)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(spacing.sm)
                ) {
                    Icon(
                        imageVector = Icons.Default.Pets,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Text(
                        text = stringResource(R.string.add_new_cow),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                }

                Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))

                OutlinedTextField(
                    value = cattleId,
                    onValueChange = { cattleId = it },
                    label = { Text(stringResource(R.string.field_cow_id)) },
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.field_name)) },
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = breed,
                    onValueChange = { breed = it },
                    label = { Text(stringResource(R.string.field_breed)) },
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = age,
                    onValueChange = { age = it },
                    label = { Text(stringResource(R.string.field_age)) },
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth()
                )

                // Gender Chips
                Text(
                    text = stringResource(R.string.field_gender),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                    FilterChip(
                        selected = gender == "Female",
                        onClick = { gender = "Female" },
                        label = { Text(stringResource(R.string.field_gender_female)) }
                    )
                    FilterChip(
                        selected = gender == "Male",
                        onClick = { gender = "Male" },
                        label = { Text(stringResource(R.string.field_gender_male)) }
                    )
                }

                OutlinedTextField(
                    value = dob,
                    onValueChange = { dob = it },
                    label = { Text(stringResource(R.string.field_dob)) },
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text(stringResource(R.string.field_notes)) },
                    maxLines = 3,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth()
                )

                SmartOutlinedButton(
                    text = if (photoUri != null) stringResource(R.string.photo_selected) else stringResource(R.string.btn_select_photo),
                    onClick = { photoPicker.launch("image/*") },
                    leadingIcon = Icons.Default.PhotoCamera,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(spacing.xs))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(spacing.sm)
                ) {
                    SmartOutlinedButton(
                        text = stringResource(R.string.btn_cancel),
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f)
                    )
                    SmartPrimaryButton(
                        text = stringResource(R.string.add_new_cow),
                        onClick = {
                            if (cattleId.isNotBlank() && name.isNotBlank()) {
                                onSave(cattleId, name, breed.ifBlank { null }, age.ifBlank { null }, gender.ifBlank { null }, dob.ifBlank { null }, notes.ifBlank { null }, photoUri)
                            }
                        },
                        enabled = cattleId.isNotBlank() && name.isNotBlank(),
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditCowDialog(
    cow: CowProfile,
    onDismiss: () -> Unit,
    onSave: (name: String?, breed: String?, age: String?, gender: String?, dob: String?, notes: String?, photoUri: Uri?) -> Unit
) {
    val spacing = MaterialTheme.spacing
    var name by remember { mutableStateOf(cow.name) }
    var breed by remember { mutableStateOf(cow.breed ?: "") }
    var age by remember { mutableStateOf(cow.age ?: "") }
    var gender by remember { mutableStateOf(cow.gender ?: "Female") }
    var dob by remember { mutableStateOf(cow.dob ?: "") }
    var notes by remember { mutableStateOf(cow.notes ?: "") }
    var photoUri by remember { mutableStateOf<Uri?>(null) }
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> photoUri = uri }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding(),
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier
                    .padding(spacing.lg)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(spacing.sm)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(spacing.sm)
                ) {
                    Icon(Icons.Default.Edit, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Column {
                        Text(
                            text = stringResource(R.string.edit_cow_profile),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = cow.cattleId,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.field_name)) },
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = breed,
                    onValueChange = { breed = it },
                    label = { Text(stringResource(R.string.field_breed)) },
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = age,
                    onValueChange = { age = it },
                    label = { Text(stringResource(R.string.field_age)) },
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth()
                )

                Text(
                    text = stringResource(R.string.field_gender),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                    FilterChip(
                        selected = gender == "Female",
                        onClick = { gender = "Female" },
                        label = { Text(stringResource(R.string.field_gender_female)) }
                    )
                    FilterChip(
                        selected = gender == "Male",
                        onClick = { gender = "Male" },
                        label = { Text(stringResource(R.string.field_gender_male)) }
                    )
                }

                OutlinedTextField(
                    value = dob,
                    onValueChange = { dob = it },
                    label = { Text(stringResource(R.string.field_dob)) },
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text(stringResource(R.string.field_notes)) },
                    maxLines = 3,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth()
                )

                SmartOutlinedButton(
                    text = if (photoUri != null) stringResource(R.string.photo_selected) else stringResource(R.string.btn_change_photo),
                    onClick = { photoPicker.launch("image/*") },
                    leadingIcon = Icons.Default.PhotoCamera,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(spacing.xs))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(spacing.sm)
                ) {
                    SmartOutlinedButton(
                        text = stringResource(R.string.btn_cancel),
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f)
                    )
                    SmartPrimaryButton(
                        text = stringResource(R.string.btn_save_changes),
                        onClick = {
                            onSave(name.ifBlank { null }, breed.ifBlank { null }, age.ifBlank { null }, gender.ifBlank { null }, dob.ifBlank { null }, notes.ifBlank { null }, photoUri)
                        },
                        enabled = name.isNotBlank(),
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
fun DeleteConfirmDialog(cowName: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val spacing = MaterialTheme.spacing
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = MaterialTheme.shapes.large,
        icon = {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(36.dp)
            )
        },
        title = {
            Text(
                text = stringResource(R.string.remove_cow_prompt),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Text(
                text = "\"$cowName\" will be removed from active view. Historical sensor telemetry and biometric history are preserved in the backend database.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        confirmButton = {
            SmartDangerButton(
                text = stringResource(R.string.remove_cow),
                onClick = onConfirm
            )
        },
        dismissButton = {
            SmartOutlinedButton(
                text = stringResource(R.string.btn_cancel),
                onClick = onDismiss
            )
        }
    )
}
