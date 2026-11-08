package tk.glucodata

import kotlin.math.sqrt

object NoiseScale {
    const val LIGHT_THRESHOLD_MGDL2 = 10f
    const val MEDIUM_THRESHOLD_MGDL2 = 60f
    const val HEAVY_THRESHOLD_MGDL2 = 200f

    @JvmStatic
    fun nightscoutLevel(varianceMgdl2: Float): Int = when {
        !varianceMgdl2.isFinite() || varianceMgdl2 <= LIGHT_THRESHOLD_MGDL2 -> 0
        varianceMgdl2 <= MEDIUM_THRESHOLD_MGDL2 -> 2
        varianceMgdl2 <= HEAVY_THRESHOLD_MGDL2 -> 3
        else -> 4
    }
}

data class NoiseEstimate(val varianceMgdl2: Float) {
    val sigmaMgdl: Float get() = sqrt(varianceMgdl2)
    val nightscoutLevel: Int get() = NoiseScale.nightscoutLevel(varianceMgdl2)
}
