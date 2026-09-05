package tk.glucodata.ui

import tk.glucodata.data.calibration.CalibrationManager

/**
 * The calibrated value a reading should show — recorded if it has one, computed
 * if it does not.
 *
 * Every surface that draws a past reading asks here, for the same reason
 * `HistoryRepository.withUncertainty` is a single helper: the alternative is
 * each site deciding for itself, and the sites then disagreeing. A reading whose
 * displayed value was recorded shows that value on the chart, in the reading
 * rows, in stats and in an export, or the freeze is not a guarantee — it is a
 * property of whichever screen happened to be updated.
 *
 * Values in, values out, all in the caller's display unit: a point's
 * [GlucosePoint.sealedDisplayValue] is converted alongside its value by
 * `inDisplayUnit`, so both sides of this choice are already in the same unit.
 */
internal object SealedGlucoseValue {

    /**
     * @return the value to display instead of the reading's own, or null when
     *   nothing calibrated applies and the sensor's value should be shown as-is.
     */
    fun calibratedFor(
        point: GlucosePoint,
        isRawMode: Boolean,
        sensorId: String?
    ): Float? {
        point.sealedDisplayValue?.takeIf { it.isFinite() && it > 0.1f }?.let { return it }
        return computeCalibrated(
            baseValue = if (isRawMode) point.rawValue else point.value,
            timestamp = point.timestamp,
            isRawMode = isRawMode,
            sensorId = sensorId
        )
    }

    /**
     * The live projection alone, for callers holding loose values rather than a
     * point (peer rows, the chart's per-lane cache).
     */
    fun computeCalibrated(
        baseValue: Float,
        timestamp: Long,
        isRawMode: Boolean,
        sensorId: String?
    ): Float? {
        if (!baseValue.isFinite() || baseValue <= 0.1f) return null
        if (!CalibrationManager.hasActiveCalibration(isRawMode, sensorId)) return null
        val calibrated = CalibrationManager.getCalibratedValue(
            baseValue,
            timestamp,
            isRawMode,
            sensorIdOverride = sensorId
        )
        return calibrated.takeIf { it.isFinite() && it > 0f }
    }
}
