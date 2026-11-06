package tk.glucodata.alerts

/** Observed display values only. A recent rise can explain a descent back to its baseline. */
internal class ForecastLowReturnToBaselineState {
    private data class Sample(val timeMs: Long, val mgdl: Float)
    private data class Context(val sensorId: String?, val generation: Int, val viewMode: Int, val isMmol: Boolean)
    private val history = ArrayDeque<Sample>()
    private var context: Context? = null
    private var lastReadingTimeMs = 0L

    fun clear() {
        history.clear()
    }

    fun observe(
        readingTimeMs: Long, value: Float, sensorId: String?, generation: Int,
        viewMode: Int, isMmol: Boolean
    ) {
        val nextContext = Context(sensorId, generation, viewMode, isMmol)
        if (context != nextContext) {
            clear()
            context = nextContext
        }
        val mgdl = value * if (isMmol) 18.0182f else 1f
        if (readingTimeMs <= 0 || !mgdl.isFinite() || mgdl <= 0f || sensorId.isNullOrBlank()) {
            clear()
            return
        }
        if (readingTimeMs < lastReadingTimeMs) {
            clear()
            return
        }
        if (readingTimeMs == lastReadingTimeMs) return
        if (readingTimeMs - lastReadingTimeMs > 6 * MINUTE_MS) clear()
        lastReadingTimeMs = readingTimeMs
        history.addLast(Sample(readingTimeMs, mgdl))
        while (history.isNotEmpty() &&
            (readingTimeMs - history.first().timeMs > 45 * MINUTE_MS || history.size > 180)
        ) history.removeFirst()
    }

    /**
     * Require an observed, quiet five-minute baseline followed by a rise of at
     * least 15 mg/dL over 3..15 minutes. The highest observed peak must be recent;
     * it cannot slide forward along the falling edge to extend the 15-minute bound.
     * Missing history fails open. No history reload after calibration or restart.
     */
    fun recentRiseBaselineMgdl(): Float? {
        if (history.size < 5) return null
        val points = history.toList()
        val peakIndex = points.indices.maxByOrNull { points[it].mgdl } ?: return null
        val peak = points[peakIndex]
        val latest = points.last()
        if (peakIndex >= points.lastIndex || latest.timeMs - peak.timeMs > 15 * MINUTE_MS ||
            latest.mgdl > peak.mgdl - 3f
        ) return null
        for (endIndex in peakIndex - 1 downTo 0) {
            val end = points[endIndex]
            val riseMs = peak.timeMs - end.timeMs
            if (riseMs !in 3 * MINUTE_MS..15 * MINUTE_MS) continue
            val baseline = points.take(endIndex + 1).takeLastWhile { end.timeMs - it.timeMs <= 6 * MINUTE_MS }
            if (baseline.size < 2 || end.timeMs - baseline.first().timeMs < 5 * MINUTE_MS - 30_000L) continue
            val low = baseline.minOf { it.mgdl }
            val high = baseline.maxOf { it.mgdl }
            if (high - low > 6f) continue
            val level = baseline.map { it.mgdl }.sorted()[baseline.size / 2]
            if (peak.mgdl - level < 15f) continue
            // An already-developing decline with a later bounce is not a return
            // from a rise. Do not borrow a baseline across that intervening dip.
            if (points.subList(endIndex, peakIndex).any { it.mgdl < level - 3f }) continue
            return level
        }
        return null
    }

    private companion object {
        const val MINUTE_MS = 60_000L
    }
}
