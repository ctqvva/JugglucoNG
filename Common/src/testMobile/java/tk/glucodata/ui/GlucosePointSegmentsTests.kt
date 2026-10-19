package tk.glucodata.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class GlucosePointSegmentsTests {
    private companion object {
        const val MINUTE_MS = 60_000L
    }

    @Test
    fun split_breaksSegmentsWhenSensorChanges() {
        val segments = GlucosePointSegments.split(
            listOf(
                point(1 * MINUTE_MS, "sensor-old"),
                point(2 * MINUTE_MS, "sensor-old"),
                point(3 * MINUTE_MS, "sensor-new"),
                point(4 * MINUTE_MS, "sensor-new")
            )
        )

        assertEquals(listOf(2, 2), segments.map { it.size })
        assertEquals(listOf("sensor-old", "sensor-new"), segments.map { it.first().sensorSerial })
    }

    @Test
    fun split_breaksSegmentsWhenGapExceedsThreshold() {
        val segments = GlucosePointSegments.split(
            listOf(
                point(1 * MINUTE_MS, "sensor-a"),
                point(2 * MINUTE_MS, "sensor-a"),
                point(25 * MINUTE_MS, "sensor-a")
            )
        )

        assertEquals(listOf(2, 1), segments.map { it.size })
    }

    /**
     * The reported case: BLE streaming drops, the stretch is covered only by 15-minute
     * NFC history, and each slot carries the second-offset of whichever scan or packet
     * wrote it. Every one of those drifted steps has to stay connected — the data is
     * complete, just coarse.
     */
    @Test
    fun split_keepsDriftedFifteenMinuteHistoryConnectedToTheStreamAroundIt() {
        val base = 3_600_000L
        val segments = GlucosePointSegments.split(
            listOf(
                point(base, "sensor-a"),                              // last 1-min poll
                point(base + 1 * MINUTE_MS, "sensor-a"),
                point(base + 13 * MINUTE_MS + 35_000L, "sensor-a"),   // history only
                point(base + 28 * MINUTE_MS + 22_000L, "sensor-a"),   // +14m47s
                point(base + 44 * MINUTE_MS + 21_000L, "sensor-a"),   // +15m59s, worst drift
                point(base + 45 * MINUTE_MS, "sensor-a"),             // stream resumes
                point(base + 46 * MINUTE_MS, "sensor-a")
            )
        )

        assertEquals(listOf(7), segments.map { it.size })
    }

    /** A genuinely missing history slot is a hole and must still break the curve. */
    @Test
    fun split_breaksWhenAHistorySlotIsActuallyMissing() {
        val base = 3_600_000L
        val segments = GlucosePointSegments.split(
            listOf(
                point(base, "sensor-a"),
                point(base + 15 * MINUTE_MS, "sensor-a"),
                point(base + 45 * MINUTE_MS, "sensor-a")
            )
        )

        assertEquals(listOf(2, 1), segments.map { it.size })
    }

    /** Worst-case drift between two adjacent slots still connects; 17 min does not. */
    @Test
    fun split_thresholdSitsBetweenSlotDriftAndARealHole() {
        val base = 3_600_000L

        assertEquals(
            listOf(2),
            GlucosePointSegments.split(
                listOf(point(base, "s"), point(base + 15 * MINUTE_MS + 59_000L, "s"))
            ).map { it.size }
        )
        assertEquals(
            listOf(1, 1),
            GlucosePointSegments.split(
                listOf(point(base, "s"), point(base + 17 * MINUTE_MS + 1L, "s"))
            ).map { it.size }
        )
    }

    private fun point(timestamp: Long, sensorSerial: String) = GlucosePoint(
        value = 100f,
        time = "",
        timestamp = timestamp,
        rawValue = 95f,
        sensorSerial = sensorSerial
    )

    /**
     * The reported artifact: a one-minute hole in the live stream filled by a row
     * no sensor owns, which the merge keeps on purpose.
     *
     * Breaking on it drew two holes with an orphaned fragment between them, at a
     * moment when nothing was actually missing. Nothing here is more than a
     * minute apart, so the only thing that could have split it was identity.
     */
    @Test
    fun split_doesNotBreakForARowNoSensorOwns() {
        val segments = GlucosePointSegments.split(
            listOf(
                point(1 * MINUTE_MS, "sensor-a"),
                point(2 * MINUTE_MS, "sensor-a"),
                point(3 * MINUTE_MS, "unknown"),
                point(4 * MINUTE_MS, "sensor-a"),
                point(5 * MINUTE_MS, "sensor-a")
            )
        )

        assertEquals(listOf(5), segments.map { it.size })
    }

    @Test
    fun split_doesNotBreakForAnImportedRowEither() {
        val segments = GlucosePointSegments.split(
            listOf(
                point(1 * MINUTE_MS, "sensor-a"),
                point(2 * MINUTE_MS, tk.glucodata.data.HistoryRepository.IMPORTED_SENSOR_SERIAL),
                point(3 * MINUTE_MS, "sensor-a")
            )
        )

        assertEquals(listOf(3), segments.map { it.size })
    }

    /**
     * A real swap still breaks, even with an unowned row sitting on the boundary.
     *
     * This is what makes the rule "compare with the last owned serial" rather
     * than "compare with the previous point": pairwise, neither
     * sensor-old→unknown nor unknown→sensor-new differs, and a genuine handover
     * would draw as one continuous line.
     *
     * The unowned row joins the run before it. Nothing attributes it to either
     * sensor, so the choice is arbitrary; being contiguous with what precedes it
     * at least makes it deterministic.
     */
    @Test
    fun split_stillBreaksBetweenTwoRealSensorsSeparatedByAnUnownedRow() {
        val segments = GlucosePointSegments.split(
            listOf(
                point(1 * MINUTE_MS, "sensor-old"),
                point(2 * MINUTE_MS, "unknown"),
                point(3 * MINUTE_MS, "sensor-new")
            )
        )

        assertEquals(2, segments.size)
        assertEquals(listOf("sensor-old", "sensor-new"), segments.map { it.first().sensorSerial })
        assertEquals(listOf(2, 1), segments.map { it.size })
    }
}
