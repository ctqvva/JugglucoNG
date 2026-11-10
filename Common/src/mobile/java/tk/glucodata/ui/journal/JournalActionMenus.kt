package tk.glucodata.ui.journal

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import tk.glucodata.ui.FontScaleCap
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.material3.ToggleFloatingActionButtonDefaults.animateIcon
import androidx.compose.material3.ToggleFloatingActionButton
import androidx.compose.material3.FloatingActionButtonMenuItem
import androidx.compose.material3.FloatingActionButtonMenu
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import android.view.HapticFeedbackConstants
import android.view.WindowManager
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bloodtype
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DirectionsRun
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Vaccines
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ToggleFloatingActionButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.material3.FloatingActionButtonMenuScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.PopupPositionProvider
import kotlinx.coroutines.delay
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Constraints
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import androidx.compose.ui.unit.Density
import kotlin.math.abs
import androidx.compose.ui.unit.lerp
import kotlin.math.hypot
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.isOutOfBounds
import androidx.compose.ui.input.pointer.PointerInputChange
import tk.glucodata.R
import tk.glucodata.data.journal.JournalEntryType
import tk.glucodata.ui.ChartActionAnchor
import tk.glucodata.ui.components.CatchNavigationTaps
import kotlin.math.roundToInt

fun journalReachActionTypes(): List<JournalEntryType> = listOf(
    JournalEntryType.NOTE,
    JournalEntryType.ACTIVITY,
    JournalEntryType.FINGERSTICK,
    JournalEntryType.CARBS,
    JournalEntryType.INSULIN
)

@Composable
fun JournalEntryType.journalActionLabel(): String = when (this) {
    JournalEntryType.INSULIN -> stringResource(R.string.journal_type_insulin)
    JournalEntryType.CARBS -> stringResource(R.string.journal_type_food)
    JournalEntryType.FINGERSTICK -> stringResource(R.string.journal_type_glucose)
    JournalEntryType.ACTIVITY -> stringResource(R.string.journal_type_activity)
    JournalEntryType.NOTE -> stringResource(R.string.journal_type_note)
    JournalEntryType.TEMP_BASAL -> stringResource(R.string.journal_type_temp_basal)
}

fun JournalEntryType.journalActionIcon(): ImageVector = when (this) {
    JournalEntryType.INSULIN -> Icons.Default.Vaccines
    JournalEntryType.CARBS -> Icons.Default.Restaurant
    JournalEntryType.FINGERSTICK -> Icons.Default.Bloodtype
    JournalEntryType.ACTIVITY -> Icons.Default.DirectionsRun
    JournalEntryType.NOTE -> Icons.AutoMirrored.Filled.Label
    JournalEntryType.TEMP_BASAL -> Icons.Default.Vaccines
}

/**
 * The chart's "add at this time" menu, grown out of the tapped time's dot where the chart drew
 * it ([anchor]), and following it as the screen moves: one column centred on the dot and
 * beside it, to the left past the middle, where the newest readings are, kept above the time
 * chip where the screen allows. It is not held to the chart: it uses the whole window, so it
 * fits short charts and small screens. Each item springs out of the dot, from the middle
 * outward, and folds back into it on close; the corners on the dot's side sharpen the nearer
 * they are to it, so the column leans toward it. As in a menu, the finger that long-pressed
 * can slide onto an item, which presses under it, and lift there to pick it. A tap or a drag
 * anywhere else closes it. [selectedTimestamp] null closes it, animated, so keep calling this
 * rather than dropping it.
 */
@Composable
fun JournalFloatingActionMenu(
    selectedTimestamp: Long?,
    anchor: ChartActionAnchor,
    onDismissRequest: () -> Unit,
    onTypeSelected: (type: JournalEntryType, timestamp: Long) -> Unit
) = FontScaleCap {
    // Position-based journal menus follow the chart and navigation font cap.
    val view = LocalView.current
    // The FAB menu's order, top to bottom.
    val actionTypes = remember { journalReachActionTypes() }
    val progress = remember { List(actionTypes.size) { Animatable(0f) } }
    val enterSpec = MaterialTheme.motionScheme.fastSpatialSpec<Float>()
    val exitSpec = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
    // What is drawn outlives selectedTimestamp by the close animation.
    var shownTimestamp by remember { mutableStateOf<Long?>(null) }
    val open = selectedTimestamp != null
    LaunchedEffect(selectedTimestamp) {
        if (selectedTimestamp != null) {
            if (shownTimestamp != null) progress.forEach { it.snapTo(0f) }
            shownTimestamp = selectedTimestamp
            coroutineScope {
                progress.forEachIndexed { index, item ->
                    launch {
                        delay(abs(index - progress.lastIndex / 2) * ChartMenuStaggerMillis)
                        item.animateTo(1f, enterSpec)
                    }
                }
            }
        } else if (shownTimestamp != null) {
            coroutineScope {
                progress.forEachIndexed { index, item ->
                    launch {
                        delay((progress.lastIndex / 2 - abs(index - progress.lastIndex / 2)) * ChartMenuStaggerMillis / 2)
                        item.animateTo(0f, exitSpec)
                    }
                }
            }
            shownTimestamp = null
        }
    }
    val interactions = remember { List(actionTypes.size) { MutableInteractionSource() } }
    val itemCoordinates = remember { arrayOfNulls<LayoutCoordinates>(actionTypes.size) }
    val currentOnTypeSelected by rememberUpdatedState(onTypeSelected)
    // The long press's finger, still down: the item under it is pressed, and lifting there picks it.
    LaunchedEffect(selectedTimestamp) {
        val actionTimestamp = selectedTimestamp ?: return@LaunchedEffect
        var held = false
        var hovered = -1
        var press: PressInteraction.Press? = null
        try {
            snapshotFlow { anchor.heldFinger }.collect { finger ->
                // Where it lifted, not the last item seen: the two can differ by a frame.
                val (index, local) = finger?.let { itemCoordinates.itemUnder(it.onScreen) } ?: (-1 to Offset.Zero)
                val down = finger != null && !finger.lifted
                if (!down) {
                    press?.let { interactions[hovered].emit(PressInteraction.Release(it)) }
                    press = null
                    hovered = -1
                    if (held && finger != null && index >= 0) {
                        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        currentOnTypeSelected(actionTypes[index], actionTimestamp)
                    }
                } else if (index != hovered) {
                    press?.let { interactions[hovered].emit(PressInteraction.Release(it)) }
                    press = if (index >= 0) PressInteraction.Press(local).also {
                        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        interactions[index].emit(it)
                    } else null
                    hovered = index
                }
                held = down
            }
        } finally {
            press?.let { interactions[hovered].tryEmit(PressInteraction.Release(it)) }
        }
    }
    val timestamp = shownTimestamp ?: return@FontScaleCap
    Popup(
        popupPositionProvider = WholeWindow,
        onDismissRequest = onDismissRequest,
        // Closing, it no longer takes Back or touches meant for the screen.
        properties = if (open) {
            PopupProperties(focusable = true, dismissOnClickOutside = false)
        } else {
            PopupProperties(
                flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            )
        }
    ) {
        var popupOnScreen by remember { mutableStateOf<Offset?>(null) }
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .onGloballyPositioned { popupOnScreen = it.positionOnScreen() }
                .pointerInput(open) {
                    if (open) detectTapGestures { onDismissRequest() }
                }
                .pointerInput(open) {
                    if (open) detectDragGestures(onDragStart = { onDismissRequest() }) { _, _ -> }
                }
        ) {
            val origin = popupOnScreen ?: return@BoxWithConstraints
            val dot = (anchor.dotOnScreen ?: return@BoxWithConstraints) - origin
            val edge = with(LocalDensity.current) { ChartMenuEdge.toPx() }
            val ceilingPx = anchor.ceilingOnScreen - origin.y
            val floorPx = (anchor.floorOnScreen - origin.y).coerceAtMost(constraints.maxHeight - edge)
            ChartMenuColumn(
                dot = dot,
                ceilingPx = ceilingPx,
                floorPx = floorPx,
                opensLeft = anchor.opensLeft,
                progress = progress
            ) { shapes ->
                actionTypes.forEachIndexed { index, actionType ->
                    ChartMenuItem(
                        actionType = actionType,
                        shape = shapes[index],
                        interactionSource = interactions[index],
                        modifier = Modifier.onGloballyPositioned { itemCoordinates[index] = it },
                        onClick = {
                            if (open) {
                                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                onTypeSelected(actionType, timestamp)
                            }
                        }
                    )
                }
            }
        }
    }
}

/** The popup spans the window; the menu places itself inside. */
/** The item under [onScreen], and where in it; null for none. */
private fun Array<LayoutCoordinates?>.itemUnder(onScreen: Offset): Pair<Int, Offset>? {
    forEachIndexed { index, coordinates ->
        if (coordinates == null || !coordinates.isAttached) return@forEachIndexed
        val local = coordinates.screenToLocal(onScreen)
        if (local.x in 0f..coordinates.size.width.toFloat() && local.y in 0f..coordinates.size.height.toFloat()) {
            return index to local
        }
    }
    return null
}

private object WholeWindow : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize
    ): IntOffset = IntOffset.Zero
}

private const val ChartMenuStaggerMillis = 30L

// From the dot's centre to the column, clear of the dot's halo.
private val ChartMenuDotGap = 24.dp
private val ChartMenuItemGap = 8.dp
private val ChartMenuEdge = 8.dp
// A corner this close to the dot comes to a point; one this far off is the pill's.
private val ChartMenuPointCorner = 4.dp
private val ChartMenuPointReach = 96.dp

/**
 * Lays the items out in one column, all as wide as the widest, centred on [dot] and beside it
 * on the [opensLeft] side, kept between the plot's [ceilingPx] and the [floorPx]; only a
 * column taller than the plot leaves it, upward, and then stays in the window. Each item's corners on the dot's side sharpen the nearer they are to it, so the
 * column leans toward it. Each item grows from its corner nearest the dot as its [progress]
 * runs from 0 to 1; a spring's overshoot carries it a
 * little past its place and back. [content] gets each item's shape.
 */
@Composable
private fun ChartMenuColumn(
    dot: Offset,
    ceilingPx: Float,
    floorPx: Float,
    opensLeft: Boolean,
    progress: List<Animatable<Float, *>>,
    content: @Composable (shapes: List<Shape>) -> Unit
) {
    val density = LocalDensity.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val tops = remember(dot, ceilingPx, floorPx, progress.size, density) {
        with(density) { chartMenuTops(progress.size, dot.y, ceilingPx, floorPx) }
    }
    val shapes = remember(tops, dot, opensLeft, rtl, density) {
        with(density) {
            val itemHeight = FabMenuItemHeight.toPx()
            // The dot's side as the layout sees it: the chart itself is never mirrored.
            val towardEnd = opensLeft != rtl
            tops.map { top -> shapeTowards(dot.y, top.toFloat(), top + itemHeight, towardEnd) }
        }
    }
    Layout(
        content = { content(shapes) },
        modifier = Modifier.fillMaxSize()
    ) { measurables, constraints ->
        // One width for all, so the column is one block and the icons and labels line up.
        val itemWidth = measurables.maxOf { it.maxIntrinsicWidth(Constraints.Infinity) }
            .coerceAtMost((constraints.maxWidth - ChartMenuEdge.roundToPx() * 2).coerceAtLeast(0))
        val placeables = measurables.map { it.measure(Constraints.fixedWidth(itemWidth)) }
        val dotGap = ChartMenuDotGap.roundToPx()
        val edge = ChartMenuEdge.roundToPx()
        layout(constraints.maxWidth, constraints.maxHeight) {
            placeables.forEachIndexed { index, placeable ->
                val x = if (opensLeft) {
                    dot.x.roundToInt() - dotGap - placeable.width
                } else {
                    dot.x.roundToInt() + dotGap
                }.coerceIn(edge, (constraints.maxWidth - edge - placeable.width).coerceAtLeast(edge))
                val y = tops[index]
                // The corner nearest the dot, where the item grows from.
                val fromBottom = abs(dot.y - (y + placeable.height)) < abs(dot.y - y)
                val cornerX = if (opensLeft) x + placeable.width else x
                val cornerY = if (fromBottom) y + placeable.height else y
                val fromX = dot.x - cornerX
                val fromY = dot.y - cornerY
                val item = progress[index]
                placeable.placeWithLayer(x, y) {
                    val t = item.value
                    alpha = t.coerceIn(0f, 1f)
                    scaleX = 0.4f + 0.6f * t
                    scaleY = 0.4f + 0.6f * t
                    transformOrigin = TransformOrigin(if (opensLeft) 1f else 0f, if (fromBottom) 1f else 0f)
                    translationX = fromX * (1f - t)
                    translationY = fromY * (1f - t)
                }
            }
        }
    }
}

/**
 * Each item's top: the column centred on [dotY], slid back inside the plot, between
 * [ceilingPx] and [floorPx]. A column taller than the plot sits on the floor and rises past
 * the plot's top, as far as the window's.
 */
private fun Density.chartMenuTops(count: Int, dotY: Float, ceilingPx: Float, floorPx: Float): List<Int> {
    val itemHeight = FabMenuItemHeight.roundToPx()
    val itemGap = ChartMenuItemGap.roundToPx()
    val edge = ChartMenuEdge.roundToPx()
    val stack = count * itemHeight + (count - 1) * itemGap
    val minTop = ceilingPx.roundToInt() + edge
    val maxTop = floorPx.roundToInt() - itemGap - stack
    val top = if (maxTop >= minTop) {
        (dotY.roundToInt() - stack / 2).coerceIn(minTop, maxTop)
    } else {
        maxTop.coerceAtLeast(edge)
    }
    return List(count) { index -> top + index * (itemHeight + itemGap) }
}

/**
 * The item's corners on the finger's side, its end side when [towardEnd], sharpen with nearness
 * to [dotY]; the other side stays the pill's. A RoundedCornerShape, as the button only
 * animates its press morph between two of those.
 */
private fun Density.shapeTowards(dotY: Float, top: Float, bottom: Float, towardEnd: Boolean): Shape {
    val gap = ChartMenuDotGap.toPx()
    fun corner(y: Float): CornerSize {
        val reach = ((hypot(gap, y - dotY) - gap) / (ChartMenuPointReach.toPx() - gap)).coerceIn(0f, 1f)
        return CornerSize(lerp(ChartMenuPointCorner, FabMenuItemHeight / 2, reach))
    }
    val full = CornerSize(FabMenuItemHeight / 2)
    return RoundedCornerShape(
        topStart = if (towardEnd) full else corner(top),
        bottomStart = if (towardEnd) full else corner(bottom),
        topEnd = if (towardEnd) corner(top) else full,
        bottomEnd = if (towardEnd) corner(bottom) else full
    )
}

/** The FAB menu item's look as a button, so it morphs when pressed. */
@Composable
private fun ChartMenuItem(
    actionType: JournalEntryType,
    shape: Shape,
    interactionSource: MutableInteractionSource,
    modifier: Modifier,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = FabMenuItemHeight),
        interactionSource = interactionSource,
        shapes = ButtonDefaults.shapes(shape = shape),
        colors = ButtonDefaults.buttonColors(
            containerColor = journalTypeSelectedContainerColor(actionType),
            contentColor = MaterialTheme.colorScheme.onSurface
        ),
        contentPadding = PaddingValues(horizontal = 16.dp)
    ) {
        // Start-aligned: the button centres its content, and the column is one width.
        Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                actionType.journalActionIcon(),
                contentDescription = null,
                tint = journalTypeColor(actionType),
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                actionType.journalActionLabel(), style = MaterialTheme.typography.labelLarge,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * The journal's "+": material3's FAB menu. The button morphs into a close button and each
 * entry type is a menu item in its own colours. A tap outside or Back closes it, as the
 * popup it replaces did, the navigation bar or rail included. Call it last in a Box, so the
 * dismissible scrim covers the rest of the screen; [modifier] places the menu, which adds
 * the spec's 16dp from the edges itself. [horizontalAlignment] lines the items up over the
 * button: End for a corner FAB, CenterHorizontally for a centred one. As in a menu, pressing the
 * button and sliding off it, or holding it, opens the menu under the finger: the item it is
 * over lights up, and lifting there picks it; lifting anywhere else leaves the menu open.
 */
@Composable
fun BoxScope.JournalExpandableFab(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onTypeSelected: (JournalEntryType) -> Unit,
    modifier: Modifier = Modifier,
    horizontalAlignment: Alignment.Horizontal = Alignment.End
) = FontScaleCap {
    val view = LocalView.current
    val actionTypes = remember { journalReachActionTypes() }
    val itemCoordinates = remember { arrayOfNulls<LayoutCoordinates>(actionTypes.size) }
    var hoveredType by remember { mutableStateOf<JournalEntryType?>(null) }
    val currentExpanded by rememberUpdatedState(expanded)
    val currentOnExpandedChange by rememberUpdatedState(onExpandedChange)
    val currentOnTypeSelected by rememberUpdatedState(onTypeSelected)
    var buttonCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    BackHandler(enabled = expanded) { onExpandedChange(false) }
    CatchNavigationTaps(active = expanded) { onExpandedChange(false) }
    AnimatedVisibility(
        visible = expanded,
        modifier = Modifier.matchParentSize(),
        enter = fadeIn(),
        exit = fadeOut()
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.0f to Color.Transparent,
                        0.35f to Color.Transparent,
                        0.70f to Color.Black.copy(alpha = 0.55f),
                        1.0f to Color.Black.copy(alpha = 0.78f)
                    )
                )
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClickLabel = stringResource(R.string.close),
                    onClick = { onExpandedChange(false) }
                )
        )
    }
    FloatingActionButtonMenu(
        expanded = expanded,
        modifier = modifier,
        horizontalAlignment = horizontalAlignment,
        button = {
            ToggleFloatingActionButton(
                checked = expanded,
                onCheckedChange = {
                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    onExpandedChange(it)
                },
                containerColor = ToggleFloatingActionButtonDefaults.containerColor(
                    initialColor = MaterialTheme.colorScheme.primaryContainer,
                    finalColor = MaterialTheme.colorScheme.surfaceContainerHighest
                ),
                modifier = Modifier
                    .onGloballyPositioned { buttonCoordinates = it }
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            // Ahead of the button's own click, which the slide then cancels.
                            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                            // Slid off the button, or still held at the long-press timeout.
                            val slid = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                                var change: PointerInputChange?
                                do {
                                    change = awaitPointerEvent(PointerEventPass.Initial).changes
                                        .firstOrNull { it.id == down.id }
                                } while (change != null && change.pressed && !change.isOutOfBounds(size, extendedTouchPadding))
                                change != null && change.pressed
                            } ?: true
                            if (!slid) return@awaitEachGesture
                            if (!currentExpanded) {
                                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                currentOnExpandedChange(true)
                            }
                            try {
                                while (true) {
                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                    event.changes.forEach { it.consume() }
                                    val button = buttonCoordinates?.takeIf { it.isAttached } ?: break
                                    val index = itemCoordinates.itemUnder(button.localToScreen(change.position))?.first ?: -1
                                    val type = actionTypes.getOrNull(index)
                                    if (!change.pressed) {
                                        if (type != null) {
                                            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                            currentOnTypeSelected(type)
                                            currentOnExpandedChange(false)
                                        }
                                        break
                                    }
                                    if (type != hoveredType) {
                                        if (type != null) view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                        hoveredType = type
                                    }
                                }
                            } finally {
                                hoveredType = null
                            }
                        }
                    }
            ) {
                val icon by remember {
                    derivedStateOf { if (checkedProgress > 0.5f) Icons.Default.Close else Icons.Default.Add }
                }
                Icon(
                    imageVector = icon,
                    contentDescription = stringResource(if (expanded) R.string.close else R.string.additem),
                    modifier = Modifier.animateIcon(
                        checkedProgress = { checkedProgress },
                        color = ToggleFloatingActionButtonDefaults.iconColor(
                            initialColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            finalColor = MaterialTheme.colorScheme.onSurface
                        )
                    )
                )
            }
        }
    ) {
        JournalFabMenuItems(actionTypes, itemCoordinates, hoveredType) { type ->
            onTypeSelected(type)
            onExpandedChange(false)
        }
    }
}

/**
 * The FAB menu's items: one per entry type, in its own colours. Each reports where it is to
 * [itemCoordinates]; the [hoveredType], under a finger slid from the button, wears the pressed
 * state layer, which the item takes no interaction source to show itself.
 */
@Composable
private fun FloatingActionButtonMenuScope.JournalFabMenuItems(
    actionTypes: List<JournalEntryType>,
    itemCoordinates: Array<LayoutCoordinates?>,
    hoveredType: JournalEntryType?,
    onTypeSelected: (JournalEntryType) -> Unit
) {
    val view = LocalView.current
    val stateLayerColor = MaterialTheme.colorScheme.onSurface
    val stateLayerSpec = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
    actionTypes.forEachIndexed { index, actionType ->
        val stateLayer by animateFloatAsState(if (actionType == hoveredType) 1f else 0f, stateLayerSpec)
        val solidContainerColor = journalTypeColor(actionType)
            .copy(alpha = 0.28f)
            .compositeOver(MaterialTheme.colorScheme.surfaceContainerHigh)
        FloatingActionButtonMenuItem(
            onClick = {
                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                onTypeSelected(actionType)
            },
            text = {
                Text(
                    actionType.journalActionLabel(),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.reachIntoPadding(end = FabMenuItemPaddingTrim)
                )
            },
            // The type colour marks the icon; the label stays onSurface, readable on every tint.
            icon = {
                Icon(
                    actionType.journalActionIcon(),
                    contentDescription = null,
                    tint = journalTypeColor(actionType),
                    modifier = Modifier
                        .reachIntoPadding(start = FabMenuItemPaddingTrim)
                        .size(20.dp)
                )
            },
            // The padding sits outside the item's container: 4dp more between items.
            modifier = Modifier
                .padding(vertical = FabMenuItemExtraGap)
                .height(FabMenuItemHeight)
                .onGloballyPositioned { itemCoordinates[index] = it }
                .drawWithContent {
                    drawContent()
                    drawRoundRect(
                        color = stateLayerColor.copy(alpha = PressedStateLayerAlpha * stateLayer),
                        cornerRadius = CornerRadius(size.height / 2)
                    )
                },
            containerColor = solidContainerColor,
            contentColor = MaterialTheme.colorScheme.onSurface
        )
    }
}

// material3's FAB menu item is 56dp tall with 24dp at each end, a size meant for a handful of
// large actions; the journal lists five. The height clamps through the item's modifier, but
// the padding is fixed inside, so the icon and the label reach 8dp into it (16dp a side).
private val FabMenuItemHeight = 44.dp
private val FabMenuItemPaddingTrim = 8.dp
private val FabMenuItemExtraGap = 2.dp
private const val PressedStateLayerAlpha = 0.1f

private fun Modifier.reachIntoPadding(start: Dp = 0.dp, end: Dp = 0.dp) = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    val startPx = start.roundToPx()
    val endPx = end.roundToPx()
    layout((placeable.width - startPx - endPx).coerceAtLeast(0), placeable.height) {
        placeable.place(-startPx, 0)
    }
}
