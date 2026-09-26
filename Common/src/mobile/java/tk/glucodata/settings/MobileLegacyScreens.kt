package tk.glucodata.settings

import android.view.View
import tk.glucodata.LegacyScreens
import tk.glucodata.MainActivity

/**
 * The phone's legacy View screens (plan §4, category P). Registered from the mobile
 * `Specific.registerBridges`; shared `Settings.java` reaches them through
 * [tk.glucodata.LegacyScreensAccess] instead of naming `LabelsClass` directly, so the
 * watch no longer needs a stub.
 */
object MobileLegacyScreens : LegacyScreens {
    override fun openLabels(activity: MainActivity, parent: View) {
        LabelsClass(activity).mklabellayout(parent)
    }
}
