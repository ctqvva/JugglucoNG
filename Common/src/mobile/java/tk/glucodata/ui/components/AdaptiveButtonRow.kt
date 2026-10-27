package tk.glucodata.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.dp

/**
 * Sibling action buttons laid out exactly like a Row of `weight(1f)` buttons, until a share is
 * narrower than some label's longest word (large fonts on narrow screens, where the Row breaks the
 * word into letters). Then each button takes its own full-width line.
 *
 * A FlowRow of weighted buttons can't do this: it breaks lines on the same minimum width but then
 * still splits the line equally, so a long label lands in a share too narrow for it.
 * Buttons must support intrinsic measurement. Placement is relative, so the order follows RTL.
 */
@Composable
fun AdaptiveButtonRow(
    modifier: Modifier = Modifier,
    spacing: Dp = 8.dp,
    content: @Composable () -> Unit,
) {
    Layout(modifier = modifier, content = content) { measurables, constraints ->
        val gap = spacing.roundToPx()
        val gaps = gap * (measurables.size - 1).coerceAtLeast(0)
        val width =
            if (constraints.hasBoundedWidth) constraints.maxWidth
            else (measurables.sumOf { it.maxIntrinsicWidth(Constraints.Infinity) } + gaps)
                .coerceAtLeast(constraints.minWidth)
        val share = if (measurables.isEmpty()) 0 else ((width - gaps) / measurables.size).coerceAtLeast(0)
        val inline = measurables.all { it.minIntrinsicWidth(Constraints.Infinity) <= share }
        val itemWidth = if (inline) share else width
        val placeables =
            measurables.map {
                it.measure(Constraints(minWidth = itemWidth, maxWidth = itemWidth))
            }
        val contentHeight =
            if (inline) placeables.maxOfOrNull { it.height } ?: 0
            else placeables.sumOf { it.height } + gaps
        val height = constraints.constrainHeight(contentHeight)
        layout(width, height) {
            var offset = 0
            placeables.forEach {
                if (inline) {
                    // Top-aligned, as the Row was.
                    it.placeRelative(offset, 0)
                    offset += share + gap
                } else {
                    it.placeRelative(0, offset)
                    offset += it.height + gap
                }
            }
        }
    }
}
