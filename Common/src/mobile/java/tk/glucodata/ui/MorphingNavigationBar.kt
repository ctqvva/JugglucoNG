package tk.glucodata.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ShortNavigationBar
import androidx.compose.material3.ShortNavigationBarArrangement
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tk.glucodata.ui.util.GlucoseFormatter
import tk.glucodata.ui.viewmodel.DashboardViewModel

@Composable
internal fun MorphingNavigationBar(
    viewModel: DashboardViewModel,
    content: @Composable () -> Unit
) {
    // Keep reading/animation state in the bar so it does not recompose the NavHost.
    val history by viewModel.glucoseHistory.collectAsStateWithLifecycle()
    val unit by viewModel.unit.collectAsStateWithLifecycle()
    val viewMode by viewModel.viewMode.collectAsStateWithLifecycle()
    val sensorName by viewModel.sensorName.collectAsStateWithLifecycle()
    val activeSensors by viewModel.activeSensorList.collectAsStateWithLifecycle()
    val latestPoint = remember(history) { latestDashboardPoint(history) }
    val currentSnapshot = rememberDashboardCurrentSnapshot(sensorName, activeSensors, latestPoint, viewMode, unit)
    val trend = rememberDashboardTrend(history, latestPoint, currentSnapshot, viewMode, GlucoseFormatter.isMmol(unit))
    val radii = remember(trend.velocity) { navigationCornerRadii(trend.velocity) }
    val animationSpec = trendCornerAnimationSpec()
    val topStart by animateDpAsState(radii.topStart.dp, animationSpec, label = "NavigationTopStart")
    val topEnd by animateDpAsState(radii.topEnd.dp, animationSpec, label = "NavigationTopEnd")
    val bottomEnd by animateDpAsState(radii.bottomEnd.dp, animationSpec, label = "NavigationBottomEnd")
    val bottomStart by animateDpAsState(radii.bottomStart.dp, animationSpec, label = "NavigationBottomStart")

    Box(
        Modifier
            .windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
            .padding(horizontal = 16.dp)
            .padding(top = 8.dp, bottom = 8.dp)
    ) {
        Surface(
            shape = RoundedCornerShape(topStart, topEnd, bottomEnd, bottomStart),
            color = MaterialTheme.colorScheme.surfaceContainer
        ) {
            // Only the outline morphs. Insets and item geometry stay fixed, including hit targets.
            ShortNavigationBar(
                modifier = Modifier.padding(vertical = 8.dp),
                containerColor = Color.Transparent,
                windowInsets = WindowInsets(0, 0, 0, 0),
                arrangement = ShortNavigationBarArrangement.EqualWeight,
                content = content
            )
        }
    }
}
