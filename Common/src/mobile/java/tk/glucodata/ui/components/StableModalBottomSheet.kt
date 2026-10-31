package tk.glucodata.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp

/**
 * App-wide modal sheet entry point.
 *
 * Material 3 derives the expanded anchor from the sheet's measured height on every layout pass.
 * A viewport-height sheet can otherwise become shorter while it is dragged because the framework
 * dynamically consumes its top inset. That moves the expanded anchor during the gesture and makes
 * the sheet appear to resist or jitter.
 *
 * Short sheets retain their intrinsic height. Once a sheet has measured at the viewport height
 * without the keyboard visible, it remains viewport-height for the current [contentKey], keeping
 * its expanded anchor stable while nested scrolling continues to work normally. Change
 * [contentKey] when one sheet instance swaps between layouts with fundamentally different
 * intrinsic heights.
 *
 * A sheet whose content animates its height passes the same [contentHeight] to
 * [Modifier.animateSheetContentHeight]; otherwise the first frame after a [contentKey] change
 * still shows the old, viewport-tall height and would lock the shorter layout at full height.
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
    contentHeight: SheetContentHeightAnimation? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val heightPolicy = remember(sheetState, contentKey) { StableSheetHeightPolicy() }
    val density = LocalDensity.current
    val isImeVisible = WindowInsets.ime.getBottom(density) > 0

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = modifier.stabilizeViewportHeight(heightPolicy, isImeVisible, contentHeight),
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

    fun minimumHeight(maxHeight: Int, hasBoundedHeight: Boolean): Int =
        if (isViewportHeightLocked && hasBoundedHeight) maxHeight else 0

    /**
     * A short sheet can fill the reduced viewport while the keyboard is open. Do not mistake that
     * temporary measurement for content that genuinely needs the full viewport. The same goes for
     * a frame of a height animation: only the height it settles on says what the content needs.
     */
    fun onMeasured(
        measuredHeight: Int,
        maxHeight: Int,
        hasBoundedHeight: Boolean,
        isImeVisible: Boolean,
        isContentHeightSettled: Boolean = true,
    ) {
        if (hasBoundedHeight && !isImeVisible && isContentHeightSettled && measuredHeight >= maxHeight) {
            isViewportHeightLocked = true
        }
    }
}

/**
 * Records a content height animation's shown and target heights within one measure pass, so the
 * sheet can tell an in-between frame from the height the content actually needs.
 */
class SheetContentHeightAnimation {
    internal var shownHeight: Int = 0
    internal var targetHeight: Int = 0

    internal val isSettled: Boolean
        get() = shownHeight == targetHeight
}

/** [animateContentSize] for a sheet's content, reporting its progress to [animation]. */
fun Modifier.animateSheetContentHeight(
    animation: SheetContentHeightAnimation,
    animationSpec: FiniteAnimationSpec<IntSize>,
): Modifier =
    layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        animation.shownHeight = placeable.height
        layout(placeable.width, placeable.height) { placeable.placeRelative(0, 0) }
    }
        .animateContentSize(animationSpec = animationSpec)
        .layout { measurable, constraints ->
            val placeable = measurable.measure(constraints)
            animation.targetHeight = placeable.height
            layout(placeable.width, placeable.height) { placeable.placeRelative(0, 0) }
        }

private fun Modifier.stabilizeViewportHeight(
    policy: StableSheetHeightPolicy,
    isImeVisible: Boolean,
    contentHeight: SheetContentHeightAnimation?,
): Modifier =
    layout { measurable, constraints ->
        val minimumHeight = policy.minimumHeight(
            maxHeight = constraints.maxHeight,
            hasBoundedHeight = constraints.hasBoundedHeight,
        )
        val measurementConstraints = if (minimumHeight > constraints.minHeight) {
            constraints.copy(minHeight = minimumHeight)
        } else {
            constraints
        }
        val placeable = measurable.measure(measurementConstraints)
        policy.onMeasured(
            measuredHeight = placeable.height,
            maxHeight = constraints.maxHeight,
            hasBoundedHeight = constraints.hasBoundedHeight,
            isImeVisible = isImeVisible,
            isContentHeightSettled = contentHeight?.isSettled ?: true,
        )

        layout(placeable.width, placeable.height) {
            placeable.placeRelative(0, 0)
        }
    }
