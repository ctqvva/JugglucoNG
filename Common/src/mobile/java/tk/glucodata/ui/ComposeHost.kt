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
import tk.glucodata.data.journal.JournalInsulinPreset
import tk.glucodata.data.prediction.GlucosePredictionSeries
import tk.glucodata.data.prediction.GlucosePredictionSeriesKind
import tk.glucodata.data.prediction.PredictiveSimulationSettings
import tk.glucodata.data.prediction.buildGlucosePrediction
import tk.glucodata.ui.journal.JournalDoseProfile
import tk.glucodata.ui.journal.JournalEntrySheet
import tk.glucodata.ui.journal.JournalInlineChip
import tk.glucodata.ui.journal.JournalSettingsScreen
import tk.glucodata.ui.journal.buildActiveInsulinSummary
import tk.glucodata.ui.journal.buildJournalChartMarkers
import tk.glucodata.ui.journal.journalTypeColor
import tk.glucodata.ui.journal.journalTypeSelectedContainerColor
import tk.glucodata.ui.journal.journalTypeSubtleContainerColor
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





// Helper to format timestamps or date strings into "Mon 20.05.2024 14:30"
fun formatSensorTime(rawTime: String): String {
    if (rawTime.isBlank()) return ""

    val outputFormat = java.text.SimpleDateFormat("EEE dd.MM.yyyy HH:mm", java.util.Locale.getDefault())

    // 1. Try as Unix Timestamp (Digits only)
    if (rawTime.all { it.isDigit() }) {
        val timestamp = rawTime.toLongOrNull()
        if (timestamp != null) {
            val millis = if (timestamp < 10_000_000_000L) timestamp * 1000L else timestamp
            return outputFormat.format(java.util.Date(millis))
        }
    }

    // 2. Try as Full Date "yyyy-MM-dd HH:mm:ss"
    try {
        val fullDateParser = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
        val date = fullDateParser.parse(rawTime)
        if (date != null) return outputFormat.format(date)
    } catch (e: Exception) {
        // Ignore and try next format
    }

    // 3. Try as Partial Date "MM-dd HH:mm:ss" (What you likely have)
    try {
        val partialParser = java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.getDefault())
        val date = partialParser.parse(rawTime)

        if (date != null) {
            // Fix the Year (partial date defaults to 1970)
            val cal = java.util.Calendar.getInstance()
            cal.time = date

            // Set to Current Year
            val nowCal = java.util.Calendar.getInstance()
            val currentYear = nowCal.get(java.util.Calendar.YEAR)
            cal.set(java.util.Calendar.YEAR, currentYear)

            // Smart Logic: If the resulting date is more than 30 days in the future,
            // it likely belongs to the PREVIOUS year (e.g. It\'s Jan 2025, but date is "12-31")
            // Sensors usually don\'t have future start dates.
            if (cal.timeInMillis > System.currentTimeMillis() + (30L * 24 * 60 * 60 * 1000)) {
                cal.add(java.util.Calendar.YEAR, -1)
            }

            return outputFormat.format(cal.time)
        }
    } catch (e: Exception) {
        // Ignore
    }

    // 4. Fallback: Return raw string if nothing matched
    return rawTime
}

data class GlucosePoint(
    val value: Float,
    val time: String,
    val timestamp: Long = 0L,
    val rawValue: Float = 0f,
    val rate: Float? = null,
    val sensorSerial: String? = null
)

fun getTrendIcon(rate: Float, modifier: Modifier = Modifier): ImageVector =
    when {
        rate > 0.5f -> Icons.Rounded.TrendingUp
        rate < -0.5f -> Icons.Rounded.TrendingDown
        else -> Icons.Rounded.TrendingFlat
    }

@Composable
fun getTrendDescription(rate: Float): String {
    return when {
        rate > 0 -> stringResource(R.string.trend_rising, rate)
        rate < 0 -> stringResource(R.string.trend_falling, abs(rate))
        else -> stringResource(R.string.trend_unchanged)
    }
}

fun getDisplayValues(
    point: GlucosePoint, 
    viewMode: Int, 
    unit: String,
    calibratedValue: Float? = null
): DisplayValues {
    val isMmol = if (unit.isNotEmpty()) tk.glucodata.ui.util.GlucoseFormatter.isMmol(unit) else tk.glucodata.ui.util.GlucoseFormatter.isMmolApp()
    val hideInitialWhenCalibrated = calibratedValue != null &&
        tk.glucodata.data.calibration.CalibrationManager.shouldHideInitialWhenCalibrated()
    return DisplayValueResolver.resolve(
        autoValue = point.value,
        rawValue = point.rawValue,
        viewMode = viewMode,
        isMmol = isMmol,
        unitLabel = unit,
        calibratedValue = calibratedValue,
        hideInitialWhenCalibrated = hideInitialWhenCalibrated
    )
}

private enum class PredictionHistorySource {
    AUTO,
    RAW,
    CALIBRATED_AUTO,
    CALIBRATED_RAW
}

private const val PREDICTION_HISTORY_WINDOW_MS = 60L * 60L * 1000L
private const val PREDICTION_HISTORY_MAX_POINTS = 96
private const val DASHBOARD_CONSUMER_HISTORY_MAX_POINTS = 512

private fun trimHistoryForPrediction(points: List<GlucosePoint>): List<GlucosePoint> {
    if (points.size <= PREDICTION_HISTORY_MAX_POINTS) return points

    var latestTimestamp = 0L
    for (index in points.indices.reversed()) {
        val point = points[index]
        if (point.timestamp > 0L &&
            ((point.value.isFinite() && point.value > 0.1f) ||
                (point.rawValue.isFinite() && point.rawValue > 0.1f))
        ) {
            latestTimestamp = point.timestamp
            break
        }
    }
    if (latestTimestamp <= 0L) return points.takeLast(PREDICTION_HISTORY_MAX_POINTS)

    val startTimestamp = latestTimestamp - PREDICTION_HISTORY_WINDOW_MS
    val recent = ArrayList<GlucosePoint>(PREDICTION_HISTORY_MAX_POINTS)
    for (index in points.indices.reversed()) {
        val point = points[index]
        if (point.timestamp < startTimestamp && recent.size >= 2) break
        if (point.timestamp in startTimestamp..latestTimestamp &&
            ((point.value.isFinite() && point.value > 0.1f) ||
                (point.rawValue.isFinite() && point.rawValue > 0.1f))
        ) {
            recent.add(point)
            if (recent.size >= PREDICTION_HISTORY_MAX_POINTS) break
        }
    }
    recent.reverse()

    return when {
        recent.size >= 2 -> recent
        else -> points.takeLast(PREDICTION_HISTORY_MAX_POINTS)
    }
}

private fun buildDisplayHistoryForPrediction(
    points: List<GlucosePoint>,
    source: PredictionHistorySource
): List<GlucosePoint> {
    if (points.isEmpty()) return emptyList()

    val useRaw = source == PredictionHistorySource.RAW || source == PredictionHistorySource.CALIBRATED_RAW
    val useCalibration = source == PredictionHistorySource.CALIBRATED_AUTO || source == PredictionHistorySource.CALIBRATED_RAW
    val calibrationActiveBySensor = HashMap<String?, Boolean>()

    return points.map { point ->
        val baseValue = if (useRaw) point.rawValue else point.value
        val sensorId = point.sensorSerial?.takeIf { it.isNotBlank() }
        val activeCalibration = useCalibration && calibrationActiveBySensor.getOrPut(sensorId) {
            tk.glucodata.data.calibration.CalibrationManager.hasActiveCalibration(useRaw, sensorId)
        }
        val displayValue = if (activeCalibration && baseValue.isFinite() && baseValue > 0.1f) {
            tk.glucodata.data.calibration.CalibrationManager.getCalibratedValue(
                baseValue,
                point.timestamp,
                useRaw,
                sensorIdOverride = sensorId
            )
        } else {
            baseValue
        }
        point.copy(value = displayValue, rawValue = baseValue)
    }
}

private fun hasAnyActiveCalibrationForPrediction(
    points: List<GlucosePoint>,
    useRaw: Boolean
): Boolean {
    val checkedSensors = HashMap<String?, Boolean>()
    return points.any { point ->
        val value = if (useRaw) point.rawValue else point.value
        if (!value.isFinite() || value <= 0.1f) {
            false
        } else {
            val sensorId = point.sensorSerial?.takeIf { it.isNotBlank() }
            checkedSensors.getOrPut(sensorId) {
                tk.glucodata.data.calibration.CalibrationManager.hasActiveCalibration(useRaw, sensorId)
            }
        }
    }
}

private fun buildPredictionSeriesForChart(
    points: List<GlucosePoint>,
    viewMode: Int,
    journalEntries: List<JournalEntry>,
    insulinPresetsById: Map<Long, JournalInsulinPreset>,
    unit: String,
    targetLow: Float,
    targetHigh: Float,
    settings: PredictiveSimulationSettings
): List<GlucosePredictionSeries> {
    if (!settings.enabled || points.size < 2) return emptyList()

    val predictionHistory = trimHistoryForPrediction(points)
    if (predictionHistory.size < 2) return emptyList()

    val isRawMode = viewMode == 1 || viewMode == 3
    val hasCalibration = hasAnyActiveCalibrationForPrediction(predictionHistory, isRawMode)
    val hideInitialWhenCalibrated = hasCalibration &&
        tk.glucodata.data.calibration.CalibrationManager.shouldHideInitialWhenCalibrated()
    val drawRaw = !(hideInitialWhenCalibrated && isRawMode) && (viewMode == 1 || viewMode == 2 || viewMode == 3)
    val drawAuto = !(hideInitialWhenCalibrated && !isRawMode) && (viewMode == 0 || viewMode == 2 || viewMode == 3)

    fun buildSeries(
        kind: GlucosePredictionSeriesKind,
        source: PredictionHistorySource
    ): GlucosePredictionSeries? {
        val seriesPoints = buildGlucosePrediction(
            history = buildDisplayHistoryForPrediction(predictionHistory, source),
            journalEntries = journalEntries,
            insulinPresetsById = insulinPresetsById,
            unit = unit,
            targetLow = targetLow,
            targetHigh = targetHigh,
            settings = settings
        )
        return seriesPoints.takeIf { it.size >= 2 }?.let { GlucosePredictionSeries(kind, it) }
    }

    return buildList {
        if (drawRaw) {
            buildSeries(GlucosePredictionSeriesKind.RAW, PredictionHistorySource.RAW)?.let(::add)
        }
        if (drawAuto) {
            buildSeries(GlucosePredictionSeriesKind.AUTO, PredictionHistorySource.AUTO)?.let(::add)
        }
        if (hasCalibration) {
            buildSeries(
                GlucosePredictionSeriesKind.CALIBRATED,
                if (isRawMode) PredictionHistorySource.CALIBRATED_RAW else PredictionHistorySource.CALIBRATED_AUTO
            )?.let(::add)
        }
    }
}

/**
 * Single source of truth for the history that downstream non-chart consumers see
 * (hero trend arrow, recent readings, predictive simulation).
 *
 * When data smoothing is on and not restricted to the graph, the recent dashboard
 * consumer tail is smoothed segment-by-segment so the trend, prediction, and
 * reading list reflect the same recent line without reprocessing all stored history.
 */
private fun buildSmoothedConsumerHistory(
    points: List<GlucosePoint>,
    smoothingMinutes: Int,
    smoothOnlyGraph: Boolean,
    collapseChunks: Boolean
): List<GlucosePoint> {
    if (points.isEmpty()) {
        return points
    }
    val source = if (points.size > DASHBOARD_CONSUMER_HISTORY_MAX_POINTS) {
        points.takeLast(DASHBOARD_CONSUMER_HISTORY_MAX_POINTS)
    } else {
        points
    }
    if (smoothOnlyGraph || smoothingMinutes <= 0) {
        return source
    }

    val processed = ArrayList<GlucosePoint>(source.size)
    GlucosePointSegments.split(source).forEach { segment ->
        val sourceByTimestamp = segment.associateBy { it.timestamp }
        val smoothed = DataSmoothing.smoothNativePoints(
            segment.map { tk.glucodata.GlucosePoint(it.timestamp, it.value, it.rawValue) },
            smoothingMinutes,
            collapseChunks
        )

        smoothed.forEach { point ->
            val source = sourceByTimestamp[point.timestamp]
            processed.add(
                GlucosePoint(
                    value = point.value,
                    time = source?.time ?: java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(point.timestamp)),
                    timestamp = point.timestamp,
                    rawValue = point.rawValue,
                    rate = source?.rate,
                    sensorSerial = source?.sensorSerial
                )
            )
        }
    }
    return processed
}

private fun buildDisplayReadings(
    consumerHistory: List<GlucosePoint>,
    limit: Int = 10
): List<GlucosePoint> {
    if (consumerHistory.isEmpty()) {
        return emptyList()
    }
    return consumerHistory
        .asReversed()
        .distinctBy { it.timestamp }
        .sortedByDescending { it.timestamp }
        .take(limit)
}


enum class ThemeMode {
    SYSTEM, LIGHT, DARK
}

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

@Keep
fun setComposeContent(activity: AppCompatActivity, legacyView: View?) {
    // CRITICAL FIX: Hide the native legacy view (histogram/nanovg) to prevent
    // double-rendering, GPU overdraw, and visual glitches (bleeding through navbar).
    legacyView?.visibility = View.GONE

    activity.setContent {
        val prefs = activity.getSharedPreferences(activity.packageName + "_preferences", Context.MODE_PRIVATE)
        val savedTheme = prefs.getString("theme_mode", "SYSTEM") ?: "SYSTEM"
        var themeMode by remember { mutableStateOf(ThemeMode.valueOf(savedTheme)) }

        JugglucoTheme(themeMode = themeMode) {
            MainApp(
                themeMode = themeMode,
                onThemeChanged = { newMode ->
                    themeMode = newMode
                    prefs.edit().putString("theme_mode", newMode.name).apply()
                }
            )
        }
    }
}



// ...

@Composable
fun JugglucoTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val darkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    val colorScheme = when {
        android.os.Build.VERSION.SDK_INT >= 31 -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> darkColorScheme(
            primary = Color(0xFF90CAF9),
            secondary = Color(0xFF81D4FA),
            tertiary = Color(0xFFCE93D8),
            background = Color(0xFF121212),
            surface = Color(0xFF1E1E1E),
            onPrimary = Color.Black,
            onSecondary = Color.Black,
            onTertiary = Color.Black,
            onBackground = Color.White,
            onSurface = Color.White
        )
        else -> lightColorScheme(
            primary = Color(0xFF1565C0),
            secondary = Color(0xFF039BE5),
            tertiary = Color(0xFF7B1FA2),
            background = Color(0xFFFAFAFA),
            surface = Color.White,
            onPrimary = Color.White,
            onSecondary = Color.White,
            onTertiary = Color.White,
            onBackground = Color.Black,
            onSurface = Color.Black
        )
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.background.toArgb()
            window.navigationBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
            WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = tk.glucodata.ui.theme.AppTypography,
    ) {
        // Edit 52b (v3): Allow Display Size scaling up to 20% above hardware native.
        // This lets users who want larger UI (accessibility) get a meaningful boost,
        // while preventing extreme Display Size settings from breaking M3 layouts.
        // fontScale capped at 1.15 — "slightly larger" text is fine, "huge" breaks cards.
        val currentDensity = LocalDensity.current
        val nativeDensity = android.util.DisplayMetrics.DENSITY_DEVICE_STABLE / 160f
        val maxDensity = nativeDensity * 1.1f
        val clampedDensity = Density(
            density = currentDensity.density.coerceAtMost(maxDensity),
            fontScale = currentDensity.fontScale.coerceAtMost(1.1f)
        )
        CompositionLocalProvider(LocalDensity provides clampedDensity) {
            content()
        }
    }
}


sealed class CalibrationSheetState {
    object Hidden : CalibrationSheetState()
    data class New(val auto: Float, val raw: Float, val timestamp: Long) : CalibrationSheetState()
    data class Edit(val entity: tk.glucodata.data.calibration.CalibrationEntity) : CalibrationSheetState()
}

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
        onTriggerCalibration = onTriggerCalibration
    )
}

@Composable
private fun HistoryRoute(
    dashboardViewModel: DashboardViewModel,
    onBack: () -> Unit,
    onTriggerCalibration: (CalibrationSheetState) -> Unit
) {
    // Use the merged multi-sensor flow so the previous sensor's calibrated
    // readings (and any imported/older sensor data) remain visible on the
    // History screen across sensor swaps. The dashboard chart still uses the
    // narrower per-sensor [glucoseHistory] flow above.
    val glucoseHistory by dashboardViewModel.historyScreenGlucoseHistory.collectAsStateWithLifecycle()
    val unit by dashboardViewModel.unit.collectAsStateWithLifecycle()
    val viewMode by dashboardViewModel.viewMode.collectAsStateWithLifecycle()
    val sensorName by dashboardViewModel.sensorName.collectAsStateWithLifecycle()
    val graphLow by dashboardViewModel.graphLow.collectAsStateWithLifecycle()
    val graphHigh by dashboardViewModel.graphHigh.collectAsStateWithLifecycle()
    val targetLow by dashboardViewModel.targetLow.collectAsStateWithLifecycle()
    val targetHigh by dashboardViewModel.targetHigh.collectAsStateWithLifecycle()
    val chartSmoothingMinutes by dashboardViewModel.chartSmoothingMinutes.collectAsStateWithLifecycle()
    val dataSmoothingCollapseChunks by dashboardViewModel.dataSmoothingCollapseChunks.collectAsStateWithLifecycle()
    val previewWindowMode by dashboardViewModel.previewWindowMode.collectAsStateWithLifecycle()
    val journalEnabled by dashboardViewModel.journalEnabled.collectAsStateWithLifecycle()
    val journalDoseCalculatorEnabled by dashboardViewModel.journalDoseCalculatorEnabled.collectAsStateWithLifecycle()
    val journalEntries by dashboardViewModel.journalEntries.collectAsStateWithLifecycle()
    val journalInsulinPresets by dashboardViewModel.journalInsulinPresets.collectAsStateWithLifecycle()
    val predictionCarbRatioGramsPerUnit by dashboardViewModel.predictionCarbRatioGramsPerUnit.collectAsStateWithLifecycle()
    val predictionInsulinSensitivityMgDlPerUnit by dashboardViewModel.predictionInsulinSensitivityMgDlPerUnit.collectAsStateWithLifecycle()
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
        graphSmoothingMinutes = chartSmoothingMinutes,
        collapseSmoothedData = dataSmoothingCollapseChunks,
        previewWindowMode = previewWindowMode,
        calibrations = calibrations,
        title = stringResource(R.string.historyname),
        browseMode = TimelineBrowseMode.HISTORY,
        journalEnabled = journalEnabled,
        journalEntries = scopedJournalEntries,
        journalInsulinPresets = journalInsulinPresets,
        onBack = onBack,
        onPointClick = { point ->
            onTriggerCalibration(CalibrationSheetState.New(point.value, point.rawValue, point.timestamp))
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
            doseJournalEntries = scopedJournalEntries,
            doseProfile = JournalDoseProfile(
                enabled = journalEnabled && journalDoseCalculatorEnabled,
                carbRatioGramsPerUnit = predictionCarbRatioGramsPerUnit,
                insulinSensitivityMgDlPerUnit = predictionInsulinSensitivityMgDlPerUnit,
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
                dashboardViewModel.saveJournalEntry(input)
                lastJournalType = input.type
                journalEditorRequest = null
            },
            onSaveEntries = { inputs ->
                inputs.forEach(dashboardViewModel::saveJournalEntry)
                inputs.firstOrNull()?.let { lastJournalType = it.type }
                journalEditorRequest = null
            },
            onDelete = { entryId ->
                dashboardViewModel.deleteJournalEntry(entryId)
                journalEditorRequest = null
            },
            sensorSerialProvider = { sensorName.ifBlank { null } }
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

    val isMmol = tk.glucodata.ui.util.GlucoseFormatter.isMmol(unit)

    tk.glucodata.ui.calibration.CalibrationListScreen(
        navController = navController,
        isMmol = isMmol,
        viewMode = viewMode,
        onAdd = {
            val latest = glucoseHistory.firstOrNull()
            val autoVal = latest?.value ?: tk.glucodata.GlucoseValueParser.parseFirstOrZero(currentGlucose)
            val rawVal = latest?.rawValue ?: autoVal
            onTriggerCalibration(CalibrationSheetState.New(autoVal, rawVal, System.currentTimeMillis()))
        },
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
    val unit by dashboardViewModel.unit.collectAsStateWithLifecycle()
    val viewMode by dashboardViewModel.viewMode.collectAsStateWithLifecycle()

    val (initAuto, initRaw, initTime) = when (sheetState) {
        is CalibrationSheetState.New -> Triple(sheetState.auto, sheetState.raw, sheetState.timestamp)
        is CalibrationSheetState.Edit -> Triple(
            sheetState.entity.sensorValue,
            sheetState.entity.sensorValueRaw,
            sheetState.entity.timestamp
        )
        CalibrationSheetState.Hidden -> Triple(0f, 0f, 0L)
    }

    tk.glucodata.ui.calibration.CalibrationBottomSheet(
        onDismiss = onDismiss,
        initialValueAuto = initAuto,
        initialValueRaw = initRaw,
        initialTimestamp = initTime,
        glucoseHistory = glucoseHistory.map { tk.glucodata.GlucosePoint(it.timestamp, it.value, it.rawValue) },
        isMmol = tk.glucodata.ui.util.GlucoseFormatter.isMmol(unit),
        viewMode = viewMode,
        onNavigateToHistory = {
            onDismiss()
            onNavigateToCalibrations()
        }
    )
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

    fun collectionModeForRoute(route: String?): DashboardViewModel.CollectionMode = when (route) {
        "dashboard", "sensors", "settings" -> DashboardViewModel.CollectionMode.DASHBOARD
        "stats" -> DashboardViewModel.CollectionMode.INACTIVE
        "history", "journal", "calibrations", "settings/calibrations" -> DashboardViewModel.CollectionMode.FULL_HISTORY
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
        route == "history" || route == "journal" -> "dashboard"
        route == "calibrations" -> "dashboard"  // calibrations is a dashboard subpage
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
            // Already on the destination, do nothing
        }
    }

    // Define items for use in both Bar and Rail
    data class NavItem(val route: String, val label: String, val selectedIcon: ImageVector, val unselectedIcon: ImageVector)
    val navItems = buildList {
        add(NavItem("stats", stringResource(R.string.statistics_title), Icons.Filled.BarChart, Icons.Outlined.BarChart))
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
                    modifier = Modifier.padding(innerPadding).consumeWindowInsets(innerPadding)
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
                            onBack = { navController.popBackStack() },
                            onTriggerCalibration = onTriggerCalibration
                        )
                    }
                    composable("journal") {
                        HistoryRoute(
                            dashboardViewModel = dashboardViewModel,
                            onBack = { navController.popBackStack() },
                            onTriggerCalibration = onTriggerCalibration
                        )
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
                    composable("settings/mirror") { MirrorSettingsScreen(navController) }
                    composable("settings/watch") { WatchSettingsScreen(navController) }
                    // Keep legacy route for backward compatibility.
                    composable("settings/weartransport") { WatchSettingsScreen(navController) }
                    composable("settings/watch/wearos-config") { WearOsConfigScreen(navController) }
                    composable("settings/watch/garmin-status") { GarminStatusScreen(navController) }
                    composable("settings/webserver") { WebServerSettingsScreen(navController) }
                    composable("settings/notification-display") {
                        NotificationSettingsScreen(navController, dashboardViewModel)
                    }
                    composable("settings/data-smoothing") {
                        DataSmoothingSettingsScreen(navController, dashboardViewModel)
                    }
                    composable("settings/floating-display") {
                        FloatingGlucoseSettingsScreen(navController, dashboardViewModel)
                    }
                    composable("settings/aod-display") { AodSettingsScreen(navController) }

                    composable("settings/turnserver") { tk.glucodata.ui.TurnServerSettingsScreen(navController) }
                    composable("settings/debug") { DebugSettingsScreen(navController) }
                    composable("settings/cgm-readiness") { CgmReadinessScreen(navController) }
                    composable("settings/alerts") { tk.glucodata.ui.alerts.AlertSettingsScreen(navController) }
                    composable("settings/alerts/talker") { tk.glucodata.ui.alerts.TalkerSettingsScreen(navController) }
                    composable("settings/journal") { JournalSettingsScreen(navController, dashboardViewModel) }
                    composable("settings/calibrations") {
                        CalibrationListRoute(
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
                            label = { Text(item.label) },
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
                        onBack = { navController.popBackStack() },
                        onTriggerCalibration = onTriggerCalibration
                    )
                }
                composable("journal") {
                    HistoryRoute(
                        dashboardViewModel = dashboardViewModel,
                        onBack = { navController.popBackStack() },
                        onTriggerCalibration = onTriggerCalibration
                    )
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
                composable("settings/mirror") { MirrorSettingsScreen(navController) }
                composable("settings/watch") { WatchSettingsScreen(navController) }
                // Keep legacy route for backward compatibility.
                composable("settings/weartransport") { WatchSettingsScreen(navController) }
                composable("settings/watch/wearos-config") { WearOsConfigScreen(navController) }
                composable("settings/watch/garmin-status") { GarminStatusScreen(navController) }
                composable("settings/webserver") { WebServerSettingsScreen(navController) }
                composable("settings/notification-display") {
                    NotificationSettingsScreen(navController, dashboardViewModel)
                }
                composable("settings/data-smoothing") {
                    DataSmoothingSettingsScreen(navController, dashboardViewModel)
                }
                composable("settings/floating-display") {
                    FloatingGlucoseSettingsScreen(navController, dashboardViewModel)
                }
                composable("settings/aod-display") { AodSettingsScreen(navController) }

                composable("settings/turnserver") { tk.glucodata.ui.TurnServerSettingsScreen(navController) }
                composable("settings/debug") { DebugSettingsScreen(navController) }
                composable("settings/cgm-readiness") { CgmReadinessScreen(navController) }
                composable("settings/alerts") { tk.glucodata.ui.alerts.AlertSettingsScreen(navController) }
                composable("settings/alerts/talker") { tk.glucodata.ui.alerts.TalkerSettingsScreen(navController) }
                composable("settings/journal") { JournalSettingsScreen(navController, dashboardViewModel) }
                composable("settings/calibrations") {
                    CalibrationListRoute(
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
            }
        }
    }

    // --- CALIBRATION BOTTOM SHEET (Global) ---
    CalibrationSheetHost(
        sheetState = calibrationSheetState,
        dashboardViewModel = dashboardViewModel,
        onDismiss = { calibrationSheetState = CalibrationSheetState.Hidden },
        onNavigateToCalibrations = { navController.navigate("calibrations") }
    )
}

private data class JournalEditorRequest(
    val type: JournalEntryType,
    val timestamp: Long,
    val existingEntry: JournalEntry? = null,
    val suggestedGlucoseMgDl: Float? = null,
    val suggestedChartAnchorGlucoseMgDl: Float? = null,
    val suggestedAmountFraction: Float? = null
)

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
    val unit by viewModel.unit.collectAsState()
    val graphLow by viewModel.graphLow.collectAsState()
    val graphHigh by viewModel.graphHigh.collectAsState()
    val targetLow by viewModel.targetLow.collectAsState()
    val targetHigh by viewModel.targetHigh.collectAsState()
    val chartSmoothingMinutes by viewModel.chartSmoothingMinutes.collectAsState()
    val dataSmoothingGraphOnly by viewModel.dataSmoothingGraphOnly.collectAsState()
    val dataSmoothingCollapseChunks by viewModel.dataSmoothingCollapseChunks.collectAsState()
    val previewWindowMode by viewModel.previewWindowMode.collectAsState()
    val journalEnabled by viewModel.journalEnabled.collectAsState()
    val journalDoseCalculatorEnabled by viewModel.journalDoseCalculatorEnabled.collectAsState()
    val predictiveSimulationEnabled by viewModel.predictiveSimulationEnabled.collectAsState()
    val predictionTrendMomentumEnabled by viewModel.predictionTrendMomentumEnabled.collectAsState()
    val predictionCarbRatioGramsPerUnit by viewModel.predictionCarbRatioGramsPerUnit.collectAsState()
    val predictionInsulinSensitivityMgDlPerUnit by viewModel.predictionInsulinSensitivityMgDlPerUnit.collectAsState()
    val predictionCarbAbsorptionGramsPerHour by viewModel.predictionCarbAbsorptionGramsPerHour.collectAsState()
    val predictionHorizonMinutes by viewModel.predictionHorizonMinutes.collectAsState()
    val journalEntries by viewModel.journalEntries.collectAsState()
    val journalInsulinPresets by viewModel.journalInsulinPresets.collectAsState()
    val sensorStatus by viewModel.sensorStatus.collectAsState()
    val sensorProgress by viewModel.sensorProgress.collectAsState()
    val viewMode by viewModel.viewMode.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val activeSensorList by viewModel.activeSensorList.collectAsState()
    val sensorHoursRemaining by viewModel.sensorHoursRemaining.collectAsState()
    val currentDay by viewModel.currentDay.collectAsState()
    val predictionCalibrationRefresh by UiRefreshBus.revision.collectAsState(initial = 0L)
    val isRawEnabled by tk.glucodata.data.calibration.CalibrationManager.isEnabledForRaw.collectAsState()
    val isAutoEnabled by tk.glucodata.data.calibration.CalibrationManager.isEnabledForAuto.collectAsState()

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
    var journalEditorRequest by remember { mutableStateOf<JournalEditorRequest?>(null) }
    var journalActionTimestamp by rememberSaveable { mutableStateOf<Long?>(null) }
    var journalActionSuggestedGlucoseMgDl by remember { mutableStateOf<Float?>(null) }
    var journalActionSuggestedAmountFraction by remember { mutableStateOf<Float?>(null) }
    var lastJournalType by rememberSaveable { mutableStateOf(JournalEntryType.INSULIN) }
    var journalNow by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var dashboardChartViewport by remember { mutableStateOf<ChartViewportSnapshot?>(null) }

    val coroutineScope = rememberCoroutineScope()
    val journalPresetsById = remember(journalInsulinPresets) { journalInsulinPresets.associateBy { it.id } }
    val activeJournalPresets = remember(journalInsulinPresets) { journalInsulinPresets.filter { !it.isArchived } }
    // Mirror the History route: journal entries are time-bound, not
    // sensor-bound. Scoping them by the active sensor would silently hide
    // entries logged before a sensor swap.
    val scopedJournalEntries = remember(journalEnabled, journalEntries) {
        if (!journalEnabled) emptyList() else journalEntries
    }
    val journalChartMarkers = remember(journalEnabled, scopedJournalEntries, journalPresetsById, unit, glucoseHistory) {
        if (!journalEnabled || scopedJournalEntries.isEmpty()) {
            emptyList()
        } else {
            buildJournalChartMarkers(scopedJournalEntries, journalPresetsById, unit, glucoseHistory)
        }
    }
    val activeInsulinSummary = remember(journalEnabled, scopedJournalEntries, journalPresetsById, journalNow) {
        if (!journalEnabled || scopedJournalEntries.isEmpty()) {
            null
        } else {
            buildActiveInsulinSummary(scopedJournalEntries, journalPresetsById, journalNow)
        }
    }
    val predictionSettings = remember(
        predictiveSimulationEnabled,
        predictionTrendMomentumEnabled,
        predictionCarbRatioGramsPerUnit,
        predictionInsulinSensitivityMgDlPerUnit,
        predictionCarbAbsorptionGramsPerHour,
        predictionHorizonMinutes
    ) {
        PredictiveSimulationSettings(
            enabled = predictiveSimulationEnabled,
            trendMomentumEnabled = predictionTrendMomentumEnabled,
            horizonMinutes = predictionHorizonMinutes,
            carbRatioGramsPerUnit = predictionCarbRatioGramsPerUnit,
            insulinSensitivityMgDlPerUnit = predictionInsulinSensitivityMgDlPerUnit,
            carbAbsorptionGramsPerHour = predictionCarbAbsorptionGramsPerHour
        )
    }
    val consumerHistory = remember(
        glucoseHistory,
        chartSmoothingMinutes,
        dataSmoothingGraphOnly,
        dataSmoothingCollapseChunks
    ) {
        buildSmoothedConsumerHistory(
            points = glucoseHistory,
            smoothingMinutes = chartSmoothingMinutes,
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

    // Import launcher for CSV files
    val importLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            coroutineScope.launch {
                val result = tk.glucodata.data.HistoryExporter.importFromCsv(context, uri)
                if (result.success) {
                    android.widget.Toast.makeText(
                        context,
                        context.getString(R.string.imported_readings_count, result.successCount),
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                    viewModel.refreshData()
                } else {
                    android.widget.Toast.makeText(
                        context,
                        context.getString(R.string.import_failed_with_error, result.errorMessage ?: ""),
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    // Sibionics Setup Wizard (Full Screen)
    if (showSibionicsWizard) {
        tk.glucodata.ui.setup.SibionicsSetupWizard(
            onDismiss = { showSibionicsWizard = false },
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
            onComplete = {
                showMQWizard = false
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
            doseJournalEntries = scopedJournalEntries,
            doseProfile = JournalDoseProfile(
                enabled = journalEnabled && journalDoseCalculatorEnabled,
                carbRatioGramsPerUnit = predictionCarbRatioGramsPerUnit,
                insulinSensitivityMgDlPerUnit = predictionInsulinSensitivityMgDlPerUnit,
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
            val collapsedChartHorizontalPadding = when (adaptiveMetrics.layoutDensity) {
                AdaptiveLayoutDensity.Compact -> 10.dp
                AdaptiveLayoutDensity.Regular -> 12.dp
                AdaptiveLayoutDensity.Comfortable -> 14.dp
            }
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
            val recentReadingJournalEntries = remember(recentReadings, scopedJournalEntries) {
                groupJournalEntriesByReading(recentReadings, scopedJournalEntries)
            }
            val hasVisibleReadingContent = dashboardDataState.isFresh || recentReadings.isNotEmpty()

            val isManualCalibrationEnabled = if (viewMode == 1 || viewMode == 3) isRawEnabled else isAutoEnabled
            val triggerCalibrationIfEnabled: (CalibrationSheetState) -> Unit = { state ->
                if (isManualCalibrationEnabled) {
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
                isRawEnabled,
                isAutoEnabled
            ) {
                if (latestPoint != null && tk.glucodata.data.calibration.CalibrationManager.hasActiveCalibration(isRawModeHero, calibrationSensorId)) {
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
                }
            },
                onImportHistory = {
                    importLauncher.launch(arrayOf("text/csv", "text/comma-separated-values", "*/*"))
                },
                modifier = Modifier
                    .padding(padding)
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
                            isMmol = tk.glucodata.ui.util.GlucoseFormatter.isMmol(unit),
                            onHeroClick = {
                                val autoVal = latestPoint?.value ?: tk.glucodata.GlucoseValueParser.parseFirstOrZero(currentGlucose)
                                val rawVal = latestPoint?.rawValue ?: autoVal
                                triggerCalibrationIfEnabled(CalibrationSheetState.New(autoVal, rawVal, System.currentTimeMillis()))
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
                                        CalibrationSheetState.New(item.value, item.rawValue, item.timestamp)
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
                                    journalMarkers = journalChartMarkers,
                                    activeInsulinSummary = activeInsulinSummary,
                                    predictionSeries = predictionSeries,
                                    graphSmoothingMinutes = chartSmoothingMinutes,
                                    collapseSmoothedData = dataSmoothingCollapseChunks,
                                    previewWindowMode = previewWindowMode,
                                    graphLow = graphLow,
                                    graphHigh = graphHigh,
                                    targetLow = targetLow,
                                    targetHigh = targetHigh,
                                    unit = unit,
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
                                        triggerCalibrationIfEnabled(CalibrationSheetState.New(point.value, point.rawValue, point.timestamp))
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
                                DashboardJournalFloatingMenu(
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
                            isMmol = tk.glucodata.ui.util.GlucoseFormatter.isMmol(unit),
                            onHeroClick = {
                                val autoVal = latestPoint?.value ?: tk.glucodata.GlucoseValueParser.parseFirstOrZero(currentGlucose)
                                val rawVal = latestPoint?.rawValue ?: autoVal
                                triggerCalibrationIfEnabled(CalibrationSheetState.New(autoVal, rawVal, System.currentTimeMillis()))
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
                                    journalMarkers = journalChartMarkers,
                                    activeInsulinSummary = activeInsulinSummary,
                                    predictionSeries = predictionSeries,
                                    graphSmoothingMinutes = chartSmoothingMinutes,
                                    collapseSmoothedData = dataSmoothingCollapseChunks,
                                    previewWindowMode = previewWindowMode,
                                    graphLow = graphLow,
                                    graphHigh = graphHigh,
                                    targetLow = targetLow,
                                    targetHigh = targetHigh,
                                    unit = unit,
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
                                        triggerCalibrationIfEnabled(CalibrationSheetState.New(point.value, point.rawValue, point.timestamp))
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
                                DashboardJournalFloatingMenu(
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
                                        CalibrationSheetState.New(item.value, item.rawValue, item.timestamp)
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
                            showEmptyAction = hasVisibleReadingContent,
                            onAddCalibration = {
                                val autoVal = latestPoint?.value ?: tk.glucodata.GlucoseValueParser.parseFirstOrZero(currentGlucose)
                                val rawVal = latestPoint?.rawValue ?: autoVal
                                triggerCalibrationIfEnabled(CalibrationSheetState.New(autoVal, rawVal, System.currentTimeMillis()))
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
        }
    }

}

// --- EXTRACTED COMPONENTS (Performance Optimization) ---

fun buildGlucoseString(
    dvs: DisplayValues,
    primaryColor: Color,
    secondaryColor: Color,
    unitColor: Color,
    includeUnit: Boolean = false,
    unit: String = "",
    tertiaryColor: Color? = null
): androidx.compose.ui.text.AnnotatedString {
    return androidx.compose.ui.text.buildAnnotatedString {
        withStyle(androidx.compose.ui.text.SpanStyle(color = primaryColor)) {
            append(dvs.primaryStr)

            // If single value, append unit here if requested
            if (includeUnit && dvs.secondaryStr == null) {
                append(" ")
                withStyle(androidx.compose.ui.text.SpanStyle(color = unitColor)) {
                    append(unit)
                }
            }
        }
        if (dvs.secondaryStr != null) {
            append(" · ")
            withStyle(androidx.compose.ui.text.SpanStyle(color = secondaryColor)) {
                append(dvs.secondaryStr)
            }
        }
        // Tertiary value (when 3 values exist)
        if (dvs.tertiaryStr != null) {
            append(" · ")
            withStyle(androidx.compose.ui.text.SpanStyle(color = tertiaryColor ?: secondaryColor.copy(alpha = 0.5f))) {
                append(dvs.tertiaryStr)
            }
        }
    }
}

@Composable
fun ReadingRow(
    point: GlucosePoint,
    unit: String,
    viewMode: Int = 0,
    index: Int = 0,
    totalCount: Int = 1,
    history: List<GlucosePoint> = emptyList(), // Advanced Trend: Need history
    sensorId: String? = null,
    calibrations: List<tk.glucodata.data.calibration.CalibrationEntity> = emptyList(),
    journalEntries: List<JournalEntry> = emptyList(),
    journalPresetsById: Map<Long, JournalInsulinPreset> = emptyMap(),
    journalChipExpanded: Boolean = false,
    onJournalEntryClick: ((JournalEntry) -> Unit)? = null,
    highlightLeadRow: Boolean = true,
    showLeadingAction: Boolean = false,
    leadingActionEmphasis: Float = 1f,
    onLeadingActionClick: (() -> Unit)? = null,
    onValueClick: (() -> Unit)? = null,
    onDeleteReading: ((GlucosePoint) -> Unit)? = null,
    isGroupStart: Boolean = index == 0,
    isGroupEnd: Boolean = index == totalCount - 1,
    dividerHorizontalInset: androidx.compose.ui.unit.Dp = 16.dp,
    modifier: Modifier = Modifier
) {
    // --- DYNAMIC COLOR LOGIC ---
    // User Request:
    // 1. Fresh (0-60s): Active Color -> Stock Color.
    // 2. Normal (60-90s): Stock Color.
    // 3. Stale (>90s): Stock Color -> Darker Shade.

    val isActive = highlightLeadRow && index == 0
    val view = LocalView.current
    var showDeleteDialog by rememberSaveable(point.timestamp, point.sensorSerial) { mutableStateOf(false) }
    val openDeleteDialog = {
        if (onDeleteReading != null) {
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            showDeleteDialog = true
        }
    }

    val activeColor = MaterialTheme.colorScheme.secondaryContainer
    val stockColor = MaterialTheme.colorScheme.surfaceContainerLow
    val staleColor = MaterialTheme.colorScheme.surfaceContainerHighest // Slightly darker/different

    val ageState = remember(point.timestamp) { mutableStateOf(System.currentTimeMillis() - point.timestamp) }
    // Timer to update age for the active item
    if (isActive) {
        LaunchedEffect(point.timestamp) {
            while (true) {
                ageState.value = System.currentTimeMillis() - point.timestamp
                delay(1000) // Update every second
            }
        }
    }

    val containerColor = if (isActive) {
        val age = ageState.value
        when {
            age < 60_000 -> {
                // Phase 1: Fade to Stock
                val progress = age / 60_000f
                androidx.compose.ui.graphics.Color(
                    androidx.core.graphics.ColorUtils.blendARGB(
                        activeColor.toArgb(),
                        stockColor.toArgb(),
                        progress.coerceIn(0f, 1f)
                    )
                )
            }
            age < 90_000 -> {
                // Phase 2: Stock (Stable)
                stockColor
            }
            else -> {
                 // Phase 3: Stale (Darken slowly)
                 // "slowly start getting different darker shade"
                 // Let's cap the darkening at 100% after another 60s (just to have a bound)
                 val staleProgress = ((age - 90_000) / 60_000f).coerceIn(0f, 1f)
                 androidx.compose.ui.graphics.Color(
                    androidx.core.graphics.ColorUtils.blendARGB(
                        stockColor.toArgb(),
                        staleColor.toArgb(),
                        staleProgress
                    )
                )
            }
        }
    } else {
        // History: Always Stock
        stockColor
    }

    // Shape:
    // Active (Hero): "4dp bottom radius" to imply continuity. Top matches parent (16dp).
    // History: Rectangle (relies on parent clipping for its 4dp bottom).
    val shape = when {
        isActive && isGroupStart && isGroupEnd -> RoundedCornerShape(16.dp)
        isActive -> RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 8.dp, bottomEnd = 8.dp)
        isGroupStart && isGroupEnd -> RoundedCornerShape(16.dp)
        isGroupStart -> RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 0.dp, bottomEnd = 0.dp)
        isGroupEnd -> RoundedCornerShape(topStart = 0.dp, topEnd = 0.dp, bottomStart = 16.dp, bottomEnd = 16.dp)
        else -> RectangleShape
    }

    // Divider Alpha Logic
    val baseDividerAlpha = 0.1f // User Request: "perhaps make it 0.1f"
    val dividerAlpha = if (isActive) {
        val age = ageState.value
        when {
            age < 60_000 -> {
                // Fresh: Color is Distinct -> Stock. Divider: Hidden -> Visible.
                val progress = age / 60_000f
                baseDividerAlpha * progress.coerceIn(0f, 1f)
            }
            age < 90_000 -> {
                // Normal: Color is Stock. Divider: Visible.
                baseDividerAlpha
            }
            else -> {
                // Stale: Color is Stock -> Distinct. Divider: Visible -> Hidden.
                val staleProgress = ((age - 90_000) / 60_000f).coerceIn(0f, 1f)
                baseDividerAlpha * (1f - staleProgress)
            }
        }
    } else {
        baseDividerAlpha
    }

    // --- ADVANCED TREND ENGINE ---
    // Calculate on the fly using the passed history subset
    val trendResult = remember(history, index) {
        val relevantHistory = if (history.isNotEmpty()) history.drop(index) else listOf(point)
        val nativeList = relevantHistory.map { tk.glucodata.GlucosePoint(it.timestamp, it.value, it.rawValue) }
        tk.glucodata.logic.TrendEngine.calculateTrend(nativeList, useRaw = (viewMode == 1 || viewMode == 3), isMmol = tk.glucodata.ui.util.GlucoseFormatter.isMmol(unit))
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .then(
                when {
                    onDeleteReading != null && onValueClick != null -> Modifier.combinedClickable(
                        onClick = onValueClick,
                        onLongClick = { openDeleteDialog() },
                        hapticFeedbackEnabled = false
                    )
                    onDeleteReading != null -> Modifier.pointerInput(point.timestamp, point.sensorSerial) {
                        detectTapGestures(onLongPress = { openDeleteDialog() })
                    }
                    onValueClick != null -> Modifier.clickable(onClick = onValueClick)
                    else -> Modifier
                }
            ),
        shape = shape,
        color = containerColor,
        // User Request: Kill shadows (0dp)
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            val hasInlineJournalEntries = journalEntries.isNotEmpty() && onJournalEntryClick != null
            val useClassicLayout = !hasInlineJournalEntries
            val rowMinHeight = 48.dp
            val valueMinWidth = if (showLeadingAction || hasInlineJournalEntries) 112.dp else 132.dp
            val timeStyle = MaterialTheme.typography.bodySmall
            val timeColor = if (isActive) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
            val timeWeight = if (isActive) FontWeight.Bold else FontWeight.Normal
            val isRawModeRR = viewMode == 1 || viewMode == 3
            val calibrationSensorId = sensorId?.takeIf { it.isNotBlank() }
            val hasCalibrationRR = tk.glucodata.data.calibration.CalibrationManager.hasActiveCalibration(
                isRawModeRR,
                calibrationSensorId
            )
            val calibratedValueRR = if (hasCalibrationRR) {
                val baseValue = if (isRawModeRR) point.rawValue else point.value
                if (baseValue.isFinite() && baseValue > 0.1f) {
                    tk.glucodata.data.calibration.CalibrationManager.getCalibratedValue(
                        baseValue,
                        point.timestamp,
                        isRawModeRR,
                        sensorIdOverride = calibrationSensorId
                    )
                } else {
                    null
                }
            } else null
            val dvs = getDisplayValues(point, viewMode, unit, calibratedValueRR)
            val primaryColor = if (isActive) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
            val secondaryColor = if (isActive) MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
            val unitColor = secondaryColor.copy(alpha = 0.6f)
            val tertiaryColor = if (isActive) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            val valueStyle = MaterialTheme.typography.titleMedium

            @Composable
            fun ReadingValueContent(modifier: Modifier = Modifier) {
                Row(
                    modifier = modifier,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (tk.glucodata.data.calibration.CalibrationManager.hasCalibrationAt(point.timestamp, isRawModeRR)) {
                        Icon(
                            imageVector = Icons.Filled.WaterDrop,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                    }

                    Text(
                        text = buildGlucoseString(dvs, primaryColor, secondaryColor, unitColor, true, "", tertiaryColor),
                        style = valueStyle.copy(fontFeatureSettings = "tnum")
                    )

                    Spacer(modifier = Modifier.width(8.dp))

                    tk.glucodata.ui.components.TrendIndicator(
                        trendResult = trendResult,
                        color = tertiaryColor,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            @Composable
            fun JournalAddAffordance(modifier: Modifier = Modifier) {
                if (showLeadingAction && onLeadingActionClick != null) {
                    Surface(
                        onClick = onLeadingActionClick,
                        modifier = modifier
                            .size(24.dp)
                            .alpha(leadingActionEmphasis.coerceIn(0f, 1f)),
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(
                            alpha = 0.38f + (0.18f * leadingActionEmphasis.coerceIn(0f, 1f))
                        ),
                        tonalElevation = 0.dp,
                        shadowElevation = 0.dp
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = null,
                                modifier = Modifier.size(13.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                                    alpha = 0.54f + (0.34f * leadingActionEmphasis.coerceIn(0f, 1f))
                                )
                            )
                        }
                    }
                }
            }

            if (useClassicLayout) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = java.text.SimpleDateFormat(
                            "HH:mm",
                            java.util.Locale.getDefault()
                        ).format(java.util.Date(point.timestamp)),
                        style = timeStyle,
                        fontWeight = timeWeight,
                        color = timeColor
                    )

                    if (showLeadingAction) {
                        Spacer(modifier = Modifier.width(8.dp))
                        JournalAddAffordance()
                    }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .defaultMinSize(minWidth = valueMinWidth),
                        contentAlignment = Alignment.CenterEnd
                    ) {
                        ReadingValueContent(modifier = Modifier.padding(start = 12.dp))
                    }
                }
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = rowMinHeight)
                        .padding(start = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = java.text.SimpleDateFormat(
                                "HH:mm",
                                java.util.Locale.getDefault()
                            ).format(java.util.Date(point.timestamp)),
                            style = timeStyle,
                            fontWeight = timeWeight,
                            color = timeColor
                        )

                        JournalAddAffordance()
                    }

                    Spacer(modifier = Modifier.width(if (showLeadingAction) 10.dp else 12.dp))

                    if (hasInlineJournalEntries) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .defaultMinSize(minHeight = rowMinHeight)
                                .padding(end = 12.dp),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            FlowRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                journalEntries.forEach { entry ->
                                    JournalInlineChip(
                                        entry = entry,
                                        unit = unit,
                                        insulinPreset = entry.insulinPresetId?.let(journalPresetsById::get),
                                        expanded = journalChipExpanded,
                                        onClick = { onJournalEntryClick?.invoke(entry) }
                                    )
                                }
                            }
                        }
                    } else {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .defaultMinSize(minHeight = rowMinHeight),
                            contentAlignment = Alignment.CenterEnd
                        ) {
                            ReadingValueContent(
                                modifier = Modifier.padding(start = 12.dp, end = 16.dp)
                            )
                        }
                    }

                    if (hasInlineJournalEntries) {
                        Box(
                            modifier = Modifier
                                .defaultMinSize(minWidth = valueMinWidth, minHeight = rowMinHeight),
                            contentAlignment = Alignment.CenterEnd
                        ) {
                            ReadingValueContent(
                                modifier = Modifier.padding(start = 12.dp, end = 16.dp)
                            )
                        }
                    }
                }
            }

            if (index < totalCount - 1) {
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = dividerHorizontalInset),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = dividerAlpha),
                    thickness = 1.dp
                )
            }
        }
    }

    if (showDeleteDialog && onDeleteReading != null) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text(text = stringResource(R.string.delete_reading_title)) },
            text = { Text(text = stringResource(R.string.delete_reading_confirmation)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteReading(point)
                        showDeleteDialog = false
                    }
                ) {
                    Text(text = stringResource(R.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text(text = stringResource(R.string.cancel))
                }
            }
        )
    }
}

@Composable
fun JournalTimelineRow(
    timestamp: Long,
    unit: String,
    journalEntries: List<JournalEntry>,
    journalPresetsById: Map<Long, JournalInsulinPreset> = emptyMap(),
    onJournalEntryClick: ((JournalEntry) -> Unit)? = null,
    onAddJournalEntry: (() -> Unit)? = null,
    index: Int = 0,
    totalCount: Int = 1,
    dividerHorizontalInset: androidx.compose.ui.unit.Dp = 16.dp,
    modifier: Modifier = Modifier
) {
    val shape = when {
        totalCount <= 1 -> RoundedCornerShape(16.dp)
        index == 0 -> RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
        index == totalCount - 1 -> RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp)
        else -> RectangleShape
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
                        .format(java.util.Date(timestamp)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (onAddJournalEntry != null) {
                    Spacer(modifier = Modifier.width(10.dp))
                    Surface(
                        onClick = onAddJournalEntry,
                        modifier = Modifier
                            .size(28.dp)
                            .alpha(0.42f),
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.48f),
                        tonalElevation = 0.dp,
                        shadowElevation = 0.dp
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.82f)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                Box(
                    modifier = Modifier.weight(1f),
                    contentAlignment = Alignment.CenterStart
                ) {
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        journalEntries.forEach { entry ->
                            JournalInlineChip(
                                entry = entry,
                                unit = unit,
                                insulinPreset = entry.insulinPresetId?.let(journalPresetsById::get),
                                expanded = true,
                                onClick = { onJournalEntryClick?.invoke(entry) }
                            )
                        }
                    }
                }
            }

            if (index < totalCount - 1) {
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = dividerHorizontalInset),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.1f),
                    thickness = 1.dp
                )
            }
        }
    }
}

@Composable
private fun DashboardJournalFloatingMenu(
    visible: Boolean,
    selectedTimestamp: Long,
    viewportSnapshot: ChartViewportSnapshot?,
    onTypeSelected: (JournalEntryType) -> Unit
) {
    val view = LocalView.current
    val actionTypes = remember {
        listOf(
            JournalEntryType.INSULIN,
            JournalEntryType.CARBS,
            JournalEntryType.FINGERSTICK,
            JournalEntryType.ACTIVITY,
            JournalEntryType.NOTE
        )
    }
    val anchorFraction = remember(selectedTimestamp, viewportSnapshot) {
        viewportSnapshot
            ?.takeIf { it.endMillis > it.startMillis }
            ?.let { snapshot ->
                ((selectedTimestamp - snapshot.startMillis).toFloat() /
                    (snapshot.endMillis - snapshot.startMillis).toFloat()).coerceIn(0f, 1f)
            }
    }
    val menuReveal = remember { Animatable(0f) }
    LaunchedEffect(visible, selectedTimestamp, anchorFraction) {
        if (visible && anchorFraction != null) {
            menuReveal.snapTo(0f)
            menuReveal.animateTo(
                targetValue = 1f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioLowBouncy,
                    stiffness = Spring.StiffnessLow
                )
            )
        } else {
            menuReveal.animateTo(
                targetValue = 0f,
                animationSpec = tween(durationMillis = 120)
            )
        }
    }
    val menuProgress = menuReveal.value
    val menuScale = 0.82f + (0.18f * menuProgress)

    if (anchorFraction != null && (visible || menuProgress > 0.01f)) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    alpha = menuProgress.coerceIn(0f, 1f)
                }
        ) {
            val density = LocalDensity.current
            val resolvedAnchorFraction = anchorFraction ?: return@BoxWithConstraints
            val containerWidthPx = with(density) { maxWidth.toPx() }
            val containerHeightPx = with(density) { maxHeight.toPx() }
            val menuWidthPx = with(density) { 176.dp.toPx() }
            val edgePaddingPx = with(density) { 12.dp.toPx() }
            val anchorGapPx = with(density) { 14.dp.toPx() }
            val menuTopPx = with(density) { 86.dp.toPx() }
            val rowTravelPx = with(density) { 18.dp.toPx() }
            val itemLiftPx = with(density) { 16.dp.toPx() }
            val anchorX = containerWidthPx * resolvedAnchorFraction
            val placeMenuLeft = resolvedAnchorFraction > 0.56f
            val desiredX = if (placeMenuLeft) {
                anchorX - menuWidthPx - anchorGapPx
            } else {
                anchorX + anchorGapPx
            }
            val clampedX = desiredX.coerceIn(
                edgePaddingPx,
                (containerWidthPx - menuWidthPx - edgePaddingPx).coerceAtLeast(edgePaddingPx)
            )
            val clampedY = menuTopPx.coerceIn(
                edgePaddingPx,
                (containerHeightPx - with(density) { 248.dp.toPx() }).coerceAtLeast(edgePaddingPx)
            )

            Column(
                modifier = Modifier
                    .offset {
                        androidx.compose.ui.unit.IntOffset(
                            x = clampedX.roundToInt(),
                            y = clampedY.roundToInt()
                        )
                    }
                    .graphicsLayer {
                        alpha = menuProgress
                        scaleX = menuScale
                        scaleY = menuScale
                        translationY = (12.dp.toPx() * (1f - menuProgress))
                    }
                    .width(176.dp),
                horizontalAlignment = if (placeMenuLeft) Alignment.End else Alignment.Start,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                actionTypes.forEachIndexed { index, actionType ->
                    val itemProgress = ((menuProgress - (index * 0.08f)) / 0.92f).coerceIn(0f, 1f)
                    val label = stringResource(actionType.dashboardLabelRes())
                    val actionTint = journalTypeColor(actionType)
                    val iconContainerColor = journalTypeSelectedContainerColor(actionType)
                    val labelContainerColor = journalTypeSubtleContainerColor(actionType)
                    Row(
                        modifier = Modifier
                            .wrapContentWidth(if (placeMenuLeft) Alignment.End else Alignment.Start)
                            .graphicsLayer {
                                alpha = itemProgress
                                translationX = (if (placeMenuLeft) rowTravelPx else -rowTravelPx) * (1f - itemProgress)
                                translationY = itemLiftPx * (1f - itemProgress)
                                scaleX = 0.78f + (0.22f * itemProgress)
                                scaleY = 0.78f + (0.22f * itemProgress)
                                rotationZ = (if (placeMenuLeft) -7f else 7f) * (1f - itemProgress)
                            },
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (!placeMenuLeft) {
                            SmallFloatingActionButton(
                                onClick = {
                                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                    onTypeSelected(actionType)
                                },
                                shape = CircleShape,
                                containerColor = iconContainerColor,
                                contentColor = actionTint,
                                elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 0.dp)
                            ) {
                                Icon(
                                    imageVector = actionType.dashboardIcon(),
                                    contentDescription = label,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }

                        Surface(
                            modifier = Modifier.clickable {
                                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                onTypeSelected(actionType)
                            },
                            shape = RoundedCornerShape(18.dp),
                            color = labelContainerColor,
                            tonalElevation = 0.dp,
                            shadowElevation = 0.dp
                        ) {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                            )
                        }

                        if (placeMenuLeft) {
                            SmallFloatingActionButton(
                                onClick = {
                                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                    onTypeSelected(actionType)
                                },
                                shape = CircleShape,
                                containerColor = iconContainerColor,
                                contentColor = actionTint,
                                elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 0.dp)
                            ) {
                                Icon(
                                    imageVector = actionType.dashboardIcon(),
                                    contentDescription = label,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun JournalEntryType.dashboardLabelRes(): Int = when (this) {
    JournalEntryType.INSULIN -> R.string.journal_type_insulin
    JournalEntryType.CARBS -> R.string.carbo
    JournalEntryType.FINGERSTICK -> R.string.journal_type_bg_short
    JournalEntryType.ACTIVITY -> R.string.journal_type_activity
    JournalEntryType.NOTE -> R.string.journal_type_note
}

private fun JournalEntryType.dashboardIcon(): ImageVector = when (this) {
    JournalEntryType.INSULIN -> Icons.Default.Vaccines
    JournalEntryType.CARBS -> Icons.Default.Restaurant
    JournalEntryType.FINGERSTICK -> Icons.Default.Bloodtype
    JournalEntryType.ACTIVITY -> Icons.Default.DirectionsRun
    JournalEntryType.NOTE -> Icons.Filled.Label
}

/*
    // --- PREVIOUS IMPLEMENTATION (Commented as requested) ---
    val backgroundColor = lerp(
        start = MaterialTheme.colorScheme.surfaceVariant,
        stop = MaterialTheme.colorScheme.primaryContainer,
        fraction = highlightAlpha.value
    )

    // Determine if we need to show date (for old data, not from today)
    val timeDisplay = remember(point.timestamp) {
        val now = System.currentTimeMillis()
        val todayStart = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis
        
        val isFromToday = point.timestamp >= todayStart
        
        if (isFromToday) {
            // Fresh data: just time "HH:mm"
            point.time
        } else {
            // Old data: show date + time "EEE dd HH:mm"
            val dateFormat = java.text.SimpleDateFormat("EEE dd HH:mm", java.util.Locale.getDefault())
            dateFormat.format(java.util.Date(point.timestamp))
        }
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp) // Minimum touch target / visual rhythm
            .padding(horizontal = 16.dp, vertical = 16.dp), // Increased vertical padding to 16dp for "Editorial" feel
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically // Center content vertically
    ) {
        // Time Column (Secondary Hierarchy)
        Text(
            text = timeDisplay,
            style = MaterialTheme.typography.bodySmall, // Receded (12sp)
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )

        // Value Column (Primary Hierarchy)
        val dvs = getDisplayValues(point, viewMode, unit)
        val primaryColor = MaterialTheme.colorScheme.onSurface
        val secondaryColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 1.0f)
        val unitColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)

        // Bold & Prominent
        Text(
            text = buildGlucoseString(dvs, primaryColor, secondaryColor, unitColor, true, unit),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = primaryColor
        )
    }
*/


@Composable
fun SearchScreen() {
    Column(modifier = Modifier.padding(16.dp)) {
        Text(stringResource(R.string.search), style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(16.dp))
        OutlinedTextField(
            value = "",
            onValueChange = {},
            label = { Text(stringResource(R.string.filter_by_date)) },
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
fun SettingsScreen(navController: androidx.navigation.NavController, themeMode: ThemeMode, onThemeChanged: (ThemeMode) -> Unit, viewModel: DashboardViewModel = viewModel()) {
    val context = LocalContext.current
    val unit by viewModel.unit.collectAsState()
    val isMmol = unit == "mmol/L"
    val uriHandler = LocalUriHandler.current

    val xDripEnabled by viewModel.xDripBroadcastEnabled.collectAsState()
    val patchedLibreEnabled by viewModel.patchedLibreBroadcastEnabled.collectAsState()

    var showUnitDialog by remember { mutableStateOf(false) }
    var showThemeDialog by remember { mutableStateOf(false) }

    val notificationChartEnabled by viewModel.notificationChartEnabled.collectAsState()

    // Alarms
    val hasLowAlarm by viewModel.hasLowAlarm.collectAsState()
    val lowAlarmValue by viewModel.lowAlarmThreshold.collectAsState()
    val lowAlarmSoundMode by viewModel.lowAlarmSoundMode.collectAsState()

    val hasHighAlarm by viewModel.hasHighAlarm.collectAsState()
    val highAlarmValue by viewModel.highAlarmThreshold.collectAsState()
    val highAlarmSoundMode by viewModel.highAlarmSoundMode.collectAsState()

    // Targets
    val targetLowValue by viewModel.targetLow.collectAsState()
    val targetHighValue by viewModel.targetHigh.collectAsState()

    // --- GRAPH RANGE STATE (Connect these to your ViewModel!) ---
    // Example: val graphMinVal by viewModel.graphMin.collectAsState()
    var graphMinVal by remember { mutableFloatStateOf(if (isMmol) 0f else 0f) }
    var graphMaxVal by remember { mutableFloatStateOf(if (isMmol) 25f else 400f) }

    // Scroll State for the Fix
    val scrollState = rememberScrollState()

    Scaffold(
        contentWindowInsets = WindowInsets.statusBars // Ensure status bar padding
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(scrollState)
        ) {
        var showLanguageDialog by remember { mutableStateOf(false) }

        Text(stringResource(R.string.settings), style = MaterialTheme.typography.headlineLarge, modifier = Modifier.padding(16.dp))
        HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
        Spacer(modifier = Modifier.padding(horizontal = 16.dp))

        // --- UNIT ---
        ListItem(
            headlineContent = { Text(stringResource(R.string.unit)) },
            supportingContent = { Text(unit) },
            modifier = Modifier.clickable { showUnitDialog = true }
        )
        if (showUnitDialog) {
            AlertDialog(
                onDismissRequest = { showUnitDialog = false },
                text = {
                    Column(modifier = Modifier.padding(vertical = 16.dp)) {
                        Text(
                            "Select Unit",
                            style = MaterialTheme.typography.headlineSmall,
                            modifier = Modifier.padding(bottom = 16.dp)
                        )
                        listOf("mg/dL" to 0, "mmol/L" to 1).forEach { (label, value) ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        viewModel.setUnit(value)
                                        showUnitDialog = false
                                        context.findActivity()?.hardRestart()
                                    }
                                    .padding(vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(selected = (if (isMmol) 1 else 0) == value, onClick = null)
                                Text(
                                    text = label,
                                    modifier = Modifier.padding(start = 12.dp),
                                    style = MaterialTheme.typography.bodyLarge
                                )
                            }
                        }
                    }
                },
                confirmButton = {},
                dismissButton = {
                    TextButton(onClick = { showUnitDialog = false }) {
                        Text(stringResource(R.string.cancel))
                    }
                }
            )
        }

        // --- THEME ---
        val themeLabel = when(themeMode) {
            ThemeMode.SYSTEM -> stringResource(R.string.theme_system)
            ThemeMode.LIGHT -> stringResource(R.string.theme_light)
            ThemeMode.DARK -> stringResource(R.string.theme_dark)
        }

        ListItem(
            headlineContent = { Text(stringResource(R.string.theme_title)) },
            supportingContent = { Text(themeLabel) },
            modifier = Modifier.clickable { showThemeDialog = true }
        )
        if (showThemeDialog) {
            AlertDialog(
                onDismissRequest = { showThemeDialog = false },
                text = {
                    Column(modifier = Modifier.padding(vertical = 16.dp)) {
                        Text(
                            "Choose theme",
                            style = MaterialTheme.typography.headlineSmall,
                            modifier = Modifier.padding(bottom = 16.dp)
                        )
                        ThemeMode.values().forEach { mode ->
                            val label = when(mode) {
                                ThemeMode.SYSTEM -> stringResource(R.string.theme_system)
                                ThemeMode.LIGHT -> stringResource(R.string.theme_light)
                                ThemeMode.DARK -> stringResource(R.string.theme_dark)
                            }
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        onThemeChanged(mode)
                                        showThemeDialog = false
                                    }
                                    .padding(vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(selected = themeMode == mode, onClick = null)
                                Text(
                                    text = label,
                                    modifier = Modifier.padding(start = 12.dp),
                                    style = MaterialTheme.typography.bodyLarge
                                )
                            }
                        }
                    }
                },
                confirmButton = {},
                dismissButton = {
                    TextButton(onClick = { showThemeDialog = false }) {
                        Text(stringResource(R.string.cancel))
                    }
                }
            )
        }

        // --- LANGUAGE ---
        // Supported Languages: be, de, fr, it, nl, pl, pt, ru, sv, tr, uk, zh, en
        val currentLocale = AppCompatDelegate.getApplicationLocales().get(0) ?: Locale.getDefault()
        val currentLangName = currentLocale.displayLanguage.capitalize()

        ListItem(
            headlineContent = { Text(stringResource(R.string.languagename)) },
            supportingContent = { Text(currentLangName) },
            modifier = Modifier.clickable { showLanguageDialog = true }
        )

        if (showLanguageDialog) {
            val languages = listOf(
                stringResource(R.string.system_default) to null,
                "English" to "en",
                "Belarusian" to "be",
                "Chinese" to "zh",
                "German" to "de",
                "French" to "fr",
                "Italian" to "it",
                "Dutch" to "nl",
                "Polish" to "pl",
                "Portuguese" to "pt",
                "Russian" to "ru",
                "Swedish" to "sv",
                "Turkish" to "tr",
                "Ukrainian" to "uk"
            )

            AlertDialog(
                onDismissRequest = { showLanguageDialog = false },
                title = { Text(stringResource(R.string.select_language)) },
                text = {
                    LazyColumn {
                        items(languages) { (name, tag) ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        val appLocale = if (tag != null) LocaleListCompat.forLanguageTags(tag) else LocaleListCompat.getEmptyLocaleList()
                                        AppCompatDelegate.setApplicationLocales(appLocale)
                                        showLanguageDialog = false
                                    }
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(text = name)
                                if ((tag == null && AppCompatDelegate.getApplicationLocales().isEmpty) ||
                                    (tag != null && AppCompatDelegate.getApplicationLocales().toLanguageTags().contains(tag))) {
                                    Spacer(Modifier.weight(1f))
                                    Icon(Icons.Default.Check, contentDescription = "Selected")
                                }
                            }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { showLanguageDialog = false }) { Text(stringResource(R.string.cancel)) } }
            )
        }


        // --- NOTIFICATION CHART ---
        ListItem(
            headlineContent = { Text(stringResource(R.string.notification_chart_title)) },
            supportingContent = { Text(stringResource(R.string.notification_chart_desc)) },
            trailingContent = {
                StyledSwitch(
                    checked = notificationChartEnabled,
                    onCheckedChange = { viewModel.toggleNotificationChart(it) }
                )
            }
        )



        // M3 Grid: 24dp between major sections
        Spacer(modifier = Modifier.height(24.dp))

        Text(stringResource(R.string.exchanges), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 16.dp))
        Spacer(modifier = Modifier.height(16.dp))

        ListItem(
            headlineContent = { Text(stringResource(R.string.xdripbroadcast)) },
            supportingContent = { Text(stringResource(R.string.patchedlibrebroadcast)) },
            trailingContent = {
                StyledSwitch(
                    checked = patchedLibreEnabled,
                    onCheckedChange = { viewModel.togglePatchedLibreBroadcast(it) }
                )
            }
        )

        ListItem(
            headlineContent = { Text(stringResource(R.string.mirror)) },
            supportingContent = { Text(stringResource(R.string.mirror_desc)) },
            modifier = Modifier.clickable { navController.navigate("settings/mirror") }
        )


        ListItem(
            headlineContent = { Text(stringResource(R.string.nightscout_config)) },
            supportingContent = { Text(stringResource(R.string.nightscout_desc)) },
            modifier = Modifier.clickable { navController.navigate("settings/nightscout") }
        )

        // Edit 67b: Only show LibreView settings when enabled or an account ID exists
        if (Natives.getuselibreview() || Natives.getlibreAccountIDnumber() > 0L) {
            ListItem(
                headlineContent = { Text(stringResource(R.string.libreview_config)) },
                supportingContent = { Text(stringResource(R.string.libreview_desc)) },
                modifier = Modifier.clickable { navController.navigate("settings/libreview") }
            )
        }



        // M3 Grid: 24dp between major sections
        Spacer(modifier = Modifier.height(24.dp))

        Text(stringResource(R.string.glucose_alerts_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 16.dp))
        Spacer(modifier = Modifier.height(16.dp))

        AlarmCard(
            title = stringResource(R.string.lowglucosealarm), // Or R.string.lowglucose
            enabled = hasLowAlarm,
            value = lowAlarmValue,
            unit = unit,
            soundMode = lowAlarmSoundMode,
            range = if (isMmol) 2.0f..6.0f else 36f..108f,
            onToggle = { viewModel.setLowAlarm(it, lowAlarmValue) },
            onValueChange = { viewModel.setLowAlarm(true, it) },
            onSoundChange = { viewModel.setAlarmSound(0, it) },
            modifier = Modifier.padding(horizontal = 16.dp)
        )

        Spacer(modifier = Modifier.height(16.dp))

        AlarmCard(
            title = stringResource(R.string.highglucosealarm), // Or R.string.highglucose
            enabled = hasHighAlarm,
            value = highAlarmValue,
            unit = unit,
            soundMode = highAlarmSoundMode,
            range = if (isMmol) 6.0f..25.0f else 108f..450f,
            onToggle = { viewModel.setHighAlarm(it, highAlarmValue) },
            onValueChange = { viewModel.setHighAlarm(true, it) },
            onSoundChange = { viewModel.setAlarmSound(1, it) },
            modifier = Modifier.padding(horizontal = 16.dp)
        )

        // M3 Grid: 24dp between major sections
        Spacer(modifier = Modifier.height(24.dp))

        Text(stringResource(R.string.target_range_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 16.dp)
        )
        Spacer(modifier = Modifier.height(16.dp))

        TargetCard(
            title = stringResource(R.string.low_label),
            value = targetLowValue,
            range = if (isMmol) 2.0f..8.0f else 40f..140f,
            onValueChange = { viewModel.setTargetLow(it) },
            modifier = Modifier.padding(horizontal = 16.dp)

        )
        Spacer(modifier = Modifier.height(16.dp))

        TargetCard(
            title = stringResource(R.string.high_label),
            value = targetHighValue,
            range = if (isMmol) 6.0f..16.0f else 100f..350f,
            onValueChange = { viewModel.setTargetHigh(it) },
            modifier = Modifier.padding(horizontal = 16.dp)

        )

//        HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
//
//        // --- GRAPH BOUNDS (New Section) ---
//        Text("Graph Y-Axis Scale", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(vertical = 8.dp))
//
//        // Reusing TargetCard logic for consistency
//        TargetCard(
//            title = "Graph Min (Bottom)",
//            value = graphMinVal,
//            unit = unit,
//            range = if (isMmol) 0f..10f else 0f..180f,
//            onValueChange = {
//                graphMinVal = it
//                // TODO: viewModel.setGraphMin(it)
//            }
//        )
//        Spacer(modifier = Modifier.height(16.dp))
//
//        TargetCard(
//            title = "Graph Max (Top)",
//            value = graphMaxVal,
//            unit = unit,
//            range = if (isMmol) 10f..40f else 180f..600f,
//            onValueChange = {
//                graphMaxVal = it
//                // TODO: viewModel.setGraphMax(it)
//            }
//        )

        // M3 Grid: 24dp between major sections
        Spacer(modifier = Modifier.height(24.dp))

        Text(stringResource(R.string.advanced_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 16.dp))
        Spacer(modifier = Modifier.height(16.dp))

        // Turbo (High Priority)
        ListItem(
            headlineContent = { Text(stringResource(R.string.turbo_title)) },
            supportingContent = { Text(stringResource(R.string.turbo_desc)) },
            trailingContent = {
                var turbo by remember { mutableStateOf(Natives.getpriority()) }
                StyledSwitch(
                    checked = turbo,
                    onCheckedChange = {
                        Natives.setpriority(it)
                        turbo = it
                    }
                )
            }
        )

        // Android (AutoConnect)
        ListItem(
            headlineContent = { Text(stringResource(R.string.autoconnect_title)) },
            supportingContent = { Text(stringResource(R.string.autoconnect_desc)) },
            trailingContent = {
                var autoConnect by remember { mutableStateOf(Natives.getAndroid13()) }
                StyledSwitch(
                    checked = autoConnect,
                    onCheckedChange = {
                        SensorBluetooth.setAutoconnect(it)
                        autoConnect = it
                    }
                )
            }
        )

        ListItem(
            headlineContent = { Text(stringResource(R.string.debug_logs_title)) },
            supportingContent = { Text(stringResource(R.string.debug_logs_desc)) },
            modifier = Modifier.clickable { navController.navigate("settings/debug") }
        )


        // M3 Grid: 24dp between major sections
        Spacer(modifier = Modifier.height(24.dp))

        //--- DATA MANAGEMENT SECTION ---
        Text(
            text = "Data Management",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        // M3 Grid: 16dp spacing after section header
        Spacer(modifier = Modifier.height(16.dp))

        val coroutineScope = rememberCoroutineScope()
        var showExportDialog by remember { mutableStateOf(false) }
        var exportType by remember { mutableStateOf("csv") }

        // EXPORT LAUNCHER
        val exportLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
            contract = androidx.activity.result.contract.ActivityResultContracts.CreateDocument("*/*")
        ) { uri ->
            if (uri != null) {
                coroutineScope.launch {
                    val format = exportType
                    val unitStr = if (isMmol) "mmol/L" else "mg/dL"
                    val data = tk.glucodata.data.HistoryRepository.getHistoryBlocking(0L, isMmol)

                    val success = if (format == "csv") {
                        tk.glucodata.data.HistoryExporter.exportToCsv(context, uri, data, unitStr)
                    } else {
                        tk.glucodata.data.HistoryExporter.exportToReadable(context, uri, data, unitStr)
                    }

                    Toast.makeText(
                        context,
                        if (success) context.getString(R.string.export_successful) else context.getString(R.string.export_failed),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }

        // IMPORT LAUNCHER
        val importLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
            contract = androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
        ) { uri ->
            if (uri != null) {
                coroutineScope.launch {
                    val result = tk.glucodata.data.HistoryExporter.importFromCsv(context, uri)
                    if (result.success) {
                        Toast.makeText(
                            context,
                            context.getString(R.string.imported_readings_count, result.successCount),
                            Toast.LENGTH_LONG
                        ).show()
                        viewModel.refreshData()
                    } else {
                        Toast.makeText(
                            context,
                            context.getString(R.string.import_failed_with_error, result.errorMessage ?: ""),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }

        // Export Format Dialog
        if (showExportDialog) {
            AlertDialog(
                onDismissRequest = { showExportDialog = false },
                title = { Text(stringResource(R.string.select_export_format)) },
                text = {
                    Column {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { exportType = "csv" }
                                .padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = exportType == "csv", onClick = null)
                            Column(Modifier.padding(start = 8.dp)) {
                                Text(stringResource(R.string.csv_file))
                                Text(stringResource(R.string.csv_desc), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { exportType = "readable" }
                                .padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = exportType == "readable", onClick = null)
                            Column(Modifier.padding(start = 8.dp)) {
                                Text(stringResource(R.string.csv_file))
                                Text(stringResource(R.string.csv_desc), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { exportType = "readable" }
                                .padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = exportType == "readable", onClick = null)
                            Column(Modifier.padding(start = 8.dp)) {
                                Text(stringResource(R.string.text_file))
                                Text(stringResource(R.string.text_desc), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        showExportDialog = false
                        val date = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
                        val ext = if (exportType == "csv") "csv" else "txt"
                        exportLauncher.launch("juggluco_history_$date.$ext")
                    }) { Text(stringResource(R.string.export_button)) }
                },
                dismissButton = { TextButton(onClick = { showExportDialog = false }) { Text(stringResource(R.string.cancel)) } }
            )
        }

        // Export History - M3 ListItem pattern (fully clickable)
        ListItem(
            headlineContent = { Text(stringResource(R.string.export_history_title)) },
            supportingContent = { Text(stringResource(R.string.export_history_sub)) },
            modifier = Modifier.clickable { showExportDialog = true },
            leadingContent = {
                Icon(
                    Icons.Default.Share,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        )

        // Import History - M3 ListItem pattern (fully clickable)
        ListItem(
            headlineContent = { Text(stringResource(R.string.import_history_title)) },
            supportingContent = { Text(stringResource(R.string.import_history_sub)) },
            modifier = Modifier.clickable { importLauncher.launch(arrayOf("text/csv", "text/plain", "*/*")) },
            leadingContent = {
                Icon(
                    Icons.Default.Download,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.secondary
                )
            }
        )

        // M3 Grid: 24dp before new subsection
        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = stringResource(R.string.danger_zone),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = 16.dp),
            color = MaterialTheme.colorScheme.error
        )
        // M3 Grid: 16dp after subsection header
        Spacer(modifier = Modifier.height(16.dp))
        var showClearHistoryDialog by remember { mutableStateOf(false) }
        var showClearDataDialog by remember { mutableStateOf(false) }
        var showFactoryResetDialog by remember { mutableStateOf(false) }
        var isClearing by remember { mutableStateOf(false) }

        // Clear History Card - Description + Action button pattern
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Row(
                modifier = Modifier.padding(16.dp).fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.History,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(16.dp))
                Text(
                    stringResource(R.string.clear_history_desc_short),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                OutlinedButton(
                    onClick = { showClearHistoryDialog = true },
                    enabled = !isClearing,
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    if (isClearing) CircularProgressIndicator(modifier = Modifier.size(16.dp))
                    else Text(stringResource(R.string.clear_history))
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Clear App Data Card - Description + Action button pattern
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
            )
        ) {
            Row(
                modifier = Modifier.padding(16.dp).fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(16.dp))
                Text(
                    stringResource(R.string.clear_app_data_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = { showClearDataDialog = true },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    ),
                    enabled = !isClearing,
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    if (isClearing) CircularProgressIndicator(modifier = Modifier.size(16.dp), color = MaterialTheme.colorScheme.onError)
                    else Text(stringResource(R.string.clear_app_data))
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Factory Reset Card - Description + Action button pattern
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer
            )
        ) {
            Row(
                modifier = Modifier.padding(16.dp).fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(16.dp))
                Text(
                    stringResource(R.string.factory_reset_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = { showFactoryResetDialog = true },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    ),
                    enabled = !isClearing,
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    if (isClearing) CircularProgressIndicator(modifier = Modifier.size(16.dp), color = MaterialTheme.colorScheme.onError)
                    else Text(stringResource(R.string.factory_reset))
                }
            }
        }

        // Confirmation Dialogs
        if (showClearHistoryDialog) {
            AlertDialog(
                onDismissRequest = { showClearHistoryDialog = false },
                icon = { Icon(Icons.Default.History, contentDescription = null) },
                title = { Text(stringResource(R.string.clear_history_title)) },
                text = { Text(stringResource(R.string.clear_history_confirmation)) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            isClearing = true
                            coroutineScope.launch {
                                tk.glucodata.data.DataManagement.clearHistory()
                                isClearing = false
                                showClearHistoryDialog = false
                            }
                        }
                    ) { Text(stringResource(R.string.clear_button)) }
                },
                dismissButton = { TextButton(onClick = { showClearHistoryDialog = false }) { Text(stringResource(R.string.cancel)) } }
            )
        }

        if (showClearDataDialog) {
            AlertDialog(
                onDismissRequest = { showClearDataDialog = false },
                icon = { Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                title = { Text(stringResource(R.string.clear_app_data_title)) },
                text = {
                    Column {
                        Text(stringResource(R.string.clear_app_data_intro))
                        Text(stringResource(R.string.all_glucose_readings))
                        Text(stringResource(R.string.cache_files))
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(stringResource(R.string.settings_preserved))
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(stringResource(R.string.cannot_be_undone_warning))
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            isClearing = true
                            coroutineScope.launch {
                                tk.glucodata.data.DataManagement.clearAppData()
                                isClearing = false
                                showClearDataDialog = false
                                (context as? Activity)?.finishAffinity()
                            }
                        },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) { Text(stringResource(R.string.clear_data_button)) }
                },
                dismissButton = { TextButton(onClick = { showClearDataDialog = false }) { Text(stringResource(R.string.cancel)) } }
            )
        }

        if (showFactoryResetDialog) {
            AlertDialog(
                onDismissRequest = { showFactoryResetDialog = false },
                icon = { Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                title = { Text(stringResource(R.string.factory_reset_title)) },
                text = {
                    Column {
                        Text(stringResource(R.string.factory_reset_intro))
                        Text(stringResource(R.string.all_glucose_readings))
                        Text(stringResource(R.string.all_settings))
                        Text(stringResource(R.string.cache_files))
                        Text(stringResource(R.string.sensor_data))
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(stringResource(R.string.factory_reset_outcome), style = MaterialTheme.typography.bodyMedium)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(stringResource(R.string.cannot_be_undone_critical), color = MaterialTheme.colorScheme.error)
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            isClearing = true
                            coroutineScope.launch {
                                tk.glucodata.data.DataManagement.factoryReset()
                                // Close app after factory reset
                                (context as? Activity)?.finishAffinity()
                            }
                        },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) { Text(stringResource(R.string.reset_everything_button)) }
                },
                dismissButton = { TextButton(onClick = { showFactoryResetDialog = false }) { Text(stringResource(R.string.cancel)) } }
            )
        }

        Spacer(modifier = Modifier.height(32.dp))

        // --- ABOUT SECTION ---
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // App Name & Logo
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Using generic icon to avoid build errors if R.mipmap.ic_launcher is missing
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "JugglucoNG",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Bold
                )
            }
            Text(
                text = "github.com/ctqwa/JugglucoNG",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textDecoration = TextDecoration.Underline,
                modifier = Modifier.clickable {
                    uriHandler.openUri("https://github.com/ctqwa/JugglucoNG")
                }
            )
            Text(
                text = "• GPL-3.0 •",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "A fork of Juggluco https://github.com/j-kaltes/Juggluco/",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )


        }

        Spacer(modifier = Modifier.height(80.dp))
    }

    // Add bottom padding so the last item isn't covered by navigation bar
}
}




@Composable
fun TargetCard(
    title: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            var sliderVal by remember(value) { mutableStateOf(value.coerceIn(range)) }

            Column {
                Text(title, style = MaterialTheme.typography.titleMedium)
                val currentSliderValStr = if (sliderVal < 30) String.format("%.1f", sliderVal) else sliderVal.toInt().toString()
                Text(
                    text = currentSliderValStr,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }
            Slider(
                value = sliderVal,
                onValueChange = { sliderVal = it },
                onValueChangeFinished = { onValueChange(sliderVal) },
                valueRange = range,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}


@Composable
fun AlarmCard(
    title: String,
    enabled: Boolean,
    value: Float,
    unit: String,
    soundMode: Int,
    range: ClosedFloatingPointRange<Float>,
    onToggle: (Boolean) -> Unit,
    onValueChange: (Float) -> Unit,
    onSoundChange: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    val valStr = if (value < 30) String.format("%.1f", value) else value.toInt().toString()
//                    Text("Threshold: $valStr $unit", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                StyledSwitch(checked = enabled, onCheckedChange = onToggle)
            }

            if (enabled) {
                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(modifier = Modifier.height(16.dp))

                var sliderVal by remember(value) { mutableStateOf(value.coerceIn(range)) }

                // Display current slider value
                val currentSliderValStr = if (sliderVal < 30) String.format("%.1f", sliderVal) else sliderVal.toInt().toString()
                Text(
                    text = "$currentSliderValStr $unit",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                Slider(
                    value = sliderVal,
                    onValueChange = { sliderVal = it },
                    onValueChangeFinished = { onValueChange(sliderVal) },
                    valueRange = range,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(8.dp))
                Text(stringResource(R.string.alert_sound), style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = soundMode == 0,
                        onClick = { onSoundChange(0) },
                        label = { Text(stringResource(R.string.vibrate_only)) }
                    )
                    FilterChip(
                        selected = soundMode == 1,
                        onClick = { onSoundChange(1) },
                        label = { Text(stringResource(R.string.sound_vibrate)) }
                    )
                }
            }
        }
    }
    }


private fun String.capitalize(): String {
    return this.replaceFirstChar { if (it.isLowerCase()) it.titlecase(java.util.Locale.getDefault()) else it.toString() }
}

@Composable
fun SensorScreen(
    onNavigateToMqAccount: () -> Unit = {},
    onNavigateToReadiness: () -> Unit = {},
    viewModel: tk.glucodata.ui.viewmodel.SensorViewModel = viewModel(),
) {
    val context = LocalContext.current
    val sensors by viewModel.sensors.collectAsState()
    val adaptiveMetrics = rememberAdaptiveWindowMetrics()
    val compactLayout = adaptiveMetrics.isCompact
    val panelPadding = 16.dp
    val titleInset = 16.dp
    val panelTopGap = if (compactLayout) 10.dp else 16.dp
    val panelBottomPadding = if (compactLayout) 88.dp else 100.dp
    val titleStyle = if (compactLayout) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.displaySmall
    val titleBottomPadding = if (compactLayout) 16.dp else 24.dp
    val fabPadding = if (compactLayout) 12.dp else 16.dp
    
    // Start/stop real-time polling based on screen visibility
    DisposableEffect(Unit) {
        viewModel.startPolling() // Start 2-second refresh when screen visible
        onDispose {
            viewModel.stopPolling() // Stop when leaving screen
        }
    }
    
    // State for sensor type picker and wizards
    var showSensorPicker by remember { mutableStateOf(false) }
    var showSibionicsWizard by remember { mutableStateOf(false) }
    var showLibreWizard by remember { mutableStateOf(false) }
    var showDexcomWizard by remember { mutableStateOf(false) }
    var showAccuChekWizard by remember { mutableStateOf(false) }
    var showCareSensAirWizard by remember { mutableStateOf(false) }
    var showAiDexWizard by remember { mutableStateOf(false) }
    var showICanHealthWizard by remember { mutableStateOf(false) }
    var showMQWizard by remember { mutableStateOf(false) }

    // Sensor Type Picker Bottom Sheet
    if (showSensorPicker) {
        tk.glucodata.ui.components.SensorTypePicker(
            onDismiss = { showSensorPicker = false },
            onSensorSelected = { type ->
                showSensorPicker = false
                when (type) {
                    tk.glucodata.ui.components.SensorType.SIBIONICS -> showSibionicsWizard = true
                    tk.glucodata.ui.components.SensorType.LIBRE -> showLibreWizard = true
                    tk.glucodata.ui.components.SensorType.DEXCOM -> showDexcomWizard = true
                    tk.glucodata.ui.components.SensorType.ACCUCHEK -> showAccuChekWizard = true
                    tk.glucodata.ui.components.SensorType.CARESENS_AIR -> showCareSensAirWizard = true
                    tk.glucodata.ui.components.SensorType.AIDEX -> showAiDexWizard = true
                    tk.glucodata.ui.components.SensorType.ICANHEALTH -> showICanHealthWizard = true
                    tk.glucodata.ui.components.SensorType.MQ -> showMQWizard = true
                }
            }
        )
    }

    // Sibionics Setup Wizard (Full Screen)
    if (showSibionicsWizard) {
        tk.glucodata.ui.setup.SibionicsSetupWizard(
            onDismiss = { showSibionicsWizard = false },
            onComplete = {
                showSibionicsWizard = false
                viewModel.refreshSensors()
            }
        )
        return // Exit early to show wizard full screen
    }
    
    // Libre Setup Wizard
    if (showLibreWizard) {
        tk.glucodata.ui.setup.LibreSetupWizard(
            onDismiss = { showLibreWizard = false },
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
            onDismiss = {
                showDexcomWizard = false
            },
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
            onDismiss = {
                showAccuChekWizard = false
            },
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
            onDismiss = {
                showCareSensAirWizard = false
            },
            onScanResult = { raw ->
                tk.glucodata.MainActivity.handleInlineQrScan(raw, tk.glucodata.MainActivity.REQUEST_BARCODE)
                showCareSensAirWizard = false
            }
        )
        return
    }
    
    // AiDex Setup Wizard (Edit 48e: was missing — selecting AiDex from SensorTypePicker did nothing)
    if (showAiDexWizard) {
        tk.glucodata.ui.setup.AiDexSetupWizard(
            onDismiss = { showAiDexWizard = false },
            onComplete = {
                showAiDexWizard = false
                viewModel.refreshSensors()
            }
        )
        return
    }

    if (showICanHealthWizard) {
        tk.glucodata.ui.setup.ICanHealthSetupWizard(
            onDismiss = { showICanHealthWizard = false },
            onComplete = {
                showICanHealthWizard = false
                viewModel.refreshSensors()
            }
        )
        return
    }

    // MQ / Glutec Setup Wizard
    if (showMQWizard) {
        tk.glucodata.ui.setup.MQSetupWizard(
            onDismiss = { showMQWizard = false },
            onComplete = {
                showMQWizard = false
                viewModel.refreshSensors()
            },
        )
        return
    }

    // Use Box instead of Scaffold to avoid double padding from parent nav
    Box(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
        if (sensors.isEmpty()) {
            // Show sensor selection cards for empty state
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = panelPadding)
                    .padding(bottom = panelBottomPadding)
            ) {
                Spacer(modifier = Modifier.height(panelTopGap))
                Text(
                    text = stringResource(R.string.sensors_title),
                    style = titleStyle,
                    modifier = Modifier.padding(start = titleInset, end = titleInset)
                )
                Spacer(modifier = Modifier.height(panelTopGap))
                SensorsCgmReadinessBanner(onOpenReadiness = onNavigateToReadiness)
                Spacer(modifier = Modifier.height(panelPadding))
                tk.glucodata.ui.components.SensorsEmptyState(
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
                        }
                    }
                )
                Spacer(modifier = Modifier.height(panelPadding))
            }
        } else {
            // LazyColumn with header that scrolls
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(0.dp),
                contentPadding = PaddingValues(
                    start = panelPadding,
                    end = panelPadding,
                    top = panelPadding,
                    bottom = panelBottomPadding
                )
            ) {
                // Scrollable header
                item {
                    Text(
                        text = stringResource(R.string.sensors_title),
                    style = titleStyle,
                    modifier = Modifier.padding(start = titleInset, bottom = titleBottomPadding)
                    )
                }
                item {
                    SensorsCgmReadinessBanner(
                        modifier = Modifier.padding(bottom = panelPadding),
                        onOpenReadiness = onNavigateToReadiness
                    )
                }
                items(sensors, key = { it.serial }) { sensor ->
                    SensorCard(
                        sensor,
                        viewModel,
                        sensorCount = sensors.size,
                        onNavigateToMqAccount = onNavigateToMqAccount,
                    )
                }
            }
        }
        
        // FAB overlay - only show when sensors exist
        if (sensors.isNotEmpty()) {
            FloatingActionButton(
                onClick = { showSensorPicker = true },
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(fabPadding)
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}

@Composable
fun TabIcon(
    isSelected: Boolean,
    selectedIcon: ImageVector,
    unselectedIcon: ImageVector,
    description: String,
    isDashboard: Boolean = false,
    isStatistics: Boolean = false
) {
    val dashboardTilt = remember { Animatable(0f) }
    val dashboardLift = remember { Animatable(0f) }
    val dashboardScale = remember { Animatable(1f) }
    val statsTilt = remember { Animatable(0f) }
    val statsLift = remember { Animatable(0f) }
    val statsAlpha by animateFloatAsState(
        targetValue = if (isSelected) 0.96f else 0.84f,
        animationSpec = tween(220),
        label = "StatsIconAlpha"
    )

    LaunchedEffect(isDashboard, isSelected) {
        if (!isDashboard) {
            dashboardTilt.snapTo(0f)
            dashboardLift.snapTo(0f)
            dashboardScale.snapTo(1f)
            return@LaunchedEffect
        }

        if (isSelected) {
            dashboardTilt.snapTo(-22f)
            dashboardLift.snapTo(13f)
            dashboardScale.snapTo(1.12f)
            launch {
                dashboardTilt.animateTo(
                    targetValue = 0f,
                    animationSpec = spring(
                        dampingRatio = 0.34f,
                        stiffness = Spring.StiffnessLow
                    )
                )
            }
            launch {
                dashboardLift.animateTo(
                    targetValue = 0f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessLow
                    )
                )
            }
            launch {
                dashboardScale.animateTo(
                    targetValue = 1f,
                    animationSpec = spring(
                        dampingRatio = 0.56f,
                        stiffness = Spring.StiffnessMediumLow
                    )
                )
            }
        } else {
            dashboardTilt.animateTo(0f, animationSpec = tween(120))
            dashboardLift.animateTo(0f, animationSpec = tween(120))
            dashboardScale.animateTo(1f, animationSpec = tween(140))
        }
    }

    LaunchedEffect(isStatistics, isSelected) {
        if (!isStatistics) {
            statsTilt.snapTo(0f)
            statsLift.snapTo(0f)
            return@LaunchedEffect
        }

        if (isSelected) {
            statsTilt.snapTo(-8f)
            statsLift.snapTo(4f)
            launch {
                statsTilt.animateTo(
                    targetValue = 0f,
                    animationSpec = spring(
                        dampingRatio = 0.72f,
                        stiffness = Spring.StiffnessLow
                    )
                )
            }
            launch {
                statsLift.animateTo(
                    targetValue = 0f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessMediumLow
                    )
                )
            }
        } else {
            statsTilt.animateTo(0f, animationSpec = tween(130))
            statsLift.animateTo(0f, animationSpec = tween(130))
        }
    }

    AnimatedContent(
        targetState = isSelected,
        transitionSpec = {
            if (targetState) {
                if (isDashboard) {
                    // Dashboard: stronger, playful motion without icon scale expansion.
                    (slideInVertically(
                        initialOffsetY = { it + (it / 3) },
                        animationSpec = spring(
                            dampingRatio = 0.58f,
                            stiffness = Spring.StiffnessMediumLow
                        )
                    ) + scaleIn(
                        initialScale = 0.82f,
                        animationSpec = tween(230)
                    ) + fadeIn(animationSpec = tween(190)))
                        .togetherWith(
                            slideOutVertically(
                                targetOffsetY = { -it / 2 },
                                animationSpec = tween(160)
                            ) + scaleOut(
                                targetScale = 1.08f,
                                animationSpec = tween(160)
                            ) + fadeOut(animationSpec = tween(130))
                        )
                } else {
                    if (isStatistics) {
                        // Statistics: slightly livelier but still calmer than Dashboard.
                        (slideInVertically(
                            initialOffsetY = { it / 3 },
                            animationSpec = spring(
                                dampingRatio = 0.8f,
                                stiffness = Spring.StiffnessMediumLow
                            )
                        ) + fadeIn(animationSpec = tween(190)))
                            .togetherWith(
                                slideOutVertically(
                                    targetOffsetY = { -it / 6 },
                                    animationSpec = tween(140)
                                ) + fadeOut(animationSpec = tween(130))
                            )
                    } else {
                        // Selected: Filled icon "pops" in (Scale + Fade)
                        (scaleIn(animationSpec = tween(200)) + fadeIn(animationSpec = tween(200)))
                            .togetherWith(fadeOut(animationSpec = tween(200)))
                    }
                }
            } else {
                if (isDashboard) {
                    (slideInVertically(
                        initialOffsetY = { -it / 4 },
                        animationSpec = tween(170)
                    ) + fadeIn(animationSpec = tween(180)))
                        .togetherWith(
                            slideOutVertically(
                                targetOffsetY = { it / 3 },
                                animationSpec = tween(160)
                            ) + fadeOut(animationSpec = tween(150))
                        )
                } else {
                    if (isStatistics) {
                        (slideInVertically(
                            initialOffsetY = { -it / 5 },
                            animationSpec = tween(170)
                        ) + fadeIn(animationSpec = tween(180)))
                            .togetherWith(
                                slideOutVertically(
                                    targetOffsetY = { it / 4 },
                                    animationSpec = tween(150)
                                ) + fadeOut(animationSpec = tween(140))
                            )
                    } else {
                        // Deselected: Outline icon fades back in normal
                        fadeIn(animationSpec = tween(200))
                            .togetherWith(scaleOut(animationSpec = tween(200)) + fadeOut(animationSpec = tween(200)))
                    }
                }
            }
        },
        label = "TabIconAnimation"
    ) { selected ->
        Icon(
            imageVector = if (selected) selectedIcon else unselectedIcon,
            contentDescription = description,
            modifier = if (isDashboard) {
                Modifier.graphicsLayer {
                    rotationZ = dashboardTilt.value
                    translationY = -dashboardLift.value
                    scaleX = dashboardScale.value
                    scaleY = dashboardScale.value
                }.size(24.dp)
            } else if (isStatistics) {
                Modifier
                    .graphicsLayer {
                        rotationZ = statsTilt.value
                        translationY = -statsLift.value
                    }
                    .size(24.dp)
                    .alpha(statsAlpha)
            } else {
                Modifier
            }
        )
    }
}


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

@Composable
fun SensorCard(
    sensor: tk.glucodata.ui.viewmodel.SensorInfo,
    viewModel: tk.glucodata.ui.viewmodel.SensorViewModel,
    sensorCount: Int = 1,
    onNavigateToMqAccount: () -> Unit = {},
) {
    val context = LocalContext.current
    var showTerminateDialog by remember { mutableStateOf(false) }
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
    var showAiDexCalibrateDialog by remember { mutableStateOf(false) }
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
            // AiDex: full teardown — removes bond, keys, prefs, and sensor entry
            AlertDialog(
                onDismissRequest = { showTerminateDialog = false },
                title = { Text(stringResource(R.string.disconnect_sensor_title)) },
                text = { Text(stringResource(R.string.disconnect_sensor_aidex_desc)) },
                confirmButton = {
                    TextButton(onClick = {
                        viewModel.terminateSensor(sensor.serial)
                        showTerminateDialog = false
                    }) { Text(stringResource(R.string.disconnect)) }
                },
                dismissButton = {
                    TextButton(onClick = { showTerminateDialog = false }) {
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
            },
            confirmButton = {
                TextButton(onClick = {
                    if (keepAutoCalChecked) {
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

    // Auto-Calibration Settings bottom sheet — redesigned with master switch
    // Master switch guards advanced controls (slider + daily restart).
    // Restart button available in both modes (native restart in OFF, windowed in ON).
    if (showSibionicsCalSheet && sensor.isSibionics && sensor.viewMode != 1) {
        @OptIn(ExperimentalMaterial3Api::class)
        ModalBottomSheet(
            onDismissRequest = { showSibionicsCalSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            dragHandle = { BottomSheetDefaults.DragHandle() }
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

                // --- Master switch: Advanced auto-calibration (prominent card) ---
                var advancedEnabled by remember(sensor.customCalEnabled) { mutableStateOf(sensor.customCalEnabled) }
                // Track whether settings were changed but not yet applied (dirty state)
                var settingsDirty by remember { mutableStateOf(false) }
                val windowLabels = remember { listOf("12H", "1D", "2D", "3D", "5D", "7D", "10D", "14D", "18D", "MAX") }
                val maxSliderPos = windowLabels.lastIndex
                var sliderPos by remember(sensor.customCalEnabled, sensor.customCalIndex) {
                    mutableStateOf(
                        if (sensor.customCalEnabled) {
                            sensor.customCalIndex.coerceIn(0, maxSliderPos).toFloat()
                        } else {
                            maxSliderPos.toFloat()
                        }
                    )
                }
                var customAutoReset by remember(sensor.customCalEnabled, sensor.customCalAutoReset) {
                    mutableStateOf(if (sensor.customCalEnabled) sensor.customCalAutoReset else true)
                }
                fun applyAdvancedToggle(targetEnabled: Boolean) {
                    if (advancedEnabled == targetEnabled) return
                    advancedEnabled = targetEnabled
                    if (!targetEnabled) {
                        viewModel.disableCustomCalAndReplay(sensor.serial)
                        settingsDirty = false
                    } else {
                        val defaultPos = sliderPos.toInt().coerceIn(0, maxSliderPos)
                        viewModel.updateCustomCalibration(sensor.serial, true, defaultPos, customAutoReset)
                        settingsDirty = true
                    }
                }
                fun applyDailyRestartToggle(targetEnabled: Boolean) {
                    if (customAutoReset == targetEnabled) return
                    customAutoReset = targetEnabled
                    val pos = sliderPos.toInt().coerceIn(0, maxSliderPos)
                    viewModel.updateCustomCalibration(sensor.serial, true, pos, targetEnabled)
                    settingsDirty = true
                }

                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = if (advancedEnabled) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                            else MaterialTheme.colorScheme.surfaceContainerHighest,
                    border = if (advancedEnabled)
                        BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f))
                    else null,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                applyAdvancedToggle(!advancedEnabled)
                            }
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Tune,
                            contentDescription = null,
                            tint = if (advancedEnabled) MaterialTheme.colorScheme.primary
                                   else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Advanced auto-calibration",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                if (advancedEnabled) "Custom calibration window active"
                                else "Standard Juggluco algorithm",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        StyledSwitch(
                            checked = advancedEnabled,
                            onCheckedChange = { checked -> applyAdvancedToggle(checked) }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // --- Advanced controls (slider + daily restart) — visible when master switch ON ---
                AnimatedVisibility(visible = advancedEnabled) {
                    Column {
                        val currentPos = sliderPos.toInt().coerceIn(0, maxSliderPos)
                        val currentLabel = windowLabels[currentPos]

                        // Current mode label
                        Text(
                            currentLabel,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = if (currentLabel == "MAX") MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            if (currentLabel == "MAX") "Use all available sensor data"
                            else "$currentLabel calibration window",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Center
                        )

                        Slider(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                            value = sliderPos,
                            onValueChange = { sliderPos = it },
                            valueRange = 0f..maxSliderPos.toFloat(),
                            steps = maxSliderPos - 1,
                            onValueChangeFinished = {
                                val pos = sliderPos.toInt().coerceIn(0, maxSliderPos)
                                viewModel.updateCustomCalibration(sensor.serial, true, pos, customAutoReset)
                                settingsDirty = true
                            }
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        // --- Restart daily toggle (full-row touch target) ---
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    applyDailyRestartToggle(!customAutoReset)
                                }
                                .padding(horizontal = 4.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "Restart daily",
                                    style = MaterialTheme.typography.bodyLarge
                                )
                                Text(
                                    "Automatically restart algorithm once per day",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            StyledSwitch(
                                checked = customAutoReset,
                                onCheckedChange = { checked -> applyDailyRestartToggle(checked) }
                            )
                        }

                        Spacer(modifier = Modifier.height(16.dp))
                    }
                }

                // --- Restart algorithm button (always visible) ---
                // Visual: RED when dirty (unapplied changes), subtle otherwise
                val restartButtonColor = if (settingsDirty)
                    MaterialTheme.colorScheme.errorContainer
                else
                    MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
                val restartIconColor = MaterialTheme.colorScheme.error
                val restartTextColor = MaterialTheme.colorScheme.error

                Surface(
                    onClick = {
                        if (advancedEnabled) {
                            viewModel.localReplay(sensor.serial)
                        } else {
                            viewModel.restartSibionicsNativeFresh(sensor.serial)
                        }
                        settingsDirty = false
                        showSibionicsCalSheet = false
                    },
                    shape = RoundedCornerShape(12.dp),
                    color = restartButtonColor,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.RestartAlt,
                            contentDescription = null,
                            tint = restartIconColor
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                if (settingsDirty) stringResource(R.string.restart_algorithm_to_apply)
                                else stringResource(R.string.restart_algorithm),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Medium,
                                color = restartTextColor
                            )
                            Text(
                                if (settingsDirty) stringResource(R.string.settings_changed_press_to_apply)
                                else if (advancedEnabled) stringResource(R.string.restart_with_current_window)
                                else stringResource(R.string.restart_with_standard_algorithm),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                TextButton(
                    onClick = { showSibionicsCalSheet = false },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(stringResource(R.string.cancel)) }
            }
        }
    }

    if (showAiDexCalibrateDialog) {
        AlertDialog(
            onDismissRequest = {
                showAiDexCalibrateDialog = false
                calibrationInputText = ""
            },
            title = { Text(stringResource(R.string.calibrate_sensor_title)) },
            text = {
                val isMmol = tk.glucodata.ui.util.GlucoseFormatter.isMmolApp()
                val unitLabel = if (isMmol) "mmol/L" else "mg/dL"
                Column {
                    Text(stringResource(R.string.calibrate_sensor_desc, unitLabel))
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
                    (if (isMmol) tk.glucodata.ui.util.GlucoseFormatter.mmolToMg(it) else it).toInt()
                }
                val isValid = glucoseMgDl != null && glucoseMgDl in 30..500
                TextButton(
                    onClick = {
                        if (glucoseMgDl != null && isValid) {
                            viewModel.calibrateAiDexSensor(sensor.serial, glucoseMgDl)
                            showAiDexCalibrateDialog = false
                            calibrationInputText = ""
                        }
                    },
                    enabled = isValid
                ) { Text(stringResource(R.string.calibrate_action)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    showAiDexCalibrateDialog = false
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
                                    text = sensor.serial,
                                    style = serialTextStyle,
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                    modifier = Modifier
                                )
                                // Toggle Main Sensor Badge
                                Spacer(modifier = Modifier.width(8.dp))
                                val isMain = sensor.isActive

                                val badgeColor = if(isMain) sensor.color else sensor.color.copy(alpha=0.6f)
                                val badgeBg = if(isMain) sensor.color.copy(alpha = 0.15f) else Color.Transparent
                                val badgeBorder = if(isMain) null else androidx.compose.foundation.BorderStroke(1.dp, sensor.color.copy(alpha=0.3f))

                                if (sensorCount > 1) {
                                    // Multi-sensor: interactive badge with Surface background
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Box(
                                        modifier = Modifier
                                            .clip(androidx.compose.foundation.shape.CircleShape)
                                            .then(
                                                if (!isMain) Modifier.clickable { viewModel.setMain(sensor.serial) }
                                                else Modifier
                                            )
                                            .defaultMinSize(minWidth = 26.dp, minHeight = 26.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Surface(
                                            color = badgeBg,
                                            shape = androidx.compose.foundation.shape.CircleShape,
                                            border = badgeBorder
                                        ) {
                                            Icon(
                                                imageVector = if (isMain) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                                                contentDescription = if (isMain) "Active" else "Set Main",
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

                    if (sensor.detailedStatus.isNotEmpty()) {
                        Text(
                            text = sensor.detailedStatus,
                            style = MaterialTheme.typography.titleSmall, // Bigger than labelMedium
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    } else if (sensor.connectionStatus.isNotEmpty()) {
                         Text(
                            text = sensor.connectionStatus,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
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

                    val DataRow = @Composable { label: String, value: String ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
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
                            Text(
                                text = value,
                                style = valueStyle,
                                maxLines = 2,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                textAlign = androidx.compose.ui.text.style.TextAlign.End,
                                modifier = Modifier.weight(0.58f)
                            )
                        }
                    }

                    if (sensor.connectionStatus.isNotEmpty()) {
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

                    if (sensor.isMq && sensor.batteryPercent >= 0) {
                        DataRow(stringResource(R.string.sensor_battery_voltage), "${sensor.batteryPercent}%")
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
                    val calSubtitle = if (sensor.customCalEnabled) {
                        val calLabels = listOf("12H", "1D", "2D", "3D", "5D", "7D", "10D", "14D", "18D", "MAX")
                        val label = calLabels.getOrElse(sensor.customCalIndex) { "12H" }
                        "$label ${stringResource(R.string.window_label)}"
                    } else {
                        stringResource(R.string.juggluco_native)
                    }
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
                                    color = if (sensor.customCalEnabled) MaterialTheme.colorScheme.tertiary
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
                    onClick = { showAiDexCalibrateDialog = true },
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
                                        text = if (calExpanded) "Show less" else "Show all $calCount",
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
            if (sensor.isSibionics2) {
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

                // Auto-reset days stepper (hardware reset scheduling, not algorithm-related)
                val isAutoResetEnabled = sensor.autoResetDays < 25
                var daysValue by remember(sensor.autoResetDays) {
                    mutableStateOf(if (isAutoResetEnabled) sensor.autoResetDays else 20)
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        stringResource(R.string.auto_reset_title),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    AnimatedVisibility(visible = isAutoResetEnabled) {
                        Surface(
                            shape = MaterialTheme.shapes.large,
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            modifier = Modifier.padding(end = 8.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(4.dp)
                            ) {
                                IconButton(
                                    onClick = {
                                        if (daysValue > 1) {
                                            daysValue--
                                            viewModel.setAutoResetDays(sensor.serial, daysValue)
                                        }
                                    },
                                    enabled = daysValue > 1,
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Remove,
                                        contentDescription = "Decrease",
                                        modifier = Modifier.size(16.dp),
                                        tint = if (daysValue > 1) MaterialTheme.colorScheme.onSurfaceVariant
                                               else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                                    )
                                }
                                Surface(
                                    shape = MaterialTheme.shapes.medium,
                                    color = MaterialTheme.colorScheme.primaryContainer
                                ) {
                                    Text(
                                        text = stringResource(R.string.auto_reset_days, daysValue),
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
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
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Add,
                                        contentDescription = "Increase",
                                        modifier = Modifier.size(16.dp),
                                        tint = if (daysValue < 22) MaterialTheme.colorScheme.onSurfaceVariant
                                               else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                                    )
                                }
                            }
                        }
                    }
                    StyledSwitch(
                        checked = isAutoResetEnabled,
                        onCheckedChange = { enabled ->
                            val newValue = if (enabled) daysValue else 300
                            viewModel.setAutoResetDays(sensor.serial, newValue)
                        }
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
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

            if (!sensor.isAidex && !sensor.isSibionics2 && sensor.supportsHardwareReset) {
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NightscoutSettingsScreenLegacy(navController: androidx.navigation.NavController) {
    val context = LocalContext.current

    // Load initial values from Natives
    // Note: Natives methods are static and blocking, but usually fast enough for UI.
    // In a perfect world, we'd wrap this in a ViewModel/Suspend, but sticking to existing pattern.
    var url by remember { mutableStateOf(Natives.getnightuploadurl() ?: "") }
    var secret by remember { mutableStateOf(Natives.getnightuploadsecret() ?: "") }
    var isActive by remember { mutableStateOf(Natives.getuseuploader()) }
    var sendTreatments by remember { mutableStateOf(Natives.getpostTreatments()) }
    // V3 is only for phone, watch doesn't support it according to NightPost.java logic (isWearable check)
    // Assuming mobile context here.
    var isV3 by remember { mutableStateOf(Natives.getnightscoutV3()) }

    var showSecret by remember { mutableStateOf(false) }

    Scaffold(
        contentWindowInsets = WindowInsets(0.dp),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.nightscout_settings_title)) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        // Auto-save on exit
        DisposableEffect(Unit) {
            onDispose {
                Natives.setNightUploader(url, secret, isActive, isV3)
                Natives.setpostTreatments(sendTreatments)
                // Optional: Toast or logging, but user wanted standard app behavior which is silent save usually
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {

            // Active Switch
            ListItem(
                headlineContent = { Text(stringResource(R.string.active)) },
                supportingContent = { Text(stringResource(R.string.nightscout_enable_upload)) },
                trailingContent = {
                    StyledSwitch(checked = isActive, onCheckedChange = { isActive = it })
                }
            )

            HorizontalDivider()

            // URL
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text(stringResource(R.string.nightscout_url_label)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text(stringResource(R.string.nightscout_url_placeholder)) }
            )

            // Secret
            OutlinedTextField(
                value = secret,
                onValueChange = { secret = it },
                label = { Text(stringResource(R.string.api_secret_label)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = if (showSecret) androidx.compose.ui.text.input.VisualTransformation.None else androidx.compose.ui.text.input.PasswordVisualTransformation(),
                trailingIcon = {
                    val image = if (showSecret)
                        Icons.Filled.Visibility
                    else
                        Icons.Filled.VisibilityOff

                    IconButton(onClick = { showSecret = !showSecret }) {
                        Icon(imageVector = image, contentDescription = if (showSecret) stringResource(R.string.hide_password) else stringResource(R.string.show_password))
                    }
                }
            )

            HorizontalDivider()

            // Send Treatments
            ListItem(
                headlineContent = { Text(stringResource(R.string.sendamounts)) },
                supportingContent = { Text(stringResource(R.string.nightscout_send_amounts_desc)) },
                trailingContent = {
                    StyledSwitch(checked = sendTreatments, onCheckedChange = { sendTreatments = it })
                }
            )

            // Mobile only V3 check
            ListItem(
                headlineContent = { Text(stringResource(R.string.nightscout_use_v3_api)) },
                supportingContent = { Text(stringResource(R.string.experimental)) },
                trailingContent = {
                    StyledSwitch(checked = isV3, onCheckedChange = { isV3 = it })
                }
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Resend / Sync Buttons
            Button(
                onClick = {
                    Natives.wakeuploader()
                    android.widget.Toast.makeText(context, context.getString(R.string.sending_now), android.widget.Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.sendnow))
            }

            OutlinedButton(
                onClick = {
                    Natives.resetuploader()
                    android.widget.Toast.makeText(context, context.getString(R.string.resend_triggered), android.widget.Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.resend_data_reset))
            }

        }
    }
}
