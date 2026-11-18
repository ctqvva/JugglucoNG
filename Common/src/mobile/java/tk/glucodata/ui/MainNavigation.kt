@file:OptIn(ExperimentalMaterial3Api::class)

package tk.glucodata.ui

import android.app.Activity
import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
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
import androidx.compose.material3.NavigationItemIconPosition
import androidx.compose.material3.ShortNavigationBar
import androidx.compose.material3.ShortNavigationBarArrangement
import androidx.compose.material3.ShortNavigationBarItem
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.key
import androidx.compose.material3.WideNavigationRail
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.WideNavigationRailItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.compose.animation.AnimatedContentScope
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.navigation.NavBackStackEntry
import tk.glucodata.InsulinPenManager
import tk.glucodata.RestrictedMode
import tk.glucodata.ui.restricted.LocalRequestFullMode
import tk.glucodata.ui.restricted.RestrictedModeRoutes
import tk.glucodata.ui.restricted.RestrictedModeSettingsScreen
import tk.glucodata.ui.restricted.RestrictedPinSetupScreen
import tk.glucodata.R
import tk.glucodata.SensorIdentity
import tk.glucodata.data.journal.JournalEntry
import tk.glucodata.data.journal.JournalEntryType
import tk.glucodata.ui.components.AppTopBar
import tk.glucodata.ui.components.LocalNavigationTapCatcher
import tk.glucodata.ui.components.NavigationTapCatcher
import tk.glucodata.ui.components.NavigationTapCatcherLayer
import tk.glucodata.ui.journal.JournalDoseProfile
import tk.glucodata.ui.journal.JournalEntrySheet
import tk.glucodata.ui.journal.JournalFoodLibraryScreen
import tk.glucodata.ui.journal.JournalInsulinLibraryScreen
import tk.glucodata.ui.journal.JournalCalculationsSettingsScreen
import tk.glucodata.ui.journal.JournalEntryTypesSettingsScreen
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
    val suggestedAmountFraction: Float? = null
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
        onNavigateToQuietWindow = { navController.navigate("settings/alerts") },
        onNavigateToPredictionModelProfile = {
            navController.navigate("settings/predictive-simulation/model-profile")
        },
        // Placed as a tab tap would, so the bar keeps working from there.
        onNavigateToSensors = {
            navController.navigate("sensors") {
                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        },
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
    // The merged cross-sensor timeline — the previous sensor's calibrated
    // readings, imports and older device data stay visible across a sensor
    // swap — loaded around the chart's viewport plus the live tail. The chart
    // reports where it is looking through onVisibleRangeChanged and the view
    // model loads accordingly; the store's ends come from timelineExtents.
    val glucoseHistory by dashboardViewModel.glucoseHistory.collectAsStateWithLifecycle()
    val timelineExtents by dashboardViewModel.timelineExtents.collectAsStateWithLifecycle()
    val unit by dashboardViewModel.unit.collectAsStateWithLifecycle()
    val viewMode by dashboardViewModel.viewMode.collectAsStateWithLifecycle()
    val sensorName by dashboardViewModel.sensorName.collectAsStateWithLifecycle()
    val graphLow by dashboardViewModel.graphLow.collectAsStateWithLifecycle()
    val graphHigh by dashboardViewModel.graphHigh.collectAsStateWithLifecycle()
    val targetLow by dashboardViewModel.targetLow.collectAsStateWithLifecycle()
    val targetHigh by dashboardViewModel.targetHigh.collectAsStateWithLifecycle()
    val chartSmoothingMinutes by dashboardViewModel.chartSmoothingMinutes.collectAsStateWithLifecycle()
    val dataSmoothingTargets by dashboardViewModel.dataSmoothingTargets.collectAsStateWithLifecycle()
    val visualSmoothingMinutes = tk.glucodata.DataSmoothing.graphSmoothingMinutes(
        smoothingMinutes = chartSmoothingMinutes,
        targets = dataSmoothingTargets
    )
    val evaluationSmoothingMinutes = tk.glucodata.DataSmoothing.localSmoothingMinutes(
        smoothingMinutes = chartSmoothingMinutes,
        targets = dataSmoothingTargets
    )
    val visualCollapseMinutes = tk.glucodata.DataSmoothing.graphCollapseMinutes(chartSmoothingMinutes, dataSmoothingTargets)
    val previewWindowMode by dashboardViewModel.previewWindowMode.collectAsStateWithLifecycle()
    val journalEnabled by dashboardViewModel.journalEnabled.collectAsStateWithLifecycle()
    val journalDoseCalculatorEnabled by dashboardViewModel.journalDoseCalculatorEnabled.collectAsStateWithLifecycle()
    val journalFoodMacrosEnabled by dashboardViewModel.journalFoodMacrosEnabled.collectAsStateWithLifecycle()
    val journalFoodLibraryEnabled by dashboardViewModel.journalFoodLibraryEnabled.collectAsStateWithLifecycle()
    val journalFoodOnlineEnabled by dashboardViewModel.journalFoodOnlineEnabled.collectAsStateWithLifecycle()
    val journalEntries by dashboardViewModel.journalEntries.collectAsStateWithLifecycle()
    val journalInsulinPresets by dashboardViewModel.journalInsulinPresets.collectAsStateWithLifecycle()
    val journalFoods by dashboardViewModel.journalFoods.collectAsStateWithLifecycle()
    val journalQuickAddAlwaysNow by dashboardViewModel.journalQuickAddAlwaysNow.collectAsStateWithLifecycle()
    val appChartRangeColorsEnabled by dashboardViewModel.glucoseAppChartRangeColorsEnabled.collectAsStateWithLifecycle()
    val rowsShowDelta by dashboardViewModel.dashboardRowsShowDelta.collectAsStateWithLifecycle()
    val deltaIntervalMinutes by dashboardViewModel.deltaIntervalMinutes.collectAsStateWithLifecycle()
    val predictionCarbRatioGramsPerUnit by dashboardViewModel.predictionCarbRatioGramsPerUnit.collectAsStateWithLifecycle()
    val predictionInsulinSensitivityMgDlPerUnit by dashboardViewModel.predictionInsulinSensitivityMgDlPerUnit.collectAsStateWithLifecycle()
    val predictionModelProfile by dashboardViewModel.predictionModelProfile.collectAsStateWithLifecycle()
    val predictionDoseTargetMgDl by dashboardViewModel.predictionDoseTargetMgDl.collectAsStateWithLifecycle()
    val calibrations by tk.glucodata.data.calibration.CalibrationManager.calibrations.collectAsStateWithLifecycle()
    var journalEditorRequest by remember { mutableStateOf<JournalEditorRequest?>(null) }
    var lastJournalType by rememberSaveable { mutableStateOf(JournalEntryType.INSULIN) }
    val restricted by RestrictedMode.state.collectAsStateWithLifecycle()
    val fullModeGuard = tk.glucodata.ui.restricted.rememberFullModeGuard()
    val journalGuard = tk.glucodata.ui.restricted.rememberRestrictedJournalGuard()
    // Restricted again with the editor open (an adult left it a minute): close it.
    LaunchedEffect(restricted.canEditJournal) {
        if (!restricted.canEditJournal) journalEditorRequest = null
    }

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
        evaluationSmoothingMinutes = evaluationSmoothingMinutes,
        graphCollapseMinutes = visualCollapseMinutes,
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
        showRowDelta = rowsShowDelta,
        deltaIntervalMinutes = deltaIntervalMinutes,
        timelineExtents = timelineExtents,
        onVisibleRangeChanged = dashboardViewModel::onChartViewportChanged,
        rangeSummaryFlow = dashboardViewModel::timelineRangeSummaryFlow,
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
        // Restricted mode: deleting readings is data clearing, and stays hidden.
        onDeleteReading = if (restricted.canClearData) {
            { point -> dashboardViewModel.deleteHistoryReading(point, sensorName) }
        } else {
            null
        },
        onJournalEntryClick = { entry ->
            val open = {
                lastJournalType = entry.type
                journalEditorRequest = JournalEditorRequest(entry.type, entry.timestamp, entry)
            }
            if (restricted.canEditJournal) open() else fullModeGuard(open)
        },
        onAddJournalEntry = if (!restricted.canEditJournal) null else { timestamp, suggestedType, suggestedDisplayGlucose ->
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
            foodLibraryEnabled = journalFoodLibraryEnabled,
            foodOnlineEnabled = journalFoodOnlineEnabled,
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
                journalGuard.save(request.existingEntry, listOf(input.type)) {
                    dashboardViewModel.saveJournalEntry(input)
                    lastJournalType = input.type
                    journalEditorRequest = null
                }
            },
            onSaveEntries = { inputs ->
                journalGuard.save(request.existingEntry, inputs.map { it.type }) {
                    inputs.forEach(dashboardViewModel::saveJournalEntry)
                    inputs.firstOrNull()?.let { lastJournalType = it.type }
                    journalEditorRequest = null
                }
            },
            onDelete = { entryId ->
                journalGuard.delete {
                    dashboardViewModel.deleteJournalEntry(entryId)
                    journalEditorRequest = null
                }
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
    val glucoseHistory by dashboardViewModel.glucoseHistory.collectAsStateWithLifecycle()
    val timelineExtents by dashboardViewModel.timelineExtents.collectAsStateWithLifecycle()
    // The ledger's readings come from bounded reads around the entries, not from
    // the loaded list; nothing else needs them, so they start with this screen.
    LaunchedEffect(dashboardViewModel) { dashboardViewModel.ensureJournalGlucoseAnchorsObserved() }
    val journalGlucoseAnchors by dashboardViewModel.journalGlucoseAnchors.collectAsStateWithLifecycle()
    val unit by dashboardViewModel.unit.collectAsStateWithLifecycle()
    val viewMode by dashboardViewModel.viewMode.collectAsStateWithLifecycle()
    val sensorName by dashboardViewModel.sensorName.collectAsStateWithLifecycle()
    val graphLow by dashboardViewModel.graphLow.collectAsStateWithLifecycle()
    val graphHigh by dashboardViewModel.graphHigh.collectAsStateWithLifecycle()
    val targetLow by dashboardViewModel.targetLow.collectAsStateWithLifecycle()
    val targetHigh by dashboardViewModel.targetHigh.collectAsStateWithLifecycle()
    val chartSmoothingMinutes by dashboardViewModel.chartSmoothingMinutes.collectAsStateWithLifecycle()
    val dataSmoothingTargets by dashboardViewModel.dataSmoothingTargets.collectAsStateWithLifecycle()
    val visualSmoothingMinutes = tk.glucodata.DataSmoothing.graphSmoothingMinutes(
        smoothingMinutes = chartSmoothingMinutes,
        targets = dataSmoothingTargets
    )
    val evaluationSmoothingMinutes = tk.glucodata.DataSmoothing.localSmoothingMinutes(
        smoothingMinutes = chartSmoothingMinutes,
        targets = dataSmoothingTargets
    )
    val visualCollapseMinutes = tk.glucodata.DataSmoothing.graphCollapseMinutes(chartSmoothingMinutes, dataSmoothingTargets)
    val previewWindowMode by dashboardViewModel.previewWindowMode.collectAsStateWithLifecycle()
    val journalEnabled by dashboardViewModel.journalEnabled.collectAsStateWithLifecycle()
    val journalDoseCalculatorEnabled by dashboardViewModel.journalDoseCalculatorEnabled.collectAsStateWithLifecycle()
    val journalFoodMacrosEnabled by dashboardViewModel.journalFoodMacrosEnabled.collectAsStateWithLifecycle()
    val journalFoodLibraryEnabled by dashboardViewModel.journalFoodLibraryEnabled.collectAsStateWithLifecycle()
    val journalFoodOnlineEnabled by dashboardViewModel.journalFoodOnlineEnabled.collectAsStateWithLifecycle()
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
    val journalGuard = tk.glucodata.ui.restricted.rememberRestrictedJournalGuard()

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
        graphCollapseMinutes = visualCollapseMinutes,
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
        modifier = modifier,
        showTitle = showTitle,
        useStatusBarsPadding = useStatusBarsPadding,
        bottomContentPadding = bottomContentPadding,
        showEiob = journalEiobDisplayEnabled,
        chartRangeColors = appChartRangeColorsEnabled,
        quickAddAlwaysNow = journalQuickAddAlwaysNow,
        glucoseAnchors = journalGlucoseAnchors,
        timelineExtents = timelineExtents,
        onVisibleRangeChanged = dashboardViewModel::onChartViewportChanged
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
            foodLibraryEnabled = journalFoodLibraryEnabled,
            foodOnlineEnabled = journalFoodOnlineEnabled,
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
            onDismiss = { journalEditorRequest = null },
            onSave = { input ->
                journalGuard.save(request.existingEntry, listOf(input.type)) {
                    dashboardViewModel.saveJournalEntry(input)
                    lastJournalType = input.type
                    journalEditorRequest = null
                }
            },
            onSaveEntries = { inputs ->
                journalGuard.save(request.existingEntry, inputs.map { it.type }) {
                    inputs.forEach(dashboardViewModel::saveJournalEntry)
                    inputs.firstOrNull()?.let { lastJournalType = it.type }
                    journalEditorRequest = null
                }
            },
            onDelete = { entryId ->
                journalGuard.delete {
                    dashboardViewModel.deleteJournalEntry(entryId)
                    journalEditorRequest = null
                }
            },
            sensorSerialProvider = { sensorName.ifBlank { null } }
        )
    }
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
            AppTopBar(
                title = stringResource(R.string.journal_title),
                onNavigateBack = { navController.popBackStack() },
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
    // The sensor being calibrated is the current one, so the reading to prefill
    // from is its own newest — not the first element of a list, which was the
    // oldest reading in the store.
    val latestReading by dashboardViewModel.currentSensorLatestReading.collectAsStateWithLifecycle()
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
            val latest = latestReading
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

    // The sheet reads the timeline around the moment it is set to — the
    // nearest reading, and the trend leading up to it. That moment moves as
    // the user edits the time or picks an earlier calibration, so the window
    // follows it: six hours each side, re-centred once the moment leaves the
    // inner half of what is loaded.
    var sheetWindowCenter by remember(init.timestamp) { mutableStateOf(init.timestamp) }
    val sheetWindow = remember(sheetWindowCenter) {
        val half = CALIBRATION_SHEET_WINDOW_HALF_MS
        sheetWindowCenter - half to sheetWindowCenter + half
    }
    val windowedHistory by remember(sheetWindow) {
        dashboardViewModel.mergedWindowFlow(sheetWindow.first, sheetWindow.second)
    }.collectAsStateWithLifecycle(initialValue = emptyList())
    val sheetHistory = remember(windowedHistory, multiSensorDisplay, init.sensorId) {
        val sensorId = init.sensorId
        val source = if (sensorId.isNullOrBlank()) {
            windowedHistory
        } else {
            val primarySerial = windowedHistory.lastOrNull()?.sensorSerial
            if (primarySerial == null || SensorIdentity.matches(sensorId, primarySerial)) {
                windowedHistory
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
        },
        onSelectedTimestampChanged = { selected ->
            val quarter = CALIBRATION_SHEET_WINDOW_HALF_MS / 2
            if (selected < sheetWindowCenter - quarter || selected > sheetWindowCenter + quarter) {
                sheetWindowCenter = selected
            }
        }
    )
}

/** How far each side of its moment the calibration sheet reads the timeline. */
private const val CALIBRATION_SHEET_WINDOW_HALF_MS = 6L * 60L * 60L * 1000L

@Composable
private fun AdaptiveNavigationLabel(text: String) {
    val regularStyle = MaterialTheme.typography.labelMedium.copy(letterSpacing = 0.sp)
    val compactFontSize = MaterialTheme.typography.labelSmall.fontSize
    val autoSize = remember(compactFontSize, regularStyle.fontSize) {
        TextAutoSize.StepBased(
            minFontSize = compactFontSize,
            maxFontSize = regularStyle.fontSize,
            stepSize = 1.sp
        )
    }

    // ShortNavigationBar queries intrinsic sizes; a BoxWithConstraints label cannot provide them.
    // Its top-icon items only add vertical padding. Reserve a horizontal label gutter
    // before auto-sizing without shrinking the item's full-width touch target.
    Text(
        text = text,
        modifier = Modifier.padding(horizontal = 8.dp),
        style = regularStyle,
        autoSize = autoSize,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Ellipsis
    )
}

@Composable
fun MainApp(themeMode: ThemeMode, onThemeChanged: (ThemeMode) -> Unit) {
    val navController = rememberNavController()
    val dashboardViewModel: DashboardViewModel = viewModel()

    // Hoisted Calibration Sheet State
    var calibrationSheetState by remember { mutableStateOf<CalibrationSheetState>(CalibrationSheetState.Hidden) }

    // Restricted mode: the switch to full mode lives on the Settings tab. Something closed
    // that was reached by navigating (a locked screen, calibration) opens Settings and carries
    // on, back where it was asked from, once full mode is on.
    val restricted by RestrictedMode.state.collectAsStateWithLifecycle()
    var pendingFullModeAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var fullModeReturnRoute by remember { mutableStateOf<String?>(null) }
    val requestFullMode: (() -> Unit) -> Unit = { action ->
        if (!RestrictedMode.isLocked()) {
            action()
        } else {
            fullModeReturnRoute = navController.currentDestination?.route
            pendingFullModeAction = action
            navController.navigate("settings") {
                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                launchSingleTop = true
            }
        }
    }

    val onTriggerCalibration: (CalibrationSheetState) -> Unit = { state ->
        if (RestrictedMode.current().canCalibrate) {
            calibrationSheetState = state
        } else {
            requestFullMode { calibrationSheetState = state }
        }
    }
    LaunchedEffect(restricted.canCalibrate) {
        if (!restricted.canCalibrate) calibrationSheetState = CalibrationSheetState.Hidden
    }
    val navigationTapCatcher = remember { NavigationTapCatcher() }
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    // Handle back button to exit app when on start destination
    val currentBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = currentBackStackEntry?.destination?.route
    val journalEnabled by dashboardViewModel.journalEnabled.collectAsStateWithLifecycle()
    val journalNavigationTabEnabled by dashboardViewModel.journalNavigationTabEnabled.collectAsStateWithLifecycle()
    val showJournalNav = journalEnabled && journalNavigationTabEnabled &&
        (!restricted.locked || RestrictedModeRoutes.isOpenWhileLocked("journal", restricted.config))

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

    LifecycleStartEffect(currentRoute, dashboardViewModel) {
        dashboardViewModel.setCollectionMode(collectionModeForRoute(currentRoute))
        onStopOrDispose {
            dashboardViewModel.setCollectionMode(DashboardViewModel.CollectionMode.INACTIVE)
        }
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        dashboardViewModel.onResume()
    }

    // Full mode lasts while the app is in use; a minute away and it is restricted again.
    LifecycleEventEffect(Lifecycle.Event.ON_START) { RestrictedMode.onAppForegrounded() }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { RestrictedMode.onAppBackgrounded() }

    // The one gate for every way a screen opens — tabs, dashboard links, notification taps.
    // A closed screen reached while restricted goes to Settings, where full mode is switched
    // on, and opens once it is; one left open when the session locks again just closes.
    var gateWasLocked by remember { mutableStateOf(restricted.locked) }
    LaunchedEffect(restricted, currentRoute) {
        // The live state, not the collected one: unlocking and navigating happen in one
        // tap, and the collected value can still read "locked" for that frame.
        val live = RestrictedMode.current()
        val justLocked = live.locked && !gateWasLocked
        gateWasLocked = live.locked
        if (live.locked && !RestrictedModeRoutes.isOpenWhileLocked(currentRoute, live.config)) {
            val blocked = currentRoute
            navController.popBackStack(navController.graph.findStartDestination().id, inclusive = false)
            if (!justLocked && blocked != null) {
                requestFullMode { navController.navigate(blocked) { launchSingleTop = true } }
            }
        }
    }

    // Full mode switched on from Settings: back to where it was asked for, then carry on.
    LaunchedEffect(restricted.locked) {
        if (restricted.locked) return@LaunchedEffect
        val action = pendingFullModeAction ?: return@LaunchedEffect
        val back = fullModeReturnRoute
        pendingFullModeAction = null
        fullModeReturnRoute = null
        if (back != null && back != "settings" && navController.currentDestination?.route == "settings") {
            navController.navigate(back) {
                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        }
        action()
    }
    // Left Settings without switching: what was waiting is dropped, so unlocking later from
    // the tab does not suddenly run it.
    // The live destination, not the captured one: the gate above runs in the same frame for
    // a closed route and has already moved on to Settings.
    LaunchedEffect(currentRoute) {
        val live = navController.currentDestination?.route
        if (live != null && live != "settings") {
            pendingFullModeAction = null
            fullModeReturnRoute = null
        }
    }

    // The TalkBack legacy menus live outside Compose, on MainActivity's back stack, and reach
    // every setting. Left open when the session locks again, they are closed the way Back
    // would close them: the menus and everything opened from them.
    LaunchedEffect(restricted.locked) {
        if (restricted.locked && tk.glucodata.LegacyScreensAccess.menusOn) {
            var steps = 0
            while (tk.glucodata.MainActivity.onbacknr() > 0 && steps++ < 32) {
                tk.glucodata.MainActivity.doonback()
            }
        }
    }

    // A screen asked for from outside Compose — a notification's tap — is parked in
    // PendingNavigation by MainActivity and taken here, so it works from a cold start.
    // It is placed directly above the dashboard, as a tab tap would place it, so the
    // bar keeps working: whatever was open is popped (state saved), Back returns to
    // the dashboard, and the dashboard tab is one tap away.
    LaunchedEffect(navController) {
        PendingNavigation.route.collect { route ->
            if (route != null) {
                runCatching {
                    navController.navigate(route) {
                        popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                        launchSingleTop = true
                    }
                }
                PendingNavigation.consume()
            }
        }
    }

    // The pen receiver is a manifest component switched by a setting; line it up with
    // the settings once per start, for an upgrade or a restored backup.
    LaunchedEffect(Unit) {
        InsulinPenManager.syncBackgroundReceiver(context)
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
        route == "journal" -> "journal"
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
    data class NavItem(
        val route: String,
        val label: String,
        val selectedIcon: ImageVector,
        val unselectedIcon: ImageVector,
        val glyph: TabGlyph
    )
    val navItems = buildList {
        add(NavItem("stats", stringResource(R.string.statistics_title), Icons.Filled.BarChart, Icons.Outlined.BarChart, TabGlyph.STATISTICS))
        if (showJournalNav) {
            add(NavItem("journal", stringResource(R.string.journal_title), Icons.Filled.EditNote, Icons.Outlined.EditNote, TabGlyph.JOURNAL))
        }
        add(NavItem("dashboard", stringResource(R.string.dashboard), Icons.Filled.LegendToggle, Icons.Outlined.LegendToggle, TabGlyph.DASHBOARD))
        add(NavItem("sensors", stringResource(R.string.sensor), Icons.Filled.Sensors, Icons.Outlined.Sensors, TabGlyph.SENSORS))
        add(NavItem("settings", stringResource(R.string.settings), Icons.Filled.Settings, Icons.Outlined.Settings, TabGlyph.SETTINGS))
    }
    val selectedNavRoute = navItems.firstOrNull {
        it.route == currentRoute || it.route == getParentRoute(currentRoute)
    }?.route
    // Before the graph has its first route nothing is selected yet; the icons must not
    // read that first selection as a tab switch.
    val selectionKnown = currentRoute != null
    val navigationTick = rememberNavigationTick()
    val onNavItemClick = { route: String ->
        if (route != selectedNavRoute) navigationTick.play()
        if (restricted.locked && !RestrictedModeRoutes.isOpenWhileLocked(route, restricted.config)) {
            requestFullMode { onNavigate(route) }
        } else {
            onNavigate(route)
        }
    }
    // The Settings tab carries a small "open in full" mark while the phone is restricted: it
    // opens to the page where full mode is switched on.
    val tabLocked = { route: String -> restricted.locked && route == "settings" }

    // One NavHost in one place for both orientations. Rotation is handled by this activity
    // (configChanges), so the composition survives it; two NavHosts — one beside a rail, one
    // above a bar — meant every rotation disposed the open screen and rebuilt it from scratch.
    // The rail or the bar comes and goes around it; the NavHost keeps its slot.
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0), // Child screens apply their own insets.
        bottomBar = {
            if (!isLandscape) {
                Box {
                    ShortNavigationBar(arrangement = ShortNavigationBarArrangement.EqualWeight) {
                        navItems.forEach { item ->
                            key(item.route) {
                                val isSelected = item.route == selectedNavRoute
                                val interactionSource = remember { MutableInteractionSource() }
                                val pressed by interactionSource.collectIsPressedAsState()
                                ShortNavigationBarItem(
                                    iconPosition = NavigationItemIconPosition.Top,
                                    icon = {
                                        LockableTabIcon(locked = tabLocked(item.route)) {
                                            TabIcon(
                                                isSelected = isSelected,
                                                selectedIcon = item.selectedIcon,
                                                unselectedIcon = item.unselectedIcon,
                                                description = item.label,
                                                glyph = item.glyph,
                                                pressed = pressed,
                                                selectionKnown = selectionKnown
                                            )
                                        }
                                    },
                                    label = {
                                        FontScaleCap { AdaptiveNavigationLabel(item.label) }
                                    },
                                    selected = isSelected,
                                    onClick = { onNavItemClick(item.route) },
                                    interactionSource = interactionSource
                                )
                            }
                        }
                    }
                    NavigationTapCatcherLayer(navigationTapCatcher)
                }
            }
        }
    ) { innerPadding ->
        Row(modifier = Modifier.fillMaxSize()) {
            if (isLandscape) {
                // The collapsed wide rail, the expressive partner of the portrait ShortNavigationBar.
                // Centred, for the thumb on a phone held sideways.
                Box {
                    WideNavigationRail(arrangement = Arrangement.Center) {
                        navItems.forEach { item ->
                            key(item.route) {
                                val isSelected = item.route == selectedNavRoute
                                val interactionSource = remember { MutableInteractionSource() }
                                val pressed by interactionSource.collectIsPressedAsState()
                                WideNavigationRailItem(
                                    railExpanded = false,
                                    icon = {
                                        LockableTabIcon(locked = tabLocked(item.route)) {
                                            TabIcon(
                                                isSelected = isSelected,
                                                selectedIcon = item.selectedIcon,
                                                unselectedIcon = item.unselectedIcon,
                                                description = item.label,
                                                glyph = item.glyph,
                                                pressed = pressed,
                                                selectionKnown = selectionKnown
                                            )
                                        }
                                    },
                                    label = { FontScaleCap { Text(item.label) } },
                                    selected = isSelected,
                                    onClick = { onNavItemClick(item.route) },
                                    interactionSource = interactionSource
                                )
                            }
                        }
                    }
                    NavigationTapCatcherLayer(navigationTapCatcher)
                }
            }
            CompositionLocalProvider(
                LocalNavigationTapCatcher provides navigationTapCatcher,
                LocalRequestFullMode provides requestFullMode,
            ) {
                NavHost(
                    navController = navController,
                    startDestination = "dashboard",
                    modifier = Modifier
                        .weight(1f)
                        .padding(innerPadding)
                        .consumeWindowInsets(innerPadding),
                    // A short fade between destinations. NavHost's own default is a 700ms
                    // crossfade; landscape used to have none at all and portrait this one.
                    enterTransition = { fadeIn(animationSpec = tween(200)) },
                    exitTransition = { fadeOut(animationSpec = tween(200)) },
                    popEnterTransition = { fadeIn(animationSpec = tween(200)) },
                    popExitTransition = { fadeOut(animationSpec = tween(200)) }
                ) {
                    appDestinations(
                        navController = navController,
                        dashboardViewModel = dashboardViewModel,
                        themeMode = themeMode,
                        onThemeChanged = onThemeChanged,
                        onTriggerCalibration = onTriggerCalibration,
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

/**
 * The tab icon, with a small "open in full" mark while its screen needs full mode. Always inside the
 * BadgedBox, so the icon keeps its state (and its one-shot glyph motion) when the mark
 * comes and goes.
 */
@Composable
private fun LockableTabIcon(locked: Boolean, icon: @Composable () -> Unit) {
    BadgedBox(
        badge = {
            if (locked) {
                Badge(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                ) {
                    Icon(
                        Icons.Filled.OpenInFull,
                        contentDescription = stringResource(R.string.restricted_unlock_title),
                        modifier = Modifier.size(12.dp),
                    )
                }
            }
        },
    ) { icon() }
}

/**
 * A destination behind the restricted-mode gate. While a closed route is on screen for the
 * moment it takes MainApp's gate to send it back, it draws nothing rather than its content.
 */
private fun NavGraphBuilder.gatedComposable(
    route: String,
    content: @Composable AnimatedContentScope.(NavBackStackEntry) -> Unit,
) = composable(route) { entry ->
    // Collected so a change recomposes; the live state decides, so a relock the collector
    // has not delivered yet still draws nothing.
    val restricted by RestrictedMode.state.collectAsStateWithLifecycle()
    val live = restricted.let { RestrictedMode.current() }
    if (live.locked && !RestrictedModeRoutes.isOpenWhileLocked(route, live.config)) {
        Box(Modifier.fillMaxSize())
    } else {
        content(entry)
    }
}

private fun NavGraphBuilder.appDestinations(
    navController: NavHostController,
    dashboardViewModel: DashboardViewModel,
    themeMode: ThemeMode,
    onThemeChanged: (ThemeMode) -> Unit,
    onTriggerCalibration: (CalibrationSheetState) -> Unit,
) {
    gatedComposable("dashboard") {
        DashboardRoute(
            dashboardViewModel = dashboardViewModel,
            navController = navController,
            onTriggerCalibration = onTriggerCalibration
        )
    }
    gatedComposable("history") {
        HistoryRoute(
            dashboardViewModel = dashboardViewModel,
            title = stringResource(R.string.historyname),
            browseMode = TimelineBrowseMode.HISTORY,
            onBack = { navController.popBackStack() },
            onTriggerCalibration = onTriggerCalibration
        )
    }
    gatedComposable("journal") {
        JournalRoute(
            dashboardViewModel = dashboardViewModel,
            navController = navController,
            onTriggerCalibration = onTriggerCalibration,
        )
    }
    gatedComposable("stats") { FontScaleCap { tk.glucodata.ui.stats.StatsScreen() } }
    gatedComposable("sensors") {
        SensorScreen(
            onNavigateToMqAccount = { navController.navigate("settings/mq-account") },
            onNavigateToReadiness = { navController.navigate("settings/cgm-readiness") }
        )
    }
    gatedComposable("settings") { ExpressiveSettingsScreen(navController, themeMode, onThemeChanged, dashboardViewModel) }
    gatedComposable("settings/restricted-mode") { RestrictedModeSettingsScreen(navController) }
    gatedComposable("settings/restricted-mode/pin") { RestrictedPinSetupScreen(navController) }
    gatedComposable("settings/nightscout") { NightscoutSettingsScreen(navController) }
    gatedComposable("settings/libreview") { LibreViewSettingsScreen(navController) }
    gatedComposable("settings/mq-account") { MQAccountSettingsScreen(navController) }
    gatedComposable("settings/mq-follower") { MQFollowerSettingsScreen(navController) }
    gatedComposable("settings/ottai") { tk.glucodata.ui.setup.OttaiSettingsScreen(navController) }
    gatedComposable("settings/mirror") { MirrorSettingsScreen(navController) }
    gatedComposable("settings/outbound-api") { OutboundApiSettingsScreen(navController) }
    gatedComposable("settings/dexcom-share") { DexcomShareSettingsScreen(navController) }
    gatedComposable("settings/lumiflex") { LumiflexCloudSettingsScreen(navController) }
    gatedComposable("settings/lumiflex/followers") { LumiflexCloudFollowersScreen(navController) }
    gatedComposable("settings/api-source") { ApiSourceSettingsScreen(navController) }
    gatedComposable("settings/glucose-meters") { GlucoseMeterSettingsScreen(navController) }
    gatedComposable("settings/insulin-pens") { InsulinPenSettingsScreen(navController) }
    gatedComposable("settings/watch") { WatchSettingsScreen(navController) }
    gatedComposable("settings/phone-apps") { PhoneAppsSettingsScreen(navController, dashboardViewModel) }
    gatedComposable("settings/sensor-connection") { SensorConnectionSettingsScreen(navController) }
    // Keep legacy route for backward compatibility.
    gatedComposable("settings/weartransport") { WatchSettingsScreen(navController) }
    gatedComposable("settings/watch/wearos-config") { WearOsConfigScreen(navController) }
    gatedComposable("settings/watch/garmin-status") { GarminStatusScreen(navController) }
    gatedComposable("settings/webserver") { WebServerSettingsScreen(navController) }
    gatedComposable("settings/notification-display") {
        NotificationSettingsScreen(navController, dashboardViewModel)
    }
    gatedComposable("settings/display-colors") {
        DisplayAndColorSettingsScreen(navController, dashboardViewModel)
    }
    gatedComposable("settings/data-smoothing") {
        DataSmoothingSettingsScreen(navController, dashboardViewModel)
    }
    gatedComposable("settings/predictive-simulation") {
        PredictiveSimulationSettingsScreen(navController, dashboardViewModel)
    }
    gatedComposable("settings/predictive-simulation/model-profile") {
        PredictionModelProfileScreen(navController, dashboardViewModel)
    }
    gatedComposable("settings/predictive-simulation/model-profile/past-meals") {
        MealReplayScreen(navController, dashboardViewModel)
    }
    gatedComposable("settings/predictive-simulation/analysis") {
        PredictionAnalysisScreen(navController, dashboardViewModel)
    }
    gatedComposable("settings/predictive-simulation/analysis/forecasts") {
        PredictionInspectorScreen(navController, dashboardViewModel)
    }
    gatedComposable("settings/floating-display") {
        FloatingGlucoseSettingsScreen(navController, dashboardViewModel)
    }
    gatedComposable("settings/aod-display") { AodSettingsScreen(navController) }

    gatedComposable("settings/turnserver") { tk.glucodata.ui.TurnServerSettingsScreen(navController) }
    gatedComposable("settings/debug") { DebugSettingsScreen(navController) }
    gatedComposable("settings/cgm-readiness") { CgmReadinessScreen(navController) }
    gatedComposable("settings/app-updates") { AppUpdatesScreen(navController) }
    gatedComposable("settings/alerts") { tk.glucodata.ui.alerts.AlertSettingsScreen(navController) }
    gatedComposable("settings/alerts/talker") { tk.glucodata.ui.alerts.TalkerSettingsScreen(navController) }
    gatedComposable("settings/journal") { JournalSettingsScreen(navController, dashboardViewModel) }
    gatedComposable("settings/journal/calculations") { JournalCalculationsSettingsScreen(navController, dashboardViewModel) }
    gatedComposable("settings/journal/entry-types") { JournalEntryTypesSettingsScreen(navController, dashboardViewModel) }
    gatedComposable("settings/journal/history") {
        JournalSettingsHistoryRoute(
            dashboardViewModel = dashboardViewModel,
            navController = navController,
            onTriggerCalibration = onTriggerCalibration
        )
    }
    gatedComposable("settings/journal/foods") { JournalFoodLibraryScreen(navController, dashboardViewModel) }
    gatedComposable("settings/journal/insulin") { JournalInsulinLibraryScreen(navController, dashboardViewModel) }
    gatedComposable("settings/calibrations") {
        CalibrationListRoute(
            dashboardViewModel = dashboardViewModel,
            navController = navController,
            onTriggerCalibration = onTriggerCalibration
        )
    }
    gatedComposable("settings/calibrations/model-table") {
        CalibrationModelTableRoute(
            dashboardViewModel = dashboardViewModel,
            navController = navController,
            onTriggerCalibration = onTriggerCalibration
        )
    }
    gatedComposable("calibrations") {
        CalibrationListRoute(
            dashboardViewModel = dashboardViewModel,
            navController = navController,
            onTriggerCalibration = onTriggerCalibration
        )
    }
    gatedComposable("calibrations/model-table") {
        CalibrationModelTableRoute(
            dashboardViewModel = dashboardViewModel,
            navController = navController,
            onTriggerCalibration = onTriggerCalibration
        )
    }
}
