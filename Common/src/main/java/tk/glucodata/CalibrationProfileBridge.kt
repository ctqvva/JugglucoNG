package tk.glucodata

/**
 * The mobile-only calibration profile export/import, as the JNI bridge needs it
 * (plan P1/Q1).
 *
 * Registered from the mobile `Specific.registerBridges`; the watch registers
 * nothing and [CalibrationProfileAccess] then reports failure, which is what the
 * old absent class did.
 *
 * [CalibrationProfileAccess] itself stays a JNI contract (its statics are found
 * by name from `javacurve.cpp`); it delegates here instead of reflecting.
 */
interface CalibrationProfileBridge {
    /** JSON profile for [sensorId], or null when it cannot be produced. */
    fun exportProfileForSensorAsJson(sensorId: String): String?

    /** True when the mirror import was applied (the policy gate may reject it). */
    fun importMirrorProfileFromJson(json: String, overrideSensorId: String?): Boolean
}
