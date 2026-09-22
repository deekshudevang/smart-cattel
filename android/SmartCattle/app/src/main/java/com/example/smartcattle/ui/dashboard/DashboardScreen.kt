package com.example.smartcattle.ui.dashboard

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.smartcattle.data.model.SensorData
import com.example.smartcattle.viewmodel.CattleViewModel
import java.util.Locale

// ── Colour tokens ──
private val BgDark    = Color(0xFF080E1A)
private val BgCard    = Color(0xFF111B2E)
private val Green     = Color(0xFF00E676)
private val Red       = Color(0xFFFF5252)
private val Orange    = Color(0xFFFFAB40)
private val Blue      = Color(0xFF448AFF)
private val Grey      = Color(0xFF8899AA)
private val Border    = Color(0xFF1E3050)

@Composable
fun DashboardScreen(viewModel: CattleViewModel) {
    val data by viewModel.latestData.collectAsState()
    val connected by viewModel.isConnected.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgDark)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // ── Header ──
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("🐄  Smart Cattle", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            StatusDot(connected)
        }

        if (data == null) {
            Box(modifier = Modifier.fillMaxWidth().padding(top = 40.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Blue)
                    Spacer(Modifier.height(12.dp))
                    Text("Waiting for sensor data…", color = Grey, fontSize = 13.sp)
                }
            }
            return@Column
        }

        val d = data!!

        // ── Overall health banner ──
        val overallColor by animateColorAsState(
            if (d.health.overall == "normal") Green else Red, label = "overallColor"
        )
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = BgCard),
            shape = RoundedCornerShape(12.dp)
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(if (d.health.overall == "normal") "✅" else "⚠️", fontSize = 28.sp)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(d.cattleId, color = Grey, fontSize = 12.sp)
                    Text(d.overallStatus, color = overallColor, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.weight(1f))
                if (d.fallDetected) {
                    FallBadge()
                }
            }
        }

        // ── Sensor cards grid ──
        Text("Live Sensors", color = Grey, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)

        val spo2Val = if (!connected) "--" else if (d.spo2 == null || d.spo2 == 0) "Place Finger" else "${d.spo2}"
        val spo2Unit = if (!connected || d.spo2 == null || d.spo2 == 0) "" else "%"
        val spo2Status = if (!connected) "unknown" else d.health.spo2
        SensorCard("💓", "SpO2", spo2Val, spo2Unit, spo2Status)

        val bpmVal = if (!connected) "--" else if (d.bpm == null || d.bpm == 0) "Place Finger" else "${d.bpm}"
        val bpmUnit = if (!connected || d.bpm == null || d.bpm == 0) "" else "bpm"
        val bpmStatus = if (!connected) "unknown" else d.health.bpm
        SensorCard("❤️", "Heart Rate", bpmVal, bpmUnit, bpmStatus)
        
        val tempVal = if (!connected || d.temperature == null) "--" else String.format(Locale.US, "%.1f", d.temperature)
        val tempStatus = if (!connected) "unknown" else d.health.temperature
        SensorCard("🌡️", "Body Temp",  tempVal, if (!connected || d.temperature == null) "" else "°C",  tempStatus)
        
        val humVal = if (!connected || d.humidity == null) "--" else String.format(Locale.US, "%.0f", d.humidity)
        SensorCard("💧", "Humidity",   humVal,    if (!connected || d.humidity == null) "" else "%",   if (!connected) "unknown" else "normal")
        
        val phVal = if (!connected || d.ph == null) "--" else String.format(Locale.US, "%.1f", d.ph)
        val phStatus = if (!connected) "unknown" else d.health.ph
        SensorCard("🧪", "pH Level",   phVal,          "",    phStatus)
        
        val ldrVal = if (!connected || d.ldr == null) "--" else "${d.ldr}"
        val ldrStatus = if (!connected) "unknown" else d.health.ldr
        SensorCard("☀️", "Light",      ldrVal,                                if (!connected || d.ldr == null) "" else "lux", ldrStatus)
        
        val memsVal = if (!connected || d.memsX == null || d.memsY == null || d.memsZ == null) "--" else String.format(Locale.US, "X:%.2f Y:%.2f Z:%.2f", d.memsX, d.memsY, d.memsZ)
        val memsStatus = if (!connected) "unknown" else if (d.fallDetected) "abnormal" else d.health.mems
        val memsBadge = if (connected && d.fallDetected) "FALL DETECTED" else null
        SensorCard(
            icon   = "🏃",
            label  = "Motion / Fall",
            value  = memsVal,
            unit   = if (!connected || d.memsX == null) "" else "g",
            status = memsStatus,
            badge  = memsBadge
        )

        if (d.timestamp.isNotEmpty()) {
            Text("Last update: ${d.timestamp.take(19)}", color = Grey, fontSize = 11.sp)
        }
    }
}

// ── Reusable sensor card ──
@Composable
private fun SensorCard(icon: String, label: String, value: String, unit: String, status: String, badge: String? = null) {
    val statusColor = when (status) {
        "normal"   -> Green
        "abnormal" -> Red
        else       -> Orange
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors   = CardDefaults.cardColors(containerColor = BgCard),
        shape    = RoundedCornerShape(10.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(icon, fontSize = 24.sp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(label, color = Grey, fontSize = 12.sp)
                Text("$value $unit".trim(), color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
            if (badge != null) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(Red.copy(alpha = 0.15f))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) { Text(badge, color = Red, fontSize = 10.sp, fontWeight = FontWeight.Bold) }
            } else {
                StatusBadge(status, statusColor)
            }
        }
    }
}

@Composable
private fun StatusBadge(status: String, color: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.15f))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(status.replaceFirstChar { it.uppercase() }, color = color, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun FallBadge() {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Red.copy(alpha = 0.2f))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text("🚨 FALL", color = Red, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun StatusDot(connected: Boolean) {
    val color by animateColorAsState(if (connected) Green else Grey, label = "dot")
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(RoundedCornerShape(50))
                .background(color)
        )
        Spacer(Modifier.width(4.dp))
        Text(if (connected) "Live" else "Offline", color = color, fontSize = 12.sp)
    }
}
