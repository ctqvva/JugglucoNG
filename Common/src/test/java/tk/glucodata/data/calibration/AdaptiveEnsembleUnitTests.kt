package tk.glucodata.data.calibration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

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
    ) = CalibrationMath.computeAlgorithm(
        algorithm = algorithm,
        targetValue = targetMgdl * factor,
        targetTimestamp = now,
        points = points.map { it.copy(x = it.x * factor, y = it.y * factor) },
        tuning = tuning,
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
}
