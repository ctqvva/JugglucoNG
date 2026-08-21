@file:OptIn(ExperimentalMaterial3Api::class)

package tk.glucodata.ui

import android.app.Activity
import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.LegendToggle
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.LegendToggle
import androidx.compose.material.icons.outlined.Sensors
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import tk.glucodata.R
import tk.glucodata.SensorIdentity
import tk.glucodata.data.journal.JournalEntry
import tk.glucodata.data.journal.JournalEntryType
import tk.glucodata.ui.journal.JournalDoseProfile
import tk.glucodata.ui.journal.JournalEntrySheet
import tk.glucodata.ui.journal.JournalFoodLibraryScreen
import tk.glucodata.ui.journal.JournalInsulinLibraryScreen
import tk.glucodata.ui.journal.JournalScreen
import tk.glucodata.ui.journal.JournalSettingsScreen
import tk.glucodata.ui.viewmodel.DashboardViewModel

sealed class CalibrationSheetState {
    object Hidden : CalibrationSheetState()
    data class New(
        val auto: Float,
        val raw: Float,
        val timestamp: Long,
        val sensorId: String,
        val viewModeOverride: Int? = null
    ) : CalibrationSheetState()
    data class Edit(val entity: tk.glucodata.data.calibration.CalibrationEntity) : CalibrationSheetState()
}

internal data class JournalEditorRequest(
    val type: JournalEntryType,
    val timestamp: Long,
    val existingEntry: JournalEntry? = null,
    val suggestedGlucoseMgDl: Float? = null,
    val suggestedChartAnchorGlucoseMgDl: Float? = null,
    val suggestedAmountFraction: Float? = null,
    /** A new entry started from a meal's "eaten" step, with the amounts already filled in. */
    val prefill: tk.glucodata.data.journal.JournalEntryInput? = null
)

@Composable
private fun DashboardRoute(
    dashboardViewModel: DashboardViewModel,
    navController: androidx.navigation.NavController,
    onTriggerCalibration: (CalibrationSheetState) -> Unit
) {
    val calibrations by tk.glucodata.data.calibration.CalibrationManager.calibrations.collectAsStateWithLifecycle()

    DashboardScreen(
        viewModel = dashboardViewModel,
        calibrations = calibrations,
        onNavigateToCalibrations = { navController.navigate("calibrations") },
        onNavigateToHistory = { navController.navigate("history") },
        onNavigateToMqAccount = { navController.navigate("settings/mq-account") },
        onNavigateToReadiness = { navController.navigate("settings/cgm-readiness") },
        onNavigateToAppUpdates = { navController.navigate("settings/app-updates") },
        onTriggerCalibration = onTriggerCalibration
    )
}

@Composable
private fun HistoryRoute(
    dashboardViewModel: DashboardViewModel,
    title: String,
    browseMode: TimelineBrowseMode,
    onBack: (() -> Unit)?,
    onTriggerCalibration: (CalibrationSheetState) -> Unit,
//    initialShowReadingRows: Boolean = true
) {
    // Use the merged multi-sensor flow so the previous sensor's calibrated
    // readings (and any imported/older sensor data) remain visible on the
    // History screen across sensor swaps. The dashboard chart still uses the
    // narrower per-sensor [glucoseHistory] flow above.
    val mergedGlucoseHistory by dashboardViewModel.historyScreenGlucoseHistory.collectAsStateWithLifecycle()
    val dashboardGlucoseHistory by dashboardViewModel.glucoseHistory.collectAsStateWithLifecycle()
    val glucoseHistory = mergedGlucoseHistory.ifEmpty { dashboardGlucoseHistory }
    val unit by dashboardViewModel.unit.collectAsStateWithLifecycle()
    val viewMode by dashboardViewModel.viewMode.collectAsStateWithLifecycle()
    val sensorName by dashboardViewModel.sensorName.collectAsStateWithLifecycle()
    val graphLow by dashboardViewModel.graphLow.collectAsStateWithLifecycle()
    val graphHigh by dashboardViewModel.graphHigh.collectAsStateWithLifecycle()
    val targetLow by dashboardViewModel.targetLow.collectAsStateWithLifecycle()
    val targetHigh by dashboardViewModel.targetHigh.collectAsStateWithLifecycle()
    val chartSmoothingMinutes by dashboardViewModel.chartSmoothingMinutes.collectAsStateWithLifecycle()
    val dataSmoothingCollapseChunks by dashboardViewModel.dataSmoothingCollapseChunks.collectAsStateWithLifecycle()
    val dataSmoothingExchangeOnly by dashboardViewModel.dataSmoothingExchangeOnly.collectAsStateWithLifecycle()
    val visualSmoothingMinutes = if (dataSmoothingExchangeOnly) 0 else chartSmoothingMinutes
    val previewWindowMode by dashboardViewModel.previewWindowMode.collectAsStateWithLifecycle()
    val journalEnabled by dashboardViewModel.journalEnabled.collectAsStateWithLifecycle()
    val journalDoseCalculatorEnabled by dashboardViewModel.journalDoseCalculatorEnabled.collectAsStateWithLifecycle()
    val journalFoodMacrosEnabled by dashboardViewModel.journalFoodMacrosEnabled.collectAsStateWithLifecycle()
    val journalEntries by dashboardViewModel.journalEntries.collectAsStateWithLifecycle()
    val journalInsulinPresets by dashboardViewModel.journalInsulinPresets.collectAsStateWithLifecycle()
    val journalFoods by dashboardViewModel.journalFoods.collectAsStateWithLifecycle()
    val journalQuickAddAlwaysNow by dashboardViewModel.journalQuickAddAlwaysNow.collectAsStateWithLifecycle()
    val appChartRangeColorsEnabled by dashboardViewModel.glucoseAppChartRangeColorsEnabled.collectAsStateWithLifecycle()
    val predictionCarbRatioGramsPerUnit by dashboardViewModel.predictionCarbRatioGramsPerUnit.collectAsStateWithLifecycle()
    val predictionInsulinSensitivityMgDlPerUnit by dashboardViewModel.predictionInsulinSensitivityMgDlPerUnit.collectAsStateWithLifecycle()
    val predictionModelProfile by dashboardViewModel.predictionModelProfile.collectAsStateWithLifecycle()
    val predictionDoseTargetMgDl by dashboardViewModel.predictionDoseTargetMgDl.collectAsStateWithLifecycle()
    val calibrations by tk.glucodata.data.calibration.CalibrationManager.calibrations.collectAsStateWithLifecycle()
    var journalEditorRequest by remember { mutableStateOf<JournalEditorRequest?>(null) }
    var lastJournalType by rememberSaveable { mutableStateOf(JournalEntryType.INSULIN) }

    // Journal entries are time-bound user events (insulin, food, fingersticks);
    // they must stay visible across sensor swaps. The History route deliberately
    // does NOT scope them by the current sensor — otherwise the previous
    // sensor's period goes empty as soon as a replacement is paired.
    val scopedJournalEntries = journalEntries

    HistoryBrowseScreen(
        glucoseHistory = glucoseHistory,
        unit = unit,
        viewMode = viewMode,
        sensorId = sensorName,
        graphLow = graphLow,
        graphHigh = graphHigh,
        targetLow = targetLow,
        targetHigh = targetHigh,
        graphSmoothingMinutes = visualSmoothingMinutes,
        collapseSmoothedData = dataSmoothingCollapseChunks,
        previewWindowMode = previewWindowMode,
        calibrations = calibrations,
        title = title,
        browseMode = browseMode,
        chartRangeColors = appChartRangeColorsEnabled,
//        initialShowReadingRows = initialShowReadingRows,
        journalEnabled = journalEnabled,
        journalEntries = scopedJournalEntries,
        journalInsulinPresets = journalInsulinPresets,
        journalFoods = journalFoods,
        quickAddAlwaysNow = journalQuickAddAlwaysNow,
        onBack = onBack,
        onPointClick = { point ->
            onTriggerCalibration(
                CalibrationSheetState.New(
                    point.value,
                    point.rawValue,
                    point.timestamp,
                    point.sensorSerial?.takeIf { it.isNotBlank() } ?: sensorName
                )
            )
        },
        onDeleteReading = { point ->
            dashboardViewModel.deleteHistoryReading(point, sensorName)
        },
        onJournalEntryClick = { entry ->
            lastJournalType = entry.type
            journalEditorRequest = JournalEditorRequest(entry.type, entry.timestamp, entry)
        },
        onAddJournalEntry = { timestamp, suggestedType, suggestedDisplayGlucose ->
            val type = suggestedType ?: lastJournalType
            val suggestedGlucoseMgDl = suggestedDisplayGlucose?.let {
                if (tk.glucodata.ui.util.GlucoseFormatter.isMmol(unit)) {
                    tk.glucodata.ui.util.GlucoseFormatter.mmolToMg(it)
                } else {
                    it
                }
            }
            lastJournalType = type
            journalEditorRequest = JournalEditorRequest(
                type = type,
                timestamp = timestamp,
                suggestedGlucoseMgDl = suggestedGlucoseMgDl,
                suggestedChartAnchorGlucoseMgDl = suggestedGlucoseMgDl
                    .takeIf { type == JournalEntryType.FINGERSTICK }
            )
        }
    )

    journalEditorRequest?.let { request ->
        JournalEntrySheet(
            unit = unit,
            selectedTimestamp = request.timestamp,
            suggestedGlucoseMgDl = request.suggestedGlucoseMgDl,
            suggestedChartAnchorGlucoseMgDl = request.suggestedChartAnchorGlucoseMgDl,
            suggestedAmountFraction = request.suggestedAmountFraction,
            insulinPresets = journalInsulinPresets,
            foods = journalFoods,
            foodMacrosEnabled = journalFoodMacrosEnabled,
            doseJournalEntries = scopedJournalEntries,
            doseProfile = JournalDoseProfile(
                enabled = journalEnabled && journalDoseCalculatorEnabled,
                carbRatioGramsPerUnit = predictionCarbRatioGramsPerUnit,
                insulinSensitivityMgDlPerUnit = predictionInsulinSensitivityMgDlPerUnit,
                foodMacrosEnabled = journalFoodMacrosEnabled,
                modelProfile = predictionModelProfile,
                targetMgDl = predictionDoseTargetMgDl
            ),
            initialType = request.type,
            existingEntry = request.existingEntry,
            onDismiss = { journalEditorRequest = null },
            onSave = { input ->
                dashboardViewModel.saveJournalEntry(input)
                lastJournalType = input.type
                journalEditorRequest = null
            },
            onSaveEntries = { inputs ->
                inputs.forEach(dashboardViewModel::saveJournalEntry)
                inputs.firstOrNull()?.let { lastJournalType = it.type }
                journalEditorRequest = null
            },
            onSaveFood = dashboardViewModel::saveJournalFood,
            onDelete = { entryId ->
                dashboardViewModel.deleteJournalEntry(entryId)
                journalEditorRequest = null
            },
            sensorSerialProvider = { sensorName.ifBlank { null } }
        )
    }
}

@Composable
private fun JournalRoute(
    dashboardViewModel: DashboardViewModel,
    navController: androidx.navigation.NavController,
    onTriggerCalibration: (CalibrationSheetState) -> Unit,
    modifier: Modifier = Modifier,
    showTitle: Boolean = true,
    useStatusBarsPadding: Boolean = true,
    bottomContentPadding: Dp = 104.dp
) {
    val mergedGlucoseHistory by dashboardViewModel.historyScreenGlucoseHistory.collectAsStateWithLifecycle()
    val dashboardGlucoseHistory by dashboardViewModel.glucoseHistory.collectAsStateWithLifecycle()
    val glucoseHistory = mergedGlucoseHistory.ifEmpty { dashboardGlucoseHistory }
    val unit by dashboardViewModel.unit.collectAsStateWithLifecycle()
    val viewMode by dashboardViewModel.viewMode.collectAsStateWithLifecycle()
    val sensorName by dashboardViewModel.sensorName.collectAsStateWithLifecycle()
    val graphLow by dashboardViewModel.graphLow.collectAsStateWithLifecycle()
    val graphHigh by dashboardViewModel.graphHigh.collectAsStateWithLifecycle()
    val targetLow by dashboardViewModel.targetLow.collectAsStateWithLifecycle()
    val targetHigh by dashboardViewModel.targetHigh.collectAsStateWithLifecycle()
    val chartSmoothingMinutes by dashboardViewModel.chartSmoothingMinutes.collectAsStateWithLifecycle()
    val dataSmoothingCollapseChunks by dashboardViewModel.dataSmoothingCollapseChunks.collectAsStateWithLifecycle()
    val dataSmoothingExchangeOnly by dashboardViewModel.dataSmoothingExchangeOnly.collectAsStateWithLifecycle()
    val visualSmoothingMinutes = if (dataSmoothingExchangeOnly) 0 else chartSmoothingMinutes
    val previewWindowMode by dashboardViewModel.previewWindowMode.collectAsStateWithLifecycle()
    val journalEnabled by dashboardViewModel.journalEnabled.collectAsStateWithLifecycle()
    val journalDoseCalculatorEnabled by dashboardViewModel.journalDoseCalculatorEnabled.collectAsStateWithLifecycle()
    val journalFoodMacrosEnabled by dashboardViewModel.journalFoodMacrosEnabled.collectAsStateWithLifecycle()
    val journalEntries by dashboardViewModel.journalEntries.collectAsStateWithLifecycle()
    val journalInsulinPresets by dashboardViewModel.journalInsulinPresets.collectAsStateWithLifecycle()
    val journalFoods by dashboardViewModel.journalFoods.collectAsStateWithLifecycle()
    val journalEiobDisplayEnabled by dashboardViewModel.journalEiobDisplayEnabled.collectAsStateWithLifecycle()
    val journalQuickAddAlwaysNow by dashboardViewModel.journalQuickAddAlwaysNow.collectAsStateWithLifecycle()
    val appChartRangeColorsEnabled by dashboardViewModel.glucoseAppChartRangeColorsEnabled.collectAsStateWithLifecycle()
    val predictionCarbRatioGramsPerUnit by dashboardViewModel.predictionCarbRatioGramsPerUnit.collectAsStateWithLifecycle()
    val predictionInsulinSensitivityMgDlPerUnit by dashboardViewModel.predictionInsulinSensitivityMgDlPerUnit.collectAsStateWithLifecycle()
    val predictionModelProfile by dashboardViewModel.predictionModelProfile.collectAsStateWithLifecycle()
    val predictionDoseTargetMgDl by dashboardViewModel.predictionDoseTargetMgDl.collectAsStateWithLifecycle()
    val calibrations by tk.glucodata.data.calibration.CalibrationManager.calibrations.collectAsStateWithLifecycle()
    var journalEditorRequest by remember { mutableStateOf<JournalEditorRequest?>(null) }
    var lastJournalType by rememberSaveable { mutableStateOf(JournalEntryType.INSULIN) }

    fun openJournalEditor(
        timestamp: Long,
        suggestedType: JournalEntryType?,
        suggestedDisplayGlucose: Float?,
        suggestedAmountFraction: Float? = null
    ) {
        val type = suggestedType ?: lastJournalType
        val suggestedGlucoseMgDl = suggestedDisplayGlucose?.let {
            if (tk.glucodata.ui.util.GlucoseFormatter.isMmol(unit)) {
                tk.glucodata.ui.util.GlucoseFormatter.mmolToMg(it)
            } else {
                it
            }
        }
        lastJournalType = type
        journalEditorRequest = JournalEditorRequest(
            type = type,
            timestamp = timestamp,
            suggestedGlucoseMgDl = suggestedGlucoseMgDl,
            suggestedChartAnchorGlucoseMgDl = suggestedGlucoseMgDl
                .takeIf { type == JournalEntryType.FINGERSTICK || suggestedAmountFraction != null },
            suggestedAmountFraction = suggestedAmountFraction
        )
    }

    JournalScreen(
        glucoseHistory = glucoseHistory,
        unit = unit,
        viewMode = viewMode,
        graphLow = graphLow,
        graphHigh = graphHigh,
        targetLow = targetLow,
        targetHigh = targetHigh,
        graphSmoothingMinutes = visualSmoothingMinutes,
        collapseSmoothedData = dataSmoothingCollapseChunks,
        previewWindowMode = previewWindowMode,
        calibrations = calibrations,
        journalEntries = journalEntries,
        journalInsulinPresets = journalInsulinPresets,
        journalFoods = journalFoods,
        sensorId = sensorName,
        onPointClick = { point ->
            onTriggerCalibration(
                CalibrationSheetState.New(
                    point.value,
                    point.rawValue,
                    point.timestamp,
                    point.sensorSerial?.takeIf { it.isNotBlank() } ?: sensorName
                )
            )
        },
        onJournalEntryClick = { entry ->
            lastJournalType = entry.type
            journalEditorRequest = JournalEditorRequest(entry.type, entry.timestamp, entry)
        },
        onAddJournalEntry = { timestamp, suggestedType, suggestedDisplayGlucose, suggestedAmountFraction ->
            openJournalEditor(timestamp, suggestedType, suggestedDisplayGlucose, suggestedAmountFraction)
        },
        onOpenFoodLibrary = { navController.navigate("settings/journal/foods") },
        onOpenInsulinLibrary = { navController.navigate("settings/journal/insulin") },
        onOpenMeals = { navController.navigate("journal/meals") },
        modifier = modifier,
        showTitle = showTitle,
        useStatusBarsPadding = useStatusBarsPadding,
        bottomContentPadding = bottomContentPadding,
        showEiob = journalEiobDisplayEnabled,
        chartRangeColors = appChartRangeColorsEnabled,
        quickAddAlwaysNow = journalQuickAddAlwaysNow
    )

    journalEditorRequest?.let { request ->
        JournalEditorSheetHost(
            dashboardViewModel = dashboardViewModel,
            request = request,
            onDismiss = { journalEditorRequest = null },
            onSaved = { type -> lastJournalType = type }
        )
    }
}

/**
 * The journal entry editor wired to the dashboard view model: the journal tab and the meal screen
 * both open it, so the dose calculator, food library and save/delete paths stay one implementation.
 */
@Composable
internal fun JournalEditorSheetHost(
    dashboardViewModel: DashboardViewModel,
    request: JournalEditorRequest,
    onDismiss: () -> Unit,
    onSaved: (JournalEntryType) -> Unit = {}
) {
    val unit by dashboardViewModel.unit.collectAsStateWithLifecycle()
    val sensorName by dashboardViewModel.sensorName.collectAsStateWithLifecycle()
    val journalEnabled by dashboardViewModel.journalEnabled.collectAsStateWithLifecycle()
    val journalDoseCalculatorEnabled by dashboardViewModel.journalDoseCalculatorEnabled.collectAsStateWithLifecycle()
    val journalFoodMacrosEnabled by dashboardViewModel.journalFoodMacrosEnabled.collectAsStateWithLifecycle()
    val journalEntries by dashboardViewModel.journalEntries.collectAsStateWithLifecycle()
    val journalInsulinPresets by dashboardViewModel.journalInsulinPresets.collectAsStateWithLifecycle()
    val journalFoods by dashboardViewModel.journalFoods.collectAsStateWithLifecycle()
    val predictionCarbRatioGramsPerUnit by dashboardViewModel.predictionCarbRatioGramsPerUnit.collectAsStateWithLifecycle()
    val predictionInsulinSensitivityMgDlPerUnit by dashboardViewModel.predictionInsulinSensitivityMgDlPerUnit.collectAsStateWithLifecycle()
    val predictionModelProfile by dashboardViewModel.predictionModelProfile.collectAsStateWithLifecycle()
    val predictionDoseTargetMgDl by dashboardViewModel.predictionDoseTargetMgDl.collectAsStateWithLifecycle()

    JournalEntrySheet(
        unit = unit,
        selectedTimestamp = request.timestamp,
        suggestedGlucoseMgDl = request.suggestedGlucoseMgDl,
        suggestedChartAnchorGlucoseMgDl = request.suggestedChartAnchorGlucoseMgDl,
        suggestedAmountFraction = request.suggestedAmountFraction,
        insulinPresets = journalInsulinPresets,
        foods = journalFoods,
        foodMacrosEnabled = journalFoodMacrosEnabled,
        doseJournalEntries = journalEntries,
        doseProfile = JournalDoseProfile(
            enabled = journalEnabled && journalDoseCalculatorEnabled,
            carbRatioGramsPerUnit = predictionCarbRatioGramsPerUnit,
            insulinSensitivityMgDlPerUnit = predictionInsulinSensitivityMgDlPerUnit,
            foodMacrosEnabled = journalFoodMacrosEnabled,
            modelProfile = predictionModelProfile,
            targetMgDl = predictionDoseTargetMgDl
        ),
        initialType = request.type,
        existingEntry = request.existingEntry,
        prefill = request.prefill,
        onDismiss = onDismiss,
        onSave = { input ->
            dashboardViewModel.saveJournalEntry(input)
            onSaved(input.type)
            onDismiss()
        },
        onSaveEntries = { inputs ->
            inputs.forEach(dashboardViewModel::saveJournalEntry)
            inputs.firstOrNull()?.let { onSaved(it.type) }
            onDismiss()
        },
        onSaveFood = dashboardViewModel::saveJournalFood,
        onDelete = { entryId ->
            dashboardViewModel.deleteJournalEntry(entryId)
            onDismiss()
        },
        sensorSerialProvider = { sensorName.ifBlank { null } }
    )
}

@Composable
private fun JournalSettingsHistoryRoute(
    dashboardViewModel: DashboardViewModel,
    navController: androidx.navigation.NavController,
    onTriggerCalibration: (CalibrationSheetState) -> Unit
) {
    Scaffold(
        contentWindowInsets = WindowInsets(0.dp),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.journal_title)) },
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
        JournalRoute(
            dashboardViewModel = dashboardViewModel,
            navController = navController,
            onTriggerCalibration = onTriggerCalibration,
            modifier = Modifier.padding(padding),
            showTitle = false,
            useStatusBarsPadding = false,
            bottomContentPadding = 28.dp
        )
    }
}

@Composable
private fun CalibrationListRoute(
    dashboardViewModel: DashboardViewModel,
    navController: androidx.navigation.NavController,
    onTriggerCalibration: (CalibrationSheetState) -> Unit
) {
    val glucoseHistory by dashboardViewModel.glucoseHistory.collectAsStateWithLifecycle()
    val unit by dashboardViewModel.unit.collectAsStateWithLifecycle()
    val viewMode by dashboardViewModel.viewMode.collectAsStateWithLifecycle()
    val currentGlucose by dashboardViewModel.currentGlucose.collectAsStateWithLifecycle()
    val sensorName by dashboardViewModel.sensorName.collectAsStateWithLifecycle()

    val isMmol = tk.glucodata.ui.util.GlucoseFormatter.isMmol(unit)

    tk.glucodata.ui.calibration.CalibrationListScreen(
        navController = navController,
        isMmol = isMmol,
        viewMode = viewMode,
        sensorId = sensorName,
        onAdd = {
            val latest = glucoseHistory.firstOrNull()
            val autoVal = latest?.value ?: tk.glucodata.GlucoseValueParser.parseFirstOrZero(currentGlucose)
            val rawVal = latest?.rawValue ?: autoVal
            onTriggerCalibration(
                CalibrationSheetState.New(
                    autoVal,
                    rawVal,
                    latest?.timestamp ?: System.currentTimeMillis(),
                    latest?.sensorSerial?.takeIf { it.isNotBlank() } ?: sensorName
                )
            )
        },
        onEdit = { entity ->
            onTriggerCalibration(CalibrationSheetState.Edit(entity))
        },
        onOpenModelTable = {
            val current = navController.currentDestination?.route
            val route = if (current?.startsWith("settings/") == true) {
                "settings/calibrations/model-table"
            } else {
                "calibrations/model-table"
            }
            navController.navigate(route)
        }
    )
}

@Composable
private fun CalibrationModelTableRoute(
    dashboardViewModel: DashboardViewModel,
    navController: androidx.navigation.NavController,
    onTriggerCalibration: (CalibrationSheetState) -> Unit
) {
    val unit by dashboardViewModel.unit.collectAsStateWithLifecycle()
    val viewMode by dashboardViewModel.viewMode.collectAsStateWithLifecycle()
    val sensorName by dashboardViewModel.sensorName.collectAsStateWithLifecycle()

    tk.glucodata.ui.calibration.CalibrationModelTableScreen(
        navController = navController,
        isMmol = tk.glucodata.ui.util.GlucoseFormatter.isMmol(unit),
        viewMode = viewMode,
        sensorId = sensorName,
        onEdit = { entity ->
            onTriggerCalibration(CalibrationSheetState.Edit(entity))
        }
    )
}

@Composable
private fun CalibrationSheetHost(
    sheetState: CalibrationSheetState,
    dashboardViewModel: DashboardViewModel,
    onDismiss: () -> Unit,
    onNavigateToCalibrations: () -> Unit
) {
    if (sheetState is CalibrationSheetState.Hidden) return

    val glucoseHistory by dashboardViewModel.glucoseHistory.collectAsStateWithLifecycle()
    val multiSensorDisplay by dashboardViewModel.multiSensorDisplay.collectAsStateWithLifecycle()
    val unit by dashboardViewModel.unit.collectAsStateWithLifecycle()
    val viewMode by dashboardViewModel.viewMode.collectAsStateWithLifecycle()
    val sensorViewModes by dashboardViewModel.sensorViewModes.collectAsStateWithLifecycle()

    data class SheetInit(
        val auto: Float,
        val raw: Float,
        val timestamp: Long,
        val sensorId: String?,
        val viewMode: Int
    )

    val init = when (sheetState) {
        is CalibrationSheetState.New -> SheetInit(
            auto = sheetState.auto,
            raw = sheetState.raw,
            timestamp = sheetState.timestamp,
            sensorId = sheetState.sensorId.takeIf { it.isNotBlank() },
            viewMode = sheetState.viewModeOverride ?: viewMode
        )
        is CalibrationSheetState.Edit -> SheetInit(
            auto = sheetState.entity.sensorValue,
            raw = sheetState.entity.sensorValueRaw,
            timestamp = sheetState.entity.timestamp,
            sensorId = sheetState.entity.sensorId.takeIf { it.isNotBlank() },
            viewMode = if (sheetState.entity.isRawMode) 1 else 0
        )
        CalibrationSheetState.Hidden -> SheetInit(0f, 0f, 0L, null, viewMode)
    }

    val sheetHistory = remember(glucoseHistory, multiSensorDisplay, init.sensorId) {
        val sensorId = init.sensorId
        val source = if (sensorId.isNullOrBlank()) {
            glucoseHistory
        } else {
            val primarySerial = glucoseHistory.lastOrNull()?.sensorSerial
            if (SensorIdentity.matches(sensorId, primarySerial)) {
                glucoseHistory
            } else {
                multiSensorDisplay.seriesFor(sensorId)?.points.orEmpty()
            }
        }
        source.map { tk.glucodata.GlucosePoint(it.timestamp, it.value, it.rawValue) }
    }

    tk.glucodata.ui.calibration.CalibrationBottomSheet(
        onDismiss = onDismiss,
        initialValueAuto = init.auto,
        initialValueRaw = init.raw,
        initialTimestamp = init.timestamp,
        glucoseHistory = sheetHistory,
        isMmol = tk.glucodata.ui.util.GlucoseFormatter.isMmol(unit),
        viewMode = sensorViewModes.entries.firstOrNull { (sensorId, _) ->
            init.sensorId != null && SensorIdentity.matches(sensorId, init.sensorId)
        }?.value ?: init.viewMode,
        sensorId = init.sensorId
            ?: SensorIdentity.resolveMainSensor()
            ?: return,
        onNavigateToHistory = {
            onDismiss()
            onNavigateToCalibrations()
        }
    )
}

@Composable
private fun AdaptiveNavigationLabel(text: String) {
    BoxWithConstraints {
        val density = LocalDensity.current
        val textMeasurer = rememberTextMeasurer()
        val regularStyle = MaterialTheme.typography.labelMedium
        val tightStyle = regularStyle.copy(letterSpacing = 0.sp)
        val compactStyle = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.sp)
        val availableWidthPx = with(density) { maxWidth.toPx() }
        val labelStyle = remember(text, availableWidthPx, regularStyle, tightStyle, compactStyle) {
            listOf(regularStyle, tightStyle, compactStyle).firstOrNull { style ->
                textMeasurer.measure(
                    text = AnnotatedString(text),
                    style = style,
                    maxLines = 1
                ).size.width <= availableWidthPx
            } ?: compactStyle
        }

        Text(
            text = text,
            style = labelStyle,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun MainApp(themeMode: ThemeMode, onThemeChanged: (ThemeMode) -> Unit) {
    val navController = rememberNavController()
    val dashboardViewModel: DashboardViewModel = viewModel()

    // Hoisted Calibration Sheet State
    var calibrationSheetState by remember { mutableStateOf<CalibrationSheetState>(CalibrationSheetState.Hidden) }

    val onTriggerCalibration: (CalibrationSheetState) -> Unit = { state ->
        calibrationSheetState = state
    }
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    // Handle back button to exit app when on start destination
    val currentBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = currentBackStackEntry?.destination?.route
    val journalEnabled by dashboardViewModel.journalEnabled.collectAsStateWithLifecycle()
    val journalNavigationTabEnabled by dashboardViewModel.journalNavigationTabEnabled.collectAsStateWithLifecycle()
    val showJournalNav = journalEnabled && journalNavigationTabEnabled

    fun collectionModeForRoute(route: String?): DashboardViewModel.CollectionMode = when (route) {
        "dashboard", "sensors", "settings" -> DashboardViewModel.CollectionMode.DASHBOARD
        "stats" -> DashboardViewModel.CollectionMode.INACTIVE
        "history",
        "journal",
        "settings/journal/history",
        "calibrations",
        "settings/calibrations",
        "calibrations/model-table",
        "settings/calibrations/model-table" -> DashboardViewModel.CollectionMode.FULL_HISTORY
        else -> when {
            route?.startsWith("sensors/") == true -> DashboardViewModel.CollectionMode.DASHBOARD
            route?.startsWith("settings/") == true -> DashboardViewModel.CollectionMode.DASHBOARD
            else -> DashboardViewModel.CollectionMode.INACTIVE
        }
    }

    LifecycleEventEffect(Lifecycle.Event.ON_START) {
        dashboardViewModel.setCollectionMode(collectionModeForRoute(currentRoute))
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        dashboardViewModel.onResume()
    }

    LaunchedEffect(currentRoute) {
        dashboardViewModel.setCollectionMode(collectionModeForRoute(currentRoute))
    }

    BackHandler(enabled = currentRoute == "dashboard") {
        // OPTION 1 (Current): Traditional Android - Back button exits/destroys app
        (context as? Activity)?.finish()

        // OPTION 2 (Alternative): Modern UX - Back = Home (minimizes instead of destroying)
        // Uncomment below to make Back button minimize the app instead of destroying it.
        // This keeps the app in memory like pressing Home, avoiding reload delay.
        // (context as? Activity)?.moveTaskToBack(true)
    }

    // Navigation Items Logic (Shared)
    // Map subpages to their parent top-level destination
    fun getParentRoute(route: String?): String? = when {
        route == null -> null
        route.startsWith("settings/") -> "settings"
        route.startsWith("sensors/") -> "sensors"
        route == "history" -> "dashboard"
        route == "journal" || route.startsWith("journal/") -> "journal"
        route == "calibrations" || route.startsWith("calibrations/") -> "dashboard"
        else -> null
    }

    val onNavigate = { route: String ->
        val parentOfCurrent = getParentRoute(currentRoute)
        val isOnSubpageOf = parentOfCurrent == route

        when {
            // If we're on a subpage of the clicked nav item, pop back to it
            isOnSubpageOf -> navController.popBackStack(route, inclusive = false)
            // If we're on a different top-level or subpage, navigate normally
            currentRoute != route -> navController.navigate(route) {
                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
            // Already on the destination. Tapping the tab you are already on is how people
            // back out of a mode, so let it close Arrange rather than doing nothing.
            else -> tk.glucodata.ui.stats.StatsArrangeMode.close()
        }
    }

    // Define items for use in both Bar and Rail
    data class NavItem(val route: String, val label: String, val selectedIcon: ImageVector, val unselectedIcon: ImageVector)
    val navItems = buildList {
        add(NavItem("stats", stringResource(R.string.statistics_title), Icons.Filled.BarChart, Icons.Outlined.BarChart))
        if (showJournalNav) {
            add(NavItem("journal", stringResource(R.string.journal_title), Icons.Filled.EditNote, Icons.Outlined.EditNote))
        }
        add(NavItem("dashboard", stringResource(R.string.dashboard), Icons.Filled.LegendToggle, Icons.Outlined.LegendToggle))
        add(NavItem("sensors", stringResource(R.string.sensor), Icons.Filled.Sensors, Icons.Outlined.Sensors))
        add(NavItem("settings", stringResource(R.string.settings), Icons.Filled.Settings, Icons.Outlined.Settings))
    }

    if (isLandscape) {
        // --- LANDSCAPE: Navigation Rail on Left ---
        Row(modifier = Modifier.fillMaxSize()) {
            NavigationRail {
                Spacer(modifier = Modifier.weight(1f)) // Center vertically? Or top? Usually top or center.
                // Let's center them vertically for likely better ergonomics in landscape phone

                navItems.forEach { item ->
                    val isSelected = currentRoute == item.route || getParentRoute(currentRoute) == item.route
                    NavigationRailItem(
                        icon = {
                            TabIcon(
                                isSelected = isSelected,
                                selectedIcon = item.selectedIcon,
                                unselectedIcon = item.unselectedIcon,
                                description = item.label,
                                isDashboard = item.route == "dashboard",
                                isStatistics = item.route == "stats"
                            )
                        },
                        label = { Text(item.label) },
                        selected = isSelected,
                        onClick = { onNavigate(item.route) }
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
            }

            // Content Area -- LANDSCAPE
            Scaffold(contentWindowInsets = WindowInsets(0.dp)) { innerPadding ->
                NavHost(
                    navController = navController,
                    startDestination = "dashboard",
                    modifier = Modifier.padding(innerPadding).consumeWindowInsets(innerPadding),
                    // No transition between rail destinations. This was never asked for —
                    // it is NavHost's own 700 ms default, which portrait overrides with a
                    // fast fade and landscape had simply been left on.
                    enterTransition = { EnterTransition.None },
                    exitTransition = { ExitTransition.None },
                    popEnterTransition = { EnterTransition.None },
                    popExitTransition = { ExitTransition.None }
                ) {
                    composable("dashboard") {
                        DashboardRoute(
                            dashboardViewModel = dashboardViewModel,
                            navController = navController,
                            onTriggerCalibration = onTriggerCalibration
                        )
                    }
                    composable("history") {
                        HistoryRoute(
                            dashboardViewModel = dashboardViewModel,
                            title = stringResource(R.string.historyname),
                            browseMode = TimelineBrowseMode.HISTORY,
                            onBack = { navController.popBackStack() },
                            onTriggerCalibration = onTriggerCalibration
                        )
                    }
                    composable("journal") {
                        JournalRoute(
                            dashboardViewModel = dashboardViewModel,
                            navController = navController,
                            onTriggerCalibration = onTriggerCalibration,
                        )
                    }
                    composable("journal/meals") { tk.glucodata.ui.meal.MealsScreen(navController) }
                    composable("journal/meals/{mealId}") { entry ->
                        val mealId = entry.arguments?.getString("mealId")?.toLongOrNull()
                        if (mealId != null) {
                            tk.glucodata.ui.meal.MealScreen(mealId, navController, dashboardViewModel)
                        }
                    }
                    composable("stats") { tk.glucodata.ui.stats.StatsScreen() }
                    composable("sensors") {
                        SensorScreen(
                            onNavigateToMqAccount = { navController.navigate("settings/mq-account") },
                            onNavigateToReadiness = { navController.navigate("settings/cgm-readiness") }
                        )
                    }
                    composable("settings") { ExpressiveSettingsScreen(navController, themeMode, onThemeChanged, dashboardViewModel) }
                    composable("settings/nightscout") { NightscoutSettingsScreen(navController) }
                    composable("settings/libreview") { LibreViewSettingsScreen(navController) }
                    composable("settings/mq-account") { MQAccountSettingsScreen(navController) }
                    composable("settings/mq-follower") { MQFollowerSettingsScreen(navController) }
                    composable("settings/ottai") { tk.glucodata.ui.setup.OttaiSettingsScreen(navController) }
                    composable("settings/mirror") { MirrorSettingsScreen(navController) }
                    composable("settings/outbound-api") { OutboundApiSettingsScreen(navController) }
                    composable("settings/api-source") { ApiSourceSettingsScreen(navController) }
                    composable("settings/glucose-meters") { GlucoseMeterSettingsScreen(navController) }
                    composable("settings/insulin-pens") { InsulinPenSettingsScreen(navController) }
                    composable("settings/watch") { WatchSettingsScreen(navController) }
                    // Keep legacy route for backward compatibility.
                    composable("settings/weartransport") { WatchSettingsScreen(navController) }
                    composable("settings/watch/wearos-config") { WearOsConfigScreen(navController) }
                    composable("settings/watch/garmin-status") { GarminStatusScreen(navController) }
                    composable("settings/webserver") { WebServerSettingsScreen(navController) }
                    composable("settings/notification-display") {
                        NotificationSettingsScreen(navController, dashboardViewModel)
                    }
                    composable("settings/display-colors") {
                        DisplayAndColorSettingsScreen(navController, dashboardViewModel)
                    }
                    composable("settings/data-smoothing") {
                        DataSmoothingSettingsScreen(navController, dashboardViewModel)
                    }
                    composable("settings/predictive-simulation") {
                        PredictiveSimulationSettingsScreen(navController, dashboardViewModel)
                    }
                    composable("settings/predictive-simulation/model-profile") {
                        PredictionModelProfileScreen(navController, dashboardViewModel)
                    }
                    composable("settings/floating-display") {
                        FloatingGlucoseSettingsScreen(navController, dashboardViewModel)
                    }
                    composable("settings/aod-display") { AodSettingsScreen(navController) }

                    composable("settings/turnserver") { tk.glucodata.ui.TurnServerSettingsScreen(navController) }
                    composable("settings/debug") { DebugSettingsScreen(navController) }
                    composable("settings/cgm-readiness") { CgmReadinessScreen(navController) }
                    composable("settings/app-updates") { AppUpdatesScreen(navController) }
                    composable("settings/alerts") { tk.glucodata.ui.alerts.AlertSettingsScreen(navController) }
                    composable("settings/alerts/talker") { tk.glucodata.ui.alerts.TalkerSettingsScreen(navController) }
                    composable("settings/journal") { JournalSettingsScreen(navController, dashboardViewModel) }
                    composable("settings/journal/history") {
                        JournalSettingsHistoryRoute(
                            dashboardViewModel = dashboardViewModel,
                            navController = navController,
                            onTriggerCalibration = onTriggerCalibration
                        )
                    }
                    composable("settings/journal/foods") { JournalFoodLibraryScreen(navController, dashboardViewModel) }
                    composable("settings/journal/insulin") { JournalInsulinLibraryScreen(navController, dashboardViewModel) }
                    composable("settings/calibrations") {
                        CalibrationListRoute(
                            dashboardViewModel = dashboardViewModel,
                            navController = navController,
                            onTriggerCalibration = onTriggerCalibration
                        )
                    }
                    composable("settings/calibrations/model-table") {
                        CalibrationModelTableRoute(
                            dashboardViewModel = dashboardViewModel,
                            navController = navController,
                            onTriggerCalibration = onTriggerCalibration
                        )
                    }
                    composable("calibrations") {
                        CalibrationListRoute(
                            dashboardViewModel = dashboardViewModel,
                            navController = navController,
                            onTriggerCalibration = onTriggerCalibration
                        )
                    }
                    composable("calibrations/model-table") {
                        CalibrationModelTableRoute(
                            dashboardViewModel = dashboardViewModel,
                            navController = navController,
                            onTriggerCalibration = onTriggerCalibration
                        )
                    }
                }
            }
        }
    } else {
        // --- PORTRAIT: Bottom Navigation Bar ---
        Scaffold(
            contentWindowInsets = WindowInsets(0, 0, 0, 0), // Fix: Prevent double padding for child Scaffolds
            bottomBar = {
                NavigationBar {
                    navItems.forEach { item ->
                        val isSelected = currentRoute == item.route || getParentRoute(currentRoute) == item.route
                        NavigationBarItem(
                            icon = {
                                TabIcon(
                                    isSelected = isSelected,
                                    selectedIcon = item.selectedIcon,
                                    unselectedIcon = item.unselectedIcon,
                                    description = item.label,
                                    isDashboard = item.route == "dashboard",
                                    isStatistics = item.route == "stats"
                                )
                            },
                            label = {
                                AdaptiveNavigationLabel(item.label)
                            },
                            selected = isSelected,
                            onClick = { onNavigate(item.route) }
                        )
                    }
                }
            }

        ) { innerPadding ->
            NavHost(
                navController = navController,
                startDestination = "dashboard",
                modifier = Modifier.padding(innerPadding).consumeWindowInsets(innerPadding),
                // Use a fast fade (200ms) for a snappy feel that isn't jarring
                enterTransition = { fadeIn(animationSpec = tween(200)) },
                exitTransition = { fadeOut(animationSpec = tween(200)) },
                popEnterTransition = { fadeIn(animationSpec = tween(200)) },
                popExitTransition = { fadeOut(animationSpec = tween(200)) }
            ) {
                composable("dashboard") {
                    DashboardRoute(
                        dashboardViewModel = dashboardViewModel,
                        navController = navController,
                        onTriggerCalibration = onTriggerCalibration
                    )
                }
                composable("history") {
                    HistoryRoute(
                        dashboardViewModel = dashboardViewModel,
                        title = stringResource(R.string.historyname),
                        browseMode = TimelineBrowseMode.HISTORY,
                        onBack = { navController.popBackStack() },
                        onTriggerCalibration = onTriggerCalibration
                    )
                }
                composable("journal") {
                    JournalRoute(
                        dashboardViewModel = dashboardViewModel,
                        navController = navController,
                        onTriggerCalibration = onTriggerCalibration,
                    )
                }
                composable("journal/meals") { tk.glucodata.ui.meal.MealsScreen(navController) }
                composable("journal/meals/{mealId}") { entry ->
                    val mealId = entry.arguments?.getString("mealId")?.toLongOrNull()
                    if (mealId != null) {
                        tk.glucodata.ui.meal.MealScreen(mealId, navController, dashboardViewModel)
                    }
                }
                composable("stats") { tk.glucodata.ui.stats.StatsScreen() }
                composable("sensors") {
                    SensorScreen(
                        onNavigateToMqAccount = { navController.navigate("settings/mq-account") },
                        onNavigateToReadiness = { navController.navigate("settings/cgm-readiness") }
                    )
                }
                composable("settings") { ExpressiveSettingsScreen(navController, themeMode, onThemeChanged, dashboardViewModel) }
                composable("settings/nightscout") { NightscoutSettingsScreen(navController) }
                composable("settings/libreview") { LibreViewSettingsScreen(navController) }
                composable("settings/mq-account") { MQAccountSettingsScreen(navController) }
                composable("settings/mq-follower") { MQFollowerSettingsScreen(navController) }
                composable("settings/ottai") { tk.glucodata.ui.setup.OttaiSettingsScreen(navController) }
                composable("settings/mirror") { MirrorSettingsScreen(navController) }
                composable("settings/outbound-api") { OutboundApiSettingsScreen(navController) }
                composable("settings/api-source") { ApiSourceSettingsScreen(navController) }
                composable("settings/glucose-meters") { GlucoseMeterSettingsScreen(navController) }
                composable("settings/insulin-pens") { InsulinPenSettingsScreen(navController) }
                composable("settings/watch") { WatchSettingsScreen(navController) }
                // Keep legacy route for backward compatibility.
                composable("settings/weartransport") { WatchSettingsScreen(navController) }
                composable("settings/watch/wearos-config") { WearOsConfigScreen(navController) }
                composable("settings/watch/garmin-status") { GarminStatusScreen(navController) }
                composable("settings/webserver") { WebServerSettingsScreen(navController) }
                composable("settings/notification-display") {
                    NotificationSettingsScreen(navController, dashboardViewModel)
                }
                composable("settings/display-colors") {
                    DisplayAndColorSettingsScreen(navController, dashboardViewModel)
                }
                composable("settings/data-smoothing") {
                    DataSmoothingSettingsScreen(navController, dashboardViewModel)
                }
                composable("settings/predictive-simulation") {
                    PredictiveSimulationSettingsScreen(navController, dashboardViewModel)
                }
                composable("settings/predictive-simulation/model-profile") {
                    PredictionModelProfileScreen(navController, dashboardViewModel)
                }
                composable("settings/floating-display") {
                    FloatingGlucoseSettingsScreen(navController, dashboardViewModel)
                }
                composable("settings/aod-display") { AodSettingsScreen(navController) }

                composable("settings/turnserver") { tk.glucodata.ui.TurnServerSettingsScreen(navController) }
                composable("settings/debug") { DebugSettingsScreen(navController) }
                composable("settings/cgm-readiness") { CgmReadinessScreen(navController) }
                composable("settings/app-updates") { AppUpdatesScreen(navController) }
                composable("settings/alerts") { tk.glucodata.ui.alerts.AlertSettingsScreen(navController) }
                composable("settings/alerts/talker") { tk.glucodata.ui.alerts.TalkerSettingsScreen(navController) }
                composable("settings/journal") { JournalSettingsScreen(navController, dashboardViewModel) }
                composable("settings/journal/history") {
                    JournalSettingsHistoryRoute(
                        dashboardViewModel = dashboardViewModel,
                        navController = navController,
                        onTriggerCalibration = onTriggerCalibration
                    )
                }
                composable("settings/journal/foods") { JournalFoodLibraryScreen(navController, dashboardViewModel) }
                composable("settings/journal/insulin") { JournalInsulinLibraryScreen(navController, dashboardViewModel) }
                composable("settings/calibrations") {
                    CalibrationListRoute(
                        dashboardViewModel = dashboardViewModel,
                        navController = navController,
                        onTriggerCalibration = onTriggerCalibration
                    )
                }
                composable("settings/calibrations/model-table") {
                    CalibrationModelTableRoute(
                        dashboardViewModel = dashboardViewModel,
                        navController = navController,
                        onTriggerCalibration = onTriggerCalibration
                    )
                }
                composable("calibrations") {
                    CalibrationListRoute(
                        dashboardViewModel = dashboardViewModel,
                        navController = navController,
                        onTriggerCalibration = onTriggerCalibration
                    )
                }
                composable("calibrations/model-table") {
                    CalibrationModelTableRoute(
                        dashboardViewModel = dashboardViewModel,
                        navController = navController,
                        onTriggerCalibration = onTriggerCalibration
                    )
                }
            }
        }
    }

    // --- INSULIN PEN REVIEW SHEET (Global) ---
    // A pen is tapped against the phone from any screen, so the review lives here.
    InsulinPenScanSheetHost()

    // --- CALIBRATION BOTTOM SHEET (Global) ---
    CalibrationSheetHost(
        sheetState = calibrationSheetState,
        dashboardViewModel = dashboardViewModel,
        onDismiss = { calibrationSheetState = CalibrationSheetState.Hidden },
        onNavigateToCalibrations = { navController.navigate("calibrations") }
    )
}
