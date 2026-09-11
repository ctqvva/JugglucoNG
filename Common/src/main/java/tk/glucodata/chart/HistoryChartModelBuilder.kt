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
        /** Whether a calibration applies to this sensor's own primary lane. */
        val hasCalibration: Boolean = false,
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

        // Every series has its lanes, not only the primary: a peer in a dual
        // view mode shows both signals, exactly as the primary does. The first
        // version gave peers one lane and their second signal simply vanished.
        val secondaryLanes = ArrayList<ChartLane>()
        val seriesHasCalibration = if (input.isPrimary) hasCalibration else input.hasCalibration
        secondaryLaneKinds(input.viewMode, seriesHasCalibration, hideInitialWhenCalibrated).forEach { kind ->
            secondaryLanes.add(ChartLane(kind, segmentLane(input.points, kind, gapThresholdMs)))
        }

        val runs = ArrayList<ChartRun>()
        // The preview lane is collected alongside the main runs: it exists at
        // exactly the points where the main line drew something other than the
        // live calibration's answer.
        val previewRuns = ArrayList<ChartRun>()
        var preview = ArrayList<ChartPointModel>()
        var previewLast = Long.MIN_VALUE
        fun flushPreview() {
            if (preview.isNotEmpty()) previewRuns.add(ChartRun(ChartLook.SECONDARY, preview))
            preview = ArrayList()
        }
        var current = ArrayList<ChartPointModel>()
        var currentLook: ChartLook? = null
        var lastTimestamp = Long.MIN_VALUE
        var lastSensorId: String? = null

        fun flush() {
            if (current.size >= 1 && currentLook != null) {
                runs.add(ChartRun(currentLook!!, current))
            }
            current = ArrayList()
        }

        for (point in input.points) {
            val sensorId = point.sensorSerial?.trim()?.takeIf { it.isNotEmpty() } ?: input.sensorId
            val value = resolveValue(point, isRawMode, sensorId, calibration) ?: continue
            val sensorChanged = lastSensorId != null && !tk.glucodata.SensorIdentity.matches(lastSensorId, sensorId)
            if (sensorChanged) { flushPreview(); previewLast = Long.MIN_VALUE }
            if (input.isPrimary) {
                val live = liveCalibratedValue(point, isRawMode, sensorId, calibration)
                val differs = live != null && kotlin.math.abs(live - value) > PREVIEW_DIFFERENCE
                if (differs) {
                    if (previewLast != Long.MIN_VALUE && point.timestamp - previewLast > gapThresholdMs) flushPreview()
                    preview.add(ChartPointModel(point.timestamp, live!!))
                    previewLast = point.timestamp
                } else if (preview.isNotEmpty()) {
                    flushPreview()
                    previewLast = Long.MIN_VALUE
                }
            }
            val look = when (ownership.isMainAt(sensorId, point.timestamp)) {
                true -> ChartLook.MAIN
                false -> ChartLook.SECONDARY
                null -> defaultLook
            }
            val model = ChartPointModel(point.timestamp, value, sensorId, look)

            val gap = lastTimestamp != Long.MIN_VALUE && (point.timestamp - lastTimestamp) > gapThresholdMs
            if (gap || sensorChanged) {
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
            lastSensorId = sensorId
        }
        flush()
        flushPreview()
        if (previewRuns.isNotEmpty()) {
            secondaryLanes.add(ChartLane(ChartLaneKind.CALIBRATION_PREVIEW, previewRuns))
        }

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
        var lastSensorId: String? = null
        for (point in points) {
            val value = if (kind == ChartLaneKind.RAW) point.rawValue else point.value
            if (value.isNaN() || value <= 0.1f) continue
            val sensorChanged = lastSensorId != null && point.sensorSerial != null &&
                !tk.glucodata.SensorIdentity.matches(lastSensorId, point.sensorSerial)
            if (sensorChanged || (lastTimestamp != Long.MIN_VALUE && point.timestamp - lastTimestamp > gapThresholdMs)) {
                if (current.isNotEmpty()) runs.add(ChartRun(ChartLook.SECONDARY, current))
                current = ArrayList()
            }
            current.add(ChartPointModel(point.timestamp, value))
            lastTimestamp = point.timestamp
            lastSensorId = point.sensorSerial
        }
        if (current.isNotEmpty()) runs.add(ChartRun(ChartLook.SECONDARY, current))
        return runs
    }

    /**
     * A record is a fact about a minute *and a lane*. It was written from the
     * main line, whose lane is the view mode's: auto for 0/2, raw for 1/3. A
     * value the user was shown on the raw line says nothing about what the
     * auto line showed, so resolving the other lane must not take it — that
     * lane derives as if nothing had been recorded. Without this, sealing a
     * minute in raw mode and then switching to auto drew the raw number on the
     * auto line. An unknown lane (a record from before the lane was kept) is
     * taken at face value rather than discarded.
     */
    fun recordAppliesToLane(sealedDisplayViewMode: Int, isRawMode: Boolean): Boolean {
        if (sealedDisplayViewMode < 0) return true
        val recordedIsRaw = sealedDisplayViewMode == 1 || sealedDisplayViewMode == 3
        return recordedIsRaw == isRawMode
    }

    /** Below this the record and the live calibration are the same number, in any unit. */
    private const val PREVIEW_DIFFERENCE = 0.05f

    /** The live calibration's answer for a point, ignoring any record. */
    private fun liveCalibratedValue(
        point: GlucosePoint,
        isRawMode: Boolean,
        sensorId: String,
        calibration: Calibration,
    ): Float? {
        val base = if (isRawMode) point.rawValue else point.value
        if (base.isNaN() || base <= 0.1f) return null
        val calibrated = calibration.apply(base, point.timestamp, isRawMode, sensorId) ?: return null
        return if (!calibrated.isNaN() && calibrated > 0.1f) calibrated else null
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
        if (!sealed.isNaN() && sealed > 0.1f && recordAppliesToLane(point.sealedDisplayViewMode, isRawMode)) {
            return sealed
        }
        val base = if (isRawMode) point.rawValue else point.value
        if (base.isNaN() || base <= 0.1f) return null
        val calibrated = calibration.apply(base, point.timestamp, isRawMode, sensorId)
        return if (calibrated != null && !calibrated.isNaN() && calibrated > 0.1f) calibrated else base
    }
}
