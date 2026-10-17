package tk.glucodata.drivers.icanhealth

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sqrt

data class ICanHealthCeCalibrationResult(
    val encodedP: Float,
    val isCalibrated: Boolean,
    val calibCount: Int,
    val calibProduct: Float,
    val currentTimesTemp: Float,
    val temperature: Float,
)

object ICanHealthCeCalibration {

    fun planCalib(
        currentGluMgDl: Float,
        fingerGluMgDl: Float,
        historyMgDl: List<Float>,
        sensorCurrent: Float,
        temperatureC: Float,
    ): ICanHealthCeCalibrationResult {
        val safeProduct = (fingerGluMgDl * sensorCurrent * temperatureC).takeIf { it.isFinite() && abs(it) > 1e-6f }
            ?: return ICanHealthCeCalibrationResult(
                encodedP = 1f,
                isCalibrated = false,
                calibCount = 0,
                calibProduct = 0f,
                currentTimesTemp = sensorCurrent * temperatureC,
                temperature = temperatureC,
            )

        val (slope, _, cv) = lineFit(historyMgDl)
        val confidenceInput = (cv * 0.5f + abs(slope) * 0.5f).coerceAtMost(1f)
        val sigmoid = (0.5f / (1f + exp((-confidenceInput / 10f).toDouble()).toFloat())) + 0.5f
        val blended = sigmoid * currentGluMgDl + (1f - sigmoid) * safeProduct
        val factor = (blended / safeProduct).takeIf { it.isFinite() } ?: 1f

        return ICanHealthCeCalibrationResult(
            encodedP = factor,
            isCalibrated = true,
            calibCount = 1,
            calibProduct = safeProduct,
            currentTimesTemp = sensorCurrent * temperatureC,
            temperature = temperatureC,
        )
    }

    /**
     * Mirrors `AlgNamespace::startCalib` from the vendor `libAlg.so`
     * (iCan Health 01.18.00.19, ARM Thumb function at file offset 0xd740).
     *
     * The previous port had the two branches swapped: it applied the
     * count-based ramp when `isFirstCalibration` was false, while the binary
     * applies it when the flag is true (the JNI bridge in `libsinoCgmSdk.so`
     * normalises the jboolean to 0/1 and passes it through without inversion).
     * Concretely the binary does:
     * - flag true:  ramp = ((cur - 1) * count) / 5 + 1, calibrated = count < 4,
     *   next count = count + 1 (wrapping to 1, not 0, once count reaches 4),
     *   product = current * temp * ramp, temperature slot = ramp;
     * - flag false: product = cur * current * temp, not calibrated, count kept
     *   as-is, temperature slot = cur.
     *
     * Like the binary, no finite/positive guard is applied to the inputs here.
     * Note the binary stores the raw finger glucose in the temperature slot on
     * the non-first path; that looks odd but is what the lib does, so it is
     * mirrored verbatim.
     *
     * Currently unreachable at runtime (`supportsSensorCalibration()` is false,
     * so `calibrateSensor()` never runs); kept exact so a future enablement
     * matches the vendor behaviour.
     */
    fun startCalib(
        currentGluMgDl: Float,
        isFirstCalibration: Boolean,
        existingCalibrationCount: Int,
        sensorCurrent: Float,
        temperatureC: Float,
    ): ICanHealthCeCalibrationResult {
        if (isFirstCalibration) {
            val rampFactor =
                (((currentGluMgDl - 1f) * existingCalibrationCount.toFloat()) / 5f) + 1f
            val calibrated = existingCalibrationCount < 4
            val nextCount =
                if (existingCalibrationCount < 4) existingCalibrationCount + 1 else 1
            return ICanHealthCeCalibrationResult(
                encodedP = currentGluMgDl,
                isCalibrated = calibrated,
                calibCount = nextCount,
                calibProduct = sensorCurrent * temperatureC * rampFactor,
                currentTimesTemp = temperatureC,
                temperature = rampFactor,
            )
        }
        return ICanHealthCeCalibrationResult(
            encodedP = currentGluMgDl,
            isCalibrated = false,
            calibCount = existingCalibrationCount,
            calibProduct = currentGluMgDl * sensorCurrent * temperatureC,
            currentTimesTemp = temperatureC,
            temperature = currentGluMgDl,
        )
    }

    private fun lineFit(history: List<Float>): Triple<Float, Float, Float> {
        val samples = history.filter { it.isFinite() && it > 0f }
        if (samples.isEmpty()) {
            return Triple(0f, 0f, 0f)
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
