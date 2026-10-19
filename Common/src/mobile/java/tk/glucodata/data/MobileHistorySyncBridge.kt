package tk.glucodata.data

import tk.glucodata.HistorySyncBridge

/**
 * Phone implementation of [HistorySyncBridge] (plan P1/Q1). Delegates to the
 * mobile-only [HistorySync].
 */
object MobileHistorySyncBridge : HistorySyncBridge {
    override fun syncSensorFromNative(serial: String, forceFull: Boolean) =
        HistorySync.syncSensorFromNative(serial, forceFull)

    override fun syncRecentSensorFromNative(serial: String, anchorTimeMs: Long) =
        HistorySync.syncRecentSensorFromNative(serial, anchorTimeMs)

    override fun syncSensorWindowFromNative(serial: String, startTimeMs: Long) =
        HistorySync.syncSensorWindowFromNative(serial, startTimeMs)

    override fun forceFullSyncForSensor(serial: String) = HistorySync.forceFullSyncForSensor(serial)

    override fun mergeFullSyncForSensor(serial: String) = HistorySync.mergeFullSyncForSensor(serial)

    override fun markSensorReset(serial: String) = HistorySync.markSensorReset(serial)
}
