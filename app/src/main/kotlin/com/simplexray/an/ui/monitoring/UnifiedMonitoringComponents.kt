package com.simplexray.an.ui.monitoring

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.simplexray.an.protocol.visualization.NetworkConnection
import com.simplexray.an.protocol.visualization.NetworkNode
import com.simplexray.an.protocol.visualization.NetworkTopology
import com.simplexray.an.protocol.visualization.TimeSeriesData
import kotlin.math.max

/**
 * Lightweight network topology visualization used by the unified monitoring screen.
 */
@Composable
fun NetworkTopologyView(
    topology: NetworkTopology,
    modifier: Modifier = Modifier
) {
    if (topology.nodes.isEmpty()) {
        Box(
            modifier = modifier.background(MaterialTheme.colorScheme.surface),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "No topology data available",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }

    Box(modifier = modifier) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            // Draw connections first so that nodes appear on top of them
            topology.connections.forEach { connection ->
                val fromNode = topology.nodes.find { it.id == connection.fromNodeId }
                val toNode = topology.nodes.find { it.id == connection.toNodeId }

                if (fromNode != null && toNode != null) {
                    val connectionColor = when (connection.status) {
                        NetworkConnection.ConnectionStatus.ESTABLISHED -> Color(0xFF4CAF50)
                        NetworkConnection.ConnectionStatus.CONNECTING -> Color(0xFFFFC107)
                        NetworkConnection.ConnectionStatus.DISCONNECTED -> Color(0xFF9E9E9E)
                        NetworkConnection.ConnectionStatus.ERROR -> Color(0xFFF44336)
                    }

                    drawLine(
                        color = connectionColor,
                        start = fromNode.position,
                        end = toNode.position,
                        strokeWidth = 3f,
                        cap = StrokeCap.Round,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f))
                    )
                }
            }

            // Draw nodes on top of connections
            topology.nodes.forEach { node ->
                val nodeColor = when (node.status) {
                    NetworkNode.NodeStatus.ACTIVE -> Color(0xFF2196F3)
                    NetworkNode.NodeStatus.INACTIVE -> Color(0xFF9E9E9E)
                    NetworkNode.NodeStatus.WARNING -> Color(0xFFFFC107)
                    NetworkNode.NodeStatus.ERROR -> Color(0xFFF44336)
                }

                // Outer glow
                drawCircle(
                    color = nodeColor.copy(alpha = 0.3f),
                    radius = 45f,
                    center = node.position
                )

                // Base circle
                drawCircle(
                    color = Color.White,
                    radius = 35f,
                    center = node.position
                )

                // Border
                drawCircle(
                    color = nodeColor,
                    radius = 33f,
                    center = node.position,
                    style = Stroke(width = 3f)
                )
            }
        }

        // Labels overlay to keep them crisp
        topology.nodes.forEach { node ->
            androidx.compose.foundation.layout.Column(
                modifier = Modifier
                    .offset(x = (node.position.x - 50).dp, y = (node.position.y + 42).dp)
                    .width(100.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = node.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = node.type.name.replace("_", " "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * Simplified time series chart used by the unified monitoring screen.
 */
@Composable
fun TimeSeriesChart(
    data: List<TimeSeriesData>,
    modifier: Modifier = Modifier
) {
    if (data.isEmpty() || data.all { it.dataPoints.isEmpty() }) {
        Box(
            modifier = modifier,
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "No data available",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }

    Canvas(modifier = modifier) {
        val chartWidth = size.width
        val chartHeight = size.height
        val padding = 32f

        data.forEach { series ->
            if (series.dataPoints.isEmpty()) return@forEach

            val points = series.dataPoints
            val maxValue = points.maxOfOrNull { it.value } ?: 1f
            val minValue = points.minOfOrNull { it.value } ?: 0f
            val valueRange = max(maxValue - minValue, 1f)

            val path = Path()
            points.forEachIndexed { index, point ->
                val x = padding + (index.toFloat() / (points.size - 1).coerceAtLeast(1)) * (chartWidth - 2 * padding)
                val y = chartHeight - padding - ((point.value - minValue) / valueRange) * (chartHeight - 2 * padding)

                if (index == 0) {
                    path.moveTo(x, y)
                } else {
                    path.lineTo(x, y)
                }
            }

            val lineColor = Color(series.color)
            drawPath(
                path = path,
                color = lineColor,
                style = Stroke(width = 3f, cap = StrokeCap.Round)
            )

            // Highlight the latest point
            val lastPoint = points.last()
            val lastX = padding + ((points.size - 1).toFloat() / (points.size - 1).coerceAtLeast(1)) * (chartWidth - 2 * padding)
            val lastY = chartHeight - padding - ((lastPoint.value - minValue) / valueRange) * (chartHeight - 2 * padding)
            drawCircle(color = lineColor, radius = 6f, center = Offset(lastX, lastY))
            drawCircle(color = Color.White, radius = 3f, center = Offset(lastX, lastY))
        }
    }
}
