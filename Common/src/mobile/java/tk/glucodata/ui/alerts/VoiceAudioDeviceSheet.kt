@file:OptIn(ExperimentalMaterial3Api::class)

package tk.glucodata.ui.alerts

import android.Manifest
import android.content.Context
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.math.ceil
import tk.glucodata.R
import tk.glucodata.VoiceAudioPolicy
import tk.glucodata.VoiceAudioTrigger
import tk.glucodata.ui.StatusPalette
import tk.glucodata.ui.components.CardPosition
import tk.glucodata.ui.components.CompactSheetDragHandle
import tk.glucodata.ui.components.IconTile
import tk.glucodata.ui.components.SectionLabel
import tk.glucodata.ui.components.SettingsSwitchItem
import tk.glucodata.ui.components.StableModalBottomSheet
import tk.glucodata.ui.components.cardShape
import tk.glucodata.ui.symbols.Symbols
import tk.glucodata.ui.util.ConnectedButtonGroup

@Composable
internal fun VoiceAudioDeviceSheet(
    snapshot: VoiceAudioTrigger.Snapshot,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var includeAllPaired by rememberSaveable { mutableStateOf(false) }
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
        dragHandle = { CompactSheetDragHandle() },
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
                    style = MaterialTheme.typography.titleLarge,
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
                Text(
                    text = stringResource(R.string.voice_bt_scope_any_desc),
                    style = MaterialTheme.typography.bodySmall,
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
            } else {
                if (!deviceList.hasBluetoothPermission && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = cardShape(CardPosition.SINGLE, radius = 16.dp),
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

                if (deviceList.connected.isNotEmpty()) {
                    Column {
                        SectionLabel(
                            text = stringResource(R.string.voice_bt_section_connected),
                            topPadding = 4.dp,
                        )
                        AudioDeviceGroup(
                            devices = deviceList.connected,
                            onToggle = { dev ->
                                VoiceAudioTrigger.toggleSavedDevice(
                                    context,
                                    VoiceAudioPolicy.SavedDevice(
                                        id = dev.id,
                                        name = dev.name,
                                        address = dev.address,
                                    ),
                                )
                            },
                        )
                    }
                }

                if (deviceList.paired.isNotEmpty()) {
                    Column {
                        SectionLabel(
                            text = stringResource(R.string.voice_bt_section_paired),
                            topPadding = 4.dp,
                        )
                        AudioDeviceGroup(
                            devices = deviceList.paired,
                            onToggle = { dev ->
                                VoiceAudioTrigger.toggleSavedDevice(
                                    context,
                                    VoiceAudioPolicy.SavedDevice(
                                        id = dev.id,
                                        name = dev.name,
                                        address = dev.address,
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
                            topPadding = 4.dp,
                        )
                        AudioDeviceGroup(
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
        }
    }
}

@Composable
private fun AudioDeviceGroup(
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
    val interactionSource = remember { MutableInteractionSource() }
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
                interactionSource = interactionSource,
                indication = ripple(bounded = true),
                role = Role.Checkbox,
                onValueChange = { onToggle() },
            ),
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 72.dp)
                .padding(horizontal = 16.dp, vertical = 16.dp),
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

internal fun formatVoiceIntervalLabel(context: Context, seconds: Int): String {
    if (seconds <= VoiceAudioPolicy.INTERVAL_EVERY_READING) return context.getString(R.string.voice_every_reading)
    val minutes = ceil(seconds / 60.0).toInt().coerceAtLeast(1)
    return context.getString(R.string.voice_every_minutes, minutes)
}

internal fun formatBtIntervalValue(context: Context, seconds: Int, defaultLabel: String): String {
    if (seconds <= 0) return context.getString(R.string.voice_bt_interval_same, defaultLabel)
    return formatVoiceIntervalLabel(context, seconds)
}

internal fun formatBtDevicesSummary(context: Context, state: VoiceAudioTrigger.Snapshot): String {
    val config = state.config
    if (config.scope == VoiceAudioPolicy.Scope.ANY_BLUETOOTH_AUDIO) {
        return if (config.includeCarMode) {
            context.getString(R.string.voice_bt_devices_any_and_car)
        } else {
            context.getString(R.string.voice_bt_scope_any)
        }
    }
    val names = buildList {
        config.savedDevices.forEach { add(it.name) }
        if (config.includeCarMode) add(context.getString(R.string.voice_bt_car_mode_label))
    }
    if (names.isEmpty()) {
        val connectedFirst = state.connectedDevices.firstOrNull()?.name
        return if (connectedFirst != null) {
            context.getString(R.string.voice_bt_devices_connected_hint, connectedFirst)
        } else {
            context.getString(R.string.voice_bt_devices_none_summary)
        }
    }
    return when (names.size) {
        1 -> names[0]
        2 -> "${names[0]} · ${names[1]}"
        else -> context.getString(R.string.voice_bt_devices_more_format, names[0], names.size - 1)
    }
}

internal fun formatBtAudioStatus(context: Context, state: VoiceAudioTrigger.Snapshot): String {
    val config = state.config
    if (!config.enabled) return context.getString(R.string.voice_bt_audio_desc)
    val match = state.activeMatch
    if (match.matched) {
        val label = match.displayLabel
            ?: if (match.viaCarMode) context.getString(R.string.voice_bt_car_mode_label)
            else context.getString(R.string.voice_bt_audio_fallback_label)
        return if (state.sessionMuted) {
            context.getString(R.string.voice_bt_status_paused, label)
        } else {
            context.getString(R.string.voice_bt_status_active, label)
        }
    }
    return when {
        config.scope == VoiceAudioPolicy.Scope.ANY_BLUETOOTH_AUDIO ->
            context.getString(R.string.voice_bt_status_waiting_any)
        config.savedDevices.size == 1 ->
            context.getString(R.string.voice_bt_status_waiting_device, config.savedDevices.first().name)
        config.savedDevices.size > 1 ->
            context.getString(R.string.voice_bt_status_waiting_count, config.savedDevices.size)
        config.includeCarMode ->
            context.getString(R.string.voice_bt_status_waiting_car_mode)
        else ->
            context.getString(R.string.voice_bt_status_no_devices)
    }
}
