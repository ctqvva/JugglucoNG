package tk.glucodata

import kotlin.math.abs
import kotlin.math.min

/**
 * Decides whether a reading is announced as a periodic update. Pure: the caller
 * supplies the clocks, so restart, reboot and clock-change cases are testable.
 *
 * Sending is driven by readings, never by a timer: a reading is sent when it is new,
 * fresh and the interval since the last send has passed. Nothing is replayed to fill
 * a missed interval and no value is repeated to fill a long sensor cadence.
 */
object PeriodicGlucoseNotificationPolicy {
    enum class Decision { SEND, DISABLED, NO_READING, STALE, ALREADY_SENT, TOO_SOON }

    /** What was last forwarded. Persisted, so it survives process restarts. */
    data class Sent(
        val readingMillis: Long,
        val wallMillis: Long,
        val elapsedMillis: Long,
    ) {
        companion object {
            @JvmField
            val NONE = Sent(0L, 0L, 0L)
        }
    }

    /** Readings arrive with jitter; a 1-minute sensor's fifth reading can land at 4:58. */
    private const val MAX_SLACK_MS = 30_000L

    /** A stored time this far ahead of the wall clock means the clock went back. */
    private const val CLOCK_BACK_TOLERANCE_MS = 2 * 60_000L

    /** Wall-minus-elapsed drift allowed within one boot (NTP slews, not clock jumps). */
    private const val SAME_BOOT_TOLERANCE_MS = 60_000L

    @JvmStatic
    fun intervalMillis(intervalMinutes: Int): Long =
        if (intervalMinutes <= 1) 0L else intervalMinutes * 60_000L

    @JvmStatic
    fun decide(
        enabled: Boolean,
        readingMillis: Long,
        baselineReadingMillis: Long,
        last: Sent,
        nowWallMillis: Long,
        nowElapsedMillis: Long,
        intervalMinutes: Int,
        freshnessMillis: Long,
        sendCurrentOnce: Boolean = false,
    ): Decision {
        if (!enabled) return Decision.DISABLED
        if (readingMillis <= 0L) return Decision.NO_READING
        if (!NotificationRefreshPolicy.isFresh(readingMillis, nowWallMillis, freshnessMillis)) {
            return Decision.STALE
        }
        // The user just switched the feature on: show the current reading once, even if
        // it went out before the switch was turned off. The persisted record is kept, so
        // restarts still never replay.
        if (sendCurrentOnce) return Decision.SEND
        // The reading last sent stays sent, even when the clock went back past it and
        // the markers below stop counting: a refresh must not forward it again.
        if (sameMeasurement(last.readingMillis, readingMillis)) return Decision.ALREADY_SENT
        // Markers from before a backwards clock change would hold back every new reading
        // until the clock caught up again; they no longer describe anything.
        val lastReading = trusted(last.readingMillis, nowWallMillis)
        val baseline = trusted(baselineReadingMillis, nowWallMillis)
        if (readingMillis <= maxOf(lastReading, baseline)) return Decision.ALREADY_SENT
        if (lastReading == 0L) return Decision.SEND
        val interval = intervalMillis(intervalMinutes)
        if (interval == 0L) return Decision.SEND
        val since = sinceLastSend(last, nowWallMillis, nowElapsedMillis) ?: return Decision.SEND
        return if (since >= interval - min(MAX_SLACK_MS, interval / 4)) Decision.SEND else Decision.TOO_SOON
    }

    /**
     * Whether an alarm notification and a snapshot describe the same measurement.
     * Readings are at least a minute apart; the two paths can differ only in sub-second
     * precision (a native time in whole seconds), so compare whole seconds, not a window
     * that would also catch the next one-minute reading.
     */
    @JvmStatic
    fun sameMeasurement(alarmReadingMillis: Long, readingMillis: Long): Boolean =
        alarmReadingMillis > 0L && readingMillis > 0L &&
            Math.floorDiv(CurrentGlucoseSource.normalizeTimeMillis(alarmReadingMillis), 1000L) ==
            Math.floorDiv(CurrentGlucoseSource.normalizeTimeMillis(readingMillis), 1000L)

    private fun trusted(millis: Long, nowWallMillis: Long): Long =
        if (millis > nowWallMillis + CLOCK_BACK_TOLERANCE_MS) 0L else millis

    /**
     * Elapsed realtime within one boot, so wall-clock changes cannot stretch or shrink
     * the interval. Across a reboot elapsed restarts from zero; the wall clock is the
     * only common reference then. Null when even that went backwards: send.
     */
    private fun sinceLastSend(last: Sent, nowWallMillis: Long, nowElapsedMillis: Long): Long? {
        if (last.wallMillis <= 0L) return null
        val sameBoot = last.elapsedMillis in 1..nowElapsedMillis &&
            abs((nowWallMillis - nowElapsedMillis) - (last.wallMillis - last.elapsedMillis)) <= SAME_BOOT_TOLERANCE_MS
        val since = if (sameBoot) nowElapsedMillis - last.elapsedMillis else nowWallMillis - last.wallMillis
        return if (since < 0L) null else since
    }
}
