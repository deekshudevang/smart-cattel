package com.example.smartcattle.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.smartcattle.R
import com.example.smartcattle.ui.theme.healthColors
import com.example.smartcattle.ui.theme.spacing

enum class HealthStatusType {
    HEALTHY,
    WARNING,
    CRITICAL,
    OFFLINE
}

@Composable
fun HealthStatusChip(
    status: String,
    modifier: Modifier = Modifier,
    statusType: HealthStatusType? = null
) {
    val healthColors = MaterialTheme.healthColors
    val spacing = MaterialTheme.spacing

    val resolvedType = statusType ?: when (status.trim().lowercase()) {
        "healthy", "normal", "good", "connected" -> HealthStatusType.HEALTHY
        "warning", "check", "moderate", "reconnecting" -> HealthStatusType.WARNING
        "critical", "danger", "alert", "fallen", "fall_detected" -> HealthStatusType.CRITICAL
        else -> HealthStatusType.OFFLINE
    }

    val (baseColor: Color, icon: ImageVector, defaultLabelRes: Int) = when (resolvedType) {
        HealthStatusType.HEALTHY -> Triple(healthColors.healthy, Icons.Default.CheckCircle, R.string.status_healthy)
        HealthStatusType.WARNING -> Triple(healthColors.warning, Icons.Default.Warning, R.string.status_warning)
        HealthStatusType.CRITICAL -> Triple(healthColors.critical, Icons.Default.Error, R.string.status_critical)
        HealthStatusType.OFFLINE -> Triple(healthColors.offline, Icons.Default.WifiOff, R.string.status_offline)
    }

    val rawText = if (status.isNotBlank()) status else stringResource(defaultLabelRes)
    val displayText = rawText.lowercase().replaceFirstChar { it.uppercase() }

    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(baseColor.copy(alpha = 0.08f))
            .border(width = 1.dp, color = baseColor.copy(alpha = 0.22f), shape = CircleShape)
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(baseColor)
        )
        Text(
            text = displayText,
            color = baseColor,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
