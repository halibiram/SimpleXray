package com.simplexray.an.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.simplexray.an.R
import com.simplexray.an.common.ROUTE_PERFORMANCE
import com.simplexray.an.common.ROUTE_GAMING
import com.simplexray.an.common.ROUTE_STREAMING
import com.simplexray.an.common.ROUTE_ADVANCED_ROUTING
import com.simplexray.an.common.ROUTE_TOPOLOGY
import com.simplexray.an.common.ROUTE_TRAFFIC_MONITOR
import com.simplexray.an.common.ROUTE_CHAIN
import com.simplexray.an.common.formatBytes
import com.simplexray.an.common.formatNumber
import com.simplexray.an.common.formatUptime
import com.simplexray.an.chain.supervisor.ChainState
import com.simplexray.an.viewmodel.ChainViewModel
import com.simplexray.an.viewmodel.MainViewModel
import kotlinx.coroutines.delay

@Composable
fun DashboardScreen(
    mainViewModel: MainViewModel,
    appNavController: NavHostController
) {
    val coreStats by mainViewModel.coreStatsState.collectAsState()
    val statsError by mainViewModel.statsErrorState.collectAsState()
    val isRefreshing by mainViewModel.isStatsRefreshing.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current
    var isVisible by remember { mutableStateOf(false) }
    
    // Chain status
    val chainViewModel: ChainViewModel = viewModel()
    val chainStatus by chainViewModel.status.collectAsState()
    val failedLayers = chainStatus.layers.values.filter { !it.isRunning && it.error != null }
    val isDegraded = chainStatus.state == ChainState.DEGRADED

    // Stop updates when screen is not visible
    LaunchedEffect(Unit) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            isVisible = true
            // Start periodic updates with configurable interval
            val updateInterval = mainViewModel.getStatsUpdateInterval()
            while (isVisible) {
                mainViewModel.updateCoreStats()
                delay(updateInterval)
            }
        }
    }
    
    // Stop updates when screen becomes invisible
    DisposableEffect(Unit) {
        onDispose {
            isVisible = false
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = 16.dp, end = 16.dp),
        contentPadding = PaddingValues(bottom = 16.dp, top = 16.dp)
    ) {
        // Show error message if stats update failed
        statsError?.let { error ->
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Text(
                        text = "Failed to update stats: $error",
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
        
        // Chain Status Card
        item {
            ChainStatusQuickCard(
                chainStatus = chainStatus,
                failedLayersCount = failedLayers.size,
                isDegraded = isDegraded,
                onClick = { appNavController.navigate(ROUTE_CHAIN) }
            )
            Spacer(modifier = Modifier.height(16.dp))
        }

        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.extraLarge),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                )
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        text = "Traffic",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    StatRow(
                        label = stringResource(id = R.string.stats_uplink),
                        value = formatBytes(coreStats.uplink)
                    )
                    StatRow(
                        label = stringResource(id = R.string.stats_downlink),
                        value = formatBytes(coreStats.downlink)
                    )
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.extraLarge),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                )
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        text = "Stats",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    StatRow(
                        label = stringResource(id = R.string.stats_num_goroutine),
                        value = formatNumber(coreStats.numGoroutine.toLong())
                    )
                    StatRow(
                        label = stringResource(id = R.string.stats_num_gc),
                        value = formatNumber(coreStats.numGC.toLong())
                    )
                    StatRow(
                        label = stringResource(id = R.string.stats_alloc),
                        value = formatBytes(coreStats.alloc)
                    )
                    StatRow(
                        label = stringResource(id = R.string.stats_uptime),
                        value = formatUptime(coreStats.uptime)
                    )
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.extraLarge),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                )
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        text = "Advanced Features",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )

                    FeatureRow(
                        title = "Performance Optimizer",
                        description = "Manage performance profiles & monitoring",
                        onClick = { appNavController.navigate(ROUTE_PERFORMANCE) }
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    FeatureRow(
                        title = "Gaming Optimization",
                        description = "Low latency settings for mobile games",
                        onClick = { appNavController.navigate(ROUTE_GAMING) }
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    FeatureRow(
                        title = "Streaming Optimization",
                        description = "Adaptive quality for video platforms",
                        onClick = { appNavController.navigate(ROUTE_STREAMING) }
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    FeatureRow(
                        title = "Advanced Routing",
                        description = "Policy-based routing & split tunneling",
                        onClick = { appNavController.navigate(ROUTE_ADVANCED_ROUTING) }
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    FeatureRow(
                        title = "Topology Graph",
                        description = "Interactive network topology visualization",
                        onClick = { appNavController.navigate(ROUTE_TOPOLOGY) }
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    FeatureRow(
                        title = "Traffic Monitor",
                        description = "Real-time speed & bandwidth monitoring",
                        onClick = { appNavController.navigate(ROUTE_TRAFFIC_MONITOR) }
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    FeatureRow(
                        title = "Tunneling Chain",
                        description = "Reality SOCKS + Hysteria2 + PepperShaper",
                        onClick = { appNavController.navigate(ROUTE_CHAIN) }
                    )
                }
            }
        }
    }
}

@Composable
fun FeatureRow(
    title: String,
    description: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = "Navigate",
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun StatRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
private fun ChainStatusQuickCard(
    chainStatus: com.simplexray.an.chain.supervisor.ChainStatus,
    failedLayersCount: Int,
    isDegraded: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.extraLarge)
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = when (chainStatus.state) {
                ChainState.RUNNING -> MaterialTheme.colorScheme.primaryContainer
                ChainState.DEGRADED -> MaterialTheme.colorScheme.errorContainer
                ChainState.FAILED -> MaterialTheme.colorScheme.errorContainer
                ChainState.STARTING -> MaterialTheme.colorScheme.secondaryContainer
                ChainState.STOPPING -> MaterialTheme.colorScheme.surfaceVariant
                ChainState.STOPPED -> MaterialTheme.colorScheme.surfaceContainer
            }
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = when (chainStatus.state) {
                            ChainState.RUNNING -> Icons.Default.CheckCircle
                            ChainState.DEGRADED -> Icons.Default.Warning
                            ChainState.FAILED -> Icons.Default.Error
                            ChainState.STARTING -> Icons.Default.PlayArrow
                            ChainState.STOPPING -> Icons.Default.Stop
                            ChainState.STOPPED -> Icons.Default.StopCircle
                        },
                        contentDescription = null,
                        tint = when (chainStatus.state) {
                            ChainState.RUNNING -> MaterialTheme.colorScheme.primary
                            ChainState.DEGRADED -> MaterialTheme.colorScheme.error
                            ChainState.FAILED -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                    Text(
                        text = "Chain Status",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = when (chainStatus.state) {
                        ChainState.RUNNING -> "All layers running"
                        ChainState.DEGRADED -> if (failedLayersCount > 0) {
                            "$failedLayersCount layer(s) failed"
                        } else {
                            "Degraded mode"
                        }
                        ChainState.FAILED -> "Chain failed"
                        ChainState.STARTING -> "Starting..."
                        ChainState.STOPPING -> "Stopping..."
                        ChainState.STOPPED -> "Stopped"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isDegraded || chainStatus.state == ChainState.FAILED) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    }
                )
                if (chainStatus.uptime > 0) {
                    Text(
                        text = "Uptime: ${formatUptime(chainStatus.uptime)}",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = "View details",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}