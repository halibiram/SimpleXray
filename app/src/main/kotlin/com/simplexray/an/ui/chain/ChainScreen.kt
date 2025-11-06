package com.simplexray.an.ui.chain

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.simplexray.an.chain.hysteria2.Hy2Config
import com.simplexray.an.chain.pepper.PepperParams
import com.simplexray.an.chain.reality.RealityConfig
import com.simplexray.an.chain.reality.TlsFingerprintProfile
import com.simplexray.an.chain.supervisor.ChainConfig
import com.simplexray.an.chain.supervisor.ChainState
import com.simplexray.an.viewmodel.ChainViewModel
import java.net.InetSocketAddress

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
        
        // Status Card
        Card {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Status: ${status.state.name}",
                    style = MaterialTheme.typography.titleMedium
                )
                if (status.uptime > 0) {
                    Text(
                        text = "Uptime: ${formatUptime(status.uptime)}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                Text(
                    text = "↑ ${formatBytes(status.totalBytesUp)} / ↓ ${formatBytes(status.totalBytesDown)}",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
        
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
fun LayerStatusCard(layer: com.simplexray.an.chain.supervisor.LayerStatus) {
    Card {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = layer.name.uppercase(),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                if (layer.error != null) {
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
                    layer.isRunning -> MaterialTheme.colorScheme.primaryContainer
                    layer.error != null -> MaterialTheme.colorScheme.errorContainer
                    else -> MaterialTheme.colorScheme.surfaceVariant
                },
                shape = MaterialTheme.shapes.small
            ) {
                Text(
                    text = if (layer.isRunning) "RUNNING" else "STOPPED",
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelSmall
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
        realityConfig = RealityConfig(
            server = "example.com",
            port = 443,
            shortId = "demo",
            publicKey = "demo-key",
            serverName = "example.com",
            fingerprintProfile = TlsFingerprintProfile.CHROME,
            localPort = 10808
        ),
        hysteria2Config = Hy2Config(
            server = "example.com",
            port = 443,
            auth = "demo-auth",
            alpn = "h3",
            upstreamSocksAddr = InetSocketAddress("127.0.0.1", 10808)
        ),
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

