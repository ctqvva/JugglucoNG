package tk.glucodata.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.dp

/**
 * Sibling action buttons at equal widths on one line, as a Row of `weight(1f)` buttons, while
 * every label fits its share on one line. Otherwise each button takes its own full-width line,
 * rather than wrapping its label inside a narrow button.
 *
 * With [lastFills] the buttons keep their own widths and the last, the row's main action, takes
 * the rest of the line, as a Row with `weight(1f)` on it alone; they stack the same way when the
 * rest is too narrow for its label.
 *
 * A FlowRow of weighted buttons can't do this: it breaks lines on a weighted item's minimum
 * intrinsic width (its longest word), then still splits the line equally, so labels wrap instead.
 * Buttons must support intrinsic measurement. Placement is relative, so the order follows RTL.
 */
@Composable
fun AdaptiveButtonRow(
    modifier: Modifier = Modifier,
    spacing: Dp = 8.dp,
    lastFills: Boolean = false,
    content: @Composable () -> Unit,
) {
    Layout(modifier = modifier, content = content) { measurables, constraints ->
        val gap = spacing.roundToPx()
        val gaps = gap * (measurables.size - 1).coerceAtLeast(0)
        val width =
            if (constraints.hasBoundedWidth) constraints.maxWidth
            else (measurables.sumOf { it.maxIntrinsicWidth(Constraints.Infinity) } + gaps)
                .coerceAtLeast(constraints.minWidth)
        val needs = measurables.map { it.maxIntrinsicWidth(Constraints.Infinity) }
        val room = (width - gaps).coerceAtLeast(0)
        val shares =
            if (lastFills) needs.dropLast(1).let { own -> own + (room - own.sum()) }
            else List(needs.size) { room / needs.size }
        val inline = needs.indices.all { needs[it] <= shares[it] }
        val placeables =
            measurables.mapIndexed { index, measurable ->
                val itemWidth = if (inline) shares[index] else width
                measurable.measure(Constraints(minWidth = itemWidth, maxWidth = itemWidth))
            }
        val contentHeight =
            if (inline) placeables.maxOfOrNull { it.height } ?: 0
            else placeables.sumOf { it.height } + gaps
        val height = constraints.constrainHeight(contentHeight)
        layout(width, height) {
            var offset = 0
            placeables.forEach {
                if (inline) {
                    it.placeRelative(offset, (height - it.height) / 2)
                    offset += it.width + gap
                } else {
                    it.placeRelative(0, offset)
                    offset += it.height + gap
                }
            }
        }
    }
}
