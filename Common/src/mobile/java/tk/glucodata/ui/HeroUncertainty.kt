package tk.glucodata.ui

import tk.glucodata.SensorIdentity
import tk.glucodata.ui.util.GlucoseFormatter

/** A range belongs to a sensor and reading, never to whichever history flow arrived last. */
internal fun heroUncertaintyText(
    history: List<GlucosePoint>,
    latestPoint: GlucosePoint?,
    sensorId: String,
    snapshotSensorId: String?,
    readingMillis: Long,
    viewMode: Int,
    isMmol: Boolean,
    isFresh: Boolean,
    enabled: Boolean = true
): String? {
    if (!enabled || !isFresh || viewMode == 1 || viewMode == 3 || sensorId.isBlank()) return null
    if (snapshotSensorId != null && !SensorIdentity.matches(snapshotSensorId, sensorId)) return null
    fun belongsToReading(point: GlucosePoint): Boolean =
        kotlin.math.abs(point.timestamp - readingMillis) <= 60_000L &&
            !point.sensorSerial.isNullOrBlank() && SensorIdentity.matches(point.sensorSerial, sensorId)
    // The current point is already resolved by the caller. Avoid walking days of
    // history on the main thread for the normal live-reading path.
    val point = latestPoint?.takeIf(::belongsToReading) ?: history.asSequence()
        .filter(::belongsToReading).maxByOrNull { it.timestamp } ?: return null
    val range = point.uncertainty?.takeIf { it.isUsable } ?: return null
    return "${GlucoseFormatter.format(range.lower, isMmol)}–${GlucoseFormatter.format(range.upper, isMmol)}"
}
