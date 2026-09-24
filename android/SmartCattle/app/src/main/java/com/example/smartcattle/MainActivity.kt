package com.example.smartcattle

import android.content.Context
import android.net.Uri
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
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

// ── Color System ──
val BgDark = Color(0xFF080E1A)
val BgCard = Color(0xFF111B2E)
val BgCardHover = Color(0xFF162236)
val BgElevated = Color(0xFF1C2A42)
val Green = Color(0xFF00E676)
val Red = Color(0xFFFF5252)
val Orange = Color(0xFFFFAB40)
val Blue = Color(0xFF448AFF)
val Cyan = Color(0xFF18FFFF)
val White = Color(0xFFF1F5F9)
val Grey = Color(0xFF8899AA)
val Muted = Color(0xFF4A5A6A)
val Border = Color(0xFF1E3050)

// ── Domain Models ──
data class Sensor(val icon: String, val label: String, val value: String, val unit: String, val status: String, val color: Color)
data class Cattle(val id: String, val name: String, val breed: String, val age: String, val overall: String, val sensors: List<Sensor>)
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
    val latestBpm: Int? = null,
    val latestTemp: Float? = null,
    val overallStatus: String? = null,
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
data class ApiHealth(val spo2: String, val bpm: String, val temperature: String, val mems: String, val ph: String, val ldr: String, val overall: String)
data class ApiLatest(
    val cattle_id: String, val timestamp: String,
    val spo2: Int?, val bpm: Int?, val temperature: Float?,
    val humidity: Float?, val mems_x: Float?, val mems_y: Float?, val mems_z: Float?,
    val ph: Float?, val ldr: Int?,
    val fall_detected: Boolean, val health: ApiHealth
)
data class ApiHistoryRow(
    val timestamp: String,
    val spo2: Int?, val bpm: Int?, val temperature: Float?,
    val humidity: Float?, val mems_x: Float?, val mems_y: Float?, val mems_z: Float?,
    val ph: Float?, val ldr: Int?,
    val fall_detected: Boolean, val health: ApiHealth
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
data class ApiLatestHealth(val timestamp: String?, val spo2: Int?, val bpm: Int?, val temperature: Float?, val overall_status: String?)
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

    // Cows CRUD state
    private val _cowProfiles = MutableStateFlow<List<CowProfile>>(emptyList())
    val cowProfiles: StateFlow<List<CowProfile>> = _cowProfiles.asStateFlow()

    private val _cowError = MutableStateFlow<String?>(null)
    val cowError: StateFlow<String?> = _cowError.asStateFlow()

    // Per-cow photo paths stored in memory (keyed by cattle_id)
    private val _photoPaths = mutableMapOf<String, String>()

    private var api: ApiService? = null
    private var webSocket: WebSocket? = null
    private val client = OkHttpClient.Builder()
        .pingInterval(30, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    fun configureIp(ip: String) {
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
                    val uiCattleList = apiCattleList.map { Cattle(it.cattle_id, it.name, "Gir", "4 yrs", it.status, emptyList()) }
                    _cattle.value = uiCattleList

                    val populated = uiCattleList.map { c ->
                        try {
                            val r = api?.getLatestReading(c.id)
                            if (r != null) buildCattleFromLatest(r) else c
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

    // ── Cows CRUD ──

    fun fetchCows() {
        viewModelScope.launch {
            try {
                val list = api?.getCows() ?: return@launch
                _cowProfiles.value = list.map { it.toCowProfile(_photoPaths[it.cattle_id]) }
                _cowError.value = null
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
                    // Save photo
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
        viewModelScope.launch {
            try {
                api?.deleteCow(cattleId)
                _photoPaths.remove(cattleId)
                fetchCows()
                onDone(true, null)
            } catch (e: Exception) {
                onDone(false, e.message)
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

    private fun buildCattleFromLatest(r: ApiLatest): Cattle {
        fun sStatus(s: String) = s.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.US) else it.toString() }
        fun sColor(s: String): Color = if (s == "abnormal") Red else Green
        val fallDetected = r.fall_detected || r.health.mems == "abnormal"
        val sensors = listOf(
            Sensor("💓", "SpO2", r.spo2?.toString() ?: "--", "%", sStatus(r.health.spo2), sColor(r.health.spo2)),
            Sensor("❤️", "Heart Rate", r.bpm?.toString() ?: "--", "bpm", sStatus(r.health.bpm), sColor(r.health.bpm)),
            Sensor("🌡️", "Body Temp", r.temperature?.let { String.format(Locale.US, "%.1f", it) } ?: "--", "°C", sStatus(r.health.temperature), sColor(r.health.temperature)),
            Sensor("🧪", "pH Level", r.ph?.let { String.format(Locale.US, "%.1f", it) } ?: "--", "", sStatus(r.health.ph), sColor(r.health.ph)),
            Sensor("☀️", "Light", r.ldr?.toString() ?: "--", "lux", sStatus(r.health.ldr), sColor(r.health.ldr)),
            Sensor("🏃", "Motion", r.mems_x?.let { String.format(Locale.US, "%.2f", it) } ?: "--", "g", if (fallDetected) "Fall Detected" else sStatus(r.health.mems), if (fallDetected) Red else sColor(r.health.mems))
        )
        val overall = if (r.health.overall == "abnormal") "At Risk" else "Healthy"
        return Cattle(r.cattle_id, r.cattle_id, "Gir", "4 yrs", overall, sensors)
    }

    private suspend fun fetchHistory(cattleId: String) {
        try {
            val rows = api?.getHistory(cattleId) ?: return
            val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
            val histRows = rows.map { r ->
                val ts = try { timeFormat.format(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).parse(r.timestamp.take(19)) ?: Date()) } catch (e: Exception) { r.timestamp.take(8) }
                listOf(ts, r.spo2?.toString() ?: "--", r.bpm?.toString() ?: "--",
                    r.temperature?.let { String.format(Locale.US, "%.1f", it) } ?: "--",
                    r.humidity?.let { String.format(Locale.US, "%.0f", it) } ?: "--",
                    r.ph?.let { String.format(Locale.US, "%.1f", it) } ?: "--",
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
            override fun onOpen(webSocket: WebSocket, response: Response) { _isConnected.value = true }

            override fun onMessage(webSocket: WebSocket, text: String) {
                viewModelScope.launch(Dispatchers.Main) {
                    try {
                        val json = JSONObject(text)
                        if (json.getString("type") == "sensor_update") {
                            val cid = json.getString("cattle_id")
                            val data = json.getJSONObject("data")
                            val health = json.getJSONObject("health")
                            val overallStatus = health.optString("overall", "normal")
                            val fallDetected = health.optBoolean("fall_detected", false)

                            fun sStatus(key: String): String {
                                val raw = health.optJSONObject(key)?.optString("status", "normal") ?: "normal"
                                return raw.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.US) else it.toString() }
                            }
                            fun sColor(key: String): Color = if (health.optJSONObject(key)?.optString("status", "normal") == "abnormal") Red else Green

                            val memsStatus = if (fallDetected) "Fall Detected" else sStatus("mems")
                            val memsColor = if (fallDetected || health.optJSONObject("mems")?.optString("status") == "abnormal") Red else Green

                            fun sVal(key: String, format: String? = null): String {
                                if (data.isNull(key)) return "--"
                                val raw = data.opt(key) ?: return "--"
                                return if (format != null) {
                                    try { String.format(Locale.US, format, (raw as Number).toDouble()) }
                                    catch (e: Exception) { raw.toString() }
                                } else raw.toString()
                            }

                            val sensors = listOf(
                                Sensor("💓", "SpO2", sVal("spo2"), "%", sStatus("spo2"), sColor("spo2")),
                                Sensor("❤️", "Heart Rate", sVal("bpm"), "bpm", sStatus("bpm"), sColor("bpm")),
                                Sensor("🌡️", "Body Temp", sVal("temperature", "%.1f"), "°C", sStatus("temperature"), sColor("temperature")),
                                Sensor("🧪", "pH Level", sVal("ph", "%.1f"), "", sStatus("ph"), sColor("ph")),
                                Sensor("☀️", "Light", sVal("ldr"), "lux", sStatus("ldr"), sColor("ldr")),
                                Sensor("🏃", "Motion", sVal("mems_x", "%.2f"), "g", memsStatus, memsColor)
                            )
                            val overall = if (overallStatus == "abnormal") "At Risk" else "Healthy"
                            val currentCattle = _cattle.value.toMutableList()
                            val idx = currentCattle.indexOfFirst { it.id == cid }
                            if (idx != -1) {
                                currentCattle[idx] = currentCattle[idx].copy(overall = overall, sensors = sensors)
                                _cattle.value = currentCattle
                            }

                            val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                            val t = timeFormat.format(Date())
                            val fallIcon = if (fallDetected) "🚨" else ""
                            val newRow = listOf(t, sVal("spo2"), sVal("bpm"), sVal("temperature", "%.1f"),
                                sVal("humidity", "%.0f"), sVal("ph", "%.1f"), fallIcon,
                                if (overall == "Healthy") "✓" else "⚠")
                            _history.value = (_history.value + listOf(newRow)).takeLast(20)

                            if (overall == "At Risk") {
                                val alertDetails = buildString {
                                    if (health.optJSONObject("spo2")?.optString("status") == "abnormal") append("SpO2 abnormal (${sVal("spo2")}%) • ")
                                    if (health.optJSONObject("bpm")?.optString("status") == "abnormal") append("BPM abnormal (${sVal("bpm")}) • ")
                                    if (health.optJSONObject("temperature")?.optString("status") == "abnormal") append("Temp abnormal (${sVal("temperature", "%.1f")}°C) • ")
                                    if (health.optJSONObject("ph")?.optString("status") == "abnormal") append("pH abnormal (${sVal("ph", "%.1f")}) • ")
                                    if (fallDetected) append("Fall Detected! ")
                                }.trimEnd(' ', '•')
                                val msg = alertDetails.ifEmpty { "Abnormal readings for $cid" }
                                val tag = if (fallDetected) "FALL" else "WARNING"
                                val color = if (fallDetected) Red else Orange
                                _alerts.value = (listOf(Alert(cid, msg, "Just now", tag, color)) + _alerts.value).take(10)
                            }
                        }
                    } catch (e: Exception) { e.printStackTrace() }
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { _isConnected.value = false; reconnect(url) }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { _isConnected.value = false; reconnect(url) }
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
    latestBpm = latest_health?.bpm,
    latestTemp = latest_health?.temperature,
    overallStatus = latest_health?.overall_status,
    latestMilkQty = latest_milk?.quantity,
    latestMilkUnit = latest_milk?.unit,
    latestFeedQty = latest_feed?.quantity,
    latestActivitySteps = latest_activity?.steps,
    activeAlertsCount = active_alerts_count ?: 0,
    photoPath = photoPath
)

// ══════════════ APP ══════════════

@Composable
fun SmartCattleApp() {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("smart_cattle_prefs", Context.MODE_PRIVATE)
    var showSplash by remember { mutableStateOf(true) }
    var showIpDialog by remember { mutableStateOf(false) }
    var ipAddress by remember { mutableStateOf(prefs.getString("backend_ip", "192.168.1.3") ?: "192.168.1.3") }
    val viewModel: CattleViewModel = viewModel()

    LaunchedEffect(ipAddress) { viewModel.configureIp(ipAddress) }

    MaterialTheme(colorScheme = darkColorScheme(background = BgDark, surface = BgCard, primary = Green)) {
        if (showSplash) {
            SplashScreen { showSplash = false }
        } else {
            MainScreen(viewModel = viewModel, onOpenSettings = { showIpDialog = true })
        }

        if (showIpDialog) {
            AlertDialog(
                onDismissRequest = { showIpDialog = false },
                shape = RoundedCornerShape(24.dp),
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(36.dp).clip(CircleShape).background(Blue.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) { Text("📡", fontSize = 18.sp) }
                        Spacer(Modifier.width(12.dp))
                        Text("Server Uplink", color = White, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                    }
                },
                text = {
                    Column {
                        Text("Enter the local Wi-Fi IP of the laptop running the FastAPI backend. Find it with `ipconfig`.", color = Grey, fontSize = 13.sp, lineHeight = 18.sp)
                        Spacer(Modifier.height(20.dp))
                        OutlinedTextField(
                            value = ipAddress, onValueChange = { ipAddress = it }, singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            leadingIcon = { Text("🌐", fontSize = 16.sp, modifier = Modifier.padding(start = 12.dp)) },
                            colors = OutlinedTextFieldDefaults.colors(focusedTextColor = White, unfocusedTextColor = White, focusedBorderColor = Green, unfocusedBorderColor = Border, focusedContainerColor = BgDark, unfocusedContainerColor = BgDark),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                confirmButton = {
                    Button(onClick = { prefs.edit().putString("backend_ip", ipAddress).apply(); viewModel.configureIp(ipAddress); showIpDialog = false },
                        colors = ButtonDefaults.buttonColors(containerColor = Green), shape = RoundedCornerShape(12.dp)
                    ) { Text("Connect", color = BgDark, fontWeight = FontWeight.Bold) }
                },
                dismissButton = {
                    OutlinedButton(onClick = { showIpDialog = false },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Grey),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Border), shape = RoundedCornerShape(12.dp)
                    ) { Text("Cancel") }
                },
                containerColor = BgCard, tonalElevation = 8.dp
            )
        }
    }
}

// ══════════════ SPLASH ══════════════

@Composable
fun SplashScreen(onFinished: () -> Unit) {
    var phase by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { delay(400); phase = 1; delay(800); phase = 2; delay(600); phase = 3; delay(500); phase = 4; delay(1200); phase = 5 }
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF0A1628), Color(0xFF061020), Color(0xFF040810)))), contentAlignment = Alignment.Center) {
        if (phase >= 1) Box(Modifier.size(300.dp).alpha(0.15f).background(Brush.radialGradient(listOf(Green, Color.Transparent)), CircleShape))
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            AnimatedVisibility(phase >= 1, enter = scaleIn(tween(600)) + fadeIn(tween(600))) {
                Box(Modifier.size(120.dp).clip(CircleShape).background(Brush.linearGradient(listOf(Color(0xFF0D2137), Color(0xFF142D4A)))).border(2.dp, Green.copy(alpha = 0.5f), CircleShape), contentAlignment = Alignment.Center) { Text("🐄", fontSize = 52.sp) }
            }
            Spacer(Modifier.height(24.dp))
            AnimatedVisibility(phase >= 2, enter = slideInVertically(tween(500)) { 30 } + fadeIn(tween(500))) { Text("Smart Cattle", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = White) }
            AnimatedVisibility(phase >= 3, enter = fadeIn(tween(400))) { Text("Health Monitoring System", fontSize = 14.sp, color = Green, letterSpacing = 3.sp) }
            Spacer(Modifier.height(36.dp))
            AnimatedVisibility(phase >= 4, enter = fadeIn(tween(500))) { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { FeaturePill("IoT Sensors"); FeaturePill("ML Engine"); FeaturePill("Real-time") } }
            Spacer(Modifier.height(48.dp))
            AnimatedVisibility(phase >= 5, enter = scaleIn(tween(400)) + fadeIn(tween(400))) {
                Button(onClick = onFinished, shape = RoundedCornerShape(28.dp), colors = ButtonDefaults.buttonColors(containerColor = Green), contentPadding = PaddingValues(horizontal = 48.dp, vertical = 14.dp)) { Text("Enter Dashboard", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = BgDark) }
            }
            Spacer(Modifier.height(32.dp))
            AnimatedVisibility(phase >= 4, enter = fadeIn(tween(600))) { Text("Powered by Arduino Mega + FastAPI + Scikit-Learn", fontSize = 10.sp, color = Muted) }
        }
    }
}

@Composable
fun FeaturePill(text: String) {
    Box(Modifier.clip(RoundedCornerShape(16.dp)).background(Green.copy(alpha = 0.08f)).border(1.dp, Green.copy(alpha = 0.2f), RoundedCornerShape(16.dp)).padding(horizontal = 14.dp, vertical = 6.dp)) { Text(text, fontSize = 11.sp, color = Green, fontWeight = FontWeight.Medium) }
}

// ══════════════ MAIN SCREEN ══════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: CattleViewModel, onOpenSettings: () -> Unit) {
    var tab by remember { mutableIntStateOf(0) }
    val isConnected by viewModel.isConnected.collectAsState()
    var selectedCow by remember { mutableStateOf<CowProfile?>(null) }

    if (selectedCow != null) {
        CowProfileScreen(cow = selectedCow!!, onBack = { selectedCow = null })
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(32.dp).clip(CircleShape).background(Green.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) { Text("🐄", fontSize = 16.sp) }
                        Spacer(Modifier.width(10.dp))
                        Column { Text("Smart Cattle", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = White); Text("Live Monitoring", fontSize = 10.sp, color = Grey) }
                    }
                },
                actions = {
                    Box(Modifier.padding(end = 12.dp).clip(RoundedCornerShape(12.dp)).background(if (isConnected) Green.copy(alpha = 0.1f) else Red.copy(alpha = 0.1f)).padding(horizontal = 10.dp, vertical = 4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(6.dp).clip(CircleShape).background(if (isConnected) Green else Red))
                            Spacer(Modifier.width(6.dp))
                            Text(if (isConnected) "Online" else "Offline", fontSize = 10.sp, color = if (isConnected) Green else Red, fontWeight = FontWeight.Medium)
                        }
                    }
                    IconButton(onClick = onOpenSettings) { Text("⚙️") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BgDark)
            )
        },
        bottomBar = {
            NavigationBar(containerColor = BgCard, tonalElevation = 0.dp) {
                listOf("Dashboard", "Alerts", "History", "Cows").forEachIndexed { i, title ->
                    val icons = listOf("🏠", "🔔", "📊", "🐄")
                    NavigationBarItem(
                        selected = tab == i, onClick = { tab = i },
                        label = { Text(title, fontSize = 10.sp, color = if (tab == i) Green else Muted) },
                        icon = { Text(icons[i], fontSize = 18.sp) },
                        colors = NavigationBarItemDefaults.colors(indicatorColor = Green.copy(alpha = 0.1f))
                    )
                }
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().background(BgDark).padding(padding)) {
            when (tab) {
                0 -> DashboardTab(viewModel)
                1 -> AlertsTab(viewModel)
                2 -> HistoryTab(viewModel)
                3 -> CowsTab(viewModel, onViewProfile = { selectedCow = it })
            }
        }
    }
}

// ══════════════ DASHBOARD ══════════════

@Composable
fun DashboardTab(viewModel: CattleViewModel) {
    val cattle by viewModel.cattle.collectAsState()
    val avgSpo2 = if (cattle.isNotEmpty()) cattle.mapNotNull { c -> c.sensors.find { it.label == "SpO2" }?.value?.toIntOrNull()?.takeIf { it > 0 } }.average().takeIf { !it.isNaN() }?.toInt()?.toString()?.plus("%") ?: "--" else "--"
    val avgBpm = if (cattle.isNotEmpty()) cattle.mapNotNull { c -> c.sensors.find { it.label == "Heart Rate" }?.value?.toIntOrNull()?.takeIf { it > 0 } }.average().takeIf { !it.isNaN() }?.toInt()?.toString() ?: "--" else "--"
    val avgTemp = if (cattle.isNotEmpty()) cattle.mapNotNull { c -> c.sensors.find { it.label == "Body Temp" }?.value?.toDoubleOrNull()?.takeIf { it > 0.0 } }.average().takeIf { !it.isNaN() }?.let { String.format(java.util.Locale.US, "%.1f°", it) } ?: "--" else "--"
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = Color.Transparent)) {
            Box(Modifier.fillMaxWidth().background(Brush.linearGradient(listOf(Color(0xFF1B5E20), Color(0xFF004D40), Color(0xFF0D47A1)))).padding(20.dp)) {
                Column {
                    Text("🏡 Farm Overview", fontSize = 11.sp, color = Color.White.copy(alpha = 0.7f), letterSpacing = 2.sp)
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                        Column {
                            Text("${cattle.size} Cattle Monitored", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White)
                            Spacer(Modifier.height(4.dp))
                            Row { StatusChip("${cattle.count { it.overall == "Healthy" }} Healthy", Green); Spacer(Modifier.width(6.dp)); StatusChip("${cattle.count { it.overall != "Healthy" }} At Risk", Red) }
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) { Text("6", fontSize = 40.sp, fontWeight = FontWeight.Bold, color = Green); Text("ML Models", fontSize = 10.sp, color = Color.White.copy(alpha = 0.6f)) }
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            VitalCard("💓", "Avg SpO2", avgSpo2, Green, Modifier.weight(1f))
            VitalCard("❤️", "Avg BPM", avgBpm, Blue, Modifier.weight(1f))
            VitalCard("🌡️", "Avg Temp", avgTemp, Orange, Modifier.weight(1f))
        }
        Spacer(Modifier.height(20.dp))
        Row(verticalAlignment = Alignment.CenterVertically) { Box(Modifier.width(3.dp).height(18.dp).clip(RoundedCornerShape(2.dp)).background(Green)); Spacer(Modifier.width(8.dp)); Text("Cattle Status", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = White) }
        Spacer(Modifier.height(10.dp))
        if (cattle.isEmpty()) {
            Text("No data. Check Wi-Fi connection and backend IP in Settings ⚙️.", color = Grey, modifier = Modifier.padding(16.dp))
        } else {
            cattle.forEach { c -> CattleCard(c); Spacer(Modifier.height(12.dp)) }
        }
    }
}

@Composable
fun StatusChip(text: String, color: Color) { Box(Modifier.clip(RoundedCornerShape(8.dp)).background(color.copy(alpha = 0.2f)).padding(horizontal = 8.dp, vertical = 3.dp)) { Text(text, fontSize = 10.sp, color = color, fontWeight = FontWeight.Bold) } }

@Composable
fun VitalCard(icon: String, label: String, value: String, color: Color, modifier: Modifier) { Card(modifier, shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = BgCard)) { Column(Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) { Text(icon, fontSize = 20.sp); Spacer(Modifier.height(6.dp)); Text(value, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = color); Text(label, fontSize = 9.sp, color = Grey) } } }

@Composable
fun CattleCard(c: Cattle) {
    val healthy = c.overall == "Healthy"
    val sColor = if (healthy) Green else Red
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = BgCard)) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(44.dp).clip(CircleShape).background(sColor.copy(alpha = 0.1f)), contentAlignment = Alignment.Center) { Text("🐄", fontSize = 22.sp) }
                    Spacer(Modifier.width(12.dp))
                    Column { Text(c.name, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = White); Text("${c.breed}  •  ${c.age}  •  ${c.id}", fontSize = 10.sp, color = Grey) }
                }
                Box(Modifier.clip(RoundedCornerShape(20.dp)).background(sColor.copy(alpha = 0.12f)).border(1.dp, sColor.copy(alpha = 0.3f), RoundedCornerShape(20.dp)).padding(horizontal = 12.dp, vertical = 5.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) { Box(Modifier.size(7.dp).clip(CircleShape).background(sColor)); Spacer(Modifier.width(5.dp)); Text(c.overall, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = sColor) }
                }
            }
            Spacer(Modifier.height(14.dp)); Divider(color = Border, thickness = 0.5.dp); Spacer(Modifier.height(14.dp))
            c.sensors.chunked(3).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { s -> SensorCell(s, Modifier.weight(1f)) }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
fun SensorCell(s: Sensor, modifier: Modifier) { Card(modifier, shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = BgElevated)) { Column(Modifier.padding(10.dp)) { Row(verticalAlignment = Alignment.CenterVertically) { Text(s.icon, fontSize = 14.sp); Spacer(Modifier.width(4.dp)); Text(s.label, fontSize = 9.sp, color = Grey) }; Spacer(Modifier.height(6.dp)); Row(verticalAlignment = Alignment.Bottom) { Text(s.value, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = White); if (s.unit.isNotEmpty()) { Spacer(Modifier.width(2.dp)); Text(s.unit, fontSize = 10.sp, color = Grey, modifier = Modifier.padding(bottom = 2.dp)) } }; Spacer(Modifier.height(4.dp)); Box(Modifier.clip(RoundedCornerShape(6.dp)).background(s.color.copy(alpha = 0.1f)).padding(horizontal = 6.dp, vertical = 2.dp)) { Text(s.status, fontSize = 8.sp, color = s.color, fontWeight = FontWeight.Bold) } } } }

// ══════════════ ALERTS ══════════════

@Composable
fun AlertsTab(viewModel: CattleViewModel) {
    val alerts by viewModel.alerts.collectAsState()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) { Box(Modifier.width(3.dp).height(18.dp).clip(RoundedCornerShape(2.dp)).background(Green)); Spacer(Modifier.width(8.dp)); Text("Health Alerts", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = White) }
        Text("AI-powered anomaly detection", fontSize = 11.sp, color = Grey)
        Spacer(Modifier.height(14.dp))
        if (alerts.isEmpty()) {
            Text("No active alerts ✓", color = Green, modifier = Modifier.padding(16.dp))
        } else {
            alerts.forEach { a ->
                Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = BgCard)) {
                    Row(Modifier.padding(14.dp)) {
                        Box(Modifier.width(3.dp).height(56.dp).clip(RoundedCornerShape(2.dp)).background(a.color))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(a.cattle, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = White)
                                Box(Modifier.clip(RoundedCornerShape(6.dp)).background(a.color.copy(alpha = 0.12f)).padding(horizontal = 6.dp, vertical = 2.dp)) { Text(a.tag, fontSize = 8.sp, color = a.color, fontWeight = FontWeight.Black) }
                            }
                            Spacer(Modifier.height(4.dp)); Text(a.msg, fontSize = 11.sp, color = Grey, lineHeight = 15.sp); Spacer(Modifier.height(4.dp)); Text(a.time, fontSize = 9.sp, color = Muted)
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

// ══════════════ HISTORY ══════════════

@Composable
fun HistoryTab(viewModel: CattleViewModel) {
    val history by viewModel.history.collectAsState()
    val headers = listOf("Time", "SpO2", "BPM", "Temp", "Hum", "pH", "Fall", "OK")
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) { Box(Modifier.width(3.dp).height(18.dp).clip(RoundedCornerShape(2.dp)).background(Green)); Spacer(Modifier.width(8.dp)); Text("Sensor History", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = White) }
        Text("Readings log (live + persisted)", fontSize = 11.sp, color = Grey)
        Spacer(Modifier.height(14.dp))
        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = BgCard)) {
            Column {
                Row(Modifier.background(Blue.copy(alpha = 0.08f)).padding(horizontal = 8.dp, vertical = 10.dp)) { headers.forEach { Text(it, Modifier.weight(1f), fontSize = 8.sp, fontWeight = FontWeight.Bold, color = Blue, textAlign = TextAlign.Center) } }
                if (history.isEmpty()) Text("No data yet — waiting for live sensor data...", color = Grey, modifier = Modifier.padding(16.dp))
                history.forEachIndexed { i, row ->
                    val hasFall = row.getOrElse(6) { "" } == "🚨"
                    val warn = row.last() == "⚠"
                    Row(Modifier.background(when { hasFall -> Red.copy(alpha = 0.08f); warn -> Orange.copy(alpha = 0.04f); else -> Color.Transparent }).padding(horizontal = 8.dp, vertical = 8.dp)) {
                        row.forEachIndexed { j, cell ->
                            val cellColor = when { j == 6 && hasFall -> Red; j == row.size - 1 && warn -> Orange; j == row.size - 1 -> Green; else -> White }
                            Text(cell, Modifier.weight(1f), fontSize = 10.sp, color = cellColor, textAlign = TextAlign.Center)
                        }
                    }
                    if (i < history.size - 1) Divider(color = Border, thickness = 0.3.dp)
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = Color.Transparent)) { Box(Modifier.fillMaxWidth().background(Brush.linearGradient(listOf(Color(0xFF1A237E), Color(0xFF311B92), Color(0xFF4A148C)))).padding(16.dp)) { Column { Row(verticalAlignment = Alignment.CenterVertically) { Text("🧠", fontSize = 20.sp); Spacer(Modifier.width(8.dp)); Text("ML Analysis Summary", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White) }; Spacer(Modifier.height(10.dp)); Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) { Text("• Random Forest classifier accuracy", fontSize = 10.sp, color = Color.White.copy(alpha = 0.7f)); Text("94.2%", fontSize = 10.sp, color = Cyan, fontWeight = FontWeight.Bold) }; Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) { Text("• Active ML models", fontSize = 10.sp, color = Color.White.copy(alpha = 0.7f)); Text("6 (SpO2, BPM, Temp, MEMS, pH, LDR)", fontSize = 10.sp, color = Cyan, fontWeight = FontWeight.Bold) } } } }
    }
}

// ══════════════ COWS TAB ══════════════

@Composable
fun CowsTab(viewModel: CattleViewModel, onViewProfile: (CowProfile) -> Unit) {
    val cows by viewModel.cowProfiles.collectAsState()
    val error by viewModel.cowError.collectAsState()
    val context = LocalContext.current
    var showAddDialog by remember { mutableStateOf(false) }
    var editTarget by remember { mutableStateOf<CowProfile?>(null) }
    var deleteTarget by remember { mutableStateOf<CowProfile?>(null) }
    var toastMsg by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(toastMsg) {
        if (toastMsg != null) { delay(2500); toastMsg = null }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(3.dp).height(18.dp).clip(RoundedCornerShape(2.dp)).background(Green))
                Spacer(Modifier.width(8.dp))
                Text("Cow Profiles", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = White)
                Spacer(Modifier.weight(1f))
                Text("${cows.size} registered", fontSize = 11.sp, color = Grey)
            }
            Text("Tap a card to view full profile • Long-press to edit/delete", fontSize = 10.sp, color = Muted)
            Spacer(Modifier.height(12.dp))

            if (error != null) {
                Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = Red.copy(alpha = 0.1f))) {
                    Text("⚠ $error", color = Red, modifier = Modifier.padding(12.dp), fontSize = 12.sp)
                }
                Spacer(Modifier.height(8.dp))
            }

            if (cows.isEmpty() && error == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("🐄", fontSize = 56.sp)
                        Spacer(Modifier.height(12.dp))
                        Text("No cows registered yet", color = Grey, fontSize = 14.sp)
                        Text("Tap + to add your first cow", color = Muted, fontSize = 12.sp)
                    }
                }
            } else {
                LazyVerticalGrid(columns = GridCells.Fixed(2), verticalArrangement = Arrangement.spacedBy(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(cows) { cow ->
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

        // FAB
        FloatingActionButton(
            onClick = { showAddDialog = true },
            modifier = Modifier.align(Alignment.BottomEnd).padding(24.dp),
            containerColor = Green, contentColor = BgDark,
            shape = CircleShape
        ) { Text("+", fontSize = 28.sp, fontWeight = FontWeight.Bold) }

        // Toast
        toastMsg?.let { msg ->
            Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 80.dp)) {
                Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = BgElevated)) {
                    Text(msg, color = White, modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp), fontSize = 13.sp)
                }
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
fun CowGridCard(cow: CowProfile, onTap: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
    val healthy = (cow.overallStatus ?: cow.status) != "abnormal"
    val statusColor = if (healthy) Green else Red
    var showMenu by remember { mutableStateOf(false) }

    Card(
        Modifier.fillMaxWidth().clickable { onTap() },
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = BgCard)
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            // Photo area
            Box(
                Modifier.fillMaxWidth().height(120.dp)
                    .clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp))
                    .background(BgElevated),
                contentAlignment = Alignment.Center
            ) {
                if (cow.photoPath != null) {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current).data(File(cow.photoPath)).crossfade(true).build(),
                        contentDescription = "Photo of ${cow.name}",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Text("🐄", fontSize = 48.sp)
                }
                // Status dot
                Box(Modifier.align(Alignment.TopEnd).padding(8.dp).size(10.dp).clip(CircleShape).background(statusColor))

                // Context menu trigger
                Box(Modifier.align(Alignment.TopStart).padding(6.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.4f)).clickable { showMenu = true }.padding(4.dp)) {
                    Text("⋮", fontSize = 14.sp, color = White)
                }
                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }, modifier = Modifier.background(BgElevated)) {
                    DropdownMenuItem(text = { Text("✏️  Edit", color = White, fontSize = 13.sp) }, onClick = { showMenu = false; onEdit() })
                    DropdownMenuItem(text = { Text("🗑️  Delete", color = Red, fontSize = 13.sp) }, onClick = { showMenu = false; onDelete() })
                }
            }

            Column(Modifier.padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(cow.name, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = White, textAlign = TextAlign.Center, maxLines = 1)
                Text(cow.cattleId, fontSize = 9.sp, color = Grey, textAlign = TextAlign.Center)
                Spacer(Modifier.height(4.dp))
                cow.breed?.let { Text(it, fontSize = 10.sp, color = Muted, textAlign = TextAlign.Center) }
                Spacer(Modifier.height(6.dp))
                Box(Modifier.clip(RoundedCornerShape(10.dp)).background(statusColor.copy(alpha = 0.12f)).padding(horizontal = 10.dp, vertical = 3.dp)) {
                    Text(if (healthy) "Healthy" else "At Risk", fontSize = 10.sp, color = statusColor, fontWeight = FontWeight.Bold)
                }
                if (cow.activeAlertsCount > 0) {
                    Spacer(Modifier.height(4.dp))
                    Text("${cow.activeAlertsCount} alert${if (cow.activeAlertsCount > 1) "s" else ""}", fontSize = 9.sp, color = Orange)
                }
            }

            Button(
                onClick = onTap,
                modifier = Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, bottom = 10.dp),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Green.copy(alpha = 0.12f), contentColor = Green),
                contentPadding = PaddingValues(vertical = 6.dp)
            ) { Text("View Profile", fontSize = 11.sp, fontWeight = FontWeight.Bold) }
        }
    }
}

// ══════════════ COW PROFILE SCREEN ══════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CowProfileScreen(cow: CowProfile, onBack: () -> Unit) {
    val healthy = (cow.overallStatus ?: cow.status) != "abnormal"
    val statusColor = if (healthy) Green else Red

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(cow.name, color = White, fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onBack) { Text("←", fontSize = 20.sp, color = Green) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BgDark)
            )
        }
    ) { padding ->
        Column(
            Modifier.fillMaxSize().background(BgDark).padding(padding)
                .verticalScroll(rememberScrollState()).padding(16.dp)
        ) {
            // Hero card
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = BgCard)) {
                Column(Modifier.padding(0.dp)) {
                    // Photo
                    Box(
                        Modifier.fillMaxWidth().height(180.dp)
                            .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                            .background(BgElevated),
                        contentAlignment = Alignment.Center
                    ) {
                        if (cow.photoPath != null) {
                            AsyncImage(
                                model = ImageRequest.Builder(LocalContext.current).data(File(cow.photoPath)).crossfade(true).build(),
                                contentDescription = "Photo of ${cow.name}",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Text("🐄", fontSize = 72.sp)
                        }
                        // Status badge
                        Box(Modifier.align(Alignment.BottomStart).padding(12.dp).clip(RoundedCornerShape(10.dp)).background(statusColor.copy(alpha = 0.9f)).padding(horizontal = 10.dp, vertical = 4.dp)) {
                            Text(if (healthy) "Healthy" else "At Risk", fontSize = 11.sp, color = BgDark, fontWeight = FontWeight.Bold)
                        }
                    }
                    // Info
                    Column(Modifier.padding(16.dp)) {
                        Text(cow.name, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = White)
                        Text(cow.cattleId, fontSize = 12.sp, color = Grey)
                        Spacer(Modifier.height(12.dp))
                        ProfileInfoGrid(cow)
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // Latest Health
            SectionHeader("💓", "Latest Health Readings")
            Spacer(Modifier.height(8.dp))
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = BgCard)) {
                Column(Modifier.padding(16.dp)) {
                    if (cow.latestSpo2 == null && cow.latestBpm == null && cow.latestTemp == null) {
                        Text("No health readings available yet.", color = Muted, fontSize = 13.sp)
                    } else {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            MiniStat("💓", "SpO2", cow.latestSpo2?.let { "$it%" } ?: "—", Green)
                            MiniStat("❤️", "BPM", cow.latestBpm?.toString() ?: "—", Blue)
                            MiniStat("🌡️", "Temp", cow.latestTemp?.let { String.format(Locale.US, "%.1f°C", it) } ?: "—", Orange)
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // Milk
            SectionHeader("🥛", "Milk Production")
            Spacer(Modifier.height(8.dp))
            InfoCard {
                if (cow.latestMilkQty == null) {
                    Text("No milk data recorded yet.", color = Muted, fontSize = 13.sp)
                } else {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Last recorded", color = Grey, fontSize = 13.sp)
                        Text("${cow.latestMilkQty} ${cow.latestMilkUnit ?: "litres"}", color = Cyan, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // Feed
            SectionHeader("🌾", "Feed Consumption")
            Spacer(Modifier.height(8.dp))
            InfoCard {
                if (cow.latestFeedQty == null) {
                    Text("No feed data recorded yet.", color = Muted, fontSize = 13.sp)
                } else {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Last recorded", color = Grey, fontSize = 13.sp)
                        Text("${cow.latestFeedQty} kg", color = Orange, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // Activity
            SectionHeader("🏃", "Activity")
            Spacer(Modifier.height(8.dp))
            InfoCard {
                if (cow.latestActivitySteps == null) {
                    Text("No activity data recorded yet.", color = Muted, fontSize = 13.sp)
                } else {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Steps (last record)", color = Grey, fontSize = 13.sp)
                        Text("${cow.latestActivitySteps}", color = Green, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                }
            }

            // Notes
            if (!cow.notes.isNullOrBlank()) {
                Spacer(Modifier.height(12.dp))
                SectionHeader("📝", "Notes")
                Spacer(Modifier.height(8.dp))
                InfoCard { Text(cow.notes, color = Grey, fontSize = 13.sp, lineHeight = 18.sp) }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
fun ProfileInfoGrid(cow: CowProfile) {
    val fields = listOf(
        "🐄 Breed" to (cow.breed ?: "—"),
        "🎂 Age" to (cow.age ?: "—"),
        "⚥ Gender" to (cow.gender ?: "—"),
        "📅 DOB" to (cow.dob ?: "—")
    )
    fields.chunked(2).forEach { row ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            row.forEach { (label, value) ->
                Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
                    Text(label, fontSize = 9.sp, color = Muted)
                    Text(value, fontSize = 13.sp, color = White, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

@Composable
fun SectionHeader(icon: String, title: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(3.dp).height(16.dp).clip(RoundedCornerShape(2.dp)).background(Green))
        Spacer(Modifier.width(8.dp))
        Text(icon, fontSize = 14.sp)
        Spacer(Modifier.width(4.dp))
        Text(title, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = White)
    }
}

@Composable
fun InfoCard(content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = BgCard)) {
        Column(Modifier.padding(14.dp), content = content)
    }
}

@Composable
fun MiniStat(icon: String, label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(icon, fontSize = 18.sp)
        Spacer(Modifier.height(4.dp))
        Text(value, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = color)
        Text(label, fontSize = 9.sp, color = Grey)
    }
}

// ══════════════ DIALOGS ══════════════

@Composable
fun AddCowDialog(
    onDismiss: () -> Unit,
    onSave: (id: String, name: String, breed: String?, age: String?, gender: String?, dob: String?, notes: String?, photoUri: Uri?) -> Unit
) {
    var cattleId by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var breed by remember { mutableStateOf("") }
    var age by remember { mutableStateOf("") }
    var gender by remember { mutableStateOf("") }
    var dob by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var photoUri by remember { mutableStateOf<Uri?>(null) }
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> photoUri = uri }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(20.dp),
        containerColor = BgCard,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("🐄", fontSize = 20.sp); Spacer(Modifier.width(8.dp))
                Text("Add New Cow", color = White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            }
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CowField("Cow ID *", cattleId) { cattleId = it }
                CowField("Name *", name) { name = it }
                CowField("Breed", breed) { breed = it }
                CowField("Age (e.g. 4 yrs)", age) { age = it }
                CowField("Gender (Female/Male)", gender) { gender = it }
                CowField("Date of Birth (YYYY-MM-DD)", dob) { dob = it }
                CowField("Notes", notes) { notes = it }
                OutlinedButton(
                    onClick = { photoPicker.launch("image/*") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, if (photoUri != null) Green else Border),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = if (photoUri != null) Green else Grey)
                ) { Text(if (photoUri != null) "✓ Photo selected" else "📷  Select Photo", fontSize = 13.sp) }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (cattleId.isNotBlank() && name.isNotBlank()) {
                        onSave(cattleId, name, breed.ifBlank { null }, age.ifBlank { null }, gender.ifBlank { null }, dob.ifBlank { null }, notes.ifBlank { null }, photoUri)
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = Green),
                shape = RoundedCornerShape(10.dp),
                enabled = cattleId.isNotBlank() && name.isNotBlank()
            ) { Text("Add Cow", color = BgDark, fontWeight = FontWeight.Bold) }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss, shape = RoundedCornerShape(10.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Border), colors = ButtonDefaults.outlinedButtonColors(contentColor = Grey)) { Text("Cancel") }
        }
    )
}

@Composable
fun EditCowDialog(
    cow: CowProfile,
    onDismiss: () -> Unit,
    onSave: (name: String?, breed: String?, age: String?, gender: String?, dob: String?, notes: String?, photoUri: Uri?) -> Unit
) {
    var name by remember { mutableStateOf(cow.name) }
    var breed by remember { mutableStateOf(cow.breed ?: "") }
    var age by remember { mutableStateOf(cow.age ?: "") }
    var gender by remember { mutableStateOf(cow.gender ?: "") }
    var dob by remember { mutableStateOf(cow.dob ?: "") }
    var notes by remember { mutableStateOf(cow.notes ?: "") }
    var photoUri by remember { mutableStateOf<Uri?>(null) }
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> photoUri = uri }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(20.dp),
        containerColor = BgCard,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("✏️", fontSize = 20.sp); Spacer(Modifier.width(8.dp))
                Column { Text("Edit Profile", color = White, fontWeight = FontWeight.Bold, fontSize = 18.sp); Text(cow.cattleId, color = Grey, fontSize = 11.sp) }
            }
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CowField("Name *", name) { name = it }
                CowField("Breed", breed) { breed = it }
                CowField("Age", age) { age = it }
                CowField("Gender", gender) { gender = it }
                CowField("Date of Birth", dob) { dob = it }
                CowField("Notes", notes) { notes = it }
                OutlinedButton(
                    onClick = { photoPicker.launch("image/*") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, if (photoUri != null) Green else Border),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = if (photoUri != null) Green else Grey)
                ) { Text(if (photoUri != null) "✓ Photo selected" else "📷  Change Photo", fontSize = 13.sp) }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(name.ifBlank { null }, breed.ifBlank { null }, age.ifBlank { null }, gender.ifBlank { null }, dob.ifBlank { null }, notes.ifBlank { null }, photoUri) },
                colors = ButtonDefaults.buttonColors(containerColor = Green),
                shape = RoundedCornerShape(10.dp),
                enabled = name.isNotBlank()
            ) { Text("Save Changes", color = BgDark, fontWeight = FontWeight.Bold) }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss, shape = RoundedCornerShape(10.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Border), colors = ButtonDefaults.outlinedButtonColors(contentColor = Grey)) { Text("Cancel") }
        }
    )
}

@Composable
fun DeleteConfirmDialog(cowName: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(20.dp),
        containerColor = BgCard,
        title = { Text("Remove Cow?", color = White, fontWeight = FontWeight.Bold, fontSize = 18.sp) },
        text = {
            Text(
                "\"$cowName\" will be removed from the list. All historical sensor, health, and milk data for this cow will be preserved in the database.",
                color = Grey, fontSize = 13.sp, lineHeight = 18.sp
            )
        },
        confirmButton = {
            Button(onClick = onConfirm, colors = ButtonDefaults.buttonColors(containerColor = Red), shape = RoundedCornerShape(10.dp)) {
                Text("Remove", color = White, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss, shape = RoundedCornerShape(10.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Border), colors = ButtonDefaults.outlinedButtonColors(contentColor = Grey)) { Text("Cancel") }
        }
    )
}

@Composable
fun CowField(label: String, value: String, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = onValueChange,
        label = { Text(label, fontSize = 12.sp, color = Grey) },
        singleLine = label != "Notes",
        maxLines = if (label == "Notes") 3 else 1,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth(),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = White, unfocusedTextColor = White,
            focusedBorderColor = Green, unfocusedBorderColor = Border,
            focusedContainerColor = BgDark, unfocusedContainerColor = BgDark,
            focusedLabelColor = Green, unfocusedLabelColor = Grey
        )
    )
}
