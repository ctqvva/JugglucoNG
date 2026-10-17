package tk.glucodata.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.IntrinsicMeasurable
import androidx.compose.ui.layout.IntrinsicMeasureScope
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp

@Composable
internal fun HeroDeltaText(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodySmall
) {
    Text(
        text = text,
        style = style.copy(fontFeatureSettings = "tnum"),
        color = color.copy(alpha = 0.65f),
        softWrap = false,
        maxLines = 1,
        modifier = modifier
    )
}

@Composable
internal fun DashboardHeroPrimaryText(
    value: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    deltaText: String? = null,
    deltaColor: Color = color
) {
    // A real Row keeps intrinsic height equal to the glucose line. The parent
    // checks available width and places the delta by the arrow if it cannot fit.
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        AnimatedHeroPrimaryText(value, style, color)
        if (!deltaText.isNullOrEmpty()) {
            Spacer(Modifier.width(8.dp))
            HeroDeltaText(deltaText, deltaColor, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

/** Keep the primary and its delta together on the same center line. The dot
 * separates that group from the secondary reading and shares its baseline. */
@Composable
internal fun DashboardHeroPairText(
    primary: String,
    secondary: String,
    primaryStyle: TextStyle,
    secondaryStyle: TextStyle,
    dotStyle: TextStyle,
    primaryColor: Color,
    secondaryColor: Color,
    contentColor: Color,
    deltaText: String?,
    deltaStyle: TextStyle,
    beforeDelta: Dp = 8.dp,
    beforeDot: Dp = 8.dp,
    afterDot: Dp = 4.dp,
    modifier: Modifier = Modifier
) {
    val hasDelta = !deltaText.isNullOrEmpty()
    val policy = remember(hasDelta, beforeDelta, beforeDot, afterDot) {
        HeroPairMeasurePolicy(hasDelta, beforeDot, afterDot, beforeDelta)
    }
    Layout(modifier = modifier, measurePolicy = policy, content = {
        AnimatedHeroPrimaryText(primary, primaryStyle, primaryColor)
        Text("·", style = dotStyle, color = contentColor.copy(alpha = 0.72f), maxLines = 1, softWrap = false)
        Text(secondary, style = secondaryStyle, color = secondaryColor, maxLines = 1, softWrap = false)
        if (hasDelta) HeroDeltaText(deltaText.orEmpty(), contentColor, style = deltaStyle)
    })
}

internal class HeroPairMeasurePolicy(
    private val hasDelta: Boolean,
    private val beforeDot: Dp = 8.dp,
    private val afterDot: Dp = 4.dp,
    private val beforeDelta: Dp = 8.dp
) : MeasurePolicy {
    override fun MeasureScope.measure(measurables: List<Measurable>, constraints: Constraints): androidx.compose.ui.layout.MeasureResult {
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val main = measurables[0].measure(loose)
        val dot = measurables[1].measure(loose)
        val second = measurables[2].measure(loose)
        val deltaGap = if (hasDelta) beforeDelta.roundToPx() else 0
        val supportWidth = beforeDot.roundToPx() + dot.width + afterDot.roundToPx() + second.width
        val delta = if (hasDelta) measurables[3].measure(loose.copy(
            maxWidth = (constraints.maxWidth - main.width - deltaGap - supportWidth).coerceAtLeast(0)
        )) else null
        val deltaX = main.width + deltaGap
        val dotX = main.width + deltaGap + (delta?.width ?: 0) + beforeDot.roundToPx()
        val secondaryX = dotX + dot.width + afterDot.roundToPx()
        val baseline = maxOf(dot[FirstBaseline], second[FirstBaseline])
        val lineHeight = baseline + maxOf(dot.height - dot[FirstBaseline], second.height - second[FirstBaseline])
        val top = ((main.height - lineHeight) / 2).coerceAtLeast(0)
        val width = constraints.constrainWidth(secondaryX + second.width)
        // Supporting text uses the primary line's existing height, including
        // during intrinsic measurement of the hero/sensor card pair.
        return layout(width, main.height) {
            main.placeRelative(0, 0)
            dot.placeRelative(dotX, top + baseline - dot[FirstBaseline])
            second.placeRelative(secondaryX, top + baseline - second[FirstBaseline])
            delta?.placeRelative(deltaX, (main.height - delta.height) / 2)
        }
    }

    override fun IntrinsicMeasureScope.minIntrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Int) =
        measurables[0].minIntrinsicHeight(width)

    override fun IntrinsicMeasureScope.maxIntrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Int) =
        measurables[0].maxIntrinsicHeight(width)

    override fun IntrinsicMeasureScope.minIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Int) =
        measurables.sumOf { it.minIntrinsicWidth(height) } + beforeDot.roundToPx() + afterDot.roundToPx() + if (hasDelta) beforeDelta.roundToPx() else 0

    override fun IntrinsicMeasureScope.maxIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Int) =
        measurables.sumOf { it.maxIntrinsicWidth(height) } + beforeDot.roundToPx() + afterDot.roundToPx() + if (hasDelta) beforeDelta.roundToPx() else 0
}

@Composable
private fun AnimatedHeroPrimaryText(value: String, style: TextStyle, color: Color) {
    AnimatedContent(
        targetState = value,
        transitionSpec = {
            (slideInVertically(spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow)) { -it / 2 } + fadeIn(tween(200)))
                .togetherWith(slideOutVertically(spring(stiffness = Spring.StiffnessMedium)) { it / 2 } + fadeOut(tween(100)))
        },
        label = "GlucoseHeroAnimation"
    ) { animatedValue ->
        Text(animatedValue, style = style, color = color, softWrap = false, maxLines = 1)
    }
}
