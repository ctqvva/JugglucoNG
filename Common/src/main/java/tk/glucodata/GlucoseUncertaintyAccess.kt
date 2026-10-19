package tk.glucodata

/**
 * Bridge from `src/main` drivers to the mobile-only uncertainty store (plan
 * P1/Q1).
 *
 * The phone registers its [GlucoseUncertaintyBridge] from
 * [Specific.registerBridges]; the watch registers nothing, and every method
 * degrades to a no-op there, so a driver can always call it unconditionally.
 *
 * This used to resolve `tk.glucodata.data.GlucoseUncertaintyStore` by name.
 * R8 renames those members in release builds; the keep rule was the only thing
 * holding the names in place. Every method keeps the old reflective failure
 * contract: a failure inside the store is logged and swallowed.
 */
object GlucoseUncertaintyAccess {
    private const val TAG = "GlucoseUncertaintyAccess"

    @Volatile
    private var bridge: GlucoseUncertaintyBridge? = null

    @JvmStatic
    fun register(bridge: GlucoseUncertaintyBridge) {
        this.bridge = bridge
    }

    /** Registration-completeness check (plan §6 Q1). */
    @JvmStatic
    fun isRegistered(): Boolean = bridge != null

    @JvmStatic
    fun storeBatch(
        sensorSerial: String?,
        timestamps: LongArray,
        lowerMgdl: FloatArray,
        upperMgdl: FloatArray,
        intervalMass: Float,
        confidences: FloatArray,
        artifactProbabilities: FloatArray,
    ) {
        if (sensorSerial.isNullOrBlank() || timestamps.isEmpty()) return
        runCatching {
            bridge?.storeBatch(
                sensorSerial,
                timestamps,
                lowerMgdl,
                upperMgdl,
                intervalMass,
                confidences,
                artifactProbabilities,
            )
        }.onFailure { Log.stack(TAG, "storeBatch failed", it) }
    }

    @JvmStatic
    fun storeReading(
        sensorSerial: String?,
        timestamp: Long,
        lowerMgdl: Float,
        upperMgdl: Float,
        intervalMass: Float,
        confidence: Float,
        artifactProbability: Float,
    ) {
        if (sensorSerial.isNullOrBlank() || timestamp <= 0L) return
        runCatching {
            bridge?.storeReading(
                sensorSerial,
                timestamp,
                lowerMgdl,
                upperMgdl,
                intervalMass,
                confidence,
                artifactProbability,
            )
        }.onFailure { Log.stack(TAG, "storeReading failed", it) }
    }

    /**
     * Drops every stored interval for a sensor.
     *
     * Used when the algorithm changes away from one that estimates uncertainty:
     * the bands describe values that are about to be replaced, and leaving them
     * draws a V2 ribbon around a stock line until a rebuild happens to overwrite
     * them.
     */
    @JvmStatic
    fun clearForSensor(sensorSerial: String?) {
        if (sensorSerial.isNullOrBlank()) return
        runCatching { bridge?.clearForSensor(sensorSerial) }
            .onFailure { Log.stack(TAG, "clearForSensor failed", it) }
    }

    @JvmStatic
    fun deleteForSensorAfter(sensorSerial: String?, timestamp: Long) {
        if (sensorSerial.isNullOrBlank()) return
        runCatching { bridge?.deleteForSensorAfter(sensorSerial, timestamp) }
            .onFailure { Log.stack(TAG, "deleteForSensorAfter failed", it) }
    }
}
