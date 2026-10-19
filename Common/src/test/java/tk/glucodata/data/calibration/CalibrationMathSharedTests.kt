package tk.glucodata.data.calibration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The phone and the watch now run this same computation, so a value shown on one
 * has to match the other. These pin the behaviour the extraction had to preserve.
 */
class CalibrationMathSharedTests {
    private val hour = 3_600_000L
    private val now = 1_700_000_000_000L

    private val tuning = CalibrationTuning.DEFAULT

    @Test
    fun singleAnchorAppliesAPlainOffset() {
        val points = listOf(CalPoint(x = 100.0, y = 120.0, timestamp = now - hour))

        val result = CalibrationMath.computeAlgorithm(
            algorithm = tuning.algorithm,
            targetValue = 150.0,
            targetTimestamp = now,
            points = points,
            tuning = tuning,
        )

        // +20 offset carried forward, exactly as the phone did before the move.
        assertEquals(170.0, result.prediction, 1e-9)
        assertEquals(20.0, result.offset!!, 1e-9)
    }

    @Test
    fun twoAnchorsProduceAFitBetweenThem() {
        val points = listOf(
            CalPoint(x = 100.0, y = 110.0, timestamp = now - 3 * hour),
            CalPoint(x = 200.0, y = 220.0, timestamp = now - hour),
        )

        val result = CalibrationMath.computeAlgorithm(
            algorithm = tuning.algorithm,
            targetValue = 150.0,
            targetTimestamp = now,
            points = points,
            tuning = tuning,
        )

        // Both anchors read 10% high, so a midpoint reading should be corrected
        // upward by roughly the same proportion.
        assertTrue("expected a correction above the raw value", result.prediction > 150.0)
        assertTrue("correction ran away: ${result.prediction}", result.prediction < 200.0)
    }

    @Test
    fun everyAlgorithmIsReachableAndSane() {
        val points = listOf(
            CalPoint(x = 90.0, y = 100.0, timestamp = now - 5 * hour),
            CalPoint(x = 140.0, y = 150.0, timestamp = now - 2 * hour),
            CalPoint(x = 180.0, y = 195.0, timestamp = now - hour),
        )
        val algorithms = listOf(
            CalibrationMath.ALG_SANE_WEIGHTED_OLS,
            CalibrationMath.ALG_XDRIP_MEDIAN_SLOPE,
            CalibrationMath.ALG_TIME_WEIGHTED_ROBUST_REGRESSION,
            CalibrationMath.ALG_ELASTIC_TIME_WEIGHTED_INTERPOLATION,
            CalibrationMath.ALG_ADAPTIVE_ENSEMBLE,
        )

        algorithms.forEach { algorithm ->
            val result = CalibrationMath.computeAlgorithm(
                algorithm = algorithm,
                targetValue = 120.0,
                targetTimestamp = now,
                points = points,
                tuning = tuning.copy(algorithm = algorithm),
            )
            assertTrue(
                "$algorithm produced ${result.prediction}",
                result.prediction.isFinite() && result.prediction in 60.0..260.0,
            )
        }
    }

    @Test
    fun anUnknownAlgorithmFallsBackInsteadOfChangingTheNumbers() {
        val points = listOf(
            CalPoint(x = 100.0, y = 110.0, timestamp = now - 2 * hour),
            CalPoint(x = 150.0, y = 165.0, timestamp = now - hour),
        )

        val fallback = CalibrationMath.computeAlgorithm(
            algorithm = "an_algorithm_from_a_newer_build",
            targetValue = 130.0,
            targetTimestamp = now,
            points = points,
            tuning = tuning.copy(algorithm = "an_algorithm_from_a_newer_build"),
        )
        val default = CalibrationMath.computeAlgorithm(
            algorithm = CalibrationMath.ALG_SANE_WEIGHTED_OLS,
            targetValue = 130.0,
            targetTimestamp = now,
            points = points,
            tuning = tuning,
        )

        assertEquals(default.prediction, fallback.prediction, 1e-9)
    }

    @Test
    fun weightModeChangesHowQuicklyOldAnchorsFade() {
        val points = listOf(
            CalPoint(x = 100.0, y = 130.0, timestamp = now - 20 * hour),
            CalPoint(x = 100.0, y = 105.0, timestamp = now - hour),
        )

        fun predict(weightMode: String): Double = CalibrationMath.computeAlgorithm(
            algorithm = CalibrationMath.ALG_SANE_WEIGHTED_OLS,
            targetValue = 100.0,
            targetTimestamp = now,
            points = points,
            tuning = tuning.copy(weightMode = weightMode),
        ).prediction

        val fresh = predict(CalibrationMath.WEIGHT_FRESH)
        val stable = predict(CalibrationMath.WEIGHT_STABLE)

        // "Fresh" leans on the recent anchor, so it should sit closer to it than
        // "stable" does — the setting has to survive the trip to the watch.
        assertTrue("fresh=$fresh stable=$stable", fresh < stable)
    }

    @Test
    fun freshModeStronglyPrefersANewerAnchor() {
        val points = listOf(
            CalPoint(x = 100.0, y = 140.0, timestamp = now - 18 * hour),
            CalPoint(x = 100.0, y = 105.0, timestamp = now - hour),
        )

        fun predict(weightMode: String): Double = CalibrationMath.computeAlgorithm(
            algorithm = CalibrationMath.ALG_SANE_WEIGHTED_OLS,
            targetValue = 100.0,
            targetTimestamp = now,
            points = points,
            tuning = tuning.copy(weightMode = weightMode),
        ).prediction

        val fresh = predict(CalibrationMath.WEIGHT_FRESH)
        val balanced = predict(CalibrationMath.WEIGHT_BALANCED)

        assertTrue("fresh=$fresh balanced=$balanced", fresh < balanced - 3.0)
    }

    @Test
    fun invalidReadingsAreLeftAlone() {
        assertEquals(0f, CalibrationMath.sanitizeCalibratedValue(Double.NaN, 0f), 1e-9f)
        assertEquals(120f, CalibrationMath.sanitizeCalibratedValue(Double.NaN, 120f), 1e-9f)
    }

    @Test
    fun lockedPastHistoryOnlyUsesAnchorsThatExistedYet() {
        val older = CalPoint(x = 100.0, y = 110.0, timestamp = now - 5 * hour)
        val newer = CalPoint(x = 100.0, y = 130.0, timestamp = now - hour)

        val atMiddle = CalibrationMath.resolvePointsForTimestamp(
            allPoints = listOf(older, newer),
            targetTimestamp = now - 3 * hour,
            earliestPoint = older,
            tuning = tuning.copy(lockPastHistory = true),
        )

        assertEquals(listOf(older), atMiddle)
    }
}
