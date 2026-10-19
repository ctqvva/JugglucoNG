package tk.glucodata.drivers.mq

/** Recover a cloud session created after the transmitter's counter already started. */
internal object MQCloudHistoryTiming {
    data class Anchor(val packetIndex: Int, val receivedAtMs: Long)
    data class Prepared(
        val history: List<MQBootstrapHistoryPoint>,
        val deferred: Boolean = false,
        val estimated: Boolean = false,
    )

    fun prepare(
        history: List<MQBootstrapHistoryPoint>,
        anchor: Anchor?,
        nowMs: Long,
        intervalMinutes: Int,
    ): Prepared {
        if (history.none { it.timestampMs > nowMs + 60_000L }) return Prepared(history)
        if (anchor == null) return Prepared(emptyList(), deferred = true)
        val intervalMs = intervalMinutes * 60_000L
        // Never map a different/newer packet cycle into this live session, or infer a
        // timeline from one point. Keep rejected input out of the history cursor too.
        if (intervalMs <= 0 || anchor.packetIndex !in 1 until 15000 ||
            anchor.receivedAtMs <= 0 || anchor.receivedAtMs > nowMs + 60_000L ||
            history.map { it.packetIndex }.distinct().size < 2 ||
            history.any { it.packetIndex !in 1..anchor.packetIndex }
        ) return Prepared(emptyList())
        val origins = history.map { it.timestampMs - it.packetIndex * intervalMs }
        // The observed server bug assigns rd = cloud start + packet * interval.
        // Do not reinterpret arbitrary future dates or a batch spanning multiple cycles.
        if (origins.max() - origins.min() > 60_000L) return Prepared(emptyList())
        val liveOrigin = anchor.receivedAtMs - anchor.packetIndex * intervalMs
        if (liveOrigin <= 0 || origins.min() - liveOrigin <= 2 * intervalMs) {
            return Prepared(emptyList())
        }
        return Prepared(history.map {
            it.copy(timestampMs = liveOrigin + it.packetIndex * intervalMs)
        }, estimated = true)
    }
}
