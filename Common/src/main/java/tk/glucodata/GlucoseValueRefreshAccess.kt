package tk.glucodata

/**
 * The watch's glucose-complication value view (direction.md §4, category S).
 *
 * The shared contract is one method. The phone copy was two lines whose `updateall` did
 * nothing, so per the ruling on #465 the phone registers nothing and the class is deleted
 * rather than kept as an empty shell; the three shared callers are on a path the phone runs
 * and simply have nothing to refresh there, which is what the no-op did.
 *
 * Not called WearGlucoseValue: there is already a composable of that name in
 * `tk.glucodata.ui.screens`, and two different symbols with one name is worse than a less
 * literal rename. This class draws a glucose value into a view for a complication.
 */
interface GlucoseValueRefresh {
    /** Redraws every live complication value view. */
    fun updateAll()
}

object GlucoseValueRefreshAccess {
    @Volatile
    private var refresh: GlucoseValueRefresh? = null

    @JvmStatic
    fun register(refresh: GlucoseValueRefresh) {
        this.refresh = refresh
    }

    /** Registration-completeness check (plan §6 Q1). */
    @JvmStatic
    fun isRegistered(): Boolean = refresh != null

    /** Null on a flavour without complication value views (the phone). */
    @JvmStatic
    fun get(): GlucoseValueRefresh? = refresh
}
