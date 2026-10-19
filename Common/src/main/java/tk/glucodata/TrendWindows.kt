package tk.glucodata

/**
 * Picks the history window each reading's trend arrow is measured over.
 *
 * A list of readings each wants the ~35 minutes leading up to it. Filtering the
 * whole horizon per row walks the history once for every row, which for a
 * screen of 48 rows against a day of one-minute readings is tens of thousands
 * of comparisons on the main thread every time the snapshot changes. Rows and
 * history are both ordered, so one pass over each is enough.
 */
object TrendWindows {

    /** The context an arrow needs; matches what the display surfaces use. */
    const val DEFAULT_WINDOW_MS = 35L * 60L * 1000L

    /**
     * Velocity per row timestamp, measured over [windowMs] of [historyAscending]
     * ending at that row. Rows with fewer than two readings behind them get 0,
     * which reads as flat rather than as a direction invented from one point.
     *
     * @param historyAscending oldest first.
     */
    fun velocities(
        historyAscending: List<GlucosePoint>,
        rows: List<GlucosePoint>,
        useRaw: Boolean,
        isMmol: Boolean,
        windowMs: Long = DEFAULT_WINDOW_MS,
    ): Map<Long, Float> {
        if (rows.isEmpty() || historyAscending.isEmpty()) return emptyMap()
        val ordered = rows.sortedBy { it.timestamp }
        val result = HashMap<Long, Float>(ordered.size)
        var start = 0
        var end = 0
        ordered.forEach { row ->
            val from = row.timestamp - windowMs
            while (start < historyAscending.size && historyAscending[start].timestamp < from) start++
            if (end < start) end = start
            while (end < historyAscending.size && historyAscending[end].timestamp <= row.timestamp) end++
            val velocity = if (end - start >= 2) {
                TrendAccess.calculateVelocity(historyAscending.subList(start, end), useRaw, isMmol)
                    .takeIf { it.isFinite() } ?: 0f
            } else {
                0f
            }
            result[row.timestamp] = velocity
        }
        return result
    }

    /**
     * The window one reading's arrow is measured over. Exposed so the sweep
     * above can be checked against the obvious per-row implementation.
     */
    fun windowFor(
        historyAscending: List<GlucosePoint>,
        atTimestamp: Long,
        windowMs: Long = DEFAULT_WINDOW_MS,
    ): List<GlucosePoint> =
        historyAscending.filter { it.timestamp in (atTimestamp - windowMs)..atTimestamp }
}
