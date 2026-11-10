package tk.glucodata.ui

import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap

/**
 * The lines of a forecast comparison: what happened (solid), the standard forecast (dashed,
 * `primary`) and the adaptive one (dotted, `tertiary`).
 *
 * The pattern carries the difference, not only the colour: with dynamic colour from a muted
 * wallpaper, `primary` and `tertiary` can be close tones, and the lines must still read apart.
 */
internal enum class ForecastLineStyle {
    SOLID,
    DASHED,
    DOTTED;

    fun pathEffect(density: Float): PathEffect? = when (this) {
        SOLID -> null
        DASHED -> PathEffect.dashPathEffect(floatArrayOf(10f * density, 6f * density))
        // A near-zero dash with a round cap draws a dot per interval.
        DOTTED -> PathEffect.dashPathEffect(floatArrayOf(0.01f, 6f * density))
    }

    val cap: StrokeCap get() = if (this == DOTTED) StrokeCap.Round else StrokeCap.Butt
}
