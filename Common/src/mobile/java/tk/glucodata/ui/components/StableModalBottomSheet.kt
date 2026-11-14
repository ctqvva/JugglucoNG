package tk.glucodata.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
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
import androidx.compose.ui.composed
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
 * Short sheets retain their intrinsic height. Once a sheet has measured at the viewport height
 * without the keyboard visible, it remains viewport-height for the current [contentKey], keeping
 * its expanded anchor stable while nested scrolling continues to work normally. Change
 * [contentKey] when one sheet instance swaps between layouts with fundamentally different
 * intrinsic heights.
 *
 * Forms that grow and shrink opt into [adaptiveHeight]. Their top inset is reserved outside the
 * moving surface, keeping the viewport stable without a height lock. Keyboard padding and tall
 * search results can then disappear without leaving a permanent minimum height behind.
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
    adaptiveHeight: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val heightPolicy = remember(sheetState, contentKey) { StableSheetHeightPolicy() }

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = if (adaptiveHeight) modifier.reserveSheetTopInset()
            else modifier.stabilizeViewportHeight(heightPolicy),
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
     * temporary measurement for content that genuinely needs the full viewport.
     */
    fun onMeasured(
        measuredHeight: Int,
        maxHeight: Int,
        hasBoundedHeight: Boolean,
        isImeVisible: Boolean,
    ) {
        if (hasBoundedHeight && !isImeVisible && measuredHeight >= maxHeight) {
            isViewportHeightLocked = true
        }
    }
}

/** Read in the dialog window, before Material consumes top insets based on the sheet's offset. */
private fun Modifier.reserveSheetTopInset(): Modifier = composed {
    windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
}

/**
 * Animate changes to a form, but let the IME's own inset animation resize its viewport directly.
 * Keeping a second size animation during IME dismissal briefly subtracts the keyboard's padding
 * before the form catches up, pulling the sheet down and back up. Removing the modifier while
 * the IME is present also initializes it at the new viewport when the keyboard finishes closing.
 */
fun Modifier.animateSheetContentSize(isImeVisible: Boolean): Modifier =
    if (isImeVisible) this else animateContentSize(animationSpec = spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = 380f
    ))

/**
 * The sheet is a window of its own, and the keyboard's inset has to come from that window: it is
 * what pads the sheet. The window behind can see the keyboard gone while the sheet is still
 * padded for it, which a back gesture does, and a sheet measured then would look like content
 * that fills the viewport. Hence composed, which resolves inside the sheet, and reading the inset
 * during measurement, so it can't lag the padding it explains.
 */
private fun Modifier.stabilizeViewportHeight(
    policy: StableSheetHeightPolicy,
): Modifier = composed {
    val ime = WindowInsets.ime
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
            isImeVisible = ime.getBottom(this) > 0,
        )

        layout(placeable.width, placeable.height) {
            placeable.placeRelative(0, 0)
        }
    }
}
