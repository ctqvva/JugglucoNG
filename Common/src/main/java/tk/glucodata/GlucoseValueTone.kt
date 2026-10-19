package tk.glucodata

/**
 * The range-driven tones a glucose readout paints, in plain ARGB.
 *
 * The phone worked these out inline in its Compose layer, so the watch could
 * only approximate them: it tinted every reading with the five-band colour,
 * in-range included, which the phone never does. Both surfaces call this now,
 * so a palette or threshold change lands identically on each.
 *
 * Two distinct schemes live here, matching [GlucoseRangeColors]:
 *  - [heroTone] — the five AGP bands, used as a faint container tint.
 *  - [valueColorArgb] — the three-tier traffic colour for the number itself,
 *    applied only when the user has switched value range colours on.
 */
object GlucoseValueTone {
    enum class Band { VERY_LOW, LOW, HIGH, VERY_HIGH }

    /** A band colour plus how strongly it should blend into a container. */
    data class Tone(val band: Band, val tintArgb: Int, val blendFraction: Float)

    /** Shared preference key for "colour the value by range" (GDH-style). */
    const val PREF_VALUE_RANGE_COLORS = "glucose_value_range_colors_enabled"

    private fun lerp(start: Float, end: Float, fraction: Float): Float =
        start + (end - start) * fraction.coerceIn(0f, 1f)

    private fun bandColor(band: Band, isDark: Boolean): Int = when (band) {
        Band.VERY_LOW -> GlucoseRangeColors.veryLow(isDark)
        Band.LOW -> GlucoseRangeColors.low(isDark)
        Band.HIGH -> GlucoseRangeColors.high(isDark)
        Band.VERY_HIGH -> GlucoseRangeColors.veryHigh(isDark)
    }

    /**
     * The band tint for [value], or null when it is in range (or unusable) and
     * the container should stay neutral.
     */
    @JvmStatic
    @JvmOverloads
    fun heroTone(
        value: Float?,
        isDark: Boolean,
        isMmol: Boolean,
        targetLow: Float,
        targetHigh: Float,
        veryLowThreshold: Float,
        veryHighThreshold: Float,
        isFreshData: Boolean = true,
    ): Tone? {
        val currentValue = value?.takeIf { it.isFinite() && it > 0f } ?: return null
        if (!isFreshData) return null

        val low = targetLow.takeIf { it.isFinite() && it > 0f } ?: GlucoseRangeColors.defaultLow(isMmol)
        val highCandidate = targetHigh.takeIf { it.isFinite() && it > low }
            ?: GlucoseRangeColors.defaultHigh(isMmol)
        val high = highCandidate.coerceAtLeast(low + 0.1f)
        val veryLow = (veryLowThreshold.takeIf { it.isFinite() && it > 0f }
            ?: GlucoseRangeColors.defaultVeryLow(isMmol)).coerceAtMost(low - 0.1f)
        val veryHigh = (veryHighThreshold.takeIf { it.isFinite() && it > 0f }
            ?: GlucoseRangeColors.defaultVeryHigh(isMmol)).coerceAtLeast(high + 0.1f)

        fun tone(band: Band, severity: Float) = Tone(
            band = band,
            tintArgb = bandColor(band, isDark),
            blendFraction = lerp(0.12f, if (isDark) 0.24f else 0.22f, severity.coerceIn(0f, 1f)),
        )

        return when {
            currentValue <= veryLow -> tone(Band.VERY_LOW, 1f)
            currentValue < low ->
                tone(Band.LOW, (low - currentValue) / (low - veryLow).coerceAtLeast(0.1f))
            currentValue >= veryHigh -> tone(Band.VERY_HIGH, 1f)
            currentValue > high ->
                tone(Band.HIGH, (currentValue - high) / (veryHigh - high).coerceAtLeast(0.1f))
            else -> null
        }
    }

    /** True when the user asked for GDH-style traffic colouring of the value. */
    @JvmStatic
    fun valueRangeColorsEnabled(): Boolean = try {
        Applic.app
            ?.getSharedPreferences(GlucoseRangeColors.PREF_FILE, android.content.Context.MODE_PRIVATE)
            ?.getBoolean(PREF_VALUE_RANGE_COLORS, false) ?: false
    } catch (_: Throwable) {
        false
    }

    /**
     * The colour for the number itself: [fallbackArgb] unless the user enabled
     * value range colours, in which case the active palette's traffic tier.
     */
    @JvmStatic
    @JvmOverloads
    fun valueColorArgb(
        value: Float?,
        isDark: Boolean,
        isMmol: Boolean,
        targetLow: Float,
        targetHigh: Float,
        veryLowThreshold: Float,
        veryHighThreshold: Float,
        fallbackArgb: Int,
        enabled: Boolean = valueRangeColorsEnabled(),
    ): Int {
        if (!enabled) return fallbackArgb
        return GlucoseRangeColors.trafficColorForValue(
            value ?: Float.NaN,
            targetLow,
            targetHigh,
            veryLowThreshold,
            veryHighThreshold,
            isDark,
            isMmol,
            fallbackArgb,
        )
    }
}
