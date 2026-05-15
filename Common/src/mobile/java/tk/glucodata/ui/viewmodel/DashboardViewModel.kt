package tk.glucodata.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.withContext
import tk.glucodata.Natives
import tk.glucodata.UiRefreshBus
import tk.glucodata.Applic
import tk.glucodata.BatteryTrace
import tk.glucodata.CurrentDisplaySource
import tk.glucodata.DataSmoothing
import tk.glucodata.Notify
import tk.glucodata.SensorIdentity
import tk.glucodata.data.GlucoseRepository
import tk.glucodata.data.HistorySync
import tk.glucodata.data.journal.JournalEntry
import tk.glucodata.data.journal.JournalEntryInput
import tk.glucodata.data.journal.JournalFood
import tk.glucodata.data.journal.JournalFoodInput
import tk.glucodata.data.journal.JournalInsulinPreset
import tk.glucodata.data.journal.JournalInsulinPresetInput
import tk.glucodata.ui.util.inDisplayUnit
import tk.glucodata.data.journal.JournalRepository
import tk.glucodata.alerts.AlertRepository
import tk.glucodata.alerts.CustomAlertRepository
import tk.glucodata.drivers.ManagedSensorRuntime
import tk.glucodata.drivers.ManagedSensorStatusPolicy
import tk.glucodata.drivers.ManagedSensorUiFamily
import tk.glucodata.ui.util.resolveDashboardSensorStatus
import kotlin.math.roundToInt

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
        val lastSerial: String?
    )

    private companion object {
        const val TARGET_RANGE_DEFAULTS_MIGRATION_KEY = "target_range_defaults_v2"
        const val UI_RECOVERY_SYNC_MIN_INTERVAL_MS = 30_000L
        const val HISTORY_RECOVERY_TOLERANCE_MS = 5L * 60L * 1000L
        const val HISTORY_RECOVERY_TAIL_TOLERANCE_MS = 2L * 60L * 1000L
        const val JOURNAL_DOSE_CALCULATOR_KEY = "dashboard_journal_dose_calculator_enabled"
        const val JOURNAL_FOOD_MACROS_KEY = "dashboard_journal_food_macros_enabled"
        const val PREDICTION_CARB_RATIO_KEY = "dashboard_prediction_carb_ratio_g_per_u"
        const val PREDICTION_INSULIN_SENSITIVITY_KEY = "dashboard_prediction_insulin_sensitivity_mgdl_per_u"
        const val PREDICTION_CARB_ABSORPTION_KEY = "dashboard_prediction_carb_absorption_g_per_h"
        const val PREDICTION_HORIZON_MINUTES_KEY = "dashboard_prediction_horizon_minutes"
        const val PREDICTION_CARB_RATIO_DEFAULT = 10f
        const val PREDICTION_INSULIN_SENSITIVITY_DEFAULT = 54f
        const val PREDICTION_CARB_ABSORPTION_DEFAULT = 35f
        const val PREDICTION_HORIZON_MINUTES_DEFAULT = 120
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

    private val _glucoseHistory = MutableStateFlow<List<tk.glucodata.ui.GlucosePoint>>(emptyList())
    val glucoseHistory = _glucoseHistory.asStateFlow()

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

    private val _journalDoseCalculatorEnabled = MutableStateFlow(false)
    val journalDoseCalculatorEnabled = _journalDoseCalculatorEnabled.asStateFlow()

    private val _journalFoodMacrosEnabled = MutableStateFlow(false)
    val journalFoodMacrosEnabled = _journalFoodMacrosEnabled.asStateFlow()

    private val _predictiveSimulationEnabled = MutableStateFlow(true)
    val predictiveSimulationEnabled = _predictiveSimulationEnabled.asStateFlow()

    private val _predictionTrendMomentumEnabled = MutableStateFlow(true)
    val predictionTrendMomentumEnabled = _predictionTrendMomentumEnabled.asStateFlow()

    private val _predictionCarbRatioGramsPerUnit = MutableStateFlow(PREDICTION_CARB_RATIO_DEFAULT)
    val predictionCarbRatioGramsPerUnit = _predictionCarbRatioGramsPerUnit.asStateFlow()

    private val _predictionInsulinSensitivityMgDlPerUnit = MutableStateFlow(PREDICTION_INSULIN_SENSITIVITY_DEFAULT)
    val predictionInsulinSensitivityMgDlPerUnit = _predictionInsulinSensitivityMgDlPerUnit.asStateFlow()

    private val _predictionCarbAbsorptionGramsPerHour = MutableStateFlow(PREDICTION_CARB_ABSORPTION_DEFAULT)
    val predictionCarbAbsorptionGramsPerHour = _predictionCarbAbsorptionGramsPerHour.asStateFlow()

    private val _predictionHorizonMinutes = MutableStateFlow(PREDICTION_HORIZON_MINUTES_DEFAULT)
    val predictionHorizonMinutes = _predictionHorizonMinutes.asStateFlow()

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

    private var collectionMode = CollectionMode.INACTIVE
    private var currentReadingJob: Job? = null
    private var historyJob: Job? = null
    private var historyScreenJob: Job? = null
    private var uiRefreshJob: Job? = null
    private var journalEntriesJob: Job? = null
    private var journalPresetsJob: Job? = null
    private var journalFoodsJob: Job? = null
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
        _journalEntries.value = emptyList()
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
        synchronized(this) {
            if ((nowMs - lastUiRecoverySyncAtMs) < UI_RECOVERY_SYNC_MIN_INTERVAL_MS) {
                android.util.Log.d(
                    "DashboardVM",
                    "requestUiRecoverySync skipped — last run was ${(nowMs - lastUiRecoverySyncAtMs)}ms ago"
                )
                return
            }
            lastUiRecoverySyncAtMs = nowMs
        }
        val serial = preferredDashboardSensorId()?.takeIf { it.isNotBlank() }
        val historyStartTimeMs = activeHistoryStartTimeMs
        val current = resolveCurrentForHistoryRecovery(serial)
        val shouldPreferHistoryRecovery = serial != null &&
            historyStartTimeMs != null &&
            shouldRequestHistoryRecovery(historyStartTimeMs, _glucoseHistory.value, serial, current)

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
            UiRefreshBus.events.collect { event ->
                when (event) {
                    UiRefreshBus.Event.DataChanged -> refreshData()
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
        _journalDoseCalculatorEnabled.value = prefs.getBoolean(JOURNAL_DOSE_CALCULATOR_KEY, false)
        _journalFoodMacrosEnabled.value = prefs.getBoolean(JOURNAL_FOOD_MACROS_KEY, false)
        _predictiveSimulationEnabled.value = prefs.getBoolean("dashboard_predictive_simulation_enabled", true)
        _predictionTrendMomentumEnabled.value = prefs.getBoolean("dashboard_prediction_trend_momentum_enabled", true)
        _predictionCarbRatioGramsPerUnit.value = prefs
            .getFloat(PREDICTION_CARB_RATIO_KEY, PREDICTION_CARB_RATIO_DEFAULT)
            .coerceIn(3f, 30f)
        _predictionInsulinSensitivityMgDlPerUnit.value = prefs
            .getFloat(PREDICTION_INSULIN_SENSITIVITY_KEY, PREDICTION_INSULIN_SENSITIVITY_DEFAULT)
            .coerceIn(10f, 180f)
        _predictionCarbAbsorptionGramsPerHour.value = prefs
            .getFloat(PREDICTION_CARB_ABSORPTION_KEY, PREDICTION_CARB_ABSORPTION_DEFAULT)
            .coerceIn(10f, 90f)
        _predictionHorizonMinutes.value = prefs
            .getInt(PREDICTION_HORIZON_MINUTES_KEY, PREDICTION_HORIZON_MINUTES_DEFAULT)
            .coerceIn(30, 360)
        if (journalEnabled) {
            ensureJournalEntriesObserved()
        } else if (journalEntriesJob != null) {
            stopJournalEntriesObservation()
        }

        _graphLow.value = Natives.graphlow()
        _graphHigh.value = Natives.graphhigh()
        _targetLow.value = Natives.targetlow()
        _targetHigh.value = Natives.targethigh()
        _xDripBroadcastEnabled.value = Natives.getxbroadcast()
        _patchedLibreBroadcastEnabled.value = Natives.getlibrelinkused()
        _glucodataBroadcastEnabled.value = Natives.getJugglucobroadcast()

        _hasLowAlarm.value = Natives.hasalarmlow()
        _lowAlarmThreshold.value = Natives.alarmlow()
        _hasHighAlarm.value = Natives.hasalarmhigh()
        _highAlarmThreshold.value = Natives.alarmhigh()

        _lowAlarmSoundMode.value = if (Natives.alarmhassound(0)) 1 else 0
        _highAlarmSoundMode.value = if (Natives.alarmhassound(1)) 1 else 0

        val anyActive = AlertRepository.loadAllConfigs().any { it.enabled }
            || CustomAlertRepository.getAll().any { it.enabled }
        _alertsSummary.value = if (anyActive) {
            context.getString(tk.glucodata.R.string.global_active)
        } else {
            context.getString(tk.glucodata.R.string.global_all_alerts_disabled)
        }
    }

    private fun refreshSensorSnapshot() {
        var sName = SensorIdentity.resolveMainSensor()
        val activeSensors = Natives.activeSensors()

        if (activeSensors != null && activeSensors.isNotEmpty()) {
            _activeSensorList.value = activeSensors
                .mapNotNull { SensorIdentity.resolveAppSensorId(it) ?: it }
                .distinct()
        } else {
            _activeSensorList.value = emptyList()
        }

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

                val lifecycle = ManagedSensorStatusPolicy.resolveLifecycleSummary(
                    startTimeMs = managedSnapshot?.startTimeMs?.takeIf { it > 0L } ?: startMsec,
                    officialEndMs = managedSnapshot?.officialEndMs?.takeIf { it > 0L } ?: officialEnd,
                    expectedEndMs = managedSnapshot?.expectedEndMs?.takeIf { it > 0L } ?: expectedEnd,
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

    private fun refreshCurrentDisplaySnapshot() {
        refreshCurrentDisplayAfterSmoothingChange()
    }

    private fun startHistoryCollectionForMode(mode: CollectionMode) {
        val recoveryStartTimeMs = when (mode) {
            CollectionMode.INACTIVE -> return
            CollectionMode.DASHBOARD,
            CollectionMode.FULL_HISTORY -> 0L
        }
        val queryStartTimeMs = when (mode) {
            CollectionMode.INACTIVE -> return
            CollectionMode.DASHBOARD,
            CollectionMode.FULL_HISTORY -> 0L
        }
        activeHistoryStartTimeMs = recoveryStartTimeMs

        if (historyJob?.isActive == true && activeHistoryMode == mode) return

        historyJob?.cancel()
        historyScreenJob?.cancel()
        activeHistoryMode = mode
        _isLoading.value = _glucoseHistory.value.isEmpty()

        if (mode == CollectionMode.FULL_HISTORY) {
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

        historyJob = viewModelScope.launch {
            var lastRecoveryRequestSerial: String? = null
            combine(
                _unit,
                glucoseRepository.getHistoryFlowRaw(queryStartTimeMs)
                    .distinctUntilChangedBy(::historyEdgeSignature)
            ) { unitStr, rawHistory ->
                unitStr to rawHistory
            }.collect { (unitStr, rawHistory) ->
                val preferredSerial = preferredDashboardSensorId()?.takeIf { it.isNotBlank() }
                val current = resolveCurrentForHistoryRecovery(preferredSerial)
                val currentRecoveryStartTimeMs = activeHistoryStartTimeMs ?: recoveryStartTimeMs
                if (preferredSerial != null &&
                    shouldRequestHistoryRecovery(currentRecoveryStartTimeMs, rawHistory, preferredSerial, current) &&
                    lastRecoveryRequestSerial != preferredSerial
                ) {
                    lastRecoveryRequestSerial = preferredSerial
                    requestHistoryRecoverySync(
                        serial = preferredSerial,
                        reason = "history_flow_${mode.name.lowercase()}_${rawHistory.size}"
                    )
                }
                BatteryTrace.bump(
                    key = "dashboard.history.emission",
                    logEvery = 20L,
                    detail = "mode=$mode size=${rawHistory.size}"
                )
                _glucoseHistory.value = withContext(Dispatchers.Default) {
                    rawHistory.inDisplayUnit(unitStr)
                }
                _isLoading.value = false
            }
        }
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
            lastSerial = last?.sensorSerial
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
        historyScreenJob?.cancel()
        historyScreenJob = null
        uiRefreshJob?.cancel()
        uiRefreshJob = null
        activeHistoryMode = null
        activeHistoryStartTimeMs = null
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
    }

    fun importHealthConnectActivity(daysBack: Int = 14) {
        tk.glucodata.HealthConnection.importActivity(daysBack)
    }

    fun setPredictiveSimulationEnabled(enabled: Boolean) {
        val context = tk.glucodata.Applic.app
        val prefs = context.getSharedPreferences("tk.glucodata_preferences", android.content.Context.MODE_PRIVATE)
        prefs.edit().putBoolean("dashboard_predictive_simulation_enabled", enabled).apply()
        _predictiveSimulationEnabled.value = enabled
    }

    fun setPredictionTrendMomentumEnabled(enabled: Boolean) {
        val context = tk.glucodata.Applic.app
        val prefs = context.getSharedPreferences("tk.glucodata_preferences", android.content.Context.MODE_PRIVATE)
        prefs.edit().putBoolean("dashboard_prediction_trend_momentum_enabled", enabled).apply()
        _predictionTrendMomentumEnabled.value = enabled
    }

    fun setPredictionCarbRatioGramsPerUnit(value: Float) {
        val normalized = value.roundToStep(1f).coerceIn(3f, 30f)
        val context = tk.glucodata.Applic.app
        val prefs = context.getSharedPreferences("tk.glucodata_preferences", android.content.Context.MODE_PRIVATE)
        prefs.edit().putFloat(PREDICTION_CARB_RATIO_KEY, normalized).apply()
        _predictionCarbRatioGramsPerUnit.value = normalized
    }

    fun setPredictionInsulinSensitivityMgDlPerUnit(value: Float) {
        val normalized = value.roundToStep(1f).coerceIn(10f, 180f)
        val context = tk.glucodata.Applic.app
        val prefs = context.getSharedPreferences("tk.glucodata_preferences", android.content.Context.MODE_PRIVATE)
        prefs.edit().putFloat(PREDICTION_INSULIN_SENSITIVITY_KEY, normalized).apply()
        _predictionInsulinSensitivityMgDlPerUnit.value = normalized
    }

    fun setPredictionCarbAbsorptionGramsPerHour(value: Float) {
        val normalized = value.roundToStep(1f).coerceIn(10f, 90f)
        val context = tk.glucodata.Applic.app
        val prefs = context.getSharedPreferences("tk.glucodata_preferences", android.content.Context.MODE_PRIVATE)
        prefs.edit().putFloat(PREDICTION_CARB_ABSORPTION_KEY, normalized).apply()
        _predictionCarbAbsorptionGramsPerHour.value = normalized
    }

    fun setPredictionHorizonMinutes(value: Int) {
        val normalized = value.coerceIn(30, 360)
        val context = tk.glucodata.Applic.app
        val prefs = context.getSharedPreferences("tk.glucodata_preferences", android.content.Context.MODE_PRIVATE)
        prefs.edit().putInt(PREDICTION_HORIZON_MINUTES_KEY, normalized).apply()
        _predictionHorizonMinutes.value = normalized
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
    }

    private fun requestHistoryRecoverySync(serial: String, reason: String) {
        if (!SensorIdentity.shouldUseNativeHistorySync(serial)) {
            return
        }
        val nowMs = SystemClock.elapsedRealtime()
        synchronized(this) {
            if (serial == lastHistoryRecoverySerial &&
                (nowMs - lastHistoryRecoverySyncAtMs) < UI_RECOVERY_SYNC_MIN_INTERVAL_MS
            ) {
                return
            }
            lastHistoryRecoverySerial = serial
            lastHistoryRecoverySyncAtMs = nowMs
        }
        BatteryTrace.bump(
            key = "dashboard.history.recovery.request",
            logEvery = 20L,
            detail = "serial=$serial reason=$reason"
        )
        HistorySync.syncSensorFromNative(serial, forceFull = false)
    }

    private fun shouldRequestHistoryRecovery(
        startTimeMs: Long,
        history: List<tk.glucodata.ui.GlucosePoint>,
        serial: String?,
        current: CurrentDisplaySource.Snapshot?
    ): Boolean {
        if (history.isEmpty()) {
            return true
        }
        val oldestTimestamp = history.firstOrNull()?.timestamp ?: return true
        if (startTimeMs > 0L && oldestTimestamp > (startTimeMs + HISTORY_RECOVERY_TOLERANCE_MS)) {
            return true
        }
        val latestTimestamp = history.lastOrNull()?.timestamp ?: return true
        if (current == null || current.timeMillis <= 0L || serial.isNullOrBlank()) {
            return false
        }
        if (!current.sensorId.isNullOrBlank() && !SensorIdentity.matches(current.sensorId, serial)) {
            return false
        }
        return current.timeMillis > (latestTimestamp + HISTORY_RECOVERY_TAIL_TOLERANCE_MS)
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
