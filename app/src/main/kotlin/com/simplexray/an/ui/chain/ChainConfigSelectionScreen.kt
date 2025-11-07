package com.simplexray.an.ui.chain

import android.util.Log
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.gson.JsonParser
import com.simplexray.an.chain.hysteria2.Hy2Config
import com.simplexray.an.chain.reality.RealityConfig
import com.simplexray.an.service.TProxyService
import com.simplexray.an.viewmodel.ChainConfigViewModel
import java.io.File

/**
 * Chain Config Selection Screen
 * 
 * Tab-based interface for selecting:
 * - VLESS Reality configs (from Xray config files)
 * - Hysteria2 configs (from Hysteria2 JSON files)
 */
@Composable
fun ChainConfigSelectionScreen(
    viewModel: ChainConfigViewModel = viewModel(),
    onConfigSelected: (realityConfig: RealityConfig?, hysteria2Config: Hy2Config?) -> Unit = { _, _ -> }
) {
    var selectedTab by remember { mutableStateOf(0) }
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Header
        Text(
            text = "Chain Configuration",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 16.dp)
        )
        
        // Tabs
        TabRow(selectedTabIndex = selectedTab) {
            Tab(
                selected = selectedTab == 0,
                onClick = { selectedTab = 0 },
                text = { Text("VLESS Reality") }
            )
            Tab(
                selected = selectedTab == 1,
                onClick = { selectedTab = 1 },
                text = { Text("Hysteria2") }
            )
        }
        
        // Tab Content
        when (selectedTab) {
            0 -> VlessRealityConfigTable(
                viewModel = viewModel,
                onConfigSelected = { realityConfig ->
                    onConfigSelected(realityConfig, viewModel.selectedHysteria2Config.value)
                }
            )
            1 -> Hysteria2ConfigTable(
                viewModel = viewModel,
                onConfigSelected = { hysteria2Config ->
                    onConfigSelected(viewModel.selectedRealityConfig.value, hysteria2Config)
                }
            )
        }
    }
}

/**
 * VLESS Reality Config Selection Table
 * 
 * Lists Xray config files and extracts Reality configs from them
 */
@Composable
fun VlessRealityConfigTable(
    viewModel: ChainConfigViewModel,
    onConfigSelected: (RealityConfig?) -> Unit
) {
    val xrayConfigs by viewModel.xrayConfigs.collectAsState()
    val selectedRealityConfig by viewModel.selectedRealityConfig.collectAsState()
    
    LaunchedEffect(Unit) {
        viewModel.loadXrayConfigs()
    }
    
    if (xrayConfigs.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "No Xray config files found.\nAdd config files in the Config screen.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(vertical = 8.dp)
        ) {
            items(xrayConfigs, key = { it.first.absolutePath }) { (file, realityConfig) ->
                VlessRealityConfigCard(
                    fileName = file.name,
                    filePath = file.absolutePath,
                    realityConfig = realityConfig,
                    isSelected = realityConfig?.let { 
                        selectedRealityConfig?.server == it.server &&
                        selectedRealityConfig?.port == it.port &&
                        selectedRealityConfig?.publicKey == it.publicKey
                    } ?: false,
                    onClick = {
                        viewModel.selectRealityConfig(realityConfig)
                        onConfigSelected(realityConfig)
                    }
                )
            }
        }
    }
}

/**
 * Hysteria2 Config Selection Table
 * 
 * Lists Hysteria2 config files
 */
@Composable
fun Hysteria2ConfigTable(
    viewModel: ChainConfigViewModel,
    onConfigSelected: (Hy2Config?) -> Unit
) {
    val hysteria2Configs by viewModel.hysteria2Configs.collectAsState()
    val selectedHysteria2Config by viewModel.selectedHysteria2Config.collectAsState()
    
    LaunchedEffect(Unit) {
        viewModel.loadHysteria2Configs()
    }
    
    if (hysteria2Configs.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "No Hysteria2 config files found.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "Import Hysteria2 configs from x-ui or create new ones.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(vertical = 8.dp)
        ) {
            items(hysteria2Configs, key = { it.first.absolutePath }) { (file, hy2Config) ->
                Hysteria2ConfigCard(
                    fileName = file.name,
                    filePath = file.absolutePath,
                    hy2Config = hy2Config,
                    isSelected = hy2Config?.let {
                        selectedHysteria2Config?.server == it.server &&
                        selectedHysteria2Config?.port == it.port &&
                        selectedHysteria2Config?.auth == it.auth
                    } ?: false,
                    onClick = {
                        viewModel.selectHysteria2Config(hy2Config)
                        onConfigSelected(hy2Config)
                    }
                )
            }
        }
    }
}

/**
 * VLESS Reality Config Card
 */
@Composable
fun VlessRealityConfigCard(
    fileName: String,
    filePath: String,
    realityConfig: RealityConfig?,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) 
                MaterialTheme.colorScheme.secondaryContainer 
            else 
                MaterialTheme.colorScheme.surfaceContainerHighest
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = fileName.removeSuffix(".json"),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                
                Spacer(modifier = Modifier.height(4.dp))
                
                if (realityConfig != null) {
                    ConfigInfoRow("Server", "${realityConfig.server}:${realityConfig.port}")
                    ConfigInfoRow("SNI", realityConfig.serverName)
                    ConfigInfoRow("Fingerprint", realityConfig.fingerprintProfile.name)
                } else {
                    Text(
                        text = "No Reality config found in this file",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            
            Icon(
                imageVector = if (isSelected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                contentDescription = if (isSelected) "Selected" else "Not selected",
                tint = if (isSelected) 
                    MaterialTheme.colorScheme.primary 
                else 
                    MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

/**
 * Hysteria2 Config Card
 */
@Composable
fun Hysteria2ConfigCard(
    fileName: String,
    filePath: String,
    hy2Config: Hy2Config?,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) 
                MaterialTheme.colorScheme.secondaryContainer 
            else 
                MaterialTheme.colorScheme.surfaceContainerHighest
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = fileName.removeSuffix(".json"),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                
                Spacer(modifier = Modifier.height(4.dp))
                
                if (hy2Config != null) {
                    ConfigInfoRow("Server", "${hy2Config.server}:${hy2Config.port}")
                    if (!hy2Config.sni.isNullOrBlank()) {
                        ConfigInfoRow("SNI", hy2Config.sni)
                    }
                    ConfigInfoRow("ALPN", hy2Config.alpn)
                    if (hy2Config.upRateMbps > 0 || hy2Config.downRateMbps > 0) {
                        ConfigInfoRow(
                            "Bandwidth", 
                            "${hy2Config.downRateMbps}↓/${hy2Config.upRateMbps}↑ Mbps"
                        )
                    }
                } else {
                    Text(
                        text = "Invalid Hysteria2 config",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
            
            Icon(
                imageVector = if (isSelected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                contentDescription = if (isSelected) "Selected" else "Not selected",
                tint = if (isSelected) 
                    MaterialTheme.colorScheme.primary 
                else 
                    MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

@Composable
private fun ConfigInfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "$label:",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium
        )
    }
}



