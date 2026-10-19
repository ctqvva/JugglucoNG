package tk.glucodata.ui.stats

internal object HistoricalSensorModePolicy {
    fun resolve(
        activeViewMode: Int,
        isImported: Boolean,
        resolvedStoredMode: Int?,
        hasRawCalibration: Boolean,
        hasAutoCalibration: Boolean,
        matchesActiveSensor: Boolean
    ): Int {
        if (isImported) return 0
        resolvedStoredMode?.let { return it.coerceIn(0, 3) }
        if (hasRawCalibration.xor(hasAutoCalibration)) {
            return if (hasRawCalibration) 1 else 0
        }
        return if (matchesActiveSensor) activeViewMode.coerceIn(0, 3) else 0
    }
}
