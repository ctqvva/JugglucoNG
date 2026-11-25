package tk.glucodata.data.calibration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class AdaptiveEnsembleUnitTests {
    private val hour = 3_600_000L
    private val now = 1_700_000_000_000L
    private val mgdlPerMmol = 18.0182
    private val unitInvarianceTolerance = 1e-6
    private val predictionToleranceMgdl = 1e-4

    private val tuning = CalibrationTuning.DEFAULT.copy(algorithm = CalibrationMath.ALG_ADAPTIVE_ENSEMBLE)

    private val points = listOf(
        CalPoint(x = 90.0, y = 110.0, timestamp = now - 4 * hour),
        CalPoint(x = 120.0, y = 105.0, timestamp = now - 3 * hour),
        CalPoint(x = 160.0, y = 175.0, timestamp = now - 2 * hour),
        CalPoint(x = 220.0, y = 200.0, timestamp = now - hour),
    )

    private fun resultIn(
        points: List<CalPoint>,
        targetMgdl: Double,
        factor: Double,
        algorithm: String = CalibrationMath.ALG_ADAPTIVE_ENSEMBLE,
        tuningOverride: CalibrationTuning = tuning,
    ) = CalibrationMath.computeAlgorithm(
        algorithm = algorithm,
        targetValue = targetMgdl * factor,
        targetTimestamp = now,
        points = points.map { it.copy(x = it.x * factor, y = it.y * factor) },
        tuning = tuningOverride,
    )

    @Test
    fun anchorInfluenceDoesNotDependOnGlucoseUnit() {
        val mgdl = resultIn(points, 130.0, 1.0)
        val mmol = resultIn(points, 130.0, 1.0 / mgdlPerMmol)

        assertEquals(mgdl.anchorInfluence!!, mmol.anchorInfluence!!, 1e-9)
    }

    @Test
    fun confidenceDoesNotDependOnGlucoseUnit() {
        val mgdl = resultIn(points, 130.0, 1.0)
        val mmol = resultIn(points, 130.0, 1.0 / mgdlPerMmol)

        assertEquals(mgdl.confidence!!, mmol.confidence!!, unitInvarianceTolerance)
    }

    @Test
    fun predictionDoesNotDependOnGlucoseUnit() {
        val mgdl = resultIn(points, 130.0, 1.0)
        val mmol = resultIn(points, 130.0, 1.0 / mgdlPerMmol)

        assertEquals(
            mgdl.prediction,
            mmol.prediction * mgdlPerMmol,
            predictionToleranceMgdl,
        )
    }

    @Test
    fun weightSwitchBranchDoesNotDependOnGlucoseUnit() {
        val points = listOf(
            CalPoint(x = 109.0, y = 231.0, timestamp = now - 4 * hour),
            CalPoint(x = 174.0, y = 58.0, timestamp = now - hour),
        )
        val mgdl = resultIn(points, 125.0, 1.0)
        val mmol = resultIn(points, 125.0, 1.0 / mgdlPerMmol)

        assertTrue(mgdl.confidence!! < 0.50)
        assertTrue(mmol.confidence!! < 0.50)
        assertEquals(mgdl.anchorInfluence!!, mmol.anchorInfluence!!, 1e-9)
        assertEquals(mgdl.confidence!!, mmol.confidence!!, unitInvarianceTolerance)
        assertEquals(
            mgdl.prediction,
            mmol.prediction * mgdlPerMmol,
            predictionToleranceMgdl,
        )
    }

    @Test
    fun twoAnchorEnsembleDoesNotDependOnGlucoseUnit() {
        val points = listOf(
            CalPoint(x = 100.0, y = 115.0, timestamp = now - 3 * hour),
            CalPoint(x = 160.0, y = 150.0, timestamp = now - hour),
        )
        val mgdl = resultIn(points, 130.0, 1.0)
        val mmol = resultIn(points, 130.0, 1.0 / mgdlPerMmol)

        assertEquals(mgdl.anchorInfluence!!, mmol.anchorInfluence!!, 1e-9)
        assertEquals(
            mgdl.prediction,
            mmol.prediction * mgdlPerMmol,
            predictionToleranceMgdl,
        )
    }

    @Test
    fun coincidentAnchorsDoNotDependOnGlucoseUnit() {
        val points = listOf(
            CalPoint(x = 120.0, y = 130.0, timestamp = now - 2 * hour),
            CalPoint(x = 120.0, y = 145.0, timestamp = now - hour),
        )
        val mgdl = resultIn(points, 120.0, 1.0)
        val mmol = resultIn(points, 120.0, 1.0 / mgdlPerMmol)

        assertEquals(mgdl.anchorInfluence!!, mmol.anchorInfluence!!, 1e-9)
        assertEquals(mgdl.confidence!!, mmol.confidence!!, 1e-9)
        assertEquals(mgdl.prediction, mmol.prediction * mgdlPerMmol, predictionToleranceMgdl)
    }

    @Test
    fun everyAlgorithmIsUnitInvariant() {
        val algorithms = listOf(
            CalibrationMath.ALG_SANE_WEIGHTED_OLS,
            CalibrationMath.ALG_XDRIP_MEDIAN_SLOPE,
            CalibrationMath.ALG_TIME_WEIGHTED_ROBUST_REGRESSION,
            CalibrationMath.ALG_ELASTIC_TIME_WEIGHTED_INTERPOLATION,
            CalibrationMath.ALG_ADAPTIVE_ENSEMBLE,
        )

        algorithms.forEach { algorithm ->
            val mgdl = resultIn(points, 130.0, 1.0, algorithm)
            val mmol = resultIn(points, 130.0, 1.0 / mgdlPerMmol, algorithm)

            assertEquals("$algorithm anchorInfluence", mgdl.anchorInfluence!!, mmol.anchorInfluence!!, 1e-9)
            assertEquals("$algorithm confidence", mgdl.confidence!!, mmol.confidence!!, unitInvarianceTolerance)
            assertEquals(
                "$algorithm prediction",
                mgdl.prediction,
                mmol.prediction * mgdlPerMmol,
                predictionToleranceMgdl,
            )
        }
    }

    @Test
    fun relativeFloorsStayInvariantForTightlyClusteredPoints() {
        val tightFit = listOf(
            CalPoint(x = 100.01, y = 100.02, timestamp = now - 3 * hour),
            CalPoint(x = 130.01, y = 130.02, timestamp = now - 2 * hour),
            CalPoint(x = 160.01, y = 160.02, timestamp = now - hour),
        )
        val tightCluster = listOf(
            CalPoint(x = 128.0, y = 130.0, timestamp = now - 2 * hour),
            CalPoint(x = 132.0, y = 135.0, timestamp = now - hour),
        )

        listOf(tightFit, tightCluster).forEach { scenario ->
            listOf(
                CalibrationMath.ALG_SANE_WEIGHTED_OLS,
                CalibrationMath.ALG_TIME_WEIGHTED_ROBUST_REGRESSION,
                CalibrationMath.ALG_ELASTIC_TIME_WEIGHTED_INTERPOLATION,
                CalibrationMath.ALG_ADAPTIVE_ENSEMBLE,
            ).forEach { algorithm ->
                val mgdl = resultIn(scenario, 130.0, 1.0, algorithm)
                val mmol = resultIn(scenario, 130.0, 1.0 / mgdlPerMmol, algorithm)

                assertEquals("$algorithm anchorInfluence", mgdl.anchorInfluence!!, mmol.anchorInfluence!!, 1e-9)
                assertEquals("$algorithm confidence", mgdl.confidence!!, mmol.confidence!!, unitInvarianceTolerance)
                assertEquals(
                    "$algorithm prediction",
                    mgdl.prediction,
                    mmol.prediction * mgdlPerMmol,
                    predictionToleranceMgdl,
                )
            }
        }
    }

    @Test
    fun nearlyCoincidentAnchorsChooseTheSameFitInBothUnits() {
        val close = listOf(
            CalPoint(x = 100.0, y = 110.0, timestamp = now - 3 * hour),
            CalPoint(x = 100.0001, y = 110.0001, timestamp = now - hour),
        )
        val mgdl = resultIn(close, 130.0, 1.0, CalibrationMath.ALG_SANE_WEIGHTED_OLS)
        val mmol = resultIn(close, 130.0, 1.0 / mgdlPerMmol, CalibrationMath.ALG_SANE_WEIGHTED_OLS)

        assertNotNull(mgdl.slope)
        assertNotNull(mmol.slope)
        assertEquals(mgdl.slope!!, mmol.slope!!, 1e-9)
        assertEquals(mgdl.prediction, mmol.prediction * mgdlPerMmol, predictionToleranceMgdl)
    }

    @Test
    fun predictionStaysContinuousAcrossNearestAnchorTransitions() {
        val conflicting = listOf(
            CalPoint(x = 100.0, y = 130.0, timestamp = now - 3 * hour),
            CalPoint(x = 160.0, y = 100.0, timestamp = now - hour),
        )
        val values = (0..120).map { step ->
            resultIn(conflicting, 100.0 + step * 0.5, 1.0, CalibrationMath.ALG_SANE_WEIGHTED_OLS).prediction
        }
        val maxStep = values.zipWithNext().maxOf { abs(it.second - it.first) }

        assertTrue("max step $maxStep mg/dL", maxStep < 1.0)
    }

    @Test
    fun infinitesimalInputChangeDoesNotJumpAtAnchorTransition() {
        val anchors = listOf(
            CalPoint(x = 100.0, y = 120.0, timestamp = now - 2 * hour),
            CalPoint(x = 160.0, y = 140.0, timestamp = now - hour),
        )
        val before = resultIn(anchors, 127.4999, 1.0, CalibrationMath.ALG_SANE_WEIGHTED_OLS).prediction
        val after = resultIn(anchors, 127.5001, 1.0, CalibrationMath.ALG_SANE_WEIGHTED_OLS).prediction

        assertTrue("jump ${abs(after - before)} mg/dL", abs(after - before) < 0.01)
    }

    @Test
    fun elasticAndEnsembleDoNotJumpAtAnchorMidpoint() {
        val anchors = listOf(
            CalPoint(x = 100.0, y = 120.0, timestamp = now - 2 * hour),
            CalPoint(x = 160.0, y = 140.0, timestamp = now - hour),
        )
        listOf(
            CalibrationMath.ALG_ELASTIC_TIME_WEIGHTED_INTERPOLATION,
            CalibrationMath.ALG_ADAPTIVE_ENSEMBLE,
        ).forEach { algorithm ->
            val before = resultIn(anchors, 129.9999, 1.0, algorithm).prediction
            val after = resultIn(anchors, 130.0001, 1.0, algorithm).prediction

            assertTrue("$algorithm jump ${abs(after - before)} mg/dL", abs(after - before) < 0.5)
        }
    }

    @Test
    fun everyAlgorithmAndWeightModeIsUnitInvariantForOldAndCloseAnchors() {
        val scenarios = listOf(
            listOf(
                CalPoint(x = 100.0, y = 115.0, timestamp = now - 48 * hour),
                CalPoint(x = 160.0, y = 150.0, timestamp = now - 24 * hour),
            ),
            listOf(
                CalPoint(x = 128.0, y = 130.0, timestamp = now - 2 * hour),
                CalPoint(x = 132.0, y = 135.0, timestamp = now - hour),
            ),
        )
        val algorithms = listOf(
            CalibrationMath.ALG_SANE_WEIGHTED_OLS,
            CalibrationMath.ALG_XDRIP_MEDIAN_SLOPE,
            CalibrationMath.ALG_TIME_WEIGHTED_ROBUST_REGRESSION,
            CalibrationMath.ALG_ELASTIC_TIME_WEIGHTED_INTERPOLATION,
            CalibrationMath.ALG_ADAPTIVE_ENSEMBLE,
        )
        val modes = listOf(
            CalibrationMath.WEIGHT_FRESH,
            CalibrationMath.WEIGHT_BALANCED,
            CalibrationMath.WEIGHT_STABLE,
        )

        scenarios.forEach { scenario ->
            algorithms.forEach { algorithm ->
                modes.forEach { mode ->
                    val modeTuning = tuning.copy(algorithm = algorithm, weightMode = mode)
                    val mgdl = resultIn(scenario, 130.0, 1.0, algorithm, modeTuning)
                    val mmol = resultIn(scenario, 130.0, 1.0 / mgdlPerMmol, algorithm, modeTuning)

                    assertEquals("$algorithm/$mode anchorInfluence", mgdl.anchorInfluence!!, mmol.anchorInfluence!!, 1e-9)
                    assertEquals("$algorithm/$mode confidence", mgdl.confidence!!, mmol.confidence!!, unitInvarianceTolerance)
                    assertEquals(
                        "$algorithm/$mode prediction",
                        mgdl.prediction,
                        mmol.prediction * mgdlPerMmol,
                        predictionToleranceMgdl,
                    )

                    val sanitizedMgdl = CalibrationMath.sanitizeCalibratedValue(mgdl.prediction, 130f)
                    val sanitizedMmol = CalibrationMath.sanitizeCalibratedValue(
                        mmol.prediction,
                        (130.0 / mgdlPerMmol).toFloat(),
                    )
                    assertEquals(
                        "$algorithm/$mode sanitized",
                        sanitizedMgdl.toDouble(),
                        sanitizedMmol.toDouble() * mgdlPerMmol,
                        predictionToleranceMgdl,
                    )
                }
            }
        }
    }
}
