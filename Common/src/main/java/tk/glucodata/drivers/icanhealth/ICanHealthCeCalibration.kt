package tk.glucodata.drivers.icanhealth

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Result of the vendor calibration library, field for field.
 *
 * The names and the order are the vendor's: `com.sinocare.cgm.cgm_sdk.bean.AlgParameterBean`, whose
 * constructor signature is `(FZIFFF)V`, i.e. exactly `(caliK, caliAdjustBool, caliAdjustNo,
 * calibBg, calik0, calikM)`.
 *
 * [caliK], [calik0] and [calikM] are **calibration coefficients**, not measurements: the vendor
 * persists them per sensor as the JSON blob of `SdkCgmDeviceInfo.getCalFactor()` and evolves them
 * from one calibration to the next. [calibBg] is the **calibrated glucose** — `util/l0.java:126-130`
 * in the RU build assigns it to the record's `originCalGlu` and recomputes `glu`/`gluMg` from it.
 *
 * This replaces the earlier field set (`encodedP`/`isCalibrated`/`calibCount`/`calibProduct`/
 * `currentTimesTemp`/`temperature`), which described the same six values with names that hid what
 * they are — in particular `calibProduct` was not a product but the calibrated glucose, and
 * `temperature`/`currentTimesTemp` were coefficients.
 */
data class ICanHealthCeCalibrationResult(
    val caliK: Float,
    val caliAdjustBool: Boolean,
    val caliAdjustNo: Int,
    val calibBg: Float,
    val calik0: Float,
    val calikM: Float,
)

/**
 * Port of `AlgNamespace::planCalib/startCalib/lineFit` from the vendor `libAlg.so`
 * (verified against `lib/arm64-v8a/libAlg.so` of the RU build `luch-ru-cgm-0.1.0-vc39`; the
 * vendor's own Java signatures are in `com/sinocare/cgm/cgm_sdk/util/AlgManager.java`).
 *
 * The vendor entry point is
 * `AlgManager.planCalib(algBg, newBg, bgList, caliK, calik0, calikM)` /
 * `AlgManager.startCalib(caliK, caliAdjustBool, caliAdjustNo, newBg, calik0)`.
 *
 * Two things the earlier port had wrong, both about meaning rather than arithmetic:
 *
 * 1. `planCalib`'s product is `max(1.0, newBg) * calik0 * calikM`. Register-level check of the
 *    binary at `0x11b8b4`: `fmul s10, s10, s8` multiplies the 4th and 5th floats (`s3 * s4` =
 *    `calik0 * calikM`), then `fcsel`/`fmul s11, s1, s10` clamps the 2nd float (`newBg`) at `1.0`
 *    and multiplies. `caliK` (3rd float, `s2`) is not read by that function at all. The earlier
 *    port multiplied `newBg * caliK * calik0` and had no clamp.
 * 2. `startCalib`'s first parameter is the coefficient `caliK` (about 1), so the ramp
 *    `((caliK - 1) * count) / 5 + 1` is only meaningful for a coefficient. Feeding a glucose value
 *    there produces a ramp of the order of the glucose itself.
 *
 * The path is unreachable at runtime while `supportsSensorCalibration()` is false, and it cannot
 * become meaningful before the coefficients have a source: they come from the `correctCode` table
 * (server, or the SN-derived fallback) — see the iCan calibration plan.
 */
object ICanHealthCeCalibration {

    /**
     * `AlgNamespace::planCalib` (`0x11b8b4`).
     *
     * [caliK] is accepted for signature parity with the vendor API; the binary does not read it in
     * this function.
     */
    fun planCalib(
        algBg: Float,
        newBg: Float,
        bgList: List<Float>,
        caliK: Float,
        calik0: Float,
        calikM: Float,
    ): ICanHealthCeCalibrationResult {
        val product = maxOf(1f, newBg) * calik0 * calikM
        if (!product.isFinite() || abs(product) <= 1e-6f) {
            // The binary has no guard here and would return NaN/inf. Kept as a port-side safety net;
            // it only changes inputs the vendor never feeds.
            return ICanHealthCeCalibrationResult(
                caliK = 1f,
                caliAdjustBool = false,
                caliAdjustNo = 0,
                calibBg = 0f,
                calik0 = calik0 * calikM,
                calikM = calikM,
            )
        }

        val (slope, _, cv) = lineFit(bgList)
        // The binary computes `fminnm(|slope| * 0.5 + cv * 0.5, 1.0)`. `fminnm` returns the non-NaN
        // operand, so a degenerate history (empty, or all-zero mean, where the binary divides 0/0)
        // produces NaN here and the clamp turns it into 1.0 — not 0. Verified on hardware-free
        // golden vectors from the library itself: with an empty list the vendor returns
        // caliK = 137.4856 for (algBg=180, newBg=0.5, coefficients 1,1,1), which is the sigmoid at
        // ci = 1.0 (0.7625), not at ci = 0 (0.75).
        val confidenceInput = abs(slope) * 0.5f + cv * 0.5f
        val clampedConfidence = if (confidenceInput.isNaN()) 1f else minOf(confidenceInput, 1f)
        val sigmoid = (0.5f / (1f + exp((-clampedConfidence / 10f).toDouble()).toFloat())) + 0.5f
        val factor = ((1f - sigmoid) * product + algBg * sigmoid) / product

        return ICanHealthCeCalibrationResult(
            caliK = factor,
            caliAdjustBool = true,
            caliAdjustNo = 1,
            calibBg = product,
            // The binary stores the product of the two coefficients here, not `calik0`.
            calik0 = calik0 * calikM,
            calikM = calikM,
        )
    }

    /**
     * `AlgNamespace::startCalib` (`0x11ba8c`).
     *
     * Branch for branch the binary does:
     * - `caliAdjustBool == true`: `ramp = ((caliK - 1) * caliAdjustNo) / 5 + 1`,
     *   `caliAdjustBool = caliAdjustNo < 4`, counter `caliAdjustNo + 1` (wrapping to 1, not 0, once
     *   it reaches 4), `calibBg = newBg * calik0 * ramp`, `calikM = ramp`;
     * - otherwise: `calibBg = caliK * newBg * calik0`, `caliAdjustBool = false`, counter unchanged,
     *   `calikM = caliK`.
     *
     * `caliK` and `calik0` pass through unchanged; like the binary, no finite/positive guard is
     * applied.
     */
    fun startCalib(
        caliK: Float,
        caliAdjustBool: Boolean,
        caliAdjustNo: Int,
        newBg: Float,
        calik0: Float,
    ): ICanHealthCeCalibrationResult {
        if (caliAdjustBool) {
            val ramp = (((caliK - 1f) * caliAdjustNo.toFloat()) / 5f) + 1f
            return ICanHealthCeCalibrationResult(
                caliK = caliK,
                caliAdjustBool = caliAdjustNo < 4,
                caliAdjustNo = if (caliAdjustNo < 4) caliAdjustNo + 1 else 1,
                calibBg = newBg * calik0 * ramp,
                calik0 = calik0,
                calikM = ramp,
            )
        }
        return ICanHealthCeCalibrationResult(
            caliK = caliK,
            caliAdjustBool = false,
            caliAdjustNo = caliAdjustNo,
            calibBg = caliK * newBg * calik0,
            calik0 = calik0,
            calikM = caliK,
        )
    }

    /**
     * `AlgNamespace::lineFit` (`0x11b6e0`): least-squares slope, intercept and coefficient of
     * variation over the history, in double precision.
     *
     * The binary uses `x = 1..n` where this port uses `0..n-1`; a constant shift of x changes
     * neither slope nor `cv` (and the intercept is unused by the callers), so the port keeps the
     * simpler index. The binary also divides by `meanY` without a guard — kept here as a
     * port-side guard against an all-zero history.
     */
    private fun lineFit(history: List<Float>): Triple<Float, Float, Float> {
        val samples = history.filter { it.isFinite() && it > 0f }
        if (samples.isEmpty()) {
            // The binary computes meanY = 0 / 0 here, i.e. NaN, and `fminnm` in planCalib turns
            // that into a confidence of 1.0. Returning zeros would silently disagree with it.
            // Invalid samples are filtered first; the vendor never puts non-positive values into
            // bgList, so this only changes degenerate inputs.
            return Triple(Float.NaN, Float.NaN, Float.NaN)
        }
        val n = samples.size.toFloat()
        var sumX = 0f
        var sumY = 0f
        var sumXY = 0f
        var sumX2 = 0f
        samples.forEachIndexed { index, value ->
            val x = index.toFloat()
            sumX += x
            sumY += value
            sumXY += x * value
            sumX2 += x * x
        }
        val meanX = sumX / n
        val meanY = sumY / n
        val denominator = (n * sumX2) - (sumX * sumX)
        val slope = if (abs(denominator) > 1e-6f) {
            ((n * sumXY) - (sumX * sumY)) / denominator
        } else {
            0f
        }
        val intercept = meanY - meanX * slope
        val sumSqDev = samples.sumOf { (it - meanY).pow(2.0f).toDouble() }.toFloat()
        val cv = if (meanY > 1e-6f) {
            (sqrt(sumSqDev / n.toDouble()) / meanY.toDouble()).toFloat()
        } else {
            0f
        }
        return Triple(slope, intercept, cv)
    }
}
