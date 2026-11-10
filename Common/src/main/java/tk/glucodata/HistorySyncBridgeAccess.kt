package tk.glucodata

/**
 * Registration seam for [HistorySyncBridge] (plan P1/Q1).
 *
 * Shared/main code used to find `tk.glucodata.data.HistorySync` by name. The
 * keep rule was a hand-copy of the reflective names; explicit registration
 * leaves ordinary interface calls behind.
 */
object HistorySyncBridgeAccess {
    private const val TAG = "HistorySyncBridgeAccess"

    @Volatile
    private var bridge: HistorySyncBridge? = null

    @JvmStatic
    fun register(bridge: HistorySyncBridge) {
        this.bridge = bridge
    }

    /** Registration-completeness check (plan §6 Q1). */
    @JvmStatic
    fun isRegistered(): Boolean = bridge != null

    @JvmStatic
    @JvmOverloads
    fun syncSensorFromNative(serial: String?, forceFull: Boolean = false) {
        if (serial.isNullOrBlank()) return
        runCatching { bridge?.syncSensorFromNative(serial, forceFull) }
            .onFailure { Log.stack(TAG, "syncSensorFromNative failed", it) }
    }

    @JvmStatic
    fun syncRecentSensorFromNative(serial: String?, anchorTimeMs: Long) {
        if (serial.isNullOrBlank() || anchorTimeMs <= 0L) return
        runCatching { bridge?.syncRecentSensorFromNative(serial, anchorTimeMs) }
            .onFailure { Log.stack(TAG, "syncRecentSensorFromNative failed", it) }
    }

    @JvmStatic
    fun syncSensorWindowFromNative(serial: String?, startTimeMs: Long) {
        if (serial.isNullOrBlank()) return
        runCatching { bridge?.syncSensorWindowFromNative(serial, startTimeMs) }
            .onFailure { Log.stack(TAG, "syncSensorWindowFromNative failed", it) }
    }

    @JvmStatic
    fun forceFullSyncForSensor(serial: String?) {
        if (serial.isNullOrBlank()) return
        val target = bridge
        if (target == null) {
            syncSensorFromNative(serial, forceFull = true)
            return
        }
        runCatching { target.forceFullSyncForSensor(serial) }
            .onFailure {
                Log.stack(TAG, "forceFullSyncForSensor failed; falling back to syncSensorFromNative(true)", it)
                syncSensorFromNative(serial, forceFull = true)
            }
    }

    @JvmStatic
    fun mergeFullSyncForSensor(serial: String?) {
        if (serial.isNullOrBlank()) return
        val target = bridge
        if (target == null) {
            syncSensorFromNative(serial, forceFull = true)
            return
        }
        runCatching { target.mergeFullSyncForSensor(serial) }
            .onFailure {
                Log.stack(TAG, "mergeFullSyncForSensor failed; falling back to syncSensorFromNative(true)", it)
                syncSensorFromNative(serial, forceFull = true)
            }
    }

    @JvmStatic
    fun markSensorReset(serial: String?) {
        if (serial.isNullOrBlank()) return
        runCatching { bridge?.markSensorReset(serial) }
            .onFailure { Log.stack(TAG, "markSensorReset failed", it) }
    }
}
