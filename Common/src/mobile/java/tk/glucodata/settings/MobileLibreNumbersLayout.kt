package tk.glucodata.settings

import android.app.Activity
import android.view.View
import android.widget.CheckBox
import tk.glucodata.LibreNumbersLayout

/**
 * The phone's night-post numbers adapter for
 * [tk.glucodata.LibreNumbersAccess] (direction.md §4, category P). `LibreNumbers`
 * builds its dialog from a phone-only layout resource, so the class stays here rather
 * than moving into `main` with the resource dragged along.
 */
object MobileLibreNumbersLayout : LibreNumbersLayout {
    override fun configureNightNumbers(
        activity: Activity,
        night: Int,
        enabled: CheckBox,
        noChange: IntArray,
        parent: View,
    ) {
        LibreNumbers.mklayout(activity, night, enabled, noChange, parent)
    }
}
