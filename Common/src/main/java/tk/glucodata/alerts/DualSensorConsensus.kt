package tk.glucodata.alerts

import kotlin.math.abs

/** Experimental comparison only. Values are calibrated display lanes converted to mg/dL. */
internal class DualSensorConsensus {
    data class Reading(val sensorId: String, val timeMs: Long, val mgdl: Float, val lane: Int = 0)
    enum class Status { UNAVAILABLE, LEARNING, COMPARABLE, DISAGREEING }
    data class Result(
        val status: Status = Status.UNAVAILABLE,
        val first: Reading? = null,
        val second: Reading? = null,
        val differencePercent: Float? = null,
        val estimateMgdl: Float? = null,
        val warningDue: Boolean = false
    )

    private data class PairSample(val first: Reading, val second: Reading) {
        val timeMs get() = minOf(first.timeMs, second.timeMs)
    }
    private val history = ArrayDeque<PairSample>()
    private var disagreementSinceMs: Long? = null
    private var pairKey: List<Pair<String, Int>>? = null

    fun reset() {
        history.clear()
        disagreementSinceMs = null
        pairKey = null
    }

    fun evaluate(readings: List<Reading>, nowMs: Long, percent: Int, durationMinutes: Int): Result {
        // Exactly two distinct logical sensors: the adapter canonicalizes identities first.
        val sorted = readings.sortedBy { it.sensorId }
        if (sorted.size != 2 || sorted[0].sensorId == sorted[1].sensorId || sorted.any {
                !it.mgdl.isFinite() || it.mgdl !in 20f..600f || it.timeMs <= 0L ||
                    it.timeMs > nowMs || nowMs - it.timeMs > MAX_AGE_MS
            } || abs(sorted[0].timeMs - sorted[1].timeMs) > MAX_SKEW_MS) {
            reset()
            return Result()
        }
        val a = sorted[0]
        val b = sorted[1]
        val key = sorted.map { it.sensorId to it.lane }
        if (pairKey != key) reset()
        pairKey = key
        var previous = history.lastOrNull()
        // Recalibration/reprocessing of a sample is not a new observation.
        if (previous != null && (a.timeMs < previous.first.timeMs || b.timeMs < previous.second.timeMs ||
                (a.timeMs == previous.first.timeMs && a.mgdl != previous.first.mgdl) ||
                (b.timeMs == previous.second.timeMs && b.mgdl != previous.second.mgdl))) {
            reset()
            pairKey = key
            previous = null
        }
        if (previous != null && minOf(a.timeMs, b.timeMs) - previous.timeMs > MAX_AGE_MS) {
            reset()
            pairKey = key
            previous = null
        }
        // Symmetric percentage; selecting the other primary cannot change the verdict.
        val difference = 200f * abs(a.mgdl - b.mgdl) / (a.mgdl + b.mgdl)
        val disagreeing = difference > sanitizeDisagreementPercent(percent)
        val newPair = previous == null || (a.timeMs > previous.first.timeMs && b.timeMs > previous.second.timeMs)
        if (!disagreeing) disagreementSinceMs = null
        if (newPair) {
            val sample = PairSample(a, b)
            if (disagreeing && disagreementSinceMs == null) disagreementSinceMs = sample.timeMs
            history.addLast(sample)
            while (history.size > 12 || (history.size > 1 && sample.timeMs - history.first().timeMs > 20 * 60_000L)) {
                history.removeFirst()
            }
        }
        val time = minOf(a.timeMs, b.timeMs)
        val learned = history.size >= 3 && time - history.first().timeMs >= 2 * 60_000L
        val estimate = if (learned && !disagreeing) {
            // Detrended short-term variability is a stability heuristic, never an accuracy score.
            // Cap either contribution at 75%; smooth shared bias cannot be detected by two CGMs.
            val noiseA = noise { it.first }
            val noiseB = noise { it.second }
            val weightA = (noiseB / (noiseA + noiseB)).coerceIn(0.25f, 0.75f)
            a.mgdl * weightA + b.mgdl * (1f - weightA)
        } else null
        return Result(
            status = if (disagreeing) Status.DISAGREEING else if (learned) Status.COMPARABLE else Status.LEARNING,
            first = a, second = b, differencePercent = difference, estimateMgdl = estimate,
            // Time passing on a timer never turns one mismatching sample into persistent evidence.
            warningDue = disagreeing && disagreementSinceMs?.let {
                (history.lastOrNull()?.timeMs ?: time) - it >= durationMinutes.coerceIn(1, 120) * 60_000L
            } == true
        )
    }

    private fun noise(reading: (PairSample) -> Reading): Float {
        val samples = history.map(reading)
        val residuals = (2 until samples.size).map { i ->
            val a = samples[i - 2]
            val b = samples[i - 1]
            val c = samples[i]
            val priorRate = (b.mgdl - a.mgdl) / (b.timeMs - a.timeMs).toFloat()
            abs(c.mgdl - b.mgdl - priorRate * (c.timeMs - b.timeMs))
        }.sorted()
        val median = residuals[residuals.size / 2].coerceAtLeast(5f)
        return median * median
    }

    companion object {
        // Accommodate CGMs that publish every five minutes without bridging a missed cycle.
        const val MAX_AGE_MS = 6 * 60_000L
        const val MAX_SKEW_MS = 60_000L
    }
}

fun sanitizeDisagreementPercent(value: Int): Int = value.coerceIn(10, 50)
