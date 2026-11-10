package tk.glucodata.data.journal

import kotlin.math.abs

/**
 * The temp basal in the status line AAPS broadcasts for xDrip
 * (`com.eveningoutpost.dexdrip.ExternalStatusline`). AAPS does not send temp basals as
 * treatments on its local broadcast, so this line is the only place a phone-local AAPS
 * reports them; xDrip draws its basal graph from the same line.
 *
 * The line opens with the running temp basal, formatted by AAPS's localised
 * `pump_base_basal_rate` ("%1$.2f U/h", "%1$.2f ед/ч", ...) or `formatPercent` ("%1$.0f%%"),
 * then IOB with its unit glued to the number ("2,09U"). No temp basal: it opens with IOB.
 * A stopped loop puts a line of its own first.
 */
object AapsStatusLine {

    sealed interface Observed {
        data class Rate(val unitsPerHour: Float) : Observed
        /** A percent pump: the absolute rate needs the AAPS profile, which Juggluco lacks. */
        data object Percent : Observed
        data object None : Observed
    }

    /** One run of a single rate, from its first sighting to its last. */
    data class Segment(val startMillis: Long, val unitsPerHour: Float, val lastSeenMillis: Long) {
        val durationMinutes: Int
            get() = ((lastSeenMillis - startMillis + MINUTE_MILLIS - 1) / MINUTE_MILLIS)
                .toInt()
                .coerceAtLeast(1)
    }

    /** [closed] is the previous segment with its final end; [current] is what runs now. */
    data class Step(val closed: Segment?, val current: Segment?)

    const val SOURCE_RECORD_PREFIX = "aaps:statusline:tbr:"

    fun isStatusLineRecord(sourceRecordId: String?): Boolean =
        sourceRecordId?.startsWith(SOURCE_RECORD_PREFIX) == true

    // AAPS loops every 5 minutes; past this nothing says the rate ran through the gap.
    private const val MAX_GAP_MILLIS = 15 * 60_000L
    private const val MINUTE_MILLIS = 60_000L
    private const val SAME_RATE_EPSILON = 0.005f

    // A number, whitespace, a unit with no digits in it, whitespace, then the IOB number.
    private val absoluteRate = Regex("""^(\d+(?:[.,]\d+)?)\s+\D+?\s+-?\d""")
    private val percentRate = Regex("""^\d+%(?:\s|$)""")

    fun parse(statusLine: String): Observed {
        for (line in statusLine.lineSequence().map(String::trim)) {
            absoluteRate.find(line)?.let { match ->
                val rate = match.groupValues[1].replace(',', '.').toFloatOrNull()
                if (rate != null && rate.isFinite() && rate >= 0f) return Observed.Rate(rate)
            }
            if (percentRate.containsMatchIn(line)) return Observed.Percent
            if (line.firstOrNull()?.isDigit() == true) return Observed.None
        }
        return Observed.None
    }

    fun advance(running: Segment?, observed: Observed, nowMillis: Long): Step {
        val continuous = running != null && nowMillis - running.lastSeenMillis in 0..MAX_GAP_MILLIS
        val rate = (observed as? Observed.Rate)?.unitsPerHour
        if (running != null && continuous && rate != null &&
            abs(running.unitsPerHour - rate) < SAME_RATE_EPSILON
        ) {
            return Step(closed = null, current = running.copy(lastSeenMillis = nowMillis))
        }
        val closed = running?.takeIf { continuous }?.copy(lastSeenMillis = nowMillis)
        val current = rate?.let { Segment(nowMillis, it, nowMillis) }
        return Step(closed, current)
    }
}
