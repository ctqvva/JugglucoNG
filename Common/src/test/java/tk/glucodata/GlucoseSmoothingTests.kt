package tk.glucodata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The chart smoothing pipeline now shared by the phone and the watch. The
 * properties worth pinning are the ones that make a smoothed curve honest: a
 * hole must not be averaged across, and a hole must not be filled in.
 */
class GlucoseSmoothingTests {

    private data class P(
        val timestamp: Long,
        val value: Float,
        val rawValue: Float = 0f,
        val serial: String? = null,
    )

    private val minute = 60_000L

    private fun smooth(
        points: List<P>,
        minutes: Int,
        collapse: Boolean = false,
        now: Long = Long.MAX_VALUE / 2,
    ) = GlucoseSmoothing.smooth(
        points = points,
        smoothingMinutes = minutes,
        collapseIntoChunks = collapse,
        timestamp = { it.timestamp },
        value = { it.value },
        rawValue = { it.rawValue },
        sensorSerial = { it.serial },
        withValues = { p, auto, raw -> p.copy(value = auto, rawValue = raw) },
        nowMillis = now,
    )

    private fun series(vararg values: Float, startAt: Long = 0L, stepMs: Long = minute) =
        values.mapIndexed { index, v -> P(startAt + index * stepMs, v, v) }

    @Test
    fun disabledSmoothingReturnsTheInputUntouched() {
        val points = series(100f, 180f, 100f)
        assertEquals(points, smooth(points, minutes = 0))
    }

    @Test
    fun tooFewPointsAreLeftAlone() {
        val points = series(100f, 180f)
        assertEquals(points, smooth(points, minutes = 15))
    }

    @Test
    fun aSpikeIsPulledTowardItsNeighbours() {
        val points = series(100f, 100f, 200f, 100f, 100f)
        val smoothed = smooth(points, minutes = 5)
        val spike = smoothed[2].value
        assertTrue("spike $spike should fall below the raw 200", spike < 200f)
        assertTrue("spike $spike should stay above the surrounding 100", spike > 100f)
    }

    @Test
    fun constantAndLinearBoundariesStayOnTheTraceAtSupportedCadences() {
        val windows = listOf(5, 10, 13)
        val cadences = listOf(minute, 5 * minute)

        for (cadence in cadences) {
            val constant = (0..12).map { P(it * cadence, 123f, 77f) }
            val rising = (0..12).map { index ->
                val at = index * cadence
                val minutes = at / minute
                P(at, 90f + 2f * minutes, 60f + minutes)
            }
            for (window in windows) {
                val constantSmoothed = smooth(constant, minutes = window)
                constantSmoothed.forEach {
                    assertEquals(123f, it.value, 1e-3f)
                    assertEquals(77f, it.rawValue, 1e-3f)
                }

                val risingSmoothed = smooth(rising, minutes = window)
                assertEquals(rising.first().value, risingSmoothed.first().value, 1e-3f)
                assertEquals(rising.last().value, risingSmoothed.last().value, 1e-3f)
                assertEquals(rising.first().rawValue, risingSmoothed.first().rawValue, 1e-3f)
                assertEquals(rising.last().rawValue, risingSmoothed.last().rawValue, 1e-3f)
            }
        }
    }

    @Test
    fun irregularCadenceBoundariesStayOnAnExactLine() {
        val timestamps = listOf(0L, 70_000L, 190_000L, 310_000L, 590_000L, 760_000L)
        val points = timestamps.map { at ->
            val minutes = at / 60_000.0
            P(at, (100.0 + 1.5 * minutes).toFloat(), (80.0 - 0.5 * minutes).toFloat())
        }

        for (window in listOf(5, 10, 13)) {
            val smoothed = smooth(points, minutes = window)
            assertEquals(points.first().value, smoothed.first().value, 1e-3f)
            assertEquals(points.last().value, smoothed.last().value, 1e-3f)
            assertEquals(points.first().rawValue, smoothed.first().rawValue, 1e-3f)
            assertEquals(points.last().rawValue, smoothed.last().rawValue, 1e-3f)
        }
    }

    @Test
    fun noisyEndpointIsReducedAndBoundaryFitCannotExceedMeasuredRange() {
        val spike = smooth(series(100f, 100f, 100f, 100f, 200f), minutes = 5).last().value
        assertTrue("endpoint spike $spike should be reduced", spike >= 100f && spike < 200f)

        val wouldOvershoot = smooth(series(100f, 200f, 200f), minutes = 5).last().value
        assertEquals(200f, wouldOvershoot, 1e-3f)
    }

    @Test
    fun duplicateTimestampsUseTheMeanFallback() {
        val points = listOf(P(0L, 100f), P(0L, 110f), P(0L, 120f))
        val smoothed = smooth(points, minutes = 5)
        smoothed.forEach { assertEquals(110f, it.value, 1e-3f) }
    }

    @Test
    fun bothLanesSmoothIndependently() {
        val points = listOf(
            P(0, 100f, 50f),
            P(minute, 200f, 50f),
            P(2 * minute, 100f, 50f),
        )
        val smoothed = smooth(points, minutes = 5)
        // The auto lane varies and moves; the flat raw lane cannot.
        assertTrue(smoothed[1].value < 200f)
        assertEquals(50f, smoothed[1].rawValue, 1e-3f)
    }

    @Test
    fun aGapIsNotAveragedAcross() {
        // Two flat runs at different levels, separated by well over the gap
        // threshold. Averaging across would drag both toward each other.
        val gap = GlucoseChartGap.THRESHOLD_MS + minute
        val before = series(100f, 100f, 100f)
        val after = series(200f, 200f, 200f, startAt = 2 * minute + gap)
        val smoothed = smooth(before + after, minutes = 120)

        smoothed.take(3).forEach { assertEquals(100f, it.value, 1e-3f) }
        smoothed.drop(3).forEach { assertEquals(200f, it.value, 1e-3f) }
    }

    @Test
    fun aSensorChangeSplitsTheSeriesToo() {
        val a = (0..2).map { P(it * minute, 100f, 100f, serial = "AAA") }
        val b = (3..5).map { P(it * minute, 200f, 200f, serial = "BBB") }
        val smoothed = smooth(a + b, minutes = 120)
        smoothed.take(3).forEach { assertEquals(100f, it.value, 1e-3f) }
        smoothed.drop(3).forEach { assertEquals(200f, it.value, 1e-3f) }
    }

    @Test
    fun holesAreCarriedThroughRatherThanInvented() {
        // A zero raw lane is "no reading", not a value to average in: smoothing
        // must not conjure a raw number the sensor never produced.
        val points = listOf(
            P(0, 100f, 90f),
            P(minute, 110f, 0f),
            P(2 * minute, 120f, 110f),
        )
        val smoothed = smooth(points, minutes = 15)
        assertEquals(0f, smoothed[1].rawValue, 1e-6f)
    }

    @Test
    fun collapseKeepsOneReadingPerClosedInterval() {
        // 5-minute smoothing collapses to 5-minute buckets; 12 minute-spaced
        // readings therefore reduce, and every survivor is a real timestamp.
        val points = series(*FloatArray(12) { 100f + it })
        val now = 60L * minute
        val collapsed = smooth(points, minutes = 5, collapse = true, now = now)
        assertTrue("expected fewer than ${points.size}, got ${collapsed.size}", collapsed.size < points.size)
        assertTrue(collapsed.all { c -> points.any { it.timestamp == c.timestamp } })
    }

    @Test
    fun collapseNeverEmptiesAShortSeries() {
        // Everything inside the still-open bucket: returning nothing would blank
        // the chart, so the newest reading has to survive.
        val now = 3 * minute
        val points = series(100f, 101f, 102f, startAt = 0L)
        val collapsed = smooth(points, minutes = 5, collapse = true, now = now)
        assertTrue(collapsed.isNotEmpty())
    }

    @Test
    fun segmentsSplitOnGapsAndSensorChangesOnly() {
        val gap = GlucoseChartGap.THRESHOLD_MS + minute
        val points = listOf(
            P(0, 100f), P(minute, 100f),
            P(minute + gap, 100f),
        )
        assertEquals(2, GlucoseSmoothing.splitSegments(points, { it.timestamp }).size)
        assertEquals(1, GlucoseSmoothing.splitSegments(points.take(2), { it.timestamp }).size)
        assertEquals(0, GlucoseSmoothing.splitSegments(emptyList<P>(), { it.timestamp }).size)
    }
}
