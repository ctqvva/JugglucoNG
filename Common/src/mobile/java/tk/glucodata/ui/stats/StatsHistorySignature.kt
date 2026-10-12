package tk.glucodata.ui.stats

import tk.glucodata.ui.GlucosePoint

internal data class StatsHistorySignature(
    val size: Int,
    val firstTimestamp: Long,
    val lastTimestamp: Long,
    val contentHash: Long,
)

/** Both flow deduplication and projection caches must see changes to any resolved minute. */
internal fun statsHistorySignature(points: List<GlucosePoint>): StatsHistorySignature {
    var hash = 1125899906842597L
    for (point in points) {
        hash = 31L * hash + point.timestamp
        hash = 31L * hash + point.value.toRawBits()
        hash = 31L * hash + point.rawValue.toRawBits()
        hash = 31L * hash + (point.sensorSerial?.hashCode() ?: 0)
        hash = 31L * hash + (point.sealedDisplayValue?.toRawBits() ?: 0)
        hash = 31L * hash + (point.sealedDisplayViewMode ?: -1)
    }
    return StatsHistorySignature(
        size = points.size,
        firstTimestamp = points.firstOrNull()?.timestamp ?: 0L,
        lastTimestamp = points.lastOrNull()?.timestamp ?: 0L,
        contentHash = hash,
    )
}
