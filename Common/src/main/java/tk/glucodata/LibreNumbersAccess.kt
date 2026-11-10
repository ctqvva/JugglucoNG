package tk.glucodata

import android.app.Activity
import android.view.View
import android.widget.CheckBox

/**
 * The phone's night-post Libre-numbers options (direction.md §4, category P — "needs
 * phone-only resources").
 *
 * `LibreNumbers` builds its dialog from `R.layout.librenumoptions`, a phone resource.
 * The recipe is explicit that the resource does not move into `main` to make a source
 * move compile, so the class stays in `src/mobile` and shared `NightPost` reaches it
 * through here. The watch registered nothing before either: its 27-line copy was a stub
 * and nothing in `src/wear` called it.
 */
interface LibreNumbersLayout {
    /**
     * Builds the options next to the night-post "treatments" switch and hides [parent].
     *
     * [noChange] is the caller's re-entrancy latch: the screen clears it once it has
     * applied the new setting, and the caller flips the switch back until then.
     */
    fun configureNightNumbers(
        activity: Activity,
        night: Int,
        enabled: CheckBox,
        noChange: IntArray,
        parent: View,
    )
}

object LibreNumbersAccess {
    @Volatile
    private var layout: LibreNumbersLayout? = null

    @JvmStatic
    fun register(layout: LibreNumbersLayout) {
        this.layout = layout
    }

    /** Registration-completeness check (plan §6 Q1). */
    @JvmStatic
    fun isRegistered(): Boolean = layout != null

    /** Null on a flavour without the phone's Libre-numbers layout (the watch). */
    @JvmStatic
    fun get(): LibreNumbersLayout? = layout
}
