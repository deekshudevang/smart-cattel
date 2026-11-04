package com.example.smartcattle.ui.dashboard

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.smartcattle.data.model.SensorData
import com.example.smartcattle.viewmodel.CattleViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.util.Locale

// ── Unslop Precision Palette (Slate / Instrument Ramp) ──
private val SurfaceBg     = Color(0xFF0D1117) // Calm graphite canvas
private val SurfaceCard   = Color(0xFF161B22) // Precision card background
private val SurfaceNested = Color(0xFF21262D) // Inner well / nested panel
private val TextPrimary   = Color(0xFFE6EDF3) // High-contrast primary reading
private val TextSecondary = Color(0xFF8B949E) // Muted labels and units
private val TextMuted     = Color(0xFF6E7681) // Timestamps and subtitles
private val BorderQuiet   = Color(0x2630363D) // 10% hairline border
private val AccentBlue    = Color(0xFF388BFD) // Single focused accent
private val StatusGood    = Color(0xFF3FB950) // Calmed emerald
private val StatusWarning = Color(0xFFD29922) // Calmed amber
private val StatusAlert   = Color(0xFFF85149) // Calmed coral red

@Composable
fun DashboardScreen(viewModel: CattleViewModel) {
    val data by viewModel.latestData.collectAsStateWithLifecycle()
    val connected by viewModel.isConnected.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SurfaceBg)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // ── Instrument Header ──
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(SurfaceNested),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Pets,
                    contentDescription = null,
                    tint = TextSecondary,
                    modifier = Modifier.size(18.dp)
                )
            }
            Spacer(Modifier.width(10.dp))
            Column {
                Text(
                    text = "Smart Cattle",
                    color = TextPrimary,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "Telemetry Instrument",
                    color = TextMuted,
                    fontSize = 11.sp
                )
            }
            Spacer(Modifier.weight(1f))
            StatusDot(connected)
        }

        val d = data ?: SensorData(
            cattleId = "CATTLE-001",
            timestamp = "",
            spo2 = null,
            heart_rate = null,
            temperature = null,
            humidity = null,
            memsX = null,
            memsY = null,
            memsZ = null,
            ph = null,
            ldr = null,
            fallDetected = false
        )

        // ── Overall Health Banner ──
        val overallColor by animateColorAsState(
            if (data == null) TextMuted else if (d.health.overall == "normal") StatusGood else StatusAlert,
            label = "overallColor"
        )
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = SurfaceCard),
            border = CardDefaults.outlinedCardBorder().copy(
                brush = androidx.compose.ui.graphics.SolidColor(BorderQuiet)
            ),
            shape = RoundedCornerShape(12.dp)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(overallColor.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = when {
                            data == null -> Icons.Default.HourglassEmpty
                            d.health.overall == "normal" -> Icons.Default.CheckCircle
                            else -> Icons.Default.Warning
                        },
                        contentDescription = null,
                        tint = overallColor,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(d.cattleId, color = TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                    val statusLabel = if (data == null) "Waiting for hardware stream" else d.overallStatus
                    Text(
                        text = statusLabel,
                        color = overallColor,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Spacer(Modifier.weight(1f))
                if (d.fallDetected) {
                    FallBadge()
                }
            }
        }

        Text(
            text = "Live telemetry (9 categories)",
            color = TextSecondary,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium
        )

        // ══════════════════════════════════════════════════════════
        // EXACT DASHBOARD LAYOUT: STRICT 9 SENSOR CARDS
        // ══════════════════════════════════════════════════════════

        // ── ROW 1: Heart Rate | SpO2 ──
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            val heartRateVal = if (!connected) "Offline" else if (d.heart_rate == null || d.heart_rate <= 0) "Not Available" else "${d.heart_rate}"
            val heartRateStatus = if (!connected) "Offline" else if (data == null) "Stale" else if (d.heart_rate == null) "Offline" else if (d.health.heart_rate == "abnormal") "Abnormal" else "Normal"
            Box(Modifier.weight(1f)) {
                SensorGridCard(
                    icon = Icons.Default.Favorite,
                    label = "Heart rate",
                    value = heartRateVal,
                    unit = if (heartRateVal == "Not Available" || heartRateVal == "Offline") "" else "bpm",
                    status = heartRateStatus
                )
            }

            val spo2Val = if (!connected) "Offline" else if (d.spo2 == null || d.spo2 <= 0) "Not Available" else "${d.spo2}"
            val spo2Status = if (!connected) "Offline" else if (data == null) "Stale" else if (d.spo2 == null) "Offline" else if (d.health.spo2 == "abnormal") "Abnormal" else "Normal"
            Box(Modifier.weight(1f)) {
                SensorGridCard(
                    icon = Icons.Default.Bloodtype,
                    label = "SpO2",
                    value = spo2Val,
                    unit = if (spo2Val == "Not Available" || spo2Val == "Offline") "" else "%",
                    status = spo2Status
                )
            }
        }

        // ── ROW 2: Temperature | pH ──
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            val tempVal = if (!connected) "Offline" else if (d.temperature == null) "Not Available" else String.format(Locale.US, "%.1f", d.temperature)
            val tempStatus = if (!connected) "Offline" else if (data == null) "Stale" else if (d.temperature == null) "Offline" else if (d.health.temperature == "abnormal") "Abnormal" else "Normal"
            Box(Modifier.weight(1f)) {
                SensorGridCard(
                    icon = Icons.Default.Thermostat,
                    label = "Temperature",
                    value = tempVal,
                    unit = if (tempVal == "Not Available" || tempVal == "Offline") "" else "°C",
                    status = tempStatus
                )
            }

            val phVal = if (!connected) "Offline" else if (d.ph == null) "Not Available" else String.format(Locale.US, "%.1f", d.ph)
            val phStatus = if (!connected) "Offline" else if (data == null) "Stale" else if (d.ph == null) "Offline" else if (d.health.ph == "abnormal") "Abnormal" else "Normal"
            Box(Modifier.weight(1f)) {
                SensorGridCard(
                    icon = Icons.Default.Science,
                    label = "pH level",
                    value = phVal,
                    unit = if (phVal == "Not Available" || phVal == "Offline") "" else "pH",
                    status = phStatus
                )
            }
        }

        // ── ROW 3: LDR | Weight ──
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            val ldrVal = if (!connected) "Offline" else if (d.ldr == null) "Not Available" else "${d.ldr}"
            val ldrStatus = if (!connected) "Offline" else if (data == null) "Stale" else if (d.ldr == null) "Offline" else if (d.health.ldr == "abnormal") "Abnormal" else "Normal"
            Box(Modifier.weight(1f)) {
                SensorGridCard(
                    icon = Icons.Default.LightMode,
                    label = "Light",
                    value = ldrVal,
                    unit = if (ldrVal == "Not Available" || ldrVal == "Offline") "" else "lux",
                    status = ldrStatus
                )
            }

            val wVal = if (!connected) "Offline" else if (d.bodyWeightKg == null) "Not Available" else String.format(Locale.US, "%.1f", d.bodyWeightKg)
            val wStatus = when {
                !connected -> "Offline"
                data == null -> "Stale"
                d.bodyWeightKg == null -> "Offline"
                !d.weightCalibrated -> "Uncalibrated"
                !d.weightStable -> "Stabilizing"
                else -> "Stable"
            }
            val wBadge = if (connected && d.bodyWeightKg != null) {
                if (d.weightStable) "Stable" else "Stabilizing"
            } else null
            Box(Modifier.weight(1f)) {
                SensorGridCard(
                    icon = Icons.Default.Scale,
                    label = "Weight",
                    value = wVal,
                    unit = if (wVal == "Not Available" || wVal == "Offline") "" else "kg",
                    status = wStatus,
                    badge = wBadge
                )
            }
        }

        // ── ROW 4: Steps | Total Steps ──
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            val stepsTodayVal = if (!connected) "Offline" else if (d.stepCountToday == null) "Not Available" else "${d.stepCountToday}"
            val stepsTodayStatus = if (!connected) "Offline" else if (data == null) "Stale" else if (d.stepCountToday == null) "Offline" else "Active"
            Box(Modifier.weight(1f)) {
                SensorGridCard(
                    icon = Icons.Default.DirectionsWalk,
                    label = "Steps",
                    value = stepsTodayVal,
                    unit = if (stepsTodayVal == "Not Available" || stepsTodayVal == "Offline") "" else "steps",
                    status = stepsTodayStatus
                )
            }

            val totalStepsVal = if (!connected) "Offline" else if (d.stepCountLifetime == null) "Not Available" else "${d.stepCountLifetime}"
            val totalStepsStatus = if (!connected) "Offline" else if (data == null) "Stale" else if (d.stepCountLifetime == null) "Offline" else "Tracking"
            Box(Modifier.weight(1f)) {
                SensorGridCard(
                    icon = Icons.Default.Timeline,
                    label = "Total steps",
                    value = totalStepsVal,
                    unit = if (totalStepsVal == "Not Available" || totalStepsVal == "Offline") "" else "steps",
                    status = totalStepsStatus
                )
            }
        }

        // ── ROW 5: MEMS Axis — ONE full-width card with X-Axis | Y-Axis | Z-Axis ──
        MemsFullWidthCard(
            x = d.accelerationX ?: d.memsX,
            y = d.accelerationY ?: d.memsY,
            z = d.accelerationZ ?: d.memsZ,
            isValid = d.accelerometerValid && connected,
            fallDetected = d.fallDetected,
            connected = connected
        )

        if (d.timestamp.isNotEmpty()) {
            Text(
                text = "Last update: ${d.timestamp.take(19)}",
                color = TextMuted,
                fontSize = 11.sp,
                modifier = Modifier.padding(start = 2.dp)
            )
        }
    }
}

// ── Sensor Grid Card for Rows 1, 2, 3 ──
@Composable
private fun SensorGridCard(
    icon: ImageVector,
    label: String,
    value: String,
    unit: String,
    status: String,
    badge: String? = null
) {
    val statusColor = when (status.lowercase()) {
        "valid", "normal", "stable" -> StatusGood
        "invalid", "abnormal", "critical" -> StatusAlert
        "stale", "stabilizing", "warning" -> StatusWarning
        else -> TextMuted
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(112.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceCard),
        border = CardDefaults.outlinedCardBorder().copy(
            brush = androidx.compose.ui.graphics.SolidColor(BorderQuiet)
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(SurfaceNested),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = TextSecondary,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                    Text(
                        text = label,
                        color = TextSecondary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
                if (badge != null) {
                    val bColor = if (badge == "Stable") StatusGood else StatusWarning
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(bColor.copy(alpha = 0.12f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(badge, color = bColor, fontSize = 10.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }

            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = value,
                    color = TextPrimary,
                    fontSize = if (value.length > 7) 16.sp else 22.sp,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.titleLarge.copy(fontFeatureSettings = "tnum")
                )
                if (unit.isNotEmpty()) {
                    Text(
                        text = unit,
                        color = TextMuted,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(bottom = 2.dp)
                    )
                }
            }

            StatusBadge(status, statusColor)
        }
    }
}

// ── MEMS AXIS: SINGLE FULL-WIDTH COMBINED CARD ──
@Composable
private fun MemsFullWidthCard(
    x: Float?,
    y: Float?,
    z: Float?,
    isValid: Boolean,
    fallDetected: Boolean,
    connected: Boolean
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = SurfaceCard),
        border = CardDefaults.outlinedCardBorder().copy(
            brush = androidx.compose.ui.graphics.SolidColor(if (fallDetected) StatusAlert else BorderQuiet)
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(SurfaceNested),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.ScreenRotation,
                            contentDescription = null,
                            tint = TextSecondary,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                    Text(
                        text = "MEMS Axis",
                        color = TextPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                if (fallDetected) {
                    FallBadge()
                } else if (!connected || !isValid) {
                    StatusBadge("Offline", TextMuted)
                } else {
                    StatusBadge("Active", StatusGood)
                }
            }

            // 3-Column Axis Display
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(SurfaceNested)
                    .padding(vertical = 10.dp, horizontal = 6.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                AxisColumn(axisName = "X-Axis", value = x, isValid = isValid && connected)
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .height(36.dp)
                        .background(BorderQuiet)
                )
                AxisColumn(axisName = "Y-Axis", value = y, isValid = isValid && connected)
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .height(36.dp)
                        .background(BorderQuiet)
                )
                AxisColumn(axisName = "Z-Axis", value = z, isValid = isValid && connected)
            }
        }
    }
}

@Composable
private fun AxisColumn(axisName: String, value: Float?, isValid: Boolean) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(axisName, color = TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(3.dp))
        val displayVal = if (!isValid || value == null) "Not Available" else String.format(Locale.US, "%.2f", value)
        Text(
            text = displayVal,
            color = if (isValid && value != null) TextPrimary else TextMuted,
            fontSize = if (displayVal == "Not Available") 11.sp else 16.sp,
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum")
        )
        if (isValid && value != null) {
            Text("m/s²", color = TextMuted, fontSize = 10.sp)
        }
    }
}

@Composable
private fun StatusBadge(status: String, color: Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(SurfaceNested)
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Box(
            modifier = Modifier
                .size(5.dp)
                .clip(CircleShape)
                .background(color)
        )
        Text(
            text = status.replaceFirstChar { it.uppercase() },
            color = TextSecondary,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun FallBadge() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(StatusAlert.copy(alpha = 0.15f))
            .border(1.dp, StatusAlert.copy(alpha = 0.4f), RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Icon(
            imageVector = Icons.Default.Warning,
            contentDescription = null,
            tint = StatusAlert,
            modifier = Modifier.size(12.dp)
        )
        Text("Fall detected", color = StatusAlert, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun StatusDot(connected: Boolean) {
    val color by animateColorAsState(if (connected) StatusGood else TextMuted, label = "dot")
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        modifier = Modifier
            .clip(CircleShape)
            .background(SurfaceCard)
            .border(1.dp, BorderQuiet, CircleShape)
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(color)
        )
        Text(
            text = if (connected) "Live" else "Offline",
            color = TextSecondary,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium
        )
    }
}
