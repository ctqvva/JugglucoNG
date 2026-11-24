package tk.glucodata.data.calibration

import org.junit.Assert.assertEquals
import org.junit.Test

class AdaptiveEnsembleUnitTests {
    private val hour = 3_600_000L
    private val now = 1_700_000_000_000L
    private val tuning = CalibrationTuning.DEFAULT
    private val mgdlPerMmol = 18.0182

    private val points = listOf(
        CalPoint(x = 90.0, y = 110.0, timestamp = now - 4 * hour),
        CalPoint(x = 120.0, y = 105.0, timestamp = now - 3 * hour),
        CalPoint(x = 160.0, y = 175.0, timestamp = now - 2 * hour),
        CalPoint(x = 220.0, y = 200.0, timestamp = now - hour),
    )

    private fun resultIn(targetMgdl: Double, factor: Double) = CalibrationMath.computeAlgorithm(
        algorithm = CalibrationMath.ALG_ADAPTIVE_ENSEMBLE,
        targetValue = targetMgdl * factor,
        targetTimestamp = now,
        points = points.map { it.copy(x = it.x * factor, y = it.y * factor) },
        tuning = tuning,
    )

    @Test
    fun anchorInfluenceDoesNotDependOnGlucoseUnit() {
        val mgdl = resultIn(130.0, 1.0)
        val mmol = resultIn(130.0, 1.0 / mgdlPerMmol)

        assertEquals(mgdl.anchorInfluence!!, mmol.anchorInfluence!!, 1e-9)
    }

    @Test
    fun confidenceDoesNotDependOnGlucoseUnit() {
        val mgdl = resultIn(130.0, 1.0)
        val mmol = resultIn(130.0, 1.0 / mgdlPerMmol)

        assertEquals(mgdl.confidence!!, mmol.confidence!!, 0.05)
    }

    @Test
    fun predictionDoesNotDependOnGlucoseUnit() {
        val mgdl = resultIn(130.0, 1.0)
        val mmol = resultIn(130.0, 1.0 / mgdlPerMmol)

        assertEquals(mgdl.prediction, mmol.prediction * mgdlPerMmol, 1.0)
    }
}
