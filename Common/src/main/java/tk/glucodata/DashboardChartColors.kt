package tk.glucodata

/**
 * Runtime copy of the Compose chart theme colors.
 *
 * Notification RemoteViews are rendered from Java outside composition, so they
 * cannot read MaterialTheme directly. JugglucoTheme updates this bridge whenever
 * Compose applies a color scheme; notification chart drawing then uses the same
 * primary/onSurfaceVariant colors as DashboardChart.
 */
object DashboardChartColors {
    private const val FALLBACK_PRIMARY_DARK = 0xFF90CAF9.toInt()
    private const val FALLBACK_PRIMARY_LIGHT = 0xFF1565C0.toInt()
    private const val FALLBACK_ON_SURFACE_VARIANT_DARK = 0xFFCAC4D0.toInt()
    private const val FALLBACK_ON_SURFACE_VARIANT_LIGHT = 0xFF49454F.toInt()

    @Volatile private var darkPrimary = FALLBACK_PRIMARY_DARK
    @Volatile private var lightPrimary = FALLBACK_PRIMARY_LIGHT
    @Volatile private var darkOnSurfaceVariant = FALLBACK_ON_SURFACE_VARIANT_DARK
    @Volatile private var lightOnSurfaceVariant = FALLBACK_ON_SURFACE_VARIANT_LIGHT

    @JvmStatic
    fun update(darkTheme: Boolean, primary: Int, onSurfaceVariant: Int) {
        if (darkTheme) {
            darkPrimary = primary
            darkOnSurfaceVariant = onSurfaceVariant
        } else {
            lightPrimary = primary
            lightOnSurfaceVariant = onSurfaceVariant
        }
    }

    @JvmStatic
    fun primary(darkTheme: Boolean): Int =
        if (darkTheme) darkPrimary else lightPrimary

    @JvmStatic
    fun onSurfaceVariant(darkTheme: Boolean): Int =
        if (darkTheme) darkOnSurfaceVariant else lightOnSurfaceVariant
}
