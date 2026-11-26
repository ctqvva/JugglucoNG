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
        if (diabetesCount <= 1) return 6
        val above = 100.0 * diabetesHigh / diabetesCount
        val below = 100.0 * diabetesLow / diabetesCount
        val cv = 100.0 * sqrt(diabetesM2 / (diabetesCount - 1)) / diabetesMean
        return when {
            below >= 5 || cv >= 36 -> 6
            above >= 60 -> 5
            above >= 30 -> 4
            above >= 15 -> 3
            above < 5 && cv < 20 -> 1
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
        }
        return bytes.toByteArray()
    }

    companion object {
        const val VERSION = 1

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
                if (i.read() != -1) return null
            }
            s
        }.getOrNull()

        private fun fill(target: DoubleArray, i: DataInputStream) {
            for (k in target.indices) target[k] = i.readDouble()
        }
    }
}
