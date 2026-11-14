package tk.glucodata.data

import org.junit.Assert.assertEquals
import org.junit.Test
import tk.glucodata.ExchangeTrend

class IcanBackfillTrendTests {

    @Test
    fun missingRateStaysUnknown() {
        assertEquals(ExchangeTrend.UNKNOWN, icanBackfillTrendIndex(null))
    }

    @Test
    fun zeroRateIsFlatNotUnknown() {
        assertEquals(ExchangeTrend.FLAT, icanBackfillTrendIndex(0f))
    }

    @Test
    fun fallingRateUsesTrendIndexNotTruncatedRate() {
        assertEquals(ExchangeTrend.SINGLE_DOWN, icanBackfillTrendIndex(-2.5f))
    }

    @Test
    fun risingRateUsesTrendIndex() {
        assertEquals(ExchangeTrend.FORTY_FIVE_UP, icanBackfillTrendIndex(1.5f))
    }

    @Test
    fun steepRiseUsesDoubleUp() {
        assertEquals(ExchangeTrend.DOUBLE_UP, icanBackfillTrendIndex(3.2f))
    }
}
