@file:OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)

package tk.glucodata.ui.journal

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.Bloodtype
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DirectionsRun
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Vaccines
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import tk.glucodata.Applic
import tk.glucodata.R
import tk.glucodata.data.journal.JournalChartMarker
import tk.glucodata.data.journal.JournalCurvePoint
import tk.glucodata.data.journal.JournalDoseCalculator
import tk.glucodata.data.journal.JournalDosePresetPolicy
import tk.glucodata.data.journal.JournalEntry
import tk.glucodata.data.journal.JournalEntryInput
import tk.glucodata.data.journal.JournalEntryType
import tk.glucodata.data.journal.JournalFood
import tk.glucodata.data.journal.JournalFoodInput
import tk.glucodata.data.journal.JournalInsulinPreset
import tk.glucodata.data.journal.JournalIntensity
import tk.glucodata.data.prediction.PredictionModelProfile
import tk.glucodata.data.journal.LegacyJournalFoodDatabase
import tk.glucodata.ui.GlucosePoint
import tk.glucodata.ui.components.CompactSheetDragHandle
import tk.glucodata.ui.components.StableModalBottomSheet
import tk.glucodata.ui.util.ConnectedButtonGroup
import tk.glucodata.ui.util.GlucoseFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class JournalEntryDraft(
    val entryId: Long? = null,
    val type: JournalEntryType,
    val timestamp: Long,
    val title: String = "",
    val amountText: String = "",
    val glucoseText: String = "",
    val durationText: String = "",
    val note: String = "",
    val intensity: JournalIntensity? = null,
    val insulinPresetId: Long? = null,
    val foodId: Long? = null,
    val proteinText: String = "",
    val fatText: String = "",
    val chartAnchorGlucoseMgDl: Float? = null,
    val doseGlucoseMgDl: Float? = null,
    val pairWithDose: Boolean = false,
    val pairedAmountText: String = "",
    val foodItems: List<JournalDraftFoodItem> = emptyList()
)

data class JournalDraftFoodItem(
    val sourceFoodId: Long?,
    val displayName: String,
    val carbsGrams: Float,
    val proteinGrams: Float?,
    val fatGrams: Float?,
    val absorptionMinutes: Int,
    val accentColor: Int
)

data class JournalDoseProfile(
    val enabled: Boolean,
    val carbRatioGramsPerUnit: Float,
    val insulinSensitivityMgDlPerUnit: Float,
    val foodMacrosEnabled: Boolean = false,
    val targetMgDl: Float,
    val modelProfile: PredictionModelProfile? = null
) {
    fun at(timestamp: Long): JournalDoseProfile {
        val resolved = modelProfile?.parametersAt(timestamp) ?: return this
        return copy(
            carbRatioGramsPerUnit = resolved.carbRatioGramsPerUnit,
            insulinSensitivityMgDlPerUnit = resolved.insulinSensitivityMgDlPerUnit,
            modelProfile = null
        )
    }
}

private enum class JournalMealShape(val durationMinutes: Int, val labelRes: Int) {
    FAST(45, R.string.journal_meal_shape_fast),
    MIXED(90, R.string.journal_meal_shape_mixed),
    SLOW(180, R.string.journal_meal_shape_slow),
    EXTENDED(240, R.string.journal_meal_shape_extended)
}

fun buildJournalChartMarkers(
    entries: List<JournalEntry>,
    presetsById: Map<Long, JournalInsulinPreset>,
    unit: String,
    foodsById: Map<Long, JournalFood> = emptyMap()
): List<JournalChartMarker> {
    val isMmol = GlucoseFormatter.isMmol(unit)

    return entries.map { entry ->
        val preset = entry.insulinPresetId?.let(presetsById::get)
        val food = entry.foodId?.let(foodsById::get)
        val chartValue = when (entry.type) {
            JournalEntryType.INSULIN -> null
            JournalEntryType.FINGERSTICK -> entry.glucoseValueMgDl?.let {
                GlucoseFormatter.displayFromMgDl(it, isMmol)
            }
            else -> null
        }
        val chartYFraction = if (chartValue == null) {
            when (entry.type) {
                JournalEntryType.CARBS -> entry.amount
                    ?.let { grams -> 0.18f + ((grams / 80f).coerceIn(0f, 1f) * 0.62f) }
                JournalEntryType.ACTIVITY -> when (entry.intensity) {
                    JournalIntensity.INTENSE -> 0.64f
                    JournalIntensity.MODERATE -> 0.52f
                    JournalIntensity.LIGHT, null -> 0.42f
                }
                JournalEntryType.NOTE -> 0.32f
                else -> null
            }
        } else {
            null
        }
        JournalChartMarker(
            entryId = entry.id,
            timestamp = entry.timestamp,
            type = entry.type,
            title = entry.title,
            accentColor = preset?.accentColor ?: food?.accentColor ?: journalTypeColor(entry.type).toArgb(),
            badgeText = journalMarkerBadge(entry.type),
            detailText = journalMarkerDetail(entry, preset, unit),
            amount = entry.amount,
            chartGlucoseValue = chartValue,
            chartYFraction = chartYFraction,
            durationMinutes = entry.durationMinutes,
            curvePoints = if (entry.type == JournalEntryType.INSULIN && preset != null) {
                preset.curvePoints
            } else {
                emptyList()
            },
            activeStartMillis = if (entry.type == JournalEntryType.INSULIN && preset != null) {
                preset.activeStartAt(entry.timestamp)
            } else {
                null
            },
            activeEndMillis = if (entry.type == JournalEntryType.INSULIN && preset != null) {
                preset.activeEndAt(entry.timestamp)
            } else if (entry.type == JournalEntryType.ACTIVITY && entry.durationMinutes != null) {
                entry.timestamp + (entry.durationMinutes.coerceAtLeast(1) * 60_000L)
            } else if (entry.type == JournalEntryType.CARBS && entry.durationMinutes != null) {
                entry.timestamp + (entry.durationMinutes.coerceAtLeast(1) * 60_000L)
            } else {
                null
            }
        )
    }
}

@Composable
fun JournalQuickDock(
    unit: String,
    visibleEntries: List<JournalEntry>,
    insulinPresets: Map<Long, JournalInsulinPreset>,
    modifier: Modifier = Modifier,
    onTypeSelected: (JournalEntryType) -> Unit,
    onEditEntry: (JournalEntry) -> Unit,
    onCalibrate: (() -> Unit)? = null
) {
    val topActions = remember(onCalibrate) {
        buildList {
            if (onCalibrate != null) add(JournalTrayAction.CALIBRATE)
            add(JournalTrayAction.FINGERSTICK)
            add(JournalTrayAction.ACTIVITY)
            add(JournalTrayAction.NOTE)
        }
    }
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        JournalActionRow(
            actions = topActions,
            onAction = { action ->
                when (action) {
                    JournalTrayAction.CALIBRATE -> onCalibrate?.invoke()
                    JournalTrayAction.FINGERSTICK -> onTypeSelected(JournalEntryType.FINGERSTICK)
                    JournalTrayAction.ACTIVITY -> onTypeSelected(JournalEntryType.ACTIVITY)
                    JournalTrayAction.NOTE -> onTypeSelected(JournalEntryType.NOTE)
                    else -> Unit
                }
            }
        )
        JournalActionRow(
            actions = listOf(
                JournalTrayAction.CARBS,
                JournalTrayAction.INSULIN
            ),
            onAction = { action ->
                when (action) {
                    JournalTrayAction.CARBS -> onTypeSelected(JournalEntryType.CARBS)
                    JournalTrayAction.INSULIN -> onTypeSelected(JournalEntryType.INSULIN)
                    else -> Unit
                }
            }
        )

        if (visibleEntries.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                visibleEntries.forEach { entry ->
                    JournalEntryChip(
                        entry = entry,
                        unit = unit,
                        insulinPreset = entry.insulinPresetId?.let(insulinPresets::get),
                        onClick = { onEditEntry(entry) }
                    )
                }
            }
        }
    }
}

@Composable
fun JournalEntrySheet(
    unit: String,
    selectedTimestamp: Long,
    suggestedGlucoseMgDl: Float? = null,
    suggestedChartAnchorGlucoseMgDl: Float? = null,
    suggestedAmountFraction: Float? = null,
    insulinPresets: List<JournalInsulinPreset>,
    foods: List<JournalFood> = emptyList(),
    foodMacrosEnabled: Boolean = false,
    doseJournalEntries: List<JournalEntry> = emptyList(),
    doseProfile: JournalDoseProfile? = null,
    initialType: JournalEntryType,
    existingEntry: JournalEntry? = null,
    onDismiss: () -> Unit,
    onSave: (JournalEntryInput) -> Unit,
    onSaveEntries: ((List<JournalEntryInput>) -> Unit)? = null,
    onSaveFood: ((JournalFoodInput) -> Unit)? = null,
    onDelete: ((Long) -> Unit)? = null,
    sensorSerialProvider: () -> String?
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val context = LocalContext.current
    val activeInsulinPresets = remember(insulinPresets) { insulinPresets.filter { !it.isArchived } }
    val calculationInsulinPresets = remember(activeInsulinPresets) {
        activeInsulinPresets.filter { it.useForCalculation }
    }
    val presetsById = remember(insulinPresets) { insulinPresets.associateBy { it.id } }
    val activeFoods = remember(foods) { foods.filter { !it.isArchived } }
    val initialDraft = remember(
        existingEntry?.id,
        initialType,
        selectedTimestamp,
        suggestedGlucoseMgDl,
        suggestedChartAnchorGlucoseMgDl,
        suggestedAmountFraction,
        unit,
        insulinPresets
    ) {
        buildDraft(
            existingEntry = existingEntry,
            initialType = initialType,
            selectedTimestamp = selectedTimestamp,
            unit = unit,
            suggestedGlucoseMgDl = suggestedGlucoseMgDl,
            suggestedChartAnchorGlucoseMgDl = suggestedChartAnchorGlucoseMgDl,
            suggestedAmountFraction = suggestedAmountFraction
        )
    }
    var draft by remember(
        existingEntry?.id,
        initialType,
        selectedTimestamp,
        suggestedGlucoseMgDl,
        suggestedChartAnchorGlucoseMgDl,
        suggestedAmountFraction,
        unit,
        insulinPresets
    ) {
        mutableStateOf(initialDraft)
    }
    var showDatePicker by remember(existingEntry?.id, initialType, selectedTimestamp) { mutableStateOf(false) }
    var showTimePicker by remember(existingEntry?.id, initialType, selectedTimestamp) { mutableStateOf(false) }
    var noteFieldFocused by remember(existingEntry?.id) { mutableStateOf(false) }
    val selectedInsulinPreset = draft.insulinPresetId?.let(presetsById::get)
    val saveInputs = draft.toInputs(unit, sensorSerialProvider(), presetsById, foodMacrosEnabled)
    val canSave = saveInputs.isNotEmpty()
    val calculatorProfile = remember(doseProfile, draft.timestamp) {
        doseProfile?.at(draft.timestamp)?.takeIf {
            it.enabled &&
                    it.carbRatioGramsPerUnit > 0f &&
                    it.insulinSensitivityMgDlPerUnit > 0f
        }
    }
    val activeInsulinUnits = remember(doseJournalEntries, presetsById, draft.timestamp) {
        JournalDosePresetPolicy.activeInsulinUnitsAt(
            entries = doseJournalEntries,
            presetsById = presetsById,
            atMillis = draft.timestamp
        )
    }
    LaunchedEffect(existingEntry?.id, draft.type, draft.pairWithDose, calculationInsulinPresets) {
        if (
            existingEntry != null ||
            draft.insulinPresetId != null ||
            (draft.type != JournalEntryType.INSULIN && !(draft.type == JournalEntryType.CARBS && draft.pairWithDose))
        ) {
            return@LaunchedEffect
        }
        JournalDosePresetPolicy.preferredPreset(calculationInsulinPresets)?.let { preset ->
            draft = draft.copy(
                insulinPresetId = preset.id,
                title = preset.displayName
            )
        }
    }
    LaunchedEffect(
        draft.type,
        draft.amountText,
        draft.proteinText,
        draft.fatText,
        draft.doseGlucoseMgDl,
        draft.pairWithDose,
        calculatorProfile,
        activeInsulinUnits,
        selectedInsulinPreset?.useForCalculation
    ) {
        if (
            draft.type == JournalEntryType.INSULIN &&
            selectedInsulinPreset?.useForCalculation != true
        ) {
            if (draft.pairWithDose || draft.pairedAmountText.isNotEmpty()) {
                draft = draft.copy(pairWithDose = false, pairedAmountText = "")
            }
            return@LaunchedEffect
        }
        if (!draft.pairWithDose || calculatorProfile == null) return@LaunchedEffect
        val suggestedPair = when (draft.type) {
            JournalEntryType.CARBS -> calculateInsulinForCarbs(
                carbs = draft.amountText.parseFloatOrNull(),
                protein = draft.proteinText.parseFloatOrNull(),
                fat = draft.fatText.parseFloatOrNull(),
                glucoseMgDl = draft.doseGlucoseMgDl,
                profile = calculatorProfile,
                activeInsulinUnits = activeInsulinUnits
            )?.totalInsulinUnits?.let(::formatInsulinDose)

            JournalEntryType.INSULIN -> calculateCoveredCarbsForInsulin(
                insulinUnits = draft.amountText.parseFloatOrNull(),
                glucoseMgDl = draft.doseGlucoseMgDl,
                profile = calculatorProfile,
                activeInsulinUnits = activeInsulinUnits
            )?.let(::formatCarbDose)

            else -> null
        }
        if (suggestedPair != null && suggestedPair != draft.pairedAmountText) {
            draft = draft.copy(pairedAmountText = suggestedPair)
        }
    }

    StableModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        dragHandle = { CompactSheetDragHandle() },
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        contentKey = draft.type,
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .navigationBarsPadding(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 4.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item(key = "header") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(
                                when (draft.type) {
                                    JournalEntryType.FINGERSTICK -> R.string.journal_type_bg_short
                                    JournalEntryType.CARBS -> R.string.journal_type_food
                                    else -> draft.type.labelRes()
                                }
                            ),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        existingEntry?.source?.presentation()?.let { source ->
                            // The icon on the row only explains itself for NFC; here the
                            // origin is spelled out.
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = source.icon,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = stringResource(source.labelRes),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
//                        Text(
//                            text = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
//                                .format(Date(draft.timestamp)),
//                            style = MaterialTheme.typography.bodyMedium,
//                            color = MaterialTheme.colorScheme.onSurfaceVariant
//                        )
                    }
                    existingEntry?.id?.let { entryId ->
                        IconButton(onClick = { onDelete?.invoke(entryId) }) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = stringResource(R.string.delete),
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }

            item(key = "type_selector") {
                JournalTypeSelector(
                    selectedType = draft.type,
                    onTypeSelected = {
                        draft = draft.copy(type = it).normalizedForType(unit, suggestedGlucoseMgDl, suggestedAmountFraction)
                    }
                )
            }

            item(key = "date_time") {
                JournalDateTimeCard(
                    date = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(draft.timestamp)),
                    time = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(draft.timestamp)),
                    onDateClick = { showDatePicker = true },
                    onTimeClick = { showTimePicker = true }
                )
            }

            when (draft.type) {
                JournalEntryType.INSULIN -> {
                    item(key = "insulin_presets") {
                        JournalInsulinPresetSelector(
                            presets = activeInsulinPresets,
                            selectedPresetId = draft.insulinPresetId,
                            onPresetSelected = { preset ->
                                draft = draft.copy(
                                    insulinPresetId = preset.id,
                                    title = preset.displayName,
                                    pairWithDose = draft.pairWithDose && preset.useForCalculation,
                                    pairedAmountText = draft.pairedAmountText.takeIf {
                                        preset.useForCalculation
                                    }.orEmpty()
                                )
                            }
                        )
                    }
                    draft.insulinPresetId?.let { presetId ->
                        presetsById[presetId]?.let { preset ->
                            item(key = "insulin_window") {
                                JournalInsulinWindowCard(preset = preset)
                            }
                        }
                    }
                    item(key = "insulin_amount") {
                        JournalStepperField(
                            value = draft.amountText,
                            onValueChange = { draft = draft.copy(amountText = it) },
                            onStep = { delta ->
                                draft = draft.copy(amountText = adjustDecimalDraft(draft.amountText, delta, step = 0.5f))
                            },
                            label = stringResource(R.string.journal_type_insulin),
                            suffix = "U",
                            prominent = true
                        )
                    }
                    if (
                        existingEntry == null &&
                        calculatorProfile != null &&
                        selectedInsulinPreset?.useForCalculation == true
                    ) {
                        item(key = "insulin_dose_assist") {
                            JournalDoseAssistCard(
                                draft = draft,
                                profile = calculatorProfile,
                                calculationInsulinPresets = calculationInsulinPresets,
                                activeInsulinUnits = activeInsulinUnits,
                                unit = unit,
                                onDraftChange = { draft = it }
                            )
                        }
                    }
                }

                JournalEntryType.CARBS -> {
                    if (activeFoods.isNotEmpty()) {
                        item(key = "carbs_foods") {
                            JournalFoodLibrarySelector(
                                foods = activeFoods,
                                selectedFoodId = draft.foodId,
                                selectedItems = draft.foodItems,
                                foodMacrosEnabled = foodMacrosEnabled,
                                onFoodSelected = { food -> draft = draft.withFood(food, foodMacrosEnabled) },
                                onFoodAdded = { food -> draft = draft.plusFood(food, foodMacrosEnabled) },
                                onFoodRemoved = { index -> draft = draft.removeFoodAt(index, foodMacrosEnabled) },
                                onFoodImported = onSaveFood,
                                onManualSelected = {
                                    draft = draft.copy(
                                        foodId = null,
                                        title = "",
                                        foodItems = emptyList(),
                                        proteinText = if (foodMacrosEnabled) draft.proteinText else "",
                                        fatText = if (foodMacrosEnabled) draft.fatText else ""
                                    )
                                }
                            )
                        }
                    }
                    item(key = "carbs_amount") {
                        JournalStepperField(
                            value = draft.amountText,
                            onValueChange = { draft = draft.copy(amountText = it) },
                            onStep = { delta ->
                                draft = draft.copy(amountText = adjustDecimalDraft(draft.amountText, delta, step = 5f))
                            },
                            label = stringResource(R.string.journal_type_food),
                            suffix = "g",
                            prominent = true
                        )
                    }
                    item(key = "carbs_meal_shape") {
                        JournalMealShapeSelector(
                            durationText = draft.durationText,
                            onShapeSelected = { shape ->
                                draft = draft.copy(durationText = shape.durationMinutes.toString())
                            }
                        )
                    }
                    if (foodMacrosEnabled) {
                        item(key = "carbs_macros") {
                            JournalMacroFields(
                                proteinText = draft.proteinText,
                                fatText = draft.fatText,
                                onProteinChange = { draft = draft.copy(proteinText = it) },
                                onFatChange = { draft = draft.copy(fatText = it) }
                            )
                        }
                    }
                    if (
                        existingEntry == null &&
                        calculatorProfile != null &&
                        calculationInsulinPresets.isNotEmpty()
                    ) {
                        item(key = "carbs_dose_assist") {
                            JournalDoseAssistCard(
                                draft = draft,
                                profile = calculatorProfile,
                                calculationInsulinPresets = calculationInsulinPresets,
                                activeInsulinUnits = activeInsulinUnits,
                                unit = unit,
                                onDraftChange = { draft = it }
                            )
                        }
                    }
                }

                JournalEntryType.FINGERSTICK -> {
                    item(key = "fingerstick_amount") {
                        JournalStepperField(
                            value = draft.glucoseText,
                            onValueChange = { draft = draft.copy(glucoseText = it) },
                            onStep = { delta ->
                                val step = if (GlucoseFormatter.isMmol(unit)) 0.1f else 5f
                                draft = draft.copy(glucoseText = adjustDecimalDraft(draft.glucoseText, delta, step))
                            },
                            label = stringResource(R.string.glucose_with_unit, unit),
                            suffix = null,
                            prominent = true
                        )
                    }
                }

                JournalEntryType.ACTIVITY -> {
                    item(key = "activity_name") {
                        OutlinedTextField(
                            value = draft.title,
                            onValueChange = { draft = draft.copy(title = it) },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(text = stringResource(R.string.name).trimTrailingLabel()) },
                            singleLine = true
                        )
                    }
                    item(key = "activity_duration") {
                        JournalStepperField(
                            value = draft.durationText,
                            onValueChange = { draft = draft.copy(durationText = it.filter(Char::isDigit)) },
                            onStep = { delta ->
                                draft = draft.copy(durationText = adjustIntegerDraft(draft.durationText, delta * 5, minValue = 0))
                            },
                            label = stringResource(R.string.duration_label),
                            suffix = stringResource(R.string.minutes),
                            keyboardType = KeyboardType.Number
                        )
                    }
                    item(key = "activity_intensity") {
                        JournalIntensitySelector(
                            selectedIntensity = draft.intensity,
                            onIntensitySelected = { draft = draft.copy(intensity = it) }
                        )
                    }
                }

                JournalEntryType.NOTE -> {
                    item(key = "note_title") {
                        OutlinedTextField(
                            value = draft.title,
                            onValueChange = { draft = draft.copy(title = it) },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(text = stringResource(R.string.name).trimTrailingLabel()) },
                            singleLine = true
                        )
                    }
                }
            }

            item(key = "note_field") {
                val noteFieldExpanded = draft.type == JournalEntryType.NOTE ||
                        noteFieldFocused ||
                        draft.note.contains('\n')
                val noteFieldMinHeight by animateDpAsState(
                    targetValue = when {
                        draft.type == JournalEntryType.NOTE -> 132.dp
                        noteFieldExpanded -> 96.dp
                        else -> 56.dp
                    },
                    animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                    label = "journalNoteFieldHeight"
                )
                TextField(
                    value = draft.note,
                    onValueChange = { draft = draft.copy(note = it) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = noteFieldMinHeight)
                        .onFocusChanged { noteFieldFocused = it.isFocused },
                    label = { Text(stringResource(R.string.journal_note_label)) },
                    singleLine = !noteFieldExpanded,
                    maxLines = when {
                        draft.type == JournalEntryType.NOTE -> 5
                        noteFieldExpanded -> 3
                        else -> 1
                    },
                    shape = RoundedCornerShape(18.dp),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        disabledIndicatorColor = Color.Transparent,
                        errorIndicatorColor = Color.Transparent
                    )
                )
            }

            item(key = "save_button") {
                Button(
                    onClick = {
                        if (canSave) {
                            if (saveInputs.size > 1) {
                                onSaveEntries?.invoke(saveInputs) ?: saveInputs.forEach(onSave)
                            } else {
                                saveInputs.firstOrNull()?.let(onSave)
                            }
                        }
                    },
                    enabled = canSave,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Icon(
                        imageVector = journalTypeIcon(draft.type),
                        contentDescription = null
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(text = stringResource(R.string.save))
                }
            }
        }
    }

    if (showDatePicker) {
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = journalTimestampToPickerUtcDateMillis(draft.timestamp)
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    datePickerState.selectedDateMillis?.let { selected ->
                        draft = draft.copy(timestamp = mergeJournalDate(draft.timestamp, selected))
                    }
                    showDatePicker = false
                }) {
                    Text(text = stringResource(R.string.ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) {
                    Text(text = stringResource(R.string.cancel))
                }
            }
        ) {
            DatePicker(state = datePickerState)
        }
    }

    if (showTimePicker) {
        val calendar = remember(draft.timestamp) {
            Calendar.getInstance().apply { timeInMillis = draft.timestamp }
        }
        val timePickerState = rememberTimePickerState(
            initialHour = calendar.get(Calendar.HOUR_OF_DAY),
            initialMinute = calendar.get(Calendar.MINUTE),
            is24Hour = android.text.format.DateFormat.is24HourFormat(context)
        )
        AlertDialog(
            onDismissRequest = { showTimePicker = false },
            title = { Text(text = stringResource(R.string.time)) },
            text = {
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    TimePicker(state = timePickerState)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    draft = draft.copy(
                        timestamp = mergeJournalTime(
                            draft.timestamp,
                            timePickerState.hour to timePickerState.minute
                        )
                    )
                    showTimePicker = false
                }) {
                    Text(text = stringResource(R.string.ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { showTimePicker = false }) {
                    Text(text = stringResource(R.string.cancel))
                }
            }
        )
    }
}

private fun buildDraft(
    existingEntry: JournalEntry?,
    initialType: JournalEntryType,
    selectedTimestamp: Long,
    unit: String,
    suggestedGlucoseMgDl: Float? = null,
    suggestedChartAnchorGlucoseMgDl: Float? = null,
    suggestedAmountFraction: Float? = null
): JournalEntryDraft {
    if (existingEntry == null) {
        return JournalEntryDraft(
            type = initialType,
            timestamp = selectedTimestamp,
            title = if (initialType == JournalEntryType.ACTIVITY) Applic.app.getString(R.string.journal_type_activity) else "",
            amountText = when (initialType) {
                JournalEntryType.INSULIN -> suggestedAmountFraction?.let(::suggestedInsulinAmountForFraction).orEmpty()
                JournalEntryType.CARBS -> suggestedAmountFraction?.let(::suggestedCarbAmountForFraction).orEmpty()
                else -> ""
            },
            glucoseText = if (initialType == JournalEntryType.FINGERSTICK) {
                suggestedGlucoseMgDl?.let { formatGlucoseForEditor(it, unit) }.orEmpty()
            } else {
                ""
            },
            durationText = if (initialType == JournalEntryType.CARBS) {
                JournalMealShape.MIXED.durationMinutes.toString()
            } else {
                ""
            },
            chartAnchorGlucoseMgDl = suggestedChartAnchorGlucoseMgDl,
            doseGlucoseMgDl = suggestedGlucoseMgDl
        )
    }
    return JournalEntryDraft(
        entryId = existingEntry.id,
        type = existingEntry.type,
        timestamp = existingEntry.timestamp,
        title = existingEntry.title,
        amountText = existingEntry.amount?.let(::formatFloatForEditor).orEmpty(),
        glucoseText = existingEntry.glucoseValueMgDl?.let { formatGlucoseForEditor(it, unit) }.orEmpty(),
        durationText = existingEntry.durationMinutes?.toString().orEmpty(),
        note = existingEntry.note.orEmpty(),
        intensity = existingEntry.intensity,
        insulinPresetId = existingEntry.insulinPresetId,
        foodId = existingEntry.foodId,
        proteinText = existingEntry.proteinGrams?.let(::formatFloatForEditor).orEmpty(),
        fatText = existingEntry.fatGrams?.let(::formatFloatForEditor).orEmpty(),
        chartAnchorGlucoseMgDl = existingEntry.glucoseValueMgDl,
        doseGlucoseMgDl = existingEntry.glucoseValueMgDl
    )
}

private fun JournalEntryDraft.withFood(
    food: JournalFood,
    foodMacrosEnabled: Boolean
): JournalEntryDraft {
    val item = food.toDraftFoodItem()
    return copy(
        foodId = food.id.takeIf { it > 0L },
        title = food.displayName,
        amountText = formatFloatForEditor(item.carbsGrams),
        proteinText = if (foodMacrosEnabled) item.proteinGrams?.let(::formatFloatForEditor).orEmpty() else "",
        fatText = if (foodMacrosEnabled) item.fatGrams?.let(::formatFloatForEditor).orEmpty() else "",
        durationText = item.absorptionMinutes.toString(),
        foodItems = listOf(item)
    )
}

private fun JournalEntryDraft.plusFood(
    food: JournalFood,
    foodMacrosEnabled: Boolean
): JournalEntryDraft {
    val items = foodItems + food.toDraftFoodItem()
    return copyWithFoodItems(items, foodMacrosEnabled)
}

private fun JournalEntryDraft.removeFoodAt(
    index: Int,
    foodMacrosEnabled: Boolean
): JournalEntryDraft {
    if (index !in foodItems.indices) return this
    return copyWithFoodItems(foodItems.filterIndexed { itemIndex, _ -> itemIndex != index }, foodMacrosEnabled)
}

private fun JournalEntryDraft.copyWithFoodItems(
    items: List<JournalDraftFoodItem>,
    foodMacrosEnabled: Boolean
): JournalEntryDraft {
    if (items.isEmpty()) {
        return copy(
            foodId = null,
            title = "",
            amountText = "",
            proteinText = "",
            fatText = "",
            durationText = JournalMealShape.MIXED.durationMinutes.toString(),
            foodItems = emptyList()
        )
    }
    val carbs = items.sumOf { it.carbsGrams.toDouble() }.toFloat()
    val protein = items.sumOf { (it.proteinGrams ?: 0f).toDouble() }.toFloat()
    val fat = items.sumOf { (it.fatGrams ?: 0f).toDouble() }.toFloat()
    val duration = items.maxOf { it.absorptionMinutes }
    return copy(
        foodId = items.singleOrNull()?.sourceFoodId,
        title = items.joinToString(" + ") { it.displayName }.take(80),
        amountText = formatFloatForEditor(carbs),
        proteinText = if (foodMacrosEnabled) formatFloatForEditor(protein) else "",
        fatText = if (foodMacrosEnabled) formatFloatForEditor(fat) else "",
        durationText = duration.coerceIn(15, 480).toString(),
        foodItems = items
    )
}

private fun JournalFood.toDraftFoodItem(): JournalDraftFoodItem {
    return JournalDraftFoodItem(
        sourceFoodId = id.takeIf { it > 0L },
        displayName = displayName,
        carbsGrams = carbsGrams,
        proteinGrams = proteinGrams,
        fatGrams = fatGrams,
        absorptionMinutes = absorptionMinutes,
        accentColor = accentColor
    )
}

private fun JournalFood.toLibraryFoodInput(sortOrder: Int): JournalFoodInput {
    return JournalFoodInput(
        displayName = displayName,
        carbsGrams = carbsGrams,
        proteinGrams = proteinGrams,
        fatGrams = fatGrams,
        absorptionMinutes = absorptionMinutes,
        accentColor = accentColor,
        isBuiltIn = false,
        isArchived = false,
        sortOrder = sortOrder
    )
}

private fun JournalFood.scaledForPortion(portionGrams: Float): JournalFood {
    val scale = (portionGrams / 100f).coerceIn(0.01f, 20f)
    return copy(
        carbsGrams = carbsGrams * scale,
        proteinGrams = proteinGrams?.let { it * scale },
        fatGrams = fatGrams?.let { it * scale }
    )
}

private fun JournalEntryDraft.normalizedForType(
    unit: String,
    suggestedGlucoseMgDl: Float? = null,
    suggestedAmountFraction: Float? = null
): JournalEntryDraft {
    return when (type) {
        JournalEntryType.INSULIN -> copy(
            amountText = amountText.ifBlank {
                suggestedAmountFraction?.let(::suggestedInsulinAmountForFraction).orEmpty()
            },
            glucoseText = "",
            durationText = "",
            intensity = null,
            foodId = null,
            proteinText = "",
            fatText = "",
            foodItems = emptyList(),
            pairWithDose = false,
            pairedAmountText = ""
        )

        JournalEntryType.CARBS -> copy(
            title = "",
            amountText = amountText.ifBlank {
                suggestedAmountFraction?.let(::suggestedCarbAmountForFraction).orEmpty()
            },
            glucoseText = "",
            durationText = durationText.ifBlank { JournalMealShape.MIXED.durationMinutes.toString() },
            intensity = null,
            insulinPresetId = null,
            proteinText = proteinText,
            fatText = fatText,
            pairWithDose = false,
            pairedAmountText = ""
        )

        JournalEntryType.FINGERSTICK -> copy(
            title = "",
            amountText = "",
            durationText = "",
            intensity = null,
            insulinPresetId = null,
            foodId = null,
            proteinText = "",
            fatText = "",
            foodItems = emptyList(),
            pairWithDose = false,
            pairedAmountText = "",
            glucoseText = glucoseText.ifBlank {
                suggestedGlucoseMgDl?.let { formatGlucoseForEditor(it, unit) }
                    ?: if (GlucoseFormatter.isMmol(unit)) "5.6" else "100"
            }
        )

        JournalEntryType.ACTIVITY -> copy(
            amountText = "",
            glucoseText = "",
            insulinPresetId = null,
            foodId = null,
            proteinText = "",
            fatText = "",
            foodItems = emptyList(),
            pairWithDose = false,
            pairedAmountText = ""
        )

        JournalEntryType.NOTE -> copy(
            amountText = "",
            glucoseText = "",
            durationText = "",
            intensity = null,
            insulinPresetId = null,
            foodId = null,
            proteinText = "",
            fatText = "",
            foodItems = emptyList(),
            pairWithDose = false,
            pairedAmountText = ""
        )
    }
}

private fun JournalEntryDraft.toInput(
    unit: String,
    sensorSerial: String?,
    presetsById: Map<Long, JournalInsulinPreset>,
    foodMacrosEnabled: Boolean
): JournalEntryInput? {
    val noteValue = note.trim().takeIf { it.isNotBlank() }
    return when (type) {
        JournalEntryType.INSULIN -> {
            val presetId = insulinPresetId ?: return null
            val preset = presetsById[presetId] ?: return null
            val amountValue = amountText.parseFloatOrNull() ?: return null
            JournalEntryInput(
                id = entryId,
                timestamp = timestamp,
                sensorSerial = sensorSerial,
                type = type,
                title = preset.displayName,
                note = noteValue,
                amount = amountValue,
                glucoseValueMgDl = chartAnchorGlucoseMgDl,
                insulinPresetId = presetId
            )
        }

        JournalEntryType.CARBS -> {
            val grams = amountText.parseFloatOrNull() ?: return null
            val absorptionMinutes = durationText.parseIntOrNull()?.coerceIn(15, 480)
            val titleValue = title.trim().takeIf { it.isNotBlank() }
                ?: Applic.app.getString(R.string.journal_type_food)
            JournalEntryInput(
                id = entryId,
                timestamp = timestamp,
                sensorSerial = sensorSerial,
                type = type,
                title = titleValue,
                note = noteValue,
                amount = grams,
                glucoseValueMgDl = chartAnchorGlucoseMgDl,
                durationMinutes = absorptionMinutes,
                foodId = foodId?.takeIf { it > 0L },
                proteinGrams = if (foodMacrosEnabled) proteinText.parseFloatOrNull()?.coerceAtLeast(0f) else null,
                fatGrams = if (foodMacrosEnabled) fatText.parseFloatOrNull()?.coerceAtLeast(0f) else null
            )
        }

        JournalEntryType.FINGERSTICK -> {
            val glucoseMgDl = parseGlucoseToMgDl(glucoseText, unit) ?: return null
            JournalEntryInput(
                id = entryId,
                timestamp = timestamp,
                sensorSerial = sensorSerial,
                type = type,
                title = Applic.app.getString(R.string.journal_type_fingerstick),
                note = noteValue,
                glucoseValueMgDl = glucoseMgDl
            )
        }

        JournalEntryType.ACTIVITY -> {
            val duration = durationText.parseIntOrNull() ?: return null
            val titleValue = title.trim().takeIf { it.isNotBlank() } ?: return null
            JournalEntryInput(
                id = entryId,
                timestamp = timestamp,
                sensorSerial = sensorSerial,
                type = type,
                title = titleValue,
                note = noteValue,
                durationMinutes = duration,
                intensity = intensity,
                glucoseValueMgDl = chartAnchorGlucoseMgDl
            )
        }

        JournalEntryType.NOTE -> {
            val titleValue = title.trim().takeIf { it.isNotBlank() }
                ?: noteValue?.take(30)
                ?: return null
            JournalEntryInput(
                id = entryId,
                timestamp = timestamp,
                sensorSerial = sensorSerial,
                type = type,
                title = titleValue,
                note = noteValue ?: titleValue,
                glucoseValueMgDl = chartAnchorGlucoseMgDl
            )
        }
    }
}

private fun JournalEntryDraft.toInputs(
    unit: String,
    sensorSerial: String?,
    presetsById: Map<Long, JournalInsulinPreset>,
    foodMacrosEnabled: Boolean
): List<JournalEntryInput> {
    val primary = toInput(unit, sensorSerial, presetsById, foodMacrosEnabled) ?: return emptyList()
    if (!pairWithDose || entryId != null || type !in setOf(JournalEntryType.INSULIN, JournalEntryType.CARBS)) {
        return listOf(primary)
    }
    val paired = when (type) {
        JournalEntryType.CARBS -> {
            val presetId = insulinPresetId ?: return emptyList()
            val preset = presetsById[presetId] ?: return emptyList()
            val amountValue = pairedAmountText.parseFloatOrNull()?.takeIf { it > 0f } ?: return emptyList()
            JournalEntryInput(
                timestamp = timestamp,
                sensorSerial = sensorSerial,
                type = JournalEntryType.INSULIN,
                title = preset.displayName,
                amount = amountValue,
                glucoseValueMgDl = chartAnchorGlucoseMgDl,
                insulinPresetId = presetId
            )
        }

        JournalEntryType.INSULIN -> {
            val grams = pairedAmountText.parseFloatOrNull()?.takeIf { it > 0f } ?: return emptyList()
            JournalEntryInput(
                timestamp = timestamp,
                sensorSerial = sensorSerial,
                type = JournalEntryType.CARBS,
                title = Applic.app.getString(R.string.journal_type_food),
                amount = grams,
                glucoseValueMgDl = chartAnchorGlucoseMgDl,
                durationMinutes = durationText.parseIntOrNull()?.coerceIn(15, 480),
                proteinGrams = null,
                fatGrams = null,
                foodId = null
            )
        }

        else -> null
    } ?: return listOf(primary)
    return listOf(primary, paired)
}

@Composable
private fun JournalDoseAssistCard(
    draft: JournalEntryDraft,
    profile: JournalDoseProfile,
    calculationInsulinPresets: List<JournalInsulinPreset>,
    activeInsulinUnits: Float,
    unit: String,
    onDraftChange: (JournalEntryDraft) -> Unit
) {
    val insulinSuggestion = remember(
        draft.amountText,
        draft.proteinText,
        draft.fatText,
        draft.doseGlucoseMgDl,
        profile,
        activeInsulinUnits
    ) {
        calculateInsulinForCarbs(
            carbs = draft.amountText.parseFloatOrNull(),
            protein = draft.proteinText.parseFloatOrNull(),
            fat = draft.fatText.parseFloatOrNull(),
            glucoseMgDl = draft.doseGlucoseMgDl,
            profile = profile,
            activeInsulinUnits = activeInsulinUnits
        )
    }
    val coveredCarbsSuggestion = remember(draft.amountText, draft.doseGlucoseMgDl, profile, activeInsulinUnits) {
        calculateCoveredCarbsForInsulin(
            insulinUnits = draft.amountText.parseFloatOrNull(),
            glucoseMgDl = draft.doseGlucoseMgDl,
            profile = profile,
            activeInsulinUnits = activeInsulinUnits
        )
    }
    val pairLabel = when (draft.type) {
        JournalEntryType.CARBS -> stringResource(R.string.journal_dose_pair_insulin)
        JournalEntryType.INSULIN -> stringResource(R.string.journal_dose_pair_carbs)
        else -> ""
    }
    val pairAmount = when (draft.type) {
        JournalEntryType.CARBS -> insulinSuggestion?.totalInsulinUnits?.let(::formatInsulinDose)
        JournalEntryType.INSULIN -> coveredCarbsSuggestion?.let(::formatCarbDose)
        else -> null
    }
    val pairEnabled = pairAmount != null
    val pairOnClick: () -> Unit = {
        val enabled = !draft.pairWithDose
        onDraftChange(
            draft.copy(
                pairWithDose = enabled,
                pairedAmountText = if (enabled) pairAmount.orEmpty() else "",
                insulinPresetId = when {
                    draft.type == JournalEntryType.CARBS && enabled -> draft.insulinPresetId
                        ?: JournalDosePresetPolicy.preferredPreset(calculationInsulinPresets)?.id
                    draft.type == JournalEntryType.CARBS -> null
                    else -> draft.insulinPresetId
                }
            )
        )
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.68f),
        shape = RoundedCornerShape(22.dp)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.journal_dose_math_title),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = stringResource(R.string.predictive_carb_ratio_value, profile.carbRatioGramsPerUnit),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            when (draft.type) {
                JournalEntryType.CARBS -> {
                    val suggestion = insulinSuggestion
                    Text(
                        text = stringResource(
                            R.string.journal_dose_suggested_insulin,
                            suggestion?.totalInsulinUnits?.let(::formatInsulinDose) ?: "—"
                        ),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (suggestion != null) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        }
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        JournalDoseMetric(
                            label = stringResource(
                                R.string.journal_dose_food_component,
                                "${suggestion?.foodInsulinUnits?.let(::formatInsulinComponent) ?: "—"} U"
                            )
                        )
                        if (suggestion != null && suggestion.correctionInsulinUnits > 0f) {
                            JournalDoseMetric(
                                label = stringResource(
                                    R.string.journal_dose_correction_component,
                                    "${formatInsulinComponent(suggestion.correctionInsulinUnits)} U"
                                )
                            )
                        }
                        if (suggestion != null && suggestion.activeInsulinCreditUnits > 0f) {
                            JournalDoseMetric(
                                label = "${stringResource(R.string.IOB)} -" +
                                        "${formatInsulinComponent(suggestion.activeInsulinCreditUnits)} U"
                            )
                        }
                        draft.doseGlucoseMgDl?.let { glucose ->
                            JournalDoseMetric(label = formatGlucoseForEditor(glucose, unit))
                        }
                    }
                }

                JournalEntryType.INSULIN -> {
                    Text(
                        text = stringResource(
                            R.string.journal_dose_covers_carbs,
                            coveredCarbsSuggestion?.let(::formatCarbDose) ?: "—"
                        ),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (coveredCarbsSuggestion != null) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        }
                    )
                }

                else -> Unit
            }

            FilledTonalButton(
                onClick = pairOnClick,
                enabled = pairEnabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 42.dp),
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = if (draft.pairWithDose) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHighest
                    },
                    contentColor = if (draft.pairWithDose) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            ) {
                Icon(
                    imageVector = if (draft.type == JournalEntryType.CARBS) Icons.Default.Vaccines else Icons.Default.Restaurant,
                    contentDescription = null
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = pairLabel)
            }

            if (draft.pairWithDose) {
                if (draft.type == JournalEntryType.CARBS) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        calculationInsulinPresets.forEach { preset ->
                            JournalPresetPill(
                                preset = preset,
                                selected = draft.insulinPresetId == preset.id,
                                onClick = {
                                    onDraftChange(
                                        draft.copy(
                                            insulinPresetId = preset.id,
                                            title = if (draft.type == JournalEntryType.INSULIN) preset.displayName else draft.title
                                        )
                                    )
                                }
                            )
                        }
                    }
                }
                JournalStepperField(
                    value = draft.pairedAmountText,
                    onValueChange = { onDraftChange(draft.copy(pairedAmountText = it)) },
                    onStep = { delta ->
                        val step = if (draft.type == JournalEntryType.CARBS) 0.5f else 5f
                        onDraftChange(
                            draft.copy(
                                pairedAmountText = adjustDecimalDraft(draft.pairedAmountText, delta, step)
                            )
                        )
                    },
                    label = if (draft.type == JournalEntryType.CARBS) {
                        stringResource(R.string.journal_type_insulin)
                    } else {
                        stringResource(R.string.journal_type_food)
                    },
                    suffix = if (draft.type == JournalEntryType.CARBS) "U" else "g"
                )
            }
        }
    }
}

@Composable
private fun JournalMealShapeSelector(
    durationText: String,
    onShapeSelected: (JournalMealShape) -> Unit
) {
    val selected = remember(durationText) {
        val minutes = durationText.parseIntOrNull()
        if (minutes == null) {
            JournalMealShape.MIXED
        } else {
            JournalMealShape.entries.minBy { abs(it.durationMinutes - minutes) }
        }
    }
    val labels = mapOf(
        JournalMealShape.FAST to stringResource(R.string.journal_meal_shape_fast),
        JournalMealShape.MIXED to stringResource(R.string.journal_meal_shape_mixed),
        JournalMealShape.SLOW to stringResource(R.string.journal_meal_shape_slow),
        JournalMealShape.EXTENDED to stringResource(R.string.journal_meal_shape_extended)
    )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
//        Text(
//            text = stringResource(R.string.journal_meal_shape),
//            style = MaterialTheme.typography.labelLarge,
//            fontWeight = FontWeight.SemiBold,
//            color = MaterialTheme.colorScheme.onSurfaceVariant
//        )
        ConnectedButtonGroup(
            options = JournalMealShape.entries.toList(),
            selectedOption = selected,
            onOptionSelected = onShapeSelected,
            label = { },
            labelText = { labels[it].orEmpty() },
            modifier = Modifier.fillMaxWidth(),
            itemHeight = 44.dp,
            selectedContainerColor = journalTypeSelectedContainerColor(
                JournalEntryType.CARBS,
                MaterialTheme.colorScheme.surfaceContainerHigh
            ),
            selectedContentColor = MaterialTheme.colorScheme.onSurface,
            unselectedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.78f),
            unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun JournalFoodLibrarySelector(
    foods: List<JournalFood>,
    selectedFoodId: Long?,
    selectedItems: List<JournalDraftFoodItem>,
    foodMacrosEnabled: Boolean,
    onFoodSelected: (JournalFood) -> Unit,
    onFoodAdded: (JournalFood) -> Unit,
    onFoodRemoved: (Int) -> Unit,
    onFoodImported: ((JournalFoodInput) -> Unit)? = null,
    onManualSelected: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var legacyFoods by remember { mutableStateOf(emptyList<JournalFood>()) }
    var expandedFoodId by remember { mutableStateOf<Long?>(null) }
    var expandedFoodDetails by remember { mutableStateOf<LegacyJournalFoodDatabase.FoodDetails?>(null) }
    var expandedPortionText by remember { mutableStateOf("100") }
    val haptics = LocalHapticFeedback.current
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val selectedFood = remember(foods, selectedFoodId) { foods.firstOrNull { it.id == selectedFoodId } }
    val visibleItems = remember(selectedItems, selectedFood) {
        selectedItems.ifEmpty { selectedFood?.toDraftFoodItem()?.let(::listOf).orEmpty() }
    }
    LaunchedEffect(query) {
        val needle = query.trim()
        legacyFoods = if (needle.length >= 2) {
            withContext(Dispatchers.Default) {
                LegacyJournalFoodDatabase.search(needle, limit = 32)
            }
        } else {
            emptyList()
        }
    }
    LaunchedEffect(expanded) {
        if (expanded) {
            focusRequester.requestFocus()
            keyboardController?.show()
        } else {
            keyboardController?.hide()
        }
    }
    val filteredFoods = remember(foods, legacyFoods, query) {
        val needle = query.trim()
        val savedMatches = foods.matchFoodQuery(needle)
        val savedNames = savedMatches.map { it.displayName.lowercase(Locale.ROOT) }.toSet()
        (savedMatches + legacyFoods.filter { it.displayName.lowercase(Locale.ROOT) !in savedNames })
            .take(if (needle.isBlank()) 10 else 36)
    }
    val savedFoodNames = remember(foods) {
        foods.map { it.displayName.lowercase(Locale.ROOT) }.toSet()
    }
    LaunchedEffect(expandedFoodId) {
        if (expandedFoodId != null) {
            expandedPortionText = "100"
        }
    }
    LaunchedEffect(expandedFoodId, filteredFoods) {
        val food = filteredFoods.firstOrNull { it.id == expandedFoodId }
        expandedFoodDetails = null
        if (food != null) {
            expandedFoodDetails = withContext(Dispatchers.Default) {
                LegacyJournalFoodDatabase.details(food)
            }
        }
    }

    val containerShape = RoundedCornerShape(if (expanded) 32.dp else 28.dp)
    val hasFoodSelection = visibleItems.isNotEmpty()
    val containerColor = when {
        expanded && !hasFoodSelection -> MaterialTheme.colorScheme.surfaceContainerLow
        hasFoodSelection -> visibleItems.first().accentColor
            .let { Color(it).copy(alpha = if (expanded) 0.12f else 0.14f) }
        else -> MaterialTheme.colorScheme.surfaceContainerHigh
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(containerShape)
            .animateContentSize(
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMediumLow
                )
            ),
        color = containerColor,
        shape = containerShape
    ) {
        if (!expanded) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 72.dp)
                    .clip(containerShape)
                    .clickable {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        expanded = true
                    }
                    .padding(horizontal = 18.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Restaurant,
                    contentDescription = null,
                    tint = visibleItems.firstOrNull()?.accentColor?.let(::Color)
                        ?: MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(28.dp)
                )
                Spacer(modifier = Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = visibleItems.singleOrNull()?.displayName ?: stringResource(R.string.journal_add_food),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = if (visibleItems.isEmpty()) {
                            stringResource(R.string.journal_food_choose_desc)
                        } else {
                            foodItemsSummary(visibleItems, foodMacrosEnabled)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp)
                )
            }
        } else {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.84f),
                    shape = RoundedCornerShape(28.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 62.dp)
                            .padding(start = 14.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = null,
                            tint = visibleItems.firstOrNull()?.accentColor?.let(::Color)
                                ?: MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(26.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        BasicTextField(
                            value = query,
                            onValueChange = { query = it },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.onSurface),
                            textStyle = MaterialTheme.typography.titleLarge.copy(
                                color = MaterialTheme.colorScheme.onSurface
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .focusRequester(focusRequester),
                            decorationBox = { innerTextField ->
                                Box(contentAlignment = Alignment.CenterStart) {
                                    if (query.isBlank()) {
                                        Text(
                                            text = stringResource(R.string.journal_food_search),
                                            style = MaterialTheme.typography.titleLarge,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    innerTextField()
                                }
                            }
                        )
                        AnimatedVisibility(
                            visible = query.isNotBlank(),
                            enter = fadeIn(),
                            exit = fadeOut()
                        ) {
                            IconButton(
                                onClick = {
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    query = ""
                                },
                                modifier = Modifier.size(42.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = stringResource(R.string.clear),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(19.dp)
                                )
                            }
                        }
                        TextButton(
                            onClick = {
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                query = ""
                                expanded = false
                            },
                            shape = RoundedCornerShape(22.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.close),
                                style = MaterialTheme.typography.labelLarge
                            )
                        }
                    }
                }

                if (visibleItems.isNotEmpty()) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        visibleItems.forEachIndexed { index, item ->
                            JournalSelectedFoodChip(
                                item = item,
                                foodMacrosEnabled = foodMacrosEnabled,
                                onRemove = {
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    if (selectedItems.isEmpty() && selectedFood != null) {
                                        onManualSelected()
                                    } else {
                                        onFoodRemoved(index)
                                    }
                                }
                            )
                        }
                    }
                }

                AnimatedVisibility(
                    visible = expanded,
                    enter = expandVertically(
                        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                        expandFrom = Alignment.Top
                    ) + fadeIn(),
                    exit = shrinkVertically(
                        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                        shrinkTowards = Alignment.Top
                    ) + fadeOut()
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        filteredFoods.forEach { food ->
                            val rowExpanded = expandedFoodId == food.id
                            val rowPortionGrams = parseFoodPortionGrams(expandedPortionText)
                            val canSaveToLibrary = onFoodImported != null &&
                                    LegacyJournalFoodDatabase.isLegacyFood(food) &&
                                    food.displayName.lowercase(Locale.ROOT) !in savedFoodNames
                            JournalFoodPickerRow(
                                title = food.displayName,
                                subtitle = foodMacroSummary(food, foodMacrosEnabled),
                                color = Color(food.accentColor),
                                selected = selectedFoodId == food.id || selectedItems.any { it.sourceFoodId == food.id },
                                expanded = rowExpanded,
                                onClick = {
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    onFoodSelected(food)
                                    expandedFoodId = null
                                    expanded = false
                                },
                                onLongClick = {
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    expandedFoodId = if (rowExpanded) {
                                        null
                                    } else {
                                        expandedPortionText = "100"
                                        food.id
                                    }
                                },
                                unselectedContainerColor = if (hasFoodSelection) {
                                    MaterialTheme.colorScheme.surfaceContainerHigh
                                } else {
                                    MaterialTheme.colorScheme.surfaceContainerHighest
                                },
                                detailsContent = {
                                    JournalFoodCompositionDetails(
                                        details = expandedFoodDetails,
                                        color = Color(food.accentColor),
                                        portionText = expandedPortionText,
                                        onPortionTextChange = { expandedPortionText = it },
                                        onSaveToLibrary = if (canSaveToLibrary) {
                                            {
                                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                onFoodImported?.invoke(food.toLibraryFoodInput(sortOrder = foods.size + 100))
                                                expandedFoodId = null
                                            }
                                        } else {
                                            null
                                        }
                                    )
                                },
                                trailingContent = {
                                    FilledTonalIconButton(
                                        onClick = {
                                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            onFoodAdded(
                                                if (rowExpanded) {
                                                    food.scaledForPortion(rowPortionGrams)
                                                } else {
                                                    food
                                                }
                                            )
                                            if (rowExpanded) {
                                                expandedFoodId = null
                                            }
                                        },
                                        modifier = Modifier.size(40.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Add,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun List<JournalFood>.matchFoodQuery(query: String): List<JournalFood> {
    val needle = query.trim()
    if (needle.isBlank()) return this
    val normalizedNeedle = needle.lowercase(Locale.ROOT)
    val terms = normalizedNeedle
        .split(Regex("\\s+"))
        .filter { it.length >= 2 }
        .distinct()
    return mapNotNull { food ->
        val name = food.displayName.lowercase(Locale.ROOT)
        val allTermsMatch = terms.all { name.contains(it) }
        if (!allTermsMatch) return@mapNotNull null
        val score = when {
            name == normalizedNeedle -> 0
            name.startsWith(normalizedNeedle) -> 1
            name.contains(normalizedNeedle) -> 2
            else -> 3
        }
        RankedJournalFood(food, score)
    }.sortedWith(
        compareBy<RankedJournalFood> { it.score }
            .thenBy { it.food.displayName.length }
            .thenBy { it.food.displayName.lowercase(Locale.ROOT) }
    ).map { it.food }
}

private data class RankedJournalFood(
    val food: JournalFood,
    val score: Int
)

@Composable
private fun JournalSelectedFoodChip(
    item: JournalDraftFoodItem,
    foodMacrosEnabled: Boolean,
    onRemove: () -> Unit
) {
    val color = Color(item.accentColor)
    Surface(
        color = color.copy(alpha = 0.18f),
        shape = RoundedCornerShape(18.dp)
    ) {
        Row(
            modifier = Modifier.padding(start = 10.dp, end = 4.dp, top = 7.dp, bottom = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(color, CircleShape)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.widthIn(max = 180.dp)) {
                Text(
                    text = item.displayName,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = foodItemSummary(item, foodMacrosEnabled),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(
                onClick = onRemove,
                modifier = Modifier.size(34.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Remove,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
private fun JournalFoodPickerRow(
    title: String,
    subtitle: String,
    color: Color,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    expanded: Boolean = false,
    unselectedContainerColor: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    detailsContent: (@Composable () -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null
) {
    val shape = RoundedCornerShape(18.dp)
    Surface(
        color = if (selected) color.copy(alpha = 0.18f) else unselectedContainerColor,
        shape = shape
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = onLongClick
                )
                .animateContentSize(
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessMediumLow
                    )
                )
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .background(color, CircleShape)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                trailingContent?.invoke()
            }
            AnimatedVisibility(
                visible = expanded && detailsContent != null,
                enter = expandVertically(
                    animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                    expandFrom = Alignment.Top
                ) + fadeIn(),
                exit = shrinkVertically(
                    animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                    shrinkTowards = Alignment.Top
                ) + fadeOut()
            ) {
                Column(modifier = Modifier.padding(top = 10.dp)) {
                    detailsContent?.invoke()
                }
            }
        }
    }
}

@Composable
internal fun JournalFoodCompositionDetails(
    details: LegacyJournalFoodDatabase.FoodDetails?,
    color: Color,
    portionText: String,
    onPortionTextChange: (String) -> Unit,
    onSaveToLibrary: (() -> Unit)? = null
) {
    if (details == null) return
    val portionGrams = parseFoodPortionGrams(portionText)

    fun updatePortion(value: Float) {
        onPortionTextChange(formatFloatForEditor(value.coerceIn(1f, 2000f)))
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.compositionof100gram),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            details.components.take(14).forEach { component ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = component.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = formatFoodComponentValue(component.rawValue),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.End,
                        modifier = Modifier.widthIn(min = 46.dp)
                    )
                    Text(
                        text = component.unit,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.End,
                        modifier = Modifier.widthIn(min = 28.dp)
                    )
                }
            }
        }
        Surface(
            color = color.copy(alpha = 0.10f),
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilledTonalIconButton(
                    onClick = { updatePortion(portionGrams - 10f) },
                    modifier = Modifier.size(44.dp)
                ) {
                    Icon(Icons.Default.Remove, contentDescription = null)
                }
                BasicTextField(
                    value = portionText,
                    onValueChange = { value ->
                        onPortionTextChange(
                            value
                                .filter { it.isDigit() || it == ',' || it == '.' }
                                .take(6)
                        )
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.onSurface),
                    textStyle = MaterialTheme.typography.titleLarge.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 6.dp),
                    decorationBox = { innerTextField ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Box(
                                modifier = Modifier.weight(1f),
                                contentAlignment = Alignment.Center
                            ) {
                                innerTextField()
                            }
                            Text(
                                text = "g",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                )
                FilledTonalIconButton(
                    onClick = { updatePortion(portionGrams + 10f) },
                    modifier = Modifier.size(44.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = null)
                }
            }
        }
        onSaveToLibrary?.let { saveToLibrary ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = saveToLibrary,
                    colors = ButtonDefaults.textButtonColors(contentColor = color)
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(text = stringResource(R.string.journal_food_save_to_library))
                }
            }
        }
    }
}

@Composable
private fun formatFoodComponentValue(rawValue: Int): String {
    return when (rawValue) {
        -3 -> stringResource(R.string.trace)
        -2 -> stringResource(R.string.unknown)
        else -> formatFloatForEditor(rawValue / 1000f)
    }
}

private fun parseFoodPortionGrams(value: String): Float {
    return value
        .trim()
        .replace(',', '.')
        .toFloatOrNull()
        ?.coerceIn(1f, 2000f)
        ?: 100f
}

@Composable
private fun foodMacroSummary(food: JournalFood, foodMacrosEnabled: Boolean): String {
    return if (foodMacrosEnabled) {
        stringResource(
            R.string.journal_food_macro_summary,
            formatFloatForEditor(food.carbsGrams),
            formatFloatForEditor(food.proteinGrams ?: 0f),
            formatFloatForEditor(food.fatGrams ?: 0f),
            food.absorptionMinutes
        )
    } else {
        stringResource(
            R.string.journal_food_carb_summary,
            formatFloatForEditor(food.carbsGrams),
            food.absorptionMinutes
        )
    }
}

@Composable
private fun foodItemsSummary(items: List<JournalDraftFoodItem>, foodMacrosEnabled: Boolean): String {
    val carbs = items.sumOf { it.carbsGrams.toDouble() }.toFloat()
    val protein = items.sumOf { (it.proteinGrams ?: 0f).toDouble() }.toFloat()
    val fat = items.sumOf { (it.fatGrams ?: 0f).toDouble() }.toFloat()
    val duration = items.maxOfOrNull { it.absorptionMinutes } ?: JournalMealShape.MIXED.durationMinutes
    return if (foodMacrosEnabled) {
        stringResource(
            R.string.journal_food_macro_summary,
            formatFloatForEditor(carbs),
            formatFloatForEditor(protein),
            formatFloatForEditor(fat),
            duration
        )
    } else {
        stringResource(
            R.string.journal_food_carb_summary,
            formatFloatForEditor(carbs),
            duration
        )
    }
}

@Composable
private fun foodItemSummary(item: JournalDraftFoodItem, foodMacrosEnabled: Boolean): String {
    return if (foodMacrosEnabled) {
        stringResource(
            R.string.journal_food_macro_summary,
            formatFloatForEditor(item.carbsGrams),
            formatFloatForEditor(item.proteinGrams ?: 0f),
            formatFloatForEditor(item.fatGrams ?: 0f),
            item.absorptionMinutes
        )
    } else {
        stringResource(
            R.string.journal_food_carb_summary,
            formatFloatForEditor(item.carbsGrams),
            item.absorptionMinutes
        )
    }
}

@Composable
private fun JournalFoodPill(
    food: JournalFood,
    selected: Boolean,
    onClick: () -> Unit
) {
    val color = Color(food.accentColor)
    Surface(
        onClick = onClick,
        color = if (selected) color.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(18.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .background(color, CircleShape)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.widthIn(max = 180.dp)) {
                Text(
                    text = food.displayName,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = stringResource(
                        R.string.journal_food_macro_summary,
                        formatFloatForEditor(food.carbsGrams),
                        formatFloatForEditor(food.proteinGrams ?: 0f),
                        formatFloatForEditor(food.fatGrams ?: 0f),
                        food.absorptionMinutes
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun JournalMacroFields(
    proteinText: String,
    fatText: String,
    onProteinChange: (String) -> Unit,
    onFatChange: (String) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        JournalStepperField(
            value = proteinText,
            onValueChange = onProteinChange,
            onStep = { delta -> onProteinChange(adjustDecimalDraft(proteinText, delta, step = 5f)) },
            label = stringResource(R.string.journal_food_protein),
            suffix = "g"
        )
        JournalStepperField(
            value = fatText,
            onValueChange = onFatChange,
            onStep = { delta -> onFatChange(adjustDecimalDraft(fatText, delta, step = 5f)) },
            label = stringResource(R.string.journal_food_fat),
            suffix = "g"
        )
    }
}

@Composable
private fun JournalDoseMetric(label: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.78f),
        shape = RoundedCornerShape(50)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
        )
    }
}

private fun calculateInsulinForCarbs(
    carbs: Float?,
    protein: Float?,
    fat: Float?,
    glucoseMgDl: Float?,
    profile: JournalDoseProfile,
    activeInsulinUnits: Float
): JournalDoseCalculator.InsulinSuggestion? {
    return JournalDoseCalculator.insulinForCarbs(
        carbsGrams = carbs,
        proteinGrams = protein,
        fatGrams = fat,
        macrosEnabled = profile.foodMacrosEnabled,
        glucoseMgDl = glucoseMgDl,
        carbRatioGramsPerUnit = profile.carbRatioGramsPerUnit,
        insulinSensitivityMgDlPerUnit = profile.insulinSensitivityMgDlPerUnit,
        targetMgDl = profile.targetMgDl,
        activeInsulinUnits = activeInsulinUnits
    )
}

private fun calculateCoveredCarbsForInsulin(
    insulinUnits: Float?,
    glucoseMgDl: Float?,
    profile: JournalDoseProfile,
    activeInsulinUnits: Float
): Float? {
    return JournalDoseCalculator.carbsCoveredByInsulin(
        insulinUnits = insulinUnits,
        glucoseMgDl = glucoseMgDl,
        carbRatioGramsPerUnit = profile.carbRatioGramsPerUnit,
        insulinSensitivityMgDlPerUnit = profile.insulinSensitivityMgDlPerUnit,
        targetMgDl = profile.targetMgDl,
        activeInsulinUnits = activeInsulinUnits
    )
}

private fun formatInsulinDose(value: Float): String = formatFloatForEditor(value)

private fun formatInsulinComponent(value: Float): String {
    return formatFloatForEditor(((value / 0.1f).roundToInt() * 0.1f).coerceAtLeast(0f))
}

private fun formatCarbDose(value: Float): String {
    return formatFloatForEditor(((value / 5f).roundToInt() * 5f).coerceAtLeast(5f))
}

private enum class JournalTrayAction {
    CALIBRATE,
    INSULIN,
    CARBS,
    FINGERSTICK,
    ACTIVITY,
    NOTE
}

@Composable
private fun JournalActionRow(
    actions: List<JournalTrayAction>,
    onAction: (JournalTrayAction) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        actions.forEach { action ->
            FilledTonalButton(
                onClick = { onAction(action) },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    contentColor = MaterialTheme.colorScheme.onSurface
                )
            ) {
                Icon(
                    imageVector = when (action) {
                        JournalTrayAction.CALIBRATE -> Icons.Default.Bloodtype
                        JournalTrayAction.INSULIN -> Icons.Default.Vaccines
                        JournalTrayAction.CARBS -> Icons.Default.Restaurant
                        JournalTrayAction.FINGERSTICK -> Icons.Default.Bloodtype
                        JournalTrayAction.ACTIVITY -> Icons.Default.DirectionsRun
                        JournalTrayAction.NOTE -> Icons.AutoMirrored.Filled.Label
                    },
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = when (action) {
                        JournalTrayAction.CALIBRATE -> stringResource(R.string.calibrate_action)
                        JournalTrayAction.INSULIN -> stringResource(R.string.journal_type_insulin)
                        JournalTrayAction.CARBS -> stringResource(R.string.journal_type_food)
                        JournalTrayAction.FINGERSTICK -> stringResource(R.string.journal_type_bg_short)
                        JournalTrayAction.ACTIVITY -> stringResource(R.string.journal_type_activity)
                        JournalTrayAction.NOTE -> stringResource(R.string.journal_type_note)
                    },
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun JournalTypeSelector(
    selectedType: JournalEntryType,
    onTypeSelected: (JournalEntryType) -> Unit
) {
    val selectedContentColor = MaterialTheme.colorScheme.onSurface
    val selectedContainerBase = MaterialTheme.colorScheme.surfaceContainerHigh
    ConnectedButtonGroup(
        options = JournalEntryType.entries,
        selectedOption = selectedType,
        onOptionSelected = onTypeSelected,
        label = { _ -> },
        icon = { journalTypeIcon(it) },
        iconOnly = true,
        modifier = Modifier.fillMaxWidth(),
        itemHeight = 48.dp,
        selectedContainerColorFor = { type -> journalTypeSelectedContainerColor(type, selectedContainerBase) },
        selectedContentColorFor = { selectedContentColor },
        iconTint = { type, _ -> journalTypeColor(type) },
        unselectedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.78f),
        unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun JournalIntensitySelector(
    selectedIntensity: JournalIntensity?,
    onIntensitySelected: (JournalIntensity) -> Unit
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        JournalIntensity.entries.forEach { intensity ->
            FilterChip(
                selected = selectedIntensity == intensity,
                onClick = { onIntensitySelected(intensity) },
                label = { Text(text = stringResource(intensity.labelRes())) }
            )
        }
    }
}

@Composable
private fun JournalInsulinPresetSelector(
    presets: List<JournalInsulinPreset>,
    selectedPresetId: Long?,
    onPresetSelected: (JournalInsulinPreset) -> Unit
) {
    if (presets.size == 2) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            presets.forEach { preset ->
                JournalPresetPill(
                    modifier = Modifier.weight(1f),
                    preset = preset,
                    selected = selectedPresetId == preset.id,
                    onClick = { onPresetSelected(preset) }
                )
            }
        }
    } else {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            presets.forEach { preset ->
                JournalPresetPill(
                    preset = preset,
                    selected = selectedPresetId == preset.id,
                    onClick = { onPresetSelected(preset) }
                )
            }
        }
    }
}

@Composable
private fun JournalPresetPill(
    modifier: Modifier = Modifier,
    preset: JournalInsulinPreset,
    selected: Boolean,
    onClick: () -> Unit
) {
    val accent = Color(preset.accentColor)
    val containerColor by animateColorAsState(
        targetValue = if (selected) {
            accent.copy(alpha = 0.30f)
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        animationSpec = tween(durationMillis = 180),
        label = "journalPresetContainer"
    )
    val borderColor by animateColorAsState(
        targetValue = if (selected) accent.copy(alpha = 0.9f) else Color.Transparent,
        animationSpec = tween(durationMillis = 180),
        label = "journalPresetBorder"
    )
    val indicatorColor by animateColorAsState(
        targetValue = if (selected) accent else accent.copy(alpha = 0.18f),
        animationSpec = tween(durationMillis = 180),
        label = "journalPresetIndicator"
    )
    val tonalElevation by animateDpAsState(
        targetValue = if (selected) 2.dp else 0.dp,
        animationSpec = tween(durationMillis = 180),
        label = "journalPresetElevation"
    )
    Surface(
        modifier = modifier,
        onClick = onClick,
        color = containerColor,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, borderColor),
        tonalElevation = tonalElevation
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = 48.dp)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(20.dp),
                shape = CircleShape,
                color = indicatorColor
            ) {
                Box(contentAlignment = Alignment.Center) {
                    AnimatedContent(
                        targetState = selected,
                        transitionSpec = {
                            fadeIn(animationSpec = tween(durationMillis = 140))
                                .togetherWith(fadeOut(animationSpec = tween(durationMillis = 90)))
                        },
                        label = "journalPresetIndicatorContent"
                    ) { isSelected ->
                        if (isSelected) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(12.dp)
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .background(accent, CircleShape)
                            )
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = preset.displayName,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
fun JournalInlineChip(
    entry: JournalEntry,
    unit: String,
    insulinPreset: JournalInsulinPreset?,
    food: JournalFood? = null,
    expanded: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val tint = food?.let { Color(it.accentColor) }
        ?: insulinPreset?.let { Color(it.accentColor) }
        ?: journalTypeColor(entry.type)
    val chipContent = journalInlineChipContent(entry, insulinPreset, food, unit, expanded)
    val inheritedMinimumInteractiveSize = LocalMinimumInteractiveComponentSize.current
    CompositionLocalProvider(
        LocalMinimumInteractiveComponentSize provides if (expanded) {
            inheritedMinimumInteractiveSize
        } else {
            // Compact ledger chips keep their visual bounds for flow measurement. Compose's
            // pointer hit expansion still supplies the platform minimum touch target without
            // inserting invisible vertical space between wrapped chip lines.
            Dp.Unspecified
        }
    ) {
        Surface(
            modifier = modifier.widthIn(max = if (expanded) 320.dp else 180.dp),
            onClick = onClick,
            color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.94f),
            shape = RoundedCornerShape(if (expanded) 18.dp else 14.dp)
        ) {
            Row(
                modifier = Modifier.padding(
                    horizontal = if (expanded) 11.dp else 10.dp,
                    vertical = if (expanded) 9.dp else 7.dp
                ),
                verticalAlignment = if (expanded) Alignment.Top else Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(if (expanded) 10.dp else 8.dp)
            ) {
                Icon(
                    imageVector = journalTypeIcon(entry.type),
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier
                        .padding(top = if (expanded) 2.dp else 0.dp)
                        .size(if (expanded) 18.dp else 14.dp)
                )
                if (expanded) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            text = chipContent.label,
                            style = MaterialTheme.typography.labelLarge,
                            color = if (chipContent.value == null) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            fontWeight = if (chipContent.value == null) FontWeight.SemiBold else FontWeight.Medium,
                            maxLines = 2,
                            softWrap = true,
                            overflow = TextOverflow.Ellipsis
                        )
                        chipContent.value?.let { value ->
                            Text(
                                text = value,
                                style = MaterialTheme.typography.titleSmall.copy(fontFeatureSettings = "tnum"),
                                color = MaterialTheme.colorScheme.onSurface,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        chipContent.detail?.let { detail ->
                            Text(
                                text = detail,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                } else {
                    Text(
                        text = chipContent.label,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                (entry.originSource ?: entry.source).presentation()?.let { source ->
                    // Where the number came from, in the colour of secondary text so it never
                    // competes with the amount. For mirrored rows this is the sender's
                    // authoritative origin; the ingress transport remains beside the time.
                    Icon(
                        imageVector = source.icon,
                        contentDescription = stringResource(source.labelRes),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .padding(top = if (expanded) 3.dp else 0.dp)
                            .size(if (expanded) 14.dp else 12.dp)
                    )
                }
            }
        }
    }
}

private data class JournalInlineChipContent(
    val label: String,
    val value: String? = null,
    val detail: String? = null
)

private fun journalInlineChipContent(
    entry: JournalEntry,
    insulinPreset: JournalInsulinPreset?,
    food: JournalFood?,
    unit: String,
    expanded: Boolean
): JournalInlineChipContent {
    if (!expanded) {
        return JournalInlineChipContent(
            journalMarkerDetail(entry, insulinPreset, unit).ifBlank { entry.title }
        )
    }
    return when (entry.type) {
        JournalEntryType.INSULIN -> {
            val amount = entry.amount?.let { "${formatFloatForEditor(it)} U" }
            val label = insulinPreset?.displayName.cleanJournalChipText()
                ?: entry.title.cleanJournalChipText()
                ?: journalMarkerDetail(entry, insulinPreset, unit)
            JournalInlineChipContent(
                label = label,
                value = amount?.takeUnless { it == label }
            )
        }

        JournalEntryType.CARBS -> {
            val amount = entry.amount?.let { "${formatFloatForEditor(it)} g" }
            val label = food?.displayName.cleanJournalChipText()
                ?: entry.title.cleanJournalChipText()
                    ?.takeUnless(::isGenericCarbsChipTitle)
                ?: Applic.app.getString(R.string.journal_type_food)
            val macros = listOfNotNull(
                entry.proteinGrams?.takeIf { it > 0f }?.let {
                    Applic.app.getString(R.string.journal_food_protein_short, formatFloatForEditor(it))
                },
                entry.fatGrams?.takeIf { it > 0f }?.let {
                    Applic.app.getString(R.string.journal_food_fat_short, formatFloatForEditor(it))
                }
            ).joinToString(" · ").takeIf { it.isNotBlank() }
            JournalInlineChipContent(
                label = label,
                value = amount?.takeUnless { it == label },
                detail = macros
            )
        }

        JournalEntryType.FINGERSTICK -> {
            val glucose = entry.glucoseValueMgDl?.let { formatGlucoseForEditor(it, unit) }
            val label = entry.title.cleanJournalChipText()
                ?: entry.note.cleanJournalChipText()
                ?: Applic.app.getString(R.string.journal_type_bg_short)
            JournalInlineChipContent(
                label = label,
                value = glucose?.takeUnless { it == label },
                detail = entry.note.cleanJournalChipText()?.takeUnless { it == label }
            )
        }

        JournalEntryType.ACTIVITY -> {
            val duration = entry.durationMinutes?.let { Applic.app.getString(R.string.minutes_short_format, it) }
            val label = entry.title.cleanJournalChipText()
                ?: Applic.app.getString(R.string.journal_type_activity)
            val intensity = entry.intensity?.let { Applic.app.getString(it.labelRes()) }
            JournalInlineChipContent(
                label = label,
                value = duration?.takeUnless { it == label },
                detail = intensity.takeIf { it != null && it != label }
            )
        }

        JournalEntryType.NOTE -> {
            val label = entry.title.cleanJournalChipText()
                ?: entry.note.cleanJournalChipText()
                ?: journalMarkerDetail(entry, insulinPreset, unit)
            JournalInlineChipContent(
                label = label,
                detail = entry.note.cleanJournalChipText()
                    ?.takeUnless { it == label }
            )
        }
    }
}

private fun String?.cleanJournalChipText(): String? =
    this?.trim()?.takeIf { it.isNotBlank() }

private fun isGenericCarbsChipTitle(value: String): Boolean {
    val normalized = value.trim().lowercase(Locale.ROOT)
    return normalized == "carbohydrate" ||
        normalized == "carbohydrates" ||
        normalized == "carb" ||
        normalized == "carbs" ||
        normalized == "food"
}

@Composable
private fun JournalEntryChip(
    entry: JournalEntry,
    unit: String,
    insulinPreset: JournalInsulinPreset?,
    onClick: () -> Unit
) {
    val tint = insulinPreset?.let { Color(it.accentColor) } ?: journalTypeColor(entry.type)
    Surface(
        onClick = onClick,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(18.dp)
    ) {
        Row(
            modifier = Modifier
                .widthIn(min = 156.dp, max = 220.dp)
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(32.dp),
                color = tint.copy(alpha = 0.18f),
                shape = RoundedCornerShape(10.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = journalTypeIcon(entry.type),
                        contentDescription = null,
                        tint = tint,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = describeJournalEntry(entry, unit, insulinPreset),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            entry.source.presentation()?.let { source ->
                Icon(
                    imageVector = source.icon,
                    contentDescription = stringResource(source.labelRes),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
            }
            Text(
                text = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(entry.timestamp)),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.wrapContentWidth(Alignment.End)
            )
        }
    }
}

@Composable
private fun JournalDateTimeCard(
    date: String,
    time: String,
    onDateClick: () -> Unit,
    onTimeClick: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            JournalDateTimeSegment(
                modifier = Modifier.weight(1f),
                icon = Icons.Default.Event,
                contentDescription = stringResource(R.string.date),
                value = date,
                onClick = onDateClick
            )
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .height(32.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f))
            )
            JournalDateTimeSegment(
                modifier = Modifier.weight(1f),
                icon = Icons.Default.AccessTime,
                contentDescription = stringResource(R.string.time),
                value = time,
                onClick = onTimeClick
            )
        }
    }
}

@Composable
private fun JournalDateTimeSegment(
    modifier: Modifier,
    icon: ImageVector,
    contentDescription: String,
    value: String,
    onClick: () -> Unit
) {
    Row(
        modifier = modifier
            .heightIn(min = 56.dp)
            .semantics(mergeDescendants = true) {
                this.contentDescription = "$contentDescription, $value"
                role = Role.Button
            }
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun JournalInsulinWindowCard(
    preset: JournalInsulinPreset
) {
    var expanded by remember { mutableStateOf(false) }
    val accent by animateColorAsState(
        targetValue = Color(preset.accentColor),
        animationSpec = tween(durationMillis = 220),
        label = "journalInsulinWindowAccent"
    )
    val onset = formatJournalWindowEndpoint(preset.onsetMinutes)
    val duration = formatJournalWindowEndpoint(preset.durationMinutes)
    val summary = "$onset – $duration"
    val windowDescription = stringResource(
        R.string.journal_active_window,
        stringResource(R.string.minutes_short_format, preset.onsetMinutes),
        stringResource(R.string.minutes_short_format, preset.durationMinutes)
    )

    Surface(
        modifier = Modifier
            .animateContentSize(
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMediumLow
                )
            )
            .then(
                if (expanded) Modifier.fillMaxWidth() else Modifier.wrapContentWidth()
            ),
        color = if (expanded) {
            MaterialTheme.colorScheme.surfaceContainerHigh
        } else {
            Color.Transparent
        },
        shape = RoundedCornerShape(16.dp)
    ) {
        Column {
            Row(
                modifier = Modifier
                    .then(
                        if (expanded) {
                            Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                        } else {
                            // The compact visual row keeps the sheet dense; Compose expands its
                            // pointer hit area to the platform minimum within the surrounding gap.
                            Modifier.height(32.dp)
                        }
                    )
                    .semantics(mergeDescendants = true) {
                        this.contentDescription = windowDescription
                        role = Role.Button
                    }
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Schedule,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                AnimatedContent(
                    targetState = summary,
                    transitionSpec = {
                        fadeIn(animationSpec = tween(durationMillis = 220))
                            .togetherWith(fadeOut(animationSpec = tween(durationMillis = 120)))
                    },
                    label = "journalInsulinWindowSummary"
                ) { value ->
                    Text(
                        text = value,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
            }
            if (expanded) {
                AnimatedContent(
                    targetState = preset,
                    transitionSpec = {
                        fadeIn(animationSpec = tween(durationMillis = 220))
                            .togetherWith(fadeOut(animationSpec = tween(durationMillis = 140)))
                    },
                    contentKey = { it.id },
                    label = "journalInsulinWindowChart"
                ) { visiblePreset ->
                    JournalInsulinActivityCurve(
                        points = visiblePreset.curvePoints,
                        durationMinutes = visiblePreset.durationMinutes,
                        color = Color(visiblePreset.accentColor),
                        contentDescription = windowDescription,
                        modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun formatJournalWindowEndpoint(minutes: Int): String {
    val safeMinutes = minutes.coerceAtLeast(0)
    if (safeMinutes < 720) {
        return stringResource(R.string.minutes_short_format, safeMinutes)
    }
    return formatJournalWindowAxisTime(safeMinutes)
}

@Composable
private fun formatJournalWindowAxisTime(minutes: Int): String {
    val safeMinutes = minutes.coerceAtLeast(0)
    if (safeMinutes == 0) return "0"
    if (safeMinutes < 120) {
        return stringResource(R.string.minutes_short_format, safeMinutes)
    }
    val hours = safeMinutes / 60
    val remainingMinutes = safeMinutes % 60
    return if (remainingMinutes == 0) {
        stringResource(R.string.stats_duration_hours, hours)
    } else {
        stringResource(R.string.stats_duration_hours_minutes, hours, remainingMinutes)
    }
}

@Composable
private fun JournalInsulinActivityCurve(
    points: List<JournalCurvePoint>,
    durationMinutes: Int,
    color: Color,
    contentDescription: String,
    modifier: Modifier = Modifier
) {
    val curvePoints = remember(points) {
        points
            .filter { it.minute >= 0 && it.activity.isFinite() }
            .sortedBy { it.minute }
    }
    val guideColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
    val axisColor = MaterialTheme.colorScheme.onSurfaceVariant
    val xAxisMinutes = remember(durationMinutes) {
        List(5) { index -> ((durationMinutes.coerceAtLeast(1) * index) / 4f).roundToInt() }
    }

    Column(
        modifier = modifier
            .semantics { this.contentDescription = contentDescription }
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(
                modifier = Modifier
                    .width(36.dp)
                    .height(96.dp),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Text("100%", style = MaterialTheme.typography.labelSmall, color = axisColor)
                Text("50%", style = MaterialTheme.typography.labelSmall, color = axisColor)
                Text("0%", style = MaterialTheme.typography.labelSmall, color = axisColor)
            }
            Spacer(modifier = Modifier.width(8.dp))
            Canvas(
                modifier = Modifier
                    .weight(1f)
                    .height(96.dp)
            ) {
                if (curvePoints.size < 2) return@Canvas
                val maxMinute = maxOf(durationMinutes, curvePoints.last().minute, 1).toFloat()
                val top = 4.dp.toPx()
                val baseline = size.height - 4.dp.toPx()
                val curveHeight = (baseline - top).coerceAtLeast(1f)

                repeat(3) { index ->
                    val y = baseline - ((curveHeight / 2f) * index)
                    drawLine(
                        color = guideColor,
                        start = androidx.compose.ui.geometry.Offset(0f, y),
                        end = androidx.compose.ui.geometry.Offset(size.width, y),
                        strokeWidth = 1.dp.toPx()
                    )
                }
                repeat(5) { index ->
                    val x = (size.width / 4f) * index
                    drawLine(
                        color = guideColor.copy(alpha = 0.6f),
                        start = androidx.compose.ui.geometry.Offset(x, top),
                        end = androidx.compose.ui.geometry.Offset(x, baseline),
                        strokeWidth = 1.dp.toPx()
                    )
                }

                val curvePath = Path()
                val fillPath = Path()
                curvePoints.forEachIndexed { index, point ->
                    val x = (point.minute / maxMinute) * size.width
                    val y = baseline - (point.activity.coerceIn(0f, 1f) * curveHeight)
                    if (index == 0) {
                        curvePath.moveTo(x, y)
                        fillPath.moveTo(x, baseline)
                        fillPath.lineTo(x, y)
                    } else {
                        curvePath.lineTo(x, y)
                        fillPath.lineTo(x, y)
                    }
                }
                fillPath.lineTo(size.width, baseline)
                fillPath.close()

                drawPath(path = fillPath, color = color.copy(alpha = 0.14f))
                drawPath(path = curvePath, color = color, style = Stroke(width = 2.dp.toPx()))
                curvePoints.maxByOrNull { it.activity }?.let { peak ->
                    drawCircle(
                        color = color,
                        radius = 4.dp.toPx(),
                        center = androidx.compose.ui.geometry.Offset(
                            x = (peak.minute / maxMinute) * size.width,
                            y = baseline - (peak.activity.coerceIn(0f, 1f) * curveHeight)
                        )
                    )
                }
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 44.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            xAxisMinutes.forEach { minute ->
                Text(
                    text = formatJournalWindowAxisTime(minute),
                    style = MaterialTheme.typography.labelSmall,
                    color = axisColor
                )
            }
        }
    }
}

@Composable
private fun JournalStepperField(
    value: String,
    onValueChange: (String) -> Unit,
    onStep: (Int) -> Unit,
    label: String,
    suffix: String?,
    keyboardType: KeyboardType = KeyboardType.Decimal,
    prominent: Boolean = false
) {
    val haptics = LocalHapticFeedback.current
    val stepWithFeedback: (Int) -> Unit = { delta ->
        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        onStep(delta)
    }
    Surface(
        color = if (prominent) {
            MaterialTheme.colorScheme.surfaceContainerHigh
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        },
        shape = RoundedCornerShape(if (prominent) 28.dp else 22.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = if (prominent) 10.dp else 12.dp,
                    vertical = if (prominent) 9.dp else 12.dp
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            FilledTonalIconButton(
                onClick = { stepWithFeedback(-1) },
                modifier = if (prominent) Modifier.size(56.dp) else Modifier,
                shape = RoundedCornerShape(if (prominent) 18.dp else 14.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Remove,
                    contentDescription = null
                )
            }
            if (prominent) {
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 64.dp),
                    textStyle = MaterialTheme.typography.displaySmall.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center
                    ),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                    decorationBox = { innerTextField ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(horizontal = 6.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                if (value.isEmpty()) {
                                    Text(
                                        text = "0",
                                        style = MaterialTheme.typography.displaySmall.copy(
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                            fontWeight = FontWeight.SemiBold,
                                            textAlign = TextAlign.Center
                                        )
                                    )
                                }
                                innerTextField()
                            }
                            suffix?.let {
                                Text(
                                    text = it,
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                )
            } else {
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 64.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.72f),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    BasicTextField(
                        value = value,
                        onValueChange = onValueChange,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        textStyle = MaterialTheme.typography.titleLarge.copy(
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Center
                        ),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.onSurface),
                        decorationBox = { innerTextField ->
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Box(
                                    modifier = Modifier.fillMaxWidth(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Box(
                                        modifier = Modifier.fillMaxWidth(),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        if (value.isBlank()) {
                                            Text(
                                                text = "0",
                                                style = MaterialTheme.typography.titleLarge.copy(
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.42f),
                                                    fontWeight = FontWeight.SemiBold,
                                                    textAlign = TextAlign.Center
                                                )
                                            )
                                        }
                                        innerTextField()
                                    }
                                    suffix?.let {
                                        Text(
                                            text = it,
                                            style = MaterialTheme.typography.titleMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.align(Alignment.CenterEnd)
                                        )
                                    }
                                }
                            }
                        }
                    )
                }
            }
            FilledTonalIconButton(
                onClick = { stepWithFeedback(1) },
                modifier = if (prominent) Modifier.size(56.dp) else Modifier,
                shape = RoundedCornerShape(if (prominent) 18.dp else 14.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = null
                )
            }
        }
    }
}

private fun describeJournalEntry(
    entry: JournalEntry,
    unit: String,
    insulinPreset: JournalInsulinPreset?
): String {
    return when (entry.type) {
        JournalEntryType.INSULIN -> {
            val amount = entry.amount?.let(::formatFloatForEditor).orEmpty()
            val window = insulinPreset?.let {
                Applic.app.getString(
                    R.string.journal_active_window,
                    Applic.app.getString(R.string.minutes_short_format, it.onsetMinutes),
                    Applic.app.getString(R.string.minutes_short_format, it.durationMinutes)
                )
            }
            listOfNotNull("$amount U".takeIf { amount.isNotBlank() }, window, entry.note).joinToString(" · ")
        }

        JournalEntryType.CARBS -> {
            val amount = entry.amount?.let(::formatFloatForEditor).orEmpty()
            val curve = entry.durationMinutes?.let {
                Applic.app.getString(R.string.minutes_short_format, it)
            }
            val protein = entry.proteinGrams?.takeIf { it > 0f }?.let {
                Applic.app.getString(R.string.journal_food_protein_short, formatFloatForEditor(it))
            }
            val fat = entry.fatGrams?.takeIf { it > 0f }?.let {
                Applic.app.getString(R.string.journal_food_fat_short, formatFloatForEditor(it))
            }
            listOfNotNull("$amount g".takeIf { amount.isNotBlank() }, protein, fat, curve, entry.note)
                .joinToString(" · ")
        }

        JournalEntryType.FINGERSTICK -> {
            listOfNotNull(
                entry.glucoseValueMgDl?.let { formatGlucoseForEditor(it, unit) },
                entry.note
            ).joinToString(" · ")
        }

        JournalEntryType.ACTIVITY -> {
            listOfNotNull(
                entry.durationMinutes?.let { Applic.app.getString(R.string.minutes_short_format, it) },
                entry.intensity?.let { Applic.app.getString(it.labelRes()) },
                entry.note
            ).joinToString(" · ")
        }

        JournalEntryType.NOTE -> {
            entry.note ?: entry.title
        }
    }
}

private fun journalMarkerBadge(type: JournalEntryType): String {
    return when (type) {
        JournalEntryType.INSULIN -> "I"
        JournalEntryType.CARBS -> "C"
        JournalEntryType.FINGERSTICK -> "B"
        JournalEntryType.ACTIVITY -> "A"
        JournalEntryType.NOTE -> "N"
    }
}

private fun journalMarkerDetail(
    entry: JournalEntry,
    insulinPreset: JournalInsulinPreset?,
    unit: String
): String {
    return when (entry.type) {
        JournalEntryType.INSULIN -> {
            val amount = entry.amount?.let(::formatFloatForEditor).orEmpty()
            "$amount U".trim()
        }

        JournalEntryType.CARBS -> {
            val amount = entry.amount?.let(::formatFloatForEditor).orEmpty()
            "$amount g".trim()
        }

        JournalEntryType.FINGERSTICK -> {
            entry.glucoseValueMgDl?.let { formatGlucoseForEditor(it, unit) }.orEmpty()
        }

        JournalEntryType.ACTIVITY -> {
            entry.durationMinutes?.let { "${it}m" } ?: insulinPreset?.displayName.orEmpty()
        }

        JournalEntryType.NOTE -> {
            (entry.note ?: entry.title).take(10)
        }
    }
}

private fun formatGlucoseForEditor(glucoseMgDl: Float, unit: String): String {
    val isMmol = GlucoseFormatter.isMmol(unit)
    val value = GlucoseFormatter.displayFromMgDl(glucoseMgDl, isMmol)
    return if (isMmol) {
        DecimalFormat("0.#", DecimalFormatSymbols(Locale.getDefault())).format(value)
    } else {
        formatFloatForEditor(value)
    }
}

private fun parseGlucoseToMgDl(value: String, unit: String): Float? {
    val parsed = value.parseFloatOrNull() ?: return null
    return if (GlucoseFormatter.isMmol(unit)) GlucoseFormatter.mmolToMg(parsed) else parsed
}

private fun formatFloatForEditor(value: Float): String {
    return if (abs(value - value.roundToInt()) < 0.001f) {
        value.roundToInt().toString()
    } else {
        DecimalFormat("0.##", DecimalFormatSymbols(Locale.getDefault())).format(value)
    }
}

private fun String.parseFloatOrNull(): Float? {
    return trim().replace(',', '.').toFloatOrNull()
}

private fun String.parseIntOrNull(): Int? {
    return trim().toIntOrNull()
}

private fun String.trimTrailingLabel(): String {
    return trim().trimEnd(':').trim()
}

private fun journalTypeIcon(type: JournalEntryType): ImageVector {
    return when (type) {
        JournalEntryType.INSULIN -> Icons.Default.Vaccines
        JournalEntryType.CARBS -> Icons.Default.Restaurant
        JournalEntryType.FINGERSTICK -> Icons.Default.Bloodtype
        JournalEntryType.ACTIVITY -> Icons.Default.DirectionsRun
        JournalEntryType.NOTE -> Icons.AutoMirrored.Filled.Label
    }
}

private fun JournalEntryType.labelRes(): Int {
    return when (this) {
        JournalEntryType.INSULIN -> R.string.journal_type_insulin
        JournalEntryType.CARBS -> R.string.journal_type_food
        JournalEntryType.FINGERSTICK -> R.string.journal_type_fingerstick
        JournalEntryType.ACTIVITY -> R.string.journal_type_activity
        JournalEntryType.NOTE -> R.string.journal_type_note
    }
}

private fun JournalIntensity.labelRes(): Int {
    return when (this) {
        JournalIntensity.LIGHT -> R.string.journal_intensity_light
        JournalIntensity.MODERATE -> R.string.journal_intensity_moderate
        JournalIntensity.INTENSE -> R.string.journal_intensity_intense
    }
}

private fun adjustDecimalDraft(value: String, direction: Int, step: Float): String {
    val current = value.parseFloatOrNull() ?: 0f
    val next = (current + (direction * step)).coerceAtLeast(0f)
    return formatFloatForEditor(next)
}

private fun suggestedInsulinAmountForFraction(fraction: Float): String {
    val normalized = fraction.coerceIn(0f, 1f)
    val stepped = ((normalized * 20f) / 0.5f).roundToInt() * 0.5f
    return formatFloatForEditor(stepped.coerceAtLeast(0.5f))
}

private fun suggestedCarbAmountForFraction(fraction: Float): String {
    val normalized = fraction.coerceIn(0f, 1f)
    val stepped = ((normalized * 100f) / 5f).roundToInt() * 5f
    return formatFloatForEditor(stepped.coerceAtLeast(5f))
}

private fun adjustIntegerDraft(value: String, delta: Int, minValue: Int = 0): String {
    val current = value.parseIntOrNull() ?: 0
    return (current + delta).coerceAtLeast(minValue).toString()
}

private fun mergeJournalTime(currentTimestamp: Long, selectedTime: Pair<Int, Int>): Long {
    return Calendar.getInstance().apply {
        timeInMillis = currentTimestamp
        set(Calendar.HOUR_OF_DAY, selectedTime.first)
        set(Calendar.MINUTE, selectedTime.second)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
}
