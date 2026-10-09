package tk.glucodata.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.ui.unit.Dp
import kotlin.math.cos
import kotlin.math.sin

internal data class TrendCornerWeights(
    val topStart: Float,
    val topEnd: Float,
    val bottomEnd: Float,
    val bottomStart: Float
)

internal data class NavigationCornerRadii(
    val topStart: Float,
    val topEnd: Float,
    val bottomEnd: Float,
    val bottomStart: Float
)

/** The hero's directional shape mapping. Velocity is always in mg/dL per minute. */
internal fun trendCornerWeightsFromVelocity(velocity: Float): TrendCornerWeights {
    val finiteVelocity = velocity.takeIf { it.isFinite() } ?: 0f
    val angleDeg = (-finiteVelocity * 25f).coerceIn(-90f, 90f)
    val angleRad = Math.toRadians(angleDeg.toDouble())
    val dirX = cos(angleRad).toFloat()
    val dirY = sin(angleRad).toFloat()

    fun cornerRoundWeight(cornerX: Float, cornerY: Float): Float {
        val dot = ((dirX * cornerX + dirY * cornerY) * 0.70710677f).coerceIn(-1f, 1f)
        return if (dot >= 0f) {
            // Toward direction: sharper as dot increases.
            0.88f + (0.08f - 0.88f) * dot
        } else {
            // Opposite side: extra rounded.
            0.88f + (1f - 0.88f) * -dot
        }
    }

    return TrendCornerWeights(
        topStart = cornerRoundWeight(-1f, -1f),
        topEnd = cornerRoundWeight(1f, -1f),
        bottomEnd = cornerRoundWeight(1f, 1f),
        bottomStart = cornerRoundWeight(-1f, 1f)
    )
}

internal fun trendCornerAnimationSpec() = spring<Dp>(
    dampingRatio = Spring.DampingRatioLowBouncy,
    stiffness = Spring.StiffnessLow
)

/** Reference panel: broad top corners, tighter bottom corners; symmetric at rest. */
internal fun navigationCornerRadii(velocity: Float): NavigationCornerRadii {
    val weights = trendCornerWeightsFromVelocity(velocity)
    val resting = trendCornerWeightsFromVelocity(0f)
    fun radius(weight: Float, rest: Float, base: Float) =
        (base + (weight - rest) * 8f).coerceIn(base - 8f, base + 8f)
    return NavigationCornerRadii(
        topStart = radius(weights.topStart, resting.topStart, 32f),
        topEnd = radius(weights.topEnd, resting.topEnd, 32f),
        bottomEnd = radius(weights.bottomEnd, resting.bottomEnd, 12f),
        bottomStart = radius(weights.bottomStart, resting.bottomStart, 12f)
    )
}
