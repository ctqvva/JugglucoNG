@file:OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)

package tk.glucodata.ui.journal

import androidx.compose.material3.TimePickerDefaults
import androidx.compose.material3.FilterChipDefaults
import tk.glucodata.ui.components.IconButtonTooltip
import androidx.compose.material3.IconButtonDefaults
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
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.TextAutoSize
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
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.focus.FocusState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import tk.glucodata.data.journal.JournalAmountAssist
import tk.glucodata.data.journal.JournalChartMarker
import tk.glucodata.data.journal.JournalCurvePoint
import tk.glucodata.data.journal.JournalDoseCalculator
import tk.glucodata.data.journal.JournalDosePresetPolicy
import tk.glucodata.data.journal.JournalEntry
import tk.glucodata.data.journal.JournalEntryInput
import tk.glucodata.data.journal.JournalEntryType
import tk.glucodata.data.journal.JournalFood
import tk.glucodata.data.journal.JournalInsulinPreset
import tk.glucodata.data.journal.JournalIntensity
import tk.glucodata.data.prediction.PredictionModelProfile
import tk.glucodata.data.prediction.StateDoseHint
import tk.glucodata.data.prediction.StateDoseHintKind
import tk.glucodata.data.journal.JournalFoodUse
import tk.glucodata.data.food.FoodCatalog
import tk.glucodata.data.food.FoodMath
import tk.glucodata.ui.GlucosePoint
import tk.glucodata.ui.components.CompactSheetDragHandle
import tk.glucodata.ui.components.SheetContentHeightAnimation
import tk.glucodata.ui.components.StableModalBottomSheet
import tk.glucodata.ui.components.animateSheetContentHeight
import tk.glucodata.ui.util.ConnectedButtonGroup
import tk.glucodata.ui.util.ExpressiveMotion
import tk.glucodata.ui.util.GlucoseFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class JournalEntryDraft(
    val entryId: Long? = null,
    val type: JournalEntryType,
    val timestamp: Long,
    val title: String = "",
    val amountText: String = "",
    /** [amountText] came from a suggestion and nobody has touched it since. */
    val amountSuggested: Boolean = false,
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
    val foodItems: List<JournalDraftFoodItem> = emptyList(),
    val recordFoodUses: Boolean = false
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
    val tempBasalEnds = tk.glucodata.data.journal.tempBasalEndsMillis(entries)

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
            title = if (entry.type == JournalEntryType.INSULIN) {
                preset?.displayName ?: entry.title
            } else {
                entry.title
            },
            accentColor = preset?.accentColor ?: food?.accentColor ?: journalTypeColor(entry.type).toArgb(),
            badgeText = journalMarkerBadge(entry.type),
            detailText = journalMarkerDetail(entry, preset, unit),
            amount = entry.amount,
            chartGlucoseValue = chartValue,
            chartYFraction = chartYFraction,
            durationMinutes = entry.durationMinutes,
            curvePoints = if (entry.type == JournalEntryType.INSULIN && preset != null) {
                val snapshot = tk.glucodata.data.journal.parseJournalCurve(entry.insulinCurveJsonSnapshot)
                if (snapshot.size >= 2) snapshot else preset.curvePoints
            } else {
                emptyList()
            },
            curveEvidence = if (entry.type == JournalEntryType.INSULIN) {
                entry.insulinCurveEvidence ?: preset?.curveEvidence
            } else {
                null
            },
            activeStartMillis = if (entry.type == JournalEntryType.INSULIN && preset != null) {
                preset.activeStartAt(entry.timestamp)
            } else if (entry.type == JournalEntryType.TEMP_BASAL && entry.durationMinutes != null) {
                entry.timestamp
            } else {
                null
            },
            activeEndMillis = if (entry.type == JournalEntryType.INSULIN && preset != null) {
                val snapshot = tk.glucodata.data.journal.parseJournalCurve(entry.insulinCurveJsonSnapshot)
                val points = if (snapshot.size >= 2) snapshot else preset.curvePoints
                entry.timestamp + ((points.lastOrNull()?.minute ?: 0).coerceAtLeast(0) * 60_000L)
            } else if (entry.type == JournalEntryType.ACTIVITY && entry.durationMinutes != null) {
                entry.timestamp + (entry.durationMinutes.coerceAtLeast(1) * 60_000L)
            } else if (entry.type == JournalEntryType.CARBS && entry.durationMinutes != null) {
                entry.timestamp + (entry.durationMinutes.coerceAtLeast(1) * 60_000L)
            } else if (entry.type == JournalEntryType.TEMP_BASAL) {
                tempBasalEnds[entry.id]
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
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
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
    /** The dashboard's dose hint, for a sheet opened from it. */
    suggestedDose: StateDoseHint? = null,
    insulinPresets: List<JournalInsulinPreset>,
    foods: List<JournalFood> = emptyList(),
    /** Off hides the food search and leaves the plain carbs amount. */
    foodLibraryEnabled: Boolean = true,
    /** Whether Open Food Facts search and the barcode scanner are offered. */
    foodOnlineEnabled: Boolean = true,
    foodMacrosEnabled: Boolean = false,
    doseJournalEntries: List<JournalEntry> = emptyList(),
    doseProfile: JournalDoseProfile? = null,
    initialType: JournalEntryType,
    existingEntry: JournalEntry? = null,
    onDismiss: () -> Unit,
    onSave: (JournalEntryInput) -> Unit,
    onSaveEntries: ((List<JournalEntryInput>) -> Unit)? = null,
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
    val foodPicker = rememberFoodPickerState(activeFoods)
    val foodSuggestions = remember(activeFoods) { FoodCatalog.suggestions(activeFoods) }
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
    var restoredFoodSelection by remember(existingEntry?.id) { mutableStateOf(false) }
    LaunchedEffect(existingEntry?.id, foods) {
        if (!restoredFoodSelection && draft.foodId == existingEntry?.foodId && draft.foodItems.isEmpty()) {
            val saved = foods.firstOrNull { it.id == existingEntry?.foodId }
            if (saved != null && existingEntry != null) {
                draft = draft.copy(foodItems = listOf(restoredFoodItem(saved, existingEntry)))
                restoredFoodSelection = true
            }
        }
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
    // The hint is about the moment the sheet opened: a new reading must not change a number
    // under the person's finger, and a dose dated hours away is not what it was computed for.
    val openedAtMillis = remember(existingEntry?.id, initialType, selectedTimestamp) { System.currentTimeMillis() }
    val openingHint = remember(existingEntry?.id, initialType, selectedTimestamp) { suggestedDose }
        ?.takeIf { abs(draft.timestamp - openedAtMillis) <= JOURNAL_HINT_MAX_AGE_MILLIS }
    // Food the hint asked for is there to stop a low. Offering a dose for it would undo that, and
    // the insulin on board only reduces a correction, never the food part of a dose. This follows
    // the hint rather than the suggested amount, because 20 g after one press is still rescue food.
    val rescueCarbs = draft.type == JournalEntryType.CARBS && openingHint?.kind == StateDoseHintKind.CARBS
    LaunchedEffect(rescueCarbs, draft.pairWithDose) {
        // Moving the date off and back brings the hint back over a pairing made in between.
        if (rescueCarbs && draft.pairWithDose) {
            draft = draft.copy(pairWithDose = false, pairedAmountText = "", insulinPresetId = null)
        }
    }
    val insulinHabit = remember(doseJournalEntries, draft.insulinPresetId, draft.timestamp) {
        JournalAmountAssist.insulinHabit(doseJournalEntries, draft.insulinPresetId, draft.timestamp)
    }
    val carbHabit = remember(doseJournalEntries, draft.timestamp) {
        JournalAmountAssist.carbHabit(doseJournalEntries, draft.timestamp)
    }
    val usesCalculation = selectedInsulinPreset?.useForCalculation == true
    val uncoveredMealDose = remember(
        doseJournalEntries,
        presetsById,
        draft.timestamp,
        draft.doseGlucoseMgDl,
        calculatorProfile,
        activeInsulinUnits,
        usesCalculation
    ) {
        val profile = calculatorProfile?.takeIf { usesCalculation } ?: return@remember null
        val meal = JournalAmountAssist.uncoveredMeal(doseJournalEntries, presetsById, draft.timestamp)
            ?: return@remember null
        calculateInsulinForCarbs(
            carbs = meal.carbsGrams,
            protein = meal.proteinGrams,
            fat = meal.fatGrams,
            glucoseMgDl = draft.doseGlucoseMgDl,
            profile = profile,
            activeInsulinUnits = activeInsulinUnits
        )?.totalInsulinUnits
    }
    // Computed for this moment, so it goes into the field and can be saved as it stands.
    val suggestedAmount = when {
        existingEntry != null -> null
        draft.type == JournalEntryType.INSULIN && usesCalculation ->
            openingHint?.takeIf { it.kind == StateDoseHintKind.INSULIN }?.amount
        draft.type == JournalEntryType.CARBS ->
            openingHint?.takeIf { it.kind == StateDoseHintKind.CARBS }?.amount
        else -> null
    }
    // Only a guess: it sits dim in the empty field and takes a press of the stepper to become
    // an amount. The meal case stays here too, because it can't tell a meal from a hypo
    // treatment, and no dose is offered at all while the hint is asking for carbs.
    val amountStart = when {
        existingEntry != null -> null
        draft.type == JournalEntryType.CARBS -> carbHabit.usual
        draft.type != JournalEntryType.INSULIN -> null
        openingHint?.kind == StateDoseHintKind.CARBS -> null
        else -> uncoveredMealDose ?: insulinHabit.usual
    }
    LaunchedEffect(existingEntry?.id, draft.type, suggestedAmount) {
        if (existingEntry == null) {
            draft = draft.withSuggestedAmount(suggestedAmount?.let(::formatFloatForEditor))
        }
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

    val sheetContentHeight = remember { SheetContentHeightAnimation() }
    StableModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        dragHandle = { CompactSheetDragHandle() },
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        contentKey = draft.type,
        contentHeight = sheetContentHeight,
    ) {
        // Inside the sheet, for its own window's keyboard inset.
        val keyboard = WindowInsets.ime
        val density = LocalDensity.current
        val listState = rememberLazyListState()
        val formFocus = remember { JournalFormFocus() }
        FormAboveKeyboardEffect(listState, formFocus) { keyboard.getBottom(density) > 0 }
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                // The sheet's top edge follows the list's height. Without this a type switch
                // jumps the whole sheet to its new height in one frame.
                .animateSheetContentHeight(sheetContentHeight, ExpressiveMotion.defaultSpatial())
                .padding(horizontal = 20.dp)
                .navigationBarsPadding(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 4.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            journalSheetItem(formFocus, key = "header") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        AnimatedContent(
                            targetState = draft.type,
                            transitionSpec = {
                                fadeIn(animationSpec = ExpressiveMotion.defaultEffects())
                                    .togetherWith(fadeOut(animationSpec = ExpressiveMotion.fastEffects()))
                            },
                            label = "journalSheetTitle"
                        ) { type ->
                            Text(
                                text = type.journalActionLabel(),
                                style = MaterialTheme.typography.headlineSmallEmphasized
                            )
                        }
                        existingEntry?.source?.presentation()?.let { source ->
                            // The icon on the row only explains itself for NFC; here the
                            // origin is spelled out.
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
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
                        IconButtonTooltip(stringResource(R.string.delete)) {
                            IconButton(onClick = { onDelete?.invoke(entryId) }, shapes = IconButtonDefaults.shapes()) {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = stringResource(R.string.delete),
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }
            }

            journalSheetItem(formFocus, key = "type_selector") {
                JournalTypeSelector(
                    selectedType = draft.type,
                    onTypeSelected = {
                        draft = draft.withoutSuggestedAmount()
                            .copy(type = it)
                            .normalizedForType(unit, suggestedGlucoseMgDl, suggestedAmountFraction)
                    }
                )
            }

            journalSheetItem(formFocus, key = "date_time") {
                JournalDateTimeCard(
                    date = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(draft.timestamp)),
                    time = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(draft.timestamp)),
                    onDateClick = { showDatePicker = true },
                    onTimeClick = { showTimePicker = true },
                    onTimeLongClick = { draft = draft.copy(timestamp = System.currentTimeMillis()) }
                )
            }

            when (draft.type) {
                JournalEntryType.INSULIN -> {
                    journalSheetItem(formFocus, key = "insulin_presets") {
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
                    val windowPresets = when {
                        // Two pills: both windows stay visible, each under its own pill.
                        activeInsulinPresets.size == 2 && (
                            selectedInsulinPreset == null ||
                                activeInsulinPresets.any { it.id == selectedInsulinPreset.id }
                            ) -> activeInsulinPresets
                        selectedInsulinPreset != null -> listOf(selectedInsulinPreset)
                        else -> emptyList()
                    }
                    if (windowPresets.isNotEmpty()) {
                        journalSheetItem(formFocus, key = "insulin_window") {
                            JournalInsulinWindowCard(presets = windowPresets)
                        }
                    }
                    journalSheetItem(formFocus, key = "insulin_amount") {
                        JournalStepperField(
                            value = draft.amountText,
                            onValueChange = { draft = draft.copy(amountText = it, amountSuggested = false) },
                            onStep = { delta ->
                                draft = draft.copy(
                                    amountText = adjustDecimalDraft(draft.amountText, delta, insulinHabit.step, amountStart),
                                    amountSuggested = false
                                )
                            },
                            label = stringResource(R.string.journal_type_insulin),
                            suffix = stringResource(R.string.unit_insulin_short),
                            prominent = true,
                            placeholder = amountStart?.let(::formatFloatForEditor),
                            caption = stringResource(R.string.journal_amount_suggested),
                            showCaption = draft.amountSuggested
                        )
                    }
                    if (
                        existingEntry == null &&
                        calculatorProfile != null &&
                        selectedInsulinPreset?.useForCalculation == true
                    ) {
                        journalSheetItem(formFocus, key = "insulin_dose_assist") {
                            JournalDoseAssistCard(
                                draft = draft,
                                profile = calculatorProfile,
                                calculationInsulinPresets = calculationInsulinPresets,
                                activeInsulinUnits = activeInsulinUnits,
                                unit = unit,
                                insulinStep = insulinHabit.step,
                                carbStep = carbHabit.step,
                                onDraftChange = { draft = it }
                            )
                        }
                    }
                }

                JournalEntryType.CARBS -> {
                    if (foodLibraryEnabled) {
                        journalFoodRows(
                            row = { key, content -> journalSheetItem(formFocus, key, content) },
                            state = foodPicker,
                            suggestions = foodSuggestions,
                            items = draft.foodItems,
                            macrosEnabled = foodMacrosEnabled,
                            onlineEnabled = foodOnlineEnabled,
                            onItemsChange = { draft = draft.withFoodItems(it, foodMacrosEnabled) },
                            onAddFood = { food ->
                                foodPicker.openItem = draft.foodItems.size
                                draft = draft.withFoodItems(draft.foodItems + JournalDraftFoodItem(food, FoodMath.defaultAmount(food)), foodMacrosEnabled)
                            }
                        )
                    }
                    journalSheetItem(formFocus, key = "carbs_amount") {
                        JournalStepperField(
                            value = draft.amountText,
                            onValueChange = { draft = draft.copy(amountText = it, amountSuggested = false, recordFoodUses = false) },
                            onStep = { delta ->
                                draft = draft.copy(
                                    amountText = adjustDecimalDraft(draft.amountText, delta, carbHabit.step, amountStart),
                                    amountSuggested = false,
                                    recordFoodUses = false
                                )
                            },
                            label = stringResource(R.string.journal_type_food),
                            suffix = stringResource(R.string.unit_carbs_short),
                            prominent = true,
                            placeholder = amountStart?.let(::formatFloatForEditor),
                            caption = stringResource(R.string.journal_amount_suggested),
                            showCaption = draft.amountSuggested
                        )
                    }
                    journalSheetItem(formFocus, key = "carbs_meal_shape") {
                        JournalMealShapeSelector(
                            durationText = draft.durationText,
                            onShapeSelected = { shape ->
                                draft = draft.copy(durationText = shape.durationMinutes.toString())
                            }
                        )
                    }
                    if (foodMacrosEnabled) {
                        journalSheetItem(formFocus, key = "carbs_macros") {
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
                        !rescueCarbs &&
                        calculatorProfile != null &&
                        calculationInsulinPresets.isNotEmpty()
                    ) {
                        journalSheetItem(formFocus, key = "carbs_dose_assist") {
                            JournalDoseAssistCard(
                                draft = draft,
                                profile = calculatorProfile,
                                calculationInsulinPresets = calculationInsulinPresets,
                                activeInsulinUnits = activeInsulinUnits,
                                unit = unit,
                                insulinStep = insulinHabit.step,
                                carbStep = carbHabit.step,
                                onDraftChange = { draft = it }
                            )
                        }
                    }
                }

                JournalEntryType.FINGERSTICK -> {
                    journalSheetItem(formFocus, key = "fingerstick_amount") {
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
                    journalSheetItem(formFocus, key = "activity_name") {
                        OutlinedTextField(
                            value = draft.title,
                            onValueChange = { draft = draft.copy(title = it) },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(text = stringResource(R.string.name).trimTrailingLabel()) },
                            singleLine = true
                        )
                    }
                    journalSheetItem(formFocus, key = "activity_duration") {
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
                    journalSheetItem(formFocus, key = "activity_intensity") {
                        JournalIntensitySelector(
                            selectedIntensity = draft.intensity,
                            onIntensitySelected = { draft = draft.copy(intensity = it) }
                        )
                    }
                }

                JournalEntryType.NOTE -> {
                    journalSheetItem(formFocus, key = "note_title") {
                        OutlinedTextField(
                            value = draft.title,
                            onValueChange = { draft = draft.copy(title = it) },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(text = stringResource(R.string.name).trimTrailingLabel()) },
                            singleLine = true
                        )
                    }
                }

                // Imported from AAPS; no editor fields.
                JournalEntryType.TEMP_BASAL -> Unit
            }

            journalSheetItem(formFocus, key = "note_field") {
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
                    shape = RoundedCornerShape(16.dp),
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

            journalSheetItem(formFocus, key = "save_button") {
                // Switching type often flips Save between enabled and disabled; fade the fill
                // instead of letting it blink.
                val defaultSaveColors = ButtonDefaults.buttonColors()
                val saveContainerColor by animateColorAsState(
                    targetValue = if (canSave) defaultSaveColors.containerColor else defaultSaveColors.disabledContainerColor,
                    animationSpec = ExpressiveMotion.defaultEffects(),
                    label = "journalSaveContainer"
                )
                val saveContentColor by animateColorAsState(
                    targetValue = if (canSave) defaultSaveColors.contentColor else defaultSaveColors.disabledContentColor,
                    animationSpec = ExpressiveMotion.defaultEffects(),
                    label = "journalSaveContent"
                )
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
                    shapes = ButtonDefaults.shapes(shape = RoundedCornerShape(16.dp)),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = saveContainerColor,
                        contentColor = saveContentColor,
                        disabledContainerColor = saveContainerColor,
                        disabledContentColor = saveContentColor
                    ),
                    contentPadding = ButtonDefaults.ContentPadding
                ) {
                    AnimatedContent(
                        targetState = draft.type,
                        transitionSpec = {
                            fadeIn(animationSpec = ExpressiveMotion.defaultEffects())
                                .togetherWith(fadeOut(animationSpec = ExpressiveMotion.fastEffects()))
                        },
                        label = "journalSaveIcon"
                    ) { type ->
                        Icon(
                            imageVector = journalTypeIcon(type),
                            contentDescription = null
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
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
                },
                    shapes = ButtonDefaults.shapes(),
                contentPadding = ButtonDefaults.TextButtonContentPadding) {
                    Text(text = stringResource(R.string.ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }, shapes = ButtonDefaults.shapes(), contentPadding = ButtonDefaults.TextButtonContentPadding) {
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
                    TimePicker(state = timePickerState, shapes = TimePickerDefaults.shapes(), colors = TimePickerDefaults.colors())
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
                },
                    shapes = ButtonDefaults.shapes(),
                contentPadding = ButtonDefaults.TextButtonContentPadding) {
                    Text(text = stringResource(R.string.ok))
                }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = {
                            draft = draft.copy(timestamp = System.currentTimeMillis())
                            showTimePicker = false
                        },
                        shapes = ButtonDefaults.shapes(),
                        contentPadding = ButtonDefaults.TextButtonContentPadding
                    ) {
                        Text(text = stringResource(R.string.now))
                    }
                    TextButton(onClick = { showTimePicker = false }, shapes = ButtonDefaults.shapes(), contentPadding = ButtonDefaults.TextButtonContentPadding) {
                        Text(text = stringResource(R.string.cancel))
                    }
                }
            }
        )
    }
}

/** Which row of a form holds the keyboard focus. */
@Stable
internal class JournalFormFocus {
    var rowKey by mutableStateOf<Any?>(null)
        private set

    fun onRowFocusChanged(key: Any, state: FocusState) {
        if (state.hasFocus) {
            rowKey = key
        } else if (rowKey == key) {
            rowKey = null
        }
    }
}

/**
 * The keyboard cuts a tall form off wherever it lands, usually short of Save. This slides the form
 * up with it as far as the form's end, but stops once the row being typed in reaches the top of
 * the list: seeing that row matters more than seeing Save.
 *
 * Only the keyboard and the focused row move anything, so a scroll by hand stays where it was put.
 */
@Composable
internal fun FormAboveKeyboardEffect(
    listState: LazyListState,
    focus: JournalFormFocus,
    isKeyboardUp: () -> Boolean
) {
    val keyboardUp by rememberUpdatedState(isKeyboardUp)
    val rowGap = with(LocalDensity.current) { JournalFormRowGap.roundToPx() }
    LaunchedEffect(listState, focus) {
        var lastViewport = Int.MAX_VALUE
        var lastRow: Any? = null
        var lastRowSize: Int? = null
        snapshotFlow {
            val layout = listState.layoutInfo
            val row = focus.rowKey
            Triple(layout.viewportSize.height, row, layout.visibleItemsInfo.firstOrNull { it.key == row }?.size)
        }.collect { (viewport, row, rowSize) ->
            val keyboardRose = viewport < lastViewport
            val focusMoved = row != lastRow
            // A row scrolled off and back has no size in between; that is not it growing.
            val rowResized = rowSize != null && lastRowSize != null && rowSize != lastRowSize
            lastViewport = viewport
            lastRow = row
            lastRowSize = rowSize ?: lastRowSize.takeIf { !focusMoved }
            if (!keyboardUp() || !(keyboardRose || focusMoved || rowResized)) return@collect
            val layout = listState.layoutInfo
            val item = layout.visibleItemsInfo.firstOrNull { it.key == row } ?: return@collect
            val roomAboveRow = item.offset - layout.viewportStartOffset - rowGap
            // Not suspending: another scroll taking over must not cancel this effect.
            if (roomAboveRow > 0) listState.dispatchRawDelta(roomAboveRow.toFloat())
        }
    }
}

private val JournalFormRowGap = 12.dp

/**
 * Switching the entry type swaps the middle of the sheet. Rows that stay glide to their new place
 * on the spatial spring; rows that come or go cross-fade on the effects springs, which never
 * overshoot.
 */
private fun LazyListScope.journalSheetItem(
    focus: JournalFormFocus,
    key: String,
    content: @Composable () -> Unit
) {
    item(key = key) {
        Box(
            modifier = Modifier
                .animateItem(
                    fadeInSpec = ExpressiveMotion.defaultEffects(),
                    placementSpec = ExpressiveMotion.defaultSpatial(),
                    fadeOutSpec = ExpressiveMotion.fastEffects()
                )
                .onFocusChanged { focus.onRowFocusChanged(key, it) }
        ) {
            content()
        }
    }
}

/** A saved nutrient can recover the amount; otherwise only the saved meal totals are known. */
internal fun restoredFoodItem(food: JournalFood, entry: JournalEntry): JournalDraftFoodItem {
    val factor = listOf(
        entry.amount to food.carbsGrams,
        entry.proteinGrams to food.proteinGrams,
        entry.fatGrams to food.fatGrams
    ).firstNotNullOfOrNull { (saved, reference) ->
        if (saved != null && saved.isFinite() && saved > 0f && reference != null && reference > 0f) saved / reference else null
    }
    val matches = factor != null && listOf(
        entry.amount to food.carbsGrams,
        entry.proteinGrams to food.proteinGrams,
        entry.fatGrams to food.fatGrams
    ).all { (saved, reference) -> saved == null || reference != null && kotlin.math.abs(saved - reference * factor) <= 0.1f }
    if (matches) {
        return JournalDraftFoodItem(food.toFoodItem(), tk.glucodata.data.food.FoodAmount(factor!! * (food.referenceGrams ?: 1f)), rememberUse = false)
    }
    // One saved portion of unknown weight. Its macros are exactly the entry's totals, so
    // adding another food preserves them even if the library definition has since changed.
    val snapshot = food.toFoodItem().copy(
        key = "journal:" + entry.id,
        name = entry.title,
        referenceGrams = null,
        portions = emptyList(),
        carbs = entry.amount ?: 0f,
        protein = entry.proteinGrams,
        fat = entry.fatGrams,
        fiber = null,
        kcal = null,
        lastAmount = null
    )
    return JournalDraftFoodItem(snapshot, tk.glucodata.data.food.FoodAmount(1f), rememberUse = false)
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

/**
 * Puts [suggestion] in an amount nobody has entered, and keeps an earlier suggestion in step with
 * it. Anything the person typed, stepped or picked stays.
 */
private fun JournalEntryDraft.withSuggestedAmount(suggestion: String?): JournalEntryDraft = when {
    amountSuggested || amountText.isBlank() -> copy(
        amountText = suggestion.orEmpty(),
        amountSuggested = suggestion != null
    )
    else -> this
}

private fun JournalEntryDraft.withoutSuggestedAmount(): JournalEntryDraft =
    if (amountSuggested) copy(amountText = "", amountSuggested = false) else this

/**
 * The draft with its meal set to [items]: the amount, the macros and the title follow the
 * foods, and so does the time the meal takes, unless every food says its own.
 */
internal fun JournalEntryDraft.withFoodItems(
    items: List<JournalDraftFoodItem>,
    foodMacrosEnabled: Boolean
): JournalEntryDraft {
    if (items.isEmpty()) {
        return copy(
            foodId = null,
            title = "",
            amountText = "",
            amountSuggested = false,
            proteinText = "",
            fatText = "",
            durationText = JournalMealShape.MIXED.durationMinutes.toString(),
            foodItems = emptyList()
        )
    }
    val nutrients = items.map { it.nutrients }
    val carbs = nutrients.sumOf { it.carbs.toDouble() }.toFloat()
    val protein = nutrients.sumOf { (it.protein ?: 0f).toDouble() }.toFloat()
    val fat = nutrients.sumOf { (it.fat ?: 0f).toDouble() }.toFloat()
    val ownDurations = items.mapNotNull { it.food.absorptionMinutes }
    val duration = if (ownDurations.size == items.size) {
        ownDurations.max()
    } else {
        maxOf(ownDurations.maxOrNull() ?: 0, FoodMath.estimatedAbsorptionMinutes(carbs, protein, fat))
    }
    return copy(
        foodId = items.singleOrNull()?.food?.libraryId,
        title = items.joinToString(" + ") { it.food.name.trim() }.take(80),
        amountText = formatFloatForEditor(roundToTenth(carbs)),
        amountSuggested = false,
        proteinText = if (foodMacrosEnabled) formatFloatForEditor(roundToTenth(protein)) else "",
        fatText = if (foodMacrosEnabled) formatFloatForEditor(roundToTenth(fat)) else "",
        durationText = duration.coerceIn(15, 480).toString(),
        foodItems = items,
        recordFoodUses = true
    )
}

private fun roundToTenth(value: Float): Float = (value * 10f).roundToInt() / 10f

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

        JournalEntryType.NOTE,
        JournalEntryType.TEMP_BASAL -> copy(
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

internal fun JournalEntryDraft.toInput(
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
                fatGrams = if (foodMacrosEnabled) fatText.parseFloatOrNull()?.coerceAtLeast(0f) else null,
                // A food still at zero was never filled in; the library has nothing to learn from it.
                foodUses = foodItems.takeIf { recordFoodUses }.orEmpty()
                    .filter { it.rememberUse && it.amount.quantity > 0f && it.food.name.isNotBlank() }
                    .map { JournalFoodUse(it.food, it.amount) }
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

        // Imported from AAPS; never entered or edited in the journal sheet.
        JournalEntryType.TEMP_BASAL -> null
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
    insulinStep: Float,
    carbStep: Float,
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
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.journal_dose_math_title),
                    style = MaterialTheme.typography.labelLargeEmphasized,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = stringResource(R.string.predictive_carb_ratio_value, tk.glucodata.ui.formatCarbRatio(profile.carbRatioGramsPerUnit)),
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
                        style = MaterialTheme.typography.titleMediumEmphasized,
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
                                stringResource(R.string.unit_insulin_value, suggestion?.foodInsulinUnits?.let(::formatInsulinComponent) ?: "—")
                            )
                        )
                        if (suggestion != null && suggestion.correctionInsulinUnits > 0f) {
                            JournalDoseMetric(
                                label = stringResource(
                                    R.string.journal_dose_correction_component,
                                    stringResource(R.string.unit_insulin_value, formatInsulinComponent(suggestion.correctionInsulinUnits))
                                )
                            )
                        }
                        if (suggestion != null && suggestion.activeInsulinCreditUnits > 0f) {
                            JournalDoseMetric(
                                label = "${stringResource(R.string.IOB)} -" +
                                        stringResource(R.string.unit_insulin_value, formatInsulinComponent(suggestion.activeInsulinCreditUnits))
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
                        style = MaterialTheme.typography.titleMediumEmphasized,
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
                ),
                shapes = ButtonDefaults.shapes(),
                contentPadding = ButtonDefaults.ContentPadding
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
                        val step = if (draft.type == JournalEntryType.CARBS) insulinStep else carbStep
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
                    suffix = if (draft.type == JournalEntryType.CARBS) stringResource(R.string.unit_insulin_short) else stringResource(R.string.unit_carbs_short)
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
private fun JournalMacroFields(
    proteinText: String,
    fatText: String,
    onProteinChange: (String) -> Unit,
    onFatChange: (String) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        JournalStepperField(
            value = proteinText,
            onValueChange = onProteinChange,
            onStep = { delta -> onProteinChange(adjustDecimalDraft(proteinText, delta, step = 5f)) },
            label = stringResource(R.string.journal_food_protein),
            suffix = stringResource(R.string.unit_carbs_short)
        )
        JournalStepperField(
            value = fatText,
            onValueChange = onFatChange,
            onStep = { delta -> onFatChange(adjustDecimalDraft(fatText, delta, step = 5f)) },
            label = stringResource(R.string.journal_food_fat),
            suffix = stringResource(R.string.unit_carbs_short)
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
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
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
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        actions.forEach { action ->
            FilledTonalButton(
                onClick = { onAction(action) },
                modifier = Modifier.weight(1f),
                shapes = ButtonDefaults.shapes(shape = RoundedCornerShape(16.dp)),
                contentPadding = ButtonDefaults.ContentPadding,
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
        options = JournalEntryType.journalRowTypes,
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
                label = { Text(text = stringResource(intensity.labelRes())) },
                shapes = FilterChipDefaults.shapes(),
                colors = FilterChipDefaults.filterChipColors()
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
                style = MaterialTheme.typography.titleMediumEmphasized,
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
        // ui-guardrails: allow-begin offgrid_spacing, offscale_radius - journal chip metrics as tuned in #572; the chart's chip layout measures them
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
                            style = if (chipContent.value == null) MaterialTheme.typography.labelLargeEmphasized else MaterialTheme.typography.labelLarge,
                            color = if (chipContent.value == null) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            maxLines = 2,
                            softWrap = true,
                            overflow = TextOverflow.Ellipsis
                        )
                        chipContent.value?.let { value ->
                            Text(
                                text = value,
                                style = MaterialTheme.typography.titleSmallEmphasized.copy(fontFeatureSettings = "tnum"),
                                color = MaterialTheme.colorScheme.onSurface,
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
                // ui-guardrails: allow-end
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
            val amount = entry.amount?.let { Applic.app.getString(R.string.unit_insulin_value, formatFloatForEditor(it)) }
            val label = insulinPreset?.displayName.cleanJournalChipText()
                ?: entry.title.cleanJournalChipText()
                ?: journalMarkerDetail(entry, insulinPreset, unit)
            JournalInlineChipContent(
                label = label,
                value = amount?.takeUnless { it == label }
            )
        }

        JournalEntryType.CARBS -> {
            val amount = entry.amount?.let { Applic.app.getString(R.string.unit_carbs_value, formatFloatForEditor(it)) }
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

        JournalEntryType.TEMP_BASAL -> {
            JournalInlineChipContent(
                label = entry.amount?.let { Applic.app.getString(R.string.journal_rate_units_per_hour, formatFloatForEditor(it)) } ?: entry.title,
                detail = entry.durationMinutes?.let { Applic.app.getString(R.string.minutes_short_format, it) }
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
    // ui-guardrails: allow-begin offgrid_spacing, offscale_radius - journal chip metrics as tuned in #572; the chart's chip layout measures them
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
                // ui-guardrails: allow-end
                Text(
                    text = if (entry.type == JournalEntryType.INSULIN) {
                        insulinPreset?.displayName ?: entry.title
                    } else {
                        entry.title
                    },
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
    onTimeClick: () -> Unit,
    onTimeLongClick: () -> Unit
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
                onClick = onTimeClick,
                onLongClick = onTimeLongClick,
                onLongClickLabel = stringResource(R.string.journal_quickadd_always_now_title)
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
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    onLongClickLabel: String? = null
) {
    val haptics = LocalHapticFeedback.current
    Row(
        modifier = modifier
            .heightIn(min = 56.dp)
            .semantics(mergeDescendants = true) {
                this.contentDescription = "$contentDescription, $value"
                role = Role.Button
            }
            .combinedClickable(
                onClick = onClick,
                onLongClickLabel = onLongClickLabel,
                onLongClick = onLongClick?.let { longClick ->
                    {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        longClick()
                    }
                }
            )
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

/**
 * One preset: the selected insulin's window. Two: each window sits under its own pill (same
 * 8dp split as the pill row) and the card shows one curve at a time, switching between them.
 */
@Composable
private fun JournalInsulinWindowCard(
    presets: List<JournalInsulinPreset>
) {
    var openPresetId by remember { mutableStateOf<Long?>(null) }
    val paired = presets.size > 1
    val openPreset = when {
        openPresetId == null -> null
        // A lone window follows the selected preset; the open state carries over.
        !paired -> presets.first()
        else -> presets.firstOrNull { it.id == openPresetId }
    }
    val expanded = openPreset != null
    val cardColor by animateColorAsState(
        targetValue = MaterialTheme.colorScheme.surfaceContainerHigh.copy(
            alpha = if (expanded) 1f else 0f
        ),
        animationSpec = tween(durationMillis = 180),
        label = "journalInsulinWindowCard"
    )

    Surface(
        modifier = Modifier
            .animateContentSize(
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    // Collapsing is a dismissal; it settles a touch quicker than opening.
                    stiffness = if (expanded) Spring.StiffnessMediumLow else Spring.StiffnessMedium
                )
            )
            .then(
                if (expanded || paired) Modifier.fillMaxWidth() else Modifier.wrapContentWidth()
            ),
        color = cardColor,
        shape = RoundedCornerShape(16.dp)
    ) {
        Column {
            // The headers' 12dp padding puts each range where its pill's name starts
            // (12 + 20 + 8 = 40), for both halves of the 8dp-split pill row.
            Row(
                modifier = if (expanded || paired) Modifier.fillMaxWidth() else Modifier,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                presets.forEach { preset ->
                    JournalInsulinWindowHeader(
                        preset = preset,
                        showing = openPreset?.id == preset.id,
                        expanded = expanded,
                        modifier = if (paired) Modifier.weight(1f) else Modifier,
                        onClick = {
                            openPresetId = if (openPreset?.id == preset.id) null else preset.id
                        }
                    )
                }
            }
            if (openPreset != null) {
                AnimatedContent(
                    targetState = openPreset,
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
                        contentDescription = journalInsulinWindowDescription(visiblePreset),
                        // Keeps the chart off the header tabs.
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun JournalInsulinWindowHeader(
    preset: JournalInsulinPreset,
    showing: Boolean,
    expanded: Boolean,
    modifier: Modifier,
    onClick: () -> Unit
) {
    val accent by animateColorAsState(
        targetValue = Color(preset.accentColor),
        animationSpec = tween(durationMillis = 220),
        label = "journalInsulinWindowAccent"
    )
    // Compact under the pills, full 48dp header once the card is open; the spring grows it
    // with the card instead of snapping.
    val height by animateDpAsState(
        targetValue = if (expanded) 48.dp else 32.dp,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = if (expanded) Spring.StiffnessMediumLow else Spring.StiffnessMedium
        ),
        label = "journalInsulinWindowHeaderHeight"
    )
    val containerColor by animateColorAsState(
        targetValue = Color(preset.accentColor).copy(alpha = if (showing) 0.16f else 0f),
        animationSpec = tween(durationMillis = 180),
        label = "journalInsulinWindowHeader"
    )
    val infoTint by animateColorAsState(
        // At rest the range is the content; the icon only has to be findable.
        targetValue = if (showing) accent else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f),
        animationSpec = tween(durationMillis = 180),
        label = "journalInsulinWindowInfo"
    )
    val onset = formatJournalWindowEndpoint(preset.onsetMinutes)
    val duration = formatJournalWindowEndpoint(preset.durationMinutes)
    val summary = "$onset – $duration"
    val windowDescription = journalInsulinWindowDescription(preset)
    val summaryStyle = MaterialTheme.typography.labelLarge

    Row(
        // Compose expands the compact row's pointer hit area to the platform minimum. The
        // 16dp corners match the card's, so the open header reads as its tab.
        modifier = modifier
            .height(height)
            .clip(RoundedCornerShape(16.dp))
            .background(containerColor)
            .semantics(mergeDescendants = true) {
                this.contentDescription = windowDescription
                role = Role.Button
                selected = showing
            }
            .clickable(onClick = onClick)
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
            label = "journalInsulinWindowSummary",
            modifier = Modifier.weight(1f, fill = false)
        ) { value ->
            // Half a narrow phone can be a few dp short of "21 min – 330 min"; the label
            // gives up a little size rather than its tail.
            Text(
                text = value,
                style = summaryStyle,
                autoSize = TextAutoSize.StepBased(
                    minFontSize = 11.sp,
                    maxFontSize = summaryStyle.fontSize,
                    stepSize = 0.25.sp
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                softWrap = false
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Icon(
            imageVector = Icons.Default.Info,
            contentDescription = null,
            tint = infoTint,
            modifier = Modifier.size(16.dp)
        )
    }
}

/** Spoken label for one insulin's window; with two side by side, the range alone is ambiguous. */
@Composable
private fun journalInsulinWindowDescription(preset: JournalInsulinPreset): String {
    val window = stringResource(
        R.string.journal_active_window,
        stringResource(R.string.minutes_short_format, preset.onsetMinutes),
        stringResource(R.string.minutes_short_format, preset.durationMinutes)
    )
    return "${preset.displayName}, $window"
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
internal fun JournalStepperField(
    value: String,
    onValueChange: (String) -> Unit,
    onStep: (Int) -> Unit,
    label: String,
    suffix: String?,
    keyboardType: KeyboardType = KeyboardType.Decimal,
    prominent: Boolean = false,
    /** Shown dim in an empty prominent field, in place of 0. */
    placeholder: String? = null,
    /** A word under a prominent field's value, saying where the value came from. */
    caption: String = "",
    showCaption: Boolean = false
) {
    val haptics = LocalHapticFeedback.current
    val stepWithFeedback: (Int) -> Unit = { delta ->
        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        onStep(delta)
    }
    val field = rememberSelectAllOnFocusField(value)
    Surface(
        color = if (prominent) {
            MaterialTheme.colorScheme.surfaceContainerHigh
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        },
        shape = RoundedCornerShape(if (prominent) 28.dp else 20.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = if (prominent) 12.dp else 12.dp,
                    vertical = if (prominent) 8.dp else 12.dp
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            JournalStepButton(
                icon = Icons.Default.Remove,
                description = stringResource(R.string.outbound_api_decrease_value),
                prominent = prominent,
                onStep = { stepWithFeedback(-1) }
            )
            if (prominent) {
                BasicTextField(
                    value = field.value,
                    onValueChange = { field.onValueChange(it, onValueChange) },
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 64.dp)
                        .onFocusChanged(field::onFocusChanged),
                    textStyle = MaterialTheme.typography.displaySmallEmphasized.copy(
                        color = MaterialTheme.colorScheme.onSurface,
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
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(horizontal = 8.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Box(
                                    modifier = Modifier.fillMaxWidth(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (value.isEmpty()) {
                                        Text(
                                            text = placeholder ?: "0",
                                            style = MaterialTheme.typography.displaySmallEmphasized.copy(
                                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                                textAlign = TextAlign.Center
                                            )
                                        )
                                    }
                                    innerTextField()
                                }
                                AnimatedVisibility(
                                    visible = showCaption,
                                    enter = fadeIn(ExpressiveMotion.defaultEffects()) + expandVertically(),
                                    exit = fadeOut(ExpressiveMotion.fastEffects()) + shrinkVertically()
                                ) {
                                    Text(
                                        text = caption,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            suffix?.let {
                                Text(
                                    text = it,
                                    style = MaterialTheme.typography.titleLargeEmphasized,
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
                        value = field.value,
                        onValueChange = { field.onValueChange(it, onValueChange) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                            .onFocusChanged(field::onFocusChanged),
                        textStyle = MaterialTheme.typography.titleLargeEmphasized.copy(
                            color = MaterialTheme.colorScheme.onSurface,
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
                                                style = MaterialTheme.typography.titleLargeEmphasized.copy(
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.42f),
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
            JournalStepButton(
                icon = Icons.Default.Add,
                description = stringResource(R.string.outbound_api_increase_value),
                prominent = prominent,
                onStep = { stepWithFeedback(1) }
            )
        }
    }
}

/**
 * A stepper button. Tapping steps once; holding steps on its own, quicker the longer it is held.
 * Holding is the button's own gesture here, so the usual long-press tooltip stays out of it.
 */
@Composable
private fun JournalStepButton(
    icon: ImageVector,
    description: String,
    prominent: Boolean,
    onStep: () -> Unit
) {
    val step by rememberUpdatedState(onStep)
    val interactionSource = remember { MutableInteractionSource() }
    // Lifting the finger still reports a click, which must not add one more to a hold.
    val holdStepped = remember { booleanArrayOf(false) }
    LaunchedEffect(interactionSource) {
        var hold: Job? = null
        interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is PressInteraction.Press -> {
                    holdStepped[0] = false
                    hold?.cancel()
                    hold = launch {
                        delay(JOURNAL_STEP_HOLD_MILLIS)
                        var repeats = 0
                        while (true) {
                            holdStepped[0] = true
                            step()
                            delay(journalStepRepeatMillis(repeats++))
                        }
                    }
                }
                is PressInteraction.Release -> hold?.cancel()
                is PressInteraction.Cancel -> {
                    hold?.cancel()
                    holdStepped[0] = false
                }
            }
        }
    }
    IconButtonTooltip(description, showOnLongPress = false) {
        FilledTonalIconButton(
            onClick = {
                if (holdStepped[0]) holdStepped[0] = false else step()
            },
            modifier = if (prominent) Modifier.size(56.dp) else Modifier,
            shapes = IconButtonDefaults.shapes(shape = RoundedCornerShape(if (prominent) 16.dp else 12.dp)),
            interactionSource = interactionSource
        ) {
            Icon(imageVector = icon, contentDescription = description)
        }
    }
}

private const val JOURNAL_STEP_HOLD_MILLIS = 400L
private const val JOURNAL_STEP_SLOWEST_MILLIS = 180L
private const val JOURNAL_STEP_FASTEST_MILLIS = 60L
private const val JOURNAL_STEP_RAMP_MILLIS = 10L

/** Slow enough at first to stop on the value next door, then about 16 steps a second. */
private fun journalStepRepeatMillis(repeats: Int): Long =
    (JOURNAL_STEP_SLOWEST_MILLIS - repeats * JOURNAL_STEP_RAMP_MILLIS)
        .coerceAtLeast(JOURNAL_STEP_FASTEST_MILLIS)

/** The dashboard hint is about now; a dose dated further away than this isn't what it computed. */
private const val JOURNAL_HINT_MAX_AGE_MILLIS = 15L * 60 * 1000

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
            listOfNotNull(Applic.app.getString(R.string.unit_insulin_value, amount).takeIf { amount.isNotBlank() }, window, entry.note).joinToString(" · ")
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
            listOfNotNull(amount.takeIf { it.isNotBlank() }?.let { Applic.app.getString(R.string.unit_carbs_value, it) }, protein, fat, curve, entry.note)
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

        JournalEntryType.TEMP_BASAL -> {
            listOfNotNull(
                entry.amount?.let { Applic.app.getString(R.string.journal_rate_units_per_hour, formatFloatForEditor(it)) },
                entry.durationMinutes?.let { Applic.app.getString(R.string.minutes_short_format, it) }
            ).joinToString(" · ")
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
        JournalEntryType.TEMP_BASAL -> "T"
    }
}

private fun journalMarkerDetail(
    entry: JournalEntry,
    insulinPreset: JournalInsulinPreset?,
    unit: String
): String {
    return when (entry.type) {
        JournalEntryType.INSULIN, JournalEntryType.CARBS -> {
            entry.amount?.let { journalMarkerAmountText(entry.type, it) }.orEmpty()
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

        JournalEntryType.TEMP_BASAL -> {
            entry.amount?.let { Applic.app.getString(R.string.journal_rate_units_per_hour, formatFloatForEditor(it)) }.orEmpty()
        }
    }
}

/** An insulin or carbs amount as its chart chip shows it, such as "2 U"; null for other types. */
internal fun journalMarkerAmountText(type: JournalEntryType, amount: Float): String? = when (type) {
    JournalEntryType.INSULIN -> Applic.app.getString(R.string.unit_insulin_value, formatFloatForEditor(amount))
    JournalEntryType.CARBS -> Applic.app.getString(R.string.unit_carbs_value, formatFloatForEditor(amount))
    else -> null
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
        JournalEntryType.TEMP_BASAL -> Icons.Default.Vaccines
    }
}

private fun JournalEntryType.labelRes(): Int {
    return when (this) {
        JournalEntryType.INSULIN -> R.string.journal_type_insulin
        JournalEntryType.CARBS -> R.string.journal_type_food
        JournalEntryType.FINGERSTICK -> R.string.journal_type_fingerstick
        JournalEntryType.ACTIVITY -> R.string.journal_type_activity
        JournalEntryType.NOTE -> R.string.journal_type_note
        JournalEntryType.TEMP_BASAL -> R.string.journal_type_temp_basal
    }
}

private fun JournalIntensity.labelRes(): Int {
    return when (this) {
        JournalIntensity.LIGHT -> R.string.journal_intensity_light
        JournalIntensity.MODERATE -> R.string.journal_intensity_moderate
        JournalIntensity.INTENSE -> R.string.journal_intensity_intense
    }
}

/** [start] is where an empty field begins: plus lands on it, minus one step below it. */
private fun adjustDecimalDraft(value: String, direction: Int, step: Float, start: Float? = null): String {
    val current = value.parseFloatOrNull()
    if (current == null && start != null) {
        return formatFloatForEditor(if (direction > 0) start else JournalAmountAssist.stepped(start, direction, step))
    }
    return formatFloatForEditor(JournalAmountAssist.stepped(current ?: 0f, direction, step))
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

/**
 * A number field's text, kept so that focusing the field selects all of it: a typed amount
 * replaces the old one instead of landing beside it. The caller's text stays the source of
 * truth; the steppers change it from outside.
 */
@Stable
private class SelectAllOnFocusField(text: String) {
    var value by mutableStateOf(TextFieldValue(text))
        private set

    // The tap that focuses the field places the cursor straight after; that must not undo it.
    private var keepSelectionOnce = false

    fun sync(text: String) {
        if (text != value.text) value = TextFieldValue(text, TextRange(text.length))
    }

    fun onFocusChanged(state: FocusState) {
        keepSelectionOnce = state.isFocused
        if (state.isFocused) value = value.copy(selection = TextRange(0, value.text.length))
    }

    fun onValueChange(newValue: TextFieldValue, onTextChange: (String) -> Unit) {
        val keepSelection = keepSelectionOnce && newValue.text == value.text
        keepSelectionOnce = false
        if (keepSelection) return
        value = newValue
        onTextChange(newValue.text)
    }
}

@Composable
private fun rememberSelectAllOnFocusField(text: String): SelectAllOnFocusField {
    val field = remember { SelectAllOnFocusField(text) }
    field.sync(text)
    return field
}
