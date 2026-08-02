package com.expenselens.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.expenselens.ui.theme.Cream400
import kotlin.math.min

data class DonutSlice(
    val label: String,
    val value: Float,
    val color: Color
)

/**
 * ExpenseLens "Category Split" — a donut chart with a total in the middle
 * and a small legend on the side.
 */
@Composable
fun DonutChart(
    slices: List<DonutSlice>,
    totalLabel: String,
    modifier: Modifier = Modifier
) {
    val total = slices.sumOf { it.value.toDouble() }.toFloat().coerceAtLeast(0.0001f)
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.size(140.dp),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.size(140.dp)) {
                val side = min(size.width, size.height)
                val strokeWidth = side * 0.18f
                val topLeft = Offset(
                    (size.width - side) / 2 + strokeWidth / 2,
                    (size.height - side) / 2 + strokeWidth / 2
                )
                val arcSize = Size(side - strokeWidth, side - strokeWidth)

                // Background ring
                drawArc(
                    color = Cream400,
                    startAngle = -90f,
                    sweepAngle = 360f,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = strokeWidth)
                )

                var startAngle = -90f
                slices.forEach { slice ->
                    val sweep = (slice.value / total) * 360f
                    drawArc(
                        color = slice.color,
                        startAngle = startAngle,
                        sweepAngle = sweep,
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        style = Stroke(width = strokeWidth)
                    )
                    startAngle += sweep
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "Total",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = totalLabel,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Spacer(Modifier.width(20.dp))

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            slices.forEach { s ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .background(s.color, CircleShape)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = s.label,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = "${(s.value / total * 100).toInt()}%",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}
