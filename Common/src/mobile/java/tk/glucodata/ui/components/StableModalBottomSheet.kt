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
import kotlin.math.abs

/**
 * App-wide modal sheet entry point.
 *
 * Material 3 derives the expanded anchor from the sheet's measured height on every layout pass.
 * A viewport-height sheet can otherwise become shorter while it is dragged because the framework
 * dynamically consumes its top inset. That moves the expanded anchor during the gesture and makes
 * the sheet appear to resist or jitter.
 *
 * Short sheets retain their intrinsic height. Once a sheet has measured at the viewport height,
 * it remains viewport-height for the current [contentKey], keeping its expanded anchor stable while
 * nested scrolling continues to work normally. Change [contentKey] when one sheet instance swaps
 * between layouts with fundamentally different intrinsic heights.
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

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = modifier.stabilizeViewportHeight(heightPolicy),
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

internal class StableSheetHeightPolicy {
    var isViewportHeightLocked: Boolean = false
        private set

    /** Intrinsic height the current decision was taken for. */
    private var decidedForIntrinsic: Int = UNDECIDED

    /**
     * Re-decides only when the *content* changes shape, never when the viewport
     * does.
     *
     * That split is the whole design. During a drag the framework consumes the
     * sheet's top inset, so `maxHeight` shrinks while the content is untouched;
     * re-running the comparison against the smaller viewport there flips the pin
     * mid-gesture, which moves the expanded anchor under the finger and makes the
     * sheet resist, jitter, and miss its dismiss target. Intrinsic height does not
     * move during a drag, so keying the decision to it leaves gestures alone.
     *
     * When a row is genuinely added or removed the intrinsic height jumps, the
     * decision is retaken, and a sheet that no longer fills the viewport is
     * allowed to shrink instead of leaving the removed row's space behind.
     *
     * The hysteresis band keeps sub-pixel and animation-frame wobble from
     * counting as a change; a control row clears it comfortably.
     */
    fun resolveMinimumHeight(intrinsicHeight: Int, maxHeight: Int, hasBoundedHeight: Boolean): Int {
        if (!hasBoundedHeight) {
            isViewportHeightLocked = false
            decidedForIntrinsic = UNDECIDED
            return 0
        }
        val undecided = decidedForIntrinsic == UNDECIDED
        val contentChanged = !undecided &&
            abs(intrinsicHeight - decidedForIntrinsic) > maxHeight / HYSTERESIS_DIVISOR
        if (undecided || contentChanged) {
            isViewportHeightLocked = intrinsicHeight >= maxHeight
            decidedForIntrinsic = intrinsicHeight
        }
        return if (isViewportHeightLocked) maxHeight else 0
    }

    private companion object {
        const val UNDECIDED = -1

        /** A twentieth of the viewport: far below one control row, far above frame noise. */
        const val HYSTERESIS_DIVISOR = 20
    }
}

private fun Modifier.stabilizeViewportHeight(policy: StableSheetHeightPolicy): Modifier =
    layout { measurable, constraints ->
        // Intrinsic rather than measured: a measurable may only be measured once
        // per pass, and asking it afterwards how tall it wanted to be is exactly
        // the question that was being answered wrongly before.
        val intrinsicHeight = runCatching {
            measurable.maxIntrinsicHeight(constraints.maxWidth)
        }.getOrDefault(NO_INTRINSICS)
        // A subtree that refuses intrinsics gets main's behaviour: no pin. The
        // previous fallback was the viewport height, which reads as "this
        // content wants the whole screen" and pinned every such sheet open at
        // full height — that is what made the insulin sheet full screen.
        val minimumHeight = if (intrinsicHeight == NO_INTRINSICS) 0 else policy.resolveMinimumHeight(
            intrinsicHeight = intrinsicHeight,
            maxHeight = constraints.maxHeight,
            hasBoundedHeight = constraints.hasBoundedHeight,
        )
        val measurementConstraints = if (minimumHeight > constraints.minHeight) {
            constraints.copy(minHeight = minimumHeight)
        } else {
            constraints
        }
        val placeable = measurable.measure(measurementConstraints)

        layout(placeable.width, placeable.height) {
            placeable.placeRelative(0, 0)
        }
    }

/** Sentinel: this subtree does not support intrinsic measurement. */
private const val NO_INTRINSICS = -1
