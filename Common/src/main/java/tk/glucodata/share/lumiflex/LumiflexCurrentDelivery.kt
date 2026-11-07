package tk.glucodata.share.lumiflex

/** Commits the live cursor only after publication succeeds, so overlapping polls can retry. */
internal class LumiflexCurrentDelivery(private val publish: (LumiflexFollowReading, Float) -> Unit) {
    data class Snapshot(val reading: LumiflexFollowReading, val rate: Float)
    @Volatile var snapshot: Snapshot? = null
        private set

    fun deliver(rows: List<LumiflexFollowReading>, nowMs: Long) {
        val newest = rows.lastOrNull() ?: return
        if (newest.timestampMs <= (snapshot?.reading?.timestampMs ?: 0L)) return
        val previous = rows.dropLast(1).lastOrNull()
        val elapsed = newest.timestampMs - (previous?.timestampMs ?: 0L)
        val rate = if (previous != null && elapsed in 1..300_000L)
            (newest.mgdl - previous.mgdl) / (elapsed / 60_000f) else 0f
        // History older than ten minutes belongs in the chart, not live alerts.
        if (nowMs - newest.timestampMs <= 10 * 60_000L) publish(newest, rate)
        snapshot = Snapshot(newest, rate)
    }
}
