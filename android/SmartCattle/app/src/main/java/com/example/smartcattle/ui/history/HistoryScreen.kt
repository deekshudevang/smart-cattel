package com.example.smartcattle.ui.history

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun HistoryScreen() {
    Column(modifier = Modifier.padding(16.dp)) {
        Text("History & Graphs", style = MaterialTheme.typography.headlineMedium)
        Text("Chart placeholder for BPM, SpO2, Temp, pH, LDR, MEMS")
    }
}
