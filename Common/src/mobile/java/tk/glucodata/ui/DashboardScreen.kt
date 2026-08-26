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
import androidx.compose.foundation.layout.windowInsetsPadding
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
import tk.glucodata.data.journal.JournalFood
import tk.glucodata.data.journal.JournalInsulinPreset
import tk.glucodata.data.prediction.calculateForecastDoseRecommendation
import tk.glucodata.data.prediction.ForecastDoseRecommendation
import tk.glucodata.data.prediction.GlucosePredictionSeries
import tk.glucodata.data.prediction.GlucosePredictionSeriesKind
import tk.glucodata.data.prediction.PredictiveSimulationSettings
import tk.glucodata.data.prediction.buildGlucosePrediction
import tk.glucodata.ui.journal.JournalDoseProfile
import tk.glucodata.ui.journal.JournalEntrySheet
import tk.glucodata.ui.journal.JournalExpandableFab
import tk.glucodata.ui.journal.JournalFloatingActionMenu
import tk.glucodata.ui.journal.JournalInlineChip
import tk.glucodata.ui.journal.JournalSettingsScreen
import tk.glucodata.data.journal.JournalIobCalculator
import tk.glucodata.data.journal.JournalActiveInsulinSummary
import tk.glucodata.drivers.nightscout.NightscoutFollowerDeviceStatus
import tk.glucodata.ui.journal.buildJournalChartMarkers
import tk.glucodata.ui.journal.journalQuickAddTimestamp
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

/** Scroll state the chart's snap-to-anchor logic reacts to, as one comparable value. */
private data class SnapInput(
    val scrolling: Boolean,
    val firstIndex: Int,
    val firstOffset: Int,
    val boost: Float
)

enum class TimeRange(val labelResId: Int, val hours: Int, val labelAmount: Int = hours) {
    H1(R.string.dashboard_range_hours, 1),
    H3(R.string.dashboard_range_hours, 3),
    H6(R.string.dashboard_range_hours, 6),
    H12(R.string.dashboard_range_hours, 12),
    H24(R.string.dashboard_range_hours, 24),
    D3(R.string.dashboard_range_days, 72, 3);

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
    onNavigateToAppUpdates: () -> Unit = {},
    onTriggerCalibration: (CalibrationSheetState) -> Unit = {}
) {
    // Read once here: the LazyColumns below use Arrangement.spacedBy, which reserves its gap
    // around an item even when that item renders nothing. An always-emitted banner item would
    // push the whole dashboard down by one gap whenever there is no update to announce.
    val appUpdateBannerVisible = rememberAppUpdateBannerVisible()
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
    // Read outside the lazy list: an item that renders nothing still costs the list's
    // inter-item spacing, so the row has to be omitted rather than emitted empty.
    val showPinnedStats = tk.glucodata.ui.stats.hasPinnedStats()
    // Owned here, not inside the strip: portrait and landscape compose it from two different
    // call sites, so state living in the strip was discarded on every rotation and the window
    // fell back to 24h. This screen stays in composition across the orientation change.
    val pinnedStatsWindow = rememberSaveable {
        mutableStateOf(tk.glucodata.ui.stats.PinnedWindow.TODAY)
    }
    val chartSmoothingMinutes by viewModel.chartSmoothingMinutes.collectAsState()
    val dataSmoothingGraphOnly by viewModel.dataSmoothingGraphOnly.collectAsState()
    val dataSmoothingCollapseChunks by viewModel.dataSmoothingCollapseChunks.collectAsState()
    val dataSmoothingExchangeOnly by viewModel.dataSmoothingExchangeOnly.collectAsState()
    val visualSmoothingMinutes = if (dataSmoothingExchangeOnly) 0 else chartSmoothingMinutes
    val previewWindowMode by viewModel.previewWindowMode.collectAsState()
    val journalEnabled by viewModel.journalEnabled.collectAsState()
    val journalEiobDisplayEnabled by viewModel.journalEiobDisplayEnabled.collectAsState()
    val journalQuickAddAlwaysNow by viewModel.journalQuickAddAlwaysNow.collectAsState()
    val journalDashboardQuickAdd by viewModel.journalDashboardQuickAddButton.collectAsState()
    val glucoseRangeColorsDisplayEnabled by viewModel.glucoseValueRangeColorsEnabled.collectAsState()
    val glucoseArrowForecastEnabled by viewModel.glucoseArrowForecastColorsEnabled.collectAsState()
    val appChartRangeColorsEnabled by viewModel.glucoseAppChartRangeColorsEnabled.collectAsState()
    val dashboardShowDelta by viewModel.dashboardShowDelta.collectAsState()
    val dashboardRowsShowDelta by viewModel.dashboardRowsShowDelta.collectAsState()
    val deltaIntervalMinutes by viewModel.deltaIntervalMinutes.collectAsState()
    val journalDoseCalculatorEnabled by viewModel.journalDoseCalculatorEnabled.collectAsState()
    val journalFoodMacrosEnabled by viewModel.journalFoodMacrosEnabled.collectAsState()
    val journalFoodLibraryEnabled by viewModel.journalFoodLibraryEnabled.collectAsState()
    val predictiveSimulationEnabled by viewModel.predictiveSimulationEnabled.collectAsState()
    val predictionTrendMomentumEnabled by viewModel.predictionTrendMomentumEnabled.collectAsState()
    val predictionCarbRatioGramsPerUnit by viewModel.predictionCarbRatioGramsPerUnit.collectAsState()
    val predictionInsulinSensitivityMgDlPerUnit by viewModel.predictionInsulinSensitivityMgDlPerUnit.collectAsState()
    val predictionModelProfile by viewModel.predictionModelProfile.collectAsState()
    val predictionDoseTargetMgDl by viewModel.predictionDoseTargetMgDl.collectAsState()
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
        // Journal BG entries can arrive while the app is not running — a meter
        // handing over its stored readings, a Nightscout pull — so the derived
        // calibrations are re-paired once here rather than only on a live edit.
        tk.glucodata.data.calibration.JournalCalibrationSync.onAppStart()
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
    var journalNow by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var dashboardChartViewport by remember { mutableStateOf<ChartViewportSnapshot?>(null) }
    var dashboardFabExpanded by rememberSaveable { mutableStateOf(false) }

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
    val localInsulinSummary = remember(journalEnabled, scopedJournalEntries, journalPresetsById, journalNow) {
        if (!journalEnabled || scopedJournalEntries.isEmpty()) {
            null
        } else {
            JournalIobCalculator.buildActiveInsulinSummary(scopedJournalEntries, journalPresetsById, journalNow)
        }
    }
    // A fresh devicestatus published by the followed uploader replaces the
    // locally recomputed IOB/eIOB so both devices show the same numbers; the
    // journalNow ticker re-evaluates the freshness window, so a stale
    // document falls back to the local computation on its own.
    val remoteInsulin = remember(journalEnabled, journalNow) {
        if (journalEnabled) NightscoutFollowerDeviceStatus.fresh(journalNow) else null
    }
    val activeInsulinSummary = remember(localInsulinSummary, remoteInsulin) {
        when {
            remoteInsulin == null -> localInsulinSummary
            localInsulinSummary == null && remoteInsulin.iobUnits < 0.005f -> null
            else -> (localInsulinSummary ?: JournalActiveInsulinSummary(0, 0f, 0, null)).copy(
                iobUnits = remoteInsulin.iobUnits,
                eiobUnits = remoteInsulin.eiobUnits.takeIf { it.isFinite() }
                    ?: localInsulinSummary?.eiobUnits ?: 0f
            )
        }
    }
    val activeInsulinFromRemote = remoteInsulin != null && activeInsulinSummary != null
    val predictionSettings = remember(
        predictiveSimulationEnabled,
        predictionTrendMomentumEnabled,
        predictionCarbRatioGramsPerUnit,
        predictionInsulinSensitivityMgDlPerUnit,
        predictionModelProfile,
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
            foodMacrosEnabled = journalEnabled && journalFoodMacrosEnabled,
            modelProfile = predictionModelProfile
        )
    }
    val consumerHistory = remember(
        glucoseHistory,
        visualSmoothingMinutes,
        dataSmoothingGraphOnly,
        dataSmoothingCollapseChunks
    ) {
        buildSmoothedConsumerHistory(
            points = glucoseHistory,
            smoothingMinutes = visualSmoothingMinutes,
            smoothOnlyGraph = dataSmoothingGraphOnly,
            collapseChunks = dataSmoothingCollapseChunks
        )
    }
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
    val forecastDoseRecommendation: ForecastDoseRecommendation? = remember(
        journalDoseCalculatorEnabled,
        predictionSeries,
        viewMode,
        unit,
        targetLow,
        predictionDoseTargetMgDl,
        predictionSettings,
        journalNow
    ) {
        if (!journalDoseCalculatorEnabled) {
            null
        } else {
            val primarySeries = predictionSeries.lastOrNull {
                it.kind == GlucosePredictionSeriesKind.CALIBRATED
            } ?: predictionSeries.firstOrNull {
                it.kind == if (viewMode == 1 || viewMode == 3) {
                    GlucosePredictionSeriesKind.RAW
                } else {
                    GlucosePredictionSeriesKind.AUTO
                }
            } ?: predictionSeries.firstOrNull()
            primarySeries?.let {
                calculateForecastDoseRecommendation(
                    predictionPoints = it.points,
                    unit = unit,
                    targetLow = targetLow,
                    doseTargetMgDl = predictionDoseTargetMgDl,
                    settings = predictionSettings,
                    nowMillis = journalNow,
                    maxBaselineAgeMillis = Notify.glucosetimeout
                )
            }
        }
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
                            // Say what was wrong with the file — a bare
                            // "unsupported" leaves the user guessing.
                            result.errorMessage != null ->
                                toast(context.getString(R.string.import_failed_with_error, result.errorMessage))
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
                modelProfile = predictionModelProfile,
                targetMgDl = predictionDoseTargetMgDl
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
        // FAB removed - empty state now has inline cards
    ) { padding ->
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
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
            // Held as a State rather than read through `by`: every consumer of this is
            // inside the chart item, and reading it out here recomposed the whole
            // ~1500-line dashboard body on every scrolled pixel.
            val collapseFractionState = remember(listState, collapseDistancePx, isLandscape) {
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
            // Collected rather than keyed on: as a LaunchedEffect key the fraction was
            // read during composition, which is the recomposition-per-frame again. The
            // hysteresis thresholds are unchanged.
            LaunchedEffect(collapseFractionState, isLandscape) {
                if (isLandscape) {
                    isChartExpanded = false
                    return@LaunchedEffect
                }
                snapshotFlow { collapseFractionState.value }.collect { fraction ->
                    isChartExpanded = if (isChartExpanded) fraction < 0.72f else fraction < 0.48f
                }
            }

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
            val collapsedChartHorizontalPadding = contentHorizontalPadding
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
            var measuredReadingBaselineHeightPx by rememberSaveable { mutableIntStateOf(0) }
            val measuredHeaderHeight = with(density) {
                if (measuredHeaderHeightPx > 0) measuredHeaderHeightPx.toDp() else heroFallbackHeight
            }
            val measuredReadingBaselineHeight = with(density) {
                if (measuredReadingBaselineHeightPx > 0) {
                    measuredReadingBaselineHeightPx.toDp()
                } else {
                    fallbackReadingRowHeight
                }
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
                measuredReadingBaselineHeight,
                middleVisibleReadingRows
            ) {
                (boundedFullscreenChartItemHeight - (measuredReadingBaselineHeight * middleVisibleReadingRows))
                    .coerceIn(0.dp, boundedFullscreenChartItemHeight)
            }
            val boundedMiddleChartItemHeight = middleChartItemHeight
                .coerceAtLeast(0.dp)
                .coerceAtMost(boundedFullscreenChartItemHeight)
            val collapsedChartItemHeight = remember(
                boundedFullscreenChartItemHeight,
                boundedMiddleChartItemHeight,
                measuredReadingBaselineHeight,
                defaultVisibleReadingRows
            ) {
                (boundedFullscreenChartItemHeight - (measuredReadingBaselineHeight * defaultVisibleReadingRows))
                    .coerceIn(0.dp, boundedMiddleChartItemHeight)
            }
            val boundedCollapsedChartItemHeight = collapsedChartItemHeight
                .coerceAtLeast(0.dp)
                .coerceAtMost(boundedFullscreenChartItemHeight)
            val middleChartBoostDp = (boundedMiddleChartItemHeight - boundedCollapsedChartItemHeight).coerceAtLeast(0.dp)
            val maxChartBoostDp = (boundedFullscreenChartItemHeight - boundedCollapsedChartItemHeight).coerceAtLeast(0.dp)
            val middleChartBoostPx = with(density) { middleChartBoostDp.toPx() }
            val maxChartBoostPx = with(density) { maxChartBoostDp.toPx() }

            val chartBoostState = rememberSaveable { mutableFloatStateOf(0f) }

            val scope = rememberCoroutineScope()

            val nestedScrollConnection = remember(maxChartBoostPx, middleChartBoostPx, listState) {
                object : NestedScrollConnection {
                    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                        // Dragging UP (scroll delta < 0): Shrink chart first.
                        // 100% absorption means chart shrinks EXACTLY as finger moves, keeping top fixed.
                        // Once boost hits 0, remainders pass to the list for uninterrupted scrolling.
                        val currentBoostPx = chartBoostState.floatValue * maxChartBoostPx
                        if (available.y < 0 && currentBoostPx > 0f && maxChartBoostPx > 0f) {
                            val newBoost = (currentBoostPx + available.y).coerceAtLeast(0f)
                            val consumed = newBoost - currentBoostPx
                            chartBoostState.floatValue = (newBoost / maxChartBoostPx).coerceIn(0f, 1f)
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
                        val currentBoostPx = chartBoostState.floatValue * maxChartBoostPx
                        if (
                            available.y > 0 &&
                            maxChartBoostPx > 0f &&
                            listState.firstVisibleItemIndex == 0 &&
                            listState.firstVisibleItemScrollOffset == 0
                        ) {
                            val newBoost = (currentBoostPx + available.y).coerceAtMost(maxChartBoostPx)
                            val consumedY = newBoost - currentBoostPx
                            chartBoostState.floatValue = (newBoost / maxChartBoostPx).coerceIn(0f, 1f)
                            return Offset(0f, consumedY)
                        }
                        return Offset.Zero
                    }

                    override suspend fun onPreFling(available: androidx.compose.ui.unit.Velocity): androidx.compose.ui.unit.Velocity {
                        val currentBoostPx = chartBoostState.floatValue * maxChartBoostPx
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
                                    chartBoostState.floatValue = (value / maxChartBoostPx).coerceIn(0f, 1f)
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
                    chartBoostState.floatValue = 0f
                } else {
                    chartBoostState.floatValue = chartBoostState.floatValue.coerceIn(0f, 1f)
                }
            }

            // Same reason as above: keying this on live scroll state read it during
            // composition. Collecting the same values leaves the behaviour identical
            // and the outer body untouched while a scroll is in flight.
            LaunchedEffect(listState, chartBoostState, maxChartBoostPx, middleChartBoostPx) {
                snapshotFlow {
                    SnapInput(
                        scrolling = listState.isScrollInProgress,
                        firstIndex = listState.firstVisibleItemIndex,
                        firstOffset = listState.firstVisibleItemScrollOffset,
                        boost = chartBoostState.floatValue
                    )
                }.collect { input ->
                    if (maxChartBoostPx <= 0f || input.scrolling) return@collect

                    val currentBoostPx = input.boost * maxChartBoostPx
                    if (currentBoostPx <= 1f) {
                        if (input.boost != 0f) chartBoostState.floatValue = 0f
                        return@collect
                    }

                    val middleAnchorPx = middleChartBoostPx.coerceIn(0f, maxChartBoostPx)
                    val shouldCollapseToDefault = input.firstIndex > 0 || input.firstOffset > 0
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
                        chartBoostState.floatValue =
                            (targetAnchorPx / maxChartBoostPx).coerceIn(0f, 1f)
                    }
                }
            }



            // --- REUSABLE UI SECTIONS ---


            val recentReadings = remember(consumerHistory) {
                buildDisplayReadings(consumerHistory, limit = 10)
            }
            // Rows drawn vs points the row arrows regress over — see buildTrendHistory.
            // recentReadings stays an exact prefix of this, which is what keeps
            // ReadingRow's `history.drop(index)` aligned to the displayed rows.
            val recentReadingsTrendHistory = remember(consumerHistory) {
                buildTrendHistory(consumerHistory)
            }
            val recentReadingPeers = remember(recentReadings, multiSensorDisplay) {
                recentReadings.map { reading -> multiSensorDisplay.peersAt(reading.timestamp) }
            }
            // Per-row Δ: the hero's delta computation anchored at each row's
            // timestamp — over the same unsmoothed history the hero reads, so the
            // newest row and the hero can never disagree.
            // The rows' movement is read once and used twice: the Δ text, and the rate the
            // row's arrow turns by. Only where the number is actually shown, though: with the
            // column off there is nothing for the arrow to contradict, and rebasing it anyway
            // would let a display setting quietly decide what an arrow means.
            val recentReadingDeltas = remember(
                dashboardRowsShowDelta, recentReadings, glucoseHistory, unit, deltaIntervalMinutes
            ) {
                if (dashboardRowsShowDelta) {
                    readingDeltas(
                        recentReadings.map { it.timestamp },
                        glucoseHistory,
                        tk.glucodata.ui.util.GlucoseFormatter.isMmol(unit),
                        deltaIntervalMinutes
                    )
                } else {
                    emptyList()
                }
            }
            val recentReadingDeltaTexts = remember(recentReadingDeltas) {
                recentReadingDeltas.map { it?.text }
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
                    importLauncher.launch(
                        arrayOf(
                            "application/json",
                            "text/csv",
                            "text/comma-separated-values",
                            "text/tab-separated-values",
                            "*/*"
                        )
                    )
                },
                modifier = Modifier
                    .padding(padding),
                readinessContent = {
                    DashboardCgmReadinessBanner(onOpenReadiness = onNavigateToReadiness)
                    if (appUpdateBannerVisible) {
                        DashboardAppUpdateBanner(
                            modifier = Modifier.padding(top = 12.dp),
                            onOpenAppUpdates = onNavigateToAppUpdates
                        )
                    }
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
                    verticalArrangement = Arrangement.spacedBy(12.dp),
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
                            history = glucoseHistory, // Trend must see measured data: visual smoothing
                            // reshapes the recent slope, and the notification/broadcast
                            // arrow computes from unsmoothed history
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
                            showDelta = dashboardShowDelta,
                            deltaIntervalMinutes = deltaIntervalMinutes,
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

                    // Pinned metrics live in the narrow left column here, two rows of
                    // two, rather than four cells squeezed across it.
                    if (showPinnedStats) {
                        item {
                            tk.glucodata.ui.stats.PinnedStatsStrip(
                                rows = 2,
                                windowState = pinnedStatsWindow
                            )
                        }
                    }


                    item {
                        DashboardCgmReadinessBanner(onOpenReadiness = onNavigateToReadiness)
                    }

                    if (appUpdateBannerVisible) {
                        item {
                            DashboardAppUpdateBanner(onOpenAppUpdates = onNavigateToAppUpdates)
                        }
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
                                history = recentReadingsTrendHistory,
                                deltaText = recentReadingDeltaTexts.getOrNull(index),
                                deltaRateMgdlPerMinute = recentReadingDeltas.getOrNull(index)?.rateMgdlPerMinute,
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

                // Right pane: the chart really is the whole pane in landscape, so it is
                // drawn full-bleed (expandedProgress = 1) rather than as an inset card
                // floating inside its own container. Pinned metrics ride underneath it.
                Column(
                    modifier = Modifier
                        .weight(0.75f)
                        .fillMaxHeight()
                        // The scaffold only consumes the top inset, so the bottom one has
                        // to be taken here or the strip sits under the gesture bar.
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
                        .padding(top = 8.dp, bottom = 8.dp)
                ) {
                        Box(modifier = Modifier.weight(1f).fillMaxSize()) {
                            key(sensorName) {
                                DashboardChartSection(
                                    modifier = Modifier.fillMaxSize(),
                                    glucoseHistory = glucoseHistory,
                                    multiSensorDisplay = multiSensorDisplay,
                                    peerPredictionSeries = peerPredictionSeries,
                                    journalMarkers = journalChartMarkers,
                                    activeInsulinSummary = activeInsulinSummary,
                                    activeInsulinFromRemote = activeInsulinFromRemote,
                                    showEiob = journalEiobDisplayEnabled,
                                    forecastDoseRecommendation = forecastDoseRecommendation,
                                    appChartRangeColors = appChartRangeColorsEnabled,
                                    predictionSeries = predictionSeries,
                                    graphSmoothingMinutes = visualSmoothingMinutes,
                                    collapseSmoothedData = dataSmoothingCollapseChunks,
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
                                    isExpanded = true,
                                    expandedProgress = 1f,
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
                            history = glucoseHistory, // Trend must see measured data: visual smoothing
                            // reshapes the recent slope, and the notification/broadcast
                            // arrow computes from unsmoothed history
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
                            showDelta = dashboardShowDelta,
                            deltaIntervalMinutes = deltaIntervalMinutes,
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

                if (appUpdateBannerVisible) {
                    item {
                        DashboardAppUpdateBanner(
                            modifier = Modifier.padding(horizontal = contentHorizontalPadding),
                            onOpenAppUpdates = onNavigateToAppUpdates
                        )
                    }
                }

                item {
                    // Every scroll-driven value is read here rather than in the screen
                    // body, so a scroll invalidates this item and nothing else.
                    val collapseFraction = collapseFractionState.value
                    val chartBoostProgress = chartBoostState.floatValue
                    val chartHeightBoostDp = with(density) {
                        (chartBoostProgress * maxChartBoostPx).toDp()
                    }
                    // The card's inset and its colour used to be animated by two springs
                    // of different stiffness. Mid-transition the chart was already inset
                    // while still painted the screen background — the stray patch of
                    // shade during expand and collapse. One spring drives both now, so
                    // the shape and the colour can no longer disagree.
                    val animatedCollapse by animateFloatAsState(
                        targetValue = collapseFraction,
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioNoBouncy,
                            stiffness = Spring.StiffnessMediumLow
                        ),
                        label = "DashboardChartCollapse"
                    )
                    val expandedProgress = (1f - animatedCollapse).coerceIn(0f, 1f)

                    // Portrait chart sizing is anchored to explicit visible-row budgets:
                    // top state shows ~3-4 rows, middle shows ~1-2, fullscreen hides the list.
                    val chartItemHeightTarget = (boundedCollapsedChartItemHeight + chartHeightBoostDp)
                        .coerceIn(boundedCollapsedChartItemHeight, boundedFullscreenChartItemHeight)
                    val animatedChartHorizontalPadding = collapsedChartHorizontalPadding * animatedCollapse
                    val animatedChartItemHeight by animateDpAsState(
                                targetValue = chartItemHeightTarget,
                                animationSpec = spring(
                                    dampingRatio = Spring.DampingRatioNoBouncy,
                                    stiffness = Spring.StiffnessLow
                        ),
                        label = "DashboardChartItemHeight"
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
                                    activeInsulinSummary = activeInsulinSummary,
                                    activeInsulinFromRemote = activeInsulinFromRemote,
                                    showEiob = journalEiobDisplayEnabled,
                                    forecastDoseRecommendation = forecastDoseRecommendation,
                                    appChartRangeColors = appChartRangeColorsEnabled,
                                    predictionSeries = predictionSeries,
                                    graphSmoothingMinutes = visualSmoothingMinutes,
                                    collapseSmoothedData = dataSmoothingCollapseChunks,
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

                    // Shares the chart's item on purpose: the range chips are the last
                    // thing inside it, so a separate item would put a full
                    // `dashboardItemSpacing` between the chips and this strip.
                    if (showPinnedStats) {
                        tk.glucodata.ui.stats.PinnedStatsStrip(
                            modifier = Modifier.padding(
                                start = contentHorizontalPadding,
                                end = contentHorizontalPadding,
                                // Between the two extremes: a separate lazy item put a
                                // full 12 dp here and read as a hole, 2 dp had the strip
                                // welded to the chart card once the chart collapses.
                                top = 8.dp
                            ),
                            windowState = pinnedStatsWindow
                        )
                    }
}


                item {
                    Column(modifier = Modifier.fillMaxWidth()) {
                    Box(
                        modifier = Modifier
                            .padding(
                                start = contentHorizontalPadding,
                                top = readingsTopSpacing,
                                end = contentHorizontalPadding
                            )
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
                                history = recentReadingsTrendHistory,
                                deltaText = recentReadingDeltaTexts.getOrNull(index),
                                deltaRateMgdlPerMinute = recentReadingDeltas.getOrNull(index)?.rateMgdlPerMinute,
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
                                onBaselineHeightMeasured = if (index == 0) {
                                    { measuredReadingBaselineHeightPx = it }
                                } else {
                                    null
                                },
                                modifier = Modifier.animateItem()
                            )
                        }
                    }
                    }
                }

                // Calibrations Card
                item {
                    Box(modifier = Modifier.padding(horizontal = contentHorizontalPadding)) {
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

            if (journalEnabled && journalDashboardQuickAdd) {
                JournalExpandableFab(
                    expanded = dashboardFabExpanded,
                    onExpandedChange = {
                        dashboardFabExpanded = it
                        if (it) clearJournalAction()
                    },
                    onTypeSelected = { type ->
                        dashboardFabExpanded = false
                        clearJournalAction()
                        lastJournalType = type
                        val selection = dashboardChartViewport?.selectedPoint
                        val suggestedGlucoseMgDl = selection?.value
                            ?.takeIf { type == JournalEntryType.FINGERSTICK && !journalQuickAddAlwaysNow }
                            ?.let {
                                if (tk.glucodata.ui.util.GlucoseFormatter.isMmol(unit)) {
                                    tk.glucodata.ui.util.GlucoseFormatter.mmolToMg(it)
                                } else {
                                    it
                                }
                            }
                        journalEditorRequest = JournalEditorRequest(
                            type = type,
                            timestamp = journalQuickAddTimestamp(
                                selection?.timestamp,
                                System.currentTimeMillis(),
                                journalQuickAddAlwaysNow
                            ),
                            suggestedGlucoseMgDl = suggestedGlucoseMgDl,
                            suggestedChartAnchorGlucoseMgDl = suggestedGlucoseMgDl
                        )
                    },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 20.dp, bottom = 20.dp)
                )
            }
        }
    }

}
