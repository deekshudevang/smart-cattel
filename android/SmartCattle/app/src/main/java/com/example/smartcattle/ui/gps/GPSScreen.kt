package com.example.smartcattle.ui.gps

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun GPSScreen() {
    Column(modifier = Modifier.padding(16.dp)) {
        Text("GPS Location", style = MaterialTheme.typography.headlineMedium)
        Text("Latitude: 0.000000")
        Text("Longitude: 0.000000")
    }
}
