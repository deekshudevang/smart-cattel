package com.example.smartcattle.ui.alerts

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.example.smartcattle.viewmodel.CattleViewModel

private val BgDark   = Color(0xFF080E1A)
private val BgCard   = Color(0xFF111B2E)
private val Green    = Color(0xFF00E676)
private val Red      = Color(0xFFFF5252)
private val Orange   = Color(0xFFFFAB40)
private val Grey     = Color(0xFF8899AA)

@Composable
fun AlertsScreen(viewModel: CattleViewModel) {
    val alerts by viewModel.alerts.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgDark)
            .padding(16.dp)
    ) {
        Text("🔔  Alerts", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text("${alerts.size} active alert(s)", color = Grey, fontSize = 12.sp)
        Spacer(Modifier.height(16.dp))

        if (alerts.isEmpty()) {
            Box(Modifier.fillMaxWidth().padding(top = 40.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("✅", fontSize = 48.sp)
                    Spacer(Modifier.height(8.dp))
                    Text("All cattle are healthy", color = Green, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Text("No active alerts", color = Grey, fontSize = 12.sp)
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
    val accentColor = if (alert.fallDetected) Red else Orange
    val ts = alert.timestamp.take(19).replace("T", " ")

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors   = CardDefaults.cardColors(containerColor = BgCard),
        shape    = RoundedCornerShape(12.dp)
    ) {
        Row(modifier = Modifier.padding(16.dp)) {
            // Accent bar on the left
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(IntrinsicSize.Min)
                    .clip(RoundedCornerShape(2.dp))
                    .background(accentColor)
                    .padding(bottom = 4.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (alert.fallDetected) "🚨 FALL DETECTED" else "⚠️ HEALTH ALERT",
                        color = accentColor,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.weight(1f))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(accentColor.copy(alpha = 0.15f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(if (alert.fallDetected) "CRITICAL" else "WARNING",
                            color = accentColor, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text("${alert.cattleName}  ·  ${alert.cattleId}", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(ts, color = Grey, fontSize = 11.sp)

                if (alert.details.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    alert.details.forEach { detail ->
                        Row(verticalAlignment = Alignment.Top, modifier = Modifier.padding(bottom = 2.dp)) {
                            Text("•  ", color = Grey, fontSize = 12.sp)
                            Text(detail, color = Grey, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}
