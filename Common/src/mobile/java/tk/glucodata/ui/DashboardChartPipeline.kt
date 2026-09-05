package tk.glucodata.ui

import tk.glucodata.DataSmoothing
import tk.glucodata.data.journal.JournalEntry
import tk.glucodata.data.journal.JournalInsulinPreset
import tk.glucodata.data.prediction.GlucosePredictionSeries
import tk.glucodata.data.prediction.GlucosePredictionSeriesKind
import tk.glucodata.data.prediction.PredictiveSimulationSettings
import tk.glucodata.data.prediction.buildGlucosePrediction

internal enum class PredictionHistorySource {
    AUTO,
    RAW,
    CALIBRATED_AUTO,
    CALIBRATED_RAW
}

internal const val PREDICTION_HISTORY_WINDOW_MS = 60L * 60L * 1000L
internal const val PREDICTION_HISTORY_MAX_POINTS = 96
internal const val DASHBOARD_CONSUMER_HISTORY_MAX_POINTS = 512

internal fun trimHistoryForPrediction(points: List<GlucosePoint>): List<GlucosePoint> {
    if (points.size <= PREDICTION_HISTORY_MAX_POINTS) return points

    var latestTimestamp = 0L
    for (index in points.indices.reversed()) {
        val point = points[index]
        if (point.timestamp > 0L &&
            ((point.value.isFinite() && point.value > 0.1f) ||
                (point.rawValue.isFinite() && point.rawValue > 0.1f))
        ) {
            latestTimestamp = point.timestamp
            break
        }
    }
    if (latestTimestamp <= 0L) return points.takeLast(PREDICTION_HISTORY_MAX_POINTS)

    val startTimestamp = latestTimestamp - PREDICTION_HISTORY_WINDOW_MS
    val recent = ArrayList<GlucosePoint>(PREDICTION_HISTORY_MAX_POINTS)
    for (index in points.indices.reversed()) {
        val point = points[index]
        if (point.timestamp < startTimestamp && recent.size >= 2) break
        if (point.timestamp in startTimestamp..latestTimestamp &&
            ((point.value.isFinite() && point.value > 0.1f) ||
                (point.rawValue.isFinite() && point.rawValue > 0.1f))
        ) {
            recent.add(point)
            if (recent.size >= PREDICTION_HISTORY_MAX_POINTS) break
        }
    }
    recent.reverse()

    return when {
        recent.size >= 2 -> recent
        else -> points.takeLast(PREDICTION_HISTORY_MAX_POINTS)
    }
}

internal fun buildDisplayHistoryForPrediction(
    points: List<GlucosePoint>,
    source: PredictionHistorySource
): List<GlucosePoint> {
    if (points.isEmpty()) return emptyList()

    val useRaw = source == PredictionHistorySource.RAW || source == PredictionHistorySource.CALIBRATED_RAW
    val useCalibration = source == PredictionHistorySource.CALIBRATED_AUTO || source == PredictionHistorySource.CALIBRATED_RAW
    val calibrationActiveBySensor = HashMap<String?, Boolean>()

    return points.map { point ->
        val baseValue = if (useRaw) point.rawValue else point.value
        val sensorId = point.sensorSerial?.takeIf { it.isNotBlank() }
        // The prediction extrapolates the line the user is looking at, so it has
        // to read the same values that line draws — a recorded one included.
        val sealed = if (useCalibration) {
            point.sealedDisplayValue?.takeIf { it.isFinite() && it > 0.1f }
        } else {
            null
        }
        val activeCalibration = useCalibration &&
            calibrationActiveBySensor.getOrPut(sensorId) {
                tk.glucodata.data.calibration.CalibrationManager.hasActiveCalibration(useRaw, sensorId)
            }
        val displayValue = sealed
            ?: if (activeCalibration && baseValue.isFinite() && baseValue > 0.1f) {
                tk.glucodata.data.calibration.CalibrationManager.getCalibratedValue(
                    baseValue,
                    point.timestamp,
                    useRaw,
                    sensorIdOverride = sensorId
                )
            } else {
                baseValue
            }
        point.copy(value = displayValue, rawValue = baseValue)
    }
}

internal fun hasAnyActiveCalibrationForPrediction(
    points: List<GlucosePoint>,
    useRaw: Boolean
): Boolean {
    val checkedSensors = HashMap<String?, Boolean>()
    return points.any { point ->
        val value = if (useRaw) point.rawValue else point.value
        if (!value.isFinite() || value <= 0.1f) {
            false
        } else {
            val sensorId = point.sensorSerial?.takeIf { it.isNotBlank() }
            checkedSensors.getOrPut(sensorId) {
                tk.glucodata.data.calibration.CalibrationManager.hasActiveCalibration(useRaw, sensorId)
            }
        }
    }
}

internal fun buildPredictionSeriesForChart(
    points: List<GlucosePoint>,
    viewMode: Int,
    journalEntries: List<JournalEntry>,
    insulinPresetsById: Map<Long, JournalInsulinPreset>,
    unit: String,
    targetLow: Float,
    targetHigh: Float,
    settings: PredictiveSimulationSettings
): List<GlucosePredictionSeries> {
    if (!settings.enabled || points.size < 2) return emptyList()

    val predictionHistory = trimHistoryForPrediction(points)
    if (predictionHistory.size < 2) return emptyList()

    val isRawMode = viewMode == 1 || viewMode == 3
    val hasCalibration = hasAnyActiveCalibrationForPrediction(predictionHistory, isRawMode)
    val hideInitialWhenCalibrated = hasCalibration &&
        tk.glucodata.data.calibration.CalibrationManager.shouldHideInitialWhenCalibrated()
    val drawRaw = !(hideInitialWhenCalibrated && isRawMode) && (viewMode == 1 || viewMode == 2 || viewMode == 3)
    val drawAuto = !(hideInitialWhenCalibrated && !isRawMode) && (viewMode == 0 || viewMode == 2 || viewMode == 3)

    fun buildSeries(
        kind: GlucosePredictionSeriesKind,
        source: PredictionHistorySource
    ): GlucosePredictionSeries? {
        val seriesPoints = buildGlucosePrediction(
            history = buildDisplayHistoryForPrediction(predictionHistory, source),
            journalEntries = journalEntries,
            insulinPresetsById = insulinPresetsById,
            unit = unit,
            targetLow = targetLow,
            targetHigh = targetHigh,
            settings = settings
        )
        return seriesPoints.takeIf { it.size >= 2 }?.let { GlucosePredictionSeries(kind, it) }
    }

    return buildList {
        if (drawRaw) {
            buildSeries(GlucosePredictionSeriesKind.RAW, PredictionHistorySource.RAW)?.let(::add)
        }
        if (drawAuto) {
            buildSeries(GlucosePredictionSeriesKind.AUTO, PredictionHistorySource.AUTO)?.let(::add)
        }
        if (hasCalibration) {
            buildSeries(
                GlucosePredictionSeriesKind.CALIBRATED,
                if (isRawMode) PredictionHistorySource.CALIBRATED_RAW else PredictionHistorySource.CALIBRATED_AUTO
            )?.let(::add)
        }
    }
}

/**
 * Single source of truth for the history every non-chart consumer sees: the trend arrow,
 * the recent readings and their Δ, the predictive simulation.
 *
 * Takes the window already resolved by [DataSmoothing.localSmoothingMinutes] rather than
 * the raw switches. Working them out here is what let this series and the one behind the
 * notification drift apart, and a caller that only has "how many minutes" cannot get the
 * answer wrong. 0 means the reading is used as measured.
 */
internal fun buildSmoothedConsumerHistory(
    points: List<GlucosePoint>,
    evaluationSmoothingMinutes: Int,
    collapseChunks: Boolean
): List<GlucosePoint> {
    if (points.isEmpty()) {
        return points
    }
    val source = if (points.size > DASHBOARD_CONSUMER_HISTORY_MAX_POINTS) {
        points.takeLast(DASHBOARD_CONSUMER_HISTORY_MAX_POINTS)
    } else {
        points
    }
    if (evaluationSmoothingMinutes <= 0) {
        return source
    }

    val processed = ArrayList<GlucosePoint>(source.size)
    GlucosePointSegments.split(source).forEach { segment ->
        val sourceByTimestamp = segment.associateBy { it.timestamp }
        val smoothed = DataSmoothing.smoothNativePoints(
            segment.map { tk.glucodata.GlucosePoint(it.timestamp, it.value, it.rawValue) },
            evaluationSmoothingMinutes,
            collapseChunks
        )

        smoothed.forEach { point ->
            val source = sourceByTimestamp[point.timestamp]
            processed.add(
                GlucosePoint(
                    value = point.value,
                    time = source?.time ?: java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(point.timestamp)),
                    timestamp = point.timestamp,
                    rawValue = point.rawValue,
                    rate = source?.rate,
                    sensorSerial = source?.sensorSerial,
                    // Smoothing displaces the value, so the interval follows it
                    // rather than being dropped or left behind around the
                    // unsmoothed one. The width is the estimator's claim and
                    // does not change: smoothing makes the line calmer, not the
                    // sensor more certain.
                    uncertainty = source?.uncertainty?.shifted(point.value - (source.value))
                )
            )
        }
    }
    return processed
}

internal fun buildDisplayReadings(
    consumerHistory: List<GlucosePoint>,
    limit: Int = 10
): List<GlucosePoint> {
    if (consumerHistory.isEmpty()) {
        return emptyList()
    }
    return consumerHistory
        .asReversed()
        .distinctBy { it.timestamp }
        .sortedByDescending { it.timestamp }
        .take(limit)
}

/**
 * The most points [tk.glucodata.logic.TrendEngine] can regress over: it cuts to a
 * 20 minute window and then caps at 30 samples, so handing it more is never read.
 */
internal const val TREND_HISTORY_LIMIT = 30

/**
 * The point list the row arrows regress over, as opposed to the rows actually drawn.
 *
 * These are two different lengths on purpose. The dashboard draws 10 recent readings,
 * but every row's arrow is a 20 minute regression, and at a one-a-minute cadence 10
 * points is only 10 minutes of it. Feeding [ReadingRow] the displayed list starved
 * that window here while the history and journal screens — which pass a whole day
 * section — got the full one, so the same reading drew a rising arrow on the dashboard
 * and a falling one in history, and neither matched the hero.
 *
 * Same source, ordering and de-duplication as [buildDisplayReadings], just long enough,
 * which keeps the displayed readings an exact prefix of this list. ReadingRow slices it
 * with `history.drop(index)` using the *display* index, so that prefix relationship is
 * what makes the row-to-window alignment correct.
 */
internal fun buildTrendHistory(consumerHistory: List<GlucosePoint>): List<GlucosePoint> =
    buildDisplayReadings(consumerHistory, limit = TREND_HISTORY_LIMIT)

/**
 * A row's measured change: the text it shows, and the same movement as a rate the arrow
 * beside it can be drawn from.
 *
 * The rate is mg/dL per minute, which is what every arrow in the app rotates by, whatever
 * unit the reading is displayed in.
 */
internal data class ReadingDelta(val text: String, val rateMgdlPerMinute: Float)

/**
 * The "Δ" readout of the hero card, anchored at arbitrary points in time: for each
 * anchor timestamp, the delta the hero would have shown when that reading was the
 * newest one. The hero's own readout is the single-anchor case (the newest point),
 * so there is exactly one walk-back and one formatting rule — a row can never
 * disagree with the hero about the same reading.
 *
 * Anchors must be newest-first (the order the dashboard rows are drawn in) and
 * [history] is the hero's input list, oldest-first — the *unsmoothed* one: visual
 * smoothing reshapes recent values, and the Δ is a raw measurement by design.
 * Both cursors only ever move toward older points, so the whole visible list is
 * one pass over the history regardless of how many rows are shown.
 *
 * An anchor with no old-enough partner (start of the data, or a gap wider than
 * the pairing rules allow) yields null, never a NaN text.
 */

internal fun readingDeltaTexts(
    anchorTimestamps: List<Long>,
    history: List<GlucosePoint>,
    isMmol: Boolean,
    deltaIntervalMinutes: Int
): List<String?> = readingDeltas(anchorTimestamps, history, isMmol, deltaIntervalMinutes)
    .map { it?.text }

/**
 * The walk itself. One pairing serves both the number and the arrow, so a row cannot show
 * a rise beside a falling arrow: they are the same two readings, read once.
 */
internal fun readingDeltas(
    anchorTimestamps: List<Long>,
    history: List<GlucosePoint>,
    isMmol: Boolean,
    deltaIntervalMinutes: Int
): List<ReadingDelta?> {
    if (anchorTimestamps.isEmpty()) return emptyList()
    val newestFirst = history.asReversed()
    val minGap = tk.glucodata.GlucoseDelta.minGapMillis(deltaIntervalMinutes)
    var anchorIndex = 0
    var previousIndex = 0
    return anchorTimestamps.map { anchorTimestamp ->
        while (anchorIndex < newestFirst.size && newestFirst[anchorIndex].timestamp > anchorTimestamp) {
            anchorIndex++
        }
        val anchor = newestFirst.getOrNull(anchorIndex) ?: return@map null
        // Same predicate as the hero's walk-back: the first point old enough for
        // the interval's window, skipping empty/sentinel values. Skips are safe to
        // keep across anchors: a value failure is permanent, and a too-small gap
        // only shrinks further for the older anchors that follow.
        while (previousIndex < newestFirst.size) {
            val candidate = newestFirst[previousIndex]
            if (candidate.value > 0.1f && anchor.timestamp - candidate.timestamp >= minGap) break
            previousIndex++
        }
        val previous = newestFirst.getOrNull(previousIndex) ?: return@map null
        val delta = tk.glucodata.GlucoseDelta.delta(
            anchor.timestamp, anchor.value,
            previous.timestamp, previous.value,
            deltaIntervalMinutes
        )
        val text = tk.glucodata.GlucoseDelta.format(delta, isMmol).takeIf { it.isNotEmpty() }
            ?: return@map null
        ReadingDelta(text, perMinuteMgdl(delta, isMmol, deltaIntervalMinutes))
    }
}

/**
 * The same "Δ" column for the history screen's reading rows, keyed by the reading
 * itself: the history renders its rows per day section, so a positional list would
 * have to be re-indexed at every section boundary; a map lets each row look its own
 * text up.
 *
 * The history also reaches across sensors, and a difference between two sensors'
 * readings is not a measured change. So the history is bucketed once per sensor —
 * by [sameSensor], which is [tk.glucodata.SensorIdentity.matches] in the app, so a
 * sensor stored under two names stays one — and every row walks back through its
 * own sensor's bucket only. Rows the app cannot attribute ([isShared]: imported and
 * unknown serials) belong to every bucket, which is what the dashboard's per-sensor
 * history holds as well. Within one bucket the delta is exactly [readingDeltas]'s,
 * gaps included: a row with no old-enough partner is absent.
 *
 * Build one index per [history] (oldest-first, unsmoothed) and ask it per visible
 * window: [deltasFor] does a binary search per bucket, not a pass over the data.
 */
internal class RowDeltaIndex(
    history: List<GlucosePoint>,
    private val sameSensor: (String, String) -> Boolean,
    private val isShared: (String) -> Boolean = { false }
) {
    private val logicalSerials = ArrayList<String>()
    private val keyBySerial = HashMap<String, String>()
    private val historyByKey: Map<String?, List<GlucosePoint>>

    init {
        val shared = ArrayList<GlucosePoint>()
        val own = LinkedHashMap<String?, ArrayList<GlucosePoint>>()
        history.forEach { point ->
            val key = keyOf(point.sensorSerial)
            if (key === SHARED) shared.add(point) else own.getOrPut(key) { ArrayList() }.add(point)
        }
        historyByKey = if (shared.isEmpty()) {
            own
        } else {
            own.mapValues { (_, points) -> (points + shared).sortedBy { it.timestamp } } + (SHARED to shared)
        }
    }

    /** Blank and missing serials are one bucket, as in [GlucosePointSegments]. */
    private fun keyOf(serial: String?): String? {
        val raw = serial?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return keyBySerial.getOrPut(raw) {
            if (isShared(raw)) {
                SHARED
            } else {
                logicalSerials.firstOrNull { sameSensor(it, raw) } ?: raw.also(logicalSerials::add)
            }
        }
    }

    /**
     * [rows] newest-first, the order the history draws them. The rate rides along with
     * the text so a history row can point its arrow at the number it prints, the same
     * way the dashboard's rows do.
     */
    fun deltasFor(rows: List<GlucosePoint>, isMmol: Boolean, deltaIntervalMinutes: Int): Map<GlucosePoint, ReadingDelta> {
        if (rows.isEmpty() || historyByKey.isEmpty()) return emptyMap()
        val deltas = HashMap<GlucosePoint, ReadingDelta>()
        rows.groupBy { keyOf(it.sensorSerial) }.forEach { (key, sensorRows) ->
            val bucket = historyByKey[key] ?: return@forEach
            // Nothing newer than the newest row takes part in its walk-back: cut the
            // bucket there, so a window months back does not start at today.
            val newest = sensorRows.first().timestamp
            var cut = bucket.binarySearchBy(newest) { it.timestamp }.let { if (it >= 0) it + 1 else -it - 1 }
            while (cut < bucket.size && bucket[cut].timestamp == newest) cut++
            val sensorDeltas = readingDeltas(
                sensorRows.map { it.timestamp },
                bucket.subList(0, cut),
                isMmol,
                deltaIntervalMinutes
            )
            sensorRows.forEachIndexed { index, row ->
                sensorDeltas.getOrNull(index)?.let { deltas[row] = it }
            }
        }
        return deltas
    }

    private companion object {
        /** The bucket of the rows no sensor owns; a string no serial can be. */
        const val SHARED = "\u0000shared"
    }
}

/**
 * The delta is a change over the configured window, in the unit on screen; an arrow turns by
 * mg/dL per minute. Same movement, said the way the arrow reads it.
 */
private fun perMinuteMgdl(delta: Float, isMmol: Boolean, deltaIntervalMinutes: Int): Float {
    val minutes = tk.glucodata.GlucoseDelta.sanitizeIntervalMinutes(deltaIntervalMinutes)
    val perMinute = delta / minutes
    return if (isMmol) perMinute * tk.glucodata.ui.util.GlucoseFormatter.MGDL_PER_MMOL else perMinute
}
