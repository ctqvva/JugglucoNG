package tk.glucodata.alerts

internal object AlertGlucoseMath {
    private const val MGDL_PER_MMOL = 18.0182f
    private const val MIN_FORECAST_MINUTES = 1
    private const val MAX_FORECAST_MINUTES = 60

    fun projectedDisplayValue(
        glucoseValue: Float,
        rateMgdlPerMinute: Float,
        forecastMinutes: Int?,
        isMmol: Boolean
    ): Float {
        if (!glucoseValue.isFinite() || !rateMgdlPerMinute.isFinite()) {
            return Float.NaN
        }
        val minutes = normalizedForecastMinutes(forecastMinutes)
        val glucoseMgdl = if (isMmol) glucoseValue * MGDL_PER_MMOL else glucoseValue
        val projectedMgdl = glucoseMgdl + rateMgdlPerMinute * minutes
        return if (isMmol) projectedMgdl / MGDL_PER_MMOL else projectedMgdl
    }

    fun normalizedForecastMinutes(forecastMinutes: Int?): Int {
        return (forecastMinutes ?: AlertDefaults.FORECAST_LOOK_AHEAD_MINUTES)
            .coerceIn(MIN_FORECAST_MINUTES, MAX_FORECAST_MINUTES)
    }
}
