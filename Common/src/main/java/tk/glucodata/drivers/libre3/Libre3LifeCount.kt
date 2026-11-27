// Libre 3 life count to wall time.
//
// The sensor numbers its minutes from activation and sends minute L when it ends, at
// activation + (L + 1) min. Traces of a 15-day Libre 3 (days 2 to 3) put every live frame 1 to 5 s
// before that boundary, with no drift. The removed native driver dated a live minute with its
// arrival and recovered minutes back from the newest arrival, which is the same grid. Clinical
// records and five-minute history carry the same life counts, so one rule dates all of them.
//
// A sensor's grid is the time of its life count 0, kept per sensor (Libre3Persistence.lifeZero)
// and never moved: Room, the native slots, the five-minute fill-ins and LibreView's record numbers
// all hold minutes at the times it gave. Preview builds before this rule dated minute L at the NFC
// start + L min; a sensor they stored keeps that grid, since a replay dated by the new one lands on
// the next minute's time and overwrites it natively while Room drops it as a duplicate.
package tk.glucodata.drivers.libre3

internal object Libre3LifeCount {
    private const val MINUTE_MS = 60_000L

    /** When [lifeCount] is dated on the grid whose life count 0 falls at [zeroMs]. */
    fun timeMs(zeroMs: Long, lifeCount: Int): Long = zeroMs + lifeCount * MINUTE_MS

    /** The grid of a session first seen by this build: minute L ends at activation + (L + 1) min. */
    fun zeroForActivation(activationMs: Long): Long = if (activationMs > 0L) activationMs + MINUTE_MS else 0L

    /** The grid learned from a live frame with no activation known: the frame is dated at its arrival. */
    fun zeroFromArrival(arrivalMs: Long, lifeCount: Int): Long = arrivalMs - lifeCount * MINUTE_MS

    /**
     * The grid an earlier build stored a sensor under: its learned anchor (the same quantity as a
     * life count 0 time), else the NFC start.
     */
    fun legacyZero(anchorMs: Long, startMs: Long): Long = when {
        anchorMs > 0L -> anchorMs
        startMs > 0L -> startMs
        else -> 0L
    }

    /** The activation a grid implies under the current rule, for a sensor with no NFC start. */
    fun activationForZero(zeroMs: Long): Long = if (zeroMs > 0L) zeroMs - MINUTE_MS else 0L
}
