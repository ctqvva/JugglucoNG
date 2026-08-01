@file:OptIn(ExperimentalMaterial3Api::class)

package tk.glucodata.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import tk.glucodata.ui.components.StyledSwitch
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Density
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import tk.glucodata.ui.util.ConnectedButtonGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.ui.draw.alpha

import androidx.compose.material.icons.filled.AccessTime
import tk.glucodata.CurrentDisplaySource
import tk.glucodata.Notify
import tk.glucodata.R
import tk.glucodata.UiRefreshBus
import tk.glucodata.drivers.ManagedSensorCalibrationSource
import tk.glucodata.drivers.anytime.AnytimeCalibrationPolicy
import tk.glucodata.drivers.sibionics.SibionicsSensitivity
import androidx.compose.ui.res.stringResource
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material3.Icon
import kotlinx.coroutines.delay
import tk.glucodata.ui.components.CardPosition
import tk.glucodata.ui.components.CompactSheetDragHandle
import tk.glucodata.ui.components.SettingsItem
import tk.glucodata.ui.components.SettingsSwitchItem
import kotlin.math.abs
import kotlin.math.roundToInt
import java.util.Locale

import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

@Composable
fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall, // Smaller label
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium, // Larger value for scannability
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

private fun formatSensorReadingAge(nowMillis: Long, readingMillis: Long): String {
    val ageSeconds = ((nowMillis - readingMillis).coerceAtLeast(0L) / 1000L)
    return if (ageSeconds < 60L) {
        "${ageSeconds}s"
    } else {
        "${(ageSeconds / 60L).coerceAtLeast(1L)}m"
    }
}

private fun nextSensorReadingAgeDelay(nowMillis: Long, readingMillis: Long): Long {
    val ageSeconds = ((nowMillis - readingMillis).coerceAtLeast(0L) / 1000L)
    return if (ageSeconds < 60L) {
        1_000L
    } else {
        ((60L - (ageSeconds % 60L)) * 1_000L).coerceAtLeast(1_000L)
    }
}

private fun formatSibionicsSensitivity(value: Float): String =
    String.format(Locale.getDefault(), "%.2f", value)

@Composable
private fun SensorCurrentValueChip(
    snapshot: CurrentDisplaySource.Snapshot,
    accentColor: Color,
    modifier: Modifier = Modifier
) {
    var nowMillis by remember(snapshot.timeMillis) { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(snapshot.timeMillis) {
        while (true) {
            nowMillis = System.currentTimeMillis()
            delay(nextSensorReadingAgeDelay(nowMillis, snapshot.timeMillis))
        }
    }
    val ageText = remember(nowMillis, snapshot.timeMillis) {
        formatSensorReadingAge(nowMillis, snapshot.timeMillis)
    }

    Surface(
        modifier = modifier.widthIn(max = 220.dp),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 1.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = snapshot.primaryStr,
                style = MaterialTheme.typography.titleSmall.copy(fontFeatureSettings = "tnum"),
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                softWrap = false,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
            snapshot.secondaryStr?.let { secondary ->
                Text(
                    text = " · $secondary",
                    style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    softWrap = false,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.AccessTime,
                    contentDescription = null,
                    tint = accentColor.copy(alpha = 0.82f),
                    modifier = Modifier.size(13.dp)
                )
                Spacer(modifier = Modifier.width(3.dp))
                Text(
                    text = ageText,
                    style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    softWrap = false
                )
            }
//            Spacer(modifier = Modifier.width(6.dp))
//            Icon(
//                imageVector = getTrendIcon(snapshot.rate),
//                contentDescription = null,
//                tint = accentColor,
//                modifier = Modifier.size(16.dp)
//            )
        }
    }
}

@Composable
fun SensorCard(
    sensor: tk.glucodata.ui.viewmodel.SensorInfo,
    viewModel: tk.glucodata.ui.viewmodel.SensorViewModel,
    sensorCount: Int = 1,
    onNavigateToMqAccount: () -> Unit = {},
) {
    val context = LocalContext.current
    var showTerminateDialog by remember { mutableStateOf(false) }
    var unbindAiDexChecked by remember(sensor.serial, sensor.isVendorPaired) {
        mutableStateOf(sensor.isVendorPaired)
    }
    var showForgetDialog by remember { mutableStateOf(false) }
    var showResetDialog by remember { mutableStateOf(false) }
    // Edit 79: showClearDialog removed — restart algorithm now in Sibionics Calibration bottom sheet
    var showClearAllDialog by remember { mutableStateOf(false) }
    var showUnifiedResetDialog by remember { mutableStateOf(false) }
    var keepAutoCalChecked by remember { mutableStateOf(false) }
    var showReconnectDialog by remember { mutableStateOf(false) }
    var showWipeDialog by remember { mutableStateOf(false) }
    var wipeDataChecked by remember { mutableStateOf(false) }
    var keepDataChecked by remember { mutableStateOf(false) }

    // Sibionics Calibration Bottom Sheet
    var showSibionicsCalSheet by remember { mutableStateOf(false) }

    // AiDex Maintenance Dialogs
    var showAiDexClearDialog by remember { mutableStateOf(false) }
    var showSensorCalibrateDialog by remember { mutableStateOf(false) }
    var showAnytimeClearCalibrationDialog by remember { mutableStateOf(false) }
    var showAiDexUnpairDialog by remember { mutableStateOf(false) }
    var showMqRestoreSheet by remember { mutableStateOf(false) }
    var showMqCalibrationSheet by remember { mutableStateOf(false) }
    var calibrationInputText by remember { mutableStateOf("") }
    var mqCalibrationInputText by remember { mutableStateOf("") }
    var mqQrInput by remember(context, sensor.serial) {
        mutableStateOf(tk.glucodata.drivers.mq.MQRegistry.loadQrContent(context, sensor.serial).orEmpty())
    }
    var aiDexBiasChecked by remember(sensor.serial, sensor.resetCompensationActive) { mutableStateOf(sensor.resetCompensationActive) }
    // Edit 78: resetBiasChecked removed — bias toggle now lives in the bottom sheet as an independent switch

    val scope = rememberCoroutineScope() // Fix: Add missing scope
    // Edit 74: Removed LocalContext.current that was added in Edit 73 for Toasts (rejected by user).
    // Status feedback now goes through getDetailedBleStatus() via vendorActionStatus field.

    // Edit 68b: AiDex disconnect button now uses terminateSensor (destructive) instead of
    // disconnectSensor (soft). The old soft-disconnect left zombie "is finished" entries —
    // bond/keys preserved, prefs not cleaned, sensor reappeared. terminateSensor calls
    // forgetVendor() + removeAiDexFromPrefs() + finishSensor() + sensorEnded() = full cleanup.
    if (showTerminateDialog) {
        if (sensor.isAidex) {
            AlertDialog(
                onDismissRequest = {
                    showTerminateDialog = false
                    unbindAiDexChecked = sensor.isVendorPaired
                },
                title = { Text(stringResource(R.string.disconnect_sensor_title)) },
                text = {
                    Column {
                        Text(
                            text = stringResource(R.string.disconnect_sensor_aidex_desc),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (sensor.isVendorPaired) {
                            Spacer(modifier = Modifier.height(16.dp))
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(MaterialTheme.shapes.large)
                                    .toggleable(
                                        value = unbindAiDexChecked,
                                        role = Role.Switch,
                                        onValueChange = { unbindAiDexChecked = it }
                                    )
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = stringResource(R.string.unbind_sensor),
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Text(
                                        text = stringResource(R.string.unbind_sensor_desc),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Spacer(modifier = Modifier.width(16.dp))
                                StyledSwitch(
                                    checked = unbindAiDexChecked,
                                    onCheckedChange = null
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        viewModel.disconnectAiDexSensor(sensor.serial, unbindAiDexChecked && sensor.isVendorPaired)
                        showTerminateDialog = false
                        unbindAiDexChecked = sensor.isVendorPaired
                    }) { Text(stringResource(R.string.disconnect)) }
                },
                dismissButton = {
                    TextButton(onClick = {
                        showTerminateDialog = false
                        unbindAiDexChecked = sensor.isVendorPaired
                    }) {
                        Text(stringResource(R.string.cancel))
                    }
                }
            )
        } else {
            // Legacy sensors: destructive terminate
            AlertDialog(
                onDismissRequest = {
                    showTerminateDialog = false
                    keepDataChecked = false
                },
                title = { Text(stringResource(R.string.disconnect_sensor_title)) },
                text = {
                    Column {
                        Text(stringResource(R.string.disconnect_sensor_desc))
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = keepDataChecked,
                                onCheckedChange = { keepDataChecked = it }
                            )
                            Text(stringResource(R.string.keep_data))
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        viewModel.terminateSensor(sensor.serial, !keepDataChecked)
                        showTerminateDialog = false
                        keepDataChecked = false
                    }) { Text(stringResource(R.string.disconnect)) }
                },
                dismissButton = {
                    TextButton(onClick = {
                        showTerminateDialog = false
                        keepDataChecked = false
                    }) { Text(stringResource(R.string.cancel)) }
                }
            )
        }
    }

    if (showReconnectDialog) {
        AlertDialog(
            onDismissRequest = {
                showReconnectDialog = false
                wipeDataChecked = false
            },
            title = { Text(stringResource(R.string.reconnect_sensor_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.reconnect_sensor_desc))
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = wipeDataChecked,
                            onCheckedChange = { wipeDataChecked = it }
                        )
                        Text(stringResource(R.string.wipe_data))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.reconnectSensor(sensor.serial, wipeDataChecked)
                    showReconnectDialog = false
                    wipeDataChecked = false
                }) { Text(stringResource(R.string.reconnect)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    showReconnectDialog = false
                    wipeDataChecked = false
                }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    // Edit 62d: Forget/Disconnect dialog — for AiDex, this is the destructive "Disconnect" path
    // that wipes vendor keys, disconnects, and removes from list.
    if (showForgetDialog) {
        if (sensor.isAidex) {
            AlertDialog(
                onDismissRequest = { showForgetDialog = false },
                title = { Text(stringResource(R.string.disconnect_sensor_title)) },
                text = { Text(stringResource(R.string.remove_sensor_desc)) },
                confirmButton = {
                    TextButton(onClick = {
                        viewModel.forgetSensor(sensor.serial)
                        showForgetDialog = false
                    }) { Text(stringResource(R.string.disconnect)) }
                },
                dismissButton = {
                    TextButton(onClick = { showForgetDialog = false }) { Text(stringResource(R.string.cancel)) }
                }
            )
        } else {
            AlertDialog(
                onDismissRequest = { showForgetDialog = false },
                title = { Text(stringResource(R.string.forget_sensor_title)) },
                text = { Text(stringResource(R.string.forget_sensor_desc)) },
                confirmButton = {
                    TextButton(onClick = {
                        viewModel.forgetSensor(sensor.serial)
                        showForgetDialog = false
                    }) { Text(stringResource(R.string.forget)) }
                },
                dismissButton = {
                    TextButton(onClick = { showForgetDialog = false }) { Text(stringResource(R.string.cancel)) }
                }
            )
        }
    }

    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = { Text(stringResource(R.string.reset_sensor_title)) },
            text = {
                Text(
                    stringResource(
                        if (sensor.isSibionics2) R.string.reset_sensor_desc else R.string.unified_reset_desc
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.resetSensor(sensor.serial)
                    showResetDialog = false
                }) { Text(stringResource(R.string.reset_sensor)) }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    if (showAnytimeClearCalibrationDialog) {
        AlertDialog(
            onDismissRequest = { showAnytimeClearCalibrationDialog = false },
            title = { Text(stringResource(R.string.clear_calibrations_title)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.clearManagedSensorCalibration(sensor.serial)
                    showAnytimeClearCalibrationDialog = false
                }) { Text(stringResource(R.string.clear)) }
            },
            dismissButton = {
                TextButton(onClick = { showAnytimeClearCalibrationDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    // Edit 79: showClearDialog AlertDialog removed — restart algorithm now lives
    // inside the Sibionics Calibration bottom sheet as a destructive action card.

    if (showUnifiedResetDialog) {
        AlertDialog(
            onDismissRequest = { 
                showUnifiedResetDialog = false
                keepAutoCalChecked = false 
            },
            title = { Text(stringResource(R.string.reset_sensor_title)) },
            text = { 
                Column {
                    Text(stringResource(R.string.unified_reset_desc))
                    if (sensor.dataptr != 0L) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.clickable { keepAutoCalChecked = !keepAutoCalChecked }
                        ) {
                            Checkbox(
                                checked = keepAutoCalChecked,
                                onCheckedChange = { keepAutoCalChecked = it }
                            )
                            Text(stringResource(R.string.keep_auto_calibration))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (sensor.dataptr == 0L || keepAutoCalChecked) {
                        viewModel.resetSensor(sensor.serial)  // Hardware reset only
                    } else {
                        viewModel.clearAll(sensor.serial)     // Full reset
                    }
                    showUnifiedResetDialog = false
                    keepAutoCalChecked = false
                }) { Text(stringResource(R.string.reset_sensor)) }
            },
            dismissButton = {
                TextButton(onClick = { 
                    showUnifiedResetDialog = false 
                    keepAutoCalChecked = false
                }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    if (showWipeDialog) {
        AlertDialog(
            onDismissRequest = { showWipeDialog = false },
            title = { Text(stringResource(R.string.wipe_sensor_data_title)) },
            text = { Text(stringResource(R.string.wipe_sensor_data_desc)) },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.wipeSensorData(sensor.serial)
                        showWipeDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text(stringResource(R.string.wipe_data))
                }
            },
            dismissButton = {
                TextButton(onClick = { showWipeDialog = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    // Edit 78: AiDex Reset & Bias Correction bottom sheet — replaces old AlertDialog.
    // Matches the destructive-action-sheet pattern from DashboardClearOptionsBottomSheet.
    if (showAiDexClearDialog) {
        @OptIn(ExperimentalMaterial3Api::class)
        ModalBottomSheet(
            onDismissRequest = { showAiDexClearDialog = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            dragHandle = { BottomSheetDefaults.DragHandle() }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 32.dp)
            ) {
                Text(
                    stringResource(R.string.reset_correction_title),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    stringResource(R.string.reset_correction_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(24.dp))

                // --- Bias Correction toggle ---
                Surface(
                    onClick = {
                        aiDexBiasChecked = !aiDexBiasChecked
                        if (aiDexBiasChecked) {
                            viewModel.enableAiDexBiasCompensation(sensor.serial)
                        } else {
                            viewModel.disableAiDexBiasCompensation(sensor.serial)
                        }
                    },
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Tune,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.tertiary
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.bias_correction),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                if (sensor.resetCompensationActive && sensor.resetCompensationStatus.isNotEmpty())
                                    sensor.resetCompensationStatus
                                else if (aiDexBiasChecked && sensor.resetCompensationStatus.isNotEmpty())
                                    sensor.resetCompensationStatus
                                else
                                    stringResource(R.string.bias_correction_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = if (aiDexBiasChecked)
                                    MaterialTheme.colorScheme.tertiary
                                else
                                    MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        StyledSwitch(
                            checked = aiDexBiasChecked,
                            onCheckedChange = {
                                aiDexBiasChecked = it
                                if (it) {
                                    viewModel.enableAiDexBiasCompensation(sensor.serial)
                                } else {
                                    viewModel.disableAiDexBiasCompensation(sensor.serial)
                                }
                            }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // --- Hardware Reset action ---
                Surface(
                    onClick = {
                        viewModel.resetAiDexSensor(sensor.serial, enableBiasCompensation = aiDexBiasChecked)
                        showAiDexClearDialog = false
                    },
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.RestartAlt,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.hardware_reset),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.error
                            )
                            Text(
                                stringResource(R.string.hardware_reset_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                TextButton(
                    onClick = { showAiDexClearDialog = false },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(stringResource(R.string.cancel)) }
            }
        }
    }

    // Independent, immediately applied sensor-algorithm features.
    if (showSibionicsCalSheet && sensor.isSibionics && sensor.viewMode != 1) {
        @OptIn(ExperimentalMaterial3Api::class)
        ModalBottomSheet(
            onDismissRequest = { showSibionicsCalSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            dragHandle = { CompactSheetDragHandle() }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 32.dp)
            ) {
                Text(
                    stringResource(R.string.auto_calibration_mode),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(16.dp))

                var algorithmFeatures by remember(sensor.customCalIndex) {
                    mutableIntStateOf(sensor.customCalIndex.coerceIn(0, 7))
                }

                fun setCalibration(enabled: Boolean) {
                    val updated = if (enabled) algorithmFeatures or 1 else algorithmFeatures and 1.inv()
                    if (algorithmFeatures == updated) return
                    algorithmFeatures = updated
                    viewModel.setSibionicsAlgorithmMode(sensor.serial, updated)
                }

                fun setAlgorithm(modelBase: Int) {
                    val updated = modelBase or (algorithmFeatures and 1)
                    if (algorithmFeatures == updated) return
                    algorithmFeatures = updated
                    viewModel.setSibionicsAlgorithmMode(sensor.serial, updated)
                }



                val algorithmOptions = listOf(
                    Triple(0, R.string.sibionics_stock_algorithm_desc, R.string.sibionics_stock_model_detail),
                    Triple(6, R.string.sibionics_responsive_algorithm, R.string.sibionics_responsive_algorithm_desc),
                    Triple(4, R.string.sibionics_balanced_algorithm, R.string.sibionics_balanced_algorithm_desc),
                    Triple(2, R.string.sibionics_state_algorithm, R.string.sibionics_state_algorithm_desc),
                )
                Column(
                    modifier = Modifier.selectableGroup(),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    algorithmOptions.forEachIndexed { index, (modelBase, titleRes, subtitleRes) ->
                        val selected = algorithmFeatures and 6 == modelBase
                        val shape = when (index) {
                            0 -> RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp, bottomStart = 6.dp, bottomEnd = 6.dp)
                            algorithmOptions.lastIndex -> RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp, bottomStart = 12.dp, bottomEnd = 12.dp)
                            else -> RoundedCornerShape(6.dp)
                        }
                        Surface(
                            shape = shape,
                            color = if (selected) MaterialTheme.colorScheme.secondaryContainer
                                else MaterialTheme.colorScheme.surfaceContainer,
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .selectable(
                                        selected = selected,
                                        onClick = { setAlgorithm(modelBase) },
                                        role = Role.RadioButton,
                                    )
                                    .padding(horizontal = 16.dp, vertical = 16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        stringResource(titleRes),
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Medium,
                                    )
                                    Text(
                                        stringResource(subtitleRes),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Spacer(modifier = Modifier.width(16.dp))
                                RadioButton(selected = selected, onClick = null)
                            }
                        }
                    }
                }

                if (sensor.algorithmSensitivity.isFinite() &&
                    sensor.automaticAlgorithmSensitivity.isFinite()
                ) {
                    var sensitivityDraft by remember(
                        sensor.serial,
                        sensor.algorithmSensitivity,
                    ) {
                        mutableFloatStateOf(
                            sensor.algorithmSensitivity.coerceIn(
                                SibionicsSensitivity.MIN_SENSITIVITY,
                                SibionicsSensitivity.MAX_SENSITIVITY,
                            ),
                        )
                    }
                    var sensitivityOverridden by remember(
                        sensor.serial,
                        sensor.hasAlgorithmSensitivityOverride,
                    ) {
                        mutableStateOf(sensor.hasAlgorithmSensitivityOverride)
                    }

                    Spacer(modifier = Modifier.height(20.dp))
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    stringResource(R.string.sibionics_sensitivity),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Medium,
                                )
                                Text(
                                    stringResource(
                                        R.string.sibionics_sensor_sensitivity,
                                        formatSibionicsSensitivity(sensor.automaticAlgorithmSensitivity),
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text(
                                formatSibionicsSensitivity(sensitivityDraft),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        Slider(
                            value = sensitivityDraft,
                            onValueChange = {
                                sensitivityDraft = (it * 100f).roundToInt() / 100f
                            },
                            onValueChangeFinished = {
                                sensitivityOverridden = true
                                viewModel.setSibionicsAlgorithmSensitivity(
                                    sensor.serial,
                                    sensitivityDraft,
                                )
                            },
                            valueRange = SibionicsSensitivity.MIN_SENSITIVITY..
                                SibionicsSensitivity.MAX_SENSITIVITY,
                        )
                        if (sensitivityOverridden) {
                            TextButton(
                                onClick = {
                                    sensitivityDraft = sensor.automaticAlgorithmSensitivity
                                    sensitivityOverridden = false
                                    viewModel.setSibionicsAlgorithmSensitivity(sensor.serial, null)
                                },
                                modifier = Modifier.align(Alignment.End),
                            ) {
                                Text(stringResource(R.string.sibionics_use_sensor_sensitivity))
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                SettingsSwitchItem(
                    title = stringResource(R.string.calibration),
                    subtitle = stringResource(R.string.sibionics_stock_algorithm_detail),
                    subtitleStyle = MaterialTheme.typography.bodySmall,
                    checked = algorithmFeatures and 1 != 0,
                    onCheckedChange = ::setCalibration,
                    icon = Icons.Default.Science,
                    iconTint = MaterialTheme.colorScheme.primary,
                    position = CardPosition.SINGLE,
//                    shape = RoundedCornerShape(16.dp),

                )
//
//                Spacer(modifier = Modifier.height(24.dp))
//                Text(
//                    stringResource(R.string.sibionics_glucose_model),
//                    style = MaterialTheme.typography.titleMedium,
//                    color = MaterialTheme.colorScheme.primary,
//                )
                Spacer(modifier = Modifier.height(18.dp))
                Button(
                    onClick = { showSibionicsCalSheet = false },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                ) { Text(stringResource(R.string.close)) }
            }
        }
    }

    if (showSensorCalibrateDialog) {
        AlertDialog(
            onDismissRequest = {
                showSensorCalibrateDialog = false
                calibrationInputText = ""
            },
            title = { Text(stringResource(R.string.calibrate_sensor_title)) },
            text = {
                val isMmol = tk.glucodata.ui.util.GlucoseFormatter.isMmolApp()
                val unitLabel = if (isMmol) "mmol/L" else "mg/dL"
                Column {
                    Text(stringResource(R.string.calibrate_sensor_desc, unitLabel))
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.calibrate_sensor_timing_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    androidx.compose.material3.OutlinedTextField(
                        value = calibrationInputText,
                        onValueChange = { newVal ->
                            calibrationInputText = if (isMmol) {
                                // Allow digits and one decimal point
                                newVal.filter { c -> c.isDigit() || c == '.' }
                                    .let { s ->
                                        val dotIndex = s.indexOf('.')
                                        if (dotIndex >= 0) s.substring(0, dotIndex + 1) + s.substring(dotIndex + 1).replace(".", "")
                                        else s
                                    }
                            } else {
                                newVal.filter { c -> c.isDigit() }
                            }
                        },
                        label = { Text(stringResource(R.string.glucose_with_unit, unitLabel)) },
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = if (isMmol)
                                androidx.compose.ui.text.input.KeyboardType.Decimal
                            else
                                androidx.compose.ui.text.input.KeyboardType.Number
                        ),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                val isMmol = tk.glucodata.ui.util.GlucoseFormatter.isMmolApp()
                val inputValue = calibrationInputText.toFloatOrNull()
                val glucoseMgDl = inputValue?.let {
                    (if (isMmol) tk.glucodata.ui.util.GlucoseFormatter.mmolToMg(it) else it).roundToInt()
                }
                val isValid = glucoseMgDl != null && glucoseMgDl in 30..500
                TextButton(
                    onClick = {
                        if (glucoseMgDl != null && isValid) {
                            if (sensor.isAidex) {
                                viewModel.calibrateAiDexSensor(sensor.serial, glucoseMgDl)
                            } else {
                                viewModel.calibrateManagedSensor(sensor.serial, glucoseMgDl)
                            }
                            showSensorCalibrateDialog = false
                            calibrationInputText = ""
                        }
                    },
                    enabled = isValid
                ) { Text(stringResource(R.string.calibrate_action)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    showSensorCalibrateDialog = false
                    calibrationInputText = ""
                }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    if (showMqRestoreSheet) {
        @OptIn(ExperimentalMaterial3Api::class)
        ModalBottomSheet(
            onDismissRequest = { showMqRestoreSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            dragHandle = { BottomSheetDefaults.DragHandle() }
        ) {
            val savedAccountState = remember(showMqRestoreSheet) {
                tk.glucodata.drivers.mq.MQRegistry.loadAccountState(context)
            }
            val accountSubtitle = when {
                savedAccountState.hasToken -> stringResource(R.string.mq_account_status_signed_in)
                savedAccountState.hasCredentials -> stringResource(R.string.mq_account_status_saved)
                else -> stringResource(R.string.mq_account_linked_desc)
            }
            val canAttemptVendorRestore = mqQrInput.isNotBlank() || savedAccountState.hasCredentials || savedAccountState.hasToken
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(androidx.compose.foundation.rememberScrollState())
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 32.dp)
            ) {
                Text(
                    text = stringResource(R.string.mq_bootstrap_dialog_title),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.mq_bootstrap_dialog_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(20.dp))
                androidx.compose.material3.OutlinedTextField(
                    value = mqQrInput,
                    onValueChange = { mqQrInput = it.trim().uppercase(java.util.Locale.US) },
                    label = { Text(stringResource(R.string.scan_sensor_qr)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(12.dp))
                SettingsItem(
                    title = stringResource(R.string.mq_account_title),
                    subtitle = accountSubtitle,
                    showArrow = true,
                    icon = Icons.Default.Cloud,
                    iconTint = MaterialTheme.colorScheme.primary,
                    position = CardPosition.SINGLE,
                    onClick = {
                        showMqRestoreSheet = false
                        onNavigateToMqAccount()
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = {
                        viewModel.fetchMqBootstrap(
                            sensor.serial,
                            mqQrInput,
                        )
                    },
                    enabled = canAttemptVendorRestore,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.mq_fetch_calibration_action))
                }
                Spacer(modifier = Modifier.height(8.dp))
                TextButton(
                    onClick = { showMqRestoreSheet = false },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.cancel))
                }
            }
        }
    }

    if (showMqCalibrationSheet) {
        @OptIn(ExperimentalMaterial3Api::class)
        ModalBottomSheet(
            onDismissRequest = {
                showMqCalibrationSheet = false
                mqCalibrationInputText = ""
            },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            dragHandle = { BottomSheetDefaults.DragHandle() }
        ) {
            val isMmol = tk.glucodata.ui.util.GlucoseFormatter.isMmolApp()
            val unitLabel = if (isMmol) "mmol/L" else "mg/dL"
            val inputValue = mqCalibrationInputText.toFloatOrNull()
            val glucoseMgDl = inputValue?.let {
                (if (isMmol) tk.glucodata.ui.util.GlucoseFormatter.mmolToMg(it) else it).toInt()
            }
            val canCalibrate = sensor.isVendorConnected && glucoseMgDl != null && glucoseMgDl in 30..500
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(androidx.compose.foundation.rememberScrollState())
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 32.dp)
            ) {
                Text(
                    text = stringResource(R.string.mq_manual_calibration_title),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.mq_manual_calibration_desc, unitLabel),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(20.dp))
                androidx.compose.material3.OutlinedTextField(
                    value = mqCalibrationInputText,
                    onValueChange = { newVal ->
                        mqCalibrationInputText = if (isMmol) {
                            newVal.filter { c -> c.isDigit() || c == '.' }
                                .let { s ->
                                    val dotIndex = s.indexOf('.')
                                    if (dotIndex >= 0) {
                                        s.substring(0, dotIndex + 1) + s.substring(dotIndex + 1).replace(".", "")
                                    } else {
                                        s
                                    }
                                }
                        } else {
                            newVal.filter { c -> c.isDigit() }
                        }
                    },
                    label = { Text(stringResource(R.string.glucose_with_unit, unitLabel)) },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = if (isMmol) {
                            androidx.compose.ui.text.input.KeyboardType.Decimal
                        } else {
                            androidx.compose.ui.text.input.KeyboardType.Number
                        }
                    ),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(20.dp))
                Button(
                    onClick = {
                        if (glucoseMgDl != null && canCalibrate) {
                            viewModel.calibrateManagedSensor(sensor.serial, glucoseMgDl)
                            showMqCalibrationSheet = false
                            mqCalibrationInputText = ""
                        }
                    },
                    enabled = canCalibrate,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.calibrate_action))
                }
                Spacer(modifier = Modifier.height(8.dp))
                TextButton(
                    onClick = {
                        showMqCalibrationSheet = false
                        mqCalibrationInputText = ""
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.cancel))
                }
            }
        }
    }

    if (showAiDexUnpairDialog) {
        AlertDialog(
            onDismissRequest = { showAiDexUnpairDialog = false },
            title = { Text(stringResource(R.string.unpair_sensor_title)) },
            text = { Text(stringResource(R.string.unpair_sensor_desc)) },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.unpairAiDexSensor(sensor.serial)
                        showAiDexUnpairDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text(stringResource(R.string.unpair)) }
            },
            dismissButton = {
                TextButton(onClick = { showAiDexUnpairDialog = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    val isStreaming = sensor.streaming
    val refreshRevision by UiRefreshBus.revision.collectAsState(initial = 0L)
    val currentSnapshot = remember(refreshRevision, sensor.serial, sensor.viewMode) {
        CurrentDisplaySource.resolveCurrent(
            maxAgeMillis = Notify.glucosetimeout,
            preferredSensorId = sensor.serial
        )?.takeIf { snapshot ->
            abs(System.currentTimeMillis() - snapshot.timeMillis) <= Notify.glucosetimeout &&
                snapshot.primaryStr.isNotBlank()
        }
    }
    // Visual Feedback: Darken card when disconnected/paused
    val containerColor = if (isStreaming) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    val contentAlpha = if (isStreaming) 1f else 0.9f


    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 0.dp, vertical = 4.dp), 
        colors = CardDefaults.cardColors(containerColor = containerColor),
        shape = RoundedCornerShape(28.dp) 
    ) {
        // --- Dynamic Background Logic ---
        val now = System.currentTimeMillis()
        val start = sensor.startMs
        val end = when {
            sensor.isAidex && sensor.officialEndMs > 0 -> sensor.officialEndMs
            !sensor.isAidex && sensor.expectedEndMs > 0 -> sensor.expectedEndMs
            sensor.officialEndMs > 0 -> sensor.officialEndMs
            sensor.isAidex -> start + (15L * 24 * 3600 * 1000)
            else -> start + (14L * 24 * 3600 * 1000)
        }
        
        val totalDuration = (end - start).coerceAtLeast(1) // Avoid div/0
        val usedDuration = (now - start).coerceAtLeast(0)
        val progress = (usedDuration.toFloat() / totalDuration).coerceIn(0f, 1f)
        
        // Color Shift: Safe -> Warning (80%) -> Critical (95%)
        val fillColor = when {
            progress > 0.95f -> MaterialTheme.colorScheme.error
            progress > 0.80f -> MaterialTheme.colorScheme.tertiary 
            else -> MaterialTheme.colorScheme.primary
        }
        val fillAlpha = 0.12f // Light tint

        Box(modifier = Modifier.fillMaxSize()) {
            // 1. Dynamic Fill Layer
            // FIX: Only show fill if start date is valid (> Jan 1 2020)
            // Prevents "100% Red Fill" bug when startMs is 0 or invalid (1970).
            if (sensor.startMs > 1577836800000L) {
                 Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(progress)
                        .background(fillColor.copy(alpha = fillAlpha))
                )
            }

            // 2. Content Layer with Color Indicator
            Row(modifier = Modifier.fillMaxWidth().padding(0.dp).alpha(contentAlpha)) {
                // Color indicator bar - shows sensor's assigned color
                Box(
                    modifier = Modifier
                        .width(4.dp)
                        .fillMaxHeight()
                        .background(
                            sensor.color.copy(alpha = if (sensor.isActive) 1f else 0.4f),
                            RoundedCornerShape(topStart = 28.dp, bottomStart = 28.dp)
                        )
                )

                Column(modifier = Modifier.padding(16.dp).weight(1f)) {
                    val statusText = if (isStreaming) stringResource(R.string.enabled_status) else stringResource(R.string.disabled_status)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            val serialTextStyle = when {
                                else -> MaterialTheme.typography.titleLarge
                            }
                            val enabledTextStyle = when {
                                else -> MaterialTheme.typography.titleMedium
                            }
                            // Title with optional "Active" badge
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = sensor.displayName.ifBlank { sensor.serial },
                                    style = serialTextStyle,
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                    modifier = Modifier
                                )
                                // Toggle Main Sensor Badge
                                Spacer(modifier = Modifier.width(8.dp))
                                val isMain = sensor.isActive
                                val isSelectedForDisplay = sensor.isSelectedForDisplay

                                val badgeColor = if (isSelectedForDisplay) sensor.color else sensor.color.copy(alpha = 0.6f)
                                val badgeBg = when {
                                    isMain -> sensor.color.copy(alpha = 0.16f)
                                    isSelectedForDisplay -> sensor.color.copy(alpha = 0.10f)
                                    else -> Color.Transparent
                                }
                                val badgeBorder = if (isSelectedForDisplay) {
                                    null
                                } else {
                                    androidx.compose.foundation.BorderStroke(1.dp, sensor.color.copy(alpha = 0.3f))
                                }

                                if (sensorCount > 1) {
                                    val selectedDescription = stringResource(R.string.sensor_display_selected)
                                    val selectDescription = stringResource(R.string.sensor_display_select)
                                    // Multi-sensor: interactive badge with Surface background
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Box(
                                        modifier = Modifier
                                            .clip(androidx.compose.foundation.shape.CircleShape)
                                            .clickable { viewModel.toggleDisplaySelection(sensor.serial) }
                                            .defaultMinSize(minWidth = 26.dp, minHeight = 26.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Surface(
                                            color = badgeBg,
                                            shape = androidx.compose.foundation.shape.CircleShape,
                                            border = badgeBorder
                                        ) {
                                            Icon(
                                                imageVector = if (isSelectedForDisplay) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                                                contentDescription = if (isSelectedForDisplay) selectedDescription else selectDescription,
                                                tint = badgeColor,
                                                modifier = Modifier
                                                    .padding(horizontal = 8.dp, vertical = 8.dp)
                                                    .size(18.dp)
                                            )
                                        }
                                    }
                                } else {
                                    // Single sensor: slim inline checkmark, no touch target
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Icon(
                                        imageVector = Icons.Rounded.CheckCircle,
                                        contentDescription = "Active",
                                        tint = badgeColor,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                                Text(
                                    text = statusText,
                                    style = enabledTextStyle,
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(start = 8.dp)
                                )
                            }
                            // Feature: Detailed Sensor Status
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                val sensorStatusText = when {
                                    sensor.detailedStatus.isNotEmpty() -> sensor.detailedStatus
                                    sensor.connectionStatus.isNotEmpty() -> sensor.connectionStatus
                                    else -> null
                                }

                                currentSnapshot?.let { snapshot ->
                                    SensorCurrentValueChip(
                                        snapshot = snapshot,
                                        accentColor = sensor.color
                                    )
                                }
                                sensorStatusText?.let { status ->
                                    Text(
                                        text = status,
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false)
                                    )
                                }
                            }
                        }

                        // Logic: Show Pause if running, Play if stopped (to resume)
                        IconButton(
                            onClick = {
                                if (isStreaming) {
                                    android.util.Log.d("SensorCard", "Pause button clicked for: ${sensor.serial}")
                                    viewModel.disconnectSensor(sensor.serial)
                                } else {
                                    android.util.Log.d("SensorCard", "Play button clicked for: ${sensor.serial}")
                                    viewModel.reconnectSensor(sensor.serial, false)
                                }
                            },
                            modifier = Modifier
                                .size(48.dp)
                                .background(MaterialTheme.colorScheme.surfaceDim.copy(alpha=0.5f), CircleShape)
                        ) {
                            Icon(
                                imageVector = if (isStreaming) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = "Toggle Sensor",
                                modifier = Modifier.size(26.dp),
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
//                } // Close Column (content)
//                } // Close Column (content)
//            } // Close Row (color indicator wrapper)
//            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(modifier = Modifier.height(16.dp))
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainer // Tonal separation
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Clean Label-Value rows
                    val labelStyle = MaterialTheme.typography.labelMedium.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val valueStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface)
                    var batteryRefreshAnimation by remember(sensor.serial) { mutableIntStateOf(0) }

                    @Composable
                    fun DataRow(
                        label: String,
                        value: String,
                        onClick: (() -> Unit)? = null,
                        valueAnimationKey: Int? = null,
                    ) {
                        val rowModifier = if (onClick != null) {
                            Modifier
                                .fillMaxWidth()
                                .minimumInteractiveComponentSize()
                                .clickable(onClick = onClick)
                        } else {
                            Modifier.fillMaxWidth()
                        }
                        Row(
                            modifier = rowModifier,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.Top
                        ) {
                            Text(
                                text = label,
                                style = labelStyle,
                                maxLines = 2,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                modifier = Modifier.weight(0.42f)
                            )
                            if (valueAnimationKey == null) {
                                Text(
                                    text = value,
                                    style = valueStyle,
                                    maxLines = 2,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.End,
                                    modifier = Modifier.weight(0.58f)
                                )
                            } else {
                                AnimatedContent(
                                    targetState = value to valueAnimationKey,
                                    transitionSpec = {
                                        (fadeIn(tween(180)) + slideInVertically(tween(180)) { it / 3 })
                                            .togetherWith(
                                                fadeOut(tween(120)) + slideOutVertically(tween(120)) { -it / 3 },
                                            )
                                    },
                                    contentAlignment = Alignment.CenterEnd,
                                    label = "sensorBatteryValue",
                                    modifier = Modifier.weight(0.58f),
                                ) { (animatedValue, _) ->
                                    Text(
                                        text = animatedValue,
                                        style = valueStyle,
                                        maxLines = 2,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                        textAlign = androidx.compose.ui.text.style.TextAlign.End,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }
                            }
                        }
                    }

                    val connectedStatus = stringResource(R.string.status_connected)
                    if (sensor.connectionStatus.isNotEmpty() &&
                        !sensor.connectionStatus.equals(connectedStatus, ignoreCase = true)
                    ) {
                        DataRow(stringResource(R.string.last_ble_status), sensor.connectionStatus)
                    }
                    DataRow(stringResource(R.string.sensor_address), sensor.deviceAddress)
                    
                    // FIX: Use Long timestamp directly to avoid String Parsing Locale bugs in formatSensorTime
                    // User reported "100% Fill / Red Color" bug in English Locale, likely due to startMs being 0 or parse fail.
                    // We also ensure we only show valid dates.
                    if (sensor.startMs > 1577836800000L) { // > Jan 1 2020
                        DataRow(stringResource(R.string.sensor_started), formatSensorTime(sensor.startMs.toString()))
                    }

                    if (sensor.officialEndMs > 0) {
                        DataRow(stringResource(R.string.sensor_ends_officially), formatSensorTime(sensor.officialEndMs.toString()))
                    } else if (sensor.officialEnd.isNotEmpty()) {
                        DataRow(stringResource(R.string.sensor_ends_officially), formatSensorTime(sensor.officialEnd))
                    }

                    if (sensor.expectedEndMs > 0) {
                        DataRow(stringResource(R.string.sensor_expected_end), formatSensorTime(sensor.expectedEndMs.toString()))
                    } else if (sensor.expectedEnd.isNotEmpty()) {
                       DataRow(stringResource(R.string.sensor_expected_end), formatSensorTime(sensor.expectedEnd))
                    }

                    if (sensor.isAnytime && sensor.batteryMillivolts > 0) {
                        // Anytime: surface both percent and voltage — voltage is the
                        // health-critical metric (low-battery cutoff is 4.05 V on CT3).
                        val voltsText = String.format(java.util.Locale.getDefault(), "%.2f V", sensor.batteryMillivolts / 1000.0)
                        val combined = if (sensor.batteryPercent >= 0) {
                            "${sensor.batteryPercent}% · $voltsText"
                        } else {
                            voltsText
                        }
                        DataRow(stringResource(R.string.sensor_battery_voltage), combined)
                    } else if (sensor.batteryPercent >= 0) {
                        val refreshBattery = if (sensor.isSibionics && sensor.isVendorConnected) {
                            {
                                if (viewModel.refreshSensorBattery(sensor.serial)) {
                                    batteryRefreshAnimation++
                                }
                            }
                        } else {
                            null
                        }
                        DataRow(
                            label = stringResource(R.string.sensor_battery_voltage),
                            value = "${sensor.batteryPercent}%",
                            onClick = refreshBattery,
                            valueAnimationKey = if (sensor.isSibionics) batteryRefreshAnimation else null,
                        )
                    } else if (sensor.batteryMillivolts > 0) {
                        DataRow(stringResource(R.string.sensor_battery_voltage), String.format(java.util.Locale.getDefault(), "%.3f V", sensor.batteryMillivolts / 1000.0))
                    }

                    if (sensor.sensorRemainingHours >= 0) {
                        val remainText = when {
                            sensor.isSensorExpired -> stringResource(R.string.expired)
                            sensor.sensorRemainingHours <= 0 -> stringResource(R.string.expired)
                            sensor.sensorRemainingHours <= 24 -> stringResource(R.string.hours_remaining, sensor.sensorRemainingHours)
                            else -> {
                                val days = sensor.sensorRemainingHours / 24
                                val hours = sensor.sensorRemainingHours % 24
                                stringResource(R.string.days_hours_remaining, days, hours)
                            }
                        }
                        val remainColor = when {
                            sensor.isSensorExpired || sensor.sensorRemainingHours <= 0 -> MaterialTheme.colorScheme.error
                            sensor.sensorRemainingHours <= 24 -> MaterialTheme.colorScheme.error
                            sensor.sensorRemainingHours <= 48 -> MaterialTheme.colorScheme.tertiary
                            else -> MaterialTheme.colorScheme.onSurface
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(stringResource(R.string.sensor_life), style = labelStyle)
                            Text(
                                remainText,
                                style = valueStyle.copy(color = remainColor),
                                fontWeight = if (sensor.sensorRemainingHours <= 24) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }

                    if (sensor.sensorAgeHours >= 0) {
                        val ageText = if (sensor.sensorAgeHours < 24) "${sensor.sensorAgeHours}h"
                                      else "${sensor.sensorAgeHours / 24}d ${sensor.sensorAgeHours % 24}h"
                        DataRow(stringResource(R.string.sensor_age), ageText)
                    }

                    if (sensor.vendorModel.isNotEmpty()) {
                        DataRow(stringResource(R.string.model), sensor.vendorModel)
                    }
                    if (sensor.sensorDetailTelemetry.isNotBlank()) {
                        DataRow(stringResource(R.string.anytime_sensor_telemetry), sensor.sensorDetailTelemetry)
                    }
                    if (sensor.vendorFirmware.isNotEmpty()) {
                        val firmwareText = if (sensor.vendorFirmware.startsWith("v", ignoreCase = true)) {
                            sensor.vendorFirmware
                        } else {
                            "v${sensor.vendorFirmware}"
                        }
                        DataRow(stringResource(R.string.firmware), firmwareText)
                    }

                    if (sensor.isSensorExpired) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(stringResource(R.string.status), style = labelStyle)
                            Text(
                                stringResource(R.string.sensor_expired_text),
                                style = valueStyle.copy(color = MaterialTheme.colorScheme.error),
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                }
            }
//
//            Card(
//                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer), // Secondary Container
//                shape = RoundedCornerShape(12.dp),
//                modifier = Modifier.fillMaxWidth()
//            ) {
//                Column(
//                    modifier = Modifier.padding(12.dp),
//                    verticalArrangement = Arrangement.spacedBy(4.dp)
//                ) {
//                    if (sensor.connectionStatus.isNotEmpty()) {
//                        InfoRow(stringResource(R.string.last_ble_status), sensor.connectionStatus)
//                    }
//                    InfoRow(stringResource(R.string.sensor_address), sensor.deviceAddress)
//
//                    InfoRow(stringResource(R.string.sensor_started), formatSensorTime(sensor.starttime))
//                    if (sensor.officialEnd.isNotEmpty()) {
//                        InfoRow(stringResource(R.string.sensor_ends_officially), formatSensorTime(sensor.officialEnd))
//                    }
//                    if (sensor.expectedEnd.isNotEmpty()) {
//                        InfoRow(stringResource(R.string.sensor_expected_end), formatSensorTime(sensor.expectedEnd))
//                    }
//                    // InfoRow("Streaming", if (sensor.streaming) "Enabled" else "Disabled")
//                }
//            }

            Spacer(modifier = Modifier.height(16.dp)) // More breathing room (M3 Expressive)
//            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
//            Spacer(modifier = Modifier.height(16.dp))

            // Edit 79 rev: Sensor Data Mode — ConnectedButtonGroup
            if (sensor.isSibionics || sensor.supportsDisplayModes) {
                Text(
                    stringResource(R.string.data_mode),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(16.dp))
                val modeLabels = listOf(
                    stringResource(R.string.auto),
                    stringResource(R.string.raw),
                    stringResource(R.string.auto_raw),
                    stringResource(R.string.raw_auto)
                )
                ConnectedButtonGroup(
                    options = modeLabels.indices.toList(),
                    selectedOption = sensor.viewMode,
                    onOptionSelected = { viewModel.setCalibrationMode(sensor.serial, it) },
                    labelText = { modeLabels[it] },
                    label = {
                        Text(
                            text = modeLabels[it],
                            maxLines = 1,
                            softWrap = false,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            modifier = Modifier
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                    selectedContentColor = MaterialTheme.colorScheme.onPrimary,
                    unselectedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                    unselectedContentColor = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(20.dp))

                // Auto-calibration entry — Sibionics only, hidden when Raw mode selected (viewMode == 1)
                if (sensor.isSibionics && sensor.viewMode != 1) {
                    val calibrationEnabled = sensor.customCalIndex and 1 != 0
                    val baseAlgorithm = stringResource(
                        when (sensor.customCalIndex and 6) {
                            2 -> R.string.sibionics_state_algorithm
                            4 -> R.string.sibionics_balanced_algorithm
                            6 -> R.string.sibionics_responsive_algorithm
                            else -> R.string.sibionics_stock_algorithm_desc
                        }
                    )
                    val calSubtitle = if (calibrationEnabled) {
                        "$baseAlgorithm • ${stringResource(R.string.calibration)}"
                    } else baseAlgorithm
                    Surface(
                        onClick = { showSibionicsCalSheet = true },
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Tune,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(16.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    stringResource(R.string.auto_calibration_mode),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    calSubtitle,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (sensor.customCalIndex != 0) MaterialTheme.colorScheme.tertiary
                                           else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Icon(
                                imageVector = Icons.Default.KeyboardArrowRight,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
//                    Spacer(modifier = Modifier.height(8.dp))
                }
            }


            // Edit 79: Auto-calibration and auto-reset controls moved to Sibionics Calibration bottom sheet.

            // --- ACTION BUTTONS (Always Visible) ---
            Spacer(modifier = Modifier.height(8.dp))

            // AiDex: Calibration history list, then Calibrate button, then Reset | Pair/Unpair row
            if (sensor.isAidex) {

                // Full-width Calibrate button — disabled when vendor BLE is not connected
                val canCalibrate = sensor.isVendorConnected
                FilledTonalButton(
                    onClick = { showSensorCalibrateDialog = true },
                    enabled = canCalibrate,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    shape = RoundedCornerShape(28.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        disabledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                        disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.Bloodtype,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (canCalibrate) stringResource(R.string.calibrate_action) else stringResource(R.string.calibrate_connect_first),
                        maxLines = 1
                    )
                }
                // Calibration history — show previous calibrations from the sensor
                if (sensor.vendorCalibrations.isNotEmpty()) {
                    val isMmol = tk.glucodata.ui.util.GlucoseFormatter.isMmolApp()
                    val calDateFormat = java.text.SimpleDateFormat("dd MMM HH:mm", java.util.Locale.getDefault())
                    val calCount = sensor.vendorCalibrations.size
                    val collapsible = calCount > 3
                    var calExpanded by rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
                    // Newest first — reverse so most recent calibrations appear at the top
//                    val allCalsReversed = sensor.vendorCalibrations()
                    val visibleCals = if (collapsible && !calExpanded) {
                        sensor.vendorCalibrations.take(3)
                    } else {
                        sensor.vendorCalibrations
                    }

                    Surface(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        tonalElevation = 1.dp
                    ) {
                        Column(
                            modifier = Modifier.animateContentSize()
                        ) {
                            visibleCals.forEachIndexed { idx, cal ->
                                // Divider between rows, but NOT after the last visible row
                                if (idx > 0) {
                                    HorizontalDivider(
                                        modifier = Modifier.padding(horizontal = 12.dp),
                                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                                    )
                                }
                                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        val displayGlucose = tk.glucodata.ui.util.GlucoseFormatter.formatFromMgDl(
                                            cal.referenceGlucoseMgDl.toFloat(),
                                            isMmol
                                        )
                                        Text(
                                            text = displayGlucose,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Medium,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        val timeText = if (cal.timestampMs > 0) {
                                            calDateFormat.format(java.util.Date(cal.timestampMs))
                                        } else {
                                            "${cal.timeOffsetMinutes}m"
                                        }
                                        Text(
                                            text = timeText,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Row(
                                        modifier = Modifier.padding(top = 2.dp),
                                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        Text(
                                            text = "CF: ${"%.2f".format(cal.cf)}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Text(
                                            text = "Offset: ${"%.2f".format(cal.offset)}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                            // Expand/collapse at BOTTOM — no extra padding, rounded bottom corners
                            if (collapsible) {
                                HorizontalDivider(
                                    modifier = Modifier.padding(horizontal = 12.dp),
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                                )
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp))
                                        .clickable { calExpanded = !calExpanded }
                                        .heightIn(min = 48.dp),
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = if (calExpanded) {
                                            stringResource(R.string.show_less)
                                        } else {
                                            stringResource(R.string.show_all_count, calCount)
                                        },
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Icon(
                                        imageVector = if (calExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                // Edit 78: Bias correction toggle moved to the Reset & Correction bottom sheet.
                // No inline toggle here — it was clipped by the card container and had
                // touch target issues. Users access it via the Reset button now.


                // Edit 74: Reset (left, smaller, no weight) | Unpair/Pair (right, larger, weight 1f)
                // Unpair/Pair is more important — it's the primary action for AiDex sensor management.
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Edit 78: Reset button — opens bottom sheet. Shows tertiary tint when
                    // bias correction is active so the user knows something is going on.
                    FilledTonalButton(
                        onClick = { showAiDexClearDialog = true },
                        shape = RoundedCornerShape(
                            topStart = 12.dp,
                            bottomStart = 12.dp,
                            topEnd = 4.dp,
                            bottomEnd = 4.dp
                        ),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = if (sensor.resetCompensationActive)
                                MaterialTheme.colorScheme.tertiaryContainer
                            else
                                MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = if (sensor.resetCompensationActive)
                                MaterialTheme.colorScheme.onTertiaryContainer
                            else
                                MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.RestartAlt,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            if (sensor.resetCompensationActive) stringResource(R.string.correcting) else stringResource(R.string.resettitle),
                            maxLines = 1
                        )
                    }
                    // Pair / Unpair toggle — right (weight 1f = fills remaining space, prominent)
                    if (sensor.isVendorPaired) {
                        FilledTonalButton(
                            onClick = { showAiDexUnpairDialog = true },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(
                                topStart = 4.dp,
                                bottomStart = 4.dp,
                                topEnd = 12.dp,
                                bottomEnd = 12.dp
                            ),
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                                contentColor = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                        ) {
                            Icon(
                                imageVector = Icons.Default.LinkOff,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.unpair), maxLines = 1)
                        }
                    } else {
                        FilledTonalButton(
                            onClick = {
                                viewModel.rePairAiDexSensor(sensor.serial)
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(
                                topStart = 4.dp,
                                bottomStart = 4.dp,
                                topEnd = 12.dp,
                                bottomEnd = 12.dp
                            ),
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        ) {
                            Icon(
                                imageVector = Icons.Default.Link,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.pair), maxLines = 1)
                        }
                    }
                }
            }

            // Row 1: Unified Reset Button (Sibionics only - full width, styled like "Previous calibrations")
            if (sensor.isSibionics) {
                FilledTonalButton(
                    onClick = { showUnifiedResetDialog = true },
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    shape = RoundedCornerShape(28.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.SettingsBackupRestore,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.reset_sensor))
                }

                if (sensor.isSibionics2) {
                    val isAutoResetEnabled = sensor.autoResetDays in 1..22
                    var daysValue by remember(sensor.serial, sensor.autoResetDays) {
                        mutableIntStateOf(sensor.autoResetDays.takeIf { it in 1..22 } ?: 22)
                    }
                    fun setAutoResetEnabled(enabled: Boolean) {
                        viewModel.setAutoResetDays(sensor.serial, if (enabled) daysValue else 300)
                    }
                    BoxWithConstraints(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp),
                    ) {
                        Row(
                            modifier = Modifier
                                // The content column starts after the 4 dp sensor rail and has
                                // another 16 dp inset. Expand asymmetrically through both while
                                // keeping the row contents on the original 20/16 dp grid.
                                .requiredWidth(maxWidth + 36.dp)
                                .offset(x = (-2).dp)
                                .toggleable(
                                    value = isAutoResetEnabled,
                                    role = Role.Switch,
                                    onValueChange = ::setAutoResetEnabled,
                                )
                                .heightIn(min = 56.dp)
                                .padding(start = 20.dp, end = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = stringResource(R.string.auto_reset_title),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                modifier = Modifier.weight(1f),
                            )
                            if (isAutoResetEnabled) {
                                Surface(
                                    shape = MaterialTheme.shapes.large,
                                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                                    modifier = Modifier.padding(end = 8.dp),
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(horizontal = 2.dp),
                                    ) {
                                        IconButton(
                                            onClick = {
                                                if (daysValue > 1) {
                                                    daysValue--
                                                    viewModel.setAutoResetDays(sensor.serial, daysValue)
                                                }
                                            },
                                            enabled = daysValue > 1,
                                            modifier = Modifier.size(40.dp),
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Remove,
                                                contentDescription = stringResource(R.string.outbound_api_decrease_value),
                                                modifier = Modifier.size(18.dp),
                                            )
                                        }
                                        Surface(
                                            shape = MaterialTheme.shapes.medium,
                                            color = MaterialTheme.colorScheme.primaryContainer,
                                        ) {
                                            Text(
                                                text = stringResource(R.string.auto_reset_days, daysValue),
                                                style = MaterialTheme.typography.labelLarge,
                                                fontWeight = FontWeight.SemiBold,
                                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                                            )
                                        }
                                        IconButton(
                                            onClick = {
                                                if (daysValue < 22) {
                                                    daysValue++
                                                    viewModel.setAutoResetDays(sensor.serial, daysValue)
                                                }
                                            },
                                            enabled = daysValue < 22,
                                            modifier = Modifier.size(40.dp),
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Add,
                                                contentDescription = stringResource(R.string.outbound_api_increase_value),
                                                modifier = Modifier.size(18.dp),
                                            )
                                        }
                                    }
                                }
                            }
                            StyledSwitch(
                                checked = isAutoResetEnabled,
                                onCheckedChange = null,
                            )
                        }
                    }
                }
            }

            if (sensor.isMq) {
                FilledTonalButton(
                    onClick = { showMqRestoreSheet = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    shape = RoundedCornerShape(28.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.SettingsBackupRestore,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.mq_fetch_calibration_action))
                }
                FilledTonalButton(
                    onClick = { showMqCalibrationSheet = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    shape = RoundedCornerShape(28.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.mq_manual_calibration_title))
                }
            }

            if (sensor.isAnytime && sensor.supportsManualCalibration) {
                val warmupComplete = AnytimeCalibrationPolicy.canAcceptManualCalibration(sensor.sensorAgeHours)
                val canCalibrate = sensor.isVendorConnected && warmupComplete
                FilledTonalButton(
                    onClick = { showSensorCalibrateDialog = true },
                    enabled = canCalibrate,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    shape = RoundedCornerShape(28.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        disabledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                        disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.Bloodtype,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = when {
                            !sensor.isVendorConnected -> stringResource(R.string.calibrate_connect_first)
                            !warmupComplete -> stringResource(R.string.calibrate_after_24h)
                            else -> stringResource(R.string.calibrate_action)
                        },
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                }

                if (sensor.vendorCalibrations.any { it.source == ManagedSensorCalibrationSource.ANYTIME }) {
                    val isMmol = tk.glucodata.ui.util.GlucoseFormatter.isMmolApp()
                    val calDateFormat = java.text.SimpleDateFormat("dd MMM HH:mm", java.util.Locale.getDefault())
                    val anytimeCals = sensor.vendorCalibrations
                        .filter { it.source == ManagedSensorCalibrationSource.ANYTIME }
                    val calCount = anytimeCals.size
                    val collapsible = calCount > 3
                    var calExpanded by rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
                    val visibleCals = if (collapsible && !calExpanded) {
                        anytimeCals.take(3)
                    } else {
                        anytimeCals
                    }

                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp),
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        tonalElevation = 1.dp
                    ) {
                        Column(modifier = Modifier.animateContentSize()) {
                            Text(
                                text = stringResource(R.string.previous_calibrations),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                            )
                            visibleCals.forEachIndexed { idx, cal ->
                                if (idx > 0) {
                                    HorizontalDivider(
                                        modifier = Modifier.padding(horizontal = 12.dp),
                                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                                    )
                                }
                                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        val displayGlucose = tk.glucodata.ui.util.GlucoseFormatter.formatFromMgDl(
                                            cal.referenceGlucoseMgDl.toFloat(),
                                            isMmol
                                        )
                                        Text(
                                            text = displayGlucose,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Medium,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        val timeText = if (cal.timestampMs > 0) {
                                            calDateFormat.format(java.util.Date(cal.timestampMs))
                                        } else {
                                            stringResource(R.string.anytime_calibration_target_reading, cal.index)
                                        }
                                        Text(
                                            text = timeText,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Row(
                                        modifier = Modifier.padding(top = 2.dp),
                                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        Text(
                                            text = stringResource(R.string.anytime_calibration_target_reading, cal.index),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Text(
                                            text = if (cal.appliedGlucoseId > 0) {
                                                stringResource(R.string.anytime_calibration_applied_reading, cal.appliedGlucoseId)
                                            } else {
                                                stringResource(R.string.anytime_calibration_pending_record)
                                            },
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    if (cal.outputGlucoseMgDl > 0) {
                                        Text(
                                            text = stringResource(
                                                R.string.anytime_calibration_algorithm_output,
                                                tk.glucodata.ui.util.GlucoseFormatter.formatFromMgDl(
                                                    cal.outputGlucoseMgDl.toFloat(),
                                                    isMmol
                                                )
                                            ),
                                            modifier = Modifier.padding(top = 2.dp),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                            if (collapsible) {
                                HorizontalDivider(
                                    modifier = Modifier.padding(horizontal = 12.dp),
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                                )
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp))
                                        .clickable { calExpanded = !calExpanded }
                                        .heightIn(min = 48.dp),
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = if (calExpanded) {
                                            stringResource(R.string.show_less)
                                        } else {
                                            stringResource(R.string.show_all_count, calCount)
                                        },
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Icon(
                                        imageVector = if (calExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                if (sensor.supportsClearCalibration) {
                    FilledTonalButton(
                        onClick = { showAnytimeClearCalibrationDialog = true },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp),
                        shape = RoundedCornerShape(28.dp),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.clear_calibrations_title),
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                    }
                }
            }

            if (!sensor.isAidex && !sensor.isSibionics && sensor.supportsHardwareReset) {
                FilledTonalButton(
                    onClick = { showResetDialog = true },
                    enabled = sensor.isVendorConnected,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    shape = RoundedCornerShape(28.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        disabledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                        disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.SettingsBackupRestore,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.reset_sensor))
                }
            }

            // Edit 63b: All sensors get the same 2-button row: Reconnect | Disconnect.
            // AiDex-specific behavior is handled in the dialogs (terminate dialog routes
            // AiDex through disconnectSensor instead of terminateSensor).
            // Edit 65c: Keep the old full-width 50/50 row when both labels fit, then let
            // Disconnect keep priority only on genuinely tight localized layouts.
            val reconnectLabel = stringResource(R.string.reconnect)
            val disconnectLabel = stringResource(R.string.disconnect)
            val layoutDirection = LocalLayoutDirection.current
            val density = LocalDensity.current
            val textMeasurer = rememberTextMeasurer()
            val buttonTextStyle = MaterialTheme.typography.labelLarge
            val buttonChromeWidth = 16.dp +
                8.dp +
                ButtonDefaults.ContentPadding.calculateLeftPadding(layoutDirection) +
                ButtonDefaults.ContentPadding.calculateRightPadding(layoutDirection)
            val reconnectPreferredWidth = with(density) {
                textMeasurer.measure(
                    text = reconnectLabel,
                    style = buttonTextStyle,
                    maxLines = 1
                ).size.width.toDp() + buttonChromeWidth
            }
            val disconnectPreferredWidth = with(density) {
                textMeasurer.measure(
                    text = disconnectLabel,
                    style = buttonTextStyle,
                    maxLines = 1
                ).size.width.toDp() + buttonChromeWidth
            }

            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val buttonSpacing = 8.dp
                val equalButtonWidth = (maxWidth - buttonSpacing) / 2
                val prioritizeDisconnect =
                    reconnectPreferredWidth > equalButtonWidth ||
                    disconnectPreferredWidth > equalButtonWidth

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(buttonSpacing)
                ) {
                    // Reconnect always stays flexible so it can either match the old 50/50
                    // layout or yield first when Disconnect needs more room.
                    FilledTonalButton(
                        onClick = { showReconnectDialog = true },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(
                            topStart = 12.dp,
                            bottomStart = 12.dp,
                            topEnd = 4.dp,
                            bottomEnd = 4.dp
                        ),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.BluetoothConnected,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            reconnectLabel,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                    }

                    FilledTonalButton(
                        onClick = { showTerminateDialog = true },
                        modifier = if (prioritizeDisconnect) Modifier else Modifier.weight(1f),
                        shape = RoundedCornerShape(
                            topStart = 4.dp,
                            bottomStart = 4.dp,
                            topEnd = 12.dp,
                            bottomEnd = 12.dp
                        ),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.DeleteForever,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            disconnectLabel,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                    }
                }
            }
            }
        }
    }
}
}
