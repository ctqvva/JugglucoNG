package tk.glucodata.chart

import tk.glucodata.GlucoseChartGap
import tk.glucodata.GlucosePoint

/**
 * Builds the resolved chart from the raw ingredients, once, for every renderer.
 *
 * Three decisions are made here and nowhere else:
 *
 * - **The value.** A point that carries a recorded value draws that value; a
 *   point that does not draws today's calibration of the sensor's own value
 *   when a calibration applies, and the sensor's own value otherwise. The same
 *   preference for every surface, so a minute the user has already been shown
 *   reads the same on all of them.
 * - **The look.** Where the record names the main sensor for a minute, the
 *   series belonging to that sensor is drawn as main there and every other
 *   series as secondary. Where the record is silent, the primary series is
 *   main and the peers are secondary — the normal current-primary logic. See
 *   [MainSensorOwnership].
 * - **The runs.** A series breaks where the chart's gap rule says readings are
 *   too far apart to join, and changes look at an ownership boundary without
 *   breaking: consecutive runs share their boundary point.
 *
 * Pure. The calibration is injected so the rule can be tested without the
 * calibration engine, and so the builder does not care which surface asked.
 */
object HistoryChartModelBuilder {

    /** One input series: a sensor's own points, in display units. */
    data class SeriesInput(
        val sensorId: String,
        val isPrimary: Boolean,
        val viewMode: Int,
        val colorArgb: Int,
        val points: List<GlucosePoint>,
    )

    /** `(baseValue, timestamp, isRawMode, sensorId) -> calibrated`, or null when no calibration applies. */
    fun interface Calibration {
        fun apply(baseValue: Float, timestamp: Long, isRawMode: Boolean, sensorId: String): Float?
    }

    fun build(
        inputs: List<SeriesInput>,
        ownership: MainSensorOwnership,
        calibration: Calibration,
        gapThresholdMs: Long = GlucoseChartGap.THRESHOLD_MS,
    ): HistoryChartModel {
        if (inputs.isEmpty()) return HistoryChartModel.EMPTY
        return HistoryChartModel(
            inputs.map { input -> buildSeries(input, ownership, calibration, gapThresholdMs) }
        )
    }

    private fun buildSeries(
        input: SeriesInput,
        ownership: MainSensorOwnership,
        calibration: Calibration,
        gapThresholdMs: Long,
    ): ChartSeriesModel {
        val isRawMode = input.viewMode == 1 || input.viewMode == 3
        val defaultLook = if (input.isPrimary) ChartLook.MAIN else ChartLook.SECONDARY

        val runs = ArrayList<ChartRun>()
        var current = ArrayList<ChartPointModel>()
        var currentLook: ChartLook? = null
        var lastTimestamp = Long.MIN_VALUE

        fun flush() {
            if (current.size >= 1 && currentLook != null) {
                runs.add(ChartRun(currentLook!!, current))
            }
            current = ArrayList()
        }

        for (point in input.points) {
            val value = resolveValue(point, isRawMode, input.sensorId, calibration) ?: continue
            val look = when (ownership.isMainAt(input.sensorId, point.timestamp)) {
                true -> ChartLook.MAIN
                false -> ChartLook.SECONDARY
                null -> defaultLook
            }
            val model = ChartPointModel(point.timestamp, value)

            val gap = lastTimestamp != Long.MIN_VALUE && (point.timestamp - lastTimestamp) > gapThresholdMs
            if (gap) {
                flush()
                currentLook = look
            } else if (currentLook != null && look != currentLook) {
                // A change of look on a continuous line: the boundary point
                // belongs to both runs, so neither renderer draws a hole.
                val boundary = current.lastOrNull()
                flush()
                currentLook = look
                if (boundary != null) current.add(boundary)
            } else if (currentLook == null) {
                currentLook = look
            }
            current.add(model)
            lastTimestamp = point.timestamp
        }
        flush()

        return ChartSeriesModel(
            sensorId = input.sensorId,
            isPrimary = input.isPrimary,
            viewMode = input.viewMode,
            colorArgb = input.colorArgb,
            runs = runs,
        )
    }

    /**
     * The one value rule. A recorded value wins outright; otherwise the
     * calibration of the sensor's own value when one applies; otherwise the
     * sensor's own value. Null when the point has nothing to draw in this lane.
     */
    fun resolveValue(
        point: GlucosePoint,
        isRawMode: Boolean,
        sensorId: String,
        calibration: Calibration,
    ): Float? {
        val sealed = point.sealedDisplayValue
        if (!sealed.isNaN() && sealed > 0.1f) return sealed
        val base = if (isRawMode) point.rawValue else point.value
        if (base.isNaN() || base <= 0.1f) return null
        val calibrated = calibration.apply(base, point.timestamp, isRawMode, sensorId)
        return if (calibrated != null && !calibrated.isNaN() && calibrated > 0.1f) calibrated else base
    }
}
