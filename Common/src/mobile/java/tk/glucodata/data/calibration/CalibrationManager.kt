package tk.glucodata.data.calibration

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import tk.glucodata.Applic
import tk.glucodata.Natives
import tk.glucodata.SensorIdentity
import tk.glucodata.UiRefreshBus
import tk.glucodata.WearSync2
import tk.glucodata.alerts.AlertRuntimeManager
import tk.glucodata.data.HistoryDatabase
import java.util.LinkedHashMap
import kotlin.math.abs
import kotlin.math.pow

object CalibrationManager {
    private const val TAG = "CalibrationManager"
    private const val PREFS_NAME = "calibration_prefs"
    private const val KEY_ENABLED_RAW = "calibration_enabled_raw"
    private const val KEY_ENABLED_AUTO = "calibration_enabled_auto"
    private const val KEY_SENSOR_ENABLEMENT_MIGRATED = "calibration_sensor_enablement_migrated"
    private const val KEY_DISABLED_RAW_SENSOR_IDS = "calibration_disabled_raw_sensor_ids"
    private const val KEY_DISABLED_AUTO_SENSOR_IDS = "calibration_disabled_auto_sensor_ids"
    private const val KEY_BLANK_SENSOR_IDS_MIGRATED = "calibration_blank_sensor_ids_migrated"
    private const val KEY_HISTORY_SENSOR_REPAIR_MIGRATED = "calibration_history_sensor_repair_migrated"
    private const val KEY_ALGORITHM_RAW = "calibration_algorithm_raw"
    private const val KEY_ALGORITHM_AUTO = "calibration_algorithm_auto"
    private const val KEY_HIDE_INITIAL_WHEN_CALIBRATED = "hide_initial_when_calibrated"
    private const val KEY_APPLY_TO_PAST = "calibration_apply_to_past"
    private const val KEY_LOCK_PAST_HISTORY = "calibration_lock_past_history"
    private const val KEY_OVERWRITE_SENSOR_VALUES = "calibration_overwrite_sensor_values"
    private const val KEY_FREEZE_DISPLAYED_VALUES = "calibration_freeze_displayed_values"
    private const val KEY_FREEZE_MIGRATED_FROM_OVERWRITE = "calibration_freeze_migrated_from_overwrite"
    private const val KEY_VISUAL_CONTINUITY = "calibration_visual_continuity"
    private const val KEY_CALIBRATE_FROM_JOURNAL = "calibration_from_journal_bg"
    private const val KEY_KEEP_DISABLED_HISTORY = "calibration_keep_disabled_history"
    private const val KEY_WEIGHT_MODE = "calibration_weight_mode"
    private const val KEY_PROFILE_REVISION_PREFIX = "calibration_profile_revision_"
    private const val HOUR_MS = 3_600_000.0
    private const val PAST_BLEND_WINDOW_MS = 30L * 60L * 1000L
    private const val INTEGRATED_ANCHOR_MATCH_MS = 2L * 60L * 1000L
    private const val LEGACY_SENSOR_RESOLUTION_WINDOW_MS = 15L * 60L * 1000L
    private const val MAX_MANAGED_CALIBRATION_ANCHORS = 12

    enum class CalibrationAlgorithm(
        val storageValue: String,
        val title: String,
        val description: String
    ) {
        SANE_WEIGHTED_OLS(
            storageValue = "sane_weighted_ols",
            title = "Sane Weighted OLS",
            description = "Recency-weighted linear fit with slope guardrails"
        ),
        XDRIP_MEDIAN_SLOPE(
            storageValue = "xdrip_median_slope",
            title = "xDrip Median Slope",
            description = "Median pair-slope fit (Theil-Sen style), robust to outliers"
        ),
        TIME_WEIGHTED_ROBUST_REGRESSION(
            storageValue = "time_weighted_robust_regression",
            title = "Time-Weighted Robust Regression",
            description = "Huber-style weighted regression with temporal decay"
        ),
        ELASTIC_TIME_WEIGHTED_INTERPOLATION(
            storageValue = "elastic_time_weighted_interpolation",
            title = "Elastic Time-Weighted Point Interpolation",
            description = "Local anchor interpolation blended with global trend"
        ),
        ADAPTIVE_ENSEMBLE(
            storageValue = "adaptive_ensemble",
            title = "Adaptive Ensemble (Recommended)",
            description = "Blends robust, elastic and median-slope predictions"
        );

        companion object {
            fun fromStorage(value: String?): CalibrationAlgorithm {
                return values().firstOrNull { it.storageValue == value } ?: ADAPTIVE_ENSEMBLE
            }
        }
    }

    enum class CalibrationWeightMode(
        val storageValue: String
    ) {
        FRESH("fresh"),
        BALANCED("balanced"),
        STABLE("stable");

        companion object {
            fun fromStorage(value: String?): CalibrationWeightMode {
                return values().firstOrNull { it.storageValue == value } ?: FRESH
            }
        }
    }


    data class CalibrationDiagnostics(
        val algorithm: CalibrationAlgorithm = CalibrationAlgorithm.ADAPTIVE_ENSEMBLE,
        val pointCount: Int = 0,
        val slope: Float? = null,
        val intercept: Float? = null,
        val offset: Float? = null,
        val anchorInfluence: Float? = null,
        val confidence: Float? = null,
        val targetValue: Float = 0f,
        val targetTimestamp: Long = 0L,
        val note: String = "Waiting for the next calibrated reading",
        val updatedAt: Long = 0L
    )

    data class CalibrationSample(
        val value: Float,
        val timestamp: Long
    )

    data class SensorCalibrationEnablement(
        val sensorId: String,
        val rawEnabled: Boolean,
        val autoEnabled: Boolean
    )

    private data class CalibrationCacheKey(
        val isRawMode: Boolean,
        val sensorId: String,
        val algorithm: CalibrationAlgorithm,
        val timestamp: Long,
        val quantizedValue: Int,
        val revision: Long
    )

    private data class ValidPointsCacheKey(
        val isRawMode: Boolean,
        val sensorId: String,
        val revision: Long
    )

    private data class CalibrationContext(
        val sensorId: String,
        val algorithm: CalibrationAlgorithm,
        val allPoints: List<CalPoint>,
        val earliestPoint: CalPoint?
    )

    private data class IntegratedContextCacheKey(
        val sensorId: String,
        val isRawMode: Boolean,
        val revision: Long,
        val unit: Int,
    )

    private data class IntegratedBaselineCacheKey(
        val sensorId: String,
        val isRawMode: Boolean,
        val unit: Int,
    )
    
    private lateinit var database: CalibrationDatabase
    private lateinit var dao: CalibrationDao
    private lateinit var prefs: SharedPreferences
    private val initLock = Any()
    @Volatile
    private var calibrationStateLoaded = false
    
    // Reactive list of calibrations
    private val _calibrations = MutableStateFlow<List<CalibrationEntity>>(emptyList())
    val calibrations: StateFlow<List<CalibrationEntity>> = _calibrations
    
    // Calibration enablement is per sensor and mode. An absent sensor ID is enabled by
    // default, matching the historical global default without leaking one sensor's toggle
    // into another sensor's history.
    private val _disabledRawSensorIds = MutableStateFlow<Set<String>>(emptySet())
    private val _disabledAutoSensorIds = MutableStateFlow<Set<String>>(emptySet())

    private val _hideInitialWhenCalibrated = MutableStateFlow(true)
    val hideInitialWhenCalibrated: StateFlow<Boolean> = _hideInitialWhenCalibrated

    private val _applyToPast = MutableStateFlow(false)
    val applyToPast: StateFlow<Boolean> = _applyToPast

    private val _lockPastHistory = MutableStateFlow(false)
    val lockPastHistory: StateFlow<Boolean> = _lockPastHistory

    private val _keepDisabledHistory = MutableStateFlow(false)
    val keepDisabledHistory: StateFlow<Boolean> = _keepDisabledHistory

    private val _overwriteSensorValues = MutableStateFlow(false)
    val overwriteSensorValues: StateFlow<Boolean> = _overwriteSensorValues

    /**
     * Whether a displayed glucose value stops changing once it has stood on
     * screen — see `tk.glucodata.data.ReadingDisplay`. On by default.
     */
    private val _freezeDisplayedValues = MutableStateFlow(true)
    val freezeDisplayedValues: StateFlow<Boolean> = _freezeDisplayedValues

    private val _visualContinuity = MutableStateFlow(true)
    val visualContinuity: StateFlow<Boolean> = _visualContinuity

    private val _calibrateFromJournal = MutableStateFlow(false)
    val calibrateFromJournal: StateFlow<Boolean> = _calibrateFromJournal

    private val _weightMode = MutableStateFlow(CalibrationWeightMode.FRESH)
    val weightMode: StateFlow<CalibrationWeightMode> = _weightMode

    // Per-mode algorithm selection
    private val _algorithmForRaw = MutableStateFlow(CalibrationAlgorithm.ADAPTIVE_ENSEMBLE)
    val algorithmForRaw: StateFlow<CalibrationAlgorithm> = _algorithmForRaw

    private val _algorithmForAuto = MutableStateFlow(CalibrationAlgorithm.ADAPTIVE_ENSEMBLE)
    val algorithmForAuto: StateFlow<CalibrationAlgorithm> = _algorithmForAuto

    private val _diagnosticsForRaw = MutableStateFlow(CalibrationDiagnostics())
    val diagnosticsForRaw: StateFlow<CalibrationDiagnostics> = _diagnosticsForRaw

    private val _diagnosticsForAuto = MutableStateFlow(CalibrationDiagnostics())
    val diagnosticsForAuto: StateFlow<CalibrationDiagnostics> = _diagnosticsForAuto

    private var lastDiagnosticsEmitRaw = 0L
    private var lastDiagnosticsEmitAuto = 0L

    @Volatile
    private var calibrationRevision = 0L
    private val _revision = MutableStateFlow(0L)
    val revision: StateFlow<Long> = _revision
    @Volatile
    private var suppressMirrorSyncCount = 0
    private val calibrationCache = object : LinkedHashMap<CalibrationCacheKey, Float>(512, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<CalibrationCacheKey, Float>?): Boolean {
            return size > 4096
        }
    }
    private val validPointsCache = object : LinkedHashMap<ValidPointsCacheKey, List<CalPoint>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<ValidPointsCacheKey, List<CalPoint>>?): Boolean {
            return size > 128
        }
    }
    private val integratedContextCache = LinkedHashMap<IntegratedContextCacheKey, CalibrationContext>()
    private val integratedBaselineCache = LinkedHashMap<IntegratedBaselineCacheKey, List<CalibrationSample>>()

    fun init(context: Context) {
        synchronized(initLock) {
            initializeLocked(context.applicationContext ?: context)
        }
    }

    private fun initializeLocked(context: Context) {
        database = CalibrationDatabase.getInstance(context)
        dao = database.calibrationDao()
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        _disabledRawSensorIds.value = readSensorIdSet(KEY_DISABLED_RAW_SENSOR_IDS)
        _disabledAutoSensorIds.value = readSensorIdSet(KEY_DISABLED_AUTO_SENSOR_IDS)
        _hideInitialWhenCalibrated.value = prefs.getBoolean(KEY_HIDE_INITIAL_WHEN_CALIBRATED, true)
        _applyToPast.value = prefs.getBoolean(KEY_APPLY_TO_PAST, false)
        _lockPastHistory.value = prefs.getBoolean(KEY_LOCK_PAST_HISTORY, false)
        _keepDisabledHistory.value = prefs.getBoolean(KEY_KEEP_DISABLED_HISTORY, false)
        _overwriteSensorValues.value = prefs.getBoolean(KEY_OVERWRITE_SENSOR_VALUES, false)
        _freezeDisplayedValues.value = prefs.getBoolean(KEY_FREEZE_DISPLAYED_VALUES, true)
        _visualContinuity.value = prefs.getBoolean(KEY_VISUAL_CONTINUITY, true)
        _calibrateFromJournal.value = prefs.getBoolean(KEY_CALIBRATE_FROM_JOURNAL, false)
        _weightMode.value = CalibrationWeightMode.fromStorage(
            prefs.getString(KEY_WEIGHT_MODE, CalibrationWeightMode.FRESH.storageValue)
        )
        runCatching { Natives.setCalibratePast(_applyToPast.value) }
        _algorithmForRaw.value = CalibrationAlgorithm.fromStorage(
            prefs.getString(KEY_ALGORITHM_RAW, CalibrationAlgorithm.ADAPTIVE_ENSEMBLE.storageValue)
        )
        _algorithmForAuto.value = CalibrationAlgorithm.fromStorage(
            prefs.getString(KEY_ALGORITHM_AUTO, CalibrationAlgorithm.ADAPTIVE_ENSEMBLE.storageValue)
        )
    }

    private fun ensureInitialized(): Boolean {
        if (::dao.isInitialized && ::prefs.isInitialized) {
            return true
        }
        val context = Applic.app ?: return false
        synchronized(initLock) {
            if (!::dao.isInitialized || !::prefs.isInitialized) {
                initializeLocked(context.applicationContext ?: context)
            }
        }
        return ::dao.isInitialized && ::prefs.isInitialized
    }

    private fun ensureCalibrationStateLoaded(): Boolean {
        if (calibrationStateLoaded && ::dao.isInitialized && ::prefs.isInitialized) {
            return true
        }
        if (!ensureInitialized()) {
            return false
        }
        synchronized(initLock) {
            if (calibrationStateLoaded) {
                return true
            }
            val list = runCatching {
                runBlocking(Dispatchers.IO) {
                    migrateCalibrationSensorIdsIfPossible()
                    dao.getAllSync()
                }
            }.onFailure {
                Log.w(TAG, "Failed to load calibrations for background access", it)
            }.getOrNull() ?: return false
            _calibrations.value = list
            calibrationStateLoaded = true
            invalidateComputationCache("ensureCalibrationStateLoaded")
        }
        return true
    }

    private fun invalidateComputationCache(reason: String) {
        calibrationRevision++
        _revision.value = calibrationRevision
        synchronized(calibrationCache) {
            calibrationCache.clear()
        }
        synchronized(validPointsCache) {
            validPointsCache.clear()
        }
        synchronized(integratedContextCache) {
            integratedContextCache.clear()
        }
        Log.d(TAG, "Calibration cache invalidated: $reason")
        runCatching {
            tk.glucodata.drivers.ManagedSensorRuntime.notifyUserCalibrationRevisionChanged(calibrationRevision)
        }
    }

    fun getRevision(): Long = calibrationRevision

    @JvmStatic
    fun getIntegratedCalibrationFingerprint(sensorIdOverride: String?, isRawMode: Boolean): Long {
        val sensorId = resolveSensorId(sensorIdOverride)
        val enabled = isEnabledForMode(isRawMode, sensorId)
        ensureCalibrationStateLoaded()
        var hash = 1_125_899_906_842_597L
        fun mix(value: Long) {
            hash = hash * 31L + value
        }
        mix(sensorId.hashCode().toLong())
        mix(if (isRawMode) 1L else 0L)
        mix(if (enabled) 1L else 0L)
        mix(Applic.unit.toLong())
        mix(getAlgorithmForMode(isRawMode).storageValue.hashCode().toLong())
        mix(_weightMode.value.storageValue.hashCode().toLong())
        mix(if (_applyToPast.value) 1L else 0L)
        mix(if (_lockPastHistory.value) 1L else 0L)
        mix(if (_keepDisabledHistory.value) 1L else 0L)
        _calibrations.value
            .asSequence()
            .filter { matchesMode(it, isRawMode) && sensorMatches(it.sensorId, sensorId) }
            .sortedWith(compareBy<CalibrationEntity> { it.timestamp }.thenBy { it.id })
            .forEach { point ->
                mix(point.id.toLong())
                mix(point.timestamp)
                mix(java.lang.Float.floatToRawIntBits(point.sensorValue).toLong())
                mix(java.lang.Float.floatToRawIntBits(point.sensorValueRaw).toLong())
                mix(java.lang.Float.floatToRawIntBits(point.userValue).toLong())
                mix(if (point.isEnabled) 1L else 0L)
            }
        return hash
    }

    /**
     * Whether the stored calibrations are actually in memory.
     *
     * Callers that publish "there is no calibration" — the watch sync above all —
     * must check this first: a load can fail transiently (a Room WAL recovering
     * right after an install, say), and an empty anchor set is then a lie that
     * makes the watch show raw readings.
     */
    @JvmStatic
    fun isCalibrationStateLoaded(): Boolean = ensureCalibrationStateLoaded()

    @JvmStatic
    fun getActiveCalibrationAnchors(sensorIdOverride: String?, isRawMode: Boolean): DoubleArray {
        val sensorId = resolveSensorId(sensorIdOverride)
        if (!isEnabledForMode(isRawMode, sensorId)) return DoubleArray(0)
        ensureCalibrationStateLoaded()
        val rows = _calibrations.value
            .asSequence()
            .filter { row ->
                row.isEnabled &&
                    matchesMode(row, isRawMode) &&
                    sensorMatches(row.sensorId, sensorId)
            }
            .sortedBy { it.timestamp }
            .toList()
            .takeLast(MAX_MANAGED_CALIBRATION_ANCHORS)
        return DoubleArray(rows.size * 3).also { packed ->
            rows.forEachIndexed { index, row ->
                packed[index * 3] = (if (isRawMode) row.sensorValueRaw else row.sensorValue).toDouble()
                packed[index * 3 + 1] = row.userValue.toDouble()
                packed[index * 3 + 2] = row.timestamp.toDouble()
            }
        }
    }

    /**
     * The anchors an integrated evaluation actually fits against, for a driver
     * that folds calibration into the values it stores.
     *
     * A stored anchor's x is the value that was on screen when it was taken,
     * which for such a driver is already corrected. Fitting against that is
     * self-referential, so [rebaseIntegratedContext] moves each anchor onto the
     * stock value at its timestamp before the model runs; this exposes the
     * result so the watch can integrate with the same numbers instead of
     * inventing its own. Falls back to the stored anchors where no stock value
     * is known for a timestamp, exactly as the local path does.
     */
    @JvmStatic
    fun getIntegratedCalibrationAnchors(sensorIdOverride: String?, isRawMode: Boolean): DoubleArray {
        val sensorId = resolveSensorId(sensorIdOverride)
        if (!isEnabledForMode(isRawMode, sensorId)) return DoubleArray(0)
        ensureCalibrationStateLoaded()
        val stored = resolveCalibrationContext(isRawMode, sensorId) ?: return DoubleArray(0)
        val baselineKey = IntegratedBaselineCacheKey(sensorId, isRawMode, Applic.unit)
        val baseline = synchronized(integratedBaselineCache) { integratedBaselineCache[baselineKey] }
        val context = applyRecordedStock(
            if (baseline.isNullOrEmpty()) stored else rebaseIntegratedContext(stored, baseline),
            isRawMode,
            sensorId,
        )
        val points = context.allPoints
            .filter { it.isEnabled }
            .sortedBy { it.timestamp }
            .takeLast(MAX_MANAGED_CALIBRATION_ANCHORS)
        return DoubleArray(points.size * 3).also { packed ->
            points.forEachIndexed { index, point ->
                packed[index * 3] = point.x
                packed[index * 3 + 1] = point.y
                packed[index * 3 + 2] = point.timestamp.toDouble()
            }
        }
    }

    @JvmStatic
    fun notifyExternalCalibrationPipelineChanged() {
        ensureInitialized()
        invalidateComputationCache("externalCalibrationPipeline")
        requestUiRefreshAfterCalibrationChange()
    }

    private fun normalizeSensorId(sensorId: String?): String {
        val normalized = sensorId?.trim()?.takeIf { it.isNotEmpty() } ?: return ""
        return SensorIdentity.resolveAppSensorId(normalized) ?: normalized
    }

    private fun resolveSensorId(sensorIdOverride: String? = null): String {
        val requested = normalizeSensorId(sensorIdOverride)
        if (requested.isNotBlank()) return requested
        val main = normalizeSensorId(SensorIdentity.resolveMainSensor())
        if (main.isNotBlank()) return main
        return normalizeSensorId(Natives.lastsensorname())
    }

    fun getResolvedCurrentSensorId(): String = resolveSensorId()

    fun getSensorEnablementSnapshot(): List<SensorCalibrationEnablement> {
        ensureCalibrationStateLoaded()
        val sensorIds = SensorIdentity.distinctLogicalSensorIds(
            buildList<String?> {
                addAll(_calibrations.value.map { it.sensorId })
                addAll(_disabledRawSensorIds.value)
                addAll(_disabledAutoSensorIds.value)
                add(resolveSensorId())
            }
        )
        return sensorIds.map { sensorId ->
            SensorCalibrationEnablement(
                sensorId = sensorId,
                rawEnabled = isEnabledForMode(isRawMode = true, sensorIdOverride = sensorId),
                autoEnabled = isEnabledForMode(isRawMode = false, sensorIdOverride = sensorId)
            )
        }
    }

    private fun readSensorIdSet(key: String): Set<String> {
        if (!::prefs.isInitialized) return emptySet()
        return prefs.getStringSet(key, emptySet())
            .orEmpty()
            .mapNotNull { normalizeSensorId(it).takeIf(String::isNotBlank) }
            .toSet()
    }

    private fun updateDisabledSensorSet(
        current: Set<String>,
        sensorId: String,
        disabled: Boolean
    ): Set<String> {
        return SensorCalibrationEnablementPolicy.update(
            disabledSensorIds = current,
            sensorId = sensorId,
            disabled = disabled,
            matches = SensorIdentity::matches
        )
    }

    private fun persistDisabledSensorSet(isRawMode: Boolean, sensorIds: Set<String>) {
        val key = if (isRawMode) KEY_DISABLED_RAW_SENSOR_IDS else KEY_DISABLED_AUTO_SENSOR_IDS
        prefs.edit().putStringSet(key, sensorIds.toSet()).apply()
    }

    private fun migrateLegacyEnablementIfPossible() {
        if (!ensureInitialized() || prefs.getBoolean(KEY_SENSOR_ENABLEMENT_MIGRATED, false)) return
        val currentSensor = resolveSensorId()
        if (currentSensor.isBlank()) return

        val migrated = SensorCalibrationEnablementPolicy.migrateLegacyGlobalState(
            rawEnabled = prefs.getBoolean(KEY_ENABLED_RAW, true),
            autoEnabled = prefs.getBoolean(KEY_ENABLED_AUTO, true),
            currentSensorId = currentSensor,
            existing = SensorCalibrationDisabledSets(
                raw = _disabledRawSensorIds.value,
                auto = _disabledAutoSensorIds.value
            ),
            matches = SensorIdentity::matches
        )
        val rawDisabled = migrated.raw
        val autoDisabled = migrated.auto
        _disabledRawSensorIds.value = rawDisabled
        _disabledAutoSensorIds.value = autoDisabled
        prefs.edit()
            .putStringSet(KEY_DISABLED_RAW_SENSOR_IDS, rawDisabled.toSet())
            .putStringSet(KEY_DISABLED_AUTO_SENSOR_IDS, autoDisabled.toSet())
            .putBoolean(KEY_SENSOR_ENABLEMENT_MIGRATED, true)
            .apply()
        Log.i(TAG, "Migrated global calibration enablement to sensor=$currentSensor")
    }

    private suspend fun migrateBlankSensorIdsIfPossible() {
        if (!::prefs.isInitialized || prefs.getBoolean(KEY_BLANK_SENSOR_IDS_MIGRATED, false)) return
        val blankRows = dao.getBlankSensorIdRows()
        if (blankRows.isEmpty()) {
            prefs.edit().putBoolean(KEY_BLANK_SENSOR_IDS_MIGRATED, true).apply()
            return
        }
        var assigned = 0
        var unresolved = 0
        blankRows.forEach { row ->
            val resolvedSensor = resolveLegacyCalibrationSensorFromHistory(row.timestamp)
            if (resolvedSensor.isNullOrBlank()) {
                unresolved++
            } else {
                assigned += dao.assignBlankSensorId(row.id, resolvedSensor)
            }
        }
        if (unresolved == 0) {
            prefs.edit().putBoolean(KEY_BLANK_SENSOR_IDS_MIGRATED, true).apply()
        }
        if (assigned > 0) {
            Log.i(TAG, "Resolved $assigned legacy blank calibration row(s) from Room history")
        }
        if (unresolved > 0) {
            Log.w(TAG, "Left $unresolved legacy blank calibration row(s) unresolved; not assigning to current sensor")
        }
    }

    private suspend fun repairHistoryAssignedSensorIdsIfPossible() {
        if (!::prefs.isInitialized) return
        if (!prefs.getBoolean(KEY_BLANK_SENSOR_IDS_MIGRATED, false)) return
        if (prefs.getBoolean(KEY_HISTORY_SENSOR_REPAIR_MIGRATED, false)) return

        val rows = dao.getAllSync().filter { it.sensorId.isNotBlank() }
        if (rows.isEmpty()) {
            prefs.edit().putBoolean(KEY_HISTORY_SENSOR_REPAIR_MIGRATED, true).apply()
            return
        }

        var repaired = 0
        var ambiguous = 0
        rows.forEach { row ->
            val resolvedSensor = resolveLegacyCalibrationSensorFromHistory(row.timestamp)
            when {
                resolvedSensor.isNullOrBlank() -> ambiguous++
                sensorMatches(row.sensorId, resolvedSensor) -> Unit
                else -> repaired += dao.updateSensorId(row.id, resolvedSensor)
            }
        }

        if (ambiguous == 0) {
            prefs.edit().putBoolean(KEY_HISTORY_SENSOR_REPAIR_MIGRATED, true).apply()
        }
        if (repaired > 0) {
            Log.w(TAG, "Repaired $repaired calibration sensor ID(s) using Room history timestamps")
        }
    }

    private suspend fun resolveLegacyCalibrationSensorFromHistory(timestamp: Long): String? {
        val context = Applic.app ?: return null
        if (timestamp <= 0L) return null
        val readings = runCatching {
            HistoryDatabase.getInstance(context)
                .historyDao()
                .getReadingsBetween(
                    startTime = timestamp - LEGACY_SENSOR_RESOLUTION_WINDOW_MS,
                    endTime = timestamp + LEGACY_SENSOR_RESOLUTION_WINDOW_MS
                )
        }.onFailure {
            Log.w(TAG, "Unable to resolve legacy calibration sensor from history", it)
        }.getOrNull().orEmpty()

        val sensorIds = SensorIdentity.distinctLogicalSensorIds(
            readings.map { reading -> reading.sensorSerial }
        )
        return sensorIds.singleOrNull()
    }

    fun calibrationMatchesSensor(calibrationSensorId: String?, sensorId: String?): Boolean {
        val calibrationSensor = normalizeSensorId(calibrationSensorId)
        val targetSensor = normalizeSensorId(sensorId)
        if (calibrationSensor.isBlank() || targetSensor.isBlank()) return false
        return SensorIdentity.matches(calibrationSensor, targetSensor)
    }

    fun isLegacyUnresolvedCalibration(calibration: CalibrationEntity): Boolean {
        return calibration.sensorId.isBlank()
    }

    private suspend fun migrateCalibrationSensorIdsIfPossible() {
        val legacyBlankMigrationAlreadyMarked = ::prefs.isInitialized &&
            prefs.getBoolean(KEY_BLANK_SENSOR_IDS_MIGRATED, false)
        migrateBlankSensorIdsIfPossible()
        if (legacyBlankMigrationAlreadyMarked) {
            repairHistoryAssignedSensorIdsIfPossible()
        }
    }

    private fun sensorMatches(calibrationSensorId: String, sensorId: String): Boolean {
        return calibrationMatchesSensor(calibrationSensorId, sensorId)
    }

    /**
     * Whether a stored point belongs to the lane being computed.
     *
     * A hand-entered calibration is made against one lane and stays there. A
     * journal-derived one is a finger stick — a fact about the blood, not about
     * a lane — and both lanes were captured when it was paired, so it counts in
     * either mode.
     */
    fun matchesMode(calibration: CalibrationEntity, isRawMode: Boolean): Boolean {
        return calibration.journalEntryId != null || calibration.isRawMode == isRawMode
    }

    private fun getValidPoints(isRawMode: Boolean, sensorId: String): List<CalPoint> {
        ensureCalibrationStateLoaded()
        val normalizedSensorId = normalizeSensorId(sensorId)
        val cacheKey = ValidPointsCacheKey(
            isRawMode = isRawMode,
            sensorId = normalizedSensorId,
            revision = calibrationRevision
        )
        synchronized(validPointsCache) {
            validPointsCache[cacheKey]
        }?.let { return it }

        val currentList = _calibrations.value
        val points = currentList
            .asSequence()
            .filter {
                it.isEnabled &&
                    matchesMode(it, isRawMode) &&
                    sensorMatches(it.sensorId, normalizedSensorId)
            }
            .map { p ->
                CalPoint(
                    x = (if (isRawMode) p.sensorValueRaw else p.sensorValue).toDouble(),
                    y = p.userValue.toDouble(),
                    timestamp = p.timestamp,
                    isEnabled = true
                )
            }
            .toList()
        synchronized(validPointsCache) {
            validPointsCache[cacheKey] = points
        }
        return points
    }

    private inline fun <T> withoutMirrorSync(block: () -> T): T {
        suppressMirrorSyncCount++
        return try {
            block()
        } finally {
            suppressMirrorSyncCount--
        }
    }

    private fun shouldMirrorSync(): Boolean = suppressMirrorSyncCount <= 0

    private fun profileRevisionKey(sensorId: String) = KEY_PROFILE_REVISION_PREFIX + sensorId

    private fun storedProfileRevision(sensorId: String): Long {
        if (!::prefs.isInitialized || sensorId.isBlank()) return 0L
        return runCatching { prefs.getLong(profileRevisionKey(sensorId), 0L) }.getOrDefault(0L)
    }

    private fun setStoredProfileRevision(sensorId: String, revision: Long) {
        if (!::prefs.isInitialized || sensorId.isBlank()) return
        prefs.edit().putLong(profileRevisionKey(sensorId), revision).apply()
    }

    /** How recent this device's calibration profile for [sensorId] is. */
    private fun localProfileRevision(sensorId: String): Long {
        val newestRow = _calibrations.value
            .asSequence()
            .filter { sensorMatches(it.sensorId, sensorId) }
            .maxOfOrNull { it.timestamp } ?: 0L
        return MirrorCalibrationProfilePolicy.localRevision(
            storedRevision = storedProfileRevision(sensorId),
            newestCalibrationTimestamp = newestRow
        )
    }

    /** Marks the local profile as the newest one, so peers stop overwriting it. */
    private fun bumpProfileRevision(sensorId: String) {
        if (!::prefs.isInitialized || sensorId.isBlank()) return
        setStoredProfileRevision(
            sensorId,
            MirrorCalibrationProfilePolicy.nextRevision(
                now = System.currentTimeMillis(),
                localRevision = localProfileRevision(sensorId)
            )
        )
    }

    private fun requestMirrorCalibrationSync(sensorId: String?) {
        if (!shouldMirrorSync()) return
        val normalized = normalizeSensorId(sensorId)
        if (normalized.isBlank()) return
        bumpProfileRevision(normalized)
        pushMirrorCalibrationProfile(normalized)
    }

    /** Re-offers the profile as it stands, without claiming a newer local edit. */
    private fun pushMirrorCalibrationProfile(sensorId: String) {
        if (sensorId.isBlank()) return
        runCatching { Natives.requestMirrorCalibrationSync(sensorId) }
            .onFailure { Log.w(TAG, "Failed requesting mirror calibration sync for $sensorId", it) }
    }

    private fun requestMirrorCalibrationSyncForSensors(sensorIds: Iterable<String?>) {
        if (!shouldMirrorSync()) return
        val targets = linkedSetOf<String>()
        sensorIds.forEach { candidate ->
            normalizeSensorId(candidate).takeIf { it.isNotBlank() }?.let(targets::add)
        }
        normalizeSensorId(Natives.lastsensorname()).takeIf { it.isNotBlank() }?.let(targets::add)
        targets.forEach(::requestMirrorCalibrationSync)
    }

    private fun requestMirrorCalibrationSyncForCurrentOrKnownSensors() {
        requestMirrorCalibrationSyncForSensors(_calibrations.value.map { it.sensorId })
    }

    private fun requestUiRefreshAfterCalibrationChange() {
        UiRefreshBus.requestDataRefresh()
        UiRefreshBus.requestStatusRefresh()
        tk.glucodata.WearSync2.onCalibrationChanged()
    }

    private fun deferGlucoseAlertsUntilNextReading() {
        AlertRuntimeManager.onDisplayCalibrationChanged()
    }

    private fun getValidPointsForSensor(isRawMode: Boolean, sensorIdOverride: String?): List<CalPoint> {
        val sensorId = normalizeSensorId(sensorIdOverride ?: Natives.lastsensorname())
        return getValidPoints(isRawMode = isRawMode, sensorId = sensorId)
    }

    private fun getHistoryAwarePointsForSensor(isRawMode: Boolean, sensorId: String): List<CalPoint> {
        if (!_lockPastHistory.value || !_keepDisabledHistory.value) {
            return getValidPoints(isRawMode = isRawMode, sensorId = sensorId)
        }

        ensureCalibrationStateLoaded()
        val normalizedSensorId = normalizeSensorId(sensorId)
        return _calibrations.value
            .asSequence()
            .filter { matchesMode(it, isRawMode) && sensorMatches(it.sensorId, normalizedSensorId) }
            .map { p ->
                CalPoint(
                    x = (if (isRawMode) p.sensorValueRaw else p.sensorValue).toDouble(),
                    y = p.userValue.toDouble(),
                    timestamp = p.timestamp,
                    isEnabled = p.isEnabled
                )
            }
            .filter { it.x.isFinite() && it.x > 0.0 && it.y.isFinite() && it.y > 0.0 }
            .toList()
    }
    
    @JvmOverloads
    fun setEnabledForMode(isRawMode: Boolean, enabled: Boolean, sensorIdOverride: String? = null) {
        if (!ensureInitialized()) return
        val sensorId = resolveSensorId(sensorIdOverride)
        if (sensorId.isBlank()) {
            Log.w(TAG, "Ignoring calibration enablement change without a sensor ID")
            return
        }
        migrateLegacyEnablementIfPossible()
        val current = if (isRawMode) _disabledRawSensorIds.value else _disabledAutoSensorIds.value
        val updated = updateDisabledSensorSet(current, sensorId, disabled = !enabled)
        if (updated == current) return
        if (isRawMode) {
            _disabledRawSensorIds.value = updated
        } else {
            _disabledAutoSensorIds.value = updated
        }
        persistDisabledSensorSet(isRawMode, updated)
        invalidateComputationCache("setEnabledForMode(${if (isRawMode) "raw" else "auto"})")
        deferGlucoseAlertsUntilNextReading()
        requestUiRefreshAfterCalibrationChange()
        requestMirrorCalibrationSync(sensorId)
        Log.i(TAG, "Calibration enabled for ${if (isRawMode) "Raw" else "Auto"}: $enabled sensor=$sensorId")
    }

    @JvmOverloads
    fun isEnabledForMode(isRawMode: Boolean, sensorIdOverride: String? = null): Boolean {
        if (!ensureInitialized()) return true
        val sensorId = resolveSensorId(sensorIdOverride)
        if (sensorId.isBlank()) {
            return if (isRawMode) {
                prefs.getBoolean(KEY_ENABLED_RAW, true)
            } else {
                prefs.getBoolean(KEY_ENABLED_AUTO, true)
            }
        }
        migrateLegacyEnablementIfPossible()
        val disabled = if (isRawMode) _disabledRawSensorIds.value else _disabledAutoSensorIds.value
        return SensorCalibrationEnablementPolicy.isEnabled(
            disabledSensorIds = disabled,
            sensorId = sensorId,
            matches = SensorIdentity::matches
        )
    }

    fun setHideInitialWhenCalibrated(enabled: Boolean) {
        if (_hideInitialWhenCalibrated.value == enabled) return
        _hideInitialWhenCalibrated.value = enabled
        if (::prefs.isInitialized) {
            prefs.edit().putBoolean(KEY_HIDE_INITIAL_WHEN_CALIBRATED, enabled).apply()
        }
        requestUiRefreshAfterCalibrationChange()
        requestMirrorCalibrationSyncForCurrentOrKnownSensors()
        Log.i(TAG, "Hide initial when calibrated: $enabled")
    }

    fun shouldHideInitialWhenCalibrated(): Boolean {
        ensureInitialized()
        return _hideInitialWhenCalibrated.value
    }

    fun setApplyToPast(enabled: Boolean) {
        if (_applyToPast.value == enabled) return
        _applyToPast.value = enabled
        if (::prefs.isInitialized) {
            prefs.edit().putBoolean(KEY_APPLY_TO_PAST, enabled).apply()
        }
        runCatching { Natives.setCalibratePast(enabled) }
        invalidateComputationCache("setApplyToPast")
        requestUiRefreshAfterCalibrationChange()
        requestMirrorCalibrationSyncForCurrentOrKnownSensors()
        Log.i(TAG, "Apply calibration to past: $enabled")
    }

    fun shouldApplyToPast(): Boolean {
        ensureInitialized()
        return _applyToPast.value
    }

    fun setLockPastHistory(enabled: Boolean) {
        if (_lockPastHistory.value == enabled) return
        _lockPastHistory.value = enabled
        if (::prefs.isInitialized) {
            prefs.edit().putBoolean(KEY_LOCK_PAST_HISTORY, enabled).apply()
        }
        invalidateComputationCache("setLockPastHistory")
        requestUiRefreshAfterCalibrationChange()
        requestMirrorCalibrationSyncForCurrentOrKnownSensors()
        Log.i(TAG, "Lock past history calibration rewrite: $enabled")
    }

    fun shouldLockPastHistory(): Boolean {
        ensureInitialized()
        return _lockPastHistory.value
    }

    fun setKeepDisabledHistory(enabled: Boolean) {
        if (_keepDisabledHistory.value == enabled) return
        _keepDisabledHistory.value = enabled
        if (::prefs.isInitialized) {
            prefs.edit().putBoolean(KEY_KEEP_DISABLED_HISTORY, enabled).apply()
        }
        invalidateComputationCache("setKeepDisabledHistory")
        requestUiRefreshAfterCalibrationChange()
        requestMirrorCalibrationSyncForCurrentOrKnownSensors()
        Log.i(TAG, "Keep disabled calibrations for locked history: $enabled")
    }

    fun shouldKeepDisabledHistory(): Boolean {
        ensureInitialized()
        return _keepDisabledHistory.value
    }

    fun setOverwriteSensorValues(enabled: Boolean) {
        if (_overwriteSensorValues.value == enabled) return
        _overwriteSensorValues.value = enabled
        if (::prefs.isInitialized) {
            prefs.edit().putBoolean(KEY_OVERWRITE_SENSOR_VALUES, enabled).apply()
        }
        requestUiRefreshAfterCalibrationChange()
        requestMirrorCalibrationSyncForCurrentOrKnownSensors()
        Log.i(TAG, "Overwrite sensor values in history DB: $enabled")
    }

    fun shouldOverwriteSensorValues(): Boolean {
        ensureInitialized()
        return _overwriteSensorValues.value
    }

    fun setFreezeDisplayedValues(enabled: Boolean) {
        if (_freezeDisplayedValues.value == enabled) return
        _freezeDisplayedValues.value = enabled
        if (::prefs.isInitialized) {
            prefs.edit().putBoolean(KEY_FREEZE_DISPLAYED_VALUES, enabled).apply()
        }
        requestUiRefreshAfterCalibrationChange()
        Log.i(TAG, "Freeze displayed values: $enabled")
    }

    fun shouldFreezeDisplayedValues(): Boolean {
        ensureInitialized()
        return _freezeDisplayedValues.value
    }

    /**
     * Retires the old "overwrite sensor values in history DB" switch.
     *
     * That switch is what corrupted stores: it rewrote `history_readings.value`
     * in place from its own previous output, so calibration compounded and the
     * sensor's own number was destroyed. Its replacement records the displayed
     * value beside the reading instead. Anyone who had it on wanted their
     * displayed values kept, so they get the freeze; the destructive behaviour
     * itself is simply gone, and the old key is cleared so a downgrade cannot
     * resurrect it.
     *
     * Runs once. Returns true if this call performed the migration.
     */
    fun migrateOverwriteSensorValuesToFreeze(): Boolean {
        ensureInitialized()
        if (!::prefs.isInitialized) return false
        if (prefs.getBoolean(KEY_FREEZE_MIGRATED_FROM_OVERWRITE, false)) return false

        val hadOverwrite = prefs.getBoolean(KEY_OVERWRITE_SENSOR_VALUES, false)
        prefs.edit()
            .putBoolean(KEY_FREEZE_MIGRATED_FROM_OVERWRITE, true)
            .putBoolean(KEY_FREEZE_DISPLAYED_VALUES, true)
            .remove(KEY_OVERWRITE_SENSOR_VALUES)
            .apply()
        _overwriteSensorValues.value = false
        _freezeDisplayedValues.value = true
        Log.i(TAG, "Migrated overwrite-sensor-values ($hadOverwrite) to freeze-displayed-values")
        return hadOverwrite
    }

    fun setVisualContinuity(enabled: Boolean) {
        if (_visualContinuity.value == enabled) return
        _visualContinuity.value = enabled
        if (::prefs.isInitialized) {
            prefs.edit().putBoolean(KEY_VISUAL_CONTINUITY, enabled).apply()
        }
        requestUiRefreshAfterCalibrationChange()
        requestMirrorCalibrationSyncForCurrentOrKnownSensors()
        Log.i(TAG, "Visual continuity mode: $enabled")
    }

    fun shouldVisualContinuity(): Boolean {
        ensureInitialized()
        return _visualContinuity.value
    }

    /**
     * Turns every blood-glucose entry in the journal — a meter reading or a
     * finger stick typed in by hand — into a calibration point. Switching it off
     * removes the derived points again; hand-entered calibrations are untouched
     * either way.
     */
    fun setCalibrateFromJournal(enabled: Boolean) {
        if (_calibrateFromJournal.value == enabled) return
        _calibrateFromJournal.value = enabled
        if (::prefs.isInitialized) {
            prefs.edit().putBoolean(KEY_CALIBRATE_FROM_JOURNAL, enabled).apply()
        }
        Log.i(TAG, "Calibrate from journal BG: $enabled")
        JournalCalibrationSync.onSettingChanged(enabled)
    }

    fun shouldCalibrateFromJournal(): Boolean {
        ensureInitialized()
        return _calibrateFromJournal.value
    }

    fun setWeightMode(mode: CalibrationWeightMode) {
        if (_weightMode.value == mode) return
        _weightMode.value = mode
        if (::prefs.isInitialized) {
            prefs.edit().putString(KEY_WEIGHT_MODE, mode.storageValue).apply()
        }
        invalidateComputationCache("setWeightMode")
        refreshDiagnosticsPreview(isRawMode = true, force = true)
        refreshDiagnosticsPreview(isRawMode = false, force = true)
        deferGlucoseAlertsUntilNextReading()
        requestUiRefreshAfterCalibrationChange()
        requestMirrorCalibrationSyncForCurrentOrKnownSensors()
        Log.i(TAG, "Calibration weight mode: ${mode.storageValue}")
    }

    fun getWeightMode(): CalibrationWeightMode {
        ensureInitialized()
        return _weightMode.value
    }

    fun setAlgorithmForMode(isRawMode: Boolean, algorithm: CalibrationAlgorithm) {
        if (isRawMode) {
            if (_algorithmForRaw.value == algorithm) return
            _algorithmForRaw.value = algorithm
            if (::prefs.isInitialized) {
                prefs.edit().putString(KEY_ALGORITHM_RAW, algorithm.storageValue).apply()
            }
        } else {
            if (_algorithmForAuto.value == algorithm) return
            _algorithmForAuto.value = algorithm
            if (::prefs.isInitialized) {
                prefs.edit().putString(KEY_ALGORITHM_AUTO, algorithm.storageValue).apply()
            }
        }
        invalidateComputationCache("setAlgorithmForMode(${if (isRawMode) "raw" else "auto"})")
        refreshDiagnosticsPreview(isRawMode = isRawMode, force = true)
        deferGlucoseAlertsUntilNextReading()
        requestUiRefreshAfterCalibrationChange()
        requestMirrorCalibrationSyncForCurrentOrKnownSensors()
        Log.i(TAG, "Calibration algorithm for ${if (isRawMode) "Raw" else "Auto"}: ${algorithm.title}")
    }

    /** The settings snapshot handed to the shared computation, and to the watch. */
    fun tuningForMode(isRawMode: Boolean): CalibrationTuning {
        ensureInitialized()
        return CalibrationTuning(
            algorithm = getAlgorithmForMode(isRawMode).storageValue,
            weightMode = _weightMode.value.storageValue,
            applyToPast = _applyToPast.value,
            lockPastHistory = _lockPastHistory.value,
            keepDisabledHistory = _keepDisabledHistory.value,
        )
    }

    fun getAlgorithmForMode(isRawMode: Boolean): CalibrationAlgorithm {
        ensureInitialized()
        return if (isRawMode) _algorithmForRaw.value else _algorithmForAuto.value
    }

    data class CalibrationProfileImportResult(
        val sensorId: String,
        val imported: Int,
        val skipped: Int,
        val replaced: Int,
        val message: String
    )

    fun exportProfileForSensorAsJson(sensorId: String): String? {
        val normalizedSensorId = normalizeSensorId(sensorId)
        if (normalizedSensorId.isBlank()) return null
        ensureCalibrationStateLoaded()

        // Journal-derived points are left out on purpose: the receiving device
        // derives its own from the journal it already syncs, and a copied one
        // would outlive the entry it came from — no entry to track it back to.
        val rows = _calibrations.value
            .filter { it.journalEntryId == null && sensorMatches(it.sensorId, normalizedSensorId) }
            .sortedByDescending { it.timestamp }

        val root = JSONObject()
        root.put("version", 2)
        root.put("sensorId", normalizedSensorId)
        root.put("createdAt", System.currentTimeMillis())
        root.put("revision", localProfileRevision(normalizedSensorId))
        root.put("rawEnabled", isEnabledForMode(isRawMode = true, sensorIdOverride = normalizedSensorId))
        root.put("autoEnabled", isEnabledForMode(isRawMode = false, sensorIdOverride = normalizedSensorId))
        root.put("hideInitialWhenCalibrated", _hideInitialWhenCalibrated.value)
        root.put("applyToPast", _applyToPast.value)
        root.put("lockPastHistory", _lockPastHistory.value)
        root.put("keepDisabledHistory", _keepDisabledHistory.value)
        root.put("overwriteSensorValues", _overwriteSensorValues.value)
        root.put("visualContinuity", _visualContinuity.value)
        root.put("weightMode", _weightMode.value.storageValue)
        root.put("rawAlgorithm", _algorithmForRaw.value.storageValue)
        root.put("autoAlgorithm", _algorithmForAuto.value.storageValue)

        val payload = JSONArray()
        rows.forEach { row ->
            val obj = JSONObject()
            obj.put("timestamp", row.timestamp)
            obj.put("sensorId", if (row.sensorId.isBlank()) normalizedSensorId else row.sensorId)
            obj.put("sensorValue", row.sensorValue.toDouble())
            obj.put("sensorValueRaw", row.sensorValueRaw.toDouble())
            obj.put("userValue", row.userValue.toDouble())
            obj.put("isEnabled", row.isEnabled)
            obj.put("isRawMode", row.isRawMode)
            payload.put(obj)
        }
        root.put("calibrations", payload)

        return root.toString(2)
    }

    suspend fun importProfileFromJson(
        json: String,
        replaceExisting: Boolean,
        overrideSensorId: String? = null
    ): CalibrationProfileImportResult {
        val root = JSONObject(json)
        val sourceSensorId = root.optString("sensorId", "").ifBlank { resolveSensorId() }
        val targetSensorId = normalizeSensorId(overrideSensorId?.ifBlank { null } ?: sourceSensorId)
        if (targetSensorId.isBlank()) {
            return CalibrationProfileImportResult(
                sensorId = "",
                imported = 0,
                skipped = 0,
                replaced = 0,
                message = "No target sensor found in profile"
            )
        }

        val rawEnabled = root.optBoolean(
            "rawEnabled",
            isEnabledForMode(isRawMode = true, sensorIdOverride = targetSensorId)
        )
        val autoEnabled = root.optBoolean(
            "autoEnabled",
            isEnabledForMode(isRawMode = false, sensorIdOverride = targetSensorId)
        )
        val hideInitialWhenCalibrated = root.optBoolean(
            "hideInitialWhenCalibrated",
            _hideInitialWhenCalibrated.value
        )
        val applyToPast = root.optBoolean("applyToPast", _applyToPast.value)
        val lockPastHistory = root.optBoolean("lockPastHistory", _lockPastHistory.value)
        val keepDisabledHistory = root.optBoolean(
            "keepDisabledHistory",
            _keepDisabledHistory.value
        )
        val overwriteSensorValues = root.optBoolean(
            "overwriteSensorValues",
            _overwriteSensorValues.value
        )
        val visualContinuity = root.optBoolean("visualContinuity", _visualContinuity.value)
        val weightMode = CalibrationWeightMode.fromStorage(root.optString("weightMode", ""))
        val rawAlgorithm = CalibrationAlgorithm.fromStorage(root.optString("rawAlgorithm", ""))
        val autoAlgorithm = CalibrationAlgorithm.fromStorage(root.optString("autoAlgorithm", ""))

        val array = root.optJSONArray("calibrations") ?: JSONArray()
        val incoming = mutableListOf<CalibrationEntity>()
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val timestamp = obj.optLong("timestamp", 0L)
            if (timestamp <= 0L) continue

            incoming.add(
                CalibrationEntity(
                    id = 0,
                    timestamp = timestamp,
                    sensorId = targetSensorId,
                    sensorValue = obj.optDouble("sensorValue", 0.0).toFloat(),
                    sensorValueRaw = obj.optDouble("sensorValueRaw", 0.0).toFloat(),
                    userValue = obj.optDouble("userValue", 0.0).toFloat(),
                    isEnabled = obj.optBoolean("isEnabled", true),
                    isRawMode = obj.optBoolean("isRawMode", false)
                )
            )
        }

        val replaced = if (replaceExisting) dao.deleteForSensor(targetSensorId) else 0

        val existing = if (replaceExisting) emptyList() else dao.getAllSync().filter { sensorMatches(it.sensorId, targetSensorId) }
        val deduped = mutableListOf<CalibrationEntity>()
        var skipped = 0
        incoming.forEach { row ->
            val duplicate = existing.any { old ->
                old.isRawMode == row.isRawMode &&
                    abs(old.timestamp - row.timestamp) <= 30_000L &&
                    abs(old.userValue - row.userValue) <= 0.05f &&
                    abs(old.sensorValue - row.sensorValue) <= 0.05f &&
                    abs(old.sensorValueRaw - row.sensorValueRaw) <= 0.05f
            } || deduped.any { added ->
                added.isRawMode == row.isRawMode &&
                    abs(added.timestamp - row.timestamp) <= 30_000L &&
                    abs(added.userValue - row.userValue) <= 0.05f &&
                    abs(added.sensorValue - row.sensorValue) <= 0.05f &&
                    abs(added.sensorValueRaw - row.sensorValueRaw) <= 0.05f
            }

            if (duplicate) {
                skipped++
            } else {
                deduped.add(row)
            }
        }

        if (deduped.isNotEmpty()) {
            dao.insertAll(deduped)
        }

        setEnabledForMode(isRawMode = true, enabled = rawEnabled, sensorIdOverride = targetSensorId)
        setEnabledForMode(isRawMode = false, enabled = autoEnabled, sensorIdOverride = targetSensorId)
        setHideInitialWhenCalibrated(hideInitialWhenCalibrated)
        setApplyToPast(applyToPast)
        setLockPastHistory(lockPastHistory)
        setKeepDisabledHistory(keepDisabledHistory)
        setOverwriteSensorValues(overwriteSensorValues)
        setVisualContinuity(visualContinuity)
        setWeightMode(weightMode)
        setAlgorithmForMode(isRawMode = true, algorithm = rawAlgorithm)
        setAlgorithmForMode(isRawMode = false, algorithm = autoAlgorithm)

        deferGlucoseAlertsUntilNextReading()
        loadCalibrations()

        requestMirrorCalibrationSync(targetSensorId)

        return CalibrationProfileImportResult(
            sensorId = targetSensorId,
            imported = deduped.size,
            skipped = skipped,
            replaced = replaced,
            message = "Imported ${deduped.size} calibration(s) for $targetSensorId"
        )
    }
    
    suspend fun loadCalibrations() {
        if (::dao.isInitialized) {
            val list = withContext(Dispatchers.IO) {
                migrateCalibrationSensorIdsIfPossible()
                dao.getAllSync()
            }
            if (calibrationStateLoaded && _calibrations.value == list) return
            _calibrations.value = list
            calibrationStateLoaded = true
            invalidateComputationCache("loadCalibrations")
            requestUiRefreshAfterCalibrationChange()
        }
    }

    @JvmOverloads
    suspend fun addCalibration(timestamp: Long, sensorValue: Float, sensorValueRaw: Float, userValue: Float, sensorId: String? = null, isRawMode: Boolean = false, sensorValueStock: Float = 0f): Boolean {
        val resolvedSensorId = resolveSensorId(sensorId)
        if (resolvedSensorId.isBlank()) {
            Log.e(TAG, "Rejected calibration without a sensor ID at $timestamp")
            return false
        }
        val entity = CalibrationEntity(
            timestamp = timestamp,
            sensorId = resolvedSensorId,
            sensorValue = sensorValue,
            sensorValueRaw = sensorValueRaw,
            userValue = userValue,
            isRawMode = isRawMode,
            sensorValueStock = sensorValueStock.takeIf { it.isFinite() && it > 0f } ?: 0f
        )
        withContext(Dispatchers.IO) { dao.insert(entity) }
        deferGlucoseAlertsUntilNextReading()
        loadCalibrations()
        refreshDiagnosticsPreview(isRawMode = isRawMode, force = true)
        requestMirrorCalibrationSync(entity.sensorId)
        Log.i(TAG, "Added calibration: sensor=$resolvedSensorId auto=$sensorValue raw=$sensorValueRaw stock=${entity.sensorValueStock} user=$userValue isRaw=$isRawMode at $timestamp")
        return true
    }

    /**
     * Applies one reconciliation of the journal-derived calibrations in a single
     * pass. Journal syncs touch many rows at once — a meter handing over its
     * stored history writes dozens — and running the per-point path for each
     * would reload the table and re-push the mirror profile every time.
     */
    suspend fun applyJournalCalibrationPlan(plan: JournalCalibrationPolicy.Plan, reason: String) {
        if (plan.isEmpty) return
        if (!ensureInitialized()) return
        withContext(Dispatchers.IO) {
            if (plan.deleteIds.isNotEmpty()) dao.deleteByIds(plan.deleteIds)
            if (plan.inserts.isNotEmpty()) dao.insertAll(plan.inserts)
            plan.updates.forEach { dao.update(it) }
        }
        deferGlucoseAlertsUntilNextReading()
        loadCalibrations()
        refreshDiagnosticsPreview(isRawMode = false, force = true)
        refreshDiagnosticsPreview(isRawMode = true, force = true)
        Log.i(
            TAG,
            "Journal calibrations synced ($reason): +${plan.inserts.size} " +
                "~${plan.updates.size} -${plan.deleteIds.size}"
        )
    }

    /** Drops every calibration derived from a journal entry, keeping manual ones. */
    suspend fun purgeJournalCalibrations() {
        if (!ensureInitialized()) return
        val removed = withContext(Dispatchers.IO) { dao.deleteJournalDerived() }
        if (removed <= 0) return
        deferGlucoseAlertsUntilNextReading()
        loadCalibrations()
        refreshDiagnosticsPreview(isRawMode = false, force = true)
        refreshDiagnosticsPreview(isRawMode = true, force = true)
        Log.i(TAG, "Removed $removed journal-derived calibration(s)")
    }

    suspend fun restoreCalibration(entity: CalibrationEntity) {
        withContext(Dispatchers.IO) { dao.insert(entity) }
        deferGlucoseAlertsUntilNextReading()
        loadCalibrations()
        refreshDiagnosticsPreview(isRawMode = entity.isRawMode, force = true)
        requestMirrorCalibrationSync(entity.sensorId)
        Log.i(TAG, "Restored calibration: id=${entity.id}")
    }

    suspend fun deleteCalibration(entity: CalibrationEntity) {
        withContext(Dispatchers.IO) { dao.delete(entity) }
        deferGlucoseAlertsUntilNextReading()
        loadCalibrations()
        refreshDiagnosticsPreview(isRawMode = entity.isRawMode, force = true)
        requestMirrorCalibrationSync(entity.sensorId)
        Log.i(TAG, "Deleted calibration: id=${entity.id}")
    }
    
    suspend fun updateCalibration(entity: CalibrationEntity) {
        withContext(Dispatchers.IO) { dao.update(entity) }
        deferGlucoseAlertsUntilNextReading()
        loadCalibrations()
        refreshDiagnosticsPreview(isRawMode = entity.isRawMode, force = true)
        requestMirrorCalibrationSync(entity.sensorId)
    }
    
    /**
     * Records a fingerstick calibration entered on the watch. The watch cannot
     * see the sensor's current lanes, so the values are taken here — the same
     * ones the phone's own calibration sheet would use.
     */
    @JvmOverloads
    fun addCalibrationFromWearBlocking(
        userValueMgdl: Float,
        sensorStockMgdl: Float = Float.NaN,
    ): Boolean = kotlinx.coroutines.runBlocking {
        val snapshot = tk.glucodata.CurrentDisplaySource.resolveCurrentForExchange()
            ?: return@runBlocking false
        val autoValue = snapshot.autoValue.takeIf { it.isFinite() && it > 0f }
            ?: snapshot.primaryValue.takeIf { it.isFinite() && it > 0f }
            ?: return@runBlocking false
        val rawValue = snapshot.rawValue.takeIf { it.isFinite() && it > 0f } ?: autoValue
        val now = System.currentTimeMillis()
        addCalibration(
            timestamp = now,
            sensorValue = autoValue,
            sensorValueRaw = rawValue,
            userValue = storedValueFromMgdl(userValueMgdl),
            sensorId = snapshot.sensorId,
            sensorValueStock = storedStockValue(sensorStockMgdl, now, snapshot.sensorId),
        )
    }

    /**
     * The stock value to record on an anchor, in the display unit.
     *
     * Prefers what the watch sent — only the device that computed the reading
     * knows it — and otherwise asks this device, which knows it when the phone
     * is the one holding the sensor. 0 where neither does, which leaves the fit
     * to recover it from the source history exactly as before.
     */
    private fun storedStockValue(sensorStockMgdl: Float, timestamp: Long, sensorId: String?): Float {
        if (sensorStockMgdl.isFinite() && sensorStockMgdl > 0f) return storedValueFromMgdl(sensorStockMgdl)
        val local = runCatching {
            tk.glucodata.IntegratedStockBaseline.stockAt(sensorId, timestamp)
        }.getOrDefault(Float.NaN)
        return local.takeIf { it.isFinite() && it > 0f } ?: 0f
    }

    /**
     * Records a watch calibration against a chosen reading rather than the
     * current one, the way the phone's sheet uses the row you tapped: the lanes
     * come from the history point nearest that time.
     */
    fun addCalibrationFromWearAtBlocking(
        timestampMs: Long,
        userValueMgdl: Float,
        sensorStockMgdl: Float,
    ): Boolean =
        kotlinx.coroutines.runBlocking {
            if (timestampMs <= 0L) {
                return@runBlocking addCalibrationFromWearBlocking(userValueMgdl, sensorStockMgdl)
            }
            val sensorId = tk.glucodata.SensorIdentity.resolveMainSensor() ?: return@runBlocking false
            val isMmol = tk.glucodata.Applic.unit == 1
            val window = 15L * 60L * 1000L
            val nearest = runCatching {
                tk.glucodata.NotificationHistorySource.getDisplayHistory(
                    timestampMs - window,
                    isMmol,
                    sensorId,
                )
            }.getOrDefault(emptyList())
                .filter { kotlin.math.abs(it.timestamp - timestampMs) <= window }
                .minByOrNull { kotlin.math.abs(it.timestamp - timestampMs) }
                ?: return@runBlocking false
            val autoValue = nearest.value.takeIf { it.isFinite() && it > 0f } ?: return@runBlocking false
            val rawValue = nearest.rawValue.takeIf { it.isFinite() && it > 0f } ?: autoValue
            addCalibration(
                timestamp = nearest.timestamp,
                sensorValue = autoValue,
                sensorValueRaw = rawValue,
                userValue = storedValueFromMgdl(userValueMgdl),
                sensorId = sensorId,
                sensorValueStock = storedStockValue(sensorStockMgdl, nearest.timestamp, sensorId),
            )
        }

    /**
     * The watch sends canonical mg/dL — it must not depend on the phone's unit
     * setting — but calibrations are stored in the display unit, the way the
     * phone's own sheet writes them. Storing mg/dL directly is what turned a
     * 5.8 mmol calibration into "104,0" in the list.
     */
    private fun storedValueFromMgdl(mgdl: Float): Float =
        if (tk.glucodata.Applic.unit == 1) mgdl / tk.glucodata.ui.util.GlucoseFormatter.MGDL_PER_MMOL else mgdl

    /**
     * Blocking per-entry operations for the shared Wear bridge. The watch knows
     * a calibration by its timestamp — it never sees the Room id — so entries
     * are looked up that way.
     */
    fun deleteCalibrationAtBlocking(timestamp: Long): Boolean = kotlinx.coroutines.runBlocking {
        val entity = _calibrations.value.firstOrNull { it.timestamp == timestamp }
            ?: return@runBlocking false
        deleteCalibration(entity)
        true
    }

    fun updateCalibrationUserValueBlocking(timestamp: Long, userValueMgdl: Float): Boolean =
        kotlinx.coroutines.runBlocking {
            val entity = _calibrations.value.firstOrNull { it.timestamp == timestamp }
                ?: return@runBlocking false
            updateCalibration(entity.copy(userValue = storedValueFromMgdl(userValueMgdl)))
            true
        }

    /** Blocking entry point for the shared Wear bridge (see WearCalibrationCommand). */
    fun clearAllBlocking() {
        kotlinx.coroutines.runBlocking { clearAll() }
    }

    suspend fun clearAll() {
        val affectedSensors = _calibrations.value.map { it.sensorId }
        withContext(Dispatchers.IO) { dao.deleteAll() }
        deferGlucoseAlertsUntilNextReading()
        loadCalibrations()
        refreshDiagnosticsPreview(isRawMode = true, force = true)
        refreshDiagnosticsPreview(isRawMode = false, force = true)
        requestMirrorCalibrationSyncForSensors(affectedSensors)
        Log.i(TAG, "Cleared all calibrations")
    }

    suspend fun restoreAll(calibrations: List<CalibrationEntity>) {
        withContext(Dispatchers.IO) { dao.insertAll(calibrations) }
        deferGlucoseAlertsUntilNextReading()
        loadCalibrations()
        refreshDiagnosticsPreview(isRawMode = true, force = true)
        refreshDiagnosticsPreview(isRawMode = false, force = true)
        requestMirrorCalibrationSyncForSensors(calibrations.map { it.sensorId })
        Log.i(TAG, "Restored ${calibrations.size} calibrations")
    }

    fun importProfileFromJsonBlocking(
        json: String,
        replaceExisting: Boolean,
        overrideSensorId: String? = null
    ): CalibrationProfileImportResult = runBlocking {
        importProfileFromJson(json, replaceExisting, overrideSensorId)
    }

    /**
     * A mirror import replaces the target sensor's calibration set wholesale, so it may
     * only ever be applied when the peer's profile is genuinely newer than ours. Without
     * that check a master with no calibrations of its own wipes the follower's every time
     * it pushes readings, which is exactly what the follower's own calibration is for.
     * See [MirrorCalibrationProfilePolicy].
     */
    fun importMirrorProfileFromJsonBlocking(
        json: String,
        overrideSensorId: String? = null
    ): CalibrationProfileImportResult {
        ensureCalibrationStateLoaded()
        val root = runCatching { JSONObject(json) }.getOrNull()
            ?: return CalibrationProfileImportResult(
                sensorId = normalizeSensorId(overrideSensorId),
                imported = 0,
                skipped = 0,
                replaced = 0,
                message = "Unreadable mirror calibration profile"
            )
        val sourceSensorId = root.optString("sensorId", "").ifBlank { resolveSensorId() }
        val targetSensorId = normalizeSensorId(overrideSensorId?.ifBlank { null } ?: sourceSensorId)
        if (targetSensorId.isBlank()) {
            return CalibrationProfileImportResult(
                sensorId = "",
                imported = 0,
                skipped = 0,
                replaced = 0,
                message = "No target sensor found in mirror profile"
            )
        }

        val incomingRevision = MirrorCalibrationProfilePolicy.incomingRevision(root)
        val localRevision = localProfileRevision(targetSensorId)
        if (!MirrorCalibrationProfilePolicy.shouldApply(incomingRevision, localRevision)) {
            Log.i(
                TAG,
                "Kept local calibration profile for $targetSensorId: mirror revision $incomingRevision <= local $localRevision"
            )
            if (MirrorCalibrationProfilePolicy.shouldOfferLocalBack(incomingRevision, localRevision)) {
                // Ours is the newer profile — offer it back so the peer converges on it.
                pushMirrorCalibrationProfile(targetSensorId)
            }
            return CalibrationProfileImportResult(
                sensorId = targetSensorId,
                imported = 0,
                skipped = 0,
                replaced = 0,
                message = "Kept newer local calibration profile for $targetSensorId"
            )
        }

        val result = withoutMirrorSync {
            runBlocking {
                importProfileFromJson(
                    json,
                    replaceExisting = true,
                    overrideSensorId = targetSensorId
                )
            }
        }
        setStoredProfileRevision(targetSensorId, incomingRevision)
        return result
    }

    /**
     * Apply calibration to a glucose value.
     * Mode-specific: only applies calibrations made in the same mode (Raw or Auto).
     * Uses the selected per-mode algorithm.
     */
    @JvmOverloads
    fun getCalibratedValue(
        value: Float,
        timestamp: Long,
        isRawMode: Boolean,
        emitDiagnostics: Boolean = false,
        sensorIdOverride: String? = null
    ): Float {
        val currentSensor = resolveSensorId(sensorIdOverride)
        if (!isEnabledForMode(isRawMode, currentSensor)) {
            return value
        }
        // Missing/invalid inputs must stay missing. Calibrating 0/NaN can produce
        // synthetic values that look real (e.g. in raw mode when raw is unavailable).
        if (!value.isFinite() || value <= 0f) {
            return value
        }
        if (tk.glucodata.drivers.ManagedSensorRuntime.integratesUserCalibration(currentSensor, isRawMode)) {
            return value
        }

        val algorithm = getAlgorithmForMode(isRawMode)
        val cacheKey = CalibrationCacheKey(
            isRawMode = isRawMode,
            sensorId = currentSensor,
            algorithm = algorithm,
            timestamp = timestamp,
            quantizedValue = java.lang.Float.floatToRawIntBits(value),
            revision = calibrationRevision
        )

        synchronized(calibrationCache) {
            calibrationCache[cacheKey]
        }?.let { cached ->
            if (emitDiagnostics) {
                refreshDiagnosticsPreview(
                    isRawMode = isRawMode,
                    targetValue = value,
                    targetTimestamp = timestamp,
                    force = true
                )
            }
            return cached
        }

        val context = resolveCalibrationContext(isRawMode, currentSensor) ?: return value
        val points = CalibrationMath.resolvePointsForTimestamp(
            allPoints = context.allPoints,
            targetTimestamp = timestamp,
            earliestPoint = context.earliestPoint,
            tuning = tuningForMode(isRawMode)
        )
        if (points.isEmpty()) return value

        val finalValue = computeCalibratedValue(
            originalValue = value,
            targetTimestamp = timestamp,
            isRawMode = isRawMode,
            points = points,
            algorithm = algorithm,
            emitDiagnostics = emitDiagnostics
        )
        synchronized(calibrationCache) {
            calibrationCache[cacheKey] = finalValue
        }
        return finalValue
    }

    @JvmOverloads
    fun getCalibratedSeries(
        samples: List<CalibrationSample>,
        isRawMode: Boolean,
        emitDiagnostics: Boolean = false,
        sensorIdOverride: String? = null
    ): FloatArray {
        return getCalibratedSeriesInternal(
            samples = samples,
            isRawMode = isRawMode,
            emitDiagnostics = emitDiagnostics,
            sensorIdOverride = sensorIdOverride,
            respectManagedOwnership = true,
        )
    }

    /**
     * Evaluates the selected Juggluco calibration model for a managed driver.
     * Values are in the user's current display unit, exactly like stored
     * calibration points. This deliberately bypasses the managed-driver
     * ownership guard; callers must store the returned values themselves.
     */
    @JvmStatic
    fun getIntegratedCalibratedSeries(
        values: FloatArray,
        timestamps: LongArray,
        isRawMode: Boolean,
        sensorIdOverride: String?,
    ): FloatArray {
        if (values.size != timestamps.size) return values.copyOf()
        val samples = List(values.size) { index ->
            CalibrationSample(value = values[index], timestamp = timestamps[index])
        }
        if (samples.isEmpty()) return FloatArray(0)
        val resolvedSensor = resolveSensorId(sensorIdOverride)
        val cacheKey = IntegratedContextCacheKey(resolvedSensor, isRawMode, calibrationRevision, Applic.unit)
        val baselineKey = IntegratedBaselineCacheKey(resolvedSensor, isRawMode, Applic.unit)
        val storedContext = resolveCalibrationContext(isRawMode, resolvedSensor)
            ?: return values.copyOf()
        val context = if (samples.size > 1) {
            val baseline = integratedBaselineSamples(storedContext, samples)
            if (baseline.isNotEmpty()) synchronized(integratedBaselineCache) {
                integratedBaselineCache[baselineKey] = baseline
            }
            applyRecordedStock(rebaseIntegratedContext(storedContext, samples), isRawMode, resolvedSensor).also { rebased ->
                synchronized(integratedContextCache) {
                    integratedContextCache[cacheKey] = rebased
                }
            }
        } else {
            synchronized(integratedContextCache) { integratedContextCache[cacheKey] }
                ?: synchronized(integratedBaselineCache) { integratedBaselineCache[baselineKey] }
                    ?.let { baseline ->
                        applyRecordedStock(
                            rebaseIntegratedContext(storedContext, baseline),
                            isRawMode,
                            resolvedSensor,
                        )
                    }
                ?: applyRecordedStock(storedContext, isRawMode, resolvedSensor)
        }
        return evaluateCalibratedSeries(samples, isRawMode, emitDiagnostics = false, context = context)
    }

    /** Restores stock-model values at calibration timestamps for a managed driver. */
    @JvmStatic
    fun seedIntegratedCalibrationBaseline(
        values: FloatArray,
        timestamps: LongArray,
        isRawMode: Boolean,
        sensorIdOverride: String?,
    ) {
        if (values.size != timestamps.size || values.isEmpty()) return
        val resolvedSensor = resolveSensorId(sensorIdOverride)
        if (resolvedSensor.isBlank()) return
        val samples = values.indices.mapNotNull { index ->
            val value = values[index]
            val timestamp = timestamps[index]
            if (value.isFinite() && value > 0f && timestamp > 0L) {
                CalibrationSample(value, timestamp)
            } else {
                null
            }
        }
        if (samples.isEmpty()) return
        val baselineKey = IntegratedBaselineCacheKey(resolvedSensor, isRawMode, Applic.unit)
        synchronized(integratedBaselineCache) {
            integratedBaselineCache[baselineKey] = samples
        }
        synchronized(integratedContextCache) {
            integratedContextCache.keys.removeAll { key ->
                key.sensorId == resolvedSensor && key.isRawMode == isRawMode && key.unit == Applic.unit
            }
        }
    }

    private fun getCalibratedSeriesInternal(
        samples: List<CalibrationSample>,
        isRawMode: Boolean,
        emitDiagnostics: Boolean,
        sensorIdOverride: String?,
        respectManagedOwnership: Boolean,
    ): FloatArray {
        if (samples.isEmpty()) return FloatArray(0)
        val resolvedSensor = resolveSensorId(sensorIdOverride)
        if (respectManagedOwnership &&
            tk.glucodata.drivers.ManagedSensorRuntime.integratesUserCalibration(resolvedSensor, isRawMode)
        ) {
            return FloatArray(samples.size) { index -> samples[index].value }
        }

        val context = resolveCalibrationContext(isRawMode, resolvedSensor)
        if (context == null) {
            return FloatArray(samples.size) { index -> samples[index].value }
        }
        return evaluateCalibratedSeries(samples, isRawMode, emitDiagnostics, context)
    }

    private fun evaluateCalibratedSeries(
        samples: List<CalibrationSample>,
        isRawMode: Boolean,
        emitDiagnostics: Boolean,
        context: CalibrationContext,
    ): FloatArray {
        val results = FloatArray(samples.size)
        if (!_lockPastHistory.value) {
            val points = context.allPoints.filter { it.isEnabled }
            samples.forEachIndexed { index, sample ->
                val modelValue = if (points.isEmpty()) {
                    sample.value
                } else {
                    computeCalibratedValue(
                        originalValue = sample.value,
                        targetTimestamp = sample.timestamp,
                        isRawMode = isRawMode,
                        points = points,
                        algorithm = context.algorithm,
                        emitDiagnostics = emitDiagnostics && index == samples.lastIndex
                    )
                }
                results[index] = modelValue
            }
            return results
        }

        val indexedSamples = samples.withIndex().sortedBy { it.value.timestamp }

        indexedSamples.forEachIndexed { sortedIndex, indexedSample ->
            val sample = indexedSample.value
            val points = CalibrationMath.resolvePointsForTimestamp(
                allPoints = context.allPoints,
                targetTimestamp = sample.timestamp,
                earliestPoint = context.earliestPoint,
                tuning = tuningForMode(isRawMode)
            )
            val modelValue = if (points.isEmpty()) {
                sample.value
            } else {
                computeCalibratedValue(
                    originalValue = sample.value,
                    targetTimestamp = sample.timestamp,
                    isRawMode = isRawMode,
                    points = points,
                    algorithm = context.algorithm,
                    emitDiagnostics = emitDiagnostics && sortedIndex == indexedSamples.lastIndex
                )
            }
            results[indexedSample.index] = modelValue
        }

        return results
    }

    /**
     * Overrides an anchor's x with the stock value recorded when it was taken.
     *
     * This wins over [rebaseIntegratedContext]'s history match, which can only
     * find a stock value on the device that produced the reading — a calibration
     * taken while the watch held the sensor has no match on the phone, and one
     * taken on the phone loses its match once the source history has rolled past
     * it. A recorded value is the same number the match would have found, and it
     * does not expire.
     */
    private fun applyRecordedStock(
        context: CalibrationContext,
        isRawMode: Boolean,
        sensorId: String,
    ): CalibrationContext {
        val normalizedSensorId = normalizeSensorId(sensorId)
        val recorded = _calibrations.value
            .asSequence()
            .filter { matchesMode(it, isRawMode) && sensorMatches(it.sensorId, normalizedSensorId) }
            .filter { it.sensorValueStock.isFinite() && it.sensorValueStock > 0f }
            .associate { it.timestamp to it.sensorValueStock.toDouble() }
        if (recorded.isEmpty()) return context
        val points = context.allPoints.map { point ->
            recorded[point.timestamp]?.let { point.copy(x = it) } ?: point
        }
        return context.copy(
            allPoints = points,
            earliestPoint = points.filter { it.isEnabled }.minByOrNull { it.timestamp },
        )
    }

    private fun rebaseIntegratedContext(
        context: CalibrationContext,
        stockSamples: List<CalibrationSample>,
    ): CalibrationContext {
        if (stockSamples.isEmpty()) return context
        val ordered = stockSamples
            .asSequence()
            .filter { it.timestamp > 0L && it.value.isFinite() && it.value > 0f }
            .sortedBy { it.timestamp }
            .toList()
        if (ordered.isEmpty()) return context

        val rebasedPoints = context.allPoints.map { point ->
            val nearest = ordered.minByOrNull { sample -> kotlin.math.abs(sample.timestamp - point.timestamp) }
            if (nearest != null && kotlin.math.abs(nearest.timestamp - point.timestamp) <= INTEGRATED_ANCHOR_MATCH_MS) {
                point.copy(x = nearest.value.toDouble())
            } else {
                point
            }
        }
        return context.copy(
            allPoints = rebasedPoints,
            earliestPoint = rebasedPoints.filter { it.isEnabled }.minByOrNull { it.timestamp },
        )
    }

    private fun integratedBaselineSamples(
        context: CalibrationContext,
        stockSamples: List<CalibrationSample>,
    ): List<CalibrationSample> {
        val ordered = stockSamples
            .asSequence()
            .filter { it.timestamp > 0L && it.value.isFinite() && it.value > 0f }
            .sortedBy { it.timestamp }
            .toList()
        if (ordered.isEmpty()) return emptyList()
        return context.allPoints
            .mapNotNull { point ->
                ordered.minByOrNull { sample -> kotlin.math.abs(sample.timestamp - point.timestamp) }
                    ?.takeIf { nearest ->
                        kotlin.math.abs(nearest.timestamp - point.timestamp) <= INTEGRATED_ANCHOR_MATCH_MS
                    }
            }
            .distinctBy { it.timestamp }
    }

    private fun resolveCalibrationContext(
        isRawMode: Boolean,
        sensorIdOverride: String?
    ): CalibrationContext? {
        val currentSensor = resolveSensorId(sensorIdOverride)
        if (!isEnabledForMode(isRawMode, currentSensor)) return null
        val allPoints = getHistoryAwarePointsForSensor(isRawMode = isRawMode, sensorId = currentSensor)
        val activePoints = allPoints.filter { it.isEnabled }
        if (activePoints.isEmpty()) {
            return null
        }
        return CalibrationContext(
            sensorId = currentSensor,
            algorithm = getAlgorithmForMode(isRawMode),
            allPoints = allPoints,
            earliestPoint = activePoints.minByOrNull { it.timestamp }
        )
    }


    private fun computeCalibratedValue(
        originalValue: Float,
        targetTimestamp: Long,
        isRawMode: Boolean,
        points: List<CalPoint>,
        algorithm: CalibrationAlgorithm,
        emitDiagnostics: Boolean
    ): Float {
        if (!originalValue.isFinite() || originalValue <= 0f) {
            return originalValue
        }
        if (points.isEmpty()) {
            return originalValue
        }

        val computation = CalibrationMath.computeAlgorithm(
            algorithm = algorithm.storageValue,
            targetValue = originalValue.toDouble(),
            targetTimestamp = targetTimestamp,
            points = points,
            tuning = tuningForMode(isRawMode)
        )

        if (emitDiagnostics) {
            emitDiagnostics(
                isRawMode = isRawMode,
                diagnostics = buildDiagnostics(
                    algorithm = algorithm,
                    pointCount = points.size,
                    computation = computation,
                    targetValue = originalValue,
                    targetTimestamp = targetTimestamp
                ),
                force = false
            )
        }

        val calibrated = CalibrationMath.sanitizeCalibratedValue(computation.prediction, originalValue)
        return if (_applyToPast.value) {
            calibrated
        } else {
            CalibrationMath.applyPastPolicy(
                originalValue = originalValue,
                calibratedValue = calibrated,
                targetTimestamp = targetTimestamp,
                points = points
            )
        }
    }

    private fun buildDiagnostics(
        algorithm: CalibrationAlgorithm,
        pointCount: Int,
        computation: AlgorithmComputation,
        targetValue: Float,
        targetTimestamp: Long
    ): CalibrationDiagnostics {
        return CalibrationDiagnostics(
            algorithm = algorithm,
            pointCount = pointCount,
            slope = computation.slope?.toFloat(),
            intercept = computation.intercept?.toFloat(),
            offset = computation.offset?.toFloat(),
            anchorInfluence = computation.anchorInfluence?.toFloat(),
            confidence = computation.confidence?.toFloat(),
            targetValue = targetValue,
            targetTimestamp = targetTimestamp,
            note = computation.note
        )
    }

    fun refreshDiagnosticsPreview(
        isRawMode: Boolean,
        targetValue: Float? = null,
        targetTimestamp: Long? = null,
        force: Boolean = true
    ) {
        val sensorId = resolveSensorId()
        val points = getValidPoints(isRawMode = isRawMode, sensorId = sensorId)
        val algorithm = getAlgorithmForMode(isRawMode)

        if (!isEnabledForMode(isRawMode, sensorId)) {
            emitDiagnostics(
                isRawMode = isRawMode,
                diagnostics = CalibrationDiagnostics(
                    algorithm = algorithm,
                    pointCount = points.size,
                    note = "Calibration disabled"
                ),
                force = force
            )
            return
        }

        if (points.isEmpty()) {
            emitDiagnostics(
                isRawMode = isRawMode,
                diagnostics = CalibrationDiagnostics(
                    algorithm = algorithm,
                    pointCount = 0,
                    note = "Add calibration points to see diagnostics"
                ),
                force = force
            )
            return
        }

        val latestPoint = points.maxByOrNull { it.timestamp } ?: points.last()
        val targetTs = targetTimestamp ?: latestPoint.timestamp
        val targetVal = targetValue?.toDouble() ?: latestPoint.x
        val computation = CalibrationMath.computeAlgorithm(
            algorithm = algorithm.storageValue,
            targetValue = targetVal,
            targetTimestamp = targetTs,
            points = points,
            tuning = tuningForMode(isRawMode)
        )

        emitDiagnostics(
            isRawMode = isRawMode,
            diagnostics = buildDiagnostics(
                algorithm = algorithm,
                pointCount = points.size,
                computation = computation,
                targetValue = targetVal.toFloat(),
                targetTimestamp = targetTs
            ),
            force = force
        )
    }

    private fun emitDiagnostics(isRawMode: Boolean, diagnostics: CalibrationDiagnostics, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (isRawMode) {
            if (!force && now - lastDiagnosticsEmitRaw < 700L) return
            lastDiagnosticsEmitRaw = now
            _diagnosticsForRaw.value = diagnostics.copy(updatedAt = now)
        } else {
            if (!force && now - lastDiagnosticsEmitAuto < 700L) return
            lastDiagnosticsEmitAuto = now
            _diagnosticsForAuto.value = diagnostics.copy(updatedAt = now)
        }
    }

    
    /**
     * Check if there's active calibration for the given mode.
     */
    fun hasActiveCalibration(isRawMode: Boolean): Boolean {
        return hasActiveCalibration(isRawMode, null)
    }

    fun hasActiveCalibration(isRawMode: Boolean, sensorIdOverride: String? = null): Boolean {
        val sensorId = resolveSensorId(sensorIdOverride)
        if (!isEnabledForMode(isRawMode, sensorId)) return false
        if (tk.glucodata.drivers.ManagedSensorRuntime.integratesUserCalibration(sensorId, isRawMode)) return false

        return getValidPointsForSensor(isRawMode, sensorId).isNotEmpty()
    }

    fun hasCalibrationPointsForMode(isRawMode: Boolean, sensorIdOverride: String? = null): Boolean {
        return getValidPointsForSensor(isRawMode, resolveSensorId(sensorIdOverride)).isNotEmpty()
    }

    /** Check if an enabled calibration was added at this timestamp (±30s tolerance) for given mode */
    @JvmOverloads
    fun hasCalibrationAt(timestamp: Long, isRawMode: Boolean, sensorIdOverride: String? = null): Boolean {
        val currentSensor = resolveSensorId(sensorIdOverride)
        if (!isEnabledForMode(isRawMode, currentSensor)) return false
        ensureCalibrationStateLoaded()
        return _calibrations.value.any { cal ->
            cal.isEnabled &&
            matchesMode(cal, isRawMode) &&
            sensorMatches(cal.sensorId, currentSensor) &&
            kotlin.math.abs(cal.timestamp - timestamp) <= 30_000L
        }
    }
    
    /** Get calibration at timestamp for editing (±30s tolerance) */
    @JvmOverloads
    fun getCalibrationAt(timestamp: Long, isRawMode: Boolean, sensorIdOverride: String? = null): CalibrationEntity? {
        ensureCalibrationStateLoaded()
        val currentSensor = resolveSensorId(sensorIdOverride)
        return _calibrations.value.find { cal ->
            matchesMode(cal, isRawMode) &&
            sensorMatches(cal.sensorId, currentSensor) &&
            kotlin.math.abs(cal.timestamp - timestamp) <= 30_000L
        }
    }
    
    /** Get visible calibrations for chart display (only enabled, matching mode and sensor) */
    @JvmOverloads
    fun getVisibleCalibrations(isRawMode: Boolean, sensorIdOverride: String? = null): List<CalibrationEntity> {
        val currentSensor = resolveSensorId(sensorIdOverride)
        if (!isEnabledForMode(isRawMode, currentSensor)) return emptyList()
        ensureCalibrationStateLoaded()
        return _calibrations.value.filter { cal ->
            cal.isEnabled &&
            matchesMode(cal, isRawMode) &&
            sensorMatches(cal.sensorId, currentSensor)
        }
    }

    fun getCachedCalibrations(): List<CalibrationEntity> {
        ensureCalibrationStateLoaded()
        return _calibrations.value
    }
    
    // Kept for backward compatibility if needed, but redundant now
    fun getCalibrationsFlow(): Flow<List<CalibrationEntity>> = _calibrations
}
