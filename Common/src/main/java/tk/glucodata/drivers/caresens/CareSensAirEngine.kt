// SPDX-License-Identifier: GPL-3.0-or-later
package tk.glucodata.drivers.caresens

internal data class CareSensAirOutput(
    val sequence: Int,
    val timestampSeconds: Long,
    val glucoseMgdl: Double,
    val rate: Double,
    val error: Int,
    val stage: Int,
) {
    val usable: Boolean
        get() = error == 0 && glucoseMgdl.isFinite() && glucoseMgdl > 35.0 && glucoseMgdl < 505.0
}

internal fun interface CareSensAirEngine {
    fun process(sequence: Int, timestampSeconds: Long, samples: IntArray, temperature: Double): CareSensAirOutput
}
