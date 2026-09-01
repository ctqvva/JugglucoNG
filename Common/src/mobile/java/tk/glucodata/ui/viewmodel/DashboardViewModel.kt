package tk.glucodata.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.os.SystemClock
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import tk.glucodata.Natives
import tk.glucodata.UiRefreshBus
import tk.glucodata.Applic
import tk.glucodata.BatteryTrace
import tk.glucodata.CurrentDisplaySource
import tk.glucodata.DataSmoothing
import tk.glucodata.MultiSensorSelection
import tk.glucodata.Notify
import tk.glucodata.SensorBluetooth
import tk.glucodata.ManagedCurrentSensor
import tk.glucodata.SensorIdentity
import tk.glucodata.data.GlucoseRepository
import tk.glucodata.data.HistorySync
import tk.glucodata.data.journal.AapsJournalImport
import tk.glucodata.data.journal.JournalEntry
import tk.glucodata.data.journal.JournalEntryInput
import tk.glucodata.data.journal.JournalFood
import tk.glucodata.data.journal.JournalFoodInput
import tk.glucodata.data.journal.JournalInsulinPreset
import tk.glucodata.data.journal.JournalInsulinPresetInput
import tk.glucodata.data.prediction.DoseTarget
import tk.glucodata.data.prediction.PredictionModelProfile
import tk.glucodata.data.prediction.PredictionModelProfileStore
import tk.glucodata.ui.GlucosePoint
import tk.glucodata.ui.util.inDisplayUnit
import tk.glucodata.data.journal.JournalRepository
import tk.glucodata.alerts.AlertRepository
import tk.glucodata.alerts.CustomAlertRepository
import tk.glucodata.drivers.ManagedSensorRuntime
import tk.glucodata.drivers.ManagedSensorStatusPolicy
import tk.glucodata.drivers.ManagedSensorUiFamily
import tk.glucodata.ui.util.resolveDashboardSensorStatus
import kotlin.math.roundToInt

internal object DashboardHistoryCollectionPolicy {
    /**
     * Whether to also run the unbounded stream that backs the History browse
     * screen.
     *
     * This used to be called `usesMergedCrossSensorHistory`, which stopped being
     * what it means: the dashboard chart is merged across sensors too now. What
     * still separates the two is the bound — the dashboard queries the visible
     * window, the History route queries everything.
     */
    fun runsUnboundedHistoryStream(mode: DashboardViewModel.CollectionMode): Boolean =
        mode == DashboardViewModel.CollectionMode.FULL_HISTORY

    fun shouldCoalesceEmission(mode: DashboardViewModel.CollectionMode, hasSeenHistoryEmission: Boolean): Boolean =
        mode == DashboardViewModel.CollectionMode.DASHBOARD && hasSeenHistoryEmission

    /**
     * Whether the current sensor's stored history is behind enough to ask native
     * for a re-sync — the pure core of `shouldRequestHistoryRecovery`.
     *
     * Every argument describes **the current sensor**. That is not a detail: the
     * caller used to pass the dashboard's drawn history, which is now merged
     * across sensors, and a retired sensor's newer rows would answer this
     * question on the live sensor's behalf — reporting it up to date while it sat
     * silent, so no re-sync was ever requested.
     *
     * @param requiredStartMs history must reach back at least this far; 0 means
     *   no coverage requirement (a new sensor legitimately has no old rows).
     * @param currentSnapshotTimeMs the live reading's time, or 0 when there is
     *   none to compare against.
     * @param snapshotIsSameSensor whether that live reading is this sensor's. A
     *   reading from another sensor says nothing about this one's staleness.
     */
    fun shouldRequestHistoryRecovery(
        requiredStartMs: Long,
        oldestStoredMs: Long?,
        latestStoredMs: Long?,
        currentSnapshotTimeMs: Long,
        snapshotIsSameSensor: Boolean,
        coverageToleranceMs: Long,
        tailToleranceMs: Long
    ): Boolean {
        if (oldestStoredMs == null || latestStoredMs == null) return true
        if (requiredStartMs > 0L && oldestStoredMs > (requiredStartMs + coverageToleranceMs)) return true
        if (currentSnapshotTimeMs <= 0L || !snapshotIsSameSensor) return false
        return currentSnapshotTimeMs > (latestStoredMs + tailToleranceMs)
    }
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DashboardViewModel(
    private val glucoseRepository: GlucoseRepository = GlucoseRepository(),
    private val journalRepository: JournalRepository = JournalRepository(),
    private val historyRepository: tk.glucodata.data.HistoryRepository = tk.glucodata.data.HistoryRepository()
) : ViewModel() {
    private data class HistoryEdgeSignature(
        val size: Int,
        val firstTimestamp: Long,
        val lastTimestamp: Long,
        val sampleHash: Int,
        val lastValueBits: Int,
        val lastRawBits: Int,
        val lastSerial: String?,
        /**
         * How many points carry a credible interval, plus the newest one's
         * bounds.
         *
         * Without this the cache is blind to uncertainty: a reading and its
         * interval arrive in separate emissions, the second differs only in
         * the band, the signature is unchanged, and the cached band-less list
         * is returned — so the ribbon permanently stopped short of the live
         * edge. Cheap to compute and it makes the cache key describe
         * everything the chart actually draws.
         */
        val uncertaintyCount: Int,
        val lastUncertaintyBits: Long
    )

    private data class DashboardHistoryCacheKey(
        val signature: HistoryEdgeSignature,
        val unit: String
    )

    private data class MultiSensorHistoryQueryConfig(
        val unit: String,
        val primarySensorId: String?,
        val selectedSensorIds: List<String>,
        val sensorViewModes: Map<String, Int>
    )

    /** Display-unit peer history plus the peer ids it covers. */
    private data class PeerRawHistory(
        val selectedSensorIds: List<String>,
        val peerIds: List<String>,
        val points: List<tk.glucodata.ui.GlucosePoint>
    ) {
        companion object {
            val EMPTY = PeerRawHistory(emptyList(), emptyList(), emptyList())
        }
    }

    /** Latest displayable reading of a peer (non-primary) selected sensor. */
    data class PeerCurrentReading(
        val sensorId: String,
        val primaryStr: String,
        val secondaryStr: String?,
        val rate: Float,
        val timeMillis: Long
    )

    private companion object {
        const val TARGET_RANGE_DEFAULTS_MIGRATION_KEY = "target_range_defaults_v2"
        const val UI_RECOVERY_SYNC_MIN_INTERVAL_MS = 30_000L

        /**
         * How far back [currentSensorTail] reaches.
         *
         * Long enough for the trend regression, the Δ interval and the recovery
         * check to have their inputs even after a gap, and short enough that it
         * stays a handful of rows rather than a second scan of the store.
         */
        const val CURRENT_SENSOR_TAIL_WINDOW_MS = 6L * 60L * 60L * 1000L
        const val DASHBOARD_HISTORY_COALESCE_MS = 300L
        // Drivers request a data refresh per delivered batch, and dowithglucose
        // requests one per non-main-sensor reading, so with three sensors these
        // arrive far faster than the dashboard can usefully redraw. Matches the
        // 1 s gate GlucoseUpdateBroadcaster already applies to the same signal.
        private const val DASHBOARD_DATA_REFRESH_COALESCE_MS = 1_000L
        const val HISTORY_RECOVERY_TOLERANCE_MS = 5L * 60L * 1000L
        const val HISTORY_RECOVERY_TAIL_TOLERANCE_MS = 2L * 60L * 1000L
        const val DASHBOARD_PEER_HISTORY_WINDOW_MS = 72L * 60L * 60L * 1000L
        const val JOURNAL_DOSE_CALCULATOR_KEY = "dashboard_journal_dose_calculator_enabled"
        const val JOURNAL_NAVIGATION_TAB_KEY = "dashboard_journal_navigation_tab_enabled"
        const val JOURNAL_FOOD_MACROS_KEY = "dashboard_journal_food_macros_enabled"
        const val JOURNAL_FOOD_LIBRARY_KEY = "dashboard_journal_food_library_enabled"
        const val JOURNAL_EIOB_DISPLAY_KEY = "dashboard_journal_eiob_display_enabled"
        const val JOURNAL_QUICKADD_ALWAYS_NOW_KEY = "dashboard_journal_quickadd_always_now"
        const val JOURNAL_DASHBOARD_QUICKADD_KEY = "dashboard_journal_quickadd_button"
        const val GLUCOSE_RANGE_COLORS_KEY = "glucose_value_range_colors_enabled"
        const val ARROW_FORECAST_COLORS_KEY = "glucose_arrow_forecast_colors_enabled"
        const val CHART_RANGE_COLORS_KEY = "glucose_chart_range_colors_enabled"
        const val APP_CHART_RANGE_COLORS_KEY = "glucose_app_chart_range_colors_enabled"
        const val DASHBOARD_SHOW_DELTA_KEY = "dashboard_show_delta"
        const val DASHBOARD_ROWS_SHOW_DELTA_KEY = "dashboard_rows_show_delta"
        const val DELTA_INTERVAL_KEY = "delta_interval_minutes"
        const val JOURNAL_HEALTH_CONNECT_ACTIVITY_KEY = "dashboard_journal_health_connect_activity_enabled"
        const val PREDICTION_CARB_RATIO_KEY = "dashboard_prediction_carb_ratio_g_per_u"
        const val PREDICTION_INSULIN_SENSITIVITY_KEY = "dashboard_prediction_insulin_sensitivity_mgdl_per_u"
        const val PREDICTION_HORIZON_MINUTES_KEY = "dashboard_prediction_horizon_minutes"
        const val PREDICTION_NOTIFICATION_CHART_KEY = "dashboard_prediction_notification_chart_enabled"
        const val PREDICTION_DOSE_TARGET_KEY = "dashboard_prediction_dose_target_mgdl"
        const val PREDICTION_CARB_RATIO_DEFAULT = 10f
        const val PREDICTION_INSULIN_SENSITIVITY_DEFAULT = 54f
        const val PREDICTION_CARB_ABSORPTION_DEFAULT = 35f
        const val PREDICTION_HORIZON_MINUTES_DEFAULT = 120

        private val processUiRecoveryLock = Any()
        private val processHistoryRecoveryLock = Any()
        private val processDashboardHistoryCacheLock = Any()

        @Volatile
        private var processLastUiRecoverySyncAtMs = 0L
        @Volatile
        private var processLastHistoryRecoverySyncAtMs = 0L
        @Volatile
        private var processLastHistoryRecoverySerial: String? = null
        @Volatile
        private var processDashboardHistoryCacheKey: DashboardHistoryCacheKey? = null
        @Volatile
        private var processDashboardHistoryCacheValue: List<GlucosePoint> = emptyList()
    }

    enum class CollectionMode {
        INACTIVE,
        DASHBOARD,
        FULL_HISTORY
    }

    private val _currentGlucose = MutableStateFlow("---")
    val currentGlucose = _currentGlucose.asStateFlow()

    @Volatile
    private var lastUiRecoverySyncAtMs = 0L
    @Volatile
    private var lastHistoryRecoverySyncAtMs = 0L
    @Volatile
    private var lastHistoryRecoverySerial: String? = null

    private val _isLoading = MutableStateFlow(true)
    val isLoading = _isLoading.asStateFlow()

    private val _currentRate = MutableStateFlow(0f)
    val currentRate = _currentRate.asStateFlow()

    private val _sensorName = MutableStateFlow("")
    val sensorName = _sensorName.asStateFlow()

    private val _activeSensorList = MutableStateFlow<List<String>>(emptyList())
    val activeSensorList = _activeSensorList.asStateFlow()

    private val _sensorStatus = MutableStateFlow("")
    val sensorStatus = _sensorStatus.asStateFlow()

    private val _daysRemaining = MutableStateFlow("")
    val daysRemaining = _daysRemaining.asStateFlow()

    private val _sensorProgress = MutableStateFlow(0f)
    val sensorProgress = _sensorProgress.asStateFlow()

    private val _xDripBroadcastEnabled = MutableStateFlow(false)
    val xDripBroadcastEnabled = _xDripBroadcastEnabled.asStateFlow()

    private val _patchedLibreBroadcastEnabled = MutableStateFlow(false)
    val patchedLibreBroadcastEnabled = _patchedLibreBroadcastEnabled.asStateFlow()

    private val _glucodataBroadcastEnabled = MutableStateFlow(false)
    val glucodataBroadcastEnabled = _glucodataBroadcastEnabled.asStateFlow()

    private val _broadcastComputedTrend = MutableStateFlow(false)
    val broadcastComputedTrend = _broadcastComputedTrend.asStateFlow()

    /**
     * The chart's line: every sensor that covers the visible window, merged.
     *
     * Not the same list as [currentSensorTail], and the difference is the whole
     * point — see that property.
     */
    private val _glucoseHistory = MutableStateFlow<List<tk.glucodata.ui.GlucosePoint>>(emptyList())
    val glucoseHistory = _glucoseHistory.asStateFlow()

    /**
     * The current sensor's own recent readings, never merged with any other.
     *
     * Deliberately not a UI input. The one thing that needs it is the native
     * history-recovery check: it asks "is this sensor behind?", and the merged
     * list the dashboard draws can answer with a *different* sensor's newer
     * reading, so a live sensor that has gone quiet looks up to date and no
     * re-sync is requested.
     *
     * Everything the user sees — rows, hero, trend, Δ, prediction — reads
     * [glucoseHistory], as it always has. Repointing those at this list changed
     * which reading each row showed and how often the list identity churned, and
     * regressed both. If you are about to wire a visible surface to this, that is
     * the reason not to.
     */
    private val _currentSensorTail = MutableStateFlow<List<tk.glucodata.ui.GlucosePoint>>(emptyList())

    private val _multiSensorDisplay =
        MutableStateFlow(tk.glucodata.ui.MultiSensorDisplayData.EMPTY)
    val multiSensorDisplay = _multiSensorDisplay.asStateFlow()

    // Raw (display-unit) peer history kept separate from view-mode resolution so
    // the display data rebuilds when a peer's auto/raw mode changes natively,
    // without re-querying Room.
    private val _multiSensorRawHistory = MutableStateFlow(PeerRawHistory.EMPTY)

    private val _peerCurrentReadings = MutableStateFlow<List<PeerCurrentReading>>(emptyList())
    val peerCurrentReadings = _peerCurrentReadings.asStateFlow()

    private val _selectedSensorIds = MutableStateFlow<List<String>>(emptyList())
    val selectedSensorIds = _selectedSensorIds.asStateFlow()

    private val _sensorViewModes = MutableStateFlow<Map<String, Int>>(emptyMap())
    val sensorViewModes = _sensorViewModes.asStateFlow()

    /**
     * Cross-sensor merged history for the History browse screen. Includes
     * previous sensor calibrated readings, CSV imports, and older device data,
     * so the History route remains complete across sensor swaps. The live
     * dashboard intentionally uses the per-sensor [glucoseHistory] above.
     */
    private val _historyScreenGlucoseHistory =
        MutableStateFlow<List<tk.glucodata.ui.GlucosePoint>>(emptyList())
    val historyScreenGlucoseHistory = _historyScreenGlucoseHistory.asStateFlow()

    private val _unit = MutableStateFlow("mg/dL")
    val unit = _unit.asStateFlow()

    private val _targetLow = MutableStateFlow(70f)
    val targetLow = _targetLow.asStateFlow()

    private val _targetHigh = MutableStateFlow(180f)
    val targetHigh = _targetHigh.asStateFlow()

    private val _veryLowThreshold = MutableStateFlow(54f)
    val veryLowThreshold = _veryLowThreshold.asStateFlow()

    private val _veryHighThreshold = MutableStateFlow(250f)
    val veryHighThreshold = _veryHighThreshold.asStateFlow()

    private val _graphLow = MutableStateFlow(40f)
    val graphLow = _graphLow.asStateFlow()

    private val _graphHigh = MutableStateFlow(240f)
    val graphHigh = _graphHigh.asStateFlow()

    private val _viewMode = MutableStateFlow(0)
    val viewMode = _viewMode.asStateFlow()

    private val _sensorHoursRemaining = MutableStateFlow(999L)
    val sensorHoursRemaining = _sensorHoursRemaining.asStateFlow()

    private val _currentDay = MutableStateFlow(0)
    val currentDay = _currentDay.asStateFlow()

    // Alarm States
    private val _hasLowAlarm = MutableStateFlow(false)
    val hasLowAlarm = _hasLowAlarm.asStateFlow()

    private val _lowAlarmThreshold = MutableStateFlow(0f)
    val lowAlarmThreshold = _lowAlarmThreshold.asStateFlow()

    private val _hasHighAlarm = MutableStateFlow(false)
    val hasHighAlarm = _hasHighAlarm.asStateFlow()

    private val _highAlarmThreshold = MutableStateFlow(0f)
    val highAlarmThreshold = _highAlarmThreshold.asStateFlow()

    // New Setting: Notification Chart Toggle
    private val _notificationChartEnabled = MutableStateFlow(true)
    val notificationChartEnabled = _notificationChartEnabled.asStateFlow()

    private val _chartSmoothingMinutes = MutableStateFlow(0)
    val chartSmoothingMinutes = _chartSmoothingMinutes.asStateFlow()

    private val _dataSmoothingGraphOnly = MutableStateFlow(true)
    val dataSmoothingGraphOnly = _dataSmoothingGraphOnly.asStateFlow()

    private val _dataSmoothingCollapseChunks = MutableStateFlow(false)
    val dataSmoothingCollapseChunks = _dataSmoothingCollapseChunks.asStateFlow()

    private val _dataSmoothingExchangeOnly = MutableStateFlow(false)
    val dataSmoothingExchangeOnly = _dataSmoothingExchangeOnly.asStateFlow()

    private val _previewWindowMode = MutableStateFlow(0)
    val previewWindowMode = _previewWindowMode.asStateFlow()

    private val _journalEnabled = MutableStateFlow(true)
    val journalEnabled = _journalEnabled.asStateFlow()

    private val _journalNavigationTabEnabled = MutableStateFlow(false)
    val journalNavigationTabEnabled = _journalNavigationTabEnabled.asStateFlow()

    private val _journalDoseCalculatorEnabled = MutableStateFlow(false)
    val journalDoseCalculatorEnabled = _journalDoseCalculatorEnabled.asStateFlow()

    private val _journalFoodMacrosEnabled = MutableStateFlow(false)
    val journalFoodMacrosEnabled = _journalFoodMacrosEnabled.asStateFlow()

    private val _journalFoodLibraryEnabled = MutableStateFlow(true)
    val journalFoodLibraryEnabled = _journalFoodLibraryEnabled.asStateFlow()

    private val _journalEiobDisplayEnabled = MutableStateFlow(true)
    val journalEiobDisplayEnabled = _journalEiobDisplayEnabled.asStateFlow()

    private val _journalQuickAddAlwaysNow = MutableStateFlow(false)
    val journalQuickAddAlwaysNow = _journalQuickAddAlwaysNow.asStateFlow()

    private val _journalDashboardQuickAddButton = MutableStateFlow(false)
    val journalDashboardQuickAddButton = _journalDashboardQuickAddButton.asStateFlow()

    private val _glucoseValueRangeColorsEnabled = MutableStateFlow(false)
    val glucoseValueRangeColorsEnabled = _glucoseValueRangeColorsEnabled.asStateFlow()

    private val _glucoseArrowForecastColorsEnabled = MutableStateFlow(false)
    val glucoseArrowForecastColorsEnabled = _glucoseArrowForecastColorsEnabled.asStateFlow()

    private val _glucoseChartRangeColorsEnabled = MutableStateFlow(false)
    val glucoseChartRangeColorsEnabled = _glucoseChartRangeColorsEnabled.asStateFlow()

    private val _glucoseAppChartRangeColorsEnabled = MutableStateFlow(false)
    val glucoseAppChartRangeColorsEnabled = _glucoseAppChartRangeColorsEnabled.asStateFlow()

    private val _dashboardShowDelta = MutableStateFlow(false)
    val dashboardShowDelta = _dashboardShowDelta.asStateFlow()

    private val _dashboardRowsShowDelta = MutableStateFlow(false)
    val dashboardRowsShowDelta = _dashboardRowsShowDelta.asStateFlow()

    private val _deltaIntervalMinutes = MutableStateFlow(tk.glucodata.GlucoseDelta.DEFAULT_INTERVAL_MINUTES)
    val deltaIntervalMinutes = _deltaIntervalMinutes.asStateFlow()

    private val _journalHealthConnectActivityEnabled = MutableStateFlow(false)
    val journalHealthConnectActivityEnabled = _journalHealthConnectActivityEnabled.asStateFlow()

    private val _aapsJournalImportEnabled = MutableStateFlow(false)
    val aapsJournalImportEnabled = _aapsJournalImportEnabled.asStateFlow()

    private val _predictiveSimulationEnabled = MutableStateFlow(true)
    val predictiveSimulationEnabled = _predictiveSimulationEnabled.asStateFlow()

    private val _predictiveSimulationNotificationChartEnabled = MutableStateFlow(true)
    val predictiveSimulationNotificationChartEnabled = _predictiveSimulationNotificationChartEnabled.asStateFlow()

    private val _predictionTrendMomentumEnabled = MutableStateFlow(true)
    val predictionTrendMomentumEnabled = _predictionTrendMomentumEnabled.asStateFlow()

    private val _predictionCarbRatioGramsPerUnit = MutableStateFlow(PREDICTION_CARB_RATIO_DEFAULT)
    val predictionCarbRatioGramsPerUnit = _predictionCarbRatioGramsPerUnit.asStateFlow()

    private val _predictionInsulinSensitivityMgDlPerUnit = MutableStateFlow(PREDICTION_INSULIN_SENSITIVITY_DEFAULT)
    val predictionInsulinSensitivityMgDlPerUnit = _predictionInsulinSensitivityMgDlPerUnit.asStateFlow()

    private val _predictionModelProfile = MutableStateFlow(
        PredictionModelProfile.single(
            PREDICTION_CARB_RATIO_DEFAULT,
            PREDICTION_INSULIN_SENSITIVITY_DEFAULT,
            PREDICTION_CARB_ABSORPTION_DEFAULT
        )
    )
    val predictionModelProfile = _predictionModelProfile.asStateFlow()

    private val _predictionCarbAbsorptionGramsPerHour = MutableStateFlow(PREDICTION_CARB_ABSORPTION_DEFAULT)
    val predictionCarbAbsorptionGramsPerHour = _predictionCarbAbsorptionGramsPerHour.asStateFlow()

    private val _predictionHorizonMinutes = MutableStateFlow(PREDICTION_HORIZON_MINUTES_DEFAULT)
    val predictionHorizonMinutes = _predictionHorizonMinutes.asStateFlow()

    private val _predictionDoseTargetMgDl = MutableStateFlow(DoseTarget.DEFAULT_MGDL)
    val predictionDoseTargetMgDl = _predictionDoseTargetMgDl.asStateFlow()

    private val _journalEntries = MutableStateFlow<List<JournalEntry>>(emptyList())
    val journalEntries = _journalEntries.asStateFlow()

    private val _journalInsulinPresets = MutableStateFlow<List<JournalInsulinPreset>>(emptyList())
    val journalInsulinPresets = _journalInsulinPresets.asStateFlow()

    private val _journalFoods = MutableStateFlow<List<JournalFood>>(emptyList())
    val journalFoods = _journalFoods.asStateFlow()

    private val _lowAlarmSoundMode = MutableStateFlow(0)
    val lowAlarmSoundMode = _lowAlarmSoundMode.asStateFlow()

    private val _highAlarmSoundMode = MutableStateFlow(0)
    val highAlarmSoundMode = _highAlarmSoundMode.asStateFlow()

    private val _alertsSummary = MutableStateFlow("")
    val alertsSummary = _alertsSummary.asStateFlow()

    private val _alertsMasterEnabled = MutableStateFlow(false)
    val alertsMasterEnabled = _alertsMasterEnabled.asStateFlow()

    private var collectionMode = CollectionMode.INACTIVE
    private var currentReadingJob: Job? = null
    private var historyJob: Job? = null
    private var multiSensorHistoryJob: Job? = null
    private var historyScreenJob: Job? = null
    private var uiRefreshJob: Job? = null
    private var journalEntriesJob: Job? = null
    private var journalPresetsJob: Job? = null
    private var journalFoodsJob: Job? = null
    private var currentSensorTailJob: Job? = null
    private var activeHistoryMode: CollectionMode? = null
    private var activeHistoryStartTimeMs: Long? = null

    init {
        _journalEnabled.value = readJournalEnabledPreference()
        observeJournalState()
        // Keep initial UI boot light. Room backfill/targeted sensor sync now cover cold start,
        // so do not force a full native history rebuild during app startup.
        refreshData()
    }

    private fun observeJournalState() {
        ensureJournalPresetsObserved()
        ensureJournalFoodsObserved()
        if (_journalEnabled.value) {
            ensureJournalEntriesObserved()
        }
    }

    private fun ensureJournalEntriesObserved() {
        if (journalEntriesJob?.isActive == true) return
        journalEntriesJob = viewModelScope.launch {
            journalRepository.observeEntries().collect { _journalEntries.value = it }
        }
    }

    private fun stopJournalEntriesObservation() {
        journalEntriesJob?.cancel()
        journalEntriesJob = null
        // The enabled state controls visibility at the UI boundary. Keep the
        // last Room emission while observation is paused so a receiver or
        // sensor-mode refresh cannot masquerade as an empty database.
    }

    private fun ensureJournalPresetsObserved() {
        if (journalPresetsJob?.isActive == true) return
        journalPresetsJob = viewModelScope.launch {
            journalRepository.ensureDefaultInsulinPresets()
            journalRepository.observeInsulinPresets().collect { _journalInsulinPresets.value = it }
        }
    }

    private fun ensureJournalFoodsObserved() {
        if (journalFoodsJob?.isActive == true) return
        journalFoodsJob = viewModelScope.launch {
            journalRepository.ensureDefaultFoods()
            journalRepository.observeFoods().collect { _journalFoods.value = it }
        }
    }

    private fun readJournalEnabledPreference(): Boolean {
        val context = tk.glucodata.Applic.app
        val prefs = context.getSharedPreferences("tk.glucodata_preferences", android.content.Context.MODE_PRIVATE)
        return prefs.getBoolean("dashboard_journal_enabled", true)
    }
    
    /**
     * Called when the app resumes from background.
     * Refreshes data to prevent stale chart state after Home button.
     * Also updates the sensor serial in GlucoseRepository so flows
     * re-subscribe to the correct sensor's data.
     */
    fun onResume() {
        refreshData()
        if (collectionMode != CollectionMode.INACTIVE) {
            ensureUiRefreshCollection()
            ensureCurrentReadingCollection()
            startHistoryCollectionForMode(collectionMode)
            viewModelScope.launch {
                requestUiRecoverySync()
            }
        }
    }

    fun setCollectionMode(mode: CollectionMode) {
        if (collectionMode == mode) return
        collectionMode = mode
        when (mode) {
            CollectionMode.INACTIVE -> stopCollectionJobs()
            CollectionMode.DASHBOARD,
            CollectionMode.FULL_HISTORY -> {
                refreshData()
                ensureUiRefreshCollection()
                ensureCurrentReadingCollection()
                startHistoryCollectionForMode(mode)
                viewModelScope.launch {
                    requestUiRecoverySync()
                }
            }
        }
    }

    private suspend fun requestUiRecoverySync() {
        val nowMs = SystemClock.elapsedRealtime()
        synchronized(processUiRecoveryLock) {
            val lastRunMs = maxOf(lastUiRecoverySyncAtMs, processLastUiRecoverySyncAtMs)
            if ((nowMs - lastRunMs) < UI_RECOVERY_SYNC_MIN_INTERVAL_MS) {
                android.util.Log.d(
                    "DashboardVM",
                    "requestUiRecoverySync skipped — last run was ${(nowMs - lastRunMs)}ms ago"
                )
                return
            }
            lastUiRecoverySyncAtMs = nowMs
            processLastUiRecoverySyncAtMs = nowMs
        }
        val serial = preferredDashboardSensorId()?.takeIf { it.isNotBlank() }
        val historyStartTimeMs = activeHistoryStartTimeMs
        val current = resolveCurrentForHistoryRecovery(serial)
        val shouldPreferHistoryRecovery = serial != null &&
            historyStartTimeMs != null &&
            shouldRequestHistoryRecovery(historyStartTimeMs, _currentSensorTail.value, serial, current)

        if (!shouldPreferHistoryRecovery) {
            glucoseRepository.syncLatestNativeReadingOnce()
        }

        if (shouldPreferHistoryRecovery) {
            requestHistoryRecoverySync(serial, reason = "ui_recovery")
        }
    }

    private fun ensureUiRefreshCollection() {
        if (uiRefreshJob?.isActive == true) return
        uiRefreshJob = viewModelScope.launch {
            // Leading edge fires immediately so a single reading still feels live;
            // a burst collapses into one trailing refresh instead of running
            // refreshDashboardSettings + refreshSensorSnapshot +
            // refreshCurrentDisplaySnapshot once per event.
            var lastDataRefreshMs = 0L
            var trailingRefresh: Job? = null
            UiRefreshBus.events.collect { event ->
                when (event) {
                    UiRefreshBus.Event.DataChanged -> {
                        val now = SystemClock.elapsedRealtime()
                        val sinceLast = now - lastDataRefreshMs
                        if (sinceLast >= DASHBOARD_DATA_REFRESH_COALESCE_MS) {
                            trailingRefresh?.cancel()
                            trailingRefresh = null
                            lastDataRefreshMs = now
                            refreshData()
                        } else if (trailingRefresh?.isActive != true) {
                            trailingRefresh = launch {
                                delay(DASHBOARD_DATA_REFRESH_COALESCE_MS - sinceLast)
                                lastDataRefreshMs = SystemClock.elapsedRealtime()
                                refreshData()
                            }
                        }
                    }
                    UiRefreshBus.Event.StatusOnly -> refreshStatusOnly()
                }
            }
        }
    }

    private fun ensureCurrentReadingCollection() {
        if (currentReadingJob?.isActive == true) return
        currentReadingJob = viewModelScope.launch {
            glucoseRepository.getCurrentReading().collect { point ->
                val preferredSensorId = preferredDashboardSensorId()
                val resolved = CurrentDisplaySource.resolveCurrent(
                    maxAgeMillis = Notify.glucosetimeout,
                    preferredSensorId = preferredSensorId
                )
                if (resolved != null) {
                    _currentGlucose.value = resolved.primaryStr
                    _currentRate.value = resolved.rate.takeIf { it.isFinite() } ?: 0f
                    return@collect
                }
                if (point != null) {
                    val valueToDisplay = if (viewMode.value == 1 || viewMode.value == 3) point.rawValue else point.value
                    _currentGlucose.value = if (valueToDisplay < 30) String.format("%.1f", valueToDisplay) else valueToDisplay.toInt().toString()
                    _currentRate.value = point.rate ?: 0f
                    // Don't append to _glucoseHistory here — the Room Flow in
                    // startHistoryCollectionForMode() handles it. Appending here caused
                    // a triple-write race (append + 24h Flow + full Flow) that
                    // triggered redundant full-screen recompositions.
                }
            }
        }
    }

    fun refreshData() {
        viewModelScope.launch {
            refreshDashboardSettings()
            refreshSensorSnapshot()
            refreshCurrentDisplaySnapshot()
        }
    }

    /**
     * Call after a glucose history import so the dashboard chart shows the
     * imported readings. Only pins the imported serial as the display sensor
     * when there is no current/live sensor to show — never hijacks a live
     * sensor. [displaySerial] may be a synthetic "imported" id for files
     * without a sensor serial.
     */
    fun onHistoryImported(displaySerial: String?) {
        val currentSerial = SensorIdentity.resolveMainSensor()?.takeIf { it.isNotBlank() }
        if (currentSerial == null) {
            // No live sensor: pin the imported serial so the dashboard shows it.
            if (!displaySerial.isNullOrBlank()) {
                ManagedCurrentSensor.set(displaySerial)
                glucoseRepository.refreshSensorSerial(displaySerial)
            }
            refreshData()
        } else {
            // Sensor already present: fold overlapping imported data into it now,
            // then refresh. Never hijacks the live sensor.
            viewModelScope.launch {
                glucoseRepository.reconcileImportedIntoCurrentSensor()
                refreshData()
            }
        }
    }

    private fun refreshStatusOnly() {
        viewModelScope.launch {
            refreshSensorSnapshot()
            refreshCurrentDisplaySnapshot()
        }
    }

    private fun refreshDashboardSettings() {
        val unitVal = Natives.getunit()
        val isMmol = unitVal == 1
        _unit.value = if (isMmol) "mmol/L" else "mg/dL"

        val context = tk.glucodata.Applic.app
        val prefs = context.getSharedPreferences("tk.glucodata_preferences", android.content.Context.MODE_PRIVATE)
        migrateTargetRangeDefaultsIfNeeded(prefs, isMmol)
        _notificationChartEnabled.value = prefs.getBoolean("notification_chart_enabled", true)
        _chartSmoothingMinutes.value = DataSmoothing.getMinutes(context)
        _dataSmoothingGraphOnly.value = DataSmoothing.isGraphOnly(context)
        _dataSmoothingCollapseChunks.value = DataSmoothing.collapseChunks(context)
        _dataSmoothingExchangeOnly.value = DataSmoothing.smoothOnlyExchangeOutputs(context)
        _previewWindowMode.value = prefs.getInt("dashboard_chart_preview_window_mode", 0)
        val journalEnabled = prefs.getBoolean("dashboard_journal_enabled", true)
        _journalEnabled.value = journalEnabled
        _journalNavigationTabEnabled.value = prefs.getBoolean(JOURNAL_NAVIGATION_TAB_KEY, false)
        _journalDoseCalculatorEnabled.value = prefs.getBoolean(JOURNAL_DOSE_CALCULATOR_KEY, false)
        _journalFoodMacrosEnabled.value = prefs.getBoolean(JOURNAL_FOOD_MACROS_KEY, false)
        _journalFoodLibraryEnabled.value = prefs.getBoolean(JOURNAL_FOOD_LIBRARY_KEY, true)
        _journalEiobDisplayEnabled.value = prefs.getBoolean(JOURNAL_EIOB_DISPLAY_KEY, true)
        _journalQuickAddAlwaysNow.value = prefs.getBoolean(JOURNAL_QUICKADD_ALWAYS_NOW_KEY, false)
        _journalDashboardQuickAddButton.value = prefs.getBoolean(JOURNAL_DASHBOARD_QUICKADD_KEY, false)
        _glucoseValueRangeColorsEnabled.value = prefs.getBoolean(GLUCOSE_RANGE_COLORS_KEY, false)
        _glucoseArrowForecastColorsEnabled.value = prefs.getBoolean(ARROW_FORECAST_COLORS_KEY, false)
        _glucoseChartRangeColorsEnabled.value = prefs.getBoolean(CHART_RANGE_COLORS_KEY, false)
        _glucoseAppChartRangeColorsEnabled.value = prefs.getBoolean(APP_CHART_RANGE_COLORS_KEY, false)
        _dashboardShowDelta.value = prefs.getBoolean(DASHBOARD_SHOW_DELTA_KEY, false)
        _dashboardRowsShowDelta.value = prefs.getBoolean(DASHBOARD_ROWS_SHOW_DELTA_KEY, false)
        _deltaIntervalMinutes.value = tk.glucodata.GlucoseDelta.sanitizeIntervalMinutes(
            prefs.getInt(DELTA_INTERVAL_KEY, tk.glucodata.GlucoseDelta.DEFAULT_INTERVAL_MINUTES)
        )
        _journalHealthConnectActivityEnabled.value = prefs.getBoolean(JOURNAL_HEALTH_CONNECT_ACTIVITY_KEY, false)
        _aapsJournalImportEnabled.value = AapsJournalImport.isEnabled(context)
        _predictiveSimulationEnabled.value = prefs.getBoolean("dashboard_predictive_simulation_enabled", true)
        _predictiveSimulationNotificationChartEnabled.value = prefs.getBoolean(PREDICTION_NOTIFICATION_CHART_KEY, true)
        _predictionTrendMomentumEnabled.value = prefs.getBoolean("dashboard_prediction_trend_momentum_enabled", true)
        _predictionCarbRatioGramsPerUnit.value = prefs
            .getFloat(PREDICTION_CARB_RATIO_KEY, PREDICTION_CARB_RATIO_DEFAULT)
            .coerceIn(3f, 30f)
        _predictionInsulinSensitivityMgDlPerUnit.value = prefs
            .getFloat(PREDICTION_INSULIN_SENSITIVITY_KEY, PREDICTION_INSULIN_SENSITIVITY_DEFAULT)
            .coerceIn(10f, 180f)
        _predictionModelProfile.value = PredictionModelProfileStore.load(prefs)
        _predictionCarbRatioGramsPerUnit.value = _predictionModelProfile.value.blocks.first().carbRatioGramsPerUnit
        _predictionInsulinSensitivityMgDlPerUnit.value =
            _predictionModelProfile.value.blocks.first().insulinSensitivityMgDlPerUnit
        _predictionCarbAbsorptionGramsPerHour.value =
            _predictionModelProfile.value.blocks.first().carbAbsorptionGramsPerHour
        _predictionHorizonMinutes.value = prefs
            .getInt(PREDICTION_HORIZON_MINUTES_KEY, PREDICTION_HORIZON_MINUTES_DEFAULT)
            .coerceIn(30, 360)
        _predictionDoseTargetMgDl.value = prefs
            .getFloat(PREDICTION_DOSE_TARGET_KEY, DoseTarget.DEFAULT_MGDL)
            .coerceIn(DoseTarget.MIN_MGDL, DoseTarget.MAX_MGDL)
        if (journalEnabled) {
            ensureJournalEntriesObserved()
        } else if (journalEntriesJob != null) {
            stopJournalEntriesObservation()
        }

        _graphLow.value = Natives.graphlow()
        _graphHigh.value = Natives.graphhigh()
        _targetLow.value = Natives.targetlow()
        _targetHigh.value = Natives.targethigh()
        _veryLowThreshold.value = Natives.alarmverylow()
        _veryHighThreshold.value = Natives.alarmveryhigh()
        _xDripBroadcastEnabled.value = Natives.getxbroadcast()
        _patchedLibreBroadcastEnabled.value = Natives.getlibrelinkused()
        _glucodataBroadcastEnabled.value = Natives.getJugglucobroadcast()
        _broadcastComputedTrend.value = prefs.getBoolean(tk.glucodata.BroadcastTrendRate.PREF_KEY, false)

        _hasLowAlarm.value = Natives.hasalarmlow()
        _lowAlarmThreshold.value = Natives.alarmlow()
        _hasHighAlarm.value = Natives.hasalarmhigh()
        _highAlarmThreshold.value = Natives.alarmhigh()

        _lowAlarmSoundMode.value = if (Natives.alarmhassound(0)) 1 else 0
        _highAlarmSoundMode.value = if (Natives.alarmhassound(1)) 1 else 0

        val anyActive = AlertRepository.loadAllConfigs().any { it.enabled }
            || CustomAlertRepository.getAll().any { it.enabled }
        _alertsMasterEnabled.value = anyActive
        _alertsSummary.value = if (anyActive) {
            context.getString(tk.glucodata.R.string.global_active)
        } else {
            context.getString(tk.glucodata.R.string.global_all_alerts_disabled)
        }
    }

    private fun refreshSensorSnapshot() {
        var sName = SensorIdentity.resolveMainSensor()
        val activeSensors = Natives.activeSensors()

        val cachedSerial = _sensorName.value.takeIf { it.isNotBlank() }
            ?: glucoseRepository.currentSerial.value.takeIf { it.isNotBlank() }
        val fallbackSerial = SensorIdentity.resolveAvailableMainSensor(
            selectedMain = sName,
            preferredSensorId = cachedSerial,
            activeSensors = activeSensors
        ) ?: cachedSerial

        if (sName.isNullOrBlank()) {
            sName = fallbackSerial
        }

        val availableDisplaySensors = availableDisplaySensorIds(activeSensors, sName)
        val selectedDisplaySensors = MultiSensorSelection.selectedAvailable(
            availableSensorIds = availableDisplaySensors,
            primarySensorId = sName
        )
        selectedDisplaySensors.firstOrNull()?.let { selectedPrimary ->
            if (!SensorIdentity.matches(sName, selectedPrimary)) {
                SensorBluetooth.setCurrentSensorSelection(selectedPrimary)
                sName = selectedPrimary
            }
        }
        _selectedSensorIds.value = selectedDisplaySensors
        _activeSensorList.value = selectedDisplaySensors
        _sensorViewModes.value = resolveSelectedSensorViewModes(selectedDisplaySensors)
        schedulePeerCurrentRefresh(selectedDisplaySensors.drop(1))

        if (!sName.isNullOrEmpty() && sName.isNotBlank()) {
            glucoseRepository.refreshSensorSerial(sName)
            _sensorName.value = sName
            val hasNativeBacking = SensorIdentity.hasNativeSensorBacking(sName)
            val managedSnapshot = ManagedSensorRuntime.resolveUiSnapshot(sName, sName)
            val nativeStatus = if (hasNativeBacking) {
                try {
                    Natives.getSensorStatusByName(sName).orEmpty()
                } catch (t: Throwable) {
                    android.util.Log.e("DashboardVM", "getSensorStatusByName failed for '$sName'", t)
                    ""
                }
            } else {
                ""
            }
            val snapshot = if (hasNativeBacking) {
                try {
                    Natives.getSensorUiSnapshot(sName)
                } catch (t: Throwable) {
                    android.util.Log.e("DashboardVM", "getSensorUiSnapshot failed for '$sName'", t)
                    null
                }
            } else {
                null
            }
            val fallbackDurationDays =
                if (managedSnapshot?.uiFamily == ManagedSensorUiFamily.AIDEX ||
                    sName.startsWith("X-", ignoreCase = true)
                ) {
                    15
                } else {
                    14
                }
            if (snapshot != null && snapshot.size >= 5) {
                val sensorKind = snapshot[0].toInt()
                val vm = snapshot[1].toInt()
                val startMsec = snapshot[2]
                val expectedEnd = snapshot[3]
                val officialEnd = snapshot[4]
                _sensorStatus.value = resolveDashboardSensorStatus(sName, sensorKind, startMsec, nativeStatus)

                _viewMode.value = managedSnapshot?.viewMode ?: vm

                val lifecycleOfficialEndMs = managedSnapshot?.officialEndMs?.takeIf { it > 0L }
                    ?: if (managedSnapshot == null) officialEnd else 0L
                val lifecycleExpectedEndMs = managedSnapshot?.expectedEndMs?.takeIf { it > 0L }
                    ?: if (managedSnapshot == null) expectedEnd else 0L
                val lifecycle = ManagedSensorStatusPolicy.resolveLifecycleSummary(
                    startTimeMs = managedSnapshot?.startTimeMs?.takeIf { it > 0L } ?: startMsec,
                    officialEndMs = lifecycleOfficialEndMs,
                    expectedEndMs = lifecycleExpectedEndMs,
                    sensorRemainingHours = managedSnapshot?.sensorRemainingHours ?: -1,
                    sensorAgeHours = managedSnapshot?.sensorAgeHours ?: -1,
                    fallbackDurationDays = fallbackDurationDays,
                    nowMs = System.currentTimeMillis()
                )
                _sensorProgress.value = lifecycle.progress
                _sensorHoursRemaining.value = lifecycle.remainingHours
                _daysRemaining.value = lifecycle.daysText
                _currentDay.value = lifecycle.currentDay
            } else {
                _sensorStatus.value = resolveDashboardSensorStatus(sName, nativeStatus)
                _viewMode.value = managedSnapshot?.viewMode ?: 0
                val lifecycle = ManagedSensorStatusPolicy.resolveLifecycleSummary(
                    startTimeMs = managedSnapshot?.startTimeMs ?: 0L,
                    officialEndMs = managedSnapshot?.officialEndMs ?: 0L,
                    expectedEndMs = managedSnapshot?.expectedEndMs ?: 0L,
                    sensorRemainingHours = managedSnapshot?.sensorRemainingHours ?: -1,
                    sensorAgeHours = managedSnapshot?.sensorAgeHours ?: -1,
                    fallbackDurationDays = fallbackDurationDays,
                    nowMs = System.currentTimeMillis()
                )
                _sensorProgress.value = lifecycle.progress
                _sensorHoursRemaining.value = lifecycle.remainingHours
                _daysRemaining.value = lifecycle.daysText
                _currentDay.value = lifecycle.currentDay
            }
        } else {
            _sensorName.value = ""
            _sensorStatus.value = ""
            _viewMode.value = 0
            _sensorProgress.value = 0f
            _sensorHoursRemaining.value = 999L
            _daysRemaining.value = ""
        }
    }

    private fun availableDisplaySensorIds(
        activeSensors: Array<String?>?,
        primarySensorId: String?
    ): List<String> {
        val candidates = ArrayList<String?>()
        activeSensors?.forEach { candidates.add(it) }
        runCatching {
            SensorBluetooth.mygatts()?.forEach { callback ->
                candidates.add(callback.SerialNumber)
            }
        }
        if (candidates.isEmpty()) {
            candidates.add(primarySensorId)
        }
        return SensorIdentity.distinctLogicalSensorIds(candidates)
    }

    private fun resolveSelectedSensorViewModes(sensorIds: List<String>): Map<String, Int> {
        if (sensorIds.isEmpty()) return emptyMap()
        // Use the same canonical resolution as CurrentDisplaySource.resolveCurrent
        // (managed runtime, then the native per-sensor UI snapshot). The old
        // gatt-dataptr path disagreed with it and made peer rows/chart ignore
        // the per-sensor auto/raw display switch.
        return sensorIds.associateWith { sensorId ->
            runCatching { CurrentDisplaySource.resolveViewModeForSensor(sensorId) }.getOrDefault(0)
        }
    }

    private fun refreshCurrentDisplaySnapshot() {
        refreshCurrentDisplayAfterSmoothingChange()
    }

    private fun startHistoryCollectionForMode(mode: CollectionMode) {
        val recoveryStartTimeMs = when (mode) {
            CollectionMode.INACTIVE -> return
            CollectionMode.DASHBOARD -> 0L
            CollectionMode.FULL_HISTORY -> 0L
        }
        val queryStartTimeMs = when (mode) {
            CollectionMode.INACTIVE -> return
            CollectionMode.DASHBOARD -> 0L
            CollectionMode.FULL_HISTORY -> 0L
        }
        activeHistoryStartTimeMs = recoveryStartTimeMs

        startMultiSensorHistoryCollectionForMode(mode)
        if (historyJob?.isActive == true && activeHistoryMode == mode) return

        historyJob?.cancel()
        historyScreenJob?.cancel()
        activeHistoryMode = mode
        _isLoading.value = _glucoseHistory.value.isEmpty()

        if (DashboardHistoryCollectionPolicy.runsUnboundedHistoryStream(mode)) {
            // Parallel cross-sensor merged stream that backs the History browse
            // screen. Keep it off the dashboard path so dashboard startup does
            // not scan and convert the full multi-sensor Room timeline.
            historyScreenJob = viewModelScope.launch {
                combine(
                    _unit,
                    glucoseRepository.getMergedHistoryFlowRaw(queryStartTimeMs)
                        .distinctUntilChangedBy(::historyEdgeSignature)
                ) { unitStr, rawHistory ->
                    unitStr to rawHistory
                }.collect { (unitStr, rawHistory) ->
                    _historyScreenGlucoseHistory.value = withContext(Dispatchers.Default) {
                        rawHistory.inDisplayUnit(unitStr)
                    }
                }
            }
        } else {
            _historyScreenGlucoseHistory.value = emptyList()
        }

        startCurrentSensorTailCollection(mode)

        historyJob = viewModelScope.launch {
            var hasSeenHistoryEmission = false
            val rawHistoryFlow = when (mode) {
                CollectionMode.DASHBOARD -> glucoseRepository.getDashboardHistoryFlowRaw(queryStartTimeMs)
                CollectionMode.FULL_HISTORY -> glucoseRepository.getHistoryFlowRaw(queryStartTimeMs)
                CollectionMode.INACTIVE -> return@launch
            }
            combine(
                _unit,
                rawHistoryFlow.distinctUntilChangedBy(::historyEdgeSignature)
            ) { unitStr, rawHistory ->
                unitStr to rawHistory
            }.collectLatest { (unitStr, rawHistory) ->
                val shouldCoalesce = DashboardHistoryCollectionPolicy.shouldCoalesceEmission(
                    mode,
                    hasSeenHistoryEmission,
                )
                hasSeenHistoryEmission = true
                if (shouldCoalesce) {
                    delay(DASHBOARD_HISTORY_COALESCE_MS)
                }
                val signature = historyEdgeSignature(rawHistory)
                BatteryTrace.bump(
                    key = "dashboard.history.emission",
                    logEvery = 20L,
                    detail = "mode=$mode size=${rawHistory.size}"
                )
                _glucoseHistory.value = resolveHistoryDisplayList(rawHistory, unitStr, mode, signature)
                _isLoading.value = false
            }
        }
    }

    /**
     * Collects the current sensor's own tail, and owns the history-recovery
     * decision that used to ride on the chart's emissions.
     *
     * Recovery moved here with the data it reasons about. Now that the chart
     * shows every sensor covering the window, "the newest point in the drawn
     * list" can belong to a sensor that is not the current one — and asking that
     * list whether the current sensor is behind would answer no while it sits
     * silent. This flow is that sensor and only that sensor.
     */
    private fun startCurrentSensorTailCollection(mode: CollectionMode) {
        currentSensorTailJob?.cancel()
        if (mode == CollectionMode.INACTIVE) {
            currentSensorTailJob = null
            _currentSensorTail.value = emptyList()
            return
        }

        currentSensorTailJob = viewModelScope.launch {
            var lastRecoveryRequestSerial: String? = null
            val tailStartMs =
                (System.currentTimeMillis() - CURRENT_SENSOR_TAIL_WINDOW_MS).coerceAtLeast(0L)
            glucoseRepository.getCurrentSensorTailFlowRaw(tailStartMs)
                .distinctUntilChangedBy(::historyEdgeSignature)
                .collectLatest { tail ->
                // Stored mg/dL, unconverted: the recovery check reads timestamps
                // only, and converting would be work no one looks at.
                _currentSensorTail.value = tail

                val preferredSerial = preferredDashboardSensorId()?.takeIf { it.isNotBlank() }
                val current = resolveCurrentForHistoryRecovery(preferredSerial)
                // Deliberately still 0: shouldRequestHistoryRecovery treats a
                // non-zero start as "history must reach back this far", and the
                // tail window is a display bound, not a coverage requirement.
                val currentRecoveryStartTimeMs = activeHistoryStartTimeMs ?: 0L
                if (preferredSerial != null &&
                    shouldRequestHistoryRecovery(currentRecoveryStartTimeMs, tail, preferredSerial, current) &&
                    lastRecoveryRequestSerial != preferredSerial
                ) {
                    lastRecoveryRequestSerial = preferredSerial
                    requestHistoryRecoverySync(
                        serial = preferredSerial,
                        reason = "current_sensor_tail_${mode.name.lowercase()}_${tail.size}"
                    )
                }
            }
        }
    }

    private fun startMultiSensorHistoryCollectionForMode(mode: CollectionMode) {
        if (mode != CollectionMode.DASHBOARD) {
            multiSensorHistoryJob?.cancel()
            multiSensorHistoryJob = null
            _multiSensorRawHistory.value = PeerRawHistory.EMPTY
            _multiSensorDisplay.value = tk.glucodata.ui.MultiSensorDisplayData.EMPTY
            _peerCurrentReadings.value = emptyList()
            return
        }
        if (multiSensorHistoryJob?.isActive == true) return

        multiSensorHistoryJob = viewModelScope.launch {
            // Builder: rebuild the display data when either the raw peer history
            // or per-sensor view modes change. Keying on _sensorViewModes is what
            // makes a native auto/raw switch propagate to the chart and reading
            // rows (not just the live-resolved hero value).
            launch {
                combine(_multiSensorRawHistory, _sensorViewModes) { raw, modes -> raw to modes }
                    .collectLatest { (raw, modes) ->
                        _multiSensorDisplay.value = if (raw.peerIds.isEmpty() || raw.points.isEmpty()) {
                            tk.glucodata.ui.MultiSensorDisplayData.EMPTY
                        } else {
                            withContext(Dispatchers.Default) {
                                tk.glucodata.ui.MultiSensorDisplay.buildDisplayData(
                                    points = raw.points,
                                    selectedPeerIds = raw.peerIds,
                                    sensorViewModes = modes,
                                    selectedSensorIdsForColors = raw.selectedSensorIds
                                )
                            }
                        }
                    }
            }

            // Query: feed raw display-unit peer history into _multiSensorRawHistory.
            combine(
                _unit,
                _sensorName,
                MultiSensorSelection.revision
            ) { unitStr, primarySensor, _ ->
                val activeSensors = runCatching { Natives.activeSensors() }.getOrNull()
                val availableSensors = availableDisplaySensorIds(activeSensors, primarySensor)
                val selectedSensors = MultiSensorSelection.selectedAvailable(
                    availableSensorIds = availableSensors,
                    primarySensorId = primarySensor
                )
                MultiSensorHistoryQueryConfig(
                    unit = unitStr,
                    primarySensorId = primarySensor,
                    selectedSensorIds = selectedSensors,
                    sensorViewModes = resolveSelectedSensorViewModes(selectedSensors)
                )
            }.distinctUntilChanged()
                .collectLatest { config ->
                _selectedSensorIds.value = config.selectedSensorIds
                _activeSensorList.value = config.selectedSensorIds
                _sensorViewModes.value = config.sensorViewModes

                val peerSensors = config.selectedSensorIds.drop(1)
                if (peerSensors.isEmpty()) {
                    _multiSensorRawHistory.value = PeerRawHistory.EMPTY
                    _peerCurrentReadings.value = emptyList()
                    return@collectLatest
                }

                var hasSeenPeerEmission = false
                val startTimeMs = System.currentTimeMillis() - DASHBOARD_PEER_HISTORY_WINDOW_MS
                historyRepository.getHistoryFlowForDisplaySensors(peerSensors, startTimeMs)
                    .conflate()
                    .distinctUntilChangedBy(::historyEdgeSignature)
                    .collectLatest { rawHistory ->
                        if (hasSeenPeerEmission) {
                            delay(DASHBOARD_HISTORY_COALESCE_MS)
                        }
                        hasSeenPeerEmission = true
                        val converted = withContext(Dispatchers.Default) {
                            rawHistory.inDisplayUnit(config.unit)
                        }
                        _multiSensorRawHistory.value = PeerRawHistory(config.selectedSensorIds, peerSensors, converted)
                        refreshPeerCurrentReadings(peerSensors)
                    }
                }
        }
    }

    private var peerCurrentRefreshJob: Job? = null

    /** Coalesced hero peer chip refresh; piggybacks on the regular data refresh. */
    private fun schedulePeerCurrentRefresh(peerSensors: List<String>) {
        if (peerSensors.isEmpty()) {
            if (_peerCurrentReadings.value.isNotEmpty()) {
                _peerCurrentReadings.value = emptyList()
            }
            return
        }
        if (peerCurrentRefreshJob?.isActive == true) return
        peerCurrentRefreshJob = viewModelScope.launch {
            refreshPeerCurrentReadings(peerSensors)
        }
    }

    private suspend fun refreshPeerCurrentReadings(peerSensors: List<String>) {
        if (peerSensors.isEmpty()) {
            _peerCurrentReadings.value = emptyList()
            return
        }
        _peerCurrentReadings.value = withContext(Dispatchers.IO) {
            peerSensors.mapNotNull { sensorId ->
                runCatching {
                    CurrentDisplaySource.resolveCurrent(
                        Notify.glucosetimeout,
                        sensorId,
                        tk.glucodata.DisplayTrendSource.TREND_WINDOW_MS
                    )
                }.getOrNull()?.let { snapshot ->
                    PeerCurrentReading(
                        sensorId = sensorId,
                        primaryStr = snapshot.primaryStr,
                        secondaryStr = snapshot.secondaryStr,
                        rate = snapshot.rate,
                        timeMillis = snapshot.timeMillis
                    )
                }
            }
        }
    }

    /**
     * Makes [sensorId] the primary display sensor (front of the multi-sensor
     * selection + native current sensor), mirroring SensorViewModel.setMain.
     */
    fun promoteSensorToPrimary(sensorId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                MultiSensorSelection.moveToFront(sensorId)
                SensorBluetooth.setCurrentSensorSelection(sensorId)
                tk.glucodata.data.HistorySync.mergeFullSyncForSensor(sensorId)
                UiRefreshBus.requestDataRefresh()
            }.onFailure {
                android.util.Log.e("DashboardVM", "promoteSensorToPrimary failed: ${it.message}")
            }
        }
    }

    private suspend fun resolveHistoryDisplayList(
        rawHistory: List<GlucosePoint>,
        unitStr: String,
        mode: CollectionMode,
        signature: HistoryEdgeSignature
    ): List<GlucosePoint> {
        if (mode != CollectionMode.DASHBOARD) {
            return withContext(Dispatchers.Default) {
                rawHistory.inDisplayUnit(unitStr)
            }
        }

        val cacheKey = DashboardHistoryCacheKey(signature, unitStr)
        synchronized(processDashboardHistoryCacheLock) {
            if (processDashboardHistoryCacheKey == cacheKey) {
                return processDashboardHistoryCacheValue
            }
        }

        val converted = withContext(Dispatchers.Default) {
            rawHistory.inDisplayUnit(unitStr)
        }
        synchronized(processDashboardHistoryCacheLock) {
            processDashboardHistoryCacheKey = cacheKey
            processDashboardHistoryCacheValue = converted
        }
        return converted
    }


    private fun historyEdgeSignature(points: List<tk.glucodata.ui.GlucosePoint>): HistoryEdgeSignature {
        val first = points.firstOrNull()
        val last = points.lastOrNull()
        return HistoryEdgeSignature(
            size = points.size,
            firstTimestamp = first?.timestamp ?: 0L,
            lastTimestamp = last?.timestamp ?: 0L,
            sampleHash = sparseHistorySampleHash(points),
            lastValueBits = java.lang.Float.floatToRawIntBits(last?.value ?: 0f),
            lastRawBits = java.lang.Float.floatToRawIntBits(last?.rawValue ?: 0f),
            lastSerial = last?.sensorSerial,
            uncertaintyCount = points.count { it.uncertainty != null },
            lastUncertaintyBits = last?.uncertainty?.let { uncertainty ->
                (java.lang.Float.floatToRawIntBits(uncertainty.lower).toLong() shl 32) or
                    (java.lang.Float.floatToRawIntBits(uncertainty.upper).toLong() and 0xffffffffL)
            } ?: 0L
        )
    }

    private fun sparseHistorySampleHash(points: List<tk.glucodata.ui.GlucosePoint>): Int {
        if (points.isEmpty()) return 0
        val sampleCount = minOf(points.size, 8)
        var hash = 1
        for (sampleIndex in 0 until sampleCount) {
            val pointIndex = ((points.lastIndex.toLong() * sampleIndex) / (sampleCount - 1).coerceAtLeast(1)).toInt()
            val point = points[pointIndex]
            hash = 31 * hash + point.timestamp.hashCode()
            hash = 31 * hash + java.lang.Float.floatToRawIntBits(point.value)
            hash = 31 * hash + java.lang.Float.floatToRawIntBits(point.rawValue)
            hash = 31 * hash + (point.sensorSerial?.hashCode() ?: 0)
        }
        return hash
    }

    private fun stopCollectionJobs() {
        currentReadingJob?.cancel()
        currentReadingJob = null
        historyJob?.cancel()
        historyJob = null
        multiSensorHistoryJob?.cancel()
        multiSensorHistoryJob = null
        historyScreenJob?.cancel()
        historyScreenJob = null
        currentSensorTailJob?.cancel()
        currentSensorTailJob = null
        uiRefreshJob?.cancel()
        uiRefreshJob = null
        activeHistoryMode = null
        activeHistoryStartTimeMs = null
        _multiSensorRawHistory.value = PeerRawHistory.EMPTY
        _multiSensorDisplay.value = tk.glucodata.ui.MultiSensorDisplayData.EMPTY
        _peerCurrentReadings.value = emptyList()
    }

    fun setLowAlarm(enabled: Boolean, threshold: Float) {
        // Natives.alarmhigh() returns value in User Unit
        val highThreshold = Natives.alarmhigh()
        val highEnabled = Natives.hasalarmhigh()
        val loss = Natives.hasalarmloss()
        
        // Natives.setalarms expects User Units
        Natives.setalarms(threshold, highThreshold, enabled, highEnabled, false, loss)
        refreshData()
    }

    fun setHighAlarm(enabled: Boolean, threshold: Float) {
        // Natives.alarmlow() returns value in User Unit
        val lowThreshold = Natives.alarmlow()
        val lowEnabled = Natives.hasalarmlow()
        val loss = Natives.hasalarmloss()
        
        Natives.setalarms(lowThreshold, threshold, lowEnabled, enabled, false, loss)
        refreshData()
    }

    fun setAlarmSound(type: Int, mode: Int) {
        // mode: 0 = Vibrate Only, 1 = Sound (System)
        // type: 0 = Low, 1 = High
        val flash = Natives.alarmhasflash(type)
        val sound = mode == 1
        val vibration = true // Always vibrate for now, or could depend on mode
        
        // Passing "" as uri to use default/clear custom
        Natives.writering(type, "", sound, flash, vibration)
        refreshData()
    }

    fun setUnit(mode: Int) {
        val app = tk.glucodata.Applic.app
        app.setunit(mode)
        
        // Force immediate state update to trigger UI flow instantly
        _unit.value = if (mode == 1) "mmol/L" else "mg/dL"
        refreshData()
    }
    
    fun setTargetLow(value: Float) {
        setTargetRange(value, Natives.targethigh())
    }

    fun setTargetHigh(value: Float) {
        setTargetRange(Natives.targetlow(), value)
    }

    fun setTargetRange(low: Float, high: Float) {
        Natives.setTargetRange(low, high)
        refreshData()
        refreshNotificationPredictionSurfaces(tk.glucodata.Applic.app)
    }

    // Shared storage with the very-low/very-high alert thresholds; alert
    // enabled flags and forecast alarms are preserved untouched.
    fun setVeryLowHighThresholds(veryLow: Float, veryHigh: Float) {
        Natives.setAdvancedAlarms(
            veryLow, veryHigh,
            Natives.hasalarmverylow(), Natives.hasalarmveryhigh(),
            Natives.hasalarmprelow(), Natives.hasalarmprehigh(),
            Natives.alarmprelow(), Natives.alarmprehigh()
        )
        refreshData()
        refreshNotificationPredictionSurfaces(tk.glucodata.Applic.app)
    }

    fun setGraphLow(value: Float) {
        setGraphRange(value, Natives.graphhigh())
    }

    fun setGraphHigh(value: Float) {
        setGraphRange(Natives.graphlow(), value)
    }

    fun setGraphRange(low: Float, high: Float) {
        Natives.setGraphRange(low, high)
        refreshData()
    }

    fun toggleXDripBroadcast(enabled: Boolean) {
        val context = tk.glucodata.Applic.app
        if (enabled) {
             val intent = android.content.Intent("com.eveningoutpost.dexdrip.BgEstimate")
             val receivers = context.packageManager.queryBroadcastReceivers(intent, 0)
             val names = receivers.mapNotNull { it.activityInfo?.packageName }.toTypedArray()
             Natives.setxdripRecepters(names)
             tk.glucodata.SendLikexDrip.setreceivers()
        } else {
             Natives.setxdripRecepters(emptyArray())
             tk.glucodata.SendLikexDrip.setreceivers()
        }
        _xDripBroadcastEnabled.value = Natives.getxbroadcast()
    }

    fun togglePatchedLibreBroadcast(enabled: Boolean) {
        val context = tk.glucodata.Applic.app
        if (enabled) {
             val intent = android.content.Intent("com.librelink.app.ThirdPartyIntegration.GLUCOSE_READING")
             val receivers = context.packageManager.queryBroadcastReceivers(intent, 0)
             val names = receivers.mapNotNull { it.activityInfo?.packageName }.toTypedArray()
             Natives.setlibrelinkRecepters(names)
             tk.glucodata.XInfuus.setlibrenames()
        } else {
             Natives.setlibrelinkRecepters(emptyArray())
             tk.glucodata.XInfuus.setlibrenames()
        }
        _patchedLibreBroadcastEnabled.value = Natives.getlibrelinkused()
    }

    fun setBroadcastComputedTrend(enabled: Boolean) {
        val context = tk.glucodata.Applic.app
        val prefs = context.getSharedPreferences("tk.glucodata_preferences", android.content.Context.MODE_PRIVATE)
        prefs.edit().putBoolean(tk.glucodata.BroadcastTrendRate.PREF_KEY, enabled).apply()
        _broadcastComputedTrend.value = enabled
    }

    fun toggleGlucodataBroadcast(enabled: Boolean) {
        val context = tk.glucodata.Applic.app
        if (enabled) {
             val intent = android.content.Intent("glucodata.Minute")
             val receivers = context.packageManager.queryBroadcastReceivers(intent, 0)
             val names = receivers.mapNotNull { it.activityInfo?.packageName }.toTypedArray()
             Natives.setglucodataRecepters(names)
             tk.glucodata.JugglucoSend.setreceivers()
        } else {
             Natives.setglucodataRecepters(emptyArray())
             tk.glucodata.JugglucoSend.setreceivers()
        }
        _glucodataBroadcastEnabled.value = Natives.getJugglucobroadcast()
    }

    fun toggleNotificationChart(enabled: Boolean) {
        val context = tk.glucodata.Applic.app
        val prefs = context.getSharedPreferences("tk.glucodata_preferences", android.content.Context.MODE_PRIVATE)
        prefs.edit().putBoolean("notification_chart_enabled", enabled).apply()
        _notificationChartEnabled.value = enabled
        
        // Force update notification to reflect change immediately
        tk.glucodata.Notify.showoldglucose()
    }

    fun setChartSmoothingMinutes(minutes: Int) {
        val context = tk.glucodata.Applic.app
        val sanitized = DataSmoothing.sanitizeMinutes(minutes)
        DataSmoothing.setMinutes(context, sanitized)
        _chartSmoothingMinutes.value = sanitized
        refreshCurrentDisplayAfterSmoothingChange()
    }

    fun setDataSmoothingEnabled(enabled: Boolean) {
        val context = tk.glucodata.Applic.app
        DataSmoothing.setEnabled(context, enabled)
        _chartSmoothingMinutes.value = DataSmoothing.getMinutes(context)
        refreshCurrentDisplayAfterSmoothingChange()
    }

    fun setDataSmoothingGraphOnly(enabled: Boolean) {
        val context = tk.glucodata.Applic.app
        if (enabled) {
            DataSmoothing.setSmoothOnlyExchangeOutputs(context, false)
            _dataSmoothingExchangeOnly.value = false
        }
        DataSmoothing.setGraphOnly(context, enabled)
        _dataSmoothingGraphOnly.value = enabled
        refreshCurrentDisplayAfterSmoothingChange()
    }

    fun setDataSmoothingCollapseChunks(enabled: Boolean) {
        val context = tk.glucodata.Applic.app
        DataSmoothing.setCollapseChunks(context, enabled)
        _dataSmoothingCollapseChunks.value = enabled
        refreshCurrentDisplayAfterSmoothingChange()
    }

    fun setDataSmoothingExchangeOnly(enabled: Boolean) {
        val context = tk.glucodata.Applic.app
        if (enabled) {
            DataSmoothing.setGraphOnly(context, false)
            _dataSmoothingGraphOnly.value = false
        }
        DataSmoothing.setSmoothOnlyExchangeOutputs(context, enabled)
        _dataSmoothingExchangeOnly.value = enabled
        refreshCurrentDisplayAfterSmoothingChange()
    }

    fun setPreviewWindowMode(mode: Int) {
        val sanitized = mode.coerceIn(0, 2)
        val context = tk.glucodata.Applic.app
        val prefs = context.getSharedPreferences("tk.glucodata_preferences", android.content.Context.MODE_PRIVATE)
        prefs.edit().putInt("dashboard_chart_preview_window_mode", sanitized).apply()
        _previewWindowMode.value = sanitized
    }

    fun setJournalEnabled(enabled: Boolean) {
        val context = tk.glucodata.Applic.app
        val prefs = context.getSharedPreferences("tk.glucodata_preferences", android.content.Context.MODE_PRIVATE)
        prefs.edit().putBoolean("dashboard_journal_enabled", enabled).apply()
        _journalEnabled.value = enabled
        if (enabled) {
            ensureJournalEntriesObserved()
        } else {
            stopJournalEntriesObservation()
        }
        refreshNotificationPredictionSurfaces(context)
    }

    fun setJournalNavigationTabEnabled(enabled: Boolean) {
        val context = tk.glucodata.Applic.app
        val prefs = context.getSharedPreferences("tk.glucodata_preferences", android.content.Context.MODE_PRIVATE)
        prefs.edit().putBoolean(JOURNAL_NAVIGATION_TAB_KEY, enabled).apply()
        _journalNavigationTabEnabled.value = enabled
    }

    fun setJournalDoseCalculatorEnabled(enabled: Boolean) {
        val context = tk.glucodata.Applic.app
        val prefs = context.getSharedPreferences("tk.glucodata_preferences", android.content.Context.MODE_PRIVATE)
        prefs.edit().putBoolean(JOURNAL_DOSE_CALCULATOR_KEY, enabled).apply()
        _journalDoseCalculatorEnabled.value = enabled
    }

    fun setJournalFoodMacrosEnabled(enabled: Boolean) {
        val context = tk.glucodata.Applic.app
        val prefs = context.getSharedPreferences("tk.glucodata_preferences", android.content.Context.MODE_PRIVATE)
        prefs.edit().putBoolean(JOURNAL_FOOD_MACROS_KEY, enabled).apply()
        _journalFoodMacrosEnabled.value = enabled
        refreshNotificationPredictionSurfaces(context)
    }

    fun setJournalFoodLibraryEnabled(enabled: Boolean) {
        val context = tk.glucodata.Applic.app
        val prefs = context.getSharedPreferences("tk.glucodata_preferences", android.content.Context.MODE_PRIVATE)
        prefs.edit().putBoolean(JOURNAL_FOOD_LIBRARY_KEY, enabled).apply()
        _journalFoodLibraryEnabled.value = enabled
    }

    fun setJournalEiobDisplayEnabled(enabled: Boolean) {
        val context = tk.glucodata.Applic.app
        val prefs = context.getSharedPreferences("tk.glucodata_preferences", android.content.Context.MODE_PRIVATE)
        prefs.edit().putBoolean(JOURNAL_EIOB_DISPLAY_KEY, enabled).apply()
        _journalEiobDisplayEnabled.value = enabled
    }

    fun setJournalQuickAddAlwaysNow(enabled: Boolean) {
        val context = tk.glucodata.Applic.app
        val prefs = context.getSharedPreferences("tk.glucodata_preferences", android.content.Context.MODE_PRIVATE)
        prefs.edit().putBoolean(JOURNAL_QUICKADD_ALWAYS_NOW_KEY, enabled).apply()
        _journalQuickAddAlwaysNow.value = enabled
    }

    fun setJournalDashboardQuickAddButton(enabled: Boolean) {
        val context = tk.glucodata.Applic.app
        val prefs = context.getSharedPreferences("tk.glucodata_preferences", android.content.Context.MODE_PRIVATE)
        prefs.edit().putBoolean(JOURNAL_DASHBOARD_QUICKADD_KEY, enabled).apply()
        _journalDashboardQuickAddButton.value = enabled
    }

    fun setGlucoseValueRangeColorsEnabled(enabled: Boolean) {
        val context = tk.glucodata.Applic.app
        val prefs = context.getSharedPreferences("tk.glucodata_preferences", android.content.Context.MODE_PRIVATE)
        prefs.edit().putBoolean(GLUCOSE_RANGE_COLORS_KEY, enabled).apply()
        _glucoseValueRangeColorsEnabled.value = enabled
        refreshNotificationPredictionSurfaces(context)
        // Travels with the palette: the watch colours its readings by the same rule.
        tk.glucodata.GlucoseColorSync.push()
    }

    fun setDashboardShowDelta(enabled: Boolean) {
        val context = tk.glucodata.Applic.app
        val prefs = context.getSharedPreferences("tk.glucodata_preferences", android.content.Context.MODE_PRIVATE)
        prefs.edit().putBoolean(DASHBOARD_SHOW_DELTA_KEY, enabled).apply()
        _dashboardShowDelta.value = enabled
    }

    fun setDashboardRowsShowDelta(enabled: Boolean) {
        val context = tk.glucodata.Applic.app
        val prefs = context.getSharedPreferences("tk.glucodata_preferences", android.content.Context.MODE_PRIVATE)
        prefs.edit().putBoolean(DASHBOARD_ROWS_SHOW_DELTA_KEY, enabled).apply()
        _dashboardRowsShowDelta.value = enabled
    }

    /**
     * Global delta interval (1 or 5 minutes). Drives both the Δ readout and the
     * FALLING_FAST / RISING_FAST alarms, so it is stored in the shared display prefs.
     */
    fun setDeltaIntervalMinutes(minutes: Int) {
        val context = tk.glucodata.Applic.app
        val prefs = context.getSharedPreferences("tk.glucodata_preferences", android.content.Context.MODE_PRIVATE)
        val sanitized = tk.glucodata.GlucoseDelta.sanitizeIntervalMinutes(minutes)
        prefs.edit().putInt(DELTA_INTERVAL_KEY, sanitized).apply()
        _deltaIntervalMinutes.value = sanitized
        refreshNotificationPredictionSurfaces(context)
    }

    fun setGlucoseArrowForecastColorsEnabled(enabled: Boolean) {
        val context = tk.glucodata.Applic.app
        val prefs = context.getSharedPreferences("tk.glucodata_preferences", android.content.Context.MODE_PRIVATE)
        prefs.edit().putBoolean(ARROW_FORECAST_COLORS_KEY, enabled).apply()
        _glucoseArrowForecastColorsEnabled.value = enabled
        refreshNotificationPredictionSurfaces(context)
    }

    fun setGlucoseChartRangeColorsEnabled(enabled: Boolean) {
        val context = tk.glucodata.Applic.app
        val prefs = context.getSharedPreferences("tk.glucodata_preferences", android.content.Context.MODE_PRIVATE)
        prefs.edit().putBoolean(CHART_RANGE_COLORS_KEY, enabled).apply()
        _glucoseChartRangeColorsEnabled.value = enabled
        refreshNotificationPredictionSurfaces(context)
    }

    fun setGlucoseAppChartRangeColorsEnabled(enabled: Boolean) {
        val context = tk.glucodata.Applic.app
        val prefs = context.getSharedPreferences("tk.glucodata_preferences", android.content.Context.MODE_PRIVATE)
        prefs.edit().putBoolean(APP_CHART_RANGE_COLORS_KEY, enabled).apply()
        _glucoseAppChartRangeColorsEnabled.value = enabled
    }

    fun setJournalHealthConnectActivityEnabled(enabled: Boolean) {
        val context = tk.glucodata.Applic.app
        val prefs = context.getSharedPreferences("tk.glucodata_preferences", android.content.Context.MODE_PRIVATE)
        prefs.edit().putBoolean(JOURNAL_HEALTH_CONNECT_ACTIVITY_KEY, enabled).apply()
        _journalHealthConnectActivityEnabled.value = enabled
        if (enabled) {
            importHealthConnectActivity(daysBack = 14)
        }
    }

    fun setAapsJournalImportEnabled(enabled: Boolean) {
        val context = tk.glucodata.Applic.app
        AapsJournalImport.setEnabled(context, enabled)
        _aapsJournalImportEnabled.value = enabled
    }

    fun importHealthConnectActivity(daysBack: Int = 14) {
        tk.glucodata.HealthConnection.importActivity(daysBack)
    }

    fun setPredictiveSimulationEnabled(enabled: Boolean) {
        val context = tk.glucodata.Applic.app
        val prefs = context.getSharedPreferences("tk.glucodata_preferences", android.content.Context.MODE_PRIVATE)
        prefs.edit().putBoolean("dashboard_predictive_simulation_enabled", enabled).apply()
        _predictiveSimulationEnabled.value = enabled
        refreshNotificationPredictionSurfaces(context)
    }

    fun setAlertsMasterEnabled(enabled: Boolean) {
        val configs = AlertRepository.loadAllConfigs()
        configs.forEach { config ->
            if (config.enabled != enabled) {
                AlertRepository.saveConfig(config.copy(enabled = enabled))
            }
        }

        val customAlerts = CustomAlertRepository.getAll()
        val updatedCustomAlerts = customAlerts.map { it.copy(enabled = enabled) }
        if (updatedCustomAlerts != customAlerts) {
            CustomAlertRepository.saveAll(updatedCustomAlerts)
        }

        _alertsMasterEnabled.value = enabled
        val context = tk.glucodata.Applic.app
        _alertsSummary.value = if (enabled) {
            context.getString(tk.glucodata.R.string.global_active)
        } else {
            context.getString(tk.glucodata.R.string.global_all_alerts_disabled)
        }
        refreshData()
    }

    fun setPredictiveSimulationNotificationChartEnabled(enabled: Boolean) {
        val context = tk.glucodata.Applic.app
        val prefs = context.getSharedPreferences("tk.glucodata_preferences", android.content.Context.MODE_PRIVATE)
        prefs.edit().putBoolean(PREDICTION_NOTIFICATION_CHART_KEY, enabled).apply()
        _predictiveSimulationNotificationChartEnabled.value = enabled
        refreshNotificationPredictionSurfaces(context)
    }

    fun setPredictionTrendMomentumEnabled(enabled: Boolean) {
        val context = tk.glucodata.Applic.app
        val prefs = context.getSharedPreferences("tk.glucodata_preferences", android.content.Context.MODE_PRIVATE)
        prefs.edit().putBoolean("dashboard_prediction_trend_momentum_enabled", enabled).apply()
        _predictionTrendMomentumEnabled.value = enabled
        refreshNotificationPredictionSurfaces(context)
    }

    fun setPredictionCarbRatioGramsPerUnit(value: Float) {
        updatePredictionModelBlock(
            startMinuteOfDay = 0,
            carbRatioGramsPerUnit = value
        )
    }

    fun setPredictionInsulinSensitivityMgDlPerUnit(value: Float) {
        updatePredictionModelBlock(
            startMinuteOfDay = 0,
            insulinSensitivityMgDlPerUnit = value
        )
    }

    fun addPredictionModelBlock(startMinuteOfDay: Int) {
        persistPredictionModelProfile(_predictionModelProfile.value.addBlock(startMinuteOfDay))
    }

    fun removePredictionModelBlock(startMinuteOfDay: Int) {
        persistPredictionModelProfile(_predictionModelProfile.value.removeBlock(startMinuteOfDay))
    }

    fun movePredictionModelBlock(oldStartMinuteOfDay: Int, newStartMinuteOfDay: Int) {
        persistPredictionModelProfile(
            _predictionModelProfile.value.moveBlock(oldStartMinuteOfDay, newStartMinuteOfDay)
        )
    }

    fun updatePredictionModelBlock(
        startMinuteOfDay: Int,
        carbRatioGramsPerUnit: Float? = null,
        insulinSensitivityMgDlPerUnit: Float? = null,
        carbAbsorptionGramsPerHour: Float? = null
    ) {
        val normalizedCarbRatio = carbRatioGramsPerUnit?.roundToStep(1f)
        val normalizedSensitivity = insulinSensitivityMgDlPerUnit?.roundToStep(1f)
        val normalizedAbsorption = carbAbsorptionGramsPerHour?.roundToStep(1f)
        persistPredictionModelProfile(
            _predictionModelProfile.value.updateBlock(
                startMinuteOfDay = startMinuteOfDay,
                carbRatioGramsPerUnit = normalizedCarbRatio,
                insulinSensitivityMgDlPerUnit = normalizedSensitivity,
                carbAbsorptionGramsPerHour = normalizedAbsorption
            )
        )
    }

    private fun persistPredictionModelProfile(profile: PredictionModelProfile) {
        if (profile == _predictionModelProfile.value) return
        val context = tk.glucodata.Applic.app
        val prefs = context.getSharedPreferences("tk.glucodata_preferences", android.content.Context.MODE_PRIVATE)
        PredictionModelProfileStore.save(prefs, profile)
        _predictionModelProfile.value = profile
        val first = profile.blocks.first()
        _predictionCarbRatioGramsPerUnit.value = first.carbRatioGramsPerUnit
        _predictionInsulinSensitivityMgDlPerUnit.value = first.insulinSensitivityMgDlPerUnit
        _predictionCarbAbsorptionGramsPerHour.value = first.carbAbsorptionGramsPerHour
        refreshNotificationPredictionSurfaces(context)
    }

    fun setPredictionDoseTargetMgDl(value: Float) {
        val normalized = value.roundToStep(1f).coerceIn(DoseTarget.MIN_MGDL, DoseTarget.MAX_MGDL)
        val context = tk.glucodata.Applic.app
        val prefs = context.getSharedPreferences("tk.glucodata_preferences", android.content.Context.MODE_PRIVATE)
        prefs.edit().putFloat(PREDICTION_DOSE_TARGET_KEY, normalized).apply()
        _predictionDoseTargetMgDl.value = normalized
        refreshNotificationPredictionSurfaces(context)
    }

    fun setPredictionHorizonMinutes(value: Int) {
        val normalized = value.coerceIn(30, 360)
        val context = tk.glucodata.Applic.app
        val prefs = context.getSharedPreferences("tk.glucodata_preferences", android.content.Context.MODE_PRIVATE)
        prefs.edit().putInt(PREDICTION_HORIZON_MINUTES_KEY, normalized).apply()
        _predictionHorizonMinutes.value = normalized
        refreshNotificationPredictionSurfaces(context)
    }

    fun refreshNotificationSurfaces() {
        refreshNotificationPredictionSurfaces(tk.glucodata.Applic.app)
    }

    private fun refreshNotificationPredictionSurfaces(context: android.content.Context) {
        tk.glucodata.Notify.showoldglucose()
        context.sendBroadcast(
            android.content.Intent(tk.glucodata.accessibility.AODOverlayService.ACTION_IMMEDIATE_REFRESH)
        )
    }

    fun saveJournalEntry(input: JournalEntryInput) {
        viewModelScope.launch {
            journalRepository.upsertEntry(input)
        }
    }

    fun deleteJournalEntry(entryId: Long) {
        viewModelScope.launch {
            journalRepository.deleteEntry(entryId)
        }
    }

    fun deleteHistoryReading(point: tk.glucodata.ui.GlucosePoint, fallbackSensorSerial: String? = null) {
        if (point.timestamp <= 0L) return
        val pointSerial = point.sensorSerial?.takeIf { it.isNotBlank() }
        val targetSerial = when {
            !fallbackSensorSerial.isNullOrBlank() &&
                !pointSerial.isNullOrBlank() &&
                SensorIdentity.matches(pointSerial, fallbackSensorSerial) -> fallbackSensorSerial
            !pointSerial.isNullOrBlank() -> pointSerial
            !fallbackSensorSerial.isNullOrBlank() -> fallbackSensorSerial
            else -> null
        } ?: return

        viewModelScope.launch {
            historyRepository.deleteReading(
                timestamp = point.timestamp,
                sensorSerial = targetSerial
            )
        }
    }

    fun saveJournalInsulinPreset(input: JournalInsulinPresetInput) {
        viewModelScope.launch {
            journalRepository.upsertInsulinPreset(input)
        }
    }

    fun deleteJournalInsulinPreset(presetId: Long) {
        viewModelScope.launch {
            journalRepository.deleteInsulinPreset(presetId)
        }
    }

    fun saveJournalFood(input: JournalFoodInput) {
        viewModelScope.launch {
            journalRepository.upsertFood(input)
        }
    }

    fun deleteJournalFood(foodId: Long) {
        viewModelScope.launch {
            journalRepository.deleteFood(foodId)
        }
    }

    // Floating Glucose Logic
    val floatingRepository = tk.glucodata.data.settings.FloatingSettingsRepository(tk.glucodata.Applic.app)

    fun toggleFloatingGlucose(enabled: Boolean) {
        val context = tk.glucodata.Applic.app
        floatingRepository.setEnabled(enabled)
        
        val intent = android.content.Intent(context, tk.glucodata.service.FloatingGlucoseService::class.java)
        if (enabled) {
            // Check permission before starting? Service will likely fail or just not show if no permission.
            // We assume UI handles permission check.
           try {
               context.startService(intent)
               // Disable native floating to avoid duplication
               Natives.setfloatglucose(false) 
           } catch (e: Exception) {
               android.util.Log.e("DashboardVM", "Failed to start floating service", e)
           }
        } else {
            context.stopService(intent)
        }
    }

    private fun migrateTargetRangeDefaultsIfNeeded(
        prefs: android.content.SharedPreferences,
        isMmol: Boolean
    ) {
        if (prefs.getBoolean(TARGET_RANGE_DEFAULTS_MIGRATION_KEY, false)) {
            return
        }

        val currentLow = Natives.targetlow()
        val currentHigh = Natives.targethigh()
        val oldLow = if (isMmol) 3.9f else 70f
        val oldHigh = if (isMmol) 10.0f else 180f

        if (kotlin.math.abs(currentLow - oldLow) < 0.11f && kotlin.math.abs(currentHigh - oldHigh) < 0.11f) {
            Natives.setTargetRange(
                if (isMmol) 3.6f else 65f,
                if (isMmol) 9.0f else 162f
            )
        }

        prefs.edit().putBoolean(TARGET_RANGE_DEFAULTS_MIGRATION_KEY, true).apply()
    }

    private fun refreshCurrentDisplayAfterSmoothingChange() {
        CurrentDisplaySource.resolveCurrent(
            maxAgeMillis = Notify.glucosetimeout,
            preferredSensorId = preferredDashboardSensorId()
        )?.let { resolved ->
            _currentGlucose.value = resolved.primaryStr
            _currentRate.value = resolved.rate.takeIf { it.isFinite() } ?: 0f
        }
        // Every smoothing setter funnels through here, so this is the one place
        // the watch needs telling; it smooths with the same settings.
        tk.glucodata.WearPrefsSync.push()
    }

    private fun requestHistoryRecoverySync(serial: String, reason: String) {
        if (!SensorIdentity.shouldUseNativeHistorySync(serial)) {
            return
        }
        val nowMs = SystemClock.elapsedRealtime()
        synchronized(processHistoryRecoveryLock) {
            val lastSerial = lastHistoryRecoverySerial ?: processLastHistoryRecoverySerial
            val lastRunMs = if (serial == lastSerial) {
                maxOf(lastHistoryRecoverySyncAtMs, processLastHistoryRecoverySyncAtMs)
            } else {
                0L
            }
            if (serial == lastSerial && (nowMs - lastRunMs) < UI_RECOVERY_SYNC_MIN_INTERVAL_MS) {
                return
            }
            lastHistoryRecoverySerial = serial
            lastHistoryRecoverySyncAtMs = nowMs
            processLastHistoryRecoverySerial = serial
            processLastHistoryRecoverySyncAtMs = nowMs
        }
        BatteryTrace.bump(
            key = "dashboard.history.recovery.request",
            logEvery = 20L,
            detail = "serial=$serial reason=$reason"
        )
        HistorySync.syncSensorFromNative(serial, forceFull = false)
    }

    /**
     * [history] must be the **current sensor's own** readings — see
     * [DashboardHistoryCollectionPolicy.shouldRequestHistoryRecovery], which
     * holds the logic and the reason.
     */
    private fun shouldRequestHistoryRecovery(
        startTimeMs: Long,
        history: List<tk.glucodata.ui.GlucosePoint>,
        serial: String?,
        current: CurrentDisplaySource.Snapshot?
    ): Boolean {
        val snapshotIsSameSensor = current != null &&
            !serial.isNullOrBlank() &&
            (current.sensorId.isNullOrBlank() || SensorIdentity.matches(current.sensorId, serial))
        return DashboardHistoryCollectionPolicy.shouldRequestHistoryRecovery(
            requiredStartMs = startTimeMs,
            oldestStoredMs = history.firstOrNull()?.timestamp,
            latestStoredMs = history.lastOrNull()?.timestamp,
            currentSnapshotTimeMs = current?.timeMillis ?: 0L,
            snapshotIsSameSensor = snapshotIsSameSensor,
            coverageToleranceMs = HISTORY_RECOVERY_TOLERANCE_MS,
            tailToleranceMs = HISTORY_RECOVERY_TAIL_TOLERANCE_MS
        )
    }

    private fun resolveCurrentForHistoryRecovery(serial: String?): CurrentDisplaySource.Snapshot? {
        if (serial.isNullOrBlank()) return null
        return CurrentDisplaySource.resolveCurrent(
            maxAgeMillis = Notify.glucosetimeout,
            preferredSensorId = serial
        )
    }

    private fun preferredDashboardSensorId(): String? {
        val nativeCurrent = SensorIdentity.resolveMainSensor()
            ?.takeIf { it.isNotBlank() }
        if (nativeCurrent != null) {
            return nativeCurrent
        }
        val cachedSerial = _sensorName.value.takeIf { it.isNotBlank() }
            ?: glucoseRepository.currentSerial.value.takeIf { it.isNotBlank() }
        if (!cachedSerial.isNullOrBlank()) {
            return cachedSerial
        }
        return SensorIdentity.resolveAvailableMainSensor(
            selectedMain = nativeCurrent,
            preferredSensorId = null,
            activeSensors = Natives.activeSensors()
        )
    }

    private fun Float.roundToStep(step: Float): Float {
        if (!isFinite() || step <= 0f) return this
        return (this / step).roundToInt() * step
    }
}
