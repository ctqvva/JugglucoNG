package tk.glucodata

object NoiseAccess {
    @Volatile
    private var provider: TrendNoiseProvider? = null

    @JvmStatic
    fun register(provider: TrendNoiseProvider) {
        this.provider = provider
    }

    /** Registration-completeness check, mirroring [TrendAccess.isRegistered]. */
    @JvmStatic
    fun isRegistered(): Boolean = provider != null

    internal fun resolve(
        provider: TrendNoiseProvider?,
        points: List<GlucosePoint>,
        useRaw: Boolean,
        isMmol: Boolean
    ): NoiseEstimate? {
        if (provider == null) return null
        return runCatching { provider.noise(points, useRaw, isMmol) }.getOrNull()
    }

    /** The points already contain the outgoing primary lane, smoothing and calibration. */
    @JvmStatic
    fun forExchange(points: List<GlucosePoint>, timeMillis: Long, isMmol: Boolean): NoiseEstimate? {
        if (!DisplayTrendSource.isFreshForExchange(timeMillis, System.currentTimeMillis())) return null
        return resolve(provider, points, useRaw = false, isMmol)
    }
}
