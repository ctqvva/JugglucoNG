package tk.glucodata

import tk.glucodata.data.calibration.CalibrationTuning

/**
 * The phone's calibration engine as `src/main` needs it (plan P1/Q1).
 *
 * The mobile `Specific.registerBridges` registers
 * [tk.glucodata.data.calibration.MobileCalibrationProvider]; the watch registers
 * [SyncedWearCalibrationProvider]. Before this, `CalibrationAccess` reached the
 * mobile-only `CalibrationManager` reflectively and the watch silently got the
 * defaults below, because the class is not on its classpath. The defaults here
 * are those same absent-class results, so a flavour without an engine behaves
 * exactly as it did.
 *
 * The mutation methods ([setEnabledForMode], [clearAllBlocking],
 * [deleteCalibrationAtBlocking], [updateCalibrationUserValueBlocking],
 * [addCalibrationFromWearAtBlocking]) return whether the engine actually handled
 * the call; their default `false` is the old "method not found" answer.
 */
interface CalibrationProvider {
    fun hasActiveCalibration(isRawMode: Boolean, sensorId: String?): Boolean

    fun getCalibratedValue(
        value: Float,
        timestamp: Long,
        isRawMode: Boolean,
        emitDiagnostics: Boolean,
        sensorId: String?,
    ): Float

    fun shouldHideInitialWhenCalibrated(): Boolean = false

    fun getActiveCalibrationAnchors(sensorId: String?, isRawMode: Boolean): DoubleArray =
        DoubleArray(0)

    fun shouldOverwriteSensorValues(): Boolean = false

    /**
     * Evaluate the calibration model over a series the way a driver that folds
     * the correction into the values it stores needs it done. Defaults to no
     * correction; a provider that cannot reproduce the phone's fit must leave
     * the values alone rather than guess at one.
     */
    fun getIntegratedCalibratedSeries(
        values: FloatArray,
        timestamps: LongArray,
        isRawMode: Boolean,
        sensorId: String?,
    ): FloatArray = values.copyOf()

    /**
     * Changes whenever [getIntegratedCalibratedSeries] would produce different
     * numbers. A managed driver stores it alongside its rebuilt algorithm and
     * replays its history when it moves, so old readings pick up a calibration
     * added after they were taken.
     */
    fun getIntegratedCalibrationFingerprint(sensorId: String?, isRawMode: Boolean): Long = 0L

    fun getRevision(): Long = 0L

    /** The anchors a driver-integrated evaluation fits against. Empty without an engine. */
    fun getIntegratedCalibrationAnchors(sensorId: String?, isRawMode: Boolean): DoubleArray =
        DoubleArray(0)

    /**
     * False only when the phone's calibrations are not in memory, so callers do
     * not mistake a failed load for "this sensor has no calibration". True where
     * there is no calibration engine at all (the watch).
     */
    fun isCalibrationStateLoaded(): Boolean = true

    /** The settings behind the phone's fit, so the watch can reproduce the same numbers. */
    fun tuningForMode(isRawMode: Boolean): CalibrationTuning = CalibrationTuning.DEFAULT

    /** Restores stock-model values at calibration timestamps for a managed driver. */
    fun seedIntegratedCalibrationBaseline(
        values: FloatArray,
        timestamps: LongArray,
        isRawMode: Boolean,
        sensorId: String?,
    ) {
    }

    fun notifyExternalCalibrationPipelineChanged() {
    }

    /** Enable or disable one calibration lane; false when no engine handled it. */
    fun setEnabledForMode(isRawMode: Boolean, enabled: Boolean, sensorId: String?): Boolean = false

    /** Delete every stored calibration; false when no engine handled it. */
    fun clearAllBlocking(): Boolean = false

    /**
     * Take effect immediately and in memory only: points of [sensorId] taken before [cutoffMs] no
     * longer match the live sensor. Cheap enough for a BLE callback, and called before the
     * persisted move below, which may be queued or retried much later.
     */
    fun declareSessionBoundary(sensorId: String, archiveId: String, cutoffMs: Long) {}

    /**
     * File the calibrations of [sensorId] taken before [cutoffMs], the insertion of the replacement,
     * under [archiveId], so a new disposable sensor on the same transmitter starts with none while
     * the old points stay in the database. Points from the cutoff on belong to the replacement.
     * False when no engine handled it.
     */
    fun archiveSensorCalibrationsBlocking(sensorId: String, archiveId: String, cutoffMs: Long): Boolean = false

    /** Delete the calibration recorded at [timestamp]; false when none matched or none handled it. */
    fun deleteCalibrationAtBlocking(timestamp: Long): Boolean = false

    /** Change the fingerstick value of the calibration recorded at [timestamp]. */
    fun updateCalibrationUserValueBlocking(timestamp: Long, userValueMgdl: Float): Boolean = false

    /** Record a fingerstick calibration against the reading at [timestampMs]. */
    fun addCalibrationFromWearAtBlocking(
        timestampMs: Long,
        userValueMgdl: Float,
        sensorStockMgdl: Float,
    ): Boolean = false
}
