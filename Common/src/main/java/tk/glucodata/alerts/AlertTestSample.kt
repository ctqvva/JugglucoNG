package tk.glucodata.alerts

import kotlin.math.sin

/**
 * What a test alarm shows instead of the live reading's story. The test value already sits on
 * the alarm's side of its threshold ([tk.glucodata.AlertTestValuePolicy]); the arrow and the
 * past hour have to agree with it, or a "Low" test arrives with today's flat arrow and a curve
 * that never went near the low line.
 */
object AlertTestSample {
    private const val MGDL_PER_MMOL = 18.0182f

    /**
     * The rate, in mg/dL per minute, a test of [type] carries: falling into a low, rising
     * into a high. Null where the alarm is not about where glucose is going (signal, expiry,
     * missed reading); those tests keep the current rate.
     */
    @JvmStatic
    fun rate(type: AlertType?): Float? = when (type) {
        AlertType.LOW, AlertType.VERY_LOW -> -1.4f
        AlertType.PRE_LOW -> -1.6f
        AlertType.FALLING_FAST -> -2.6f
        AlertType.HIGH, AlertType.VERY_HIGH -> 1.4f
        AlertType.PRE_HIGH -> 1.6f
        AlertType.RISING_FAST -> 2.6f
        // High for a long while: it stays up rather than climbing.
        AlertType.PERSISTENT_HIGH -> 0.2f
        else -> null
    }

    @JvmStatic
    fun customRate(isHigh: Boolean): Float = if (isHigh) 1.4f else -1.4f

    /**
     * A believable hour, one reading a minute, that ends at [endValue] (display unit) at
     * [endMs] arriving at [rateMgdlPerMin]: level at the start of the hour, bending into
     * the alarm's direction, with a sensor's small wobble on top. The slope at the end is
     * the rate the arrow shows, so the curve and the arrow tell the same story.
     */
    @JvmStatic
    @JvmOverloads
    fun trace(
        endValue: Float,
        rateMgdlPerMin: Float,
        isMmol: Boolean,
        endMs: Long,
        spanMinutes: Int = 60,
    ): List<Pair<Long, Float>> {
        if (!endValue.isFinite() || !rateMgdlPerMin.isFinite() || spanMinutes < 2) return emptyList()
        val endMgdl = if (isMmol) endValue * MGDL_PER_MMOL else endValue
        val span = spanMinutes.toFloat()
        return (-spanMinutes..0).map { minute ->
            val t = minute.toFloat()
            // v(t) = end + r·t + r·t²/2T: slope r now, slope 0 an hour ago.
            val curve = endMgdl + rateMgdlPerMin * t + rateMgdlPerMin * t * t / (2f * span)
            // The wobble fades out at the end, so the last reading is exactly the test value.
            val wobble = 1.2f * sin(t / 3.1f) * (-t / span)
            val mgdl = (curve + wobble).coerceIn(40f, 400f)
            val value = if (isMmol) mgdl / MGDL_PER_MMOL else mgdl
            (endMs + minute * 60_000L) to value
        }
    }
}
