package tk.glucodata.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tk.glucodata.CurrentDisplaySource
import tk.glucodata.DisplayTrendSource
import tk.glucodata.Notify
import tk.glucodata.UiRefreshBus
import tk.glucodata.logic.TrendEngine

internal fun latestDashboardPoint(history: List<GlucosePoint>): GlucosePoint? {
    val tail = history.lastOrNull()
    if (tail == null || history.size < 2) return tail
    val previous = history[history.lastIndex - 1]
    return if (tail.timestamp >= previous.timestamp) tail else history.maxByOrNull { it.timestamp }
}

@Composable
internal fun rememberDashboardCurrentSnapshot(
    sensorName: String,
    activeSensors: List<String>,
    latestPoint: GlucosePoint?,
    viewMode: Int,
    unit: String
): CurrentDisplaySource.Snapshot? {
    val refreshRevision by UiRefreshBus.revision.collectAsStateWithLifecycle(initialValue = 0L)
    return remember(refreshRevision, sensorName, activeSensors, latestPoint?.timestamp, viewMode, unit) {
        CurrentDisplaySource.resolveCurrent(
            maxAgeMillis = Notify.glucosetimeout,
            preferredSensorId = sensorName.ifBlank { activeSensors.firstOrNull() }
        )
    }
}

/** Shared by the hero and navigation: measured history plus the canonical live tail. */
internal fun dashboardTrend(
    history: List<GlucosePoint>,
    latestPoint: GlucosePoint?,
    currentSnapshot: CurrentDisplaySource.Snapshot?,
    viewMode: Int,
    isMmol: Boolean
): TrendEngine.TrendResult {
    val useRaw = viewMode == 1 || viewMode == 3
    return when {
        history.isNotEmpty() -> {
            val nativeList = history.map { tk.glucodata.GlucosePoint(it.timestamp, it.value, it.rawValue) }
            val trendPoints = DisplayTrendSource.resolveTrendPoints(nativeList, currentSnapshot, null)
            TrendEngine.calculateTrend(trendPoints, useRaw = useRaw, isMmol = isMmol)
        }
        latestPoint != null -> TrendEngine.calculateTrend(
            listOf(tk.glucodata.GlucosePoint(latestPoint.timestamp, latestPoint.value, latestPoint.rawValue)),
            useRaw = useRaw,
            isMmol = isMmol
        )
        else -> TrendEngine.TrendResult(TrendEngine.TrendState.Unknown, 0f, 0f, 0f, 0f)
    }
}

@Composable
internal fun rememberDashboardTrend(
    history: List<GlucosePoint>,
    latestPoint: GlucosePoint?,
    currentSnapshot: CurrentDisplaySource.Snapshot?,
    viewMode: Int,
    isMmol: Boolean
): TrendEngine.TrendResult = remember(history, latestPoint, currentSnapshot, viewMode, isMmol) {
    dashboardTrend(history, latestPoint, currentSnapshot, viewMode, isMmol)
}
