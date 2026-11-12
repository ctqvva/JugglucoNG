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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.platform.LocalDensity
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
            Spacer(Modifier.width(16.dp))
            HeroDeltaText(deltaText, deltaColor, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

/**
 * The separator between a reading and its supporting value. Drawn rather than typed:
 * a "·" glyph took the small supporting style, so it shrank to a speck and sat on the
 * font's x-height with its side bearings deciding the gaps.
 */
internal object HeroSeparatorDot {
    /** Diameter as a share of the supporting value's text size, about twice its stroke. */
    const val DIAMETER_EM = 0.24f
    /** Centre height above the supporting value's baseline: half of Plex's 0.7 em digits. */
    const val CENTER_EM = 0.35f
    /** Same visual gap on both sides of the dot. */
    val Gap = 8.dp

    @Composable
    fun diameter(style: TextStyle): Dp = with(LocalDensity.current) { (style.fontSize * DIAMETER_EM).toDp() }

    @Composable
    fun lift(style: TextStyle): Dp = with(LocalDensity.current) { (style.fontSize * CENTER_EM).toDp() }

    @Composable
    fun Dot(diameter: Dp, color: Color, modifier: Modifier = Modifier) {
        Box(modifier.size(diameter).background(color, CircleShape))
    }
}

/** The dot centres on the secondary's digits; the smaller primary delta sits below
 * them. The delta has its own horizontal inset. */
@Composable
internal fun DashboardHeroPairText(
    primary: String,
    secondary: String,
    primaryStyle: TextStyle,
    secondaryStyle: TextStyle,
    primaryColor: Color,
    secondaryColor: Color,
    contentColor: Color,
    deltaText: String?,
    deltaStyle: TextStyle,
    beforeDot: Dp = HeroSeparatorDot.Gap,
    afterDot: Dp = HeroSeparatorDot.Gap,
    beforeDelta: Dp = 16.dp,
    modifier: Modifier = Modifier
) {
    val hasDelta = !deltaText.isNullOrEmpty()
    val dotLift = HeroSeparatorDot.lift(secondaryStyle)
    val policy = remember(hasDelta, beforeDot, afterDot, beforeDelta, dotLift) {
        HeroPairMeasurePolicy(hasDelta, beforeDot, afterDot, beforeDelta, dotLift)
    }
    Layout(modifier = modifier, measurePolicy = policy, content = {
        AnimatedHeroPrimaryText(primary, primaryStyle, primaryColor)
        HeroSeparatorDot.Dot(HeroSeparatorDot.diameter(secondaryStyle), contentColor.copy(alpha = 0.72f))
        Text(secondary, style = secondaryStyle, color = secondaryColor, maxLines = 1, softWrap = false)
        if (hasDelta) HeroDeltaText(deltaText.orEmpty(), contentColor, style = deltaStyle)
    })
}

internal class HeroPairMeasurePolicy(
    private val hasDelta: Boolean,
    private val beforeDot: Dp = HeroSeparatorDot.Gap,
    private val afterDot: Dp = HeroSeparatorDot.Gap,
    private val beforeDelta: Dp = 16.dp,
    /** Dot centre above the secondary's baseline. */
    private val dotLift: Dp = 0.dp
) : MeasurePolicy {
    override fun MeasureScope.measure(measurables: List<Measurable>, constraints: Constraints): androidx.compose.ui.layout.MeasureResult {
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val main = measurables[0].measure(loose)
        val dot = measurables[1].measure(loose)
        val second = measurables[2].measure(loose)
        val supportX = main.width + beforeDot.roundToPx()
        val deltaX = main.width + beforeDelta.roundToPx()
        val delta = if (hasDelta) measurables[3].measure(loose.copy(
            maxWidth = (constraints.maxWidth - deltaX).coerceAtLeast(0)
        )) else null
        val secondaryX = supportX + dot.width + afterDot.roundToPx()
        val baseline = second[FirstBaseline]
        val lineHeight = second.height
        val supportHeight = lineHeight + (delta?.height ?: 0)
        val top = ((main.height - supportHeight) / 2).coerceAtLeast(0)
        val width = constraints.constrainWidth(maxOf(secondaryX + second.width, if (delta != null) deltaX + delta.width else 0))
        // Supporting text uses the primary line's existing height, including
        // during intrinsic measurement of the hero/sensor card pair.
        return layout(width, main.height) {
            main.placeRelative(0, 0)
            dot.placeRelative(supportX, top + baseline - dotLift.roundToPx() - dot.height / 2)
            second.placeRelative(secondaryX, top)
            delta?.placeRelative(deltaX, top + lineHeight)
        }
    }

    override fun IntrinsicMeasureScope.minIntrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Int) =
        measurables[0].minIntrinsicHeight(width)

    override fun IntrinsicMeasureScope.maxIntrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Int) =
        measurables[0].maxIntrinsicHeight(width)

    override fun IntrinsicMeasureScope.minIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Int) =
        measurables[0].minIntrinsicWidth(height) + maxOf(
            beforeDot.roundToPx() + measurables[1].minIntrinsicWidth(height) + afterDot.roundToPx() + measurables[2].minIntrinsicWidth(height),
            if (hasDelta) beforeDelta.roundToPx() + measurables[3].minIntrinsicWidth(height) else 0
        )

    override fun IntrinsicMeasureScope.maxIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Int) =
        measurables[0].maxIntrinsicWidth(height) + maxOf(
            beforeDot.roundToPx() + measurables[1].maxIntrinsicWidth(height) + afterDot.roundToPx() + measurables[2].maxIntrinsicWidth(height),
            if (hasDelta) beforeDelta.roundToPx() + measurables[3].maxIntrinsicWidth(height) else 0
        )
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
