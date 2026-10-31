@file:OptIn(ExperimentalMaterial3Api::class)

package tk.glucodata.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.QrCode
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
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
    listsConnections: Boolean,
    backgroundLiveness: Boolean,
    nearbyDevices: List<DiscoveredMirror>,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onMasterChanged: (Boolean) -> Unit,
    onShowCode: () -> Unit,
    onScan: () -> Unit,
    onGetReadings: (DiscoveredMirror) -> Unit,
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
            // Returning users come to check their connections; first-time users have none, so
            // pairing leads for them.
            connections()
            item(key = "pairing") {
                Column(
                    modifier = Modifier.animateItem().padding(top = if (listsConnections) 24.dp else 0.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // A phone sharing on this Wi-Fi is the most specific thing to do here.
                    nearbyDevices.forEach { device ->
                        AppUpdateCard(
                            accent = MaterialTheme.colorScheme.tertiary,
                            title = stringResource(R.string.clone_nearby_sharing, device.name),
                            body = stringResource(R.string.clone_nearby_sharing_body),
                            icon = Icons.Default.PhoneAndroid,
                            elevated = true,
                        ) {
                            ClonePairAction(
                                label = stringResource(R.string.clone_get_readings),
                                icon = Icons.Default.Download,
                                filled = true,
                                accent = MaterialTheme.colorScheme.tertiary,
                                onClick = { onGetReadings(device) },
                            )
                        }
                    }
                    AppUpdateCard(
                        accent = MaterialTheme.colorScheme.primary,
                        title = stringResource(R.string.clone_connect_title),
                        body = stringResource(
                            R.string.clone_connect_body,
                            stringResource(R.string.clone_show_code),
                            stringResource(R.string.clone_scan_code),
                        ),
                        icon = Icons.Default.AddLink,
                        // The screen's hero until something is paired; a quiet card after that.
                        elevated = !hasConnections,
                    ) {
                        ClonePairAction(
                            label = stringResource(R.string.clone_scan_code),
                            icon = Icons.Outlined.QrCodeScanner,
                            filled = false,
                            accent = MaterialTheme.colorScheme.primary,
                            onClick = onScan,
                        )
                        ClonePairAction(
                            label = stringResource(R.string.clone_show_code),
                            icon = Icons.Outlined.QrCode,
                            filled = true,
                            accent = MaterialTheme.colorScheme.primary,
                            onClick = onShowCode,
                        )
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
private fun ClonePairAction(
    label: String,
    icon: ImageVector,
    filled: Boolean,
    accent: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
) {
    val content: @Composable RowScope.() -> Unit = {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, maxLines = 1)
    }
    if (filled) {
        Button(
            onClick = onClick,
            colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = contentColorFor(accent)),
            shapes = ButtonDefaults.shapes(),
            contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
            content = content,
        )
    } else {
        OutlinedButton(
            onClick = onClick,
            shapes = ButtonDefaults.shapes(),
            contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
            content = content,
        )
    }
}
