package tk.glucodata

import android.app.Activity
import android.view.View
import android.widget.CheckBox

/**
 * The phone-only legacy View screens that shared code reaches (plan §4, category P).
 *
 * `SetColors`/`LabelsClass` and the other legacy screens live in `src/mobile`; the
 * shared `Settings.java` used to reference `LabelsClass` directly, which is why a
 * stub had to exist in `src/wear`. That is the pattern P2 removes: shared code asks
 * the registry, the phone registers its implementation in `Specific.registerBridges`,
 * and a flavour without the screen registers nothing and the caller does without it.
 *
 * The methods grow one screen at a time; the first batch was the number labels,
 * the second the exchange/meter screens whose watch copies were dead no-ops.
 */
interface LegacyScreens {
    /** The number-labels editor, opened from the settings screen. */
    fun openLabels(activity: MainActivity, parent: View)

    /** The IOB (insulin-on-board) view, opened from the settings screen. */
    fun openIob(activity: MainActivity)

    /** The Bluetooth-meter list. [parent] is null when opened from the sensor-list panel. */
    fun openMeterList(activity: MainActivity, parent: View?)

    /** The Nightscout web-server configuration. */
    fun openNightscout(activity: Activity, parent: View)

    /**
     * The Libreview upload configuration. [noChange] is the caller's re-entrancy
     * latch: the mobile screen clears it once it has applied the new setting.
     */
    fun configureLibreview(
        activity: MainActivity,
        parent: View,
        sendTo: CheckBox,
        noChange: BooleanArray,
    )

    /** The export screen, opened from the settings screen. */
    /**
     * The export screen, opened from the settings screen. [parent] is null when opened
     * from the menus, which is how that caller has always called showexport.
     */
    fun openExport(activity: MainActivity, width: Int, height: Int, parent: View?)

    /** The export screen's status line, set from the result of the document picker. */
    fun setExportStatus(text: CharSequence)

    /** As [setExportStatus], for a string resource. */
    fun setExportStatus(resId: Int)

    /**
     * The number/label menus, opened from about a dozen places including some the watch
     * also runs. It also turns the menus on, which is why [LegacyScreensAccess.menusOn]
     * is shared state rather than something this returns.
     */
    fun openMenus(activity: MainActivity)
}

object LegacyScreensAccess {
    /**
     * How many days of history the pending export covers, remembered between asking for a
     * document and getting the result back.
     *
     * Shared state rather than a screen, and it lives here because the ruling on #465 for
     * `Menus.on` was to move a phone static that shared code reads into `src/main`. It was
     * `Dialogs.showdays` before: written only by the phone's `Dialogs`, read only from
     * shared `MainActivity`, and the watch's copy was a `final int` nobody read while the
     * native call takes a float.
     */
    @JvmStatic
    @Volatile
    var exportShowDays: Float = 0f

    /**
     * Whether the number/label menus are on. Shared state, and the watch reads it as
     * readily as the phone: it was a phone static before, read from shared code in about a
     * dozen places and written both by ScanNfcV and by Menus.show itself, with a watch copy
     * that only had to exist so those reads compiled.
     */
    @JvmStatic
    @Volatile
    var menusOn: Boolean = false

    /**
     * The no-op a flavour without the legacy screens gets. Explicit rather than default
     * interface methods on purpose: a method the phone adapter forgets to override then
     * fails to compile instead of quietly doing nothing.
     */
    private val none = object : LegacyScreens {
        override fun openLabels(activity: MainActivity, parent: View) {}
        override fun openIob(activity: MainActivity) {}
        override fun openMeterList(activity: MainActivity, parent: View?) {}
        override fun openNightscout(activity: Activity, parent: View) {}
        override fun configureLibreview(
            activity: MainActivity,
            parent: View,
            sendTo: CheckBox,
            noChange: BooleanArray,
        ) {
        }

        override fun openExport(activity: MainActivity, width: Int, height: Int, parent: View?) {}
        override fun setExportStatus(text: CharSequence) {}
        override fun setExportStatus(resId: Int) {}
        override fun openMenus(activity: MainActivity) {}
    }

    @Volatile
    private var screens: LegacyScreens? = null

    @JvmStatic
    fun register(screens: LegacyScreens) {
        this.screens = screens
    }

    /** Registration-completeness check (plan §6 Q1). */
    @JvmStatic
    fun isRegistered(): Boolean = screens != null

    /**
     * The registered screens, or the no-op. Never null: the menus have about a dozen call
     * sites and the watch's copy of them was empty, so the ruling on #465 for many call
     * sites applies -- a no-op the watch gets by default, and every call site stays one
     * line. Absence is still a distinct, testable state: [isRegistered] is false there.
     */
    @JvmStatic
    fun get(): LegacyScreens = screens ?: none
}
