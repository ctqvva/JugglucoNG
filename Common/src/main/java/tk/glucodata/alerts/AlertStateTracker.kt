package tk.glucodata.alerts

import tk.glucodata.Log
import tk.glucodata.sms.SmsWatchdog

/**
 * Tracks episode state for active alerts.
 *
 * The first firing for an episode comes from the display-lane alert runtime.
 * Timed retries are scheduled by Notify after that first firing, so this
 * tracker only needs to answer "has this episode already fired or been
 * acknowledged?".
 */
object AlertStateTracker {
    private const val LOG_ID = "AlertStateTracker"
    private const val DEFAULT_REARM_COOLDOWN_MS = 5L * 60L * 1000L

    // Last time an alert of this type was triggered (ms)
    private val lastTriggerTime = mutableMapOf<AlertType, Long>()
    private val cooldownUntilTime = mutableMapOf<AlertType, Long>()
    
    // User explicitly dismissed this alert for the current episode.
    // It stays suppressed until the condition clears and resetState() is called.
    private val dismissedAlerts = mutableSetOf<AlertType>()

    // Manual tests use the real delivery surface, but must never acknowledge,
    // snooze, or cool down the corresponding production alert episode.
    private val manualTests = ManualAlertTestState<AlertType>()

    /**
     * Determine if the runtime should fire an alert now.
     *
     * Once an episode has fired, timed retries are handled by Notify rather than
     * by subsequent glucose readings, so repeated live readings stay suppressed
     * until resetState() is called.
     */
    @Synchronized
    fun shouldTrigger(type: AlertType, config: AlertConfig): Boolean {
        if (manualTests.consumeBypassAndActivate(type)) {
            Log.i(LOG_ID, "${type.name}: Manual test bypass")
            return true
        }

        if (!config.isActiveNow()) {
            // Treat inactive time windows as condition-cleared boundaries so the alert
            // rearms cleanly when the active window starts again.
            resetState(type)
            return false
        }

        // 1. Snooze Check (Global priority)
        if (SnoozeManager.isSnoozed(type)) {
            Log.i(LOG_ID, "${type.name}: Suppressed by snooze")
            return false
        }

        if (dismissedAlerts.contains(type)) {
            Log.i(LOG_ID, "${type.name}: Suppressed by episode dismissal")
            return false
        }

        val now = System.currentTimeMillis()
        val cooldownUntil = cooldownUntilTime[type] ?: 0L
        if (cooldownUntil > now) {
            Log.i(LOG_ID, "${type.name}: Suppressed by rearm cooldown for ${cooldownUntil - now}ms")
            return false
        }

        val lastTime = lastTriggerTime[type] ?: 0L
        if (lastTime == 0L) {
            // Only an accepted real trigger supersedes an unacknowledged test surface.
            manualTests.supersede(type)
            Log.i(LOG_ID, "${type.name}: First trigger")
            return true
        }

        return false
    }

    /**
     * Call this when the alert ACTUALLY fires (sound/notification played).
     * Updates timestamps and counters.
     */
    @JvmOverloads
    @Synchronized
    fun onAlertTriggered(type: AlertType, config: AlertConfig? = null): Boolean {
        if (manualTests.isActive(type)) {
            return false
        }
        dismissedAlerts.remove(type)
        lastTriggerTime[type] = System.currentTimeMillis()
        cooldownUntilTime[type] = lastTriggerTime.getValue(type) + effectiveRearmCooldownMs(config)
        SmsWatchdog.onAlertFired(type.id)
        return true
    }

    /**
     * The rearm cooldown after a firing. The configured minimum re-arm
     * interval (forecast alerts) extends the built-in short cooldown, never
     * shortens it; unset/0 keeps today's behaviour.
     */
    internal fun effectiveRearmCooldownMs(config: AlertConfig?): Long {
        val configuredMs = (config?.rearmMinIntervalMinutes ?: 0).coerceAtLeast(0) * 60_000L
        return maxOf(DEFAULT_REARM_COOLDOWN_MS, configuredMs)
    }

    @Synchronized
    fun onAlertDismissed(type: AlertType): Boolean {
        if (manualTests.consumeAction(type)) {
            return false
        }
        dismissedAlerts.add(type)
        SmsWatchdog.onAlertAcknowledged(type.id)
        Log.i(LOG_ID, "Dismissed ${type.name} for current episode")
        return true
    }

    @Synchronized
    fun consumeManualTestAction(type: AlertType): Boolean {
        return manualTests.consumeAction(type)
    }

    @Synchronized
    fun isWaitingForRearmCooldown(type: AlertType): Boolean {
        return type !in dismissedAlerts &&
            (cooldownUntilTime[type] ?: 0L) > System.currentTimeMillis()
    }

    @Synchronized
    fun allowNextTriggerForTest(type: AlertType) {
        manualTests.arm(type)
    }

    /**
     * Reset state for an alert type.
     * Call this when:
     * - Glucose returns to normal.
     * - The alert's active time window closes.
     */
    @Synchronized
    fun resetState(type: AlertType) {
        if (
            lastTriggerTime.containsKey(type) ||
            dismissedAlerts.contains(type) ||
            manualTests.isPending(type)
        ) {
            Log.i(LOG_ID, "Resetting state for ${type.name}")
        }
        lastTriggerTime.remove(type)
        dismissedAlerts.remove(type)
        manualTests.clearPending(type)
        SmsWatchdog.onAlertResolved(type.id)
    }
}

/** Keeps manual-test actions separate from production alert episode state. */
internal class ManualAlertTestState<T> {
    private val pending = mutableSetOf<T>()
    private val active = mutableSetOf<T>()

    fun arm(key: T) {
        pending.add(key)
    }

    fun consumeBypassAndActivate(key: T): Boolean {
        if (!pending.remove(key)) return false
        active.add(key)
        return true
    }

    fun supersede(key: T) {
        active.remove(key)
    }

    fun consumeAction(key: T): Boolean = active.remove(key)

    fun clearPending(key: T) {
        pending.remove(key)
    }

    fun isPending(key: T): Boolean = key in pending

    fun isActive(key: T): Boolean = key in active
}
