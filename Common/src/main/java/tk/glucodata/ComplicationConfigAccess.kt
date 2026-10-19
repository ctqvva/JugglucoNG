package tk.glucodata

import android.view.View

/**
 * The watch's glucose-complication colour screen (direction.md §4, category S).
 *
 * Two real implementations is the normal shape for this category, but not here: the phone
 * copy was five lines whose `show` did nothing, and the only shared caller is the
 * complications button, which lives inside `if (isWearable)`. So the phone side is not a
 * stub to be renamed, it is nothing, and it is deleted rather than registered: what the
 * shared code needs is the watch's screen, and the watch is the only flavour that has it.
 *
 * One call site, so absence is a nullable registry and a caller-side check rather than a
 * no-op implementation, per the ruling on #465.
 */
interface ComplicationConfig {
    /** The complication colour configuration, opened from the settings screen. */
    fun show(activity: MainActivity, parent: View)
}

object ComplicationConfigAccess {
    @Volatile
    private var config: ComplicationConfig? = null

    @JvmStatic
    fun register(config: ComplicationConfig) {
        this.config = config
    }

    /** Registration-completeness check (plan §6 Q1). */
    @JvmStatic
    fun isRegistered(): Boolean = config != null

    /** Null on a flavour without the screen (the phone). */
    @JvmStatic
    fun get(): ComplicationConfig? = config
}
