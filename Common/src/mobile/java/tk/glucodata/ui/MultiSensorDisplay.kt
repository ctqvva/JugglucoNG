package tk.glucodata.ui

import tk.glucodata.SensorIdentity
import tk.glucodata.SensorVisuals

/**
 * One peer sensor's display-ready history.
 *
 * [points] are sorted ascending by timestamp and every point's
 * [GlucosePoint.sensorSerial] is rewritten to the logical [sensorId], so
 * consumers can use plain string equality / map hits instead of
 * [SensorIdentity.matches] in per-point or per-frame code.
 */
data class PeerSensorSeries(
    val sensorId: String,
    val viewMode: Int,
    val colorArgb: Int,
    val points: List<GlucosePoint>
)

/**
 * Precomputed multi-sensor display data. Built once per history emission on a
 * background dispatcher (see DashboardViewModel); all dashboard consumers only
 * do O(1) lookups on it.
 */
data class MultiSensorDisplayData(
    /** Peer series in selection order (primary sensor excluded). */
    val series: List<PeerSensorSeries>,
    /**
     * Minute-bucket key -> latest point per sensor inside that bucket,
     * ordered by selection order.
     */
    val bucketLookup: Map<Long, List<GlucosePoint>>
) {
    val isEmpty: Boolean get() = series.isEmpty()

    fun seriesFor(sensorId: String?): PeerSensorSeries? {
        if (sensorId.isNullOrBlank()) return null
        series.firstOrNull { it.sensorId == sensorId }?.let { return it }
        return series.firstOrNull { SensorIdentity.matches(it.sensorId, sensorId) }
    }

    fun peersAt(timestamp: Long): List<GlucosePoint> =
        bucketLookup[MultiSensorDisplay.bucketKeyForTimestamp(timestamp)].orEmpty()

    companion object {
        val EMPTY = MultiSensorDisplayData(emptyList(), emptyMap())
    }
}

object MultiSensorDisplay {
    private const val BUCKET_MS = 60_000L

    /**
     * Builds display-ready peer data from raw Room history (already converted
     * to the display unit). Identity resolution runs once per distinct serial
     * encountered, never per point.
     */
    fun buildDisplayData(
        points: List<GlucosePoint>,
        selectedPeerIds: List<String>,
        sensorViewModes: Map<String, Int>,
        selectedSensorIdsForColors: List<String> = selectedPeerIds,
        fallbackViewMode: Int = 0
    ): MultiSensorDisplayData {
        if (points.isEmpty() || selectedPeerIds.isEmpty()) return MultiSensorDisplayData.EMPTY

        // serial -> logical peer id (or null when the serial is not a selected peer)
        val logicalBySerial = HashMap<String, String?>()
        fun logicalId(serial: String?): String? {
            val raw = serial?.takeIf { it.isNotBlank() } ?: return null
            return logicalBySerial.getOrPut(raw) {
                selectedPeerIds.firstOrNull { it == raw }
                    ?: selectedPeerIds.firstOrNull { SensorIdentity.matches(raw, it) }
            }
        }

        val grouped = LinkedHashMap<String, ArrayList<GlucosePoint>>()
        points.forEach { point ->
            val logical = logicalId(point.sensorSerial) ?: return@forEach
            grouped.getOrPut(logical) { ArrayList() }
                .add(if (point.sensorSerial == logical) point else point.copy(sensorSerial = logical))
        }
        if (grouped.isEmpty()) return MultiSensorDisplayData.EMPTY

        val colorBySensorId = SensorVisuals.distinctColorArgbMap(selectedSensorIdsForColors)
        val series = selectedPeerIds.mapNotNull { sensorId ->
            val sensorPoints = grouped[sensorId] ?: return@mapNotNull null
            sensorPoints.sortBy { it.timestamp }
            PeerSensorSeries(
                sensorId = sensorId,
                viewMode = sensorViewModes[sensorId]
                    ?: sensorViewModes.entries.firstOrNull { SensorIdentity.matches(it.key, sensorId) }?.value
                    ?: fallbackViewMode,
                colorArgb = colorBySensorId.entries.firstOrNull { (selected, _) ->
                    SensorIdentity.matches(selected, sensorId)
                }?.value ?: SensorVisuals.colorArgb(sensorId),
                points = sensorPoints
            )
        }

        // bucket -> (sensor handled implicitly by iteration order) latest point per sensor
        val bucketLookup = LinkedHashMap<Long, ArrayList<GlucosePoint>>()
        series.forEach { sensorSeries ->
            var lastBucket = Long.MIN_VALUE
            var lastIndexInBucket = -1
            sensorSeries.points.forEach { point ->
                val bucket = bucketKey(point.timestamp)
                val bucketPoints = bucketLookup.getOrPut(bucket) { ArrayList(series.size) }
                if (bucket == lastBucket && lastIndexInBucket in bucketPoints.indices) {
                    // ascending order: later point in the same bucket wins
                    bucketPoints[lastIndexInBucket] = point
                } else {
                    bucketPoints.add(point)
                    lastBucket = bucket
                    lastIndexInBucket = bucketPoints.lastIndex
                }
            }
        }

        return MultiSensorDisplayData(series, bucketLookup)
    }

    /**
     * Most recent [count] points at or before [untilTimestamp], newest first.
     * [pointsAscending] must be sorted ascending (as in [PeerSensorSeries.points]).
     */
    fun recentWindow(
        pointsAscending: List<GlucosePoint>,
        untilTimestamp: Long,
        count: Int
    ): List<GlucosePoint> {
        if (pointsAscending.isEmpty() || count <= 0) return emptyList()
        var lo = 0
        var hi = pointsAscending.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (pointsAscending[mid].timestamp <= untilTimestamp) lo = mid + 1 else hi = mid
        }
        // lo == first index with timestamp > untilTimestamp
        if (lo == 0) return emptyList()
        val from = (lo - count).coerceAtLeast(0)
        val window = ArrayList<GlucosePoint>(lo - from)
        for (i in lo - 1 downTo from) {
            window.add(pointsAscending[i])
        }
        return window
    }

    fun bucketKeyForTimestamp(timestamp: Long): Long = bucketKey(timestamp)

    private fun bucketKey(timestamp: Long): Long = timestamp / BUCKET_MS
}
