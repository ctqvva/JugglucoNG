package tk.glucodata.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.dp

/**
 * A Row of weighted [content] and an unweighted trailing action, laid out exactly like that Row
 * while it works. Once the action leaves the text less room than its longest word (large fonts on
 * narrow screens, where a Row breaks words into single letters), the action moves below the text.
 *
 * [spacing] is the gap the Row had between the two; pass the call site's original value so the
 * default layout does not shift. Slots must support intrinsic measurement (text, icons and ordinary
 * controls, not lazy lists or BoxWithConstraints). Placement is relative so the trailing edge
 * follows RTL.
 */
@Composable
fun AdaptiveTrailingRow(
    modifier: Modifier = Modifier,
    spacing: Dp = 12.dp,
    trailingContent: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    Layout(
        modifier = modifier,
        content = {
            Box { content() }
            Box { trailingContent() }
        },
    ) { measurables, constraints ->
        val primary = measurables[0]
        val trailing = measurables[1]
        val trailingIntrinsic = trailing.maxIntrinsicWidth(Constraints.Infinity)
        val gap = if (trailingIntrinsic == 0) 0 else spacing.roundToPx()
        val width =
            if (constraints.hasBoundedWidth) constraints.maxWidth
            else primary.maxIntrinsicWidth(Constraints.Infinity) + gap + trailingIntrinsic
        val trailingWidth = trailingIntrinsic.coerceAtMost(width)
        val stacked = primary.minIntrinsicWidth(Constraints.Infinity).toLong() + gap + trailingWidth > width
        val childConstraints =
            constraints.copy(minWidth = 0, minHeight = 0, maxHeight = Constraints.Infinity)
        val action = trailing.measure(childConstraints.copy(maxWidth = trailingWidth))
        val textWidth = if (stacked) width else (width - gap - action.width).coerceAtLeast(0)
        val text = primary.measure(childConstraints.copy(maxWidth = textWidth))
        val stackGap = 12.dp.roundToPx()
        val contentHeight =
            if (stacked) text.height + stackGap + action.height else maxOf(text.height, action.height)
        // Centre in the laid-out height: a caller's heightIn(min) can exceed the content.
        val height = constraints.constrainHeight(contentHeight)
        layout(width, height) {
            if (stacked) {
                val top = (height - contentHeight) / 2
                text.placeRelative(0, top)
                action.placeRelative(width - action.width, top + text.height + stackGap)
            } else {
                text.placeRelative(0, (height - text.height) / 2)
                action.placeRelative(width - action.width, (height - action.height) / 2)
            }
        }
    }
}
