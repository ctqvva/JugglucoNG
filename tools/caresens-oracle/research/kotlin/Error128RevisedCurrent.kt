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

/**
 * Experimental revised-current stage (`err128_CGM_c_noise_revised_value`) consumed by
 * [Error16Prototype]. Derived by black-box state perturbation against the pinned reference.
 */
internal class Error128RevisedCurrent {
    private var acceptedCount = 0
    private val sequences = IntArray(WINDOW)
    private val glucose = DoubleArray(WINDOW) { Double.NaN }
    private val times = LongArray(WINDOW)
    private val flags = IntArray(FLAG_HISTORY)

    var flag = 0
        private set
    var normal = Double.NaN
        private set
    var revisedValue = 0.0
        private set

    fun update(sequence: Int, timeSeconds: Long, current5Min: Double, readingGlucose: Double, preRecordProfile: Int): Double {
        val roundedGlucose = roundAwayFromZero(readingGlucose)
        val nextFlag: Int
        val nextNormal: Double
        val nextRevised: Double

        if (acceptedCount < MIN_ACCEPTED || preRecordProfile > MAX_PROFILE) {
            nextFlag = 0
            nextNormal = Double.NaN
            nextRevised = current5Min
        } else if (flags[FLAG_HISTORY - 1] != 0) {
            val withinTimeout = flags[TIMEOUT_SLOT] == 0
            val withinGap = abs(sequence - sequences[WINDOW - 1]) <= MAX_PERSIST_SEQ_GAP
            val recovered = roundedGlucose >= RECOVERY_FLOOR || roundedGlucose >= normal
            if (withinTimeout && withinGap && !recovered) {
                nextFlag = 1
                nextNormal = normal
                nextRevised = revisedValue
            } else {
                nextFlag = 0
                nextNormal = Double.NaN
                nextRevised = current5Min
            }
        } else {
            val oldestSeq = sequences[1]
            val seqSpan = sequence - oldestSeq
            val seqContinuous = oldestSeq != 0 && seqSpan in 1..11
            var belowCeiling = true
            for (k in 1 until WINDOW) {
                if (glucose[k] > ONSET_GLUCOSE_CEILING) {
                    belowCeiling = false
                    break
                }
            }
            var stableIntervals = 0
            for (k in 1 until WINDOW - 1) {
                val dt = (times[k + 1] - times[k]) and 0xffffffffL
                if (dt != 0L) {
                    val rate = (glucose[k + 1] - glucose[k]) / (dt / 60.0)
                    if (abs(rate) < STABILITY_RATE_LIMIT) stableIntervals++
                }
            }
            val currentDt = (timeSeconds - times[WINDOW - 1]) and 0xffffffffL
            val sharpDrop = currentDt != 0L && (roundedGlucose - glucose[WINDOW - 1]) / (currentDt / 60.0) < DROP_RATE_LIMIT
            if (seqContinuous && belowCeiling && stableIntervals >= MIN_STABLE_INTERVALS && sharpDrop) {
                nextFlag = 1
                nextNormal = glucose[WINDOW - 1]
                nextRevised = revisedValue
            } else {
                nextFlag = 0
                nextNormal = Double.NaN
                nextRevised = current5Min
            }
        }

        acceptedCount++
        sequences.copyInto(sequences, endIndex = WINDOW, startIndex = 1)
        sequences[WINDOW - 1] = sequence
        glucose.copyInto(glucose, endIndex = WINDOW, startIndex = 1)
        glucose[WINDOW - 1] = roundedGlucose
        times.copyInto(times, endIndex = WINDOW, startIndex = 1)
        times[WINDOW - 1] = timeSeconds and 0xffffffffL
        flags.copyInto(flags, endIndex = FLAG_HISTORY, startIndex = 1)
        flags[FLAG_HISTORY - 1] = nextFlag

        flag = nextFlag
        normal = nextNormal
        revisedValue = nextRevised
        return nextRevised
    }

    fun restoreVendor(block: ByteBuffer) {
        fun u8(offset: Int) = block.get(offset).toInt() and 0xff
        fun u16(offset: Int) = block.getShort(offset).toInt() and 0xffff
        fun u32(offset: Int) = block.getInt(offset).toLong() and 0xffffffffL
        // Offsets follow tools/caresens-oracle/research/state-offsets.txt.
        acceptedCount = u16(1608) // idx
        for (i in 0 until WINDOW) {
            sequences[i] = u16(1610 + 2 * (865 - WINDOW + i)) // accu_seq
            times[i] = u32(456 + 4 * (288 - WINDOW + i)) // time_standard_arr
            glucose[i] = block.getDouble(17144 + 8 * (288 - WINDOW + i)) // err_glu_arr
        }
        for (i in 0 until FLAG_HISTORY) flags[i] = u8(48826 + i) // err128_flag_prev
        flag = flags[FLAG_HISTORY - 1]
        normal = block.getDouble(48872) // err128_normal_prev
        revisedValue = block.getDouble(48880) // err128_revised_value_prev
    }

    companion object {
        private const val WINDOW = 12
        private const val FLAG_HISTORY = 40
        private const val TIMEOUT_SLOT = 4 // 36 accepted records before the current record
        private const val MIN_ACCEPTED = 36
        private const val MAX_PROFILE = 2
        private const val MAX_PERSIST_SEQ_GAP = 3
        private const val RECOVERY_FLOOR = 80.0
        private const val ONSET_GLUCOSE_CEILING = 120.0
        private const val STABILITY_RATE_LIMIT = 0.8
        private const val MIN_STABLE_INTERVALS = 9
        private const val DROP_RATE_LIMIT = -2.5

        private fun roundAwayFromZero(value: Double): Double =
            if (value < 0) ceil(value - 0.5) else floor(value + 0.5)
    }
}
