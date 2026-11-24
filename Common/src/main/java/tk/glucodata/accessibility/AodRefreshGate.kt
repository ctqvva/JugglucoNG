package tk.glucodata.accessibility

/**
 * Coalesces overlay refreshes: the first request runs at once, requests within
 * [minIntervalMs] of the last run share one trailing run. Main thread only; the caller owns
 * the clock and the queue.
 */
internal class AodRefreshGate(private val minIntervalMs: Long) {
    private var queued = false
    private var lastRunAt: Long? = null

    /** Milliseconds until the refresh to queue, or null when one already is. */
    fun delayForRequest(now: Long): Long? {
        if (queued) return null
        queued = true
        val sinceLast = now - (lastRunAt ?: return 0L)
        return (minIntervalMs - sinceLast).coerceIn(0L, minIntervalMs)
    }

    fun onRefresh(now: Long) {
        queued = false
        lastRunAt = now
    }

    /** The queued refresh was dropped with the overlay. */
    fun cancel() {
        queued = false
        lastRunAt = null
    }
}
