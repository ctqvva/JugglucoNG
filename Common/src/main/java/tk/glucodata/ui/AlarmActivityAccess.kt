package tk.glucodata.ui

import android.content.Context
import android.content.Intent

/**
 * The variant's full-screen alarm activity as `src/main` needs it (plan P1/Q1,
 * category S).
 *
 * [tk.glucodata.Notify] used to resolve `tk.glucodata.ui.AlarmActivity` by name
 * and [tk.glucodata.receivers.AlarmLaunchReceiver] imported the class directly;
 * both flavours have their own `AlarmActivity`, so shared code named a variant
 * class. Each flavour registers its implementation from
 * `Specific.registerBridges`, and callers build the Intent through [AlarmActivityAccess].
 */
fun interface AlarmActivityHost {
    /** A bare Intent bound to the flavour's AlarmActivity; callers add the extras. */
    fun newAlarmActivityIntent(context: Context): Intent
}

object AlarmActivityAccess {
    private var observerOwner: Any? = null
    private var observerKind = -1
    private var onAcknowledgement: (() -> Unit)? = null

    fun observeAcknowledgement(owner: Any, kind: Int, callback: () -> Unit) {
        observerOwner = owner
        observerKind = kind
        onAcknowledgement = callback
    }

    fun removeObserver(owner: Any) {
        if (observerOwner === owner) {
            observerOwner = null
            onAcknowledgement = null
        }
    }

    fun acknowledge(kind: Int) {
        if (observerKind == kind) onAcknowledgement?.invoke()
    }

    @Volatile
    private var host: AlarmActivityHost? = null

    @JvmStatic
    fun register(host: AlarmActivityHost) {
        this.host = host
    }

    /** Registration-completeness check (plan §6 Q1). */
    @JvmStatic
    fun isRegistered(): Boolean = host != null

    /**
     * Null where the flavour has no full-screen alarm UI, so the caller keeps its
     * notification fallback. That is the old `ClassNotFoundException` path.
     */
    @JvmStatic
    fun newIntent(context: Context): Intent? = host?.newAlarmActivityIntent(context)
}
