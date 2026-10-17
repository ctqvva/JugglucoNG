package tk.glucodata

import kotlin.math.roundToInt
import tk.glucodata.ui.DisplayValueResolver
import tk.glucodata.ui.DisplayValues

object CurrentDisplaySource {
    private const val DEFAULT_HISTORY_WINDOW_MS = DisplayTrendSource.TREND_WINDOW_MS
    private const val LIVE_CONTEXT_WINDOW_MS = 2 * 60 * 1000L
    private const val MATCH_WINDOW_MS = 60 * 1000L
    private const val MGDL_PER_MMOLL = 18.0182f

    internal data class SmoothingMode(
        val smoothAllData: Boolean,
        val smoothingMinutes: Int,
        val collapseChunks: Boolean
    )

    data class Snapshot(
        val timeMillis: Long,
        val rate: Float,
        val sensorId: String?,
        val sensorGen: Int,
        val index: Int,
        val viewMode: Int,
        val source: String,
        val autoValue: Float,
        val rawValue: Float,
        val sharedDisplayValue: Float,
        val sharedMgdl: Int,
        val isMmol: Boolean,
        val displayValues: DisplayValues
    ) {
        val primaryValue: Float get() = displayValues.primaryValue
        val primaryStr: String get() = displayValues.primaryStr
        val speechPrimaryStr: String get() = DisplayValueResolver.formatForSpeech(primaryValue, isMmol)
        val secondaryStr: String? get() = displayValues.secondaryStr
        val tertiaryStr: String? get() = displayValues.tertiaryStr
        val fullFormatted: String get() = displayValues.fullFormatted
    }

    @JvmStatic
    @JvmOverloads
    fun resolveCurrent(
        maxAgeMillis: Long = Notify.glucosetimeout,
        preferredSensorId: String? = null,
        historyWindowMs: Long = DEFAULT_HISTORY_WINDOW_MS
    ): Snapshot? {
        return resolveCurrentInternal(
            maxAgeMillis = maxAgeMillis,
            preferredSensorId = preferredSensorId,
            historyWindowMs = historyWindowMs,
            smoothingMode = localSmoothingMode()
        )
    }

    @JvmStatic
    @JvmOverloads
    fun resolveCurrentForExchange(
        maxAgeMillis: Long = Notify.glucosetimeout,
        preferredSensorId: String? = null,
        historyWindowMs: Long = DEFAULT_HISTORY_WINDOW_MS
    ): Snapshot? {
        return resolveCurrentInternal(
            maxAgeMillis = maxAgeMillis,
            preferredSensorId = preferredSensorId,
            historyWindowMs = historyWindowMs,
            smoothingMode = exchangeSmoothingMode()
        )
    }

    private fun resolveCurrentInternal(
        maxAgeMillis: Long,
        preferredSensorId: String?,
        historyWindowMs: Long,
        smoothingMode: SmoothingMode
    ): Snapshot? {
        val resolvedSensorId = preferredSensorId ?: SensorIdentity.resolveMainSensor()
        val current = CurrentGlucoseSource.getFresh(maxAgeMillis, resolvedSensorId)
        val isMmol = Applic.unit == 1
        val now = System.currentTimeMillis()
        val liveHistoryWindowMs = historyWindowMs.coerceAtLeast(LIVE_CONTEXT_WINDOW_MS)
        val historyStart = when {
            current != null && current.timeMillis > 0L -> (current.timeMillis - liveHistoryWindowMs).coerceAtLeast(0L)
            else -> now - historyWindowMs
        }
        val recentPoints = try {
            NotificationHistorySource.getDisplayHistory(historyStart, isMmol, resolvedSensorId)
        } catch (_: Throwable) {
            emptyList()
        }
        val viewMode = resolveSensorViewMode(resolvedSensorId)
        return resolveSnapshot(
            current = current,
            recentPoints = recentPoints,
            historyStart = historyStart,
            viewMode = viewMode,
            isMmol = isMmol,
            smoothingMode = smoothingMode,
            sensorId = resolvedSensorId
        )
    }

    @JvmStatic
    @JvmOverloads
    fun resolveIncomingReading(
        liveNumericValue: Float,
        rate: Float,
        targetTimeMillis: Long,
        preferredSensorId: String? = null,
        sensorGen: Int = 0,
        index: Int = 0,
        source: String = "incoming",
        historyWindowMs: Long = DEFAULT_HISTORY_WINDOW_MS
    ): Snapshot? = resolveIncomingReading(
        reading = LiveReadingLanes.stock(liveNumericValue, Float.NaN),
        rate = rate,
        targetTimeMillis = targetTimeMillis,
        preferredSensorId = preferredSensorId,
        sensorGen = sensorGen,
        index = index,
        source = source,
        historyWindowMs = historyWindowMs
    )

    /**
     * A reading that has not necessarily reached history yet, resolved exactly as
     * [resolveCurrent] would resolve it once it is the current one. The two used
     * to be separate copies, and the alert engine switches between them from one
     * tick to the next: any difference fires an alert and clears it again.
     * [preferIncomingSample] prevents a neighboring history timestamp from replacing
     * a known live sample while its storage write is pending. Earlier samples still
     * participate in configured smoothing and trend estimation, but chunk collapsing
     * cannot remove the incoming sample from live publication.
     */
    @JvmStatic
    @JvmOverloads
    fun resolveIncomingReading(
        reading: LiveReadingLanes,
        rate: Float,
        targetTimeMillis: Long,
        preferredSensorId: String? = null,
        sensorGen: Int = 0,
        index: Int = 0,
        source: String = "incoming",
        historyWindowMs: Long = DEFAULT_HISTORY_WINDOW_MS,
        preferIncomingSample: Boolean = false
    ): Snapshot? {
        if (!reading.hasValue || targetTimeMillis <= 0L) {
            return null
        }
        val resolvedSensorId = preferredSensorId ?: SensorIdentity.resolveMainSensor()
        val isMmol = Applic.unit == 1
        val liveHistoryWindowMs = historyWindowMs.coerceAtLeast(LIVE_CONTEXT_WINDOW_MS)
        val historyStart = (targetTimeMillis - liveHistoryWindowMs).coerceAtLeast(0L)
        val recentPoints = try {
            NotificationHistorySource.getDisplayHistory(historyStart, isMmol, resolvedSensorId)
        } catch (_: Throwable) {
            emptyList()
        }
        val current = CurrentGlucoseSource.Snapshot.of(
            reading = reading,
            timeMillis = targetTimeMillis,
            valueText = "",
            rate = rate,
            sensorId = resolvedSensorId,
            sensorGen = sensorGen,
            index = index,
            source = source
        )
        return resolveSnapshot(
            current = current,
            recentPoints = recentPoints,
            historyStart = historyStart,
            viewMode = resolveSensorViewMode(resolvedSensorId),
            isMmol = isMmol,
            smoothingMode = localSmoothingMode(),
            sensorId = resolvedSensorId,
            preferIncomingSample = preferIncomingSample
        )
    }

    /** The part of both resolutions that touches no storage. */
    internal fun resolveSnapshot(
        current: CurrentGlucoseSource.Snapshot?,
        recentPoints: List<GlucosePoint>,
        historyStart: Long,
        viewMode: Int,
        isMmol: Boolean,
        smoothingMode: SmoothingMode,
        sensorId: String?,
        preferIncomingSample: Boolean = false,
        nowMillis: Long = System.currentTimeMillis()
    ): Snapshot? {
        val processedPoints = prepareRecentPointsForCurrent(
            recentPoints = recentPoints,
            current = current,
            historyStart = historyStart,
            viewMode = viewMode,
            smoothAllData = smoothingMode.smoothAllData,
            smoothingMinutes = smoothingMode.smoothingMinutes,
            collapseChunks = smoothingMode.collapseChunks,
            nowMillis = nowMillis,
            preferIncomingSample = preferIncomingSample
        )
        val targetTime = exchangeTargetTimeMillis(
            collapseChunks = smoothingMode.collapseChunks && !preferIncomingSample,
            processedPoints = processedPoints,
            liveTimeMillis = current?.timeMillis
        )
        val initialSnapshot = resolveFromLive(
            liveValueText = current?.valueText,
            liveNumericValue = current?.let { liveLaneValue(it, viewMode) } ?: Float.NaN,
            liveCalibratedValue = current?.calibratedNumericValue ?: Float.NaN,
            rate = current?.rate ?: Float.NaN,
            targetTimeMillis = targetTime,
            sensorId = sensorId,
            sensorGen = current?.sensorGen ?: 0,
            index = current?.index ?: 0,
            source = current?.source ?: if (processedPoints.isNotEmpty()) "history" else "none",
            // A known live sample must not borrow a neighboring minute's glucose
            // (or its raw lane). Keep the full series for smoothing and trends.
            recentPoints = if (preferIncomingSample) {
                processedPoints.filter { it.timestamp == targetTime }
            } else processedPoints,
            viewMode = viewMode,
            isMmol = isMmol
        ) ?: return null
        val trendPoints = DisplayTrendSource.augmentHistory(
            historyPoints = processedPoints,
            current = initialSnapshot,
            activeSensorSerial = sensorId,
            startTimeMs = historyStart
        )
        val canonicalRate = DisplayTrendSource.resolveArrowRate(
            recentPoints = trendPoints,
            current = initialSnapshot,
            viewMode = viewMode,
            isMmol = isMmol,
            fallbackRate = current?.rate ?: Float.NaN
        )
        return initialSnapshot.copy(rate = canonicalRate)
    }

    /** The live value for the lane this view mode shows first. */
    private fun liveLaneValue(current: CurrentGlucoseSource.Snapshot, viewMode: Int): Float {
        val auto = current.numericValue.takeIf { it.isFinite() && it > 0.1f }
        val raw = current.rawNumericValue.takeIf { it.isFinite() && it > 0.1f }
        return (if (isRawPrimary(viewMode)) raw ?: auto else auto ?: raw) ?: Float.NaN
    }

    /**
     * The timestamp a snapshot is resolved for. With collapse on, the series has been cut
     * down to the last point of each *completed* bucket, so the newest surviving point is up
     * to one interval behind the live reading and the snapshot carries that point's time.
     * With it off the live reading's own time wins.
     */
    internal fun exchangeTargetTimeMillis(
        collapseChunks: Boolean,
        processedPoints: List<GlucosePoint>,
        liveTimeMillis: Long?
    ): Long = if (collapseChunks) {
        processedPoints.lastOrNull()?.timestamp ?: liveTimeMillis ?: 0L
    } else {
        liveTimeMillis ?: processedPoints.lastOrNull()?.timestamp ?: 0L
    }

    internal fun prepareRecentPointsForCurrent(
        recentPoints: List<GlucosePoint>,
        current: CurrentGlucoseSource.Snapshot?,
        historyStart: Long,
        viewMode: Int,
        smoothAllData: Boolean,
        smoothingMinutes: Int,
        collapseChunks: Boolean,
        nowMillis: Long = System.currentTimeMillis(),
        preferIncomingSample: Boolean = false
    ): List<GlucosePoint> {
        val pointsWithCurrent = mergeLivePoint(recentPoints, current, historyStart, viewMode, preferIncomingSample)
        return if (smoothAllData) {
            DataSmoothing.smoothNativePoints(
                pointsWithCurrent,
                smoothingMinutes,
                // Chunk collapsing is presentation thinning. A known incoming sample
                // must remain available at its own timestamp for live publication.
                collapseChunks && !preferIncomingSample,
                nowMillis
            )
        } else {
            pointsWithCurrent
        }
    }

    private fun localSmoothingMode(): SmoothingMode {
        val smoothingMinutes = DataSmoothing.getMinutes(Applic.app)
        val smoothAllData = DataSmoothing.shouldSmoothLocalData(Applic.app)
        return SmoothingMode(
            smoothAllData = smoothAllData,
            smoothingMinutes = smoothingMinutes,
            collapseChunks = smoothAllData && DataSmoothing.collapseChunks(Applic.app)
        )
    }

    private fun exchangeSmoothingMode(): SmoothingMode {
        val context = Applic.app
        return exchangeSmoothingMode(
            smoothingMinutes = DataSmoothing.getMinutes(context),
            graphOnly = DataSmoothing.isGraphOnly(context),
            exchangeOutputsOnly = DataSmoothing.smoothOnlyExchangeOutputs(context),
            collapseChunks = DataSmoothing.collapseChunks(context)
        )
    }

    /**
     * An exchange snapshot is smoothed per the exchange settings but never collapsed: it is the
     * newest reading under its own timestamp. "Collapse into chunks" is applied as a rate limit
     * on the way out ([DataSmoothing.exchangeThrottleIntervalMinutes]), not by resolving the
     * snapshot at the last point of a completed chunk. Every exchange destination — including the
     * xDrip/xInfuus loop feed — shares this one mode; there is no separate loop-feed variant.
     */
    internal fun exchangeSmoothingMode(
        smoothingMinutes: Int,
        graphOnly: Boolean,
        exchangeOutputsOnly: Boolean,
        collapseChunks: Boolean
    ): SmoothingMode = SmoothingMode(
        smoothAllData = DataSmoothing.shouldSmoothExchangeOutputs(
            smoothingMinutes, graphOnly, exchangeOutputsOnly, collapseChunks
        ),
        smoothingMinutes = smoothingMinutes,
        collapseChunks = false
    )

    @JvmStatic
    fun getFreshNotGlucose(maxAgeMillis: Long): notGlucose? {
        val snapshot = resolveCurrent(maxAgeMillis) ?: return null
        return notGlucose(snapshot.timeMillis, snapshot.primaryStr, snapshot.rate, snapshot.sensorGen)
    }

    @JvmStatic
    fun getFreshNotGlucose(): notGlucose? = getFreshNotGlucose(Notify.glucosetimeout)

    @JvmStatic
    fun resolveFromLive(
        liveValueText: String?,
        liveNumericValue: Float,
        rate: Float,
        targetTimeMillis: Long,
        sensorId: String?,
        sensorGen: Int,
        index: Int,
        source: String,
        recentPoints: List<GlucosePoint>,
        viewMode: Int,
        isMmol: Boolean
    ): Snapshot? =
        resolveFromLive(
            liveValueText = liveValueText,
            liveNumericValue = liveNumericValue,
            liveCalibratedValue = Float.NaN,
            rate = rate,
            targetTimeMillis = targetTimeMillis,
            sensorId = sensorId,
            sensorGen = sensorGen,
            index = index,
            source = source,
            recentPoints = recentPoints,
            viewMode = viewMode,
            isMmol = isMmol
        )

    @JvmStatic
    fun resolveFromLive(
        liveValueText: String?,
        liveNumericValue: Float,
        liveCalibratedValue: Float = Float.NaN,
        rate: Float,
        targetTimeMillis: Long,
        sensorId: String?,
        sensorGen: Int,
        index: Int,
        source: String,
        recentPoints: List<GlucosePoint>,
        viewMode: Int,
        isMmol: Boolean
    ): Snapshot? {
        val exactMatch = findExactPoint(recentPoints, targetTimeMillis)
        val match = exactMatch ?: recentPoints.lastOrNull()
        val isRawMode = isRawPrimary(viewMode)
        val liveValue = liveNumericValue.takeIf { it.isFinite() && it > 0.1f }

        var autoValue = match?.value?.takeIf { it.isFinite() && it > 0.1f } ?: Float.NaN
        var rawValue = match?.rawValue?.takeIf { it.isFinite() && it > 0.1f } ?: Float.NaN

        val canUseLiveAsLaneFallback = exactMatch == null

        if (canUseLiveAsLaneFallback && !autoValue.isFinite() && !isRawMode && liveValue != null) {
            autoValue = liveValue
        }
        if (canUseLiveAsLaneFallback && !rawValue.isFinite() && isRawMode && liveValue != null) {
            rawValue = liveValue
        }

        val importedCalibratedValue = liveCalibratedValue
            .takeIf { it.isFinite() && it > 0.1f }
        val displayValues = exactMatch?.let { point ->
            val hideInitialWhenCalibrated = shouldHideInitialWhenCalibrated()
            if (importedCalibratedValue != null) {
                DisplayValueResolver.resolve(
                    autoValue = point.value,
                    rawValue = point.rawValue,
                    viewMode = viewMode,
                    isMmol = isMmol,
                    unitLabel = "",
                    calibratedValue = importedCalibratedValue,
                    hideInitialWhenCalibrated = hideInitialWhenCalibrated
                )
            } else {
                resolveDisplayValuesForPoint(
                    point = point,
                    viewMode = viewMode,
                    isMmol = isMmol,
                    sensorId = sensorId
                )
            }
        } ?: run {
            val hideInitialWhenCalibrated = shouldHideInitialWhenCalibrated()
            val calibratedValue = importedCalibratedValue ?: resolveCalibratedValue(
                liveValue = liveValue,
                autoValue = autoValue,
                rawValue = rawValue,
                sensorId = sensorId,
                viewMode = viewMode,
                targetTimeMillis = targetTimeMillis,
                allowLiveFallback = exactMatch == null
            )

            DisplayValueResolver.resolve(
                autoValue = autoValue,
                rawValue = rawValue,
                viewMode = viewMode,
                isMmol = isMmol,
                unitLabel = "",
                calibratedValue = calibratedValue,
                hideInitialWhenCalibrated = calibratedValue != null && hideInitialWhenCalibrated
            )
        }

        val resolvedTime = when {
            targetTimeMillis > 0L -> targetTimeMillis
            match != null -> match.timestamp
            else -> 0L
        }
        if (resolvedTime <= 0L || !displayValues.primaryValue.isFinite() || displayValues.primaryValue <= 0f) {
            return null
        }

        val sharedMgdl = resolveSharedMgdl(
            sensorId = sensorId,
            autoValue = autoValue,
            rawValue = rawValue,
            calibratedValue = liveCalibratedValue,
            targetTimeMillis = resolvedTime,
            isMmol = isMmol
        )
        val sharedDisplayValue = if (sharedMgdl > 0) {
            if (isMmol) sharedMgdl / MGDL_PER_MMOLL else sharedMgdl.toFloat()
        } else {
            0f
        }

        return Snapshot(
            timeMillis = resolvedTime,
            rate = rate,
            sensorId = sensorId,
            sensorGen = sensorGen,
            index = index,
            viewMode = viewMode,
            source = source,
            autoValue = autoValue,
            rawValue = rawValue,
            sharedDisplayValue = sharedDisplayValue,
            sharedMgdl = sharedMgdl,
            isMmol = isMmol,
            displayValues = displayValues
        )
    }

    private fun resolveCalibratedValue(
        liveValue: Float?,
        autoValue: Float,
        rawValue: Float,
        sensorId: String?,
        viewMode: Int,
        targetTimeMillis: Long,
        allowLiveFallback: Boolean
    ): Float? {
        val isRawMode = isRawPrimary(viewMode)
        if (!shouldApplyDisplayCalibration(isRawMode, sensorId)) {
            if (allowLiveFallback && liveValue != null && shouldApplyDisplayCalibration(isRawMode, null)) {
                val fallbackCalibrated = CalibrationAccess.getCalibratedValue(
                    liveValue,
                    targetTimeMillis,
                    isRawMode,
                    false,
                    null
                )
                return fallbackCalibrated.takeIf { it.isFinite() && it > 0.1f } ?: liveValue
            }
            return null
        }
        val baseValue = (if (isRawMode) rawValue else autoValue).takeIf { it.isFinite() && it > 0.1f }
            ?: autoValue.takeIf { it.isFinite() && it > 0.1f }
            ?: rawValue.takeIf { it.isFinite() && it > 0.1f }
            ?: liveValue?.takeIf { allowLiveFallback && it.isFinite() && it > 0.1f }
            ?: return null

        val calibratedValue = CalibrationAccess.getCalibratedValue(
            baseValue,
            targetTimeMillis,
            isRawMode,
            false,
            sensorId
        )
        return calibratedValue.takeIf { it.isFinite() && it > 0.1f }
            ?: liveValue?.takeIf { allowLiveFallback && it.isFinite() && it > 0.1f }
    }

    private fun resolveDisplayValuesForPoint(
        point: GlucosePoint,
        viewMode: Int,
        isMmol: Boolean,
        sensorId: String?
    ): DisplayValues {
        val isRawMode = isRawPrimary(viewMode)
        val calibratedValue = if (shouldApplyDisplayCalibration(isRawMode, sensorId)) {
            val baseValue = if (isRawMode) point.rawValue else point.value
            if (baseValue.isFinite() && baseValue > 0.1f) {
                CalibrationAccess.getCalibratedValue(
                    baseValue,
                    point.timestamp,
                    isRawMode,
                    false,
                    sensorId
                ).takeIf { it.isFinite() && it > 0.1f }
            } else {
                null
            }
        } else {
            null
        }
        return DisplayValueResolver.resolve(
            autoValue = point.value,
            rawValue = point.rawValue,
            viewMode = viewMode,
            isMmol = isMmol,
            unitLabel = "",
            calibratedValue = calibratedValue,
            hideInitialWhenCalibrated = calibratedValue != null && shouldHideInitialWhenCalibrated()
        )
    }

    private fun mergeLivePoint(
        points: List<GlucosePoint>,
        current: CurrentGlucoseSource.Snapshot?,
        historyStart: Long,
        viewMode: Int,
        preferIncomingSample: Boolean
    ): List<GlucosePoint> {
        if (current == null || current.timeMillis < historyStart) {
            return points
        }
        val latestHistory = points.lastOrNull()
        if (latestHistory != null &&
            kotlin.math.abs(latestHistory.timestamp - current.timeMillis) <= MATCH_WINDOW_MS &&
            (!preferIncomingSample || latestHistory.timestamp == current.timeMillis) &&
            hasUsableDisplayLane(latestHistory, viewMode)
        ) {
            return points
        }
        val liveAuto = current.numericValue.takeIf { it.isFinite() && it > 0.1f } ?: Float.NaN
        val liveRawDirect = current.rawNumericValue.takeIf { it.isFinite() && it > 0.1f }
        // liveRawIsFallback signals to preferRicherLivePoint that, on an exact
        // timestamp merge, history's raw should win — the candidate has no real
        // raw to contribute. It is intentionally NOT baked into the candidate's
        // own rawValue: doing so would put the auto value into the raw lane,
        // and a non-exact insertion (Sibionics live arriving offset from history)
        // would surface that auto-as-raw lie in raw-primary modes (1, 3) and
        // shadow the real history raw via findExactPoint.
        val liveRawIsFallback = liveRawDirect == null && isRawPrimary(viewMode) &&
            liveAuto.isFinite() && liveAuto > 0.1f
        if ((!liveAuto.isFinite() || liveAuto <= 0.1f) && liveRawDirect == null) {
            return points
        }

        val candidate = GlucosePoint(
            current.timeMillis,
            liveAuto.takeIf { it.isFinite() && it > 0.1f } ?: 0f,
            liveRawDirect ?: 0f
        )
        if (points.isEmpty()) {
            return listOf(candidate)
        }

        val merged = ArrayList<GlucosePoint>(points.size + 1)
        var inserted = false
        points.forEach { point ->
            if (!inserted && candidate.timestamp <= point.timestamp) {
                if (candidate.timestamp == point.timestamp) {
                    merged.add(preferRicherLivePoint(point, candidate, liveRawIsFallback))
                    inserted = true
                    return@forEach
                }
                merged.add(candidate)
                inserted = true
            }
            merged.add(point)
        }
        if (!inserted) {
            merged.add(candidate)
        }
        return merged
    }

    private fun preferRicherLivePoint(
        historyPoint: GlucosePoint,
        livePoint: GlucosePoint,
        liveRawIsFallback: Boolean
    ): GlucosePoint {
        val mergedValue = livePoint.value.takeIf { it.isFinite() && it > 0.1f }
            ?: historyPoint.value
        val historyRawIsValid = historyPoint.rawValue.isFinite() && historyPoint.rawValue > 0.1f
        val mergedRawValue = if (liveRawIsFallback && historyRawIsValid) {
            historyPoint.rawValue
        } else {
            livePoint.rawValue.takeIf { it.isFinite() && it > 0.1f }
                ?: historyPoint.rawValue
        }
        val merged = GlucosePoint(historyPoint.timestamp, mergedValue, mergedRawValue)
        merged.color = historyPoint.color
        return merged
    }

    private fun hasUsableDisplayLane(point: GlucosePoint, viewMode: Int): Boolean {
        val autoValid = point.value.isFinite() && point.value > 0.1f
        val rawValid = point.rawValue.isFinite() && point.rawValue > 0.1f
        return when (viewMode) {
            2, 3 -> autoValid && rawValid
            else -> if (isRawPrimary(viewMode)) rawValid || autoValid else autoValid || rawValid
        }
    }

    private fun shouldHideInitialWhenCalibrated(): Boolean {
        return CalibrationAccess.shouldHideInitialWhenCalibrated()
    }

    private fun shouldApplyDisplayCalibration(isRawMode: Boolean, sensorId: String?): Boolean {
        return !CalibrationAccess.shouldOverwriteSensorValues() &&
            CalibrationAccess.hasActiveCalibration(isRawMode, sensorId)
    }

    private fun isRawPrimary(viewMode: Int): Boolean = viewMode == 1 || viewMode == 3

    private fun matchesSensor(candidate: String?, expected: String?): Boolean {
        if (expected.isNullOrBlank()) {
            return true
        }
        return SensorIdentity.matches(candidate, expected)
    }

    private fun findExactPoint(points: List<GlucosePoint>, targetTimeMillis: Long): GlucosePoint? {
        if (points.isEmpty()) {
            return null
        }
        if (targetTimeMillis <= 0L) {
            return points.lastOrNull { it.rawValue.isFinite() && it.rawValue > 0.1f }
                ?: points.lastOrNull()
        }
        // Prefer the latest point in the match window that carries a valid raw
        // lane. mergeLivePoint splices a synthetic live candidate at current.time
        // with rawValue=0 for sources that don't expose live raw (native Sibionics).
        // Without this preference, that candidate would shadow a slightly earlier
        // real history point that has both lanes — silently dropping the secondary
        // lane in every snapshot consumer (notification, widgets, AOD, overlays).
        val withinWindow = points.filter {
            kotlin.math.abs(it.timestamp - targetTimeMillis) <= MATCH_WINDOW_MS
        }
        if (withinWindow.isEmpty()) return null
        return withinWindow.lastOrNull { it.rawValue.isFinite() && it.rawValue > 0.1f }
            ?: withinWindow.last()
    }

    /**
     * Canonical per-sensor view mode (auto/raw/auto+raw/raw+auto) resolution,
     * shared by every multi-sensor surface so the dashboard, notification and
     * hero card always agree.
     */
    @JvmStatic
    fun resolveViewModeForSensor(sensorName: String?): Int = resolveSensorViewMode(sensorName)

    /**
     * Updates the same resolved driver identity used by [resolveViewModeForSensor].
     *
     * The persisted store is always written, not just the driver and native
     * copies. On a watch following a phone-held sensor there is no local driver
     * and often no native data pointer either, so every earlier path returned
     * false and the mode control did nothing at all.
     */
    @JvmStatic
    fun setViewModeForSensor(sensorName: String?, mode: Int): Boolean {
        val normalized = tk.glucodata.drivers.ManagedSensorViewModeStore.sanitize(mode)
        if (sensorName.isNullOrEmpty()) {
            return false
        }
        tk.glucodata.drivers.ManagedSensorViewModeStore.write(Applic.app, sensorName, normalized)
        tk.glucodata.drivers.ManagedSensorRuntime.resolveDriver(sensorName)?.let { driver ->
            driver.viewMode = normalized
            val ptr = driver.getManagedUiSnapshot()?.dataptr ?: 0L
            if (ptr != 0L) {
                runCatching { Natives.setViewMode(ptr, normalized) }
            }
            return true
        }
        if (SensorIdentity.hasNativeSensorBacking(sensorName)) {
            runCatching {
                val dataptr = Natives.getdataptr(sensorName)
                if (dataptr != 0L) {
                    Natives.setViewMode(dataptr, normalized)
                }
            }
        }
        return true
    }

    private fun resolveSensorViewMode(sensorName: String?): Int {
        if (sensorName.isNullOrEmpty()) {
            return 0
        }
        tk.glucodata.drivers.ManagedSensorRuntime.resolveUiSnapshot(sensorName, sensorName)
            ?.let { return it.viewMode }
        // No live driver: the persisted choice outranks native. Native only
        // carries a mode for sensors this device streams itself, so on a watch
        // following a phone-held sensor it reports a permanent 0 and used to
        // shadow whatever the user picked. Every writer keeps the store current.
        tk.glucodata.drivers.ManagedSensorViewModeStore.readOrNull(Applic.app, sensorName)
            ?.let { return it }
        if (!SensorIdentity.hasNativeSensorBacking(sensorName)) {
            return 0
        }
        return try {
            val snapshot = Natives.getSensorUiSnapshot(sensorName)
            if (snapshot != null && snapshot.size >= 2) snapshot[1].toInt() else 0
        } catch (_: Throwable) {
            0
        }
    }

    private fun resolveSharedMgdl(
        sensorId: String?,
        autoValue: Float,
        rawValue: Float,
        calibratedValue: Float,
        targetTimeMillis: Long,
        isMmol: Boolean
    ): Int {
        val importedCalibratedMgdl = displayToMgdl(calibratedValue, isMmol)
        if (importedCalibratedMgdl > 0) {
            return importedCalibratedMgdl
        }

        val calibratedAuto = calibrateForShare(sensorId, autoValue, targetTimeMillis, false)
        if (calibratedAuto > 0f) {
            return displayToMgdl(calibratedAuto, isMmol)
        }

        val calibratedRaw = calibrateForShare(sensorId, rawValue, targetTimeMillis, true)
        if (calibratedRaw > 0f) {
            return displayToMgdl(calibratedRaw, isMmol)
        }

        val nativeAutoMgdl = resolveNativeAutoMgdl(sensorId, isMmol)
        if (nativeAutoMgdl > 0) {
            return nativeAutoMgdl
        }

        val autoMgdl = displayToMgdl(autoValue, isMmol)
        if (autoMgdl > 0) {
            return autoMgdl
        }

        val rawMgdl = displayToMgdl(rawValue, isMmol)
        return rawMgdl.coerceAtLeast(0)
    }

    private fun calibrateForShare(
        sensorId: String?,
        baseValue: Float,
        targetTimeMillis: Long,
        isRawMode: Boolean
    ): Float {
        if (!baseValue.isFinite() || baseValue <= 0f) {
            return 0f
        }
        if (!shouldApplyDisplayCalibration(isRawMode, sensorId)) {
            return 0f
        }
        val calibrated = CalibrationAccess.getCalibratedValue(
            baseValue,
            targetTimeMillis,
            isRawMode,
            false,
            sensorId
        )
        return calibrated.takeIf { it.isFinite() && it > 0f } ?: 0f
    }

    private fun resolveNativeAutoMgdl(sensorId: String?, isMmol: Boolean): Int {
        if (!SensorIdentity.hasNativeSensorBacking(sensorId)) {
            return 0
        }
        val latest = try {
            Natives.lastglucose()
        } catch (_: Throwable) {
            null
        } ?: return 0
        if (!SensorIdentity.matches(latest.sensorid, sensorId)) {
            return 0
        }
        val latestValue = GlucoseValueParser.parseFirst(latest.value)
            ?.takeIf { it.isFinite() && it > 0f }
            ?: return 0
        return displayToMgdl(latestValue, isMmol)
    }

    private fun displayToMgdl(value: Float, isMmol: Boolean): Int {
        if (!value.isFinite() || value <= 0f) {
            return 0
        }
        return (if (isMmol) value * MGDL_PER_MMOLL else value).roundToInt()
    }
}
