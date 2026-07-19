@file:OptIn(ExperimentalMaterial3Api::class)

package tk.glucodata.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.appcompat.app.AppCompatActivity
import androidx.annotation.Keep
import androidx.core.content.res.ResourcesCompat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.material3.Slider
import tk.glucodata.ui.components.StyledSwitch
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.Alignment
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.clip
import androidx.compose.ui.zIndex
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.ui.platform.LocalConfiguration
import android.content.res.Configuration
import android.text.Layout
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.graphics.RectangleShape
import tk.glucodata.SensorIdentity
import tk.glucodata.ui.util.ConnectedButtonGroup
import tk.glucodata.ui.util.AdaptiveLayoutDensity
import tk.glucodata.ui.util.findActivity
import tk.glucodata.ui.util.hardRestart
import tk.glucodata.ui.util.rememberAdaptiveWindowMetrics
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.graphics.lerp
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.draw.alpha
import androidx.compose.animation.togetherWith

import androidx.compose.ui.draw.alpha

import androidx.compose.animation.core.tween
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.shrinkHorizontally

import androidx.compose.material.icons.automirrored.filled.LastPage
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Download
import kotlinx.coroutines.launch
import tk.glucodata.CurrentDisplaySource
import tk.glucodata.DataSmoothing
import tk.glucodata.DisplayDataState
import tk.glucodata.Libre3NfcSettings
import tk.glucodata.Natives
import tk.glucodata.Notify
import tk.glucodata.SensorBluetooth
import tk.glucodata.QRmake
import tk.glucodata.R
import tk.glucodata.MainActivity
import tk.glucodata.UiRefreshBus
import android.widget.Toast
import tk.glucodata.data.journal.JournalEntry
import tk.glucodata.data.journal.JournalEntryType
import tk.glucodata.data.journal.JournalActiveInsulinSummary
import tk.glucodata.data.journal.JournalFood
import tk.glucodata.data.journal.JournalInsulinPreset
import tk.glucodata.data.journal.JournalPeriodSummaryCalculator
import tk.glucodata.data.prediction.GlucosePredictionSeries
import tk.glucodata.data.prediction.GlucosePredictionSeriesKind
import tk.glucodata.data.prediction.PredictiveSimulationSettings
import tk.glucodata.data.prediction.buildGlucosePrediction
import tk.glucodata.ui.journal.JournalDoseProfile
import tk.glucodata.ui.journal.JournalEntrySheet
import tk.glucodata.ui.journal.JournalFloatingActionMenu
import tk.glucodata.ui.journal.JournalExpandableFab
import tk.glucodata.ui.journal.JournalInlineChip
import tk.glucodata.ui.journal.JournalSettingsScreen
import tk.glucodata.data.journal.JournalIobCalculator
import tk.glucodata.ui.journal.buildJournalChartMarkers
import tk.glucodata.ui.viewmodel.DashboardViewModel
import tk.glucodata.ui.theme.displayLargeExpressive
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.compose.ui.res.stringResource
import java.util.Locale
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Icon
import androidx.compose.ui.platform.LocalUriHandler
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.outlined.ShowChart
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.outlined.Sensors
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.outlined.Analytics
import androidx.compose.material.icons.outlined.LegendToggle
import androidx.compose.material.icons.rounded.TrendingUp
import androidx.compose.material.icons.rounded.TrendingDown
import androidx.compose.material.icons.rounded.TrendingFlat
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.LifecycleEventObserver
import tk.glucodata.ui.components.CardPosition
import tk.glucodata.ui.components.MasterSwitchCard
import tk.glucodata.ui.components.SectionLabel
import tk.glucodata.ui.components.SettingsItem
import tk.glucodata.ui.components.SettingsSwitchItem
import kotlin.math.abs
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.withStyle
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown

import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.animation.Crossfade
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange

enum class TimeRange(val label: String, val hours: Int) {
    H1("1H", 1),
    H3("3H", 3),
    H6("6H", 6),
    H12("12H", 12),
    H24("24H", 24),
    D3("3D", 72);

    companion object {
        fun fromPreference(value: String?): TimeRange =
            values().firstOrNull { it.name == value } ?: H3
    }
}

@Composable
fun DashboardScreen(
    viewModel: DashboardViewModel = viewModel(),
    calibrations: List<tk.glucodata.data.calibration.CalibrationEntity> = emptyList(),
    onNavigateToCalibrations: () -> Unit = {},
    onNavigateToHistory: () -> Unit = {},
    onNavigateToMqAccount: () -> Unit = {},
    onNavigateToReadiness: () -> Unit = {},
    onTriggerCalibration: (CalibrationSheetState) -> Unit = {}
) {
    val context = LocalContext.current
    val view = LocalView.current
    val dashboardPrefs = remember(context) {
        context.getSharedPreferences("tk.glucodata_preferences", Context.MODE_PRIVATE)
    }
    var timeRange by rememberSaveable {
        mutableStateOf(
            TimeRange.fromPreference(
                dashboardPrefs.getString("dashboard_chart_time_range", TimeRange.H3.name)
            )
        )
    }
    LaunchedEffect(timeRange) {
        dashboardPrefs.edit().putString("dashboard_chart_time_range", timeRange.name).apply()
    }
    val currentGlucose by viewModel.currentGlucose.collectAsState()
    val currentRate by viewModel.currentRate.collectAsState()
    val sensorName by viewModel.sensorName.collectAsState()
    val daysRemaining by viewModel.daysRemaining.collectAsState()
    val glucoseHistory by viewModel.glucoseHistory.collectAsState()
    val multiSensorDisplay by viewModel.multiSensorDisplay.collectAsState()
    val peerCurrentReadings by viewModel.peerCurrentReadings.collectAsState()
    val selectedSensorIds by viewModel.selectedSensorIds.collectAsState()
    val sensorViewModes by viewModel.sensorViewModes.collectAsState()
    // Multi-sensor mode is active whenever more than one sensor is selected —
    // stable across new readings, so per-row tinting never flashes uncolored.
    val multiSensorActive = selectedSensorIds.size > 1
    val unit by viewModel.unit.collectAsState()
    val graphLow by viewModel.graphLow.collectAsState()
    val graphHigh by viewModel.graphHigh.collectAsState()
    val targetLow by viewModel.targetLow.collectAsState()
    val targetHigh by viewModel.targetHigh.collectAsState()
    val veryLowThreshold by viewModel.veryLowThreshold.collectAsState()
    val veryHighThreshold by viewModel.veryHighThreshold.collectAsState()
    val graphSmoothingLevel by viewModel.graphSmoothingLevel.collectAsState()
    val previewWindowMode by viewModel.previewWindowMode.collectAsState()
    val journalEnabled by viewModel.journalEnabled.collectAsState()
    val journalEiobDisplayEnabled by viewModel.journalEiobDisplayEnabled.collectAsState()
    val glucoseRangeColorsDisplayEnabled by viewModel.glucoseValueRangeColorsEnabled.collectAsState()
    val glucoseArrowForecastEnabled by viewModel.glucoseArrowForecastColorsEnabled.collectAsState()
    val appChartRangeColorsEnabled by viewModel.glucoseAppChartRangeColorsEnabled.collectAsState()
    val journalDoseCalculatorEnabled by viewModel.journalDoseCalculatorEnabled.collectAsState()
    val journalFoodMacrosEnabled by viewModel.journalFoodMacrosEnabled.collectAsState()
    val journalFoodLibraryEnabled by viewModel.journalFoodLibraryEnabled.collectAsState()
    val predictiveSimulationEnabled by viewModel.predictiveSimulationEnabled.collectAsState()
    val predictionTrendMomentumEnabled by viewModel.predictionTrendMomentumEnabled.collectAsState()
    val predictionCarbRatioGramsPerUnit by viewModel.predictionCarbRatioGramsPerUnit.collectAsState()
    val predictionInsulinSensitivityMgDlPerUnit by viewModel.predictionInsulinSensitivityMgDlPerUnit.collectAsState()
    val predictionCarbAbsorptionGramsPerHour by viewModel.predictionCarbAbsorptionGramsPerHour.collectAsState()
    val predictionHorizonMinutes by viewModel.predictionHorizonMinutes.collectAsState()
    val journalEntries by viewModel.journalEntries.collectAsState()
    val journalInsulinPresets by viewModel.journalInsulinPresets.collectAsState()
    val journalFoods by viewModel.journalFoods.collectAsState()
    val sensorStatus by viewModel.sensorStatus.collectAsState()
    val sensorProgress by viewModel.sensorProgress.collectAsState()
    val viewMode by viewModel.viewMode.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val activeSensorList by viewModel.activeSensorList.collectAsState()
    val sensorHoursRemaining by viewModel.sensorHoursRemaining.collectAsState()
    val currentDay by viewModel.currentDay.collectAsState()
    val predictionCalibrationRefresh by UiRefreshBus.revision.collectAsState(initial = 0L)
    val calibrationRevision by tk.glucodata.data.calibration.CalibrationManager.revision.collectAsState()

    // Initialize Calibration Manager
    LaunchedEffect(Unit) {
        tk.glucodata.data.calibration.CalibrationManager.init(context)
        tk.glucodata.data.calibration.CalibrationManager.loadCalibrations()
    }
    // State for wizards (matching SensorScreen pattern)
    var showSibionicsWizard by remember { mutableStateOf(false) }
    var showLibreWizard by remember { mutableStateOf(false) }
    var showDexcomWizard by remember { mutableStateOf(false) }
    var showAccuChekWizard by remember { mutableStateOf(false) }
    var showCareSensAirWizard by remember { mutableStateOf(false) }
    var showAiDexWizard by remember { mutableStateOf(false) }
    var showICanHealthWizard by remember { mutableStateOf(false) }
    var showMQWizard by remember { mutableStateOf(false) }
    var showAnytimeWizard by remember { mutableStateOf(false) }
    var showOttaiWizard by remember { mutableStateOf(false) }
    var journalEditorRequest by remember { mutableStateOf<JournalEditorRequest?>(null) }
    var journalActionTimestamp by rememberSaveable { mutableStateOf<Long?>(null) }
    var journalActionSuggestedGlucoseMgDl by remember { mutableStateOf<Float?>(null) }
    var journalActionSuggestedAmountFraction by remember { mutableStateOf<Float?>(null) }
    var lastJournalType by rememberSaveable { mutableStateOf(JournalEntryType.INSULIN) }
    var dashboardFabExpanded by rememberSaveable { mutableStateOf(false) }
    var journalNow by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var dashboardChartViewport by remember { mutableStateOf<ChartViewportSnapshot?>(null) }

    val coroutineScope = rememberCoroutineScope()
    val journalPresetsById = remember(journalInsulinPresets) { journalInsulinPresets.associateBy { it.id } }
    val journalFoodsById = remember(journalFoods) { journalFoods.associateBy { it.id } }
    val activeJournalPresets = remember(journalInsulinPresets) { journalInsulinPresets.filter { !it.isArchived } }
    // Mirror the History route: journal entries are time-bound, not
    // sensor-bound. Scoping them by the active sensor would silently hide
    // entries logged before a sensor swap.
    val scopedJournalEntries = remember(journalEnabled, journalEntries) {
        if (!journalEnabled) emptyList() else journalEntries
    }
    val journalChartMarkers = remember(journalEnabled, scopedJournalEntries, journalPresetsById, journalFoodsById, unit, glucoseHistory) {
        if (!journalEnabled || scopedJournalEntries.isEmpty()) {
            emptyList()
        } else {
            buildJournalChartMarkers(scopedJournalEntries, journalPresetsById, unit, glucoseHistory, journalFoodsById)
        }
    }
    val activeInsulinSummary = remember(journalEnabled, scopedJournalEntries, journalPresetsById, journalNow) {
        if (!journalEnabled || scopedJournalEntries.isEmpty()) {
            null
        } else {
            JournalIobCalculator.buildActiveInsulinSummary(scopedJournalEntries, journalPresetsById, journalNow)
        }
    }
    val todayRange = remember(journalNow) {
        val zone = java.time.ZoneId.systemDefault()
        val date = java.time.Instant.ofEpochMilli(journalNow).atZone(zone).toLocalDate()
        val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
        start until date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    }
    val journalTodaySummary = remember(scopedJournalEntries, todayRange) {
        JournalPeriodSummaryCalculator.calculate(
            entries = scopedJournalEntries,
            startMillisInclusive = todayRange.first,
            endMillisExclusive = todayRange.last + 1L
        )
    }
    val dashboardInsulinSummary = activeInsulinSummary ?: journalTodaySummary
        .takeIf { it.eventCount > 0 }
        ?.let {
            JournalActiveInsulinSummary(
                activeEntryCount = 0,
                totalUnits = 0f,
                weightedActivityPercent = 0,
                nextEndingAt = null,
                iobUnits = 0f,
                eiobUnits = 0f
            )
        }
    val predictionSettings = remember(
        predictiveSimulationEnabled,
        predictionTrendMomentumEnabled,
        predictionCarbRatioGramsPerUnit,
        predictionInsulinSensitivityMgDlPerUnit,
        predictionCarbAbsorptionGramsPerHour,
        predictionHorizonMinutes,
        journalEnabled,
        journalFoodMacrosEnabled
    ) {
        PredictiveSimulationSettings(
            enabled = predictiveSimulationEnabled,
            trendMomentumEnabled = predictionTrendMomentumEnabled,
            horizonMinutes = predictionHorizonMinutes,
            carbRatioGramsPerUnit = predictionCarbRatioGramsPerUnit,
            insulinSensitivityMgDlPerUnit = predictionInsulinSensitivityMgDlPerUnit,
            carbAbsorptionGramsPerHour = predictionCarbAbsorptionGramsPerHour,
            foodMacrosEnabled = journalEnabled && journalFoodMacrosEnabled
        )
    }
    // Prediction, current values, rows and chart hit-testing all use actual
    // readings. Visual graph smoothing is applied only inside DashboardChart.
    val consumerHistory = glucoseHistory
    val predictionSeries = remember(
        journalEnabled,
        predictionSettings,
        consumerHistory,
        viewMode,
        predictionCalibrationRefresh,
        scopedJournalEntries,
        journalPresetsById,
        unit,
        targetLow,
        targetHigh
    ) {
        buildPredictionSeriesForChart(
            points = consumerHistory,
            journalEntries = if (journalEnabled) scopedJournalEntries else emptyList(),
            insulinPresetsById = journalPresetsById,
            unit = unit,
            targetLow = targetLow,
            targetHigh = targetHigh,
            viewMode = viewMode,
            settings = predictionSettings
        )
    }
    // Per-peer prediction series so the simulation extends every drawn line,
    // not just the primary. Same journal/settings (same person), each peer's
    // own points + view mode.
    val peerPredictionSeries = remember(
        multiSensorDisplay,
        journalEnabled,
        predictionSettings,
        predictionCalibrationRefresh,
        scopedJournalEntries,
        journalPresetsById,
        unit,
        targetLow,
        targetHigh
    ) {
        if (multiSensorDisplay.isEmpty || !predictionSettings.enabled) {
            emptyMap()
        } else {
            multiSensorDisplay.series.associate { peer ->
                peer.sensorId to buildPredictionSeriesForChart(
                    points = peer.points,
                    journalEntries = if (journalEnabled) scopedJournalEntries else emptyList(),
                    insulinPresetsById = journalPresetsById,
                    unit = unit,
                    targetLow = targetLow,
                    targetHigh = targetHigh,
                    viewMode = peer.viewMode,
                    settings = predictionSettings
                )
            }.filterValues { it.isNotEmpty() }
        }
    }
    val journalEntriesById = remember(scopedJournalEntries) { scopedJournalEntries.associateBy { it.id } }
    val isMmolUnit = remember(unit) { tk.glucodata.ui.util.GlucoseFormatter.isMmol(unit) }
    val journalActionSuggestedDisplayValue = remember(journalActionSuggestedGlucoseMgDl, isMmolUnit) {
        journalActionSuggestedGlucoseMgDl?.let {
            tk.glucodata.ui.util.GlucoseFormatter.displayFromMgDl(it, isMmolUnit)
        }
    }
    fun clearJournalAction(withHaptic: Boolean = false) {
        journalActionTimestamp = null
        journalActionSuggestedGlucoseMgDl = null
        journalActionSuggestedAmountFraction = null
        if (withHaptic) {
            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        }
    }
    fun showJournalAction(suggestion: ChartTimelineTapSuggestion) {
        if (journalActionTimestamp != null && !suggestion.forceMenu) {
            clearJournalAction(withHaptic = true)
            return
        }
        val suggestedMgDl = suggestion.suggestedDisplayGlucose?.let {
            if (isMmolUnit) tk.glucodata.ui.util.GlucoseFormatter.mmolToMg(it) else it
        }
        journalActionTimestamp = suggestion.timestamp
        journalActionSuggestedGlucoseMgDl = suggestedMgDl
        journalActionSuggestedAmountFraction = suggestion.normalizedYFraction
        view.performHapticFeedback(
            if (suggestion.forceMenu) HapticFeedbackConstants.LONG_PRESS
            else HapticFeedbackConstants.CLOCK_TICK
        )
    }
    LaunchedEffect(journalEnabled) {
        if (!journalEnabled) {
            clearJournalAction()
            journalEditorRequest = null
        }
    }

    LaunchedEffect(journalEnabled) {
        journalNow = System.currentTimeMillis()
        if (!journalEnabled) return@LaunchedEffect
        while (true) {
            delay(30_000L)
            journalNow = System.currentTimeMillis()
        }
    }

    // Import launcher for glucose history. Accepts a JugglucoNG export package
    // (JSON) — from which ONLY the glucose section is imported — and history CSV
    // files. Settings/calibrations in a package are intentionally ignored here.
    fun toast(message: String) {
        android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_LONG).show()
    }
    val importLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            coroutineScope.launch {
                when {
                    tk.glucodata.data.ExportPackageExporter.isExportPackage(context, uri) -> {
                        tk.glucodata.data.ExportPackageExporter.importHistoryFromPackage(context, uri)
                            .onSuccess { outcome ->
                                if (outcome == null || outcome.readings == 0) {
                                    // Valid package, but it has no glucose (e.g. settings-only export)
                                    toast(context.getString(R.string.import_no_glucose))
                                } else {
                                    toast(context.getString(R.string.imported_readings_count, outcome.readings))
                                    viewModel.onHistoryImported(outcome.displaySerial)
                                }
                            }
                            .onFailure { throwable ->
                                toast(context.getString(R.string.import_failed_with_error, throwable.localizedMessage ?: ""))
                            }
                    }
                    tk.glucodata.data.SettingsExporter.isSettingsExport(context, uri) -> {
                        // A valid JugglucoNG settings export contains no glucose data
                        toast(context.getString(R.string.import_no_glucose))
                    }
                    else -> {
                        val result = tk.glucodata.data.HistoryExporter.importFromCsv(context, uri)
                        when {
                            result.success && result.successCount > 0 -> {
                                toast(context.getString(R.string.imported_readings_count, result.successCount))
                                viewModel.onHistoryImported(tk.glucodata.data.HistoryRepository.IMPORTED_SENSOR_SERIAL)
                            }
                            result.success -> toast(context.getString(R.string.import_no_glucose))
                            else -> toast(context.getString(R.string.import_unsupported_file))
                        }
                    }
                }
            }
        }
    }

    // Sibionics Setup Wizard (Full Screen)
    if (showSibionicsWizard) {
        tk.glucodata.ui.setup.SibionicsSetupWizard(
            onDismiss = { showSibionicsWizard = false },
            onNavigateToReadiness = onNavigateToReadiness,
            onComplete = {
                showSibionicsWizard = false
                viewModel.refreshData()
            }
        )
        return // Exit early to show wizard full screen
    }

    // Libre Setup Wizard
    if (showLibreWizard) {
        tk.glucodata.ui.setup.LibreSetupWizard(
            onDismiss = { showLibreWizard = false },
            onNavigateToReadiness = onNavigateToReadiness,
            onScanNfc = {
                showLibreWizard = false
                tk.glucodata.MainActivity.launchLibreNfcScan()
            }
        )
        return
    }

    // Dexcom Setup Wizard
    if (showDexcomWizard) {
        tk.glucodata.ui.setup.DexcomSetupWizard(
            onDismiss = { showDexcomWizard = false },
            onNavigateToReadiness = onNavigateToReadiness,
            onScanResult = { raw ->
                tk.glucodata.MainActivity.handleInlineQrScan(raw, tk.glucodata.MainActivity.REQUEST_BARCODE)
                showDexcomWizard = false
            }
        )
        return
    }

    // Accu-Chek Setup Wizard
    if (showAccuChekWizard) {
        tk.glucodata.ui.setup.AccuChekSetupWizard(
            onDismiss = { showAccuChekWizard = false },
            onNavigateToReadiness = onNavigateToReadiness,
            onScanResult = { raw ->
                tk.glucodata.MainActivity.handleInlineQrScan(raw, tk.glucodata.MainActivity.REQUEST_BARCODE)
                showAccuChekWizard = false
            }
        )
        return
    }

    // CareSens Air Setup Wizard
    if (showCareSensAirWizard) {
        tk.glucodata.ui.setup.CareSensAirSetupWizard(
            onDismiss = { showCareSensAirWizard = false },
            onNavigateToReadiness = onNavigateToReadiness,
            onScanResult = { raw ->
                tk.glucodata.MainActivity.handleInlineQrScan(raw, tk.glucodata.MainActivity.REQUEST_BARCODE)
                showCareSensAirWizard = false
            }
        )
        return
    }

    // AiDex Setup Wizard
    if (showAiDexWizard) {
        tk.glucodata.ui.setup.AiDexSetupWizard(
            onDismiss = { showAiDexWizard = false },
            onNavigateToReadiness = onNavigateToReadiness,
            onComplete = {
                showAiDexWizard = false
                viewModel.refreshData()
            }
        )
        return
    }

    // iCan Health Setup Wizard
    if (showICanHealthWizard) {
        tk.glucodata.ui.setup.ICanHealthSetupWizard(
            onDismiss = { showICanHealthWizard = false },
            onNavigateToReadiness = onNavigateToReadiness,
            onComplete = {
                showICanHealthWizard = false
                viewModel.refreshData()
            }
        )
        return
    }

    // MQ / Glutec Setup Wizard
    if (showMQWizard) {
        tk.glucodata.ui.setup.MQSetupWizard(
            onDismiss = { showMQWizard = false },
            onNavigateToReadiness = onNavigateToReadiness,
            onComplete = {
                showMQWizard = false
                viewModel.refreshData()
            },
        )
        return
    }

    // Anytime / Yuwell CT3 Setup Wizard
    if (showAnytimeWizard) {
        tk.glucodata.ui.setup.AnytimeSetupWizard(
            onDismiss = { showAnytimeWizard = false },
            onNavigateToReadiness = onNavigateToReadiness,
            onComplete = {
                showAnytimeWizard = false
                viewModel.refreshData()
            },
        )
        return
    }

    // Ottai Setup Wizard
    if (showOttaiWizard) {
        tk.glucodata.ui.setup.OttaiSetupWizard(
            onDismiss = { showOttaiWizard = false },
            onComplete = {
                showOttaiWizard = false
                viewModel.refreshData()
            },
        )
        return
    }

    journalEditorRequest?.let { request ->
        JournalEntrySheet(
            unit = unit,
            selectedTimestamp = request.timestamp,
            suggestedGlucoseMgDl = request.suggestedGlucoseMgDl,
            suggestedChartAnchorGlucoseMgDl = request.suggestedChartAnchorGlucoseMgDl,
            suggestedAmountFraction = request.suggestedAmountFraction,
            insulinPresets = if (request.existingEntry != null) journalInsulinPresets else activeJournalPresets,
            foods = if (journalFoodLibraryEnabled) journalFoods else emptyList(),
            foodMacrosEnabled = journalFoodMacrosEnabled,
            doseJournalEntries = scopedJournalEntries,
            doseProfile = JournalDoseProfile(
                enabled = journalEnabled && journalDoseCalculatorEnabled,
                carbRatioGramsPerUnit = predictionCarbRatioGramsPerUnit,
                insulinSensitivityMgDlPerUnit = predictionInsulinSensitivityMgDlPerUnit,
                foodMacrosEnabled = journalFoodMacrosEnabled,
                targetHighMgDl = if (tk.glucodata.ui.util.GlucoseFormatter.isMmol(unit)) {
                    tk.glucodata.ui.util.GlucoseFormatter.mmolToMg(targetHigh)
                } else {
                    targetHigh
                }
            ),
            initialType = request.type,
            existingEntry = request.existingEntry,
            onDismiss = { journalEditorRequest = null },
            onSave = { input ->
                viewModel.saveJournalEntry(input)
                lastJournalType = input.type
                journalEditorRequest = null
                clearJournalAction()
            },
            onSaveEntries = { inputs ->
                inputs.forEach(viewModel::saveJournalEntry)
                inputs.firstOrNull()?.let { lastJournalType = it.type }
                journalEditorRequest = null
                clearJournalAction()
            },
            onSaveFood = viewModel::saveJournalFood,
            onDelete = { entryId ->
                viewModel.deleteJournalEntry(entryId)
                journalEditorRequest = null
                clearJournalAction()
            },
            sensorSerialProvider = { sensorName.ifBlank { null } }
        )
    }

    // Snackbar state for undo actions
    val snackbarHostState = remember { androidx.compose.material3.SnackbarHostState() }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top),
        snackbarHost = { androidx.compose.material3.SnackbarHost(snackbarHostState) }
    ) { padding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            val latestPoint = remember(glucoseHistory) {
                val tail = glucoseHistory.lastOrNull()
                if (tail == null || glucoseHistory.size < 2) {
                    tail
                } else {
                    val previous = glucoseHistory[glucoseHistory.lastIndex - 1]
                    if (tail.timestamp >= previous.timestamp) tail else glucoseHistory.maxByOrNull { it.timestamp }
                }
            }
            val refreshRevision by UiRefreshBus.revision.collectAsState(initial = 0L)
            val hasSensorContext = sensorName.isNotBlank() || activeSensorList.isNotEmpty() || sensorStatus.isNotBlank()
            val dashboardCurrentSnapshot = remember(
                refreshRevision,
                sensorName,
                activeSensorList,
                latestPoint?.timestamp,
                viewMode
            ) {
                CurrentDisplaySource.resolveCurrent(
                    maxAgeMillis = Notify.glucosetimeout,
                    preferredSensorId = sensorName.ifBlank { activeSensorList.firstOrNull() }
                )
            }
            val freshnessTick by produceState(
                initialValue = System.currentTimeMillis(),
                key1 = hasSensorContext,
                key2 = dashboardCurrentSnapshot?.timeMillis,
                key3 = latestPoint?.timestamp
            ) {
                if (!hasSensorContext) return@produceState
                while (true) {
                    delay(15_000L)
                    value = System.currentTimeMillis()
                }
            }
            val dashboardDataState = remember(
                hasSensorContext,
                dashboardCurrentSnapshot?.timeMillis,
                latestPoint?.timestamp,
                freshnessTick
            ) {
                DisplayDataState.resolve(
                    sensorPresent = hasSensorContext,
                    currentTimestampMillis = dashboardCurrentSnapshot?.timeMillis ?: 0L,
                    latestHistoryTimestampMillis = latestPoint?.timestamp ?: 0L,
                    nowMillis = freshnessTick
                )
            }
            val adaptiveMetrics = rememberAdaptiveWindowMetrics()
            val isLandscape = adaptiveMetrics.isLandscape
            val topContentInset = padding.calculateTopPadding()
            val bottomContentInset = padding.calculateBottomPadding()
            val viewportHeight = remember(maxHeight, topContentInset, bottomContentInset) {
                (maxHeight - topContentInset - bottomContentInset).coerceAtLeast(0.dp)
            }
            val listState = rememberLazyListState()
            val collapseDistancePx = with(LocalDensity.current) { 220.dp.toPx() }
            val collapseFraction by remember(listState, collapseDistancePx, isLandscape) {
                derivedStateOf {
                    if (isLandscape) {
                        1f
                    } else if (listState.firstVisibleItemIndex > 0) {
                        1f
                    } else {
                        (listState.firstVisibleItemScrollOffset / collapseDistancePx).coerceIn(0f, 1f)
                    }
                }
            }
            var isChartExpanded by rememberSaveable(isLandscape) { mutableStateOf(!isLandscape) }
            LaunchedEffect(collapseFraction, isLandscape) {
                if (isLandscape) {
                    isChartExpanded = false
                } else {
                    isChartExpanded = if (isChartExpanded) {
                        collapseFraction < 0.72f
                    } else {
                        collapseFraction < 0.48f
                    }
                }
            }
            val expandedProgressTarget = if (isLandscape) 0f else (1f - collapseFraction).coerceIn(0f, 1f)
            val expandedProgress by animateFloatAsState(
                        targetValue = expandedProgressTarget,
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioNoBouncy,
                            stiffness = Spring.StiffnessLow
                ),
                label = "DashboardExpandedProgress"
                        )

            val contentHorizontalPadding = when (adaptiveMetrics.layoutDensity) {
                AdaptiveLayoutDensity.Compact -> 12.dp
                AdaptiveLayoutDensity.Regular -> 14.dp
                AdaptiveLayoutDensity.Comfortable -> 16.dp
            }
            val contentGap = contentHorizontalPadding
            val heroFallbackHeight = when (adaptiveMetrics.layoutDensity) {
                AdaptiveLayoutDensity.Compact -> 84.dp
                AdaptiveLayoutDensity.Regular -> 92.dp
                AdaptiveLayoutDensity.Comfortable -> 100.dp
            }
            val dashboardListTopPadding = 16.dp
            val dashboardItemSpacing = 12.dp
            val readingsTopSpacing = 0.dp
            val collapsedChartHorizontalPadding = 16.dp
            val defaultVisibleReadingRows = when (adaptiveMetrics.layoutDensity) {
                AdaptiveLayoutDensity.Compact -> 3.0f
                AdaptiveLayoutDensity.Regular -> 3.5f
                AdaptiveLayoutDensity.Comfortable -> 4.0f
            }
            val middleVisibleReadingRows = when (adaptiveMetrics.layoutDensity) {
                AdaptiveLayoutDensity.Compact -> 1.0f
                AdaptiveLayoutDensity.Regular -> 1.5f
                AdaptiveLayoutDensity.Comfortable -> 2.0f
            }
            val fallbackReadingRowHeight = when (adaptiveMetrics.layoutDensity) {
                AdaptiveLayoutDensity.Compact -> 56.dp
                AdaptiveLayoutDensity.Regular -> 58.dp
                AdaptiveLayoutDensity.Comfortable -> 60.dp
            }
            val density = LocalDensity.current
            var measuredHeaderHeightPx by rememberSaveable { mutableIntStateOf(0) }
            var measuredReadingRowHeightPx by rememberSaveable { mutableIntStateOf(0) }
            val measuredHeaderHeight = with(density) {
                if (measuredHeaderHeightPx > 0) measuredHeaderHeightPx.toDp() else heroFallbackHeight
            }
            val measuredReadingRowHeight = with(density) {
                if (measuredReadingRowHeightPx > 0) measuredReadingRowHeightPx.toDp() else fallbackReadingRowHeight
            }

            // --- GESTURE-CONTROLLED CHART EXPANSION (Nested Scroll) ---

            val chartViewportReserve = remember(
                viewportHeight,
                measuredHeaderHeight,
                dashboardListTopPadding,
                dashboardItemSpacing,
                readingsTopSpacing
            ) {
                dashboardListTopPadding +
                    measuredHeaderHeight +
                    dashboardItemSpacing +
                    dashboardItemSpacing +
                    readingsTopSpacing
            }
            val fullscreenChartItemHeight = remember(viewportHeight, chartViewportReserve) {
                (viewportHeight - chartViewportReserve).coerceAtLeast(0.dp)
            }
            val boundedFullscreenChartItemHeight = fullscreenChartItemHeight.coerceAtLeast(0.dp)
            val middleChartItemHeight = remember(
                boundedFullscreenChartItemHeight,
                measuredReadingRowHeight,
                middleVisibleReadingRows
            ) {
                (boundedFullscreenChartItemHeight - (measuredReadingRowHeight * middleVisibleReadingRows))
                    .coerceIn(0.dp, boundedFullscreenChartItemHeight)
            }
            val boundedMiddleChartItemHeight = middleChartItemHeight
                .coerceAtLeast(0.dp)
                .coerceAtMost(boundedFullscreenChartItemHeight)
            val collapsedChartItemHeight = remember(
                boundedFullscreenChartItemHeight,
                boundedMiddleChartItemHeight,
                measuredReadingRowHeight,
                defaultVisibleReadingRows
            ) {
                (boundedFullscreenChartItemHeight - (measuredReadingRowHeight * defaultVisibleReadingRows))
                    .coerceIn(0.dp, boundedMiddleChartItemHeight)
            }
            val boundedCollapsedChartItemHeight = collapsedChartItemHeight
                .coerceAtLeast(0.dp)
                .coerceAtMost(boundedFullscreenChartItemHeight)
            val middleChartBoostDp = (boundedMiddleChartItemHeight - boundedCollapsedChartItemHeight).coerceAtLeast(0.dp)
            val maxChartBoostDp = (boundedFullscreenChartItemHeight - boundedCollapsedChartItemHeight).coerceAtLeast(0.dp)
            val middleChartBoostPx = with(density) { middleChartBoostDp.toPx() }
            val maxChartBoostPx = with(density) { maxChartBoostDp.toPx() }

            var chartBoostProgress by rememberSaveable { mutableFloatStateOf(0f) }

            val scope = rememberCoroutineScope()

            val nestedScrollConnection = remember(maxChartBoostPx, middleChartBoostPx, listState) {
                object : NestedScrollConnection {
                    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                        // Dragging UP (scroll delta < 0): Shrink chart first.
                        // 100% absorption means chart shrinks EXACTLY as finger moves, keeping top fixed.
                        // Once boost hits 0, remainders pass to the list for uninterrupted scrolling.
                        val currentBoostPx = chartBoostProgress * maxChartBoostPx
                        if (available.y < 0 && currentBoostPx > 0f && maxChartBoostPx > 0f) {
                            val newBoost = (currentBoostPx + available.y).coerceAtLeast(0f)
                            val consumed = newBoost - currentBoostPx
                            chartBoostProgress = (newBoost / maxChartBoostPx).coerceIn(0f, 1f)
                            return Offset(0f, consumed)
                        }
                        return Offset.Zero
                    }

                    override fun onPostScroll(
                        consumed: Offset,
                        available: Offset,
                        source: NestedScrollSource
                    ): Offset {
                        // Dragging DOWN at top of list: Grow chart 1:1 with finger.
                        // Removing artificial damping so it feels completely free, not restrictive or jiggly.
                        val currentBoostPx = chartBoostProgress * maxChartBoostPx
                        if (
                            available.y > 0 &&
                            maxChartBoostPx > 0f &&
                            listState.firstVisibleItemIndex == 0 &&
                            listState.firstVisibleItemScrollOffset == 0
                        ) {
                            val newBoost = (currentBoostPx + available.y).coerceAtMost(maxChartBoostPx)
                            val consumedY = newBoost - currentBoostPx
                            chartBoostProgress = (newBoost / maxChartBoostPx).coerceIn(0f, 1f)
                            return Offset(0f, consumedY)
                        }
                        return Offset.Zero
                    }

                    override suspend fun onPreFling(available: androidx.compose.ui.unit.Velocity): androidx.compose.ui.unit.Velocity {
                        val currentBoostPx = chartBoostProgress * maxChartBoostPx
                        if (currentBoostPx > 0f && maxChartBoostPx > 0f) {
                            val middleAnchor = middleChartBoostPx.coerceIn(0f, maxChartBoostPx)

                            val target = when {
                                // FAST EXIT: extremely strong upward fling → collapse to 0
                                available.y < -3000f -> 0f
                                // GRADUAL EXIT: moderate upward fling → step DOWN one state
                                available.y < -400f -> {
                                    if (currentBoostPx > middleAnchor + 10f) middleAnchor
                                    else 0f
                                }
                                // FAST EXPAND: extremely strong downward fling → jump full expand
                                available.y > 3000f -> maxChartBoostPx
                                // GRADUAL EXPAND: moderate downward fling → step UP one state
                                available.y > 400f -> {
                                    if (currentBoostPx < middleAnchor - 10f) middleAnchor
                                    else maxChartBoostPx
                                }
                                // NO FLING (slow release): position-based zones
                                else -> when {
                                    currentBoostPx <= middleAnchor * 0.5f -> 0f
                                    currentBoostPx < ((middleAnchor + maxChartBoostPx) * 0.5f) -> middleAnchor
                                    else -> maxChartBoostPx
                                }
                            }

                            // Already maxed and flinging down → let list handle overscroll glow
                            if (currentBoostPx >= maxChartBoostPx - 1f && available.y > 0) {
                                return androidx.compose.ui.unit.Velocity.Zero
                            }

                            val isCollapsing = target < currentBoostPx
                            scope.launch {
                                androidx.compose.animation.core.animate(
                                    initialValue = currentBoostPx,
                                    targetValue = target,
                                    animationSpec = androidx.compose.animation.core.spring(
                                        dampingRatio = 0.85f,
                                        stiffness = if (isCollapsing) androidx.compose.animation.core.Spring.StiffnessMedium
                                                    else androidx.compose.animation.core.Spring.StiffnessMediumLow
                                    )
                                ) { value, _ ->
                                    chartBoostProgress = (value / maxChartBoostPx).coerceIn(0f, 1f)
                                }
                            }

                            return if (target == 0f && isCollapsing) androidx.compose.ui.unit.Velocity.Zero else available
                        }
                        return androidx.compose.ui.unit.Velocity.Zero
                    }
                }
            }

            LaunchedEffect(maxChartBoostPx) {
                if (maxChartBoostPx <= 0f) {
                    chartBoostProgress = 0f
                } else {
                    chartBoostProgress = chartBoostProgress.coerceIn(0f, 1f)
                }
            }

            LaunchedEffect(
                listState.isScrollInProgress,
                listState.firstVisibleItemIndex,
                listState.firstVisibleItemScrollOffset,
                maxChartBoostPx,
                middleChartBoostPx,
                chartBoostProgress
            ) {
                if (maxChartBoostPx <= 0f || listState.isScrollInProgress) return@LaunchedEffect

                val currentBoostPx = chartBoostProgress * maxChartBoostPx
                if (currentBoostPx <= 1f) {
                    if (chartBoostProgress != 0f) chartBoostProgress = 0f
                    return@LaunchedEffect
                }

                val middleAnchorPx = middleChartBoostPx.coerceIn(0f, maxChartBoostPx)
                val shouldCollapseToDefault =
                    listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0
                val targetAnchorPx = if (shouldCollapseToDefault) {
                    0f
                } else {
                    when {
                        currentBoostPx <= middleAnchorPx * 0.5f -> 0f
                        currentBoostPx < ((middleAnchorPx + maxChartBoostPx) * 0.5f) -> middleAnchorPx
                        else -> maxChartBoostPx
                    }
                }

                if (abs(targetAnchorPx - currentBoostPx) > 1f) {
                    chartBoostProgress = (targetAnchorPx / maxChartBoostPx).coerceIn(0f, 1f)
                }
            }

            val chartHeightBoostPx = chartBoostProgress * maxChartBoostPx
            val chartHeightBoostDp = with(density) { chartHeightBoostPx.toDp() }


            // --- REUSABLE UI SECTIONS ---


            val recentReadings = remember(consumerHistory) {
                buildDisplayReadings(consumerHistory, limit = 10)
            }
            val recentReadingPeers = remember(recentReadings, multiSensorDisplay) {
                recentReadings.map { reading -> multiSensorDisplay.peersAt(reading.timestamp) }
            }
            val peerSeriesById = remember(multiSensorDisplay) {
                multiSensorDisplay.series.associateBy { it.sensorId }
            }
            val recentReadingJournalEntries = remember(recentReadings, scopedJournalEntries) {
                groupJournalEntriesByReading(recentReadings, scopedJournalEntries)
            }
            val hasVisibleReadingContent = dashboardDataState.isFresh || recentReadings.isNotEmpty()

            val triggerCalibrationIfEnabled: (CalibrationSheetState) -> Unit = { state ->
                val targetSensorId = when (state) {
                    is CalibrationSheetState.New -> state.sensorId
                    is CalibrationSheetState.Edit -> state.entity.sensorId
                    CalibrationSheetState.Hidden -> ""
                }
                val targetRawMode = when (state) {
                    is CalibrationSheetState.New -> {
                        val targetMode = state.viewModeOverride ?: viewMode
                        targetMode == 1 || targetMode == 3
                    }
                    is CalibrationSheetState.Edit -> state.entity.isRawMode
                    CalibrationSheetState.Hidden -> false
                }
                if (tk.glucodata.data.calibration.CalibrationManager.isEnabledForMode(targetRawMode, targetSensorId)) {
                    onTriggerCalibration(state)
                }
            }

        // Compute calibrated value for current reading (respects viewMode)
            val isRawModeHero = viewMode == 1 || viewMode == 3
            val calibrationSensorId = sensorName.ifBlank { null }
            val calibratedValue = remember(
                latestPoint,
                viewMode,
                calibrationSensorId,
                predictionCalibrationRefresh,
                calibrationRevision
            ) {
                if (latestPoint != null &&
                    !tk.glucodata.data.calibration.CalibrationManager.shouldOverwriteSensorValues() &&
                    tk.glucodata.data.calibration.CalibrationManager.hasActiveCalibration(isRawModeHero, calibrationSensorId)
                ) {
                    val baseValue = if (isRawModeHero) latestPoint.rawValue else latestPoint.value
                    if (baseValue.isFinite() && baseValue > 0.1f) {
                        tk.glucodata.data.calibration.CalibrationManager.getCalibratedValue(
                            baseValue,
                            latestPoint.timestamp,
                            isRawModeHero,
                            sensorIdOverride = calibrationSensorId
                        )
                    } else {
                        null
                    }
                } else null
            }

        // --- LAYOUT LOGIC ---

        // Empty state check
            if (isLoading) {
            Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
            } else if (glucoseHistory.isEmpty() && !hasSensorContext) {
            tk.glucodata.ui.components.DashboardEmptyState(
                onSensorSelected = { type ->
                    when (type) {
                        tk.glucodata.ui.components.SensorType.SIBIONICS -> showSibionicsWizard = true
                        tk.glucodata.ui.components.SensorType.LIBRE -> showLibreWizard = true
                    tk.glucodata.ui.components.SensorType.DEXCOM -> showDexcomWizard = true
                    tk.glucodata.ui.components.SensorType.ACCUCHEK -> showAccuChekWizard = true
                    tk.glucodata.ui.components.SensorType.CARESENS_AIR -> showCareSensAirWizard = true
                    tk.glucodata.ui.components.SensorType.AIDEX -> showAiDexWizard = true
                    tk.glucodata.ui.components.SensorType.ICANHEALTH -> showICanHealthWizard = true
                    tk.glucodata.ui.components.SensorType.MQ -> showMQWizard = true
                    tk.glucodata.ui.components.SensorType.ANYTIME -> showAnytimeWizard = true
                    tk.glucodata.ui.components.SensorType.OTTAI -> showOttaiWizard = true
                }
            },
                onImportHistory = {
                    importLauncher.launch(arrayOf("application/json", "text/csv", "text/comma-separated-values", "*/*"))
                },
                modifier = Modifier
                    .padding(padding),
                readinessContent = {
                    DashboardCgmReadinessBanner(onOpenReadiness = onNavigateToReadiness)
                }
            )
            } else if (isLandscape) {
            // LANDSCAPE: SPLIT VIEW
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = contentHorizontalPadding),
                horizontalArrangement = Arrangement.spacedBy(contentGap)
            ) {
                // Left Pane: Status + Info + History (Scrollable)
                LazyColumn(
                    modifier = Modifier.weight(0.25f).fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(16.dp), // Gap between Header and History?
                    contentPadding = PaddingValues(top = 16.dp, bottom = 16.dp)
                ) {
                     item {
                        DashboardCombinedHeader(
                            currentGlucose = currentGlucose,
                            currentRate = currentRate,
                            viewMode = viewMode,
                            latestPoint = latestPoint,
                            sensorName = sensorName,
                            daysRemaining = daysRemaining,
                            activeSensors = activeSensorList,
                            sensorStatus = sensorStatus,
                            sensorProgress = sensorProgress,
                            sensorHoursRemaining = sensorHoursRemaining,
                            currentDay = currentDay,
                            history = consumerHistory, // Advanced Trend (smoothed when active)
                            calibratedValue = calibratedValue,
                            currentSnapshot = dashboardCurrentSnapshot,
                            dataState = dashboardDataState,
                            peerReadings = peerCurrentReadings,
                            onPeerReadingClick = { viewModel.promoteSensorToPrimary(it) },
                            isMmol = tk.glucodata.ui.util.GlucoseFormatter.isMmol(unit),
                            targetLow = targetLow,
                            targetHigh = targetHigh,
                            veryLowThreshold = veryLowThreshold,
                            veryHighThreshold = veryHighThreshold,
                            valueRangeColorsEnabled = glucoseRangeColorsDisplayEnabled,
                            arrowForecastColorsEnabled = glucoseArrowForecastEnabled,
                            onHeroClick = {
                                val autoVal = latestPoint?.value ?: tk.glucodata.GlucoseValueParser.parseFirstOrZero(currentGlucose)
                                val rawVal = latestPoint?.rawValue ?: autoVal
                                triggerCalibrationIfEnabled(
                                    CalibrationSheetState.New(
                                        autoVal,
                                        rawVal,
                                        latestPoint?.timestamp ?: System.currentTimeMillis(),
                                        latestPoint?.sensorSerial?.takeIf { it.isNotBlank() } ?: sensorName
                                    )
                                )
                            }
                        )
                    }

                    item {
                        DashboardCgmReadinessBanner(onOpenReadiness = onNavigateToReadiness)
                    }

                    item {
                        RecentReadingsCard(
                            recentReadings = recentReadings,
                            unit = unit,
                            viewMode = viewMode,
                            footerLabel = stringResource(R.string.historyname),
                            onViewHistory = onNavigateToHistory
                        ) { index, item ->
                            ReadingRow(
                                point = item,
                                unit = unit,
                                viewMode = viewMode,
                                index = index,
                                totalCount = recentReadings.size,
                                history = recentReadings,
                                peerReadings = recentReadingPeers.getOrNull(index).orEmpty(),
                                peerSeries = peerSeriesById,
                                multiSensorActive = multiSensorActive,
                                sensorId = sensorName,
                                calibrations = calibrations,
                                journalEntries = recentReadingJournalEntries[item.timestamp].orEmpty(),
                                journalPresetsById = journalPresetsById,
                                journalChipExpanded = false,
                                onJournalEntryClick = { entry ->
                                    lastJournalType = entry.type
                                    clearJournalAction()
                                    journalEditorRequest = JournalEditorRequest(entry.type, entry.timestamp, entry)
                                },
                                showLeadingAction = journalEnabled,
                                leadingActionEmphasis = if (index == 0) 1f else 0.38f,
                                onLeadingActionClick = if (journalEnabled) {
                                    {
                                        clearJournalAction()
                                        val rowGlucoseMgDl = if (tk.glucodata.ui.util.GlucoseFormatter.isMmol(unit)) {
                                            tk.glucodata.ui.util.GlucoseFormatter.mmolToMg(item.value)
                                        } else {
                                            item.value
                                        }
                                        journalEditorRequest = JournalEditorRequest(
                                            type = lastJournalType,
                                            timestamp = item.timestamp,
                                            suggestedGlucoseMgDl = rowGlucoseMgDl,
                                            suggestedChartAnchorGlucoseMgDl = rowGlucoseMgDl
                                                .takeIf { lastJournalType == JournalEntryType.FINGERSTICK }
                                        )
                                    }
                                } else {
                                    null
                                },
                                onValueClick = {
                                    clearJournalAction()
                                    triggerCalibrationIfEnabled(
                                        CalibrationSheetState.New(
                                            item.value,
                                            item.rawValue,
                                            item.timestamp,
                                            item.sensorSerial?.takeIf { it.isNotBlank() } ?: sensorName
                                        )
                                    )
                                },
                                onSensorValueClick = { reading ->
                                    clearJournalAction()
                                    val readingSensorId = reading.sensorSerial?.takeIf { it.isNotBlank() } ?: sensorName
                                    val readingViewMode = sensorViewModes.entries.firstOrNull { (sensorId, _) ->
                                        SensorIdentity.matches(sensorId, readingSensorId)
                                    }?.value ?: viewMode
                                    triggerCalibrationIfEnabled(
                                        CalibrationSheetState.New(
                                            auto = reading.value,
                                            raw = reading.rawValue,
                                            timestamp = reading.timestamp,
                                            sensorId = readingSensorId,
                                            viewModeOverride = readingViewMode
                                        )
                                    )
                                },
                                onDeleteReading = { point ->
                                    viewModel.deleteHistoryReading(point, sensorName)
                                },
                                modifier = Modifier.animateItem()
                            )
                        }
                    }
                }

                // Right Pane: Big Chart (Full Height)
                Column(
                    modifier = Modifier
                        .weight(0.75f)
                        .fillMaxHeight()
                        .padding(vertical = 16.dp)
                ) {
                        Box(modifier = Modifier.weight(1f).fillMaxSize()) {
                            key(sensorName) {
                                DashboardChartSection(
                                    modifier = Modifier.fillMaxSize(),
                                    glucoseHistory = glucoseHistory,
                                    multiSensorDisplay = multiSensorDisplay,
                                    peerPredictionSeries = peerPredictionSeries,
                                    journalMarkers = journalChartMarkers,
                                    activeInsulinSummary = dashboardInsulinSummary,
                                    journalPeriodSummary = journalTodaySummary,
                                    showEiob = journalEiobDisplayEnabled,
                                    appChartRangeColors = appChartRangeColorsEnabled,
                                    predictionSeries = predictionSeries,
                                    graphSmoothingLevel = graphSmoothingLevel,
                                    previewWindowMode = previewWindowMode,
                                    graphLow = graphLow,
                                    graphHigh = graphHigh,
                                    targetLow = targetLow,
                                    targetHigh = targetHigh,
                                    unit = unit,
                                    veryLowThreshold = veryLowThreshold,
                                    veryHighThreshold = veryHighThreshold,
                                    calibrations = calibrations,
                                    viewMode = viewMode,
                                    onTimeRangeSelected = { timeRange = it },
                                    selectedTimeRange = timeRange,
                                    isExpanded = false,
                                    expandedProgress = 0f,
                                    expandedUnderlayBottom = 0.dp,
                                    onToggleExpanded = null,
                                    onPointClick = { point ->
                                        clearJournalAction()
                                        triggerCalibrationIfEnabled(
                                            CalibrationSheetState.New(
                                                point.value,
                                                point.rawValue,
                                                point.timestamp,
                                                point.sensorSerial?.takeIf { it.isNotBlank() } ?: sensorName
                                            )
                                        )
                                    },
                                    onCalibrationClick = { cal ->
                                        clearJournalAction()
                                        triggerCalibrationIfEnabled(CalibrationSheetState.Edit(cal))
                                    },
                                    onTimelineTap = { suggestion ->
                                        if (journalEnabled) {
                                            showJournalAction(suggestion)
                                        }
                                    },
                                    journalActionTimestamp = if (journalEnabled) journalActionTimestamp else null,
                                    journalActionDisplayValue = if (journalEnabled) journalActionSuggestedDisplayValue else null,
                                    onDismissJournalAction = { clearJournalAction() },
                                    onJournalMarkerClick = { entryId ->
                                        journalEntriesById[entryId]?.let { entry ->
                                            clearJournalAction()
                                            lastJournalType = entry.type
                                            journalEditorRequest = JournalEditorRequest(entry.type, entry.timestamp, entry)
                                        }
                                    },
                                    onViewportSnapshotChanged = { dashboardChartViewport = it }
                                )
                            }
                            journalActionTimestamp?.let { actionTimestamp ->
                                JournalFloatingActionMenu(
                                    visible = journalEnabled,
                                    selectedTimestamp = actionTimestamp,
                                    viewportSnapshot = dashboardChartViewport,
                                    onTypeSelected = {
                                        lastJournalType = it
                                        val suggestedGlucoseMgDl = journalActionSuggestedGlucoseMgDl
                                        val suggestedAmountFraction = journalActionSuggestedAmountFraction
                                        clearJournalAction()
                                        journalEditorRequest = JournalEditorRequest(
                                            type = it,
                                            timestamp = actionTimestamp,
                                            suggestedGlucoseMgDl = suggestedGlucoseMgDl,
                                            suggestedChartAnchorGlucoseMgDl = suggestedGlucoseMgDl,
                                            suggestedAmountFraction = suggestedAmountFraction
                                        )
                                    }
                                )
                            }
                        }
                }
            }
            } else {
            // PORTRAIT: UNIFIED VERTICAL SCROLL
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .nestedScroll(nestedScrollConnection)
                    .padding(padding),
                state = listState,
                verticalArrangement = Arrangement.spacedBy(dashboardItemSpacing),
                contentPadding = PaddingValues(top = dashboardListTopPadding, bottom = 12.dp)
            ) {
                item {
                    Box(
                        modifier = Modifier
                            .padding(horizontal = contentHorizontalPadding)
                            .onSizeChanged { measuredHeaderHeightPx = it.height }
                    ) {
                        DashboardCombinedHeader(
                            currentGlucose = currentGlucose,
                            currentRate = currentRate,
                            viewMode = viewMode,
                            latestPoint = latestPoint,
                            sensorName = sensorName,
                            daysRemaining = daysRemaining,
                            activeSensors = activeSensorList,
                            sensorStatus = sensorStatus,
                            sensorProgress = sensorProgress,
                            sensorHoursRemaining = sensorHoursRemaining,
                            currentDay = currentDay,
                            history = consumerHistory, // Advanced Trend (smoothed when active)
                            calibratedValue = calibratedValue,
                            currentSnapshot = dashboardCurrentSnapshot,
                            dataState = dashboardDataState,
                            peerReadings = peerCurrentReadings,
                            onPeerReadingClick = { viewModel.promoteSensorToPrimary(it) },
                            isMmol = tk.glucodata.ui.util.GlucoseFormatter.isMmol(unit),
                            targetLow = targetLow,
                            targetHigh = targetHigh,
                            veryLowThreshold = veryLowThreshold,
                            veryHighThreshold = veryHighThreshold,
                            valueRangeColorsEnabled = glucoseRangeColorsDisplayEnabled,
                            arrowForecastColorsEnabled = glucoseArrowForecastEnabled,
                            onHeroClick = {
                                val autoVal = latestPoint?.value ?: tk.glucodata.GlucoseValueParser.parseFirstOrZero(currentGlucose)
                                val rawVal = latestPoint?.rawValue ?: autoVal
                                triggerCalibrationIfEnabled(
                                    CalibrationSheetState.New(
                                        autoVal,
                                        rawVal,
                                        latestPoint?.timestamp ?: System.currentTimeMillis(),
                                        latestPoint?.sensorSerial?.takeIf { it.isNotBlank() } ?: sensorName
                                    )
                                )
                            }
                        )
                    }
                }

                item {
                    DashboardCgmReadinessBanner(
                        modifier = Modifier.padding(horizontal = contentHorizontalPadding),
                        onOpenReadiness = onNavigateToReadiness
                    )
                }

                item {
                    // Portrait chart sizing is anchored to explicit visible-row budgets:
                    // top state shows ~3-4 rows, middle shows ~1-2, fullscreen hides the list.
                    val chartItemHeightTarget = (boundedCollapsedChartItemHeight + chartHeightBoostDp)
                        .coerceIn(boundedCollapsedChartItemHeight, boundedFullscreenChartItemHeight)
                    val chartHorizontalPaddingTarget = (collapsedChartHorizontalPadding.value * collapseFraction).dp
                    val animatedChartItemHeight by animateDpAsState(
                                targetValue = chartItemHeightTarget,
                                animationSpec = spring(
                                    dampingRatio = Spring.DampingRatioNoBouncy,
                                    stiffness = Spring.StiffnessLow
                        ),
                        label = "DashboardChartItemHeight"
                                )
                    val animatedChartHorizontalPadding by animateDpAsState(
                                targetValue = chartHorizontalPaddingTarget,
                                animationSpec = spring(
                                    dampingRatio = Spring.DampingRatioNoBouncy,
                                    stiffness = Spring.StiffnessMedium
                        ),
                        label = "DashboardChartHorizontalPadding"
                                )
                    Box(
                        modifier = Modifier
                            .padding(horizontal = animatedChartHorizontalPadding.coerceAtLeast(0.dp))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(animatedChartItemHeight)
                        ) {
                            key(sensorName) {
                                DashboardChartSection(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(bottom = 0.dp),
                                    glucoseHistory = glucoseHistory,
                                    multiSensorDisplay = multiSensorDisplay,
                                    peerPredictionSeries = peerPredictionSeries,
                                    journalMarkers = journalChartMarkers,
                                    activeInsulinSummary = dashboardInsulinSummary,
                                    journalPeriodSummary = journalTodaySummary,
                                    showEiob = journalEiobDisplayEnabled,
                                    appChartRangeColors = appChartRangeColorsEnabled,
                                    predictionSeries = predictionSeries,
                                    graphSmoothingLevel = graphSmoothingLevel,
                                    previewWindowMode = previewWindowMode,
                                    graphLow = graphLow,
                                    graphHigh = graphHigh,
                                    targetLow = targetLow,
                                    targetHigh = targetHigh,
                                    unit = unit,
                                    veryLowThreshold = veryLowThreshold,
                                    veryHighThreshold = veryHighThreshold,
                                    calibrations = calibrations,
                                    viewMode = viewMode,
                                    onTimeRangeSelected = { timeRange = it },
                                    selectedTimeRange = timeRange,
                                    isExpanded = isChartExpanded,
                                    expandedProgress = expandedProgress,
                                    expandedUnderlayBottom = 0.dp,
                                    onToggleExpanded = null,
                                    chartBoostProgress = chartBoostProgress,
                                    onPointClick = { point ->
                                        clearJournalAction()
                                        triggerCalibrationIfEnabled(
                                            CalibrationSheetState.New(
                                                point.value,
                                                point.rawValue,
                                                point.timestamp,
                                                point.sensorSerial?.takeIf { it.isNotBlank() } ?: sensorName
                                            )
                                        )
                                    },
                                    onCalibrationClick = { cal ->
                                        clearJournalAction()
                                        triggerCalibrationIfEnabled(CalibrationSheetState.Edit(cal))
                                    },
                                    onTimelineTap = { suggestion ->
                                        if (journalEnabled) {
                                            showJournalAction(suggestion)
                                        }
                                    },
                                    journalActionTimestamp = if (journalEnabled) journalActionTimestamp else null,
                                    journalActionDisplayValue = if (journalEnabled) journalActionSuggestedDisplayValue else null,
                                    onDismissJournalAction = { clearJournalAction() },
                                    onJournalMarkerClick = { entryId ->
                                        journalEntriesById[entryId]?.let { entry ->
                                            clearJournalAction()
                                            lastJournalType = entry.type
                                            journalEditorRequest = JournalEditorRequest(entry.type, entry.timestamp, entry)
                                        }
                                    },
                                    onViewportSnapshotChanged = { dashboardChartViewport = it }
                                )
                            }
                            journalActionTimestamp?.let { actionTimestamp ->
                                JournalFloatingActionMenu(
                                    visible = journalEnabled,
                                    selectedTimestamp = actionTimestamp,
                                    viewportSnapshot = dashboardChartViewport,
                                    onTypeSelected = {
                                        lastJournalType = it
                                        val suggestedGlucoseMgDl = journalActionSuggestedGlucoseMgDl
                                        val suggestedAmountFraction = journalActionSuggestedAmountFraction
                                        clearJournalAction()
                                        journalEditorRequest = JournalEditorRequest(
                                            type = it,
                                            timestamp = actionTimestamp,
                                            suggestedGlucoseMgDl = suggestedGlucoseMgDl,
                                            suggestedChartAnchorGlucoseMgDl = suggestedGlucoseMgDl,
                                            suggestedAmountFraction = suggestedAmountFraction
                                        )
                                    }
                                )
                            }
                        }
                    }
                }


                item {
                    Box(
                        modifier = Modifier
                            .padding(start = 16.dp, top = readingsTopSpacing, end = 16.dp)
                    ) {
                        RecentReadingsCard(
                            recentReadings = recentReadings,
                            unit = unit,
                            viewMode = viewMode,
                            footerLabel = stringResource(R.string.historyname),
                            onViewHistory = onNavigateToHistory
                        ) { index, item ->
                            ReadingRow(
                                point = item,
                                unit = unit,
                                viewMode = viewMode,
                                index = index,
                                totalCount = recentReadings.size,
                                history = recentReadings,
                                peerReadings = recentReadingPeers.getOrNull(index).orEmpty(),
                                peerSeries = peerSeriesById,
                                multiSensorActive = multiSensorActive,
                                sensorId = sensorName,
                                calibrations = calibrations,
                                journalEntries = recentReadingJournalEntries[item.timestamp].orEmpty(),
                                journalPresetsById = journalPresetsById,
                                journalChipExpanded = false,
                                onJournalEntryClick = { entry ->
                                    lastJournalType = entry.type
                                    clearJournalAction()
                                    journalEditorRequest = JournalEditorRequest(entry.type, entry.timestamp, entry)
                                },
                                showLeadingAction = journalEnabled,
                                leadingActionEmphasis = if (index == 0) 1f else 0.38f,
                                onLeadingActionClick = if (journalEnabled) {
                                    {
                                        clearJournalAction()
                                        val rowGlucoseMgDl = if (tk.glucodata.ui.util.GlucoseFormatter.isMmol(unit)) {
                                            tk.glucodata.ui.util.GlucoseFormatter.mmolToMg(item.value)
                                        } else {
                                            item.value
                                        }
                                        journalEditorRequest = JournalEditorRequest(
                                            type = lastJournalType,
                                            timestamp = item.timestamp,
                                            suggestedGlucoseMgDl = rowGlucoseMgDl,
                                            suggestedChartAnchorGlucoseMgDl = rowGlucoseMgDl
                                                .takeIf { lastJournalType == JournalEntryType.FINGERSTICK }
                                        )
                                    }
                                } else {
                                    null
                                },
                                onValueClick = {
                                    clearJournalAction()
                                    triggerCalibrationIfEnabled(
                                        CalibrationSheetState.New(
                                            item.value,
                                            item.rawValue,
                                            item.timestamp,
                                            item.sensorSerial?.takeIf { it.isNotBlank() } ?: sensorName
                                        )
                                    )
                                },
                                onSensorValueClick = { reading ->
                                    clearJournalAction()
                                    val readingSensorId = reading.sensorSerial?.takeIf { it.isNotBlank() } ?: sensorName
                                    val readingViewMode = sensorViewModes.entries.firstOrNull { (sensorId, _) ->
                                        SensorIdentity.matches(sensorId, readingSensorId)
                                    }?.value ?: viewMode
                                    triggerCalibrationIfEnabled(
                                        CalibrationSheetState.New(
                                            auto = reading.value,
                                            raw = reading.rawValue,
                                            timestamp = reading.timestamp,
                                            sensorId = readingSensorId,
                                            viewModeOverride = readingViewMode
                                        )
                                    )
                                },
                                onDeleteReading = { point ->
                                    viewModel.deleteHistoryReading(point, sensorName)
                                },
                                modifier = Modifier
                                    .then(
                                        if (index == 0) {
                                            Modifier.onSizeChanged { measuredReadingRowHeightPx = it.height }
                                        } else {
                                            Modifier
                                        }
                                    )
                                    .animateItem()
                            )
                        }
                    }
                }

                // Calibrations Card
                item {
                    Box(modifier = Modifier.padding(horizontal = 16.dp)) {
                        CalibrationsCard(
                            viewMode = viewMode,
                            isMmol = tk.glucodata.ui.util.GlucoseFormatter.isMmol(unit),
                            sensorId = sensorName,
                            showEmptyAction = hasVisibleReadingContent,
                            onAddCalibration = {
                                val autoVal = latestPoint?.value ?: tk.glucodata.GlucoseValueParser.parseFirstOrZero(currentGlucose)
                                val rawVal = latestPoint?.rawValue ?: autoVal
                                triggerCalibrationIfEnabled(
                                    CalibrationSheetState.New(
                                        autoVal,
                                        rawVal,
                                        latestPoint?.timestamp ?: System.currentTimeMillis(),
                                        latestPoint?.sensorSerial?.takeIf { it.isNotBlank() } ?: sensorName
                                    )
                                )
                            },
                            onEditCalibration = { cal ->
                                triggerCalibrationIfEnabled(CalibrationSheetState.Edit(cal))
                            },
                            onViewHistory = onNavigateToCalibrations,
                            snackbarHostState = snackbarHostState
                        )
                    }
                }
            }
            }

            if (journalEnabled && hasSensorContext) {
                JournalExpandableFab(
                    expanded = dashboardFabExpanded,
                    onExpandedChange = {
                        dashboardFabExpanded = it
                        if (it) clearJournalAction()
                    },
                    onTypeSelected = { type ->
                        val timestamp = System.currentTimeMillis()
                        val latestGlucoseMgDl = latestPoint?.value?.let { value ->
                            if (isMmolUnit) tk.glucodata.ui.util.GlucoseFormatter.mmolToMg(value) else value
                        }
                        lastJournalType = type
                        dashboardFabExpanded = false
                        journalEditorRequest = JournalEditorRequest(
                            type = type,
                            timestamp = timestamp,
                            suggestedGlucoseMgDl = latestGlucoseMgDl,
                            suggestedChartAnchorGlucoseMgDl = latestGlucoseMgDl
                                .takeIf { type == JournalEntryType.FINGERSTICK }
                        )
                    },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(
                            end = 20.dp,
                            bottom = padding.calculateBottomPadding() + 20.dp
                        )
                        .zIndex(4f)
                )
            }
        }
    }

}
