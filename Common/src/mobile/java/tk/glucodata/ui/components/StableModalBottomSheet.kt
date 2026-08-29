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

    /** Largest viewport this sheet has been offered, in pixels. */
    private var viewportCeiling: Int = 0

    /**
     * Decides the floor from what the content wants, measured against the
     * *largest* viewport seen rather than the current one.
     *
     * Two things look like "the sheet got shorter" and they need opposite
     * answers. While a sheet is dragged the framework consumes its top inset,
     * so the space offered shrinks although the content is untouched — the pin
     * has to hold, or the expanded anchor moves under the finger and the sheet
     * fights the gesture and misses its dismiss target. When a row is removed
     * the content genuinely stops wanting the viewport, and the pin has to let
     * go or the row's space stays behind as dead space.
     *
     * Comparing against the ceiling separates them by construction: a drag
     * lowers `maxHeight` but not `intrinsicHeight`, so the comparison is
     * unchanged and the pin cannot flip mid-gesture. Removing content lowers
     * `intrinsicHeight` below the ceiling, and it releases.
     */
    fun resolveMinimumHeight(intrinsicHeight: Int, maxHeight: Int, hasBoundedHeight: Boolean): Int {
        if (!hasBoundedHeight) {
            isViewportHeightLocked = false
            return 0
        }
        if (maxHeight > viewportCeiling) viewportCeiling = maxHeight
        isViewportHeightLocked = intrinsicHeight >= viewportCeiling
        return if (isViewportHeightLocked) maxHeight else 0
    }
}

private fun Modifier.stabilizeViewportHeight(policy: StableSheetHeightPolicy): Modifier =
    layout { measurable, constraints ->
        // Intrinsic, not measured: a measurable may only be measured once per
        // pass, so asking afterwards how tall it wanted to be is the question
        // that was previously answered wrongly. Guarded — a subtree that
        // refuses intrinsics falls back to "wants the viewport", which is the
        // conservative answer.
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
