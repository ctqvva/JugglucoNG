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
 * Short sheets retain their intrinsic height, including across temporary viewport changes such as
 * the keyboard opening. A sheet whose content intrinsically needs the whole viewport remains
 * viewport-height for the current [contentKey], keeping its expanded anchor stable while nested
 * scrolling continues to work normally. Change [contentKey] when one sheet instance swaps between
 * layouts with fundamentally different intrinsic heights.
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

    private var decidedForIntrinsicHeight: Int = UNDECIDED

    /**
     * Reconsiders the height lock only when the content's intrinsic height changes, not when the
     * available viewport changes. The latter happens while dragging a full-height sheet and while
     * the keyboard is visible. A temporary keyboard-sized viewport must not permanently turn an
     * otherwise short sheet into a full-height one.
     */
    fun resolveMinimumHeight(intrinsicHeight: Int, maxHeight: Int, hasBoundedHeight: Boolean): Int {
        if (!hasBoundedHeight) {
            isViewportHeightLocked = false
            decidedForIntrinsicHeight = UNDECIDED
            return 0
        }

        val undecided = decidedForIntrinsicHeight == UNDECIDED
        val contentChanged = !undecided &&
            abs(intrinsicHeight - decidedForIntrinsicHeight) > maxHeight / HYSTERESIS_DIVISOR
        if (undecided || contentChanged) {
            isViewportHeightLocked = intrinsicHeight >= maxHeight
            decidedForIntrinsicHeight = intrinsicHeight
        }
        return if (isViewportHeightLocked) maxHeight else 0
    }

    private companion object {
        const val UNDECIDED = -1
        const val HYSTERESIS_DIVISOR = 20
    }
}

private fun Modifier.stabilizeViewportHeight(policy: StableSheetHeightPolicy): Modifier =
    layout { measurable, constraints ->
        val intrinsicHeight = runCatching {
            measurable.maxIntrinsicHeight(constraints.maxWidth)
        }.getOrDefault(constraints.maxHeight)
        val minimumHeight = policy.resolveMinimumHeight(
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
