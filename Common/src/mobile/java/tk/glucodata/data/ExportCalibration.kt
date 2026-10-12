package tk.glucodata.data

import kotlin.math.abs
import tk.glucodata.CurrentDisplaySource
import tk.glucodata.chart.HistoryChartModelBuilder
import tk.glucodata.data.calibration.CalibrationManager
import tk.glucodata.ui.util.GlucoseFormatter

/**
 * Emit-time software-calibration projection for on-device file exports (issue #130).
 *
 * Live outputs (Nightscout, xDrip, notifications, widgets, on-screen) apply the
 * software calibration on a copy at emit time while the stored reading stays raw.
 * File exports historically read the raw store directly, so a local CSV / report /
 * JSON could disagree with what Nightscout and the screen showed — a CSV could even
 * show a hypo that never appeared anywhere else.
 *
 * These helpers apply the SAME projection the UI uses (see `ReadingRow`) so exports
 * can carry a calibrated column alongside the untouched raw values. This is
 * deliberately non-destructive: the stored data stays raw and reversible.
 */
object ExportCalibration {

    /** viewMode 1/3 mean the sensor's primary lane is the raw signal, not auto. */
    private fun isRawMode(viewMode: Int): Boolean = viewMode == 1 || viewMode == 3

    /**
     * The stored value the projection starts from: the raw lane in raw mode, the auto
     * lane otherwise, or null when that lane has nothing usable. Split out because
     * "which lane" is the whole of the disagreement this object exists to prevent
     * (issue #130), and it is the one decision here that needs no database to test.
     */
    fun laneBaseValue(autoDisplayValue: Float, rawDisplayValue: Float, viewMode: Int): Float? {
        val base = if (isRawMode(viewMode)) rawDisplayValue else autoDisplayValue
        return base.takeIf { it.isFinite() && it > 0.1f }
    }

    /** Per-run cache so we resolve a sensor's viewMode once, not once per reading. */
    fun viewModeResolver(): (String?) -> Int {
        val cache = HashMap<String, Int>()
        return { sensorId ->
            val key = sensorId?.takeIf { it.isNotBlank() }
            if (key == null) 0
            else cache.getOrPut(key) { CurrentDisplaySource.resolveViewModeForSensor(key) }
        }
    }

    /**
     * Calibrated value (in the user's display unit) for a stored reading already
     * expressed in display units, or null when no non-destructive calibration
     * applies. Null is returned when no active software calibration exists for
     * this sensor's primary lane, or the base value is missing/unusable.
     *
     * [sealedDisplayValue] wins outright when present **and recorded for this
     * lane**: a record is a fact about a minute *and a lane* (see
     * [HistoryChartModelBuilder.recordAppliesToLane]), so a value shown on the
     * raw line must not stand in for the auto line. An export that recomputed
     * a value the user was shown a different number for would contradict both
     * the chart and Nightscout, which is the disagreement this file exists to
     * prevent.
     *
     * @param autoDisplayValue the stored auto value in display units
     * @param rawDisplayValue   the stored raw value in display units
     * @param sealedDisplayValue the recorded displayed value, in display units
     * @param sealedDisplayViewMode the view mode in force when [sealedDisplayValue]
     *   was recorded (0/2 auto, 1/3 raw); null/negative when unknown, which is
     *   taken at face value rather than discarded.
     */
    fun calibratedDisplayValue(
        autoDisplayValue: Float,
        rawDisplayValue: Float,
        timestamp: Long,
        sensorId: String?,
        viewMode: Int,
        sealedDisplayValue: Float? = null,
        sealedDisplayViewMode: Int? = null
    ): Float? {
        val raw = isRawMode(viewMode)
        sealedDisplayValue?.takeIf { it.isFinite() && it > 0.1f }
            ?.takeIf { HistoryChartModelBuilder.recordAppliesToLane(sealedDisplayViewMode ?: -1, raw) }
            ?.let { return it }
        val base = laneBaseValue(autoDisplayValue, rawDisplayValue, viewMode) ?: return null
        val calSensorId = sensorId?.takeIf { it.isNotBlank() }
        if (!CalibrationManager.hasActiveCalibration(raw, calSensorId)) return null
        val calibrated = CalibrationManager.getCalibratedValue(
            base,
            timestamp,
            raw,
            sensorIdOverride = calSensorId
        )
        if (!calibrated.isFinite() || calibrated <= 0f) return null
        // Nothing to add when the projection is a no-op.
        return if (abs(calibrated - base) > 0.0001f) calibrated else null
    }

    /**
     * Calibrated value for a stored reading expressed in mg/dL, returned in mg/dL,
     * or null when no non-destructive calibration applies. Handles the round-trip
     * through the user's display unit that the calibration model operates in.
     */
    fun calibratedMgDl(
        autoMgDl: Float,
        rawMgDl: Float,
        timestamp: Long,
        sensorId: String?,
        viewMode: Int,
        isMmol: Boolean,
        sealedMgDl: Float? = null,
        sealedViewMode: Int? = null
    ): Float? {
        val autoDisplay = GlucoseFormatter.displayFromMgDl(autoMgDl, isMmol)
        val rawDisplay = GlucoseFormatter.displayFromMgDl(rawMgDl, isMmol)
        val calibratedDisplay = calibratedDisplayValue(
            autoDisplayValue = autoDisplay,
            rawDisplayValue = rawDisplay,
            timestamp = timestamp,
            sensorId = sensorId,
            viewMode = viewMode,
            sealedDisplayValue = sealedMgDl?.let { GlucoseFormatter.displayFromMgDl(it, isMmol) },
            sealedDisplayViewMode = sealedViewMode
        ) ?: return null
        return if (isMmol) GlucoseFormatter.mmolToMg(calibratedDisplay) else calibratedDisplay
    }
}
