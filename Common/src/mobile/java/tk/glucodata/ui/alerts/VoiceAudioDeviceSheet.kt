@file:OptIn(ExperimentalMaterial3Api::class)

package tk.glucodata.ui.alerts

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import tk.glucodata.R
import tk.glucodata.VoiceAudioPolicy
import tk.glucodata.VoiceAudioTrigger
import tk.glucodata.ui.components.CardPosition
import tk.glucodata.ui.components.IconTile
import tk.glucodata.ui.components.SectionLabel
import tk.glucodata.ui.components.SettingsSwitchItem
import tk.glucodata.ui.components.StableModalBottomSheet
import tk.glucodata.ui.components.cardShape
import tk.glucodata.ui.symbols.Symbols
import tk.glucodata.ui.StatusPalette
import tk.glucodata.ui.util.ConnectedButtonGroup

@Composable
internal fun VoiceAudioDeviceSheet(
    snapshot: VoiceAudioTrigger.Snapshot,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var includeAllPaired by remember { mutableStateOf(false) }
    var permissionRefreshTick by remember { mutableIntStateOf(0) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) {
        permissionRefreshTick++
        VoiceAudioTrigger.refreshState(context)
    }

    val deviceList = remember(
        snapshot.config.savedDevices,
        snapshot.connectedDevices,
        includeAllPaired,
        permissionRefreshTick,
    ) {
        VoiceAudioTrigger.listSelectableDevices(context, includeAllPaired = includeAllPaired)
    }

    val config = snapshot.config
    val scopeLabels = mapOf(
        VoiceAudioPolicy.Scope.SELECTED_DEVICES to stringResource(R.string.voice_bt_scope_selected),
        VoiceAudioPolicy.Scope.ANY_BLUETOOTH_AUDIO to stringResource(R.string.voice_bt_scope_any),
    )

    StableModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        adaptiveHeight = true,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .navigationBarsPadding()
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = stringResource(R.string.voice_bt_devices_title),
                    style = MaterialTheme.typography.titleLargeEmphasized,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(R.string.voice_bt_devices_sheet_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            ConnectedButtonGroup(
                options = listOf(
                    VoiceAudioPolicy.Scope.SELECTED_DEVICES,
                    VoiceAudioPolicy.Scope.ANY_BLUETOOTH_AUDIO,
                ),
                selectedOption = config.scope,
                onOptionSelected = { VoiceAudioTrigger.setScope(context, it) },
                labelText = { scopeLabels[it].orEmpty() },
                label = { Text(scopeLabels[it].orEmpty(), style = MaterialTheme.typography.labelLarge) },
                modifier = Modifier.fillMaxWidth(),
            )

            SettingsSwitchItem(
                title = stringResource(R.string.voice_bt_car_mode_title),
                subtitle = stringResource(R.string.voice_bt_car_mode_desc),
                checked = config.includeCarMode,
                onCheckedChange = { VoiceAudioTrigger.setIncludeCarMode(context, it) },
                icon = Symbols.DirectionsCarFilled,
                iconTint = MaterialTheme.colorScheme.primary,
                position = CardPosition.SINGLE,
            )

            if (config.scope == VoiceAudioPolicy.Scope.ANY_BLUETOOTH_AUDIO) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.voice_bt_scope_any_desc),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (deviceList.connected.isNotEmpty()) {
                            val names = deviceList.connected.joinToString(" · ") { it.name }
                            Text(
                                text = stringResource(R.string.voice_bt_status_active, names),
                                style = MaterialTheme.typography.labelLarge,
                                color = StatusPalette.success(),
                            )
                        }
                    }
                }
            } else {
                if (deviceList.connected.isNotEmpty()) {
                    Column {
                        SectionLabel(
                            text = stringResource(R.string.voice_bt_section_connected),
                            topPadding = 8.dp,
                        )
                        DeviceGroup(
                            devices = deviceList.connected,
                            onToggle = { dev ->
                                VoiceAudioTrigger.toggleSavedDevice(
                                    context,
                                    VoiceAudioPolicy.SavedDevice(
                                        id = dev.id,
                                        address = dev.address,
                                        name = dev.name,
                                        isCar = dev.isCar,
                                    ),
                                )
                            },
                        )
                    }
                }

                if (!deviceList.hasBluetoothPermission && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.large,
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.voice_bt_permission_desc),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            FilledTonalButton(
                                onClick = {
                                    permissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
                                },
                                shapes = ButtonDefaults.shapes(),
                                contentPadding = ButtonDefaults.ContentPadding,
                            ) {
                                Text(stringResource(R.string.voice_bt_grant_permission))
                            }
                        }
                    }
                }

                if (deviceList.paired.isNotEmpty()) {
                    Column {
                        SectionLabel(
                            text = stringResource(R.string.voice_bt_section_paired),
                            topPadding = 8.dp,
                        )
                        DeviceGroup(
                            devices = deviceList.paired,
                            onToggle = { dev ->
                                VoiceAudioTrigger.toggleSavedDevice(
                                    context,
                                    VoiceAudioPolicy.SavedDevice(
                                        id = dev.id,
                                        address = dev.address,
                                        name = dev.name,
                                        isCar = dev.isCar,
                                    ),
                                )
                            },
                        )
                    }
                }

                if (deviceList.savedUnavailable.isNotEmpty()) {
                    Column {
                        SectionLabel(
                            text = stringResource(R.string.voice_bt_section_saved),
                            topPadding = 8.dp,
                        )
                        DeviceGroup(
                            devices = deviceList.savedUnavailable,
                            onToggle = { dev ->
                                VoiceAudioTrigger.removeSavedDevice(context, dev.id)
                            },
                        )
                    }
                }

                if (deviceList.connected.isEmpty() &&
                    deviceList.paired.isEmpty() &&
                    deviceList.savedUnavailable.isEmpty() &&
                    deviceList.hasBluetoothPermission
                ) {
                    Text(
                        text = stringResource(R.string.voice_bt_no_devices_found),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (deviceList.hiddenNonAudioPairedCount > 0 || includeAllPaired) {
                    TextButton(
                        onClick = { includeAllPaired = !includeAllPaired },
                        shapes = ButtonDefaults.shapes(),
                        contentPadding = ButtonDefaults.TextButtonContentPadding,
                    ) {
                        Text(
                            stringResource(
                                if (includeAllPaired) R.string.voice_bt_show_audio_only
                                else R.string.voice_bt_show_all_paired
                            )
                        )
                    }
                }
            }

            Spacer(Modifier.height(4.dp))
        }
    }
}

@Composable
private fun DeviceGroup(
    devices: List<VoiceAudioTrigger.SelectableDevice>,
    onToggle: (VoiceAudioTrigger.SelectableDevice) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        devices.forEachIndexed { index, device ->
            val position = when {
                devices.size == 1 -> CardPosition.SINGLE
                index == 0 -> CardPosition.TOP
                index == devices.lastIndex -> CardPosition.BOTTOM
                else -> CardPosition.MIDDLE
            }
            SelectableAudioDeviceRow(
                device = device,
                position = position,
                onToggle = { onToggle(device) },
            )
        }
    }
}

@Composable
private fun SelectableAudioDeviceRow(
    device: VoiceAudioTrigger.SelectableDevice,
    position: CardPosition,
    onToggle: () -> Unit,
) {
    val shape = cardShape(position)
    val icon = when {
        device.isCar -> Symbols.DirectionsCarFilled
        device.isConnected -> Symbols.BluetoothConnectedFilled
        else -> Symbols.HeadphonesFilled
    }
    val tint = when {
        device.isSelected -> MaterialTheme.colorScheme.primary
        device.isConnected -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val statusText = when {
        device.isConnected -> stringResource(R.string.voice_bt_device_connected)
        device.isPaired -> stringResource(R.string.voice_bt_device_paired)
        else -> stringResource(R.string.voice_bt_device_not_paired)
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .toggleable(
                value = device.isSelected,
                role = Role.Checkbox,
                onValueChange = { onToggle() },
            ),
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconTile(icon = icon, tint = tint)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = device.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (device.isConnected) {
                        StatusPalette.success()
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(12.dp))
            Checkbox(
                checked = device.isSelected,
                onCheckedChange = null,
            )
        }
    }
}
