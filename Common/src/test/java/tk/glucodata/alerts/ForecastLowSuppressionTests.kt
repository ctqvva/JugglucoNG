package tk.glucodata.alerts

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ForecastLowSuppressionTests {
    @Test
    fun flatAndRisingArrowsBlockEvenWithoutCarbs() {
        for (rate in listOf(-0.5f, -0.2f, -0.0f, 0f, 0.5f, 1f, 3f)) {
            assertFalse("rate=$rate", ForecastLowSuppression.hasDownwardArrow(rate))
        }
    }

    @Test
    fun everyDownwardArrowPassesTheDirectionGate() {
        for (rate in listOf(-0.5001f, -0.75f, -1f, -2f, -10f)) {
            assertTrue("rate=$rate", ForecastLowSuppression.hasDownwardArrow(rate))
        }
    }

    @Test
    fun unavailableArrowDoesNotPermitForecastLow() {
        for (rate in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            assertFalse(ForecastLowSuppression.hasDownwardArrow(rate))
        }
    }

    @Test
    fun absentOrInsufficientCarbsDoNotCoverTheLow() {
        // A 20 mg/dL shortfall needs 4 g at 10 g/U and 50 mg/dL/U.
        for (cob in listOf(Float.NaN, Float.POSITIVE_INFINITY, -1f, 0f, 3.99f)) {
            assertFalse("COB=$cob", covered(cob))
        }
    }

    @Test
    fun exactOrGreaterCoverageSuppressesTheForecast() {
        assertTrue(covered(4f))
        assertTrue(covered(10f))
    }

    @Test
    fun coverageUsesTheConfiguredCarbRatioAndSensitivity() {
        assertFalse(ForecastLowSuppression.cobCoversLow(50f, 70f, false, 4f, 20f, 50f))
        assertTrue(ForecastLowSuppression.cobCoversLow(50f, 70f, false, 4f, 20f, 100f))
    }

    @Test
    fun mmolShortfallIsConvertedBeforeComparingCarbEffect() {
        assertFalse(ForecastLowSuppression.cobCoversLow(3f, 4f, true, 3.6f, 10f, 50f))
        assertTrue(ForecastLowSuppression.cobCoversLow(3f, 4f, true, 3.61f, 10f, 50f))
    }

    @Test
    fun invalidProfileOrForecastDoesNotClaimCoverage() {
        for (invalid in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, 0f, -1f)) {
            assertFalse(ForecastLowSuppression.cobCoversLow(50f, 70f, false, 10f, invalid, 50f))
            assertFalse(ForecastLowSuppression.cobCoversLow(50f, 70f, false, 10f, 10f, invalid))
            assertFalse(ForecastLowSuppression.cobCoversLow(50f, invalid, false, 10f, 10f, 50f))
        }
        assertFalse(ForecastLowSuppression.cobCoversLow(Float.NaN, 70f, false, 10f, 10f, 50f))
    }

    @Test
    fun recoveredProjectionInAnOpenEpisodeStillRequiresPositiveCob() {
        assertTrue(ForecastLowSuppression.cobCoversLow(75f, 70f, false, 1f, 10f, 50f))
        assertFalse(ForecastLowSuppression.cobCoversLow(75f, 70f, false, 0f, 10f, 50f))
    }

    private fun covered(cob: Float) =
        ForecastLowSuppression.cobCoversLow(50f, 70f, false, cob, 10f, 50f)
}
