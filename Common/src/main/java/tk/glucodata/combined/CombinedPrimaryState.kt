package tk.glucodata.combined

import tk.glucodata.alerts.DualSensorConsensus
import tk.glucodata.alerts.SensorGroupConsensus

/** Decides the one primary output; physical source records are never modified. */
internal class CombinedPrimaryState(private val inputs: List<String>) {
    data class Output(val timeMs: Long, val mgdl: Float, val rate: Float,
        val blended: Boolean, val fallbackSensorId: String? = null)
    private val engine = SensorGroupConsensus()
    var result = DualSensorConsensus.Result()
        private set
    var latest: Output? = null
        private set

    fun resetLearning() { engine.reset() }

    fun restore(output: Output) {
        if (latest == null) latest = output
    }

    fun update(readings: List<DualSensorConsensus.Reading>, nowMs: Long, percent: Int, duration: Int): Output? {
        val components = inputs.mapNotNull { id -> readings.firstOrNull { it.sensorId == id } }
        result = engine.evaluate(components.takeIf { it.size == inputs.size }.orEmpty(), nowMs, percent, duration)
        if (result.status == DualSensorConsensus.Status.WAITING_FOR_PEER && latest != null) return null
        val estimate = result.estimateMgdl
        val fallback = components.firstOrNull {
            it.timeMs > 0L && it.timeMs <= nowMs && nowMs - it.timeMs <= DualSensorConsensus.MAX_AGE_MS &&
                it.mgdl.isFinite() && it.mgdl in 20f..600f
        }
        val time = if (estimate != null) components.minOf { it.timeMs } else fallback?.timeMs ?: return null
        val value = estimate ?: fallback!!.mgdl
        val previous = latest
        if (time <= (previous?.timeMs ?: 0L)) return null
        val rate = if (previous != null && time - previous.timeMs <= DualSensorConsensus.MAX_AGE_MS &&
            previous.blended == (estimate != null) && previous.fallbackSensorId == (if (estimate == null) fallback?.sensorId else null))
            (value - previous.mgdl) / ((time - previous.timeMs) / 60_000f) else Float.NaN
        return Output(time, value, rate, estimate != null, if (estimate == null) fallback?.sensorId else null)
            .also { latest = it }
    }
}
