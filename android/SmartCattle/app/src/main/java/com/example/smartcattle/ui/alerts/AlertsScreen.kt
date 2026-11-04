package com.example.smartcattle.ui.alerts

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.smartcattle.data.model.AlertRow
import com.example.smartcattle.data.model.formatAlertDateTime
import com.example.smartcattle.viewmodel.CattleViewModel

private val BgDark   = Color(0xFF0D1117)
private val BgCard   = Color(0xFF161B22)
private val Border   = Color(0xFF30363D)
private val Green    = Color(0xFF2EA043)
private val Red      = Color(0xFFF85149)
private val Amber    = Color(0xFFD29922)
private val Muted    = Color(0xFF8B949E)

@Composable
fun AlertsScreen(viewModel: CattleViewModel) {
    val alerts by viewModel.alerts.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgDark)
            .padding(16.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Notifications,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(22.dp)
            )
            Text("Alerts", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(4.dp))
        Text("${alerts.size} active alert(s)", color = Muted, fontSize = 12.sp)
        Spacer(Modifier.height(16.dp))

        if (alerts.isEmpty()) {
            Box(Modifier.fillMaxWidth().padding(top = 48.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(Green.copy(alpha = 0.12f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = Green,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Text("All cattle are healthy", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(2.dp))
                    Text("No active physiological alerts", color = Muted, fontSize = 12.sp)
                }
            }
            return@Column
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(alerts) { alert ->
                AlertCard(alert)
            }
        }
    }
}

@Composable
private fun AlertCard(alert: AlertRow) {
    val accentColor = if (alert.fallDetected) Red else Amber
    val ts = formatAlertDateTime(alert.timestamp)

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors   = CardDefaults.cardColors(containerColor = BgCard),
        shape    = RoundedCornerShape(10.dp),
        border   = androidx.compose.foundation.BorderStroke(1.dp, Border)
    ) {
        Row(modifier = Modifier.padding(14.dp)) {
            // Quiet accent bar on the left
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(IntrinsicSize.Min)
                    .clip(RoundedCornerShape(1.5.dp))
                    .background(accentColor)
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (alert.fallDetected) Icons.Default.Warning else Icons.Default.Notifications,
                        contentDescription = null,
                        tint = accentColor,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (alert.fallDetected) "Fall detected" else "Health alert",
                        color = accentColor,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.weight(1f))
                    Surface(
                        shape = CircleShape,
                        color = accentColor.copy(alpha = 0.12f),
                        border = androidx.compose.foundation.BorderStroke(1.dp, accentColor.copy(alpha = 0.25f))
                    ) {
                        Text(
                            text = if (alert.fallDetected) "Critical" else "Warning",
                            color = accentColor,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text("${alert.cattleName}  ·  ${alert.cattleId}", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.AccessTime,
                        contentDescription = "Alert Time",
                        tint = Muted,
                        modifier = Modifier.size(11.dp)
                    )
                    Text(ts, color = Muted, fontSize = 11.sp)
                }

                if (alert.details.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    alert.details.forEach { detail ->
                        Row(verticalAlignment = Alignment.Top, modifier = Modifier.padding(bottom = 2.dp)) {
                            Text("•  ", color = Muted, fontSize = 12.sp)
                            Text(detail, color = Muted, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}
