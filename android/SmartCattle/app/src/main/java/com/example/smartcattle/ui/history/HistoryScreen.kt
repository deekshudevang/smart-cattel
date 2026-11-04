package com.example.smartcattle.ui.history

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.smartcattle.data.model.HistoryRow
import com.example.smartcattle.viewmodel.CattleViewModel
import java.util.Locale

private val BgDark  = Color(0xFF0D1117)
private val BgCard  = Color(0xFF161B22)
private val Border  = Color(0xFF30363D)
private val Green   = Color(0xFF2EA043)
private val Red     = Color(0xFFF85149)
private val Amber   = Color(0xFFD29922)
private val Muted   = Color(0xFF8B949E)

// Column headers shown in the table
private val HEADERS = listOf("Time", "SpO2%", "Heart Rate", "Temp°C", "Hum%", "pH", "Fall", "Status")

@Composable
fun HistoryScreen(viewModel: CattleViewModel) {
    val rows by viewModel.history.collectAsState()

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
                imageVector = Icons.Default.History,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(22.dp)
            )
            Text("History", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(4.dp))
        Text("Last ${rows.size} readings", color = Muted, fontSize = 12.sp)
        Spacer(Modifier.height(16.dp))

        if (rows.isEmpty()) {
            Box(Modifier.fillMaxWidth().padding(top = 40.dp), contentAlignment = Alignment.Center) {
                Text("No history yet. Waiting for telemetry…", color = Muted, fontSize = 13.sp)
            }
            return@Column
        }

        // Scrollable horizontal table
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(BgCard)
                .border(androidx.compose.foundation.BorderStroke(1.dp, Border), RoundedCornerShape(10.dp))
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
    val textColor = if (isHeader) Muted else Color.White
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
                7 -> 76.dp   // Status
                else -> 72.dp
            }
            Box(modifier = Modifier.width(w), contentAlignment = Alignment.Center) {
                when {
                    i == 6 && !isHeader -> {
                        // Fall column — clean status
                        val fall = row?.fallDetected ?: false
                        if (fall) {
                            Surface(
                                shape = CircleShape,
                                color = Red.copy(alpha = 0.12f),
                                border = androidx.compose.foundation.BorderStroke(1.dp, Red.copy(alpha = 0.25f))
                            ) {
                                Text(
                                    text = "Fall",
                                    color = Red,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        } else {
                            Text("—", color = Muted, fontSize = 13.sp, textAlign = TextAlign.Center)
                        }
                    }
                    i == 7 && !isHeader -> {
                        // Status badge
                        val ok = row?.health?.overall == "normal"
                        val stColor = if (ok) Green else Amber
                        Surface(
                            shape = CircleShape,
                            color = stColor.copy(alpha = 0.12f),
                            border = androidx.compose.foundation.BorderStroke(1.dp, stColor.copy(alpha = 0.25f))
                        ) {
                            Text(
                                text = if (ok) "Normal" else "Warning",
                                color = stColor,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                    else -> Text(
                        text = cell,
                        color = textColor,
                        fontSize = 13.sp,
                        fontWeight = weight,
                        textAlign = TextAlign.Center,
                        style = TextStyle(fontFeatureSettings = "tnum")
                    )
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
        r.heart_rate.toString(),
        r.temperature?.let { if (it == 0f) "Not Available" else String.format(Locale.US, "%.1f", it) } ?: "Not Available",
        r.humidity?.let { if (it == 0f) "Not Available" else String.format(Locale.US, "%.0f", it) } ?: "Not Available",
        String.format(Locale.US, "%.1f", r.ph),
        if (r.fallDetected) "FALL" else "—",
        if (r.health.overall == "normal") "OK" else "WARN"
    )
}
