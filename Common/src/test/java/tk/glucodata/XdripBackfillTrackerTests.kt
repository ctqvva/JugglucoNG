package tk.glucodata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tk.glucodata.XdripBackfillTracker.Candidate

class XdripBackfillTrackerTests {
    private val second = 1_000L
    private val minute = 60_000L
    private val now = 10_000L * minute

    private fun at(timeMs: Long, mgdl: Double = 100.0) = Candidate(timeMs, mgdl, Float.NaN)

    private fun floor(timeMs: Long) = XdripBackfillTracker.Coverage(timeMs, 1, 0L, emptyList())

    private fun point(minutesAgo: Long, mgdl: Double = 100.0) = at(now - minutesAgo * minute, mgdl)

    @Test
    fun `gap before the first live send after a restart is backfilled in order`() {
        val tracker = XdripBackfillTracker()
        tracker.restore("S", floor(now - 40 * minute))
        tracker.recordSent("S", now, 1, 0L)

        val picked = tracker.pick("S", (0L..40L).map { point(it) }, now)

        // 40 .. 1 minutes ago; 40 is the watermark itself, 0 was sent live.
        assertEquals((39L downTo 1L).map { now - it * minute }, picked.map { it.timeMs })
    }

    @Test
    fun `history that arrives before the live reading is also backfilled`() {
        val tracker = XdripBackfillTracker()
        tracker.restore("S", floor(now - 30 * minute))

        val picked = tracker.pick("S", (0L..29L).map { point(it) }, now)

        assertEquals(30, picked.size)
        assertEquals(now - 29 * minute, picked.first().timeMs)
    }

    @Test
    fun `the history row of a reading already sent live is not sent again`() {
        // The trace case: the history row lands a few seconds off the live timestamp.
        val tracker = XdripBackfillTracker()
        tracker.restore("S", floor(now - 10 * minute))
        tracker.recordSent("S", now - 20 * second, 1, 0L)

        val rows = listOf(160L, 100L, 40L).map { at(now - it * second) }

        val picked = tracker.pick("S", rows, now)

        assertEquals(listOf(now - 160 * second, now - 100 * second), picked.map { it.timeMs })
    }

    @Test
    fun `a five minute sensor deduplicates against a live send minutes away`() {
        val tracker = XdripBackfillTracker()
        tracker.restore("S", floor(now - 60 * minute))
        tracker.recordSent("S", now - 3 * minute, 1, 0L)

        // History every 5 minutes; the row 2 minutes after the live send is the same reading.
        val picked = tracker.pick("S", listOf(point(20), point(15), point(10), point(5), point(1)), now)

        assertEquals(listOf(20L, 15L, 10L).map { now - it * minute }, picked.map { it.timeMs })
    }

    @Test
    fun `a one minute sensor keeps every minute of the gap`() {
        val tracker = XdripBackfillTracker()
        tracker.restore("S", floor(now - 6 * minute))
        tracker.recordSent("S", now, 1, 0L)

        val picked = tracker.pick("S", (1L..5L).map { point(it) }, now)

        assertEquals(5, picked.size)
    }

    @Test
    fun `a single row batch uses the period measured earlier`() {
        val tracker = XdripBackfillTracker()
        tracker.restore("S", floor(now - 60 * minute))
        tracker.pick("S", listOf(point(50), point(45), point(40)), now)
        tracker.recordSent("S", now - 3 * minute, 1, 0L)

        assertEquals(emptyList<Candidate>(), tracker.pick("S", listOf(point(1)), now))
    }

    @Test
    fun `a batch re-synced a second time sends nothing`() {
        val tracker = XdripBackfillTracker()
        tracker.restore("S", floor(now - 10 * minute))
        val batch = (0L..9L).map { point(it) }

        assertEquals(10, tracker.pick("S", batch, now).size)
        assertEquals(0, tracker.pick("S", batch, now).size)
    }

    @Test
    fun `nothing before the start watermark or older than the window is sent`() {
        val tracker = XdripBackfillTracker(windowMs = 60 * minute)
        tracker.restore("S", floor(now - 500 * minute))

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
        tracker.restore("S", floor(now - 20 * minute))

        val picked = tracker.pick("S", (0L..19L).map { point(it) }, now, spacingMs = 5 * minute)

        assertEquals(listOf(19L, 14L, 9L, 4L).map { now - it * minute }, picked.map { it.timeMs })
    }

    @Test
    fun `invalid and future readings are dropped`() {
        val tracker = XdripBackfillTracker()
        tracker.restore("S", floor(now - 10 * minute))

        val picked = tracker.pick("S", listOf(point(5, 0.0), point(4, Double.NaN), at(now + minute)), now)

        assertEquals(emptyList<Candidate>(), picked)
    }

    @Test
    fun `a gap still open at a restart is filled afterwards and nothing sent is repeated`() {
        val before = XdripBackfillTracker()
        before.restore("S", floor(now - 60 * minute))
        (50L downTo 45L).forEach { before.recordSent("S", now - it * minute, 1, 0L) }
        // Reconnect after a 30 minute gap; the app stops before the backfill arrives.
        before.recordSent("S", now - 15 * minute, 1, 0L)
        val saved = XdripBackfillCoverageFormat.encode(before.coverage("S", now)!!)

        val after = XdripBackfillTracker()
        after.restore("S", XdripBackfillCoverageFormat.decode(saved)!!)
        val picked = after.pick("S", (50L downTo 15L).map { point(it) }, now)

        assertEquals((44L downTo 16L).map { now - it * minute }, picked.map { it.timeMs })
    }

    @Test
    fun `readings that arrived on time were sent live and are never backfilled`() {
        // #686: a release that keeps no record of its sends was installed over a nightly for a
        // day. The next nightly restored the nightly's old record and re-sent that whole day.
        val tracker = XdripBackfillTracker()
        tracker.restore("S", XdripBackfillCoverageFormat.decode("${now - 23 * 60 * minute},3,0")!!)
        tracker.recordSent("S", now, 1, 0L)

        val onTime = (1L..60L).map { point(it).copy(storedAtMs = now - it * minute + 400L) }
        val late = (61L..70L).map { point(it).copy(storedAtMs = now - 50 * minute) }

        val picked = tracker.pick("S", onTime + late, now)

        assertEquals((70L downTo 61L).map { now - it * minute }, picked.map { it.timeMs })
    }

    @Test
    fun `a live reading filed under the sensor's earlier history time still counts as live`() {
        val tracker = XdripBackfillTracker()
        tracker.restore("S", floor(now - 60 * minute))
        tracker.recordSent("S", now, 1, 0L)

        val filedEarly = point(10).copy(storedAtMs = now - 10 * minute + 60 * second)
        val gap = point(20).copy(storedAtMs = now - 10 * minute + 60 * second)

        assertEquals(listOf(now - 20 * minute), tracker.pick("S", listOf(filedEarly, gap), now).map { it.timeMs })
    }

    @Test
    fun `a late history row next to an on-time reading is not sent again`() {
        // Libre 3 history every 5 minutes reaches the phone ~19 minutes late, seconds away from a
        // minute reading another build already sent live: the send record knows nothing of it.
        val tracker = XdripBackfillTracker()
        tracker.restore("S", floor(now - 60 * minute))
        tracker.recordSent("S", now, 1, 0L)

        val minutes = (1L..30L).map { point(it).copy(storedAtMs = now - it * minute + second) }
        val history = listOf(25L, 20L).map {
            at(now - it * minute - 20 * second).copy(storedAtMs = now - it * minute + 19 * minute)
        }

        assertEquals(emptyList<Candidate>(), tracker.pick("S", minutes + history, now))
    }

    @Test
    fun `coverage keeps sends apart by a gap in separate stretches`() {
        val tracker = XdripBackfillTracker()
        listOf(30L, 29L, 28L, 10L, 9L).forEach { tracker.recordSent("S", now - it * minute, 1, 0L) }

        val coverage = tracker.coverage("S", now)!!

        assertEquals(
            listOf((now - 30 * minute)..(now - 28 * minute), (now - 10 * minute)..(now - 9 * minute)),
            coverage.ranges,
        )
    }

    @Test
    fun `a reading already sent needs no backfill while a gap does`() {
        val tracker = XdripBackfillTracker()
        tracker.recordSent("S", now - 10 * minute, 1, 0L)

        assertFalse(tracker.needsBackfill("S", listOf(point(10)), now))
        assertTrue(tracker.needsBackfill("S", listOf(point(5)), now))
        assertFalse(tracker.needsBackfill("S", listOf(point(5)), now - 60 * minute))
    }

    @Test
    fun `needsBackfill agrees with pick when the batch changes the cadence`() {
        fun seeded(): XdripBackfillTracker = XdripBackfillTracker().also { tracker ->
            tracker.recordSent("S", now - 30 * minute, 1, 0L)
            // A sparse batch teaches a five-minute cadence.
            tracker.pick("S", listOf(point(20), point(15)), now)
            tracker.recordSent("S", now - 5 * minute, 1, 0L)
            tracker.recordSent("S", now, 1, 0L)
        }
        val batch = (4L downTo 1L).map { point(it) }

        val picked = seeded().pick("S", batch, now)

        assertTrue(picked.isNotEmpty())
        assertEquals(picked.isNotEmpty(), seeded().needsBackfill("S", batch, now))
    }

    @Test
    fun `needsBackfill agrees with pick about readings that arrived on time`() {
        fun seeded(): XdripBackfillTracker = XdripBackfillTracker().also { tracker ->
            tracker.restore("S", floor(now - 60 * minute))
            tracker.recordSent("S", now, 1, 0L)
        }
        val minutes = (1L..30L).map { point(it).copy(storedAtMs = now - it * minute + second) }
        val history = listOf(25L, 20L).map {
            at(now - it * minute - 20 * second).copy(storedAtMs = now - it * minute + 19 * minute)
        }
        val late = point(45).copy(storedAtMs = now - 10 * minute)

        assertFalse(seeded().needsBackfill("S", minutes, now))
        assertFalse(seeded().needsBackfill("S", minutes + history, now))
        assertTrue(seeded().needsBackfill("S", minutes + late, now))
        assertEquals(listOf(late.timeMs), seeded().pick("S", minutes + late, now).map { it.timeMs })
    }

    @Test
    fun `an unknown sensor needs no backfill`() {
        assertFalse(XdripBackfillTracker().needsBackfill("S", listOf(point(0)), now))
    }

    @Test
    fun `the first release's single timestamp still decodes as a floor`() {
        val coverage = XdripBackfillCoverageFormat.decode("1792378995000,3,1790000000000")!!

        assertEquals(1792378995000L, coverage.floorMs)
        assertEquals(3, coverage.sensorGen)
        assertEquals(emptyList<LongRange>(), coverage.ranges)
        assertNull(XdripBackfillCoverageFormat.decode("garbage"))
    }
}
