package tk.glucodata

/**
 * The mobile-only glucose uncertainty store, as the shared code needs it
 * (plan P1/Q1).
 *
 * Registered from the mobile `Specific.registerBridges`; the watch registers
 * nothing and [GlucoseUncertaintyAccess] then stores nothing, so a driver can
 * still call it unconditionally.
 */
interface GlucoseUncertaintyBridge {
    fun storeBatch(
        sensorSerial: String?,
        timestamps: LongArray,
        lowerMgdl: FloatArray,
        upperMgdl: FloatArray,
        intervalMass: Float,
        confidences: FloatArray,
        artifactProbabilities: FloatArray,
    )

    fun storeReading(
        sensorSerial: String?,
        timestamp: Long,
        lowerMgdl: Float,
        upperMgdl: Float,
        intervalMass: Float,
        confidence: Float,
        artifactProbability: Float,
    )

    fun clearForSensor(sensorSerial: String?)

    fun deleteForSensorAfter(sensorSerial: String?, timestamp: Long)
}
