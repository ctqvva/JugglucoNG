package tk.glucodata.drivers.icanhealth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Golden vectors taken from the vendor library itself, not derived from the decompilation.
 *
 * A small arm64 harness (`dlopen` + `dlsym` on `AlgNamespace::planCalib`/`startCalib`, pushed to a
 * rooted device together with `lib/arm64-v8a/libAlg.so` and `libc++_shared.so` from
 * `luch-ru-cgm-0.1.0-vc39.apk`) printed these values. They pin the port to the binary's own output
 * for both functions, including the degenerate cases.
 *
 * Deltas are absolute and generous enough for the binary's double-precision internals against this
 * port's floats.
 */
class ICanHealthCeCalibrationGoldenVectorTests {

    private val eps = 0.01f

    @Test
    fun startCalibGolden_rampFromCoefficient() {
        val r = ICanHealthCeCalibration.startCalib(1.2f, true, 2, 180f, 1f)
        assertEquals(1.2f, r.caliK, eps)
        assertTrue(r.caliAdjustBool)
        assertEquals(3, r.caliAdjustNo)
        assertEquals(194.4f, r.calibBg, eps)
        assertEquals(1f, r.calik0, eps)
        assertEquals(1.08f, r.calikM, eps)
    }

    @Test
    fun startCalibGolden_firstCalibrationHasNoRamp() {
        val r = ICanHealthCeCalibration.startCalib(1.0f, true, 0, 180f, 1f)
        assertEquals(1f, r.caliK, eps)
        assertTrue(r.caliAdjustBool)
        assertEquals(1, r.caliAdjustNo)
        assertEquals(180f, r.calibBg, eps)
        assertEquals(1f, r.calik0, eps)
        assertEquals(1f, r.calikM, eps)
    }

    @Test
    fun startCalibGolden_wrapsAndStopsCalibratingAtFour() {
        val r = ICanHealthCeCalibration.startCalib(1.2f, true, 4, 180f, 1f)
        assertEquals(1.2f, r.caliK, eps)
        assertEquals(false, r.caliAdjustBool)
        assertEquals(1, r.caliAdjustNo)
        assertEquals(208.8f, r.calibBg, eps)
        assertEquals(1f, r.calik0, eps)
        assertEquals(1.16f, r.calikM, eps)
    }

    @Test
    fun startCalibGolden_nonFirstScalesByCoefficient() {
        val r = ICanHealthCeCalibration.startCalib(1.1f, false, 2, 180f, 1f)
        assertEquals(1.1f, r.caliK, eps)
        assertEquals(false, r.caliAdjustBool)
        assertEquals(2, r.caliAdjustNo)
        assertEquals(198f, r.calibBg, eps)
        assertEquals(1f, r.calik0, eps)
        assertEquals(1.1f, r.calikM, eps)
    }

    @Test
    fun startCalibGolden_nanPropagates() {
        val r = ICanHealthCeCalibration.startCalib(Float.NaN, true, 0, 180f, 1f)
        assertTrue(r.caliK.isNaN())
        assertTrue(r.caliAdjustBool)
        assertEquals(1, r.caliAdjustNo)
        assertTrue(r.calibBg.isNaN())
        assertEquals(1f, r.calik0, eps)
        assertTrue(r.calikM.isNaN())
    }

    @Test
    fun planCalibGolden_unitInputsGiveUnitFactor() {
        val r = ICanHealthCeCalibration.planCalib(180f, 180f, emptyList(), 1f, 1f, 1f)
        assertEquals(1f, r.caliK, eps)
        assertTrue(r.caliAdjustBool)
        assertEquals(1, r.caliAdjustNo)
        assertEquals(180f, r.calibBg, eps)
        assertEquals(1f, r.calik0, eps)
        assertEquals(1f, r.calikM, eps)
    }

    /**
     * The discriminating vector: an empty history makes the binary divide 0/0, `fminnm` turns that
     * NaN into a confidence of 1.0, and the sigmoid lands at 0.7625:
     * `((1 - 0.7625) * 1 + 180 * 0.7625) / 1 = 137.4875`. A port that treats an empty history as
     * "confidence 0" returns 135.25 instead.
     */
    @Test
    fun planCalibGolden_emptyHistoryClampsConfidenceToOne() {
        val r = ICanHealthCeCalibration.planCalib(180f, 0.5f, emptyList(), 1f, 1f, 1f)
        assertEquals(137.4856f, r.caliK, eps)
        assertEquals(1f, r.calibBg, eps)
    }

    /**
     * Proves three things at once: the product uses `calik0 * calikM`, the outputs carry
     * `calik0 * calikM` and `calikM`, and `caliK` is not read by `planCalib` (999 changes nothing).
     */
    @Test
    fun planCalibGolden_usesCalik0TimesCalikMAndIgnoresCaliK() {
        val r = ICanHealthCeCalibration.planCalib(180f, 180f, emptyList(), 999f, 1f, 2f)
        assertEquals(0.6187552f, r.caliK, eps)
        assertEquals(1, r.caliAdjustNo)
        assertEquals(360f, r.calibBg, eps)
        assertEquals(2f, r.calik0, eps)
        assertEquals(2f, r.calikM, eps)
    }

    @Test
    fun planCalibGolden_nanAlgorithmBgPropagates() {
        val r = ICanHealthCeCalibration.planCalib(Float.NaN, 180f, emptyList(), 1f, 1f, 1f)
        assertTrue(r.caliK.isNaN())
        assertEquals(180f, r.calibBg, eps)
    }

    @Test
    fun planCalibGolden_linearHistoryStillGivesUnitFactor() {
        val r = ICanHealthCeCalibration.planCalib(
            180f, 180f, listOf(170f, 175f, 180f, 185f, 190f), 1f, 1f, 1f,
        )
        assertEquals(1f, r.caliK, eps)
        assertEquals(180f, r.calibBg, eps)
    }
}
