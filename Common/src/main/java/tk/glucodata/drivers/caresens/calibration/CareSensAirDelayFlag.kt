/*
 * This file is part of JugglucoNG.
 *
 * JugglucoNG is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option)
 * any later version.
 */

package tk.glucodata.drivers.caresens.calibration

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

/**
 * Low-signal delay tests (`err4_delay_condi` / `err4_delay_flag`) and
 * trend-withholding window (`err_delay_arr`).
 *
 * Evaluates the five delay tests across the three wear phases controlled by
 * sequence number and the diabetes profile (`diabetes_before_7days_flag`),
 * applies the day-3+ rapid-drop gate, and tracks the seven-record window that
 * withholds [CareSensAirCalibration.Result.trend].
 */
internal object CareSensAirDelayFlag {
    private val FIR_WEIGHTS = doubleArrayOf(
        0.19203786935092992, 0.16522460111444, 0.11844988518211, 0.0716100935714,
        0.03473077453465, 0.01186996852659, 0.00419148479069, 0.01186996852659,
        0.03473077453465, 0.0716100935714, 0.11844988518211, 0.16522460111444,
    )

    fun roundAwayFromZero(value: Double): Double =
        if (value < 0.0) ceil(value - 0.5) else floor(value + 0.5)

    /**
     * Updates pre-evaluation profile state, computes `err4_delay_flag`, and
     * advances the delay-flag and trend-withholding histories for one accepted
     * record.
     */
    fun update(
        state: CareSensAirCalibrationState,
        sequence: Int,
        rawMax: Double,
        current5Min: Double,
        glucose: Double,
        slope100: Float,
        noiseBreak: Boolean,
    ): Boolean {
        val roundedGlucose = roundAwayFromZero(glucose)
        val priorSeq = state.delaySequence
        val priorGlu = state.delayGlucose
        val consecutive = state.count > 0 && priorSeq[23] == sequence - 1

        // 12-tap FIR smoothed glucose (plasma) uses the 11 prior rounded
        // glucoses plus the current one when their sequence numbers are continuous.
        val continuous12 = priorSeq[13] != 0 && sequence - priorSeq[13] == 11
        val smoothedGlucose = if (continuous12) {
            var sum = FIR_WEIGHTS[0] * roundedGlucose
            for (k in 1 until 12) sum += FIR_WEIGHTS[k] * priorGlu[24 - k]
            roundAwayFromZero(sum)
        } else {
            Double.NaN
        }
        System.arraycopy(state.delayPlasma, 1, state.delayPlasma, 0, 11)
        state.delayPlasma[11] = smoothedGlucose

        // Update the diabetes profile (diabetes_before_7days_flag) before D0-D4.
        if (sequence >= 2017 && state.diabetesCount > 288) {
            state.delayProfile = state.diabetesLevel
        } else if (sequence >= 864) {
            if (state.delayProfile == 100) state.delayProfile = 0
            if (sequence > 864 && state.delayProfile == 0) {
                state.delayProfileCount++
                state.delayProfileSum += smoothedGlucose
                if (smoothedGlucose >= 180.0) state.delayProfileAbove++
                if (smoothedGlucose in 70.0..180.0) state.delayProfileInRange++
                var allHigh = true
                for (p in state.delayPlasma) {
                    if (!(p >= 300.0)) {
                        allHigh = false
                        break
                    }
                }
                if (allHigh || (state.delayProfileCount >= 288 && (
                        state.delayProfileSum > 140.0 * state.delayProfileCount ||
                            state.delayProfileAbove > 0.1 * state.delayProfileCount ||
                            state.delayProfileInRange < 0.8 * state.delayProfileCount))) {
                    state.delayProfile = 6
                }
            }
        }

        val phase1 = sequence < 865
        val phase3 = sequence >= 2017 && state.delayProfile >= 4
        val phase2 = !phase1 && !phase3
        val slope = slope100.toDouble()

        val d0 = !phase3 && consecutive && max(rawMax, state.delayPreviousRawMax) < 1.1
        val d1 = phase2 && roundedGlucose < 40.0 && rawMax < 1.5
        val d2 = phase2 && roundedGlucose < 40.0 && rawMax < 3.0 && rawMax * 100.0 / slope < 60.0
        val d3 = roundedGlucose < 20.0 || current5Min < 0.4
        val d4 = phase3 && (roundedGlucose < 30.0 || (rawMax < 0.8 && rawMax * 100.0 / slope < 20.0))

        val delayFlag = if (!(d0 || d1 || d2 || d3 || d4)) {
            false
        } else if (phase1 || state.count == 0 || state.previousDelayFlag) {
            true
        } else {
            var inWindow = 0
            for (s in priorSeq) {
                if (s > 0 && sequence - s <= 23) inWindow++
            }
            if (inWindow >= 21) {
                val start = priorGlu.size - inWindow
                var rapidDrop = false
                for (k in start + 1 until priorGlu.size) {
                    val dest = priorGlu[k]
                    if (priorGlu[k - 1] - dest >= 40.0 && dest < 100.0) {
                        rapidDrop = true
                        break
                    }
                }
                if (!rapidDrop && priorGlu[23] - roundedGlucose >= 40.0 && roundedGlucose < 100.0) {
                    rapidDrop = true
                }
                rapidDrop
            } else {
                d3 && priorGlu[23] - roundedGlucose > 50.0
            }
        }

        System.arraycopy(priorSeq, 1, priorSeq, 0, 23)
        priorSeq[23] = sequence
        System.arraycopy(priorGlu, 1, priorGlu, 0, 23)
        priorGlu[23] = roundedGlucose
        state.previousDelayFlag = delayFlag
        state.delayPreviousRawMax = rawMax

        val dataType4 = delayFlag || noiseBreak
        System.arraycopy(state.delayTrendFlags, 1, state.delayTrendFlags, 0, 6)
        state.delayTrendFlags[6] = dataType4
        return delayFlag
    }
}
