package tk.glucodata

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

object BatteryTrace {
    private const val TAG = "BatteryTrace"
    private val counters = ConcurrentHashMap<String, AtomicLong>()

    private class Timing {
        val count = AtomicLong(0L)
        val totalNs = AtomicLong(0L)
        val maxNs = AtomicLong(0L)
    }

    private val timings = ConcurrentHashMap<String, Timing>()

    /**
     * Times [block] and reports the aggregate periodically.
     *
     * For work that runs per reading, per sensor: logging every call floods the
     * trace and changes the timing it is trying to measure, while a single
     * one-shot timer says nothing about accumulated cost. This reports
     * count/total/avg/max every [logEvery] calls, and immediately for any single
     * call at or over [warnAboveMs] so a rare multi-second outlier is not hidden
     * inside an average.
     */
    @JvmStatic
    @JvmOverloads
    fun <T> time(
        key: String,
        logEvery: Long = 200L,
        warnAboveMs: Long = 250L,
        block: () -> T,
    ): T {
        val startNs = System.nanoTime()
        try {
            return block()
        } finally {
            val elapsedNs = System.nanoTime() - startNs
            val timing = timings.getOrPut(key) { Timing() }
            val count = timing.count.incrementAndGet()
            val total = timing.totalNs.addAndGet(elapsedNs)
            timing.maxNs.getAndUpdate { previous -> if (elapsedNs > previous) elapsedNs else previous }
            val elapsedMs = elapsedNs / 1_000_000L
            if (warnAboveMs > 0L && elapsedMs >= warnAboveMs) {
                Log.w(TAG, "$key SLOW ${elapsedMs}ms (call #$count)")
            } else if (logEvery > 0L && count % logEvery == 0L) {
                Log.i(
                    TAG,
                    "$key #$count total=${total / 1_000_000L}ms " +
                        "avg=${total / count / 1_000L}us max=${timing.maxNs.get() / 1_000L}us",
                )
            }
        }
    }

    /**
     * Same accounting as [time] for callers that already have an elapsed time, or
     * whose region is awkward to wrap in a lambda (a large `synchronized` block).
     */
    @JvmStatic
    @JvmOverloads
    fun record(key: String, elapsedNs: Long, logEvery: Long = 200L, warnAboveMs: Long = 250L) {
        val timing = timings.getOrPut(key) { Timing() }
        val count = timing.count.incrementAndGet()
        val total = timing.totalNs.addAndGet(elapsedNs)
        timing.maxNs.getAndUpdate { previous -> if (elapsedNs > previous) elapsedNs else previous }
        val elapsedMs = elapsedNs / 1_000_000L
        if (warnAboveMs > 0L && elapsedMs >= warnAboveMs) {
            Log.w(TAG, "$key SLOW ${elapsedMs}ms (call #$count)")
        } else if (logEvery > 0L && count % logEvery == 0L) {
            Log.i(
                TAG,
                "$key #$count total=${total / 1_000_000L}ms " +
                    "avg=${total / count / 1_000L}us max=${timing.maxNs.get() / 1_000L}us",
            )
        }
    }

    /** Dump every accumulated timer. Useful at a natural boundary, e.g. onPause. */
    @JvmStatic
    fun reportTimings() {
        timings.forEach { (key, timing) ->
            val count = timing.count.get()
            if (count == 0L) return@forEach
            val total = timing.totalNs.get()
            Log.i(
                TAG,
                "TIMING $key count=$count total=${total / 1_000_000L}ms " +
                    "avg=${total / count / 1_000L}us max=${timing.maxNs.get() / 1_000L}us",
            )
        }
    }

    /** Current value of a [bump] counter, or 0. Lets a caller measure a delta. */
    @JvmStatic
    fun count(key: String): Long = counters[key]?.get() ?: 0L

    @JvmStatic
    fun bump(key: String, logEvery: Long = 50L, detail: String? = null): Long {
        val count = counters.getOrPut(key) { AtomicLong(0L) }.incrementAndGet()
        if (count <= 3L || (logEvery > 0L && count % logEvery == 0L)) {
            val suffix = detail?.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""
            Log.i(TAG, "$key #$count$suffix")
        }
        return count
    }
}
