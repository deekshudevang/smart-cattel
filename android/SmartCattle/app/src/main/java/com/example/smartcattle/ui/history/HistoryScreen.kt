package com.example.smartcattle.ui.history

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.smartcattle.data.model.HistoryRow
import com.example.smartcattle.viewmodel.CattleViewModel
import java.util.Locale

private val BgDark  = Color(0xFF080E1A)
private val BgCard  = Color(0xFF111B2E)
private val Green   = Color(0xFF00E676)
private val Red     = Color(0xFFFF5252)
private val Orange  = Color(0xFFFFAB40)
private val Grey    = Color(0xFF8899AA)
private val Border  = Color(0xFF1E3050)

// Column headers shown in the table
private val HEADERS = listOf("Time", "SpO2%", "BPM", "Temp°C", "Hum%", "pH", "Fall", "Status")

@Composable
fun HistoryScreen(viewModel: CattleViewModel) {
    val rows by viewModel.history.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgDark)
            .padding(16.dp)
    ) {
        Text("📋  History", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text("Last ${rows.size} readings", color = Grey, fontSize = 12.sp)
        Spacer(Modifier.height(16.dp))

        if (rows.isEmpty()) {
            Box(Modifier.fillMaxWidth().padding(top = 40.dp), contentAlignment = Alignment.Center) {
                Text("No history yet. Waiting for sensor data…", color = Grey)
            }
            return@Column
        }

        // Scrollable horizontal table
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(BgCard)
        ) {
            Column(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                // Header row
                TableRow(HEADERS, isHeader = true)
                Divider(color = Border, thickness = 1.dp)

                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    items(rows) { row ->
                        TableRow(rowCells(row), isHeader = false, row = row)
                        Divider(color = Border.copy(alpha = 0.5f), thickness = 0.5.dp)
                    }
                }
            }
        }
    }
}

@Composable
private fun TableRow(cells: List<String>, isHeader: Boolean, row: HistoryRow? = null) {
    val textColor = if (isHeader) Grey else Color.White
    val weight = if (isHeader) FontWeight.SemiBold else FontWeight.Normal

    Row(
        modifier = Modifier
            .background(if (isHeader) BgDark else Color.Transparent)
            .padding(vertical = if (isHeader) 8.dp else 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        cells.forEachIndexed { i, cell ->
            val w = when (i) {
                0 -> 90.dp   // Time
                6 -> 60.dp   // Fall
                7 -> 70.dp   // Status
                else -> 72.dp
            }
            Box(modifier = Modifier.width(w), contentAlignment = Alignment.Center) {
                when {
                    i == 6 && !isHeader -> {
                        // Fall column — show icon
                        val fall = row?.fallDetected ?: false
                        Text(if (fall) "🚨" else "—", fontSize = 14.sp, textAlign = TextAlign.Center)
                    }
                    i == 7 && !isHeader -> {
                        // Status badge
                        val ok = row?.health?.overall == "normal"
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background((if (ok) Green else Red).copy(alpha = 0.15f))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                if (ok) "✓ OK" else "⚠ Alert",
                                color = if (ok) Green else Red,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    else -> Text(cell, color = textColor, fontSize = 13.sp, fontWeight = weight, textAlign = TextAlign.Center)
                }
            }
        }
    }
}

private fun rowCells(r: HistoryRow): List<String> {
    val ts = r.timestamp.take(19).let { ts ->
        val parts = ts.split("T", " ")
        if (parts.size >= 2) parts[1].take(8) else ts.take(8)
    }
    return listOf(
        ts,
        r.spo2.toString(),
        r.bpm.toString(),
        String.format(Locale.US, "%.1f", r.temperature),
        String.format(Locale.US, "%.0f", r.humidity),
        String.format(Locale.US, "%.1f", r.ph),
        if (r.fallDetected) "🚨" else "—",
        if (r.health.overall == "normal") "✓" else "⚠"
    )
}
