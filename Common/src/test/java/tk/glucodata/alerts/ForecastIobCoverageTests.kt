package tk.glucodata.alerts

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PRE_HIGH IOB coverage. Field case: forecast-high at 187 mg/dl, +18.9 per
 * 5 minutes, IOB 3.0 U at eIOB 1.3 U - the announced rise was already
 * treated, the alert was arithmetically correct and worthless.
 */
class ForecastIobCoverageTests {

    @Test
    fun remainingInsulinEffectCoveringTheOvershootSuppresses() {
        // IOB 3.0 U x ISF 54 = 162 mg/dl of remaining effect against a
        // projected overshoot of 250 - 234 = 16 mg/dl.
        assertTrue(
            ForecastIobCoverage.covered(
                projectedValue = 250f,
                threshold = 234f,
                isMmol = false,
                iobUnits = 3.0f,
                insulinSensitivityMgdlPerUnit = 54f,
                coverageFactor = 1.0f
            )
        )
    }

    @Test
    fun theSameSituationWithoutIobFires() {
        assertFalse(
            ForecastIobCoverage.covered(
                projectedValue = 250f,
                threshold = 234f,
                isMmol = false,
                iobUnits = 0f,
                insulinSensitivityMgdlPerUnit = 54f,
                coverageFactor = 1.0f
            )
        )
        assertFalse(
            ForecastIobCoverage.covered(
                projectedValue = 250f,
                threshold = 234f,
                isMmol = false,
                iobUnits = Float.NaN,
                insulinSensitivityMgdlPerUnit = 54f,
                coverageFactor = 1.0f
            )
        )
    }

    @Test
    fun insufficientCoverageStillFires() {
        // 0.2 U x 54 = 10.8 mg/dl against a 16 mg/dl overshoot.
        assertFalse(
            ForecastIobCoverage.covered(
                projectedValue = 250f,
                threshold = 234f,
                isMmol = false,
                iobUnits = 0.2f,
                insulinSensitivityMgdlPerUnit = 54f,
                coverageFactor = 1.0f
            )
        )
    }

    @Test
    fun factorZeroOrMissingSensitivityDisablesTheFeature() {
        assertFalse(
            ForecastIobCoverage.covered(
                projectedValue = 250f,
                threshold = 234f,
                isMmol = false,
                iobUnits = 3.0f,
                insulinSensitivityMgdlPerUnit = 54f,
                coverageFactor = 0f
            )
        )
        assertFalse(
            ForecastIobCoverage.covered(
                projectedValue = 250f,
                threshold = 234f,
                isMmol = false,
                iobUnits = 3.0f,
                insulinSensitivityMgdlPerUnit = Float.NaN,
                coverageFactor = 1.0f
            )
        )
        assertFalse(
            ForecastIobCoverage.covered(
                projectedValue = 250f,
                threshold = 234f,
                isMmol = false,
                iobUnits = 3.0f,
                insulinSensitivityMgdlPerUnit = 0f,
                coverageFactor = 1.0f
            )
        )
    }

    @Test
    fun coverageFactorScalesTheRequiredEffect() {
        // Overshoot 100 mg/dl; effect 81 mg/dl: covers at factor 0.8, not at 1.
        assertTrue(
            ForecastIobCoverage.covered(
                projectedValue = 334f,
                threshold = 234f,
                isMmol = false,
                iobUnits = 1.5f,
                insulinSensitivityMgdlPerUnit = 54f,
                coverageFactor = 0.8f
            )
        )
        assertFalse(
            ForecastIobCoverage.covered(
                projectedValue = 334f,
                threshold = 234f,
                isMmol = false,
                iobUnits = 1.5f,
                insulinSensitivityMgdlPerUnit = 54f,
                coverageFactor = 1.0f
            )
        )
    }

    @Test
    fun preLowShapedInputNeverSuppressesEvenWithHighIob() {
        // Asymmetry regression, defence in depth: a PRE_LOW projection sits
        // below its threshold, the overshoot is negative, and no amount of
        // IOB may suppress. The runtime additionally never consults this
        // check for PRE_LOW at all.
        assertFalse(
            ForecastIobCoverage.covered(
                projectedValue = 55f,
                threshold = 70f,
                isMmol = false,
                iobUnits = 10f,
                insulinSensitivityMgdlPerUnit = 54f,
                coverageFactor = 1.0f
            )
        )
    }

    @Test
    fun mmolOvershootIsComparedInMgdl() {
        // Projected 14.0 mmol vs threshold 13.0: overshoot 1 mmol = 18 mg/dl.
        // 0.5 U x 54 = 27 mg/dl covers it; 0.2 U x 54 = 10.8 does not.
        assertTrue(
            ForecastIobCoverage.covered(
                projectedValue = 14.0f,
                threshold = 13.0f,
                isMmol = true,
                iobUnits = 0.5f,
                insulinSensitivityMgdlPerUnit = 54f,
                coverageFactor = 1.0f
            )
        )
        assertFalse(
            ForecastIobCoverage.covered(
                projectedValue = 14.0f,
                threshold = 13.0f,
                isMmol = true,
                iobUnits = 0.2f,
                insulinSensitivityMgdlPerUnit = 54f,
                coverageFactor = 1.0f
            )
        )
    }
}
