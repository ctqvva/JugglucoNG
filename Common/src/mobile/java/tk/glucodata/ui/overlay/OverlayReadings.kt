package tk.glucodata.ui.overlay

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import tk.glucodata.CurrentDisplaySource
import tk.glucodata.ui.PeerCurrentReading

internal data class OverlayReadings(
    val sensorId: String?,
    val snapshot: CurrentDisplaySource.Snapshot?,
    val peers: List<PeerCurrentReading>,
    val deltaText: String = "",
    val deltaIntervalMinutes: Int = tk.glucodata.GlucoseDelta.DEFAULT_INTERVAL_MINUTES
)

/** Commit a complete main/peer frame. A refresh never publishes a temporary empty peer list. */
@Composable
internal fun rememberOverlayReadings(
    sensorId: String?,
    showSecondary: Boolean,
    revision: Any?,
    resolve: suspend (String?, Boolean) -> OverlayReadings
): State<OverlayReadings> = produceState(
    initialValue = OverlayReadings(sensorId, null, emptyList()),
    key1 = sensorId,
    key2 = showSecondary,
    key3 = revision
) {
    value = resolve(sensorId, showSecondary)
}

/** The number is drawn from snapshot.displayValues, even when history has newer bucket rows. */
internal fun overlayDeltaText(
    readings: OverlayReadings,
    enabled: Boolean,
    requestedSensorId: String?,
    intervalMinutes: Int,
    nowMillis: Long,
    maxAgeMillis: Long = tk.glucodata.Notify.glucosetimeout
): String {
    val snapshot = readings.snapshot ?: return ""
    if (!enabled || readings.deltaIntervalMinutes != intervalMinutes || requestedSensorId.isNullOrBlank() ||
        !tk.glucodata.SensorIdentity.matches(snapshot.sensorId, requestedSensorId) ||
        snapshot.timeMillis <= 0L || snapshot.timeMillis > nowMillis || nowMillis - snapshot.timeMillis > maxAgeMillis
    ) return ""
    return readings.deltaText
}
