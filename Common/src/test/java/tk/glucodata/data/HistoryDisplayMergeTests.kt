package tk.glucodata.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Test
import tk.glucodata.GlucoseReadingSource

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

    @Test
    fun mergeReadings_keepsFirstDeliveryWhenNightscoutBecomesPreferred() {
        val clone = reading(
            id = 20,
            timestamp = 5 * HOUR_MS,
            sensorSerial = "physical-sensor",
            value = 112f,
            rawValue = 111f,
            source = GlucoseReadingSource.CLONE_TURN,
            firstStoredAt = 1_000L,
        )
        val nightscout = reading(
            id = 21,
            timestamp = 5 * HOUR_MS,
            sensorSerial = "NSF-TEST",
            value = 112f,
            rawValue = 112f,
            source = GlucoseReadingSource.NIGHTSCOUT,
            firstStoredAt = 2_000L,
        )

        val clonePreferred = HistoryDisplayMerge.mergeReadings(
            listOf(clone, nightscout),
            preferredSerial = "physical-sensor",
        )
        val nightscoutPreferred = HistoryDisplayMerge.mergeReadings(
            listOf(clone, nightscout),
            preferredSerial = "NSF-TEST",
        )

        assertEquals(clone, clonePreferred.single())
        assertEquals(clone, nightscoutPreferred.single())
        assertEquals(GlucoseReadingSource.CLONE_TURN, nightscoutPreferred.single().source)
    }

    @Test
    fun mergeReadings_selectsThePreferredCopyWhenReplicatedValuesDisagree() {
        val clone = reading(
            id = 30,
            timestamp = 6 * HOUR_MS,
            sensorSerial = "physical-sensor",
            value = 112f,
            rawValue = 111f,
            source = GlucoseReadingSource.CLONE_LOCAL_ICE,
            firstStoredAt = 1_000L,
        )
        val nightscout = reading(
            id = 31,
            timestamp = 6 * HOUR_MS,
            sensorSerial = "NSF-TEST",
            value = 118f,
            rawValue = 118f,
            source = GlucoseReadingSource.NIGHTSCOUT,
            firstStoredAt = 2_000L,
        )

        val merged = HistoryDisplayMerge.mergeReadings(
            listOf(clone, nightscout),
            preferredSerial = "NSF-TEST",
        )

        assertEquals(nightscout, merged.single())
    }

    @Test
    fun mergeReadings_keepsAReplicaWinnerAcrossSmallTimestampSkew() {
        val clone = reading(
            id = 40,
            timestamp = 7 * HOUR_MS,
            sensorSerial = "physical-sensor",
            value = 121f,
            rawValue = 120f,
            source = GlucoseReadingSource.CLONE_LOCAL_ICE,
            firstStoredAt = 1_000L,
        )
        val nightscout = reading(
            id = 41,
            timestamp = 7 * HOUR_MS + 20_000L,
            sensorSerial = "NSF-TEST",
            value = 122f,
            rawValue = 122f,
            source = GlucoseReadingSource.NIGHTSCOUT,
            firstStoredAt = 2_000L,
        )

        assertEquals(
            clone,
            HistoryDisplayMerge.mergeReadings(listOf(clone, nightscout), "physical-sensor").single(),
        )
        assertEquals(
            clone,
            HistoryDisplayMerge.mergeReadings(listOf(clone, nightscout), "NSF-TEST").single(),
        )
    }

    @Test
    fun mergeReadings_doesNotCollapseTwoIndependentPhysicalSensors() {
        val first = reading(
            id = 50,
            timestamp = 8 * HOUR_MS,
            sensorSerial = "physical-a",
            value = 133f,
            rawValue = 132f,
            firstStoredAt = 1_000L,
        )
        val second = reading(
            id = 51,
            timestamp = 8 * HOUR_MS,
            sensorSerial = "physical-b",
            value = 133f,
            rawValue = 132f,
            firstStoredAt = 2_000L,
        )

        assertEquals(
            first,
            HistoryDisplayMerge.mergeReadings(listOf(first, second), "physical-a").single(),
        )
        assertEquals(
            second,
            HistoryDisplayMerge.mergeReadings(listOf(first, second), "physical-b").single(),
        )
    }

    @Test
    fun mergeReadings_keepsMultiRowReplicaProvenanceUnderEitherPreference() {
        val cloneFirst = reading(
            id = 60,
            timestamp = 9 * HOUR_MS,
            sensorSerial = "physical-sensor",
            value = 140f,
            rawValue = 139f,
            source = GlucoseReadingSource.CLONE_TURN,
            firstStoredAt = 1_000L,
        )
        val nightscoutFirst = reading(
            id = 61,
            timestamp = 9 * HOUR_MS + 5 * MINUTE_MS,
            sensorSerial = "NSF-TEST",
            value = 145f,
            rawValue = 145f,
            source = GlucoseReadingSource.NIGHTSCOUT,
            firstStoredAt = 2_000L,
        )
        val copies = listOf(
            cloneFirst,
            reading(62, cloneFirst.timestamp, "NSF-TEST", 140f, 140f, GlucoseReadingSource.NIGHTSCOUT, 3_000L),
            nightscoutFirst,
            reading(63, nightscoutFirst.timestamp, "physical-sensor", 145f, 144f, GlucoseReadingSource.CLONE_TURN, 4_000L),
        ).sortedBy(HistoryReading::timestamp)

        val expected = listOf(cloneFirst, nightscoutFirst)
        assertEquals(expected, HistoryDisplayMerge.mergeReadings(copies, "physical-sensor"))
        assertEquals(expected, HistoryDisplayMerge.mergeReadings(copies, "NSF-TEST"))
    }

    private fun reading(
        id: Long,
        timestamp: Long,
        sensorSerial: String,
        value: Float,
        rawValue: Float,
        source: String = GlucoseReadingSource.SENSOR,
        firstStoredAt: Long = id,
    ) = HistoryReading(
        id = id,
        timestamp = timestamp,
        sensorSerial = sensorSerial,
        value = value,
        rawValue = rawValue,
        rate = null,
        source = source,
        firstStoredAt = firstStoredAt,
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

    /**
     * The reported gaps, from a trace with three sensors:
     * `liveMain=70D07E2552DB, selectedMain=BB368A3`.
     *
     * The preferred sensor is the user's selected main, which had gone quiet
     * while two others streamed. With only two ranks — preferred, then everyone
     * else — it suppressed nothing, so both live sensors' readings passed through
     * and interleaved minute by minute. The chart starts a segment on every
     * sensor change, so a complete stream drew as disconnected fragments.
     *
     * At most one sensor may contribute to a stretch, and it should be the one
     * still reading.
     */
    @Test
    fun twoLiveSensorsDoNotInterleaveWhenThePreferredOneHasGoneQuiet() {
        val readings = ArrayList<HistoryReading>()
        var id = 0L
        // The selected main stopped an hour ago.
        for (minute in 0 until 10) {
            readings.add(reading(++id, minute * MINUTE_MS, "selected-main", 100f, 100f))
        }
        // Two other sensors have been streaming since, alternating in the list.
        for (minute in 60 until 90) {
            readings.add(reading(++id, minute * MINUTE_MS, "live-a", 110f, 110f))
            readings.add(reading(++id, minute * MINUTE_MS + 20_000L, "live-b", 60f, 60f))
        }

        val merged = HistoryDisplayMerge.mergeReadings(
            readings.sortedBy { it.timestamp },
            preferredSerial = "selected-main"
        )

        val recent = merged.filter { it.timestamp >= 60 * MINUTE_MS }
        assertEquals(
            "one sensor must own the recent stretch, not two alternating",
            1,
            recent.map { it.sensorSerial }.distinct().size
        )
        // live-b reads last, so it outranks live-a for that stretch.
        assertEquals("live-b", recent.first().sensorSerial)
        // And the quiet selected main keeps its own earlier stretch.
        assertEquals(10, merged.count { it.sensorSerial == "selected-main" })
    }

    /**
     * The rank must not reorder history: a retired sensor still owns the stretch
     * that predates the one which replaced it, however long ago it stopped.
     */
    @Test
    fun aRetiredSensorStillOwnsTheStretchBeforeTheOneThatReplacedIt() {
        val readings = ArrayList<HistoryReading>()
        var id = 0L
        for (minute in 0 until 60) {
            readings.add(reading(++id, minute * MINUTE_MS, "sensor-old", 90f, 90f))
        }
        for (minute in 90 until 150) {
            readings.add(reading(++id, minute * MINUTE_MS, "sensor-new", 100f, 100f))
        }

        val merged = HistoryDisplayMerge.mergeReadings(readings, preferredSerial = "sensor-new")

        assertEquals(60, merged.count { it.sensorSerial == "sensor-old" })
        assertEquals(60, merged.count { it.sensorSerial == "sensor-new" })
    }
}
