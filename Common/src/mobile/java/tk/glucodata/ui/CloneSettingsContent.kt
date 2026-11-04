@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package tk.glucodata.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.QrCode
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import tk.glucodata.R
import tk.glucodata.util.DiscoveredMirror
import tk.glucodata.ui.components.*
import tk.glucodata.ui.components.AppTopBar
import tk.glucodata.ui.util.ConnectedButtonGroup

internal enum class ClonePairMode { SEND, GET }

/** What this phone is offering while "Send readings" is open. */
internal data class CloneSendState(
    val kind: QuickPairKind,
    val json: String,
    val announced: Boolean,
    val connected: Boolean,
)

@Composable
internal fun CloneSettingsContent(
    cloneEnabled: Boolean,
    controlsEnabled: Boolean,
    hasConnections: Boolean,
    listsConnections: Boolean,
    backgroundLiveness: Boolean,
    pairMode: ClonePairMode?,
    send: CloneSendState?,
    deviceName: String,
    nearbyDevices: List<DiscoveredMirror>,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onMasterChanged: (Boolean) -> Unit,
    onPairMode: (ClonePairMode?) -> Unit,
    onSendKind: (QuickPairKind) -> Unit,
    onScan: () -> Unit,
    onConnectNearby: (DiscoveredMirror) -> Unit,
    onBackgroundChanged: (Boolean) -> Unit,
    onNetworkSettings: () -> Unit,
    onAddManually: () -> Unit,
    connections: LazyListScope.() -> Unit,
) {
    Scaffold(
        contentWindowInsets = WindowInsets(0.dp),
        topBar = {
            AppTopBar(
                title = stringResource(R.string.clone_sync_title),
                onNavigateBack = onBack,
                actions = {
                    IconButtonTooltip(stringResource(R.string.mirror_reconnect_all)) {
                        IconButton(onClick = onRefresh, shapes = IconButtonDefaults.shapes()) {
                            Icon(Icons.Default.Refresh, stringResource(R.string.mirror_reconnect_all))
                        }
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            // The master switch only means something once there is a connection to turn off.
            if (hasConnections) {
                item(key = "clone_master") {
                    MasterSwitchCard(
                        title = stringResource(R.string.mirror_clone_master),
                        subtitle = stringResource(if (cloneEnabled) R.string.enabled_status else R.string.disabled_status),
                        checked = cloneEnabled,
                        enabled = controlsEnabled,
                        icon = Icons.Default.SyncAlt,
                        onCheckedChange = onMasterChanged,
                    )
                }
            }
            connections()
            item(key = "pair_section") {
                SectionLabel(
                    stringResource(R.string.clone_connect_title),
                    topPadding = if (listsConnections || hasConnections) 24.dp else 8.dp,
                )
            }
            // The phone's role is the first decision; everything after it happens on this screen.
            item(key = "pair_modes") {
                val sendLabel = stringResource(R.string.clone_send_readings)
                val getLabel = stringResource(R.string.clone_get_readings)
                val modeLabel = { mode: ClonePairMode -> if (mode == ClonePairMode.SEND) sendLabel else getLabel }
                ConnectedButtonGroup(
                    options = listOf(ClonePairMode.SEND, ClonePairMode.GET),
                    selectedOption = pairMode,
                    onOptionSelected = { mode -> onPairMode(if (mode == pairMode) null else mode) },
                    labelText = modeLabel,
                    label = { mode -> Text(modeLabel(mode)) },
                    icon = { mode -> if (mode == ClonePairMode.SEND) Icons.Outlined.QrCode else Icons.Outlined.QrCodeScanner },
                    itemHeight = 56.dp,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (pairMode != null) {
                item(key = "pair_panel") {
                    Surface(
                        modifier = Modifier.animateItem().fillMaxWidth().padding(top = 8.dp),
                        shape = MaterialTheme.shapes.extraLarge,
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                    ) {
                        AnimatedContent(targetState = pairMode, label = "pair_panel") { mode ->
                            Column(Modifier.padding(16.dp)) {
                                when (mode) {
                                    ClonePairMode.SEND -> CloneSendPanel(send, deviceName, onSendKind)
                                    ClonePairMode.GET -> CloneGetPanel(nearbyDevices, onScan, onConnectNearby)
                                }
                            }
                        }
                    }
                }
            }
            item(key = "setup_section") { SectionLabel(stringResource(R.string.clone_connection_options)) }
            // A preference, not a status: gating it on a connection being live right
            // now is what made it look deleted whenever one was not.
            if (hasConnections) {
                item(key = "clone_background_liveness") {
                    SettingsSwitchItem(
                        title = stringResource(R.string.mirror_background_liveness),
                        subtitle = stringResource(R.string.clone_background_desc),
                        checked = backgroundLiveness,
                        enabled = controlsEnabled,
                        onCheckedChange = onBackgroundChanged,
                        icon = Icons.Default.BatterySaver,
                        iconTint = MaterialTheme.colorScheme.secondary,
                        position = CardPosition.TOP,
                    )
                }
            }
            item(key = "turn") {
                SettingsItem(
                    title = stringResource(R.string.clone_network_title),
                    subtitle = stringResource(R.string.clone_network_summary),
                    icon = Icons.Default.Hub,
                    iconTint = MaterialTheme.colorScheme.secondary,
                    position = if (hasConnections) CardPosition.MIDDLE else CardPosition.TOP,
                    animatePosition = true,
                    onClick = onNetworkSettings,
                )
            }
            item(key = "add_manually") {
                SettingsItem(
                    title = stringResource(R.string.add_connection),
                    subtitle = stringResource(R.string.clone_add_manually_desc),
                    icon = Icons.Default.Add,
                    iconTint = MaterialTheme.colorScheme.secondary,
                    position = CardPosition.BOTTOM,
                    onClick = onAddManually,
                )
            }
        }
    }
}

@Composable
private fun CloneSendPanel(
    send: CloneSendState?,
    deviceName: String,
    onKind: (QuickPairKind) -> Unit,
) {
    if (send == null) return
    // Once the other phone has joined, the code has done its job; showing it longer only
    // invites a second phone to scan a code already in use.
    AnimatedVisibility(visible = !send.connected) {
        Column {
            Text(
                stringResource(
                    R.string.clone_qr_instruction,
                    stringResource(R.string.clone_sync_title),
                    stringResource(R.string.clone_get_readings),
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            val anyNetwork = stringResource(R.string.clone_code_any_network)
            val wifiOnly = stringResource(R.string.clone_code_wifi_only)
            val kindLabel = { kind: QuickPairKind -> if (kind == QuickPairKind.HYBRID) anyNetwork else wifiOnly }
            ConnectedButtonGroup(
                options = listOf(QuickPairKind.HYBRID, QuickPairKind.LOCAL),
                selectedOption = send.kind,
                onOptionSelected = { if (it != send.kind) onKind(it) },
                labelText = kindLabel,
                label = { kind -> Text(kindLabel(kind)) },
                selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                selectedContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                unselectedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))
            BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                // The encoder draws the code's own white quiet zone.
                val qrSize = minOf(maxWidth, 320.dp)
                QRCodeImage(
                    send.json,
                    with(LocalDensity.current) { qrSize.roundToPx() },
                    Modifier
                        .size(qrSize)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.White),
                )
            }
            Spacer(Modifier.height(16.dp))
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (send.connected) {
            Icon(
                Icons.Filled.CheckCircle,
                contentDescription = null,
                tint = StatusPalette.success(),
                modifier = Modifier.size(32.dp),
            )
        } else {
            LoadingIndicator(modifier = Modifier.size(32.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(if (send.connected) R.string.clone_pairing_connected else R.string.clone_pairing_waiting),
                style = MaterialTheme.typography.titleSmall,
            )
            if (!send.connected && send.announced) {
                Text(
                    stringResource(R.string.clone_pairing_announced, deviceName),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun CloneGetPanel(
    nearbyDevices: List<DiscoveredMirror>,
    onScan: () -> Unit,
    onConnectNearby: (DiscoveredMirror) -> Unit,
) {
    Button(
        onClick = onScan,
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        shapes = ButtonDefaults.shapes(),
        contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
    ) {
        Icon(Icons.Outlined.QrCodeScanner, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.clone_scan_code))
    }
    Spacer(Modifier.height(16.dp))
    Text(
        stringResource(R.string.clone_on_this_wifi),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
    )
    if (nearbyDevices.isEmpty()) {
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.clone_nearby_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        nearbyDevices.forEach { device ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp).heightIn(min = 56.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(device.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(R.string.clone_sharing_readings),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(12.dp))
                FilledTonalButton(
                    onClick = { onConnectNearby(device) },
                    shapes = ButtonDefaults.shapes(),
                    contentPadding = ButtonDefaults.ContentPadding,
                ) {
                    Text(stringResource(R.string.mirror_connect_action))
                }
            }
        }
    }
}
