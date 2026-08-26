package tk.glucodata.drivers.anytime

import tk.glucodata.ExchangeTrend

/** Official Anytime trend identifiers after normalizing family-specific wire codes. */
object AnytimeTrend {
    const val RISE_FAST = 2
    const val RISE_SLOW = 1
    const val STEADY = 0
    const val DROP_SLOW = -1
    const val DROP_FAST = -2
    const val DROP_FAST_LOW = -3
    const val NONE = 10

    fun fromCt3ComputedCode(raw: Int): Int = when (raw) {
        0 -> RISE_FAST
        1 -> RISE_SLOW
        2 -> STEADY
        3 -> DROP_SLOW
        4 -> DROP_FAST
        5 -> DROP_FAST_LOW
        else -> NONE
    }

    fun fromCt5PackedCode(raw: Int): Int = when (raw) {
        1 -> DROP_FAST_LOW
        2 -> DROP_FAST
        3 -> DROP_SLOW
        4 -> STEADY
        5 -> RISE_SLOW
        6 -> RISE_FAST
        else -> NONE
    }

    /** Representative mg/dL/min rates that preserve the official arrow category. */
    fun rateFor(trend: Int): Float = when (trend) {
        RISE_FAST -> 1.5f
        RISE_SLOW -> 0.75f
        STEADY -> 0f
        DROP_SLOW -> -0.75f
        DROP_FAST, DROP_FAST_LOW -> -1.5f
        else -> Float.NaN
    }

    fun exchangeIndexFor(trend: Int): Int = when (trend) {
        RISE_FAST -> ExchangeTrend.SINGLE_UP
        RISE_SLOW -> ExchangeTrend.FORTY_FIVE_UP
        STEADY -> ExchangeTrend.FLAT
        DROP_SLOW -> ExchangeTrend.FORTY_FIVE_DOWN
        DROP_FAST, DROP_FAST_LOW -> ExchangeTrend.SINGLE_DOWN
        else -> ExchangeTrend.UNKNOWN
    }
}
