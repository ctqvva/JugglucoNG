package tk.glucodata.settings

import android.app.Activity
import android.view.View
import android.widget.CheckBox
import tk.glucodata.Dialogs
import tk.glucodata.Menus
import tk.glucodata.IOB
import tk.glucodata.LegacyScreens
import tk.glucodata.Libreview
import tk.glucodata.MainActivity
import tk.glucodata.MeterList
import tk.glucodata.Nightscout

/**
 * The phone's legacy View screens (plan §4, category P). Registered from the mobile
 * `Specific.registerBridges`; shared `Settings.java` reaches them through
 * [tk.glucodata.LegacyScreensAccess] instead of naming `LabelsClass` directly, so the
 * watch no longer needs a stub.
 */
object MobileLegacyScreens : LegacyScreens {
    /**
     * The export screen's dialogs, built on first use. It used to be a field on
     * [tk.glucodata.GlucoseCurve], which shared code reached as `curve.dialogs`; nothing
     * owned it by anything shorter-lived than the process before either (Applic holds the
     * one curve), so holding it here is the same lifetime.
     */
    private var dialogs: Dialogs? = null

    override fun openLabels(activity: MainActivity, parent: View) {
        LabelsClass(activity).mklabellayout(parent)
    }

    override fun openIob(activity: MainActivity) {
        IOB.mkview(activity)
    }

    override fun openMeterList(activity: MainActivity, parent: View?) {
        MeterList.show(activity, parent)
    }

    override fun openNightscout(activity: Activity, parent: View) {
        Nightscout.show(activity, parent)
    }

    override fun configureLibreview(
        activity: MainActivity,
        parent: View,
        sendTo: CheckBox,
        noChange: BooleanArray,
    ) {
        Libreview.config(activity, parent, sendTo, noChange)
    }

    override fun openExport(activity: MainActivity, width: Int, height: Int, parent: View?) {
        exportDialogs(activity).showexport(activity, width, height, parent)
    }

    override fun setExportStatus(text: CharSequence) {
        dialogs?.setExportStatus(text)
    }

    override fun setExportStatus(resId: Int) {
        dialogs?.setExportStatus(resId)
    }

    override fun openMenus(activity: MainActivity) {
        Menus.show(activity)
    }

    /** Density comes from the activity, the same way GlucoseCurve used to pass it. */
    private fun exportDialogs(activity: MainActivity): Dialogs =
        dialogs ?: Dialogs.create(activity.resources.displayMetrics.density).also { dialogs = it }
}
