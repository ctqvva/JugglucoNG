package tk.glucodata.ui

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.view.HapticFeedbackConstants
import android.os.Vibrator
import android.os.VibrationEffect
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.filled.FirstPage
import androidx.compose.material.icons.automirrored.filled.LastPage
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.Bloodtype
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.DirectionsRun
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Vaccines
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.core.content.res.ResourcesCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import tk.glucodata.GlucoseRangeColors
import tk.glucodata.SensorIdentity
import tk.glucodata.R
import tk.glucodata.DataSmoothing
import tk.glucodata.data.journal.JournalActiveInsulinSummary
import tk.glucodata.data.journal.JournalChartMarker
import tk.glucodata.data.journal.JournalEntryType
import tk.glucodata.data.prediction.GlucosePredictionPoint
import tk.glucodata.data.prediction.GlucosePredictionSeries
import tk.glucodata.data.prediction.GlucosePredictionSeriesKind
import tk.glucodata.data.prediction.ForecastDoseRecommendation
import tk.glucodata.data.prediction.ForecastDoseRecommendationKind
import tk.glucodata.ui.getDisplayValues
import tk.glucodata.ui.util.ExpressiveMotion
import tk.glucodata.ui.util.GlucoseFormatter
import tk.glucodata.ui.viewmodel.SensorColors
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.roundToInt
import java.text.NumberFormat

private const val PREVIEW_WINDOW_MODE_EXPANDED_ONLY = 0
private const val PREVIEW_WINDOW_MODE_ALWAYS = 1
private const val PREVIEW_WINDOW_MODE_NEVER = 2
private const val PREVIEW_WINDOW_DURATION_MS = 24L * 60L * 60L * 1000L
private const val ACTIVE_INSULIN_MAX_WIDTH_FRACTION = 0.46f
private const val ACTIVE_INSULIN_EXPANDED_MAX_WIDTH_FRACTION = 0.58f
private val PreviewWindowHeight = 58.dp
// Band edges soften over this fraction of the preview strip's height.
private const val PREVIEW_BAND_FADE_FRACTION = 0.025f
private val PreviewWindowOuterPadding = 12.dp

private data class ChartRangeThresholds(
    val veryLow: Float,
    val low: Float,
    val high: Float,
    val veryHigh: Float
)

private data class ChartRangePalette(
    val veryLow: Color,
    val low: Color,
    val inRange: Color,
    val high: Color,
    val veryHigh: Color
)

private data class PeerSensorChartSeries(
    val sensorId: String,
    val viewMode: Int,
    val color: Color,
    val points: List<GlucosePoint>
)

private fun chartRangeThresholds(
    isMmol: Boolean,
    targetLow: Float,
    targetHigh: Float,
    veryLowThreshold: Float,
    veryHighThreshold: Float
): ChartRangeThresholds {
    val low = targetLow.takeIf { it.isFinite() && it > 0f } ?: GlucoseRangeColors.defaultLow(isMmol)
    val highCandidate = targetHigh.takeIf { it.isFinite() && it > low } ?: GlucoseRangeColors.defaultHigh(isMmol)
    val high = highCandidate.coerceAtLeast(low + 0.1f)
    val veryLow = (veryLowThreshold.takeIf { it.isFinite() && it > 0f } ?: GlucoseRangeColors.defaultVeryLow(isMmol))
        .coerceAtMost(low - 0.1f)
    val veryHigh = (veryHighThreshold.takeIf { it.isFinite() && it > 0f } ?: GlucoseRangeColors.defaultVeryHigh(isMmol))
        .coerceAtLeast(high + 0.1f)
    return ChartRangeThresholds(
        veryLow = veryLow,
        low = low,
        high = high,
        veryHigh = veryHigh
    )
}

// The five band colors a glucose trace is stroked with. The dashboard resolves
// this once and hands it to the preview navigator, so the strip cannot drift
// from the trace above it.
//
// [lowTintBase] / [highTintBase] are the out-of-range tones used when the
// app-range-color setting is off. They are parameters rather than
// GlucoseRangeColors lookups because the caller owns them: today the dashboard
// happens to define them as exactly that, but retuning them there must move
// both surfaces, not silently split them.
private fun chartRangePalette(
    isDark: Boolean,
    appChartRangeColors: Boolean,
    primaryColor: Color,
    lowTintBase: Color,
    highTintBase: Color
): ChartRangePalette = ChartRangePalette(
    veryLow = Color(GlucoseRangeColors.veryLow(isDark)),
    low = if (appChartRangeColors) Color(GlucoseRangeColors.low(isDark)) else lowTintBase,
    inRange = if (appChartRangeColors) Color(GlucoseRangeColors.inRange(isDark)) else primaryColor,
    high = if (appChartRangeColors) Color(GlucoseRangeColors.high(isDark)) else highTintBase,
    veryHigh = Color(GlucoseRangeColors.veryHigh(isDark))
)

internal fun coerceChartYToDrawableRange(
    value: Float,
    chartHeight: Float,
    edgeInset: Float
): Float {
    val safeHeight = if (chartHeight.isFinite() && chartHeight > 0f) chartHeight else 0f
    val safeInset = if (edgeInset.isFinite() && edgeInset > 0f) edgeInset else 0f
    if (!value.isFinite()) return safeHeight / 2f

    val minY = safeInset
    val maxY = safeHeight - safeInset
    return if (maxY >= minY) {
        value.coerceIn(minY, maxY)
    } else {
        value.coerceIn(0f, safeHeight)
    }
}

internal fun previewCenterTimeForWindowEnd(windowEndTime: Long): Long {
    return windowEndTime - PREVIEW_WINDOW_DURATION_MS / 2L
}

internal fun previewCenterTimeContainingViewport(
    previewCenterTime: Long,
    viewportCenterTime: Long,
    visibleDuration: Long
): Long {
    if (visibleDuration >= PREVIEW_WINDOW_DURATION_MS) {
        return viewportCenterTime
    }

    val previewHalfDuration = PREVIEW_WINDOW_DURATION_MS / 2L
    val previewStart = previewCenterTime - previewHalfDuration
    val previewEnd = previewCenterTime + previewHalfDuration
    val viewportStart = viewportCenterTime - visibleDuration / 2L
    val viewportEnd = viewportCenterTime + visibleDuration / 2L

    return when {
        viewportStart < previewStart -> viewportStart + previewHalfDuration
        viewportEnd > previewEnd -> viewportEnd - previewHalfDuration
        else -> previewCenterTime
    }
}

private fun buildSmoothedChartData(
    points: List<GlucosePoint>,
    smoothingMinutes: Int,
    collapseIntoChunks: Boolean
): List<GlucosePoint> = tk.glucodata.GlucoseSmoothing.smooth(
    points = points,
    smoothingMinutes = smoothingMinutes,
    collapseIntoChunks = collapseIntoChunks,
    timestamp = { it.timestamp },
    value = { it.value },
    rawValue = { it.rawValue },
    sensorSerial = { it.sensorSerial },
    // The band follows the value it describes; smoothing displaces the line but
    // does not make the sensor more certain, so the width is preserved.
    withValues = { point, auto, raw ->
        point.copy(
            value = auto,
            rawValue = raw,
            uncertainty = point.uncertainty?.shifted(auto - point.value),
        )
    }
)

private class CalibratedValueResolver(private val points: List<GlucosePoint>) {
    private val rawComputed = BooleanArray(points.size)
    private val autoComputed = BooleanArray(points.size)
    private val rawValues = FloatArray(points.size)
    private val autoValues = FloatArray(points.size)
    private val rawCalibrationActive = HashMap<String?, Boolean>()
    private val autoCalibrationActive = HashMap<String?, Boolean>()

    /**
     * Timestamp to index, built once.
     *
     * [valueForPoint] used to find its index with `points.indexOf(point)` — a
     * linear scan comparing every field of a data class — from inside the
     * per-dot draw loop. That is visible-dots times total-history equality
     * checks per frame, so it degraded as the database grew rather than as the
     * chart got busier, which is the shape of the jank that showed up on a
     * long-lived store. Timestamps are unique per rendered series here (the
     * merge collapses minute buckets before this sees them); a collision would
     * only pick the other point with the same timestamp, which is what the scan
     * did too.
     */
    private val indexByTimestamp: Map<Long, Int> by lazy(LazyThreadSafetyMode.NONE) {
        HashMap<Long, Int>(points.size * 2).apply {
            points.forEachIndexed { index, point -> putIfAbsent(point.timestamp, index) }
        }
    }

    fun hasCalibration(isRawMode: Boolean, sensorId: String? = null): Boolean {
        val cache = if (isRawMode) rawCalibrationActive else autoCalibrationActive
        return cache.getOrPut(sensorId) {
            tk.glucodata.data.calibration.CalibrationManager.hasActiveCalibration(isRawMode, sensorId)
        }
    }

    fun valueAt(index: Int, isRawMode: Boolean): Float {
        if (index !in points.indices) return Float.NaN
        val computed = if (isRawMode) rawComputed else autoComputed
        val values = if (isRawMode) rawValues else autoValues
        if (computed[index]) {
            return values[index]
        }
        val point = points[index]
        val baseValue = if (isRawMode) point.rawValue else point.value
        // A reading whose displayed value was recorded draws at that value, so
        // the line does not move under a calibration edit — see ReadingDisplay.
        val resolved = point.sealedDisplayValue?.takeIf { it.isFinite() && it > 0.1f }
            ?: if (
                baseValue.isFinite() &&
                baseValue > 0.1f &&
                hasCalibration(isRawMode, point.sensorSerial)
            ) {
                tk.glucodata.data.calibration.CalibrationManager.getCalibratedValue(
                    baseValue,
                    point.timestamp,
                    isRawMode,
                    sensorIdOverride = point.sensorSerial
                )
            } else {
                baseValue
            }
        values[index] = resolved
        computed[index] = true
        return resolved
    }

    fun valueForPoint(point: GlucosePoint, isRawMode: Boolean): Float {
        val pointIndex = indexByTimestamp[point.timestamp] ?: -1
        return if (pointIndex >= 0) valueAt(pointIndex, isRawMode) else {
            val baseValue = if (isRawMode) point.rawValue else point.value
            point.sealedDisplayValue?.takeIf { it.isFinite() && it > 0.1f }
                ?: if (
                    baseValue.isFinite() &&
                    baseValue > 0.1f &&
                    hasCalibration(isRawMode, point.sensorSerial)
                ) {
                    tk.glucodata.data.calibration.CalibrationManager.getCalibratedValue(
                        baseValue,
                        point.timestamp,
                        isRawMode,
                        sensorIdOverride = point.sensorSerial
                    )
                } else {
                    baseValue
                }
        }
    }
}

@Composable
private fun PreviewWindowNavigator(
    modifier: Modifier = Modifier,
    renderData: List<GlucosePoint>,
    calibratedValueResolver: CalibratedValueResolver,
    previewCenterTime: Long,
    viewMode: Int,
    rangeThresholds: ChartRangeThresholds,
    rangePalette: ChartRangePalette,
    isMmol: Boolean,
    currentCenterTime: Long,
    currentVisibleDuration: Long
) {
    val previewDuration = PREVIEW_WINDOW_DURATION_MS
    val previewHalfDuration = previewDuration / 2
    val previewStart = previewCenterTime - previewHalfDuration
    val previewEnd = previewCenterTime + previewHalfDuration
    val secondaryLineColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
    val isDark = isSystemInDarkTheme()
    val paletteRevision = GlucosePaletteState.revision
    val targetBandColor = remember(isDark, paletteRevision) {
        Color(GlucoseRangeColors.targetBackground(isDark)).copy(alpha = 0.12f)
    }
    val windowFillColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.11f)
    val windowStrokeColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
    val surfaceColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.92f)
    val minimumWindowWidthPx = with(LocalDensity.current) { 12.dp.toPx() }
    val isRawMode = viewMode == 1 || viewMode == 3
    val hasCalibration = calibratedValueResolver.hasCalibration(isRawMode)

    fun activeValue(index: Int): Float {
        return if (hasCalibration) {
            calibratedValueResolver.valueAt(index, isRawMode)
        } else {
            val renderPoint = renderData[index]
            if (isRawMode) renderPoint.rawValue else renderPoint.value
        }
    }

    fun windowBoundsPx(width: Float): Pair<Float, Float> {
        val safeWidth = width.coerceAtLeast(1f)
        val windowStartTime = currentCenterTime - currentVisibleDuration / 2
        val windowEndTime = currentCenterTime + currentVisibleDuration / 2
        val left = (((windowStartTime - previewStart).toFloat() / previewDuration.toFloat()) * safeWidth).coerceIn(0f, safeWidth)
        val right = (((windowEndTime - previewStart).toFloat() / previewDuration.toFloat()) * safeWidth).coerceIn(0f, safeWidth)
        return left to maxOf(right, left + minimumWindowWidthPx)
    }

    Surface(
        modifier = modifier.zIndex(2f),
        shape = RoundedCornerShape(18.dp),
        color = surfaceColor,
        tonalElevation = 2.dp,
        shadowElevation = 0.dp
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(PreviewWindowHeight)
        ) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 10.dp, vertical = 8.dp)
            ) {
                if (renderData.isEmpty()) return@Canvas

                val startIdx = renderData.binarySearchBy(previewStart) { it.timestamp }
                    .let { if (it < 0) -it - 2 else it }
                    .coerceIn(0, renderData.lastIndex)
                val endExclusive = renderData.binarySearchBy(previewEnd) { it.timestamp }
                    .let { if (it < 0) -it else it + 1 }
                    .coerceIn(startIdx + 1, renderData.size)

                var minValue = rangeThresholds.low
                var maxValue = rangeThresholds.high
                for (index in startIdx until endExclusive) {
                    val value = activeValue(index)
                    if (value.isFinite() && value > 0.1f) {
                        minValue = minOf(minValue, value)
                        maxValue = maxOf(maxValue, value)
                    }
                }
                if (maxValue <= minValue) {
                    val fallbackMax = if (isMmol) 14f else 252f
                    minValue = 0f
                    maxValue = fallbackMax
                } else {
                    val padding = maxOf((maxValue - minValue) * 0.18f, if (isMmol) 0.5f else 9f)
                    minValue = (minValue - padding).coerceAtLeast(0f)
                    maxValue += padding
                }

                val yRange = (maxValue - minValue).coerceAtLeast(0.1f)
                val widthPx = size.width
                val heightPx = size.height
                fun timeToX(timestamp: Long): Float =
                    ((timestamp - previewStart).toFloat() / previewDuration.toFloat()) * widthPx
                fun valueToY(value: Float): Float =
                    heightPx - (((value - minValue) / yRange) * heightPx)

                // Same band geometry as the main chart, so the preview reads as a
                // miniature of the trace above it instead of a flat primary line.
                // GlucoseChartBands fades a band into the in-range tone over a
                // fixed pixel distance, tuned for the full-height chart. This
                // strip squeezes the same value span into ~42.dp, where 18px is
                // most of a mmol/L: the low tone would start bleeding in around
                // 4.5 on a 3.5 target, which reads as the preview ignoring the
                // target range. Fade over a slice of the strip instead.
                val previewFadePx = (heightPx * PREVIEW_BAND_FADE_FRACTION)
                    .coerceIn(1f, GlucoseChartBands.DEFAULT_FADE_PX)
                val rangeStops = GlucoseChartBands.verticalStops(
                    veryHigh = rangePalette.veryHigh,
                    high = rangePalette.high,
                    inRange = rangePalette.inRange,
                    low = rangePalette.low,
                    veryLow = rangePalette.veryLow,
                    yVeryHigh = valueToY(rangeThresholds.veryHigh),
                    yHigh = valueToY(rangeThresholds.high),
                    yLow = valueToY(rangeThresholds.low),
                    yVeryLow = valueToY(rangeThresholds.veryLow),
                    chartHeightPx = heightPx,
                    fadePx = previewFadePx
                )
                val rangeBrush = Brush.verticalGradient(
                    *rangeStops.toTypedArray(),
                    startY = 0f,
                    endY = heightPx
                )

                val bandTop = valueToY(rangeThresholds.high).coerceIn(0f, heightPx)
                val bandBottom = valueToY(rangeThresholds.low).coerceIn(0f, heightPx)
                drawRoundRect(
                    color = targetBandColor,
                    topLeft = Offset(0f, minOf(bandTop, bandBottom)),
                    size = Size(widthPx, kotlin.math.abs(bandBottom - bandTop)),
                    cornerRadius = CornerRadius(10f, 10f)
                )

                val previewPath = Path()
                var started = false
                var lastTimestamp = 0L
                var lastSensorSerial: String? = null
                val gapThreshold = ChartGap.THRESHOLD_MS
                val previewRun = ChartLineRun()
                for (index in startIdx until endExclusive) {
                    val value = activeValue(index)
                    if (!value.isFinite() || value < 0.1f) {
                        // No value in the active lane is not a hole in it — the gap check
                        // below still measures elapsed time. See the main chart's AUTO branch.
                        continue
                    }
                    val timestamp = renderData[index].timestamp
                    val sensorSerial = renderData[index].sensorSerial
                    val x = timeToX(timestamp)
                    val y = valueToY(value).coerceIn(-64f, heightPx + 64f)
                    val sensorChanged = started &&
                        lastSensorSerial != null &&
                        sensorSerial != null &&
                        sensorSerial != lastSensorSerial
                    if (!started || (timestamp - lastTimestamp) > gapThreshold || sensorChanged) {
                        previewPath.moveTo(x, y)
                        previewRun.begin(x, y)
                        started = true
                    } else {
                        previewPath.lineTo(x, y)
                        previewRun.extend()
                    }
                    lastTimestamp = timestamp
                    lastSensorSerial = sensorSerial
                }
                previewRun.flush()
                val previewStroke = 2.5.dp.toPx()
                drawPath(
                    path = previewPath,
                    brush = rangeBrush,
                    style = Stroke(width = previewStroke, cap = StrokeCap.Round, join = StrokeJoin.Round)
                )
                previewRun.isolatedPoints.forEach { point ->
                    drawCircle(brush = rangeBrush, radius = previewStroke / 2f, center = point)
                }

                val currentStart = currentCenterTime - currentVisibleDuration / 2
                val currentEnd = currentCenterTime + currentVisibleDuration / 2
                val windowStartX = timeToX(currentStart).coerceIn(0f, widthPx)
                val windowEndX = timeToX(currentEnd).coerceIn(0f, widthPx)
                val windowWidth = (windowEndX - windowStartX).coerceAtLeast(12.dp.toPx())

                drawRoundRect(
                    color = windowFillColor,
                    topLeft = Offset(windowStartX, 0f),
                    size = Size(windowWidth, heightPx),
                    cornerRadius = CornerRadius(12f, 12f)
                )
                drawRoundRect(
                    color = windowStrokeColor,
                    topLeft = Offset(windowStartX, 0f),
                    size = Size(windowWidth, heightPx),
                    cornerRadius = CornerRadius(12f, 12f),
                    style = Stroke(width = 2.dp.toPx())
                )

                val leftHandleX = windowStartX + 1.dp.toPx()
                val rightHandleX = windowStartX + windowWidth - 1.dp.toPx()
                drawLine(
                    color = secondaryLineColor,
                    start = Offset(leftHandleX, 10.dp.toPx()),
                    end = Offset(leftHandleX, heightPx - 10.dp.toPx()),
                    strokeWidth = 1.dp.toPx(),
                    cap = StrokeCap.Round
                )
                drawLine(
                    color = secondaryLineColor,
                    start = Offset(rightHandleX, 10.dp.toPx()),
                    end = Offset(rightHandleX, heightPx - 10.dp.toPx()),
                    strokeWidth = 1.dp.toPx(),
                    cap = StrokeCap.Round
                )
            }
        }
    }
}

/**
 * Above this posterior artifact probability the tooltip mentions the
 * possibility. Chosen so the notice appears when the artifact hypothesis is a
 * genuine competitor, not whenever it is merely non-zero.
 */
private const val ARTIFACT_NOTICE_PROBABILITY = 0.25f

/** Below this confidence the tooltip says so rather than leaving the wide band unexplained. */
private const val LOW_CONFIDENCE_NOTICE = 0.45f

data class ChartViewportSnapshot(
    val startMillis: Long,
    val endMillis: Long,
    val visiblePoints: List<GlucosePoint>,
    val selectedPoint: GlucosePoint?
)

data class ChartTimelineTapSuggestion(
    val timestamp: Long,
    val suggestedDisplayGlucose: Float? = null,
    val normalizedYFraction: Float? = null,
    val forceMenu: Boolean = false
)

private fun List<GlucosePoint>.sliceByTimestampRange(startMillis: Long, endMillis: Long): List<GlucosePoint> {
    if (isEmpty()) return emptyList()
    val startIndex = binarySearchBy(startMillis) { it.timestamp }
        .let { if (it >= 0) it else (-it - 1).coerceAtLeast(0) }
    val endInsertionPoint = binarySearchBy(endMillis) { it.timestamp }
        .let { if (it >= 0) it + 1 else (-it - 1) }
        .coerceAtMost(size)
    if (startIndex >= endInsertionPoint) return emptyList()
    return subList(startIndex, endInsertionPoint)
}

@Composable
fun DashboardChartSection(
    modifier: Modifier,
    glucoseHistory: List<GlucosePoint>,
    multiSensorDisplay: MultiSensorDisplayData = MultiSensorDisplayData.EMPTY,
    peerPredictionSeries: Map<String, List<GlucosePredictionSeries>> = emptyMap(),
    journalMarkers: List<JournalChartMarker> = emptyList(),
    activeInsulinSummary: JournalActiveInsulinSummary? = null,
    activeInsulinFromRemote: Boolean = false,
    showEiob: Boolean = true,
    forecastDoseRecommendation: ForecastDoseRecommendation? = null,
    appChartRangeColors: Boolean = false,
    predictionPoints: List<GlucosePredictionPoint> = emptyList(),
    predictionSeries: List<GlucosePredictionSeries> = emptyList(),
    graphSmoothingMinutes: Int = 0,
    collapseSmoothedData: Boolean = false,
    previewWindowMode: Int = 0,
    graphLow: Float,
    graphHigh: Float,
    targetLow: Float,
    targetHigh: Float,
    unit: String,
    veryLowThreshold: Float = GlucoseRangeColors.defaultVeryLow(GlucoseFormatter.isMmol(unit)),
    veryHighThreshold: Float = GlucoseRangeColors.defaultVeryHigh(GlucoseFormatter.isMmol(unit)),
    viewMode: Int,
    onTimeRangeSelected: (TimeRange) -> Unit,
    selectedTimeRange: TimeRange,
    isExpanded: Boolean = false,
    expandedProgress: Float = if (isExpanded) 1f else 0f,
    expandedUnderlayBottom: androidx.compose.ui.unit.Dp = 116.dp,
    onToggleExpanded: (() -> Unit)? = null,
    calibrations: List<tk.glucodata.data.calibration.CalibrationEntity> = emptyList(),
    onPointClick: ((GlucosePoint) -> Unit)? = null,
    onCalibrationClick: ((tk.glucodata.data.calibration.CalibrationEntity) -> Unit)? = null,
    onTimelineTap: ((ChartTimelineTapSuggestion) -> Unit)? = null,
    journalActionTimestamp: Long? = null,
    journalActionDisplayValue: Float? = null,
    onDismissJournalAction: (() -> Unit)? = null,
    onJournalMarkerClick: ((Long) -> Unit)? = null,
    chartBoostProgress: Float = 0f,
    resetToLatestOnResume: Boolean = true,
    onViewportSnapshotChanged: ((ChartViewportSnapshot) -> Unit)? = null
) {
    val chartContent: @Composable () -> Unit = {
        Column(modifier = Modifier.padding(bottom = 0.dp)) {
             Box(modifier = Modifier.weight(1f)) {
                if (glucoseHistory.isNotEmpty()) {
                    InteractiveGlucoseChart(
                        fullData = glucoseHistory,
                        multiSensorDisplay = multiSensorDisplay,
                        peerPredictionSeries = peerPredictionSeries,
                        journalMarkers = journalMarkers,
                        activeInsulinSummary = activeInsulinSummary,
                        activeInsulinFromRemote = activeInsulinFromRemote,
                        showEiob = showEiob,
                        forecastDoseRecommendation = forecastDoseRecommendation,
                        appChartRangeColors = appChartRangeColors,
                        predictionPoints = predictionPoints,
                        predictionSeries = predictionSeries,
                        graphSmoothingMinutes = graphSmoothingMinutes,
                        collapseSmoothedData = collapseSmoothedData,
                        previewWindowMode = previewWindowMode,
                        graphLow = graphLow,
                        graphHigh = graphHigh,
                        targetLow = targetLow,
                        targetHigh = targetHigh,
                        veryLowThreshold = veryLowThreshold,
                        veryHighThreshold = veryHighThreshold,
                        unit = unit,
                        viewMode = viewMode,
                        calibrations = calibrations,
                        onTimeRangeSelected = onTimeRangeSelected,
                        selectedTimeRange = selectedTimeRange,
                        isExpanded = isExpanded,
                        expandedProgress = expandedProgress,
                        expandedUnderlayBottom = expandedUnderlayBottom,
                        onToggleExpanded = onToggleExpanded,
                        onPointClick = onPointClick,
                        onCalibrationClick = onCalibrationClick,
                        onTimelineTap = onTimelineTap,
                        journalActionTimestamp = journalActionTimestamp,
                        journalActionDisplayValue = journalActionDisplayValue,
                        onDismissJournalAction = onDismissJournalAction,
                        onJournalMarkerClick = onJournalMarkerClick,
                        chartBoostProgress = chartBoostProgress,
                        resetToLatestOnResume = resetToLatestOnResume,
                        onViewportSnapshotChanged = onViewportSnapshotChanged
                    )
                } else {
                    Box(Modifier.fillMaxSize())
                }
                }
        }
    }
    val safeExpandedProgress = expandedProgress.coerceIn(0f, 1f)
    // No dead zone: the 6% one meant the colour and the corner radius sat still while
    // the card was already pulling in from the edges, so the shape led the paint.
    val collapseVisualProgress = 1f - safeExpandedProgress
    val containerColor = androidx.compose.ui.graphics.lerp(
        MaterialTheme.colorScheme.background,
        MaterialTheme.colorScheme.surfaceContainerHigh,
        collapseVisualProgress
    )
    val cornerRadius = (16.dp * collapseVisualProgress).coerceAtLeast(0.dp)
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(cornerRadius),
        color = containerColor,
        // Deliberately no tonalElevation. Material's Surface only applies it when the
        // colour is exactly colorScheme.surface, so with a lerped colour it switches on
        // and off at whichever endpoint happens to match in the current scheme — a tint
        // that pops at the end of the animation in some themes and never appears in
        // others. surfaceContainerHigh is already the elevated tone, so the lerp alone
        // carries the intent.
        shadowElevation = 0.dp
    ) {
        chartContent()
    }
}

@Composable
fun InteractiveGlucoseChart(
    fullData: List<GlucosePoint>,
    multiSensorDisplay: MultiSensorDisplayData = MultiSensorDisplayData.EMPTY,
    peerPredictionSeries: Map<String, List<GlucosePredictionSeries>> = emptyMap(),
    journalMarkers: List<JournalChartMarker> = emptyList(),
    activeInsulinSummary: JournalActiveInsulinSummary? = null,
    activeInsulinFromRemote: Boolean = false,
    showEiob: Boolean = true,
    forecastDoseRecommendation: ForecastDoseRecommendation? = null,
    appChartRangeColors: Boolean = false,
    predictionPoints: List<GlucosePredictionPoint> = emptyList(),
    predictionSeries: List<GlucosePredictionSeries> = emptyList(),
    graphSmoothingMinutes: Int = 0,
    collapseSmoothedData: Boolean = false,
    previewWindowMode: Int = 0,
    graphLow: Float,
    graphHigh: Float,
    targetLow: Float,
    targetHigh: Float,
    unit: String,
    veryLowThreshold: Float = GlucoseRangeColors.defaultVeryLow(GlucoseFormatter.isMmol(unit)),
    veryHighThreshold: Float = GlucoseRangeColors.defaultVeryHigh(GlucoseFormatter.isMmol(unit)),
    viewMode: Int = 0,
    calibrations: List<tk.glucodata.data.calibration.CalibrationEntity> = emptyList(),
    onDateSelected: (Long) -> Unit = {},
    onTimeRangeSelected: ((TimeRange) -> Unit)? = null,
    selectedTimeRange: TimeRange? = null,
    isExpanded: Boolean = false,
    expandedProgress: Float = if (isExpanded) 1f else 0f,
    expandedUnderlayBottom: androidx.compose.ui.unit.Dp = 116.dp,
    onToggleExpanded: (() -> Unit)? = null,
    onPointClick: ((GlucosePoint) -> Unit)? = null,
    onCalibrationClick: ((tk.glucodata.data.calibration.CalibrationEntity) -> Unit)? = null,
    onTimelineTap: ((ChartTimelineTapSuggestion) -> Unit)? = null,
    journalActionTimestamp: Long? = null,
    journalActionDisplayValue: Float? = null,
    onDismissJournalAction: (() -> Unit)? = null,
    onJournalMarkerClick: ((Long) -> Unit)? = null,
    chartBoostProgress: Float = 0f,
    resetToLatestOnResume: Boolean = true,
    onViewportSnapshotChanged: ((ChartViewportSnapshot) -> Unit)? = null
) {
    // --- THEME & PAINTS ---
    val isDark = isSystemInDarkTheme()
    // Observe the glucose palette so target band / band tints recompute live
    // when the user switches presets or edits a band colour (no restart).
    val glucosePaletteRevision = GlucosePaletteState.revision
    // User requested stronger dark mode lines ("oddly pale").
    // Standard M3 dark primary is pastel. We use a more saturated blue for data.
    val primaryColor = MaterialTheme.colorScheme.primary
    val secondaryColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
    val tertiaryColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f) // Lighter shade for 3rd line
    val pointColor = MaterialTheme.colorScheme.onSurface
    val gridColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.125f)
    // A dedicated override may replace the in-range hue; without one, the band
    // follows IN_RANGE. The 0.12 alpha restores the original target-band weight.
    val targetBandColor =
        Color(GlucoseRangeColors.targetBackground(isDark)).copy(alpha = 0.12f)
//    val targetBandColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f)
    val hoverLineColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
    val minMaxLineColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
    val safeExpandedProgress = expandedProgress.coerceIn(0f, 1f)
    val chartUnderlayBottomDp = expandedUnderlayBottom * safeExpandedProgress
    val chartUnderlayBottomPx = with(LocalDensity.current) { chartUnderlayBottomDp.toPx() }
    val chartUnderlayBottomIntPx = with(LocalDensity.current) { chartUnderlayBottomDp.roundToPx() }
    val previewRevealProgress = when (previewWindowMode) {
        PREVIEW_WINDOW_MODE_ALWAYS -> 1f
        PREVIEW_WINDOW_MODE_NEVER -> 0f
        else -> chartBoostProgress.coerceIn(0f, 1f)
    }
    val previewWindowReservedDp = 72.dp * previewRevealProgress
    val previewWindowReservedPx = with(LocalDensity.current) { previewWindowReservedDp.toPx() }
    val previewWindowReservedIntPx = with(LocalDensity.current) { previewWindowReservedDp.roundToPx() }
    val labelsLiftPx = with(LocalDensity.current) { (4.dp * safeExpandedProgress).toPx() }
    val chartPlotBottomGapPx = with(LocalDensity.current) { (4.dp * safeExpandedProgress).toPx() }
    val bottomAxisHeightPx = with(LocalDensity.current) { 32.dp.toPx() }
    val axisLabelBackgroundColor = androidx.compose.ui.graphics.lerp(
        MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.96f),
        MaterialTheme.colorScheme.background.copy(alpha = 0.96f),
        safeExpandedProgress
    )

    val TirLowColor = Color(GlucoseRangeColors.low(isDark))
    val TirHighColor = Color(GlucoseRangeColors.high(isDark))

    // Using the "High" color for the generic high tint base, and "Low" for low tint base
    // Adjusting alpha for visibility on graph background
    val lowOutOfRangeTintBase = TirLowColor
    val highOutOfRangeTintBase = TirHighColor
    // Resolved once for the trace and the preview strip below it.
    val chartBandPalette = remember(
        isDark,
        appChartRangeColors,
        primaryColor,
        lowOutOfRangeTintBase,
        highOutOfRangeTintBase,
        glucosePaletteRevision
    ) {
        chartRangePalette(
            isDark,
            appChartRangeColors,
            primaryColor,
            lowOutOfRangeTintBase,
            highOutOfRangeTintBase
        )
    }
    // Neutral target for desaturating peer (secondary sensor) traces — theme
    // token so it adapts to light/dark and dynamic color.
    val peerNeutralBase = MaterialTheme.colorScheme.onSurfaceVariant


    val context = LocalContext.current
    // 1. Get Vibrator & Check Capabilities ONCE (Performance)
    val hapticsConfig = remember(context) {
        val vib = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            context.getSystemService(Vibrator::class.java)
        } else {
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

        // Check if the device has an advanced haptic engine (Linear Resonant Actuator)
        // primitive_low_tick is the best proxy for "High Quality Motor"
        val hasCrispHaptics = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            vib?.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_LOW_TICK) == true
        } else {
            false
        }

        Triple(vib, hasCrispHaptics, context)
    }

    val (vibrator, hasCrispHaptics, _) = hapticsConfig

    // 2. Throttling State
    // We store the last time we ACTUALLY vibrated to prevent "buzzing"
    var lastHapticExecutionTime by remember { mutableLongStateOf(0L) }


    val view = LocalView.current
    val density = context.resources.displayMetrics.density
    val dashboardPrefs = remember(context) {
        context.getSharedPreferences("tk.glucodata_preferences", Context.MODE_PRIVATE)
    }
    var scrubValueLabelOffsetDp by rememberSaveable {
        mutableFloatStateOf(dashboardPrefs.getFloat("dashboard_scrub_value_label_offset_dp", 48f))
    }
    var scrubTimeLabelOffsetDp by rememberSaveable {
        mutableFloatStateOf(dashboardPrefs.getFloat("dashboard_scrub_time_label_offset_dp", 2f))
    }
    var isAdjustingScrubLabel by remember { mutableStateOf(false) }
    
    val graphFont = remember(context) {
        ResourcesCompat.getFont(context, R.font.ibm_plex_sans_var)
    }
    val graphFontBold = remember(graphFont) {
        if (graphFont != null) android.graphics.Typeface.create(graphFont, android.graphics.Typeface.BOLD) else android.graphics.Typeface.DEFAULT_BOLD
    }

    // Paints
    val axisTextPaint = remember(graphFont) {
        android.graphics.Paint().apply {
            color = android.graphics.Color.GRAY
            textSize = 10f * density
            textAlign = android.graphics.Paint.Align.LEFT
            typeface = graphFont
        }
    }
    val xTextPaint = remember(graphFont) {
        android.graphics.Paint().apply {
            color = android.graphics.Color.GRAY
            textSize = 10f * density
            textAlign = android.graphics.Paint.Align.CENTER
            typeface = graphFont
        }
    }


    // --- ONE-TIME INIT ---
    // Ensure Fast Random Access for the drawing loop (critical for performance)
    val safeData = remember(fullData) {
        if (fullData is java.util.RandomAccess) fullData else ArrayList(fullData)
    }
    val renderData = remember(safeData, graphSmoothingMinutes, collapseSmoothedData) {
        buildSmoothedChartData(safeData, graphSmoothingMinutes, collapseSmoothedData)
    }
    val peerChartSeries = remember(
        multiSensorDisplay,
        graphSmoothingMinutes,
        collapseSmoothedData
    ) {
        multiSensorDisplay.series.mapNotNull { series ->
            if (series.points.size < 2) return@mapNotNull null
            PeerSensorChartSeries(
                sensorId = series.sensorId,
                viewMode = series.viewMode,
                color = Color(series.colorArgb),
                points = buildSmoothedChartData(series.points, graphSmoothingMinutes, collapseSmoothedData)
            )
        }
    }
    val peerPointsByBucket = multiSensorDisplay.bucketLookup
    // serial (normalized to logical id) -> draw attributes; O(1) lookups in the
    // cursor/tooltip hot paths instead of SensorIdentity.matches per frame.
    val peerDrawAttrs = remember(multiSensorDisplay) {
        multiSensorDisplay.series.associate { series ->
            series.sensorId to (Color(series.colorArgb) to series.viewMode)
        }
    }
    val primarySerial = fullData.lastOrNull()?.sensorSerial
    val primaryIdentityColor = remember(primarySerial) {
        val logical = SensorIdentity.resolveAppSensorId(primarySerial) ?: primarySerial
        SensorColors.getColor(logical.orEmpty())
    }
    val interactionData = remember(safeData, renderData, graphSmoothingMinutes) {
        if (graphSmoothingMinutes > 0) renderData else safeData
    }
    val calibrationRevision by tk.glucodata.data.calibration.CalibrationManager.revision.collectAsState()
    val calibratedValueResolver = remember(renderData, calibrationRevision) {
        CalibratedValueResolver(renderData)
    }
    val calibrationIsRawMode = viewMode == 1 || viewMode == 3
    val visibleCalibrations = remember(calibrationRevision, calibrationIsRawMode, primarySerial) {
        tk.glucodata.data.calibration.CalibrationManager.getVisibleCalibrations(
            calibrationIsRawMode,
            primarySerial
        )
    }

    // --- FORMATTERS & TOOLS (Hoisted for Performance) ---
    val cal = remember { java.util.Calendar.getInstance() }
    val formatDate = remember { java.text.SimpleDateFormat("EEE dd", java.util.Locale.getDefault()) }

    // Reusable objects to avoid allocation on every frame
    // The ribbon is a display preference, and it is also gated on the data
    // actually carrying intervals: after switching away from Adaptive V2 the
    // stored bands are cleared, so a chart holding a stale list must not keep
    // drawing them. Re-read on every refresh rather than remembered once,
    // because the setting lives in the sensor sheet, not here.
    val uncertaintyRibbonEnabled = GlucoseUncertaintyDisplay.isRibbonEnabled()

    val reusablePath = remember { Path() }
    val reusablePeerPath = remember { Path() }
    val reusableRawPath = remember { Path() }
    val reusableAutoPath = remember { Path() }
    val reusableDate = remember { java.util.Date() }

    // Hoist intervals array to avoid allocation in Canvas loop
    val gridIntervals = remember {
        longArrayOf(
            5 * 60 * 1000L,      // 5m
            15 * 60 * 1000L,     // 15m
            30 * 60 * 1000L,     // 30m
            60 * 60 * 1000L,     // 1h
            2 * 60 * 60 * 1000L, // 2h
            4 * 60 * 60 * 1000L, // 4h
            8 * 60 * 60 * 1000L, // 8h
            12 * 60 * 60 * 1000L,// 12h
            24 * 60 * 60 * 1000L // 24h
        )
    }

    // Hoisted PathEffect for dashed lines (Zero-Allocation)
    val dashEffect = remember { androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f) }

    // Label Cache to avoid SimpleDateFormat overhead during scroll
    // Maps Timestamp -> Formatted String
    val labelCache = remember { mutableMapOf<Long, String>() }
    val axisLabelBounds = remember { android.graphics.Rect() }
    val indicatorLabelBounds = remember { android.graphics.Rect() }

    // --- VIEWPORT STATE ---
    val now = System.currentTimeMillis()
    val latestDataTimestamp = safeData.lastOrNull()?.timestamp ?: 0L
    val earliestDataTimestamp = safeData.firstOrNull()?.timestamp ?: 0L
    val dataSeriesSignature = remember(earliestDataTimestamp, latestDataTimestamp, safeData.size) {
        "$earliestDataTimestamp:$latestDataTimestamp:${safeData.size}"
    }
    val resolvedPredictionSeries = remember(predictionPoints, predictionSeries) {
        when {
            predictionSeries.isNotEmpty() -> predictionSeries
            predictionPoints.isNotEmpty() -> listOf(
                GlucosePredictionSeries(
                    kind = GlucosePredictionSeriesKind.CALIBRATED,
                    points = predictionPoints
                )
            )
            else -> emptyList()
        }
    }
    val hasPredictionOverlay = resolvedPredictionSeries.any { it.points.size >= 2 }
    val predictionEndTimestamp = resolvedPredictionSeries
        .maxOfOrNull { series -> series.points.lastOrNull()?.timestamp ?: Long.MIN_VALUE }
        ?.takeIf { it != Long.MIN_VALUE }
        ?: 0L
    val latestJournalTimelineTimestamp = remember(journalMarkers) {
        journalMarkers.maxOfOrNull { marker ->
            maxOf(marker.timestamp, marker.activeEndMillis ?: marker.timestamp)
        } ?: 0L
    }
    fun predictionLeadMillis(durationMillis: Long): Long {
        return (durationMillis * 0.18f).toLong()
            .coerceIn(15L * 60L * 1000L, 35L * 60L * 1000L)
    }

    var lastAutoScrolledTimestamp by rememberSaveable { mutableLongStateOf(0L) }
    // Jitter fix: Track the auto-scroll job to cancel it on user interaction
    var autoScrollJob by remember { mutableStateOf<Job?>(null) }

    // FIX: Single source of truth for duration, initialized from range but independent thereafter.
    // Explicit key "visibleDuration" ensures it's saved/restored correctly across recompositions and navigations.
    // NOTE: Removed 'key' parameter to fix deprecation warning. Relying on default saving.
    // We now manage 'lastAppliedTimeRange' to ensure manual zooms aren't overwritten.
    var visibleDuration by rememberSaveable { 
        mutableLongStateOf((selectedTimeRange?.hours?.toLong() ?: 3L) * 60L * 60L * 1000L) 
    }
    fun liveEndTimeFor(latestTimestamp: Long, durationMillis: Long): Long {
        if (!hasPredictionOverlay || latestTimestamp <= 0L || predictionEndTimestamp <= latestTimestamp) {
            return maxOf(latestTimestamp, System.currentTimeMillis())
        }
        val predictionLead = minOf(
            predictionLeadMillis(durationMillis),
            predictionEndTimestamp - latestTimestamp
        )
        return latestTimestamp + predictionLead
    }

    fun liveCenterTimeFor(latestTimestamp: Long, durationMillis: Long): Long {
        return liveEndTimeFor(latestTimestamp, durationMillis) - (durationMillis / 2L)
    }

    fun livePreviewCenterTimeFor(latestTimestamp: Long, durationMillis: Long): Long {
        return previewCenterTimeForWindowEnd(liveEndTimeFor(latestTimestamp, durationMillis))
    }
    
    var preZoomDuration by rememberSaveable { mutableLongStateOf(0L) } // For toggle zoom
    var centerTime by rememberSaveable { mutableLongStateOf(now - visibleDuration / 2) }
    var previewCenterTime by rememberSaveable {
        mutableLongStateOf(livePreviewCenterTimeFor(latestDataTimestamp.takeIf { it > 0L } ?: now, visibleDuration))
    }

    // Date picker state
    var showDatePicker by remember { mutableStateOf(false) }
    val datePickerState = rememberDatePickerState(
        initialSelectedDateMillis = centerTime,
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long): Boolean {
                // Only allow selecting dates we have data for
                return utcTimeMillis >= earliestDataTimestamp && utcTimeMillis <= now
            }
        }
    )

    // Auto-scroll logic: Only jump if we are explicitly RESUMED (Active)
    val lifecycleOwner = LocalLifecycleOwner.current
    var isResumed by remember { mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }

    var isUserInteracting by remember { mutableStateOf(false) }
    var lastInteractionTimestamp by rememberSaveable { mutableLongStateOf(0L) }
    var suppressDoubleTapUntil by rememberSaveable { mutableLongStateOf(0L) }

    // Limits
    val minDuration = 10L * 60 * 1000
    val maxDuration = remember(earliestDataTimestamp, latestDataTimestamp) {
        val fullSpan = (latestDataTimestamp - earliestDataTimestamp).coerceAtLeast(0L)
        maxOf(72L * 60L * 60L * 1000L, fullSpan + (2L * 60L * 60L * 1000L))
    }
    fun maxAllowedCenterTime(durationMillis: Long): Long {
        val journalAwareEnd = maxOf(System.currentTimeMillis(), latestDataTimestamp, latestJournalTimelineTimestamp)
        return if (hasPredictionOverlay && predictionEndTimestamp > journalAwareEnd) {
            predictionEndTimestamp - durationMillis / 2L
        } else {
            journalAwareEnd + (2L * 60L * 1000L)
        }
    }

    val maxAllowedTime = maxAllowedCenterTime(visibleDuration)

    fun isViewportAtLiveEdge(durationMillis: Long): Boolean {
        val latestOrNow = latestDataTimestamp.takeIf { it > 0L } ?: System.currentTimeMillis()
        val viewportEnd = centerTime + durationMillis / 2L
        val liveEnd = liveEndTimeFor(latestOrNow, durationMillis)
        return abs(viewportEnd - liveEnd) <= 10L * 60L * 1000L
    }

    fun applyTimeRangeDuration(durationMillis: Long) {
        val keepLiveEdge = isViewportAtLiveEdge(visibleDuration)
        val latestOrNow = latestDataTimestamp.takeIf { it > 0L } ?: System.currentTimeMillis()
        val targetCenter = if (keepLiveEdge) {
            liveCenterTimeFor(latestOrNow, durationMillis)
        } else {
            centerTime
        }.coerceAtMost(maxAllowedCenterTime(durationMillis))

        visibleDuration = durationMillis
        centerTime = targetCenter
        previewCenterTime = if (keepLiveEdge) {
            previewCenterTimeForWindowEnd(targetCenter + durationMillis / 2L)
        } else {
            previewCenterTimeContainingViewport(
                previewCenterTime = previewCenterTime,
                viewportCenterTime = targetCenter,
                visibleDuration = durationMillis
            )
        }
    }

    // TRACKING INACTIVITY FOR GRAPH RESET
    var lastActiveTime by rememberSaveable { mutableLongStateOf(System.currentTimeMillis()) }
    val currentLatestDataTimestamp by rememberUpdatedState(latestDataTimestamp)
    val currentSelectedTimeRange by rememberUpdatedState(selectedTimeRange)

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isResumed = true

                val currentTime = System.currentTimeMillis()
                val latestTimestamp = currentLatestDataTimestamp
                // Bound how long the chart may keep a frozen viewport. After 5 min away,
                // anchor the view to "now" even if the data flow has not re-emitted yet
                // (cold start / process death restore). Clearing lastAutoScrolledTimestamp
                // lets the dataSeriesSignature path re-snap on the next emission.
                if (currentTime - lastActiveTime > 5 * 60 * 1000L) {
                    visibleDuration = (currentSelectedTimeRange?.hours?.toLong() ?: 3L) * 60 * 60 * 1000
                    val targetCenter = if (latestTimestamp > 0L) {
                        liveCenterTimeFor(latestTimestamp, visibleDuration)
                    } else {
                        currentTime - visibleDuration / 2
                    }
                    centerTime = targetCenter
                    previewCenterTime = previewCenterTimeForWindowEnd(targetCenter + visibleDuration / 2L)
                    lastAutoScrolledTimestamp = 0L
                }
            }
            else if (event == Lifecycle.Event.ON_PAUSE) {
                isResumed = false
                lastActiveTime = System.currentTimeMillis() // Save time on pause
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    // React to parent range state only when it represents a new range button choice.
    // Range changes alter the visible duration inside the current frame; tapping the
    // already-active range button is the explicit "back to now" action.
    var lastAppliedRangeName by rememberSaveable { mutableStateOf(selectedTimeRange?.name) }

    LaunchedEffect(selectedTimeRange) {
        if (selectedTimeRange != null && selectedTimeRange.name != lastAppliedRangeName) {
            lastAppliedRangeName = selectedTimeRange.name
            val target = selectedTimeRange.hours * 60 * 60 * 1000L
            applyTimeRangeDuration(target)
        }
    }

    LaunchedEffect(dataSeriesSignature) {
        if (safeData.isEmpty() || latestDataTimestamp <= 0L) {
            return@LaunchedEffect
        }

        val switchedToOlderSeries = lastAutoScrolledTimestamp > 0L && latestDataTimestamp + 60_000L < lastAutoScrolledTimestamp

        if (lastAutoScrolledTimestamp == 0L || switchedToOlderSeries) {
            val targetCenter = liveCenterTimeFor(latestDataTimestamp, visibleDuration)
            centerTime = targetCenter
            previewCenterTime = previewCenterTimeForWindowEnd(targetCenter + visibleDuration / 2L)
            lastAutoScrolledTimestamp = latestDataTimestamp
        }
    }

    LaunchedEffect(predictionEndTimestamp, latestDataTimestamp, visibleDuration) {
        if (safeData.isEmpty() || latestDataTimestamp <= 0L || !hasPredictionOverlay || isUserInteracting) {
            return@LaunchedEffect
        }
        val currentEnd = centerTime + visibleDuration / 2
        if (abs(currentEnd - latestDataTimestamp) < 75L * 60L * 1000L) {
            val targetCenter = liveCenterTimeFor(latestDataTimestamp, visibleDuration)
            centerTime = targetCenter
            previewCenterTime = previewCenterTimeForWindowEnd(targetCenter + visibleDuration / 2L)
        }
    }

    LaunchedEffect(centerTime, visibleDuration, previewWindowMode, latestDataTimestamp, predictionEndTimestamp, isUserInteracting) {
        if (previewWindowMode == PREVIEW_WINDOW_MODE_NEVER) return@LaunchedEffect

        val viewportEnd = centerTime + visibleDuration / 2L
        val liveEnd = liveEndTimeFor(latestDataTimestamp.takeIf { it > 0L } ?: System.currentTimeMillis(), visibleDuration)
        val isLiveViewport = !isUserInteracting && abs(viewportEnd - liveEnd) <= 10L * 60L * 1000L

        previewCenterTime = if (isLiveViewport) {
            previewCenterTimeForWindowEnd(liveEnd)
        } else {
            previewCenterTimeContainingViewport(
                previewCenterTime = previewCenterTime,
                viewportCenterTime = centerTime,
                visibleDuration = visibleDuration
            )
        }
    }

    LaunchedEffect(latestDataTimestamp, isResumed, isUserInteracting, autoScrollJob, lastInteractionTimestamp) {
        if (latestDataTimestamp > lastAutoScrolledTimestamp) {
            val currentEnd = centerTime + visibleDuration / 2
            val dist = kotlin.math.abs(lastAutoScrolledTimestamp - currentEnd)
            val isMonitoring = lastAutoScrolledTimestamp == 0L || dist < 60 * 60 * 1000

            if (!isResumed) {
                if (isMonitoring) {
                    val targetCenter = liveCenterTimeFor(latestDataTimestamp, visibleDuration)
                    centerTime = targetCenter
                    previewCenterTime = previewCenterTimeForWindowEnd(targetCenter + visibleDuration / 2L)
                }
                lastAutoScrolledTimestamp = latestDataTimestamp
                return@LaunchedEffect
            }
            if (isUserInteracting || autoScrollJob != null || System.currentTimeMillis() - lastInteractionTimestamp < 1200L) {
                return@LaunchedEffect
            }

            // Keep monitoring users on live data, but preserve viewport when they were browsing history.
            if (isMonitoring) {
                val targetCenter = liveCenterTimeFor(latestDataTimestamp, visibleDuration)
                centerTime = targetCenter
                previewCenterTime = previewCenterTimeForWindowEnd(targetCenter + visibleDuration / 2L)
            }
            lastAutoScrolledTimestamp = latestDataTimestamp
        }
    }

    // Heartbeat: while the chart is visible, keep the live edge anchored to real time
    // even when no new sample arrives. Without this the viewport silently drifts behind
    // System.currentTimeMillis() (especially with the prediction overlay, whose liveEnd
    // is anchored to latestDataTimestamp + lead, not to wall-clock time).
    // Only advances when the user appears to be monitoring; history browsing and active
    // gestures are preserved. Intentionally lightweight (30s tick).
    LaunchedEffect(isResumed) {
        if (!isResumed) return@LaunchedEffect
        while (true) {
            kotlinx.coroutines.delay(30_000L)
            if (isUserInteracting || autoScrollJob != null) continue
            if (System.currentTimeMillis() - lastInteractionTimestamp < 1200L) continue

            val currentEnd = centerTime + visibleDuration / 2
            val dist = kotlin.math.abs(lastAutoScrolledTimestamp - currentEnd)
            val isMonitoring = lastAutoScrolledTimestamp == 0L || dist < 60 * 60 * 1000L
            if (!isMonitoring) continue

            val targetCenter = liveCenterTimeFor(latestDataTimestamp, visibleDuration)
            // Only advance forward, never rewind (avoids fighting other effects).
            if (targetCenter > centerTime) {
                centerTime = targetCenter
                previewCenterTime = previewCenterTimeForWindowEnd(targetCenter + visibleDuration / 2L)
            }
        }
    }

    // --- Y-AXIS STATE (Manual Scaling) ---
    val isMmol = if (unit.isNotEmpty()) tk.glucodata.ui.util.GlucoseFormatter.isMmol(unit) else tk.glucodata.ui.util.GlucoseFormatter.isMmolApp()
    val rangeThresholds = remember(isMmol, targetLow, targetHigh, veryLowThreshold, veryHighThreshold) {
        chartRangeThresholds(
            isMmol = isMmol,
            targetLow = targetLow,
            targetHigh = targetHigh,
            veryLowThreshold = veryLowThreshold,
            veryHighThreshold = veryHighThreshold
        )
    }
    val fallbackMin = 0f
    val fallbackMax = if (isMmol) 13f else 234f
    val graphRangeDefaults = resolveGraphRangeDefaults(
        requestedLow = graphLow,
        requestedHigh = graphHigh,
        fallbackLow = fallbackMin,
        fallbackHigh = fallbackMax
    )
    val minYAxisSpan = if (isMmol) 6f else 108f

    val isRawModeChart = viewMode == 1 || viewMode == 3
    val hasCalibration = calibratedValueResolver.hasCalibration(isRawModeChart)
    val hideInitialWhenCalibrated = hasCalibration &&
        tk.glucodata.data.calibration.CalibrationManager.shouldHideInitialWhenCalibrated()

    val visibleValueRange = remember(
        renderData,
        peerChartSeries,
        resolvedPredictionSeries,
        peerPredictionSeries,
        centerTime,
        visibleDuration,
        viewMode,
        hasCalibration,
        hideInitialWhenCalibrated,
        calibratedValueResolver
    ) {
        val viewportStart = centerTime - visibleDuration / 2L
        val viewportEnd = centerTime + visibleDuration / 2L
        var visibleMin = Float.POSITIVE_INFINITY
        var visibleMax = Float.NEGATIVE_INFINITY

        fun include(value: Float) {
            if (value.isFinite() && value > 0.1f) {
                visibleMin = minOf(visibleMin, value)
                visibleMax = maxOf(visibleMax, value)
            }
        }

        fun visibleIndices(points: List<GlucosePoint>): IntRange? {
            if (points.isEmpty()) return null
            val start = points.binarySearchBy(viewportStart) { it.timestamp }
                .let { if (it >= 0) it else -it - 1 }
            val endExclusive = points.binarySearchBy(viewportEnd) { it.timestamp }
                .let { if (it >= 0) it + 1 else -it - 1 }
                .coerceAtMost(points.size)
            return if (start < endExclusive) start until endExclusive else null
        }

        val drawRaw = !hideInitialWhenCalibrated && (viewMode == 1 || viewMode == 2 || viewMode == 3)
        val drawAuto = !hideInitialWhenCalibrated && (viewMode == 0 || viewMode == 2 || viewMode == 3)
        visibleIndices(renderData)?.forEach { index ->
            val point = renderData[index]
            if (drawRaw) include(point.rawValue)
            if (drawAuto) include(point.value)
            if (hasCalibration) include(calibratedValueResolver.valueAt(index, isRawModeChart))
        }

        peerChartSeries.forEach { series ->
            val drawRawPeer = series.viewMode == 1 || series.viewMode == 2 || series.viewMode == 3
            val drawAutoPeer = series.viewMode == 0 || series.viewMode == 2 || series.viewMode == 3
            visibleIndices(series.points)?.forEach { index ->
                val point = series.points[index]
                if (drawRawPeer) include(point.rawValue)
                if (drawAutoPeer) include(point.value)
            }
        }

        fun includePredictions(series: List<GlucosePredictionSeries>) {
            series.forEach { prediction ->
                prediction.points.forEach { point ->
                    if (point.timestamp in viewportStart..viewportEnd) include(point.value)
                }
            }
        }
        includePredictions(resolvedPredictionSeries)
        peerChartSeries.forEach { peer ->
            includePredictions(peerPredictionSeries[peer.sensorId].orEmpty())
        }

        visibleMin.takeIf { it.isFinite() } to visibleMax.takeIf { it.isFinite() }
    }

    // Manual scaling establishes the baseline; visible outliers may temporarily expand it.
    var baselineYMin by rememberSaveable { mutableFloatStateOf(graphRangeDefaults.first) }
    var baselineYMax by rememberSaveable { mutableFloatStateOf(graphRangeDefaults.second) }
    var isYAxisAdjusting by remember { mutableStateOf(false) }

    LaunchedEffect(graphRangeDefaults) {
        baselineYMin = graphRangeDefaults.first
        baselineYMax = graphRangeDefaults.second
    }

    val automaticYRange = remember(
        baselineYMin,
        baselineYMax,
        visibleValueRange,
        isMmol,
        isYAxisAdjusting
    ) {
        if (isYAxisAdjusting) {
            ChartYRange(min = baselineYMin, max = baselineYMax)
        } else {
            autoExpandedChartYRange(
                baselineMin = baselineYMin,
                baselineMax = baselineYMax,
                visibleMin = visibleValueRange.first,
                visibleMax = visibleValueRange.second,
                isMmol = isMmol
            )
        }
    }
    val yRangeAnimationSpec = if (isYAxisAdjusting) {
        tween<Float>(durationMillis = 0)
    } else {
        spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessLow
        )
    }
    val displayYMin by animateFloatAsState(
        targetValue = automaticYRange.min,
        animationSpec = yRangeAnimationSpec,
        label = "ChartYMinAutoRange"
    )
    val displayYMax by animateFloatAsState(
        targetValue = automaticYRange.max,
        animationSpec = yRangeAnimationSpec,
        label = "ChartYMaxAutoRange"
    )
    val renderedYMin = if (isYAxisAdjusting) baselineYMin else displayYMin
    val renderedYMax = if (isYAxisAdjusting) baselineYMax else displayYMax

    // --- INTERACTION STATE ---
    var selectedPoint by remember { mutableStateOf<GlucosePoint?>(null) }
    var isScrubbing by remember { mutableStateOf(false) } // Touching the line?
    var lastScrubHapticTimestamp by remember { mutableLongStateOf(Long.MIN_VALUE) }
    var isActiveInsulinExpanded by rememberSaveable { mutableStateOf(false) }
    var pendingTimelineTapJob by remember { mutableStateOf<Job?>(null) }

    fun cancelPendingTimelineTap() {
        pendingTimelineTapJob?.cancel()
        pendingTimelineTapJob = null
    }


    // Auto-dismiss selection if off-screen (User Request)
    LaunchedEffect(centerTime, visibleDuration, selectedPoint) {
        selectedPoint?.let { p ->
            val start = centerTime - visibleDuration / 2
            val end = centerTime + visibleDuration / 2
            // Allow a small buffer so it doesn't flicker on edge
            if (p.timestamp < start || p.timestamp > end) {
                selectedPoint = null
            }
        }
    }

    // Physics / Animation
    val coroutineScope = rememberCoroutineScope()
    val velocityTracker = remember { VelocityTracker() }
    val inertiaAnim = remember { Animatable(0f) }

    fun cancelAutoScroll() {
        autoScrollJob?.cancel()
        autoScrollJob = null
    }

    fun startAutoScrollTo(targetTime: Long) {
        cancelAutoScroll()
        val maxScroll = 12 * 60 * 60 * 1000L
        val job = coroutineScope.launch {
            val diff = targetTime - centerTime
            var startScroll = centerTime
            if (abs(diff) > maxScroll) {
                startScroll = targetTime - (if (diff > 0) maxScroll else -maxScroll)
                centerTime = startScroll
            }
            androidx.compose.animation.core.Animatable(startScroll.toFloat()).animateTo(
                targetValue = targetTime.toFloat(),
                animationSpec = spring(stiffness = Spring.StiffnessMediumLow)
            ) {
                centerTime = value.toLong()
            }
        }
        autoScrollJob = job
        job.invokeOnCompletion {
            if (autoScrollJob === job) {
                autoScrollJob = null
            }
        }
    }

//    fun performSubtleTick(isFrequent: Boolean = false) {
//        val feedbackType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
//            if (isFrequent) HapticFeedbackConstants.SEGMENT_FREQUENT_TICK else HapticFeedbackConstants.CLOCK_TICK
//        } else {
//            if (isFrequent) HapticFeedbackConstants.NO_HAPTICS else HapticFeedbackConstants.KEYBOARD_TAP
//        }
//        view.performHapticFeedback(feedbackType)
//    }

    fun performSubtleTick(isFrequent: Boolean = false) {
        val now = System.currentTimeMillis()

        // --- SAFEGUARD: DYNAMIC RATE LIMITING ---
        // High-End: 15ms gap (allows ~60 ticks/sec). Feels like "texture".
        // Low-End: 70ms gap (allows ~14 ticks/sec). Ensures motor stops spinning between ticks.
        val minGap = if (hasCrispHaptics) 15L else 70L

        if (now - lastHapticExecutionTime < minGap) {
            return // Skip this tick to save the user's sanity
        }

        lastHapticExecutionTime = now

        // --- EXECUTION ---
        if (hasCrispHaptics && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                // High-End Path: Variable amplitude
                val amplitude = if (isFrequent) 0.2f else 0.5f
                vibrator?.vibrate(
                    VibrationEffect.startComposition()
                        .addPrimitive(VibrationEffect.Composition.PRIMITIVE_LOW_TICK, amplitude)
                        .compose()
                )
            } catch (e: Exception) {
                // Fallback safe
            }
        } else {
            // Low-End Path: Standard View Haptics
            // We use KEYBOARD_TAP or VIRTUAL_KEY as they are usually shorter than CLOCK_TICK
            // on older Android versions.
            val feedbackConstant = if (isFrequent) {
                // On old phones, TEXT_HANDLE_MOVE is often the quietest standard constant
                HapticFeedbackConstants.TEXT_HANDLE_MOVE
            } else {
                HapticFeedbackConstants.KEYBOARD_TAP
            }
            view.performHapticFeedback(feedbackConstant)
        }
    }

    fun markProgrammaticViewportChange(now: Long = System.currentTimeMillis()) {
        lastInteractionTimestamp = now
        suppressDoubleTapUntil = now + 450L
    }

    // --- DATA CAPTURE FOR GESTURES ---
    // Use rememberUpdatedState to ensure the running gesture coroutine always sees the latest data
    val currentSafeData by rememberUpdatedState(safeData)
    val currentInteractionData by rememberUpdatedState(interactionData)
    val currentViewMode by rememberUpdatedState(viewMode)
    val currentCalibratedValueResolver by rememberUpdatedState(calibratedValueResolver)
    val currentRenderedYMin by rememberUpdatedState(renderedYMin)
    val currentRenderedYMax by rememberUpdatedState(renderedYMax)
    // The pointerInput lambda below is keyed on previewWindowReservedIntPx and otherwise
    // long-lived, so it would otherwise capture these as stale snapshots — when the
    // prediction horizon, zoom level, or expansion progress change, gesture clamping
    // (maxAllowedTime) and useful-width math would keep using the values from the
    // composition that started the gesture coroutine. With predictive simulation enabled
    // that desynced clamp pulls centerTime back to the old bound after zoom or back-to-now.
    val currentMaxAllowedTime by rememberUpdatedState(maxAllowedTime)
    val currentHasPredictionOverlay by rememberUpdatedState(hasPredictionOverlay)
    val currentSafeExpandedProgress by rememberUpdatedState(safeExpandedProgress)

    fun panViewportByPixels(deltaX: Float, widthPx: Float) {
        if (!deltaX.isFinite() || widthPx <= 0f) return
        val timePerPixel = visibleDuration.toFloat() / widthPx
        val timeDelta = -(deltaX * timePerPixel).toLong()
        centerTime = (centerTime + timeDelta).coerceAtMost(currentMaxAllowedTime)
    }

    // --- DATA HELPER (Fixed Interpolation) ---
    fun getPointAt(timeAtTapRaw: Double): GlucosePoint? {
        val data = currentInteractionData
        val minuteInMillis = 60000.0
        val snappedTime = (kotlin.math.round(timeAtTapRaw / minuteInMillis) * minuteInMillis).toLong()

        // Manual Binary Search (Zero-Allocation)
        var low = 0
        var high = data.size - 1
        var idx = -1
        while (low <= high) {
            val mid = (low + high) ushr 1
            val midVal = data[mid].timestamp
            if (midVal < snappedTime) low = mid + 1
            else if (midVal > snappedTime) high = mid - 1
            else { idx = mid; break }
        }
        
        if (idx >= 0) return data[idx]

        // Find closest neighbor
        val insPoint = low

        if (insPoint >= data.size) return data.lastOrNull()
        if (insPoint <= 0) return data.firstOrNull()

        val p1 = data[insPoint - 1]
        val p2 = data[insPoint]
        return if (kotlin.math.abs(p1.timestamp - snappedTime) < kotlin.math.abs(p2.timestamp - snappedTime)) p1 else p2
    }

    fun getSourcePointAt(timestamp: Long): GlucosePoint? {
        val data = currentSafeData
        if (data.isEmpty()) return null
        val index = data.binarySearchBy(timestamp) { it.timestamp }
        if (index >= 0) {
            return data[index]
        }
        val insertionPoint = -index - 1
        if (insertionPoint >= data.size) return data.lastOrNull()
        if (insertionPoint <= 0) return data.firstOrNull()
        val before = data[insertionPoint - 1]
        val after = data[insertionPoint]
        return if (kotlin.math.abs(before.timestamp - timestamp) <= kotlin.math.abs(after.timestamp - timestamp)) {
            before
        } else {
            after
        }
    }

    LaunchedEffect(interactionData, centerTime, visibleDuration, selectedPoint, onViewportSnapshotChanged) {
        val callback = onViewportSnapshotChanged ?: return@LaunchedEffect
        val viewportStart = centerTime - visibleDuration / 2
        val viewportEnd = centerTime + visibleDuration / 2
        val visiblePoints = interactionData
            .sliceByTimestampRange(viewportStart, viewportEnd)
            .mapNotNull { renderPoint -> getSourcePointAt(renderPoint.timestamp) }
            .distinctBy { it.timestamp }
        callback(
            ChartViewportSnapshot(
                startMillis = viewportStart,
                endMillis = viewportEnd,
                visiblePoints = visiblePoints,
                selectedPoint = selectedPoint?.let { getSourcePointAt(it.timestamp) ?: it }
            )
        )
    }

    fun performScrubHaptic(point: GlucosePoint?) {
        if (point == null) return
        if (point.timestamp != lastScrubHapticTimestamp) {
            performSubtleTick(isFrequent = true)
            lastScrubHapticTimestamp = point.timestamp
        }
    }





    Column(modifier = Modifier.fillMaxSize()) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .pointerInput(previewWindowReservedIntPx) {
                    // Manual Gesture Handler for:
                    // 1. Pan / Inertia
                    // 2. Pinch Zoom
                    // 3. One-Finger Zoom (Double-Tap + Drag)
                    // 4. Tap to Select

                    var lastGestureWasTap = false
                    var lastTapTime = 0L
                    var lastTapPos = Offset.Zero

                    awaitEachGesture {
                        var dismissedJournalActionForGesture = false
                        fun dismissJournalActionIfNeeded() {
                            if (!dismissedJournalActionForGesture) {
                                onDismissJournalAction?.invoke()
                                dismissedJournalActionForGesture = true
                            }
                        }
                        isUserInteracting = true
                        isYAxisAdjusting = false
                        try {
                            // FIX: Use requireUnconsumed = true (default) to respect z-order.
                            // This prevents the chart from hijacking touches meant for the floating buttons.
                            val down = awaitFirstDown()
                            val gestureStartTime = System.currentTimeMillis()
                            lastInteractionTimestamp = gestureStartTime
                            cancelAutoScroll()
                            val previewIsVisible = previewRevealProgress > 0.02f
                            val previewHorizontalPaddingPx = PreviewWindowOuterPadding.toPx()
                            val previewVerticalPaddingPx = PreviewWindowOuterPadding.toPx()
                            val previewHeightPx = PreviewWindowHeight.toPx()
                            val previewTop =
                                size.height.toFloat() - previewVerticalPaddingPx - previewHeightPx
                            val previewBottom = size.height.toFloat() - previewVerticalPaddingPx
                            val previewLeft = previewHorizontalPaddingPx
                            val previewRight =
                                size.width.toFloat() - previewHorizontalPaddingPx
                            val isPreviewTouch = previewIsVisible &&
                                down.position.x in previewLeft..previewRight &&
                                down.position.y in previewTop..previewBottom
                            if (isPreviewTouch) {
                                dismissJournalActionIfNeeded()
                                val previewSafeWidth =
                                    (previewRight - previewLeft).coerceAtLeast(1f)
                                val previewDuration = PREVIEW_WINDOW_DURATION_MS
                                val previewHalfDuration = previewDuration / 2
                                val gestureStartCenter = centerTime
                                val gestureStartPreviewCenter = previewCenterTime
                                val gesturePreviewStart = gestureStartPreviewCenter - previewHalfDuration
                                val downLocalX =
                                    (down.position.x - previewLeft).coerceIn(0f, previewSafeWidth)

                                fun adjustedPreviewCenter(
                                    targetCenter: Long,
                                    basePreviewCenter: Long
                                ): Long {
                                    if (visibleDuration >= previewDuration) {
                                        return targetCenter
                                    }

                                    val basePreviewStart = basePreviewCenter - previewHalfDuration
                                    val basePreviewEnd = basePreviewCenter + previewHalfDuration
                                    val targetStart = targetCenter - visibleDuration / 2
                                    val targetEnd = targetCenter + visibleDuration / 2
                                    return when {
                                        targetStart < basePreviewStart -> targetStart + previewHalfDuration
                                        targetEnd > basePreviewEnd -> targetEnd - previewHalfDuration
                                        else -> basePreviewCenter
                                    }
                                }

                                fun updatePreviewViewport(localX: Float) {
                                    val fraction = (localX / previewSafeWidth).coerceIn(0f, 1f)
                                    val targetCenter =
                                        (gesturePreviewStart + (previewDuration * fraction)).toLong()
                                    val maxAllowed = currentMaxAllowedTime
                                    centerTime = targetCenter.coerceAtMost(maxAllowed)
                                    previewCenterTime = adjustedPreviewCenter(
                                        centerTime,
                                        gestureStartPreviewCenter
                                    ).coerceAtMost(maxAllowed)
                                }

                                val windowStartTime = gestureStartCenter - visibleDuration / 2
                                val windowEndTime = gestureStartCenter + visibleDuration / 2
                                val windowLeft =
                                    (((windowStartTime - gesturePreviewStart).toFloat() / previewDuration.toFloat()) * previewSafeWidth)
                                        .coerceIn(0f, previewSafeWidth)
                                val windowRight =
                                    (((windowEndTime - gesturePreviewStart).toFloat() / previewDuration.toFloat()) * previewSafeWidth)
                                        .coerceIn(0f, previewSafeWidth)
                                val minimumWindowWidthPx = 12.dp.toPx()
                                val effectiveWindowRight = maxOf(windowRight, windowLeft + minimumWindowWidthPx)
                                val draggingWindow = downLocalX in windowLeft..effectiveWindowRight

                                down.consume()
                                markProgrammaticViewportChange()
                                performSubtleTick(isFrequent = true)

                                if (!draggingWindow) {
                                    updatePreviewViewport(downLocalX)
                                }

                                while (true) {
                                    val previewEvent = awaitPointerEvent()
                                    val previewChange = previewEvent.changes.firstOrNull() ?: break
                                    if (!previewChange.pressed) break
                                    val localX =
                                        (previewChange.position.x - previewLeft).coerceIn(
                                            0f,
                                            previewSafeWidth
                                        )
                                    if (draggingWindow) {
                                        val totalDeltaX = localX - downLocalX
                                        val totalDeltaMs =
                                            ((totalDeltaX / previewSafeWidth) * previewDuration.toFloat()).toLong()
                                        val maxAllowed = currentMaxAllowedTime
                                        centerTime =
                                            (gestureStartCenter + totalDeltaMs).coerceAtMost(
                                                maxAllowed
                                            )
                                        previewCenterTime = adjustedPreviewCenter(
                                            centerTime,
                                            gestureStartPreviewCenter
                                        ).coerceAtMost(maxAllowed)
                                    } else {
                                        updatePreviewViewport(localX)
                                    }
                                    previewEvent.changes.forEach { it.consume() }
                                }
                                return@awaitEachGesture
                            }
                            if (isAdjustingScrubLabel) {
                                down.consume()
                                while (true) {
                                    val holdEvent = awaitPointerEvent()
                                    holdEvent.changes.forEach { it.consume() }
                                    if (holdEvent.changes.any { it.changedToUp() }) break
                                }
                                return@awaitEachGesture
                            }

                            // STRICT DOUBLE TAP DETECTION
                            // Only trigger if:
                            // 1. Previous gesture was a tap (not a scroll)
                            // 2. Short duration since then (<300ms)
                            // 3. Close spatial proximity (<100px)
                            val isDoubleTapStart = gestureStartTime >= suppressDoubleTapUntil &&
                                    lastGestureWasTap &&
                                    (gestureStartTime - lastTapTime < 300) &&
                                    (down.position - lastTapPos).getDistance() < 100.dp.toPx()

                            if (isDoubleTapStart) {
                                cancelPendingTimelineTap()
                            }

                            var isOneFingerZoom = isDoubleTapStart

                            // Kill inertia
                            coroutineScope.launch { inertiaAnim.snapTo(0f) }
                            velocityTracker.resetTracking()

                            // --- HIT TEST (Hit Logic reused) ---
                            val width = size.width.toFloat()
                            val rightPaddingPx = if (currentHasPredictionOverlay) 0f else (16.dp.toPx() * currentSafeExpandedProgress)
                            val usefulWidth = (width - rightPaddingPx).coerceAtLeast(1f)
                            val contentHeight =
                                (size.height.toFloat() - chartUnderlayBottomPx - previewWindowReservedPx).coerceAtLeast(1f)
                            val chartHeight =
                                (contentHeight - 32.dp.toPx() - chartPlotBottomGapPx).coerceAtLeast(
                                    1f
                                )
                            val downDuration = visibleDuration
                            val downCenterTime = centerTime
                            val viewportStart = downCenterTime - downDuration / 2
                            val downX = down.position.x.coerceIn(0f, usefulWidth)
                            val timeAtTouch =
                                viewportStart + ((downX / usefulWidth).toDouble() * downDuration)
                            val pointAtTouch = getPointAt(timeAtTouch)
                            var touchThreshold = 32.dp.toPx()
                            fun buildTimelineTapSuggestion(position: Offset, forceMenu: Boolean): ChartTimelineTapSuggestion? {
                                if (position.y > chartHeight) return null
                                val clampedX = position.x.coerceIn(0f, usefulWidth)
                                val tapTime = viewportStart + ((clampedX / usefulWidth).toDouble() * downDuration)
                                val normalizedY = 1f - (position.y / chartHeight).coerceIn(0f, 1f)
                                val suggestedValue = if (currentRenderedYMax - currentRenderedYMin < 0.001f) {
                                    currentRenderedYMin
                                } else {
                                    (normalizedY * (currentRenderedYMax - currentRenderedYMin)) + currentRenderedYMin
                                }
                                return ChartTimelineTapSuggestion(
                                    timestamp = tapTime.toLong(),
                                    suggestedDisplayGlucose = suggestedValue,
                                    normalizedYFraction = normalizedY.coerceIn(0f, 1f),
                                    forceMenu = forceMenu
                                )
                            }

                            var latestTouchPosition = down.position
                            var longPressTriggered = false
                            var change = down
                            var totalDragDistance = 0f
                            var accumulatedPanX = 0f
                            var accumulatedPanY = 0f
                            var lockedPanAxis = 0 // 0 undecided, 1 horizontal, 2 vertical
                            var yGestureStartMin = 0f
                            var yGestureStartMax = 0f
                            var yGestureAdjustsMax = false
                            var lastPointerCount = 1
                            val longPressJob = if (onTimelineTap != null && !isDoubleTapStart) {
                                coroutineScope.launch {
                                    kotlinx.coroutines.delay(viewConfiguration.longPressTimeoutMillis.toLong())
                                    if (!longPressTriggered && totalDragDistance < viewConfiguration.touchSlop) {
                                        buildTimelineTapSuggestion(latestTouchPosition, forceMenu = true)?.let { suggestion ->
                                            dismissJournalActionIfNeeded()
                                            cancelPendingTimelineTap()
                                            selectedPoint = null
                                            onTimelineTap.invoke(suggestion)
                                            longPressTriggered = true
                                        }
                                    }
                                }
                            } else {
                                null
                            }

                            // Only allow scrubbing if purely single tap start (not double tap sequence)
                            isScrubbing = if (pointAtTouch != null && !isOneFingerZoom) {
                                val timeDiff = timeAtTouch - pointAtTouch.timestamp
                                if (timeDiff > 15 * 60 * 1000) false else {
                                    // When calibration is on and is primary, use calibrated value for touch target
                                    val isRawMode = currentViewMode == 1 || currentViewMode == 3
                                    val hasCalibration = currentCalibratedValueResolver.hasCalibration(isRawMode)
                                    val v = if (hasCalibration) {
                                        currentCalibratedValueResolver.valueForPoint(pointAtTouch, isRawMode)
                                    } else if (isRawMode) {
                                        pointAtTouch.rawValue
                                    } else {
                                        pointAtTouch.value
                                    }
                                    val liveYMin = currentRenderedYMin
                                    val liveYMax = currentRenderedYMax
                                    val dataY =
                                        chartHeight - ((v - liveYMin) / (liveYMax - liveYMin)) * chartHeight
                                    abs(down.position.y - dataY) < touchThreshold
                                }
                            } else {
                                false
                            }

                            if (isScrubbing) {
                                dismissJournalActionIfNeeded()
                                selectedPoint = pointAtTouch
                                performScrubHaptic(pointAtTouch)
                            }

                            while (true) {
                                val event = awaitPointerEvent()
                                val pointerCount = event.changes.count { it.pressed }
                                val newChange = event.changes.firstOrNull { it.pressed }
                                    ?: event.changes.firstOrNull() ?: break
                                latestTouchPosition = newChange.position
                                if (pointerCount == 0 || newChange.changedToUp()) break
                                if (pointerCount != lastPointerCount) {
                                    longPressJob?.cancel()
                                    lockedPanAxis = 0
                                    accumulatedPanX = 0f
                                    accumulatedPanY = 0f
                                    isYAxisAdjusting = false
                                    totalDragDistance = maxOf(totalDragDistance, viewConfiguration.touchSlop)
                                    change = newChange
                                    lastPointerCount = pointerCount
                                    velocityTracker.resetTracking()
                                    continue
                                }
                                if (isAdjustingScrubLabel) {
                                    longPressJob?.cancel()
                                    event.changes.forEach { it.consume() }
                                    change = newChange
                                    continue
                                }
                                if (longPressTriggered) {
                                    event.changes.forEach { it.consume() }
                                    change = newChange
                                    continue
                                }

                                velocityTracker.addPointerInputChange(newChange)
                                if (pointerCount > 1) {
                                    longPressJob?.cancel()
                                    isOneFingerZoom = false
                                }

                                if (isOneFingerZoom && pointerCount == 1) {
                                    longPressJob?.cancel()
                                    // ONE FINGER ZOOM MODE (Double-Tap-Drag)
                                    val panY = newChange.position.y - change.position.y

                                    // Only apply zoom if there's meaningful vertical movement
                                    if (abs(panY) > 2f) {
                                        dismissJournalActionIfNeeded()
                                        // EXPONENTIAL ZOOM (Smoother feel)
                                        // panY > 0 (Down) -> Zoom IN (Duration shrinks)
                                        // Form: newDur = oldDur * exp(-panY * sensitivity)
                                        val zoomSensitivity =
                                            3f / contentHeight // Adjust constant for speed
                                        val zoomFactor = kotlin.math.exp(-panY * zoomSensitivity)

                                        val newDuration = (visibleDuration * zoomFactor).toLong()
                                        visibleDuration =
                                            newDuration.coerceIn(minDuration, maxDuration)
                                        totalDragDistance += abs(panY) // Mark as dragged, not tap
                                    }
                                    newChange.consume()

                                } else if (pointerCount > 1) {
                                    longPressJob?.cancel()
                                    dismissJournalActionIfNeeded()
                                    // 2-FINGER ZOOM
                                    totalDragDistance += viewConfiguration.touchSlop
                                    val zoomChange = event.calculateZoom()
                                    if (zoomChange != 1f) {
                                        val effectiveZoom = 1f + (zoomChange - 1f) * 2.0f
                                        val newDuration = (visibleDuration / effectiveZoom).toLong()
                                        visibleDuration =
                                            newDuration.coerceIn(minDuration, maxDuration)
                                    }
                                    event.changes.forEach { it.consume() }
                                } else {
                                    // 1-FINGER PAN / SCRUB
                                    if (isScrubbing) {
                                        val scrubDx = newChange.position.x - change.position.x
                                        val scrubDy = newChange.position.y - change.position.y
                                        totalDragDistance += kotlin.math.sqrt(scrubDx * scrubDx + scrubDy * scrubDy)
                                        if (totalDragDistance > viewConfiguration.touchSlop) {
                                            longPressJob?.cancel()
                                        }
                                        val clampedX =
                                            newChange.position.x.coerceIn(0f, usefulWidth)
                                        val currentFrac = (clampedX / usefulWidth).toDouble()
                                        val currentViewportStart = centerTime - visibleDuration / 2
                                        val currentTime =
                                            currentViewportStart + (currentFrac * visibleDuration)
                                        val updatedPoint = getPointAt(currentTime)
                                        selectedPoint = updatedPoint
                                        performScrubHaptic(updatedPoint)
                                    } else {
                                        val panX = newChange.position.x - change.position.x
                                        val panY = newChange.position.y - change.position.y
                                        val dragDist = kotlin.math.sqrt(panX * panX + panY * panY)
                                        totalDragDistance += dragDist
                                        accumulatedPanX += panX
                                        accumulatedPanY += panY

                                        val displacement = kotlin.math.sqrt(
                                            accumulatedPanX * accumulatedPanX + accumulatedPanY * accumulatedPanY
                                        )
                                        if (displacement > viewConfiguration.touchSlop) {
                                            longPressJob?.cancel()
                                            dismissJournalActionIfNeeded()
                                            if (lockedPanAxis == 0) {
                                                lockedPanAxis = if (abs(accumulatedPanX) > abs(accumulatedPanY)) 1 else 2
                                                if (lockedPanAxis == 2) {
                                                    yGestureStartMin = currentRenderedYMin
                                                    yGestureStartMax = currentRenderedYMax
                                                    yGestureAdjustsMax = newChange.position.y < contentHeight / 2f
                                                    baselineYMin = yGestureStartMin
                                                    baselineYMax = yGestureStartMax
                                                    isYAxisAdjusting = true
                                                }
                                            }
                                        }

                                        if (lockedPanAxis == 1) {
                                            // Horizontal pan
                                            panViewportByPixels(panX, usefulWidth)
                                        } else if (lockedPanAxis == 2 && abs(accumulatedPanY) > 30f) {
                                            // Vertical scale
                                            val manualRange = manuallyAdjustedChartYRange(
                                                startMin = yGestureStartMin,
                                                startMax = yGestureStartMax,
                                                totalDragY = accumulatedPanY,
                                                chartHeight = contentHeight,
                                                adjustsMax = yGestureAdjustsMax,
                                                minimumSpan = minYAxisSpan
                                            )
                                            baselineYMin = manualRange.min
                                            baselineYMax = manualRange.max
                                        }
                                    }
                                    newChange.consume()
                                }
                                change = newChange
                                lastPointerCount = pointerCount
                            }

                            // ON UP
                            val wasTap = totalDragDistance < viewConfiguration.touchSlop
                            longPressJob?.cancel()
                            lastGestureWasTap = wasTap && !isOneFingerZoom && !isScrubbing && !longPressTriggered

                            if (wasTap) {
                                lastTapTime = System.currentTimeMillis()
                                lastTapPos = change.position
                            }

                            if (!isScrubbing) {
                                if (wasTap && !longPressTriggered) {
                                    // TAP DETECTED
                                    if (isOneFingerZoom) {
                                        cancelPendingTimelineTap()
                                        // DOUBLE TAP TOGGLE ZOOM
                                        if (preZoomDuration > 0) {
                                            visibleDuration = preZoomDuration
                                            preZoomDuration = 0L
                                        } else {
                                            preZoomDuration = visibleDuration
                                            visibleDuration = (visibleDuration / 2f).toLong()
                                                .coerceIn(minDuration, maxDuration)
                                        }
                                    } else {
                                        // SINGLE TAP (Selection)
                                        val isFutureTap = pointAtTouch != null &&
                                                pointAtTouch.timestamp == currentSafeData.lastOrNull()?.timestamp &&
                                                timeAtTouch > pointAtTouch.timestamp

                                        if (isFutureTap && onTimelineTap != null) {
                                            buildTimelineTapSuggestion(down.position, forceMenu = false)?.let { suggestion ->
                                                cancelPendingTimelineTap()
                                                selectedPoint = null
                                                pendingTimelineTapJob = coroutineScope.launch {
                                                    kotlinx.coroutines.delay(280L)
                                                    onTimelineTap.invoke(
                                                        suggestion.copy(
                                                            timestamp = suggestion.timestamp.coerceAtMost(
                                                                System.currentTimeMillis()
                                                            )
                                                        )
                                                    )
                                                }
                                            }
                                        } else if (isFutureTap) {
                                            dismissJournalActionIfNeeded()
                                            selectedPoint = pointAtTouch
                                        } else {
                                            if (selectedPoint != null) {
                                                cancelPendingTimelineTap()
                                                selectedPoint = null
                                            } else {
                                                buildTimelineTapSuggestion(down.position, forceMenu = false)?.let { suggestion ->
                                                    cancelPendingTimelineTap()
                                                    pendingTimelineTapJob = coroutineScope.launch {
                                                        kotlinx.coroutines.delay(280L)
                                                        onTimelineTap?.invoke(suggestion)
                                                    }
                                                }
                                            }
                                        }
                                    }
                                } else if (!isOneFingerZoom && lockedPanAxis != 2) {
                                    // FLING - simple defaults
                                    val velocity = velocityTracker.calculateVelocity()
                                    val vx = velocity.x
                                    if (abs(vx) > 1000f) { // High threshold - ignore small movements
                                        // Velocity-dependent boost: fast swipes get more acceleration
                                        val boost =
                                            if (abs(vx) > 3000f) 2f else if (abs(vx) > 2000f) 1.5f else 1f
                                        coroutineScope.launch {
                                            var lastVal = 0f
                                            inertiaAnim.snapTo(0f)
                                            inertiaAnim.animateDecay(
                                                initialVelocity = -vx * boost,
                                                animationSpec = exponentialDecay(frictionMultiplier = 2.0f) // Higher friction = stops faster
                                            ) {
                                                val delta = this.value - lastVal
                                                val rightPaddingPx =
                                                    if (currentHasPredictionOverlay) 0f else (16.dp.toPx() * currentSafeExpandedProgress)
                                                val usefulWidth =
                                                    (size.width.toFloat() - rightPaddingPx).coerceAtLeast(
                                                        1f
                                                    )
                                                val tPerPix =
                                                    visibleDuration.toFloat() / usefulWidth
                                                centerTime =
                                                    (centerTime + (delta * tPerPix).toLong()).coerceAtMost(
                                                        currentMaxAllowedTime
                                                    )
                                                lastVal = this.value
                                            }
                                        }
                                    }
                                }
                            }
                        } finally {
                            isYAxisAdjusting = false
                            isUserInteracting = false
                            lastInteractionTimestamp = System.currentTimeMillis()
                        }
                    }
                }
        ) {
            // Smooth zoom animation (DO NOT TOUCH)
            val animatedVisibleDuration by animateFloatAsState(
                targetValue = visibleDuration.toFloat(),
                animationSpec = spring<Float>(stiffness = Spring.StiffnessMedium),
                label = "ChartZoomAnimation"
            )
            val journalActionIndicatorProgress by animateFloatAsState(
                targetValue = if (journalActionTimestamp != null && selectedPoint == null) 1f else 0f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioLowBouncy,
                    stiffness = Spring.StiffnessLow
                ),
                label = "JournalActionIndicatorProgress"
            )

            // Calculate gradient brush logic outside Canvas (Optimized)
            // Pre-calculate stops based on Y position (requires mapping High/Low to Y)
            val heightPx = constraints.maxHeight.toFloat()
            val chartHeightPx = (heightPx - chartUnderlayBottomPx - previewWindowReservedPx - bottomAxisHeightPx - chartPlotBottomGapPx).coerceAtLeast(1f)
            
            val ySpanForGradient = renderedYMax - renderedYMin
            fun thresholdY(value: Float): Float {
                return if (ySpanForGradient > 0.001f) {
                    (chartHeightPx * (1f - (value - renderedYMin) / ySpanForGradient)).coerceIn(-2000f, chartHeightPx + 2000f)
                } else {
                    0f
                }
            }

            val limitYVeryHigh = thresholdY(rangeThresholds.veryHigh)
            val limitYHigh = thresholdY(rangeThresholds.high)
            val limitYLow = thresholdY(rangeThresholds.low)
            val limitYVeryLow = thresholdY(rangeThresholds.veryLow)

            // Multi-sensor: the primary trace carries a subtle identity tint so
            // it pairs with its (tinted) values, like the peer traces do.
            val primaryLineTintFraction = if (peerChartSeries.isNotEmpty()) 0.22f else 0f
            val gradientBrush = remember(
                limitYVeryHigh,
                limitYHigh,
                limitYLow,
                limitYVeryLow,
                chartHeightPx,
                chartBandPalette,
                primaryLineTintFraction,
                primaryIdentityColor
            ) {
                if (chartHeightPx <= 0f) {
                    Brush.linearGradient(listOf(Color.Transparent, Color.Transparent))
                } else {
                    fun identityTinted(color: Color): Color =
                        if (primaryLineTintFraction > 0f) {
                            androidx.compose.ui.graphics.lerp(color, primaryIdentityColor, primaryLineTintFraction)
                        } else {
                            color
                        }

                    // Range-color mode uses the same five effective colors shown
                    // in settings, including every per-band override.
                    val veryHighTint = identityTinted(chartBandPalette.veryHigh)
                    val highTint = identityTinted(chartBandPalette.high)
                    val lowTint = identityTinted(chartBandPalette.low)
                    val veryLowTint = identityTinted(chartBandPalette.veryLow)
                    val inRangeTint = identityTinted(chartBandPalette.inRange)
                    // Stop geometry lives in GlucoseChartBands so the watch's
                    // curve bands on the same lines this one does.
                    val stops = GlucoseChartBands.verticalStops(
                        veryHigh = veryHighTint,
                        high = highTint,
                        inRange = inRangeTint,
                        low = lowTint,
                        veryLow = veryLowTint,
                        yVeryHigh = limitYVeryHigh,
                        yHigh = limitYHigh,
                        yLow = limitYLow,
                        yVeryLow = limitYVeryLow,
                        chartHeightPx = chartHeightPx
                    )

                    Brush.verticalGradient(
                        *stops.toTypedArray(),
                        startY = 0f,
                        endY = chartHeightPx
                    )
                }
            }
            val peerBrushes = remember(
                peerChartSeries,
                limitYVeryHigh,
                limitYHigh,
                limitYLow,
                limitYVeryLow,
                chartHeightPx,
                highOutOfRangeTintBase,
                lowOutOfRangeTintBase,
                peerNeutralBase,
                glucosePaletteRevision
            ) {
                if (chartHeightPx <= 0f) {
                    emptyMap()
                } else {
                    val veryHighTint = Color(GlucoseRangeColors.veryHigh(isDark))
                    val veryLowTint = Color(GlucoseRangeColors.veryLow(isDark))
                    val fadePx = 18f
                    peerChartSeries.associate { series ->
                        // Tone down the coloring (desaturate toward the neutral
                        // theme token) while keeping the trace clearly visible.
                        val base = androidx.compose.ui.graphics.lerp(series.color, peerNeutralBase, 0.46f)
                        val normal = base.copy(alpha = 0.76f)
                        val high = androidx.compose.ui.graphics.lerp(base, highOutOfRangeTintBase, 0.48f).copy(alpha = 0.72f)
                        val veryHigh = androidx.compose.ui.graphics.lerp(base, veryHighTint, 0.58f).copy(alpha = 0.74f)
                        val low = androidx.compose.ui.graphics.lerp(base, lowOutOfRangeTintBase, 0.48f).copy(alpha = 0.72f)
                        val veryLow = androidx.compose.ui.graphics.lerp(base, veryLowTint, 0.58f).copy(alpha = 0.74f)
                        val stops = mutableListOf<Pair<Float, Color>>()

                        fun addStop(y: Float, color: Color) {
                            stops += (y / chartHeightPx).coerceIn(0f, 1f) to color
                        }

                        addStop(0f, veryHigh)
                        addStop(limitYVeryHigh, veryHigh)
                        addStop(limitYHigh, high)
                        addStop(limitYHigh + fadePx, normal)
                        addStop(limitYLow - fadePx, normal)
                        addStop(limitYLow, low)
                        addStop(limitYVeryLow, veryLow)
                        addStop(chartHeightPx, veryLow)

                        series.sensorId to Brush.verticalGradient(
                            *stops.sortedBy { it.first }.toTypedArray(),
                            startY = 0f,
                            endY = chartHeightPx
                        )
                    }
                }
            }

            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        compositingStrategy =
                            androidx.compose.ui.graphics.CompositingStrategy.ModulateAlpha
                    }
            ) {
                val width = size.width
                val rightPaddingPx = if (hasPredictionOverlay) 0f else (16.dp.toPx() * safeExpandedProgress)
                val dataWidth = (width - rightPaddingPx).coerceAtLeast(1f)
                val contentHeight = (size.height - chartUnderlayBottomPx - previewWindowReservedPx).coerceAtLeast(1f)
                val bottomAxisHeight = 32.dp.toPx()
                val chartHeight = (contentHeight - bottomAxisHeight - chartPlotBottomGapPx).coerceAtLeast(1f)
                if (labelCache.size > 200) labelCache.clear()

                // Viewport Logic (STABLE)
                // Use animated duration for smooth zoom, but ensure logic matches
                val animDur = animatedVisibleDuration.coerceAtLeast(minDuration.toFloat())
                val currentDur = animDur.toLong()
                
                // Calculate Viewport
                val viewportStart = centerTime - currentDur / 2
                val viewportEnd = centerTime + currentDur / 2
                
                // Data Access (Strict Guards)
                if (renderData.isEmpty()) return@Canvas

                // 2. SEARCH RANGE (With ample padding to prevent popping)
                val padding = currentDur / 2 // 50% padding on each side
                val searchStart = viewportStart - padding
                val searchEnd = viewportEnd + padding

                // 3. BINARY SEARCH (Standard Library)
                // binarySearchBy returns: index if found, or -(insertion point) - 1
                // Calculate visible range indices with padding for connecting lines
                // startIdx: Include point just BEFORE viewport start
                val startIdx = renderData.binarySearchBy(searchStart) { it.timestamp }
                    .let { if (it < 0) -it - 2 else it } // if not found, insertion point - 1
                    .coerceIn(0, renderData.size)
                
                // endIdx: Include point just AFTER viewport end (for exclusive loop)
                val endIdx = renderData.binarySearchBy(searchEnd) { it.timestamp }
                    .let { if (it < 0) -it else it + 1 } // if not found, insertion point + 1
                    .coerceIn(startIdx, renderData.size)

                // 4. COORDINATE MAPPING (Inline for performance)
                // Maps timestamp to X relative to CURRENT viewport
                fun timeToX(t: Long): Float {
                    return ((t - viewportStart).toFloat() / animDur) * width
                }
                fun timeToDataX(t: Long): Float {
                    return ((t - viewportStart).toFloat() / animDur) * dataWidth
                }

                // Maps Value to Y (Inverted: High value = Low Y)
                // Hoist state reads for performance loop
                val cYMin = renderedYMin
                val cYMax = renderedYMax
                val cYRange = cYMax - cYMin
                
                fun valToY(v: Float): Float {
                    if (cYRange < 0.001f) return chartHeight / 2 // Prevent div/0
                    val y = chartHeight - ((v - cYMin) / cYRange) * chartHeight
                    return y.coerceIn(-2000f, chartHeight + 2000f) // Clamp for safety against huge values
                }
                fun yToVal(y: Float): Float {
                    if (cYRange < 0.001f) return cYMin
                    val normalized = 1f - (y / chartHeight).coerceIn(0f, 1f)
                    return (normalized * cYRange) + cYMin
                }

                // --- 1. DRAW Y-AXIS GRID ---
                val yStep = if (cYRange < 25) 2f else 50f
                var yVal = (kotlin.math.ceil(cYMin / yStep) * yStep).toInt() // integer steps
                
                while (yVal < cYMax) {
                    val y = valToY(yVal.toFloat())
                    if (y in 0f..chartHeight) {
                        drawLine(gridColor, Offset(0f, y), Offset(width, y), 1f)
                        // Text - Vertically centered with grid line, 4dp left padding
                        val labelText = yVal.toString()
                        axisTextPaint.getTextBounds(labelText, 0, labelText.length, axisLabelBounds)
                        val centeredY = y + (axisLabelBounds.height() / 2f)
                        val labelPadding = 16f * density
                        val textWidth = axisTextPaint.measureText(labelText)
                        val backgroundPadH = 8f * density
                        val backgroundPadV = 3f * density
                        drawRoundRect(
                            color = axisLabelBackgroundColor,
                            topLeft = Offset(
                                x = labelPadding - backgroundPadH,
                                y = centeredY - axisLabelBounds.height() - backgroundPadV
                            ),
                            size = androidx.compose.ui.geometry.Size(
                                width = textWidth + (backgroundPadH * 2f),
                                height = axisLabelBounds.height().toFloat() + (backgroundPadV * 2f)
                            ),
                            cornerRadius = CornerRadius(8f * density, 8f * density)
                        )
                        drawContext.canvas.nativeCanvas.drawText(
                            labelText, labelPadding, centeredY, axisTextPaint
                        )
                    }
                    yVal += yStep.toInt()
                }

                // --- 2. DRAW X-AXIS GRID ---
                // Intervals: 5m, 15m, 30m, 1h, 2h, 4h, 8h, 12h, 24h
                // Pick interval keeping labels ~120px apart
                val pxPerMs = width / animDur
                val minMs = (120f / pxPerMs).toLong()
                val gridInterval = gridIntervals.firstOrNull { it >= minMs } ?: gridIntervals.last()

                // Align t to timezone day boundary
                val tzOffset = java.util.TimeZone.getDefault().getOffset(viewportStart).toLong()
                
                // Start drawing from just before viewport to cover edge
                var tGrid = ((viewportStart + tzOffset) / gridInterval) * gridInterval - tzOffset
                if (tGrid < viewportStart) tGrid += gridInterval

                // Limit loop to prevent freeze if interval is tiny (sanity check)
                val loopLimit = viewportEnd + gridInterval
                
                // Safety: Don't draw if interval is dangerously small 
                 if (gridInterval > 1000L) {
                    while (tGrid <= loopLimit) {
                        val x = timeToX(tGrid)
                        if (x > -50f && x < width + 50f) {
                            cal.timeInMillis = tGrid
                            val h = cal.get(java.util.Calendar.HOUR_OF_DAY)
                            val m = cal.get(java.util.Calendar.MINUTE)
                            val isMidnight = h == 0 && m == 0
                            
                            if (isMidnight) {
                                // Date Line
                                drawLine(gridColor.copy(alpha=0.8f), Offset(x, 0f), Offset(x, chartHeight), 3f)
                                val dateLabel = labelCache.getOrPut(tGrid) {
                                    reusableDate.time = tGrid
                                    formatDate.format(reusableDate)
                                }
                                drawContext.canvas.nativeCanvas.drawText(
                                    dateLabel, x, contentHeight - labelsLiftPx - 25f,
                                    xTextPaint.apply { typeface = graphFontBold }
                                )
                                xTextPaint.typeface = graphFont
                            } else {
                                // Time Line
                                drawLine(gridColor, Offset(x, 0f), Offset(x, chartHeight), 1f)
                                val timeLabel = labelCache.getOrPut(tGrid) {
                                    val hh = if (h < 10) "0$h" else h.toString()
                                    val mm = if (m < 10) "0$m" else m.toString()
                                    "$hh:$mm"
                                }
                                drawContext.canvas.nativeCanvas.drawText(timeLabel, x, contentHeight - labelsLiftPx - 28f, xTextPaint)
                            }
                        }
                        tGrid += gridInterval
                    }
                }

                if (peerChartSeries.isNotEmpty()) {
                    val peerGapThreshold = ChartGap.THRESHOLD_MS
                    val peerStroke = 2.dp.toPx()
                    fun drawPeerSeriesLine(
                        series: PeerSensorChartSeries,
                        useRaw: Boolean,
                        alpha: Float,
                        strokeWidth: Float
                    ) {
                        val points = series.points
                        if (points.size < 2) return
                        val peerStartIdx = points.binarySearchBy(searchStart) { it.timestamp }
                            .let { if (it < 0) -it - 2 else it }
                            .coerceIn(0, points.size)
                        val peerEndIdx = points.binarySearchBy(searchEnd) { it.timestamp }
                            .let { if (it < 0) -it else it + 1 }
                            .coerceIn(peerStartIdx, points.size)
                        if (peerEndIdx <= peerStartIdx) return

                        reusablePeerPath.rewind()
                        var first = true
                        var hasPath = false
                        var lastTimestamp = 0L
                        val peerRun = ChartLineRun()
                        for (i in peerStartIdx until peerEndIdx) {
                            val point = points[i]
                            val value = if (useRaw) point.rawValue else point.value
                            if (!value.isFinite() || value <= 0.1f) {
                                // No value in this lane is not a hole in it — the gap check
                                // below still measures elapsed time. See the main chart's
                                // AUTO branch.
                                continue
                            }
                            val px = timeToDataX(point.timestamp)
                            val py = valToY(value)
                            if (!px.isFinite() || !py.isFinite()) {
                                first = true
                                continue
                            }
                            if (!first && point.timestamp - lastTimestamp > peerGapThreshold) {
                                first = true
                            }
                            if (first) {
                                reusablePeerPath.moveTo(px, py)
                                peerRun.begin(px, py)
                                first = false
                            } else {
                                reusablePeerPath.lineTo(px, py)
                                peerRun.extend()
                            }
                            hasPath = true
                            lastTimestamp = point.timestamp
                        }
                        if (!hasPath) return
                        peerRun.flush()
                        val peerDotColor = androidx.compose.ui.graphics.lerp(series.color, peerNeutralBase, 0.46f)
                            .copy(alpha = 0.5f * alpha)
                        peerRun.isolatedPoints.forEach { dot ->
                            drawCircle(color = peerDotColor, radius = strokeWidth / 2f, center = dot)
                        }
                        val brush = peerBrushes[series.sensorId]
                        if (brush != null) {
                            drawPath(
                                path = reusablePeerPath,
                                brush = brush,
                                alpha = alpha,
                                style = Stroke(
                                    width = strokeWidth,
                                    cap = StrokeCap.Round,
                                    join = StrokeJoin.Round
                                )
                            )
                        } else {
                            drawPath(
                                path = reusablePeerPath,
                                color = androidx.compose.ui.graphics.lerp(series.color, peerNeutralBase, 0.46f).copy(alpha = 0.5f * alpha),
                                style = Stroke(
                                    width = strokeWidth,
                                    cap = StrokeCap.Round,
                                    join = StrokeJoin.Round
                                )
                            )
                        }
                    }
                    peerChartSeries.forEach { series ->
                        val drawRawPeer = series.viewMode == 1 || series.viewMode == 2 || series.viewMode == 3
                        val drawAutoPeer = series.viewMode == 0 || series.viewMode == 2 || series.viewMode == 3
                        when {
                            drawRawPeer && drawAutoPeer -> {
                                drawPeerSeriesLine(series, useRaw = true, alpha = 0.68f, strokeWidth = peerStroke * 0.84f)
                                drawPeerSeriesLine(series, useRaw = false, alpha = 0.88f, strokeWidth = peerStroke)
                            }
                            drawRawPeer -> drawPeerSeriesLine(series, useRaw = true, alpha = 0.88f, strokeWidth = peerStroke)
                            drawAutoPeer -> drawPeerSeriesLine(series, useRaw = false, alpha = 0.88f, strokeWidth = peerStroke)
                        }
                    }
                }

                // --- 2b. UNCERTAINTY RIBBON (behind the lines) ---
                // Drawn only for the algorithm lane, which is the only one an
                // estimator attaches a credible interval to; raw-only mode has
                // no interval and renders exactly as it always did.
                if (endIdx > startIdx && uncertaintyRibbonEnabled &&
                    (viewMode == 0 || viewMode == 2 || viewMode == 3)
                ) {
                    val ribbonIsRawMode = viewMode == 1 || viewMode == 3
                    val ribbonHasCalibration = calibratedValueResolver.hasCalibration(ribbonIsRawMode)
                    with(GlucoseUncertaintyRibbon) {
                        drawUncertaintyRibbon(
                            renderData = renderData,
                            startIndex = startIdx,
                            endIndex = endIdx,
                            viewportStartMs = viewportStart,
                            timeScale = dataWidth / animDur,
                            chartHeight = chartHeight,
                            yMin = cYMin,
                            yScale = if (cYRange < 0.001f) 0f else chartHeight / cYRange,
                            gapThresholdMs = ChartGap.THRESHOLD_MS,
                            color = primaryColor,
                            centerValueAt = { index ->
                                if (ribbonHasCalibration) {
                                    calibratedValueResolver.valueAt(index, ribbonIsRawMode)
                                } else {
                                    renderData[index].value
                                }
                            },
                        )
                    }
                }

                // --- 3. DATA LINES (Unified & Optimized) ---
                if (endIdx > startIdx) {
                    val gapThreshold = ChartGap.THRESHOLD_MS

                    val hideRawSource = hideInitialWhenCalibrated && isRawModeChart
                    val hideAutoSource = hideInitialWhenCalibrated && !isRawModeChart
                    val drawRaw = !hideRawSource && (viewMode == 1 || viewMode == 2 || viewMode == 3)
                    val drawAuto = !hideAutoSource && (viewMode == 0 || viewMode == 2 || viewMode == 3)

                    // Only tint the main/active line using gradient. No density check as requested.
                    val doTintRaw = !hasCalibration && (viewMode == 1 || viewMode == 3)
                    val doTintAuto = !hasCalibration && (viewMode == 0 || viewMode == 2)
                    val doTintCal = hasCalibration
                    
                    val needsGradient = doTintRaw || doTintAuto || doTintCal
                    
                    // Colors
                    val rawColor = when {
                        hasCalibration && viewMode == 2 -> tertiaryColor
                        hasCalibration || viewMode == 2 -> secondaryColor
                        else -> primaryColor // Used if not tinted
                    }
                    val autoColor = when {
                        hasCalibration && viewMode == 3 -> tertiaryColor
                        hasCalibration || viewMode == 3 -> secondaryColor
                        else -> primaryColor // Used if not tinted
                    }
                    
                    // Stroke Widths
                    val rawStrokeWidth = if (hasCalibration || viewMode == 2) 2.dp.toPx() else 3.dp.toPx()
                    val autoStrokeWidth = if (hasCalibration || viewMode == 3) 2.dp.toPx() else 3.dp.toPx()
                    val calStrokeWidth = 3.dp.toPx()

                    // Reset Paths
                    if (drawRaw) {
                        reusableRawPath.rewind()
                    }
                    if (drawAuto) {
                        reusableAutoPath.rewind()
                    }
                    if (hasCalibration) {
                        reusablePath.rewind()
                    }

                    // Unified Loop State
                    var rawFirst = true
                    var rawLastX = -10000f
                    var rawLastY = -10000f // Track Y for spike detection
                    var rawLastTimestamp = 0L

                    var autoFirst = true
                    var autoLastX = -10000f
                    var autoLastY = -10000f
                    var autoLastTimestamp = 0L
                    
                    var calFirst = true
                    var calLastX = -10000f
                    var calLastY = -10000f
                    var calLastTimestamp = 0L
                    var lastSensorSerial: String? = null

                    // A run of a single point strokes as nothing; keep those visible as
                    // dots so readings that stand alone between gaps are not lost.
                    val rawRun = ChartLineRun()
                    val autoRun = ChartLineRun()
                    val calRun = ChartLineRun()

                    // Optimization: Pre-calculate scaling factors to avoid repeated division in valToY/timeToDataX
                    val timeScale = dataWidth / animDur
                    val yScale = if (cYRange < 0.001f) 0f else chartHeight / cYRange
                    
                    // Use Round caps/joins for better visuals
                    val strokeCap = StrokeCap.Round
                    val strokeJoin = StrokeJoin.Round

                    for (i in startIdx until endIdx) {
                        val renderPoint = renderData[i]
                        val sensorChanged = lastSensorSerial != null &&
                            renderPoint.sensorSerial != null &&
                            renderPoint.sensorSerial != lastSensorSerial
                        if (sensorChanged) {
                            rawFirst = true
                            autoFirst = true
                            calFirst = true
                        }
                        // X is shared for all lines at this timestamp
                        val px = (renderPoint.timestamp - viewportStart) * timeScale
                        
                        if (!px.isFinite()) continue

                        // --- RAW LINE ---
                        if (drawRaw) {
                            // FAST PATH DECIMATION: Check X proximity first
                            if (!rawFirst && kotlin.math.abs(px - rawLastX) < 0.8f) {
                                // If horizontally close, check vertical spike using CHEAP value (raw/auto)
                                val rawV = renderPoint.rawValue
                                val rawY = chartHeight - ((rawV - cYMin) * yScale)
                                if (kotlin.math.abs(rawY - rawLastY) < 1.0f) {
                                    continue // SKIP drawing this point
                                }
                            }

                            val v = renderPoint.rawValue
                            if (v.isNaN() || v < 0.1f) {
                                // Nothing to say about the raw line, which is not the same as
                                // a break in it — see the AUTO branch below. NFC 15-minute
                                // history records carry an algorithm value and no raw lane.
                            } else {
                                val rawY = chartHeight - ((v - cYMin) * yScale)
                                val py = rawY.coerceIn(-2000f, chartHeight + 2000f)
                                
                                if (!py.isFinite()) {
                                    rawFirst = true
                                } else {
                                    if (!rawFirst && (renderPoint.timestamp - rawLastTimestamp) > gapThreshold) {
                                        rawFirst = true
                                    }
                                    
                                    if (rawFirst) {
                                        reusableRawPath.moveTo(px, py)
                                        rawRun.begin(px, py)
                                        rawFirst = false
                                    } else {
                                        reusableRawPath.lineTo(px, py)
                                        rawRun.extend()
                                    }
                                    rawLastX = px
                                    rawLastY = py
                                    rawLastTimestamp = renderPoint.timestamp
                                }
                            }
                        }

                        // --- AUTO LINE ---
                        if (drawAuto) {
                            if (!autoFirst && kotlin.math.abs(px - autoLastX) < 0.8f) {
                                val autoV = renderPoint.value
                                val autoY = chartHeight - ((autoV - cYMin) * yScale)
                                if (kotlin.math.abs(autoY - autoLastY) < 1.0f) {
                                    continue
                                }
                            }

                            val v = renderPoint.value
                            if (v.isNaN() || v < 0.1f) {
                                // A point with no value for THIS series is not a hole in it.
                                // The two lanes are populated by different sources: an NFC
                                // scan's per-minute trend is raw-only (saveScanTrend() in
                                // share/savehistory.cpp — only Abbott's library can turn a
                                // raw sample into a reading, and only for the current
                                // minute), while 15-minute history records are algorithm-only.
                                // Breaking here made every history point in NFC-only mode a
                                // single-point run between two raw-only minutes, which is
                                // what drew the chart as a field of dots (issue #166).
                                //
                                // Skipping keeps the real rule intact: gapThreshold below
                                // still measures elapsed time against the last point that
                                // actually had a value, and a minute with neither value
                                // never becomes a point at all (appendPollHistory() in
                                // g.cpp), so genuine holes are still holes.
                            } else {
                                val autoY = chartHeight - ((v - cYMin) * yScale)
                                val py = autoY.coerceIn(-2000f, chartHeight + 2000f)
                                
                                if (!py.isFinite()) {
                                    autoFirst = true
                                } else {
                                    if (!autoFirst && (renderPoint.timestamp - autoLastTimestamp) > gapThreshold) {
                                        autoFirst = true
                                    }
                                    
                                    if (autoFirst) {
                                        reusableAutoPath.moveTo(px, py)
                                        autoRun.begin(px, py)
                                        autoFirst = false
                                    } else {
                                        reusableAutoPath.lineTo(px, py)
                                        autoRun.extend()
                                    }
                                    autoLastX = px
                                    autoLastY = py
                                    autoLastTimestamp = renderPoint.timestamp
                                }
                            }
                        }
                        
                        // --- CALIBRATION LINE ---
                        if (hasCalibration) {
                            val baseV = if (isRawModeChart) renderPoint.rawValue else renderPoint.value
                            if (!baseV.isFinite() || baseV <= 0.1f) {
                                // No value in the lane calibration is applied to: skip the
                                // point rather than break the line — see the AUTO branch.
                                continue
                            }
                            if (!calFirst && kotlin.math.abs(px - calLastX) < 0.8f) {
                                val proxyY = chartHeight - ((baseV - cYMin) * yScale)
                                if (kotlin.math.abs(proxyY - calLastY) < 1.0f) {
                                     continue
                                }
                            }

                            val v = calibratedValueResolver.valueAt(i, isRawModeChart)
                            
                            if (v.isNaN() || v < 0.1f) {
                                calFirst = true
                            } else {
                                val calY = chartHeight - ((v - cYMin) * yScale)
                                val py = calY.coerceIn(-2000f, chartHeight + 2000f)
                                
                                if (!py.isFinite()) {
                                    calFirst = true
                                } else {
                                    if (!calFirst && (renderPoint.timestamp - calLastTimestamp) > gapThreshold) {
                                        calFirst = true
                                    }
                                    
                                    if (calFirst) {
                                        reusablePath.moveTo(px, py)
                                        calRun.begin(px, py)
                                        calFirst = false
                                    } else {
                                        reusablePath.lineTo(px, py)
                                        calRun.extend()
                                    }
                                    calLastX = px
                                    calLastY = py
                                    calLastTimestamp = renderPoint.timestamp
                                }
                            }
                        }

                        lastSensorSerial = renderPoint.sensorSerial
                    }

                    rawRun.flush()
                    autoRun.flush()
                    calRun.flush()

                    // --- DRAW PATHS ---
                    // Using Gradient Brush for primary/active lines for smooth transition

                    fun drawIsolated(run: ChartLineRun, color: Color, strokeWidth: Float) {
                        if (run.isolatedPoints.isEmpty()) return
                        val radius = strokeWidth / 2f
                        run.isolatedPoints.forEach { point ->
                            drawCircle(color = color, radius = radius, center = point)
                        }
                    }

                    if (drawRaw) {
                        if (doTintRaw) {
                            drawPath(reusableRawPath, brush = gradientBrush, style = Stroke(width = rawStrokeWidth, cap = strokeCap, join = strokeJoin))
                        } else {
                            drawPath(reusableRawPath, rawColor, style = Stroke(width = rawStrokeWidth, cap = strokeCap, join = strokeJoin))
                        }
                        drawIsolated(rawRun, rawColor, rawStrokeWidth)
                    }
                    if (drawAuto) {
                        if (doTintAuto) {
                            drawPath(reusableAutoPath, brush = gradientBrush, style = Stroke(width = autoStrokeWidth, cap = strokeCap, join = strokeJoin))
                        } else {
                            drawPath(reusableAutoPath, autoColor, style = Stroke(width = autoStrokeWidth, cap = strokeCap, join = strokeJoin))
                        }
                        drawIsolated(autoRun, autoColor, autoStrokeWidth)
                    }
                    if (hasCalibration) {
                        if (doTintCal) {
                            drawPath(reusablePath, brush = gradientBrush, style = Stroke(width = calStrokeWidth, cap = strokeCap, join = strokeJoin))
                        } else {
                            drawPath(reusablePath, primaryColor, style = Stroke(width = calStrokeWidth, cap = strokeCap, join = strokeJoin))
                        }
                        drawIsolated(calRun, primaryColor, calStrokeWidth)
                    }
                }

                fun addSmoothedPredictionOffsets(path: Path, samples: List<Offset>, moveToFirst: Boolean) {
                    if (samples.isEmpty()) return
                    val first = samples.first()
                    if (moveToFirst) {
                        path.moveTo(first.x, first.y)
                    } else {
                        path.lineTo(first.x, first.y)
                    }
                    if (samples.size == 1) return
                    if (samples.size == 2) {
                        val last = samples.last()
                        path.lineTo(last.x, last.y)
                        return
                    }
                    for (index in 1 until samples.lastIndex) {
                        val current = samples[index]
                        val next = samples[index + 1]
                        val midX = (current.x + next.x) * 0.5f
                        val midY = (current.y + next.y) * 0.5f
                        path.quadraticTo(current.x, current.y, midX, midY)
                    }
                    val last = samples.last()
                    path.lineTo(last.x, last.y)
                }

                fun predictionUncertainty(point: GlucosePredictionPoint): Float {
                    val uncertainty = 1f - point.confidence.coerceIn(0f, 1f)
                    return if (isMmol) {
                        0.18f + (uncertainty * 1.3f)
                    } else {
                        3.2f + (uncertainty * 24f)
                    }
                }

                val predictionRawColor = when {
                    calibratedValueResolver.hasCalibration(viewMode == 1 || viewMode == 3) && viewMode == 2 -> tertiaryColor
                    calibratedValueResolver.hasCalibration(viewMode == 1 || viewMode == 3) || viewMode == 2 -> secondaryColor
                    else -> primaryColor
                }
                val predictionAutoColor = when {
                    calibratedValueResolver.hasCalibration(viewMode == 1 || viewMode == 3) && viewMode == 3 -> tertiaryColor
                    calibratedValueResolver.hasCalibration(viewMode == 1 || viewMode == 3) || viewMode == 3 -> secondaryColor
                    else -> primaryColor
                }

                resolvedPredictionSeries.forEach { series ->
                    val validPredictionPoints = series.points.filter { point ->
                            point.value.isFinite() &&
                            point.value > 0.1f
                    }
                    val firstVisiblePredictionIndex = validPredictionPoints.indexOfFirst { it.timestamp >= viewportStart }
                    val lastVisiblePredictionIndex = validPredictionPoints.indexOfLast { it.timestamp <= viewportEnd }
                    if (firstVisiblePredictionIndex == -1 || lastVisiblePredictionIndex == -1) return@forEach
                    val predictionStartIndex = (firstVisiblePredictionIndex - 1).coerceAtLeast(0)
                    val predictionEndIndex = (lastVisiblePredictionIndex + 1).coerceAtMost(validPredictionPoints.lastIndex)
                    val visiblePredictionPoints = validPredictionPoints.subList(
                        predictionStartIndex,
                        predictionEndIndex + 1
                    )
                    if (visiblePredictionPoints.size < 2) return@forEach

                    val predictionTint = when (series.kind) {
                        GlucosePredictionSeriesKind.RAW -> predictionRawColor
                        GlucosePredictionSeriesKind.AUTO -> predictionAutoColor
                        GlucosePredictionSeriesKind.CALIBRATED -> primaryColor
                    }
                    val isPrimaryPrediction = series.kind == GlucosePredictionSeriesKind.CALIBRATED ||
                        (series.kind == GlucosePredictionSeriesKind.RAW && (viewMode == 1 || viewMode == 3)) ||
                        (series.kind == GlucosePredictionSeriesKind.AUTO && (viewMode == 0 || viewMode == 2))

                    val lineSamples = visiblePredictionPoints.mapNotNull { point ->
                        val x = timeToDataX(point.timestamp)
                        val y = valToY(point.value)
                        if (x.isFinite() && y.isFinite()) Offset(x, y) else null
                    }
                    if (lineSamples.size < 2) return@forEach

                    val upperSamples = visiblePredictionPoints.mapNotNull { point ->
                        val x = timeToDataX(point.timestamp)
                        val y = valToY(point.value + predictionUncertainty(point))
                        if (x.isFinite() && y.isFinite()) Offset(x, y) else null
                    }
                    val lowerSamples = visiblePredictionPoints.asReversed().mapNotNull { point ->
                        val x = timeToDataX(point.timestamp)
                        val y = valToY(point.value - predictionUncertainty(point))
                        if (x.isFinite() && y.isFinite()) Offset(x, y) else null
                    }
                    if (isPrimaryPrediction && upperSamples.size >= 2 && lowerSamples.size >= 2) {
                        val bandPath = Path().apply {
                            addSmoothedPredictionOffsets(this, upperSamples, moveToFirst = true)
                            addSmoothedPredictionOffsets(this, lowerSamples, moveToFirst = false)
                            close()
                        }
                        drawPath(
                            path = bandPath,
                            color = predictionTint.copy(alpha = 0.055f)
                        )
                    }
                    val predictionPath = Path().apply {
                        addSmoothedPredictionOffsets(this, lineSamples, moveToFirst = true)
                    }
                    val startX = lineSamples.first().x
                    val endX = lineSamples.last().x.takeIf { abs(it - startX) > 1f } ?: (startX + 1f)
                    val startAlpha = if (isPrimaryPrediction) 0.58f else 0.34f
                    val midAlpha = if (isPrimaryPrediction) 0.38f else 0.24f
                    drawPath(
                        path = predictionPath,
                        brush = Brush.horizontalGradient(
                            colorStops = arrayOf(
                                0f to predictionTint.copy(alpha = startAlpha),
                                0.55f to predictionTint.copy(alpha = midAlpha),
                                1f to predictionTint.copy(alpha = 0.04f)
                            ),
                            startX = startX,
                            endX = endX
                        ),
                        style = Stroke(
                            width = if (isPrimaryPrediction) 2.dp.toPx() else 1.45f.dp.toPx(),
                            cap = StrokeCap.Round,
                            join = StrokeJoin.Round,
                            pathEffect = dashEffect
                        )
                    )
                }

                // --- 3b. PEER PREDICTION LINES ---
                // Extend the simulation to every peer line too (dashed, in the
                // peer's desaturated identity color, no uncertainty band).
                if (peerPredictionSeries.isNotEmpty() && peerChartSeries.isNotEmpty()) {
                    peerChartSeries.forEach { peer ->
                        val peerSeriesList = peerPredictionSeries[peer.sensorId] ?: return@forEach
                        val peerPredColor = androidx.compose.ui.graphics.lerp(peer.color, peerNeutralBase, 0.46f)
                        peerSeriesList.forEach predict@{ series ->
                            val valid = series.points.filter { it.value.isFinite() && it.value > 0.1f }
                            val firstVis = valid.indexOfFirst { it.timestamp >= viewportStart }
                            val lastVis = valid.indexOfLast { it.timestamp <= viewportEnd }
                            if (firstVis == -1 || lastVis == -1) return@predict
                            val s = (firstVis - 1).coerceAtLeast(0)
                            val e = (lastVis + 1).coerceAtMost(valid.lastIndex)
                            val vis = valid.subList(s, e + 1)
                            if (vis.size < 2) return@predict
                            val samples = vis.mapNotNull { p ->
                                val x = timeToDataX(p.timestamp)
                                val y = valToY(p.value)
                                if (x.isFinite() && y.isFinite()) Offset(x, y) else null
                            }
                            if (samples.size < 2) return@predict
                            val predictionPath = Path().apply {
                                addSmoothedPredictionOffsets(this, samples, moveToFirst = true)
                            }
                            val startX = samples.first().x
                            val endX = samples.last().x.takeIf { abs(it - startX) > 1f } ?: (startX + 1f)
                            drawPath(
                                path = predictionPath,
                                brush = Brush.horizontalGradient(
                                    colorStops = arrayOf(
                                        0f to peerPredColor.copy(alpha = 0.30f),
                                        0.55f to peerPredColor.copy(alpha = 0.20f),
                                        1f to peerPredColor.copy(alpha = 0.04f)
                                    ),
                                    startX = startX,
                                    endX = endX
                                ),
                                style = Stroke(
                                    width = 1.5.dp.toPx(),
                                    cap = StrokeCap.Round,
                                    join = StrokeJoin.Round,
                                    pathEffect = dashEffect
                                )
                            )
                        }
                    }
                }

                // --- 4. MIN/MAX INDICATORS (Restored & Optimized) ---
                if (endIdx > startIdx) {
                    var minPoint = renderData[startIdx]
                    var maxPoint = renderData[startIdx]
                    var minVal = Float.MAX_VALUE
                    var maxVal = Float.MIN_VALUE

                    // Single fast pass for min/max
                    for (i in startIdx until endIdx) {
                        val p = renderData[i]
                        // Determine value based on mode commonality
                        // If showing Raw (Mode 1) or Raw-Primary (Mode 3), prioritize Raw
                        val useRaw = viewMode == 1 || viewMode == 3
                        val v = if (hideInitialWhenCalibrated) {
                            calibratedValueResolver.valueAt(i, useRaw)
                        } else {
                            if (useRaw) p.rawValue else p.value
                        }

                        if (v.isNaN() || v < 0.1f) continue

                        if (v < minVal) { minVal = v; minPoint = p }
                        if (v > maxVal) { maxVal = v; maxPoint = p }
                    }

                    // Helper to draw
                    fun drawIndicator(point: GlucosePoint, valToDraw: Float) {
                        val y = valToY(valToDraw)
                        val x = timeToDataX(point.timestamp)

                        if (y.isFinite() && x.isFinite() && y in 0f..chartHeight && x in 0f..width) {
                            val label = tk.glucodata.ui.util.GlucoseFormatter.format(valToDraw, isMmol)

                            // Text - Vertically centered with guide line, 4dp left padding
                            axisTextPaint.getTextBounds(label, 0, label.length, indicatorLabelBounds)
                            val centeredY = y + (indicatorLabelBounds.height() / 2f)
                            val indicatorPadding = 16f * density
                            val textWidth = axisTextPaint.measureText(label)

                            drawContext.canvas.nativeCanvas.drawText(
                                label, indicatorPadding, centeredY,
                                axisTextPaint.apply { color = android.graphics.Color.DKGRAY } // Dark Gray for visibility
                            )
                            // Guide line - starts after text
                            val lineStartX = indicatorPadding + textWidth + 8f * density
                            drawLine(
                                color = minMaxLineColor,
                                start = Offset(lineStartX, y),
                                end = Offset(x, y),
                                strokeWidth = 1f,
                                pathEffect = dashEffect
                            )
                            // Restore paint color (axisTextPaint is shared)
                            axisTextPaint.color = android.graphics.Color.GRAY
                        }
                    }

                    if (maxVal > Float.MIN_VALUE) drawIndicator(maxPoint, maxVal)
                    if (minVal < Float.MAX_VALUE && minVal != maxVal) drawIndicator(minPoint, minVal)
                }

                // --- 5. TARGET RANGE ---
                val yHigh = valToY(targetHigh)
                val yLow = valToY(targetLow)
                // Only draw if valid
                if (yHigh.isFinite() && yLow.isFinite()) {
                    drawRect(targetBandColor, topLeft = Offset(0f, yHigh), size = Size(width, yLow - yHigh))
                }

                // --- 6. CALIBRATION MARKERS ---
                // Draw permanent vertical lines for calibration points in visible range (respects mode)
                visibleCalibrations.forEach { cal ->
                    if (cal.timestamp !in viewportStart..viewportEnd) return@forEach
                    val calX = timeToDataX(cal.timestamp)
                    if (calX in 0f..width) {
                        // Draw vertical line with opacity
                        drawLine(
                            color = primaryColor.copy(alpha = 0.4f),
                            start = Offset(calX, 0f),
                            end = Offset(calX, chartHeight),
                            strokeWidth = 1.5.dp.toPx()
                        )
                    }
                }

                val visibleJournalMarkers = journalMarkers.filter { marker ->
                    val bandStart = marker.activeStartMillis ?: marker.timestamp
                    val bandEnd = marker.activeEndMillis ?: marker.timestamp
                    bandEnd >= viewportStart && bandStart <= viewportEnd
                }
                if (visibleJournalMarkers.isNotEmpty()) {
                    val visibleInsulinMarkers = visibleJournalMarkers.filter { it.type == JournalEntryType.INSULIN }
                    val visibleEventMarkers = visibleJournalMarkers.filter { it.type != JournalEntryType.INSULIN }

                    if (visibleInsulinMarkers.isNotEmpty()) {
                        val baseCurveHeight = (chartHeight * 0.018f).coerceIn(5.dp.toPx(), 12.dp.toPx())
                        val extraCurveHeight = (chartHeight * 0.20f).coerceIn(72.dp.toPx(), 156.dp.toPx())
                        val strokeWidth = 1.8.dp.toPx()
                        val offscreenPaddingPx = 32.dp.toPx()
                        val curveBottomInset = 0.5.dp.toPx()
                        val curveReferenceY = chartHeight - curveBottomInset
                        val referenceDoseUnits = 18f

                        visibleInsulinMarkers.forEach { marker ->
                            val tint = Color(marker.accentColor)
                            val startTime = marker.timestamp
                            val endTime = marker.activeEndMillis ?: marker.timestamp
                            val doseUnits = (marker.amount ?: 0.5f).coerceAtLeast(0.05f)
                            val amountFactor = (ln(1f + doseUnits) / ln(1f + referenceDoseUnits)).coerceIn(0.08f, 1f)
                            val curveHeight = baseCurveHeight + (extraCurveHeight * amountFactor)
                            val curveBaseY = curveReferenceY.coerceAtLeast(curveHeight + 1.dp.toPx())
                            val markerX = timeToDataX(marker.timestamp)

                            if (marker.curvePoints.isNotEmpty() && endTime > startTime) {
                                val fillPath = Path()
                                val curveStartX = timeToDataX(startTime).coerceIn(-offscreenPaddingPx, dataWidth + offscreenPaddingPx)
                                val curveEndX = timeToDataX(endTime).coerceIn(-offscreenPaddingPx, dataWidth + offscreenPaddingPx)
                                val resolvedCurveEndX = if (abs(curveEndX - curveStartX) < 1f) {
                                    curveStartX + 1f
                                } else {
                                    curveEndX
                                }
                                val fillBrush = Brush.horizontalGradient(
                                    colorStops = arrayOf(
                                        0f to tint.copy(alpha = 0f),
                                        0.18f to tint.copy(alpha = tint.alpha * 0.18f),
                                        0.82f to tint.copy(alpha = tint.alpha * 0.15f),
                                        1f to tint.copy(alpha = 0f)
                                    ),
                                    startX = curveStartX,
                                    endX = resolvedCurveEndX
                                )
                                val curveSamples = buildList {
                                    add(Triple(curveStartX, curveBaseY, 0f))
                                    marker.curvePoints.forEach { point ->
                                        val pointTime = marker.timestamp + (point.minute * 60_000L)
                                        val x = timeToDataX(pointTime).coerceIn(-offscreenPaddingPx, dataWidth + offscreenPaddingPx)
                                        val activity = point.activity.coerceIn(0f, 1f)
                                        val y = curveBaseY - (activity * curveHeight)
                                        add(Triple(x, y, activity))
                                    }
                                    add(Triple(resolvedCurveEndX, curveBaseY, 0f))
                                }.sortedBy { it.first }

                                fun Path.addSmoothedCurve(
                                    samples: List<Triple<Float, Float, Float>>,
                                    moveToFirst: Boolean
                                ) {
                                    if (samples.isEmpty()) return
                                    val first = samples.first()
                                    if (moveToFirst) {
                                        moveTo(first.first, first.second)
                                    } else {
                                        lineTo(first.first, first.second)
                                    }
                                    if (samples.size == 1) return
                                    if (samples.size == 2) {
                                        val last = samples.last()
                                        lineTo(last.first, last.second)
                                        return
                                    }
                                    for (index in 1 until samples.lastIndex) {
                                        val current = samples[index]
                                        val next = samples[index + 1]
                                        val midX = (current.first + next.first) * 0.5f
                                        val midY = (current.second + next.second) * 0.5f
                                        quadraticTo(current.first, current.second, midX, midY)
                                    }
                                    val last = samples.last()
                                    lineTo(last.first, last.second)
                                }

                                fillPath.moveTo(curveSamples.first().first, curveBaseY)
                                fillPath.addSmoothedCurve(curveSamples, moveToFirst = false)
                                fillPath.lineTo(resolvedCurveEndX, curveBaseY)
                                fillPath.close()
                                val strokePath = Path().apply {
                                    addSmoothedCurve(curveSamples, moveToFirst = true)
                                }
                                val strokeBrush = Brush.horizontalGradient(
                                    colorStops = arrayOf(
                                        0f to tint.copy(alpha = 0f),
                                        0.16f to tint.copy(alpha = tint.alpha * 0.34f),
                                        0.5f to tint.copy(alpha = tint.alpha * 0.62f),
                                        0.84f to tint.copy(alpha = tint.alpha * 0.28f),
                                        1f to tint.copy(alpha = 0f)
                                    ),
                                    startX = curveStartX,
                                    endX = resolvedCurveEndX
                                )
                                drawLine(
                                    color = tint.copy(alpha = tint.alpha * 0.08f),
                                    start = Offset(curveStartX, curveBaseY),
                                    end = Offset(resolvedCurveEndX, curveBaseY),
                                    strokeWidth = 0.8.dp.toPx()
                                )
                                drawPath(fillPath, fillBrush)
                                drawPath(
                                    path = strokePath,
                                    brush = strokeBrush,
                                    style = Stroke(
                                        width = strokeWidth,
                                        cap = StrokeCap.Round,
                                        join = StrokeJoin.Round
                                    )
                                )
                            } else if (markerX in 0f..dataWidth) {
                                drawCircle(
                                    color = tint.copy(alpha = tint.alpha * 0.72f),
                                    radius = 3.5.dp.toPx(),
                                    center = Offset(markerX, curveBaseY)
                                )
                            }
                        }
                    }

                    if (visibleEventMarkers.isNotEmpty()) {
                        val eventRailY = coerceChartYToDrawableRange(
                            chartHeight - 10.dp.toPx(),
                            chartHeight,
                            8.dp.toPx()
                        )
                        val markerEdgeInset = 6.dp.toPx()
                        visibleEventMarkers.forEach { marker ->
                            val markerX = timeToDataX(marker.timestamp)
                            if (markerX !in 0f..dataWidth) return@forEach
                            val tint = Color(marker.accentColor)
                            val markerY = marker.chartGlucoseValue
                                ?.let(::valToY)
                                ?.takeIf { it.isFinite() }
                                ?.let { coerceChartYToDrawableRange(it, chartHeight, markerEdgeInset) }
                                ?: marker.chartYFraction
                                    ?.let { fraction -> chartHeight - (fraction.coerceIn(0f, 1f) * chartHeight) }
                                    ?.takeIf { it.isFinite() }
                                    ?.let { coerceChartYToDrawableRange(it, chartHeight, markerEdgeInset) }
                                ?: eventRailY
                            if (marker.type == JournalEntryType.ACTIVITY && marker.durationMinutes != null) {
                                val endX = timeToDataX(marker.timestamp + marker.durationMinutes.coerceAtLeast(1) * 60_000L)
                                    .coerceIn(0f, dataWidth)
                                drawLine(
                                    color = tint.copy(alpha = 0.26f),
                                    start = Offset(markerX, markerY),
                                    end = Offset(endX, markerY),
                                    strokeWidth = 3.dp.toPx(),
                                    cap = StrokeCap.Round
                                )
                            }
                            if (marker.type == JournalEntryType.FINGERSTICK) {
                                drawCircle(
                                    color = tint.copy(alpha = 0.18f),
                                    radius = 6.dp.toPx(),
                                    center = Offset(markerX, markerY)
                                )
                                drawCircle(
                                    color = tint,
                                    radius = 3.25.dp.toPx(),
                                    center = Offset(markerX, markerY)
                                )
                            } else {
                                drawCircle(
                                    color = tint,
                                    radius = 4.5.dp.toPx(),
                                    center = Offset(markerX, markerY)
                                )
                            }
                        }
                    }
                }

                if (journalActionTimestamp != null && selectedPoint == null && journalActionTimestamp in viewportStart..viewportEnd) {
                    val actionX = timeToDataX(journalActionTimestamp)
                    if (actionX in 0f..width) {
                        val actionEdgeInset = 12.dp.toPx()
                        val actionMarkerY = journalActionDisplayValue
                            ?.let(::valToY)
                            ?.takeIf { it.isFinite() }
                            ?.let { coerceChartYToDrawableRange(it, chartHeight, actionEdgeInset) }
                            ?: coerceChartYToDrawableRange(chartHeight - actionEdgeInset, chartHeight, actionEdgeInset)
                        drawLine(
                            color = primaryColor.copy(alpha = 0.22f + (0.14f * journalActionIndicatorProgress)),
                            start = Offset(actionX, 0f),
                            end = Offset(actionX, chartHeight),
                            strokeWidth = 1.5.dp.toPx(),
                            pathEffect = dashEffect
                        )
                        drawCircle(
                            color = primaryColor.copy(alpha = 0.18f * journalActionIndicatorProgress),
                            radius = (10.dp.toPx() * (0.72f + (0.28f * journalActionIndicatorProgress))),
                            center = Offset(actionX, actionMarkerY)
                        )
                        drawCircle(
                            color = primaryColor.copy(alpha = 0.96f),
                            radius = 4.5.dp.toPx(),
                            center = Offset(actionX, actionMarkerY)
                        )
                    }
                }

                // --- 7. CURSOR ---
                val cursorX = selectedPoint?.let { timeToDataX(it.timestamp) }
                if (cursorX != null && cursorX in 0f..width) {
                    drawLine(hoverLineColor, Offset(cursorX, 0f), Offset(cursorX, chartHeight), 2.dp.toPx())

                    selectedPoint?.let { p ->
                        val dotRadius = 5.dp.toPx()
                        val isRawModeDot = viewMode == 1 || viewMode == 3
                        val hasCalibrationDot = calibratedValueResolver.hasCalibration(isRawModeDot)
                        val hideRawDot = hideInitialWhenCalibrated && isRawModeDot
                        val hideAutoDot = hideInitialWhenCalibrated && !isRawModeDot

                        // Draw dots for active lines (demoted when calibration active)
                         if (!hideRawDot && (viewMode == 1 || viewMode == 2 || viewMode == 3) && p.rawValue > 0.1f) {
                              val color = if (hasCalibrationDot) secondaryColor else if (viewMode == 1 || viewMode == 3) primaryColor else secondaryColor
                              val py = valToY(p.rawValue)
                              if (py.isFinite()) drawCircle(color, dotRadius, Offset(cursorX, py))
                          }
                         if (!hideAutoDot && (viewMode == 0 || viewMode == 2 || viewMode == 3)) {
                             val color = if (hasCalibrationDot) secondaryColor else if (viewMode == 0 || viewMode == 2) primaryColor else secondaryColor
                             val py = valToY(p.value)
                             if (py.isFinite()) drawCircle(color, dotRadius, Offset(cursorX, py))
                         }

                         // Draw calibrated dot on top (primary when active)
                         if (hasCalibrationDot) {
                             val calibratedV = calibratedValueResolver.valueForPoint(p, isRawModeDot)
                             if (calibratedV.isFinite() && calibratedV > 0.1f) {
                                 val py = valToY(calibratedV)
                                 if (py.isFinite()) drawCircle(primaryColor, dotRadius, Offset(cursorX, py))
                             }
                         }

                         peerPointsByBucket[MultiSensorDisplay.bucketKeyForTimestamp(p.timestamp)]
                             .orEmpty()
                             .forEach { peer ->
                                 val attrs = peerDrawAttrs[peer.sensorSerial]
                                 val peerMode = attrs?.second ?: viewMode
                                 val peerColor = attrs?.first ?: SensorColors.getColor(peer.sensorSerial.orEmpty())
                                 val drawPeerRaw = peerMode == 1 || peerMode == 2 || peerMode == 3
                                 val drawPeerAuto = peerMode == 0 || peerMode == 2 || peerMode == 3
                                 if (drawPeerRaw && peer.rawValue.isFinite() && peer.rawValue > 0.1f) {
                                     val py = valToY(peer.rawValue)
                                     if (py.isFinite()) {
                                         drawCircle(
                                             color = peerColor.copy(alpha = if (drawPeerAuto) 0.32f else 0.46f),
                                             radius = dotRadius * 0.7f,
                                             center = Offset(cursorX, py)
                                         )
                                     }
                                 }
                                 if (drawPeerAuto && peer.value.isFinite() && peer.value > 0.1f) {
                                     val py = valToY(peer.value)
                                     if (py.isFinite()) {
                                         drawCircle(
                                             color = peerColor.copy(alpha = 0.46f),
                                             radius = dotRadius * 0.75f,
                                             center = Offset(cursorX, py)
                                         )
                                     }
                                 }
                             }
                    }
                }
            }

            // --- INFO CARD ---
            val overlayRightPaddingPx = if (hasPredictionOverlay) {
                0f
            } else {
                16f * density * safeExpandedProgress
            }
            val overlayDataWidthPx = (constraints.maxWidth.toFloat() - overlayRightPaddingPx).coerceAtLeast(1f)
            val overlayDuration = animatedVisibleDuration.coerceAtLeast(minDuration.toFloat())
            val overlayDurationMillis = overlayDuration.toLong()
            val overlayViewportStart = centerTime - overlayDurationMillis / 2
            val overlayViewportEnd = centerTime + overlayDurationMillis / 2
            val journalChipLaneStepPx = with(LocalDensity.current) { 30.dp.toPx() }
            val journalChipMinTopPx = with(LocalDensity.current) { 8.dp.toPx() }
            val journalChipMinGapPx = with(LocalDensity.current) { 90.dp.toPx() }
            val journalActionChipYOffsetPx = with(LocalDensity.current) { (chartHeightPx - 34.dp.toPx()).coerceAtLeast(12.dp.toPx()) }
            val overlayValueToY: (Float) -> Float = { value ->
                val range = (renderedYMax - renderedYMin).takeIf { it > 0.001f } ?: 1f
                (chartHeightPx - ((value - renderedYMin) / range) * chartHeightPx).coerceIn(0f, chartHeightPx)
            }
            fun markerOverlayY(marker: JournalChartMarker): Float? {
                return marker.chartGlucoseValue
                    ?.let(overlayValueToY)
                    ?.takeIf { it.isFinite() }
                    ?: marker.chartYFraction
                        ?.let { fraction -> chartHeightPx - (fraction.coerceIn(0f, 1f) * chartHeightPx) }
                        ?.takeIf { it.isFinite() }
                        ?.coerceIn(0f, chartHeightPx)
            }
            fun laneAssignments(markers: List<Pair<JournalChartMarker, Float>>): Map<Long, Int> {
                val laneEndXs = mutableListOf<Float>()
                val assignments = mutableMapOf<Long, Int>()
                markers.sortedBy { it.second }.forEach { (marker, markerX) ->
                    val laneIndex = laneEndXs.indexOfFirst { markerX - it >= journalChipMinGapPx }
                    val resolvedLane = if (laneIndex >= 0) laneIndex else laneEndXs.size.also {
                        laneEndXs.add(Float.NEGATIVE_INFINITY)
                    }
                    laneEndXs[resolvedLane] = markerX
                    assignments[marker.entryId] = resolvedLane
                }
                return assignments
            }
            val visibleOverlayMarkers = journalMarkers.filter { marker ->
                marker.timestamp in overlayViewportStart..overlayViewportEnd
            }
            val markerXById = visibleOverlayMarkers.associate { marker ->
                val xFraction = (marker.timestamp - overlayViewportStart).toFloat() / overlayDuration
                marker.entryId to (overlayDataWidthPx * xFraction).coerceIn(0f, overlayDataWidthPx)
            }
            val fingerstickLanes = laneAssignments(
                visibleOverlayMarkers
                    .filter { it.type == JournalEntryType.FINGERSTICK }
                    .mapNotNull { marker -> markerXById[marker.entryId]?.let { marker to it } }
            )
            val insulinLanes = laneAssignments(
                visibleOverlayMarkers
                    .filter { it.type == JournalEntryType.INSULIN }
                    .mapNotNull { marker -> markerXById[marker.entryId]?.let { marker to it } }
            )
            val eventLanes = laneAssignments(
                visibleOverlayMarkers
                    .filter { it.type != JournalEntryType.INSULIN && it.type != JournalEntryType.FINGERSTICK }
                    .mapNotNull { marker -> markerXById[marker.entryId]?.let { marker to it } }
            )
            val fingerstickLiftPx = with(LocalDensity.current) { 48.dp.toPx() }
            val journalChipSideOffsetPx = with(LocalDensity.current) { 10.dp.toPx() }
            val eventBaseTopPx = (chartHeightPx - with(LocalDensity.current) { 44.dp.toPx() }).coerceAtLeast(journalChipMinTopPx)
            val eventRailOverlayY = (chartHeightPx - with(LocalDensity.current) { 10.dp.toPx() })
                .coerceAtLeast(with(LocalDensity.current) { 8.dp.toPx() })
            val insulinLabelGapPx = with(LocalDensity.current) { 34.dp.toPx() }
            val insulinBaseCurveHeightPx = (chartHeightPx * 0.018f).coerceIn(
                with(LocalDensity.current) { 5.dp.toPx() },
                with(LocalDensity.current) { 12.dp.toPx() }
            )
            val insulinExtraCurveHeightPx = (chartHeightPx * 0.20f).coerceIn(
                with(LocalDensity.current) { 72.dp.toPx() },
                with(LocalDensity.current) { 156.dp.toPx() }
            )
            val insulinReferenceDoseUnits = 18f
            fun insulinCurveHeightFor(amount: Float?): Float {
                val doseUnits = (amount ?: 0.5f).coerceAtLeast(0.05f)
                val amountFactor = (ln(1f + doseUnits) / ln(1f + insulinReferenceDoseUnits)).coerceIn(0.08f, 1f)
                return insulinBaseCurveHeightPx + (insulinExtraCurveHeightPx * amountFactor)
            }
            val journalChipMaxTopPx = (chartHeightPx - with(LocalDensity.current) { 34.dp.toPx() }).coerceAtLeast(journalChipMinTopPx)
            val connectorStrokePx = with(LocalDensity.current) { 0.85.dp.toPx() }
            val connectorLabelCenterOffsetPx = with(LocalDensity.current) { 16.dp.toPx() }
            val connectorUnderlapPx = with(LocalDensity.current) { 8.dp.toPx() }
            val insulinConnectorY = chartHeightPx - with(LocalDensity.current) { 1.dp.toPx() }

            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(1.35f)
            ) {
                visibleOverlayMarkers.forEach { marker ->
                    val markerX = markerXById[marker.entryId] ?: return@forEach
                    val preferLeadingAnchor = markerX > (overlayDataWidthPx * 0.58f)
                    val markerTop = when (marker.type) {
                        JournalEntryType.FINGERSTICK -> {
                            val lane = fingerstickLanes[marker.entryId] ?: 0
                            (
                                (marker.chartGlucoseValue?.let(overlayValueToY) ?: eventBaseTopPx) -
                                    fingerstickLiftPx -
                                    (lane * journalChipLaneStepPx)
                                ).coerceIn(journalChipMinTopPx, journalChipMaxTopPx)
                        }

                        JournalEntryType.INSULIN -> {
                            val lane = insulinLanes[marker.entryId] ?: 0
                            (
                                chartHeightPx -
                                    insulinCurveHeightFor(marker.amount) -
                                    insulinLabelGapPx -
                                    (lane * journalChipLaneStepPx)
                                ).coerceAtLeast(journalChipMinTopPx)
                        }

                        else -> {
                            val lane = eventLanes[marker.entryId] ?: 0
                            (
                                (markerOverlayY(marker) ?: eventBaseTopPx) -
                                    fingerstickLiftPx -
                                    (lane * journalChipLaneStepPx)
                                ).coerceIn(journalChipMinTopPx, journalChipMaxTopPx)
                        }
                    }
                    val sourceY = when (marker.type) {
                        JournalEntryType.FINGERSTICK -> marker.chartGlucoseValue
                            ?.let(overlayValueToY)
                            ?.takeIf { it.isFinite() }
                            ?: eventRailOverlayY
                        JournalEntryType.INSULIN -> insulinConnectorY
                        else -> markerOverlayY(marker) ?: eventRailOverlayY
                    }
                    val labelEdgeX = if (preferLeadingAnchor) {
                        markerX - journalChipSideOffsetPx - connectorUnderlapPx
                    } else {
                        markerX + journalChipSideOffsetPx + connectorUnderlapPx
                    }.coerceIn(0f, size.width)
                    val labelCenterY = (markerTop + connectorLabelCenterOffsetPx).coerceIn(0f, chartHeightPx)
                    drawLine(
                        color = Color(marker.accentColor).copy(alpha = 0.22f),
                        start = Offset(markerX, sourceY.coerceIn(0f, chartHeightPx)),
                        end = Offset(labelEdgeX, labelCenterY),
                        strokeWidth = connectorStrokePx,
                        cap = StrokeCap.Round
                    )
                }
            }

            visibleOverlayMarkers.forEach { marker ->
                val markerX = markerXById[marker.entryId] ?: return@forEach
                val preferLeadingAnchor = markerX > (overlayDataWidthPx * 0.58f)
                val markerTop = when (marker.type) {
                    JournalEntryType.FINGERSTICK -> {
                        val lane = fingerstickLanes[marker.entryId] ?: 0
                        (
                            (marker.chartGlucoseValue?.let(overlayValueToY) ?: eventBaseTopPx) -
                                fingerstickLiftPx -
                                (lane * journalChipLaneStepPx)
                            ).coerceIn(journalChipMinTopPx, journalChipMaxTopPx)
                    }

                    JournalEntryType.INSULIN -> {
                        val lane = insulinLanes[marker.entryId] ?: 0
                        (
                            chartHeightPx -
                                insulinCurveHeightFor(marker.amount) -
                                insulinLabelGapPx -
                                (lane * journalChipLaneStepPx)
                            ).coerceAtLeast(journalChipMinTopPx)
                    }

                    else -> {
                        val lane = eventLanes[marker.entryId] ?: 0
                        (
                            (markerOverlayY(marker) ?: eventBaseTopPx) -
                                fingerstickLiftPx -
                                (lane * journalChipLaneStepPx)
                            ).coerceIn(journalChipMinTopPx, journalChipMaxTopPx)
                    }
                }
                JournalMarkerChip(
                    marker = marker,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .zIndex(1.5f)
                        .offset {
                            androidx.compose.ui.unit.IntOffset(
                                x = markerX.toInt(),
                                y = markerTop.toInt()
                            )
                        }
                        .graphicsLayer {
                            translationX = if (preferLeadingAnchor) {
                                -size.width - journalChipSideOffsetPx
                            } else {
                                journalChipSideOffsetPx
                            }
                        },
                    onClick = { onJournalMarkerClick?.invoke(marker.entryId) }
                )
            }

            journalActionTimestamp
                ?.takeIf { selectedPoint == null && it in overlayViewportStart..overlayViewportEnd }
                ?.let { actionTimestamp ->
                    val xFraction = (actionTimestamp - overlayViewportStart).toFloat() / overlayDuration
                    val actionX = (overlayDataWidthPx * xFraction).coerceIn(0f, overlayDataWidthPx)
                    Surface(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .zIndex(1.45f)
                            .offset {
                                androidx.compose.ui.unit.IntOffset(
                                    x = actionX.toInt(),
                                    y = journalActionChipYOffsetPx.toInt()
                                )
                            }
                            .graphicsLayer { translationX = -size.width / 2f },
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.94f),
                        tonalElevation = 0.dp,
                        shadowElevation = 0.dp
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.AccessTime,
                                contentDescription = null,
                                modifier = Modifier.size(12.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT)
                                    .format(java.util.Date(actionTimestamp)),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

            // The suggestion is not about insulin already given, so it must survive an empty
            // board: a meal with nothing dosed for it is exactly when it has something to say.
            if (activeInsulinSummary != null || forecastDoseRecommendation != null) {
                val summary = activeInsulinSummary
                val unitsLabel = { units: Float ->
                    if (units % 1f < 0.05f) {
                        units.roundToInt().toString()
                    } else {
                        String.format(java.util.Locale.getDefault(), "%.1f", units)
                    }
                }
                val iobValue = summary?.let {
                    stringResource(R.string.unit_insulin_value, unitsLabel(it.iobUnits))
                }
                val eiobValue = summary?.let {
                    stringResource(R.string.unit_insulin_value, unitsLabel(it.eiobUnits))
                }
                val totalUnitsLabel = summary?.let { unitsLabel(it.totalUnits) }
                val remainingLabel = formatRemainingDuration(
                    LocalContext.current,
                    summary?.activeUntil?.minus(System.currentTimeMillis())
                )
                val forecastRecommendationAmount = remember(
                    forecastDoseRecommendation?.kind,
                    forecastDoseRecommendation?.amount
                ) {
                    forecastDoseRecommendation?.let { recommendation ->
                        NumberFormat.getNumberInstance().apply {
                            minimumFractionDigits = if (
                                recommendation.kind == ForecastDoseRecommendationKind.INSULIN
                            ) 1 else 0
                            maximumFractionDigits = minimumFractionDigits
                            isGroupingUsed = false
                        }.format(recommendation.amount)
                    }
                }
                // Collapsed shows the bare amount ("≏ 17 g" — the unit already says which);
                // expanded has room to name what is being suggested. Standing alone it always
                // names it: without the IoB line above, "≏ 2,3 U" has nothing to lean on.
                val nameTheRecommendation = isActiveInsulinExpanded || summary == null
                val forecastRecommendationLabel = if (
                    forecastDoseRecommendation != null && forecastRecommendationAmount != null
                ) {
                    when (forecastDoseRecommendation.kind) {
                        ForecastDoseRecommendationKind.CARBS -> if (nameTheRecommendation) {
                            stringResource(
                                R.string.dashboard_forecast_recommendation_carbs,
                                forecastRecommendationAmount
                            )
                        } else {
                            stringResource(
                                R.string.dashboard_forecast_recommendation_short,
                                stringResource(R.string.unit_carbs_value, forecastRecommendationAmount)
                            )
                        }
                        ForecastDoseRecommendationKind.INSULIN -> if (nameTheRecommendation) {
                            stringResource(
                                R.string.dashboard_forecast_recommendation_insulin,
                                forecastRecommendationAmount
                            )
                        } else {
                            stringResource(
                                R.string.dashboard_forecast_recommendation_short,
                                stringResource(R.string.unit_insulin_value, forecastRecommendationAmount)
                            )
                        }
                    }
                } else null
                val activeInsulinCorner by animateDpAsState(
                    targetValue = if (isActiveInsulinExpanded) 24.dp else 16.dp,
                    animationSpec = activeInsulinMotionSpec(),
                    label = "activeInsulinCorner"
                )
                val activeInsulinShape = RoundedCornerShape(activeInsulinCorner)
                // Collapsed stays under half the chart so it never reaches the date chip;
                // expanded is allowed a little more room but still stops short of it.
                val activeInsulinMaxWidth = maxWidth * if (isActiveInsulinExpanded) {
                    ACTIVE_INSULIN_EXPANDED_MAX_WIDTH_FRACTION
                } else {
                    ACTIVE_INSULIN_MAX_WIDTH_FRACTION
                }
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(start = 12.dp, top = 12.dp)
                        .widthIn(max = activeInsulinMaxWidth)
                        .zIndex(1.6f)
                        .clip(activeInsulinShape)
                        // Nothing to expand into when the suggestion stands alone, so no tap.
                        .then(
                            if (summary != null) {
                                Modifier.clickable { isActiveInsulinExpanded = !isActiveInsulinExpanded }
                            } else {
                                Modifier
                            }
                        ),
                    shape = activeInsulinShape,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.96f),
                    tonalElevation = 0.dp,
                    shadowElevation = 0.dp
                ) {
                    Column(
                        modifier = Modifier
                            .animateContentSize(animationSpec = activeInsulinMotionSpec())
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        // Expanding spells the abbreviations out in place instead of
                        // repeating the same two numbers in a second block below.
                        if (iobValue != null) {
                            if (isActiveInsulinExpanded) {
                                Text(
                                    text = stringResource(R.string.dashboard_iob_full, iobValue),
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.SemiBold
                                )
                                if (showEiob && eiobValue != null) {
                                    Text(
                                        text = stringResource(R.string.dashboard_eiob_full, eiobValue),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            } else {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.Bottom
                                ) {
                                    Text(
                                        text = stringResource(R.string.dashboard_iob_short, iobValue),
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1
                                    )
                                    if (showEiob && eiobValue != null) {
                                        Text(
                                            text = stringResource(R.string.dashboard_eiob_short, eiobValue),
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1
                                        )
                                    }
                                }
                            }
                        }
                        // The suggestion and, while collapsed, how long the insulin still runs.
                        // Expanded states the end time in full below. With an empty board this
                        // is the card's only line.
                        if (forecastRecommendationLabel != null ||
                            (remainingLabel != null && !isActiveInsulinExpanded)
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                forecastRecommendationLabel?.let { label ->
                                    // Alone in the card it is the content, not a footnote to it.
                                    Text(
                                        text = label,
                                        style = if (summary == null) {
                                            MaterialTheme.typography.labelLarge
                                        } else {
                                            MaterialTheme.typography.labelMedium
                                        },
                                        fontWeight = if (summary == null) FontWeight.SemiBold else null,
                                        color = if (summary == null) {
                                            MaterialTheme.colorScheme.onSurface
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                        // Standing alone the label names what it suggests, which
                                        // is long enough in some locales to need the second line.
                                        maxLines = if (isActiveInsulinExpanded || summary == null) 2 else 1
                                    )
                                }
                                if (!isActiveInsulinExpanded) {
                                    remainingLabel?.let { label ->
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(
                                                imageVector = Icons.Default.AccessTime,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.size(14.dp)
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text(
                                                text = label,
                                                style = MaterialTheme.typography.labelMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        if (isActiveInsulinExpanded && summary != null && totalUnitsLabel != null) {
                            Text(
                                text = stringResource(
                                    R.string.journal_active_insulin_summary,
                                    summary.activeEntryCount,
                                    totalUnitsLabel,
                                    summary.weightedActivityPercent
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            summary.activeUntil?.let { activeUntil ->
                                Text(
                                    text = stringResource(
                                        R.string.journal_active_insulin_until,
                                        java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT)
                                            .format(java.util.Date(activeUntil))
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (activeInsulinFromRemote) {
                                Text(
                                    text = stringResource(R.string.journal_iob_source_remote),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            selectedPoint?.let { point ->
                // Calculate X position to follow cursor
                val xFraction = (point.timestamp - overlayViewportStart).toFloat() / overlayDuration
                // Clamp horizontal position to keep card on screen (assuming approx card width ~120dp)
                // We use specific offsets in standard DP
                val cardXOffset = (overlayDataWidthPx * xFraction).coerceIn(0f, overlayDataWidthPx)

                // Resolve Colors matching Graph Lines

                // --- COLORS & STYLING ---
                // MATCH GRAPH COLORS EXACTLY
                val textPrimaryColor = MaterialTheme.colorScheme.primary
                val textSecondaryColor = MaterialTheme.colorScheme.onSurfaceVariant

                // Capture Theme Colors outside non-composable builder
                val onSurfaceVariantColor = MaterialTheme.colorScheme.onSurfaceVariant

                // User Feedback: "same color as graph bg" -> Use distinct colors.
                // Info Card: Standard SurfaceContainer (Distinct from base Surface)
                // --- COLORS & STYLING ---
                // MATCH "Current Status Card" (Top Card, lines 500-503) EXACTLY
                // Top Card uses: colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                // Default Card Shape is usually Medium (12.dp) in M3.

                val statusCardColor = MaterialTheme.colorScheme.primaryContainer
                val statusContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                val cardShape = RoundedCornerShape(12.dp) // M3 standard card corner radius

                // AXIS TEXT MATCHING
                val axisFontSize = 12.sp

                // Compute calibrated value for tooltip
                val isRawModeTT = viewMode == 1 || viewMode == 3
                val hasCalibrationTT = calibratedValueResolver.hasCalibration(isRawModeTT)
                val calibratedValueTT = if (hasCalibrationTT) {
                    calibratedValueResolver.valueForPoint(point, isRawModeTT).takeIf { it > 0.1f }
                } else null
                val dvs = getDisplayValues(point, viewMode, unit, calibratedValueTT)
                val tooltipPeerPoints = peerPointsByBucket[MultiSensorDisplay.bucketKeyForTimestamp(point.timestamp)]
                    .orEmpty()

                // --- 1. INFO CARD (Top) ---
                // "Current Status Card styling" -> primaryContainer
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .zIndex(2f) // Above calibration tooltips
                        // 1. Move to Cursor X, Fixed Y
                        .offset {
                            androidx.compose.ui.unit.IntOffset(
                                x = cardXOffset.toInt(),
                                y = scrubValueLabelOffsetDp.dp.roundToPx()
                            )
                        }
                        // 2. Center
                        .graphicsLayer { translationX = -size.width / 2f }
                        .widthIn(min = 48.dp)
                        .clip(cardShape) // Clip ripple to match rounded corners
                        .pointerInput(overlayDataWidthPx, visibleDuration) {
                            detectDragGestures(
                                onDragStart = {
                                    cancelAutoScroll()
                                    isUserInteracting = true
                                    markProgrammaticViewportChange()
                                },
                                onDragEnd = {
                                    isUserInteracting = false
                                    lastInteractionTimestamp = System.currentTimeMillis()
                                },
                                onDragCancel = {
                                    isUserInteracting = false
                                    lastInteractionTimestamp = System.currentTimeMillis()
                                }
                            ) { change, dragAmount ->
                                if (abs(dragAmount.x) >= abs(dragAmount.y)) {
                                    change.consume()
                                    panViewportByPixels(dragAmount.x, overlayDataWidthPx)
                                }
                            }
                        }
                        .pointerInput(constraints.maxHeight) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = { isAdjustingScrubLabel = true },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    val minOffset = 8f
                                    val effectiveHeight =
                                        (constraints.maxHeight - chartUnderlayBottomIntPx - previewWindowReservedIntPx).coerceAtLeast(
                                            56.dp.roundToPx()
                                        )
                                    val maxOffset =
                                        ((effectiveHeight - 56.dp.roundToPx()) / density).coerceAtLeast(
                                            minOffset
                                        )
                                    scrubValueLabelOffsetDp =
                                        (scrubValueLabelOffsetDp + dragAmount.y / density).coerceIn(
                                            minOffset,
                                            maxOffset
                                        )
                                },
                                onDragEnd = {
                                    isAdjustingScrubLabel = false
                                    dashboardPrefs.edit()
                                        .putFloat(
                                            "dashboard_scrub_value_label_offset_dp",
                                            scrubValueLabelOffsetDp
                                        )
                                        .apply()
                                },
                                onDragCancel = { isAdjustingScrubLabel = false }
                            )
                        }
                        .clickable { onPointClick?.invoke(getSourcePointAt(point.timestamp) ?: point) },
                    shape = cardShape,
                    color = statusCardColor.copy(alpha = 1f),
                    contentColor = statusContentColor.copy(alpha = 1f),
                    shadowElevation = 0.dp,
                    tonalElevation = 0.dp
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // Colored Text Logic (Keep matching graph lines for values)
                        // Multi-sensor: tint the primary value subtly with its
                        // sensor identity color so it pairs with peer rows below.
                        val primaryTooltipColor = if (tooltipPeerPoints.isNotEmpty()) {
                            androidx.compose.ui.graphics.lerp(
                                statusContentColor,
                                primaryIdentityColor,
                                tk.glucodata.SensorVisuals.PRIMARY_TEXT_BLEND
                            )
                        } else {
                            statusContentColor
                        }
                        val styledText = androidx.compose.ui.text.buildAnnotatedString {
                            // Primary Value
                            withStyle(SpanStyle(color = primaryTooltipColor, fontWeight = FontWeight.Bold)) {
                                append(dvs.primaryStr)
                            }

                            // Separator & Secondary
                            dvs.secondaryStr?.let { sec ->
                                withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant)) {
                                    append(" · ")
                                }
                                withStyle(SpanStyle(color = LocalContentColor.current.copy(alpha = 0.6f), fontWeight = FontWeight.Bold)) {
                                    append(sec)
                                }
                            }

                            // Tertiary (when 3 values exist)
                            dvs.tertiaryStr?.let { ter ->
                                withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant)) {
                                    append(" · ")
                                }
                                withStyle(SpanStyle(color = LocalContentColor.current.copy(alpha = 0.4f))) {
                                    append(ter)
                                }
                            }
                        }

                        Text(
                            text = styledText,
                            style = MaterialTheme.typography.titleMedium
                        )
                        // Uncertainty, when the estimator that produced this
                        // point actually reported it. The range is stated as a
                        // range; the artifact line is phrased as a possibility,
                        // because an elevated posterior probability is not the
                        // same as an artifact having occurred.
                        // Gated on the same preference as the ribbon, not just
                        // on the point carrying an interval. Stored bands outlive
                        // a switch away from Adaptive V2, so without this the
                        // range kept appearing here for a model that never
                        // produced one.
                        point.uncertainty
                            ?.takeIf { it.isUsable && uncertaintyRibbonEnabled }
                            ?.let { uncertainty ->
                            val isMmolTooltip = GlucoseFormatter.isMmol(unit)
                            Text(
                                text = stringResource(
                                    R.string.glucose_likely_range_value,
                                    GlucoseFormatter.format(uncertainty.lower, isMmolTooltip),
                                    GlucoseFormatter.format(uncertainty.upper, isMmolTooltip),
                                ),
                                style = MaterialTheme.typography.labelMedium,
                                color = statusContentColor.copy(alpha = 0.72f),
                                modifier = Modifier.padding(top = 4.dp),
                            )
                            val statusRes = when {
                                (uncertainty.artifactProbability ?: 0f) >= ARTIFACT_NOTICE_PROBABILITY ->
                                    R.string.glucose_possible_artifact
                                (uncertainty.confidence ?: 1f) <= LOW_CONFIDENCE_NOTICE ->
                                    R.string.glucose_uncertainty_elevated
                                else -> null
                            }
                            statusRes?.let {
                                Text(
                                    text = stringResource(it),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = statusContentColor.copy(alpha = 0.58f),
                                )
                            }
                        }
                        tooltipPeerPoints.forEach { peer ->
                            val attrs = peerDrawAttrs[peer.sensorSerial]
                            val peerColor = attrs?.first ?: SensorColors.getColor(peer.sensorSerial.orEmpty())
                            val peerMode = attrs?.second ?: viewMode
                            val peerTextColor = androidx.compose.ui.graphics.lerp(
                                statusContentColor,
                                peerColor,
                                tk.glucodata.SensorVisuals.PEER_TEXT_BLEND
                            )
                            val peerDvs = getDisplayValues(peer, peerMode, unit, calibratedValue = null)
                            Text(
                                modifier = Modifier.padding(top = 3.dp),
                                text = buildGlucoseString(
                                    peerDvs,
                                    peerTextColor,
                                    statusContentColor.copy(alpha = 0.68f),
                                    statusContentColor.copy(alpha = 0.48f),
                                    false,
                                    "",
                                    statusContentColor.copy(alpha = 0.38f)
                                ),
                                style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum")
                            )
                        }
                    }
                }

                // --- 2. TIME CHIP (Bottom) - Text centered on same line as X-axis labels ---
                // Height: 32dp, Corner: 8dp, Padding: horizontal 8dp
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .zIndex(2f) // Above calibration tooltips
                        .offset {
                            androidx.compose.ui.unit.IntOffset(
                                x = cardXOffset.toInt(),
                                y = scrubTimeLabelOffsetDp.dp.roundToPx() - (chartUnderlayBottomIntPx + previewWindowReservedIntPx + labelsLiftPx.toInt()) // Keep chip aligned with visible x-axis band
                            )
                        }
                        .graphicsLayer { translationX = -size.width / 2f }
                        .pointerInput(overlayDataWidthPx, visibleDuration) {
                            detectDragGestures(
                                onDragStart = {
                                    cancelAutoScroll()
                                    isUserInteracting = true
                                    markProgrammaticViewportChange()
                                },
                                onDragEnd = {
                                    isUserInteracting = false
                                    lastInteractionTimestamp = System.currentTimeMillis()
                                },
                                onDragCancel = {
                                    isUserInteracting = false
                                    lastInteractionTimestamp = System.currentTimeMillis()
                                }
                            ) { change, dragAmount ->
                                if (abs(dragAmount.x) >= abs(dragAmount.y)) {
                                    change.consume()
                                    panViewportByPixels(dragAmount.x, overlayDataWidthPx)
                                }
                            }
                        }
                        .pointerInput(constraints.maxHeight) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = { isAdjustingScrubLabel = true },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    val effectiveHeight =
                                        (constraints.maxHeight - chartUnderlayBottomIntPx - previewWindowReservedIntPx).coerceAtLeast(
                                            40.dp.roundToPx()
                                        )
                                    val minOffset =
                                        -((effectiveHeight - 40.dp.roundToPx()) / density)
                                    val maxOffset = 16f
                                    scrubTimeLabelOffsetDp =
                                        (scrubTimeLabelOffsetDp + dragAmount.y / density).coerceIn(
                                            minOffset,
                                            maxOffset
                                        )
                                },
                                onDragEnd = {
                                    isAdjustingScrubLabel = false
                                    dashboardPrefs.edit()
                                        .putFloat(
                                            "dashboard_scrub_time_label_offset_dp",
                                            scrubTimeLabelOffsetDp
                                        )
                                        .apply()
                                },
                                onDragCancel = { isAdjustingScrubLabel = false }
                            )
                        }
                        .height(32.dp)
                        .background(
                            MaterialTheme.colorScheme.surfaceContainerHigh,
                            RoundedCornerShape(8.dp)
                        )
                        .padding(horizontal = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = point.time,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            // Brace removed here to keep subsequent elements inside BoxWithConstraints

            // --- CALIBRATION TOOLTIPS ---
            // Show permanent tooltips for calibration points in visible range (respects mode)
            val viewportStartTooltip = overlayViewportStart
            val viewportEndTooltip = overlayViewportEnd
            val visibleCalibrationsTooltip = visibleCalibrations.filter {
                it.timestamp in viewportStartTooltip..viewportEndTooltip
            }

            visibleCalibrationsTooltip.forEach { cal ->
                val calXFraction = (cal.timestamp - viewportStartTooltip).toFloat() / overlayDuration
                val calXOffset = (overlayDataWidthPx * calXFraction).coerceIn(0f, overlayDataWidthPx)
                val calTimeFormat = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
                val calTimeStr = calTimeFormat.format(java.util.Date(cal.timestamp))

                // Top: Value chip with waterdrop icon (clickable to edit)
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .zIndex(1f) // Below scrubbing tooltip
                        .offset {
                            androidx.compose.ui.unit.IntOffset(
                                x = calXOffset.toInt(),
                                y = 8.dp.roundToPx()
                            )
                        }
                        .graphicsLayer { translationX = -size.width / 2f }
                        .clip(RoundedCornerShape(8.dp))
                        .clickable {
                            // Open CalibrationBottomSheet to edit this calibration
                            onCalibrationClick?.invoke(cal)
                        },
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.85f),
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    shadowElevation = 0.dp
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Filled.WaterDrop,
                            contentDescription = null,
                            modifier = Modifier.size(12.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = String.format(java.util.Locale.getDefault(), if (unit.contains("mmol", true)) "%.1f" else "%.0f", cal.userValue),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                // Bottom: Time chip
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .zIndex(1f) // Below scrubbing tooltip
                        .offset {
                            androidx.compose.ui.unit.IntOffset(
                                x = calXOffset.toInt(),
                                y = 0.dp.roundToPx() - (chartUnderlayBottomIntPx + previewWindowReservedIntPx)
                            )
                        }
                        .graphicsLayer { translationX = -size.width / 2f }
                        .height(24.dp)
                        .background(
                            MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.85f),
                            RoundedCornerShape(6.dp)
                        )
                        .padding(horizontal = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = calTimeStr,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }


            // --- DATE HEADER OVERLAY ---
            // Show only if NOT today AND not "recent" (to avoid showing date when day just started)
            val now = System.currentTimeMillis()
            // Bucketed to a local day so panning within one day neither reformats the label
            // nor restarts the transition below — centerTime itself changes every frame.
            val headerDay = localDayIndex(centerTime)
            val isToday = headerDay == localDayIndex(now)
            val isRecent = abs(now - centerTime) < 4 * 60 * 60 * 1000L // 4 Hour buffer for "just started" days
            val showHeaderDate = !isToday && !isRecent
            val headerDate = remember(headerDay) {
                java.text.SimpleDateFormat("EEEE, d MMMM", java.util.Locale.getDefault()).format(java.util.Date(centerTime))
            }

            androidx.compose.animation.AnimatedVisibility(
                visible = showHeaderDate,
                // Grows out of its own top-right corner, where it is anchored, so it reads
                // as the chart putting the label up rather than a rectangle materialising.
                enter = fadeIn(ExpressiveMotion.defaultEffects()) + scaleIn(
                    animationSpec = ExpressiveMotion.defaultSpatial(),
                    initialScale = 0.85f,
                    transformOrigin = TransformOrigin(1f, 0f)
                ),
                exit = fadeOut(ExpressiveMotion.fastEffects()) + scaleOut(
                    animationSpec = ExpressiveMotion.fastSpatial(),
                    targetScale = 0.85f,
                    transformOrigin = TransformOrigin(1f, 0f)
                ),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 16.dp, end = 16.dp)
            ) {
                Box(
                    modifier = Modifier
                        .height(32.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .padding(horizontal = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    // Crossing midnight slides the new date in from the side the pan came
                    // from, so the label moves with the finger instead of blinking. The
                    // pill itself is outside this, so only the text travels; SizeTransform
                    // still morphs its width as the day names change length.
                    AnimatedContent(
                        targetState = headerDay to headerDate,
                        transitionSpec = {
                            val backwards = targetState.first < initialState.first
                            val entryEdge = if (backwards) -1 else 1
                            val enter = slideInHorizontally(ExpressiveMotion.defaultSpatial()) {
                                entryEdge * it / 2
                            } + fadeIn(ExpressiveMotion.defaultEffects())
                            val exit = slideOutHorizontally(ExpressiveMotion.defaultSpatial()) {
                                -entryEdge * it / 2
                            } + fadeOut(ExpressiveMotion.fastEffects())
                            enter togetherWith exit using SizeTransform(clip = false) { _, _ ->
                                ExpressiveMotion.defaultSpatial()
                            }
                        },
                        label = "chartDateHeader"
                    ) { (_, label) ->
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
            }

            /*
            // --- BACK TO NOW BUTTON ---
            // Show if centerTime is more than 5 minutes away from "real now"
            val isFarFromNow = abs(centerTime - (System.currentTimeMillis() - visibleDuration / 2)) > 60 * 60 * 1000

            androidx.compose.animation.AnimatedVisibility(
                visible = isFarFromNow,
                enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.scaleIn(),
                exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.scaleOut(),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp)
            ) {
                // M3 Expressive: FilledTonalIconButton is lighter than FAB ("less like an action button")
                // Icon: LastPage (>|) implies "Go to End/Now"
                FilledTonalIconButton(
                    onClick = {
                        val realNow = System.currentTimeMillis()
                        val targetTime = realNow - visibleDuration / 2
                        val diff = targetTime - centerTime

                        coroutineScope.launch {
                            // "Smart Scroll": Avoid crazy jumps
                            val maxScroll = 12 * 60 * 60 * 1000L // 12 Hours
                            var startScroll = centerTime

                            // If distance is huge, snap closer first
                            if (abs(diff) > maxScroll) {
                                startScroll = targetTime - (if (diff > 0) maxScroll else -maxScroll)
                                centerTime = startScroll
                            }

                            // Animate the remaining distance
                            androidx.compose.animation.core.Animatable(startScroll.toFloat()).animateTo(
                                targetValue = targetTime.toFloat(),
                                animationSpec = spring(stiffness = Spring.StiffnessMediumLow)
                            ) {
                                centerTime = value.toLong()
                            }
                        }
                    },
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                    ),
                    modifier = Modifier.size(48.dp) // Slightly larger than standard 40dp for touch target
                ) {
                    Icon(
                        imageVector = androidx.compose.material.icons.Icons.AutoMirrored.Filled.LastPage,
                        contentDescription = null
                    )
                }
            }
            */

            val showPreviewWindow = previewWindowMode == PREVIEW_WINDOW_MODE_ALWAYS ||
                (previewWindowMode == PREVIEW_WINDOW_MODE_EXPANDED_ONLY && chartBoostProgress > 0.02f)

            androidx.compose.animation.AnimatedVisibility(
                visible = showPreviewWindow,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 12.dp, vertical = 12.dp)
                    .graphicsLayer {
                        alpha = previewRevealProgress
                        translationY = (1f - previewRevealProgress) * 18.dp.toPx()
                    }
            ) {
                PreviewWindowNavigator(
                    modifier = Modifier.fillMaxWidth(),
                    renderData = renderData,
                    calibratedValueResolver = calibratedValueResolver,
                    previewCenterTime = previewCenterTime,
                    viewMode = viewMode,
                    rangeThresholds = rangeThresholds,
                    rangePalette = chartBandPalette,
                    isMmol = isMmol,
                    currentCenterTime = centerTime,
                    currentVisibleDuration = visibleDuration
                )
            }
        }

        // --- ZOOM BUTTONS (Expressive Connected Group) ---
        // --- ZOOM BUTTONS (M3 Expressive: Text + Pill Selection) ---
        // Refined based on user feedback:
        // 1. Alignment: Centered Horizontally (Arrangement) and Vertically.
        // 2. Spacing: Top 16dp, Bottom reduced to 4dp (tighter).
        val configuration = LocalConfiguration.current
        val density = LocalDensity.current
        val items = TimeRange.values()
        val now = System.currentTimeMillis()
        val targetTime = liveCenterTimeFor(latestDataTimestamp.takeIf { it > 0L } ?: now, visibleDuration)
        val isAtNow = abs(centerTime - targetTime) < 60 * 60 * 1000 // 1 hour threshold (Old behavior)
        val showBackToNow = !isAtNow
        val isScrolledRight = centerTime > targetTime

        // Responsive Breakpoints (Using user's original exact constraints)
        val baseInset = (12f * safeExpandedProgress).dp
        val baseInnerPadding = (8f * safeExpandedProgress).dp // Symmetrical now, more breathing room
        val baseVerticalPadding = (12f - (4f * safeExpandedProgress)).dp // Slightly taller container padding
        val baseOuterButtonWidth = 40.dp // Wider pill to match the taller 40dp height
        val baseOuterButtonHeight = 32.dp // Bumped up from 36dp
        val baseOuterIconSize = 22.dp // Bumped up from 20dp
        val baseBackIconSize = 26.dp
        val baseRangeHeight = 32.dp // Bumped up from 36dp
        val baseRangeHorizontalPadding = 12.dp // Looser horizontal padding around text, active one reduced down below
        val baseRangeClockGap = 4.dp // Comfortable gap between clock icon and text
        val baseRangeClockSize = 16.dp // Bumped up from 16dp
        val baseOuterButtonGap = 6.dp // Keep outer gap wide
        val baseInterItemSpacing = 2.dp // Consistent 1dp everywhere for ranges
        val baseLabelStyle = MaterialTheme.typography.labelLarge // Richer, chunkier font (16sp) to match bigger layout

        val pickerVerticalOffset = -(chartUnderlayBottomDp + (8.dp * safeExpandedProgress))

        // Two-phase dynamic shrink: measure the ideal pill width against the available
        // screen width. Phase 1 reduces inter-item spacing. Phase 2 scales everything.
        // Picker container with optional drag handle for chart expansion gesture
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .offset(y = pickerVerticalOffset)
                .zIndex(1f),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .padding(bottom = 8.dp * safeExpandedProgress)
                    .width(32.dp)
                    .height(4.dp * safeExpandedProgress)
                    .graphicsLayer { alpha = chartBoostProgress }
                    .background(
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                        shape = RoundedCornerShape(2.dp)
                    )
            )

            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = baseInset),
                contentAlignment = Alignment.Center
            ) {
            // Estimate intrinsic width of all elements at their base (unscaled) sizes.
            val avgTextWidth = 18.dp
            val rangesWidth = (baseRangeHorizontalPadding * 2 + avgTextWidth) * items.size +
                (baseRangeClockSize + baseRangeClockGap) // selected item's icon
            val rangeGapsWidth = baseInterItemSpacing * (items.size - 1)
            // Always reserve space for the Back-to-Now button so the entire UI doesn't visually resize/jump
            val fixedWidth = (baseInnerPadding * 2) + 
                baseOuterButtonWidth + baseOuterButtonGap + // date button + gap
                baseOuterButtonGap + baseOuterButtonWidth   // gap + back-to-now button
            val idealWidth = fixedWidth + rangesWidth + rangeGapsWidth + 16.dp // tighter safety margin
            val available = maxWidth
            val rangeSpacing = baseInterItemSpacing

            // Fully dynamic sizing: smoothly stretches between 0.75x (smallest screens) to 1.25x (largest screens)
            val uniformScale = if (idealWidth > 0.dp) {
                (available / idealWidth).coerceIn(0.55f, 1f)
            } else 1f

            val safeUniformScale = uniformScale.takeIf { it.isFinite() && it > 0f } ?: 1f

            fun scaled(dp: androidx.compose.ui.unit.Dp): androidx.compose.ui.unit.Dp {
                val scaled = dp * safeUniformScale
                return if (scaled.value.isFinite()) {
                    scaled.coerceAtLeast(0.dp)
                } else {
                    dp.coerceAtLeast(0.dp)
                }
            }

            val scaledLabelStyle = baseLabelStyle.copy(fontSize = baseLabelStyle.fontSize * safeUniformScale)

            // Button gaps: always consistent (never conditional on adjacent selection)
            val buttonGap = scaled(baseOuterButtonGap)

            // Connected group colors (inspired by segmented button group)
            val inactiveChipColor = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.45f)
            val activeChipColor = MaterialTheme.colorScheme.secondaryContainer

            Row(
                modifier = Modifier
                    .widthIn(max = maxWidth) // Hard cap: NEVER overflow the container
                    .wrapContentWidth()
                    .background(
                        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.65f * safeExpandedProgress),
                        shape = RoundedCornerShape(scaled(16.dp))
                    )
                    .padding(
                        horizontal = scaled(baseInnerPadding),
                        vertical = scaled(baseVerticalPadding)
                    ),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Fixed Left: Date Picker Button
                Box(
                    modifier = Modifier
                        .requiredSize(width = scaled(baseOuterButtonWidth), height = scaled(baseOuterButtonHeight))
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.secondaryContainer)
                        .clickable { showDatePicker = true },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = androidx.compose.material.icons.Icons.Filled.DateRange,
                        contentDescription = "Jump to Date",
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.requiredSize(scaled(baseOuterIconSize))
                    )
                }

                // Consistent gap after date picker
                Spacer(Modifier.width(buttonGap))

                // Time Ranges — connected group style
                items.forEachIndexed { index, range ->
                    // Collapsible gap between range chips (not before the first one)
                    if (index > 0) {
                        Spacer(Modifier.width(scaled(rangeSpacing)))
                    }

                    val rangeDur = range.hours * 60 * 60 * 1000L
                    val isSel = abs(visibleDuration - rangeDur) < 1000

                    val colorSpec = spring<Color>(stiffness = Spring.StiffnessMediumLow)

                    val containerColor by animateColorAsState(
                        targetValue = if (isSel) activeChipColor else inactiveChipColor,
                        animationSpec = colorSpec,
                        label = "ButtonContainerColor"
                    )
                    val contentColor by animateColorAsState(
                        targetValue = if (isSel) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                        animationSpec = colorSpec,
                        label = "ButtonContentColor"
                    )

                    val iconRotation by animateFloatAsState(
                        targetValue = if (isSel) 360f else 0f,
                        animationSpec = spring<Float>(
                            dampingRatio = Spring.DampingRatioMediumBouncy,
                            stiffness = Spring.StiffnessLow
                        ),
                        label = "IconRotation"
                    )

                    // Match ConnectedButtonGroup shape morphing
                    val fullRadiusPercent = 50
                    val smallRadiusPercent = 16
                    
                    val targetTopStart = if (isSel || index == 0) fullRadiusPercent else smallRadiusPercent
                    val targetBottomStart = if (isSel || index == 0) fullRadiusPercent else smallRadiusPercent
                    val targetTopEnd = if (isSel || index == items.lastIndex) fullRadiusPercent else smallRadiusPercent
                    val targetBottomEnd = if (isSel || index == items.lastIndex) fullRadiusPercent else smallRadiusPercent
                    val topStart by animateIntAsState(targetTopStart, animationSpec = spring(stiffness = Spring.StiffnessMediumLow), label = "topStart")
                    val bottomStart by animateIntAsState(targetBottomStart, animationSpec = spring(stiffness = Spring.StiffnessMediumLow), label = "bottomStart")
                    val topEnd by animateIntAsState(targetTopEnd, animationSpec = spring(stiffness = Spring.StiffnessMediumLow), label = "topEnd")
                    val bottomEnd by animateIntAsState(targetBottomEnd, animationSpec = spring(stiffness = Spring.StiffnessMediumLow), label = "bottomEnd")

                    val chipShape = RoundedCornerShape(
                        topStartPercent = topStart,
                        topEndPercent = topEnd,
                        bottomEndPercent = bottomEnd,
                        bottomStartPercent = bottomStart
                    )

                    Box(
                        modifier = Modifier
                            .height(scaled(baseRangeHeight))
                            .clip(chipShape)
                            .background(containerColor)
                            .clickable {
                                val now = System.currentTimeMillis()
                                performSubtleTick()
                                markProgrammaticViewportChange(now)
                                cancelAutoScroll()

                                if (isSel) {
                                    startAutoScrollTo(
                                        liveCenterTimeFor(
                                            latestDataTimestamp.takeIf { it > 0L } ?: now,
                                            visibleDuration
                                        )
                                    )
                                } else {
                                    applyTimeRangeDuration(rangeDur)
                                    onTimeRangeSelected?.invoke(range)
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                            modifier = Modifier.padding(
                                horizontal = scaled(if (isSel) 8.dp else baseRangeHorizontalPadding)
                            )
                        ) {
                            if (isSel) {
                                Icon(
                                    imageVector = Icons.Default.AccessTime,
                                    contentDescription = null,
                                    tint = contentColor,
                                    modifier = Modifier
                                        .padding(end = scaled(baseRangeClockGap))
                                        .requiredSize(scaled(baseRangeClockSize))
                                        .graphicsLayer { rotationZ = iconRotation }
                                )
                            }

                            Text(
                                text = stringResource(range.labelResId, range.labelAmount),
                                style = scaledLabelStyle,
                                color = contentColor,
                                softWrap = false,
                                maxLines = 1,
                                fontWeight = if (isSel) FontWeight.Bold else FontWeight.Medium
                            )
                        }
                    }
                }

                // Fixed Right: Back to Now Button (Slides in cleanly)
                AnimatedVisibility(
                    visible = showBackToNow,
                    enter = fadeIn() + expandHorizontally(expandFrom = Alignment.Start) + scaleIn(),
                    exit = fadeOut() + shrinkHorizontally(shrinkTowards = Alignment.Start) + scaleOut()
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Consistent gap before back-to-now
                        Spacer(Modifier.width(buttonGap))

                        Box(
                            modifier = Modifier
                                .requiredSize(width = scaled(baseOuterButtonWidth), height = scaled(baseOuterButtonHeight))
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.secondaryContainer)
                                .clickable {
                                    performSubtleTick()
                                    markProgrammaticViewportChange()
                                    startAutoScrollTo(targetTime)
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (isScrolledRight) Icons.Filled.FirstPage else Icons.AutoMirrored.Filled.LastPage,
                                contentDescription = "Back to Now",
                                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.requiredSize(scaled(baseBackIconSize))
                            )
                        }
                    }
                }
            }
            }
        }
    }

    // Date Picker Dialog
    if (showDatePicker) {
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        datePickerState.selectedDateMillis?.let { selectedDate ->
                            // Jump to selected date
                            performSubtleTick()
                            markProgrammaticViewportChange()
                            cancelAutoScroll()
                            centerTime = selectedDate + (12 * 60 * 60 * 1000) // Center on noon of selected day
                        }
                        showDatePicker = false
                    }
                ) {
                    Text(stringResource(R.string.go))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        ) {
            DatePicker(state = datePickerState)
        }
    }
}

@Composable
private fun JournalMarkerChip(
    marker: JournalChartMarker,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val tint = Color(marker.accentColor)
    Surface(
        modifier = modifier,
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.92f),
        border = BorderStroke(1.dp, tint.copy(alpha = 0.18f)),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = when (marker.type) {
                    JournalEntryType.INSULIN -> Icons.Default.Vaccines
                    JournalEntryType.CARBS -> Icons.Default.Restaurant
                    JournalEntryType.FINGERSTICK -> Icons.Default.Bloodtype
                    JournalEntryType.ACTIVITY -> Icons.Default.DirectionsRun
                    JournalEntryType.NOTE -> Icons.AutoMirrored.Filled.Label
                },
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(14.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = marker.detailText.ifBlank { marker.title.take(10) },
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 88.dp)
            )
        }
    }
}

/**
 * Countdown next to the clock glyph on the active-insulin chip. Rendered as "2 h 40 min"
 * rather than "02:40" so it cannot be mistaken for a wall-clock time.
 */
private fun formatRemainingDuration(context: android.content.Context, remainingMillis: Long?): String? {
    if (remainingMillis == null || remainingMillis <= 0L) return null
    val totalMinutes = (remainingMillis / 60_000L).toInt()
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return when {
        hours == 0 -> context.getString(R.string.stats_duration_minutes, minutes)
        minutes == 0 -> context.getString(R.string.stats_duration_hours, hours)
        else -> context.getString(R.string.stats_duration_hours_minutes, hours, minutes)
    }
}

/** Shared spring for the active-insulin chip's size and corner morph, so they move as one. */
private fun <T> activeInsulinMotionSpec() = ExpressiveMotion.defaultSpatial<T>()

/**
 * Which local calendar day a timestamp falls in, as a day number.
 *
 * Cheap enough to call on every pan frame, unlike the `SimpleDateFormat` comparison it
 * replaced. The DST-shortened day is off by an hour at its boundary, which is invisible
 * for a header label.
 */
private fun localDayIndex(millis: Long): Long {
    val zone = java.util.TimeZone.getDefault()
    return Math.floorDiv(millis + zone.getOffset(millis), 86_400_000L)
}

private fun resolveGraphRangeDefaults(
    requestedLow: Float,
    requestedHigh: Float,
    fallbackLow: Float,
    fallbackHigh: Float
): Pair<Float, Float> {
    val safeLow = requestedLow.takeIf { it.isFinite() && it >= 0f } ?: fallbackLow
    val safeHigh = requestedHigh.takeIf { it.isFinite() && it > safeLow + 0.1f } ?: fallbackHigh
    return if (safeHigh > safeLow + 0.1f) {
        safeLow to safeHigh
    } else {
        fallbackLow to fallbackHigh
    }
}
