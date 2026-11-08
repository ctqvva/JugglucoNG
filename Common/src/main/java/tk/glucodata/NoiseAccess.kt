package tk.glucodata

object NoiseAccess {
    private const val LOG_ID = "NoiseAccess"

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
        val estimate = runCatching { provider.noise(points, useRaw, isMmol) }.getOrNull() ?: return null
        if (!estimate.varianceMgdl2.isFinite() || estimate.varianceMgdl2 < 0f) return null
        return estimate
    }

    /** The broadcast carries the calibrated lane, so its noise must describe that lane. */
    internal fun resolveForExchange(
        provider: TrendNoiseProvider?,
        points: List<GlucosePoint>,
        isMmol: Boolean
    ): NoiseEstimate? = resolve(provider, points, useRaw = false, isMmol)

    @JvmStatic
    fun current(sensorId: String?): NoiseEstimate? {
        if (sensorId.isNullOrEmpty()) return null
        val active = provider ?: return null
        return try {
            val isMmol = Applic.unit == 1
            val points = DisplayTrendSource.exchangePoints(sensorId, isMmol)
            val newest = points.maxByOrNull { it.timestamp } ?: return null
            if (!DisplayTrendSource.isFreshForExchange(newest.timestamp, System.currentTimeMillis())) return null
            resolveForExchange(active, points, isMmol)
        } catch (th: Throwable) {
            if (Log.doLog) Log.i(LOG_ID, "current failed: $th")
            null
        }
    }
}
