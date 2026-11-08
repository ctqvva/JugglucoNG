package tk.glucodata

fun interface TrendNoiseProvider {
    fun noise(points: List<GlucosePoint>, useRaw: Boolean, isMmol: Boolean): NoiseEstimate?
}
