package tk.glucodata.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tk.glucodata.logic.TrendEngine
import tk.glucodata.ui.util.GlucoseFormatter
import tk.glucodata.ui.viewmodel.DashboardViewModel

/** The same four animated values drive the primary hero, sensor card and navigation. */
internal class DashboardTrendMorph(
    val trend: State<TrendEngine.TrendResult>,
    val corners: TrendCornerMotion
)

internal val LocalDashboardTrendMorph = compositionLocalOf<DashboardTrendMorph?> { null }

@Composable
internal fun DashboardTrendMorphProvider(viewModel: DashboardViewModel, content: @Composable () -> Unit) {
    val history by viewModel.glucoseHistory.collectAsStateWithLifecycle()
    val unit by viewModel.unit.collectAsStateWithLifecycle()
    val viewMode by viewModel.viewMode.collectAsStateWithLifecycle()
    val sensorName by viewModel.sensorName.collectAsStateWithLifecycle()
    val activeSensors by viewModel.activeSensorList.collectAsStateWithLifecycle()
    val latestPoint = remember(history) { latestDashboardPoint(history) }
    val snapshot = rememberDashboardCurrentSnapshot(sensorName, activeSensors, latestPoint, viewMode, unit)
    val trend = rememberDashboardTrend(history, latestPoint, snapshot, viewMode, GlucoseFormatter.isMmol(unit))
    val currentTrend = rememberUpdatedState(trend)
    val corners = rememberTrendCornerMotion(trendShapeVelocity(trend))
    // Stable holders: animation frames invalidate only consumers, not the NavHost/provider.
    val morph = remember(currentTrend, corners) { DashboardTrendMorph(currentTrend, corners) }
    CompositionLocalProvider(LocalDashboardTrendMorph provides morph, content = content)
}
