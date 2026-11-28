/*
 * This file is part of JugglucoNG.
 *
 * JugglucoNG is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option)
 * any later version.
 */

package tk.glucodata.drivers.caresens.calibration

import kotlin.math.min

internal object CareSensAirError4 {
    /** Condition 0: signal stays below both limits for four consecutive accepted records. */
    fun update(state: CareSensAirCalibrationState, sequence: Int, consecutive: Boolean, rawMax: Double, slope100: Float) {
        if (!consecutive) state.lowRawCount = 0
        System.arraycopy(state.lowRawMax, 1, state.lowRawMax, 0, 3)
        state.lowRawMax[3] = rawMax
        state.lowRawCount = min(4, state.lowRawCount + 1)
        var highest = Double.NEGATIVE_INFINITY
        for (v in state.lowRawMax) if (v > highest) highest = v
        val ratio = highest / slope100.toDouble()
        val lowCurrent = state.lowRawCount == 4 &&
            if (sequence < 865) highest < 0.5 && ratio < 0.1 else highest < 0.7 && ratio < 0.2
        state.lowError = CareSensAirDetectorLatch.consecutive(state.lowError, lowCurrent, consecutive, accepted = true)
    }

    fun reject(state: CareSensAirCalibrationState) {
        state.lowError = false
        state.lowRawCount = 0
    }
}
