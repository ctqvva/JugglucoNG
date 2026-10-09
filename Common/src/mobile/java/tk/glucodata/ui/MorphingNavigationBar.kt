package tk.glucodata.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
    val radii = remember(trend.state, trend.velocity) { navigationCornerRadiiForTrend(trend) }
    MorphingNavigationContainer(radii, content)
}
