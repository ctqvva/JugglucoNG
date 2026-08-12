package tk.glucodata.ui.screens

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TimeText
import java.util.Date
import kotlin.math.abs
import kotlin.math.ceil
import kotlinx.coroutines.launch
import tk.glucodata.Applic
import tk.glucodata.CalibrationAccess
import tk.glucodata.CurrentDisplaySource
import tk.glucodata.GlucosePoint
import tk.glucodata.GlucoseRangeColors
import tk.glucodata.GlucoseValuePlausibility
import tk.glucodata.Natives
import tk.glucodata.NotificationHistorySource
import tk.glucodata.R
import tk.glucodata.UiRefreshBus
import tk.glucodata.ui.WearGlucoseStore

internal val CHART_RANGES = intArrayOf(3, 6, 12, 24, 72)

private const val HOUR_MS = 3_600_000L
// Matches the WearSync2 backfill horizon so panning can reach the
// oldest synced reading instead of stopping a day back.
private const val MAX_HISTORY_HOURS = 14 * 24
private const val RIGHT_GAP_FRACTION = 0.09f
private const val MIN_VIEWPORT_MS = 45 * 60_000L

private fun plausibleRawValue(point: GlucosePoint, isMmol: Boolean): Float? =
    point.rawValue.takeIf {
        GlucoseValuePlausibility.isPlausibleDisplayValue(it, isMmol)
    }

internal data class ChartThresholds(val low: Float, val high: Float, val veryLow: Float, val veryHigh: Float)
internal data class CalibrationMark(val timestamp: Long, val value: Float)
internal data class WearChartData(
    val points: List<GlucosePoint>,
    val calibrations: List<CalibrationMark>,
    val thresholds: ChartThresholds,
    val start: Long,
    val end: Long,
    val historyStart: Long,
    val isMmol: Boolean,
)

private fun thresholds(isMmol: Boolean): ChartThresholds {
    fun nativeOrDefault(value: () -> Float, fallback: Float) =
        runCatching(value).getOrNull()?.takeIf { it.isFinite() && it > 0f } ?: fallback
    return ChartThresholds(
        nativeOrDefault(Natives::targetlow, GlucoseRangeColors.defaultLow(isMmol)),
        nativeOrDefault(Natives::targethigh, GlucoseRangeColors.defaultHigh(isMmol)),
        nativeOrDefault(Natives::alarmverylow, GlucoseRangeColors.defaultVeryLow(isMmol)),
        nativeOrDefault(Natives::alarmveryhigh, GlucoseRangeColors.defaultVeryHigh(isMmol)),
    )
}

/**
 * Projects the shared snapshot onto a viewport. Pure: the reading of native and
 * Room happens once in [WearGlucoseStore], off the main thread, instead of here
 * on every range change, refresh and minute tick.
 */
internal fun chartDataFrom(snapshot: WearGlucoseStore.Snapshot, hours: Int): WearChartData {
    val now = System.currentTimeMillis()
    val duration = hours * HOUR_MS
    val start = now - duration
    val end = now + (duration * RIGHT_GAP_FRACTION).toLong()
    val historyStart = if (snapshot.isLoaded) snapshot.horizonStartMs else start
    val isMmol = snapshot.isMmol
    val conversion = if (isMmol) 18.0182f else 1f
    val anchors = snapshot.anchors
    val marks = anchors.indices.step(3).mapNotNull { offset ->
        if (offset + 2 >= anchors.size) return@mapNotNull null
        CalibrationMark(anchors[offset + 2].toLong(), anchors[offset + 1].toFloat() / conversion)
            .takeIf { it.timestamp in historyStart..now && it.value.isFinite() && it.value > 0f }
    }
    return WearChartData(snapshot.points, marks, thresholds(isMmol), start, end, historyStart, isMmol)
}

private fun clampedViewport(data: WearChartData, start: Long, end: Long): Pair<Long, Long> {
    val availableDuration = (data.end - data.historyStart).coerceAtLeast(MIN_VIEWPORT_MS)
    val duration = (end - start).coerceIn(MIN_VIEWPORT_MS, availableDuration)
    val clampedStart = start.coerceIn(data.historyStart, data.end - duration)
    return clampedStart to (clampedStart + duration)
}

@Composable
fun ChartScreen() {
    ScreenScaffold(timeText = { TimeText() }) {
        InteractiveWearChartPanel(
            modifier = Modifier.fillMaxSize().padding(top = 22.dp),
        )
    }
}

@Composable
internal fun InteractiveWearChartPanel(
    modifier: Modifier = Modifier,
    initialRangeIndex: Int = 1,
    requestInitialFocus: Boolean = true,
    rangeIndexOverride: Int? = null,
    showRangeOverlay: Boolean = true,
    onRangeIndexChange: ((Int) -> Unit)? = null,
    onGestureOwnership: ((Boolean) -> Unit)? = null,
    headlineTopPadding: androidx.compose.ui.unit.Dp = 3.dp,
) {
    var rangeIndex by remember { mutableIntStateOf(initialRangeIndex.coerceIn(CHART_RANGES.indices)) }
    LaunchedEffect(Unit) { WearGlucoseStore.start() }
    val storeSnapshot by WearGlucoseStore.snapshot.collectAsState()
    val isMmol = storeSnapshot.isMmol
    // The full mode, not a raw/auto boolean: collapsing it here meant the
    // second trace of auto+raw / raw+auto could never be drawn.
    val viewMode = storeSnapshot.viewMode
    val data = remember(storeSnapshot, rangeIndex) {
        chartDataFrom(storeSnapshot, CHART_RANGES[rangeIndex])
    }
    var viewportStart by remember { mutableLongStateOf(data.start) }
    var viewportEnd by remember { mutableLongStateOf(data.end) }
    // Whether the viewport is parked at "now" and should follow new readings, or
    // the user has panned back and should be left where they put it.
    var followNow by remember { mutableStateOf(true) }
    var selected by remember { mutableStateOf<GlucosePoint?>(null) }
    val requester = remember { FocusRequester() }
    val context = LocalContext.current
    val timeFormat = remember(context) { DateFormat.getTimeFormat(context) }

    fun resetViewport(nextData: WearChartData = data) {
        viewportStart = nextData.start
        viewportEnd = nextData.end
        followNow = true
        selected = null
    }

    fun zoomViewport(zoomFactor: Float, focusFraction: Float = 0.5f) {
        val oldDuration = (viewportEnd - viewportStart).coerceAtLeast(1L)
        val maxDuration = data.end - data.historyStart
        val duration = (oldDuration / zoomFactor).toLong().coerceIn(MIN_VIEWPORT_MS, maxDuration)
        val focus = viewportStart + (oldDuration * focusFraction).toLong()
        val start = focus - (duration * focusFraction).toLong()
        clampedViewport(data, start, start + duration).let {
            viewportStart = it.first
            viewportEnd = it.second
        }
        followNow = abs(viewportEnd - data.end) < 2 * 60_000L
        selected = null
    }

    // Picking a wider range needs history that deep; anything shorter draws from
    // what is already loaded.
    LaunchedEffect(rangeIndex) {
        WearGlucoseStore.ensureHorizon(CHART_RANGES[rangeIndex] * HOUR_MS * 2)
        resetViewport(chartDataFrom(storeSnapshot, CHART_RANGES[rangeIndex]))
    }
    LaunchedEffect(rangeIndexOverride) {
        rangeIndexOverride?.let { rangeIndex = it.coerceIn(CHART_RANGES.indices) }
    }
    // New data shifts "now": follow it while parked at the right edge, otherwise
    // just keep the panned viewport inside the available range.
    LaunchedEffect(data.start, data.end) {
        if (followNow) {
            viewportStart = data.start
            viewportEnd = data.end
        } else {
            clampedViewport(data, viewportStart, viewportEnd).let {
                viewportStart = it.first
                viewportEnd = it.second
            }
        }
    }
    LaunchedEffect(Unit) {
        if (requestInitialFocus) requester.requestFocus()
    }

    val primaryRaw = viewMode == 1 || viewMode == 3
    val showSecondary = viewMode == 2 || viewMode == 3
    val neutralLineColor = MaterialTheme.colorScheme.onSurface
    val lineColor = data.points.lastOrNull()?.let {
        rangeColor(
            if (primaryRaw) plausibleRawValue(it, isMmol) ?: it.value else it.value,
            isMmol,
            neutralLineColor,
        )
    } ?: neutralLineColor
    val gridColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.13f)
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val targetColor = Color(GlucoseRangeColors.inRange(true))
    val alarmColor = MaterialTheme.colorScheme.error
    val selectionColor = MaterialTheme.colorScheme.primary

    Box(
        modifier
            .onRotaryScrollEvent { event ->
                zoomViewport(if (event.verticalScrollPixels < 0f) 1.16f else 1f / 1.16f)
                true
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    requester.requestFocus()
                    var hasPressedPointers: Boolean
                    do {
                        hasPressedPointers = awaitPointerEvent().changes.any { it.pressed }
                    } while (hasPressedPointers)
                }
            }
            .focusRequester(requester)
            .focusable(),
    ) {
            WearChart(
                data = data,
                viewportStart = viewportStart,
                viewportEnd = viewportEnd,
                lineColor = lineColor,
                rawColor = labelColor.copy(alpha = 0.52f),
                primaryRaw = primaryRaw,
                showSecondary = showSecondary,
                targetColor = targetColor,
                alarmColor = alarmColor,
                gridColor = gridColor,
                labelColor = labelColor,
                selected = selected,
                selectionColor = selectionColor,
                formatTime = { timeFormat.format(Date(it)) },
                onSelect = { selected = it },
                onViewportChange = { start, end ->
                    clampedViewport(data, start, end).let {
                        viewportStart = it.first
                        viewportEnd = it.second
                    }
                    // Panning to the loaded edge pulls in more history, so the
                    // deep horizon is read only when someone goes looking for it.
                    followNow = abs(viewportEnd - data.end) < 2 * 60_000L
                    if (viewportStart <= data.historyStart + HOUR_MS) {
                        WearGlucoseStore.ensureHorizon(
                            (System.currentTimeMillis() - viewportStart) * 2,
                        )
                    }
                    selected = null
                },
                onReset = { resetViewport() },
                onGestureOwnership = onGestureOwnership,
                modifier = Modifier.fillMaxSize().padding(top = 24.dp, bottom = 8.dp),
            )
            if (showRangeOverlay) WearChartRangeChip(
                rangeIndex = rangeIndex,
                onClick = {
                    rangeIndex = (rangeIndex + 1) % CHART_RANGES.size
                    onRangeIndexChange?.invoke(rangeIndex)
                },
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 2.dp),
            )
            val headline = selected?.let {
                val raw = plausibleRawValue(it, isMmol)
                val primary = if (primaryRaw && raw != null) raw else it.value
                val secondary = if (showSecondary) {
                    if (primaryRaw) it.value else raw
                } else null
                val values = if (secondary != null) {
                    "${formatWearGlucose(primary, isMmol)} / ${formatWearGlucose(secondary, isMmol)}"
                } else {
                    formatWearGlucose(primary, isMmol)
                }
                "$values  ${timeFormat.format(Date(it.timestamp))}"
            }
            headline?.let {
                Text(it, style = MaterialTheme.typography.labelLarge, modifier = Modifier.align(Alignment.TopCenter).padding(top = headlineTopPadding))
            }
            if (data.points.isEmpty()) {
                Text(
                    stringResource(R.string.nodata),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
    }
}

@Composable
internal fun WearChartRangeChip(
    rangeIndex: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Text(
        CHART_RANGES[rangeIndex.coerceIn(CHART_RANGES.indices)].let { h ->
            if (h >= 48) "${h / 24}d" else "${h}h"
        },
        style = MaterialTheme.typography.labelLarge,
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.85f), CircleShape)
            .pointerInput(Unit) { detectTapGestures { onClick() } }
            .padding(horizontal = 11.dp, vertical = 4.dp),
    )
}

private fun currentWearViewMode(): Int {
    val sensor = NotificationHistorySource.resolveSensorSerial()
    return CurrentDisplaySource.resolveViewModeForSensor(sensor).coerceIn(0, 3)
}

private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.detectChartTransforms(
    onOwnership: (Boolean) -> Unit = {},
    onTransform: (centroid: Offset, pan: Offset, zoom: Float) -> Unit,
) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        var accumulatedPan = Offset.Zero
        var chartOwnsGesture = false
        // Ownership pauses the parent list's scrolling, so it must be released
        // on every exit from this loop: a missed release left the whole screen
        // unscrollable until the app was restarted.
        try {
            while (true) {
                val event = awaitPointerEvent()
                val pressedCount = event.changes.count { it.pressed }
                if (pressedCount == 0) break
                if (!chartOwnsGesture && event.changes.any { it.isConsumed }) break

                val pan = event.calculatePan()
                val zoom = event.calculateZoom()
                accumulatedPan += pan
                if (!chartOwnsGesture) {
                    // A thumb drag on a round screen is never purely horizontal,
                    // so the chart takes a mostly sideways drag; a clearly
                    // vertical one is left to the list.
                    val dx = abs(accumulatedPan.x)
                    val dy = abs(accumulatedPan.y)
                    val pastSlop = accumulatedPan.getDistance() > viewConfiguration.touchSlop * 0.5f
                    if (pastSlop && dy > dx * 1.2f) break
                    chartOwnsGesture = pressedCount >= 2 || (pastSlop && dx >= dy)
                    if (chartOwnsGesture) onOwnership(true)
                }
                if (chartOwnsGesture) {
                    onTransform(event.calculateCentroid(), pan, zoom)
                    event.changes.forEach { change ->
                        if (change.positionChanged()) change.consume()
                    }
                }
            }
        } finally {
            if (chartOwnsGesture) onOwnership(false)
        }
    }
}

private fun pointAtLive(
    x: Float,
    width: Int,
    start: Long,
    end: Long,
    points: List<GlucosePoint>,
): GlucosePoint? {
    if (width <= 0) return null
    val timestamp = start + ((x / width) * (end - start)).toLong()
    return points.minByOrNull { abs(it.timestamp - timestamp) }
}

@Composable
internal fun WearChart(
    data: WearChartData,
    onGestureOwnership: ((Boolean) -> Unit)? = null,
    viewportStart: Long = data.start,
    viewportEnd: Long = data.end,
    lineColor: Color,
    rawColor: Color = Color.Transparent,
    primaryRaw: Boolean = false,
    showSecondary: Boolean = false,
    targetColor: Color,
    alarmColor: Color,
    gridColor: Color,
    labelColor: Color,
    selected: GlucosePoint?,
    selectionColor: Color,
    formatTime: (Long) -> String,
    onSelect: (GlucosePoint?) -> Unit,
    onViewportChange: ((Long, Long) -> Unit)? = null,
    onReset: (() -> Unit)? = null,
    modifier: Modifier,
) {
    val selectedState = rememberUpdatedState(selected)
    val viewportPoints = remember(data.points, viewportStart, viewportEnd) {
        data.points.filter { it.timestamp in viewportStart..viewportEnd }
    }
    fun pointAt(x: Float, width: Int): GlucosePoint? {
        if (width <= 0) return null
        val timestamp = viewportStart + ((x / width) * (viewportEnd - viewportStart)).toLong()
        return viewportPoints.minByOrNull { abs(it.timestamp - timestamp) }
    }
    // The pointerInput keys must not contain the viewport: it changes on every
    // frame of a pan, which tore down and restarted the gesture detector
    // mid-drag. Each touch then moved the chart once and waited for a fresh
    // touch slop, which is why panning crawled. The handlers read the live
    // viewport through state instead, so the detector survives the gesture.
    val liveStart = rememberUpdatedState(viewportStart)
    val liveEnd = rememberUpdatedState(viewportEnd)
    val livePoints = rememberUpdatedState(viewportPoints)
    val gestures = if (onViewportChange == null) {
        Modifier
    } else {
        Modifier
            .pointerInput(Unit) {
                detectChartTransforms(onOwnership = onGestureOwnership ?: {}) { centroid, pan, zoom ->
                    val width = size.width.toFloat().coerceAtLeast(1f)
                    val start = liveStart.value
                    val oldDuration = (liveEnd.value - start).coerceAtLeast(1L)
                    val duration = (oldDuration / zoom).toLong().coerceAtLeast(1L)
                    val focusFraction = (centroid.x / width).coerceIn(0f, 1f)
                    val focus = start + (oldDuration * focusFraction).toLong()
                    val zoomedStart = focus - (duration * focusFraction).toLong()
                    val panMillis = (-(pan.x / width) * duration).toLong()
                    onViewportChange(zoomedStart + panMillis, zoomedStart + panMillis + duration)
                }
            }
            .pointerInput(Unit) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { onSelect(pointAtLive(it.x, size.width, liveStart.value, liveEnd.value, livePoints.value)) },
                    onDrag = { change, _ ->
                        change.consume()
                        onSelect(pointAtLive(change.position.x, size.width, liveStart.value, liveEnd.value, livePoints.value))
                    },
                )
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = { onReset?.invoke() },
                    onTap = { onSelect(pointAt(it.x, size.width)) },
                )
            }
    }
    Box(
        modifier.then(gestures).drawWithCache {
            val floor = if (data.isMmol) 2.2f else 40f
            // Fit the value range to the visible data, not the alarm limits —
            // forcing veryLow..veryHigh into view squashed a flat curve into a
            // sliver. Threshold/target lines simply clip when out of range.
            fun primaryValue(point: GlucosePoint) =
                if (primaryRaw) plausibleRawValue(point, data.isMmol) ?: point.value else point.value
            var minValue = viewportPoints.minOfOrNull(::primaryValue) ?: data.thresholds.low
            var maxValue = viewportPoints.maxOfOrNull(::primaryValue) ?: data.thresholds.high
            val minSpan = if (data.isMmol) 3f else 54f
            if (maxValue - minValue < minSpan) {
                val mid = (maxValue + minValue) / 2f
                minValue = mid - minSpan / 2f
                maxValue = mid + minSpan / 2f
            }
            if (showSecondary) {
                viewportPoints.forEach { point ->
                    val value = if (primaryRaw) point.value else plausibleRawValue(point, data.isMmol)
                    if (value != null && value.isFinite() && value > 0f) {
                        minValue = minOf(minValue, value)
                        maxValue = maxOf(maxValue, value)
                    }
                }
            }
            val padding = ((maxValue - minValue) * 0.12f).coerceAtLeast(if (data.isMmol) 0.4f else 8f)
            minValue = (minValue - padding).coerceAtLeast(floor)
            maxValue += padding
            val valueRange = (maxValue - minValue).coerceAtLeast(0.1f)
            val timeRange = (viewportEnd - viewportStart).toFloat().coerceAtLeast(1f)
            val plotTop = 8.dp.toPx()
            val plotBottom = (size.height - 10.dp.toPx()).coerceAtLeast(plotTop + 1f)
            val plotHeight = plotBottom - plotTop
            fun x(time: Long) = ((time - viewportStart).toFloat() / timeRange) * size.width
            fun y(value: Float) = plotBottom - ((value - minValue) / valueRange) * plotHeight

            fun buildCurve(raw: Boolean): Path {
                val curve = Path()
                var previous: Offset? = null
                viewportPoints.forEach { point ->
                    val value = if (raw) plausibleRawValue(point, data.isMmol) else point.value
                    if (value == null || !value.isFinite() || value <= 0f) {
                        previous = null
                    } else {
                        val current = Offset(x(point.timestamp), y(value))
                        val last = previous
                        if (last == null) {
                            curve.moveTo(current.x, current.y)
                        } else {
                            val controlX = (last.x + current.x) / 2f
                            curve.cubicTo(controlX, last.y, controlX, current.y, current.x, current.y)
                        }
                        previous = current
                    }
                }
                return curve
            }

            val curve = buildCurve(primaryRaw)
            val secondaryCurve = if (showSecondary) buildCurve(!primaryRaw) else null
            val alarmDash = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 5.dp.toPx()))
            val calibrationDrops = data.calibrations.mapNotNull { mark ->
                if (mark.timestamp !in viewportStart..viewportEnd) return@mapNotNull null
                val center = Offset(x(mark.timestamp), y(mark.value))
                Path().apply {
                    moveTo(center.x, center.y - 6.dp.toPx())
                    cubicTo(center.x - 5.dp.toPx(), center.y, center.x - 4.dp.toPx(), center.y + 5.dp.toPx(), center.x, center.y + 5.dp.toPx())
                    cubicTo(center.x + 4.dp.toPx(), center.y + 5.dp.toPx(), center.x + 5.dp.toPx(), center.y, center.x, center.y - 6.dp.toPx())
                    close()
                }
            }
            val textPaint = android.graphics.Paint().apply {
                color = android.graphics.Color.argb((labelColor.alpha * 255).toInt(), (labelColor.red * 255).toInt(), (labelColor.green * 255).toInt(), (labelColor.blue * 255).toInt())
                textSize = 9.dp.toPx()
                textAlign = android.graphics.Paint.Align.CENTER
                isAntiAlias = true
            }
            val yStep = if (data.isMmol) 2f else 50f
            val yLabels = buildList {
                var value = ceil(minValue / yStep) * yStep
                while (value < maxValue) { add(value); value += yStep }
            }
            val yLabelTexts = yLabels.map { value -> value to formatWearGlucose(value, data.isMmol) }
            val bandColor = targetColor.copy(alpha = 0.06f)
            val lowAlarmColor = alarmColor.copy(alpha = 0.56f)
            val selectedLineColor = selectionColor.copy(alpha = 0.6f)
            val quarterTimes = longArrayOf(
                viewportStart + (viewportEnd - viewportStart) / 4,
                viewportStart + (viewportEnd - viewportStart) * 3 / 4,
            )
            val quarterLabels = quarterTimes.map { timestamp -> timestamp to formatTime(timestamp) }
            onDrawBehind {
                val bandTop = y(data.thresholds.high)
                val bandBottom = y(data.thresholds.low)
                drawRect(bandColor, Offset(0f, bandTop), Size(size.width, bandBottom - bandTop))
                yLabelTexts.forEach { (value, text) ->
                    val lineY = y(value)
                    drawLine(gridColor, Offset(0f, lineY), Offset(size.width, lineY), 1f)
                    textPaint.textAlign = android.graphics.Paint.Align.LEFT
                    drawContext.canvas.nativeCanvas.drawText(text, 14.dp.toPx(), lineY - 2.dp.toPx(), textPaint)
                }
                drawLine(lowAlarmColor, Offset(0f, y(data.thresholds.veryLow)), Offset(size.width, y(data.thresholds.veryLow)), 1.dp.toPx(), pathEffect = alarmDash)
                drawLine(lowAlarmColor, Offset(0f, y(data.thresholds.veryHigh)), Offset(size.width, y(data.thresholds.veryHigh)), 1.dp.toPx(), pathEffect = alarmDash)
                quarterLabels.forEach { (timestamp, text) ->
                    val lineX = x(timestamp)
                    drawLine(gridColor, Offset(lineX, 0f), Offset(lineX, size.height), 1f)
                    textPaint.textAlign = android.graphics.Paint.Align.CENTER
                    drawContext.canvas.nativeCanvas.drawText(text, lineX, size.height - 2.dp.toPx(), textPaint)
                }
                secondaryCurve?.let { drawPath(it, rawColor, style = Stroke(1.35.dp.toPx())) }
                if (viewportPoints.size >= 2) drawPath(curve, lineColor, style = Stroke(2.6.dp.toPx()))
                calibrationDrops.forEach { drawPath(it, selectionColor) }
                selectedState.value?.takeIf { it.timestamp in viewportStart..viewportEnd }?.let {
                    val sx = x(it.timestamp)
                    drawLine(selectedLineColor, Offset(sx, 0f), Offset(sx, size.height), 1.dp.toPx())
                    drawCircle(selectionColor, 4.dp.toPx(), Offset(sx, y(it.value)))
                }
            }
        },
    )
}
