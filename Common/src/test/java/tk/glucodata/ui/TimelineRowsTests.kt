package tk.glucodata.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import tk.glucodata.logic.TrendEngine

class TimelineRowsTests {
    private val start = 1_760_000_000_000L
    private fun point(i: Int, value: Float = 100f + (i % 40)) =
        GlucosePoint(value = value, time = "", timestamp = start + i * 60_000L, rawValue = value - 2f)

    @Test
    fun ascendingHistoryIsReturnedAsIs() {
        val ascending = List(5_000) { point(it) }
        assertSame(ascending, ascending.ascendingByTimestamp())
    }

    @Test
    fun unorderedHistoryIsSorted() {
        val shuffled = List(300) { point(it) }.shuffled(java.util.Random(7))
        val sorted = shuffled.ascendingByTimestamp()
        assertEquals(shuffled.sortedBy { it.timestamp }, sorted)
    }

    @Test
    fun rowTrendHistoryIsTheRowAndWhatPrecedesItNewestFirst() {
        val history = List(2_000) { point(it) }
        val tail = rowTrendHistory(history, history[1_500].timestamp)
        assertEquals(ROW_TREND_TAIL_LIMIT, tail.size)
        assertEquals(history[1_500], tail.first())
        assertEquals(history[1_500 - ROW_TREND_TAIL_LIMIT + 1], tail.last())
        assertTrue(tail.zipWithNext().all { (newer, older) -> newer.timestamp > older.timestamp })
    }

    @Test
    fun rowTrendHistoryClipsAtTheStartOfTheStoreAndStopsBeforeLaterReadings() {
        val history = List(50) { point(it) }
        assertEquals(history.take(4).asReversed(), rowTrendHistory(history, history[3].timestamp))
        // A timestamp between two readings takes the earlier one as newest.
        assertEquals(history.take(4).asReversed(), rowTrendHistory(history, history[3].timestamp + 30_000L))
        assertEquals(emptyList<GlucosePoint>(), rowTrendHistory(history, start - 1L))
        assertEquals(emptyList<GlucosePoint>(), rowTrendHistory(emptyList(), start))
    }

    @Test
    fun theArrowOverTheTailIsTheArrowOverTheWholePrecedingHistory() {
        // The tail exists so the row does not copy the store; it must not change the answer.
        val history = List(3_000) { i -> point(i, value = 90f + 60f * kotlin.math.sin(i / 25.0).toFloat()) }
        for (index in listOf(0, 1, 29, 300, 1_234, 2_999)) {
            val whole = history.subList(0, index + 1).asReversed()
            val tail = rowTrendHistory(history, history[index].timestamp)
            assertEquals(
                TrendEngine.calculateTrend(whole, useRaw = false, isMmol = false),
                TrendEngine.calculateTrend(tail, useRaw = false, isMmol = false)
            )
        }
    }

    @Test
    fun keysStayUniqueWithoutAnIndexAndOnlyDuplicatesGetASuffix() {
        assertEquals(listOf("a", "b", "a#1", "c", "a#2"), uniqueRowKeys(listOf("a", "b", "a", "c", "a")))
        // Prepending a row does not rename the rows below it.
        val before = uniqueRowKeys(listOf("t2", "t1", "t0"))
        val after = uniqueRowKeys(listOf("t3", "t2", "t1", "t0"))
        assertEquals(before, after.drop(1))
    }
}
