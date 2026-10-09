package tk.glucodata.ui

import androidx.compose.runtime.Composable

@Composable
internal fun MorphingNavigationBar(content: @Composable () -> Unit) {
    val morph = checkNotNull(LocalDashboardTrendMorph.current)
    // Read the hero's animated values directly; there is no second animation clock.
    MorphingNavigationContainer(navigationCornerRadiiFromWeights(morph.corners.weights), content)
}
