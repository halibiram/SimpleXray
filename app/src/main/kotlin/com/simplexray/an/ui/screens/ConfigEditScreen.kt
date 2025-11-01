package com.simplexray.an.ui.screens

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simplexray.an.R
import com.simplexray.an.ui.util.bracketMatcherTransformation
import com.simplexray.an.viewmodel.ConfigEditUiEvent
import com.simplexray.an.viewmodel.ConfigEditViewModel
import kotlinx.coroutines.flow.collectLatest
import org.json.JSONArray
import org.json.JSONObject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfigEditScreen(
    onBackClick: () -> Unit,
    snackbarHostState: SnackbarHostState,
    viewModel: ConfigEditViewModel
) {
    var showMenu by remember { mutableStateOf(false) }
    val filename by viewModel.filename.collectAsStateWithLifecycle()
    val configTextFieldValue by viewModel.configTextFieldValue.collectAsStateWithLifecycle()
    val filenameErrorMessage by viewModel.filenameErrorMessage.collectAsStateWithLifecycle()
    val hasConfigChanged by viewModel.hasConfigChanged.collectAsStateWithLifecycle()

    val scrollState = rememberScrollState()
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior(rememberTopAppBarState())
    val isKeyboardOpen = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val focusManager = LocalFocusManager.current
    val shareLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {}

    LaunchedEffect(Unit) {
        viewModel.uiEvent.collectLatest { event ->
            when (event) {
                is ConfigEditUiEvent.NavigateBack -> {
                    onBackClick()
                }

                is ConfigEditUiEvent.ShowSnackbar -> {
                    snackbarHostState.showSnackbar(
                        event.message,
                        duration = SnackbarDuration.Short
                    )
                }

                is ConfigEditUiEvent.ShareContent -> {
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, event.content)
                    }
                    shareLauncher.launch(Intent.createChooser(shareIntent, null))
                }
            }
        }
    }

    Scaffold(modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection), topBar = {
        TopAppBar(title = { Text(stringResource(id = R.string.config)) }, navigationIcon = {
            IconButton(onClick = onBackClick) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(
                        R.string.back
                    )
                )
            }
        }, actions = {
            IconButton(onClick = {
                viewModel.saveConfigFile()
                focusManager.clearFocus()
            }, enabled = hasConfigChanged) {
                Icon(
                    painter = painterResource(id = R.drawable.save),
                    contentDescription = stringResource(id = R.string.save)
                )
            }
            IconButton(onClick = { showMenu = !showMenu }) {
                Icon(
                    Icons.Default.MoreVert,
                    contentDescription = stringResource(R.string.more)
                )
            }
            DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(id = R.string.share)) },
                    onClick = {
                        viewModel.shareConfigFile()
                        showMenu = false
                    })
            }
        }, scrollBehavior = scrollBehavior
        )
    }, snackbarHost = { SnackbarHost(snackbarHostState) }, content = { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .imePadding()
                .padding(top = paddingValues.calculateTopPadding())
                .verticalScroll(scrollState)
        ) {
            AdvancedTransportTlsEditor(
                configText = configTextFieldValue.text,
                onApply = { updated ->
                    // Replace editor content with updated JSON (preserve caret at end)
                    viewModel.onConfigContentChange(
                        TextFieldValue(text = updated, selection = TextRange(updated.length))
                    )
                }
            )
            Spacer(Modifier.height(12.dp))
            TextField(value = filename,
                onValueChange = { v ->
                    viewModel.onFilenameChange(v)
                },
                label = { Text(stringResource(id = R.string.filename)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    disabledContainerColor = Color.Transparent,
                    errorContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                    errorIndicatorColor = Color.Transparent,
                ),
                isError = filenameErrorMessage != null,
                supportingText = {
                    filenameErrorMessage?.let { Text(it) }
                })

            TextField(
                value = configTextFieldValue,
                onValueChange = { newTextFieldValue ->
                    val newText = newTextFieldValue.text
                    val oldText = configTextFieldValue.text
                    val cursorPosition = newTextFieldValue.selection.start

                    if (newText.length == oldText.length + 1 &&
                        cursorPosition > 0 &&
                        newText[cursorPosition - 1] == '\n'
                    ) {
                        val pair = viewModel.handleAutoIndent(newText, cursorPosition - 1)
                        viewModel.onConfigContentChange(
                            TextFieldValue(
                                text = pair.first,
                                selection = TextRange(pair.second)
                            )
                        )
                    } else {
                        viewModel.onConfigContentChange(newTextFieldValue.copy(text = newText))
                    }
                },
                visualTransformation = bracketMatcherTransformation(configTextFieldValue),
                label = { Text(stringResource(R.string.content)) },
                modifier = Modifier
                    .padding(bottom = if (isKeyboardOpen) 0.dp else paddingValues.calculateBottomPadding())
                    .fillMaxWidth(),
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                keyboardOptions = KeyboardOptions.Default.copy(
                    keyboardType = KeyboardType.Text
                ),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    disabledContainerColor = Color.Transparent,
                    errorContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                    errorIndicatorColor = Color.Transparent,
                )
            )
        }
    })
}

@Composable
private fun AdvancedTransportTlsEditor(
    configText: String,
    onApply: (String) -> Unit,
) {
    // Parse current config stream settings
    var network by remember(configText) { mutableStateOf("tcp") }
    var tlsEnabled by remember(configText) { mutableStateOf(false) }
    var sni by remember(configText) { mutableStateOf("") }
    var alpn by remember(configText) { mutableStateOf("") }
    var fingerprint by remember(configText) { mutableStateOf("") }

    var wsPath by remember(configText) { mutableStateOf("") }
    var wsHost by remember(configText) { mutableStateOf("") }

    var grpcService by remember(configText) { mutableStateOf("") }
    var grpcMulti by remember(configText) { mutableStateOf(true) }

    var httpHost by remember(configText) { mutableStateOf("") }
    var httpPath by remember(configText) { mutableStateOf("") }

    var quicSecurity by remember(configText) { mutableStateOf("") }

    var protocolLabel by remember { mutableStateOf("tcp") }
    var showProtoMenu by remember { mutableStateOf(false) }

    // REALITY (VLESS) fields
    var useReality by remember(configText) { mutableStateOf(false) }
    var realityServerName by remember(configText) { mutableStateOf("") }
    var realityPublicKey by remember(configText) { mutableStateOf("") }
    var realityShortId by remember(configText) { mutableStateOf("") }
    var realitySpiderX by remember(configText) { mutableStateOf("/") }

    LaunchedEffect(configText) {
        runCatching {
            val root = JSONObject(configText)
            val outbound = findPrimaryOutbound(root) ?: return@runCatching
            val stream = outbound.optJSONObject("streamSettings") ?: JSONObject()
            val net = stream.optString("network", "tcp")
            network = net
            protocolLabel = when (net) {
                "http" -> "http (h2)"
                else -> net
            }
            val security = stream.optString("security", "")
            tlsEnabled = security.equals("tls", true)
            stream.optJSONObject("tlsSettings")?.let { tls ->
                sni = tls.optString("serverName", "")
                val alpnArr = tls.optJSONArray("alpn")
                alpn = if (alpnArr != null) (0 until alpnArr.length()).joinToString(",") { i -> alpnArr.getString(i) } else ""
                fingerprint = tls.optString("fingerprint", "")
            }
            stream.optJSONObject("wsSettings")?.let { ws ->
                wsPath = ws.optString("path", "")
                wsHost = ws.optJSONObject("headers")?.optString("Host", "") ?: ""
            }
            stream.optJSONObject("grpcSettings")?.let { g ->
                grpcService = g.optString("serviceName", "")
                grpcMulti = g.optBoolean("multiMode", true)
            }
            stream.optJSONObject("httpSettings")?.let { h ->
                val hostArr = h.optJSONArray("host")
                httpHost = if (hostArr != null && hostArr.length() > 0) hostArr.getString(0) else ""
                httpPath = h.optString("path", "")
            }
            stream.optJSONObject("quicSettings")?.let { q ->
                quicSecurity = q.optString("security", "")
            }
        }
    }

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Text("Advanced Transport / TLS", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))

        // Network selector
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = protocolLabel,
                onValueChange = {},
                modifier = Modifier.weight(1f),
                label = { Text("Network") },
                readOnly = true,
                trailingIcon = {
                    IconButton(onClick = { showProtoMenu = true }) {
                        Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                    }
                }
            )
            DropdownMenu(expanded = showProtoMenu, onDismissRequest = { showProtoMenu = false }) {
                listOf("tcp", "ws", "grpc", "http (h2)", "quic").forEach { item ->
                    DropdownMenuItem(text = { Text(item) }, onClick = {
                        protocolLabel = item
                        network = if (item == "http (h2)") "http" else item
                        showProtoMenu = false
                    })
                }
            }
            OutlinedButton(onClick = {
                // Apply to JSON
                val updated = runCatching { applyAdvancedSettings(configText, network, tlsEnabled, sni, alpn, fingerprint, wsPath, wsHost, grpcService, grpcMulti, httpHost, httpPath, quicSecurity, useReality, realityServerName, realityPublicKey, realityShortId, realitySpiderX) }.getOrNull()
                if (updated != null) onApply(updated)
            }) { Text("Apply") }
        }

        // Quick presets
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = {
                network = "ws"; protocolLabel = "ws"; tlsEnabled = true; sni = "example.com"; wsPath = "/ws"; wsHost = "cdn.example.com"; useReality = false
            }) { Text("WS CDN") }
            OutlinedButton(onClick = {
                network = "grpc"; protocolLabel = "grpc"; tlsEnabled = true; grpcService = "grpc"; useReality = false
            }) { Text("gRPC") }
            OutlinedButton(onClick = {
                network = "http"; protocolLabel = "http (h2)"; tlsEnabled = true; httpPath = "/"; useReality = false
            }) { Text("H2") }
            OutlinedButton(onClick = {
                useReality = true; tlsEnabled = false; network = "tcp"; protocolLabel = "tcp"; fingerprint = "chrome"; realitySpiderX = "/"
            }) { Text("REALITY") }
        }

        // TLS toggle and fields
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = if (tlsEnabled) "Enabled" else "Disabled",
                onValueChange = {},
                label = { Text("TLS") },
                readOnly = true,
                modifier = Modifier.weight(1f),
                trailingIcon = {
                    IconButton(onClick = { tlsEnabled = !tlsEnabled }) {
                        Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                    }
                }
            )
            OutlinedTextField(value = sni, onValueChange = { sni = it }, label = { Text("SNI / ServerName") }, modifier = Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(value = alpn, onValueChange = { alpn = it }, label = { Text("ALPN (comma)") }, modifier = Modifier.weight(1f))
            OutlinedTextField(value = fingerprint, onValueChange = { fingerprint = it }, label = { Text("uTLS Fingerprint") }, modifier = Modifier.weight(1f))
        }

        if (useReality) {
            Text("REALITY (VLESS)", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(value = realityServerName, onValueChange = { realityServerName = it }, label = { Text("serverName (SNI)") }, modifier = Modifier.weight(1f))
                OutlinedTextField(value = realityPublicKey, onValueChange = { realityPublicKey = it }, label = { Text("publicKey") }, modifier = Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(value = realityShortId, onValueChange = { realityShortId = it }, label = { Text("shortId") }, modifier = Modifier.weight(1f))
                OutlinedTextField(value = realitySpiderX, onValueChange = { realitySpiderX = it }, label = { Text("spiderX") }, modifier = Modifier.weight(1f))
            }
        }

        when (network) {
            "ws" -> {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(value = wsPath, onValueChange = { wsPath = it }, label = { Text("WS Path") }, modifier = Modifier.weight(1f))
                    OutlinedTextField(value = wsHost, onValueChange = { wsHost = it }, label = { Text("WS Host header") }, modifier = Modifier.weight(1f))
                }
            }
            "grpc" -> {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(value = grpcService, onValueChange = { grpcService = it }, label = { Text("gRPC serviceName") }, modifier = Modifier.weight(1f))
                    OutlinedTextField(value = if (grpcMulti) "multi" else "unary", onValueChange = {}, label = { Text("Mode") }, readOnly = true, modifier = Modifier.weight(1f), trailingIcon = {
                        IconButton(onClick = { grpcMulti = !grpcMulti }) { Icon(Icons.Default.ArrowDropDown, contentDescription = null) }
                    })
                }
            }
            "http" -> {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(value = httpHost, onValueChange = { httpHost = it }, label = { Text("HTTP Host") }, modifier = Modifier.weight(1f))
                    OutlinedTextField(value = httpPath, onValueChange = { httpPath = it }, label = { Text("HTTP Path") }, modifier = Modifier.weight(1f))
                }
            }
            "quic" -> {
                OutlinedTextField(value = quicSecurity, onValueChange = { quicSecurity = it }, label = { Text("QUIC Security") }, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

private fun findPrimaryOutbound(root: JSONObject): JSONObject? {
    val arr = root.optJSONArray("outbounds") ?: return null
    for (i in 0 until arr.length()) {
        val o = arr.optJSONObject(i) ?: continue
        val proto = o.optString("protocol", "")
        if (proto !in listOf("freedom", "blackhole", "dns")) return o
    }
    return null
}

private fun applyAdvancedSettings(
    configText: String,
    network: String,
    tlsEnabled: Boolean,
    sni: String?,
    alpn: String?,
    fingerprint: String?,
    wsPath: String?,
    wsHost: String?,
    grpcService: String?,
    grpcMulti: Boolean,
    httpHost: String?,
    httpPath: String?,
    quicSecurity: String?,
    useReality: Boolean,
    realityServerName: String?,
    realityPublicKey: String?,
    realityShortId: String?,
    realitySpiderX: String?,
): String {
    val root = JSONObject(configText)
    val outbound = findPrimaryOutbound(root) ?: return configText
    val stream = outbound.optJSONObject("streamSettings") ?: JSONObject().also { outbound.put("streamSettings", it) }

    // network mapping
    stream.put("network", network)

    // TLS
    if (tlsEnabled && !useReality) {
        stream.put("security", "tls")
        val tls = stream.optJSONObject("tlsSettings") ?: JSONObject().also { stream.put("tlsSettings", it) }
        if (!sni.isNullOrBlank()) tls.put("serverName", sni) else tls.remove("serverName")
        val alpnList = alpn?.split(',')?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList()
        if (alpnList.isNotEmpty()) tls.put("alpn", JSONArray().apply { alpnList.forEach { put(it) } }) else tls.remove("alpn")
        if (!fingerprint.isNullOrBlank()) tls.put("fingerprint", fingerprint) else tls.remove("fingerprint")
        // Clear REALITY if switching back
        stream.remove("realitySettings")
    } else if (!useReality) {
        stream.remove("security")
        stream.remove("tlsSettings")
        stream.remove("realitySettings")
    }

    // REALITY (only valid for VLESS outbound)
    if (useReality && outbound.optString("protocol", "") == "vless") {
        stream.put("security", "reality")
        stream.remove("tlsSettings")
        val r = stream.optJSONObject("realitySettings") ?: JSONObject().also { stream.put("realitySettings", it) }
        r.put("show", false)
        if (!fingerprint.isNullOrBlank()) r.put("fingerprint", fingerprint) else r.remove("fingerprint")
        if (!realityServerName.isNullOrBlank()) r.put("serverName", realityServerName) else r.remove("serverName")
        if (!realityPublicKey.isNullOrBlank()) r.put("publicKey", realityPublicKey) else r.remove("publicKey")
        if (!realityShortId.isNullOrBlank()) r.put("shortId", realityShortId) else r.remove("shortId")
        if (!realitySpiderX.isNullOrBlank()) r.put("spiderX", realitySpiderX) else r.remove("spiderX")
    }

    // Clear transport-specific blocks first
    stream.remove("wsSettings"); stream.remove("grpcSettings"); stream.remove("httpSettings"); stream.remove("quicSettings")

    when (network) {
        "ws" -> {
            val ws = JSONObject()
            if (!wsPath.isNullOrBlank()) ws.put("path", wsPath)
            if (!wsHost.isNullOrBlank()) ws.put("headers", JSONObject().apply { put("Host", wsHost) })
            stream.put("wsSettings", ws)
        }
        "grpc" -> {
            val g = JSONObject()
            if (!grpcService.isNullOrBlank()) g.put("serviceName", grpcService)
            g.put("multiMode", grpcMulti)
            stream.put("grpcSettings", g)
        }
        "http" -> {
            val h = JSONObject()
            if (!httpHost.isNullOrBlank()) h.put("host", JSONArray().apply { put(httpHost) })
            if (!httpPath.isNullOrBlank()) h.put("path", httpPath)
            stream.put("httpSettings", h)
        }
        "quic" -> {
            val q = JSONObject()
            if (!quicSecurity.isNullOrBlank()) q.put("security", quicSecurity)
            stream.put("quicSettings", q)
        }
    }

    return root.toString(2)
}
