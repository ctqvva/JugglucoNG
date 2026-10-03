package tk.glucodata.ui.screens

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.MaterialTheme
import tk.glucodata.GlucosePoint
import tk.glucodata.ui.WearGlucoseStore
import tk.glucodata.ui.components.TrendArrowCanvas

internal data class WearReadingPeer(
    val series: WearGlucoseStore.PeerSeries,
    val point: GlucosePoint,
    val velocity: Float,
)

/** The phone's row rule: latest actual reading in the same minute, never carry forward. */
internal fun readingPeers(
    rows: List<GlucosePoint>,
    peers: List<WearGlucoseStore.PeerSeries>,
    isMmol: Boolean,
): Map<Long, List<WearReadingPeer>> {
    val result = rows.associate { it.timestamp to mutableListOf<WearReadingPeer>() }
    peers.forEach { peer ->
        val byMinute = peer.points.associateBy { it.timestamp / 60_000L }
        val matches = rows.mapNotNull { byMinute[it.timestamp / 60_000L] }
        val velocities = rowVelocities(peer.points, matches, peer.isRawMode, isMmol)
        rows.forEach { row ->
            byMinute[row.timestamp / 60_000L]?.let { point ->
                result.getValue(row.timestamp).add(WearReadingPeer(peer, point, velocities[point.timestamp] ?: 0f))
            }
        }
    }
    return result
}

/** Match the phone: one inline value/arrow per sensor, with a subtle identity tint. */
@Composable
internal fun ReadingValues(
    point: GlucosePoint,
    viewMode: Int,
    isMmol: Boolean,
    velocity: Float,
    peers: List<WearReadingPeer>,
    primaryColorArgb: Int? = null,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SensorValue(point, viewMode, isMmol, velocity, primaryColorArgb, false)
        peers.forEach { peer ->
            SensorValue(peer.point, peer.series.viewMode, isMmol, peer.velocity, peer.series.colorArgb, true,
                Modifier.semantics { contentDescription = peer.series.sensorId })
        }
    }
}

@Composable
private fun SensorValue(
    point: GlucosePoint,
    viewMode: Int,
    isMmol: Boolean,
    velocity: Float,
    identityArgb: Int?,
    isPeer: Boolean,
    modifier: Modifier = Modifier,
) {
    val neutral = MaterialTheme.colorScheme.onSurface
    val valueColor = identityArgb?.let {
        androidx.compose.ui.graphics.lerp(
            neutral, Color(it),
            if (isPeer) tk.glucodata.SensorVisuals.PEER_TEXT_BLEND
            else tk.glucodata.SensorVisuals.PRIMARY_TEXT_BLEND,
        )
    } ?: tk.glucodata.ui.WearGlucoseColors.valueColor(primaryLaneValue(point, viewMode), isMmol, neutral)
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        WearGlucoseValue(
            point = point,
            isMmol = isMmol,
            viewMode = viewMode,
            style = if (isPeer) readingValueStyle(
                viewMode, MaterialTheme.typography.bodySmall, MaterialTheme.typography.labelSmall,
            ) else readingValueStyle(viewMode),
            primaryColor = valueColor,
        )
        TrendArrowCanvas(
            velocity = velocity,
            pulseKey = null,
            modifier = Modifier.padding(start = 4.dp).size(12.dp),
            color = valueColor.copy(alpha = 0.7f),
        )
    }
}
