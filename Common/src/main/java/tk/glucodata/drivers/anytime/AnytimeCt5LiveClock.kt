package tk.glucodata.drivers.anytime

/** Live-only evidence for clock-based CT5 reconnect recovery; history must not feed this. */
internal class AnytimeCt5LiveClock {
    private var lastLiveId = -1
    private var lastLiveAtMs = 0L
    private var lastLiveAdvancedWithGlucose = false

    @Synchronized
    fun observeLive(glucoseId: Int, receivedAtMs: Long, hasGlucose: Boolean, errorCode: Int) {
        lastLiveAdvancedWithGlucose = lastLiveId >= 0 && glucoseId > lastLiveId && hasGlucose && errorCode == 0
        lastLiveId = glucoseId
        lastLiveAtMs = receivedAtMs
    }

    @Synchronized
    fun reset() {
        lastLiveId = -1
        lastLiveAtMs = 0L
        lastLiveAdvancedWithGlucose = false
    }

    @Synchronized
    fun expectedCurrentId(
        nowMs: Long,
        intervalMs: Long,
        streamingSinceMs: Long,
        lastObservedId: Int,
        timelineStartMs: Long,
        profileEndNumber: Int,
    ): Int {
        if (intervalMs <= 0L) return -1
        val liveAgeMs = if (lastLiveAtMs > 0L) nowMs - lastLiveAtMs else Long.MAX_VALUE
        // The last frame is authoritative while the same session is alive, or
        // when ids repeat / carry no glucose. A CT5 at its final id must not
        // turn every elapsed cadence into another request for nonexistent ids.
        // But a healthy, advancing frame from an earlier GATT session says
        // nothing about the push missed while that connection was down.
        val recoveringAdvancingSession = lastLiveAdvancedWithGlucose &&
                streamingSinceMs > lastLiveAtMs && streamingSinceMs <= nowMs
        if (liveAgeMs in 0..(intervalMs * 2) && !recoveringAdvancingSession) return lastObservedId
        if (timelineStartMs <= 0L || nowMs < timelineStartMs) return -1
        val horizon = profileEndNumber.takeIf { it > 0 } ?: Int.MAX_VALUE
        return ((nowMs - timelineStartMs) / intervalMs)
            .coerceAtMost(maxOf(horizon, lastObservedId).toLong()).toInt()
    }
}
