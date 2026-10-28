package tk.glucodata.data

/**
 * How far back a native sync must reach for a Libre 1/2's History lane.
 *
 * History is exported on the stream reading of its own minute, which is ~16
 * minutes old when the history arrives -- and an NFC scan brings hours of it at
 * once -- so a sync of the last few minutes alone would never carry it into
 * Room. The window reaches back to the earliest history not yet stored.
 */
internal object LibreHistorySyncWindow {
    /** The stream reading History pairs with is up to a minute from it. */
    const val PAIRING_SLACK_SEC = 120L

    /** A Libre 1/2 hands over at most its FRAM history at once: 32 x 15 minutes. */
    const val SENSOR_HISTORY_SPAN_SEC = 8L * 60L * 60L

    /**
     * @param historyTimestamps the sensor's stored history now, epoch ms; empty for any other sensor.
     * @param known the same set at this sensor's last stored sync in this process, or null.
     * @param backfilled whether this sensor's whole session was re-exported once already.
     */
    fun startSec(
        requestedStartSec: Long,
        historyTimestamps: Set<Long>,
        known: Set<Long>?,
        backfilled: Boolean,
    ): Long {
        if (historyTimestamps.isEmpty()) return requestedStartSec
        // Rows written before History had a lane gain it, once per sensor. From
        // the session's start: older exports also wrote NFC trend minutes, which
        // can predate the earliest history, as Raw-only rows (see [rowsLeftBehind]).
        if (known == null && !backfilled) return 0L
        val earliestNewMs = when {
            known != null -> historyTimestamps.filterNot(known::contains).minOrNull()
            // A new process. Native storage only grows while the app runs, but
            // the last process may have died between storing history and
            // syncing it. Whatever arrived then is no older than the sensor's
            // history span before the newest history stored.
            else -> historyTimestamps.maxOrNull()?.let { it - SENSOR_HISTORY_SPAN_SEC * 1000L }
        } ?: return requestedStartSec
        return minOf(requestedStartSec, earliestNewMs / 1000L - PAIRING_SLACK_SEC).coerceAtLeast(0L)
    }

    /**
     * Stored history rows an export from [exportStartMs] (exclusive) no longer
     * writes. Older exports stored each history value as a row of its own; it
     * now rides on the stream reading of its minute id, which can fall in the
     * next clock minute. Where the history's own clock minute then gets no row
     * (the previous id's reading was missed), the bucket replace never rewrites
     * it, and the old row would keep drawing History on the Stream line.
     *
     * Older exports also wrote the uncompensated NFC trend minutes as Raw-only
     * rows. They are no longer exported, so a row with no Auto value in a
     * minute the export skips would show as History.
     */
    fun rowsLeftBehind(
        stored: Collection<HistoryReading>,
        exportedMinutes: Set<Long>,
        historyTimestamps: Set<Long>,
        exportStartMs: Long,
    ): List<HistoryReading> = stored.filter {
        it.timestamp > exportStartMs &&
            ReadingDisplay.minuteOf(it.timestamp) !in exportedMinutes &&
            (it.timestamp in historyTimestamps || !(it.value.isFinite() && it.value > 0f))
    }
}
