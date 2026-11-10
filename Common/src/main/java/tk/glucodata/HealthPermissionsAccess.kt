package tk.glucodata

/**
 * The phone's Health Connect permission request (direction.md §4, category P).
 *
 * `LaunchShit` is androidx.health and stays in `src/mobile`. It wraps an
 * `ActivityResultLauncher`, which has to be registered while the activity is still being
 * constructed, so the requester cannot be created on demand the way the other bridges
 * are: shared `MainActivity` has to build it in a field initialiser, and the phone
 * supplies the construction. That is the whole difference from [HealthConnectAccess] --
 * a factory rather than a ready-made instance -- and the same P2 shape otherwise: the
 * watch registers nothing, [create] returns null, and the field stays null.
 */
fun interface HealthPermissionRequester {
    /** [permissions] is a set because that is what the Health Connect contract takes. */
    fun request(permissions: Set<String>)
}

object HealthPermissionsAccess {
    fun interface Factory {
        fun create(activity: MainActivity): HealthPermissionRequester
    }

    @Volatile
    private var factory: Factory? = null

    @JvmStatic
    fun register(factory: Factory) {
        this.factory = factory
    }

    /** Registration-completeness check (plan §6 Q1). */
    @JvmStatic
    fun isRegistered(): Boolean = factory != null

    /**
     * The requester for this activity, or null on a flavour without Health Connect. Called
     * from a field initialiser, so registration has to have happened already: it does, in
     * `Applic.onCreate` and again in `Specific.start()`, both of which precede every
     * component.
     */
    @JvmStatic
    fun create(activity: MainActivity): HealthPermissionRequester? = factory?.create(activity)
}
