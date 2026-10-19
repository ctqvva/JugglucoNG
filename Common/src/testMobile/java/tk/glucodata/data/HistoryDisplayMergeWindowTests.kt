package tk.glucodata.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * The property the windowed timeline stands on: merging a padded window of the
 * store against the whole store's coverage gives exactly the whole timeline's
 * merge restricted to that window.
 *
 * `mergingASliceIsNotMergingTheTimelineRestrictedToThatSlice` in
 * [HistoryDisplayMergeTests] shows why a bare slice fails; this shows that the
 * coverage — the timestamps of every sensor — is the only whole-store fact the
 * slice was missing.
 */
class HistoryDisplayMergeWindowTests {
    private companion object {
        const val MINUTE_MS = 60L * 1000L
        const val HOUR_MS = 60L * MINUTE_MS
        const val ORIGIN = 1_760_000_000_000L
    }

    private class Store(val rows: List<HistoryReading>) {
        val index = HistoryTimestampIndex().also { index ->
            rows.forEach { index.add(it.sensorSerial, it.timestamp) }
        }
    }

    /**
     * Sensors with cadences of one and five minutes, second-level jitter,
     * outages of every length around the 15-minute coverage rule and the
     * 17-minute chart gap, an imported serial, replaced sensors and a sensor
     * that goes quiet while another streams.
     */
    private fun randomStore(seed: Long): Store {
        val random = Random(seed)
        val rows = ArrayList<HistoryReading>()
        var id = 1L
        fun stream(serial: String, startMs: Long, endMs: Long, cadenceMin: Int, jitterS: Int, gaps: List<LongRange>) {
            var t = startMs
            while (t <= endMs) {
                if (gaps.none { t in it }) {
                    val jitter = if (jitterS == 0) 0L else random.nextInt(jitterS + 1) * 1000L
                    val ts = t + jitter
                    val value = if (random.nextInt(40) == 0) 0f else 80f + random.nextInt(120)
                    rows.add(HistoryReading(id++, ts, serial, value, value - 2f, if (random.nextBoolean()) 0.1f else null))
                }
                t += cadenceMin * MINUTE_MS
            }
        }
        fun gapsFor(startMs: Long, endMs: Long): List<LongRange> {
            val gaps = ArrayList<LongRange>()
            var cursor = startMs
            while (cursor < endMs) {
                cursor += (1 + random.nextInt(4)) * HOUR_MS
                // 3..40 minutes: on both sides of the 15-minute coverage rule and the 17-minute chart gap.
                val length = (3 + random.nextInt(38)) * MINUTE_MS
                gaps.add(cursor..(cursor + length))
                cursor += length
            }
            return gaps
        }
        val span = 6L * 24L * HOUR_MS
        // The preferred sensor: may stop early (goes quiet) in some stores.
        val preferredEnd = if (random.nextInt(3) == 0) ORIGIN + span / 2 else ORIGIN + span
        stream("sensor-new", ORIGIN + 8 * HOUR_MS, preferredEnd, 1, 3, gapsFor(ORIGIN, preferredEnd))
        // A retired sensor overlapping the start of the preferred one.
        stream("sensor-old", ORIGIN - 2 * 24 * HOUR_MS, ORIGIN + 12 * HOUR_MS, if (random.nextBoolean()) 1 else 5, 0, gapsFor(ORIGIN - 2 * 24 * HOUR_MS, ORIGIN + 12 * HOUR_MS))
        // A second live sensor for the whole span, at a different cadence.
        stream("sensor-peer", ORIGIN, ORIGIN + span, 5, 20, gapsFor(ORIGIN, ORIGIN + span))
        // Imported rows scattered through everything.
        stream("imported", ORIGIN - 24 * HOUR_MS, ORIGIN + span, 15, 0, emptyList())
        rows.sortBy { it.timestamp }
        return Store(rows)
    }

    private fun HistoryReading.key() = Triple(timestamp, sensorSerial, id)

    private fun assertWindowAgrees(store: Store, preferred: String?, start: Long, end: Long) {
        val whole = HistoryDisplayMerge.mergeReadings(store.rows, preferred)
            .filter { it.timestamp in start..end }
        val padded = store.rows.filter {
            it.timestamp >= start - HistoryDisplayMerge.WINDOW_PADDING_MS &&
                it.timestamp <= end + HistoryDisplayMerge.WINDOW_PADDING_MS
        }
        val window = HistoryDisplayMerge.mergeWindow(padded, preferred, store.index)
            .filter { it.timestamp in start..end }
        assertEquals(
            "preferred=$preferred window=[$start,$end]",
            whole.map { it.key() },
            window.map { it.key() }
        )
    }

    @Test
    fun aPaddedWindowMergedAgainstTheStoreCoverageIsTheWholeMergeRestrictedToIt() {
        for (seed in 1L..12L) {
            val store = randomStore(seed)
            val random = Random(seed * 31)
            val first = store.rows.first().timestamp
            val last = store.rows.last().timestamp
            for (preferred in listOf("sensor-new", "sensor-peer", "sensor-old", "nobody", null)) {
                repeat(40) {
                    val length = (10 + random.nextInt(48 * 60)) * MINUTE_MS
                    val start = first - HOUR_MS + (random.nextDouble() * (last - first + 2 * HOUR_MS)).toLong()
                    assertWindowAgrees(store, preferred, start, start + length)
                }
                // The edges of the store, and the whole store as one window.
                assertWindowAgrees(store, preferred, first, first + HOUR_MS)
                assertWindowAgrees(store, preferred, last - HOUR_MS, last)
                assertWindowAgrees(store, preferred, first, last)
            }
        }
    }

    @Test
    fun aSingleSensorStoreTakesTheSingleSensorPathFromTheCoverageNotTheWindow() {
        val rows = (0 until 600).map { i ->
            HistoryReading(i + 1L, ORIGIN + i * MINUTE_MS + (i % 3) * 1000L, "only", 100f, 98f, null)
        } + listOf(HistoryReading(1000L, ORIGIN + 10 * MINUTE_MS + 20_000L, "only", 101f, 99f, null))
        val store = Store(rows.sortedBy { it.timestamp })
        assertWindowAgrees(store, "only", ORIGIN, ORIGIN + 30 * MINUTE_MS)
        assertWindowAgrees(store, null, ORIGIN + 5 * HOUR_MS, ORIGIN + 6 * HOUR_MS)
    }

    @Test
    fun theIndexReportsTheStoreItWasBuiltFrom() {
        val store = randomStore(3L)
        val index = store.index
        assertEquals(store.rows.map { it.sensorSerial to it.timestamp }.distinct().size, index.readingCount)
        assertEquals(store.rows.first().timestamp, index.earliest)
        assertEquals(store.rows.last().timestamp, index.latest)
        assertEquals(setOf("sensor-new", "sensor-old", "sensor-peer", "imported"), index.rawSerials)

        val serials = store.rows.filter { it.sensorSerial == "sensor-peer" }
        val peer = index.timestampsOf("sensor-peer")!!
        assertEquals(serials.size, peer.size)
        val someMinute = serials[7].timestamp / MINUTE_MS
        assertTrue(index.hasReadingInMinute(listOf("sensor-peer"), someMinute))
        assertEquals(
            serials.count { it.timestamp in serials[10].timestamp..serials[20].timestamp },
            peer.countInRange(serials[10].timestamp, serials[20].timestamp)
        )
    }

    @Test
    fun paddingAnOpenEndedWindowDoesNotWrapTheClock() {
        val padded = HistoryDisplayMerge.paddedWindow(ORIGIN, Long.MAX_VALUE)
        assertEquals(ORIGIN - HistoryDisplayMerge.WINDOW_PADDING_MS, padded.first)
        assertEquals(Long.MAX_VALUE, padded.last)
        assertEquals(Long.MIN_VALUE, HistoryDisplayMerge.paddedWindow(Long.MIN_VALUE, ORIGIN).first)
        val bounded = HistoryDisplayMerge.paddedWindow(ORIGIN, ORIGIN + HOUR_MS)
        assertEquals(ORIGIN + HOUR_MS + HistoryDisplayMerge.WINDOW_PADDING_MS, bounded.last)
    }

    @Test
    fun removingAndReplacingKeepTheIndexExact() {
        val index = HistoryTimestampIndex()
        val times = (0 until 100).map { ORIGIN + it * MINUTE_MS }
        times.forEach { index.add("s", it) }
        assertEquals(100, index.readingCount)
        index.remove("s", times[50])
        assertEquals(99, index.readingCount)
        assertTrue(!index.hasReadingInMinute(listOf("s"), times[50] / MINUTE_MS))
        // Out-of-order backfill lands in the right place.
        index.add("s", times[50] + 5_000L)
        assertEquals(times.toSet() - times[50] + (times[50] + 5_000L), index.timestampsOf("s")!!.toArray().toSet())
        assertTrue(index.timestampsOf("s")!!.toArray().toList().zipWithNext().all { (a, b) -> a < b })
        val version = index.version
        index.add("s", times[3]) // already present
        assertEquals(version, index.version)
        index.replaceSensor("s", longArrayOf(ORIGIN, ORIGIN, ORIGIN + MINUTE_MS))
        assertEquals(2, index.readingCount)
        index.replaceSensor("s", LongArray(0))
        assertTrue(index.isEmpty)
    }
}
