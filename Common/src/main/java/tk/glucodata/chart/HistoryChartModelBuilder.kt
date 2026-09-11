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

    /**
     * @param hasCalibration whether a calibration applies to the primary — this
     *   decides whether the primary's own lane also appears as an uncalibrated
     *   source lane beside the calibrated main line.
     * @param hideInitialWhenCalibrated the user's "hide source values" setting.
     */
    fun build(
        inputs: List<SeriesInput>,
        ownership: MainSensorOwnership,
        calibration: Calibration,
        hasCalibration: Boolean = false,
        hideInitialWhenCalibrated: Boolean = true,
        gapThresholdMs: Long = GlucoseChartGap.THRESHOLD_MS,
    ): HistoryChartModel {
        if (inputs.isEmpty()) return HistoryChartModel.EMPTY
        return HistoryChartModel(
            inputs.map { input ->
                buildSeries(input, ownership, calibration, hasCalibration, hideInitialWhenCalibrated, gapThresholdMs)
            }
        )
    }

    /**
     * Which lanes are drawn thin beside the primary's main line.
     *
     * The other signal in a dual view mode is always beside it. The primary's
     * own signal appears beside it only when a calibration has replaced it as
     * the main line and the user has not hidden source values — otherwise it
     * *is* the main line, and drawing it again would double it.
     */
    fun secondaryLaneKinds(viewMode: Int, hasCalibration: Boolean, hideInitialWhenCalibrated: Boolean): List<ChartLaneKind> {
        val primaryIsRaw = viewMode == 1 || viewMode == 3
        val hideSource = hasCalibration && hideInitialWhenCalibrated
        val out = ArrayList<ChartLaneKind>(2)
        val rawShown = viewMode == 1 || viewMode == 2 || viewMode == 3
        val autoShown = viewMode == 0 || viewMode == 2 || viewMode == 3
        if (rawShown && (if (primaryIsRaw) hasCalibration && !hideSource else true)) out.add(ChartLaneKind.RAW)
        if (autoShown && (if (!primaryIsRaw) hasCalibration && !hideSource else true)) out.add(ChartLaneKind.AUTO)
        return out
    }

    private fun buildSeries(
        input: SeriesInput,
        ownership: MainSensorOwnership,
        calibration: Calibration,
        hasCalibration: Boolean,
        hideInitialWhenCalibrated: Boolean,
        gapThresholdMs: Long,
    ): ChartSeriesModel {
        val isRawMode = input.viewMode == 1 || input.viewMode == 3
        val defaultLook = if (input.isPrimary) ChartLook.MAIN else ChartLook.SECONDARY

        val secondaryLanes = if (input.isPrimary) {
            secondaryLaneKinds(input.viewMode, hasCalibration, hideInitialWhenCalibrated).map { kind ->
                ChartLane(kind, segmentLane(input.points, kind, gapThresholdMs))
            }
        } else {
            emptyList()
        }

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
            secondaryLanes = secondaryLanes,
        )
    }

    /** A secondary lane: the sensor's own values in that lane, thin, split only at gaps. */
    private fun segmentLane(points: List<GlucosePoint>, kind: ChartLaneKind, gapThresholdMs: Long): List<ChartRun> {
        val runs = ArrayList<ChartRun>()
        var current = ArrayList<ChartPointModel>()
        var lastTimestamp = Long.MIN_VALUE
        for (point in points) {
            val value = if (kind == ChartLaneKind.RAW) point.rawValue else point.value
            if (value.isNaN() || value <= 0.1f) continue
            if (lastTimestamp != Long.MIN_VALUE && point.timestamp - lastTimestamp > gapThresholdMs) {
                if (current.isNotEmpty()) runs.add(ChartRun(ChartLook.SECONDARY, current))
                current = ArrayList()
            }
            current.add(ChartPointModel(point.timestamp, value))
            lastTimestamp = point.timestamp
        }
        if (current.isNotEmpty()) runs.add(ChartRun(ChartLook.SECONDARY, current))
        return runs
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
