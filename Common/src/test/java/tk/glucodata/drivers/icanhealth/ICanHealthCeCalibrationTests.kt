package tk.glucodata.drivers.icanhealth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the vendor `libAlg.so` behaviour with the vendor's own parameter names.
 *
 * `planCalib(algBg, newBg, bgList, caliK, calik0, calikM)` and
 * `startCalib(caliK, caliAdjustBool, caliAdjustNo, newBg, calik0)`, result
 * `(caliK, caliAdjustBool, caliAdjustNo, calibBg, calik0, calikM)` — see `AlgManager.java` in the
 * RU build. `caliK`/`calik0`/`calikM` are coefficients; `calibBg` is the calibrated glucose.
 */
class ICanHealthCeCalibrationTests {

    @Test
    fun planCalibRejectsNonUsableProduct() {
        val result = ICanHealthCeCalibration.planCalib(
            algBg = 180f,
            newBg = 0f,
            bgList = emptyList(),
            caliK = 1f,
            calik0 = 0f,
            calikM = 1f,
        )
        assertFalse(result.caliAdjustBool)
        assertEquals(1f, result.caliK, 0.0001f)
        assertEquals(0, result.caliAdjustNo)
        assertEquals(0f, result.calibBg, 0.0001f)
    }

    @Test
    fun planCalibWithUnitCoefficientsKeepsFactorOne() {
        val result = ICanHealthCeCalibration.planCalib(
            algBg = 180f,
            newBg = 180f,
            bgList = emptyList(),
            caliK = 1f,
            calik0 = 1f,
            calikM = 1f,
        )
        assertTrue(result.caliAdjustBool)
        assertEquals(1, result.caliAdjustNo)
        assertEquals(180f, result.calibBg, 0.0001f)
        assertEquals(1f, result.caliK, 0.0001f)
        assertEquals(1f, result.calikM, 0.0001f)
    }

    @Test
    fun planCalibProductIsNewBgTimesCalik0TimesCalikM() {
        // Register-level check of the binary at 0x11b8b4: s3 * s4 = calik0 * calikM, then
        // max(1.0, newBg) multiplies it. The earlier port used caliK instead of calikM here.
        val result = ICanHealthCeCalibration.planCalib(
            algBg = 180f,
            newBg = 180f,
            bgList = emptyList(),
            caliK = 999f,
            calik0 = 1f,
            calikM = 2f,
        )
        assertEquals(360f, result.calibBg, 0.0001f)
        assertEquals(2f, result.calik0, 0.0001f)
    }

    @Test
    fun planCalibClampsNewBgAtOne() {
        val result = ICanHealthCeCalibration.planCalib(
            algBg = 180f,
            newBg = 0.5f,
            bgList = emptyList(),
            caliK = 1f,
            calik0 = 1f,
            calikM = 1f,
        )
        // max(1.0, 0.5) * 1 * 1 = 1.0, not 0.5
        assertEquals(1f, result.calibBg, 0.0001f)
    }

    @Test
    fun planCalibDoesNotReadCaliK() {
        val withOne = ICanHealthCeCalibration.planCalib(180f, 180f, emptyList(), 1f, 1f, 1f)
        val withOther = ICanHealthCeCalibration.planCalib(180f, 180f, emptyList(), 42f, 1f, 1f)
        assertEquals(withOne.caliK, withOther.caliK, 0.0001f)
        assertEquals(withOne.calibBg, withOther.calibBg, 0.0001f)
    }

    @Test
    fun planCalibFiltersInvalidHistorySamples() {
        val withNoise = ICanHealthCeCalibration.planCalib(
            algBg = 180f,
            newBg = 180f,
            bgList = listOf(Float.NaN, -1f, 0f, Float.POSITIVE_INFINITY),
            caliK = 1f,
            calik0 = 1f,
            calikM = 1f,
        )
        val empty = ICanHealthCeCalibration.planCalib(180f, 180f, emptyList(), 1f, 1f, 1f)
        assertEquals(empty.caliK, withNoise.caliK, 0.0001f)
    }

    @Test
    fun startCalibFirstCalibrationRampsFromCoefficient() {
        // ramp = ((caliK - 1) * count) / 5 + 1 = ((1.2 - 1) * 2) / 5 + 1 = 1.08
        val result = ICanHealthCeCalibration.startCalib(
            caliK = 1.2f,
            caliAdjustBool = true,
            caliAdjustNo = 2,
            newBg = 180f,
            calik0 = 1f,
        )
        assertTrue(result.caliAdjustBool)
        assertEquals(3, result.caliAdjustNo)
        assertEquals(1.2f, result.caliK, 0.0001f)
        assertEquals(1.08f, result.calikM, 0.0001f)
        assertEquals(194.4f, result.calibBg, 0.001f)
    }

    @Test
    fun startCalibWrapsCountToOneAfterFour() {
        // ramp = ((1.2 - 1) * 4) / 5 + 1 = 1.16; the vendor wraps the counter to 1, not 0.
        val result = ICanHealthCeCalibration.startCalib(
            caliK = 1.2f,
            caliAdjustBool = true,
            caliAdjustNo = 4,
            newBg = 180f,
            calik0 = 1f,
        )
        assertFalse(result.caliAdjustBool)
        assertEquals(1, result.caliAdjustNo)
        assertEquals(1.16f, result.calikM, 0.0001f)
    }

    @Test
    fun startCalibNonFirstScalesByCaliK() {
        val result = ICanHealthCeCalibration.startCalib(
            caliK = 1.1f,
            caliAdjustBool = false,
            caliAdjustNo = 2,
            newBg = 180f,
            calik0 = 1f,
        )
        assertFalse(result.caliAdjustBool)
        assertEquals(2, result.caliAdjustNo)
        assertEquals(1.1f, result.caliK, 0.0001f)
        // The binary stores caliK in the calikM slot on this path.
        assertEquals(1.1f, result.calikM, 0.0001f)
        assertEquals(198f, result.calibBg, 0.001f)
    }

    @Test
    fun startCalibPassesThroughRawInputLikeNativeLib() {
        // libAlg.so applies no finite/positive guard; NaN propagates verbatim.
        val result = ICanHealthCeCalibration.startCalib(
            caliK = Float.NaN,
            caliAdjustBool = true,
            caliAdjustNo = 0,
            newBg = 180f,
            calik0 = 1f,
        )
        assertTrue(result.caliK.isNaN())
        assertTrue(result.calibBg.isNaN())
        assertEquals(1, result.caliAdjustNo)
    }

    @Test
    fun startCalibTreatingCaliKAsGlucoseIsTheOldDefect() {
        // Documents why the old call site was wrong: caliK is a coefficient (~1), so a glucose
        // value there turns the ramp into roughly the glucose itself.
        val coefficientRamp = ICanHealthCeCalibration.startCalib(1.2f, true, 2, 180f, 1f).calikM
        val glucoseRamp = ICanHealthCeCalibration.startCalib(120f, true, 2, 180f, 1f).calikM
        assertEquals(1.08f, coefficientRamp, 0.0001f)
        assertEquals(48.6f, glucoseRamp, 0.0001f)
    }
}
