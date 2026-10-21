package tk.glucodata.ui

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class JournalOverviewLayoutTests {

    private companion object {
        const val MIN = 60_000L
        const val TOP = 0
        const val BOTTOM = 1
    }

    private fun entry(minute: Long, rail: Int = TOP, key: Any = "carbs") =
        JournalOverviewLayout.Entry(minute * MIN, rail, key)

    private fun layout(entries: List<JournalOverviewLayout.Entry>, width: Long = 30 * MIN) =
        JournalOverviewLayout.layout(entries, mergeMillis = 90 * MIN, gapMillis = 5 * MIN, rows = 2) { width }

    @Test
    fun nearbyEntriesOfOneKindMergeIntoOneMark() {
        val marks = layout(listOf(entry(0), entry(30), entry(80)))
        assertEquals(1, marks.size)
        assertArrayEquals(intArrayOf(0, 1, 2), marks.single().members)
        assertEquals(40 * MIN, marks.single().centerMillis)
    }

    @Test
    fun mergingMeasuresFromTheFirstEntrySoRunsDoNotChain() {
        // Every dose is an hour after the last; chaining would make one mark of all five.
        val marks = layout((0L..4L).map { entry(it * 60) })
        assertEquals(listOf(listOf(0, 1), listOf(2, 3), listOf(4)), marks.map { it.members.toList() })
    }

    @Test
    fun differentKindsNeverMerge() {
        val marks = layout(listOf(entry(0, BOTTOM, "Novorapid"), entry(10, BOTTOM, "Tresiba"), entry(20, BOTTOM, "Novorapid")))
        assertEquals(listOf(listOf(0, 2), listOf(1)), marks.map { it.members.toList() })
    }

    @Test
    fun touchingMarksTakeTheNextRow() {
        val marks = layout(listOf(entry(0, BOTTOM, "Novorapid"), entry(10, BOTTOM, "Tresiba")))
        assertEquals(listOf(0, 1), marks.map { it.row })
    }

    @Test
    fun marksWithRoomShareTheEdgeRow() {
        val marks = layout(listOf(entry(0), entry(200), entry(400)))
        assertEquals(listOf(0, 0, 0), marks.map { it.row })
    }

    @Test
    fun railsPackIndependently() {
        val marks = layout(listOf(entry(0, TOP, "carbs"), entry(5, BOTTOM, "Novorapid")))
        assertEquals(listOf(0, 0), marks.map { it.row })
    }

    @Test
    fun aFullRailOverlapsOnTheRowThatFreesUpFirst() {
        val marks = layout(
            listOf(entry(0, BOTTOM, "a"), entry(5, BOTTOM, "b"), entry(10, BOTTOM, "c")),
            width = 60 * MIN
        )
        assertEquals(listOf(0, 1, 0), marks.map { it.row })
    }
}
