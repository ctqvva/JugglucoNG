package tk.glucodata

import android.content.Context
import android.os.Handler
import android.os.Looper
import tk.glucodata.alerts.AlertActions
import tk.glucodata.alerts.AlertType

/** Two-way acknowledgements. Retain the newest action per kind across process death/reconnect. */
object WearAlertSync {
    private val prefs get() = Applic.app?.getSharedPreferences("wear_alert_ack", Context.MODE_PRIVATE)

    private fun current(kind: Int): WearAlertAcknowledgement? {
        val at = prefs?.getLong("at_$kind", 0) ?: 0
        if (at == 0L) return null
        return WearAlertAcknowledgement(kind, prefs?.getBoolean("snoozed_$kind", false) ?: false, at,
            prefs?.getLong("until_$kind", 0) ?: 0)
    }

    private fun save(action: WearAlertAcknowledgement) {
        prefs?.edit()?.putLong("at_${action.kind}", action.actedAtMs)
            ?.putLong("until_${action.kind}", action.untilMs)
            ?.putBoolean("snoozed_${action.kind}", action.snoozed)?.apply()
    }

    fun nextActionTimeMillis(kind: Int): Long =
        maxOf(System.currentTimeMillis(), (current(kind)?.actedAtMs ?: 0) + 1)

    fun record(action: WearAlertAcknowledgement) {
        save(action)
        MessageSender.sendAlertAcknowledgement(null, action.encode())
    }

    /** Explicitly clearing a snooze must also clear its persisted Wear quiet deadline. */
    fun onSnoozeCleared(kind: Int) {
        current(kind)?.takeIf { it.snoozed }?.let { save(it.copy(untilMs = 0)) }
    }

    @JvmStatic
    fun isSuppressed(kind: Int): Boolean = (current(kind)?.untilMs ?: 0) > System.currentTimeMillis()

    fun onMessage(data: ByteArray?) {
        val action = WearAlertAcknowledgement.decode(data, System.currentTimeMillis()) ?: return
        Handler(Looper.getMainLooper()).post {
            // Expired messages must not dismiss a fresh episode. Keep the timestamp as a
            // tombstone so an older, reordered message cannot replace this action either.
            if (action.supersedes(current(action.kind))) {
                save(action)
                if (action.untilMs > System.currentTimeMillis()) AlertActions.applyRemote(action)
            }
        }
    }

    /** Discovery sends a snapshot, so an acknowledgement made while disconnected is not lost. */
    fun pushTo(nodeId: String) {
        AlertType.settingsEntries.forEach { type ->
            current(type.id)?.takeIf { it.untilMs > System.currentTimeMillis() }?.let {
                MessageSender.sendAlertAcknowledgement(nodeId, it.encode())
            }
        }
    }
}
