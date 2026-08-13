package tk.glucodata.ui.stats

import android.view.HapticFeedbackConstants
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.WidthFull
import androidx.compose.material.icons.filled.WidthNormal
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventTimeoutCancellationException
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import tk.glucodata.R
import kotlin.math.roundToInt

private val EditorRowHeight = 56.dp
private val EditorRowSpacing = 4.dp

/**
 * Arrange mode.
 *
 * Reordering full statistics cards in place would mean dragging a 350 dp chart around
 * a scrolling list. Instead every card collapses to a uniform row for the duration of
 * the edit, which makes both the drag maths and the user's aim exact, then expands
 * back when they are done.
 */
@Composable
internal fun StatsLayoutEditor(
    layout: StatsLayoutState,
    summary: StatsSummary,
    targets: StatsTargets,
    unit: GlucoseUnit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier
) {
    val view = LocalView.current
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Title, hint and Done are in the sheet's fixed header, not in this list.
        EditorSectionLabel(stringResource(R.string.stats_arrange_sections))
        ReorderableRows(
            items = layout.cardOrder,
            onReordered = StatsLayoutStore::setCardOrder
        ) { card, dragging, handle ->
            EditorRow(
                title = stringResource(card.titleResId),
                hidden = card in layout.hiddenCards,
                dragging = dragging,
                handle = handle,
                onToggleHidden = {
                    StatsLayoutStore.setCardHidden(card, card !in layout.hiddenCards)
                }
            )
        }

        Spacer(modifier = Modifier.height(8.dp))
        EditorSectionLabel(
            stringResource(
                R.string.stats_arrange_metrics,
                layout.dashboardMetrics.size,
                StatsLayoutStore.MAX_DASHBOARD_METRICS
            )
        )
        ReorderableRows(
            items = layout.metricOrder,
            onReordered = StatsLayoutStore::setMetricOrder
        ) { metric, dragging, handle ->
            val spec = metricSpec(metric, summary, targets, unit)
            EditorRow(
                title = spec.title,
                // The live number and the metric's own tint, so this list reads as the
                // grid it governs rather than as a settings screen about it.
                value = spec.value,
                tone = spec.tone,
                hidden = metric in layout.hiddenMetrics,
                dragging = dragging,
                handle = handle,
                pinned = metric in layout.dashboardMetrics,
                pinnable = true,
                pinEnabled = metric in layout.dashboardMetrics ||
                    layout.dashboardMetrics.size < StatsLayoutStore.MAX_DASHBOARD_METRICS,
                wide = metric in layout.wideMetrics,
                onToggleWide = {
                    StatsLayoutStore.setMetricWide(metric, metric !in layout.wideMetrics)
                },
                onTogglePinned = {
                    val accepted = StatsLayoutStore.setPinnedToDashboard(
                        metric,
                        metric !in layout.dashboardMetrics
                    )
                    if (!accepted) {
                        view.performHapticFeedback(HapticFeedbackConstants.REJECT)
                    }
                },
                onToggleHidden = {
                    StatsLayoutStore.setMetricHidden(metric, metric !in layout.hiddenMetrics)
                }
            )
        }

        TextButton(
            onClick = { StatsLayoutStore.resetLayout() },
            modifier = Modifier.padding(top = 4.dp)
        ) {
            Text(text = stringResource(R.string.stats_arrange_reset))
        }
    }
}

@Composable
private fun EditorSectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp, bottom = 2.dp)
    )
}

/**
 * Uniform-height drag reordering.
 *
 * The list order is left alone for the whole gesture: the held row is translated by the
 * drag, and the rows it passes slide one slot the other way. The first version mutated
 * the list mid-drag while also translating the held row, so the row was displaced twice
 * and landed on top of its neighbour, and committing to the store reset the state the
 * gesture was still using — which is why it stuck.
 */
@Composable
private fun <T> ReorderableRows(
    items: List<T>,
    onReordered: (List<T>) -> Unit,
    row: @Composable (item: T, dragging: Boolean, handle: Modifier) -> Unit
) {
    val density = LocalDensity.current
    val view = LocalView.current
    val pitchPx = with(density) { (EditorRowHeight + EditorRowSpacing).toPx() }
    var dragFrom by remember(items) { mutableStateOf<Int?>(null) }
    var dragOffset by remember(items) { mutableFloatStateOf(0f) }

    val from = dragFrom
    val dragTo = if (from == null) {
        null
    } else {
        (from + (dragOffset / pitchPx).roundToInt()).coerceIn(0, items.lastIndex)
    }
    // Nudge the row the drag last crossed, so the gap tracks the finger.
    var lastCrossed by remember(items) { mutableStateOf<Int?>(null) }
    LaunchedEffect(dragTo) {
        if (dragTo != null && dragTo != lastCrossed) {
            lastCrossed = dragTo
            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(EditorRowSpacing)) {
        items.forEachIndexed { index, item ->
            val dragging = index == from
            val shift = when {
                from == null || dragTo == null || dragging -> 0f
                from < dragTo && index in (from + 1)..dragTo -> -pitchPx
                from > dragTo && index in dragTo until from -> pitchPx
                else -> 0f
            }
            val animatedShift by animateFloatAsState(
                targetValue = shift,
                label = "reorderShift"
            )
            // `draggable` rather than `detectDragGestures`: it claims the vertical
            // drag before the surrounding scroll can take it, so the handle actually
            // drags instead of scrolling the page.
            val dragState = rememberDraggableState { delta -> dragOffset += delta }
            val handle = Modifier.draggable(
                orientation = Orientation.Vertical,
                state = dragState,
                onDragStarted = {
                    dragFrom = index
                    dragOffset = 0f
                    lastCrossed = index
                    view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                },
                onDragStopped = {
                    // Recomputed here from live state. Reading the `dragTo` computed
                    // during composition captured the value from when this lambda was
                    // created — always null — so nothing was ever reordered.
                    val start = dragFrom
                    if (start != null) {
                        val target = (start + (dragOffset / pitchPx).roundToInt())
                            .coerceIn(0, items.lastIndex)
                        if (target != start) onReordered(items.moved(start, target))
                    }
                    dragFrom = null
                    dragOffset = 0f
                    lastCrossed = null
                }
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(EditorRowHeight)
                    .zIndex(if (dragging) 1f else 0f)
                    .graphicsLayer {
                        translationY = if (dragging) dragOffset else animatedShift
                    }
            ) {
                row(item, dragging, handle)
            }
        }
    }
}

@Composable
private fun EditorRow(
    title: String,
    hidden: Boolean,
    dragging: Boolean,
    handle: Modifier,
    onToggleHidden: () -> Unit,
    value: String? = null,
    tone: Color? = null,
    pinned: Boolean = false,
    pinnable: Boolean = false,
    pinEnabled: Boolean = true,
    onTogglePinned: () -> Unit = {},
    wide: Boolean? = null,
    onToggleWide: () -> Unit = {}
) {
    val container by animateColorAsState(
        targetValue = when {
            dragging -> MaterialTheme.colorScheme.secondaryContainer
            // Shown rows carry the metric's own tint, in the same shape and strength as
            // the tile they stand for; hidden ones fall back to bare surface.
            tone != null && !hidden -> tone.copy(alpha = 0.14f)
                .compositeOver(MaterialTheme.colorScheme.surfaceContainerHigh)
            hidden -> MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.4f)
            else -> MaterialTheme.colorScheme.surfaceContainerHigh
        },
        label = "editorRowContainer"
    )
    val elevation: Dp by animateDpAsState(if (dragging) 6.dp else 0.dp, label = "editorRowLift")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(EditorRowHeight)
            .graphicsLayer { shadowElevation = elevation.toPx() }
            .clip(statsCardShape(20.dp, 12.dp))
            .background(container)
            .padding(start = 6.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = handle.padding(horizontal = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.DragHandle,
                contentDescription = stringResource(R.string.stats_arrange_drag),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp)
            )
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = if (hidden) {
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (value != null && tone != null) {
            Text(
                text = value,
                style = MaterialTheme.typography.titleSmall.copy(
                    fontFeatureSettings = "tnum",
                    fontWeight = FontWeight.SemiBold
                ),
                color = tone.copy(alpha = if (hidden) 0.45f else 1f),
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.padding(end = 2.dp)
            )
        }
        if (wide != null) {
            IconButton(onClick = onToggleWide, modifier = Modifier.size(38.dp)) {
                Icon(
                    imageVector = if (wide) Icons.Default.WidthFull else Icons.Default.WidthNormal,
                    contentDescription = stringResource(R.string.stats_arrange_width),
                    tint = if (wide) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    },
                    modifier = Modifier.size(20.dp)
                )
            }
        }
        if (pinnable) {
            IconButton(
                onClick = onTogglePinned,
                enabled = pinEnabled,
                modifier = Modifier.size(38.dp)
            ) {
                Icon(
                    imageVector = if (pinned) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                    contentDescription = stringResource(R.string.stats_arrange_pin),
                    tint = when {
                        pinned -> MaterialTheme.colorScheme.primary
                        pinEnabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f)
                    },
                    modifier = Modifier.size(20.dp)
                )
            }
        }
        IconButton(onClick = onToggleHidden, modifier = Modifier.size(38.dp)) {
            Icon(
                imageVector = if (hidden) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                contentDescription = stringResource(R.string.stats_arrange_visibility),
                tint = if (hidden) {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                } else {
                    MaterialTheme.colorScheme.primary
                },
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

/** Bottom entry point into arrange mode, for people who never try a long press. */
@Composable
internal fun StatsEditLayoutButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp)),
        horizontalArrangement = Arrangement.Center
    ) {
        TextButton(onClick = onClick) {
            Icon(
                imageVector = Icons.Default.DragHandle,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.size(8.dp))
            Text(text = stringResource(R.string.stats_arrange_action))
        }
    }
}

/**
 * Inline card dragging.
 *
 * Long press a card and it lifts where it stands; the cards it passes slide out of the
 * way and the new order is committed as you cross each one, so releasing just drops it.
 * `detectDragGesturesAfterLongPress` gives up the gesture the moment a child consumes
 * it or the finger moves before the press completes, so chart scrubbing, TIR rows and
 * calendar squares all keep working underneath.
 */
internal class StatsCardDragState(
    private val listState: LazyListState,
    private val orderOf: () -> List<StatsCard>,
    private val onReordered: (List<StatsCard>) -> Unit
) {
    var dragging by mutableStateOf<StatsCard?>(null)
        private set
    var offset by mutableFloatStateOf(0f)
        private set

    fun start(card: StatsCard) {
        dragging = card
        offset = 0f
    }

    fun stop() {
        dragging = null
        offset = 0f
    }

    /** Returns true when the drag crossed into a new slot, so the caller can tick. */
    fun onDrag(deltaY: Float): Boolean {
        val card = dragging ?: return false
        offset += deltaY
        val info = listState.layoutInfo
        val held = info.visibleItemsInfo.firstOrNull { it.key == card.name } ?: return false
        val middle = held.offset + offset + held.size / 2f
        val target = info.visibleItemsInfo.firstOrNull { item ->
            item.key != card.name &&
                (item.key as? String)?.let { key -> StatsCard.entries.any { it.name == key } } == true &&
                middle.toInt() in item.offset..(item.offset + item.size)
        } ?: return false
        val targetCard = StatsCard.entries.firstOrNull { it.name == target.key } ?: return false

        val order = orderOf()
        val from = order.indexOf(card)
        val to = order.indexOf(targetCard)
        if (from < 0 || to < 0 || from == to) return false
        onReordered(order.moved(from, to))
        // Keep the held card under the finger now that its slot has changed.
        offset += (held.offset - target.offset).toFloat()
        return true
    }
}

@Composable
internal fun rememberStatsCardDragState(
    listState: LazyListState,
    order: List<StatsCard>,
    onReordered: (List<StatsCard>) -> Unit
): StatsCardDragState {
    val latestOrder = rememberUpdatedState(order)
    return remember(listState) {
        StatsCardDragState(
            listState = listState,
            orderOf = { latestOrder.value },
            onReordered = onReordered
        )
    }
}

internal fun Modifier.draggableStatsCard(
    card: StatsCard,
    state: StatsCardDragState,
    onLift: () -> Unit,
    onTick: () -> Unit
): Modifier = pointerInput(card, state) {
    detectDragGesturesAfterLongPress(
        onDragStart = {
            state.start(card)
            onLift()
        },
        onDragEnd = { state.stop() },
        onDragCancel = { state.stop() }
    ) { change, amount ->
        change.consume()
        if (state.onDrag(amount.y)) onTick()
    }
}

/**
 * Inline drag reordering for the individual metric tiles.
 *
 * Two-dimensional, unlike the card list, and split across two grids (the three visible
 * rows and the disclosed remainder). Tiles therefore report their bounds in *root*
 * coordinates, so hit-testing works across both grids and across the fold.
 */
internal class MetricDragState(
    private val orderOf: () -> List<StatsMetric>,
    private val onReordered: (List<StatsMetric>) -> Unit
) {
    var dragging by mutableStateOf<StatsMetric?>(null)
        private set
    var offset by mutableStateOf(Offset.Zero)
        private set

    private val bounds = mutableMapOf<StatsMetric, Rect>()

    fun reportBounds(metric: StatsMetric, rect: Rect) {
        bounds[metric] = rect
    }

    fun forget(metric: StatsMetric) {
        bounds.remove(metric)
    }

    fun start(metric: StatsMetric) {
        dragging = metric
        offset = Offset.Zero
    }

    fun stop() {
        dragging = null
        offset = Offset.Zero
    }

    /** Returns true when the tile crossed into another tile's slot. */
    fun onDrag(delta: Offset): Boolean {
        val metric = dragging ?: return false
        offset += delta
        val held = bounds[metric] ?: return false
        val centre = held.center + offset
        // Whichever slot the tile is now nearest to, and only once it is nearer to that
        // one than to its own. Hit-testing by containment instead meant every gap between
        // and around the tiles was dead space where a drag did nothing, which is what made
        // dragging feel like it could only ever move a tile to the neighbouring slot.
        val nearest = bounds.entries
            .filter { (other, _) -> other != metric }
            .minByOrNull { (_, rect) -> (rect.center - centre).getDistance() }
            ?: return false
        if ((nearest.value.center - centre).getDistance() >= (held.center - centre).getDistance()) {
            return false
        }
        val target = nearest.key

        val order = orderOf()
        val from = order.indexOf(metric)
        val to = order.indexOf(target)
        if (from < 0 || to < 0 || from == to) return false
        onReordered(order.moved(from, to))
        // The tile has taken the target's slot; keep it under the finger.
        offset += held.center - (bounds[target]?.center ?: held.center)
        return true
    }
}

@Composable
internal fun rememberMetricDragState(
    order: List<StatsMetric>,
    onReordered: (List<StatsMetric>) -> Unit
): MetricDragState {
    val latestOrder = rememberUpdatedState(order)
    return remember {
        MetricDragState(orderOf = { latestOrder.value }, onReordered = onReordered)
    }
}

/**
 * Reports a tile's slot in root coordinates.
 *
 * Must sit *outside* the drag's `graphicsLayer` in the modifier chain. Inside it, the
 * reported position followed the finger — `positionInRoot` includes the layer's
 * translation — so the hit-test point moved at twice the drag distance and the tile
 * would swap once and then chase itself off into nowhere.
 */
internal fun Modifier.reportMetricBounds(
    metric: StatsMetric,
    state: MetricDragState
): Modifier = onGloballyPositioned { coordinates ->
    state.reportBounds(
        metric,
        Rect(coordinates.positionInRoot(), coordinates.size.toSize())
    )
}

internal fun Modifier.draggableMetric(
    metric: StatsMetric,
    state: MetricDragState,
    onLift: () -> Unit,
    onTick: () -> Unit
): Modifier = this
    .pointerInput(metric, state) {
        detectDragGesturesAfterLongPress(
            onDragStart = {
                state.start(metric)
                onLift()
            },
            onDragEnd = { state.stop() },
            onDragCancel = { state.stop() }
        ) { change, amount ->
            change.consume()
            if (state.onDrag(amount)) onTick()
        }
    }
