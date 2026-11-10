package tk.glucodata.ui

import android.content.Context
import android.content.Intent

/**
 * Wear full-screen alarm activity (plan P1/Q1, category S). Registered from the
 * wear `Specific.registerBridges`; `src/main` reaches it through
 * [AlarmActivityAccess] instead of resolving `AlarmActivity` by name.
 */
object WearAlarmActivityHost : AlarmActivityHost {
    override fun newAlarmActivityIntent(context: Context): Intent =
        Intent(context, AlarmActivity::class.java)
}
