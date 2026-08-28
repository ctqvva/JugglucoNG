@file:OptIn(ExperimentalMaterial3Api::class)

package tk.glucodata.ui

import android.app.Activity
import android.content.Context
import android.text.Html
import android.widget.TextView
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.QrCode
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.navigation.NavController
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import tk.glucodata.MainActivity
import tk.glucodata.Natives
import tk.glucodata.R
import tk.glucodata.ui.components.*
import tk.glucodata.ui.util.ConnectedButtonGroup
import tk.glucodata.util.DiscoveredMirror
import tk.glucodata.util.MDnsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

private const val UNIFIED_EXTRA_SCAN_TEXT = "tk.glucodata.extra.scan_text"
private const val UNIFIED_EXTRA_SCAN_CONTEXT = "tk.glucodata.extra.scan_context"
private const val UNIFIED_SCAN_CONTEXT_MIRROR = 1

private enum class ConnTestState { IDLE, TESTING, SUCCESS, FAILURE }

// Juggluco mirror protocol handshake (sendmagicinit / receivemagic)
private val JUGGLUCO_SEND_MAGIC = byteArrayOf(
    218.toByte(), 173.toByte(), 190.toByte(), 237.toByte(),
    222.toByte(), 237.toByte(), 190.toByte(), 239.toByte(),
    209.toByte(), 239.toByte(), 0, 0, 0, 1          // last byte must be non-zero
)
private val JUGGLUCO_RECV_MAGIC_PREFIX = byteArrayOf(
    103, 249.toByte(), 45, 66, 52, 128.toByte(), 40,
    222.toByte(), 186.toByte(), 129.toByte(), 63    // first 11 bytes of receivemagic
)

private suspend fun testJugglucoConnection(host: String, port: Int): Boolean =
    withContext(Dispatchers.IO) {
        try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), 5000)
                socket.soTimeout = 5000
                socket.getOutputStream().apply { write(JUGGLUCO_SEND_MAGIC); flush() }
                val buf = ByteArray(15)
                val read = socket.getInputStream().read(buf)
                read >= 11 && buf.copyOf(11).contentEquals(JUGGLUCO_RECV_MAGIC_PREFIX)
            }
        } catch (_: Exception) {
            false
        }
    }

// ── QR Code ──────────────────────────────────────────────────────────────────

@Composable
fun QRCodeImage(content: String, size: Int = 500) {
    if (content.isEmpty()) return
    val bitmap = remember(content) {
        try { com.journeyapps.barcodescanner.BarcodeEncoder().encodeBitmap(content, com.google.zxing.BarcodeFormat.QR_CODE, size, size) }
        catch (_: Exception) { null }
    }
    bitmap?.let {
        androidx.compose.foundation.Image(bitmap = it.asImageBitmap(), contentDescription = "QR Code", modifier = Modifier.size(240.dp))
    }
}

fun injectMirrorJson(jsonstr: String, context: Context): Boolean {
    try {
        val json = parseMirrorQrJson(jsonstr)
        val turnConfig = parseHybridQrTurnConfig(json)
        val iceLabel = json.optString("ICElabel").takeIf { it.isNotEmpty() }
        val namesArray = json.optJSONArray("names")
        val names = if (namesArray != null) {
            Array(namesArray.length()) { i -> namesArray.getString(i) }
        } else {
            emptyArray()
        }
        val pos = Natives.changebackuphost(
            -1, names, json.optInt("nr", names.size),
            json.optBoolean("detect", false), json.optString("port", "8795"),
            json.optBoolean("nums", false), json.optBoolean("stream", false),
            json.optBoolean("scans", false), false, json.optBoolean("receive", false),
            json.optBoolean("activeonly", false), json.optBoolean("passiveonly", false),
            if (json.isNull("pass")) null else json.getString("pass"), 0L,
            if (json.isNull("label")) null else json.getString("label"),
            json.optBoolean("testip", false), json.optBoolean("hasname", false),
            iceLabel, json.optBoolean("side", false)
        )
        if (pos < 0) {
            Toast.makeText(context, changeHostErrorMessage(context, pos), Toast.LENGTH_SHORT).show()
            return false
        }
        if (turnConfig != null) {
            Natives.setTurnHost(0, turnConfig.host)
            Natives.setTurnPort(0, turnConfig.port)
            Natives.setTurnUser(0, turnConfig.username)
            Natives.setTurnPassword(0, turnConfig.password)
            Natives.resetnetwork()
        }
        Toast.makeText(context, context.getString(R.string.mirrorscansucces), Toast.LENGTH_SHORT).show()
        tk.glucodata.Applic.wakemirrors()
        return true
    } catch (_: Exception) {
        Toast.makeText(context, context.getString(R.string.mirror_invalid_qr_data), Toast.LENGTH_SHORT).show()
        return false
    }
}

private fun changeHostErrorMessage(context: Context, code: Int): String = when (code) {
    -1 -> context.getString(R.string.mirror_portrange)
    -2 -> context.getString(R.string.parseip)
    -3 -> context.getString(R.string.toomanyhosts)
    -4 -> context.getString(R.string.senthosts)
    -5 -> context.getString(R.string.mirror_host_error_hostname_too_long)
    -6 -> context.getString(R.string.mirror_host_error_database_busy)
    -16 -> context.getString(R.string.mirror_host_error_ice_label_short)
    else -> context.getString(R.string.mirror_error_with_code, code)
}

// ── Main Screen ──────────────────────────────────────────────────────────────

@Composable
fun MirrorSettingsScreen(navController: NavController) {
    val context = LocalContext.current
    var triggerRefresh by remember { mutableIntStateOf(0) }
    var mirrors by remember { mutableStateOf(emptyList<MirrorItemData>()) }
    var showMyQR by remember { mutableStateOf<String?>(null) }

    // mDNS
    val mdnsManager = remember { MDnsManager(context) }
    var isBroadcasting by remember { mutableStateOf(false) }
    var broadcastSenderIdx by remember { mutableIntStateOf(-1) } // index of the sender entry on master
    var discoveredMirrors by remember { mutableStateOf(emptyList<DiscoveredMirror>()) }

    // Pending states
    var scannedQrPayload by remember { mutableStateOf<String?>(null) }
    var pendingNearby by remember { mutableStateOf<DiscoveredMirror?>(null) }

    // Edit sheet state
    var editSheetPos by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(triggerRefresh) { mirrors = getMirrorsList() }

    DisposableEffect(Unit) {
        mdnsManager.discoverServices { mirror ->
            if (discoveredMirrors.none { it.ip == mirror.ip }) {
                discoveredMirrors = discoveredMirrors + mirror
            }
        }
        onDispose {
            mdnsManager.stopDiscovery()
            if (isBroadcasting) mdnsManager.unregisterService()
        }
    }

    val handleMirrorScanRaw: (String?) -> Unit = handle@{ raw ->
        if (raw.isNullOrBlank()) {
            return@handle
        }
        if (raw.contains("MirrorJuggluco") || raw.contains("\"port\"")) {
            scannedQrPayload = raw
        } else {
            Toast.makeText(context, context.getString(R.string.mirror_invalid_qr_code), Toast.LENGTH_SHORT).show()
        }
    }

    val unifiedScannerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK) {
            return@rememberLauncherForActivityResult
        }
        handleMirrorScanRaw(result.data?.getStringExtra(UNIFIED_EXTRA_SCAN_TEXT))
    }

    // Legacy ZXing fallback
    val zxingLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        handleMirrorScanRaw(result.contents)
    }
    val launchScanner: () -> Unit = launch@{
        val legacyFallback = {
            zxingLauncher.launch(
                ScanOptions().apply {
                    setPrompt(context.getString(R.string.mirror_scan_prompt))
                    setBeepEnabled(false)
                }
            )
        }

        val unifiedIntent = tk.glucodata.PhotoScan.createUnifiedScanIntent(
            context,
            MainActivity.REQUEST_BARCODE,
            0L,
            null
        )
        if (unifiedIntent != null) {
            unifiedIntent.putExtra(UNIFIED_EXTRA_SCAN_CONTEXT, UNIFIED_SCAN_CONTEXT_MIRROR)
            unifiedScannerLauncher.launch(unifiedIntent)
            return@launch
        }
        legacyFallback()
    }

    // ── Dialogs ──────────────────────────────────────────────────────────

    if (scannedQrPayload != null) {
        AlertDialog(
            onDismissRequest = { scannedQrPayload = null },
            icon = { Icon(Icons.Filled.Link, contentDescription = null) },
            title = { Text(stringResource(R.string.mirror_connect_device_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.mirror_connect_qr_message))
                    if (mirrorQrContainsTurnConfig(scannedQrPayload!!)) {
                        Text(
                            stringResource(R.string.mirror_connect_qr_turn_message),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (injectMirrorJson(scannedQrPayload!!, context)) triggerRefresh++
                    scannedQrPayload = null
                }) { Text(stringResource(R.string.mirror_connect_action)) }
            },
            dismissButton = { OutlinedButton(onClick = { scannedQrPayload = null }) { Text(stringResource(R.string.cancel)) } }
        )
    }

    if (pendingNearby != null) {
        val device = pendingNearby!!
        AlertDialog(
            onDismissRequest = { pendingNearby = null },
            icon = { Icon(Icons.Filled.Wifi, contentDescription = null) },
            title = { Text(stringResource(R.string.mirror_connect_nearby_title, device.name)) },
            text = { Text(stringResource(R.string.mirror_connect_nearby_message, device.ip, device.port, device.name)) },
            confirmButton = {
                Button(onClick = {
                    // Use injectMirrorJson — same code path as QR scanning
                    if (device.mirrorJson.isNotEmpty()) {
                        if (injectMirrorJson(device.mirrorJson, context)) triggerRefresh++
                    } else {
                        Toast.makeText(context, context.getString(R.string.mirror_connection_data_missing), Toast.LENGTH_SHORT).show()
                    }
                    pendingNearby = null
                }) { Text(stringResource(R.string.mirror_connect_action)) }
            },
            dismissButton = { OutlinedButton(onClick = { pendingNearby = null }) { Text(stringResource(R.string.cancel)) } }
        )
    }

    if (showMyQR != null) {
        AlertDialog(
            onDismissRequest = { showMyQR = null },
            title = { Text(stringResource(R.string.auto_qr)) },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.scan_with_follower), style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(16.dp))
                    QRCodeImage(showMyQR!!)
                }
            },
            confirmButton = { Button(onClick = { showMyQR = null }) { Text(stringResource(R.string.close)) } }
        )
    }

    // ── Content ──────────────────────────────────────────────────────────

    Scaffold(
        contentWindowInsets = WindowInsets(0.dp),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.sync)) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
                actions = {
                    IconButton(onClick = {
                        Natives.resetnetwork()
                        tk.glucodata.Applic.wakemirrors()
                        Toast.makeText(context, context.getString(R.string.reinit_progress), Toast.LENGTH_SHORT).show()
                        triggerRefresh++
                    }) {
                        Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.mirror_reconnect_all))
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            // ── QR Row ───────────────────────────────────────────────
            item(key = "qr_section") {
                SectionLabel(stringResource(R.string.mirror_quick_pair), topPadding = 8.dp)
            }
            item(key = "qr_share_hybrid") {
                SettingsItem(
                    title = stringResource(R.string.mirror_share_relay_qr),
                    subtitle = stringResource(R.string.mirror_share_relay_qr_desc),
                    icon = Icons.Filled.Cloud,
                    iconTint = MaterialTheme.colorScheme.tertiary,
                    position = CardPosition.TOP,
                    onClick = {
                        val idx = Natives.makeICESender()
                        if (idx >= 0) {
                            showMyQR = Natives.getbackJson(idx)
                            triggerRefresh++
                        } else {
                            Toast.makeText(context, context.getString(R.string.mirror_error_with_code, idx), Toast.LENGTH_SHORT).show()
                        }
                    }
                )
            }
            item(key = "qr_share_local") {
                SettingsItem(
                    title = stringResource(R.string.mirror_share_my_qr),
                    subtitle = stringResource(R.string.mirror_share_my_qr_desc),
                    icon = Icons.Outlined.QrCode,
                    iconTint = MaterialTheme.colorScheme.tertiary,
                    position = CardPosition.MIDDLE,
                    onClick = {
                        val idx = Natives.makeHomeSender()
                        if (idx >= 0) {
                            showMyQR = Natives.getbackJson(idx)
                            triggerRefresh++
                        } else {
                            Toast.makeText(context, context.getString(R.string.mirror_error_with_code, idx), Toast.LENGTH_SHORT).show()
                        }
                    }
                )
            }
            item(key = "qr_scan") {
                SettingsItem(
                    title = stringResource(R.string.scan_qr_button),
                    subtitle = stringResource(R.string.mirror_scan_qr_desc),
                    icon = Icons.Outlined.QrCodeScanner,
                    iconTint = MaterialTheme.colorScheme.tertiary,
                    position = CardPosition.BOTTOM,
                    onClick = { launchScanner() }
                )
            }

            // ── Local Network ────────────────────────────────────────
            item(key = "network_section") {
                SectionLabel(stringResource(R.string.mirror_local_network))
            }
            item(key = "broadcast") {
                SettingsSwitchItem(
                    title = stringResource(R.string.mirror_broadcast_network),
                    subtitle = stringResource(R.string.mirror_broadcast_network_desc),
                    icon = Icons.Filled.CellTower,
                    iconTint = MaterialTheme.colorScheme.tertiary,
                    checked = isBroadcasting,
                    position = CardPosition.SINGLE,
                    onCheckedChange = { checked ->
                        isBroadcasting = checked
                        if (checked) {
                            // Create a sender entry so master actually listens
                            val idx = Natives.makeHomeSender()
                            if (idx >= 0) {
                                broadcastSenderIdx = idx
                                triggerRefresh++
                                val senderPort = Natives.getbackuphostport(idx)?.toIntOrNull() ?: 8795
                                // Get the full JSON (same data as QR code) for the follower
                                val mirrorJson = Natives.getbackJson(idx) ?: ""
                                mdnsManager.registerService(
                                    android.os.Build.MODEL ?: "Device",
                                    senderPort,
                                    mirrorJson
                                )
                            } else {
                                mdnsManager.registerService(android.os.Build.MODEL ?: "Device")
                            }
                        } else {
                            mdnsManager.unregisterService()
                        }
                    }
                )
            }
            if (discoveredMirrors.isNotEmpty()) {
                items(discoveredMirrors, key = { it.ip }) { device ->
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { pendingNearby = device },
                        shape = cardShape(CardPosition.SINGLE),
                        color = MaterialTheme.colorScheme.tertiaryContainer
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Filled.Wifi, contentDescription = null, tint = MaterialTheme.colorScheme.onTertiaryContainer, modifier = Modifier.size(24.dp))
                            Spacer(Modifier.width(16.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(device.name, style = MaterialTheme.typography.titleSmall)
                                Text("${device.ip}:${device.port}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.7f))
                            }
                            Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.5f))
                        }
                    }
                }
            }

            // ── Relay ────────────────────────────────────────────────
            item(key = "relay_section") {
                SectionLabel(stringResource(R.string.mirror_relay))
            }
            item(key = "turn") {
                SettingsItem(
                    title = stringResource(R.string.turnserver),
                    subtitle = stringResource(R.string.mirror_turn_server_desc),
                    icon = Icons.Filled.Cloud,
                    iconTint = MaterialTheme.colorScheme.tertiary,
                    showArrow = true,
                    position = CardPosition.SINGLE,
                    onClick = { navController.navigate("settings/turnserver") }
                )
            }

            // ── Connections ──────────────────────────────────────────
            item(key = "connections_section") {
                Row(Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 12.dp, start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.mirror_connections), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { editSheetPos = -1 }) {
                        Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.add_connection), tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }

            if (mirrors.isEmpty()) {
                item(key = "empty_msg") {
                    Surface(Modifier.fillMaxWidth(), shape = cardShape(CardPosition.SINGLE), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                        Text(stringResource(R.string.mirror_no_connections), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
                    }
                }
            } else {
                items(mirrors, key = { it.index }) { mirror ->
                    MirrorConnectionCard(
                        mirror = mirror,
                        onEdit = { editSheetPos = mirror.index },
                        onToggle = {
                            Natives.setHostDeactivated(mirror.index, !mirror.isDeactivated)
                            Natives.resetnetwork()
                            tk.glucodata.Applic.wakemirrors()
                            triggerRefresh++
                        },
                        onShowQR = { mirror.index },
                        onDelete = {
                            Natives.deletebackuphost(mirror.index)
                            Natives.resetnetwork()
                            tk.glucodata.Applic.wakemirrors()
                            triggerRefresh++
                        }
                    )
                }
            }
        }
    }

    // ── Edit Bottom Sheet ────────────────────────────────────────────────

    if (editSheetPos != null) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        MirrorEditSheet(
            pos = editSheetPos!!,
            sheetState = sheetState,
            onDismiss = { editSheetPos = null; triggerRefresh++ }
        )
    }
}

// ── Connection Card (expandable) ─────────────────────────────────────────────

@Composable
fun MirrorConnectionCard(
    mirror: MirrorItemData,
    onEdit: () -> Unit,
    onToggle: () -> Unit,
    onShowQR: () -> Unit,
    onDelete: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var expanded by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var qrContent by remember { mutableStateOf<String?>(null) }
    var cardTestState by remember { mutableStateOf(ConnTestState.IDLE) }
    val chevronRotation by animateFloatAsState(if (expanded) 180f else 0f, label = "chevron")
    LaunchedEffect(expanded) { if (!expanded) cardTestState = ConnTestState.IDLE }

    if (qrContent != null) {
        AlertDialog(
            onDismissRequest = { qrContent = null },
            title = { Text(mirror.label ?: context.getString(R.string.connection_number, mirror.index)) },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    QRCodeImage(qrContent!!)
                }
            },
            confirmButton = { Button(onClick = { qrContent = null }) { Text(stringResource(R.string.close)) } }
        )
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            icon = { Icon(Icons.Filled.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            title = { Text(stringResource(R.string.mirror_delete_connection_title)) },
            text = { Text(stringResource(R.string.mirror_delete_connection_message, mirror.label ?: mirror.names?.firstOrNull() ?: stringResource(R.string.connection_label))) },
            confirmButton = {
                Button(
                    onClick = { onDelete(); showDeleteConfirm = false },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = { OutlinedButton(onClick = { showDeleteConfirm = false }) { Text(stringResource(R.string.cancel)) } }
        )
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = cardShape(CardPosition.SINGLE),
        color = if (mirror.isDeactivated) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        mirror.label?.takeIf { it.isNotEmpty() } ?: mirror.names?.firstOrNull() ?: context.getString(R.string.connection_number, mirror.index),
                        style = MaterialTheme.typography.titleMedium,
                        color = if (mirror.isDeactivated) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f) else Color.Unspecified
                    )
                    val sub = if (mirror.isDeactivated) stringResource(R.string.deactivated)
                    else if (!mirror.port.isNullOrEmpty()) ":${mirror.port}" else null
                    if (sub != null) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(Icons.Filled.ExpandMore, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.graphicsLayer { rotationZ = chevronRotation })
            }

            AnimatedVisibility(visible = expanded, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                Column {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    if (!mirror.isDeactivated) {
                        AndroidView<TextView>(
                            factory = { ctx -> TextView(ctx).apply { textSize = 13f } },
                            update = { it.text = Html.fromHtml(mirror.status, Html.FROM_HTML_MODE_LEGACY) },
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = { showDeleteConfirm = true }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                            Text(stringResource(R.string.delete))
                        }
                        TextButton(onClick = onToggle) {
                            Text(if (mirror.isDeactivated) stringResource(R.string.enable) else stringResource(R.string.disable))
                        }
                        TextButton(onClick = { qrContent = Natives.getbackJson(mirror.index) }) {
                            Text(stringResource(R.string.qr))
                        }
                        val testHost = mirror.names?.firstOrNull()?.takeIf { it.isNotBlank() }
                        if (testHost != null) {
                            TextButton(
                                onClick = {
                                    cardTestState = ConnTestState.TESTING
                                    scope.launch {
                                        val portInt = mirror.port?.toIntOrNull() ?: 8795
                                        cardTestState = if (testJugglucoConnection(testHost, portInt))
                                            ConnTestState.SUCCESS else ConnTestState.FAILURE
                                    }
                                },
                                enabled = cardTestState != ConnTestState.TESTING,
                                colors = ButtonDefaults.textButtonColors(
                                    contentColor = when (cardTestState) {
                                        ConnTestState.SUCCESS -> Color(0xFF4CAF50)
                                        ConnTestState.FAILURE -> MaterialTheme.colorScheme.error
                                        else -> MaterialTheme.colorScheme.primary
                                    }
                                )
                            ) {
                                if (cardTestState == ConnTestState.TESTING) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(12.dp),
                                        strokeWidth = 1.5.dp,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(Modifier.width(4.dp))
                                }
                                Text(stringResource(R.string.test))
                            }
                        }
                        TextButton(onClick = onEdit) {
                            Text(stringResource(R.string.edit))
                        }
                    }
                }
            }
        }
    }
}

// ── Edit Sheet ───────────────────────────────────────────────────────────────

enum class ConnectionType { LOCAL, ICE, DIRECT }
enum class ConnectionDirection { PASSIVE, ACTIVE, BOTH }

@Composable
fun MirrorEditSheet(pos: Int, sheetState: SheetState, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val isNew = pos == -1
    val scope = rememberCoroutineScope()
    var testState by remember { mutableStateOf(ConnTestState.IDLE) }
    fun connectionTypeLabel(option: ConnectionType): String = when (option) {
        ConnectionType.LOCAL -> context.getString(R.string.mirror_type_local)
        ConnectionType.ICE -> context.getString(R.string.ice)
        ConnectionType.DIRECT -> context.getString(R.string.mirror_type_direct_ip)
    }
    fun directionLabel(option: ConnectionDirection): String = when (option) {
        ConnectionDirection.PASSIVE -> context.getString(R.string.mirror_direction_passive)
        ConnectionDirection.ACTIVE -> context.getString(R.string.mirror_direction_active)
        ConnectionDirection.BOTH -> context.getString(R.string.mirror_direction_both)
    }

    // Determine connection type from existing entry
    val existingICELabel = if (!isNew) Natives.getICElabel(pos) else null
    val hasICE = !existingICELabel.isNullOrBlank()
    val existingAutoDetect = if (!isNew && !hasICE) Natives.detectIP(pos) else true
    val existingHasHostname = if (!isNew && !hasICE) Natives.getbackupHasHostname(pos) else false
    val existingActiveOnly = if (!isNew) Natives.getbackuphostactive(pos) else false
    val existingPassiveOnly = if (!isNew) Natives.getbackuphostpassive(pos) else false
    val originalConnectionType = when {
        isNew -> ConnectionType.LOCAL
        hasICE -> ConnectionType.ICE
        existingHasHostname -> ConnectionType.DIRECT
        else -> ConnectionType.LOCAL
    }

    var connectionType by remember { mutableStateOf(
        originalConnectionType
    )}

    // Connection fields
    var port by remember { mutableStateOf(if (!isNew) Natives.getbackuphostport(pos) ?: "8795" else "8795") }
    var password by remember { mutableStateOf(if (!isNew) Natives.getbackuppassword(pos) ?: "" else "") }
    var passwordVisible by remember { mutableStateOf(false) }

    // ICE label (email / identifier for ICE connections)
    var iceLabel by remember { mutableStateOf(existingICELabel ?: "") }
    var iceSide by remember { mutableStateOf(if (!isNew && hasICE) Natives.getICEside(pos) else false) }

    // Connection label (human-readable name for this entry)
    var connectionLabel by remember { mutableStateOf(if (!isNew) Natives.getbackuplabel(pos) ?: "" else "") }

    // IP/hostname for non-ICE connections
    var hostname by remember { mutableStateOf(
        if (!isNew && !hasICE) Natives.getbackupIPs(pos)?.firstOrNull() ?: "" else ""
    )}

    // Role: what data flows
    var isSending by remember { mutableStateOf(
        if (!isNew) Natives.getbackuphostnums(pos) || Natives.getbackuphoststream(pos) || Natives.getbackuphostscans(pos)
        else false
    )}
    var isReceiving by remember { mutableStateOf(
        if (!isNew) (Natives.getbackuphostreceive(pos) and 2) != 0 else true
    )}

    // Auto-detect IP (only for Local mode)
    var autoDetect by remember { mutableStateOf(
        existingAutoDetect
    )}

    // Connection direction: passive (listen only), active (connect out only), or both
    var direction by remember { mutableStateOf(
        when {
            existingPassiveOnly -> ConnectionDirection.PASSIVE
            existingActiveOnly -> ConnectionDirection.ACTIVE
            else -> ConnectionDirection.BOTH
        }
    )}

    LaunchedEffect(hostname, port, connectionType) { testState = ConnTestState.IDLE }

    fun save(): Boolean {
        val isICE = connectionType == ConnectionType.ICE
        val isDirect = connectionType == ConnectionType.DIRECT
        val isLocal = connectionType == ConnectionType.LOCAL
        val finalActiveOnly = direction == ConnectionDirection.ACTIVE
        val finalPassiveOnly = direction == ConnectionDirection.PASSIVE
        val finalDetect = isLocal && autoDetect && !finalActiveOnly

        if (!isSending && !isReceiving) {
            Toast.makeText(context, context.getString(R.string.specifyreceiveordata), Toast.LENGTH_SHORT).show()
            return false
        }
        if (isSending && isReceiving) {
            Toast.makeText(context, context.getString(R.string.allsentnoreceive), Toast.LENGTH_LONG).show()
            return false
        }
        if ((isDirect || (isLocal && !finalDetect)) && hostname.isBlank()) {
            Toast.makeText(context, context.getString(R.string.specifyip), Toast.LENGTH_SHORT).show()
            return false
        }
        if (isICE && iceLabel.length < 16) {
            Toast.makeText(context, changeHostErrorMessage(context, -16), Toast.LENGTH_SHORT).show()
            return false
        }

        // Build names array
        val finalNames: Array<String>
        val nameCount: Int
        if (isICE) {
            finalNames = arrayOf("")
            nameCount = 0
        } else if (finalDetect) {
            finalNames = arrayOf("")
            nameCount = 0
        } else if (hostname.isNotEmpty()) {
            finalNames = arrayOf(hostname)
            nameCount = 1
        } else {
            finalNames = arrayOf("")
            nameCount = 0
        }

        val finalPort = port.ifEmpty { "8795" }

        // Map flags per connection type, matching Backup.java line 612:
        // changebackuphost(pos, names, nr, detect, port, nums, stream, scans,
        //   recover, receive, activeonly, passiveonly, pass, starttime, label,
        //   testip, hasname, icelabel, side)
        val result = Natives.changebackuphost(
            if (isNew) -1 else pos,
            finalNames,
            nameCount,
            /* detect */ finalDetect,
            finalPort,
            /* nums */ isSending,
            /* stream */ isSending,
            /* scans */ isSending,
            /* recover */ false,
            /* receive */ isReceiving,
            /* activeonly */ finalActiveOnly,
            /* passiveonly */ finalPassiveOnly,
            /* pass */ password.ifEmpty { null },
            /* starttime */ 0L,
            /* label */ connectionLabel.ifEmpty { null },
            /* testip */ isICE || isDirect || (isLocal && !autoDetect),
            /* hasname */ isDirect || (isLocal && !autoDetect),
            /* icelabel */ if (isICE) iceLabel else null,
            /* side */ iceSide
        )
        if (result < 0) {
            Toast.makeText(context, changeHostErrorMessage(context, result), Toast.LENGTH_SHORT).show()
            return false
        }
        Natives.resetnetwork()
        tk.glucodata.Applic.wakemirrors()
        return true
    }

    StableModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        dragHandle = { CompactSheetDragHandle() },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 32.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                if (isNew) stringResource(R.string.add_connection) else stringResource(R.string.edit_connection),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp)
            )

            // Connection Type
            SectionLabel(stringResource(R.string.mirror_connection_type), topPadding = 0.dp, modifier = Modifier.padding(horizontal = 24.dp))
            ConnectedButtonGroup(
                options = ConnectionType.entries.toList(),
                selectedOption = connectionType,
                onOptionSelected = { connectionType = it },
                labelText = ::connectionTypeLabel,
                label = { option -> Text(connectionTypeLabel(option)) },
                itemHeight = 40.dp,
                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                selectedContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                unselectedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)
            )

            // Type hint
            val typeHint = when (connectionType) {
                ConnectionType.LOCAL -> stringResource(R.string.mirror_type_local_hint)
                ConnectionType.ICE -> stringResource(R.string.mirror_type_ice_hint)
                ConnectionType.DIRECT -> stringResource(R.string.mirror_type_direct_hint)
            }
            Text(typeHint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))

            // Fields per connection type
            SectionLabel(stringResource(R.string.mirror_details), modifier = Modifier.padding(horizontal = 24.dp))
            Column(modifier = Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (connectionType) {
                    ConnectionType.LOCAL -> {
                        val localActiveOnly = direction == ConnectionDirection.ACTIVE
                        if (!localActiveOnly) {
                            SettingsSwitchItem(
                                title = stringResource(R.string.mirror_auto_detect_ip),
                                subtitle = stringResource(R.string.mirror_auto_detect_ip_desc),
                                checked = autoDetect,
                                onCheckedChange = { autoDetect = it },
                                icon = Icons.Filled.Wifi,
                                iconTint = MaterialTheme.colorScheme.tertiary,
                                position = CardPosition.SINGLE
                            )
                        }
                        if (localActiveOnly || !autoDetect) {
                            OutlinedTextField(
                                value = hostname, onValueChange = { hostname = it },
                                label = { Text(stringResource(R.string.mirror_ip_address)) },
                                supportingText = { Text(stringResource(R.string.mirror_ip_address_desc)) },
                                modifier = Modifier.fillMaxWidth(), singleLine = true
                            )
                        }
                    }
                    ConnectionType.ICE -> {
                        OutlinedTextField(
                            value = iceLabel, onValueChange = { iceLabel = it },
                            label = { Text(stringResource(R.string.ice_label)) },
                            supportingText = { Text(stringResource(R.string.mirror_ice_label_desc)) },
                            modifier = Modifier.fillMaxWidth(), singleLine = true
                        )
                    }
                    ConnectionType.DIRECT -> {
                        OutlinedTextField(
                            value = hostname, onValueChange = { hostname = it },
                            label = { Text(stringResource(R.string.mirror_hostname_ip_address)) },
                            supportingText = { Text(stringResource(R.string.mirror_hostname_ip_desc)) },
                            modifier = Modifier.fillMaxWidth(), singleLine = true
                        )
                    }
                }
                OutlinedTextField(
                    value = port, onValueChange = { port = it },
                    label = { Text(stringResource(R.string.port)) },
                    supportingText = { Text(stringResource(R.string.mirror_port_default)) },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
            }

            // Connection label
            SectionLabel(stringResource(R.string.label), modifier = Modifier.padding(horizontal = 24.dp))
            OutlinedTextField(
                value = connectionLabel, onValueChange = { connectionLabel = it },
                label = { Text(stringResource(R.string.mirror_connection_label)) },
                supportingText = { Text(stringResource(R.string.mirror_connection_label_desc)) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp), singleLine = true
            )

            // Connection Direction (visible for Local and Direct only)
            if (connectionType != ConnectionType.ICE) {
                SectionLabel(stringResource(R.string.mirror_direction), modifier = Modifier.padding(horizontal = 24.dp))
                ConnectedButtonGroup(
                    options = ConnectionDirection.entries.toList(),
                    selectedOption = direction,
                    onOptionSelected = { direction = it },
                    labelText = ::directionLabel,
                    label = { option -> Text(directionLabel(option)) },
                    itemHeight = 40.dp,
                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    selectedContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    unselectedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)
                )
                val dirHint = when (direction) {
                    ConnectionDirection.PASSIVE -> stringResource(R.string.mirror_direction_passive_hint)
                    ConnectionDirection.ACTIVE -> stringResource(R.string.mirror_direction_active_hint)
                    ConnectionDirection.BOTH -> stringResource(R.string.mirror_direction_both_hint)
                }
                Text(dirHint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
            }

            // Role
            SectionLabel(stringResource(R.string.mirror_role), modifier = Modifier.padding(horizontal = 24.dp))
            Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.padding(horizontal = 24.dp)) {
                SettingsSwitchItem(
                    title = stringResource(R.string.mirror_receive_data), subtitle = stringResource(R.string.mirror_receive_data_desc),
                    checked = isReceiving, onCheckedChange = { isReceiving = it },
                    icon = Icons.Filled.Download, iconTint = MaterialTheme.colorScheme.tertiary,
                    position = CardPosition.TOP
                )
                SettingsSwitchItem(
                    title = stringResource(R.string.mirror_send_data), subtitle = stringResource(R.string.mirror_send_data_desc),
                    checked = isSending, onCheckedChange = { isSending = it },
                    icon = Icons.Filled.Upload, iconTint = MaterialTheme.colorScheme.tertiary,
                    position = CardPosition.BOTTOM
                )
            }

            // Password
            SectionLabel(stringResource(R.string.mirror_security), modifier = Modifier.padding(horizontal = 24.dp))
            OutlinedTextField(
                value = password, onValueChange = { password = it },
                label = { Text(stringResource(R.string.password)) },
                supportingText = { Text(stringResource(R.string.mirror_password_desc)) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp), singleLine = true,
                visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { passwordVisible = !passwordVisible }) {
                        Icon(if (passwordVisible) Icons.Filled.Visibility else Icons.Filled.VisibilityOff, contentDescription = null)
                    }
                }
            )

            // Test connectivity (only for connections with a specific hostname/IP)
            val canTest = (connectionType == ConnectionType.DIRECT ||
                    (connectionType == ConnectionType.LOCAL && !autoDetect)) &&
                    hostname.isNotBlank()
            if (canTest) {
                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            testState = ConnTestState.TESTING
                            scope.launch {
                                val portInt = port.toIntOrNull() ?: 8795
                                testState = if (testJugglucoConnection(hostname.trim(), portInt))
                                    ConnTestState.SUCCESS else ConnTestState.FAILURE
                            }
                        },
                        enabled = testState != ConnTestState.TESTING,
                        modifier = Modifier.weight(1f)
                    ) {
                        if (testState == ConnTestState.TESTING) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(if (testState == ConnTestState.TESTING)
                            stringResource(R.string.connecting) else stringResource(R.string.test))
                    }
                    when (testState) {
                        ConnTestState.SUCCESS -> {
                            Icon(Icons.Filled.CheckCircle, contentDescription = null,
                                tint = Color(0xFF4CAF50), modifier = Modifier.size(24.dp))
                            Text(stringResource(R.string.status_connected),
                                color = Color(0xFF4CAF50),
                                style = MaterialTheme.typography.bodyMedium)
                        }
                        ConnTestState.FAILURE -> {
                            Icon(Icons.Filled.Error, contentDescription = null,
                                tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(24.dp))
                            Text(stringResource(R.string.status_connection_failed),
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodyMedium)
                        }
                        else -> {}
                    }
                }
            }

            // Save
            Spacer(Modifier.height(24.dp))
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (!isNew) {
                    OutlinedButton(
                        onClick = {
                            Natives.deletebackuphost(pos)
                            Natives.resetnetwork()
                            tk.glucodata.Applic.wakemirrors()
                            onDismiss()
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) { Text(stringResource(R.string.delete)) }
                }
                Button(onClick = { if (save()) onDismiss() }, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.save))
                }
            }
        }
    }
}

// ── Data ──────────────────────────────────────────────────────────────────────

data class MirrorItemData(
    val index: Int, val label: String?, val names: Array<String>?,
    val port: String?, val isDeactivated: Boolean, val status: String
)

fun getMirrorsList(): List<MirrorItemData> {
    val mirrors = mutableListOf<MirrorItemData>()
    for (i in 0 until Natives.backuphostNr()) {
        val names = Natives.getbackupIPs(i) ?: emptyArray()
        mirrors.add(MirrorItemData(i, Natives.getbackuplabel(i), names, Natives.getbackuphostport(i), Natives.getHostDeactivated(i), Natives.mirrorStatus(i) ?: ""))
    }
    return mirrors
}
