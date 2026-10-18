package com.example.smartcattle.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.smartcattle.R
import com.example.smartcattle.ui.theme.healthColors
import com.example.smartcattle.ui.theme.spacing

@Composable
fun ConnectionBanner(
    isConnected: Boolean,
    serverUrl: String,
    modifier: Modifier = Modifier,
    isReconnecting: Boolean = false,
    lastUpdateText: String? = null,
    onConfigureClick: () -> Unit,
    onRetryClick: () -> Unit
) {
    val spacing = MaterialTheme.spacing
    val healthColors = MaterialTheme.healthColors

    val targetBgColor = when {
        isReconnecting -> healthColors.warning.copy(alpha = 0.12f)
        isConnected -> healthColors.healthy.copy(alpha = 0.08f)
        else -> healthColors.critical.copy(alpha = 0.12f)
    }

    val targetBorderColor = when {
        isReconnecting -> healthColors.warning.copy(alpha = 0.4f)
        isConnected -> healthColors.healthy.copy(alpha = 0.3f)
        else -> healthColors.critical.copy(alpha = 0.4f)
    }

    val targetTextColor = when {
        isReconnecting -> healthColors.warning
        isConnected -> healthColors.healthy
        else -> healthColors.critical
    }

    val bgColor by animateColorAsState(targetValue = targetBgColor, label = "BannerBg")
    val borderColor by animateColorAsState(targetValue = targetBorderColor, label = "BannerBorder")
    val textColor by animateColorAsState(targetValue = targetTextColor, label = "BannerText")

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(bgColor)
            .border(1.dp, borderColor, MaterialTheme.shapes.small),
        color = Color.Transparent
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = spacing.md, vertical = spacing.xs)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                modifier = Modifier.weight(1f)
            ) {
                // Status Indicator Dot or Icon
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(textColor)
                )

                Column(
                    verticalArrangement = Arrangement.Center
                ) {
                    val statusText = when {
                        isReconnecting -> stringResource(R.string.status_reconnecting)
                        isConnected -> stringResource(R.string.status_connected)
                        else -> stringResource(R.string.status_offline)
                    }

                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = textColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    val secondaryText = if (!lastUpdateText.isNullOrBlank()) {
                        "${stringResource(R.string.last_sync)} $lastUpdateText"
                    } else {
                        serverUrl
                    }

                    Text(
                        text = secondaryText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // Action Buttons with touch targets
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.xxs)
            ) {
                if (!isConnected && !isReconnecting) {
                    IconButton(
                        onClick = onRetryClick,
                        modifier = Modifier.size(spacing.minTouchTarget)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = stringResource(R.string.action_retry),
                            tint = textColor
                        )
                    }
                }
                IconButton(
                    onClick = onConfigureClick,
                    modifier = Modifier.size(spacing.minTouchTarget)
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = stringResource(R.string.action_settings),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
