package tk.glucodata.drivers.anytime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tk.glucodata.ExchangeTrend

class AnytimeTrendTests {
    @Test
    fun ct3ComputedCodesNormalizeToOfficialTrendIds() {
        assertEquals(AnytimeTrend.RISE_FAST, AnytimeTrend.fromCt3ComputedCode(0))
        assertEquals(AnytimeTrend.RISE_SLOW, AnytimeTrend.fromCt3ComputedCode(1))
        assertEquals(AnytimeTrend.STEADY, AnytimeTrend.fromCt3ComputedCode(2))
        assertEquals(AnytimeTrend.DROP_SLOW, AnytimeTrend.fromCt3ComputedCode(3))
        assertEquals(AnytimeTrend.DROP_FAST, AnytimeTrend.fromCt3ComputedCode(4))
        assertEquals(AnytimeTrend.DROP_FAST_LOW, AnytimeTrend.fromCt3ComputedCode(5))
        assertEquals(AnytimeTrend.NONE, AnytimeTrend.fromCt3ComputedCode(6))
    }

    @Test
    fun ct5PackedCodesNormalizeToOfficialTrendIds() {
        assertEquals(AnytimeTrend.NONE, AnytimeTrend.fromCt5PackedCode(0))
        assertEquals(AnytimeTrend.DROP_FAST_LOW, AnytimeTrend.fromCt5PackedCode(1))
        assertEquals(AnytimeTrend.DROP_FAST, AnytimeTrend.fromCt5PackedCode(2))
        assertEquals(AnytimeTrend.DROP_SLOW, AnytimeTrend.fromCt5PackedCode(3))
        assertEquals(AnytimeTrend.STEADY, AnytimeTrend.fromCt5PackedCode(4))
        assertEquals(AnytimeTrend.RISE_SLOW, AnytimeTrend.fromCt5PackedCode(5))
        assertEquals(AnytimeTrend.RISE_FAST, AnytimeTrend.fromCt5PackedCode(6))
        assertEquals(AnytimeTrend.NONE, AnytimeTrend.fromCt5PackedCode(7))
    }

    @Test
    fun representativeRatesStayInsideExpectedArrowCategories() {
        assertEquals(1.5f, AnytimeTrend.rateFor(AnytimeTrend.RISE_FAST), 0f)
        assertEquals(0.75f, AnytimeTrend.rateFor(AnytimeTrend.RISE_SLOW), 0f)
        assertEquals(0f, AnytimeTrend.rateFor(AnytimeTrend.STEADY), 0f)
        assertEquals(-0.75f, AnytimeTrend.rateFor(AnytimeTrend.DROP_SLOW), 0f)
        assertEquals(-1.5f, AnytimeTrend.rateFor(AnytimeTrend.DROP_FAST), 0f)
        assertTrue(AnytimeTrend.rateFor(AnytimeTrend.NONE).isNaN())
    }

    @Test
    fun officialDirectionsMapExactlyForExchangeConsumers() {
        assertEquals(ExchangeTrend.SINGLE_UP, AnytimeTrend.exchangeIndexFor(AnytimeTrend.RISE_FAST))
        assertEquals(ExchangeTrend.FORTY_FIVE_UP, AnytimeTrend.exchangeIndexFor(AnytimeTrend.RISE_SLOW))
        assertEquals(ExchangeTrend.FLAT, AnytimeTrend.exchangeIndexFor(AnytimeTrend.STEADY))
        assertEquals(ExchangeTrend.FORTY_FIVE_DOWN, AnytimeTrend.exchangeIndexFor(AnytimeTrend.DROP_SLOW))
        assertEquals(ExchangeTrend.SINGLE_DOWN, AnytimeTrend.exchangeIndexFor(AnytimeTrend.DROP_FAST))
        assertEquals(ExchangeTrend.UNKNOWN, AnytimeTrend.exchangeIndexFor(AnytimeTrend.NONE))
    }
}
