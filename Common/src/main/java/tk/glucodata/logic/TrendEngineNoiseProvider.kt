package tk.glucodata.logic

import tk.glucodata.GlucosePoint
import tk.glucodata.NoiseEstimate
import tk.glucodata.TrendNoiseProvider

object TrendEngineNoiseProvider : TrendNoiseProvider {
    override fun noise(points: List<GlucosePoint>, useRaw: Boolean, isMmol: Boolean): NoiseEstimate? {
        val result = TrendEngine.calculateTrend(points, useRaw, isMmol)
        val samples = result.noiseSamples
        if (!result.noiseValid || samples < 4) return null
        // xDrip publishes SSE / (n - 3) for a quadratic fit; the engine keeps SSE / n for
        // the UI chip. Rebase so EXTRA_NOISE and the 10/60/200 levels mean what xDrip means.
        val variance = result.noiseLevel * samples / (samples - 3f)
        if (!variance.isFinite() || variance < 0f) return null
        return NoiseEstimate(variance)
    }
}
