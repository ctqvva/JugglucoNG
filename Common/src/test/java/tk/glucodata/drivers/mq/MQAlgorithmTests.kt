package tk.glucodata.drivers.mq

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MQAlgorithmTests {

    private val delta = 0.0001

    @Test
    fun legacyAdjustIsIdentityOutsideWindow() {
        assertEquals(
            100.0,
            MQAlgorithm.adjustSampleCurrent(0, 5.0, 100.0, 720.0, 1.1),
            delta,
        )
        assertEquals(
            100.0,
            MQAlgorithm.adjustSampleCurrent(0, 250.0, 100.0, 720.0, 1.1),
            delta,
        )
    }

    @Test
    fun legacyAdjustAppliesLinearRampInsideWindow() {
        val expected = ((100.0 - ((((250.0 - 129.0) + 1.0) * 25.0) / 242.0)) * 10.0) / 100.0
        assertEquals(expected, MQAlgorithm.adjustSampleCurrent(0, 129.0, 10.0, 720.0, 1.1), delta)
    }

    @Test
    fun versionedAdjustIsIdentityBeforeAndAfterWarmup() {
        assertEquals(50.0, MQAlgorithm.adjustSampleCurrent(1, 5.0, 50.0, 720.0, 1.1), delta)
        // threshold = packages + 19; strictly greater than threshold is untouched
        assertEquals(50.0, MQAlgorithm.adjustSampleCurrent(1, 740.0, 50.0, 720.0, 1.1), delta)
    }

    @Test
    fun versionedAdjustRampsTowardsTrustedMultiplier() {
        // factor = 1.1 - ((1.1 - 1) / 739) * (20 - 19)
        val factor = 1.1 - ((1.1 - 1.0) / 739.0) * 1.0
        assertEquals(factor * 10.0, MQAlgorithm.adjustSampleCurrent(1, 20.0, 10.0, 720.0, 1.1), delta)
    }

    @Test
    fun calculateReturnsZeroGlucoseWithoutCalibration() {
        val result = MQAlgorithm.calculate(
            algorithmVersion = 1,
            initTimeMinutes = 0.0,
            packetIndex = 0.0,
            sampleCurrent = 5.0,
            previousReviseCurrent2 = 0.0,
            kValue = 0.0,
            referenceBgTimes10Mmol = 0.0,
            bValue = 0.0,
            packages = 720.0,
            multiplier = 1.1,
        )
        assertEquals(0.0, result[7], delta)
        assertEquals(0.0, result[4], delta)
    }

    @Test
    fun calculateUsesKValueToConvertCurrentToGlucose() {
        val result = MQAlgorithm.calculate(
            algorithmVersion = 1,
            initTimeMinutes = 0.0,
            packetIndex = 0.0,
            sampleCurrent = 5.0,
            previousReviseCurrent2 = 0.0,
            kValue = 2.0,
            referenceBgTimes10Mmol = 0.0,
            bValue = 0.0,
            packages = 720.0,
            multiplier = 1.1,
        )
        // reviseCurrent2 = 5, raw = ((5 / 2) + 0.05) * 10 = 25.5 -> 25
        assertEquals(5.0, result[2], delta)
        assertEquals(2.0, result[4], delta)
        assertEquals(25.0, result[7], delta)
    }

    @Test
    fun calculateWithReferenceRecalibratesK() {
        val result = MQAlgorithm.calculate(
            algorithmVersion = 1,
            initTimeMinutes = 60.0,
            packetIndex = 20.0,
            sampleCurrent = 10.0,
            previousReviseCurrent2 = 10.0,
            kValue = 1.0,
            referenceBgTimes10Mmol = 100.0,
            bValue = 1.0,
            packages = 720.0,
            multiplier = 1.1,
        )
        assertTrue(result[7] in 99.0..101.0)
        assertEquals(2.0, result[6], delta)
    }

    @Test
    fun calculateResultMapsVendorFieldsAndUnits() {
        val result = MQAlgorithm.calculateResult(
            algorithmVersion = 1,
            initTimeMinutes = 0.0,
            packetIndex = 0.0,
            sampleCurrent = 5.0,
            previousReviseCurrent2 = 0.0,
            kValue = 2.0,
            referenceBgTimes10Mmol = 0.0,
            bValue = 0.0,
            packages = 720.0,
            multiplier = 1.1,
        )
        assertEquals(25, result.glucoseTimes10Mmol)
        assertEquals(2.5, result.glucoseMmol, delta)
        assertEquals((2.5 * MQConstants.MMOL_TO_MGDL * 10.0).toInt(), result.mgdlTimes10)
        assertEquals((2.5 * MQConstants.MMOL_TO_MGDL).toFloat(), result.mgdl, 0.001f)
        assertEquals(2.5f, result.mmol, delta.toFloat())
    }

    @Test
    fun glucoseIsClampedToAlgorithmBounds() {
        val low = MQAlgorithm.calculate(1, 0.0, 0.0, 1.0, 0.0, 100.0, 0.0, 0.0, 720.0, 1.1)
        assertEquals(MQConstants.ALGO_MMOL_MIN_TIMES10.toInt().toDouble(), low[7], 1.0)
        val high = MQAlgorithm.calculate(1, 0.0, 0.0, 100000.0, 0.0, 0.001, 0.0, 0.0, 720.0, 1.1)
        assertEquals(MQConstants.ALGO_MMOL_MAX_TIMES10.toInt().toDouble(), high[7], 1.0)
    }
}
