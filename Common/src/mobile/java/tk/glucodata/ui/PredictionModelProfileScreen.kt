@file:OptIn(ExperimentalMaterial3Api::class)

package tk.glucodata.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import tk.glucodata.R
import tk.glucodata.data.prediction.DoseTarget
import tk.glucodata.data.prediction.PredictionModelBlock
import tk.glucodata.data.prediction.PredictionModelProfile
import tk.glucodata.data.prediction.StateDoseHintCalculator
import tk.glucodata.ui.util.GlucoseFormatter
import tk.glucodata.ui.viewmodel.DashboardViewModel
import kotlin.math.roundToInt

private data class ProfileTimePickerRequest(
    val existingStartMinute: Int?,
    val initialMinute: Int
)

@Composable
fun PredictionModelProfileScreen(
    navController: NavController,
    viewModel: DashboardViewModel
) {
    val profile by viewModel.predictionModelProfile.collectAsState()
    val unit by viewModel.unit.collectAsState()
    val doseTargetMgDl by viewModel.predictionDoseTargetMgDl.collectAsState()
    val stateDoseHintEnabled by viewModel.stateDoseHintEnabled.collectAsState()
    val stateDoseHintHorizonMinutes by viewModel.stateDoseHintHorizonMinutes.collectAsState()
    val isMmol = GlucoseFormatter.isMmol(unit)
    var timePickerRequest by remember { mutableStateOf<ProfileTimePickerRequest?>(null) }
    var pendingDeleteStart by remember { mutableStateOf<Int?>(null) }

    Scaffold(
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0.dp),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.predictive_model_tuning)) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.navigate_back)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item(key = "dose_target") {
                DoseTargetCard(
                    targetMgDl = doseTargetMgDl,
                    isMmol = isMmol,
                    onTargetChange = { displayValue ->
                        viewModel.setPredictionDoseTargetMgDl(
                            if (isMmol) GlucoseFormatter.mmolToMg(displayValue) else displayValue
                        )
                    }
                )
            }

            // Only while the hint is on: with it off the number steers nothing, and the
            // screen is long enough already.
            if (stateDoseHintEnabled) {
                item(key = "dose_hint_horizon") {
                    DoseHintHorizonCard(
                        horizonMinutes = stateDoseHintHorizonMinutes,
                        onHorizonChange = { viewModel.setStateDoseHintHorizonMinutes(it.roundToInt()) }
                    )
                }
            }

            item(key = "explanation") {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.56f)
                ) {
                    Text(
                        text = stringResource(R.string.predictive_model_profile_explanation),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)
                    )
                }
            }

            itemsIndexed(
                items = profile.blocks,
                key = { _, block -> block.startMinuteOfDay }
            ) { index, block ->
                PredictionModelBlockCard(
                    block = block,
                    endMinute = profile.endMinuteFor(index),
                    isMmol = isMmol,
                    canEditStart = block.startMinuteOfDay != 0,
                    canDelete = block.startMinuteOfDay != 0,
                    onEditStart = {
                        timePickerRequest = ProfileTimePickerRequest(
                            existingStartMinute = block.startMinuteOfDay,
                            initialMinute = block.startMinuteOfDay
                        )
                    },
                    onDelete = { pendingDeleteStart = block.startMinuteOfDay },
                    onCarbRatioChange = { value ->
                        viewModel.updatePredictionModelBlock(
                            startMinuteOfDay = block.startMinuteOfDay,
                            carbRatioGramsPerUnit = value
                        )
                    },
                    onSensitivityChange = { value ->
                        viewModel.updatePredictionModelBlock(
                            startMinuteOfDay = block.startMinuteOfDay,
                            insulinSensitivityMgDlPerUnit = value
                        )
                    },
                    onCarbAbsorptionChange = { value ->
                        viewModel.updatePredictionModelBlock(
                            startMinuteOfDay = block.startMinuteOfDay,
                            carbAbsorptionGramsPerHour = value
                        )
                    }
                )
            }

            if (profile.blocks.size < PredictionModelProfile.MAX_BLOCKS) {
                item(key = "add_period") {
                    FilledTonalButton(
                        onClick = {
                            timePickerRequest = ProfileTimePickerRequest(
                                existingStartMinute = null,
                                initialMinute = profile.suggestedSplitMinute()
                            )
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(imageVector = Icons.Default.Add, contentDescription = null)
                        Text(
                            text = stringResource(R.string.predictive_add_time_period),
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }
                }
            }
        }
    }

    timePickerRequest?.let { request ->
        ProfileTimePickerDialog(
            request = request,
            occupiedStarts = profile.blocks.mapTo(mutableSetOf()) { it.startMinuteOfDay },
            onDismiss = { timePickerRequest = null },
            onConfirm = { selectedMinute ->
                if (request.existingStartMinute == null) {
                    viewModel.addPredictionModelBlock(selectedMinute)
                } else {
                    viewModel.movePredictionModelBlock(request.existingStartMinute, selectedMinute)
                }
                timePickerRequest = null
            }
        )
    }

    pendingDeleteStart?.let { startMinute ->
        AlertDialog(
            onDismissRequest = { pendingDeleteStart = null },
            title = { Text(stringResource(R.string.predictive_delete_time_period_title)) },
            text = { Text(stringResource(R.string.predictive_delete_time_period_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.removePredictionModelBlock(startMinute)
                        pendingDeleteStart = null
                    }
                ) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteStart = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

/**
 * One target for the whole day, above the per-period carb ratio and sensitivity cards:
 * it is what both dose suggestions correct toward, so it belongs with them rather than
 * with the in-range band, which is a display range.
 */
@Composable
private fun DoseTargetCard(
    targetMgDl: Float,
    isMmol: Boolean,
    onTargetChange: (Float) -> Unit
) {
    val targetDisplay = remember(targetMgDl, isMmol) {
        GlucoseFormatter.displayFromMgDl(targetMgDl, isMmol)
    }
    val targetValue = if (isMmol) {
        stringResource(R.string.predictive_dose_target_value_mmol, targetDisplay)
    } else {
        stringResource(R.string.predictive_dose_target_value_mgdl, targetMgDl)
    }
    // Same bounds the setter coerces to, so the slider cannot reach a value it would snap back from.
    val targetRange = remember(isMmol) {
        if (isMmol) {
            GlucoseFormatter.mgToMmol(DoseTarget.MIN_MGDL)..GlucoseFormatter.mgToMmol(DoseTarget.MAX_MGDL)
        } else {
            DoseTarget.MIN_MGDL..DoseTarget.MAX_MGDL
        }
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            PredictiveSimulationParameterRow(
                title = stringResource(R.string.predictive_dose_target),
                valueLabel = targetValue,
                value = targetDisplay,
                valueRange = targetRange,
                enabled = true,
                onValueChange = onTargetChange
            )
            Text(
                text = stringResource(R.string.predictive_dose_target_explanation),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }
    }
}

/**
 * How far ahead the falling case looks. Its own card under the dose target: it belongs to
 * the same decision, but it is a duration rather than a glucose value.
 */
@Composable
private fun DoseHintHorizonCard(
    horizonMinutes: Int,
    onHorizonChange: (Float) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            PredictiveSimulationParameterRow(
                title = stringResource(R.string.state_dose_hint_horizon),
                valueLabel = stringResource(R.string.predictive_horizon_value, horizonMinutes),
                value = horizonMinutes.toFloat(),
                valueRange = StateDoseHintCalculator.HORIZON_MINUTES_MIN.toFloat()..
                    StateDoseHintCalculator.HORIZON_MINUTES_MAX.toFloat(),
                enabled = true,
                onValueChange = onHorizonChange
            )
            Text(
                text = stringResource(R.string.state_dose_hint_horizon_explanation),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }
    }
}

@Composable
private fun PredictionModelBlockCard(
    block: PredictionModelBlock,
    endMinute: Int,
    isMmol: Boolean,
    canEditStart: Boolean,
    canDelete: Boolean,
    onEditStart: () -> Unit,
    onDelete: () -> Unit,
    onCarbRatioChange: (Float) -> Unit,
    onSensitivityChange: (Float) -> Unit,
    onCarbAbsorptionChange: (Float) -> Unit
) {
    val sensitivityDisplay = remember(block.insulinSensitivityMgDlPerUnit, isMmol) {
        GlucoseFormatter.displayFromMgDl(block.insulinSensitivityMgDlPerUnit, isMmol)
    }
    val sensitivityValue = if (isMmol) {
        stringResource(R.string.predictive_sensitivity_value_mmol, sensitivityDisplay)
    } else {
        stringResource(R.string.predictive_sensitivity_value_mgdl, block.insulinSensitivityMgDlPerUnit)
    }
    val sensitivityRange = if (isMmol) 0.6f..10f else 10f..180f

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${formatMinuteOfDay(block.startMinuteOfDay)} – ${formatMinuteOfDay(endMinute)}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )
                if (canEditStart) {
                    IconButton(onClick = onEditStart) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = stringResource(R.string.predictive_edit_period_start)
                        )
                    }
                }
                if (canDelete) {
                    IconButton(onClick = onDelete) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = stringResource(R.string.delete),
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.42f))
            PredictiveSimulationParameterRow(
                title = stringResource(R.string.predictive_carb_ratio),
                valueLabel = stringResource(R.string.predictive_carb_ratio_value, block.carbRatioGramsPerUnit),
                value = block.carbRatioGramsPerUnit,
                valueRange = PredictionModelProfile.CARB_RATIO_MIN..PredictionModelProfile.CARB_RATIO_MAX,
                enabled = true,
                onValueChange = onCarbRatioChange
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.42f))
            PredictiveSimulationParameterRow(
                title = stringResource(R.string.predictive_insulin_sensitivity),
                valueLabel = sensitivityValue,
                value = sensitivityDisplay,
                valueRange = sensitivityRange,
                enabled = true,
                onValueChange = { displayValue ->
                    onSensitivityChange(
                        if (isMmol) GlucoseFormatter.mmolToMg(displayValue) else displayValue
                    )
                }
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.42f))
            PredictiveSimulationParameterRow(
                title = stringResource(R.string.predictive_carb_absorption),
                valueLabel = stringResource(
                    R.string.predictive_absorption_value,
                    block.carbAbsorptionGramsPerHour
                ),
                value = block.carbAbsorptionGramsPerHour,
                valueRange = PredictionModelProfile.CARB_ABSORPTION_MIN..PredictionModelProfile.CARB_ABSORPTION_MAX,
                enabled = true,
                onValueChange = onCarbAbsorptionChange
            )
        }
    }
}

@Composable
private fun ProfileTimePickerDialog(
    request: ProfileTimePickerRequest,
    occupiedStarts: Set<Int>,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit
) {
    val state = rememberTimePickerState(
        initialHour = request.initialMinute / PredictionModelProfile.MINUTES_PER_HOUR,
        initialMinute = request.initialMinute % PredictionModelProfile.MINUTES_PER_HOUR,
        is24Hour = true
    )
    val selectedMinute = state.hour * PredictionModelProfile.MINUTES_PER_HOUR + state.minute
    val selectionAvailable = selectedMinute != 0 &&
        (selectedMinute == request.existingStartMinute || selectedMinute !in occupiedStarts)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (request.existingStartMinute == null) {
                        R.string.predictive_add_time_period_title
                    } else {
                        R.string.predictive_edit_time_period_title
                    }
                )
            )
        },
        text = {
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                TimePicker(state = state)
            }
        },
        confirmButton = {
            TextButton(
                enabled = selectionAvailable,
                onClick = { onConfirm(selectedMinute) }
            ) {
                Text(stringResource(R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

private fun formatMinuteOfDay(minuteOfDay: Int): String {
    val minute = minuteOfDay.coerceIn(0, PredictionModelProfile.LAST_MINUTE_OF_DAY)
    return "%02d:%02d".format(
        java.util.Locale.ROOT,
        minute / PredictionModelProfile.MINUTES_PER_HOUR,
        minute % PredictionModelProfile.MINUTES_PER_HOUR
    )
}
