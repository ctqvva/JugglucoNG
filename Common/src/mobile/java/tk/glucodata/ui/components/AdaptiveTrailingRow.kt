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
 * Keeps a useful reading width for [content]. A Row's weighted text only receives what its
 * unweighted action leaves behind, which can be a single glyph at large font sizes. Move the action
 * below the text when the two cannot share a line comfortably: when the text would get less than a
 * reading width that grows with the font (or less than its longest word, if that is wider).
 *
 * [spacing] is the gap between text and action on one line. Slots must support intrinsic
 * measurement (text, icons and ordinary controls, not lazy lists or BoxWithConstraints). Placement
 * is relative so the trailing edge follows RTL.
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
        val readingWidth =
            minOf(
                primary.maxIntrinsicWidth(Constraints.Infinity),
                maxOf(
                    (160.dp.toPx() * fontScale).toInt(),
                    primary.minIntrinsicWidth(Constraints.Infinity),
                ),
            )
        // An empty slot (SettingsItem always passes one) has nothing to move below the text.
        val stacked = trailingIntrinsic > 0 && readingWidth.toLong() + gap + trailingWidth > width
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
