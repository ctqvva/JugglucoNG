package tk.glucodata

/**
 * The app's trend estimator, handed to [TrendAccess] once at startup.
 *
 * The estimator itself lives in the mobile source set, which the shared code cannot reference at
 * compile time. Registering an implementation through this interface keeps the call site a plain
 * interface invoke: R8 renames both sides consistently, so it survives minification. Looking the
 * estimator up by name at runtime does not -- see [TrendAccess].
 */
fun interface TrendVelocityProvider {
    /**
     * @param points glucose history, either order; the estimator decides its own window.
     * @return trend in mg/dL per minute, or a non-finite value when it cannot say.
     */
    fun velocity(points: List<GlucosePoint>, useRaw: Boolean, isMmol: Boolean): Float
}
