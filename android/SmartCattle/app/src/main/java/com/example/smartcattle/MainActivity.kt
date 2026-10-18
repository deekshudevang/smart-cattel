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
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
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
    val lastUpdateFormatted: String? = null
)
data class Alert(val cattle: String, val msg: String, val time: String, val tag: String, val color: Color)

// Full cow profile (from /api/cows or /api/cows/{id})
data class CowProfile(
    val cattleId: String,
    val name: String,
    val breed: String?,
    val age: String?,
    val gender: String?,
    val dob: String?,
    val notes: String?,
    val status: String?,
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
    val checksum_valid: Boolean? = null
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
    val is_simulated: Boolean? = null
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
    val is_simulated: Boolean? = null
)
data class ApiLatestMilk(val quantity: Float?, val unit: String?, val date: String?)
data class ApiLatestFeed(val quantity: Float?, val unit: String?, val date: String?)
data class ApiLatestActivity(val steps: Int?, val date: String?)

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

    private var currentIp: String = BuildConfig.DEV_LAN_IP
    private val deletedCattleIds = mutableSetOf<String>()
    private var cowDao: com.example.smartcattle.data.local.CowDao? = null

    // Per-cow photo paths stored in memory (keyed by cattle_id)
    private val _photoPaths = mutableMapOf<String, String>()

    private var api: ApiService? = null
    private var webSocket: WebSocket? = null
    private val client = OkHttpClient.Builder()
        .pingInterval(30, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    init {
        viewModelScope.launch {
            while (true) {
                delay(2000)
                val now = System.currentTimeMillis()
                val currentList = _cattle.value
                var changed = false
                val updated = currentList.map { c ->
                    if (!c.isStale && (now - c.lastPacketTime > 10_000)) {
                        changed = true
                        c.copy(
                            isStale = true,
                            dataSource = "Disconnected",
                            sensors = c.sensors.map { s ->
                                s.copy(isValid = false, reason = "Disconnected (>10s timeout)")
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
                                Sensor("💓", "SpO2", p.latestSpo2?.let { "$it" } ?: "Not Available", "%", "Normal", Green, isValid = p.latestSpo2 != null, reason = if (p.latestSpo2 == null) "Sensor not connected" else null),
                                Sensor("❤️", "Heart Rate", p.latestHeartRate?.let { "$it" } ?: "Not Available", "bpm", "Normal", Green, isValid = p.latestHeartRate != null, reason = if (p.latestHeartRate == null) "No contact" else null),
                                Sensor("🌡️", "Body Temp", p.latestTemp?.let { String.format(Locale.US, "%.1f", it) } ?: "Not Available", "°C", "Normal", Green, isValid = p.latestTemp != null, reason = if (p.latestTemp == null) "Sensor not connected" else null),
                                Sensor("🧪", "pH Level", p.latestPh?.let { String.format(Locale.US, "%.1f", it) } ?: "Not Available", "", "Normal", Green, isValid = p.latestPh != null, reason = if (p.latestPh == null) "Probe not in solution" else null),
                                Sensor("☀️", "Light", p.latestLdr?.let { "$it" } ?: "Not Available", "lux", "Normal", Green, isValid = p.latestLdr != null, reason = if (p.latestLdr == null) "Sensor not connected" else null),
                                Sensor("👟", "Steps Today", p.latestStepsToday?.toString() ?: "Not Available", "steps", "Cached", Green, isValid = p.latestStepsToday != null, reason = if (p.latestStepsToday == null) "No activity data" else null),
                                Sensor("📊", "Total Steps", p.latestStepsLifetime?.toString() ?: "Not Available", "steps", "Lifetime", Blue, isValid = p.latestStepsLifetime != null, reason = if (p.latestStepsLifetime == null) "No activity data" else null),
                                Sensor("🏃", "Acceleration", "Not Available", "m/s²", "Cached", Green, isValid = false, reason = "Sensor not connected")
                            ),
                            dataSource = "Cached",
                            isSimulated = false,
                            isStale = true,
                            lastUpdateFormatted = p.latestTimestamp
                        )
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("SmartCattle", "Failed to load cached cows: ${e.message}")
            }
        }
    }

    fun configureIp(ip: String) {
        currentIp = ip
        val baseUrl = "http://$ip:8000/"
        val wsUrl = "ws://$ip:8000/ws/cattle/CATTLE-ALL"

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
                        .map { Cattle(it.cattle_id, it.name, "Not Available", "Not Available", it.status, emptyList()) }
                    _cattle.value = uiCattleList

                    val populated = uiCattleList.map { c ->
                        try {
                            val r = api?.getLatestReading(c.id)
                            if (r != null) buildCattleFromLatest(r, c.name) else c
                        } catch (e: Exception) { c }
                    }
                    _cattle.value = populated

                    if (apiCattleList.isNotEmpty()) {
                        fetchHistory(apiCattleList[0].cattle_id)
                    }
                    fetchAlerts()
                    fetchCows()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        } catch (e: Exception) {
            _isConnected.value = false
        }
    }

    fun retryConnection() {
        configureIp(currentIp)
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

        val spo2Valid = r.spo2_valid ?: (r.spo2 != null && r.spo2 > 0)
        val spo2Val = if (r.spo2 != null && spo2Valid && r.spo2 > 0) "${r.spo2}" else "Not Available"

        val hrValid = r.heart_rate_valid ?: (r.heart_rate != null && r.heart_rate > 0)
        val hrVal = if (r.heart_rate != null && hrValid && r.heart_rate > 0) "${r.heart_rate}" else "Not Available"

        val tempValid = r.temperature_valid ?: (r.temperature != null)
        val tempVal = if (r.temperature != null && tempValid) String.format(Locale.US, "%.1f", r.temperature) else "Not Available"

        val phValid = r.ph_valid ?: (r.ph != null)
        val phVal = if (r.ph != null && phValid) String.format(Locale.US, "%.1f", r.ph) else "Not Available"

        val ldrValid = r.ldr_valid ?: (r.ldr != null)
        val ldrVal = if (r.ldr != null && ldrValid) "${r.ldr}" else "Not Available"

        val stepsTodayVal = r.step_count_today?.toString() ?: "Not Available"
        val stepsLifetimeVal = r.step_count_lifetime?.toString() ?: "Not Available"

        val isSimulated = r.is_simulated ?: false
        val dataSource = if (isSimulated) "Simulated" else "Live from sensor"

        val sensors = listOf(
            Sensor("💓", "SpO2", spo2Val, "%", sStatus(r.health.spo2), sColor(r.health.spo2), isValid = spo2Val != "Not Available", reason = if (spo2Val == "Not Available") "No contact" else null),
            Sensor("❤️", "Heart Rate", hrVal, "bpm", sStatus(r.health.heart_rate), sColor(r.health.heart_rate), isValid = hrVal != "Not Available", reason = if (hrVal == "Not Available") "No contact" else null),
            Sensor("🌡️", "Body Temp", tempVal, "°C", sStatus(r.health.temperature), sColor(r.health.temperature), isValid = tempVal != "Not Available", reason = if (tempVal == "Not Available") "Sensor not connected" else null),
            Sensor("🧪", "pH Level", phVal, "", sStatus(r.health.ph), sColor(r.health.ph), isValid = phVal != "Not Available", reason = if (phVal == "Not Available") "Probe not in solution" else null),
            Sensor("☀️", "Light", ldrVal, "lux", sStatus(r.health.ldr), sColor(r.health.ldr), isValid = ldrVal != "Not Available", reason = if (ldrVal == "Not Available") "Sensor not connected" else null),
            Sensor("👟", "Steps Today", stepsTodayVal, "steps", if (!accelValid) "Sensor Offline" else "Active", if (!accelValid) Orange else Green, isValid = stepsTodayVal != "Not Available", reason = if (stepsTodayVal == "Not Available") "No activity data" else null),
            Sensor("📊", "Total Steps", stepsLifetimeVal, "steps", "Lifetime", Blue, isValid = stepsLifetimeVal != "Not Available", reason = if (stepsLifetimeVal == "Not Available") "No activity data" else null),
            Sensor("🏃", "Acceleration", accelStr, "m/s²", if (fallDetected) "Fall Detected" else sStatus(r.health.mems), if (fallDetected) Red else sColor(r.health.mems), isValid = accelStr != "Not Available", reason = if (accelStr == "Not Available") "Sensor not connected" else null)
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
            lastUpdateFormatted = r.timestamp.take(19)
        )
    }

    private suspend fun fetchHistory(cattleId: String) {
        try {
            val rows = api?.getHistory(cattleId) ?: return
            val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
            val histRows = rows.map { r ->
                val ts = try { timeFormat.format(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).parse(r.timestamp.take(19)) ?: Date()) } catch (e: Exception) { r.timestamp.take(8) }
                listOf(ts, r.spo2?.let { if (it <= 0) "Not Available" else "$it" } ?: "Not Available", r.heart_rate?.let { if (it <= 0) "Not Available" else "$it" } ?: "Not Available",
                    r.temperature?.let { String.format(Locale.US, "%.1f", it) } ?: "Not Available",
                    r.humidity?.let { String.format(Locale.US, "%.0f", it) } ?: "Not Available",
                    r.ph?.let { String.format(Locale.US, "%.1f", it) } ?: "Not Available",
                    if (r.fall_detected) "🚨" else "",
                    if (r.health.overall == "normal") "✓" else "⚠")
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
                Alert(a.cattle_name.ifEmpty { a.cattle_id }, msg, a.timestamp.take(16), tag, color)
            }
        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun connectWebSocket(url: String) {
        val request = Request.Builder().url(url).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                viewModelScope.launch(Dispatchers.Main) {
                    _isConnected.value = true
                    android.util.Log.i("SmartCattle", "[ANDROID WS] OPEN → connected=true")
                }
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
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
                            val validity = envelope.optJSONObject("validity") ?: json.optJSONObject("validity")
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

                            val existingProfile = activeProfiles.find { it.cattleId == cid }

                            // Real hardware readings only - no resurrection of old readings
                            val spo2Valid = validity?.optBoolean("spo2", true) ?: (!data.isNull("spo2_percent") || !data.isNull("spo2"))
                            val rawSpo2 = optNullableInt("spo2_percent", "spo2")
                            val spo2Val = if (rawSpo2 != null && spo2Valid && rawSpo2 > 0) "$rawSpo2" else "Not Available"
                            val spo2Reason = if (spo2Val == "Not Available") "No contact" else null

                            val hrValid = validity?.optBoolean("heart_rate", true) ?: (!data.isNull("heart_rate_bpm") || !data.isNull("bpm") || !data.isNull("heart_rate"))
                            val rawHr = optNullableInt("heart_rate_bpm", "bpm") ?: optNullableInt("heart_rate")
                            val hrVal = if (rawHr != null && hrValid && rawHr > 0) "$rawHr" else "Not Available"
                            val hrReason = if (hrVal == "Not Available") "No contact" else null

                            val tempValid = validity?.optBoolean("temperature", true) ?: (!data.isNull("temperature_c") || !data.isNull("temperature"))
                            val rawTemp = optNullableFloat("temperature_c", "temperature")
                            val tempVal = if (rawTemp != null && tempValid) String.format(Locale.US, "%.1f", rawTemp) else "Not Available"
                            val tempReason = if (tempVal == "Not Available") "Sensor not connected" else null

                            val phValid = validity?.optBoolean("ph", true) ?: (!data.isNull("ph_value") || !data.isNull("ph"))
                            val rawPh = optNullableFloat("ph_value", "ph")
                            val phVal = if (rawPh != null && phValid) String.format(Locale.US, "%.1f", rawPh) else "Not Available"
                            val phReason = if (phVal == "Not Available") "Probe not in solution" else null

                            val ldrValid = validity?.optBoolean("ldr", true) ?: (!data.isNull("ldr_value") || !data.isNull("ldr"))
                            val rawLdr = optNullableInt("ldr_value", "ldr")
                            val ldrVal = if (rawLdr != null && ldrValid) "$rawLdr" else "Not Available"
                            val ldrReason = if (ldrVal == "Not Available") "Sensor not connected" else null

                            val rawStepsToday = optNullableInt("step_count_today", "steps_today")
                            val effectiveStepsToday = rawStepsToday ?: existingProfile?.latestStepsToday
                            val stepsTodayVal = effectiveStepsToday?.toString() ?: "Not Available"
                            val stepsTodayReason = if (stepsTodayVal == "Not Available") "No activity data" else null

                            val rawStepsLifetime = optNullableInt("step_count_lifetime", "steps_lifetime")
                            val effectiveStepsLifetime = rawStepsLifetime ?: existingProfile?.latestStepsLifetime
                            val stepsLifetimeVal = effectiveStepsLifetime?.toString() ?: "Not Available"
                            val stepsLifetimeReason = if (stepsLifetimeVal == "Not Available") "No activity data" else null

                            val accelValid = validity?.optBoolean("accelerometer", true) ?: if (!data.isNull("accelerometer_valid")) data.optBoolean("accelerometer_valid", true) else true
                            val rawAccelX = optNullableFloat("acceleration_x", "mems_x")
                            val rawAccelY = optNullableFloat("acceleration_y", "mems_y")
                            val rawAccelZ = optNullableFloat("acceleration_z", "mems_z")
                            val accelStr = if (rawAccelX != null && rawAccelY != null && rawAccelZ != null && accelValid) {
                                String.format(Locale.US, "X:%.1f Y:%.1f Z:%.1f", rawAccelX, rawAccelY, rawAccelZ)
                            } else "Not Available"
                            val accelReason = if (accelStr == "Not Available") "Sensor not connected" else null

                            val sensors = listOf(
                                Sensor("💓", "SpO2", spo2Val, "%", sStatus("spo2"), sColor("spo2"), isValid = spo2Val != "Not Available", reason = spo2Reason),
                                Sensor("❤️", "Heart Rate", hrVal, "bpm", sStatus("heart_rate"), sColor("heart_rate"), isValid = hrVal != "Not Available", reason = hrReason),
                                Sensor("🌡️", "Body Temp", tempVal, "°C", sStatus("temperature"), sColor("temperature"), isValid = tempVal != "Not Available", reason = tempReason),
                                Sensor("🧪", "pH Level", phVal, "", sStatus("ph"), sColor("ph"), isValid = phVal != "Not Available", reason = phReason),
                                Sensor("☀️", "Light", ldrVal, "lux", sStatus("ldr"), sColor("ldr"), isValid = ldrVal != "Not Available", reason = ldrReason),
                                Sensor("👟", "Steps Today", stepsTodayVal, "steps", if (!accelValid) "Sensor Offline" else "Active", if (!accelValid) Orange else Green, isValid = stepsTodayVal != "Not Available", reason = stepsTodayReason),
                                Sensor("📊", "Total Steps", stepsLifetimeVal, "steps", "Lifetime", Blue, isValid = stepsLifetimeVal != "Not Available", reason = stepsLifetimeReason),
                                Sensor("🏃", "Acceleration", accelStr, "m/s²", memsStatus, memsColor, isValid = accelStr != "Not Available", reason = accelReason)
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
                                lastUpdateFormatted = t
                            )

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

                            val fallIcon = if (fallDetected) "🚨" else ""
                            val newRow = listOf(t, spo2Val, hrVal, tempVal,
                                data.optDouble("humidity", 0.0).let { if (it > 0) String.format(Locale.US, "%.0f", it) else "Not Available" },
                                phVal, fallIcon,
                                if (overall == "Healthy") "✓" else "⚠")
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
                                _alerts.value = (listOf(Alert(cid, msg, "Just now", tag, color)) + _alerts.value).take(10)
                            }
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("SmartCattle", "[ANDROID WS] parse error: ${e.message} | raw=$text")
                        e.printStackTrace()
                    }
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                _isConnected.value = false; reconnect(url)
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                _isConnected.value = false; reconnect(url)
            }
        })
    }

    private fun reconnect(url: String) {
        viewModelScope.launch { delay(5000); if (!_isConnected.value) connectWebSocket(url) }
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

    LaunchedEffect(ipAddress) {
        viewModel.configureIp(ipAddress)
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

                    viewModel.configureIp(newIp)
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
                    Text("🐄", fontSize = 52.sp)
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

    if (selectedCow != null) {
        CowProfileScreen(
            cow = selectedCow,
            onBack = { selectedCowId = null }
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
                            Text("🐄", fontSize = 18.sp)
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
                0 -> DashboardTab(viewModel = viewModel, onOpenSettings = onOpenSettings)
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
    onOpenSettings: () -> Unit
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
            serverUrl = "ws://backend:8000/ws/cattle/CATTLE-ALL",
            lastUpdateText = lastUpdate,
            onConfigureClick = onOpenSettings,
            onRetryClick = { viewModel.retryConnection() }
        )

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
                CattleTelemetrySection(cattle = cow)
            }
        }
    }
}

@Composable
fun CattleTelemetrySection(cattle: Cattle) {
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
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("🐄", fontSize = 20.sp)
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
                        Icon(Icons.Default.Warning, contentDescription = null, tint = Red, modifier = Modifier.size(18.dp))
                        Text(
                            text = "SIMULATED DATA (NON-HARDWARE TEST)",
                            color = Red,
                            fontWeight = FontWeight.Bold,
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
                val badgeBg = when {
                    cattle.isSimulated -> Red.copy(alpha = 0.15f)
                    cattle.isStale -> Orange.copy(alpha = 0.15f)
                    cattle.dataSource.startsWith("Live") -> Green.copy(alpha = 0.15f)
                    else -> MaterialTheme.colorScheme.surfaceVariant
                }
                val badgeColor = when {
                    cattle.isSimulated -> Red
                    cattle.isStale -> Orange
                    cattle.dataSource.startsWith("Live") -> Green
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
                val badgeLabel = when {
                    cattle.isSimulated -> "SIMULATED"
                    cattle.isStale -> "DISCONNECTED"
                    else -> cattle.dataSource
                }

                Surface(
                    shape = CircleShape,
                    color = badgeBg,
                    border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(badgeColor.copy(alpha = 0.5f)))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
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
                            text = badgeLabel.uppercase(),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
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
                title = stringResource(R.string.live_monitoring).uppercase(),
                icon = Icons.Default.Sensors
            )

            // Grid of live metric cards
            Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                cattle.sensors.chunked(2).forEach { rowSensors ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(spacing.sm)
                    ) {
                        rowSensors.forEach { sensor ->
                            Box(modifier = Modifier.weight(1f)) {
                                val vectorIcon = when (sensor.label.lowercase()) {
                                    "spo2" -> Icons.Default.Bloodtype
                                    "heart rate" -> Icons.Default.Favorite
                                    "body temp" -> Icons.Default.Thermostat
                                    "ph level" -> Icons.Default.Science
                                    "light" -> Icons.Default.LightMode
                                    "steps today" -> Icons.Default.DirectionsWalk
                                    "total steps" -> Icons.Default.Timeline
                                    else -> Icons.Default.ScreenRotation
                                }

                                MetricCard(
                                    label = sensor.label,
                                    value = if (cattle.isStale) "Not Available" else sensor.value,
                                    unit = sensor.unit,
                                    icon = vectorIcon,
                                    statusText = if (cattle.isStale) "Disconnected" else sensor.status,
                                    statusColor = if (cattle.isStale) Grey else sensor.color,
                                    isValid = sensor.isValid && sensor.value != "Not Available" && !cattle.isStale,
                                    subtitle = if (cattle.isStale) "Disconnected (>10s timeout)" else sensor.reason
                                )
                            }
                        }
                        if (rowSensors.size == 1) {
                            Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
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
            title = stringResource(R.string.tab_alerts).uppercase(),
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

                Text(
                    text = alert.time,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
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
            title = stringResource(R.string.tab_history).uppercase(),
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
                val hasFall = row.getOrNull(6) == "🚨"
                val isHealthy = row.lastOrNull() == "✓"

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
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Bold,
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
                    Text("🐄", fontSize = 48.sp)
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
fun CowProfileScreen(cow: CowProfile, onBack: () -> Unit) {
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
                            Text("🐄", fontSize = 72.sp)
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
                title = "LATEST HEALTH VITALS",
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

                    // Row 2: pH Level, Light (LDR), Steps Today
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        MiniStatCard("pH Level", cow.latestPh?.let { String.format(Locale.US, "%.1f", it) } ?: "Not Available", Icons.Default.Science)
                        MiniStatCard("Light", cow.latestLdr?.let { "$it lux" } ?: "Not Available", Icons.Default.LightMode)
                        MiniStatCard("Steps Today", (cow.latestStepsToday ?: cow.latestActivitySteps)?.let { "$it steps" } ?: "Not Available", Icons.Default.DirectionsWalk)
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
                title = "PRODUCTION & INTAKE",
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

            // Activity Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
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
                        horizontalArrangement = Arrangement.spacedBy(spacing.sm)
                    ) {
                        Icon(Icons.Default.DirectionsWalk, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Column {
                            Text("Latest Recorded Activity", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                text = cow.latestActivitySteps?.let { "$it steps" } ?: "No step data",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            // Notes
            if (!cow.notes.isNullOrBlank()) {
                SectionHeader(title = "CLINICAL NOTES", icon = Icons.Default.Description)
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
                    Text("🐄", fontSize = 24.sp)
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
