package com.example.smartcattle.ui.previews

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bloodtype
import androidx.compose.material.icons.filled.DirectionsWalk
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.smartcattle.ui.components.*
import com.example.smartcattle.ui.theme.SmartCattleTheme
import com.example.smartcattle.ui.theme.ThemeMode

@Preview(name = "Light Mode Preview", showBackground = true)
@Composable
fun LightModePreview() {
    SmartCattleTheme(themeMode = ThemeMode.LIGHT) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.background
        ) {
            ComponentShowcase()
        }
    }
}

@Preview(name = "Dark Mode Preview", showBackground = true)
@Composable
fun DarkModePreview() {
    SmartCattleTheme(themeMode = ThemeMode.DARK) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.background
        ) {
            ComponentShowcase()
        }
    }
}

@Preview(name = "200% Font Scale Preview", fontScale = 2.0f, showBackground = true)
@Composable
fun LargeFontPreview() {
    SmartCattleTheme(themeMode = ThemeMode.DARK) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.background
        ) {
            ComponentShowcase()
        }
    }
}

@Preview(name = "Small Screen Preview", widthDp = 320, heightDp = 640, showBackground = true)
@Composable
fun SmallScreenPreview() {
    SmartCattleTheme(themeMode = ThemeMode.DARK) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            ComponentShowcase()
        }
    }
}

@Preview(name = "Tablet Landscape Preview", widthDp = 840, heightDp = 600, showBackground = true)
@Composable
fun TabletPreview() {
    SmartCattleTheme(themeMode = ThemeMode.LIGHT) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            ComponentShowcase()
        }
    }
}

@Composable
private fun ComponentShowcase() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Connection Banner
        ConnectionBanner(
            isConnected = true,
            serverUrl = "ws://192.168.1.3:8000/ws/cattle/CATTLE-ALL",
            lastUpdateText = "12:20:00",
            onConfigureClick = {},
            onRetryClick = {}
        )

        // Section Header
        SectionHeader(
            title = "REAL-TIME BIOMETRICS",
            subtitle = "Active ADXL345 & MAX30102 live stream",
            icon = Icons.Default.DirectionsWalk
        )

        // Health Status Chips
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HealthStatusChip(status = "Healthy", statusType = HealthStatusType.HEALTHY)
            HealthStatusChip(status = "Warning", statusType = HealthStatusType.WARNING)
            HealthStatusChip(status = "Critical", statusType = HealthStatusType.CRITICAL)
            HealthStatusChip(status = "Offline", statusType = HealthStatusType.OFFLINE)
        }

        // Metric Cards Grid
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(Modifier.weight(1f)) {
                MetricCard(
                    label = "Steps Today",
                    value = "1,420",
                    unit = "steps",
                    icon = Icons.Default.DirectionsWalk,
                    statusText = "Active"
                )
            }
            Box(Modifier.weight(1f)) {
                MetricCard(
                    label = "Heart Rate",
                    value = "72",
                    unit = "bpm",
                    icon = Icons.Default.Favorite,
                    statusText = "Normal"
                )
            }
        }

        // Buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SmartPrimaryButton(
                text = "Primary Action",
                onClick = {},
                modifier = Modifier.weight(1f)
            )
            SmartSecondaryButton(
                text = "Secondary",
                onClick = {},
                modifier = Modifier.weight(1f)
            )
        }
    }
}
