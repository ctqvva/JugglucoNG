package tk.glucodata

import android.util.Log
import androidx.annotation.Keep

/**
 * Reflective bridge from `src/main` drivers to the mobile-only uncertainty
 * store, mirroring [HistorySyncAccess].
 *
 * Every method degrades to a no-op when the mobile class is absent (wear
 * builds, unit tests), so a driver can always call it unconditionally.
 *
 * The target class and method signatures below must stay in step with
 * `tk.glucodata.data.GlucoseUncertaintyStore` **and** with the keep rules in
 * `proguard-rules.my` — a reflective bridge that loses its keep rule fails
 * silently, and only in minified builds.
 */
@Keep
object GlucoseUncertaintyAccess {
    private const val TAG = "GlucoseUncertaintyAccess"
    private const val STORE_CLASS_NAME = "tk.glucodata.data.GlucoseUncertaintyStore"

    private val storeHolder by lazy { runCatching { Class.forName(STORE_CLASS_NAME) }.getOrNull() }
    private val storeInstance by lazy {
        runCatching { storeHolder?.getField("INSTANCE")?.get(null) }.getOrNull()
    }

    private val storeBatchMethod by lazy {
        runCatching {
            storeHolder?.getMethod(
                "storeBatch",
                String::class.java,
                LongArray::class.java,
                FloatArray::class.java,
                FloatArray::class.java,
                Float::class.javaPrimitiveType,
                FloatArray::class.java,
                FloatArray::class.java,
            )
        }.getOrNull()
    }
    private val storeReadingMethod by lazy {
        runCatching {
            storeHolder?.getMethod(
                "storeReading",
                String::class.java,
                Long::class.javaPrimitiveType,
                Float::class.javaPrimitiveType,
                Float::class.javaPrimitiveType,
                Float::class.javaPrimitiveType,
                Float::class.javaPrimitiveType,
                Float::class.javaPrimitiveType,
            )
        }.getOrNull()
    }
    private val clearForSensorMethod by lazy {
        runCatching {
            storeHolder?.getMethod("clearForSensor", String::class.java)
        }.getOrNull()
    }
    private val deleteAfterMethod by lazy {
        runCatching {
            storeHolder?.getMethod(
                "deleteForSensorAfter",
                String::class.java,
                Long::class.javaPrimitiveType,
            )
        }.getOrNull()
    }

    val isAvailable: Boolean get() = storeHolder != null

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
        val method = storeBatchMethod ?: run {
            Log.w(TAG, "storeBatch unavailable for serial=$sensorSerial")
            return
        }
        runCatching {
            method.invoke(
                storeInstance,
                sensorSerial,
                timestamps,
                lowerMgdl,
                upperMgdl,
                intervalMass,
                confidences,
                artifactProbabilities,
            )
        }.onFailure {
            Log.w(TAG, "storeBatch failed for serial=$sensorSerial size=${timestamps.size}", it)
        }
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
        val method = storeReadingMethod ?: run {
            Log.w(TAG, "storeReading unavailable for serial=$sensorSerial")
            return
        }
        runCatching {
            method.invoke(
                storeInstance,
                sensorSerial,
                timestamp,
                lowerMgdl,
                upperMgdl,
                intervalMass,
                confidence,
                artifactProbability,
            )
        }.onFailure {
            Log.w(TAG, "storeReading failed for serial=$sensorSerial timestamp=$timestamp", it)
        }
    }

    /**
     * Drops every stored interval for a sensor.
     *
     * Used when the algorithm changes away from one that estimates
     * uncertainty: the bands describe values that are about to be replaced,
     * and leaving them draws a V2 ribbon around a stock line until a rebuild
     * happens to overwrite them.
     */
    @JvmStatic
    fun clearForSensor(sensorSerial: String?) {
        if (sensorSerial.isNullOrBlank()) return
        val method = clearForSensorMethod ?: return
        runCatching { method.invoke(storeInstance, sensorSerial) }
            .onFailure { Log.w(TAG, "clearForSensor failed for serial=$sensorSerial", it) }
    }

    @JvmStatic
    fun deleteForSensorAfter(sensorSerial: String?, timestamp: Long) {
        if (sensorSerial.isNullOrBlank()) return
        val method = deleteAfterMethod ?: return
        runCatching { method.invoke(storeInstance, sensorSerial, timestamp) }
            .onFailure { Log.w(TAG, "deleteForSensorAfter failed for serial=$sensorSerial", it) }
    }
}
