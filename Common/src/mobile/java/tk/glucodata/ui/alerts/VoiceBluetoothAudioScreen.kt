package tk.glucodata.ui.alerts

import android.Manifest
import android.content.Context
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import kotlin.math.ceil
import kotlin.math.roundToInt
import tk.glucodata.R
import tk.glucodata.Talker
import tk.glucodata.VoiceAudioPolicy
import tk.glucodata.VoiceAudioTrigger
import tk.glucodata.ui.SettingsSearchAnchors
import tk.glucodata.ui.StatusPalette
import tk.glucodata.ui.components.AppTopBar
import tk.glucodata.ui.components.CardPosition
import tk.glucodata.ui.components.IconTile
import tk.glucodata.ui.components.MasterSwitchCard
import tk.glucodata.ui.components.SectionLabel
import tk.glucodata.ui.components.SettingsSwitchItem
import tk.glucodata.ui.components.cardShape
import tk.glucodata.ui.symbols.Symbols
import tk.glucodata.ui.util.ConnectedButtonGroup

/**
 * Dedicated settings screen for automatic spoken glucose readings and alarms on Bluetooth and
 * car audio (#653, #1002). Follows the same structure as DataSmoothingSettingsScreen: a
 * [MasterSwitchCard] hero, a slider control card, device selection rows, and grouped switches.
 */
@Composable
fun VoiceBluetoothAudioScreen(navController: NavController) {
    val context = LocalContext.current
    val btState by VoiceAudioTrigger.state.collectAsStateWithLifecycle()
    var includeAllPaired by rememberSaveable { mutableStateOf(false) }
    var permissionRefreshTick by remember { mutableIntStateOf(0) }

    DisposableEffect(context) {
        VoiceAudioTrigger.acquireUiMonitoring(context)
        onDispose { VoiceAudioTrigger.releaseUiMonitoring(context) }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) {
        permissionRefreshTick++
        VoiceAudioTrigger.refreshState(context)
    }

    val config = btState.config
    val isEnabled = config.enabled
    val contentAlpha by animateFloatAsState(
        targetValue = if (isEnabled) 1f else 0.7f,
        label = "voiceBtContentAlpha",
    )

    val deviceList = remember(
        config.savedDevices,
        btState.connectedDevices,
        includeAllPaired,
        permissionRefreshTick,
    ) {
        VoiceAudioTrigger.listSelectableDevices(context, includeAllPaired = includeAllPaired)
    }

    val intervalPresets = VoiceAudioPolicy.INTERVAL_PRESETS_SECONDS
    val currentPresetIndex = intervalPresets.indexOf(config.intervalSeconds).coerceAtLeast(0)
    var sliderIndex by rememberSaveable(config.intervalSeconds) {
        mutableFloatStateOf(currentPresetIndex.toFloat())
    }
    val selectedPresetSeconds = intervalPresets[sliderIndex.roundToInt().coerceIn(0, intervalPresets.lastIndex)]
    val defaultSeparationSeconds = remember { Talker.getSeparationSeconds().coerceAtLeast(1) }
    val defaultIntervalLabel = formatVoiceIntervalLabel(context, defaultSeparationSeconds)
    val selectedChipLabel = if (selectedPresetSeconds <= 0) {
        stringResource(R.string.defaultname)
    } else {
        stringResource(R.string.minutes_short_format, selectedPresetSeconds / 60)
    }
    val selectedDescLabel = formatBtIntervalDescription(context, selectedPresetSeconds, defaultIntervalLabel)

    val scopeLabels = mapOf(
        VoiceAudioPolicy.Scope.SELECTED_DEVICES to stringResource(R.string.voice_bt_scope_selected),
        VoiceAudioPolicy.Scope.ANY_BLUETOOTH_AUDIO to stringResource(R.string.voice_bt_scope_any),
    )

    Scaffold(
        contentWindowInsets = WindowInsets(0.dp),
        topBar = {
            AppTopBar(
                title = stringResource(R.string.voice_bt_audio_title),
                onNavigateBack = { navController.popBackStack() },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 32.dp),
        ) {
            MasterSwitchCard(
                title = stringResource(R.string.voice_bt_audio_title),
                subtitle = formatBtAudioStatus(context, btState),
                checked = isEnabled,
                onCheckedChange = { VoiceAudioTrigger.setEnabled(context, it) },
                icon = Symbols.DirectionsCarFilled,
            )

            if (isEnabled && btState.activeMatch.matched && btState.sessionMuted) {
                Spacer(Modifier.height(12.dp))
                FilledTonalButton(
                    onClick = { VoiceAudioTrigger.setSessionMuted(context, false) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp),
                    shapes = ButtonDefaults.shapes(),
                    contentPadding = ButtonDefaults.ContentPadding,
                ) {
                    Icon(Symbols.VolumeUpFilled, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.voice_bt_resume_session))
                }
            }

            Column(modifier = Modifier.alpha(contentAlpha)) {
                // Speak interval control card (same shape as DataSmoothingSettingsScreen's window card).
                Spacer(Modifier.height(16.dp))
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = if (isEnabled) {
                            MaterialTheme.colorScheme.surfaceContainerLow
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerLowest
                        },
                    ),
                    shape = MaterialTheme.shapes.extraLarge,
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text(
                                    text = stringResource(R.string.voice_bt_interval_title),
                                    style = MaterialTheme.typography.titleMediumEmphasized,
                                )
                                Text(
                                    text = selectedDescLabel,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = if (isEnabled) {
                                    MaterialTheme.colorScheme.surfaceContainerHighest
                                } else {
                                    MaterialTheme.colorScheme.surfaceContainerHigh
                                },
                            ) {
                                Text(
                                    text = selectedChipLabel,
                                    style = MaterialTheme.typography.labelLargeEmphasized,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                )
                            }
                        }

                        Slider(
                            value = sliderIndex,
                            onValueChange = { sliderIndex = it },
                            onValueChangeFinished = {
                                val nextSeconds = intervalPresets[
                                    sliderIndex.roundToInt().coerceIn(0, intervalPresets.lastIndex)
                                ]
                                if (nextSeconds != config.intervalSeconds) {
                                    VoiceAudioTrigger.setIntervalSeconds(context, nextSeconds)
                                }
                            },
                            valueRange = 0f..intervalPresets.lastIndex.toFloat(),
                            steps = (intervalPresets.size - 2).coerceAtLeast(0),
                            enabled = isEnabled,
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = stringResource(R.string.defaultname),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = stringResource(R.string.minutes_short_format, intervalPresets.last() / 60),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                // Trigger scope & audio devices.
                SectionLabel(stringResource(R.string.voice_bt_devices_title))

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
                    enabled = isEnabled,
                )

                Spacer(Modifier.height(12.dp))

                SettingsSwitchItem(
                    title = stringResource(R.string.voice_bt_car_mode_title),
                    subtitle = stringResource(R.string.voice_bt_car_mode_desc),
                    checked = config.includeCarMode,
                    onCheckedChange = { VoiceAudioTrigger.setIncludeCarMode(context, it) },
                    icon = Symbols.DirectionsCarFilled,
                    iconTint = MaterialTheme.colorScheme.primary,
                    position = CardPosition.SINGLE,
                    enabled = isEnabled,
                    searchAnchor = SettingsSearchAnchors.forRow(R.string.voice_bt_car_mode_title),
                )

                if (config.scope == VoiceAudioPolicy.Scope.ANY_BLUETOOTH_AUDIO) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.voice_bt_scope_any_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (deviceList.connected.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        val names = deviceList.connected.joinToString(" · ") { it.name }
                        Text(
                            text = stringResource(R.string.voice_bt_status_active, names),
                            style = MaterialTheme.typography.labelLarge,
                            color = StatusPalette.success(),
                        )
                    }
                } else {
                    if (deviceList.connected.isNotEmpty()) {
                        SectionLabel(
                            text = stringResource(R.string.voice_bt_section_connected),
                            topPadding = 16.dp,
                        )
                        AudioDeviceGroup(
                            devices = deviceList.connected,
                            enabled = isEnabled,
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

                    if (!deviceList.hasBluetoothPermission && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        Spacer(Modifier.height(12.dp))
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(20.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            ),
                            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
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
                                    enabled = isEnabled,
                                    shapes = ButtonDefaults.shapes(),
                                    contentPadding = ButtonDefaults.ContentPadding,
                                ) {
                                    Text(stringResource(R.string.voice_bt_grant_permission))
                                }
                            }
                        }
                    }

                    if (deviceList.paired.isNotEmpty()) {
                        SectionLabel(
                            text = stringResource(R.string.voice_bt_section_paired),
                            topPadding = 16.dp,
                        )
                        AudioDeviceGroup(
                            devices = deviceList.paired,
                            enabled = isEnabled,
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

                    if (deviceList.savedUnavailable.isNotEmpty()) {
                        SectionLabel(
                            text = stringResource(R.string.voice_bt_section_saved),
                            topPadding = 16.dp,
                        )
                        AudioDeviceGroup(
                            devices = deviceList.savedUnavailable,
                            enabled = isEnabled,
                            onToggle = { dev ->
                                VoiceAudioTrigger.removeSavedDevice(context, dev.id)
                            },
                        )
                    }

                    if (deviceList.connected.isEmpty() &&
                        deviceList.paired.isEmpty() &&
                        deviceList.savedUnavailable.isEmpty() &&
                        deviceList.hasBluetoothPermission
                    ) {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = stringResource(R.string.voice_bt_no_devices_found),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    if (deviceList.hiddenNonAudioPairedCount > 0 || includeAllPaired) {
                        Spacer(Modifier.height(8.dp))
                        TextButton(
                            onClick = { includeAllPaired = !includeAllPaired },
                            enabled = isEnabled,
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

                // Behavior options while connected.
                Spacer(Modifier.height(24.dp))
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    SettingsSwitchItem(
                        title = stringResource(R.string.voice_bt_speak_alarms_title),
                        subtitle = stringResource(R.string.voice_bt_speak_alarms_desc),
                        checked = config.speakAlarms,
                        onCheckedChange = { VoiceAudioTrigger.setSpeakAlarms(context, it) },
                        icon = Symbols.NotificationsActiveFilled,
                        iconTint = MaterialTheme.colorScheme.error,
                        position = CardPosition.TOP,
                        enabled = isEnabled,
                        searchAnchor = SettingsSearchAnchors.forRow(R.string.voice_bt_speak_alarms_title),
                    )
                    SettingsSwitchItem(
                        title = stringResource(R.string.voice_bt_announce_connect_title),
                        subtitle = stringResource(R.string.voice_bt_announce_connect_desc),
                        checked = config.announceOnConnect,
                        onCheckedChange = { VoiceAudioTrigger.setAnnounceOnConnect(context, it) },
                        icon = Symbols.PlayArrowFilled,
                        iconTint = MaterialTheme.colorScheme.tertiary,
                        position = CardPosition.MIDDLE,
                        enabled = isEnabled,
                        searchAnchor = SettingsSearchAnchors.forRow(R.string.voice_bt_announce_connect_title),
                    )
                    SettingsSwitchItem(
                        title = stringResource(R.string.voice_bt_media_stream_title),
                        subtitle = stringResource(R.string.voice_bt_media_stream_desc),
                        checked = config.useMediaStream,
                        onCheckedChange = { VoiceAudioTrigger.setUseMediaStream(context, it) },
                        icon = Symbols.MusicNoteFilled,
                        iconTint = MaterialTheme.colorScheme.secondary,
                        position = CardPosition.BOTTOM,
                        enabled = isEnabled,
                        searchAnchor = SettingsSearchAnchors.forRow(R.string.voice_bt_media_stream_title),
                    )
                }
            }
        }
    }
}

@Composable
private fun AudioDeviceGroup(
    devices: List<VoiceAudioTrigger.SelectableDevice>,
    enabled: Boolean,
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
                enabled = enabled,
                onToggle = { onToggle(device) },
            )
        }
    }
}

@Composable
private fun SelectableAudioDeviceRow(
    device: VoiceAudioTrigger.SelectableDevice,
    position: CardPosition,
    enabled: Boolean,
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
                enabled = enabled,
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
                enabled = enabled,
            )
        }
    }
}

internal fun formatVoiceIntervalLabel(context: Context, seconds: Int): String {
    if (seconds <= 60) return context.getString(R.string.voice_every_reading)
    val minutes = ceil(seconds / 60.0).toInt()
    return context.getString(R.string.voice_every_minutes, minutes)
}

internal fun formatBtIntervalDescription(context: Context, seconds: Int, defaultLabel: String): String {
    if (seconds <= 0) return context.getString(R.string.voice_bt_interval_same, defaultLabel)
    return formatVoiceIntervalLabel(context, seconds)
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
