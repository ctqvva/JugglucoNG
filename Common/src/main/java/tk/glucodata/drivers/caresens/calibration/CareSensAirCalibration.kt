/*
 * This file is part of JugglucoNG.
 *
 * JugglucoNG is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option)
 * any later version.
 */

package tk.glucodata.drivers.caresens.calibration

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign
import kotlin.math.sqrt

/**
 * Open factory-calibration engine for CareSens Air records.
 *
 * It reproduces the observable behaviour of i-SENS' `air1_opcal4_algorithm`
 * for the inputs JugglucoNG feeds it: factory calibration only, no
 * fingerstick list. The behaviour was established by differential testing
 * against the vendor library through its public interface; see
 * `docs/caresens-air-open-calibration.md` for provenance, the verified range
 * and what is not modelled yet ([Result.exact] is false there).
 *
 * The engine is a pure function of [CareSensAirCalibrationState] and the
 * record, so it can be persisted and restored between any two records.
 */
class CareSensAirCalibration(
    val parameters: CareSensAirFactoryParameters,
    val state: CareSensAirCalibrationState = CareSensAirCalibrationState(),
) {

    data class Record(
        val sequence: Int,
        val timeSeconds: Long,
        val adc: IntArray,
        val temperatureC: Double,
    ) {
        init {
            require(adc.size == SAMPLES) { "a record holds $SAMPLES samples" }
        }
    }

    data class Result(
        val sequenceOriginal: Int,
        val sequenceFinal: Int,
        val timeSeconds: Long,
        /** mg/dL; meaningful only when [errorCode] is 0 and [dataType] is 0. */
        val glucose: Double,
        /** mg/dL per minute, or [NO_TREND]. */
        val trend: Double,
        val errorCode: Int,
        /** 0 normal, 2 rejected record (sensor contact). The low-signal type 4 is not reproduced. */
        val dataType: Int,
        /** Sequence numbers and smoothed values of the six newest history entries. */
        val historySequence: IntArray,
        val history: DoubleArray,
        /**
         * False when the record reached behaviour that is not reproduced yet:
         * filling missed records after a clock jump, a trend while the
         * reference's low-signal flag may mark a record in its window, or the
         * sensitivity compensation after day 7 when that flag may have marked a
         * record of its reference period. Errors 1, 2, 8, 16 and 128, and the
         * noise part of the low-signal flag, are not reproduced; see the
         * documentation.
         */
        val exact: Boolean,
        val intermediates: Intermediates,
    )

    /** Values between the stages, for differential tests. */
    data class Intermediates(
        val minuteCurrents: DoubleArray,
        val correctedCurrent: Double,
        val driftCurrent: Double,
        val factoryGlucose: Double,
        val rescaled: Double,
        val diabetesLevel: Int,
    )

    fun process(record: Record): Result {
        val s = state
        if (!parameters.valid) {
            return Result(
                record.sequence, record.sequence, record.timeSeconds, 0.0, 0.0, ERROR_PARAMETERS, 0,
                IntArray(6), DoubleArray(6), true, EMPTY_INTERMEDIATES,
            )
        }
        val currents = DoubleArray(SAMPLES) { current(record.adc[it], parameters) }
        val first = s.count == 0
        val consecutive = !first && record.sequence == s.previousSequence + 1
        val start = record.timeSeconds - 290
        val sampleTime = LongArray(SAMPLES) { start + 10L * it }

        // The contact check starts once as many records were accepted as the
        // first sequence number of at least 6.
        if (s.contactCheckStart == 0 && record.sequence >= CONTACT_CHECK_AFTER) s.contactCheckStart = record.sequence
        if (s.contactCheckStart != 0 && s.count >= s.contactCheckStart && contactLost(record.adc)) {
            timing(record, s)
            s.rejectedSinceAccepted = true
            // Rejection hook for detectors with per-accepted-record state.
            CareSensAirError4.reject(s)
            return Result(
                record.sequence, record.sequence, record.timeSeconds, 0.0, 0.0, detectorErrors(s, accepted = false), 2,
                IntArray(6), DoubleArray(6), true, EMPTY_INTERMEDIATES,
            )
        }
        var exact = true
        if (consecutive) {
            val dt = record.timeSeconds - s.previousTime
            if (dt in 480 until 1380) exact = false
        }

        // Spike limiting per 10 s sample against the previous five.
        val cleaned = if (consecutive) {
            limitSpikes(currents, s, sampleTime[0] - s.history60Time[55] < 58)
        } else {
            for (j in 0 until 5) {
                s.rawTail[j] = currents[25 + j]
                s.cleanTail[j] = currents[25 + j]
            }
            s.flags.fill(0)
            currents.copyOf()
        }

        val smoothed: DoubleArray
        val blend: Boolean
        if (first) {
            smoothed = cleaned
            blend = false
        } else {
            val y = DoubleArray(90)
            val t = LongArray(90)
            System.arraycopy(s.history60, 0, y, 0, 60)
            System.arraycopy(cleaned, 0, y, 60, 30)
            System.arraycopy(s.history60Time, 0, t, 0, 60)
            System.arraycopy(sampleTime, 0, t, 60, 30)
            smoothed = if (s.count >= 2 && t[89] - t[0] < 898) robustLoess(y).copyOfRange(60, 90) else cleaned
            blend = sampleTime[0] - s.history60Time[59] < 18
        }
        val medians = groupMedians(smoothed)
        val minute = minuteMeans(if (blend) blendWithPrevious(medians, s.previousMedians) else medians)
        for (j in 0 until 3) s.previousMedians[j] = medians[27 + j]
        if (first) {
            s.history60Time.fill(-1_000_000_000L)
            s.history60.fill(0.0)
        }
        System.arraycopy(s.history60, 30, s.history60, 0, 30)
        System.arraycopy(cleaned, 0, s.history60, 30, 30)
        System.arraycopy(s.history60Time, 30, s.history60Time, 0, 30)
        System.arraycopy(sampleTime, 0, s.history60Time, 30, 30)

        val seq = record.sequence
        var corrected = trimmedMean(minute) - if (parameters.lotType == 1) 0.7 else 0.243
        corrected = sensitivityCompensated(corrected, seq, s)
        val drift = if (seq < 1829) {
            min(1.0, 0.92319967 + seq * (4.76441685e-05 + seq * (5.39473347e-09 - seq * 4.63640418e-12)))
        } else 1.0
        val driftCurrent = corrected / drift

        // Four slots by sequence number: a missed record leaves its slot empty,
        // a repeated or older one starts over.
        val step = seq - s.previousSequence
        if (first || step <= 0 || step >= 4) {
            s.temperatures.fill(Double.NaN)
        } else {
            System.arraycopy(s.temperatures, 0, s.temperatures, step, 4 - step)
            for (j in 1 until step) s.temperatures[j] = Double.NaN
        }
        s.temperatures[0] = record.temperatureC
        var temperatureSum = 0.0
        var temperatureCount = 0
        for (v in s.temperatures) if (!v.isNaN()) { temperatureSum += v; temperatureCount++ }
        val temperatureRatio = (0.0328 * (temperatureSum / temperatureCount) - 0.118).coerceIn(0.882, 1.134)
        val factory = driftCurrent * 100.0 / parameters.slope100.toDouble() / temperatureRatio

        var rescaled = factory
        if (factory < 180.0) {
            val fade = if (seq <= 288) 1.0 else max(0.0, (432 - seq) / 144.0)
            rescaled += 30.0 * (1 - factory / 180.0) * (1 - factory / 385.0) * fade
        }

        val glucose = kalman(rescaled, record, first, s)
        s.lastGlucose = glucose
        s.hasLastGlucose = true

        // Accepted-record detector hook, after Kalman and before rotating history.
        var rawMax = currents[0]
        for (v in currents) if (v > rawMax) rawMax = v
        CareSensAirError4.update(s, seq, consecutive, rawMax, parameters.slope100)

        // The reference's low-signal flag (data type 4) is only partly known.
        // Every decoded part of it needs one of these, so without them the
        // flag is surely off. It suppresses the trend, and from day 3 to 7 a
        // flagged record can be left out of the sensitivity reference.
        val maybeLowFlag = rawMax < 1.1 || glucose < 40.5 || factory < 20.5 || (factory < 40.5 && rawMax < 1.5)
        // The reference leaves a record out when it and the one before are both flagged.
        if (maybeLowFlag && s.historyMaybeFlagged[9] && seq in 865..2016) s.referenceMaybeFlagged = true
        if (seq > 2016 && s.referenceMaybeFlagged) exact = false

        // History: ten newest values, the six behind the newest re-smoothed.
        System.arraycopy(s.history, 1, s.history, 0, 9)
        System.arraycopy(s.historyTime, 1, s.historyTime, 0, 9)
        System.arraycopy(s.historySequence, 1, s.historySequence, 0, 9)
        s.history[9] = glucose
        s.historyTime[9] = record.timeSeconds
        s.historySequence[9] = seq
        System.arraycopy(s.historyRaw, 1, s.historyRaw, 0, 9)
        s.historyRaw[9] = glucose
        System.arraycopy(s.historyMaybeFlagged, 1, s.historyMaybeFlagged, 0, 9)
        s.historyMaybeFlagged[9] = maybeLowFlag

        if (s.count >= 9 && historyContinuous(s)) smoothHistory(s.history)
        val trend = trend(s)
        // The reference gives no trend while its low-signal flag marks a record
        // in the window.
        if (s.count >= 11) for (j in 3 until 10) if (s.historyMaybeFlagged[j]) exact = false

        timing(record, s)
        s.rejectedSinceAccepted = false
        s.previousSequence = seq
        s.previousTime = record.timeSeconds
        s.count++
        return Result(
            seq, seq, record.timeSeconds, glucose, trend, detectorErrors(s, accepted = true), 0,
            s.historySequence.copyOfRange(3, 9), s.history.copyOfRange(3, 9), exact,
            Intermediates(minute, corrected, driftCurrent, factory, rescaled, s.diabetesLevel),
        )
    }

    private fun detectorErrors(s: CareSensAirCalibrationState, accepted: Boolean): Int =
        (if (s.timingError) ERROR_TIMING else 0) or (if (accepted && s.lowError) ERROR_LOW else 0)

    private fun sensitivityCompensated(corrected: Double, seq: Int, s: CareSensAirCalibrationState): Double {
        if (seq in 5..24) {
            s.initialSum += corrected
            s.initialCount++
        }
        if (seq >= 24) {
            if (!s.highPassStarted) {
                // Without records from 5..24 the filter starts from the current value.
                s.highPassInput = if (s.initialCount > 0) s.initialSum / s.initialCount else corrected
                s.highPassStarted = true
            }
            s.highPass = 0.99891 * (corrected - s.highPassInput) + 0.99782 * s.highPass
            s.highPassInput = corrected
        }
        if (seq in 865..2016) {
            s.referenceSum += corrected
            s.referenceCount++
            if (s.hasLastGlucose && s.lastGlucose >= 40.0 && s.lastGlucose <= 600.0) s.addDiabetesSample(s.lastGlucose)
        }
        if (seq >= 2017 && !s.diabetesLevelDone) {
            s.diabetesLevelDone = true
            s.diabetesLevel = s.diabetesLevel()
        }
        val factor = LEVEL_FACTOR[s.diabetesLevel]
        if (seq > 2016 && s.referenceCount > 0 && factor > 0.0) {
            val reference = factor * s.referenceSum / s.referenceCount
            val lowPass = corrected - s.highPass
            if (lowPass < reference) return corrected + min(1.0, (seq - 2016) / 288.0) * (reference - lowPass)
        }
        return corrected
    }

    private fun kalman(z: Double, record: Record, first: Boolean, s: CareSensAirCalibrationState): Double {
        val dt = record.timeSeconds - s.previousTime
        var init: Boolean
        // A rejected record in between shortens the gap that restarts the filter.
        if (first || record.sequence < 10 || dt > if (s.rejectedSinceAccepted) 1080 else 1500) {
            System.arraycopy(s.rates, 0, s.rates, 1, 3)
            s.rates[0] = Double.NaN
            s.fluctuating = false
            s.fluctuationCount = 0
            init = true
        } else {
            val dz = z - s.previousRescaled
            val rate = if (dt != 0L) dz / (dt / 60.0) else if (dz == 0.0) Double.NaN else Double.POSITIVE_INFINITY * sign(dz)
            val mean = (s.rates[0] + s.rates[1] + s.rates[2]) / 3
            System.arraycopy(s.rates, 0, s.rates, 1, 3)
            s.rates[0] = rate
            if (s.fluctuating) {
                s.fluctuationCount = if (abs(rate) > 3) 1 else s.fluctuationCount + 1
                if (s.fluctuationCount >= 6) {
                    s.fluctuating = false
                    s.fluctuationCount = 0
                }
                init = true
            } else {
                // Needs three earlier rates: a NaN mean never restarts the filter.
                init = abs(rate) > 5 && mean * sign(rate) <= 1
                if (init) {
                    s.fluctuating = true
                    s.fluctuationCount = 1
                }
            }
        }
        s.previousRescaled = z
        if (init) {
            s.kalman[0] = z
            s.kalman[1] = z
            s.kalman[2] = 0.0
            s.warmup = 1
            return z
        }
        val p0 = E_HALF * s.kalman[0] + (1 - E_HALF) * s.kalman[1]
        val p1 = s.kalman[1] + s.kalman[2]
        val p2 = s.kalman[2]
        val innovation = z - p0
        s.kalman[0] = p0 + 0.6729 * innovation
        s.kalman[1] = p1 + 1.761 * innovation
        s.kalman[2] = p2 + 0.1279 * innovation
        s.warmup++
        val weight = min(1.0, (s.warmup - 1) / 24.0)
        return weight * s.kalman[1] + (1 - weight) * z
    }

    private fun trend(s: CareSensAirCalibrationState): Double {
        val h = s.history
        val t = s.historyTime
        if (s.count < 11 || t[3] <= 0) return NO_TREND
        val minutes = (t[9] - t[3]) / 60.0
        if (minutes > 35) return NO_TREND
        for (j in 3 until 10) if (h[j] < 40 || h[j] > 500) return NO_TREND
        var rate = (h[9] - h[3]) / minutes
        val last = h[9] - h[8]
        if (abs(rate) > 1 && sign(last) != sign(rate)) {
            rate = if (sign(h[8] - h[7]) != sign(rate)) last / ((t[9] - t[8]) / 60.0) else 0.0
        }
        return rate
    }

    /** Interruption check: the transmitter's time ran ahead of its sequence. */
    private fun timing(record: Record, s: CareSensAirCalibrationState) {
        var triggered = false
        if (s.timingStarted && record.sequence == s.timingSequence + 1) {
            val dt = record.timeSeconds - s.timingTime
            // The window counts accepted records, not sequence numbers: the
            // reference stores its record index + 1, also for a rejected record.
            val index = s.count + 1
            if (dt >= 23 * 60) {
                s.pushGap23(index)
                triggered = s.gap23Count >= 4 && index - s.gap23[0] <= 864
            }
            if (dt >= 60 * 60) {
                s.pushGap60(index)
                triggered = triggered || (s.gap60Count >= 2 && index - s.gap60[0] <= 864)
            }
            triggered = triggered || dt >= 600 * 60
        }
        s.timingError = CareSensAirDetectorLatch.permanent(s.timingError, triggered)
        s.timingStarted = true
        s.timingSequence = record.sequence
        s.timingTime = record.timeSeconds
    }

    private fun limitSpikes(x: DoubleArray, s: CareSensAirCalibrationState, continuous: Boolean): DoubleArray {
        val raw = DoubleArray(5 + SAMPLES)
        val clean = DoubleArray(5 + SAMPLES)
        val flags = IntArray(6 + SAMPLES)
        System.arraycopy(s.rawTail, 0, raw, 0, 5)
        System.arraycopy(s.cleanTail, 0, clean, 0, 5)
        System.arraycopy(s.flags, 0, flags, 0, 6)
        val window = DoubleArray(6)
        for (j in 0 until SAMPLES) {
            val v = x[j]
            val r = 5 + j
            val f = 6 + j
            if (j < 5 && !continuous) {
                raw[r] = v
                clean[r] = v
                flags[f] = 0
                continue
            }
            var outliers = 0
            for (k in f - 6 until f) if (flags[k] != 0) outliers++
            val source = if (outliers >= 4) clean else raw
            for (k in 0 until 5) window[k] = source[r - 5 + k]
            window[5] = v
            val median = median(window)
            var scale = 1.486 * medianAbsoluteDeviation(window, median)
            var floor = 0.0
            if (scale == 0.0) {
                // Zero MAD: mean absolute deviation; after five identical samples
                // a step must also exceed 0.0036 nA (about one ADC count).
                if (window[0] == window[1] && window[1] == window[2] && window[2] == window[3] && window[3] == window[4]) {
                    floor = SPIKE_FLOOR
                }
                val mean = window.average()
                var sum = 0.0
                for (w in window) sum += abs(w - mean)
                scale = 1.253314 * sum / window.size
            }
            raw[r] = v
            if (abs(v - median) > 1.5 * scale && abs(v - median) > floor) {
                clean[r] = clean[r - 1] + if (v - median >= 0) scale else -scale
                flags[f] = if (v > median) 1 else -1
            } else {
                clean[r] = v
                flags[f] = 0
            }
        }
        System.arraycopy(raw, SAMPLES, s.rawTail, 0, 5)
        System.arraycopy(clean, SAMPLES, s.cleanTail, 0, 5)
        System.arraycopy(flags, SAMPLES, s.flags, 0, 6)
        return clean.copyOfRange(5, 5 + SAMPLES)
    }

    companion object {
        /**
         * Sample current in nA. Equal to the reference to a few units in the
         * last place, not bit for bit (its operation order is not known).
         */
        fun current(adc: Int, parameters: CareSensAirFactoryParameters): Double =
            (adc * parameters.vref.toDouble() / 40950.0 - parameters.eapp.toDouble()) * 100.0

        const val SAMPLES = 30
        private const val SPIKE_FLOOR = 0.0036
        const val NO_TREND = 100.0
        const val ERROR_LOW = 4
        const val ERROR_TIMING = 32
        const val ERROR_PARAMETERS = 64
        private const val CONTACT_CHECK_AFTER = 6
        private val E_HALF = exp(-0.5)
        private val LEVEL_FACTOR = doubleArrayOf(0.0, 1.0, 0.95, 0.85, 0.80, 0.75, 0.0)
        private val HISTORY_WEIGHTS = doubleArrayOf(-1.6, 2.7, 6.6, 5.6, 5.4, 3.9, -1.6)
        private val BLEND_WEIGHTS = doubleArrayOf(-1.0, 4.0, 7.0, 8.0, 7.0, 4.0, -1.0)
        private val EMPTY_INTERMEDIATES = Intermediates(DoubleArray(5), 0.0, 0.0, 0.0, 0.0, 6)
        private val LOESS_KERNEL: Array<DoubleArray> = Array(90) { i ->
            val d = DoubleArray(90) { abs(it - i).toDouble() }
            val h = d.sortedArray()[86]
            DoubleArray(90) { val u = d[it] / h; if (u < 1) (1 - u * u * u).let { c -> c * c * c } else 0.0 }
        }

        /** Sample with the first maximum in the first 20 s that stands out from the rest. */
        internal fun contactLost(adc: IntArray): Boolean {
            val w = DoubleArray(SAMPLES) { adc[it].toDouble() }
            var top = 0
            for (j in 1 until SAMPLES) if (w[j] > w[top]) top = j
            if (top > 1) return false
            var low = 0
            for (j in 1 until SAMPLES) if (w[j] < w[low]) low = j
            val neighbour = if (top == 0) 1 else if (w[0] >= w[2]) 0 else 2
            var restSum = 0.0
            for (j in 0 until SAMPLES) if (j != top) restSum += w[j]
            if (!(w[top] > 1.2 * restSum / (SAMPLES - 1))) return false
            val all = standardDeviation(w, -1, -1)
            return all > 1.5 * standardDeviation(w, top, neighbour) && all < 1.1 * standardDeviation(w, low, -1)
        }

        private fun standardDeviation(w: DoubleArray, skipA: Int, skipB: Int): Double {
            var n = 0
            var sum = 0.0
            for (j in w.indices) if (j != skipA && j != skipB) { sum += w[j]; n++ }
            val mean = sum / n
            var ss = 0.0
            for (j in w.indices) if (j != skipA && j != skipB) ss += (w[j] - mean) * (w[j] - mean)
            return sqrt(ss / (n - 1))
        }

        internal fun median(values: DoubleArray, from: Int = 0, to: Int = values.size): Double {
            val sorted = values.copyOfRange(from, to).also { it.sort() }
            val n = sorted.size
            return if (n % 2 == 1) sorted[n / 2] else (sorted[n / 2 - 1] + sorted[n / 2]) / 2
        }

        private fun medianAbsoluteDeviation(values: DoubleArray, center: Double): Double =
            median(DoubleArray(values.size) { abs(values[it] - center) })

        /** Linear loess over 90 samples (87 nearest), two bisquare robustness passes. */
        internal fun robustLoess(y: DoubleArray): DoubleArray {
            val n = y.size
            val weight = DoubleArray(n) { 1.0 }
            val fit = DoubleArray(n)
            val residual = DoubleArray(n)
            for (pass in 0 until 3) {
                for (i in 0 until n) {
                    val kernel = LOESS_KERNEL[i]
                    var s0 = 0.0; var s1 = 0.0; var s2 = 0.0; var t0 = 0.0; var t1 = 0.0
                    for (j in 0 until n) {
                        val w = kernel[j] * weight[j]
                        val u = (j - i).toDouble()
                        s0 += w; s1 += w * u; s2 += w * u * u; t0 += w * y[j]; t1 += w * y[j] * u
                    }
                    fit[i] = (s2 * t0 - s1 * t1) / (s0 * s2 - s1 * s1)
                }
                if (pass == 2) break
                for (i in 0 until n) residual[i] = abs(y[i] - fit[i])
                val m = median(residual)
                if (m > 0) for (i in 0 until n) {
                    val u = (y[i] - fit[i]) / (6 * m)
                    weight[i] = if (abs(u) < 1) (1 - u * u).let { it * it } else 0.0
                }
            }
            return fit
        }

        /** Moving median over [i-3, i+2] inside each one-minute group of six samples. */
        internal fun groupMedians(x: DoubleArray): DoubleArray {
            val out = DoubleArray(x.size)
            for (g in 0 until x.size / 6) {
                val base = g * 6
                for (i in 0 until 6) out[base + i] = median(x, base + max(0, i - 3), base + min(6, i + 3))
            }
            return out
        }

        internal fun blendWithPrevious(medians: DoubleArray, previous: DoubleArray): DoubleArray {
            val x = DoubleArray(3 + medians.size)
            System.arraycopy(previous, 0, x, 0, 3)
            System.arraycopy(medians, 0, x, 3, medians.size)
            val n = x.size
            return DoubleArray(medians.size) { k ->
                val i = k + 3
                var sum = 0.0
                var norm = 0.0
                for (o in -3..3) {
                    val j = i + o
                    if (j in 0 until n) { sum += BLEND_WEIGHTS[o + 3] * x[j]; norm += BLEND_WEIGHTS[o + 3] }
                }
                sum / norm
            }
        }

        internal fun minuteMeans(x: DoubleArray): DoubleArray =
            DoubleArray(x.size / 6) { g -> trimmedMean(x.copyOfRange(g * 6, g * 6 + 6)) }

        internal fun trimmedMean(x: DoubleArray): Double {
            var sum = 0.0
            var lo = x[0]
            var hi = x[0]
            for (v in x) { sum += v; lo = min(lo, v); hi = max(hi, v) }
            return (sum - lo - hi) / (x.size - 2)
        }

        private fun historyContinuous(s: CareSensAirCalibrationState): Boolean {
            for (j in 1 until 10) if (s.historyTime[j] - s.historyTime[j - 1] >= 600) return false
            return true
        }

        internal fun smoothHistory(h: DoubleArray) {
            val padded = DoubleArray(13) { if (it < 10) h[it] else h[9] }
            val out = h.copyOf()
            for (i in 3 until 9) {
                var sum = 0.0
                for (o in -3..3) sum += HISTORY_WEIGHTS[o + 3] * padded[i + o]
                out[i] = sum / 21.0
            }
            System.arraycopy(out, 0, h, 0, 10)
        }
    }
}
