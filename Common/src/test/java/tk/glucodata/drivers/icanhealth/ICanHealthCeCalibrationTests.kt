package tk.glucodata.drivers.icanhealth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ICanHealthCeCalibrationTests {

    @Test
    fun planCalibRejectsNonUsableProduct() {
        val result = ICanHealthCeCalibration.planCalib(
            currentGluMgDl = 180f,
            fingerGluMgDl = 0f,
            historyMgDl = emptyList(),
            sensorCurrent = 1f,
            temperatureC = 1f,
        )
        assertFalse(result.isCalibrated)
        assertEquals(1f, result.encodedP, 0.0001f)
        assertEquals(0, result.calibCount)
        assertEquals(0f, result.calibProduct, 0.0001f)
        assertEquals(1f, result.currentTimesTemp, 0.0001f)
        assertEquals(1f, result.temperature, 0.0001f)
    }

    @Test
    fun planCalibWithMatchingInputsKeepsFactorOne() {
        val result = ICanHealthCeCalibration.planCalib(
            currentGluMgDl = 180f,
            fingerGluMgDl = 180f,
            historyMgDl = emptyList(),
            sensorCurrent = 1f,
            temperatureC = 1f,
        )
        assertTrue(result.isCalibrated)
        assertEquals(1, result.calibCount)
        assertEquals(180f, result.calibProduct, 0.0001f)
        assertEquals(1f, result.encodedP, 0.0001f)
    }

    @Test
    fun planCalibFiltersInvalidHistorySamples() {
        val withNoise = ICanHealthCeCalibration.planCalib(
            currentGluMgDl = 180f,
            fingerGluMgDl = 180f,
            historyMgDl = listOf(Float.NaN, -1f, 0f, Float.POSITIVE_INFINITY),
            sensorCurrent = 1f,
            temperatureC = 1f,
        )
        val empty = ICanHealthCeCalibration.planCalib(180f, 180f, emptyList(), 1f, 1f)
        assertEquals(empty.encodedP, withNoise.encodedP, 0.0001f)
    }

    @Test
    fun startCalibFirstCalibrationRampsFromCurrent() {
        val result = ICanHealthCeCalibration.startCalib(
            currentGluMgDl = 180f,
            isFirstCalibration = true,
            existingCalibrationCount = 0,
            sensorCurrent = 1f,
            temperatureC = 1f,
        )
        assertFalse(result.isCalibrated)
        assertEquals(180f, result.encodedP, 0.0001f)
        assertEquals(1, result.calibCount)
        assertEquals(180f, result.calibProduct, 0.0001f)
        assertEquals(180f, result.temperature, 0.0001f)
        assertEquals(1f, result.currentTimesTemp, 0.0001f)
    }

    @Test
    fun startCalibRampsByExistingCount() {
        val result = ICanHealthCeCalibration.startCalib(
            currentGluMgDl = 180f,
            isFirstCalibration = false,
            existingCalibrationCount = 2,
            sensorCurrent = 1f,
            temperatureC = 1f,
        )
        assertTrue(result.isCalibrated)
        assertEquals(3, result.calibCount)
        // rampFactor = ((180 - 1) * 2) / 5 + 1
        assertEquals(72.6f, result.temperature, 0.0001f)
        assertEquals(72.6f, result.calibProduct, 0.0001f)
    }

    @Test
    fun startCalibWrapsCountAfterFour() {
        val result = ICanHealthCeCalibration.startCalib(
            currentGluMgDl = 180f,
            isFirstCalibration = false,
            existingCalibrationCount = 4,
            sensorCurrent = 1f,
            temperatureC = 1f,
        )
        assertFalse(result.isCalibrated)
        assertEquals(0, result.calibCount)
    }

    @Test
    fun startCalibFallsBackToOneForInvalidCurrent() {
        val result = ICanHealthCeCalibration.startCalib(
            currentGluMgDl = Float.NaN,
            isFirstCalibration = true,
            existingCalibrationCount = -3,
            sensorCurrent = 1f,
            temperatureC = 1f,
        )
        assertEquals(1f, result.encodedP, 0.0001f)
        assertEquals(1f, result.temperature, 0.0001f)
        assertEquals(1, result.calibCount)
    }
}
