package com.example.smartcattle.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.smartcattle.ui.theme.spacing
import java.util.Locale

@Composable
fun VitalTrendChart(
    title: String,
    dataPoints: List<Float>,
    unit: String = "",
    lineColor: Color = MaterialTheme.colorScheme.primary,
    modifier: Modifier = Modifier
) {
    val spacing = MaterialTheme.spacing

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        border = CardDefaults.outlinedCardBorder()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.sm)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title.uppercase(Locale.US),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (dataPoints.isNotEmpty()) {
                    val latest = dataPoints.last()
                    Text(
                        text = String.format(Locale.US, "%.1f %s", latest, unit),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = lineColor
                    )
                }
            }

            if (dataPoints.size < 2) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(100.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Collecting telemetry history...",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                val minVal = (dataPoints.minOrNull() ?: 0f) - 0.5f
                val maxVal = (dataPoints.maxOrNull() ?: 1f) + 0.5f
                val range = if (maxVal - minVal > 0.001f) maxVal - minVal else 1f

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp)
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val w = size.width
                        val h = size.height

                        // Horizontal baseline grid lines
                        val gridColor = Color.Gray.copy(alpha = 0.2f)
                        drawLine(gridColor, Offset(0f, 0f), Offset(w, 0f), strokeWidth = 1f)
                        drawLine(gridColor, Offset(0f, h / 2f), Offset(w, h / 2f), strokeWidth = 1f)
                        drawLine(gridColor, Offset(0f, h), Offset(w, h), strokeWidth = 1f)

                        val stepX = w / (dataPoints.size - 1)
                        val linePath = Path()
                        val fillPath = Path()

                        dataPoints.forEachIndexed { i, pt ->
                            val x = i * stepX
                            val normalized = (pt - minVal) / range
                            val y = h - (normalized * h)

                            if (i == 0) {
                                linePath.moveTo(x, y)
                                fillPath.moveTo(x, h)
                                fillPath.lineTo(x, y)
                            } else {
                                linePath.lineTo(x, y)
                                fillPath.lineTo(x, y)
                            }

                            // Draw data point circles
                            drawCircle(
                                color = lineColor,
                                radius = 4.dp.toPx(),
                                center = Offset(x, y)
                            )
                        }

                        fillPath.lineTo(w, h)
                        fillPath.close()

                        // Gradient fill under the curve
                        drawPath(
                            path = fillPath,
                            brush = Brush.verticalGradient(
                                colors = listOf(lineColor.copy(alpha = 0.35f), lineColor.copy(alpha = 0.02f)),
                                startY = 0f,
                                endY = h
                            )
                        )

                        // Outline line
                        drawPath(
                            path = linePath,
                            color = lineColor,
                            style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round)
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = String.format(Locale.US, "Min: %.1f", dataPoints.minOrNull() ?: 0f),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = String.format(Locale.US, "Max: %.1f", dataPoints.maxOrNull() ?: 0f),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
