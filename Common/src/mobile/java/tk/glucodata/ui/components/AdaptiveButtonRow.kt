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
 * every label fits its share. Otherwise each button takes its own full-width line.
 *
 * A FlowRow can't do this: it breaks lines on a weighted item's minimum intrinsic width (its
 * longest word), so weighted buttons never wrap and their labels break mid-phrase instead.
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
        val widths = measurables.map { it.maxIntrinsicWidth(Constraints.Infinity) }
        val width =
            if (constraints.hasBoundedWidth) constraints.maxWidth
            else (widths.sum() + gaps).coerceAtLeast(constraints.minWidth)
        val share = if (measurables.isEmpty()) 0 else ((width - gaps) / measurables.size).coerceAtLeast(0)
        val inline = widths.all { it <= share }
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
                    it.placeRelative(offset, (height - it.height) / 2)
                    offset += share + gap
                } else {
                    it.placeRelative(0, offset)
                    offset += it.height + gap
                }
            }
        }
    }
}
