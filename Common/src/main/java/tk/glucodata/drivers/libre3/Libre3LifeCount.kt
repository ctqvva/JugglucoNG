// Libre 3 life count to wall time.
//
// The sensor numbers its minutes from activation and sends minute L when it ends, at
// activation + (L + 1) min. Traces of a 15-day Libre 3 (days 2 to 3) put every live frame 1 to 5 s
// before that boundary, with no drift. The removed native driver dated a live minute with its
// arrival and recovered minutes back from the newest arrival, which is the same grid. Clinical
// records and five-minute history carry the same life counts, so one rule dates all of them.
package tk.glucodata.drivers.libre3

internal object Libre3LifeCount {
    private const val MINUTE_MS = 60_000L

    /** When the minute with [lifeCount] ended. */
    fun endMs(activationMs: Long, lifeCount: Int): Long = activationMs + (lifeCount + 1L) * MINUTE_MS

    /** The activation implied by a minute that arrived at [arrivalMs]. */
    fun activationFromArrival(arrivalMs: Long, lifeCount: Int): Long = arrivalMs - (lifeCount + 1L) * MINUTE_MS

    /** Earlier builds saved the start of life count 0 for a learned grid: the same grid. */
    fun activationFromLegacyAnchor(anchorMs: Long): Long = if (anchorMs > 0L) anchorMs - MINUTE_MS else 0L

    fun legacyAnchor(activationMs: Long): Long = if (activationMs > 0L) activationMs + MINUTE_MS else 0L
}
