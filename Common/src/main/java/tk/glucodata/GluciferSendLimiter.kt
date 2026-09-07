package tk.glucodata

/** A shared request budget for live data, retries, tests, and history, using monotonic time. */
internal class GluciferSendLimiter {
    private val lastRequest = mutableMapOf<String, Long>()

    fun remaining(id: String, seconds: Int, nowMs: Long): Long =
        lastRequest[id]?.let { (interval(seconds) * 1000L - (nowMs - it)).coerceAtLeast(0) } ?: 0

    fun ready(id: String, seconds: Int, nowMs: Long): Boolean = remaining(id, seconds, nowMs) == 0L

    fun acquire(id: String, seconds: Int, nowMs: Long): Boolean {
        if (!ready(id, seconds, nowMs)) return false
        lastRequest[id] = nowMs
        return true
    }

    companion object {
        val intervals = listOf(1, 5, 10, 30, 60, 120, 360, 900, 1800, 3600, 21600, 43200, 86400)
        fun interval(seconds: Int): Int = seconds.takeIf { it in intervals } ?: 3600
    }
}
