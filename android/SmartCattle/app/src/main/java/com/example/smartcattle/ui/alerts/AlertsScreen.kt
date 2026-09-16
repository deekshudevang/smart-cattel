package com.example.smartcattle.ui.alerts

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun AlertsScreen() {
    Column(modifier = Modifier.padding(16.dp)) {
        Text("Alerts Log", style = MaterialTheme.typography.headlineMedium)
        Text("List of recent INFO, WARNING, and CRITICAL alerts")
    }
}
