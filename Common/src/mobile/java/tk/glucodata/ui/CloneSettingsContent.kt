@file:OptIn(ExperimentalMaterial3Api::class)

package tk.glucodata.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.QrCode
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import tk.glucodata.R
import tk.glucodata.ui.components.*

@Composable
internal fun CloneSettingsContent(
    cloneEnabled: Boolean,
    controlsEnabled: Boolean,
    hasConnections: Boolean,
    hasReceiver: Boolean,
    backgroundLiveness: Boolean,
    broadcasting: Boolean,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onMasterChanged: (Boolean) -> Unit,
    onShare: (QuickPairKind) -> Unit,
    onScan: () -> Unit,
    onBackgroundChanged: (Boolean) -> Unit,
    onBroadcastChanged: (Boolean) -> Unit,
    onNetworkSettings: () -> Unit,
    connections: LazyListScope.() -> Unit,
) {
    Scaffold(
        contentWindowInsets = WindowInsets(0.dp),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.clone_sync_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.navigate_back)) }
                },
                actions = {
                    IconButton(onClick = onRefresh) { Icon(Icons.Default.Refresh, stringResource(R.string.mirror_reconnect_all)) }
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
                    onClick = { onShare(QuickPairKind.HYBRID) },
                )
            }
            item(key = "qr_share_local") {
                SettingsItem(
                    title = stringResource(R.string.mirror_share_my_qr),
                    subtitle = stringResource(R.string.mirror_share_my_qr_desc),
                    icon = Icons.Outlined.QrCode,
                    iconTint = MaterialTheme.colorScheme.tertiary,
                    position = CardPosition.MIDDLE,
                    onClick = { onShare(QuickPairKind.LOCAL) },
                )
            }
            item(key = "qr_scan") {
                SettingsItem(
                    title = stringResource(R.string.scan_qr_button),
                    subtitle = stringResource(R.string.mirror_scan_qr_desc),
                    icon = Icons.Outlined.QrCodeScanner,
                    iconTint = MaterialTheme.colorScheme.tertiary,
                    position = CardPosition.BOTTOM,
                    onClick = onScan,
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
                        iconTint = MaterialTheme.colorScheme.tertiary,
                        position = CardPosition.TOP,
                    )
                }
            }
            item(key = "broadcast") {
                SettingsSwitchItem(
                    title = stringResource(R.string.clone_visible_nearby),
                    subtitle = stringResource(R.string.clone_visible_nearby_desc),
                    checked = broadcasting,
                    onCheckedChange = onBroadcastChanged,
                    icon = Icons.Default.CellTower,
                    iconTint = MaterialTheme.colorScheme.tertiary,
                    position = if (hasConnections) CardPosition.MIDDLE else CardPosition.TOP,
                )
            }
            item(key = "turn") {
                SettingsItem(
                    title = stringResource(R.string.clone_network_title),
                    subtitle = stringResource(R.string.clone_network_summary),
                    icon = Icons.Default.Hub,
                    iconTint = MaterialTheme.colorScheme.tertiary,
                    position = CardPosition.BOTTOM,
                    onClick = onNetworkSettings,
                )
            }
            connections()
        }
    }
}
