package com.example.smartcattle.ui.dashboard

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.smartcattle.viewmodel.CattleViewModel

@Composable
fun DashboardScreen(viewModel: CattleViewModel) {
    val cattleData = viewModel.latestData.collectAsState()

    Column(modifier = Modifier.padding(16.dp)) {
        Text(text = "Smart Cattle Dashboard", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(16.dp))
        
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Latest Status: ${cattleData.value?.overallStatus ?: "Loading..."}")
                Text("SpO2: ${cattleData.value?.spo2 ?: "--"}%")
                Text("BPM: ${cattleData.value?.bpm ?: "--"}")
            }
        }
    }
}
