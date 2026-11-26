/*
 * This file is part of JugglucoNG.
 *
 * JugglucoNG is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option)
 * any later version.
 */

package tk.glucodata.drivers.caresens.calibration

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import kotlin.math.abs
import kotlin.math.sqrt

/** Everything [CareSensAirCalibration] carries from one record to the next. */
class CareSensAirCalibrationState {
    var count = 0
    var previousSequence = 0
    var previousTime = 0L
    var contactCheckStart = 0
    var rejectedSinceAccepted = false

    // Spike limiter and minute averaging.
    val rawTail = DoubleArray(5)
    val cleanTail = DoubleArray(5)
    val flags = IntArray(6)
    val history60 = DoubleArray(60)
    val history60Time = LongArray(60)
    val previousMedians = DoubleArray(3)

    // Sensitivity-loss compensation.
    var initialSum = 0.0
    var initialCount = 0
    var highPassStarted = false
    var highPassInput = 0.0
    var highPass = 0.0
    var referenceSum = 0.0
    var referenceCount = 0

    // Day-7 glucose statistics (count, Welford mean and M2, above 180, below 70).
    var diabetesCount = 0
    var diabetesMean = 0.0
    var diabetesM2 = 0.0
    var diabetesHigh = 0
    var diabetesLow = 0
    var diabetesLevel = 6
    var diabetesLevelDone = false
    var lastGlucose = 0.0
    var hasLastGlucose = false

    val temperatures = DoubleArray(4) { Double.NaN }

    // Kalman filter.
    val kalman = DoubleArray(3)
    var warmup = 0
    val rates = DoubleArray(4) { Double.NaN }
    var fluctuating = false
    var fluctuationCount = 0
    var previousRescaled = 0.0

    val history = DoubleArray(10)
    val historyTime = LongArray(10)
    val historySequence = IntArray(10)
    val historyRaw = DoubleArray(10) { Double.NaN }

    // Error 4: raw maxima of the newest records with consecutive sequence numbers.
    val lowRawMax = DoubleArray(4)
    var lowRawCount = 0
    var lowError = false
    val historyMaybeFlagged = BooleanArray(10)
    var referenceMaybeFlagged = false

    // Interruption check.
    var timingStarted = false
    var timingSequence = 0
    var timingTime = 0L
    val gap23 = IntArray(4)
    var gap23Count = 0
    val gap60 = IntArray(2)
    var gap60Count = 0
    var timingError = false

    fun addDiabetesSample(v: Double) {
        diabetesCount++
        val d = v - diabetesMean
        diabetesMean += d / diabetesCount
        diabetesM2 += d * (v - diabetesMean)
        if (v > 180) diabetesHigh++
        if (v < 70) diabetesLow++
    }

    fun diabetesLevel(): Int {
        // Needs more than a day of samples.
        if (diabetesCount <= 288) return 6
        // Same operation order as the reference, so values on a threshold agree.
        val above = diabetesHigh.toDouble() / diabetesCount * 100
        val below = diabetesLow.toDouble() / diabetesCount * 100
        val cv = sqrt(diabetesM2 / (diabetesCount - 1)) / diabetesMean * 100
        return when {
            below > 5 || cv > 36 -> 6
            above > 60 -> 5
            above > 30 -> 4
            above >= 15 || cv > 30 -> 3
            above < 5 && cv <= 20 -> 1
            else -> 2
        }
    }

    fun pushGap23(seq: Int) {
        if (gap23Count == 4) System.arraycopy(gap23, 1, gap23, 0, 3) else gap23Count++
        gap23[gap23Count - 1] = seq
    }

    fun pushGap60(seq: Int) {
        if (gap60Count == 2) gap60[0] = gap60[1] else gap60Count++
        gap60[gap60Count - 1] = seq
    }

    fun toBytes(): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { o ->
            o.writeInt(VERSION)
            o.writeInt(count); o.writeInt(previousSequence); o.writeLong(previousTime); o.writeInt(contactCheckStart); o.writeBoolean(rejectedSinceAccepted)
            rawTail.forEach(o::writeDouble); cleanTail.forEach(o::writeDouble); flags.forEach(o::writeInt)
            history60.forEach(o::writeDouble); history60Time.forEach(o::writeLong); previousMedians.forEach(o::writeDouble)
            o.writeDouble(initialSum); o.writeInt(initialCount); o.writeBoolean(highPassStarted)
            o.writeDouble(highPassInput); o.writeDouble(highPass); o.writeDouble(referenceSum); o.writeInt(referenceCount)
            o.writeInt(diabetesCount); o.writeDouble(diabetesMean); o.writeDouble(diabetesM2)
            o.writeInt(diabetesHigh); o.writeInt(diabetesLow); o.writeInt(diabetesLevel); o.writeBoolean(diabetesLevelDone)
            o.writeDouble(lastGlucose); o.writeBoolean(hasLastGlucose)
            temperatures.forEach(o::writeDouble)
            kalman.forEach(o::writeDouble); o.writeInt(warmup); rates.forEach(o::writeDouble)
            o.writeBoolean(fluctuating); o.writeInt(fluctuationCount); o.writeDouble(previousRescaled)
            history.forEach(o::writeDouble); historyTime.forEach(o::writeLong); historySequence.forEach(o::writeInt); historyRaw.forEach(o::writeDouble)
            o.writeBoolean(timingStarted); o.writeInt(timingSequence); o.writeLong(timingTime)
            gap23.forEach(o::writeInt); o.writeInt(gap23Count); gap60.forEach(o::writeInt); o.writeInt(gap60Count)
            o.writeBoolean(timingError)
            lowRawMax.forEach(o::writeDouble); o.writeInt(lowRawCount); o.writeBoolean(lowError)
            historyMaybeFlagged.forEach(o::writeBoolean); o.writeBoolean(referenceMaybeFlagged)
        }
        return bytes.toByteArray()
    }

    companion object {
        const val VERSION = 2

        /** Restores [toBytes]; null for another format version or a damaged copy. */
        fun fromBytes(data: ByteArray): CareSensAirCalibrationState? = runCatching {
            val s = CareSensAirCalibrationState()
            DataInputStream(ByteArrayInputStream(data)).use { i ->
                if (i.readInt() != VERSION) return null
                s.count = i.readInt(); s.previousSequence = i.readInt(); s.previousTime = i.readLong(); s.contactCheckStart = i.readInt(); s.rejectedSinceAccepted = i.readBoolean()
                fill(s.rawTail, i); fill(s.cleanTail, i); for (k in s.flags.indices) s.flags[k] = i.readInt()
                fill(s.history60, i); for (k in s.history60Time.indices) s.history60Time[k] = i.readLong(); fill(s.previousMedians, i)
                s.initialSum = i.readDouble(); s.initialCount = i.readInt(); s.highPassStarted = i.readBoolean()
                s.highPassInput = i.readDouble(); s.highPass = i.readDouble(); s.referenceSum = i.readDouble(); s.referenceCount = i.readInt()
                s.diabetesCount = i.readInt(); s.diabetesMean = i.readDouble(); s.diabetesM2 = i.readDouble()
                s.diabetesHigh = i.readInt(); s.diabetesLow = i.readInt(); s.diabetesLevel = i.readInt(); s.diabetesLevelDone = i.readBoolean()
                s.lastGlucose = i.readDouble(); s.hasLastGlucose = i.readBoolean()
                fill(s.temperatures, i)
                fill(s.kalman, i); s.warmup = i.readInt(); fill(s.rates, i)
                s.fluctuating = i.readBoolean(); s.fluctuationCount = i.readInt(); s.previousRescaled = i.readDouble()
                fill(s.history, i); for (k in s.historyTime.indices) s.historyTime[k] = i.readLong()
                for (k in s.historySequence.indices) s.historySequence[k] = i.readInt()
                fill(s.historyRaw, i)
                s.timingStarted = i.readBoolean(); s.timingSequence = i.readInt(); s.timingTime = i.readLong()
                for (k in s.gap23.indices) s.gap23[k] = i.readInt(); s.gap23Count = i.readInt()
                for (k in s.gap60.indices) s.gap60[k] = i.readInt(); s.gap60Count = i.readInt()
                s.timingError = i.readBoolean()
                fill(s.lowRawMax, i); s.lowRawCount = i.readInt(); s.lowError = i.readBoolean()
                for (k in 0 until 10) s.historyMaybeFlagged[k] = i.readBoolean()
                s.referenceMaybeFlagged = i.readBoolean()
                if (i.read() != -1) return null
            }
            s
        }.getOrNull()

        private fun fill(target: DoubleArray, i: DataInputStream) {
            for (k in target.indices) target[k] = i.readDouble()
        }
    }
}

/**
 * Reads the state block the vendor library keeps per sensor
 * (`air1_opcal4_arguments_t`, 117 312 bytes, natural alignment, little
 * endian; offsets from Common/src/main/cpp/air/air.hpp), so a sensor started
 * on the vendor library can continue on the open engine.
 *
 * The engine's sample currents can differ from the reference's in the last
 * bits. The previous record's samples are therefore recomputed from its ADC
 * values, which the block keeps: the spike limiter compares them for
 * equality with the next record's.
 */
object CareSensAirVendorState {
    const val SIZE = 117312

    fun read(block: ByteArray, parameters: CareSensAirFactoryParameters): CareSensAirCalibrationState {
        require(block.size >= SIZE) { "vendor state is $SIZE bytes" }
        val b = java.nio.ByteBuffer.wrap(block).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        fun u8(o: Int) = b.get(o).toInt() and 0xff
        fun i8(o: Int) = b.get(o).toInt()
        fun u16(o: Int) = b.getShort(o).toInt() and 0xffff
        fun u32(o: Int) = b.getInt(o).toLong() and 0xffffffffL
        fun f64(o: Int) = b.getDouble(o)
        val s = CareSensAirCalibrationState()
        s.count = u16(1608)
        s.previousSequence = u16(16)
        s.previousTime = u32(12)
        s.contactCheckStart = u16(452)
        s.rejectedSinceAccepted = u16(454) > s.previousSequence
        for (k in 0 until 5) { s.rawTail[k] = f64(3344 + 8 * k); s.cleanTail[k] = f64(3384 + 8 * k) }
        for (k in 0 until 6) s.flags[k] = i8(3424 + k)
        for (k in 0 until 60) { s.history60[k] = f64(3432 + 8 * k); s.history60Time[k] = u32(92 + 4 * (30 + k)) }
        for (k in 0 until 3) s.previousMedians[k] = f64(3912 + 8 * k)
        s.initialSum = f64(10960); s.initialCount = u16(10968)
        s.highPassStarted = u8(11016) != 0; s.highPassInput = f64(10992 + 8); s.highPass = f64(11008)
        s.referenceSum = f64(10976); s.referenceCount = u16(10984)
        s.diabetesHigh = u16(10856); s.diabetesLow = u16(10858); s.diabetesCount = u16(10860)
        s.diabetesLevel = u8(10862); s.diabetesMean = f64(10888); s.diabetesM2 = f64(10896)
        s.diabetesLevelDone = s.previousSequence >= 2017
        s.lastGlucose = f64(10904); s.hasLastGlucose = s.count > 0
        for (k in 0 until 4) s.temperatures[k] = f64(11032 + 8 * k)
        for (k in 0 until 3) s.kalman[k] = f64(11128 + 8 * k)
        s.warmup = u16(11122)
        for (k in 0 until 4) s.rates[k] = f64(11152 + 8 * k)
        s.fluctuating = u8(11120) == 3
        s.fluctuationCount = f64(11184).toInt()
        s.previousRescaled = f64(11192)
        for (k in 0 until 10) {
            s.history[k] = f64(11200 + 8 * k)
            s.historyTime[k] = u32(11280 + 4 * k)
            s.historySequence[k] = u16(1610 + 2 * (855 + k))
            s.historyRaw[k] = s.history[k]
        }
        for (j in 0 until CareSensAirCalibration.SAMPLES) {
            val own = CareSensAirCalibration.current(u16(18 + 2 * j), parameters)
            fun same(v: Double) = abs(v - own) <= 16 * Math.ulp(own)
            if (same(s.history60[30 + j])) s.history60[30 + j] = own
            val k = j - 25
            if (k >= 0) {
                if (same(s.rawTail[k])) s.rawTail[k] = own
                if (same(s.cleanTail[k])) s.cleanTail[k] = own
            }
        }
        // Error 4: the block keeps the raw currents of the last 13 accepted
        // records (newest last), their sequence numbers in accu_seq.
        for (k in 0 until 4) {
            var m = Double.NEGATIVE_INFINITY
            for (j in 0 until CareSensAirCalibration.SAMPLES) m = maxOf(m, f64(40096 + 8 * (30 * (9 + k) + j)))
            s.lowRawMax[k] = m
        }
        var run = if (s.count > 0) 1 else 0
        while (run < minOf(4, s.count) && u16(1610 + 2 * (864 - run)) == u16(1610 + 2 * (864 - run + 1)) - 1) run++
        s.lowRawCount = run
        s.lowError = u8(48824) != 0
        // The block records the low-signal decisions of the last 576 records.
        for (k in 0 until 10) s.historyMaybeFlagged[k] = u8(48248 + 566 + k) != 0
        // The reference sums taken over are the vendor's own, flagged records already left out.
        s.timingTime = u32(117284); s.timingSequence = u16(117288); s.timingStarted = s.timingTime != 0L
        for (k in 0 until 4) u16(117290 + 2 * k).takeIf { it != 0 }?.let { s.pushGap23(it) }
        for (k in 0 until 2) u16(117298 + 2 * k).takeIf { it != 0 }?.let { s.pushGap60(it) }
        s.timingError = u8(117307) != 0
        return s
    }
}
