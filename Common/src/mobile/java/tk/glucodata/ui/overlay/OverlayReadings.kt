package tk.glucodata.ui.overlay

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import tk.glucodata.CurrentDisplaySource
import tk.glucodata.ui.PeerCurrentReading

internal data class OverlayReadings(
    val sensorId: String?,
    val snapshot: CurrentDisplaySource.Snapshot?,
    val peers: List<PeerCurrentReading>
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
