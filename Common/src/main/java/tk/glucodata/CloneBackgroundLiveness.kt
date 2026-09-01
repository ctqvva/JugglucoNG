package tk.glucodata

import android.annotation.SuppressLint
import android.content.Context
import android.os.PowerManager

/** Optional receiver policy for uninterrupted Clone traffic while Android is asleep. */
object CloneBackgroundLiveness {
    private const val PREFS_NAME = "tk.glucodata_preferences"
    private const val KEY_ENABLED = "clone_background_liveness_v1"
    private const val WAKE_LOCK_TAG = "Juggluco::CloneReceiver"
    private val lock = Any()

    @Volatile
    private var wakeLock: PowerManager.WakeLock? = null

    private fun prefs() = Applic.app?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @JvmStatic
    fun isEnabled(): Boolean = prefs()?.getBoolean(KEY_ENABLED, false) ?: false

    @JvmStatic
    fun setEnabled(enabled: Boolean) {
        prefs()?.edit()?.putBoolean(KEY_ENABLED, enabled)?.apply()
        sync()
    }

    internal fun shouldHold(enabled: Boolean, receptionEnabled: Boolean, hasReceiver: Boolean): Boolean =
        enabled && receptionEnabled && hasReceiver

    private fun hasEnabledReceiver(): Boolean = runCatching {
        (0 until Natives.backuphostNr()).any { index ->
            !Natives.isWearOS(index) &&
                !Natives.getHostDeactivated(index) &&
                (Natives.getbackuphostreceive(index) and 2) != 0
        }
    }.getOrDefault(false)

    /**
     * A timeout would reintroduce the exact sleep gap this option is meant to prevent.
     * The foreground service and every Clone configuration transition call [sync], and
     * Android releases the lock automatically if the process dies.
     */
    @JvmStatic
    @SuppressLint("WakelockTimeout")
    fun sync() {
        val shouldHold = shouldHold(isEnabled(), CloneSensorRegistry.isReceptionEnabled(), hasEnabledReceiver())
        synchronized(lock) {
            val current = wakeLock
            if (!shouldHold) {
                releaseLocked(current)
                wakeLock = null
                return
            }
            if (current?.isHeld == true) return
            releaseLocked(current)
            val powerManager = Applic.app?.getSystemService(Context.POWER_SERVICE) as? PowerManager
                ?: return
            wakeLock = runCatching {
                powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }.onFailure {
                Log.stack("CloneBackgroundLiveness", "acquire", it)
            }.getOrNull()
        }
    }

    @JvmStatic
    fun release() {
        synchronized(lock) {
            releaseLocked(wakeLock)
            wakeLock = null
        }
    }

    private fun releaseLocked(current: PowerManager.WakeLock?) {
        runCatching {
            if (current?.isHeld == true) current.release()
        }.onFailure { Log.stack("CloneBackgroundLiveness", "release", it) }
    }
}
