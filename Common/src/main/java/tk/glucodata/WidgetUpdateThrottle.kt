package tk.glucodata

/** Spaces out widget redraws. A forced redraw always runs and restarts the interval. */
internal class WidgetUpdateThrottle(private val minIntervalMs: Long) {
    private var lastUpdateMs: Long? = null

    @Synchronized
    fun tryAcquire(nowMs: Long, force: Boolean): Boolean {
        val last = lastUpdateMs
        if (!force && last != null && nowMs - last < minIntervalMs) return false
        lastUpdateMs = nowMs
        return true
    }
}
