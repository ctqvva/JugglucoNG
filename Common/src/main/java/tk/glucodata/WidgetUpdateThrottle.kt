package tk.glucodata

/**
 * Spaces out ordinary widget redraws. A forced redraw always runs and leaves the interval
 * alone: a reading right after it must still draw, or the widget keeps the forced snapshot.
 */
internal class WidgetUpdateThrottle(private val minIntervalMs: Long) {
    private var lastUpdateMs: Long? = null

    @Synchronized
    fun tryAcquire(nowMs: Long, force: Boolean): Boolean {
        if (force) return true
        val last = lastUpdateMs
        if (last != null && nowMs - last < minIntervalMs) return false
        lastUpdateMs = nowMs
        return true
    }
}
