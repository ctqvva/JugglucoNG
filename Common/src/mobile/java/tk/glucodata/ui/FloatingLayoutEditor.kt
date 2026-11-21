package tk.glucodata.ui

import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import sh.calvin.reorderable.ReorderableColumn
import tk.glucodata.R
import tk.glucodata.logic.TrendEngine
import tk.glucodata.ui.components.CardPosition
import tk.glucodata.ui.components.SettingsItem
import tk.glucodata.ui.components.cardShape
import tk.glucodata.ui.components.settingsSearchAnchor
import tk.glucodata.ui.overlay.FloatingLayoutItem
import tk.glucodata.ui.overlay.FloatingLayoutOrder
import tk.glucodata.ui.overlay.FloatingOverlayBody
import tk.glucodata.ui.overlay.FloatingOverlayContent
import tk.glucodata.ui.overlay.FloatingOverlayMode
import tk.glucodata.ui.overlay.FloatingOverlayStyle
import tk.glucodata.ui.symbols.Symbols

/**
 * The floating glucose's arrangement (#981): a preview of the overlay as it is set up now,
 * over the rows of what it shows, which drag into any order. In Dynamic Island mode the camera
 * is a row too, so dragging items past it moves them to its other side.
 */
@Composable
internal fun FloatingLayoutEditor(
    order: List<FloatingLayoutItem>,
    island: Boolean,
    gap: Float,
    showSecondary: Boolean,
    showArrow: Boolean,
    showDelta: Boolean,
    style: FloatingOverlayStyle,
    mmol: Boolean,
    onOrderChange: (List<FloatingLayoutItem>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val view = LocalView.current
    val rows = FloatingLayoutOrder.visible(order, island)
    // The drop reads the order it lands on, not the one the gesture started from.
    val currentOrder by rememberUpdatedState(order)
    val currentIsland by rememberUpdatedState(island)
    val currentOnOrderChange by rememberUpdatedState(onOrderChange)

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        FloatingLayoutPreview(
            order = order,
            island = island,
            gap = if (gap > 0f) gap.dp else PreviewAutoGap,
            showSecondary = showSecondary,
            showArrow = showArrow,
            showDelta = showDelta,
            style = style,
            mmol = mmol,
        )
        ReorderableColumn(
            list = rows,
            onSettle = { from, to ->
                currentOnOrderChange(FloatingLayoutOrder.movedVisible(currentOrder, currentIsland, from, to))
            },
            onMove = { view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) },
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) { index, item, dragging ->
            key(item) {
                ReorderableItem {
                    FloatingLayoutRow(
                        item = item,
                        shown = when (item) {
                            FloatingLayoutItem.SECONDARY -> showSecondary
                            FloatingLayoutItem.ARROW -> showArrow
                            FloatingLayoutItem.DELTA -> showDelta
                            else -> true
                        },
                        index = index,
                        count = rows.size,
                        dragging = dragging,
                        handle = Modifier.draggableHandle(
                            onDragStarted = { view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS) }
                        ),
                        onMove = { to -> onOrderChange(FloatingLayoutOrder.movedVisible(order, island, index, to)) },
                    )
                }
            }
        }
        Text(
            text = stringResource(R.string.floating_layout_order_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, top = 8.dp, end = 16.dp)
        )
    }
}

/** Before the first reading the real cutout width is unknown here; a typical punch hole's. */
private val PreviewAutoGap = 32.dp

/**
 * The overlay drawn with sample values through the same body that floats, on a plain stage
 * in the app's theme. Island mode shows a camera in the gap, the way a punch hole sits.
 */
@Composable
private fun FloatingLayoutPreview(
    order: List<FloatingLayoutItem>,
    island: Boolean,
    gap: Dp,
    showSecondary: Boolean,
    showArrow: Boolean,
    showDelta: Boolean,
    style: FloatingOverlayStyle,
    mmol: Boolean,
) {
    val content = FloatingOverlayContent(
        primary = if (mmol) "5.8" else "105",
        secondary = if (showSecondary) (if (mmol) "6.1" else "110") else null,
        delta = if (showDelta) (if (mmol) "+0.2" else "+4") else null,
        trend = if (showArrow) TrendEngine.TrendResult(TrendEngine.TrendState.FortyFiveUp, 0.7f, 0f, 1f) else null,
    )
    val previewLabel = stringResource(R.string.preview)
    // The island at the top stacks the delta only within the status bar's height, as it floats.
    val barHeight = with(LocalDensity.current) { WindowInsets.statusBars.getTop(this).toDp() }
    val shape = cardShape(CardPosition.TOP)
    Surface(
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier
            .fillMaxWidth()
            .settingsSearchAnchor(SettingsSearchAnchors.FLOATING_LAYOUT, shape)
    ) {
        Box(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                .padding(vertical = 24.dp)
                .clearAndSetSemantics { contentDescription = previewLabel },
            contentAlignment = Alignment.Center
        ) {
            Box(contentAlignment = Alignment.Center) {
                FloatingOverlayBody(
                    order = order,
                    mode = if (island) FloatingOverlayMode.ISLAND_ROW else FloatingOverlayMode.PILL,
                    gap = gap,
                    style = style,
                    content = content,
                    modifier = Modifier.clip(style.shape),
                    stackLimit = barHeight.takeIf { island && it > 0.dp } ?: Dp.Unspecified,
                )
                if (island) {
                    // The body is symmetric about the gap, so its centre is the camera's.
                    Box(
                        Modifier
                            .size(minOf(gap - 8.dp, 12.dp).coerceAtLeast(4.dp))
                            .clip(CircleShape)
                            .background(Color.Black)
                            // A rim, or the lens vanishes into a filled pill around it.
                            .border(1.dp, Color.White.copy(alpha = 0.32f), CircleShape)
                    )
                }
            }
        }
    }
}

/**
 * One arranged item: its name, "Hidden" while its switch under Metrics is off (it keeps its
 * place), and the drag handle at the end. Talkback moves it with Move up and Move down.
 */
@Composable
private fun FloatingLayoutRow(
    item: FloatingLayoutItem,
    shown: Boolean,
    index: Int,
    count: Int,
    dragging: Boolean,
    handle: Modifier,
    onMove: (to: Int) -> Unit,
) {
    val position = CardPosition.MIDDLE.takeIf { index < count - 1 } ?: CardPosition.BOTTOM
    val shape = cardShape(position)
    val lift by animateDpAsState(if (dragging) 6.dp else 0.dp, label = "floatingLayoutLift")
    val moveUp = stringResource(R.string.food_move_up)
    val moveDown = stringResource(R.string.food_move_down)
    val moves = buildList {
        if (index > 0) add(CustomAccessibilityAction(moveUp) { onMove(index - 1); true })
        if (index < count - 1) add(CustomAccessibilityAction(moveDown) { onMove(index + 1); true })
    }
    val (title, icon) = item.label()
    val subtitle = when {
        item == FloatingLayoutItem.CUTOUT -> stringResource(R.string.floating_layout_camera_desc)
        !shown -> stringResource(R.string.floating_layout_hidden)
        else -> null
    }
    SettingsItem(
        title = title,
        subtitle = subtitle,
        icon = icon,
        position = position,
        animatePosition = true,
        modifier = Modifier
            .shadow(lift, shape)
            .semantics { customActions = moves },
        trailingContent = {
            Box(
                modifier = handle
                    .size(40.dp)
                    .pointerInput(Unit) { detectTapGestures { } },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Symbols.DragHandleFilled,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    )
}

@Composable
private fun FloatingLayoutItem.label(): Pair<String, ImageVector> = when (this) {
    FloatingLayoutItem.VALUE -> stringResource(R.string.floating_layout_reading) to Symbols.WaterDropFilled
    FloatingLayoutItem.SECONDARY -> stringResource(R.string.floating_layout_secondary) to Symbols.WaterDrop
    FloatingLayoutItem.DELTA -> stringResource(R.string.floating_layout_delta) to Symbols.ChangeHistoryFilled
    FloatingLayoutItem.ARROW -> stringResource(R.string.floating_layout_arrow) to Symbols.TrendingUpFilled
    FloatingLayoutItem.CUTOUT -> stringResource(R.string.floating_layout_camera) to Symbols.PhotoCameraFilled
}
