package tk.glucodata.drivers.anytime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnytimeCalibrationPolicyTests {

    @Test
    fun manualCalibrationRequiresKnownTwentyFourHourAge() {
        assertEquals(24, AnytimeCalibrationPolicy.MANUAL_CALIBRATION_MIN_AGE_HOURS)
        assertFalse(AnytimeCalibrationPolicy.canAcceptManualCalibration(-1))
        assertFalse(AnytimeCalibrationPolicy.canAcceptManualCalibration(0))
        assertFalse(AnytimeCalibrationPolicy.canAcceptManualCalibration(23))
        assertTrue(AnytimeCalibrationPolicy.canAcceptManualCalibration(24))
        assertTrue(AnytimeCalibrationPolicy.canAcceptManualCalibration(25))
    }

    @Test
    fun nativeCalibrationStatusAllowsAnythingExceptExplicitNot() {
        assertTrue(
            AnytimeCalibrationPolicy.canAcceptAlgorithmCalibrationStatus(
                AnytimeCalibrationPolicy.CALIBRATION_STATUS_UNKNOWN
            )
        )
        assertFalse(
            AnytimeCalibrationPolicy.canAcceptAlgorithmCalibrationStatus(
                AnytimeCalibrationPolicy.CALIBRATION_STATUS_NOT
            )
        )
        assertTrue(
            AnytimeCalibrationPolicy.canAcceptAlgorithmCalibrationStatus(
                AnytimeCalibrationPolicy.CALIBRATION_STATUS_CAN
            )
        )
        assertTrue(
            AnytimeCalibrationPolicy.canAcceptAlgorithmCalibrationStatus(
                AnytimeCalibrationPolicy.CALIBRATION_STATUS_HOPE
            )
        )
        assertTrue(
            AnytimeCalibrationPolicy.canAcceptAlgorithmCalibrationStatus(
                AnytimeCalibrationPolicy.CALIBRATION_STATUS_INIT
            )
        )
        assertTrue(
            AnytimeCalibrationPolicy.canAcceptAlgorithmCalibrationStatus(
                AnytimeCalibrationPolicy.CALIBRATION_STATUS_MUST
            )
        )
    }

    @Test
    fun nativeCalibrationStatusNamesMatchOfficialIds() {
        assertEquals("UNKNOWN", AnytimeCalibrationPolicy.calibrationStatusName(-1))
        assertEquals("NOT", AnytimeCalibrationPolicy.calibrationStatusName(0))
        assertEquals("CAN", AnytimeCalibrationPolicy.calibrationStatusName(1))
        assertEquals("HOPE", AnytimeCalibrationPolicy.calibrationStatusName(2))
        assertEquals("INIT", AnytimeCalibrationPolicy.calibrationStatusName(3))
        assertEquals("MUST", AnytimeCalibrationPolicy.calibrationStatusName(4))
    }

    @Test
    fun trendClassifierMatchesReferenceAppThresholds() {
        assertEquals(AnytimeCalibrationPolicy.Trend.STABLE, AnytimeCalibrationPolicy.classifyTrend(0f))
        assertEquals(AnytimeCalibrationPolicy.Trend.STABLE, AnytimeCalibrationPolicy.classifyTrend(0.059f))
        assertEquals(AnytimeCalibrationPolicy.Trend.STABLE, AnytimeCalibrationPolicy.classifyTrend(-0.059f))
        assertEquals(AnytimeCalibrationPolicy.Trend.MODERATE_RISE, AnytimeCalibrationPolicy.classifyTrend(0.06f))
        assertEquals(AnytimeCalibrationPolicy.Trend.MODERATE_RISE, AnytimeCalibrationPolicy.classifyTrend(0.109f))
        assertEquals(AnytimeCalibrationPolicy.Trend.RAPID_RISE, AnytimeCalibrationPolicy.classifyTrend(0.11f))
        assertEquals(AnytimeCalibrationPolicy.Trend.MODERATE_FALL, AnytimeCalibrationPolicy.classifyTrend(-0.06f))
        assertEquals(AnytimeCalibrationPolicy.Trend.MODERATE_FALL, AnytimeCalibrationPolicy.classifyTrend(-0.109f))
        assertEquals(AnytimeCalibrationPolicy.Trend.RAPID_FALL, AnytimeCalibrationPolicy.classifyTrend(-0.11f))
    }

    @Test
    fun calibrationRequiresStableTrend() {
        assertTrue(AnytimeCalibrationPolicy.canAcceptTrendForCalibration(0f))
        assertTrue(AnytimeCalibrationPolicy.canAcceptTrendForCalibration(0.059f))
        assertFalse(AnytimeCalibrationPolicy.canAcceptTrendForCalibration(0.06f))
        assertFalse(AnytimeCalibrationPolicy.canAcceptTrendForCalibration(-0.06f))
    }

    @Test
    fun calibrationRequiresLowNoise() {
        assertEquals(0.2f, AnytimeCalibrationPolicy.MAX_CALIBRATION_NOISE_MMOL)
        assertTrue(AnytimeCalibrationPolicy.canAcceptNoiseForCalibration(0f))
        assertTrue(AnytimeCalibrationPolicy.canAcceptNoiseForCalibration(0.199f))
        assertFalse(AnytimeCalibrationPolicy.canAcceptNoiseForCalibration(0.2f))
        assertFalse(AnytimeCalibrationPolicy.canAcceptNoiseForCalibration(0.5f))
    }
}
