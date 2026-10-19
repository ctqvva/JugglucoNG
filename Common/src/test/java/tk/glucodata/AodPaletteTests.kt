package tk.glucodata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tk.glucodata.accessibility.AodPalette

class AodPaletteTests {

    @Test
    fun ambientPanelAlwaysDrawsLight() {
        // The panel is black in ambient mode, so no wallpaper signal may override it.
        assertTrue(AodPalette.usesLightPalette(false, supportsDarkText = true, primaryColor = 0xFFFFFFFF.toInt()))
        assertTrue(AodPalette.usesLightPalette(false, supportsDarkText = null, primaryColor = null))
    }

    @Test
    fun lockscreenFollowsTheWallpaperHint() {
        // HINT_SUPPORTS_DARK_TEXT means the wallpaper wants dark foreground, so we stop drawing light.
        assertEquals(false, AodPalette.usesLightPalette(true, supportsDarkText = true, primaryColor = null))
        assertEquals(true, AodPalette.usesLightPalette(true, supportsDarkText = false, primaryColor = null))
    }

    @Test
    fun theHintWinsOverTheWallpaperColour() {
        // A dark primary colour must not override an explicit dark-text recommendation.
        assertEquals(
            false,
            AodPalette.usesLightPalette(true, supportsDarkText = true, primaryColor = 0xFF101010.toInt())
        )
    }

    @Test
    fun withoutAHintTheWallpaperLuminanceDecides() {
        // Pre-31 devices report no hint, so fall back to the wallpaper's primary colour.
        assertEquals(
            false,
            AodPalette.usesLightPalette(true, supportsDarkText = null, primaryColor = 0xFFF2F2F2.toInt())
        )
        assertEquals(
            true,
            AodPalette.usesLightPalette(true, supportsDarkText = null, primaryColor = 0xFF1A1A1A.toInt())
        )
    }

    @Test
    fun noWallpaperSignalKeepsTheLightPalette() {
        assertTrue(AodPalette.usesLightPalette(true, supportsDarkText = null, primaryColor = null))
    }

    @Test
    fun luminanceSpansBlackToWhite() {
        assertEquals(0f, AodPalette.relativeLuminance(0xFF000000.toInt()), 0.001f)
        assertEquals(1f, AodPalette.relativeLuminance(0xFFFFFFFF.toInt()), 0.001f)
        // Alpha must not leak into the calculation.
        assertEquals(
            AodPalette.relativeLuminance(0xFF808080.toInt()),
            AodPalette.relativeLuminance(0x00808080),
            0.001f
        )
    }
}
