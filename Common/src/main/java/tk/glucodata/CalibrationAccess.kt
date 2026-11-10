package tk.glucodata

import tk.glucodata.data.calibration.CalibrationTuning

/**
 * Registration seam for the phone's calibration engine (plan P1/Q1).
 *
 * `src/main` used to resolve `tk.glucodata.data.calibration.CalibrationManager`
 * by name and reach every method reflectively, which is why
 * `proguard-rules.my` carried a hand-kept list of its members. The mobile
 * `Specific.registerBridges` registers
 * [tk.glucodata.data.calibration.MobileCalibrationProvider]; the watch registers
 * [SyncedWearCalibrationProvider]. Every method keeps the old reflective failure
 * contract: a failure degrades to the default instead of reaching the shared
 * caller.
 */
object CalibrationAccess {
    private const val TAG = "CalibrationAccess"

    @Volatile
    private var provider: CalibrationProvider? = null

    @JvmStatic
    fun register(provider: CalibrationProvider) {
        this.provider = provider
    }

    /** Tests only: drop a provider installed with [register]. */
    internal fun unregisterForTests() {
        provider = null
    }

    /** Registration-completeness check (plan §6 Q1). */
    @JvmStatic
    fun isRegistered(): Boolean = provider != null

    /** Enable or disable calibration for both lanes of the current sensor. */
    @JvmStatic
    fun setEnabled(enabled: Boolean): Boolean {
        val engine = provider ?: return false
        return runCatching {
            if (!engine.setEnabledForMode(false, enabled, null)) return@runCatching false
            engine.setEnabledForMode(true, enabled, null)
            true
        }.onFailure { Log.stack(TAG, "setEnabled failed", it) }.getOrDefault(false)
    }

    /** Delete every stored calibration. */
    @JvmStatic
    fun clearAll(): Boolean =
        runCatching { provider?.clearAllBlocking() }
            .onFailure { Log.stack(TAG, "clearAll failed", it) }
            .getOrNull() ?: false

    /**
     * Move one sensor's calibrations to [archiveId]. For a driver whose serial outlives its disposable
     * sensor: the old electrode's calibration must not apply to the replacement.
     */
    @JvmStatic
    fun declareSessionBoundary(sensorId: String, archiveId: String, cutoffMs: Long) {
        runCatching { provider?.declareSessionBoundary(sensorId, archiveId, cutoffMs) }
            .onFailure { Log.stack(TAG, "declareSessionBoundary failed", it) }
    }

    @JvmStatic
    fun archiveSensorCalibrations(sensorId: String, archiveId: String, cutoffMs: Long): Boolean =
        runCatching { provider?.archiveSensorCalibrationsBlocking(sensorId, archiveId, cutoffMs) }
            .onFailure { Log.stack(TAG, "archiveSensorCalibrations failed", it) }
            .getOrNull() ?: false

    @JvmStatic
    fun deleteCalibrationAt(timestamp: Long): Boolean =
        runCatching { provider?.deleteCalibrationAtBlocking(timestamp) }
            .onFailure { Log.stack(TAG, "deleteCalibrationAt failed", it) }
            .getOrNull() ?: false

    /** Change the fingerstick value of the calibration recorded at [timestamp]. */
    @JvmStatic
    fun updateCalibrationUserValue(timestamp: Long, userValueMgdl: Float): Boolean =
        runCatching { provider?.updateCalibrationUserValueBlocking(timestamp, userValueMgdl) }
            .onFailure { Log.stack(TAG, "updateCalibrationUserValue failed", it) }
            .getOrNull() ?: false

    /**
     * Record a fingerstick calibration. [timestampMs] picks the reading it is
     * measured against; 0 means the current one. [sensorStockMgdl] is the
     * sensor's own uncorrected value there, which only the device that produced
     * the reading knows; NaN means unknown and leaves the phone to recover it
     * from its own history as before.
     */
    @JvmStatic
    @JvmOverloads
    fun addCalibration(
        userValueMgdl: Float,
        timestampMs: Long = 0L,
        sensorStockMgdl: Float = Float.NaN,
    ): Boolean =
        runCatching { provider?.addCalibrationFromWearAtBlocking(timestampMs, userValueMgdl, sensorStockMgdl) }
            .onFailure { Log.stack(TAG, "addCalibration failed", it) }
            .getOrNull() ?: false

    @JvmStatic
    fun hasActiveCalibration(isRawMode: Boolean, sensorId: String? = null): Boolean =
        runCatching { provider?.hasActiveCalibration(isRawMode, sensorId) }
            .onFailure { Log.stack(TAG, "hasActiveCalibration failed", it) }
            .getOrNull() ?: false

    @JvmStatic
    @JvmOverloads
    fun getCalibratedValue(
        value: Float,
        timestamp: Long,
        isRawMode: Boolean,
        emitDiagnostics: Boolean = false,
        sensorIdOverride: String? = null
    ): Float =
        runCatching { provider?.getCalibratedValue(value, timestamp, isRawMode, emitDiagnostics, sensorIdOverride) }
            .onFailure { Log.stack(TAG, "getCalibratedValue failed", it) }
            .getOrNull() ?: value

    @JvmStatic
    fun shouldHideInitialWhenCalibrated(): Boolean =
        runCatching { provider?.shouldHideInitialWhenCalibrated() }
            .onFailure { Log.stack(TAG, "shouldHideInitialWhenCalibrated failed", it) }
            .getOrNull() ?: false

    @JvmStatic
    fun notifyExternalCalibrationPipelineChanged() {
        runCatching { provider?.notifyExternalCalibrationPipelineChanged() }
            .onFailure { Log.stack(TAG, "notifyExternalCalibrationPipelineChanged failed", it) }
    }

    @JvmStatic
    fun getActiveCalibrationAnchors(sensorId: String?, isRawMode: Boolean = false): DoubleArray =
        runCatching { provider?.getActiveCalibrationAnchors(sensorId, isRawMode) }
            .onFailure { Log.stack(TAG, "getActiveCalibrationAnchors failed", it) }
            .getOrNull() ?: DoubleArray(0)

    /**
     * The anchors a driver-integrated evaluation fits against, rebased onto
     * stock values. Empty where there is no calibration engine, which is also
     * where nothing integrates locally.
     */
    @JvmStatic
    fun getIntegratedCalibrationAnchors(sensorId: String?, isRawMode: Boolean): DoubleArray =
        runCatching { provider?.getIntegratedCalibrationAnchors(sensorId, isRawMode) }
            .onFailure { Log.stack(TAG, "getIntegratedCalibrationAnchors failed", it) }
            .getOrNull() ?: DoubleArray(0)

    /**
     * False when the phone's calibrations are not in memory, so callers do not
     * mistake a failed load for "this sensor has no calibration". True where
     * there is no calibration engine at all (the watch), which has nothing to
     * load.
     */
    @JvmStatic
    fun isCalibrationStateLoaded(): Boolean {
        val engine = provider ?: return true
        return runCatching { engine.isCalibrationStateLoaded() }
            .onFailure { Log.stack(TAG, "isCalibrationStateLoaded failed", it) }
            .getOrDefault(false)
    }

    /**
     * The settings behind the phone's fit, so the watch can reproduce the same
     * numbers with the shared computation.
     */
    @JvmStatic
    fun tuningForMode(isRawMode: Boolean): CalibrationTuning =
        runCatching { provider?.tuningForMode(isRawMode) }
            .onFailure { Log.stack(TAG, "tuningForMode failed", it) }
            .getOrNull() ?: CalibrationTuning.DEFAULT

    @JvmStatic
    fun shouldOverwriteSensorValues(): Boolean =
        runCatching { provider?.shouldOverwriteSensorValues() }
            .onFailure { Log.stack(TAG, "shouldOverwriteSensorValues failed", it) }
            .getOrNull() ?: false

    @JvmStatic
    fun getRevision(): Long =
        runCatching { provider?.getRevision() }
            .onFailure { Log.stack(TAG, "getRevision failed", it) }
            .getOrNull() ?: 0L

    @JvmStatic
    fun getIntegratedCalibratedSeries(
        values: FloatArray,
        timestamps: LongArray,
        isRawMode: Boolean,
        sensorIdOverride: String?,
    ): FloatArray {
        if (values.size != timestamps.size) return values.copyOf()
        return runCatching {
            provider?.getIntegratedCalibratedSeries(values, timestamps, isRawMode, sensorIdOverride)
        }.onFailure { Log.stack(TAG, "getIntegratedCalibratedSeries failed", it) }
            .getOrNull()?.takeIf { it.size == values.size } ?: values.copyOf()
    }

    @JvmStatic
    fun getIntegratedCalibrationFingerprint(sensorIdOverride: String?, isRawMode: Boolean): Long =
        runCatching { provider?.getIntegratedCalibrationFingerprint(sensorIdOverride, isRawMode) }
            .onFailure { Log.stack(TAG, "getIntegratedCalibrationFingerprint failed", it) }
            .getOrNull() ?: 0L

    @JvmStatic
    fun seedIntegratedCalibrationBaseline(
        values: FloatArray,
        timestamps: LongArray,
        isRawMode: Boolean,
        sensorIdOverride: String?,
    ) {
        if (values.size != timestamps.size || values.isEmpty()) return
        runCatching {
            provider?.seedIntegratedCalibrationBaseline(values, timestamps, isRawMode, sensorIdOverride)
        }.onFailure { Log.stack(TAG, "seedIntegratedCalibrationBaseline failed", it) }
    }
}
