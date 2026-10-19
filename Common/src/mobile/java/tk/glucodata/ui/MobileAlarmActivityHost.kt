package tk.glucodata.ui

import android.content.Context
import android.content.Intent

/**
 * Mobile full-screen alarm activity (plan P1/Q1, category S). Registered from the
 * mobile `Specific.registerBridges`; `src/main` reaches it through
 * [AlarmActivityAccess] instead of resolving `AlarmActivity` by name.
 */
object MobileAlarmActivityHost : AlarmActivityHost {
    override fun newAlarmActivityIntent(context: Context): Intent =
        Intent(context, AlarmActivity::class.java)
}
