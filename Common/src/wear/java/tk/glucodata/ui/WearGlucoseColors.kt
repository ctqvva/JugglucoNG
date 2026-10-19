package tk.glucodata.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import tk.glucodata.GlucoseRangeColors
import tk.glucodata.GlucoseValueTone
import tk.glucodata.Natives

/**
 * The watch's glucose colours, resolved exactly as the phone resolves them.
 *
 * The watch used to paint every reading with the five-band AGP colour — the
 * in-range hue included — so a whole screen came out green and carried no
 * information, and none of it followed the palette the user had chosen on the
 * phone. Both problems have the same fix: run the phone's own rules
 * ([GlucoseValueTone]) over the palette [tk.glucodata.GlucoseColorSync] mirrors
 * from the phone.
 *
 * The phone's scheme is:
 *  - the number stays neutral unless "colour values by range" is on, and then
 *    takes the palette's three-tier traffic colour;
 *  - the hero container carries a faint five-band tint when out of range;
 *  - the chart traces by band.
 *
 * Wear renders on a dark background throughout, so the dark variants apply.
 */
object WearGlucoseColors {
    private const val DARK = true

    /** Thresholds as native holds them, already in the display unit. */
    private fun targetLow(): Float = runCatching { Natives.targetlow() }.getOrDefault(Float.NaN)
    private fun targetHigh(): Float = runCatching { Natives.targethigh() }.getOrDefault(Float.NaN)
    private fun veryLow(): Float = runCatching { Natives.alarmverylow() }.getOrDefault(Float.NaN)
    private fun veryHigh(): Float = runCatching { Natives.alarmveryhigh() }.getOrDefault(Float.NaN)

    /** True when the phone has value range colouring switched on. */
    fun valueRangeColorsEnabled(): Boolean = GlucoseValueTone.valueRangeColorsEnabled()

    /**
     * Colour for a glucose number: [neutral] unless the user asked for range
     * colours, matching the phone's hero and reading rows.
     */
    fun valueColor(value: Float?, isMmol: Boolean, neutral: Color): Color {
        if (!valueRangeColorsEnabled()) return neutral
        return Color(
            GlucoseValueTone.valueColorArgb(
                value = value,
                isDark = DARK,
                isMmol = isMmol,
                targetLow = targetLow(),
                targetHigh = targetHigh(),
                veryLowThreshold = veryLow(),
                veryHighThreshold = veryHigh(),
                fallbackArgb = neutral.toArgb(),
                enabled = true,
            )
        )
    }

    /**
     * The faint band tint the hero blends over its background when the reading
     * is out of range, or null when it is in range and should stay neutral.
     */
    fun heroTint(value: Float?, isMmol: Boolean, isFresh: Boolean): Pair<Color, Float>? =
        GlucoseValueTone.heroTone(
            value = value,
            isDark = DARK,
            isMmol = isMmol,
            targetLow = targetLow(),
            targetHigh = targetHigh(),
            veryLowThreshold = veryLow(),
            veryHighThreshold = veryHigh(),
            isFreshData = isFresh,
        )?.let { tone -> Color(tone.tintArgb) to tone.blendFraction }

    /**
     * The band colour for an out-of-range value, or null while it is in range.
     * For surfaces that colour unconditionally — an alarm, say — and supply
     * their own neutral tone.
     */
    fun bandColorOrNull(value: Float?, isMmol: Boolean): Color? =
        heroTint(value, isMmol, isFresh = true)?.first

    /**
     * Band colour for chart traces, which the phone colours by range whatever
     * the value-colour setting says. [neutral] covers the in-range stretch.
     */
    fun bandColor(value: Float, isMmol: Boolean, neutral: Color): Color = Color(
        runCatching {
            GlucoseRangeColors.colorForValue(
                value, targetLow(), targetHigh(), veryLow(), veryHigh(),
                neutral.toArgb(), DARK, isMmol,
            )
        }.getOrDefault(neutral.toArgb())
    )
}
