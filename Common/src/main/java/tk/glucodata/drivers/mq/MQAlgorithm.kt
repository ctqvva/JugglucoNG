// MQAlgorithm.kt — Glutec glucose calculation.
//
// Direct port of the vendor GlucoseCalculation(...) path, but with the actual
// parameter semantics recovered from the service call sites:
//   dArr[0]  initTimeMinutes
//   dArr[1]  packetIndex
//   dArr[2]  sampleCurrent
//   dArr[4]  previousReviseCurrent2
//   dArr[5]  kValue
//   dArr[6]  referenceBgTimes10Mmol
//   dArr[7]  bValue
//   dArr[9]  packages
//   dArr[10] multiplier
//
// Output array mirrors the app:
//   [0] packetIndex
//   [1] sampleCurrent
//   [2] reviseCurrent2
//   [3] previousReviseCurrent2
//   [4] kValue
//   [5] referenceBgTimes10Mmol
//   [6] bValue
//   [7] finalGlucose_mmol_x10

package tk.glucodata.drivers.mq

import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.roundToInt

object MQAlgorithm {

    /** Preferences store floats; recover their decimal value before vendor decimal truncation. */
    internal fun decimalParameter(value: Float): Double = value.toString().toDouble()

    private fun roundDown2(value: Double): Double =
        BigDecimal(value.toString()).setScale(2, RoundingMode.DOWN).toDouble()

    @JvmStatic
    fun adjustSampleCurrent(
        algorithmVersion: Int,
        packetIndex: Double,
        sampleCurrent: Double,
        packages: Double,
        multiplier: Double,
    ): Double {
        if (algorithmVersion >= 1) {
            if (packetIndex < MQConstants.ALGO_WARMUP_PACKET_THRESHOLD) {
                return sampleCurrent
            }
            val threshold = packages + MQConstants.ALGO_WARMUP_PACKET_THRESHOLD
            return if (packetIndex <= threshold) {
                val factor = multiplier - (((multiplier - 1.0) / threshold) *
                    (packetIndex - MQConstants.ALGO_WARMUP_PACKET_THRESHOLD))
                factor * sampleCurrent
            } else {
                sampleCurrent
            }
        }
        return if (packetIndex > 8.0 && packetIndex < 250.0) {
            ((100.0 - ((((250.0 - packetIndex) + 1.0) * 25.0) / 242.0)) * sampleCurrent) / 100.0
        } else {
            sampleCurrent
        }
    }

    @JvmStatic
    fun calculate(
        algorithmVersion: Int,
        initTimeMinutes: Double,
        packetIndex: Double,
        sampleCurrent: Double,
        previousReviseCurrent2: Double,
        kValue: Double,
        referenceBgTimes10Mmol: Double,
        bValue: Double,
        packages: Double,
        multiplier: Double,
    ): DoubleArray {
        var reviseCurrent = adjustSampleCurrent(
            algorithmVersion = algorithmVersion,
            packetIndex = packetIndex,
            sampleCurrent = sampleCurrent,
            packages = packages,
            multiplier = multiplier,
        )

        var nextBValue = if (bValue != 0.0) bValue else 0.0
        var nextKValue = if (kValue != 0.0) kValue else 0.0

        if (packetIndex > 8.0 && nextKValue != 0.0 && nextBValue != 0.0 && initTimeMinutes > 9.0) {
            if (reviseCurrent < 5.0) {
                reviseCurrent = previousReviseCurrent2 + nextBValue
            } else {
                val target = previousReviseCurrent2 + nextBValue
                val tolerance = target * 0.05
                val upper = target + tolerance + 0.5
                val lower = target - tolerance - 0.5
                if (reviseCurrent > upper) reviseCurrent = upper
                else if (reviseCurrent < lower) reviseCurrent = lower
            }
        }

        var reviseCurrent2 = MQConstants.ALGO_MMOL_FLOOR
        if (referenceBgTimes10Mmol == 0.0) {
            if (reviseCurrent > nextBValue) reviseCurrent -= nextBValue
            if (reviseCurrent >= MQConstants.ALGO_MMOL_FLOOR || nextKValue == 0.0) {
                reviseCurrent2 = reviseCurrent
            }
        } else {
            nextBValue = 2.0
            if (reviseCurrent > 2.0) reviseCurrent -= 2.0
            reviseCurrent2 =
                if (reviseCurrent < MQConstants.ALGO_MMOL_FLOOR) MQConstants.ALGO_MMOL_FLOOR else reviseCurrent
            val referenceBgMmol = referenceBgTimes10Mmol / 10.0
            nextKValue = if (referenceBgMmol > 0.0) reviseCurrent2 / referenceBgMmol else 0.0
        }

        val glucoseTimes10Mmol: Double = if (nextKValue > 0.0) {
            val raw = ((reviseCurrent2 / nextKValue) + 0.05) * 10.0
            when {
                raw <= MQConstants.ALGO_MMOL_MIN_TIMES10 -> MQConstants.ALGO_MMOL_MIN_TIMES10
                raw >= MQConstants.ALGO_MMOL_MAX_TIMES10 -> MQConstants.ALGO_MMOL_MAX_TIMES10
                else -> raw
            }
        } else 0.0

        return doubleArrayOf(
            packetIndex.toInt().toDouble(),
            sampleCurrent.toInt().toDouble(),
            (reviseCurrent2 + 0.5).toInt().toDouble(),
            previousReviseCurrent2.toInt().toDouble(),
            roundDown2(nextKValue),
            referenceBgTimes10Mmol.toInt().toDouble(),
            roundDown2(nextBValue),
            glucoseTimes10Mmol.toInt().toDouble(),
        )
    }

    /** Decoded glucose reading suitable for feeding the app's reading pipeline. */
    data class Result(
        val packetIndex: Int,
        val sampleCurrent: Double,
        val reviseCurrent2: Double,
        val previousReviseCurrent2: Double,
        val kValue: Double,
        val referenceBgTimes10Mmol: Double,
        val bValue: Double,
        val glucoseTimes10Mmol: Int,
        val glucoseMmol: Double,
    ) {
        val mgdlTimes10: Int
            get() = (glucoseMmol * MQConstants.MMOL_TO_MGDL * 10.0).roundToInt()

        /** True mg/dL value (converted from vendor mmol output). */
        val mgdl: Float get() = (glucoseMmol * MQConstants.MMOL_TO_MGDL).toFloat()

        /** True mmol/L value for unit-agnostic logging. */
        val mmol: Float get() = glucoseMmol.toFloat()
    }

    @JvmStatic
    fun calculateResult(
        algorithmVersion: Int,
        initTimeMinutes: Double,
        packetIndex: Double,
        sampleCurrent: Double,
        previousReviseCurrent2: Double,
        kValue: Double,
        referenceBgTimes10Mmol: Double,
        bValue: Double,
        packages: Double,
        multiplier: Double,
    ): Result {
        val arr = calculate(
            algorithmVersion,
            initTimeMinutes,
            packetIndex,
            sampleCurrent,
            previousReviseCurrent2,
            kValue,
            referenceBgTimes10Mmol,
            bValue,
            packages,
            multiplier,
        )
        val glucoseTimes10Mmol = arr[7].toInt()
        return Result(
            packetIndex = arr[0].toInt(),
            sampleCurrent = arr[1],
            reviseCurrent2 = arr[2],
            previousReviseCurrent2 = arr[3],
            kValue = arr[4],
            referenceBgTimes10Mmol = arr[5],
            bValue = arr[6],
            glucoseTimes10Mmol = glucoseTimes10Mmol,
            glucoseMmol = glucoseTimes10Mmol / 10.0,
        )
    }
}
