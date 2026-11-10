package tk.glucodata.drivers.aidex

import android.content.Context
import java.util.Locale

/** Applied history ownership survives removal of the BLE registration. */
internal object AiDexRetainedCalibration {
    fun integrates(context: Context?, sensorId: String, isRaw: Boolean): Boolean {
        if (isRaw || context == null) return false
        val id = sensorId.trim().uppercase(Locale.ROOT)
        if (id.isEmpty()) return false
        val canonical = if (id.startsWith("X-")) id else "X-$id"
        // This is the APPLIED mode, persisted with the committed history, never
        // the requested mode. Native prefs are retained on non-destructive
        // removal and included in settings/full backups.
        val mode = context.getSharedPreferences("AiDexNativePrefs", Context.MODE_PRIVATE)
            .getInt("algorithmAppliedMode_$canonical", 0)
        return AiDexAlgorithmSelection.fromStorage(mode).calibration
    }
}
