package tk.glucodata.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Test

class HistoryDisplayMergeTests {
    private companion object {
        const val HOUR_MS = 60L * 60L * 1000L
        const val MINUTE_MS = 60L * 1000L
    }

    @Test
    fun mergeReadings_keepsOlderNonConflictingRowsAndPrefersCurrentSensorOnConflict() {
        val merged = HistoryDisplayMerge.mergeReadings(
            readings = listOf(
                reading(id = 1, timestamp = 1 * HOUR_MS, sensorSerial = "sensor-old", value = 100f, rawValue = 95f),
                reading(id = 2, timestamp = 2 * HOUR_MS, sensorSerial = "sensor-old", value = 110f, rawValue = 104f),
                reading(id = 3, timestamp = 2 * HOUR_MS, sensorSerial = "sensor-new", value = 111f, rawValue = 105f),
                reading(id = 4, timestamp = 3 * HOUR_MS, sensorSerial = "sensor-new", value = 120f, rawValue = 114f)
            ),
            preferredSerial = "sensor-new"
        )

        assertEquals(listOf(1 * HOUR_MS, 2 * HOUR_MS, 3 * HOUR_MS), merged.map { it.timestamp })
        assertEquals(listOf("sensor-old", "sensor-new", "sensor-new"), merged.map { it.sensorSerial })
    }

    @Test
    fun mergeReadings_withoutPreferredSensorChoosesRicherReadingForSameTimestamp() {
        val merged = HistoryDisplayMerge.mergeReadings(
            readings = listOf(
                reading(id = 1, timestamp = 2 * HOUR_MS, sensorSerial = "sensor-a", value = 110f, rawValue = 0f),
                reading(id = 2, timestamp = 2 * HOUR_MS, sensorSerial = "sensor-b", value = 0f, rawValue = 108f),
                reading(id = 3, timestamp = 2 * HOUR_MS, sensorSerial = "sensor-c", value = 111f, rawValue = 109f)
            ),
            preferredSerial = null
        )

        assertEquals(1, merged.size)
        assertEquals("sensor-c", merged.single().sensorSerial)
        assertEquals(111f, merged.single().value, 0.001f)
    }

    @Test
    fun mergeReadings_dropsOverlappingOlderSensorRangeWhenPreferredSensorHasCoverage() {
        val merged = HistoryDisplayMerge.mergeReadings(
            readings = listOf(
                reading(id = 1, timestamp = 1 * HOUR_MS, sensorSerial = "sensor-old", value = 100f, rawValue = 95f),
                reading(id = 2, timestamp = 2 * HOUR_MS + 42 * MINUTE_MS, sensorSerial = "sensor-old", value = 101f, rawValue = 96f),
                reading(id = 3, timestamp = 2 * HOUR_MS + 58 * MINUTE_MS, sensorSerial = "sensor-old", value = 102f, rawValue = 97f),
                reading(id = 4, timestamp = 2 * HOUR_MS + 40 * MINUTE_MS, sensorSerial = "sensor-new", value = 120f, rawValue = 115f),
                reading(id = 5, timestamp = 2 * HOUR_MS + 50 * MINUTE_MS, sensorSerial = "sensor-new", value = 121f, rawValue = 116f),
                reading(id = 6, timestamp = 3 * HOUR_MS, sensorSerial = "sensor-new", value = 122f, rawValue = 117f)
            ).sortedBy { it.timestamp },
            preferredSerial = "sensor-new"
        )

        assertEquals(
            listOf(
                1 * HOUR_MS,
                2 * HOUR_MS + 40 * MINUTE_MS,
                2 * HOUR_MS + 50 * MINUTE_MS,
                3 * HOUR_MS
            ),
            merged.map { it.timestamp }
        )
        assertEquals(
            listOf("sensor-old", "sensor-new", "sensor-new", "sensor-new"),
            merged.map { it.sensorSerial }
        )
    }

    @Test
    fun mergeReadings_keepsOlderRowsAcrossLargePreferredSensorGap() {
        val merged = HistoryDisplayMerge.mergeReadings(
            readings = listOf(
                reading(id = 1, timestamp = 1 * HOUR_MS, sensorSerial = "sensor-old", value = 100f, rawValue = 95f),
                reading(id = 2, timestamp = 2 * HOUR_MS, sensorSerial = "sensor-new", value = 120f, rawValue = 115f),
                reading(id = 3, timestamp = 3 * HOUR_MS, sensorSerial = "sensor-old", value = 101f, rawValue = 96f),
                reading(id = 4, timestamp = 4 * HOUR_MS, sensorSerial = "sensor-new", value = 121f, rawValue = 116f)
            ).sortedBy { it.timestamp },
            preferredSerial = "sensor-new"
        )

        assertEquals(
            listOf(1 * HOUR_MS, 2 * HOUR_MS, 3 * HOUR_MS, 4 * HOUR_MS),
            merged.map { it.timestamp }
        )
        assertEquals(
            listOf("sensor-old", "sensor-new", "sensor-old", "sensor-new"),
            merged.map { it.sensorSerial }
        )
    }

    @Test
    fun mergeReadings_collapsesNativeLongAndShortAliasesInSameMinute() {
        val merged = HistoryDisplayMerge.mergeReadings(
            readings = listOf(
                reading(id = 1, timestamp = 3 * HOUR_MS + 27 * MINUTE_MS, sensorSerial = "240601YL08230BFY", value = 110f, rawValue = 50f),
                reading(id = 2, timestamp = 3 * HOUR_MS + 27 * MINUTE_MS + 15_000L, sensorSerial = "1YL08230BFY", value = 111f, rawValue = 51f),
                reading(id = 3, timestamp = 3 * HOUR_MS + 28 * MINUTE_MS, sensorSerial = "1YL08230BFY", value = 112f, rawValue = 52f)
            ),
            preferredSerial = "1YL08230BFY"
        )

        assertEquals(
            listOf(3 * HOUR_MS + 27 * MINUTE_MS + 15_000L, 3 * HOUR_MS + 28 * MINUTE_MS),
            merged.map { it.timestamp }
        )
        assertEquals(listOf("1YL08230BFY", "1YL08230BFY"), merged.map { it.sensorSerial })
    }

    @Test
    fun mergeReadings_collapsesSameSensorRowsInSameMinute() {
        val merged = HistoryDisplayMerge.mergeReadings(
            readings = listOf(
                reading(id = 1, timestamp = 8 * HOUR_MS + 39 * MINUTE_MS + 1_000L, sensorSerial = "F0FD4509C7C2", value = 63f, rawValue = 13f),
                reading(id = 2, timestamp = 8 * HOUR_MS + 39 * MINUTE_MS + 34_000L, sensorSerial = "F0FD4509C7C2", value = 64f, rawValue = 13f),
                reading(id = 3, timestamp = 8 * HOUR_MS + 42 * MINUTE_MS + 34_000L, sensorSerial = "F0FD4509C7C2", value = 65f, rawValue = 14f)
            ),
            preferredSerial = "F0FD4509C7C2"
        )

        assertEquals(
            listOf(
                8 * HOUR_MS + 39 * MINUTE_MS + 34_000L,
                8 * HOUR_MS + 42 * MINUTE_MS + 34_000L
            ),
            merged.map { it.timestamp }
        )
        assertEquals(listOf(64f, 65f), merged.map { it.value })
    }

    @Test
    fun mergeReadings_reusesSingleSensorRowsWhenMinuteBucketsAreUnique() {
        val readings = listOf(
            reading(id = 1, timestamp = 8 * HOUR_MS + 35 * MINUTE_MS, sensorSerial = "F0FD4509C7C2", value = 63f, rawValue = 13f),
            reading(id = 2, timestamp = 8 * HOUR_MS + 40 * MINUTE_MS, sensorSerial = "F0FD4509C7C2", value = 64f, rawValue = 13f),
            reading(id = 3, timestamp = 8 * HOUR_MS + 45 * MINUTE_MS, sensorSerial = "F0FD4509C7C2", value = 65f, rawValue = 14f)
        )

        assertSame(readings, HistoryDisplayMerge.mergeReadings(readings, preferredSerial = "F0FD4509C7C2"))
    }

    @Test
    fun mergeReadings_keepsImportedRowsAndPrefersLiveSensorOnOverlap() {
        val merged = HistoryDisplayMerge.mergeReadings(
            readings = listOf(
                reading(id = 1, timestamp = 1 * HOUR_MS, sensorSerial = HistoryRepository.IMPORTED_SENSOR_SERIAL, value = 100f, rawValue = 90f),
                reading(id = 2, timestamp = 2 * HOUR_MS, sensorSerial = HistoryRepository.IMPORTED_SENSOR_SERIAL, value = 101f, rawValue = 91f),
                reading(id = 3, timestamp = 2 * HOUR_MS, sensorSerial = "sensor-new", value = 120f, rawValue = 110f),
                reading(id = 4, timestamp = 3 * HOUR_MS, sensorSerial = "sensor-new", value = 121f, rawValue = 111f)
            ),
            preferredSerial = "sensor-new"
        )

        assertEquals(listOf(1 * HOUR_MS, 2 * HOUR_MS, 3 * HOUR_MS), merged.map { it.timestamp })
        assertEquals(
            listOf(HistoryRepository.IMPORTED_SENSOR_SERIAL, "sensor-new", "sensor-new"),
            merged.map { it.sensorSerial }
        )
    }

    @Test
    fun mergeReadings_keepsImportedRowsInsidePreferredCoverageWhenTheyFillGaps() {
        val merged = HistoryDisplayMerge.mergeReadings(
            readings = listOf(
                reading(id = 1, timestamp = 1 * HOUR_MS, sensorSerial = "sensor-new", value = 120f, rawValue = 110f),
                reading(id = 2, timestamp = 1 * HOUR_MS + 5 * MINUTE_MS, sensorSerial = HistoryRepository.IMPORTED_SENSOR_SERIAL, value = 101f, rawValue = 91f),
                reading(id = 3, timestamp = 1 * HOUR_MS + 10 * MINUTE_MS, sensorSerial = "sensor-new", value = 121f, rawValue = 111f),
                reading(id = 4, timestamp = 1 * HOUR_MS + 10 * MINUTE_MS + 20_000L, sensorSerial = HistoryRepository.IMPORTED_SENSOR_SERIAL, value = 102f, rawValue = 92f),
                reading(id = 5, timestamp = 1 * HOUR_MS + 20 * MINUTE_MS, sensorSerial = "sensor-new", value = 122f, rawValue = 112f)
            ).sortedBy { it.timestamp },
            preferredSerial = "sensor-new"
        )

        assertEquals(
            listOf(
                1 * HOUR_MS,
                1 * HOUR_MS + 5 * MINUTE_MS,
                1 * HOUR_MS + 10 * MINUTE_MS,
                1 * HOUR_MS + 20 * MINUTE_MS
            ),
            merged.map { it.timestamp }
        )
        assertEquals(
            listOf(
                "sensor-new",
                HistoryRepository.IMPORTED_SENSOR_SERIAL,
                "sensor-new",
                "sensor-new"
            ),
            merged.map { it.sensorSerial }
        )
    }

    private fun reading(
        id: Long,
        timestamp: Long,
        sensorSerial: String,
        value: Float,
        rawValue: Float
    ) = HistoryReading(
        id = id,
        timestamp = timestamp,
        sensorSerial = sensorSerial,
        value = value,
        rawValue = rawValue,
        rate = null
    )

    /**
     * Why the dashboard query cannot be bounded to the visible window.
     *
     * Merging a slice is not merging the timeline restricted to that slice. Over
     * the whole timeline the old sensor is suppressed where the current one
     * covers it; over a window holding none of the current sensor's rows,
     * nothing is suppressed and the old sensor draws raw. Shipped once as a
     * bounded chart query, seen as a foreign sensor's line across the chart.
     */
    @Test
    fun mergingASliceIsNotMergingTheTimelineRestrictedToThatSlice() {
        val whole = listOf(
            reading(id = 1, timestamp = 1 * HOUR_MS, sensorSerial = "sensor-old", value = 40f, rawValue = 40f),
            reading(id = 2, timestamp = 2 * HOUR_MS, sensorSerial = "sensor-old", value = 41f, rawValue = 41f),
            reading(id = 3, timestamp = 2 * HOUR_MS, sensorSerial = "sensor-new", value = 110f, rawValue = 105f),
            reading(id = 4, timestamp = 3 * HOUR_MS, sensorSerial = "sensor-new", value = 120f, rawValue = 114f)
        )

        val mergedWhole = HistoryDisplayMerge.mergeReadings(whole, preferredSerial = "sensor-new")
        // At 2h the current sensor wins outright; the old sensor's 41 is dropped.
        assertEquals(
            listOf("sensor-old", "sensor-new", "sensor-new"),
            mergedWhole.map { it.sensorSerial }
        )

        // The same merge over a window holding only the old sensor's rows keeps
        // both of them — there is no current sensor in scope to lose to.
        val windowOnly = whole.filter { it.timestamp <= 2 * HOUR_MS && it.sensorSerial == "sensor-old" }
        val mergedWindow = HistoryDisplayMerge.mergeReadings(windowOnly, preferredSerial = "sensor-new")
        assertEquals(listOf("sensor-old", "sensor-old"), mergedWindow.map { it.sensorSerial })

        // So slicing the merged whole and merging the slice disagree: the rule
        // is a property of the timeline, and the merge has to see all of it.
        val slicedAfterMerge = mergedWhole.filter { it.timestamp <= 2 * HOUR_MS }
        assertNotEquals(
            slicedAfterMerge.map { it.sensorSerial },
            mergedWindow.map { it.sensorSerial }
        )
    }

    /**
     * Why the dashboard's *first paint* may use a recent window when the chart's
     * real input may not.
     *
     * The two properties an arbitrary window breaks both hold for a recent one,
     * as long as the current sensor is still producing: its rows are in the
     * window, so the merge has something to suppress with, and the newest
     * reading in the store is in there too, so "latest" agrees with the full
     * list and the viewport does not jump when the full list replaces it.
     *
     * Stated as: merging the recent tail gives the same answer as merging
     * everything and then taking that tail.
     */
    @Test
    fun mergingTheRecentTailAgreesWithTheTailOfTheFullMerge() {
        val whole = listOf(
            reading(id = 1, timestamp = 1 * HOUR_MS, sensorSerial = "sensor-old", value = 40f, rawValue = 40f),
            reading(id = 2, timestamp = 2 * HOUR_MS, sensorSerial = "sensor-old", value = 41f, rawValue = 41f),
            reading(id = 3, timestamp = 3 * HOUR_MS, sensorSerial = "sensor-new", value = 110f, rawValue = 105f),
            reading(id = 4, timestamp = 3 * HOUR_MS, sensorSerial = "sensor-old", value = 42f, rawValue = 42f),
            reading(id = 5, timestamp = 4 * HOUR_MS, sensorSerial = "sensor-new", value = 120f, rawValue = 114f)
        )
        val tailStart = 3 * HOUR_MS

        val fullThenSliced = HistoryDisplayMerge
            .mergeReadings(whole, preferredSerial = "sensor-new")
            .filter { it.timestamp >= tailStart }
        val slicedThenMerged = HistoryDisplayMerge.mergeReadings(
            whole.filter { it.timestamp >= tailStart },
            preferredSerial = "sensor-new"
        )

        assertEquals(
            fullThenSliced.map { it.timestamp to it.sensorSerial },
            slicedThenMerged.map { it.timestamp to it.sensorSerial }
        )
        // And the live edge — what the chart calls "latest" — is the same point.
        assertEquals(fullThenSliced.last().timestamp, slicedThenMerged.last().timestamp)
    }
}
