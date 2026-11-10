package tk.glucodata.data.calibration

import tk.glucodata.CalibrationProvider

/**
 * Phone implementation of [CalibrationProvider] (plan P1/Q1). Registered from the
 * mobile `Specific.registerBridges`; delegates to [CalibrationManager]. The watch
 * registers [tk.glucodata.SyncedWearCalibrationProvider] instead.
 *
 * Before this, `CalibrationAccess` in `src/main` resolved `CalibrationManager`
 * with `Class.forName` and invoked every method reflectively, which needed the
 * `-keepclassmembers` block in `proguard-rules.my` or it silently returned the
 * defaults in release builds.
 */
object MobileCalibrationProvider : CalibrationProvider {
    override fun hasActiveCalibration(isRawMode: Boolean, sensorId: String?): Boolean =
        CalibrationManager.hasActiveCalibration(isRawMode, sensorId)

    override fun getCalibratedValue(
        value: Float,
        timestamp: Long,
        isRawMode: Boolean,
        emitDiagnostics: Boolean,
        sensorId: String?,
    ): Float = CalibrationManager.getCalibratedValue(value, timestamp, isRawMode, emitDiagnostics, sensorId)

    override fun shouldHideInitialWhenCalibrated(): Boolean =
        CalibrationManager.shouldHideInitialWhenCalibrated()

    override fun getActiveCalibrationAnchors(sensorId: String?, isRawMode: Boolean): DoubleArray =
        CalibrationManager.getActiveCalibrationAnchors(sensorId, isRawMode)

    override fun shouldOverwriteSensorValues(): Boolean =
        CalibrationManager.shouldOverwriteSensorValues()

    override fun getIntegratedCalibratedSeries(
        values: FloatArray,
        timestamps: LongArray,
        isRawMode: Boolean,
        sensorId: String?,
    ): FloatArray = CalibrationManager.getIntegratedCalibratedSeries(values, timestamps, isRawMode, sensorId)

    override fun getIntegratedCalibrationFingerprint(sensorId: String?, isRawMode: Boolean): Long =
        CalibrationManager.getIntegratedCalibrationFingerprint(sensorId, isRawMode)

    override fun getRevision(): Long = CalibrationManager.getRevision()

    override fun getIntegratedCalibrationAnchors(sensorId: String?, isRawMode: Boolean): DoubleArray =
        CalibrationManager.getIntegratedCalibrationAnchors(sensorId, isRawMode)

    override fun isCalibrationStateLoaded(): Boolean = CalibrationManager.isCalibrationStateLoaded()

    override fun tuningForMode(isRawMode: Boolean): CalibrationTuning =
        CalibrationManager.tuningForMode(isRawMode)

    override fun seedIntegratedCalibrationBaseline(
        values: FloatArray,
        timestamps: LongArray,
        isRawMode: Boolean,
        sensorId: String?,
    ) = CalibrationManager.seedIntegratedCalibrationBaseline(values, timestamps, isRawMode, sensorId)

    override fun notifyExternalCalibrationPipelineChanged() =
        CalibrationManager.notifyExternalCalibrationPipelineChanged()

    override fun setEnabledForMode(isRawMode: Boolean, enabled: Boolean, sensorId: String?): Boolean {
        CalibrationManager.setEnabledForMode(isRawMode, enabled, sensorId)
        return true
    }

    override fun clearAllBlocking(): Boolean {
        CalibrationManager.clearAllBlocking()
        return true
    }

    override fun declareSessionBoundary(sensorId: String, archiveId: String, cutoffMs: Long) =
        CalibrationManager.declareSessionBoundary(sensorId, archiveId, cutoffMs)

    override fun archiveSensorCalibrationsBlocking(sensorId: String, archiveId: String, cutoffMs: Long): Boolean =
        // Handled even when the sensor had nothing to move; only an engine that is not ready yet fails.
        CalibrationManager.archiveSensorCalibrationsBlocking(sensorId, archiveId, cutoffMs) >= 0

    override fun deleteCalibrationAtBlocking(timestamp: Long): Boolean =
        CalibrationManager.deleteCalibrationAtBlocking(timestamp)

    override fun updateCalibrationUserValueBlocking(timestamp: Long, userValueMgdl: Float): Boolean =
        CalibrationManager.updateCalibrationUserValueBlocking(timestamp, userValueMgdl)

    override fun addCalibrationFromWearAtBlocking(
        timestampMs: Long,
        userValueMgdl: Float,
        sensorStockMgdl: Float,
    ): Boolean = CalibrationManager.addCalibrationFromWearAtBlocking(timestampMs, userValueMgdl, sensorStockMgdl)
}
