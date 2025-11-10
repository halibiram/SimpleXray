package com.simplexray.an.ui.chain

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.simplexray.an.chain.pepper.PepperParams
import com.simplexray.an.chain.supervisor.ChainConfig
import com.simplexray.an.chain.supervisor.ChainState
import com.simplexray.an.viewmodel.ChainViewModel

/**
 * Chain Overview Screen
 * 
 * Displays status for each layer, start/stop controls, and quick actions
 */
@Composable
fun ChainScreen(
    viewModel: ChainViewModel = viewModel()
) {
    val status by viewModel.status.collectAsState()
    
    // Calculate failed layers
    val failedLayers = status.layers.values.filter { !it.isRunning && it.error != null }
    val isDegraded = status.state == ChainState.DEGRADED
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Header
        Text(
            text = "Tunneling Chain",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )
        
        // Degraded Mode Warning Banner
        if (isDegraded) {
            DegradedModeBanner(failedLayers = failedLayers)
        }
        
        // Status Card with enhanced information
        ChainStatusCard(
            status = status,
            failedLayersCount = failedLayers.size
        )
        
        // Layer Status List
        Text(
            text = "Layers",
            style = MaterialTheme.typography.titleMedium
        )
        
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(status.layers.values.toList()) { layer ->
                LayerStatusCard(layer = layer)
            }
        }
        
        // Control Buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = {
                    // Create demo config for testing
                    val demoConfig = createDemoConfig()
                    when (status.state) {
                        ChainState.STOPPED, ChainState.DEGRADED, ChainState.FAILED -> {
                            viewModel.startChain(demoConfig)
                        }
                        ChainState.RUNNING, ChainState.STARTING -> {
                            viewModel.stopChain()
                        }
                        ChainState.STOPPING -> {
                            // Do nothing
                        }
                    }
                },
                modifier = Modifier.weight(1f),
                enabled = status.state != ChainState.STOPPING
            ) {
                Text(
                    when (status.state) {
                        ChainState.STOPPED, ChainState.DEGRADED, ChainState.FAILED -> "Start Chain"
                        ChainState.RUNNING, ChainState.STARTING -> "Stop Chain"
                        ChainState.STOPPING -> "Stopping..."
                    }
                )
            }
        }
    }
}

@Composable
fun DegradedModeBanner(failedLayers: List<com.simplexray.an.chain.supervisor.LayerStatus>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Degraded Mode",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
                Text(
                    text = if (failedLayers.isNotEmpty()) {
                        "Failed layers: ${failedLayers.joinToString(", ") { it.name }}"
                    } else {
                        "Some critical layers failed to start"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
                if (failedLayers.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    failedLayers.forEach { layer ->
                        if (layer.error != null) {
                            Text(
                                text = "• ${layer.name}: ${layer.error}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ChainStatusCard(
    status: com.simplexray.an.chain.supervisor.ChainStatus,
    failedLayersCount: Int
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = when (status.state) {
                ChainState.RUNNING -> MaterialTheme.colorScheme.primaryContainer
                ChainState.DEGRADED -> MaterialTheme.colorScheme.errorContainer
                ChainState.FAILED -> MaterialTheme.colorScheme.errorContainer
                ChainState.STARTING -> MaterialTheme.colorScheme.secondaryContainer
                ChainState.STOPPING -> MaterialTheme.colorScheme.surfaceVariant
                ChainState.STOPPED -> MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = when (status.state) {
                        ChainState.RUNNING -> Icons.Default.CheckCircle
                        ChainState.DEGRADED -> Icons.Default.Warning
                        ChainState.FAILED -> Icons.Default.Error
                        ChainState.STARTING -> Icons.Default.PlayArrow
                        ChainState.STOPPING -> Icons.Default.Stop
                        ChainState.STOPPED -> Icons.Default.StopCircle
                    },
                    contentDescription = null,
                    tint = when (status.state) {
                        ChainState.RUNNING -> MaterialTheme.colorScheme.primary
                        ChainState.DEGRADED -> MaterialTheme.colorScheme.error
                        ChainState.FAILED -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
                Text(
                    text = when (status.state) {
                        ChainState.RUNNING -> "Running"
                        ChainState.DEGRADED -> "Degraded Mode"
                        ChainState.FAILED -> "Failed"
                        ChainState.STARTING -> "Starting..."
                        ChainState.STOPPING -> "Stopping..."
                        ChainState.STOPPED -> "Stopped"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            
            if (status.state == ChainState.DEGRADED && failedLayersCount > 0) {
                Text(
                    text = "$failedLayersCount layer(s) failed",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
            }
            
            if (status.uptime > 0) {
                Text(
                    text = "Uptime: ${formatUptime(status.uptime)}",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            
            if (status.totalBytesUp > 0 || status.totalBytesDown > 0) {
                Text(
                    text = "↑ ${formatBytes(status.totalBytesUp)} / ↓ ${formatBytes(status.totalBytesDown)}",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
fun LayerStatusCard(layer: com.simplexray.an.chain.supervisor.LayerStatus) {
    val isFailed = !layer.isRunning && layer.error != null
    
    Card(
        colors = CardDefaults.cardColors(
            containerColor = when {
                layer.isRunning -> MaterialTheme.colorScheme.primaryContainer
                isFailed -> MaterialTheme.colorScheme.errorContainer
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = when {
                            layer.isRunning -> Icons.Default.CheckCircle
                            isFailed -> Icons.Default.Error
                            else -> Icons.Default.Info
                        },
                        contentDescription = null,
                        tint = when {
                            layer.isRunning -> MaterialTheme.colorScheme.primary
                            isFailed -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                    Text(
                        text = layer.name.uppercase(),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
                if (layer.error != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = layer.error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
            
            // Status indicator
            Surface(
                color = when {
                    layer.isRunning -> MaterialTheme.colorScheme.primary
                    isFailed -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.surfaceVariant
                },
                shape = MaterialTheme.shapes.small
            ) {
                Text(
                    text = when {
                        layer.isRunning -> "READY"
                        isFailed -> "FAILED"
                        else -> "STOPPED"
                    },
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = when {
                        layer.isRunning -> MaterialTheme.colorScheme.onPrimary
                        isFailed -> MaterialTheme.colorScheme.onError
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
        }
    }
}

/**
 * Create a demo configuration for testing
 */
fun createDemoConfig(): ChainConfig {
    return ChainConfig(
        name = "Demo Profile",
        pepperParams = PepperParams(
            mode = com.simplexray.an.chain.pepper.PepperMode.BURST_FRIENDLY
        ),
        xrayConfigPath = "xray.json",
        tlsMode = ChainConfig.TlsMode.AUTO
    )
}

private fun formatUptime(ms: Long): String {
    val seconds = ms / 1000
    val minutes = seconds / 60
    val hours = minutes / 60
    return when {
        hours > 0 -> "${hours}h ${minutes % 60}m"
        minutes > 0 -> "${minutes}m ${seconds % 60}s"
        else -> "${seconds}s"
    }
}

private fun formatBytes(bytes: Long): String {
    return when {
        bytes >= 1_000_000_000 -> "${bytes / 1_000_000_000.0} GB"
        bytes >= 1_000_000 -> "${bytes / 1_000_000.0} MB"
        bytes >= 1_000 -> "${bytes / 1_000.0} KB"
        else -> "$bytes B"
    }
}

