package tk.glucodata

import kotlin.math.roundToInt
import tk.glucodata.ui.DisplayValueResolver
import tk.glucodata.ui.DisplayValues

object CurrentDisplaySource {
    private const val DEFAULT_HISTORY_WINDOW_MS = DisplayTrendSource.TREND_WINDOW_MS
    private const val LIVE_CONTEXT_WINDOW_MS = 2 * 60 * 1000L
    private const val MATCH_WINDOW_MS = 60 * 1000L
    private const val MGDL_PER_MMOLL = 18.0182f

    private data class SmoothingMode(
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
        val displayValues: DisplayValues
    ) {
        val primaryValue: Float get() = displayValues.primaryValue
        val primaryStr: String get() = displayValues.primaryStr
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
        val processedPoints = prepareRecentPointsForCurrent(
            recentPoints = recentPoints,
            current = current,
            historyStart = historyStart,
            viewMode = viewMode,
            smoothAllData = smoothingMode.smoothAllData,
            smoothingMinutes = smoothingMode.smoothingMinutes,
            collapseChunks = smoothingMode.collapseChunks
        )
        val initialSnapshot = resolveFromLive(
            liveValueText = current?.valueText,
            liveNumericValue = current?.numericValue ?: Float.NaN,
            rate = current?.rate ?: Float.NaN,
            targetTimeMillis = if (smoothingMode.collapseChunks) {
                processedPoints.lastOrNull()?.timestamp ?: current?.timeMillis ?: 0L
            } else {
                current?.timeMillis ?: processedPoints.lastOrNull()?.timestamp ?: 0L
            },
            sensorId = resolvedSensorId,
            sensorGen = current?.sensorGen ?: 0,
            index = current?.index ?: 0,
            source = current?.source ?: if (processedPoints.isNotEmpty()) "history" else "none",
            recentPoints = processedPoints,
            viewMode = viewMode,
            isMmol = isMmol
        )
        if (initialSnapshot == null) {
            return null
        }
        val trendPoints = DisplayTrendSource.augmentHistory(
            historyPoints = processedPoints,
            current = initialSnapshot,
            activeSensorSerial = resolvedSensorId,
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
    ): Snapshot? {
        if (!liveNumericValue.isFinite() || liveNumericValue <= 0.1f || targetTimeMillis <= 0L) {
            return null
        }
        val resolvedSensorId = preferredSensorId ?: SensorIdentity.resolveMainSensor()
        val isMmol = Applic.unit == 1
        val smoothingMinutes = DataSmoothing.getMinutes(Applic.app)
        val smoothAllData = DataSmoothing.shouldSmoothLocalData(Applic.app)
        val collapseChunks = smoothAllData && DataSmoothing.collapseChunks(Applic.app)
        val liveHistoryWindowMs = historyWindowMs.coerceAtLeast(LIVE_CONTEXT_WINDOW_MS)
        val historyStart = (targetTimeMillis - liveHistoryWindowMs).coerceAtLeast(0L)
        val recentPoints = try {
            NotificationHistorySource.getDisplayHistory(historyStart, isMmol, resolvedSensorId)
        } catch (_: Throwable) {
            emptyList()
        }
        val viewMode = resolveSensorViewMode(resolvedSensorId)
        val current = CurrentGlucoseSource.Snapshot(
            timeMillis = targetTimeMillis,
            valueText = "",
            numericValue = liveNumericValue,
            rawNumericValue = Float.NaN,
            rate = rate,
            sensorId = resolvedSensorId,
            sensorGen = sensorGen,
            index = index,
            source = source
        )
        val processedPoints = prepareRecentPointsForCurrent(
            recentPoints = recentPoints,
            current = current,
            historyStart = historyStart,
            viewMode = viewMode,
            smoothAllData = smoothAllData,
            smoothingMinutes = smoothingMinutes,
            collapseChunks = collapseChunks
        )
        val initialSnapshot = resolveFromLive(
            liveValueText = null,
            liveNumericValue = liveNumericValue,
            rate = rate,
            targetTimeMillis = if (collapseChunks) {
                processedPoints.lastOrNull()?.timestamp ?: targetTimeMillis
            } else {
                targetTimeMillis
            },
            sensorId = resolvedSensorId,
            sensorGen = sensorGen,
            index = index,
            source = source,
            recentPoints = processedPoints,
            viewMode = viewMode,
            isMmol = isMmol
        ) ?: return null
        val trendPoints = DisplayTrendSource.augmentHistory(
            historyPoints = processedPoints,
            current = initialSnapshot,
            activeSensorSerial = resolvedSensorId,
            startTimeMs = historyStart
        )
        val canonicalRate = DisplayTrendSource.resolveArrowRate(
            recentPoints = trendPoints,
            current = initialSnapshot,
            viewMode = viewMode,
            isMmol = isMmol,
            fallbackRate = rate
        )
        return initialSnapshot.copy(rate = canonicalRate)
    }

    internal fun prepareRecentPointsForCurrent(
        recentPoints: List<GlucosePoint>,
        current: CurrentGlucoseSource.Snapshot?,
        historyStart: Long,
        viewMode: Int,
        smoothAllData: Boolean,
        smoothingMinutes: Int,
        collapseChunks: Boolean
    ): List<GlucosePoint> {
        val pointsWithCurrent = mergeLivePoint(recentPoints, current, historyStart, viewMode)
        return if (smoothAllData) {
            DataSmoothing.smoothNativePoints(
                pointsWithCurrent,
                smoothingMinutes,
                collapseChunks
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
        val smoothingMinutes = DataSmoothing.getMinutes(Applic.app)
        val smoothExchangeData = DataSmoothing.shouldSmoothExchangeOutputs(Applic.app)
        return SmoothingMode(
            smoothAllData = smoothExchangeData,
            smoothingMinutes = smoothingMinutes,
            collapseChunks = smoothExchangeData && DataSmoothing.collapseChunks(Applic.app)
        )
    }

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

        val displayValues = exactMatch?.let {
            resolveDisplayValuesForPoint(
                point = it,
                viewMode = viewMode,
                isMmol = isMmol,
                sensorId = sensorId
            )
        } ?: run {
            val hideInitialWhenCalibrated = shouldHideInitialWhenCalibrated()
            val calibratedValue = resolveCalibratedValue(
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
        viewMode: Int
    ): List<GlucosePoint> {
        if (current == null || current.timeMillis < historyStart) {
            return points
        }
        val latestHistory = points.lastOrNull()
        if (latestHistory != null &&
            kotlin.math.abs(latestHistory.timestamp - current.timeMillis) <= MATCH_WINDOW_MS &&
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

    private fun resolveSensorViewMode(sensorName: String?): Int {
        if (sensorName.isNullOrEmpty()) {
            return 0
        }
        tk.glucodata.drivers.ManagedSensorRuntime.resolveUiSnapshot(sensorName, sensorName)
            ?.let { return it.viewMode }
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
        targetTimeMillis: Long,
        isMmol: Boolean
    ): Int {
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
