package tk.glucodata

import android.content.Context

/**
 * Bridge from the shared code to the app's custom-alert engine.
 *
 * This used to find the engine by name at runtime, via a literal class lookup plus
 * `getField`/`getMethod`. The class lookup itself survived, because R8 folds a
 * literal lookup into a direct class reference. The members did not: in a release
 * build that class becomes `a71`, whose entire member list is `<clinit> a b c d e`,
 * so the field and method lookups threw. `runCatching` swallowed that,
 * [checkAndTrigger] returned having done nothing, and custom alerts silently never
 * fired in a release build. Notify's dismissal path carried its own copy of the same
 * lookup, so they could not be dismissed either.
 *
 * No keep rule protects any of this, and adding one would only pin the names the detour depends on.
 * The sibling bridge to the TrendEngine happens to still work, purely because R8's allocator handed
 * that one method its own name back; this is what it looks like when that luck runs out. So the
 * implementation is registered explicitly instead ([register], from the mobile Specific.start()),
 * leaving ordinary interface calls behind.
 */
object CustomAlertAccess {
    private const val LOG_ID = "CustomAlertAccess"

    @Volatile
    private var controller: CustomAlertController? = null

    /** Registered at startup, before any reading can be evaluated. */
    @JvmStatic
    fun register(controller: CustomAlertController) {
        this.controller = controller
    }

    /** Registration-completeness check (plan §6 Q1). */
    @JvmStatic
    fun isRegistered(): Boolean = controller != null

    @JvmStatic
    fun checkAndTrigger(context: Context, glucose: Float, rate: Float, timestampMillis: Long) {
        checkAndTrigger(context, glucose, rate, timestampMillis, null, 0)
    }

    @JvmStatic
    fun checkAndTrigger(
        context: Context,
        glucose: Float,
        rate: Float,
        timestampMillis: Long,
        sensorId: String?,
        sensorGen: Int
    ) {
        checkAndTrigger(context, LiveReadingLanes.stock(glucose, Float.NaN), rate, timestampMillis, sensorId, sensorGen)
    }

    @JvmStatic
    fun checkAndTrigger(
        context: Context,
        reading: LiveReadingLanes,
        rate: Float,
        timestampMillis: Long,
        sensorId: String?,
        sensorGen: Int
    ) {
        val target = controller
        if (target == null) {
            warnMissing("checkAndTrigger")
            return
        }
        runCatching {
            target.checkAndTrigger(context, reading, rate, timestampMillis, sensorId, sensorGen)
        }.onFailure { Log.stack(LOG_ID, "checkAndTrigger", it) }
    }

    /** @return whether the engine actually handled the dismissal. */
    @JvmStatic
    fun dismissAlert(alertId: String): Boolean {
        val target = controller
        if (target == null) {
            warnMissing("dismissAlert")
            return false
        }
        return runCatching { target.dismissAlert(alertId); true }
            .onFailure { Log.stack(LOG_ID, "dismissAlert", it) }
            .getOrDefault(false)
    }

    /**
     * Snooze and ignore are what the notification actions need beyond dismissal.
     * Like the rest, they no-op when no engine is registered (the watch has none).
     */
    @JvmStatic
    fun snoozeAlert(alertId: String, snoozeMinutes: Int): Boolean {
        val target = controller
        if (target == null) {
            warnMissing("snoozeAlert")
            return false
        }
        return runCatching { target.snoozeAlert(alertId, snoozeMinutes); true }
            .onFailure { Log.stack(LOG_ID, "snoozeAlert", it) }
            .getOrDefault(false)
    }

    @JvmStatic
    fun ignoreAlert(alertId: String): Boolean {
        val target = controller
        if (target == null) {
            warnMissing("ignoreAlert")
            return false
        }
        return runCatching { target.ignoreAlert(alertId); true }
            .onFailure { Log.stack(LOG_ID, "ignoreAlert", it) }
            .getOrDefault(false)
    }

    // Deliberately not behind doLog: without the engine registered, custom alerts do not fire at
    // all. That is precisely the failure that went unnoticed for as long as this was reflection.
    private fun warnMissing(what: String) {
        Log.e(LOG_ID, "CustomAlertController not registered - custom alerts inert ($what)")
    }

}
