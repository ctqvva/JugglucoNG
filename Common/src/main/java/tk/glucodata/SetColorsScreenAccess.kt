package tk.glucodata

/**
 * The invert/colours screen (direction.md §4, category S).
 *
 * Both sides are real implementations -- 144 lines on the phone, 96 on the watch, and the
 * watch's is not a shell: it adjusts the light bars and opens its own colour dialog. So
 * unlike the three pairs before it, both are renamed (`MobileSetColors`, `WearSetColors`) and
 * both register, and the contract is the single method the settings screen calls.
 *
 * Note this duplicate was invisible to the compiler: `Settings` is in the same package,
 * `tk.glucodata.settings`, and `show` is package-private on both sides, so shared code
 * called a phone class by a name the watch happened to also have. That is the whole
 * mechanism category S exists to remove.
 */
interface SetColorsScreen {
    /** The invert/colours settings, opened from the settings screen. */
    fun show(activity: MainActivity)
}

object SetColorsScreenAccess {
    @Volatile
    private var screen: SetColorsScreen? = null

    @JvmStatic
    fun register(screen: SetColorsScreen) {
        this.screen = screen
    }

    /** Registration-completeness check (plan §6 Q1). */
    @JvmStatic
    fun isRegistered(): Boolean = screen != null

    @JvmStatic
    fun get(): SetColorsScreen? = screen
}
