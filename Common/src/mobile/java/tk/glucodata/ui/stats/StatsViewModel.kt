package tk.glucodata.ui.stats

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tk.glucodata.Applic
import tk.glucodata.Natives
import tk.glucodata.R
import tk.glucodata.SensorIdentity
import tk.glucodata.UiRefreshBus
import tk.glucodata.data.HistoryRepository
import tk.glucodata.data.calibration.CalibrationManager
import tk.glucodata.drivers.ManagedSensorRuntime
import tk.glucodata.ui.GlucosePoint
import tk.glucodata.ui.DisplayValueResolver
import tk.glucodata.ui.util.GlucoseFormatter
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sign
import kotlin.math.sqrt

class StatsViewModel : ViewModel() {
    private val tag = "StatsViewModel"
    private val historyRepository = HistoryRepository()

    private data class StatsHistorySignature(
        val size: Int,
        val firstTimestamp: Long,
        val lastTimestamp: Long,
        val contentHash: Long
    )

    private data class StatsHistoryEdgeSignature(
        val size: Int,
        val firstTimestamp: Long,
        val middleTimestamp: Long,
        val lastTimestamp: Long,
        val firstValueBits: Int,
        val middleValueBits: Int,
        val lastValueBits: Int
    )

    private data class StatsDisplayHistoryCacheKey(
        val historySignature: StatsHistorySignature,
        val viewMode: Int,
        val unit: GlucoseUnit,
        val calibrationRevision: Long,
        val activeSerial: String?
    )

    private data class StatsRangeProjectionCacheKey(
        val historySignature: StatsHistorySignature,
        val viewMode: Int,
        val unit: GlucoseUnit,
        val calibrationRevision: Long,
        val activeSerial: String?,
        val rangeStartMillis: Long,
        val rangeEndMillis: Long,
        val lowMgDl: Float,
        val highMgDl: Float,
        val veryLowMgDl: Float,
        val veryHighMgDl: Float
    )

    private data class StatsRangeProjection(
        val filteredHistory: List<GlucosePoint>,
        val summary: StatsSummary
    )

    private val _selectedRange = MutableStateFlow<StatsTimeRange?>(StatsTimeRange.DAY_1)
    val selectedRange: StateFlow<StatsTimeRange?> = _selectedRange.asStateFlow()
    private val _customRange = MutableStateFlow<StatsDateRange?>(null)
    private val _availableRange = MutableStateFlow<StatsDateRange?>(null)

    private val _unit = MutableStateFlow(GlucoseUnit.MGDL)
    private val _targets = MutableStateFlow(StatsTargets())
    private val _viewMode = MutableStateFlow(0)
    private val _calibrationRevision = MutableStateFlow(CalibrationManager.getRevision())
    private val _isLoading = MutableStateFlow(true)
    private val _hasSensor = MutableStateFlow(true)
    private val _historyPoints = MutableStateFlow<List<GlucosePoint>>(emptyList())
    private val _temperaturePoints = MutableStateFlow<List<TemperaturePoint>>(emptyList())

    private var historyJob: Job? = null
    private var activeSerial: String? = null
    private var historyWindowStartMs: Long = Long.MAX_VALUE
    private var cachedTemperatureSerial: String? = null
    private var cachedTemperaturePoints: List<TemperaturePoint> = emptyList()
    private var lastTemperatureRefreshMs: Long = 0L
    @Volatile private var statsDisplayHistoryCacheKey: StatsDisplayHistoryCacheKey? = null
    @Volatile private var statsDisplayHistoryCacheValue: List<GlucosePoint> = emptyList()
    @Volatile private var statsRangeProjectionCacheKey: StatsRangeProjectionCacheKey? = null
    @Volatile private var statsRangeProjectionCacheValue = StatsRangeProjection(
        filteredHistory = emptyList(),
        summary = StatsSummary()
    )

    private val baseState = combine(
        _selectedRange,
        _customRange,
        _availableRange,
        _unit,
        _targets,
        _viewMode,
        _calibrationRevision,
        _isLoading,
        _hasSensor
    ) { values ->
        BaseInput(
            range = values[0] as StatsTimeRange?,
            customRange = values[1] as StatsDateRange?,
            availableRange = values[2] as StatsDateRange?,
            unit = values[3] as GlucoseUnit,
            targets = values[4] as StatsTargets,
            viewMode = values[5] as Int,
            calibrationRevision = values[6] as Long,
            isLoading = values[7] as Boolean,
            hasSensor = values[8] as Boolean
        )
    }

    val uiState: StateFlow<StatsUiState> = combine(
        baseState,
        _historyPoints,
        _temperaturePoints
    ) { base, history, temperature ->
        UiInput(
            range = base.range,
            customRange = base.customRange,
            availableRange = base.availableRange,
            unit = base.unit,
            targets = base.targets,
            viewMode = base.viewMode,
            calibrationRevision = base.calibrationRevision,
            isLoading = base.isLoading,
            hasSensor = base.hasSensor,
            historyPoints = history,
            temperaturePoints = temperature
        )
    }.map { input ->
        withContext(Dispatchers.Default) {
            buildUiState(input)
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = StatsUiState()
    )

    init {
        observeUiRefreshBus()
        refreshFromNative()
    }

    fun setTimeRange(range: StatsTimeRange) {
        if (_selectedRange.value == range && _customRange.value == null) return
        _selectedRange.value = range
        _customRange.value = null
        resubscribeToRequestedWindow()
    }

    fun setCustomRange(startMillis: Long, endMillis: Long) {
        val normalizedRange = normalizeCustomRange(startMillis, endMillis)
        if (_customRange.value == normalizedRange && _selectedRange.value == null) return
        _selectedRange.value = null
        _customRange.value = normalizedRange
        resubscribeToRequestedWindow()
    }

    private fun observeUiRefreshBus() {
        viewModelScope.launch {
            UiRefreshBus.events.collect { event ->
                when (event) {
                    UiRefreshBus.Event.DataChanged -> refreshFromNative()
                    UiRefreshBus.Event.StatusOnly -> refreshDisplayState()
                }
            }
        }
    }

    suspend fun buildReportUiState(reportDays: Int): StatsUiState = withContext(Dispatchers.Default) {
        val clampedDays = reportDays.coerceIn(1, MAX_REPORT_DAYS)
        val cutoff = System.currentTimeMillis() - (clampedDays.toLong() * DAY_MS)
        val reportHistory = resolveHistoryForStartTime(cutoff)
        val filteredHistory = resolveStatsDisplayHistory(
            history = reportHistory,
            viewMode = _viewMode.value,
            unit = _unit.value
        ).filter {
            it.timestamp >= cutoff && isStatsValueValid(it.value)
        }
        val filteredTemperature = _temperaturePoints.value.filter { it.timestamp >= cutoff }

        StatsUiState(
            selectedRange = _selectedRange.value,
            activeRange = StatsDateRange(
                startMillis = cutoff,
                endMillis = System.currentTimeMillis()
            ),
            availableRange = _availableRange.value,
            unit = _unit.value,
            targets = _targets.value,
            isLoading = _isLoading.value,
            hasSensor = _hasSensor.value,
            summary = calculateSummary(filteredHistory, _targets.value),
            temperaturePoints = filteredTemperature,
            readings = filteredHistory
        )
    }

    fun refreshFromNative() {
        viewModelScope.launch {
            _calibrationRevision.value = CalibrationManager.getRevision()
            val unit = resolveUnit()
            _unit.value = unit
            _targets.value = resolveTargets(unit)

            val nativeSerial = resolveStatsSensorSerial().orEmpty()
            val serial = if (nativeSerial.isBlank()) {
                val availableRange = loadAvailableRange()
                if (availableRange != null) {
                    _availableRange.value = availableRange
                    HistoryRepository.IMPORTED_SENSOR_SERIAL
                } else {
                    ""
                }
            } else {
                nativeSerial
            }

            if (serial.isBlank()) {
                _hasSensor.value = false
                _isLoading.value = false
                _historyPoints.value = emptyList()
                _temperaturePoints.value = emptyList()
                _availableRange.value = null
                _viewMode.value = 0
                activeSerial = null
                historyWindowStartMs = Long.MAX_VALUE
                historyJob?.cancel()
                return@launch
            }

            _hasSensor.value = true
            _viewMode.value = resolveViewModeForStats(serial)
            subscribeToHistory(serial, resolveSubscriptionStartTime())
        }
    }

    private fun subscribeToHistory(serial: String, startTime: Long) {
        historyJob?.cancel()
        _isLoading.value = true
        activeSerial = serial
        historyWindowStartMs = startTime

        historyJob = viewModelScope.launch {
            if (!isImportedHistoryOnlySerial(serial)) {
                withContext(Dispatchers.IO) {
                    historyRepository.ensureBackfilled(serial, startTime)
                }
            }
            _availableRange.value = loadAvailableRange()

            historyRepository.getDisplayHistoryFlowForStats(serial, startTime)
                .distinctUntilChangedBy(::historyEdgeSignature)
                .collect { points ->
                    _historyPoints.value = points
                    _isLoading.value = false
                    _temperaturePoints.value = if (isImportedHistoryOnlySerial(serial)) {
                        emptyList()
                    } else {
                        maybeRefreshTemperaturePoints(serial, points)
                    }

                    // Keep unit/targets in sync if user changed unit while this screen is open.
                    val latestUnit = resolveUnit()
                    if (latestUnit != _unit.value) {
                        _unit.value = latestUnit
                        _targets.value = resolveTargets(latestUnit)
                    }
                    _viewMode.value = resolveViewModeForStats(serial)
                }
        }
    }

    private fun refreshDisplayState() {
        viewModelScope.launch {
            _calibrationRevision.value = CalibrationManager.getRevision()
            val unit = resolveUnit()
            _unit.value = unit
            _targets.value = resolveTargets(unit)

            val nativeSerial = resolveStatsSensorSerial().orEmpty()
            val serial = if (nativeSerial.isBlank()) {
                val availableRange = loadAvailableRange()
                if (availableRange != null) {
                    _availableRange.value = availableRange
                    HistoryRepository.IMPORTED_SENSOR_SERIAL
                } else {
                    ""
                }
            } else {
                nativeSerial
            }

            if (serial.isBlank()) {
                _hasSensor.value = false
                _viewMode.value = 0
                return@launch
            }

            _hasSensor.value = true
            _viewMode.value = resolveViewModeForStats(serial)
            _availableRange.value = loadAvailableRange()

            if (serial != activeSerial || needsHistoryWindowExpansion(resolveSubscriptionStartTime())) {
                subscribeToHistory(serial, resolveSubscriptionStartTime())
            }
        }
    }

    private fun resolveStatsSensorSerial(): String? {
        val selectedMain = SensorIdentity.resolveMainSensor()?.takeIf { it.isNotBlank() }
        if (selectedMain != null) {
            return selectedMain
        }
        val preferred = (SensorIdentity.resolveAppSensorId(activeSerial) ?: activeSerial)
            ?.takeIf { it.isNotBlank() }
        if (preferred != null) {
            return preferred
        }
        return SensorIdentity.resolveAvailableMainSensor(
            selectedMain = null,
            preferredSensorId = null,
            activeSensors = Natives.activeSensors()
        )
    }

    private fun resolveViewMode(serial: String): Int {
        ManagedSensorRuntime.resolveUiSnapshot(serial, serial)?.let { managedSnapshot ->
            return managedSnapshot.viewMode
        }
        if (!SensorIdentity.hasNativeSensorBacking(serial)) {
            return 0
        }
        return try {
            val snapshot = Natives.getSensorUiSnapshot(serial)
            if (snapshot != null && snapshot.size >= 2) snapshot[1].toInt() else 0
        } catch (_: Throwable) {
            0
        }
    }

    private fun resolveViewModeForStats(serial: String): Int {
        return if (isImportedHistoryOnlySerial(serial)) 0 else resolveViewMode(serial)
    }

    private fun isImportedHistoryOnlySerial(serial: String?): Boolean {
        return serial == HistoryRepository.IMPORTED_SENSOR_SERIAL
    }

    private fun resubscribeToRequestedWindow() {
        val serial = activeSerial ?: resolveStatsSensorSerial() ?: return
        val requestedStartTime = resolveSubscriptionStartTime()
        if (serial != activeSerial || needsHistoryWindowExpansion(requestedStartTime)) {
            subscribeToHistory(serial, requestedStartTime)
        }
    }

    private fun needsHistoryWindowExpansion(targetStartTime: Long): Boolean {
        return when {
            activeSerial == null -> true
            historyWindowStartMs == Long.MAX_VALUE -> true
            targetStartTime == 0L && historyWindowStartMs != 0L -> true
            targetStartTime < historyWindowStartMs -> true
            else -> false
        }
    }

    private fun resolveSubscriptionStartTime(): Long {
        val customRange = _customRange.value
        return when {
            customRange != null -> customRange.startMillis
            _selectedRange.value == StatsTimeRange.DAY_ALL -> 0L
            else -> {
                val quickRangeDays = (_selectedRange.value ?: DEFAULT_STATS_RANGE).days.toLong()
                val endMillis = _availableRange.value?.endMillis ?: System.currentTimeMillis()
                (endMillis - (quickRangeDays * DAY_MS) + 1L).coerceAtLeast(0L)
            }
        }
    }

    private suspend fun resolveHistoryForStartTime(startTime: Long): List<GlucosePoint> {
        val currentHistory = _historyPoints.value
        val serial = activeSerial ?: resolveStatsSensorSerial().orEmpty()
        val currentWindowCoversRequest = historyWindowStartMs != Long.MAX_VALUE &&
            historyWindowStartMs <= startTime &&
            currentHistory.isNotEmpty()

        if (currentWindowCoversRequest || serial.isBlank()) {
            return currentHistory.filter { it.timestamp >= startTime }
        }

        if (!isImportedHistoryOnlySerial(serial)) {
            withContext(Dispatchers.IO) {
                historyRepository.ensureBackfilled(serial, startTime)
            }
        }
        return historyRepository.getDisplayHistoryForStats(serial, startTime)
    }

    private suspend fun loadAvailableRange(): StatsDateRange? {
        val oldest = historyRepository.getOldestDisplayTimestamp()
        val latest = historyRepository.getLatestTimestamp()
        return if (oldest > 0L && latest >= oldest) {
            StatsDateRange(startMillis = oldest, endMillis = latest)
        } else {
            null
        }
    }

    private fun normalizeCustomRange(startMillis: Long, endMillis: Long): StatsDateRange {
        val zone = ZoneId.systemDefault()
        val startDate = Instant.ofEpochMilli(minOf(startMillis, endMillis)).atZone(zone).toLocalDate()
        val endDate = Instant.ofEpochMilli(maxOf(startMillis, endMillis)).atZone(zone).toLocalDate()
        return StatsDateRange(
            startMillis = startDate.atStartOfDay(zone).toInstant().toEpochMilli(),
            endMillis = endDate.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1L
        )
    }

    private fun resolveActiveRange(
        quickRange: StatsTimeRange?,
        customRange: StatsDateRange?,
        availableRange: StatsDateRange?
    ): StatsDateRange? {
        customRange?.let { return it }
        return when (quickRange) {
            null -> availableRange
            StatsTimeRange.DAY_ALL -> availableRange
            else -> {
                val endMillis = availableRange?.endMillis ?: System.currentTimeMillis()
                val range = StatsDateRange(
                    startMillis = (endMillis - (quickRange.days.toLong() * DAY_MS) + 1L).coerceAtLeast(0L),
                    endMillis = endMillis
                )
                clampStatsDateRangeToAvailable(range, availableRange)
            }
        }
    }

    private fun resolveStatsDisplayHistory(
        history: List<GlucosePoint>,
        viewMode: Int,
        unit: GlucoseUnit,
        historySignature: StatsHistorySignature = historySignature(history)
    ): List<GlucosePoint> {
        if (history.isEmpty()) return emptyList()

        val calibrationRevision = CalibrationManager.getRevision()
        val cacheKey = StatsDisplayHistoryCacheKey(
            historySignature = historySignature,
            viewMode = viewMode,
            unit = unit,
            calibrationRevision = calibrationRevision,
            activeSerial = activeSerial
        )
        if (statsDisplayHistoryCacheKey == cacheKey) {
            return statsDisplayHistoryCacheValue
        }

        val isRawMode = viewMode == 1 || viewMode == 3
        val isMmol = unit == GlucoseUnit.MMOL
        val overwriteSensorValues = CalibrationManager.shouldOverwriteSensorValues()
        val hideInitialWhenCalibrated = CalibrationManager.shouldHideInitialWhenCalibrated()
        val sensorSerial = activeSerial ?: history.firstOrNull()?.sensorSerial
        val calibratedDisplayValues = arrayOfNulls<Float>(history.size)

        if (!overwriteSensorValues) {
            history.withIndex()
                .groupBy { indexedPoint -> indexedPoint.value.sensorSerial ?: sensorSerial }
                .forEach { (pointSensorSerial, indexedPoints) ->
                    if (pointSensorSerial == null || !CalibrationManager.hasActiveCalibration(isRawMode, pointSensorSerial)) {
                        return@forEach
                    }
                    val samples = indexedPoints.map { indexedPoint ->
                        val point = indexedPoint.value
                        val baseValue = if (isRawMode) point.rawValue else point.value
                        CalibrationManager.CalibrationSample(
                            value = GlucoseFormatter.displayFromMgDl(baseValue, isMmol),
                            timestamp = point.timestamp
                        )
                    }
                    val calibratedSeries = CalibrationManager.getCalibratedSeries(
                        samples = samples,
                        isRawMode = isRawMode,
                        emitDiagnostics = false,
                        sensorIdOverride = pointSensorSerial
                    )
                    indexedPoints.forEachIndexed { localIndex, indexedPoint ->
                        val calibrated = calibratedSeries[localIndex]
                        if (calibrated.isFinite() && calibrated > 0f) {
                            calibratedDisplayValues[indexedPoint.index] = calibrated
                        }
                    }
                }
        }

        val resolved = history.mapIndexedNotNull { index, point ->
            val displayAutoValue = GlucoseFormatter.displayFromMgDl(point.value, isMmol)
            val displayRawValue = GlucoseFormatter.displayFromMgDl(point.rawValue, isMmol)
            val calibratedDisplayValue = calibratedDisplayValues[index]

            val primaryValueMgDl = resolvePrimaryStatsValueMgDl(
                displayAutoValue = displayAutoValue,
                displayRawValue = displayRawValue,
                viewMode = viewMode,
                unit = unit,
                calibratedDisplayValue = calibratedDisplayValue,
                hideInitialWhenCalibrated = calibratedDisplayValue != null && hideInitialWhenCalibrated
            )
            if (primaryValueMgDl == null || !primaryValueMgDl.isFinite() || primaryValueMgDl <= 0f) {
                null
            } else {
                point.copy(value = primaryValueMgDl, rawValue = primaryValueMgDl)
            }
        }
        statsDisplayHistoryCacheKey = cacheKey
        statsDisplayHistoryCacheValue = resolved
        return resolved
    }

    private fun resolveRangeProjection(
        history: List<GlucosePoint>,
        viewMode: Int,
        unit: GlucoseUnit,
        targets: StatsTargets,
        activeRange: StatsDateRange?
    ): StatsRangeProjection {
        if (history.isEmpty()) {
            return StatsRangeProjection(
                filteredHistory = emptyList(),
                summary = StatsSummary()
            )
        }

        val historySignature = historySignature(history)
        val cacheKey = StatsRangeProjectionCacheKey(
            historySignature = historySignature,
            viewMode = viewMode,
            unit = unit,
            calibrationRevision = CalibrationManager.getRevision(),
            activeSerial = activeSerial,
            rangeStartMillis = activeRange?.startMillis ?: Long.MIN_VALUE,
            rangeEndMillis = activeRange?.endMillis ?: Long.MAX_VALUE,
            lowMgDl = targets.lowMgDl,
            highMgDl = targets.highMgDl,
            veryLowMgDl = targets.veryLowMgDl,
            veryHighMgDl = targets.veryHighMgDl
        )
        if (statsRangeProjectionCacheKey == cacheKey) {
            return statsRangeProjectionCacheValue
        }

        val displayHistory = resolveStatsDisplayHistory(
            history = history,
            viewMode = viewMode,
            unit = unit,
            historySignature = historySignature
        )
        val filteredHistory = displayHistory.filter { point ->
            val withinTimeWindow = activeRange?.let { point.timestamp in it.startMillis..it.endMillis } ?: true
            withinTimeWindow && isStatsValueValid(point.value)
        }
        val projection = StatsRangeProjection(
            filteredHistory = filteredHistory,
            summary = calculateSummary(filteredHistory, targets)
        )
        statsRangeProjectionCacheKey = cacheKey
        statsRangeProjectionCacheValue = projection
        return projection
    }

    private fun resolvePrimaryStatsValueMgDl(
        displayAutoValue: Float,
        displayRawValue: Float,
        viewMode: Int,
        unit: GlucoseUnit,
        calibratedDisplayValue: Float?,
        hideInitialWhenCalibrated: Boolean
    ): Float? {
        val isMmol = unit == GlucoseUnit.MMOL
        val displayValues = DisplayValueResolver.resolve(
            autoValue = displayAutoValue,
            rawValue = displayRawValue,
            viewMode = viewMode,
            isMmol = isMmol,
            calibratedValue = calibratedDisplayValue,
            hideInitialWhenCalibrated = hideInitialWhenCalibrated
        )
        val primaryDisplayValue = displayValues.primaryValue
        if (!primaryDisplayValue.isFinite() || primaryDisplayValue <= 0f) {
            return null
        }
        return toMgDl(primaryDisplayValue, unit)
    }

    private fun historySignature(points: List<GlucosePoint>): StatsHistorySignature {
        if (points.isEmpty()) {
            return StatsHistorySignature(
                size = 0,
                firstTimestamp = 0L,
                lastTimestamp = 0L,
                contentHash = 0L
            )
        }

        var hash = 1125899906842597L
        points.forEach { point ->
            hash = 31L * hash + point.timestamp
            hash = 31L * hash + java.lang.Float.floatToRawIntBits(point.value).toLong()
            hash = 31L * hash + java.lang.Float.floatToRawIntBits(point.rawValue).toLong()
            hash = 31L * hash + (point.sensorSerial?.hashCode()?.toLong() ?: 0L)
        }

        return StatsHistorySignature(
            size = points.size,
            firstTimestamp = points.first().timestamp,
            lastTimestamp = points.last().timestamp,
            contentHash = hash
        )
    }

    private fun historyEdgeSignature(points: List<GlucosePoint>): StatsHistoryEdgeSignature {
        if (points.isEmpty()) {
            return StatsHistoryEdgeSignature(0, 0L, 0L, 0L, 0, 0, 0)
        }
        val middle = points[points.lastIndex / 2]
        return StatsHistoryEdgeSignature(
            size = points.size,
            firstTimestamp = points.first().timestamp,
            middleTimestamp = middle.timestamp,
            lastTimestamp = points.last().timestamp,
            firstValueBits = java.lang.Float.floatToRawIntBits(points.first().value),
            middleValueBits = java.lang.Float.floatToRawIntBits(middle.value),
            lastValueBits = java.lang.Float.floatToRawIntBits(points.last().value)
        )
    }

    private fun maybeRefreshTemperaturePoints(serial: String, history: List<GlucosePoint>): List<TemperaturePoint> {
        val now = System.currentTimeMillis()
        val shouldRefresh = serial != cachedTemperatureSerial ||
            now - lastTemperatureRefreshMs > TEMPERATURE_REFRESH_INTERVAL_MS

        if (!shouldRefresh) {
            return cachedTemperaturePoints
        }

        val refreshed = readTemperaturePoints(serial, history)
        cachedTemperatureSerial = serial
        cachedTemperaturePoints = refreshed
        lastTemperatureRefreshMs = now
        return refreshed
    }

    private fun resolveUnit(): GlucoseUnit {
        val unitInt = Natives.getunit()
        return if (unitInt == 1 || Applic.unit == 1) GlucoseUnit.MMOL else GlucoseUnit.MGDL
    }

    private fun resolveTargets(unit: GlucoseUnit): StatsTargets {
        return try {
            val lowMg = toMgDl(Natives.targetlow(), unit)
            val highMg = toMgDl(Natives.targethigh(), unit)
            val veryLowMg = toMgDl(Natives.alarmverylow(), unit)
            val veryHighMg = toMgDl(Natives.alarmveryhigh(), unit)

            val resolvedLow = (if (lowMg > 0f) lowMg else 70f).coerceAtLeast(40f)
            val resolvedHigh = (if (highMg > 0f) highMg else 180f).coerceAtLeast(resolvedLow + 1f)
            val veryLowCandidate = if (veryLowMg > 0f) veryLowMg else 54f
            val veryHighCandidate = if (veryHighMg > 0f) veryHighMg else 250f
            val resolvedVeryLow = veryLowCandidate.coerceAtLeast(35f).coerceAtMost(resolvedLow - 1f)
            val resolvedVeryHigh = veryHighCandidate.coerceAtLeast(resolvedHigh + 1f)

            StatsTargets(
                lowMgDl = resolvedLow,
                highMgDl = resolvedHigh,
                veryLowMgDl = resolvedVeryLow,
                veryHighMgDl = resolvedVeryHigh
            )
        } catch (e: Exception) {
            Log.e(tag, "resolveTargets failed", e)
            StatsTargets()
        }
    }

    private fun toMgDl(rawValue: Float, unit: GlucoseUnit): Float {
        return if (unit == GlucoseUnit.MMOL && rawValue > 0f) GlucoseFormatter.mmolToMg(rawValue) else rawValue
    }

    private fun readTemperaturePoints(serial: String, history: List<GlucosePoint>): List<TemperaturePoint> {
        return try {
            val tempRaw = Natives.getTemperatureDataByName(serial)
            if (tempRaw == null || tempRaw.isEmpty()) return emptyList()

            val firstTs = history.firstOrNull()?.timestamp
            val lastTs = history.lastOrNull()?.timestamp
            val endTs = lastTs ?: System.currentTimeMillis()
            val startTs = firstTs ?: (endTs - tempRaw.size * 5L * 60L * 1000L)
            val step = ((endTs - startTs) / tempRaw.size.coerceAtLeast(1)).coerceAtLeast(60_000L)

            buildList(tempRaw.size) {
                tempRaw.forEachIndexed { index, value ->
                    if (value > 0) {
                        add(
                            TemperaturePoint(
                                timestamp = startTs + index * step,
                                temperatureCelsius = value / 10f
                            )
                        )
                    }
                }
            }
        } catch (e: Throwable) {
            Log.e(tag, "readTemperaturePoints failed", e)
            emptyList()
        }
    }

    private fun buildUiState(input: UiInput): StatsUiState {
        val activeRange = resolveActiveRange(
            quickRange = input.range,
            customRange = input.customRange,
            availableRange = input.availableRange
        )
        val rangeProjection = resolveRangeProjection(
            history = input.historyPoints,
            viewMode = input.viewMode,
            unit = input.unit,
            targets = input.targets,
            activeRange = activeRange
        )
        val filteredTemperature = input.temperaturePoints.filter { point ->
            activeRange?.let { point.timestamp in it.startMillis..it.endMillis } ?: true
        }

        return StatsUiState(
            selectedRange = input.range,
            activeRange = activeRange,
            availableRange = input.availableRange,
            unit = input.unit,
            targets = input.targets,
            isLoading = input.isLoading,
            hasSensor = input.hasSensor,
            summary = rangeProjection.summary,
            temperaturePoints = filteredTemperature,
            readings = rangeProjection.filteredHistory
        )
    }

    private fun isStatsValueValid(valueMgDl: Float): Boolean {
        return valueMgDl.isFinite() &&
            valueMgDl in MIN_STATS_GLUCOSE_MGDL..MAX_STATS_GLUCOSE_MGDL
    }

    private fun calculateSummary(history: List<GlucosePoint>, targets: StatsTargets): StatsSummary {
        if (history.isEmpty()) {
            return StatsSummary()
        }

        val values = history.map { it.value }
        val sortedValues = values.sorted()
        val count = sortedValues.size

        val avg = (sortedValues.sum() / count.toFloat())
        val median = if (count % 2 == 0) {
            (sortedValues[count / 2 - 1] + sortedValues[count / 2]) / 2f
        } else {
            sortedValues[count / 2]
        }
        val p25 = percentile(sortedValues, 0.25f)
        val p75 = percentile(sortedValues, 0.75f)

        val min = sortedValues.first()
        val max = sortedValues.last()

        val variance = sortedValues.fold(0.0) { acc, value ->
            val diff = value - avg
            acc + diff * diff
        } / count.toDouble()
        val stdDev = sqrt(variance).toFloat()
        val cv = if (avg > 0f) (stdDev / avg) * 100f else 0f
        val gmi = (3.31f + (0.02392f * avg)).coerceAtLeast(0f)

        // Use a sensor-neutral, noise-robust series for variability scores.
        val variabilityHistory = toVariabilitySeries(history)
        val variabilityValues = variabilityHistory.map { it.value }
        val variabilityAvg = if (variabilityValues.isNotEmpty()) {
            variabilityValues.average().toFloat()
        } else {
            avg
        }
        val variabilityVariance = if (variabilityValues.isNotEmpty()) {
            variabilityValues.fold(0.0) { acc, value ->
                val diff = value - variabilityAvg
                acc + diff * diff
            } / variabilityValues.size.toDouble()
        } else {
            variance
        }
        val variabilityStdDev = sqrt(variabilityVariance).toFloat()
        val variabilityCv = if (variabilityAvg > 0f) {
            (variabilityStdDev / variabilityAvg) * 100f
        } else {
            cv
        }

        val veryLowThreshold = targets.veryLowMgDl.coerceAtLeast(35f)
        val targetLow = targets.lowMgDl.coerceAtLeast(veryLowThreshold + 1f)
        val targetHigh = targets.highMgDl.coerceAtLeast(targetLow + 1f)
        val veryHighThreshold = targets.veryHighMgDl.coerceAtLeast(targetHigh + 1f)

        val veryLowCount = values.count { it < veryLowThreshold }
        val lowCount = values.count { it >= veryLowThreshold && it < targetLow }
        val inRangeCount = values.count { it in targetLow..targetHigh }
        val highCount = values.count { it > targetHigh && it < veryHighThreshold }
        val veryHighCount = values.count { it >= veryHighThreshold }

        fun percent(part: Int): Float = (part.toFloat() / count.toFloat()) * 100f

        val tir = TimeInRangeBreakdown(
            veryLowPercent = percent(veryLowCount),
            lowPercent = percent(lowCount),
            inRangePercent = percent(inRangeCount),
            highPercent = percent(highCount),
            veryHighPercent = percent(veryHighCount)
        )

        val agp = calculateAgpByHour(history)
        val daily = calculateDailyStats(history, targetLow, targetHigh)
        val gvi = calculateGvi(
            history = variabilityHistory,
            averageMgDl = variabilityAvg,
            stdDevMgDl = variabilityStdDev
        )
        val psg = calculatePsg(
            history = variabilityHistory,
            averageMgDl = avg,
            cvPercent = variabilityCv,
            targets = targets
        )
        val insights = buildInsights(tir = tir, cv = cv, gmi = gmi, dailyStats = daily, agp = agp)

        return StatsSummary(
            readingCount = count,
            avgMgDl = avg,
            p25MgDl = p25,
            medianMgDl = median,
            p75MgDl = p75,
            stdDevMgDl = stdDev,
            cvPercent = cv,
            gmiPercent = gmi,
            gvi = gvi,
            psg = psg,
            minMgDl = min,
            maxMgDl = max,
            firstTimestamp = history.first().timestamp,
            lastTimestamp = history.last().timestamp,
            tir = tir,
            agpByHour = agp,
            dailyStats = daily,
            insights = insights
        )
    }

    private fun calculateAgpByHour(history: List<GlucosePoint>): List<AgpHourBin> {
        val zone = ZoneId.systemDefault()
        val valuesByHour = Array(24) { mutableListOf<Float>() }

        history.forEach { point ->
            val hour = Instant.ofEpochMilli(point.timestamp).atZone(zone).hour
            valuesByHour[hour].add(point.value)
        }

        return (0..23).map { hour ->
            val values = valuesByHour[hour]
            if (values.isEmpty()) {
                AgpHourBin(hour = hour)
            } else {
                val sorted = values.sorted()
                AgpHourBin(
                    hour = hour,
                    p10MgDl = percentile(sorted, 0.10f),
                    p25MgDl = percentile(sorted, 0.25f),
                    medianMgDl = percentile(sorted, 0.50f),
                    p75MgDl = percentile(sorted, 0.75f),
                    p90MgDl = percentile(sorted, 0.90f),
                    sampleCount = sorted.size
                )
            }
        }
    }

    private fun calculateDailyStats(
        history: List<GlucosePoint>,
        targetLow: Float,
        targetHigh: Float
    ): List<DailyStats> {
        val zone = ZoneId.systemDefault()

        return history.groupBy { point ->
            Instant.ofEpochMilli(point.timestamp).atZone(zone).toLocalDate()
        }.entries
            .sortedBy { it.key }
            .map { (date, points) ->
                val values = points.map { it.value }
                val inRangeCount = values.count { it in targetLow..targetHigh }
                DailyStats(
                    date = date,
                    averageMgDl = values.average().toFloat(),
                    inRangePercent = (inRangeCount.toFloat() / values.size.toFloat()) * 100f,
                    readingCount = values.size
                )
            }
    }

    private fun calculateGvi(
        history: List<GlucosePoint>,
        averageMgDl: Float,
        stdDevMgDl: Float
    ): GviScore {
        if (history.size < 2) return GviScore()

        val sorted = history.sortedBy { it.timestamp }
        var totalDelta = 0f
        var rateOfChangeAccum = 0f
        var rateOfChangeSamples = 0

        for (index in 1..sorted.lastIndex) {
            val previous = sorted[index - 1]
            val current = sorted[index]
            val delta = abs(current.value - previous.value)
            val elapsedMinutes = (current.timestamp - previous.timestamp).toFloat() / 60_000f
            totalDelta += delta
            if (elapsedMinutes > 0f && elapsedMinutes < 30f) {
                rateOfChangeAccum += delta / elapsedMinutes
                rateOfChangeSamples++
            }
        }

        val meanDelta = totalDelta / sorted.lastIndex.coerceAtLeast(1)
        val cvFactor = if (averageMgDl > 0f) (stdDevMgDl / averageMgDl).coerceAtLeast(0f) else 0f
        val rateOfChange = if (rateOfChangeSamples > 0) {
            rateOfChangeAccum / rateOfChangeSamples
        } else {
            0f
        }

        // Normalize components so GVI doesn't collapse stability to 0% in common profiles.
        val normalizedDelta = if (averageMgDl > 0f) {
            (meanDelta / averageMgDl).coerceIn(0f, 1.2f)
        } else {
            0f
        }
        val normalizedRoc = (rateOfChange / 3.5f).coerceIn(0f, 1f)

        val gviValue = (
            1f +
                (cvFactor * 1.1f) +
                (normalizedDelta * 0.9f) +
                (normalizedRoc * 0.6f)
            ).coerceIn(0.8f, 3f)
        val stability = (((2.4f - gviValue) / 1.6f) * 100f).coerceIn(0f, 100f)

        val labelResId = when {
            gviValue < 1.25f -> R.string.gvi_excellent
            gviValue < 1.55f -> R.string.gvi_good
            gviValue < 1.90f -> R.string.gvi_moderate
            else -> R.string.gvi_poor
        }

        return GviScore(
            value = gviValue,
            labelResId = labelResId,
            stability = stability,
            rateOfChange = rateOfChange
        )
    }

    private fun calculatePsg(
        history: List<GlucosePoint>,
        averageMgDl: Float,
        cvPercent: Float,
        targets: StatsTargets
    ): PsgScore {
        if (history.isEmpty()) return PsgScore()

        val sorted = history.sortedBy { it.timestamp }
        val halfSize = sorted.size / 2
        val firstHalfAvg = if (halfSize > 0) {
            sorted.take(halfSize).map { it.value }.average().toFloat()
        } else {
            averageMgDl
        }
        val secondHalfAvg = if (halfSize < sorted.size) {
            sorted.drop(halfSize).map { it.value }.average().toFloat()
        } else {
            averageMgDl
        }

        val trend = if (secondHalfAvg > 0f) {
            ((firstHalfAvg - secondHalfAvg) / secondHalfAvg).coerceIn(-1f, 1f)
        } else {
            0f
        }
        val confidence = ((sorted.size.coerceIn(0, MAX_PSG_CONFIDENCE_SAMPLES).toFloat() /
            MAX_PSG_CONFIDENCE_SAMPLES.toFloat()) * (100f - cvPercent).coerceIn(0f, 100f))
            .coerceIn(0f, 100f)

        val labelResId = when {
            averageMgDl < targets.lowMgDl -> R.string.psg_low
            averageMgDl > targets.highMgDl -> R.string.psg_elevated
            cvPercent > 36f -> R.string.psg_unstable
            else -> R.string.psg_stable
        }

        return PsgScore(
            baselineMgDl = averageMgDl,
            labelResId = labelResId,
            trend = trend,
            confidence = confidence
        )
    }

    private fun toVariabilitySeries(history: List<GlucosePoint>): List<GlucosePoint> {
        if (history.size <= 8) return history.sortedBy { it.timestamp }

        val sorted = history.sortedBy { it.timestamp }
        val bucketed = sorted
            .groupBy { it.timestamp / VARIABILITY_BUCKET_MS }
            .toSortedMap()
            .map { (_, points) ->
                val centerPoint = points[points.size / 2]
                val median = percentile(points.map { it.value }.sorted(), 0.5f)
                centerPoint.copy(value = median, rawValue = median)
            }

        if (bucketed.size <= 2) return bucketed

        val stabilized = bucketed.toMutableList()

        // Remove single-point spikes that are likely sensor noise.
        for (index in 1 until stabilized.lastIndex) {
            val previous = stabilized[index - 1].value
            val current = stabilized[index].value
            val next = stabilized[index + 1].value

            val neighborhoodMid = (previous + next) / 2f
            val spikeDistance = abs(current - neighborhoodMid)
            val neighborDistance = abs(previous - next)

            if (
                spikeDistance >= NOISE_SPIKE_THRESHOLD_MGDL &&
                neighborDistance <= NOISE_NEIGHBOR_DISTANCE_MGDL
            ) {
                stabilized[index] = stabilized[index].copy(
                    value = neighborhoodMid,
                    rawValue = neighborhoodMid
                )
            }
        }

        // Cap physiologically implausible jump rates to reduce aged-sensor jitter impact.
        for (index in 1 until stabilized.size) {
            val previous = stabilized[index - 1]
            val current = stabilized[index]
            val elapsedMinutes = ((current.timestamp - previous.timestamp).toFloat() / 60_000f)
                .coerceAtLeast(1f)
            val maxDelta = MAX_PHYS_ROC_MGDL_PER_MIN * elapsedMinutes
            val delta = current.value - previous.value

            if (abs(delta) > maxDelta) {
                val clippedValue = previous.value + (delta.sign * maxDelta)
                stabilized[index] = current.copy(value = clippedValue, rawValue = clippedValue)
            }
        }

        return stabilized
    }

    private fun buildInsights(
        tir: TimeInRangeBreakdown,
        cv: Float,
        gmi: Float,
        dailyStats: List<DailyStats>,
        agp: List<AgpHourBin>
    ): List<StatsInsight> {
        val context = Applic.app
        val insights = mutableListOf<StatsInsight>()

        when {
            tir.inRangePercent >= 70f -> insights += StatsInsight(
                title = context.getString(R.string.insight_excellent_control),
                message = context.getString(
                    R.string.insight_excellent_control_desc,
                    tir.inRangePercent.toInt()
                ),
                severity = InsightSeverity.POSITIVE
            )

            tir.inRangePercent >= 55f -> insights += StatsInsight(
                title = context.getString(R.string.insight_good_progress),
                message = context.getString(
                    R.string.insight_good_progress_desc,
                    tir.inRangePercent.toInt()
                ),
                severity = InsightSeverity.ATTENTION
            )

            else -> insights += StatsInsight(
                title = context.getString(R.string.insight_room_improvement),
                message = context.getString(
                    R.string.insight_room_improvement_desc,
                    tir.inRangePercent.toInt()
                ),
                severity = InsightSeverity.CAUTION
            )
        }

        when {
            cv <= 36f -> insights += StatsInsight(
                title = context.getString(R.string.insight_stable_glucose),
                message = context.getString(
                    R.string.insight_stable_glucose_desc,
                    cv.toInt()
                ),
                severity = InsightSeverity.POSITIVE
            )

            cv <= 45f -> insights += StatsInsight(
                title = context.getString(R.string.insight_variability_rising),
                message = context.getString(
                    R.string.insight_variability_rising_desc,
                    String.format(Locale.getDefault(), "%.1f", cv)
                ),
                severity = InsightSeverity.ATTENTION
            )

            else -> insights += StatsInsight(
                title = context.getString(R.string.insight_high_variability),
                message = context.getString(
                    R.string.insight_high_variability_desc,
                    cv.toInt()
                ),
                severity = InsightSeverity.CAUTION
            )
        }

        if (tir.veryLowPercent >= 1f) {
            insights += StatsInsight(
                title = context.getString(R.string.insight_hypoglycemia_exposure),
                message = context.getString(
                    R.string.insight_hypoglycemia_exposure_desc,
                    String.format(Locale.getDefault(), "%.1f", tir.veryLowPercent)
                ),
                severity = InsightSeverity.CAUTION
            )
        }

        if (tir.veryHighPercent >= 5f) {
            insights += StatsInsight(
                title = context.getString(R.string.insight_prolonged_hyperglycemia),
                message = context.getString(
                    R.string.insight_prolonged_hyperglycemia_desc,
                    String.format(Locale.getDefault(), "%.1f", tir.veryHighPercent)
                ),
                severity = InsightSeverity.ATTENTION
            )
        }

        val overnightMedian = agp
            .filter { it.hour in 0..5 }
            .mapNotNull { it.medianMgDl }
            .average()
            .toFloat()
        val daytimeMedian = agp
            .filter { it.hour in 10..18 }
            .mapNotNull { it.medianMgDl }
            .average()
            .toFloat()

        if (overnightMedian > 0f && daytimeMedian > 0f && overnightMedian - daytimeMedian > 20f) {
            insights += StatsInsight(
                title = context.getString(R.string.insight_overnight_drift),
                message = context.getString(R.string.insight_overnight_drift_desc),
                severity = InsightSeverity.ATTENTION
            )
        }

        val unstableDays = dailyStats.count { it.inRangePercent < 50f }
        if (unstableDays >= 3 && dailyStats.size >= 7) {
            insights += StatsInsight(
                title = context.getString(R.string.insight_unstable_days),
                message = context.getString(R.string.insight_unstable_days_desc, unstableDays),
                severity = InsightSeverity.CAUTION
            )
        }

        if (gmi >= 7.5f && tir.inRangePercent < 60f) {
            insights += StatsInsight(
                title = context.getString(R.string.insight_a1c_estimate),
                message = context.getString(R.string.insight_a1c_estimate_desc, gmi),
                severity = InsightSeverity.ATTENTION
            )
        }

        return insights.distinctBy { it.title }.take(MAX_INSIGHTS)
    }

    private fun percentile(sorted: List<Float>, percentile: Float): Float {
        if (sorted.isEmpty()) return 0f
        if (sorted.size == 1) return sorted.first()

        val clamped = percentile.coerceIn(0f, 1f)
        val position = clamped * (sorted.size - 1)
        val lowerIndex = position.toInt()
        val upperIndex = (lowerIndex + 1).coerceAtMost(sorted.lastIndex)
        val weight = position - lowerIndex

        return sorted[lowerIndex] + (sorted[upperIndex] - sorted[lowerIndex]) * weight
    }

    private data class UiInput(
        val range: StatsTimeRange?,
        val customRange: StatsDateRange?,
        val availableRange: StatsDateRange?,
        val unit: GlucoseUnit,
        val targets: StatsTargets,
        val viewMode: Int,
        val calibrationRevision: Long,
        val isLoading: Boolean,
        val hasSensor: Boolean,
        val historyPoints: List<GlucosePoint>,
        val temperaturePoints: List<TemperaturePoint>
    )

    private data class BaseInput(
        val range: StatsTimeRange?,
        val customRange: StatsDateRange?,
        val availableRange: StatsDateRange?,
        val unit: GlucoseUnit,
        val targets: StatsTargets,
        val viewMode: Int,
        val calibrationRevision: Long,
        val isLoading: Boolean,
        val hasSensor: Boolean
    )

    companion object {
        private const val DAY_MS = 24L * 60L * 60L * 1000L
        private const val MAX_INSIGHTS = 5
        private const val TEMPERATURE_REFRESH_INTERVAL_MS = 15L * 60L * 1000L
        private const val MAX_PSG_CONFIDENCE_SAMPLES = 288
        private const val VARIABILITY_BUCKET_MS = 5L * 60L * 1000L
        private const val NOISE_SPIKE_THRESHOLD_MGDL = 18f
        private const val NOISE_NEIGHBOR_DISTANCE_MGDL = 9f
        private const val MAX_PHYS_ROC_MGDL_PER_MIN = 3.5f
        private const val MIN_STATS_GLUCOSE_MGDL = 30f
        private const val MAX_STATS_GLUCOSE_MGDL = 500f
        private const val MAX_REPORT_DAYS = 365
        private val DEFAULT_STATS_RANGE = StatsTimeRange.DAY_1
    }
}
