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
    onConfigSelected: (realityConfig: RealityConfig?) -> Unit = { _ -> }
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
        }
        
        // Tab Content
        VlessRealityConfigTable(
            viewModel = viewModel,
            onConfigSelected = { realityConfig ->
                onConfigSelected(realityConfig)
            }
        )
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

// Hysteria2 selection removed

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

// Hysteria2 card removed

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








