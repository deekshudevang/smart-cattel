package com.example.smartcattle

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.*
import org.json.JSONObject
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Path
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

// ── Data Models ──
data class Sensor(val icon: String, val label: String, val value: String, val unit: String, val status: String, val color: Color)
data class Cattle(val id: String, val name: String, val breed: String, val age: String, val overall: String, val sensors: List<Sensor>)
data class Alert(val cattle: String, val msg: String, val time: String, val tag: String, val color: Color)

// ── Retrofit API Models ──
data class ApiCattle(val cattle_id: String, val name: String, val status: String)
data class ApiLatest(val cattle_id: String, val timestamp: String, val spo2: Float, val bpm: Float, val temperature_c: Float, val ph_level: Float, val motion_level: String, val light_level: Float, val health_status: String)

interface ApiService {
    @GET("api/cattle")
    suspend fun getCattle(): List<ApiCattle>
    @GET("api/cattle/{id}/latest")
    suspend fun getLatestReading(@Path("id") id: String): ApiLatest
}

class CattleViewModel : ViewModel() {
    private val _cattle = MutableStateFlow<List<Cattle>>(emptyList())
    val cattle: StateFlow<List<Cattle>> = _cattle.asStateFlow()

    private val _alerts = MutableStateFlow<List<Alert>>(emptyList())
    val alerts: StateFlow<List<Alert>> = _alerts.asStateFlow()

    private val _history = MutableStateFlow<List<List<String>>>(emptyList())
    val history: StateFlow<List<List<String>>> = _history.asStateFlow()

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private var api: ApiService? = null
    private var webSocket: WebSocket? = null
    private val client = OkHttpClient()

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
            
            // Initial fetch
            viewModelScope.launch {
                try {
                    val apiCattleList = api?.getCattle() ?: emptyList()
                    // Map to basic UI state to show something immediately (Loading state handled here)
                    val uiCattleList = apiCattleList.map { Cattle(it.cattle_id, it.name, "Gir", "4 yrs", it.status, emptyList()) }
                    _cattle.value = uiCattleList
                } catch(e: Exception) {
                    // Empty state on error
                }
            }
            
        } catch (e: Exception) {
            _isConnected.value = false
        }
    }

    private fun connectWebSocket(url: String) {
        val request = Request.Builder().url(url).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                _isConnected.value = true
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                viewModelScope.launch(Dispatchers.Main) {
                    try {
                        val json = JSONObject(text)
                        if (json.getString("type") == "sensor_update") {
                            val cid = json.getString("cattle_id")
                            val data = json.getJSONObject("data")
                            val health = json.getJSONObject("health")
                            
                            val sensors = listOf(
                                Sensor("💓", "SpO2", data.getInt("spo2").toString(), "%", health.optJSONObject("spo2")?.optString("status") ?: "Normal", Green),
                                Sensor("❤\u200D🩹", "Heart Rate", data.getInt("bpm").toString(), "bpm", health.optJSONObject("bpm")?.optString("status") ?: "Normal", Green),
                                Sensor("🌡️", "Body Temp", String.format("%.1f", data.getDouble("temperature")), "°C", health.optJSONObject("temperature")?.optString("status") ?: "Normal", Green),
                                Sensor("🧪", "pH Level", String.format("%.1f", data.getDouble("ph")), "", health.optJSONObject("ph")?.optString("status") ?: "Normal", Green),
                                Sensor("☀️", "Light", data.getInt("ldr").toString(), "lux", health.optJSONObject("ldr")?.optString("status") ?: "Normal", Cyan)
                            )
                            
                            // Check overall health
                            var overall = "Healthy"
                            if (health.keys().asSequence().any { health.optJSONObject(it)?.optString("status") == "abnormal" }) {
                                overall = "At Risk"
                            }
                            
                            // Update cattle state
                            val currentCattle = _cattle.value.toMutableList()
                            val idx = currentCattle.indexOfFirst { it.id == cid }
                            if (idx != -1) {
                                val c = currentCattle[idx]
                                currentCattle[idx] = c.copy(overall = overall, sensors = sensors)
                                _cattle.value = currentCattle
                            }
                            
                            // Update history
                            val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                            val t = timeFormat.format(Date())
                            val newRow = listOf(t, data.getInt("spo2").toString(), data.getInt("bpm").toString(), String.format("%.1f", data.getDouble("temperature")), String.format("%.1f", data.getDouble("ph")), if(overall == "Healthy") "✓" else "⚠")
                            _history.value = (_history.value + listOf(newRow)).takeLast(20)
                            
                            // Create alerts
                            if (overall == "At Risk") {
                                val msg = "Abnormal health readings detected for $cid"
                                _alerts.value = (listOf(Alert(cid, msg, "Just now", "WARNING", Orange)) + _alerts.value).take(10)
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                _isConnected.value = false
                reconnect(url)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                _isConnected.value = false
                reconnect(url)
            }
        })
    }
    
    private fun reconnect(url: String) {
        viewModelScope.launch {
            delay(5000) // 5s reconnect delay
            if (!_isConnected.value) connectWebSocket(url)
        }
    }
}

// ══════════════ APP ══════════════

@Composable
fun SmartCattleApp() {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("smart_cattle_prefs", Context.MODE_PRIVATE)
    var showSplash by remember { mutableStateOf(true) }
    var showIpDialog by remember { mutableStateOf(false) }
    var ipAddress by remember { mutableStateOf(prefs.getString("backend_ip", "192.168.1.3") ?: "192.168.1.3") }
    
    val viewModel: CattleViewModel = viewModel()
    
    // Initialize networking
    LaunchedEffect(ipAddress) {
        viewModel.configureIp(ipAddress)
    }

    MaterialTheme(colorScheme = darkColorScheme(background = BgDark, surface = BgCard, primary = Green)) {
        if (showSplash) {
            SplashScreen { showSplash = false }
        } else {
            MainScreen(
                viewModel = viewModel, 
                onOpenSettings = { showIpDialog = true }
            )
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
                        Text("Enter the local Wi-Fi IP address of the laptop running the FastAPI backend. Find this using `ipconfig`.", color = Grey, fontSize = 13.sp, lineHeight = 18.sp)
                        Spacer(Modifier.height(20.dp))
                        OutlinedTextField(
                            value = ipAddress,
                            onValueChange = { ipAddress = it },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            leadingIcon = { Text("🌐", fontSize = 16.sp, modifier = Modifier.padding(start = 12.dp)) },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = White, unfocusedTextColor = White,
                                focusedBorderColor = Green, unfocusedBorderColor = Border,
                                focusedContainerColor = BgDark, unfocusedContainerColor = BgDark
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = { 
                            prefs.edit().putString("backend_ip", ipAddress).apply()
                            viewModel.configureIp(ipAddress)
                            showIpDialog = false 
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Green),
                        shape = RoundedCornerShape(12.dp)
                    ) { Text("Connect", color = BgDark, fontWeight = FontWeight.Bold) }
                },
                dismissButton = {
                    OutlinedButton(
                        onClick = { showIpDialog = false },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Grey),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Border),
                        shape = RoundedCornerShape(12.dp)
                    ) { Text("Cancel") }
                },
                containerColor = BgCard,
                tonalElevation = 8.dp
            )
        }
    }
}

// ══════════════ SPLASH ══════════════

@Composable
fun SplashScreen(onFinished: () -> Unit) {
    var phase by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        delay(400)
        phase = 1; delay(800)
        phase = 2; delay(600)
        phase = 3; delay(500)
        phase = 4; delay(1200)
        phase = 5
    }

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
            AnimatedVisibility(phase >= 4, enter = fadeIn(tween(500))) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { FeaturePill("IoT Sensors"); FeaturePill("ML Engine"); FeaturePill("Real-time") }
            }
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
                    Box(Modifier.padding(end = 12.dp).clip(RoundedCornerShape(12.dp)).background(if(isConnected) Green.copy(alpha = 0.1f) else Red.copy(alpha=0.1f)).padding(horizontal = 10.dp, vertical = 4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(6.dp).clip(CircleShape).background(if(isConnected) Green else Red))
                            Spacer(Modifier.width(6.dp))
                            Text(if(isConnected) "Online" else "Offline", fontSize = 10.sp, color = if(isConnected) Green else Red, fontWeight = FontWeight.Medium)
                        }
                    }
                    IconButton(onClick = onOpenSettings) { Text("⚙️") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BgDark)
            )
        },
        bottomBar = {
            NavigationBar(containerColor = BgCard, tonalElevation = 0.dp) {
                listOf("Dashboard", "Alerts", "History").forEachIndexed { i, title ->
                    val icons = listOf("🏠", "🔔", "📊")
                    NavigationBarItem(selected = tab == i, onClick = { tab = i }, label = { Text(title, fontSize = 10.sp, color = if (tab == i) Green else Muted) }, icon = { Text(icons[i], fontSize = 18.sp) }, colors = NavigationBarItemDefaults.colors(indicatorColor = Green.copy(alpha = 0.1f)))
                }
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().background(BgDark).padding(padding)) {
            when (tab) {
                0 -> DashboardTab(viewModel)
                1 -> AlertsTab(viewModel)
                2 -> HistoryTab(viewModel)
            }
        }
    }
}

// ══════════════ DASHBOARD ══════════════

@Composable
fun DashboardTab(viewModel: CattleViewModel) {
    val cattle by viewModel.cattle.collectAsState()
    
    // Calculate averages dynamically from real data
    val avgSpo2 = if (cattle.isNotEmpty()) cattle.mapNotNull { c -> c.sensors.find { it.label == "SpO2" }?.value?.toIntOrNull() }.average().takeIf { !it.isNaN() }?.toInt()?.toString()?.plus("%") ?: "--" else "--"
    val avgBpm = if (cattle.isNotEmpty()) cattle.mapNotNull { c -> c.sensors.find { it.label == "Heart Rate" }?.value?.toIntOrNull() }.average().takeIf { !it.isNaN() }?.toInt()?.toString() ?: "--" else "--"
    val avgTemp = if (cattle.isNotEmpty()) cattle.mapNotNull { c -> c.sensors.find { it.label == "Body Temp" }?.value?.toDoubleOrNull() }.average().takeIf { !it.isNaN() }?.let { String.format(java.util.Locale.US, "%.1f°", it) } ?: "--" else "--"
    
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
                            Row {
                                StatusChip("${cattle.count { it.overall == "Healthy" }} Healthy", Green)
                                Spacer(Modifier.width(6.dp))
                                StatusChip("${cattle.count { it.overall != "Healthy" }} At Risk", Red)
                            }
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
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) { Box(Modifier.width(3.dp).height(18.dp).clip(RoundedCornerShape(2.dp)).background(Green)); Spacer(Modifier.width(8.dp)); Text("Sensor History", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = White) }
        Text("Live Readings log", fontSize = 11.sp, color = Grey)
        Spacer(Modifier.height(14.dp))

        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = BgCard)) {
            Column {
                Row(Modifier.background(Blue.copy(alpha = 0.08f)).padding(horizontal = 12.dp, vertical = 10.dp)) { listOf("Time", "SpO2", "BPM", "Temp", "pH", "").forEach { Text(it, Modifier.weight(1f), fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Blue, textAlign = TextAlign.Center) } }
                if (history.isEmpty()) Text("No data yet...", color = Grey, modifier = Modifier.padding(16.dp))
                history.forEachIndexed { i, row ->
                    val warn = row.last() == "⚠"
                    Row(Modifier.background(if (warn) Red.copy(alpha = 0.04f) else Color.Transparent).padding(horizontal = 12.dp, vertical = 9.dp)) {
                        row.forEachIndexed { j, cell -> Text(cell, Modifier.weight(1f), fontSize = 11.sp, color = when { j == row.size - 1 && warn -> Orange; j == row.size - 1 -> Green; else -> White }, textAlign = TextAlign.Center) }
                    }
                    if (i < history.size - 1) Divider(color = Border, thickness = 0.3.dp)
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = Color.Transparent)) { Box(Modifier.fillMaxWidth().background(Brush.linearGradient(listOf(Color(0xFF1A237E), Color(0xFF311B92), Color(0xFF4A148C)))).padding(16.dp)) { Column { Row(verticalAlignment = Alignment.CenterVertically) { Text("🧠", fontSize = 20.sp); Spacer(Modifier.width(8.dp)); Text("ML Analysis Summary", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White) }; Spacer(Modifier.height(10.dp)); Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) { Text("• Random Forest classifier accuracy", fontSize = 10.sp, color = Color.White.copy(alpha = 0.7f)); Text("94.2%", fontSize = 10.sp, color = Cyan, fontWeight = FontWeight.Bold) }; Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) { Text("• Active ML models", fontSize = 10.sp, color = Color.White.copy(alpha = 0.7f)); Text("6 (SpO2, BPM, Temp, MEMS, pH, LDR)", fontSize = 10.sp, color = Cyan, fontWeight = FontWeight.Bold) } } } }
    }
}
