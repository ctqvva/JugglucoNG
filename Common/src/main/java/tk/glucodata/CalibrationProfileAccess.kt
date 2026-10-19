package tk.glucodata

import androidx.annotation.Keep

/**
 * Bridge between the native calibration-profile transfer and the mobile-only
 * [tk.glucodata.data.calibration.CalibrationManager].
 *
 * This object's own static methods are a **JNI contract**: `curve/javacurve.cpp`
 * does `FindClass("tk/glucodata/CalibrationProfileAccess")` and
 * `GetStaticMethodID` for both methods, so they keep their names (`@Keep` + the
 * keep rule). Inside, it no longer resolves CalibrationManager by name: the phone
 * registers its [CalibrationProfileBridge] from [Specific.registerBridges] and the
 * methods delegate (plan P1/Q1).
 *
 * Every method keeps the old reflective failure contract: a failure degrades to
 * null/false instead of reaching the caller.
 */
@Keep
object CalibrationProfileAccess {
    private const val TAG = "CalibrationProfileAccess"

    @Volatile
    private var bridge: CalibrationProfileBridge? = null

    /** Registration-completeness check (plan §6 Q1). */
    @JvmStatic
    fun isRegistered(): Boolean = bridge != null

    @JvmStatic
    fun register(bridge: CalibrationProfileBridge) {
        this.bridge = bridge
    }

    @JvmStatic
    fun exportProfileForSensorAsJson(sensorId: String?): String? {
        if (sensorId.isNullOrBlank()) return null
        return runCatching { bridge?.exportProfileForSensorAsJson(sensorId) }
            .onFailure { Log.stack(TAG, "exportProfileForSensorAsJson failed for sensor=$sensorId", it) }
            .getOrNull()
    }

    @JvmStatic
    fun importMirrorProfileFromJson(json: String?, overrideSensorId: String? = null): Boolean {
        if (json.isNullOrBlank()) return false
        return runCatching { bridge?.importMirrorProfileFromJson(json, overrideSensorId) }
            .onFailure { Log.stack(TAG, "importMirrorProfileFromJson failed for sensor=$overrideSensorId", it) }
            .getOrNull() ?: false
    }
}
