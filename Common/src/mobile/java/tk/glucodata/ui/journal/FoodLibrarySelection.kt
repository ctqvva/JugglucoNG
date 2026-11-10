package tk.glucodata.ui.journal

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp

/** The list owns the held pointer, so selection can continue across recycled rows. */
@Composable
internal fun Modifier.foodDragSelection(
    list: LazyListState,
    management: FoodLibraryManagement,
    orderedIds: List<Long>,
    enabled: Boolean
): Modifier {
    val latestIds by rememberUpdatedState(orderedIds)
    val haptics = LocalHapticFeedback.current
    val edge = with(LocalDensity.current) { 56.dp.toPx() }
    var pointer by remember { mutableStateOf<Offset?>(null) }

    fun selectAt(position: Offset) {
        val id = list.foodAt(position, nearest = true) ?: return
        if (management.dragTo(id, latestIds)) {
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
    }

    LaunchedEffect(management.dragging) {
        var previousFrame = withFrameNanos { it }
        while (management.dragging) {
            val frame = withFrameNanos { it }
            val seconds = ((frame - previousFrame) / 1_000_000_000f).coerceAtMost(0.05f)
            previousFrame = frame
            val position = pointer ?: continue
            val height = list.layoutInfo.viewportSize.height
            val speed = when {
                position.y < edge -> -((edge - position.y) / edge).coerceIn(0f, 1f)
                position.y > height - edge -> ((position.y - height + edge) / edge).coerceIn(0f, 1f)
                else -> 0f
            }
            if (speed != 0f) {
                // At most twelve edge widths per second, ramped by proximity to the edge.
                list.scrollBy(speed * edge * 12f * seconds)
                selectAt(position)
            }
        }
    }

    return pointerInput(list, management, enabled) {
        if (!enabled) return@pointerInput
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val anchor = list.foodAt(down.position) ?: return@awaitEachGesture
            // A normal scroll, horizontal swipe or handle drag wins before the hold timeout.
            val held = awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
            held.consume()
            management.startDrag(anchor, latestIds)
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            pointer = held.position
            try {
                while (true) {
                    // Capture before click/swipe/scroll handlers, including the final up.
                    // A stationary long press must never also become the row's edit tap.
                    val change = awaitPointerEvent(PointerEventPass.Initial).changes
                        .firstOrNull { it.id == held.id } ?: break
                    change.consume()
                    if (!change.pressed) break
                    pointer = change.position
                    selectAt(change.position)
                }
            } finally {
                pointer = null
                management.endDrag()
            }
        }
    }
}

private fun LazyListState.foodAt(position: Offset, nearest: Boolean = false): Long? {
    val info = layoutInfo
    if (!nearest && (position.x < 0 || position.x > info.viewportSize.width)) return null
    val y = position.y - info.beforeContentPadding
    val foods = info.visibleItemsInfo.filter { (it.key as? String)?.startsWith("managed_food:") == true }
    val row = foods.firstOrNull { y >= it.offset && y < it.offset + it.size }
        ?: if (nearest) foods.minByOrNull { kotlin.math.abs(y - (it.offset + it.size / 2f)) } else null
    return (row?.key as? String)?.removePrefix("managed_food:")?.toLongOrNull()
}
