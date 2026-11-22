package tk.glucodata

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrendExchangeFreshnessTests {
    private val now = 1_700_000_000_000L

    @Test
    fun exchangeAcceptsOnlyFreshTimestamps() {
        assertTrue(DisplayTrendSource.isFreshForExchange(now, now))
        assertTrue(DisplayTrendSource.isFreshForExchange(now - Notify.glucosetimeout, now))
        assertFalse(DisplayTrendSource.isFreshForExchange(now - Notify.glucosetimeout - 1L, now))
        assertFalse(DisplayTrendSource.isFreshForExchange(0L, now))
        assertTrue(DisplayTrendSource.isFreshForExchange(now + Notify.glucosetimeout, now))
        assertFalse(DisplayTrendSource.isFreshForExchange(now + Notify.glucosetimeout + 1L, now))
    }
}
