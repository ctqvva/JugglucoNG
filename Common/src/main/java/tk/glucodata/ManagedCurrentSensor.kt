package tk.glucodata

import android.content.Context

/**
 * Logical current-sensor slot for managed sensors without native backing.
 *
 * Native `lastsensorname` must keep pointing at a real native sensor or be empty;
 * storing virtual ids there makes native history/status paths repeatedly try to
 * open non-existent sensor files.
 */
object ManagedCurrentSensor {
    private const val PREFS_NAME = "tk.glucodata_preferences"
    private const val KEY_CURRENT = "managed_current_sensor"

    @Volatile private var cached: String? = null
    // Distinguishes "never read" from "read, and it was absent". Only set once a
    // read actually reached SharedPreferences — before Applic.app exists there is
    // nothing to read, and caching that absence would be permanent.
    @Volatile private var cacheLoaded: Boolean = false

    private fun prefs() = Applic.app?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Cache-first. This is called from [SensorIdentity.resolveAvailableMainSensor],
     * which runs per reading per sensor — twice per reading for a non-main sensor —
     * so hitting SharedPreferences every time put a `getSharedPreferences` +
     * `getString` on the BLE hot path for no benefit.
     *
     * [ManagedSensorHandoff.importEntry] writes this key straight through
     * SharedPreferences rather than via [set], so it must call [invalidate].
     */
    @JvmStatic
    fun get(): String? {
        if (cacheLoaded) {
            return cached?.trim()?.takeIf { it.isNotEmpty() }
        }
        val store = prefs() ?: return cached?.trim()?.takeIf { it.isNotEmpty() }
        val stored = store.getString(KEY_CURRENT, null)
        cached = stored
        cacheLoaded = true
        return stored?.trim()?.takeIf { it.isNotEmpty() }
    }

    /** Drop the cache after a write that bypassed [set] / [clear]. */
    @JvmStatic
    fun invalidate() {
        cacheLoaded = false
        cached = null
    }

    @JvmStatic
    fun set(sensorId: String?) {
        val normalized = sensorId?.trim()?.takeIf { it.isNotEmpty() }
        cached = normalized
        cacheLoaded = true
        prefs()?.edit()?.putString(KEY_CURRENT, normalized)?.apply()
    }

    @JvmStatic
    fun clear() {
        cached = null
        cacheLoaded = true
        prefs()?.edit()?.remove(KEY_CURRENT)?.apply()
    }

    @JvmStatic
    fun clearIfMatches(sensorId: String?) {
        val current = get() ?: return
        if (SensorIdentity.matches(current, sensorId)) {
            clear()
        }
    }
}
