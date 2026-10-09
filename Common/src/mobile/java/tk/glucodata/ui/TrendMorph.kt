package tk.glucodata.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import kotlin.math.cos
import kotlin.math.sin

internal data class TrendCornerWeights(
    val topStart: Float,
    val topEnd: Float,
    val bottomEnd: Float,
    val bottomStart: Float
)

internal data class TrendCornerRadii(
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

internal fun trendCornerAnimationSpec() = spring<Float>(
    dampingRatio = Spring.DampingRatioLowBouncy,
    stiffness = Spring.StiffnessLow,
    visibilityThreshold = 0.001f
)

/** Primary hero targets; navigation uses these same animated directional weights. */
internal fun heroCornerRadiiFromWeights(weights: TrendCornerWeights): TrendCornerRadii {
    fun radius(weight: Float, min: Float, max: Float) = min + (max - min) * weight.coerceIn(0f, 1f)
    return TrendCornerRadii(
        radius(weights.topStart, 22f, 52f),
        radius(weights.topEnd, 8f, 24f),
        radius(weights.bottomEnd, 8f, 24f),
        radius(weights.bottomStart, 22f, 46f)
    )
}

/** Navigation uses the hero's geometry as well as its shared animated weights. */
internal fun navigationCornerRadii(velocity: Float): TrendCornerRadii =
    navigationCornerRadiiFromWeights(trendCornerWeightsFromVelocity(velocity))

internal fun navigationCornerRadiiFromWeights(weights: TrendCornerWeights): TrendCornerRadii =
    heroCornerRadiiFromWeights(weights)
