package tk.glucodata.share

import org.junit.Assert.assertEquals
import org.junit.Test
import tk.glucodata.ExchangeTrend

class DexcomShareBackfillTrendTests {
    private val minute = 60_000L
    private val base = 1_760_000_000_000L

    private fun source(minutes: Long, mgdl: Int, rate: Float?) =
        DexcomShareBackfillTrend.Source(base + minutes * minute, mgdl, rate)

    @Test
    fun aZeroRatePlaceholderIsNotAFlatArrow() {
        // The native history import stores rate = 0 for "unknown"; glucose is falling 3 mg/dL/min.
        val rows = DexcomShareBackfillTrend.rows(
            listOf(source(0, 150, 0f), source(5, 135, 0f), source(10, 120, 0f)),
        )

        assertEquals(
            listOf(ExchangeTrend.UNKNOWN, ExchangeTrend.DOUBLE_DOWN, ExchangeTrend.DOUBLE_DOWN),
            rows.map { it.trendIndex },
        )
    }

    @Test
    fun aRowsOwnNonZeroRateIsUsed() {
        val rows = DexcomShareBackfillTrend.rows(listOf(source(0, 120, 2.5f)))

        assertEquals(ExchangeTrend.SINGLE_UP, rows.single().trendIndex)
    }

    @Test
    fun minuteRowsLookFurtherBackForADerivableRate() {
        // Neighbours one minute apart are too close; the row four minutes back is usable.
        val rows = DexcomShareBackfillTrend.rows((0L..4L).map { source(it, 100 + it.toInt() * 2, null) })

        assertEquals(ExchangeTrend.SINGLE_UP, rows.last().trendIndex)
        assertEquals(ExchangeTrend.UNKNOWN, rows[3].trendIndex)
    }

    @Test
    fun aGapLongerThanTheDeriveWindowLeavesNoTrend() {
        val rows = DexcomShareBackfillTrend.rows(listOf(source(0, 100, null), source(30, 180, null)))

        assertEquals(ExchangeTrend.UNKNOWN, rows.last().trendIndex)
    }

    @Test
    fun rowsComeBackOldestFirst() {
        val rows = DexcomShareBackfillTrend.rows(listOf(source(10, 110, null), source(0, 100, null)))

        assertEquals(listOf(base, base + 10 * minute), rows.map { it.timeMs })
    }
}
