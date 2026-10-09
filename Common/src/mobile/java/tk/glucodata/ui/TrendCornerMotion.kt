package tk.glucodata.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember

internal class TrendCornerMotion(
    private val topStart: State<Float>,
    private val topEnd: State<Float>,
    private val bottomEnd: State<Float>,
    private val bottomStart: State<Float>
) {
    val weights: TrendCornerWeights
        get() = TrendCornerWeights(topStart.value, topEnd.value, bottomEnd.value, bottomStart.value)
}

@Composable
internal fun rememberTrendCornerMotion(velocity: Float): TrendCornerMotion {
    val targets = remember(velocity) { trendCornerWeightsFromVelocity(velocity) }
    val spec = trendCornerAnimationSpec()
    val topStart = animateFloatAsState(targets.topStart, spec, label = "TrendTopStart")
    val topEnd = animateFloatAsState(targets.topEnd, spec, label = "TrendTopEnd")
    val bottomEnd = animateFloatAsState(targets.bottomEnd, spec, label = "TrendBottomEnd")
    val bottomStart = animateFloatAsState(targets.bottomStart, spec, label = "TrendBottomStart")
    return remember(topStart, topEnd, bottomEnd, bottomStart) {
        TrendCornerMotion(topStart, topEnd, bottomEnd, bottomStart)
    }
}
