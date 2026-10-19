package tk.glucodata

import android.content.Context

/**
 * The app's custom-alert engine, handed to [CustomAlertAccess] once at startup.
 *
 * The engine itself lives in the mobile source set, which the shared code cannot reference at
 * compile time. Registering it through this interface keeps every call an ordinary interface
 * invoke, which R8 renames on both sides. Looking it up by name at runtime does not survive
 * minification: see [CustomAlertAccess].
 */
interface CustomAlertController {
    /** [reading] says whether the value was already calibrated; see [LiveReadingLanes]. */
    fun checkAndTrigger(
        context: Context,
        reading: LiveReadingLanes,
        rate: Float,
        timestampMillis: Long,
        sensorId: String?,
        sensorGen: Int
    )

    fun dismissAlert(alertId: String)

    fun snoozeAlert(alertId: String, snoozeMinutes: Int)

    fun ignoreAlert(alertId: String)
}
