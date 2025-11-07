package com.simplexray.an.ui.visualization

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.simplexray.an.protocol.visualization.NetworkTopology
import com.simplexray.an.protocol.visualization.NetworkConnection
import com.simplexray.an.protocol.visualization.NetworkNode

/**
 * Network topology visualization component
 */
@Composable
fun NetworkTopologyView(
    topology: NetworkTopology,
    modifier: Modifier = Modifier
) {
    // If no nodes or connections, show empty state
    if (topology.nodes.isEmpty() && topology.connections.isEmpty()) {
        Box(
            modifier = modifier,
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(16.dp)
            ) {
                Text(
                    text = "No network topology data available",
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Configure 'Online IP Stat Name' in Settings to enable topology visualization.\n\nExample: user>>ip",
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        return
    }

    NetworkTopologyGraph(
        topology = topology,
        modifier = modifier
    )
}

/**
 * Network topology graph component that draws nodes and connections on Canvas
 */
@Composable
fun NetworkTopologyGraph(
    topology: NetworkTopology,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    // Calculate layout if positions are not set or need adjustment
    val layoutNodes = remember(topology.nodes, topology.connections) {
        layoutTopologyNodes(topology)
    }

    Canvas(modifier = modifier) {
        val canvasWidth = size.width
        val canvasHeight = size.height

        // Draw connections first (so they appear behind nodes)
        topology.connections.forEach { connection ->
            val fromNode = layoutNodes.find { it.id == connection.fromNodeId }
            val toNode = layoutNodes.find { it.id == connection.toNodeId }

            if (fromNode != null && toNode != null) {
                val connectionColor = when (connection.status) {
                    NetworkConnection.ConnectionStatus.ESTABLISHED -> Color(0xFF4CAF50)
                    NetworkConnection.ConnectionStatus.CONNECTING -> Color(0xFFFFC107)
                    NetworkConnection.ConnectionStatus.DISCONNECTED -> Color(0xFF9E9E9E)
                    NetworkConnection.ConnectionStatus.ERROR -> Color(0xFFF44336)
                }

                // Animated connection line
                drawLine(
                    color = connectionColor.copy(alpha = pulseAlpha),
                    start = fromNode.position,
                    end = toNode.position,
                    strokeWidth = 3f,
                    cap = StrokeCap.Round,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f))
                )

                // Latency label in the middle
                val midPoint = Offset(
                    (fromNode.position.x + toNode.position.x) / 2,
                    (fromNode.position.y + toNode.position.y) / 2 - 20
                )
                drawCircle(
                    color = Color.White,
                    radius = 20f,
                    center = midPoint
                )
                drawCircle(
                    color = connectionColor,
                    radius = 18f,
                    center = midPoint,
                    style = Stroke(width = 2f)
                )
                
                // Draw latency text
                drawContext.canvas.nativeCanvas.apply {
                    save()
                    translate(midPoint.x, midPoint.y)
                    val paint = android.graphics.Paint().apply {
                        color = android.graphics.Color.BLACK
                        textSize = 24f
                        textAlign = android.graphics.Paint.Align.CENTER
                        isAntiAlias = true
                    }
                    drawText("${connection.latency}ms", 0f, 8f, paint)
                    restore()
                }
            }
        }

        // Draw nodes
        layoutNodes.forEach { node ->
            val nodeColor = when (node.status) {
                NetworkNode.NodeStatus.ACTIVE -> Color(0xFF2196F3)
                NetworkNode.NodeStatus.INACTIVE -> Color(0xFF9E9E9E)
                NetworkNode.NodeStatus.WARNING -> Color(0xFFFFC107)
                NetworkNode.NodeStatus.ERROR -> Color(0xFFF44336)
            }

            // Outer circle (glow effect)
            drawCircle(
                color = nodeColor.copy(alpha = 0.3f),
                radius = 50f,
                center = node.position
            )

            // Main circle
            drawCircle(
                color = Color.White,
                radius = 40f,
                center = node.position
            )

            // Border
            drawCircle(
                color = nodeColor,
                radius = 38f,
                center = node.position,
                style = Stroke(width = 3f)
            )

            // Inner circle
            drawCircle(
                color = nodeColor.copy(alpha = 0.2f),
                radius = 30f,
                center = node.position
            )
        }
    }

    // Node labels (overlay)
    Box(modifier = modifier) {
        layoutNodes.forEach { node ->
            Column(
                modifier = Modifier
                    .offset(
                        x = with(density) { (node.position.x - 50f).toDp() },
                        y = with(density) { (node.position.y + 50f).toDp() }
                    )
                    .width(100.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = node.label,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold
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
 * Layout nodes in a circular or grid pattern if positions are not properly set
 */
private fun layoutTopologyNodes(topology: NetworkTopology): List<NetworkNode> {
    if (topology.nodes.isEmpty()) return emptyList()
    
    // Check if nodes have valid positions
    val hasValidPositions = topology.nodes.all { 
        it.position.x > 0 && it.position.y > 0 
    }
    
    if (hasValidPositions) {
        return topology.nodes
    }
    
    // Layout nodes in a circular pattern
    val nodeCount = topology.nodes.size
    val centerX = 400f
    val centerY = 300f
    val radius = 200f
    
    return topology.nodes.mapIndexed { index, node ->
        val angle = (2 * Math.PI * index) / nodeCount
        val x = centerX + radius * Math.cos(angle).toFloat()
        val y = centerY + radius * Math.sin(angle).toFloat()
        node.copy(position = Offset(x, y))
    }
}
