package tk.glucodata.ui.screens

import android.text.format.DateFormat
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

// Resolved for the selection's primary, not for whatever native calls current:
// with two sensors syncing the latter changed with every chunk.
private fun currentSnapshot(): CurrentDisplaySource.Snapshot? =
    runCatching {
        CurrentDisplaySource.resolveCurrent(
            preferredSensorId = tk.glucodata.ui.WearSensorSelection.resolve(),
        )
    }.getOrNull()

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
 * Trend velocity for each of [rows], measured over the ~35 minutes of [history]
 * leading up to that reading — the same window the hero uses, so a row's arrow
 * and the hero's agree on the newest reading.
 *
 * The sweep itself is in tk.glucodata.TrendWindows, where it can be tested;
 * the wear source set is not on the unit-test classpath.
 */
internal fun rowVelocities(
    history: List<GlucosePoint>,
    rows: List<GlucosePoint>,
    useRaw: Boolean,
    isMmol: Boolean,
): Map<Long, Float> = tk.glucodata.TrendWindows.velocities(history, rows, useRaw, isMmol)

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
    onAddJournalAt: ((GlucosePoint) -> Unit)? = null,
) {
    var snapshot by remember { mutableStateOf(currentSnapshot()) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var chartRangeIndex by remember { mutableIntStateOf(0) }
    var chartOwnsDrag by remember { mutableStateOf(false) }

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
    val rowPeers = remember(storeSnapshot, recent, isMmol) {
        readingPeers(recent, storeSnapshot.peers, isMmol)
    }
    val primaryIdentity = remember(storeSnapshot.sensorId, storeSnapshot.peers) {
        if (storeSnapshot.peers.isEmpty()) null
        else tk.glucodata.ui.WearSensorSelection.colorOf(storeSnapshot.sensorId)
    }
    // The other shown sensors' newest readings, for the hero's peer chips.
    val peerReadings = remember(storeSnapshot, isMmol, now / TICK_MS) {
        peerReadings(storeSnapshot.peers, isMmol, now)
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
                    // Where the hero actually ends, measured rather than guessed,
                    // so the scrub chip butts straight against it instead of
                    // landing over the curve at a hand-tuned offset.
                    var heroBottom by remember { mutableStateOf(HERO_TOP_OFFSET) }
                    InteractiveWearChartPanel(
                        initialRangeIndex = 0,
                        requestInitialFocus = false,
                        rangeIndexOverride = chartRangeIndex,
                        showRangeOverlay = true,
                        onRangeIndexChange = { chartRangeIndex = it },
                        onGestureOwnership = { chartOwnsDrag = it },
                        // The scrub chip acts on the reading it shows, the same
                        // way the hero and the rows act on theirs.
                        onSelectedReadingClick = onCalibrateReading,
                        headlineTopPadding = heroBottom,
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
                            peers = peerReadings,
                            onClick = { onCalibrateReading(newestReading) },
                            // As on the phone's hero: a peer's chip promotes it.
                            onPeerClick = { tk.glucodata.ui.WearSensorSelection.makePrimary(it) },
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
                        peers = rowPeers[point.timestamp].orEmpty(),
                        primaryColorArgb = primaryIdentity,
                        // Tapping a reading acts on that reading, as on the
                        // phone: it calibrates against it, or edits the
                        // calibration it already carries.
                        onClick = { onCalibrateReading(point) },
                        onAddJournal = onAddJournalAt
                            ?.takeIf { ReadingActions.journalAvailable() }
                            ?.let { add -> { add(point) } },
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
    peers: List<WearReadingPeer> = emptyList(),
    primaryColorArgb: Int? = null,
    onAddJournal: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val formatter = remember(context) { DateFormat.getTimeFormat(context) }
    val action = remember(point.timestamp) { ReadingActions.resolve(point.timestamp) }
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    formatter.format(Date(point.timestamp)),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // Sits just right of the time, where the phone's rows put it:
                // the row calibrates, the "+" logs against that reading.
                if (onAddJournal != null) {
                    Box(
                        Modifier
                            .padding(start = 4.dp)
                            .size(20.dp)
                            .clip(androidx.compose.foundation.shape.CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .clickable(onClick = onAddJournal),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "+",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
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
        ReadingValues(point, viewMode, isMmol, velocity, peers, primaryColorArgb)
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
    peers: List<PeerReading> = emptyList(),
    onClick: (() -> Unit)? = null,
    onPeerClick: ((String) -> Unit)? = null,
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
    // curve stays visible behind it. The peers' chips sit in a row under the
    // value, as they do in the phone's hero.
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(24.dp))
            .background(background)
            .padding(horizontal = 12.dp, vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
    Row(
        // Tapping the hero acts on the reading it shows, as tapping a row
        // acts on that row's — the phone's hero behaves the same way.
        modifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier,
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
        Text(
            dvs.primaryStr,
            style = MaterialTheme.typography.displayLarge.copy(
                fontSize = 44.sp,
                fontWeight = FontWeight.SemiBold,
            ),
            color = valueColor,
            maxLines = 1,
        )
        // The extra lanes ride alongside rather than inline at hero size: nine
        // characters at 44sp run off the side of a round screen, and the point
        // of the secondary lane is comparison, not prominence.
        val extraLanes = listOfNotNull(dvs.secondaryStr, dvs.tertiaryStr)
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
            modifier = Modifier.size(28.dp).padding(start = 4.dp),
            color = valueColor,
        )
    }
    if (peers.isNotEmpty()) {
        HeroPeerStrip(
            peers = peers,
            contentColor = neutral,
            onPeerClick = onPeerClick,
            modifier = Modifier.padding(bottom = 4.dp),
        )
    }
    }
}

/** A peer's newest reading, for a chip in the hero. */
internal data class PeerReading(
    val sensorId: String,
    val primaryStr: String,
    val secondaryStr: String?,
    val velocity: Float,
    val colorArgb: Int,
)

/**
 * The newest reading of each peer that has one inside the staleness window,
 * formatted in that peer's own view mode. Nothing is shown for a peer that has
 * gone quiet — the phone's hero drops those chips too.
 */
internal fun peerReadings(
    peers: List<WearGlucoseStore.PeerSeries>,
    isMmol: Boolean,
    now: Long,
): List<PeerReading> = peers.mapNotNull { peer ->
    val newest = peer.points.lastOrNull() ?: return@mapNotNull null
    if (now - newest.timestamp >= tk.glucodata.Notify.glucosetimeout) return@mapNotNull null
    val dvs = tk.glucodata.ui.DisplayValueResolver.resolve(
        autoValue = newest.value,
        rawValue = newest.rawValue,
        viewMode = peer.viewMode,
        isMmol = isMmol,
    )
    val velocity = rowVelocities(peer.points, listOf(newest), peer.isRawMode, isMmol)[newest.timestamp] ?: 0f
    PeerReading(peer.sensorId, dvs.primaryStr, dvs.secondaryStr, velocity, peer.colorArgb)
}

/**
 * Compact per-peer chips: identity dot, the value (and second lane when the
 * peer's mode has one), a small arrow. The phone's hero has the same strip,
 * and tapping a chip there makes that sensor the primary; here too.
 */
@Composable
private fun HeroPeerStrip(
    peers: List<PeerReading>,
    contentColor: Color,
    onPeerClick: ((String) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        peers.forEach { peer ->
            val identity = Color(peer.colorArgb)
            val textColor = androidx.compose.ui.graphics.lerp(
                contentColor,
                identity,
                tk.glucodata.SensorVisuals.PEER_TEXT_BLEND,
            )
            Row(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(identity.copy(alpha = 0.14f))
                    .then(
                        onPeerClick?.let { act -> Modifier.clickable { act(peer.sensorId) } } ?: Modifier,
                    )
                    .padding(horizontal = 7.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(5.dp)
                        .background(identity.copy(alpha = 0.9f), androidx.compose.foundation.shape.CircleShape),
                )
                Text(
                    peer.primaryStr,
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                        fontFeatureSettings = "tnum",
                    ),
                    color = textColor,
                    maxLines = 1,
                    modifier = Modifier.padding(start = 4.dp),
                )
                peer.secondaryStr?.let { secondary ->
                    Text(
                        " · $secondary",
                        style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                        color = textColor.copy(alpha = 0.72f),
                        maxLines = 1,
                    )
                }
                TrendArrowCanvas(
                    velocity = peer.velocity,
                    pulseKey = null,
                    modifier = Modifier.size(12.dp).padding(start = 3.dp),
                    color = textColor,
                )
            }
        }
    }
}
