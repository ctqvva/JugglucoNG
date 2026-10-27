package tk.glucodata.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.dp

/**
 * Keeps a useful reading width for [content]. A Row's weighted text only receives what its
 * unweighted action leaves behind, which can be a single glyph at large font sizes. Move the action
 * below the text when the two cannot share a line comfortably.
 *
 * Slots must support intrinsic measurement (text, icons and ordinary controls, not lazy lists or
 * BoxWithConstraints). Placement is relative so the trailing edge follows RTL.
 */
@Composable
fun AdaptiveTrailingRow(
    modifier: Modifier = Modifier,
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
        val width = constraints.maxWidth
        val primary = measurables[0]
        val trailing = measurables[1]
        val readingWidth =
            minOf(
                primary.maxIntrinsicWidth(Constraints.Infinity),
                (160.dp.toPx() * fontScale).toInt(),
            )
        val trailingWidth = trailing.maxIntrinsicWidth(Constraints.Infinity).coerceAtMost(width)
        val gap = if (trailingWidth == 0) 0 else 12.dp.roundToPx()
        val stacked = readingWidth.toLong() + gap + trailingWidth > width
        val childConstraints =
            constraints.copy(minWidth = 0, minHeight = 0, maxHeight = Constraints.Infinity)
        val action = trailing.measure(childConstraints.copy(maxWidth = trailingWidth))
        val textWidth = if (stacked) width else (width - gap - action.width).coerceAtLeast(0)
        val text = primary.measure(childConstraints.copy(maxWidth = textWidth))
        val contentHeight =
            if (stacked) text.height + gap + action.height else maxOf(text.height, action.height)
        // Centre in the laid-out height: a caller's heightIn(min) can exceed the content.
        val height = constraints.constrainHeight(contentHeight)
        layout(width, height) {
            if (stacked) {
                val top = (height - contentHeight) / 2
                text.placeRelative(0, top)
                action.placeRelative(width - action.width, top + text.height + gap)
            } else {
                text.placeRelative(0, (height - text.height) / 2)
                action.placeRelative(width - action.width, (height - action.height) / 2)
            }
        }
    }
}
