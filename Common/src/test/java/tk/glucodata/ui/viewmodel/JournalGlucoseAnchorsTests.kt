package tk.glucodata.ui.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tk.glucodata.ui.GlucosePoint

class JournalGlucoseAnchorsTests {
    private val minute = 60_000L
    private val hour = 60 * minute
    private val origin = 1_760_000_000_000L

    private fun point(ts: Long) = GlucosePoint(value = 100f, time = "", timestamp = ts)

    @Test
    fun entriesAnHourApartShareOneReadAndClustersComeNewestFirst() {
        val timestamps = listOf(origin, origin + 30 * minute, origin + 90 * minute, origin + 5 * hour, origin + 5 * hour + 10 * minute)
        val clusters = JournalGlucoseAnchors.clusters(timestamps.shuffled())
        assertEquals(2, clusters.size)
        assertEquals(listOf(origin + 5 * hour, origin + 5 * hour + 10 * minute), clusters[0].entryTimestamps)
        assertEquals(listOf(origin, origin + 30 * minute, origin + 90 * minute), clusters[1].entryTimestamps)
        val first = clusters[1]
        assertEquals(origin - JournalGlucoseAnchors.MAX_ANCHOR_DISTANCE_MS - JournalGlucoseAnchors.TREND_LOOKBACK_MS, first.startMs)
        assertEquals(origin + 90 * minute + JournalGlucoseAnchors.MAX_ANCHOR_DISTANCE_MS, first.endMs)
    }

    @Test
    fun onlyUnknownAndLiveEntriesArePending() {
        val latest = origin + 24 * hour
        val settled = origin + hour
        val live = latest - 10 * minute
        val known = mapOf(
            settled to JournalGlucoseAnchor(null, emptyList()),
            live to JournalGlucoseAnchor(null, emptyList()),
        )
        val pending = JournalGlucoseAnchors.pending(listOf(settled, live, origin), known, latest)
        assertEquals(listOf(live, origin), pending)
        // Without a newest reading nothing is live; only the unknown one.
        assertEquals(listOf(origin), JournalGlucoseAnchors.pending(listOf(settled, live, origin), known, null))
    }

    @Test
    fun anEntryTakesTheNearestReadingWithinTwentyMinutesAndTheHistoryBehindIt() {
        val points = (0 until 221).map { point(origin + it * minute) }
        val cluster = JournalGlucoseAnchors.clusters(listOf(origin + 200 * minute + 20_000L, origin + 250 * minute)).single()
        val anchors = JournalGlucoseAnchors.resolve(cluster, points)
        val onReading = anchors.getValue(origin + 200 * minute + 20_000L)
        assertEquals(origin + 200 * minute, onReading.point!!.timestamp)
        assertEquals(JournalGlucoseAnchors.TREND_POINTS_KEPT, onReading.trendHistory.size)
        assertEquals(origin + 200 * minute, onReading.trendHistory.first().timestamp)
        assertTrue(onReading.trendHistory.zipWithNext().all { (a, b) -> a.timestamp > b.timestamp })
        // 250 minutes in: the nearest reading is 30 minutes away, too far.
        val offReading = anchors.getValue(origin + 250 * minute)
        assertNull(offReading.point)
        assertTrue(offReading.trendHistory.isEmpty())
    }

    @Test
    fun nearestIsTheLedgersRule() {
        val points = listOf(point(origin), point(origin + 10 * minute), point(origin + 20 * minute))
        assertEquals(origin + 10 * minute, JournalGlucoseAnchors.nearest(points, origin + 12 * minute)!!.timestamp)
        assertEquals(origin, JournalGlucoseAnchors.nearest(points, origin - 19 * minute)!!.timestamp)
        assertNull(JournalGlucoseAnchors.nearest(points, origin - 21 * minute))
        assertNull(JournalGlucoseAnchors.nearest(emptyList(), origin))
    }
}
