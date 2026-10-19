package tk.glucodata.logic

import android.content.Context
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import tk.glucodata.Applic
import tk.glucodata.CurrentDisplaySource
import tk.glucodata.LiveReadingLanes
import tk.glucodata.Notify
import tk.glucodata.alerts.AlertEpisodeState
import tk.glucodata.alerts.CustomAlertConfig
import tk.glucodata.alerts.CustomAlertRepository
import tk.glucodata.alerts.CustomAlertType

object CustomAlertManager {
    private const val TAG = "CustomAlertManager"
    private const val RETRY_RESHOW_GAP_MS = 10_000L
    private const val REARM_COOLDOWN_MS = 5L * 60L * 1000L

    private data class ActiveSession(
        var config: CustomAlertConfig,
        var glucose: Float,
        var rate: Float,
        var lastFireStartedAtMs: Long,
        var retriesUsed: Int = 0,
        var scheduledRetry: ScheduledFuture<*>? = null
    )

    private val lastTriggerMap = mutableMapOf<String, Long>()
    private val cooldownUntilMap = mutableMapOf<String, Long>()
    private val dismissedMap = mutableSetOf<String>()
    private val sessionLock = Any()
    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private val conditionEpisodes = AlertEpisodeState<String>()
    private var activeSession: ActiveSession? = null

    fun checkAndTrigger(context: Context, glucose: Float, rate: Float, timestamp: Long) {
        checkAndTrigger(context, LiveReadingLanes.stock(glucose, Float.NaN), rate, timestamp, null, 0)
    }

    fun checkAndTrigger(
        context: Context,
        reading: LiveReadingLanes,
        rate: Float,
        timestamp: Long,
        sensorId: String?,
        sensorGen: Int
    ) {
        val snapshot = CurrentDisplaySource.resolveIncomingReading(
            reading = reading,
            rate = rate,
            targetTimeMillis = timestamp,
            preferredSensorId = sensorId,
            sensorGen = sensorGen
        ) ?: return
        @Suppress("NAME_SHADOWING") val glucose = snapshot.primaryValue
        @Suppress("NAME_SHADOWING") val rate = snapshot.rate

        val allAlerts = CustomAlertRepository.getAll()
        if (allAlerts.isEmpty()) {
            synchronized(sessionLock) {
                lastTriggerMap.clear()
                cooldownUntilMap.clear()
                dismissedMap.clear()
                conditionEpisodes.clearAll()
                clearActiveSessionLocked("no-custom-alerts")
            }
            return
        }

        // Apply active-window and snooze gating against the actual delivery time, not the
        // sensor sample timestamp. A delayed/stale reading should not fire outside the
        // user's currently selected alert window just because the sample itself was older.
        val evaluationMs = System.currentTimeMillis()

        val activeWindowConfigs = allAlerts.filter { config ->
            if (!config.enabled) return@filter false
            if (!config.isActiveAt(evaluationMs)) return@filter false
            true
        }

        val activeConditionConfigs = activeWindowConfigs.filter { config ->
            isConditionActive(config, glucose)
        }

        val activeIds = activeConditionConfigs.map { it.id }.toSet()
        val transition = synchronized(sessionLock) {
            conditionEpisodes.update(activeIds)
        }
        synchronized(sessionLock) {
            val staleIds = (
                transition.cleared +
                    lastTriggerMap.keys +
                    dismissedMap +
                    listOfNotNull(activeSession?.config?.id)
                ).minus(activeIds)
            staleIds.forEach { id ->
                lastTriggerMap.remove(id)
                dismissedMap.remove(id)
                conditionEpisodes.clear(id)
                if (activeSession?.config?.id == id) {
                    clearActiveSessionLocked("condition-cleared:$id")
                }
            }
        }

        if (activeConditionConfigs.isEmpty()) return

        val candidate = activeConditionConfigs.sortedWith(
            compareBy<CustomAlertConfig> { it.type == CustomAlertType.HIGH }
                .thenBy { if (it.type == CustomAlertType.LOW) it.threshold else -it.threshold }
        ).firstOrNull() ?: return

        if (!transition.shouldTryFire(candidate.id)) {
            return
        }

        if (evaluationMs < candidate.snoozedUntil) {
            synchronized(sessionLock) {
                conditionEpisodes.markPendingDelivery(candidate.id)
            }
            return
        }

        var shouldFire = false
        synchronized(sessionLock) {
            val currentSession = activeSession
            if (currentSession != null && currentSession.config.id != candidate.id) {
                lastTriggerMap.remove(currentSession.config.id)
                clearActiveSessionLocked("candidate-replaced:${currentSession.config.id}->${candidate.id}")
            }

            if (dismissedMap.contains(candidate.id)) {
                conditionEpisodes.clearPending(candidate.id)
                return@synchronized
            }

            val session = activeSession
            if (session != null && session.config.id == candidate.id) {
                session.config = candidate
                session.glucose = glucose
                session.rate = rate
                return@synchronized
            }

            val cooldownUntil = cooldownUntilMap[candidate.id] ?: 0L
            if (evaluationMs < cooldownUntil) {
                conditionEpisodes.markPendingDelivery(candidate.id)
                return@synchronized
            }

            if (!lastTriggerMap.containsKey(candidate.id)) {
                val now = System.currentTimeMillis()
                lastTriggerMap[candidate.id] = now
                cooldownUntilMap[candidate.id] = now + REARM_COOLDOWN_MS
                activeSession = ActiveSession(
                    config = candidate,
                    glucose = glucose,
                    rate = rate,
                    lastFireStartedAtMs = now
                )
                shouldFire = true
            }
        }

        if (shouldFire) {
            triggerAlert(context, candidate, glucose, rate)
            synchronized(sessionLock) {
                conditionEpisodes.clearPending(candidate.id)
                activeSession?.takeIf { it.config.id == candidate.id }?.let { session ->
                    scheduleRetryFromStartLocked(session)
                }
            }
        }
    }

    fun dismissAlert(alertId: String) {
        synchronized(sessionLock) {
            dismissedMap.add(alertId)
            conditionEpisodes.clearPending(alertId)
            if (activeSession?.config?.id == alertId) {
                clearActiveSessionLocked("dismiss:$alertId")
            }
        }
        Log.i(TAG, "Dismissed custom alert: $alertId")
    }

    fun snoozeAlert(alertId: String, snoozeMinutes: Int) {
        val snoozeUntil = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(snoozeMinutes.toLong())
        val updated = CustomAlertRepository.getAll().firstOrNull { it.id == alertId }?.copy(snoozedUntil = snoozeUntil)
            ?: return
        CustomAlertRepository.update(updated)
        synchronized(sessionLock) {
            dismissedMap.remove(alertId)
            lastTriggerMap.remove(alertId)
            conditionEpisodes.clear(alertId)
            if (activeSession?.config?.id == alertId) {
                clearActiveSessionLocked("snooze:$alertId")
            }
        }
        Log.i(TAG, "Snoozed custom alert $alertId for $snoozeMinutes minutes")
    }

    fun ignoreAlert(alertId: String?) {
        synchronized(sessionLock) {
            val session = activeSession ?: return
            if (alertId != null && session.config.id != alertId) {
                return
            }
            scheduleRetryAfterStopLocked(session, System.currentTimeMillis())
        }
    }

    private fun triggerAlert(context: Context, config: CustomAlertConfig, glucose: Float, rate: Float) {
        Log.i(TAG, "Triggering Custom Alert: ${config.name} (Threshold: ${config.threshold}, Glucose: $glucose)")

        Notify.triggerCustomAlert(
            config.soundUri,
            config.sound,
            config.vibrate,
            config.flash,
            config.type == CustomAlertType.HIGH,
            glucose,
            config.style,
            config.hapticProfile,
            config.durationSeconds,
            config.overrideDnd,
            config.id,
            config.name,
            rate,
            config.soundVolumePercent
        )
    }

    private fun isConditionActive(config: CustomAlertConfig, glucose: Float): Boolean {
        return if (config.type == CustomAlertType.HIGH) {
            glucose > config.threshold
        } else {
            glucose < config.threshold
        }
    }

    private fun scheduleRetryFromStartLocked(session: ActiveSession) {
        if (!session.config.retryEnabled) {
            clearScheduledRetryLocked(session)
            return
        }
        if (session.config.retryCount != 0 && session.retriesUsed >= session.config.retryCount) {
            clearScheduledRetryLocked(session)
            return
        }
        val durationMs = Notify.estimateAlertEffectDurationMs(
            session.config.soundUri,
            if (session.config.type == CustomAlertType.HIGH) 1 else 0,
            session.config.sound,
            session.config.flash,
            session.config.vibrate,
            session.config.hapticProfile,
            session.config.durationSeconds
        )
        val intervalMs = retryIntervalMs(session.config)
        val delayMs = if (intervalMs <= 0L) {
            durationMs + RETRY_RESHOW_GAP_MS
        } else {
            maxOf(intervalMs, durationMs + RETRY_RESHOW_GAP_MS)
        }
        scheduleRetryLocked(session, delayMs, "from-start")
    }

    private fun scheduleRetryAfterStopLocked(session: ActiveSession, stopTimeMs: Long) {
        if (!session.config.retryEnabled) {
            clearScheduledRetryLocked(session)
            return
        }
        if (session.config.retryCount != 0 && session.retriesUsed >= session.config.retryCount) {
            clearScheduledRetryLocked(session)
            return
        }
        val intervalMs = retryIntervalMs(session.config)
        val delayMs = if (intervalMs <= 0L) {
            RETRY_RESHOW_GAP_MS
        } else {
            maxOf(RETRY_RESHOW_GAP_MS, (session.lastFireStartedAtMs + intervalMs) - stopTimeMs)
        }
        scheduleRetryLocked(session, delayMs, "after-stop")
    }

    private fun scheduleRetryLocked(session: ActiveSession, delayMs: Long, reason: String) {
        clearScheduledRetryLocked(session)
        session.scheduledRetry = scheduler.schedule({
            fireScheduledRetry(session.config.id)
        }, delayMs, TimeUnit.MILLISECONDS)
        Log.i(TAG, "Scheduled custom retry id=${session.config.id} delayMs=$delayMs reason=$reason retriesUsed=${session.retriesUsed}")
    }

    private fun fireScheduledRetry(alertId: String) {
        val sessionSnapshot: ActiveSession
        synchronized(sessionLock) {
            val session = activeSession ?: return
            if (session.config.id != alertId) return

            val refreshedConfig = CustomAlertRepository.getAll().firstOrNull { it.id == alertId }
            if (refreshedConfig == null) {
                clearActiveSessionLocked("config-missing:$alertId")
                return
            }

            val now = System.currentTimeMillis()
            if (!refreshedConfig.enabled || !refreshedConfig.isActiveAt(now) || now < refreshedConfig.snoozedUntil || dismissedMap.contains(alertId)) {
                clearActiveSessionLocked("retry-not-allowed:$alertId")
                return
            }

            if (refreshedConfig.retryCount != 0 && session.retriesUsed >= refreshedConfig.retryCount) {
                clearScheduledRetryLocked(session)
                Log.i(TAG, "Custom alert retry limit reached for $alertId")
                return
            }

            session.config = refreshedConfig
            session.lastFireStartedAtMs = now
            session.retriesUsed += 1
            sessionSnapshot = session.copy(config = refreshedConfig, glucose = session.glucose, rate = session.rate, lastFireStartedAtMs = now, retriesUsed = session.retriesUsed, scheduledRetry = null)
            lastTriggerMap[alertId] = now
            cooldownUntilMap[alertId] = now + REARM_COOLDOWN_MS
            scheduleRetryFromStartLocked(session)
        }

        triggerAlert(Applic.app, sessionSnapshot.config, sessionSnapshot.glucose, sessionSnapshot.rate)
    }

    private fun clearActiveSessionLocked(reason: String) {
        activeSession?.let { session ->
            clearScheduledRetryLocked(session)
            Log.i(TAG, "Clear custom alert session id=${session.config.id} reason=$reason")
        }
        activeSession = null
    }

    private fun clearScheduledRetryLocked(session: ActiveSession) {
        session.scheduledRetry?.cancel(false)
        session.scheduledRetry = null
    }

    private fun retryIntervalMs(config: CustomAlertConfig): Long {
        return if (config.retryIntervalMinutes <= 0) 0L else TimeUnit.MINUTES.toMillis(config.retryIntervalMinutes.toLong())
    }

}
