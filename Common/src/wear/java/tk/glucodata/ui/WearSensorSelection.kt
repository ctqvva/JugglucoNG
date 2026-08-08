package tk.glucodata.ui

import android.content.Context
import tk.glucodata.Applic
import tk.glucodata.Log
import tk.glucodata.Natives
import tk.glucodata.NotificationHistorySource
import tk.glucodata.SensorIdentity

/**
 * Which sensor the watch shows when more than one is present.
 *
 * The watch used to display whatever native called the "main" sensor. With two
 * sensors — one synced from the phone, one the watch reads itself after a
 * handoff — that could be the stale one, so the screens sat on an hour-old
 * reading while a live sensor was right there in the list. There was also no way
 * to say which one you wanted.
 *
 * Resolution order: the sensor the user picked, if it still has recent data;
 * otherwise whichever sensor reported most recently.
 */
object WearSensorSelection {
    private const val TAG = "WearSensorSelection"
    private const val PREFS = "wear_sensor_selection"
    private const val KEY_PINNED = "pinned_sensor"

    /** How recent a reading has to be for a sensor to count as reporting. */
    private const val FRESH_WINDOW_MS = 45L * 60L * 1000L

    private fun prefs() = Applic.app?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The sensor the user chose, or null to follow the freshest automatically. */
    @JvmStatic
    fun pinned(): String? = prefs()?.getString(KEY_PINNED, null)?.takeIf { it.isNotBlank() }

    @JvmStatic
    fun pin(sensorId: String?) {
        val editor = prefs()?.edit() ?: return
        if (sensorId.isNullOrBlank()) editor.remove(KEY_PINNED) else editor.putString(KEY_PINNED, sensorId)
        editor.apply()
        Log.i(TAG, "display sensor pinned to ${sensorId ?: "automatic"}")
        WearGlucoseStore.refresh(force = true)
    }

    /** True when this sensor is the one the screens are showing. */
    @JvmStatic
    fun isDisplayed(sensorId: String?): Boolean {
        val displayed = resolve() ?: return false
        return sensorId != null && SensorIdentity.matches(displayed, sensorId)
    }

    /**
     * The sensor the screens should draw. Falls back to the resolver the rest of
     * the app uses when there is nothing to choose between.
     */
    @JvmStatic
    fun resolve(): String? {
        val fallback = runCatching { NotificationHistorySource.resolveSensorSerial() }.getOrNull()
        val candidates = candidates(fallback)
        if (candidates.size <= 1) return candidates.firstOrNull() ?: fallback

        val now = System.currentTimeMillis()
        val newestByCandidate = candidates.associateWith { serial -> newestReadingMs(serial, now) }

        pinned()?.let { chosen ->
            val match = candidates.firstOrNull { SensorIdentity.matches(it, chosen) }
            if (match != null && now - (newestByCandidate[match] ?: 0L) <= FRESH_WINDOW_MS) {
                return match
            }
        }

        val freshest = newestByCandidate.maxByOrNull { it.value }
        if (freshest != null && freshest.value > 0L) return freshest.key
        return fallback
    }

    private fun candidates(fallback: String?): List<String> {
        val seen = HashSet<String>()
        val out = ArrayList<String>()
        fun add(candidate: String?) {
            val serial = candidate?.trim()?.takeIf { SensorIdentity.isUsableSensorId(it) } ?: return
            val canonical = (runCatching { SensorIdentity.resolveAppSensorId(serial) }.getOrNull() ?: serial).lowercase()
            if (seen.add(canonical)) out.add(serial)
        }
        add(fallback)
        runCatching { Natives.activeSensors() }.getOrNull()?.forEach(::add)
        return out
    }

    private fun newestReadingMs(serial: String, now: Long): Long = runCatching {
        NotificationHistorySource
            .getDisplayHistory(now - FRESH_WINDOW_MS, false, serial)
            .lastOrNull()
            ?.timestamp
            ?: 0L
    }.getOrDefault(0L)
}
