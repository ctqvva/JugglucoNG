package tk.glucodata

import android.view.View

/**
 * The phone-only legacy View screens that shared code reaches (plan §4, category P).
 *
 * `SetColors`/`LabelsClass` and the other legacy screens live in `src/mobile`; the
 * shared `Settings.java` used to reference `LabelsClass` directly, which is why a
 * stub had to exist in `src/wear`. That is the pattern P2 removes: shared code asks
 * the registry, the phone registers its implementation in `Specific.registerBridges`,
 * and a flavour without the screen registers nothing and the caller does without it.
 *
 * The methods grow one screen at a time (this pilot: the number labels); later
 * batches add the rest of the legacy screens here.
 */
interface LegacyScreens {
    /** The number-labels editor, opened from the settings screen. */
    fun openLabels(activity: MainActivity, parent: View)
}

object LegacyScreensAccess {
    @Volatile
    private var screens: LegacyScreens? = null

    @JvmStatic
    fun register(screens: LegacyScreens) {
        this.screens = screens
    }

    /** Registration-completeness check (plan §6 Q1). */
    @JvmStatic
    fun isRegistered(): Boolean = screens != null

    /** Null on a flavour without the legacy screens (the watch). */
    @JvmStatic
    fun get(): LegacyScreens? = screens
}
