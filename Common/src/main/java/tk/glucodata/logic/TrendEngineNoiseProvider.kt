package tk.glucodata.logic

import tk.glucodata.GlucosePoint
import tk.glucodata.NoiseEstimate
import tk.glucodata.TrendNoiseProvider

object TrendEngineNoiseProvider : TrendNoiseProvider {
    override fun noise(points: List<GlucosePoint>, useRaw: Boolean, isMmol: Boolean): NoiseEstimate? {
        val result = TrendEngine.calculateTrend(points, useRaw, isMmol)
        if (!result.noiseValid) return null
        return NoiseEstimate(result.noiseLevel)
    }
}
