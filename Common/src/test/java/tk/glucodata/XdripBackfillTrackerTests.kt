package tk.glucodata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tk.glucodata.XdripBackfillTracker.Candidate

class XdripBackfillTrackerTests {
    private val minute = 60_000L
    private val now = 10_000L * minute

    private fun point(minutesAgo: Long, mgdl: Double = 100.0) =
        Candidate(now - minutesAgo * minute, mgdl, Float.NaN)

    @Test
    fun `gap before the first live send after a restart is backfilled in order`() {
        val tracker = XdripBackfillTracker()
        tracker.restore("S", now - 40 * minute, 1, 0L)
        tracker.recordSent("S", now, 1, 0L)

        val picked = tracker.pick("S", (0L..40L).map { point(it) }, now)

        // 40 .. 1 minutes ago; 40 is the watermark itself, 0 was sent live.
        assertEquals((39L downTo 1L).map { now - it * minute }, picked.map { it.timeMs })
    }

    @Test
    fun `history that arrives before the live reading is also backfilled`() {
        val tracker = XdripBackfillTracker()
        tracker.restore("S", now - 30 * minute, 1, 0L)

        val picked = tracker.pick("S", (0L..29L).map { point(it) }, now)

        assertEquals(30, picked.size)
        assertEquals(now - 29 * minute, picked.first().timeMs)
    }

    @Test
    fun `a point near a live send is not sent twice`() {
        val tracker = XdripBackfillTracker()
        tracker.recordSent("S", now - 20_000L, 1, 0L)

        val picked = tracker.pick("S", listOf(Candidate(now - 40_000L, 100.0, Float.NaN)), now)

        assertEquals(emptyList<Candidate>(), picked)
    }

    @Test
    fun `a batch re-synced a second time sends nothing`() {
        val tracker = XdripBackfillTracker()
        tracker.restore("S", now - 10 * minute, 1, 0L)
        val batch = (0L..9L).map { point(it) }

        assertEquals(10, tracker.pick("S", batch, now).size)
        assertEquals(0, tracker.pick("S", batch, now).size)
    }

    @Test
    fun `nothing before the start watermark or older than the window is sent`() {
        val tracker = XdripBackfillTracker(windowMs = 60 * minute)
        tracker.restore("S", now - 500 * minute, 1, 0L)

        val picked = tracker.pick("S", listOf(point(61), point(59), point(1)), now)

        assertEquals(listOf(now - 59 * minute, now - minute), picked.map { it.timeMs })
    }

    @Test
    fun `a sensor with no live send is never backfilled`() {
        val tracker = XdripBackfillTracker()

        assertEquals(emptyList<Candidate>(), tracker.pick("S", listOf(point(5)), now))
        assertNull(tracker.sensor("S"))
    }

    @Test
    fun `collapse spacing thins the backfill`() {
        val tracker = XdripBackfillTracker()
        tracker.restore("S", now - 20 * minute, 1, 0L)

        val picked = tracker.pick("S", (0L..19L).map { point(it) }, now, spacingMs = 5 * minute)

        assertEquals(listOf(19L, 14L, 9L, 4L).map { now - it * minute }, picked.map { it.timeMs })
    }

    @Test
    fun `invalid and future readings are dropped`() {
        val tracker = XdripBackfillTracker()
        tracker.restore("S", now - 10 * minute, 1, 0L)

        val picked = tracker.pick(
            "S",
            listOf(point(5, 0.0), point(4, Double.NaN), Candidate(now + minute, 100.0, Float.NaN)),
            now,
        )

        assertEquals(emptyList<Candidate>(), picked)
    }
}
