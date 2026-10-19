package tk.glucodata

/**
 * The mobile-only native→Room history synchroniser, as the shared code needs it
 * (plan P1/Q1).
 *
 * Registered from the mobile `Specific.registerBridges`; the watch registers
 * nothing and [HistorySyncBridgeAccess] then no-ops (P2).
 */
interface HistorySyncBridge {
    fun syncSensorFromNative(serial: String, forceFull: Boolean)

    fun syncRecentSensorFromNative(serial: String, anchorTimeMs: Long)

    fun syncSensorWindowFromNative(serial: String, startTimeMs: Long)

    fun forceFullSyncForSensor(serial: String)

    fun mergeFullSyncForSensor(serial: String)

    fun markSensorReset(serial: String)
}
