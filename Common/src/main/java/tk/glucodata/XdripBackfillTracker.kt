package tk.glucodata

import java.util.TreeSet

/**
 * Remembers what the xDrip broadcast has already carried to AAPS, and picks the readings of a
 * history batch that fell into a gap and still need to go.
 *
 * The live path sends one reading a minute and only the newest, so after a disconnect the sensor's
 * backfill lands in the database but never reaches AAPS. xDrip sends every missed value under its
 * own timestamp; this is the bookkeeping that lets us do the same without re-sending what live
 * already delivered.
 *
 * A sensor is only known once a live send has been recorded (or a persisted watermark loaded), so
 * non-main sensors, which never live-send, are never backfilled, and enabling the broadcast does not
 * dump the existing history.
 */
class XdripBackfillTracker(
    private val windowMs: Long = DEFAULT_WINDOW_MS,
    private val minSpacingMs: Long = MIN_SPACING_MS,
) {
    data class Candidate(val timeMs: Long, val mgdl: Double, val rate: Float)

    data class Sensor(val startWatermarkMs: Long, val sensorGen: Int, val sensorStartMs: Long)

    private class State(var startWatermarkMs: Long, var sensorGen: Int, var sensorStartMs: Long) {
        val sent = TreeSet<Long>()
    }

    private val states = HashMap<String, State>()

    /** Restore a sensor from persisted state. Readings at or before [watermarkMs] are never backfilled. */
    @Synchronized
    fun restore(sensorId: String, watermarkMs: Long, sensorGen: Int, sensorStartMs: Long) {
        if (watermarkMs <= 0L || states.containsKey(sensorId)) return
        states[sensorId] = State(watermarkMs, sensorGen, sensorStartMs)
    }

    /** A live reading went out. The first one of a sensor fixes where backfill may start. */
    @Synchronized
    fun recordSent(sensorId: String, timeMs: Long, sensorGen: Int, sensorStartMs: Long) {
        if (timeMs <= 0L) return
        val state = states.getOrPut(sensorId) { State(timeMs, sensorGen, sensorStartMs) }
        state.sensorGen = sensorGen
        if (sensorStartMs > 0L) state.sensorStartMs = sensorStartMs
        state.sent.add(timeMs)
        prune(state, timeMs)
    }

    @Synchronized
    fun sensor(sensorId: String): Sensor? =
        states[sensorId]?.let { Sensor(it.startWatermarkMs, it.sensorGen, it.sensorStartMs) }

    /**
     * The readings of [batch] to send, oldest first. A reading is skipped when it is outside the
     * window, at or before the sensor's start watermark, or within [spacingMs] of something already
     * sent. The ones returned are recorded as sent.
     */
    @Synchronized
    fun pick(
        sensorId: String,
        batch: List<Candidate>,
        nowMs: Long,
        spacingMs: Long = minSpacingMs,
    ): List<Candidate> {
        val state = states[sensorId] ?: return emptyList()
        val spacing = maxOf(spacingMs, minSpacingMs)
        val lowerBound = maxOf(nowMs - windowMs, state.startWatermarkMs)
        val picked = ArrayList<Candidate>()
        for (candidate in batch.sortedBy(Candidate::timeMs)) {
            if (candidate.timeMs <= lowerBound || candidate.timeMs > nowMs) continue
            if (!(candidate.mgdl.isFinite() && candidate.mgdl > 0.0)) continue
            if (hasSentWithin(state.sent, candidate.timeMs, spacing)) continue
            state.sent.add(candidate.timeMs)
            picked.add(candidate)
        }
        return picked
    }

    private fun hasSentWithin(sent: TreeSet<Long>, timeMs: Long, spacingMs: Long): Boolean {
        val before = sent.floor(timeMs)
        if (before != null && timeMs - before < spacingMs) return true
        val after = sent.ceiling(timeMs)
        return after != null && after - timeMs < spacingMs
    }

    private fun prune(state: State, newestMs: Long) {
        val cutoff = newestMs - windowMs
        while (state.sent.isNotEmpty() && state.sent.first() < cutoff) state.sent.pollFirst()
    }

    companion object {
        const val DEFAULT_WINDOW_MS = 24L * 60L * 60L * 1000L
        const val MIN_SPACING_MS = 60_000L
    }
}
