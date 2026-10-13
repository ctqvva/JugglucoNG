package tk.glucodata.alerts

import tk.glucodata.CustomAlertAccess
import tk.glucodata.Notify
import tk.glucodata.WearAlertAcknowledgement
import tk.glucodata.WearAlertSync
import tk.glucodata.ui.AlarmActivityAccess

/** Shared by notification buttons/swipes, notification opening and both alarm activities. */
object AlertActions {
    @JvmStatic
    @JvmOverloads
    fun handle(kind: Int, customId: String?, snoozeMinutes: Int? = null) {
        val type = AlertType.fromId(kind)
        val production = if (customId != null) {
            if (snoozeMinutes != null) CustomAlertAccess.snoozeAlert(customId, snoozeMinutes)
            else CustomAlertAccess.dismissAlert(customId)
            true
        } else if (type != null) {
            !AlertStateTracker.consumeManualTestAction(type)
        } else false

        if (production && type != null && customId == null) {
            val now = WearAlertSync.nextActionTimeMillis(kind)
            val duration = if (snoozeMinutes != null) snoozeMinutes.coerceAtLeast(1) * 60_000L
                else AlertStateTracker.effectiveRearmCooldownMs(AlertRepository.loadConfig(type))
            val action = WearAlertAcknowledgement(kind, snoozeMinutes != null, now,
                now + duration.coerceAtMost(WearAlertAcknowledgement.MAX_DURATION_MS))
            applyState(action)
            WearAlertSync.record(action)
        }
        Notify.cancelQueuedAlarmActivityLaunch(kind, customId, "user-acknowledgement")
        // Cancel before stopping: stop's runner schedules another retry for an active session.
        if (production) {
            if (customId != null) Notify.cancelCurrentRetrySession("custom-user-acknowledgement")
            else Notify.cancelRetrySession(kind, "user-acknowledgement")
        }
        if (customId != null || kind < 0 || Notify.isPresentedStandardAlert(kind)) {
            Notify.stopalarm()
            Notify.cancelAlertNotification()
        }
    }

    fun applyRemote(action: WearAlertAcknowledgement) {
        val type = AlertType.fromId(action.kind) ?: return
        if (AlertStateTracker.isManualTestActive(type)) return
        applyState(action)
        Notify.cancelRetrySession(action.kind, "wear-acknowledgement")
        Notify.cancelQueuedAlarmActivityLaunch(action.kind, null, "wear-acknowledgement")
        // A high acknowledgement must never stop a newer low alarm (or a manual test).
        if (Notify.isCurrentStandardAlert(action.kind)) {
            Notify.stopalarmnotsend(false)
            Notify.cancelAlertNotification()
        }
        AlarmActivityAccess.acknowledge(action.kind)
    }

    private fun applyState(action: WearAlertAcknowledgement) {
        val type = AlertType.fromId(action.kind) ?: return
        if (action.snoozed) {
            SnoozeManager.snoozeUntil(type, action.untilMs)
            AlertStateTracker.resetState(type)
        } else {
            // A peer can acknowledge before this device has entered the condition.
            // Only an existing local episode can own an episode-long dismissal.
            if (AlertStateTracker.isEpisodeActive(type)) {
                AlertStateTracker.onAlertDismissed(type, action.untilMs)
            }
            SnoozeManager.clearSnooze(type, recordCancellation = false)
        }
    }

    fun applyExpiredRemote(action: WearAlertAcknowledgement, previous: WearAlertAcknowledgement?) {
        val type = AlertType.fromId(action.kind) ?: return
        // Reconcile the old shared snooze only. A newer local/preemptive snooze, and
        // the sound/notification/episode of a fresh alarm, belong to another action.
        if (previous?.snoozed == true &&
            SnoozeManager.getSnoozeState(type)?.snoozeUntilMillis == previous.untilMs) {
            SnoozeManager.clearSnooze(type, recordCancellation = false)
        }
    }
}
