@file:OptIn(ExperimentalMaterial3Api::class)

package tk.glucodata.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.QrCode
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import tk.glucodata.R
import tk.glucodata.util.DiscoveredMirror
import tk.glucodata.ui.components.*
import tk.glucodata.ui.components.AppTopBar

@Composable
internal fun CloneSettingsContent(
    cloneEnabled: Boolean,
    controlsEnabled: Boolean,
    hasConnections: Boolean,
    hasReceiver: Boolean,
    backgroundLiveness: Boolean,
    broadcasting: Boolean,
    broadcastName: String,
    nearbyDevices: List<DiscoveredMirror>,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onMasterChanged: (Boolean) -> Unit,
    onShare: (QuickPairKind) -> Unit,
    onScan: () -> Unit,
    onBackgroundChanged: (Boolean) -> Unit,
    onBroadcastChanged: (Boolean) -> Unit,
    onNearbyDevice: (DiscoveredMirror) -> Unit,
    onNetworkSettings: () -> Unit,
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
            item(key = "clone_master") {
                MasterSwitchCard(
                    title = stringResource(R.string.mirror_clone_master),
                    subtitle = stringResource(if (!hasConnections) R.string.clone_add_first
                        else if (cloneEnabled) R.string.enabled_status else R.string.disabled_status),
                    checked = cloneEnabled,
                    enabled = controlsEnabled,
                    icon = Icons.Default.SyncAlt,
                    onCheckedChange = onMasterChanged,
                )
            }
            // Pairing comes first: it is what almost everyone opens this screen to do.
            item(key = "qr_section") {
                SectionLabel(stringResource(R.string.mirror_quick_pair))
            }
            item(key = "qr_share_hybrid") {
                SettingsItem(
                    title = stringResource(R.string.mirror_share_relay_qr),
                    subtitle = stringResource(R.string.mirror_share_relay_qr_desc),
                    icon = Icons.Outlined.QrCode,
                    iconTint = MaterialTheme.colorScheme.primary,
                    position = CardPosition.TOP,
                    onClick = { onShare(QuickPairKind.HYBRID) },
                )
            }
            item(key = "qr_scan") {
                SettingsItem(
                    title = stringResource(R.string.scan_qr_button),
                    subtitle = stringResource(R.string.mirror_scan_qr_desc),
                    icon = Icons.Outlined.QrCodeScanner,
                    iconTint = MaterialTheme.colorScheme.primary,
                    position = CardPosition.BOTTOM,
                    onClick = onScan,
                )
            }
            item(key = "wifi_section") { SectionLabel(stringResource(R.string.clone_same_wifi)) }
            item(key = "broadcast") {
                SettingsSwitchItem(
                    title = stringResource(R.string.clone_visible_nearby),
                    subtitle = if (broadcasting) {
                        stringResource(R.string.clone_visible_nearby_on, broadcastName)
                    } else {
                        stringResource(R.string.clone_visible_nearby_desc)
                    },
                    checked = broadcasting,
                    onCheckedChange = onBroadcastChanged,
                    icon = Icons.Default.WifiTethering,
                    iconTint = MaterialTheme.colorScheme.tertiary,
                    position = CardPosition.TOP,
                )
            }
            item(key = "qr_share_local") {
                SettingsItem(
                    title = stringResource(R.string.mirror_share_my_qr),
                    subtitle = stringResource(R.string.mirror_share_my_qr_desc),
                    icon = Icons.Outlined.QrCode,
                    iconTint = MaterialTheme.colorScheme.tertiary,
                    position = if (nearbyDevices.isEmpty()) CardPosition.BOTTOM else CardPosition.MIDDLE,
                    animatePosition = true,
                    onClick = { onShare(QuickPairKind.LOCAL) },
                )
            }
            itemsIndexed(nearbyDevices, key = { _, device -> "nearby_${device.ip}" }) { index, device ->
                SettingsItem(
                    title = device.name,
                    subtitle = stringResource(R.string.clone_nearby_device_desc),
                    icon = Icons.Default.PhoneAndroid,
                    iconTint = MaterialTheme.colorScheme.tertiary,
                    position = if (index == nearbyDevices.lastIndex) CardPosition.BOTTOM else CardPosition.MIDDLE,
                    animatePosition = true,
                    onClick = { onNearbyDevice(device) },
                    trailingContent = {
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                )
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
                    position = if (hasConnections) CardPosition.BOTTOM else CardPosition.SINGLE,
                    onClick = onNetworkSettings,
                )
            }
            connections()
        }
    }
}
