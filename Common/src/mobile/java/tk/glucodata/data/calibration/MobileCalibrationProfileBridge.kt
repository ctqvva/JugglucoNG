package tk.glucodata.data.calibration

import tk.glucodata.CalibrationProfileBridge

/**
 * Phone implementation of [CalibrationProfileBridge] (plan P1/Q1). Delegates to
 * [CalibrationManager]; the mirror import policy still decides whether it applies.
 */
object MobileCalibrationProfileBridge : CalibrationProfileBridge {
    override fun exportProfileForSensorAsJson(sensorId: String): String? =
        CalibrationManager.exportProfileForSensorAsJson(sensorId)

    override fun importMirrorProfileFromJson(json: String, overrideSensorId: String?): Boolean {
        // The old reflective bridge reported "true" whenever the call returned at all;
        // the policy gate inside already decides whether anything is applied, and the
        // JNI caller ignores the value. Keep that contract rather than reading counts.
        CalibrationManager.importMirrorProfileFromJsonBlocking(json, overrideSensorId)
        return true
    }
}
