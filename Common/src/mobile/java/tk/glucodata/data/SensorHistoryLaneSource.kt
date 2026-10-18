package tk.glucodata.data

import tk.glucodata.Natives
import tk.glucodata.SensorIdentity
import tk.glucodata.ui.GlucosePoint

/**
 * A Libre 1/2's own 15-minute history, read from native storage for the
 * dashboard's History lane.
 *
 * Not Room: Room holds one line per sensor, and that line is the stream. The
 * history is a second series beside it, so it is read where it lives. A wipe of
 * native sensor data takes the lane with it while Room keeps the stream; for a
 * comparison lane that is the right way round.
 */
object SensorHistoryLaneSource {

    /** Points in mg/dL, ascending, newer than [startTimeMs]; empty for any sensor without a history lane. */
    fun load(sensorId: String, startTimeMs: Long): List<GlucosePoint> {
        val startSec = (startTimeMs / 1000L).coerceAtLeast(0L)
        val names = SensorIdentity.resolveNativeHistorySensorNames(sensorId).ifEmpty { listOf(sensorId) }
        for (name in names) {
            val packed = try {
                Natives.getSensorHistoryLane(name, startSec)
            } catch (_: Throwable) {
                null
            } ?: continue
            return parse(packed, sensorId)
        }
        return emptyList()
    }

    /** `[time_sec, mgdl*10, ...]` to points; drops anything that is not a plausible reading. */
    internal fun parse(packed: LongArray, sensorId: String): List<GlucosePoint> {
        val out = ArrayList<GlucosePoint>(packed.size / 2)
        var i = 0
        while (i + 1 < packed.size) {
            val timeMs = packed[i] * 1000L
            val mgdl = packed[i + 1] / 10f
            if (timeMs > 0L && mgdl > 0f) {
                out.add(GlucosePoint(value = mgdl, time = "", timestamp = timeMs, sensorSerial = sensorId))
            }
            i += 2
        }
        out.sortBy { it.timestamp }
        return out
    }
}
