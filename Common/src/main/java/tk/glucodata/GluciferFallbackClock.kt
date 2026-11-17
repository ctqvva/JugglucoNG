package tk.glucodata

/** Inactivity deadlines, reset by successful live pushes. All times are monotonic. */
internal class GluciferFallbackClock {
    private val activity = mutableMapOf<String, Long>()

    fun remaining(id: String, seconds: Int, nowMs: Long): Long {
        val last = activity.getOrPut(id) { nowMs }
        return (GluciferSendLimiter.interval(seconds) * 1000L - (nowMs - last)).coerceAtLeast(0)
    }

    fun reset(id: String, nowMs: Long) { activity[id] = nowMs }
    fun retain(ids: Set<String>) { activity.keys.retainAll(ids) }
}
