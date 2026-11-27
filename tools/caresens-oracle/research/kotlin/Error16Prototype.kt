/*
 * This file is part of JugglucoNG.
 *
 * JugglucoNG is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option)
 * any later version.
 */

package tk.glucodata.drivers.caresens.calibration

import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

/** Experimental empty-calibration sensitivity detector. See docs/caresens-air-handoff/error-16-evidence.md. */
internal class Error16Prototype {
    private val sequences = IntArray(DAYS_3 + 1)
    private val current = history(12)
    private val glucose = history(12)
    private val isf = history(DAYS_3 + 1)
    private val plasma = history(12)
    private val minutes = history(7)
    private val rates = history(289)
    private val steady = history(PERSISTENCE)
    private val minimumTrend = history(DAYS_3 + 1)
    private val meanTrend = history(24 * 12 - 11)
    private val minimumReference = RunningReference(0.0, maximum = true)
    private val meanReference = RunningReference(5.0, maximum = true)
    private val steadyReference = RunningReference(2.0, maximum = false)
    private val minimumDrop = Change()
    private val meanDrop = Change()
    private val steadyChange = Change()
    private val shortMinimumFit = Fit()
    private val longMinimumFit = Fit()
    private val meanFit = Fit()
    private val signalBuffer = DoubleArray(DAYS_3 + 1)

    private var firstTime = 0L
    private var profile = 100
    private var profileCount = 0
    private var profileSum = 0.0
    private var profileAbove = 0.0
    private var profileInRange = 0.0
    var latched = false
        private set
    var conditions = 0
        private set

    // Column order follows STATS in err16_replay.py. Leave tails visible on rejections.
    fun writeStatistics(output: ByteBuffer) {
        output.putDouble(firstTime.toDouble())
        output.putDouble(minutes.last())
        output.putDouble(isf.last())
        output.putDouble(plasma.last())
        output.putDouble(rates.last())
        output.putDouble(steady.last())
        steadyReference.writeStatistics(output)
        steadyChange.writeStatistics(output)
        output.putDouble(minimumTrend.last())
        output.putDouble(meanTrend.last())
        shortMinimumFit.writeStatistics(output)
        longMinimumFit.writeStatistics(output)
        meanFit.writeStatistics(output)
        minimumReference.writeStatistics(output)
        meanReference.writeStatistics(output)
        minimumDrop.writeStatistics(output)
        meanDrop.writeStatistics(output)
        output.putDouble(profile.toDouble())
        output.putDouble(profileCount.toDouble())
    }

    fun update(sequence: Int, time: Long, current5Min: Double, reading: Double, slope100: Float, diabetesLevel: Int) {
        if (firstTime == 0L) firstTime = time
        sequences.copyInto(sequences, endIndex = sequences.size, startIndex = 1)
        sequences[sequences.lastIndex] = sequence
        current.push(current5Min)
        glucose.push(roundAwayFromZero(reading))
        val continuous = sequences[sequences.size - 12] != 0 && sequence - sequences[sequences.size - 12] == 11
        isf.push(if (continuous) roundAwayFromZero(filter(current) * 100.0 / slope100) else Double.NaN)
        val smoothedGlucose = if (continuous) roundAwayFromZero(filter(glucose)) else Double.NaN
        plasma.push(smoothedGlucose)
        minutes.push((time - firstTime) / 60.0)
        val near = minutes.last() - minutes.first() <= 40.0

        val recentSignal = isf.size - 7
        var rate = Double.NaN
        if (sequence >= 871) {
            var hi = Double.NEGATIVE_INFINITY
            var lo = Double.POSITIVE_INFINITY
            var valid = 0
            for (i in recentSignal until isf.size) {
                if (!isf[i].isNaN() && sequence - sequences[i] <= 6) {
                    hi = maxOf(hi, isf[i])
                    lo = minOf(lo, isf[i])
                    valid++
                }
            }
            if (valid >= 4) {
                rate = hi - lo
            }
        }
        rates.push(rate)
        var withinDay = 0
        var steadyRates = 0
        for (i in rates.indices) {
            val q = sequences[sequences.size - rates.size + i]
            if (q != 0 && sequence - q <= 288 && !rates[i].isNaN()) {
                withinDay++
                if (rates[i] <= 1.0) steadyRates++
            }
        }
        val steadyPercent = if (near && withinDay >= 231) steadyRates.toDouble() / withinDay * 100.0 else Double.NaN
        steady.push(steadyPercent)
        steadyReference.update(steadyPercent, !steadyPercent.isNaN(), sequence, sequences, signalBuffer)
        steadyChange.push(steadyPercent, steadyReference.value)

        val inWindow = sequences[sequences.size - 692] != 0 && sequence - sequences[sequences.size - 692] <= DAYS_3
        var minTrend = minimumTrend.last()
        var centerTrend = meanTrend.last()
        if (inWindow) {
            var valid = 0
            for (i in isf.indices) {
                if (sequences[i] != 0 && sequence - sequences[i] <= DAYS_3 && !isf[i].isNaN()) {
                    signalBuffer[valid++] = isf[i]
                }
            }
            if (valid > 0) {
                java.util.Arrays.sort(signalBuffer, 0, valid)
                minTrend = signalBuffer[rank(valid, 10)]
                centerTrend = centralMean(signalBuffer, valid)
            } else {
                minTrend = Double.NaN
                centerTrend = Double.NaN
            }
        }
        minimumTrend.push(minTrend)
        meanTrend.push(centerTrend)
        shortMinimumFit.update(minimumTrend, 12, 3, 12, inWindow)
        longMinimumFit.update(minimumTrend, 73, 12, 12, inWindow)
        meanFit.update(meanTrend, 24, 12, 24, inWindow)
        minimumReference.update(minTrend, inWindow && sequence >= 865, sequence, sequences, signalBuffer)
        meanReference.update(centerTrend, inWindow && sequence >= 865, sequence, sequences, signalBuffer)
        if (inWindow) {
            minimumDrop.push(minTrend, minimumReference.value)
            meanDrop.push(centerTrend, meanReference.value)
        } else {
            minimumDrop.hold()
            meanDrop.hold()
        }

        conditions = if (meanReference.count >= DAYS_3) evaluate(near) else 0
        latched = CareSensAirDetectorLatch.permanent(latched, conditions != 0)
        // The detector sees the profile from before this record, including on seq 2017.
        if (sequence >= 2017) {
            profile = diabetesLevel
        } else if (sequence >= 864) {
            if (profile == 100) profile = 0
            if (sequence > 864 && profile == 0 && !smoothedGlucose.isNaN()) {
                profileCount++
                profileSum += smoothedGlucose
                if (smoothedGlucose > 180) profileAbove++
                if (smoothedGlucose in 70.0..180.0) profileInRange++
                if (plasma.all { it >= 300 } || (profileCount >= 288 &&
                        (profileSum > 140.0 * profileCount || profileAbove > 0.1 * profileCount ||
                            profileInRange < 0.8 * profileCount))
                ) {
                    profile = 6
                }
            }
        }
    }

    private fun evaluate(near: Boolean): Int {
        val rise = steady.all { it > 50 } && steadyChange.difference.all { it > 50 } && steadyChange.percent.all { it > 50 }
        val loss = minimumDrop.atLeast(50) && meanDrop.atLeast(40)
        val rateCriterion = near && minimumDrop.atLeast(20) && meanDrop.atLeast(20) &&
            (rise || steady.all { it > 60 })
        val condition0 = when (profile) {
            3, 4 -> loss
            5, 6 -> near && loss && steady.all { it > 5 } && longMinimumFit.falling(10.0, LONG_SLOPE_HALF_STEP)
            else -> meanDrop.atLeast(50)
        }
        val condition1 = profile in 0..2 &&
            ((meanDrop.difference.all { it <= -50 } && meanFit.falling(5.0)) ||
                (minimumDrop.difference.all { it <= -50 } && shortMinimumFit.falling(15.0)))
        val condition2 = when (profile) {
            3, 4 -> rateCriterion
            5, 6 -> rateCriterion && (longMinimumFit.falling(7.0, LONG_SLOPE_HALF_STEP) ||
                    (longMinimumFit.slope.all { abs(it) < 4.0 - LONG_SLOPE_HALF_STEP } &&
                        longMinimumFit.rSquared.all { it > 0.9 }))
            else -> near && rise && meanDrop.atLeast(35)
        }
        return (if (condition0) 1 else 0) or (if (condition1) 2 else 0) or (if (condition2) 4 else 0)
    }

    private class Change {
        val difference = history(PERSISTENCE)
        val percent = history(PERSISTENCE)
        fun push(current: Double, reference: Double) {
            difference.push(current - reference)
            percent.push((current - reference) / reference * 100.0)
        }
        fun hold() {
            difference.push(difference.last())
            percent.push(percent.last())
        }
        fun atLeast(drop: Int): Boolean =
            difference.all { it <= -drop } && percent.all { it <= -drop }

        fun writeStatistics(output: ByteBuffer) {
            output.putDouble(difference.last())
            output.putDouble(percent.last())
        }
    }

    private class Fit {
        val slope = history(PERSISTENCE)
        val rSquared = history(PERSISTENCE)

        fun update(values: DoubleArray, points: Int, stride: Int, required: Int, active: Boolean) {
            var sx = 0.0
            var sy = 0.0
            var n = 0
            if (active) for (i in 0 until points) {
                val y = values[values.lastIndex - (points - 1 - i) * stride]
                if (!y.isNaN()) {
                    sx += i * stride / 288.0
                    sy += y
                    n++
                }
            }
            if (n < required) {
                slope.push(Double.NaN)
                rSquared.push(Double.NaN)
                return
            }
            val mx = sx / n
            val my = sy / n
            var xy = 0.0
            var xx = 0.0
            var yy = 0.0
            for (i in 0 until points) {
                val y = values[values.lastIndex - (points - 1 - i) * stride]
                if (!y.isNaN()) {
                    val dx = i * stride / 288.0 - mx
                    val dy = y - my
                    xy += dx * dy
                    xx += dx * dx
                    yy += dy * dy
                }
            }
            slope.push(xy / xx)
            rSquared.push(xy * xy / (xx * yy))
        }

        fun falling(limit: Double, margin: Double = 0.0): Boolean =
            slope.all { if (margin == 0.0) it < -limit else it <= -limit - margin } && rSquared.all { it > 0.8 }

        fun writeStatistics(output: ByteBuffer) {
            output.putDouble(slope.last())
            output.putDouble(rSquared.last())
        }
    }

    private class RunningReference(private val tolerance: Double, private val maximum: Boolean) {
        var value = Double.NaN
        var count = 0
        val candidates = history(DAYS_3 + 1)

        fun writeStatistics(output: ByteBuffer) {
            output.putDouble(count.toDouble())
            output.putDouble(candidates.last())
            output.putDouble(value)
        }

        fun update(observed: Double, active: Boolean, sequence: Int, sequences: IntArray, buffer: DoubleArray) {
            val candidate = when {
                !active -> if (maximum) value else Double.NaN
                value.isNaN() -> observed
                observed.isNaN() -> value
                maximum -> maxOf(value, observed)
                else -> minOf(value, observed)
            }
            candidates.push(candidate)
            if (!active) return
            count++
            if (count == DAYS_3) {
                var valid = 0
                for (v in candidates) if (!v.isNaN()) buffer[valid++] = v
                if (valid > 0) {
                    java.util.Arrays.sort(buffer, 0, valid)
                    value = centralMean(buffer, valid)
                }
            } else if (count in 25 until DAYS_3 && !candidate.isNaN()) {
                for (i in candidates.lastIndex - 24 until candidates.lastIndex) {
                    if (sequence - sequences[i] <= 24 && !(abs(candidates[i] - candidate) <= tolerance)) return
                }
                value = candidate
            }
        }
    }

    fun restoreVendor(block: ByteBuffer) {
        fun u16(offset: Int) = block.getShort(offset).toInt() and 0xffff
        fun read(target: DoubleArray, offset: Int, length: Int) {
            for (i in target.indices) target[i] = block.getDouble(offset + 8 * (length - target.size + i))
        }
        // Offsets follow tools/caresens-oracle/research/state-offsets.txt.
        for (i in sequences.indices) sequences[i] = u16(1610 + 2 * i) // accu_seq
        read(current, 48888, 36) // err128_CGM_c_noise_revised_value
        read(glucose, 17144, 288) // err_glu_arr
        firstTime = block.getInt(49176).toLong() and 0xffffffffL // err16_time5_first
        read(minutes, 49184, 36) // err16_dt_arr
        read(isf, 51648, 865) // err16_CGM_ISF_smooth
        read(plasma, 58568, 36) // err16_CGM_plasma
        steadyReference.count = block.getDouble(58856).toInt() // err16_CGM_ISF_roc_n
        read(rates, 58864, 577) // err16_CGM_ISF_roc_value
        read(steady, 63480, 36) // err16_CGM_ISF_roc_steady
        steadyReference.value = block.getDouble(63768) // err16_CGM_ISF_roc_min
        read(steadyReference.candidates, 63776, 865) // err16_CGM_ISF_roc_min_temp
        read(steadyChange.difference, 70704, 36) // err16_CGM_ISF_roc_diff
        read(steadyChange.percent, 70992, 36) // err16_CGM_ISF_roc_ratio
        minimumReference.count = block.getDouble(71280).toInt() // err16_CGM_ISF_trend_min_n
        read(minimumTrend, 71304, 865) // err16_CGM_ISF_trend_min_value_arr
        read(shortMinimumFit.slope, 78224, 36) // err16_CGM_ISF_trend_min_slope1
        read(longMinimumFit.slope, 78512, 36) // err16_CGM_ISF_trend_min_slope2
        read(shortMinimumFit.rSquared, 78800, 36) // err16_CGM_ISF_trend_min_rsq1
        read(longMinimumFit.rSquared, 79088, 36) // err16_CGM_ISF_trend_min_rsq2
        read(minimumDrop.difference, 79376, 36) // err16_CGM_ISF_trend_min_diff
        read(minimumDrop.percent, 79664, 36) // err16_CGM_ISF_trend_min_ratio
        minimumReference.value = block.getDouble(79952) // err16_CGM_ISF_trend_min_max
        read(minimumReference.candidates, 79960, 865) // err16_CGM_ISF_trend_min_max_temp
        meanReference.count = block.getDouble(94736).toInt() // err16_CGM_ISF_trend_mean_n
        read(meanTrend, 94760, 865) // err16_CGM_ISF_trend_mean_value_arr
        read(meanFit.slope, 101680, 36) // err16_CGM_ISF_trend_mean_slope
        read(meanFit.rSquared, 101968, 36) // err16_CGM_ISF_trend_mean_rsq
        read(meanDrop.difference, 102256, 36) // err16_CGM_ISF_trend_mean_diff
        read(meanDrop.percent, 102544, 36) // err16_CGM_ISF_trend_mean_ratio
        meanReference.value = block.getDouble(102832) // err16_CGM_ISF_trend_mean_max
        read(meanReference.candidates, 102840, 865) // err16_CGM_ISF_trend_mean_max_temp
        profileCount = u16(10912) // diabetes_before_7days_n_valid
        profile = block.get(10914).toInt() and 0xff // diabetes_before_7days_flag
        profileSum = block.getDouble(10920) // diabetes_before_7days_eAG
        profileAbove = block.getDouble(10936) // diabetes_before_7days_TAR
        profileInRange = block.getDouble(10944) // diabetes_before_7days_TIR
        latched = block.get(117280).toInt() != 0 // err16_result_prev
    }

    companion object {
        const val STAT_COUNT = 31
        private const val DAYS_3 = 864
        private const val PERSISTENCE = 24
        private const val LONG_SLOPE_HALF_STEP = 5e-11
        private val weights = doubleArrayOf(
            0.19203786935092992, 0.16522460111444, 0.11844988518211, 0.0716100935714, 0.03473077453465,
            0.01186996852659, 0.00419148479069, 0.01186996852659, 0.03473077453465, 0.0716100935714,
            0.11844988518211, 0.16522460111444,
        )

        private fun history(size: Int) = DoubleArray(size) { Double.NaN }

        private fun DoubleArray.push(value: Double) {
            copyInto(this, startIndex = 1)
            this[lastIndex] = value
        }

        private fun filter(values: DoubleArray): Double {
            var sum = 0.0
            for (i in weights.indices) sum += weights[i] * values[values.lastIndex - i]
            return sum
        }

        private fun roundAwayFromZero(value: Double): Double =
            if (value < 0) ceil(value - 0.5) else floor(value + 0.5)

        private fun rank(count: Int, percent: Int): Int = maxOf(0, (count * percent + 50) / 100 - 1)

        private fun centralMean(sorted: DoubleArray, count: Int): Double {
            val low = sorted[rank(count, 20)]
            val high = sorted[rank(count, 80)]
            var sum = 0.0
            var n = 0
            for (i in 0 until count) if (low == high || sorted[i] in low..high) {
                sum += sorted[i]
                n++
            }
            return sum / n
        }
    }
}
