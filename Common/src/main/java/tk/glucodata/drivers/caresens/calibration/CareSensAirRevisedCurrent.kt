/*
 * This file is part of JugglucoNG.
 *
 * JugglucoNG is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option)
 * any later version.
 */

package tk.glucodata.drivers.caresens.calibration

import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import kotlin.math.abs

/** Revised current consumed by error 16, derived from black-box vendor replay. */
internal class CareSensAirRevisedCurrent {
    private val times = LongArray(PRIOR_RECORDS) { MISSING_TIME }
    private val flags = IntArray(FLAG_HISTORY)

    var normal = Double.NaN
        private set
    var revisedValue = 0.0
        private set

    // Reads the shared accepted sequence/glucose history and profile before the delay stage advances them.
    fun update(state: CareSensAirCalibrationState, sequence: Int, timeSeconds: Long, current5Min: Double, glucose: Double): Double {
        val roundedGlucose = CareSensAirDelayFlag.roundAwayFromZero(glucose)
        val priorGlucose = state.delayGlucose
        val first = priorGlucose.size - PRIOR_RECORDS
        val nextFlag: Int
        val nextNormal: Double
        val nextRevised: Double

        if (state.count < MIN_ACCEPTED || state.delayProfile > MAX_PROFILE) {
            nextFlag = 0
            nextNormal = Double.NaN
            nextRevised = current5Min
        } else if (flags.last() != 0) {
            val withinTimeout = flags[TIMEOUT_SLOT] == 0
            val withinGap = abs(sequence - state.delaySequence.last()) <= MAX_PERSIST_SEQ_GAP
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
            val oldestSeq = state.delaySequence[first]
            val seqSpan = sequence - oldestSeq
            val seqContinuous = oldestSeq != 0 && seqSpan in 1..11
            var belowCeiling = true
            for (k in first until priorGlucose.size) {
                if (priorGlucose[k] > ONSET_GLUCOSE_CEILING) {
                    belowCeiling = false
                    break
                }
            }
            var stableIntervals = 0
            for (k in 0 until times.lastIndex) {
                val dt = (times[k + 1] - times[k]) and 0xffffffffL
                if (dt != 0L) {
                    val rate = (priorGlucose[first + k + 1] - priorGlucose[first + k]) / (dt / 60.0)
                    if (abs(rate) < STABILITY_RATE_LIMIT) stableIntervals++
                }
            }
            val currentDt = (timeSeconds - times.last()) and 0xffffffffL
            val sharpDrop = currentDt != 0L && (roundedGlucose - priorGlucose.last()) / (currentDt / 60.0) < DROP_RATE_LIMIT
            if (times[0] != MISSING_TIME && seqContinuous && belowCeiling && stableIntervals >= MIN_STABLE_INTERVALS && sharpDrop) {
                nextFlag = 1
                nextNormal = priorGlucose.last()
                nextRevised = revisedValue
            } else {
                nextFlag = 0
                nextNormal = Double.NaN
                nextRevised = current5Min
            }
        }

        times.copyInto(times, startIndex = 1)
        times[times.lastIndex] = timeSeconds and 0xffffffffL
        flags.copyInto(flags, startIndex = 1)
        flags[flags.lastIndex] = nextFlag

        normal = nextNormal
        revisedValue = nextRevised
        return nextRevised
    }

    fun write(output: DataOutputStream) {
        times.forEach(output::writeLong)
        flags.forEach(output::writeByte)
        output.writeDouble(normal)
        output.writeDouble(revisedValue)
    }

    fun read(input: DataInputStream) {
        for (i in times.indices) times[i] = input.readLong()
        for (i in flags.indices) flags[i] = input.readUnsignedByte()
        normal = input.readDouble()
        revisedValue = input.readDouble()
    }

    fun restoreVendor(block: ByteBuffer) {
        // Offsets follow tools/caresens-oracle/research/state-offsets.txt.
        for (i in times.indices) {
            times[i] = block.getInt(456 + 4 * (288 - PRIOR_RECORDS + i)).toLong() and 0xffffffffL // time_standard_arr
        }
        for (i in flags.indices) flags[i] = block.get(48826 + i).toInt() and 0xff // err128_flag_prev
        normal = block.getDouble(48872) // err128_normal_prev
        revisedValue = block.getDouble(48880) // err128_revised_value_prev
    }

    companion object {
        private const val PRIOR_RECORDS = 11
        private const val MISSING_TIME = -1L // Older engine states lack this timestamp history.
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
    }
}
