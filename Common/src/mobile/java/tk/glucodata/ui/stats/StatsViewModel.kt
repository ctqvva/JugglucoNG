package tk.glucodata.ui.stats

import android.content.ContextWrapper
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapLatest
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
import tk.glucodata.drivers.ManagedSensorViewModeStore
import tk.glucodata.drivers.anytime.AnytimeRegistry
import tk.glucodata.drivers.ottai.OttaiRegistry
import tk.glucodata.ui.GlucosePoint
import tk.glucodata.ui.DisplayValueResolver
import tk.glucodata.ui.util.GlucoseFormatter
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.sqrt

/**
 * The one statistics view model for the whole activity.
 *
 * A plain `viewModel()` inside a `composable("stats") { … }` scopes to that destination's
 * [androidx.navigation.NavBackStackEntry]. Bottom navigation here pops the destination it
 * leaves — `popUpTo(startDestination) { saveState = true }` — and popping clears that
 * entry's `ViewModelStore`; `saveState` preserves a `SavedStateHandle`, not view models.
 * So every single visit to the Statistics tab was building a new view model from nothing:
 * a fresh Room subscription, another native backfill check, and every statistic
 * recomputed against an empty cache. The dashboard strip was getting a second instance of
 * its own for the same reason, doing the same work twice over.
 *
 * Scoping to the activity means one instance, built once, kept warm.
 */
@Composable
internal fun rememberStatsViewModel(): StatsViewModel {
    val context = LocalContext.current
    val owner = remember(context) {
        generateSequence(context) { (it as? ContextWrapper)?.baseContext }
            .filterIsInstance<ViewModelStoreOwner>()
            .firstOrNull()
    }
    return viewModel(viewModelStoreOwner = owner ?: checkNotNull(LocalViewModelStoreOwner.current))
}

@OptIn(ExperimentalCoroutinesApi::class)
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
        val sensorModesHash: Long,
        val unit: GlucoseUnit,
        val calibrationRevision: Long,
        val activeSerial: String?
    )

    private data class StatsRangeProjectionCacheKey(
        val historySignature: StatsHistorySignature,
        val viewMode: Int,
        val sensorModesHash: Long,
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

    private data class CachedRenderedState(
        val serial: String,
        val state: StatsUiState
    )

    private val restoredSelection: StatsRangeSelection? = StatsRangeStore.load(Applic.app)
    private val _selectedRange = MutableStateFlow<StatsTimeRange?>(
        when (restoredSelection) {
            is StatsRangeSelection.Preset -> restoredSelection.range
            is StatsRangeSelection.Custom -> null
            null -> DEFAULT_STATS_RANGE
        }
    )
    val selectedRange: StateFlow<StatsTimeRange?> = _selectedRange.asStateFlow()
    private val _customRange = MutableStateFlow(
        (restoredSelection as? StatsRangeSelection.Custom)?.range
    )
    private val _availableRange = MutableStateFlow<StatsDateRange?>(null)
    // A restored custom range may reach outside the data that exists today; it gets
    // clamped once the available range is known.
    private var pendingRestoredCustomClamp = restoredSelection is StatsRangeSelection.Custom

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
    private var cachedTemperatureHistoryStartMs: Long = Long.MAX_VALUE
    private var cachedTemperatureHistoryEndMs: Long = Long.MIN_VALUE
    private var lastTemperatureRefreshMs: Long = 0L
    private var availableRangeJob: Job? = null
    @Volatile private var statsDisplayHistoryCacheKey: StatsDisplayHistoryCacheKey? = null
    @Volatile private var statsDisplayHistoryCacheValue: List<GlucosePoint> = emptyList()
    /**
     * One slot per consumer. The statistics screen and the dashboard strip ask for
     * different windows of the same history, so a single shared slot would have been
     * invalidated by whichever of them recomposed last.
     */
    private class ProjectionCache {
        @Volatile var key: StatsRangeProjectionCacheKey? = null
        @Volatile var value = StatsRangeProjection(
            filteredHistory = emptyList(),
            summary = StatsSummary()
        )
    }

    private val screenProjectionCache = ProjectionCache()
    private val pinnedProjectionCache = ProjectionCache()

    /**
     * The window the dashboard strip is summarising. Its own, so that cycling the pill on
     * the dashboard no longer overwrites — and persists — whichever range the user had
     * chosen on the statistics screen.
     */
    private val _pinnedWindow = MutableStateFlow<PinnedWindow?>(null)

    /**
     * Which deferred parts anything has actually read. Shared across summaries and across
     * threads, so it is a concurrent set rather than a plain one.
     */
    private val demandedParts: MutableSet<StatsPart> =
        java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** True from the moment a range is chosen until the state for it has been built. */
    private val _isSwitchingRange = MutableStateFlow(false)
    val isSwitchingRange: StateFlow<Boolean> = _isSwitchingRange.asStateFlow()

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
            activeSerial = activeSerial,
            historyPoints = history,
            temperaturePoints = temperature
        )
    }.mapLatest { input ->
        withContext(Dispatchers.Default) {
            buildUiState(input).also { state ->
                // Everything already known to be on screen is resolved here, on this
                // thread, before the state is handed to the UI.
                (state.summary.parts as? LazyStatsParts)?.warmDemanded()
                rememberRenderedState(input.activeSerial, state)
            }
        }.also { _isSwitchingRange.value = false }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = resolveInitialUiState()
    )

    /**
     * Numbers for the dashboard strip, over the strip's own window.
     *
     * Deliberately a second flow rather than a second reader of [uiState]: the strip must
     * not drag the statistics screen's range around with it — cycling the window pill used
     * to overwrite and persist whatever range the user had chosen on the statistics screen
     * — and the screen's full projection is far more work than three chips need. Both go
     * through [resolveRangeProjection], so where the windows agree the numbers agree.
     */
    internal val pinnedState: StateFlow<StatsPinnedState> = combine(
        baseState,
        _historyPoints,
        _pinnedWindow
    ) { base, history, window ->
        PinnedInput(
            unit = base.unit,
            targets = base.targets,
            viewMode = base.viewMode,
            calibrationRevision = base.calibrationRevision,
            history = history,
            window = window
        )
    }.mapLatest { input ->
        withContext(Dispatchers.Default) { buildPinnedState(input) }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = StatsPinnedState()
    )

    init {
        observeUiRefreshBus()
        if (pendingRestoredCustomClamp) {
            clampRestoredCustomRangeWhenAvailable()
        }
        refreshFromNative()
    }

    internal fun setPinnedWindow(window: PinnedWindow?) {
        if (_pinnedWindow.value == window) return
        _pinnedWindow.value = window
        resubscribeToRequestedWindow()
    }

    fun setTimeRange(range: StatsTimeRange) {
        pendingRestoredCustomClamp = false
        if (_selectedRange.value == range && _customRange.value == null) return
        // The screen says so straight away. Recomputing is quick now, but "quick" is not
        // "instant", and a control that goes dead for a moment with no acknowledgement
        // reads as a control that did not register the tap.
        _isSwitchingRange.value = true
        _selectedRange.value = range
        _customRange.value = null
        StatsRangeStore.savePreset(Applic.app, range)
        resubscribeToRequestedWindow()
    }

    fun setCustomRange(startMillis: Long, endMillis: Long) {
        pendingRestoredCustomClamp = false
        val normalizedRange = normalizeCustomRange(startMillis, endMillis)
        if (_customRange.value == normalizedRange && _selectedRange.value == null) return
        _isSwitchingRange.value = true
        _selectedRange.value = null
        _customRange.value = normalizedRange
        StatsRangeStore.saveCustom(Applic.app, normalizedRange)
        resubscribeToRequestedWindow()
    }

    private fun clampRestoredCustomRangeWhenAvailable() {
        viewModelScope.launch {
            val available = _availableRange.filterNotNull().first()
            if (!pendingRestoredCustomClamp) return@launch
            pendingRestoredCustomClamp = false
            val restored = _customRange.value ?: return@launch
            val clamped = clampStatsDateRangeToAvailable(restored, available)
            if (clamped == null) {
                _customRange.value = null
                _selectedRange.value = DEFAULT_STATS_RANGE
            } else if (clamped != restored) {
                _customRange.value = clamped
            }
        }
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
            summary = calculateSummary(
                history = filteredHistory,
                targets = _targets.value,
                unit = _unit.value,
                activeRange = StatsDateRange(
                    startMillis = cutoff,
                    endMillis = System.currentTimeMillis()
                )
            ),
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
                cachedTemperatureSerial = null
                cachedTemperaturePoints = emptyList()
                cachedTemperatureHistoryStartMs = Long.MAX_VALUE
                cachedTemperatureHistoryEndMs = Long.MIN_VALUE
                lastTemperatureRefreshMs = 0L
                historyJob?.cancel()
                availableRangeJob?.cancel()
                return@launch
            }

            _hasSensor.value = true
            _viewMode.value = resolveViewModeForStats(serial)
            val requestedStartTime = resolveSubscriptionStartTime()
            // Ordinary glucose updates arrive through the active Room flow.
            val shouldSubscribe = serial != activeSerial ||
                needsHistoryWindowExpansion(requestedStartTime) ||
                historyJob?.isActive != true
            if (shouldSubscribe) {
                subscribeToHistory(serial, requestedStartTime)
            } else {
                refreshAvailableRangeAsync()
            }
        }
    }

    private fun subscribeToHistory(serial: String, startTime: Long) {
        historyJob?.cancel()
        val previousSerial = activeSerial
        val previousWindowStart = historyWindowStartMs
        _isLoading.value = _historyPoints.value.isEmpty() ||
            previousSerial != serial ||
            startTime < previousWindowStart
        activeSerial = serial
        historyWindowStartMs = startTime

        historyJob = viewModelScope.launch {
            refreshAvailableRangeAsync()
            // Native backfill can be slow on reopen; don't block already-persisted Room data.
            launch(Dispatchers.IO) {
                if (!isImportedHistoryOnlySerial(serial)) {
                    historyRepository.ensureBackfilled(serial, startTime)
                }
                _availableRange.value = loadAvailableRange()
            }

            historyRepository.getDisplayHistoryFlowForStats(serial, startTime)
                .conflate()
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
            refreshAvailableRangeAsync()

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

    private fun resolveViewModeOrNull(serial: String): Int? {
        ManagedSensorRuntime.resolveUiSnapshot(serial, serial)?.let { managedSnapshot ->
            return managedSnapshot.viewMode
        }
        if (SensorIdentity.hasNativeSensorBacking(serial)) {
            val nativeMode = try {
                val snapshot = Natives.getSensorUiSnapshot(serial)
                if (snapshot != null && snapshot.size >= 2) snapshot[1].toInt() else null
            } catch (_: Throwable) {
                null
            }
            if (nativeMode != null) return nativeMode.coerceIn(0, 3)
        }
        return SensorIdentity.resolveRoomQuerySensorIds(serial)
            .asSequence()
            .mapNotNull { candidate -> ManagedSensorViewModeStore.readOrNull(Applic.app, candidate) }
            .firstOrNull()
            ?: ManagedSensorViewModeStore.readOrNull(Applic.app, serial)
    }

    private fun resolveViewMode(serial: String): Int {
        return resolveViewModeOrNull(serial) ?: 0
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

    private fun refreshAvailableRangeAsync() {
        availableRangeJob?.cancel()
        availableRangeJob = viewModelScope.launch(Dispatchers.IO) {
            _availableRange.value = loadAvailableRange()
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

    /**
     * How far back the history subscription has to reach.
     *
     * Deliberately not narrowed to whoever happens to be on screen. Deferring the wide
     * read until the statistics screen composed did make app start cheaper, but it moved
     * the whole cost onto the moment you open that screen — cancelling the live
     * subscription, re-reading the database and flipping the screen back into its loading
     * state on every first visit. The prefetch is what makes the screen open instantly;
     * it happens in the background while the dashboard is up, and that is the right
     * trade.
     */
    private fun resolveSubscriptionStartTime(): Long {
        val screenStart = resolveScreenSubscriptionStartTime()
        val pinnedStart = _pinnedWindow.value?.resolveRange()?.startMillis
        return if (pinnedStart != null) minOf(screenStart, pinnedStart) else screenStart
    }

    private fun resolveScreenSubscriptionStartTime(): Long {
        val customRange = _customRange.value
        return when {
            customRange != null -> {
                val spanDays = customRange.daySpan.toLong()
                if (spanDays in 1..COMPARISON_MAX_RANGE_DAYS) {
                    (customRange.startMillis - (spanDays * DAY_MS)).coerceAtLeast(0L)
                } else {
                    customRange.startMillis
                }
            }
            _selectedRange.value == StatsTimeRange.DAY_ALL -> 0L
            else -> {
                val quickRangeDays = (_selectedRange.value ?: DEFAULT_STATS_RANGE).days.toLong()
                val endMillis = _availableRange.value?.endMillis ?: System.currentTimeMillis()
                // Load one extra period so the screen can say how this window compares to
                // the one before it. Only for short windows — doubling a 90-day pull of
                // 1-minute data costs far more than the comparison chip is worth.
                val periods = if (quickRangeDays <= COMPARISON_MAX_RANGE_DAYS) 2L else 1L
                (endMillis - (quickRangeDays * periods * DAY_MS) + 1L).coerceAtLeast(0L)
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
        historySignature: StatsHistorySignature = historySignature(history),
        useCache: Boolean = true
    ): List<GlucosePoint> {
        if (history.isEmpty()) return emptyList()

        val sensorViewModes = resolveHistoricalSensorViewModes(history, viewMode)
        val sensorModesHash = sensorViewModesHash(sensorViewModes)
        val calibrationRevision = CalibrationManager.getRevision()
        val cacheKey = StatsDisplayHistoryCacheKey(
            historySignature = historySignature,
            viewMode = viewMode,
            sensorModesHash = sensorModesHash,
            unit = unit,
            calibrationRevision = calibrationRevision,
            activeSerial = activeSerial
        )
        if (useCache && statsDisplayHistoryCacheKey == cacheKey) {
            return statsDisplayHistoryCacheValue
        }

        val isMmol = unit == GlucoseUnit.MMOL
        val hideInitialWhenCalibrated = CalibrationManager.shouldHideInitialWhenCalibrated()
        val sensorSerial = activeSerial ?: history.firstOrNull()?.sensorSerial
        val calibratedDisplayValues = arrayOfNulls<Float>(history.size)

        run {
            history.withIndex()
                .groupBy { indexedPoint -> indexedPoint.value.sensorSerial ?: sensorSerial }
                .forEach { (pointSensorSerial, indexedPoints) ->
                    val pointViewMode = sensorViewModes[pointSensorSerial] ?: viewMode
                    val isRawMode = pointViewMode == 1 || pointViewMode == 3
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

            // A reading whose displayed value was recorded overrides the series
            // recomputed above: the statistic has to describe what was on screen,
            // not what today's calibration would have made of it.
            history.forEachIndexed { index, point ->
                point.sealedDisplayValue
                    ?.takeIf { it.isFinite() && it > 0f }
                    ?.let { calibratedDisplayValues[index] = it }
            }
        }

        val resolved = history.mapIndexedNotNull { index, point ->
            val pointSensorSerial = point.sensorSerial ?: sensorSerial
            val pointViewMode = sensorViewModes[pointSensorSerial] ?: viewMode
            val displayAutoValue = GlucoseFormatter.displayFromMgDl(point.value, isMmol)
            val displayRawValue = GlucoseFormatter.displayFromMgDl(point.rawValue, isMmol)
            val calibratedDisplayValue = calibratedDisplayValues[index]
            val primaryValueMgDl = resolvePrimaryStatsValueMgDl(
                displayAutoValue = displayAutoValue,
                displayRawValue = displayRawValue,
                viewMode = pointViewMode,
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
        if (useCache) {
            statsDisplayHistoryCacheKey = cacheKey
            statsDisplayHistoryCacheValue = resolved
        }
        return resolved
    }

    private fun resolveHistoricalSensorViewModes(
        history: List<GlucosePoint>,
        activeViewMode: Int
    ): Map<String?, Int> {
        val sensorIds = LinkedHashSet<String?>()
        history.forEach { point -> sensorIds.add(point.sensorSerial ?: activeSerial) }
        return sensorIds.associateWith { sensorId ->
            resolveHistoricalSensorViewMode(sensorId, activeViewMode)
        }
    }

    private fun resolveHistoricalSensorViewMode(sensorId: String?, activeViewMode: Int): Int {
        if (sensorId.isNullOrBlank()) return activeViewMode.coerceIn(0, 3)
        val isImported = isImportedHistoryOnlySerial(sensorId) ||
            sensorId == "imported" || sensorId == "unknown"
        val hasRawCalibration = CalibrationManager.hasCalibrationPointsForMode(
            isRawMode = true,
            sensorIdOverride = sensorId
        )
        val hasAutoCalibration = CalibrationManager.hasCalibrationPointsForMode(
            isRawMode = false,
            sensorIdOverride = sensorId
        )
        return HistoricalSensorModePolicy.resolve(
            activeViewMode = activeViewMode,
            isImported = isImported,
            resolvedStoredMode = if (isImported) null else resolveViewModeOrNull(sensorId),
            hasRawCalibration = hasRawCalibration,
            hasAutoCalibration = hasAutoCalibration,
            matchesActiveSensor = !activeSerial.isNullOrBlank() && SensorIdentity.matches(sensorId, activeSerial)
        )
    }

    private fun sensorViewModesHash(sensorViewModes: Map<String?, Int>): Long {
        var hash = 1125899906842597L
        sensorViewModes.entries
            .sortedBy { it.key.orEmpty() }
            .forEach { (sensorId, mode) ->
                hash = 31L * hash + (sensorId?.hashCode()?.toLong() ?: 0L)
                hash = 31L * hash + mode.toLong()
            }
        return hash
    }

    private fun resolveRangeProjection(
        history: List<GlucosePoint>,
        viewMode: Int,
        unit: GlucoseUnit,
        targets: StatsTargets,
        activeRange: StatsDateRange?,
        cache: ProjectionCache = screenProjectionCache,
        useDisplayCache: Boolean = true,
        withComparison: Boolean = true
    ): StatsRangeProjection {
        if (history.isEmpty()) {
            return StatsRangeProjection(
                filteredHistory = emptyList(),
                summary = StatsSummary()
            )
        }

        val rawHistory = filterHistoryForRange(history, activeRange)
        if (rawHistory.isEmpty()) {
            return StatsRangeProjection(
                filteredHistory = emptyList(),
                summary = StatsSummary()
            )
        }

        val historySignature = historySignature(rawHistory)
        val sensorModesHash = sensorViewModesHash(resolveHistoricalSensorViewModes(rawHistory, viewMode))
        val cacheKey = StatsRangeProjectionCacheKey(
            historySignature = historySignature,
            viewMode = viewMode,
            sensorModesHash = sensorModesHash,
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
        if (cache.key == cacheKey) {
            return cache.value
        }

        val displayHistory = resolveStatsDisplayHistory(
            history = rawHistory,
            viewMode = viewMode,
            unit = unit,
            historySignature = historySignature,
            useCache = useDisplayCache
        )
        val filteredHistory = displayHistory.filter { point ->
            isStatsValueValid(point.value)
        }
        val projection = StatsRangeProjection(
            filteredHistory = filteredHistory,
            summary = calculateSummary(
                history = filteredHistory,
                targets = targets,
                unit = unit,
                activeRange = activeRange,
                previousScalars = if (withComparison) {
                    resolvePreviousPeriodScalars(
                        history = history,
                        viewMode = viewMode,
                        unit = unit,
                        targets = targets,
                        activeRange = activeRange,
                        currentReadingCount = filteredHistory.size
                    )
                } else {
                    null
                }
            )
        )
        cache.key = cacheKey
        cache.value = projection
        return projection
    }

    /**
     * Scalars for the equally long window immediately before the active one.
     *
     * Returns null unless that window is loaded and holds enough readings to compare
     * honestly — a "+18 points" chip computed against two days of data is worse than
     * no chip at all.
     */
    private fun resolvePreviousPeriodScalars(
        history: List<GlucosePoint>,
        viewMode: Int,
        unit: GlucoseUnit,
        targets: StatsTargets,
        activeRange: StatsDateRange?,
        currentReadingCount: Int
    ): PeriodScalars? {
        if (activeRange == null || currentReadingCount < MIN_COMPARISON_READINGS) return null
        val spanMillis = activeRange.endMillis - activeRange.startMillis
        if (spanMillis <= 0L || activeRange.daySpan > COMPARISON_MAX_RANGE_DAYS) return null

        val previousRange = StatsDateRange(
            startMillis = (activeRange.startMillis - spanMillis - 1L).coerceAtLeast(0L),
            endMillis = activeRange.startMillis - 1L
        )
        val rawPrevious = filterHistoryForRange(history, previousRange)
        if (rawPrevious.size < currentReadingCount * MIN_COMPARISON_COVERAGE) return null

        val values = resolveStatsDisplayHistory(
            history = rawPrevious,
            viewMode = viewMode,
            unit = unit,
            useCache = false
        ).mapNotNull { point -> point.value.takeIf { isStatsValueValid(it) } }
        if (values.size < currentReadingCount * MIN_COMPARISON_COVERAGE) return null
        return StatsAnalytics.periodScalars(values, targets)
    }

    private fun filterHistoryForRange(
        history: List<GlucosePoint>,
        activeRange: StatsDateRange?
    ): List<GlucosePoint> {
        if (history.isEmpty() || activeRange == null) return history

        val startIndex = lowerBoundByTimestamp(history, activeRange.startMillis)
        val endExclusive = upperBoundByTimestamp(history, activeRange.endMillis)
        if (startIndex >= endExclusive) return emptyList()
        if (startIndex == 0 && endExclusive == history.size) return history
        return history.subList(startIndex, endExclusive)
    }

    private fun lowerBoundByTimestamp(points: List<GlucosePoint>, timestamp: Long): Int {
        var low = 0
        var high = points.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (points[mid].timestamp < timestamp) {
                low = mid + 1
            } else {
                high = mid
            }
        }
        return low
    }

    private fun upperBoundByTimestamp(points: List<GlucosePoint>, timestamp: Long): Int {
        var low = 0
        var high = points.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (points[mid].timestamp <= timestamp) {
                low = mid + 1
            } else {
                high = mid
            }
        }
        return low
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
        val historyStartMs = history.firstOrNull()?.timestamp ?: Long.MAX_VALUE
        val historyEndMs = history.lastOrNull()?.timestamp ?: Long.MIN_VALUE
        val historyWindowExpanded = historyStartMs < cachedTemperatureHistoryStartMs
        // A new reading means a new temperature sample too — without this the card is
        // stuck on whatever it read up to 15 minutes ago and looks like it never grows.
        val newerReadingArrived = historyEndMs > cachedTemperatureHistoryEndMs
        val shouldRefresh = serial != cachedTemperatureSerial ||
            historyWindowExpanded ||
            newerReadingArrived ||
            (cachedTemperaturePoints.isEmpty() && history.isNotEmpty()) ||
            now - lastTemperatureRefreshMs > TEMPERATURE_REFRESH_INTERVAL_MS

        if (!shouldRefresh) {
            return cachedTemperaturePoints
        }

        val refreshed = readTemperaturePoints(serial, history)
        cachedTemperatureSerial = serial
        cachedTemperaturePoints = refreshed
        cachedTemperatureHistoryStartMs = historyStartMs
        cachedTemperatureHistoryEndMs = historyEndMs
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
        readAnytimeTemperaturePoints(serial, history).takeIf { it.isNotEmpty() }?.let {
            return it
        }
        readOttaiTemperaturePoints(serial, history).takeIf { it.isNotEmpty() }?.let {
            return it
        }
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

    private fun readAnytimeTemperaturePoints(serial: String, history: List<GlucosePoint>): List<TemperaturePoint> {
        return try {
            val context = Applic.app ?: return emptyList()
            val sensorIds = resolveAnytimeTemperatureSensorIds(context, serial)
            val records = sensorIds
                .asSequence()
                .map { AnytimeRegistry.loadTemperatureHistory(context, it) }
                .firstOrNull { it.isNotEmpty() }
                .orEmpty()
            if (records.isEmpty()) return emptyList()

            val firstTs = history.firstOrNull()?.timestamp ?: Long.MIN_VALUE
            val lastTs = history.lastOrNull()?.timestamp ?: Long.MAX_VALUE
            records.asSequence()
                .filter { it.timestampMs > 0L }
                .filter { history.isEmpty() || it.timestampMs in firstTs..lastTs }
                .filter { it.temperatureC.isFinite() && it.temperatureC > -20f && it.temperatureC < 80f }
                .distinctBy { it.timestampMs }
                .sortedBy { it.timestampMs }
                .map {
                    TemperaturePoint(
                        timestamp = it.timestampMs,
                        temperatureCelsius = it.temperatureC
                    )
                }
                .toList()
        } catch (e: Throwable) {
            Log.e(tag, "readAnytimeTemperaturePoints failed", e)
            emptyList()
        }
    }

    private fun readOttaiTemperaturePoints(serial: String, history: List<GlucosePoint>): List<TemperaturePoint> {
        return try {
            val context = Applic.app ?: return emptyList()
            val records = resolveOttaiTemperatureSensorIds(context, serial)
                .asSequence()
                .map { OttaiRegistry.loadTemperatureHistory(context, it) }
                .firstOrNull { it.isNotEmpty() }
                .orEmpty()
            if (records.isEmpty()) return emptyList()

            val firstTs = history.firstOrNull()?.timestamp ?: Long.MIN_VALUE
            val lastTs = history.lastOrNull()?.timestamp ?: Long.MAX_VALUE
            records.asSequence()
                .filter { it.timestampMs > 0L }
                .filter { history.isEmpty() || it.timestampMs in firstTs..lastTs }
                .filter { it.temperatureC.isFinite() && it.temperatureC > -20f && it.temperatureC < 80f }
                .distinctBy { it.timestampMs }
                .sortedBy { it.timestampMs }
                .map {
                    TemperaturePoint(
                        timestamp = it.timestampMs,
                        temperatureCelsius = it.temperatureC
                    )
                }
                .toList()
        } catch (e: Throwable) {
            Log.e(tag, "readOttaiTemperaturePoints failed", e)
            emptyList()
        }
    }

    private fun resolveOttaiTemperatureSensorIds(
        context: android.content.Context,
        serial: String
    ): List<String> {
        val candidates = LinkedHashSet<String>()
        fun add(value: String?) {
            value
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.let(candidates::add)
        }

        add(serial)
        add(SensorIdentity.resolveAppSensorId(serial))
        add(SensorIdentity.resolveNativeSensorName(serial))
        add(runCatching { Natives.resolveFullSensorName(serial) }.getOrNull())

        candidates.toList().forEach { candidate ->
            add(OttaiRegistry.resolveCanonicalSensorId(context, candidate))
        }

        val known = OttaiRegistry.persistedRecords(context)
        known.firstOrNull { record -> candidates.any { record.matchesId(it) } }?.let { add(it.sensorId) }

        return candidates.toList()
    }

    private fun resolveAnytimeTemperatureSensorIds(
        context: android.content.Context,
        serial: String
    ): List<String> {
        val candidates = LinkedHashSet<String>()
        fun add(value: String?) {
            value
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.let(candidates::add)
        }

        add(serial)
        add(SensorIdentity.resolveAppSensorId(serial))
        add(SensorIdentity.resolveNativeSensorName(serial))
        add(runCatching { Natives.resolveFullSensorName(serial) }.getOrNull())

        candidates.toList().forEach { candidate ->
            add(AnytimeRegistry.resolveCanonicalSensorId(context, candidate))
        }

        val known = AnytimeRegistry.persistedRecords(context)
        known.firstOrNull { record ->
            candidates.any { candidate ->
                record.matchesId(candidate) ||
                    record.sensorId.endsWith(candidate, ignoreCase = true) ||
                    candidate.endsWith(record.sensorId, ignoreCase = true)
            }
        }?.let { add(it.sensorId) }

        return candidates.toList()
    }

    private fun buildUiState(input: UiInput): StatsUiState {
        if (input.hasSensor && input.isLoading && input.historyPoints.isEmpty()) {
            val cacheSerial = input.activeSerial ?: resolveStatsSensorSerial()
            cachedStateFor(cacheSerial, input.range, input.customRange)?.let { cached ->
                return cached.copy(isLoading = true)
            }
        }

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

    private fun buildPinnedState(input: PinnedInput): StatsPinnedState {
        val window = input.window ?: return StatsPinnedState(unit = input.unit, targets = input.targets)
        val projection = resolveRangeProjection(
            history = input.history,
            viewMode = input.viewMode,
            unit = input.unit,
            targets = input.targets,
            activeRange = window.resolveRange(),
            cache = pinnedProjectionCache,
            // The strip's window is a day or so of readings; recomputing the display
            // projection is cheaper than letting two windows fight over one cache slot.
            useDisplayCache = false,
            // Nothing on the strip shows a comparison, and loading the preceding period
            // to compute one would double the very cost this pipeline exists to avoid.
            withComparison = false
        )
        return StatsPinnedState(
            summary = projection.summary,
            targets = input.targets,
            unit = input.unit
        )
    }

    private fun resolveInitialUiState(): StatsUiState {
        return cachedStateFor(resolveStatsSensorSerial(), _selectedRange.value, _customRange.value)
            ?.copy(isLoading = true)
            ?: StatsUiState(selectedRange = _selectedRange.value)
    }

    private fun cachedStateFor(
        serial: String?,
        range: StatsTimeRange?,
        customRange: StatsDateRange?
    ): StatsUiState? {
        val normalizedSerial = serial?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val cached = lastRenderedState ?: return null
        return cached.state
            .takeIf {
                it.summary.readingCount > 0 &&
                    SensorIdentity.matches(cached.serial, normalizedSerial) &&
                    it.selectedRange == range &&
                    (customRange == null || it.activeRange == customRange)
            }
    }

    private fun rememberRenderedState(serial: String?, state: StatsUiState) {
        val normalizedSerial = serial?.trim()?.takeIf { it.isNotEmpty() } ?: return
        if (!state.hasSensor || state.summary.readingCount <= 0) return
        lastRenderedState = CachedRenderedState(
            serial = normalizedSerial,
            state = state.copy(isLoading = false)
        )
    }

    private fun isStatsValueValid(valueMgDl: Float): Boolean {
        return valueMgDl.isFinite() &&
            valueMgDl in MIN_STATS_GLUCOSE_MGDL..MAX_STATS_GLUCOSE_MGDL
    }

    private fun calculateSummary(
        history: List<GlucosePoint>,
        targets: StatsTargets,
        unit: GlucoseUnit,
        activeRange: StatsDateRange?,
        previousScalars: PeriodScalars? = null
    ): StatsSummary {
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
        val p25 = StatsAnalytics.percentile(sortedValues, 0.25f)
        val p75 = StatsAnalytics.percentile(sortedValues, 0.75f)

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
        val variabilityHistory = StatsAnalytics.toVariabilitySeries(history)
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

        // Only what the header, ring and scalar tiles need is worked out here. Everything
        // below is a full pass over the window and goes into the deferred half.
        val chronological = StatsAnalytics.sortedByTimestampIfNeeded(history)
        val coverage = StatsAnalytics.sensorCoverage(chronological, activeRange)

        return StatsSummary(
            readingCount = count,
            avgMgDl = avg,
            p25MgDl = p25,
            medianMgDl = median,
            p75MgDl = p75,
            stdDevMgDl = stdDev,
            cvPercent = cv,
            gmiPercent = gmi,
            minMgDl = min,
            maxMgDl = max,
            firstTimestamp = history.first().timestamp,
            lastTimestamp = history.last().timestamp,
            tir = tir,
            tightRangePercent = StatsAnalytics.tightRangePercent(values, targets),
            coverage = coverage,
            parts = LazyStatsParts(
                demanded = demandedParts,
                history = history,
                chronological = chronological,
                variabilityHistory = variabilityHistory,
                values = values,
                targets = targets,
                unit = unit,
                tir = tir,
                coverage = coverage,
                avg = avg,
                cv = cv,
                variabilityAvg = variabilityAvg,
                variabilityStdDev = variabilityStdDev,
                variabilityCv = variabilityCv,
                targetLow = targetLow,
                targetHigh = targetHigh,
                previousScalars = previousScalars,
                agpOf = ::calculateAgpByHour,
                dailyOf = ::calculateDailyStats,
                insightsOf = ::buildInsights
            )
        )
    }

    /**
     * Resolves each expensive part on first read, once, and never again for that summary.
     *
     * `by lazy` rather than a hand-rolled cache because the dependencies between these are
     * real — the insights need the episodes, which need the coverage — and lazy chaining
     * expresses that without anyone having to remember the order.
     */
    private enum class StatsPart {
        GVI, PSG, MAGE, MODD, DAWN, STREAK, GRI, RISK, EPISODES, LOW_EPISODES, HIGH_EPISODES,
        DAY_PARTS, WEEKDAYS, DAYS, COMPARISON, AGP, HOURLY, DAILY, INSIGHTS
    }

    private class LazyStatsParts(
        private val demanded: MutableSet<StatsPart>,
        private val history: List<GlucosePoint>,
        private val chronological: List<GlucosePoint>,
        private val variabilityHistory: List<GlucosePoint>,
        private val values: List<Float>,
        private val targets: StatsTargets,
        private val unit: GlucoseUnit,
        private val tir: TimeInRangeBreakdown,
        private val coverage: SensorCoverage,
        private val avg: Float,
        private val cv: Float,
        private val variabilityAvg: Float,
        private val variabilityStdDev: Float,
        private val variabilityCv: Float,
        private val targetLow: Float,
        private val targetHigh: Float,
        private val previousScalars: PeriodScalars?,
        private val agpOf: (List<GlucosePoint>) -> List<AgpHourBin>,
        private val dailyOf: (List<GlucosePoint>, Float, Float) -> List<DailyStats>,
        private val insightsOf: (List<StatsFinding>, GlucoseUnit) -> List<StatsInsight>
    ) : StatsSummaryParts {

        private val gviValue = lazy {
            StatsAnalytics.calculateGvi(
                history = variabilityHistory,
                averageMgDl = variabilityAvg,
                stdDevMgDl = variabilityStdDev
            )
        }
        private val psgValue = lazy {
            StatsAnalytics.calculatePsg(
                history = variabilityHistory,
                averageMgDl = avg,
                cvPercent = variabilityCv,
                targets = targets
            )
        }
        private val mageValue = lazy { StatsAnalytics.mage(chronological) }
        private val moddValue = lazy { StatsAnalytics.modd(chronological) }
        private val dawnValue = lazy { StatsAnalytics.dawnRise(chronological) }
        private val streakValue = lazy { StatsAnalytics.bestInRangeStreak(daysValue.value) }
        private val griValue = lazy { StatsAnalytics.glycemiaRiskIndex(values, targets) }
        private val riskValue = lazy { StatsAnalytics.riskIndices(values) }
        private val episodesValue = lazy { StatsAnalytics.detectEpisodes(chronological, targets) }
        private val windowDays: Float get() = coverage.windowDays.coerceAtLeast(1f)
        private val lowEpisodesValue = lazy {
            StatsAnalytics.summarizeEpisodes(episodesValue.value, EpisodeKind.LOW, windowDays)
        }
        private val highEpisodesValue = lazy {
            StatsAnalytics.summarizeEpisodes(episodesValue.value, EpisodeKind.HIGH, windowDays)
        }
        private val dayPartsValue = lazy { StatsAnalytics.dayPartStats(chronological, targets) }
        private val weekdaysValue = lazy { StatsAnalytics.weekdayStats(chronological, targets) }
        private val daysValue = lazy { StatsAnalytics.dayBreakdowns(chronological, targets) }
        private val comparisonValue = lazy {
            previousScalars?.let { previous ->
                StatsAnalytics.compare(StatsAnalytics.periodScalars(values, targets), previous)
            }
        }
        private val agpValue = lazy { agpOf(history) }
        private val hourlyValue = lazy { StatsAnalytics.hourlyStats(chronological, targets) }
        private val dailyValue = lazy { dailyOf(history, targetLow, targetHigh) }
        private val insightsValue = lazy {
            insightsOf(
                StatsAnalytics.findings(
                    FindingInput(
                        tir = tir,
                        averageMgDl = avg,
                        cvPercent = cv,
                        coverage = coverage,
                        lowEpisodes = lowEpisodesValue.value,
                        highEpisodes = highEpisodesValue.value,
                        dayParts = dayPartsValue.value,
                        days = daysValue.value,
                        comparison = comparisonValue.value
                    )
                ),
                unit
            )
        }

        private fun <T> read(part: StatsPart, value: Lazy<T>): T {
            // Noting what was actually asked for is what keeps the *next* summary off the
            // main thread: a part is computed there once, the first time a card scrolls
            // into view, and from then on the view model knows to work it out in the
            // background before the state is handed over.
            demanded += part
            return value.value
        }

        override val gvi: GviScore get() = read(StatsPart.GVI, gviValue)
        override val psg: PsgScore get() = read(StatsPart.PSG, psgValue)
        override val mageMgDl: Float get() = read(StatsPart.MAGE, mageValue)
        override val moddMgDl: Float get() = read(StatsPart.MODD, moddValue)
        override val dawnRiseMgDl: Float get() = read(StatsPart.DAWN, dawnValue)
        override val bestStreakDays: Int get() = read(StatsPart.STREAK, streakValue)
        override val gri: GlycemiaRiskIndex get() = read(StatsPart.GRI, griValue)
        override val risk: RiskIndices get() = read(StatsPart.RISK, riskValue)
        override val episodes: List<GlucoseEpisode> get() = read(StatsPart.EPISODES, episodesValue)
        override val lowEpisodes: EpisodeSummary get() = read(StatsPart.LOW_EPISODES, lowEpisodesValue)
        override val highEpisodes: EpisodeSummary get() = read(StatsPart.HIGH_EPISODES, highEpisodesValue)
        override val dayParts: List<DayPartStats> get() = read(StatsPart.DAY_PARTS, dayPartsValue)
        override val weekdays: List<WeekdayStats> get() = read(StatsPart.WEEKDAYS, weekdaysValue)
        override val days: List<DayBreakdown> get() = read(StatsPart.DAYS, daysValue)
        override val comparison: StatsComparison? get() = read(StatsPart.COMPARISON, comparisonValue)
        override val agpByHour: List<AgpHourBin> get() = read(StatsPart.AGP, agpValue)
        override val hourlyStats: List<HourlyGlucoseStats> get() = read(StatsPart.HOURLY, hourlyValue)
        override val dailyStats: List<DailyStats> get() = read(StatsPart.DAILY, dailyValue)
        override val insights: List<StatsInsight> get() = read(StatsPart.INSIGHTS, insightsValue)

        /**
         * Resolves what the previous composition actually read, while still off the main
         * thread, and hands the record back empty so this composition can restate it.
         *
         * Taking and clearing in one go is what stops the set growing: a metric behind the
         * fold, or a card scrolled past, is not composed, so it is not read, so it drops
         * straight back out and is never computed again until it is put back on screen.
         */
        fun warmDemanded() {
            val toWarm = demanded.toSet()
            demanded.clear()
            toWarm.forEach { part ->
                when (part) {
                    StatsPart.GVI -> gviValue.value
                    StatsPart.PSG -> psgValue.value
                    StatsPart.MAGE -> mageValue.value
                    StatsPart.MODD -> moddValue.value
                    StatsPart.DAWN -> dawnValue.value
                    StatsPart.STREAK -> streakValue.value
                    StatsPart.GRI -> griValue.value
                    StatsPart.RISK -> riskValue.value
                    StatsPart.EPISODES -> episodesValue.value
                    StatsPart.LOW_EPISODES -> lowEpisodesValue.value
                    StatsPart.HIGH_EPISODES -> highEpisodesValue.value
                    StatsPart.DAY_PARTS -> dayPartsValue.value
                    StatsPart.WEEKDAYS -> weekdaysValue.value
                    StatsPart.DAYS -> daysValue.value
                    StatsPart.COMPARISON -> comparisonValue.value
                    StatsPart.AGP -> agpValue.value
                    StatsPart.HOURLY -> hourlyValue.value
                    StatsPart.DAILY -> dailyValue.value
                    StatsPart.INSIGHTS -> insightsValue.value
                }
            }
        }
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
                    p10MgDl = StatsAnalytics.percentile(sorted, 0.10f),
                    p25MgDl = StatsAnalytics.percentile(sorted, 0.25f),
                    medianMgDl = StatsAnalytics.percentile(sorted, 0.50f),
                    p75MgDl = StatsAnalytics.percentile(sorted, 0.75f),
                    p90MgDl = StatsAnalytics.percentile(sorted, 0.90f),
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

    private fun buildInsights(findings: List<StatsFinding>, unit: GlucoseUnit): List<StatsInsight> {
        val context = Applic.app
        val isMmol = unit == GlucoseUnit.MMOL

        fun glucose(valueMgDl: Float): String =
            GlucoseFormatter.formatFromMgDl(valueMgDl = valueMgDl, isMmol = isMmol)

        fun hour(value: Int): String =
            String.format(Locale.getDefault(), "%02d:00", value.coerceIn(0, 24))

        fun duration(minutes: Int): String {
            val hours = minutes / 60
            val rest = minutes % 60
            return when {
                hours == 0 -> context.getString(R.string.stats_duration_minutes, rest)
                rest == 0 -> context.getString(R.string.stats_duration_hours, hours)
                else -> context.getString(R.string.stats_duration_hours_minutes, hours, rest)
            }
        }

        return findings.mapNotNull { finding ->
            when (finding.kind) {
                FindingKind.SPARSE_COVERAGE -> StatsInsight(
                    title = context.getString(R.string.stats_finding_coverage),
                    message = context.getString(
                        R.string.stats_finding_coverage_desc,
                        finding.primary.roundToInt()
                    ),
                    severity = finding.severity
                )

                FindingKind.LOWS_CLUSTER -> StatsInsight(
                    title = context.getString(
                        R.string.stats_finding_lows_cluster,
                        hour(finding.hour),
                        hour(finding.hour + 4)
                    ),
                    message = context.getString(
                        R.string.stats_finding_lows_cluster_desc,
                        finding.primary.roundToInt(),
                        finding.secondary.roundToInt()
                    ),
                    severity = finding.severity
                )

                FindingKind.SEVERE_LOWS -> StatsInsight(
                    title = context.getString(R.string.stats_finding_severe_lows),
                    message = context.getString(
                        R.string.stats_finding_severe_lows_desc,
                        finding.primary.roundToInt(),
                        duration(finding.secondary.roundToInt())
                    ),
                    severity = finding.severity
                )

                FindingKind.LONG_HIGHS -> StatsInsight(
                    title = context.getString(R.string.stats_finding_long_highs),
                    message = context.getString(
                        R.string.stats_finding_long_highs_desc,
                        duration((finding.primary * 60f).roundToInt()),
                        finding.secondary.roundToInt()
                    ),
                    severity = finding.severity
                )

                FindingKind.DAYPART_HIGH, FindingKind.DAYPART_LOW -> {
                    val part = finding.dayPart ?: return@mapNotNull null
                    val titleRes = if (finding.kind == FindingKind.DAYPART_HIGH) {
                        R.string.stats_finding_daypart_high
                    } else {
                        R.string.stats_finding_daypart_low
                    }
                    StatsInsight(
                        title = context.getString(titleRes, context.getString(part.pluralLabelResId)),
                        message = context.getString(
                            R.string.stats_finding_daypart_desc,
                            glucose(finding.primary),
                            glucose(finding.secondary)
                        ),
                        severity = finding.severity
                    )
                }

                FindingKind.TREND_UP -> StatsInsight(
                    title = context.getString(R.string.stats_finding_trend_up),
                    message = context.getString(
                        R.string.stats_finding_trend_up_desc,
                        finding.primary.roundToInt(),
                        finding.secondary.roundToInt()
                    ),
                    severity = finding.severity
                )

                FindingKind.TREND_DOWN -> StatsInsight(
                    title = context.getString(R.string.stats_finding_trend_down),
                    message = context.getString(
                        R.string.stats_finding_trend_down_desc,
                        finding.primary.roundToInt(),
                        finding.secondary.roundToInt()
                    ),
                    severity = finding.severity
                )

                FindingKind.STEADY_STREAK -> StatsInsight(
                    title = context.getString(R.string.stats_finding_steady),
                    message = context.getString(
                        R.string.stats_finding_steady_desc,
                        finding.primary.roundToInt()
                    ),
                    severity = finding.severity
                )

                FindingKind.ROUGH_DAYS -> StatsInsight(
                    title = context.getString(R.string.stats_finding_rough_days),
                    message = context.getString(
                        R.string.stats_finding_rough_days_desc,
                        finding.primary.roundToInt(),
                        finding.secondary.roundToInt()
                    ),
                    severity = finding.severity
                )

                FindingKind.VARIABILITY -> StatsInsight(
                    title = context.getString(R.string.stats_finding_variability),
                    message = context.getString(
                        R.string.stats_finding_variability_desc,
                        finding.primary.roundToInt()
                    ),
                    severity = finding.severity
                )

                FindingKind.ON_TARGET -> StatsInsight(
                    title = context.getString(R.string.stats_finding_on_target),
                    message = context.getString(
                        R.string.stats_finding_on_target_desc,
                        finding.primary.roundToInt(),
                        finding.secondary.roundToInt()
                    ),
                    severity = finding.severity
                )
            }
        }.distinctBy { it.title }.take(MAX_INSIGHTS)
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
        val activeSerial: String?,
        val historyPoints: List<GlucosePoint>,
        val temperaturePoints: List<TemperaturePoint>
    )

    private data class PinnedInput(
        val unit: GlucoseUnit,
        val targets: StatsTargets,
        val viewMode: Int,
        val calibrationRevision: Long,
        val history: List<GlucosePoint>,
        val window: PinnedWindow?
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
        private const val MIN_STATS_GLUCOSE_MGDL = 30f
        private const val MAX_STATS_GLUCOSE_MGDL = 500f
        private const val MAX_REPORT_DAYS = 365

        /** Longest window we pull a second copy of, to compare against the period before. */
        private const val COMPARISON_MAX_RANGE_DAYS = 30

        /** Below this, a period-over-period delta is noise dressed up as a trend. */
        private const val MIN_COMPARISON_READINGS = 24

        /** The previous window needs this share of the current window's readings to count. */
        private const val MIN_COMPARISON_COVERAGE = 0.6f

        private val DEFAULT_STATS_RANGE = StatsTimeRange.DAY_14
        @Volatile private var lastRenderedState: CachedRenderedState? = null
    }
}
