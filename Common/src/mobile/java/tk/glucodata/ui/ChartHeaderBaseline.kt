package tk.glucodata.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp

/** The date keeps its existing position; all top-of-plot labels follow its text baseline. */
@Composable
internal fun chartHeaderTextBaseline(date: String, backdropAlpha: Float): Float {
    val heightPx = with(LocalDensity.current) { 32.dp.roundToPx() }
    val text = rememberTextMeasurer().measure(
        text = date,
        style = MaterialTheme.typography.labelLarge,
        constraints = Constraints(maxHeight = heightPx),
        maxLines = 1,
        softWrap = false
    )
    return with(LocalDensity.current) { lerp(16.dp, 0.dp, backdropAlpha).toPx() } +
        (heightPx - text.size.height) / 2 + text.firstBaseline
}

/** Use the laid-out first line, including padding, whether the state chip has one or many rows. */
internal fun Modifier.chartHeaderBaseline(baselinePx: Int): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    val firstBaseline = placeable[FirstBaseline]
    layout(placeable.width, placeable.height) {
        placeable.placeRelative(0, baselinePx - firstBaseline)
    }
}
