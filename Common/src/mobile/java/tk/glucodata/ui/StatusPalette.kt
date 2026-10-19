package tk.glucodata.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color

/**
 * Status colours the M3 scheme has no role for. Failure is `colorScheme.error`; glucose is
 * GlucoseRangeColors. Success and warning come as light/dark pairs, so text drawn in them
 * stays readable on either surface.
 */
object StatusPalette {
    /** Done, connected, stable. */
    @Composable
    @ReadOnlyComposable
    fun success(): Color = if (isAppInDarkTheme()) Color(0xFF81C784) else Color(0xFF1B5E20)

    /** Not wrong, but wait or look: an unstable trend, a reading that needs time. */
    @Composable
    @ReadOnlyComposable
    fun warning(): Color = if (isAppInDarkTheme()) Color(0xFFFFCA28) else Color(0xFF8F5600)

    /** The sensor-life fill while most of the sensor's life is left. */
    val sensorLifeFill = Color(0xFF66BB6A)

    // xDrip's noise scale for the signal-quality glyph, clean to heavy, at 70% opacity.
    val noiseClean = Color(0xB34CAF50)
    val noiseLight = Color(0xB38BC34A)
    val noiseMedium = Color(0xB3FFC107)
    val noiseHeavy = Color(0xB3F44336)
}
