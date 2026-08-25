package tk.glucodata.ui.alerts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import tk.glucodata.Notify
import tk.glucodata.R
import tk.glucodata.alerts.AlertRepository
import tk.glucodata.alerts.AlertType
import tk.glucodata.alerts.CompressionHoldRuntime
import tk.glucodata.logic.CompressionLowDetector
import tk.glucodata.ui.components.StyledSwitch
import kotlin.math.roundToInt
import kotlin.math.roundToLong

private const val MGDL_PER_MMOL = 18.0182f

/**
 * The settings card of the sensor-pressure hold (the "compression low gatekeeper"),
 * including the cue's own sound and haptics: the cue is not an alarm anyone arms on its
 * own, so it does not sit in the alert list pretending to be one — it lives here, with
 * the feature that is the only thing able to fire it, and the opt-in switch arms it.
 *
 * Everything here is user-owned by deliberate decision, safety rails included: the
 * recommended values are defaults and warning texts, never locks. The card owes the
 * user plain words for every lever — nobody should need a search engine to understand
 * what a knob does or why they would touch it.
 */
@Composable
internal fun SensorPressureHoldCard(
    isMmol: Boolean,
    onPickCueSound: (currentUri: String?, typeId: Int, onPicked: (String?) -> Unit) -> Unit
) {
    var optedIn by remember { mutableStateOf(CompressionHoldRuntime.isOptedIn()) }
    var selfDisabled by remember { mutableStateOf(CompressionHoldRuntime.isSelfDisabled()) }
    var maxHold by remember { mutableStateOf(CompressionHoldRuntime.maxHoldMinutes()) }
    var floorMode by remember { mutableStateOf(CompressionHoldRuntime.floorMode()) }
    var floorCustomMgdl by remember { mutableStateOf(CompressionHoldRuntime.floorCustomMgdl()) }
    var selfDisableLimit by remember { mutableStateOf(CompressionHoldRuntime.selfDisableLimit()) }
    var tuning by remember { mutableStateOf(CompressionHoldRuntime.loadTuning()) }
    var advancedExpanded by remember { mutableStateOf(false) }
    var cueConfig by remember { mutableStateOf(AlertRepository.loadConfig(AlertType.SENSOR_PRESSURE)) }
    val log = remember(optedIn) { CompressionHoldRuntime.loadLog() }

    fun saveCue(updated: tk.glucodata.alerts.AlertConfig) {
        if (updated == cueConfig) return
        cueConfig = updated
        AlertRepository.saveConfig(updated)
    }

    fun saveTuning(updated: CompressionLowDetector.Tuning) {
        tuning = updated
        CompressionHoldRuntime.saveTuning(updated)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors()
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // Collapsed by default: this is a whole feature, not one alarm, and the
            // alert screen is a list of alarms. The header carries what matters at a
            // glance — what it is, that it is experimental, and whether it is on.
            var cardExpanded by remember { mutableStateOf(false) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(
                    Modifier
                        .weight(1f)
                        .clickable { cardExpanded = !cardExpanded },
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    // Give translated titles the full text column. The badge sits on
                    // its own line so neither label has to compete for horizontal room.
                    Text(
                        stringResource(R.string.sensor_pressure_hold_section_title),
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2
                    )
                    Surface(
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                        shape = MaterialTheme.shapes.small
                    ) {
                        Text(
                            stringResource(R.string.experimental_badge),
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            softWrap = false,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                    Text(
                        stringResource(R.string.sensor_pressure_hold_optin_title),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2
                    )
                }
                Icon(
                    if (cardExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = stringResource(
                        if (cardExpanded) R.string.sensor_pressure_hold_collapse
                        else R.string.sensor_pressure_hold_expand
                    ),
                    modifier = Modifier
                        .size(24.dp)
                        .clickable { cardExpanded = !cardExpanded }
                )
                Spacer(Modifier.width(16.dp))
                StyledSwitch(
                    checked = optedIn && !selfDisabled,
                    onCheckedChange = { enabled ->
                        // setEnabled arms the cue too; read back what it wrote.
                        CompressionHoldRuntime.setEnabled(enabled)
                        cueConfig = AlertRepository.loadConfig(AlertType.SENSOR_PRESSURE)
                        optedIn = enabled
                        selfDisabled = false
                        if (enabled) cardExpanded = true
                    }
                )
            }
            if (selfDisabled) {
                Text(
                    stringResource(
                        R.string.sensor_pressure_hold_self_disabled_notice,
                        log.escalatedCount()
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            if (log.entries.isNotEmpty()) {
                Text(
                    stringResource(
                        R.string.sensor_pressure_hold_summary_text,
                        log.entries.size, log.resolvedCount(), log.escalatedCount()
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (cardExpanded) {
                // Two sentences up front; the full risk text lives one tap away — a wall of
                // prose next to a master switch is read by nobody.
                var descriptionExpanded by remember { mutableStateOf(false) }
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clickable { descriptionExpanded = !descriptionExpanded }
                ) {
                    Text(
                        stringResource(R.string.sensor_pressure_hold_optin_summary),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(
                                if (descriptionExpanded) R.string.sensor_pressure_hold_optin_less
                                else R.string.sensor_pressure_hold_optin_more
                            ),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Icon(
                            if (descriptionExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    if (descriptionExpanded) {
                        Text(
                            stringResource(R.string.sensor_pressure_hold_optin_description),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
                if (optedIn) {
                    LabeledSlider(
                        label = stringResource(R.string.sensor_pressure_hold_max_hold_label),
                        description = stringResource(R.string.sensor_pressure_hold_max_hold_description),
                        valueText = "$maxHold min",
                        value = maxHold.toFloat(),
                        range = 1f..30f,
                        onChange = {
                            maxHold = it.roundToInt()
                            CompressionHoldRuntime.setMaxHoldMinutes(maxHold)
                        }
                    )
                    if (maxHold > CompressionHoldRuntime.RECOMMENDED_MAX_HOLD_MINUTES) {
                        WarningText(
                            stringResource(
                                R.string.sensor_pressure_hold_warning_long_hold,
                                CompressionHoldRuntime.RECOMMENDED_MAX_HOLD_MINUTES
                            )
                        )
                    }

                    Text(
                        stringResource(R.string.sensor_pressure_hold_floor_label),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        stringResource(R.string.sensor_pressure_hold_floor_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    FloorModeOption(
                        label = stringResource(R.string.sensor_pressure_hold_floor_very_low),
                        selected = floorMode == CompressionHoldRuntime.FLOOR_MODE_VERY_LOW
                    ) {
                        floorMode = CompressionHoldRuntime.FLOOR_MODE_VERY_LOW
                        CompressionHoldRuntime.setFloorMode(floorMode)
                    }
                    FloorModeOption(
                        label = stringResource(R.string.sensor_pressure_hold_floor_custom) +
                            "  (" + formatGlucose(floorCustomMgdl, isMmol) + ")",
                        selected = floorMode == CompressionHoldRuntime.FLOOR_MODE_CUSTOM
                    ) {
                        floorMode = CompressionHoldRuntime.FLOOR_MODE_CUSTOM
                        CompressionHoldRuntime.setFloorMode(floorMode)
                    }
                    if (floorMode == CompressionHoldRuntime.FLOOR_MODE_CUSTOM) {
                        LabeledSlider(
                            label = "",
                            description = null,
                            valueText = formatGlucose(floorCustomMgdl, isMmol),
                            value = floorCustomMgdl,
                            range = 36f..80f,
                            onChange = {
                                floorCustomMgdl = it.roundToInt().toFloat()
                                CompressionHoldRuntime.setFloorCustomMgdl(floorCustomMgdl)
                            }
                        )
                    }
                    FloorModeOption(
                        label = stringResource(R.string.sensor_pressure_hold_floor_off),
                        selected = floorMode == CompressionHoldRuntime.FLOOR_MODE_OFF
                    ) {
                        floorMode = CompressionHoldRuntime.FLOOR_MODE_OFF
                        CompressionHoldRuntime.setFloorMode(floorMode)
                    }
                    if (floorMode == CompressionHoldRuntime.FLOOR_MODE_OFF) {
                        WarningText(stringResource(R.string.sensor_pressure_hold_floor_off_warning))
                    }

                    LabeledSlider(
                        label = stringResource(R.string.sensor_pressure_hold_self_disable_label),
                        description = stringResource(R.string.sensor_pressure_hold_self_disable_description),
                        valueText = if (selfDisableLimit == 0)
                            stringResource(R.string.sensor_pressure_hold_never)
                        else selfDisableLimit.toString(),
                        value = selfDisableLimit.toFloat(),
                        range = 0f..10f,
                        onChange = {
                            selfDisableLimit = it.roundToInt()
                            CompressionHoldRuntime.setSelfDisableLimit(selfDisableLimit)
                        }
                    )
                    if (selfDisableLimit == 0) {
                        WarningText(stringResource(R.string.sensor_pressure_hold_never_warning))
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.sensor_pressure_cue_settings_title),
                                style = MaterialTheme.typography.titleSmall
                            )
                            Text(
                                stringResource(R.string.sensor_pressure_cue_settings_description),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        StyledSwitch(
                            checked = cueConfig.enabled,
                            onCheckedChange = { on -> saveCue(cueConfig.copy(enabled = on)) }
                        )
                    }
                    if (!cueConfig.enabled) {
                        WarningText(stringResource(R.string.sensor_pressure_cue_disabled_warning))
                    }
                    CommonAlertSettings(
                        config = cueConfig,
                        onConfigChange = ::saveCue,
                        onPickSound = { current ->
                            onPickCueSound(current.customSoundUri, current.type.id) { uri ->
                                saveCue(cueConfig.copy(customSoundUri = uri))
                            }
                        },
                        onTest = { Notify.testTrigger(AlertType.SENSOR_PRESSURE.id) }
                    )

                    OutlinedButton(
                        onClick = { advancedExpanded = !advancedExpanded },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            if (advancedExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            stringResource(
                                if (advancedExpanded) R.string.sensor_pressure_hold_advanced_hide
                                else R.string.sensor_pressure_hold_advanced
                            )
                        )
                    }
                    if (advancedExpanded) {
                        Text(
                            stringResource(R.string.sensor_pressure_hold_advanced_description),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        TuningSliders(tuning, ::saveTuning)
                    }
                }
            }
        }
    }
}

@Composable
private fun TuningSliders(
    tuning: CompressionLowDetector.Tuning,
    onSave: (CompressionLowDetector.Tuning) -> Unit
) {
    LabeledSlider(
        label = stringResource(R.string.tuning_suspect_rate_label),
        description = stringResource(R.string.tuning_suspect_rate_description),
        valueText = "%.1f mg/dL/min".format(tuning.suspectDropMgdlPerMinute),
        value = tuning.suspectDropMgdlPerMinute,
        range = 0.5f..6f,
        onChange = { onSave(tuning.copy(suspectDropMgdlPerMinute = (it * 10).roundToInt() / 10f)) }
    )
    LabeledSlider(
        label = stringResource(R.string.tuning_min_depth_label),
        description = stringResource(R.string.tuning_min_depth_description),
        valueText = "%.0f mg/dL".format(tuning.minDropDepthMgdl),
        value = tuning.minDropDepthMgdl,
        range = 5f..60f,
        onChange = { onSave(tuning.copy(minDropDepthMgdl = it.roundToInt().toFloat())) }
    )
    LabeledSlider(
        label = stringResource(R.string.tuning_flat_window_label),
        description = stringResource(R.string.tuning_flat_window_description),
        valueText = "${tuning.flatWindowMinutes} min",
        value = tuning.flatWindowMinutes.toFloat(),
        range = 5f..30f,
        onChange = { onSave(tuning.copy(flatWindowMinutes = it.roundToLong())) }
    )
    LabeledSlider(
        label = stringResource(R.string.tuning_flat_span_label),
        description = stringResource(R.string.tuning_flat_span_description),
        valueText = "${tuning.minFlatSpanMinutes} min",
        value = tuning.minFlatSpanMinutes.toFloat(),
        range = 1f..30f,
        onChange = { onSave(tuning.copy(minFlatSpanMinutes = it.roundToLong())) }
    )
    LabeledSlider(
        label = stringResource(R.string.tuning_flat_min_rate_label),
        description = stringResource(R.string.tuning_flat_min_rate_description),
        valueText = "%.1f mg/dL/min".format(tuning.flatMinRateMgdlPerMinute),
        value = tuning.flatMinRateMgdlPerMinute,
        range = -2f..0f,
        onChange = { onSave(tuning.copy(flatMinRateMgdlPerMinute = (it * 10).roundToInt() / 10f)) }
    )
    LabeledSlider(
        label = stringResource(R.string.tuning_flat_dip_label),
        description = stringResource(R.string.tuning_flat_dip_description),
        valueText = "%.0f mg/dL".format(tuning.flatDipToleranceMgdl),
        value = tuning.flatDipToleranceMgdl,
        range = 1f..30f,
        onChange = { onSave(tuning.copy(flatDipToleranceMgdl = it.roundToInt().toFloat())) }
    )
    LabeledSlider(
        label = stringResource(R.string.tuning_unexplained_factor_label),
        description = stringResource(R.string.tuning_unexplained_factor_description),
        valueText = "×%.2f".format(tuning.unexplainedFactor),
        value = tuning.unexplainedFactor,
        range = 0.5f..2f,
        onChange = { onSave(tuning.copy(unexplainedFactor = (it * 20).roundToInt() / 20f)) }
    )
    if (tuning.unexplainedFactor < 1f) {
        WarningText(stringResource(R.string.tuning_unexplained_factor_warning))
    }
    LabeledSlider(
        label = stringResource(R.string.tuning_max_gap_label),
        description = stringResource(R.string.tuning_max_gap_description),
        valueText = "${tuning.maxGapMinutes} min",
        value = tuning.maxGapMinutes.toFloat(),
        range = 2f..20f,
        onChange = { onSave(tuning.copy(maxGapMinutes = it.roundToLong())) }
    )
    LabeledSlider(
        label = stringResource(R.string.tuning_recovery_window_label),
        description = stringResource(R.string.tuning_recovery_window_description),
        valueText = "${tuning.recoveryWindowMinutes} min",
        value = tuning.recoveryWindowMinutes.toFloat(),
        range = 10f..90f,
        onChange = { onSave(tuning.copy(recoveryWindowMinutes = it.roundToLong())) }
    )
    LabeledSlider(
        label = stringResource(R.string.tuning_recovery_band_label),
        description = stringResource(R.string.tuning_recovery_band_description),
        valueText = "%.0f mg/dL".format(tuning.recoveryBandMgdl),
        value = tuning.recoveryBandMgdl,
        range = 5f..30f,
        onChange = { onSave(tuning.copy(recoveryBandMgdl = it.roundToInt().toFloat())) }
    )
    LabeledSlider(
        label = stringResource(R.string.tuning_carb_grams_label),
        description = stringResource(R.string.tuning_carb_grams_description),
        valueText = "%.0f g".format(tuning.negligibleCarbGrams),
        value = tuning.negligibleCarbGrams,
        range = 0f..20f,
        onChange = { onSave(tuning.copy(negligibleCarbGrams = it.roundToInt().toFloat())) }
    )
    LabeledSlider(
        label = stringResource(R.string.tuning_carb_lookback_label),
        description = stringResource(R.string.tuning_carb_lookback_description),
        valueText = "${tuning.carbLookbackMinutes} min",
        value = tuning.carbLookbackMinutes.toFloat(),
        range = 0f..90f,
        onChange = { onSave(tuning.copy(carbLookbackMinutes = it.roundToLong())) }
    )
}

@Composable
private fun LabeledSlider(
    label: String,
    description: String?,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        if (label.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Text(
                    valueText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
        if (description != null) {
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            valueRange = range
        )
    }
}

@Composable
private fun FloorModeOption(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onSelect),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        // Weighted so a long translation wraps instead of running off the card.
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun WarningText(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error
    )
}

private fun formatGlucose(mgdl: Float, isMmol: Boolean): String =
    if (isMmol) "%.1f mmol/L".format(mgdl / MGDL_PER_MMOL) else "%.0f mg/dL".format(mgdl)
