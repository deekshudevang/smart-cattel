package com.example.smartcattle.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.smartcattle.ui.theme.healthColors
import com.example.smartcattle.ui.theme.spacing

@Composable
fun MetricCard(
    label: String,
    value: String,
    unit: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    statusText: String? = null,
    statusColor: Color? = null,
    subtitle: String? = null,
    isValid: Boolean = true,
    onClick: (() -> Unit)? = null
) {
    val spacing = MaterialTheme.spacing
    val healthColors = MaterialTheme.healthColors

    val resolvedStatusColor = statusColor ?: when (statusText?.lowercase()) {
        "healthy", "normal", "good" -> healthColors.healthy
        "warning", "check", "moderate" -> healthColors.warning
        "critical", "danger", "alert" -> healthColors.critical
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    val accessibilityDesc = "$label: $value $unit" + if (statusText != null) ", status: $statusText" else ""

    val baseModifier = modifier
        .fillMaxWidth()
        .semantics { contentDescription = accessibilityDesc }
    val finalCardModifier = if (onClick != null) {
        baseModifier.clickable { onClick() }
    } else {
        baseModifier
    }

    Card(
        modifier = finalCardModifier,
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        border = CardDefaults.outlinedCardBorder().copy(
            brush = androidx.compose.ui.graphics.SolidColor(
                if (isValid) MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f)
            )
        )
    ) {
        Column(
            modifier = Modifier
                .padding(spacing.md)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(spacing.xs)
        ) {
            // Header Row: Icon + Label + Status Chip
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(spacing.xs),
                    modifier = Modifier.weight(1f, fill = false)
                ) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                if (!statusText.isNullOrBlank()) {
                    Spacer(Modifier.width(spacing.xxs))
                    val formattedStatus = statusText.lowercase().replaceFirstChar { it.uppercase() }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(resolvedStatusColor)
                        )
                        Text(
                            text = formattedStatus,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            // Value + Unit
            AnimatedContent(
                targetState = value to unit,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "MetricValueAnimation"
            ) { (currentValue, currentUnit) ->
                Row(
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(spacing.xxs),
                    modifier = Modifier.padding(top = spacing.xxs)
                ) {
                    Text(
                        text = currentValue,
                        style = MaterialTheme.typography.headlineMedium.copy(
                            fontFeatureSettings = "tnum"
                        ),
                        fontWeight = FontWeight.SemiBold,
                        color = if (isValid) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val showUnit = isValid && currentValue != "Not Available" && currentUnit.isNotBlank()
                    if (showUnit) {
                        Text(
                            text = currentUnit,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 3.dp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            // Subtitle / Reason label (e.g., "No contact", "Sensor not connected", or target info)
            val displaySubtitle = subtitle ?: if (!isValid || value == "Not Available") "Not Available" else null
            if (!displaySubtitle.isNullOrBlank()) {
                Text(
                    text = displaySubtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (!isValid || value == "Not Available") MaterialTheme.colorScheme.error.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
