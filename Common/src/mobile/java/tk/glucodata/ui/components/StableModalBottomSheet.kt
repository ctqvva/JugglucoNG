package tk.glucodata.ui.components

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.SheetState
import androidx.compose.material3.contentColorFor
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * App-wide modal sheet entry point.
 *
 * Material 3 recomputes the sheet's anchors from its measured size on every
 * layout pass, and `AnchoredDraggableState.anchoredDrag` **cancels and restarts
 * the in-flight drag whenever those anchors change**. A sheet that resizes
 * under the finger therefore does not merely jitter: the gesture is torn down
 * mid-swipe, so the sheet stops following the finger and settles back to
 * expanded instead of dismissing.
 *
 * A sheet does resize under the finger. The framework consumes its own drag
 * offset as a top inset, so the status-bar padding inside the sheet melts away
 * as it travels down, and any sheet whose content ends within a status bar of
 * the viewport gets shorter for it.
 *
 * So the height is held still for the duration of a finger drag, and only for
 * that: the sheet is measured freely at rest and during the framework's own
 * show / hide / settle animations, which is where a row being added or removed
 * has to be allowed to change its height.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StableModalBottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(),
    sheetMaxWidth: Dp = BottomSheetDefaults.SheetMaxWidth,
    sheetGesturesEnabled: Boolean = true,
    shape: Shape = BottomSheetDefaults.ExpandedShape,
    containerColor: Color = BottomSheetDefaults.ContainerColor,
    contentColor: Color = contentColorFor(containerColor),
    tonalElevation: Dp = 0.dp,
    scrimColor: Color = BottomSheetDefaults.ScrimColor,
    dragHandle: @Composable (() -> Unit)? = { BottomSheetDefaults.DragHandle() },
    contentWindowInsets: @Composable () -> WindowInsets = { BottomSheetDefaults.windowInsets },
    properties: ModalBottomSheetProperties = ModalBottomSheetProperties(),
    contentKey: Any? = Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val heightPolicy = remember(sheetState, contentKey) { StableSheetHeightPolicy() }
    // dragTarget-backed: true for show / hide / settle, false while a finger is
    // driving the sheet. That is exactly the split the policy needs, and it is
    // the only state here that has to be read during composition.
    val isAnimating = sheetState.isAnimationRunning

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = modifier.holdHeightWhileDragged(heightPolicy, isAnimating) {
            runCatching { sheetState.requireOffset() }.getOrDefault(Float.NaN)
        },
        sheetState = sheetState,
        sheetMaxWidth = sheetMaxWidth,
        sheetGesturesEnabled = sheetGesturesEnabled,
        shape = shape,
        containerColor = containerColor,
        contentColor = contentColor,
        tonalElevation = tonalElevation,
        scrimColor = scrimColor,
        dragHandle = dragHandle,
        contentWindowInsets = contentWindowInsets,
        properties = properties,
        content = content,
    )
}

/**
 * Decides, per layout pass, whether the sheet may take its content's height or
 * has to repeat the height it last measured freely.
 *
 * The signal for "a finger is on it" is the sheet's own drag offset moving
 * while no animation is running. Nothing here looks at intrinsic height: asking
 * the sheet's subtree for its intrinsic height runs Material's
 * `draggableAnchors` measure block against an infinite height constraint, which
 * writes garbage anchors into the drag state and kills the very gesture this
 * exists to protect.
 */
internal class StableSheetHeightPolicy {
    /** Height of the last free measurement, or [FREE] before there is one. */
    private var heldHeight: Int = FREE
    private var lastOffset: Float = Float.NaN

    /** True while the sheet is being held to a previously measured height. */
    val isHoldingHeight: Boolean
        get() = holding

    private var holding: Boolean = false

    /**
     * Returns the height this pass must be measured at, or [FREE] to measure
     * the content normally.
     *
     * [sheetOffset] is [Float.NaN] until the anchors exist, which is the first
     * pass of every sheet; a pass without a comparable offset is never treated
     * as a drag.
     */
    fun heightForPass(sheetOffset: Float, isAnimating: Boolean, hasBoundedHeight: Boolean): Int {
        val previousOffset = lastOffset
        lastOffset = sheetOffset
        val dragged = hasBoundedHeight &&
            !isAnimating &&
            !sheetOffset.isNaN() &&
            !previousOffset.isNaN() &&
            sheetOffset != previousOffset
        holding = dragged && heldHeight != FREE
        return if (holding) heldHeight else FREE
    }

    /** Records the outcome of a free measurement as the height to hold next. */
    fun recordFreeHeight(height: Int) {
        heldHeight = height
    }

    companion object {
        /** Sentinel: measure the content, do not impose a height. */
        const val FREE = -1
    }
}

private fun Modifier.holdHeightWhileDragged(
    policy: StableSheetHeightPolicy,
    isAnimating: Boolean,
    sheetOffset: () -> Float,
): Modifier =
    layout { measurable, constraints ->
        val held = policy.heightForPass(
            sheetOffset = sheetOffset(),
            isAnimating = isAnimating,
            hasBoundedHeight = constraints.hasBoundedHeight,
        )
        val measurementConstraints = if (held == StableSheetHeightPolicy.FREE) {
            constraints
        } else {
            val height = held.coerceIn(constraints.minHeight, constraints.maxHeight)
            constraints.copy(minHeight = height, maxHeight = height)
        }
        val placeable = measurable.measure(measurementConstraints)
        if (held == StableSheetHeightPolicy.FREE) {
            policy.recordFreeHeight(placeable.height)
        }

        layout(placeable.width, placeable.height) {
            placeable.placeRelative(0, 0)
        }
    }
