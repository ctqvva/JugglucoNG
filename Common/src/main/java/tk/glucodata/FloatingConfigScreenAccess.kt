package tk.glucodata

import android.view.View

/**
 * The floating-window configuration screen (direction.md §4, category S).
 *
 * Unlike the three pairs before it, this one has a real implementation on both flavours --
 * MobileFloatingConfig and WearFloatingConfig, 298 and 305 lines of near-identical fork --
 * and the contract shared code uses is the single method the settings screen calls.
 * Both register; the plan's "the phone registers nothing" is for pairs where the phone copy
 * is a shell, and this one is not.
 */
interface FloatingConfigScreen {
    /** The floating-window configuration, opened from the settings screen. */
    fun show(activity: MainActivity, parent: View)
}

object FloatingConfigScreenAccess {
    @Volatile
    private var screen: FloatingConfigScreen? = null

    @JvmStatic
    fun register(screen: FloatingConfigScreen) {
        this.screen = screen
    }

    /** Registration-completeness check (plan §6 Q1). */
    @JvmStatic
    fun isRegistered(): Boolean = screen != null

    /** Null on a flavour that has not registered the screen. */
    @JvmStatic
    fun get(): FloatingConfigScreen? = screen
}
