package tk.glucodata.data.calibration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SeriesCalibratorTests {
    private val hour = 60L * 60L * 1000L
    private val start = 1_760_000_000_000L

    private fun points(): List<CalPoint> = listOf(
        CalPoint(x = 110.0, y = 118.0, timestamp = start + 6 * hour),
        CalPoint(x = 150.0, y = 141.0, timestamp = start + 30 * hour),
        CalPoint(x = 90.0, y = 97.0, timestamp = start + 54 * hour, isEnabled = false),
        CalPoint(x = 200.0, y = 188.0, timestamp = start + 80 * hour),
    )

    /** What CalibrationManager.getCalibratedValue computes once it has a context: the reference. */
    private fun reference(
        all: List<CalPoint>,
        value: Float,
        timestamp: Long,
        tuning: CalibrationTuning,
        applyToPast: Boolean,
    ): Float {
        val earliest = all.filter { it.isEnabled }.minByOrNull { it.timestamp }
        val resolved = CalibrationMath.resolvePointsForTimestamp(all, timestamp, earliest, tuning)
        if (resolved.isEmpty()) return value
        val computation = CalibrationMath.computeAlgorithm(tuning.algorithm, value.toDouble(), timestamp, resolved, tuning)
        val calibrated = CalibrationMath.sanitizeCalibratedValue(computation.prediction, value)
        return if (applyToPast) calibrated else CalibrationMath.applyPastPolicy(value, calibrated, timestamp, resolved)
    }

    private fun calibrator(tuning: CalibrationTuning, applyToPast: Boolean, all: List<CalPoint> = points()) =
        SeriesCalibrator(
            allPoints = all,
            earliestPoint = all.filter { it.isEnabled }.minByOrNull { it.timestamp },
            algorithm = tuning.algorithm,
            tuning = tuning,
            applyToPast = applyToPast,
        )

    private val tunings = listOf(
        CalibrationTuning.DEFAULT,
        CalibrationTuning.DEFAULT.copy(algorithm = CalibrationMath.ALG_ADAPTIVE_ENSEMBLE),
        CalibrationTuning.DEFAULT.copy(algorithm = CalibrationMath.ALG_ADAPTIVE_ENSEMBLE, keepDisabledHistory = true),
        CalibrationTuning.DEFAULT.copy(algorithm = CalibrationMath.ALG_XDRIP_MEDIAN_SLOPE, lockPastHistory = false),
        CalibrationTuning.DEFAULT.copy(algorithm = CalibrationMath.ALG_ELASTIC_TIME_WEIGHTED_INTERPOLATION, applyToPast = true),
        CalibrationTuning.DEFAULT.copy(algorithm = CalibrationMath.ALG_TIME_WEIGHTED_ROBUST_REGRESSION, weightMode = CalibrationMath.WEIGHT_STABLE),
    )

    @Test
    fun matchesThePerValueComputationAcrossTheWholeTimeline() {
        for (tuning in tunings) for (applyToPast in listOf(false, true)) {
            val all = points()
            val series = calibrator(tuning, applyToPast, all)
            var index = 0
            var timestamp = start - 2 * hour
            while (timestamp < start + 100 * hour) {
                val value = 80f + (index * 13 % 140)
                val expected = reference(all, value, timestamp, tuning, applyToPast)
                val actual = series.calibrate(value, timestamp)
                assertEquals("tuning=$tuning applyToPast=$applyToPast t=$timestamp", expected, actual, 0f)
                timestamp += 60_000L * 7
                index++
            }
        }
    }

    @Test
    fun resolvesThePointSetOncePerStretchBetweenCalibrations() {
        val series = calibrator(CalibrationTuning.DEFAULT.copy(keepDisabledHistory = true), applyToPast = false)
        var timestamp = start - hour
        while (timestamp < start + 100 * hour) {
            series.calibrate(120f, timestamp)
            timestamp += 60_000L
        }
        // Before the first point, and after each of the four: five stretches.
        assertEquals(5, series.resolvedStretchCount)
    }

    @Test
    fun leavesNonReadingsAlone() {
        val series = calibrator(CalibrationTuning.DEFAULT, applyToPast = false)
        assertEquals(0f, series.calibrate(0f, start + 10 * hour), 0f)
        assertEquals(-3f, series.calibrate(-3f, start + 10 * hour), 0f)
        assertTrue(series.calibrate(Float.NaN, start + 10 * hour).isNaN())
        assertEquals(0, series.cachedResultCount)
    }

    @Test
    fun rememberedResultIsInvalidatedWhenTheValueAtATimestampMoves() {
        val series = calibrator(CalibrationTuning.DEFAULT, applyToPast = false)
        val t = start + 40 * hour
        val first = series.calibrate(120f, t)
        val second = series.calibrate(130f, t)
        assertTrue(first != second)
        assertEquals(reference(points(), 130f, t, CalibrationTuning.DEFAULT, false), second, 0f)
        assertEquals(1, series.cachedResultCount)
    }

    @Test
    fun resultCacheSurvivesGrowthAndDistinguishesValueBits() {
        val cache = LongFloatResultCache(initialCapacity = 16)
        val n = 50_000
        for (i in 0 until n) cache.put(start + i * 60_000L, i, i.toFloat())
        assertEquals(n, cache.size)
        for (i in 0 until n step 997) {
            assertEquals(i.toFloat(), cache.get(start + i * 60_000L, i)!!, 0f)
            assertNull(cache.get(start + i * 60_000L, i + 1))
        }
        assertNull(cache.get(start - 60_000L, 0))
        cache.put(start, 7, 42f)
        assertEquals(n, cache.size)
        assertEquals(42f, cache.get(start, 7)!!, 0f)
    }
}
