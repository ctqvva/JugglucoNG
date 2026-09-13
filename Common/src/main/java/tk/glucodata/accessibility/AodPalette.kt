package tk.glucodata.accessibility

/**
 * Decides whether the AOD overlay draws its light-on-dark palette.
 *
 * Over a genuinely-off ambient panel the answer is always "light": the screen is black.
 * On an interactive lock screen the overlay sits on the wallpaper instead, so it follows
 * the same signal the system clock does — Android's own recommendation for that wallpaper,
 * exposed as [android.app.WallpaperColors.HINT_SUPPORTS_DARK_TEXT]. Devices that predate
 * the hint (API < 31) only give us the wallpaper's primary colour, so we fall back to its
 * luminance.
 *
 * Kept free of Android types so the decision itself is unit-testable.
 */
object AodPalette {
    /** Below this the wallpaper is dark enough to keep the light palette. */
    private const val DARK_TEXT_LUMINANCE_THRESHOLD = 0.5f

    /**
     * @param screenInteractive false while the panel is in ambient mode.
     * @param supportsDarkText the wallpaper's own hint, or null when the platform doesn't report one.
     * @param primaryColor the lock wallpaper's primary colour, or null when unavailable.
     */
    @JvmStatic
    fun usesLightPalette(
        screenInteractive: Boolean,
        supportsDarkText: Boolean?,
        primaryColor: Int?,
    ): Boolean {
        if (!screenInteractive) return true
        if (supportsDarkText != null) return !supportsDarkText
        if (primaryColor != null) return relativeLuminance(primaryColor) < DARK_TEXT_LUMINANCE_THRESHOLD
        // No wallpaper signal at all: white on an unknown background is the safer miss,
        // because it stays legible over the dark scrim the keyguard draws behind its own text.
        return true
    }

    /** WCAG relative luminance of an opaque ARGB colour, 0f (black) to 1f (white). */
    @JvmStatic
    fun relativeLuminance(color: Int): Float {
        val r = channel((color shr 16) and 0xFF)
        val g = channel((color shr 8) and 0xFF)
        val b = channel(color and 0xFF)
        return 0.2126f * r + 0.7152f * g + 0.0722f * b
    }

    private fun channel(value: Int): Float {
        val c = value / 255f
        return if (c < 0.03928f) c / 12.92f else Math.pow(((c + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()
    }
}
