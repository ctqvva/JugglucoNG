package tk.glucodata.ui.screens

import android.text.format.DateFormat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TimeText
import java.util.Date
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tk.glucodata.Applic
import tk.glucodata.CurrentDisplaySource
import tk.glucodata.DisplayDataState
import tk.glucodata.GlucosePoint
import tk.glucodata.Natives
import tk.glucodata.NotificationHistorySource
import tk.glucodata.R
import tk.glucodata.TrendAccess
import tk.glucodata.UiRefreshBus
import tk.glucodata.ui.WearGlucoseStore
import tk.glucodata.ui.WearNavigationRow
import tk.glucodata.ui.components.TrendArrowCanvas

private const val TICK_MS = 30_000L

private fun currentSnapshot(): CurrentDisplaySource.Snapshot? =
    runCatching { CurrentDisplaySource.resolveCurrent() }.getOrNull()

private fun sensorPresent(): Boolean = runCatching {
    Natives.activeSensors()?.isNotEmpty() == true
}.getOrDefault(false)

/**
 * The band colour for an alarm's value, or null while it is in range so the
 * caller keeps its own neutral tone. An alarm is worth colouring outright —
 * unlike the routine readouts, which follow the phone's value-colour setting.
 */
internal fun glucoseColor(snapshot: CurrentDisplaySource.Snapshot): Color? =
    tk.glucodata.ui.WearGlucoseColors.bandColorOrNull(snapshot.primaryValue, snapshot.isMmol)

/** The lane a view mode shows first, which is what a colour rule must judge. */
internal fun primaryLaneValue(point: GlucosePoint, viewMode: Int): Float =
    if (viewMode == 1 || viewMode == 3) {
        point.rawValue.takeIf { it.isFinite() && it > 0f } ?: point.value
    } else {
        point.value
    }

/**
 * Trend velocity for each of [rows], each measured over the ~35 minutes of
 * [history] leading up to that reading — the same window the hero uses, so a
 * row's arrow and the hero's agree on the newest reading.
 */
internal fun rowVelocities(
    history: List<GlucosePoint>,
    rows: List<GlucosePoint>,
    useRaw: Boolean,
    isMmol: Boolean,
): Map<Long, Float> {
    if (rows.isEmpty() || history.isEmpty()) return emptyMap()
    val windowMs = 35 * 60_000L
    return rows.associate { row ->
        val from = row.timestamp - windowMs
        val window = history.filter { it.timestamp in from..row.timestamp }
        val velocity = if (window.size >= 2) {
            TrendAccess.calculateVelocity(window, useRaw, isMmol).takeIf { it.isFinite() } ?: 0f
        } else {
            0f
        }
        row.timestamp to velocity
    }
}

internal fun trendArrow(rate: Float): String = runCatching {
    when (Natives.getxDripTrendName(rate)) {
        "DoubleUp" -> "↑↑"
        "SingleUp" -> "↑"
        "FortyFiveUp" -> "↗"
        "Flat" -> "→"
        "FortyFiveDown" -> "↘"
        "SingleDown" -> "↓"
        "DoubleDown" -> "↓↓"
        else -> ""
    }
}.getOrDefault("")

@Composable
fun MainScreen(
    onOpenSettings: () -> Unit,
    onOpenSensor: () -> Unit,
    onOpenReadings: () -> Unit,
    onOpenCalibrations: () -> Unit,
    onOpenJournal: () -> Unit = {},
    onCalibrateReading: (GlucosePoint) -> Unit = {},
) {
    var snapshot by remember { mutableStateOf(currentSnapshot()) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var chartRangeIndex by remember { mutableIntStateOf(0) }
    var chartOwnsDrag by remember { mutableStateOf(false) }
    var scrubbing by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        WearGlucoseStore.start()
        launch { UiRefreshBus.revision.collect { snapshot = currentSnapshot(); now = System.currentTimeMillis() } }
        while (true) { delay(TICK_MS); snapshot = currentSnapshot(); now = System.currentTimeMillis() }
    }

    // Readings come from the shared snapshot: the home list used to run its own
    // main-thread history read on every refresh, on top of the chart's.
    val storeSnapshot by WearGlucoseStore.snapshot.collectAsState()
    val isMmol = snapshot?.isMmol ?: storeSnapshot.isMmol
    val viewMode = storeSnapshot.viewMode
    val recent = remember(storeSnapshot) { WearGlucoseStore.recent(count = 6) }
    val newestReading = recent.firstOrNull()
    // One pass over the shared history gives every row its own arrow, instead of
    // each row walking the snapshot again on the main thread.
    val velocities = remember(storeSnapshot, recent, isMmol) {
        rowVelocities(storeSnapshot.points, recent, storeSnapshot.isRawMode, isMmol)
    }

    ScreenScaffold(timeText = { TimeText() }) {
        ScalingLazyColumn(
            contentPadding = PaddingValues(top = 34.dp, bottom = 28.dp),
            userScrollEnabled = !chartOwnsDrag,
        ) {
            item {
                val snap = snapshot
                val status = DisplayDataState.resolve(
                    sensorPresent = sensorPresent() || snap != null || newestReading != null,
                    currentTimestampMillis = newestReading?.timestamp ?: 0L,
                    latestHistoryTimestampMillis = 0L,
                    nowMillis = now,
                )
                // The chart IS the first screen: it takes the entire viewport
                // and the hero floats over it instead of stacking above.
                Box(Modifier.fillParentMaxHeight(0.86f).fillMaxWidth()) {
                    val density = androidx.compose.ui.platform.LocalDensity.current
                    // Where the hero actually ends, measured rather than guessed:
                    // the scrub chip docks straight underneath it, and the hero
                    // changes height when it shrinks for scrubbing or when the
                    // user's font scale moves.
                    var heroBottom by remember { mutableStateOf(HERO_TOP_OFFSET) }
                    InteractiveWearChartPanel(
                        initialRangeIndex = 0,
                        requestInitialFocus = false,
                        rangeIndexOverride = chartRangeIndex,
                        showRangeOverlay = true,
                        onRangeIndexChange = { chartRangeIndex = it },
                        onGestureOwnership = { chartOwnsDrag = it },
                        onScrubChange = { scrubbing = it },
                        headlineTopPadding = heroBottom + 3.dp,
                        modifier = Modifier.fillMaxSize(),
                    )
                    if (newestReading != null && status.hasData) {
                        HeroCard(
                            point = newestReading,
                            isMmol = isMmol,
                            viewMode = viewMode,
                            stale = status.isStale,
                            sensorId = snap?.sensorId,
                            velocity = velocities[newestReading.timestamp] ?: 0f,
                            // Scrubbing turns the hero into a reference value —
                            // the reading being read is the selected one — so it
                            // gives up its size to make room for the scrub chip
                            // instead of the chip covering the curve.
                            compact = scrubbing,
                            // Sits as high as the clock allows so the big value
                            // overlaps as little of the curve as possible.
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .offset(y = -HERO_TOP_OFFSET)
                                .onSizeChanged {
                                    heroBottom = with(density) {
                                        (it.height.toDp() - HERO_TOP_OFFSET).coerceAtLeast(0.dp)
                                    }
                                },
                        )
                    } else {
                        Column(
                            Modifier
                                .align(Alignment.TopCenter)
                                .padding(top = 32.dp)
                                .clip(RoundedCornerShape(22.dp))
                                .background(MaterialTheme.colorScheme.background.copy(alpha = 0.65f))
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                stringResource(if (status.kind == DisplayDataState.Kind.NO_SENSOR) R.string.no_sensor_title else R.string.nodata),
                                style = MaterialTheme.typography.titleMedium,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
            }
            if (recent.isNotEmpty()) {
                items(recent, key = { it.timestamp }) { point ->
                    ReadingRow(
                        point = point,
                        isMmol = isMmol,
                        viewMode = viewMode,
                        velocity = velocities[point.timestamp] ?: 0f,
                        // Tapping a reading acts on that reading, as on the
                        // phone: it calibrates against it, or edits the
                        // calibration it already carries.
                        onClick = { onCalibrateReading(point) },
                        modifier = Modifier.padding(horizontal = 18.dp),
                    )
                }
                // The phone puts History under the readings; same here.
                item {
                    Box(Modifier.padding(horizontal = 18.dp)) {
                        WearNavigationRow(stringResource(R.string.historyname), onClick = onOpenReadings)
                    }
                }
            }
            item {
                SensorCard(
                    snapshot?.sensorId,
                    now,
                    onOpenSensor,
                    modifier = Modifier.padding(horizontal = 18.dp),
                )
            }
            item {
                Box(Modifier.padding(horizontal = 18.dp)) {
                    WearNavigationRow(stringResource(R.string.calibration), onClick = onOpenCalibrations)
                }
            }
            // Only offered when the phone reports the journal as enabled, so the
            // row never leads to a screen that can do nothing.
            if (ReadingActions.journalAvailable()) {
                item {
                    Box(Modifier.padding(horizontal = 18.dp)) {
                        WearNavigationRow(stringResource(R.string.journal_title), onClick = onOpenJournal)
                    }
                }
            }
            item {
                Box(Modifier.padding(horizontal = 18.dp)) {
                    WearNavigationRow(stringResource(R.string.settings), onClick = onOpenSettings)
                }
            }
        }
    }
}

@Composable
private fun SensorCard(
    sensorId: String?,
    now: Long,
    onOpenSensor: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val id = sensorId
        ?: runCatching { Natives.lastsensorname() }.getOrNull().takeUnless { it.isNullOrEmpty() }
        ?: return
    val sensor = remember(id, now / 60_000L) { loadWearSensorPresentation(id, now) }
    val progressColor = when {
        (sensor.lifecycleProgress ?: 0f) >= 0.95f -> MaterialTheme.colorScheme.error
        (sensor.lifecycleProgress ?: 0f) >= 0.80f -> MaterialTheme.colorScheme.tertiary
        else -> Color(0xFF66BB6A)
    }
    val edgeTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
    val signalText = sensor.rssi?.let { "$it dBm" }
        ?: compactReadingAge(sensor.lastReadingMs, now)
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .drawBehind {
                val edgeWidth = 5.dp.toPx()
                drawRect(edgeTrackColor, size = Size(edgeWidth, size.height))
                sensor.lifecycleProgress?.let { progress ->
                    val fillHeight = size.height * progress.coerceIn(0f, 1f)
                    drawRect(
                        progressColor,
                        topLeft = Offset(0f, size.height - fillHeight),
                        size = Size(edgeWidth, fillHeight),
                    )
                }
            }
            .clickable(onClick = onOpenSensor)
            .padding(start = 17.dp, end = 13.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(sensor.serial, style = MaterialTheme.typography.labelLarge)
            sensor.dayValueText.takeIf { it.isNotEmpty() }?.let { dayValue ->
                Text(
                    stringResource(R.string.wear_sensor_day_format, dayValue),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            signalText?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("›", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ReadingRow(
    point: GlucosePoint,
    isMmol: Boolean,
    viewMode: Int,
    velocity: Float,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val formatter = remember(context) { DateFormat.getTimeFormat(context) }
    val action = remember(point.timestamp) { ReadingActions.resolve(point.timestamp) }
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                formatter.format(Date(point.timestamp)),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // A reading that already carries a calibration shows it, so tapping
            // is understood as editing rather than adding another.
            if (action.hasCalibration) {
                Text(
                    formatWearGlucose(
                        if (isMmol) action.calibrationUserValueMgdl / 18.0182f
                        else action.calibrationUserValueMgdl,
                        isMmol,
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        WearGlucoseValue(
            point = point,
            isMmol = isMmol,
            viewMode = viewMode,
            style = readingValueStyle(viewMode),
            primaryColor = tk.glucodata.ui.WearGlucoseColors.valueColor(
                primaryLaneValue(point, viewMode),
                isMmol,
                MaterialTheme.colorScheme.onSurface,
            ),
        )
        // The phone puts a trend arrow on every reading row; the watch showed it
        // on the hero alone, so a row said nothing about direction.
        TrendArrowCanvas(
            velocity = velocity,
            pulseKey = null,
            modifier = Modifier.size(14.dp).padding(start = 6.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * A reading rendered in the sensor's view mode: the auto lane, the raw lane, or
 * both as "4,4 · 3,1" with the secondary lane subdued, exactly as the phone
 * draws it. Values arrive from [tk.glucodata.ui.WearGlucoseStore] already
 * calibrated, so no correction is applied a second time here.
 */
@Composable
internal fun WearGlucoseValue(
    point: GlucosePoint,
    isMmol: Boolean,
    viewMode: Int,
    style: androidx.compose.ui.text.TextStyle,
    primaryColor: Color,
    modifier: Modifier = Modifier,
) {
    val dvs = remember(point.timestamp, point.value, point.rawValue, viewMode, isMmol) {
        tk.glucodata.ui.DisplayValueResolver.resolve(
            autoValue = point.value,
            rawValue = point.rawValue,
            viewMode = viewMode,
            isMmol = isMmol,
        )
    }
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
    Text(
        text = tk.glucodata.ui.buildGlucoseString(
            dvs = dvs,
            primaryColor = primaryColor,
            secondaryColor = secondary,
            unitColor = secondary.copy(alpha = 0.6f),
            tertiaryColor = secondary.copy(alpha = 0.55f),
        ),
        // Time, value and arrow have to share a narrow round screen; wrapping a
        // two-lane value onto a second line would break the row's rhythm.
        style = style,
        maxLines = 1,
        modifier = modifier,
    )
}

/**
 * Row-sized style for a reading. A second lane roughly doubles the width the
 * value needs, so the dual modes step down one size rather than crowd out the
 * arrow; a single lane keeps the row's usual [singleLane] size.
 */
@Composable
internal fun readingValueStyle(
    viewMode: Int,
    singleLane: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.titleMedium,
    dualLane: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.bodyMedium,
): androidx.compose.ui.text.TextStyle =
    if (viewMode == 2 || viewMode == 3) dualLane else singleLane

/** How far the hero is lifted above the chart's top edge. */
internal val HERO_TOP_OFFSET = 18.dp

@Composable
internal fun HeroCard(
    point: GlucosePoint,
    isMmol: Boolean,
    viewMode: Int,
    stale: Boolean,
    sensorId: String?,
    velocity: Float,
    compact: Boolean = false,
    modifier: Modifier = Modifier,
) {
    // The big number must be the same value the readings row below shows: the
    // hero used to resolve its own snapshot through CurrentDisplaySource while
    // the list came from NotificationHistorySource, so the two disagreed and
    // the hero looked stale against its own list.
    val primaryValue = remember(point.value, point.rawValue, viewMode) {
        primaryLaneValue(point, viewMode)
    }
    val neutral = MaterialTheme.colorScheme.onSurface
    // Same rule as the phone hero: the number is neutral unless the user has
    // value range colours on, and the container carries the band tint instead.
    val valueColor = if (stale) {
        MaterialTheme.colorScheme.onSurfaceVariant
    } else {
        tk.glucodata.ui.WearGlucoseColors.valueColor(primaryValue, isMmol, neutral)
    }
    val scrim = MaterialTheme.colorScheme.background.copy(alpha = 0.60f)
    val tint = tk.glucodata.ui.WearGlucoseColors.heroTint(primaryValue, isMmol, isFresh = !stale)
    val background = tint?.let { (tone, fraction) ->
        androidx.compose.ui.graphics.lerp(scrim, tone.copy(alpha = scrim.alpha), fraction)
    } ?: scrim
    // Floating pill over the chart: wraps content, translucent scrim so the
    // curve stays visible behind it.
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(24.dp))
            .background(background)
            .padding(horizontal = 12.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val dvs = remember(point.timestamp, point.value, point.rawValue, viewMode, isMmol) {
            tk.glucodata.ui.DisplayValueResolver.resolve(
                autoValue = point.value,
                rawValue = point.rawValue,
                viewMode = viewMode,
                isMmol = isMmol,
            )
        }
        val valueSize by animateFloatAsState(
            targetValue = if (compact) 24f else 44f,
            label = "HeroValueSize",
        )
        Text(
            dvs.primaryStr,
            style = MaterialTheme.typography.displayLarge.copy(
                fontSize = valueSize.sp,
                fontWeight = FontWeight.SemiBold,
            ),
            color = valueColor,
            maxLines = 1,
        )
        // The extra lanes ride alongside rather than inline at hero size: nine
        // characters at 44sp run off the side of a round screen, and the point
        // of the secondary lane is comparison, not prominence. Compact drops
        // them entirely — the scrub chip below is showing both lanes already.
        val extraLanes = if (compact) emptyList() else listOfNotNull(dvs.secondaryStr, dvs.tertiaryStr)
        if (extraLanes.isNotEmpty()) {
            Column(
                Modifier.padding(start = 5.dp),
                horizontalAlignment = Alignment.Start,
            ) {
                extraLanes.forEachIndexed { index, lane ->
                    Text(
                        lane,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                            .copy(alpha = if (index == 0) 0.85f else 0.6f),
                        maxLines = 1,
                    )
                }
            }
        }
        TrendArrowCanvas(
            velocity = velocity,
            pulseKey = point.timestamp,
            modifier = Modifier.size(if (compact) 18.dp else 28.dp).padding(start = 4.dp),
            color = valueColor,
        )
    }
}
