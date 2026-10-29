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
    isFresh: Boolean
): String? {
    if (!isFresh || viewMode == 1 || viewMode == 3 || sensorId.isBlank()) return null
    if (snapshotSensorId != null && !SensorIdentity.matches(snapshotSensorId, sensorId)) return null
    val point = (history.asSequence() + listOfNotNull(latestPoint).asSequence())
        .filter { !it.sensorSerial.isNullOrBlank() && SensorIdentity.matches(it.sensorSerial, sensorId) }
        .filter { kotlin.math.abs(it.timestamp - readingMillis) <= 60_000L }
        .maxByOrNull { it.timestamp } ?: return null
    val range = point.uncertainty?.takeIf { it.isUsable } ?: return null
    return "${GlucoseFormatter.format(range.lower, isMmol)}–${GlucoseFormatter.format(range.upper, isMmol)}"
}
