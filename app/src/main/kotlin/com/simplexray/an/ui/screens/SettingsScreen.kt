package com.simplexray.an.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.result.ActivityResultLauncher
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.simplexray.an.ui.components.UpdateDownloadBottomSheet
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simplexray.an.BuildConfig
import com.simplexray.an.R
import com.simplexray.an.common.ThemeMode
import com.simplexray.an.performance.PerformanceManager
import com.simplexray.an.viewmodel.MainViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    mainViewModel: MainViewModel,
    geoipFilePickerLauncher: ActivityResultLauncher<Array<String>>,
    geositeFilePickerLauncher: ActivityResultLauncher<Array<String>>,
    scrollState: androidx.compose.foundation.ScrollState,
) {
    val context = LocalContext.current
    val settingsState by mainViewModel.settingsState.collectAsStateWithLifecycle()
    val geoipProgress by mainViewModel.geoipDownloadProgress.collectAsStateWithLifecycle()
    val geositeProgress by mainViewModel.geositeDownloadProgress.collectAsStateWithLifecycle()
    val isCheckingForUpdates by mainViewModel.isCheckingForUpdates.collectAsStateWithLifecycle()
    val newVersionTag by mainViewModel.newVersionAvailable.collectAsStateWithLifecycle()
    val isDownloadingUpdate by mainViewModel.isDownloadingUpdate.collectAsStateWithLifecycle()
    val downloadProgress by mainViewModel.downloadProgress.collectAsStateWithLifecycle()
    val downloadCompletion by mainViewModel.downloadCompletion.collectAsStateWithLifecycle()

    val vpnDisabled = settingsState.switches.disableVpn

    var showGeoipDeleteDialog by remember { mutableStateOf(false) }
    var showGeositeDeleteDialog by remember { mutableStateOf(false) }

    val sheetState = rememberModalBottomSheetState()
    val scope = rememberCoroutineScope()

    var editingRuleFile by remember { mutableStateOf<String?>(null) }
    var ruleFileUrl by remember { mutableStateOf("") }

    val themeOptions = listOf(
        ThemeMode.Light,
        ThemeMode.Dark,
        ThemeMode.Auto
    )
    var selectedThemeOption by remember { mutableStateOf(settingsState.switches.themeMode) }
    var themeExpanded by remember { mutableStateOf(false) }

    if (editingRuleFile != null) {
        ModalBottomSheet(
            onDismissRequest = { editingRuleFile = null },
            sheetState = sheetState
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                OutlinedTextField(
                    value = ruleFileUrl,
                    onValueChange = { ruleFileUrl = it },
                    label = { Text("URL") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp, end = 12.dp),
                    trailingIcon = {
                        val clipboardManager = LocalClipboard.current
                        IconButton(onClick = {
                            scope.launch {
                                clipboardManager.getClipEntry()?.clipData?.getItemAt(0)?.text
                                    .let {
                                        ruleFileUrl = it.toString()
                                    }
                            }
                        }) {
                            Icon(
                                painter = painterResource(id = R.drawable.paste),
                                contentDescription = "Paste"
                            )
                        }
                    }
                )

                Spacer(modifier = Modifier.height(8.dp))

                TextButton(onClick = {
                    ruleFileUrl =
                        if (editingRuleFile == "geoip.dat") context.getString(R.string.geoip_url)
                        else context.getString(R.string.geosite_url)
                }) {
                    Text(stringResource(id = R.string.restore_default_url))
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(end = 8.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = {
                        scope.launch { sheetState.hide() }.invokeOnCompletion {
                            if (!sheetState.isVisible) {
                                editingRuleFile = null
                            }
                        }
                    }) {
                        Text(stringResource(R.string.cancel))
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(onClick = {
                        mainViewModel.downloadRuleFile(ruleFileUrl, editingRuleFile!!)
                        scope.launch { sheetState.hide() }.invokeOnCompletion {
                            if (!sheetState.isVisible) {
                                editingRuleFile = null
                            }
                        }
                    }) {
                        Text(stringResource(R.string.update))
                    }
                }
            }
        }
    }

    if (showGeoipDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showGeoipDeleteDialog = false },
            title = { Text(stringResource(R.string.delete_rule_file_title)) },
            text = { Text(stringResource(R.string.delete_rule_file_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        mainViewModel.restoreDefaultGeoip { }
                        showGeoipDeleteDialog = false
                    }
                ) {
                    Text(stringResource(R.string.confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showGeoipDeleteDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    if (showGeositeDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showGeositeDeleteDialog = false },
            title = { Text(stringResource(R.string.delete_rule_file_title)) },
            text = { Text(stringResource(R.string.delete_rule_file_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        mainViewModel.restoreDefaultGeosite { }
                        showGeositeDeleteDialog = false
                    }
                ) {
                    Text(stringResource(R.string.confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showGeositeDeleteDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    // Show update dialog when new version is available (only if not downloading)
    if (newVersionTag != null && !isDownloadingUpdate && downloadCompletion == null) {
        AlertDialog(
            onDismissRequest = { mainViewModel.clearNewVersionAvailable() },
            title = { Text(stringResource(R.string.new_version_available_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.new_version_available_message,
                        newVersionTag!!
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = { mainViewModel.downloadNewVersion(newVersionTag!!) }) {
                    Text(stringResource(R.string.download))
                }
            },
            dismissButton = {
                TextButton(onClick = { mainViewModel.clearNewVersionAvailable() }) {
                    Text(stringResource(id = android.R.string.cancel))
                }
            }
        )
    }
    
    // Show download progress bottom sheet during download and when complete
    UpdateDownloadBottomSheet(
        isDownloading = isDownloadingUpdate,
        downloadProgress = downloadProgress,
        isDownloadComplete = downloadCompletion != null,
        onCancel = {
            mainViewModel.cancelDownload()
        },
        onInstall = {
            mainViewModel.installDownloadedApk()
        },
        onDismiss = {
            mainViewModel.clearDownloadCompletion()
        }
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(10.dp)
    ) {
        PreferenceCategoryTitle(stringResource(R.string.general))

        ListItem(
            headlineContent = { Text(stringResource(R.string.use_template_title)) },
            supportingContent = { Text(stringResource(R.string.use_template_summary)) },
            trailingContent = {
                Switch(
                    checked = settingsState.switches.useTemplateEnabled,
                    onCheckedChange = {
                        mainViewModel.setUseTemplateEnabled(it)
                    }
                )
            }
        )

        ListItem(
            headlineContent = { Text(stringResource(R.string.theme_title)) },
            supportingContent = {
                Text(stringResource(id = R.string.theme_summary))
            },
            trailingContent = {
                ExposedDropdownMenuBox(
                    expanded = themeExpanded,
                    onExpandedChange = { themeExpanded = it }
                ) {
                    TextButton(
                        onClick = {},
                        modifier = Modifier
                            .menuAnchor(MenuAnchorType.PrimaryEditable, true),
                        colors = ButtonDefaults.textButtonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainer
                        )
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = stringResource(
                                    id = when (selectedThemeOption) {
                                        ThemeMode.Light -> R.string.theme_light
                                        ThemeMode.Dark -> R.string.theme_dark
                                        ThemeMode.Auto -> R.string.auto
                                    }
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                            if (themeExpanded) {
                                Icon(
                                    imageVector = Icons.Filled.KeyboardArrowUp,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Filled.KeyboardArrowDown,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                    ExposedDropdownMenu(
                        expanded = themeExpanded,
                        onDismissRequest = { themeExpanded = false }
                    ) {
                        themeOptions.forEach { option ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        stringResource(
                                            id = when (option) {
                                                ThemeMode.Light -> R.string.theme_light
                                                ThemeMode.Dark -> R.string.theme_dark
                                                ThemeMode.Auto -> R.string.auto
                                            }
                                        )
                                    )
                                },
                                onClick = {
                                    selectedThemeOption = option
                                    mainViewModel.setTheme(option)
                                    themeExpanded = false
                                }
                            )
                        }
                    }
                }
            }
        )

        PreferenceCategoryTitle(stringResource(R.string.vpn_interface))

        ListItem(
            modifier = Modifier.clickable {
                mainViewModel.navigateToAppList()
            },
            headlineContent = { Text(stringResource(R.string.apps_title)) },
            supportingContent = { Text(stringResource(R.string.apps_summary)) },
            trailingContent = {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null
                )
            }
        )

        ListItem(
            headlineContent = { Text(stringResource(R.string.disable_vpn_title)) },
            supportingContent = { Text(stringResource(R.string.disable_vpn_summary)) },
            trailingContent = {
                Switch(
                    checked = settingsState.switches.disableVpn,
                    onCheckedChange = {
                        mainViewModel.setDisableVpnEnabled(it)
                    }
                )
            }
        )

        EditableListItemWithBottomSheet(
            headline = stringResource(R.string.socks_port),
            currentValue = settingsState.socksPort.value,
            onValueConfirmed = { newValue -> mainViewModel.updateSocksPort(newValue) },
            label = stringResource(R.string.socks_port),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            isError = !settingsState.socksPort.isValid,
            errorMessage = settingsState.socksPort.error,
            enabled = !vpnDisabled,
            sheetState = sheetState,
            scope = scope
        )

        EditableListItemWithBottomSheet(
            headline = stringResource(R.string.dns_ipv4),
            currentValue = settingsState.dnsIpv4.value,
            onValueConfirmed = { newValue -> mainViewModel.updateDnsIpv4(newValue) },
            label = stringResource(R.string.dns_ipv4),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            isError = !settingsState.dnsIpv4.isValid,
            errorMessage = settingsState.dnsIpv4.error,
            enabled = !vpnDisabled,
            sheetState = sheetState,
            scope = scope
        )

        EditableListItemWithBottomSheet(
            headline = stringResource(R.string.dns_ipv6),
            currentValue = settingsState.dnsIpv6.value,
            onValueConfirmed = { newValue -> mainViewModel.updateDnsIpv6(newValue) },
            label = stringResource(R.string.dns_ipv6),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            isError = !settingsState.dnsIpv6.isValid,
            errorMessage = settingsState.dnsIpv6.error,
            enabled = settingsState.switches.ipv6Enabled && !vpnDisabled,
            sheetState = sheetState,
            scope = scope
        )

        ListItem(
            headlineContent = { Text(stringResource(R.string.ipv6)) },
            supportingContent = { Text(stringResource(R.string.ipv6_enabled)) },
            trailingContent = {
                Switch(
                    checked = settingsState.switches.ipv6Enabled,
                    onCheckedChange = {
                        mainViewModel.setIpv6Enabled(it)
                    },
                    enabled = !vpnDisabled
                )
            }
        )

        ListItem(
            headlineContent = { Text(stringResource(R.string.http_proxy_title)) },
            supportingContent = { Text(stringResource(R.string.http_proxy_summary)) },
            trailingContent = {
                Switch(
                    checked = settingsState.switches.httpProxyEnabled,
                    onCheckedChange = {
                        mainViewModel.setHttpProxyEnabled(it)
                    },
                    enabled = !vpnDisabled
                )
            }
        )

        ListItem(
            headlineContent = { Text(stringResource(R.string.bypass_lan_title)) },
            supportingContent = { Text(stringResource(R.string.bypass_lan_summary)) },
            trailingContent = {
                Switch(
                    checked = settingsState.switches.bypassLanEnabled,
                    onCheckedChange = {
                        mainViewModel.setBypassLanEnabled(it)
                    },
                    enabled = !vpnDisabled
                )
            }
        )

        PreferenceCategoryTitle("Performance")

        // Check native library status
        val isNativeLibraryLoaded = remember { PerformanceManager.isNativeLibraryLoaded() }
        val nativeLibraryError = remember { PerformanceManager.getNativeLibraryLoadError() }

        ListItem(
            headlineContent = { Text("Performance Mode") },
            supportingContent = { 
                Column {
                    Text(
                        "Enable aggressive performance optimizations (CPU affinity, zero-copy I/O, connection pooling, etc.)"
                    )
                    if (!isNativeLibraryLoaded) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "⚠️ ${nativeLibraryError ?: "Native library not loaded. Some optimizations may be limited."}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            },
            trailingContent = {
                Switch(
                    checked = settingsState.switches.enablePerformanceMode,
                    onCheckedChange = {
                        mainViewModel.setEnablePerformanceMode(it)
                    }
                )
            }
        )

        ListItem(
            modifier = Modifier.clickable {
                mainViewModel.navigateToPerformance()
            },
            headlineContent = { Text("Performance Dashboard") },
            supportingContent = { 
                Text(
                    "View real-time performance metrics, charts, and detailed statistics"
                )
            },
            trailingContent = {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null
                )
            }
        )
        
        ListItem(
            modifier = Modifier.clickable {
                mainViewModel.navigateToAdvancedPerformanceSettings()
            },
            headlineContent = { Text("Advanced Performance Settings") },
            supportingContent = {
                Text(
                    "Fine-tune CPU affinity, memory pools, connection pools, and other optimizations"
                )
            },
            trailingContent = {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null
                )
            }
        )

        // QUIC Mode Settings (QUICHE Native Client)
        PreferenceCategoryTitle("QUIC Mode (Maximum Performance)")

        var showQuicSettings by remember { mutableStateOf(false) }

        ListItem(
            headlineContent = { Text("Enable QUIC Mode") },
            supportingContent = {
                Column {
                    Text(
                        "Use QUICHE native client for maximum performance (800-1200 Mbps throughput, +2-5ms latency)"
                    )
                    if (settingsState.quicSettings.enabled && settingsState.quicSettings.serverHost.isBlank()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "⚠️ QUIC server host not configured. Configure settings below.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            },
            trailingContent = {
                Switch(
                    checked = settingsState.quicSettings.enabled,
                    onCheckedChange = {
                        mainViewModel.setQuicModeEnabled(it)
                        if (it) showQuicSettings = true
                    }
                )
            }
        )

        // Collapsible QUIC settings
        if (settingsState.quicSettings.enabled || showQuicSettings) {
            var quicServerHost by remember { mutableStateOf(settingsState.quicSettings.serverHost) }
            var quicServerPort by remember { mutableStateOf(settingsState.quicSettings.serverPort.toString()) }

            // QUIC Server Host
            EditableListItemWithBottomSheet(
                headline = "QUIC Server Host",
                currentValue = quicServerHost.ifBlank { "Not configured" },
                onValueConfirmed = { newValue ->
                    quicServerHost = newValue
                    mainViewModel.setQuicServerHost(newValue)
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
            )

            // QUIC Server Port
            EditableListItemWithBottomSheet(
                headline = "QUIC Server Port",
                currentValue = quicServerPort,
                onValueConfirmed = { newValue ->
                    val port = newValue.toIntOrNull()
                    if (port != null && port in 1..65535) {
                        quicServerPort = newValue
                        mainViewModel.setQuicServerPort(port)
                    }
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )

            // Congestion Control Algorithm
            var congestionControlExpanded by remember { mutableStateOf(false) }
            val congestionControlOptions = listOf("BBR", "BBR2", "CUBIC", "RENO")

            ListItem(
                headlineContent = { Text("Congestion Control") },
                supportingContent = {
                    Text("BBR2 recommended for mobile networks (lowest latency, best throughput)")
                },
                trailingContent = {
                    ExposedDropdownMenuBox(
                        expanded = congestionControlExpanded,
                        onExpandedChange = { congestionControlExpanded = it }
                    ) {
                        TextButton(
                            onClick = {},
                            modifier = Modifier
                                .menuAnchor(MenuAnchorType.PrimaryEditable, true),
                            colors = ButtonDefaults.textButtonColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainer
                            )
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = settingsState.quicSettings.congestionControl,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                if (congestionControlExpanded) {
                                    Icon(
                                        imageVector = Icons.Filled.KeyboardArrowUp,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                } else {
                                    Icon(
                                        imageVector = Icons.Filled.KeyboardArrowDown,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                        ExposedDropdownMenu(
                            expanded = congestionControlExpanded,
                            onDismissRequest = { congestionControlExpanded = false }
                        ) {
                            congestionControlOptions.forEach { option ->
                                DropdownMenuItem(
                                    text = { Text(option) },
                                    onClick = {
                                        mainViewModel.setQuicCongestionControl(option)
                                        congestionControlExpanded = false
                                    }
                                )
                            }
                        }
                    }
                }
            )

            // Zero-Copy I/O
            ListItem(
                headlineContent = { Text("Zero-Copy I/O") },
                supportingContent = {
                    Text("Enable zero-copy packet forwarding for maximum throughput (recommended)")
                },
                trailingContent = {
                    Switch(
                        checked = settingsState.quicSettings.zeroCopyEnabled,
                        onCheckedChange = {
                            mainViewModel.setQuicZeroCopyEnabled(it)
                        }
                    )
                }
            )

            // CPU Affinity
            var cpuAffinityExpanded by remember { mutableStateOf(false) }
            val cpuAffinityOptions = listOf("BIG_CORES", "LITTLE_CORES", "ALL_CORES", "NO_AFFINITY")
            val cpuAffinityLabels = mapOf(
                "BIG_CORES" to "Big Cores (High Performance)",
                "LITTLE_CORES" to "Little Cores (Power Saving)",
                "ALL_CORES" to "All Cores",
                "NO_AFFINITY" to "No Affinity (OS Default)"
            )

            ListItem(
                headlineContent = { Text("CPU Affinity") },
                supportingContent = {
                    Text("Pin QUIC threads to specific CPU cores. Big cores recommended for maximum speed.")
                },
                trailingContent = {
                    ExposedDropdownMenuBox(
                        expanded = cpuAffinityExpanded,
                        onExpandedChange = { cpuAffinityExpanded = it }
                    ) {
                        TextButton(
                            onClick = {},
                            modifier = Modifier
                                .menuAnchor(MenuAnchorType.PrimaryEditable, true),
                            colors = ButtonDefaults.textButtonColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainer
                            )
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = cpuAffinityLabels[settingsState.quicSettings.cpuAffinity] ?: settingsState.quicSettings.cpuAffinity,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                if (cpuAffinityExpanded) {
                                    Icon(
                                        imageVector = Icons.Filled.KeyboardArrowUp,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                } else {
                                    Icon(
                                        imageVector = Icons.Filled.KeyboardArrowDown,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                        ExposedDropdownMenu(
                            expanded = cpuAffinityExpanded,
                            onDismissRequest = { cpuAffinityExpanded = false }
                        ) {
                            cpuAffinityOptions.forEach { option ->
                                DropdownMenuItem(
                                    text = { Text(cpuAffinityLabels[option] ?: option) },
                                    onClick = {
                                        mainViewModel.setQuicCpuAffinity(option)
                                        cpuAffinityExpanded = false
                                    }
                                )
                            }
                        }
                    }
                }
            )
        }

        PreferenceCategoryTitle(stringResource(R.string.rule_files_category_title))

        ListItem(
            headlineContent = { Text("geoip.dat") },
            supportingContent = { Text(geoipProgress ?: settingsState.info.geoipSummary) },
            trailingContent = {
                Row {
                    if (geoipProgress != null) {
                        IconButton(onClick = { mainViewModel.cancelDownload("geoip.dat") }) {
                            Icon(
                                painter = painterResource(id = R.drawable.cancel),
                                contentDescription = stringResource(R.string.cancel)
                            )
                        }
                    } else {
                        IconButton(onClick = {
                            ruleFileUrl = settingsState.info.geoipUrl
                            editingRuleFile = "geoip.dat"
                            scope.launch { sheetState.show() }
                        }) {
                            Icon(
                                painter = painterResource(id = R.drawable.cloud_download),
                                contentDescription = stringResource(R.string.rule_file_update_url)
                            )
                        }
                        if (!settingsState.files.isGeoipCustom) {
                            IconButton(onClick = { geoipFilePickerLauncher.launch(arrayOf("*/*")) }) {
                                Icon(
                                    painter = painterResource(id = R.drawable.place_item),
                                    contentDescription = stringResource(R.string.import_file)
                                )
                            }
                        } else {
                            IconButton(onClick = { showGeoipDeleteDialog = true }) {
                                Icon(
                                    painter = painterResource(id = R.drawable.delete),
                                    contentDescription = stringResource(R.string.reset_file)
                                )
                            }
                        }
                    }
                }
            },
            modifier = Modifier
        )

        ListItem(
            headlineContent = { Text("geosite.dat") },
            supportingContent = { Text(geositeProgress ?: settingsState.info.geositeSummary) },
            trailingContent = {
                Row {
                    if (geositeProgress != null) {
                        IconButton(onClick = { mainViewModel.cancelDownload("geosite.dat") }) {
                            Icon(
                                painter = painterResource(id = R.drawable.cancel),
                                contentDescription = stringResource(R.string.cancel)
                            )
                        }
                    } else {
                        IconButton(onClick = {
                            ruleFileUrl = settingsState.info.geositeUrl
                            editingRuleFile = "geosite.dat"
                            scope.launch { sheetState.show() }
                        }) {
                            Icon(
                                painter = painterResource(id = R.drawable.cloud_download),
                                contentDescription = stringResource(R.string.rule_file_update_url)
                            )
                        }
                        if (!settingsState.files.isGeositeCustom) {
                            IconButton(onClick = { geositeFilePickerLauncher.launch(arrayOf("*/*")) }) {
                                Icon(
                                    painter = painterResource(id = R.drawable.place_item),
                                    contentDescription = stringResource(R.string.import_file)
                                )
                            }
                        } else {
                            IconButton(onClick = { showGeositeDeleteDialog = true }) {
                                Icon(
                                    painter = painterResource(id = R.drawable.delete),
                                    contentDescription = stringResource(R.string.reset_file)
                                )
                            }
                        }
                    }
                }
            },
            modifier = Modifier
        )

        PreferenceCategoryTitle(stringResource(R.string.connectivity_test))

        EditableListItemWithBottomSheet(
            headline = stringResource(R.string.connectivity_test_target),
            currentValue = settingsState.connectivityTestTarget.value,
            onValueConfirmed = { newValue -> mainViewModel.updateConnectivityTestTarget(newValue) },
            label = stringResource(R.string.connectivity_test_target),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            isError = !settingsState.connectivityTestTarget.isValid,
            errorMessage = settingsState.connectivityTestTarget.error,
            sheetState = sheetState,
            scope = scope
        )

        EditableListItemWithBottomSheet(
            headline = stringResource(R.string.connectivity_test_timeout),
            currentValue = settingsState.connectivityTestTimeout.value,
            onValueConfirmed = { newValue -> mainViewModel.updateConnectivityTestTimeout(newValue) },
            label = stringResource(R.string.connectivity_test_timeout),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            isError = !settingsState.connectivityTestTimeout.isValid,
            errorMessage = settingsState.connectivityTestTimeout.error,
            sheetState = sheetState,
            scope = scope
        )

        PreferenceCategoryTitle(stringResource(R.string.about))

        ListItem(
            headlineContent = { Text(stringResource(R.string.version)) },
            supportingContent = { Text(settingsState.info.appVersion) },
            trailingContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        onClick = {
                            mainViewModel.checkForUpdates()
                        },
                        colors = ButtonDefaults.textButtonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainer,
                            disabledContainerColor = Color.Transparent
                        ),
                        enabled = !isCheckingForUpdates
                    ) {
                        if (isCheckingForUpdates) {
                            CircularProgressIndicator(
                                modifier = Modifier
                                    .width(20.dp)
                                    .height(20.dp),
                                color = MaterialTheme.colorScheme.primary,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text(stringResource(R.string.check_for_updates))
                        }
                    }
                }
            }
        )

        ListItem(
            headlineContent = { Text(stringResource(R.string.kernel)) },
            supportingContent = { Text(settingsState.info.kernelVersion) }
        )

        ListItem(
            modifier = Modifier.clickable {
                val browserIntent =
                    Intent(Intent.ACTION_VIEW, Uri.parse(BuildConfig.REPOSITORY_URL))
                context.startActivity(browserIntent)
            },
            headlineContent = { Text(stringResource(R.string.source)) },
            supportingContent = { Text(stringResource(R.string.open_source)) },
            trailingContent = {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null
                )
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditableListItemWithBottomSheet(
    headline: String,
    currentValue: String,
    onValueConfirmed: (String) -> Unit,
    label: String,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    isError: Boolean = false,
    errorMessage: String? = null,
    enabled: Boolean = true,
    sheetState: SheetState,
    scope: CoroutineScope
) {
    var showSheet by remember { mutableStateOf(false) }
    var tempValue by remember { mutableStateOf(currentValue) }

    if (showSheet) {
        ModalBottomSheet(
            onDismissRequest = { showSheet = false },
            sheetState = sheetState
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                OutlinedTextField(
                    value = tempValue,
                    onValueChange = { tempValue = it },
                    label = { Text(label) },
                    keyboardOptions = keyboardOptions,
                    isError = isError,
                    supportingText = {
                        if (isError) {
                            Text(text = errorMessage ?: "")
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp, end = 12.dp),
                )

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(end = 8.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = {
                        scope.launch { sheetState.hide() }.invokeOnCompletion {
                            if (!sheetState.isVisible) {
                                showSheet = false
                            }
                        }
                    }) {
                        Text(stringResource(R.string.cancel))
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(onClick = {
                        onValueConfirmed(tempValue)
                        scope.launch { sheetState.hide() }.invokeOnCompletion {
                            if (!sheetState.isVisible) {
                                showSheet = false
                            }
                        }
                    }) {
                        Text(stringResource(R.string.confirm))
                    }
                }
            }
        }
    }

    ListItem(
        headlineContent = { Text(headline) },
        supportingContent = { Text(currentValue) },
        modifier = Modifier.clickable(enabled = enabled) {
            tempValue = currentValue
            showSheet = true
        },
        trailingContent = {
            if (isError) {
                Icon(
                    painter = painterResource(id = R.drawable.cancel),
                    contentDescription = errorMessage,
                    tint = MaterialTheme.colorScheme.error
                )
            } else {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null
                )
            }
        }
    )
}

@Composable
fun PreferenceCategoryTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 16.dp, start = 16.dp, end = 16.dp, bottom = 4.dp)
    )
}
