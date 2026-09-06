package tk.glucodata

/** Live events can bypass background pacing; all requests retain a one-second ceiling. */
internal class GluciferDeliveryBudget {
    private val requests = GluciferSendLimiter()
    private val background = GluciferSendLimiter()

    fun remaining(id: String, seconds: Int, bypass: Boolean, live: Boolean, nowMs: Long): Long =
        maxOf(requests.remaining(id, 1, nowMs),
            if (live && bypass) 0 else background.remaining(id, seconds, nowMs))

    fun acquire(id: String, seconds: Int, bypass: Boolean, live: Boolean, nowMs: Long): Boolean {
        if (remaining(id, seconds, bypass, live, nowMs) != 0L) return false
        requests.acquire(id, 1, nowMs)
        if (!live || !bypass) background.acquire(id, seconds, nowMs)
        return true
    }
}
