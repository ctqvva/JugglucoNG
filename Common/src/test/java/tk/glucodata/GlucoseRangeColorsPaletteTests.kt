package tk.glucodata

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import tk.glucodata.GlucoseRangeColors.Band
import tk.glucodata.GlucoseRangeColors.Palette

/**
 * Covers the palette resolution order (override -> active preset -> dark/light)
 * and the regression guarantee that MUTED with no overrides is bit-identical to
 * the historical hard-coded tones.
 */
class GlucoseRangeColorsPaletteTests {

    @Before
    @After
    fun reset() {
        GlucoseRangeColors.setChangeListener(null)
        GlucoseRangeColors.setPalette(Palette.MUTED)
        GlucoseRangeColors.clearOverrides()
    }

    @Test
    fun mutedDefaultsAreBitIdentical() {
        assertEquals(0xFFC7655C.toInt(), GlucoseRangeColors.veryLow(false))
        assertEquals(0xFFC97970.toInt(), GlucoseRangeColors.low(false))
        assertEquals(0xFF4E8A55.toInt(), GlucoseRangeColors.inRange(false))
        assertEquals(0xFFD0A23A.toInt(), GlucoseRangeColors.high(false))
        assertEquals(0xFFC56F33.toInt(), GlucoseRangeColors.veryHigh(false))
        // MUTED is intentionally identical in dark and light.
        assertEquals(GlucoseRangeColors.veryLow(false), GlucoseRangeColors.veryLow(true))
        assertEquals(GlucoseRangeColors.inRange(false), GlucoseRangeColors.inRange(true))
        assertEquals(GlucoseRangeColors.veryHigh(false), GlucoseRangeColors.veryHigh(true))
    }

    @Test
    fun vibrantHasDistinctDarkVariantAndDiffersFromMuted() {
        GlucoseRangeColors.setPalette(Palette.VIBRANT)
        assertNotEquals(GlucoseRangeColors.inRange(false), GlucoseRangeColors.inRange(true))
        assertNotEquals(0xFF4E8A55.toInt(), GlucoseRangeColors.inRange(false))
    }

    @Test
    fun auroraHasDistinctDarkVariantAndTrafficColors() {
        GlucoseRangeColors.setPalette(Palette.AURORA)
        assertEquals(0xFF00897B.toInt(), GlucoseRangeColors.inRange(false))
        assertEquals(0xFF4DB6AC.toInt(), GlucoseRangeColors.inRange(true))
        assertEquals(0xFFF9A825.toInt(), GlucoseRangeColors.valueBorderline(false))
        assertEquals(0xFFF06292.toInt(), GlucoseRangeColors.valueOut(true))
    }

    @Test
    fun gdhLikeMapsThreeTiersToFiveBands() {
        GlucoseRangeColors.setPalette(Palette.GDH_LIKE)
        assertEquals(0xFF00FF00.toInt(), GlucoseRangeColors.inRange(false)) // colorOK
        assertEquals(0xFFFFDC00.toInt(), GlucoseRangeColors.low(false))     // colorOutOfRange
        assertEquals(0xFFFFDC00.toInt(), GlucoseRangeColors.high(false))
        assertEquals(0xFFFF0000.toInt(), GlucoseRangeColors.veryLow(false)) // colorAlarm
        assertEquals(0xFFFF0000.toInt(), GlucoseRangeColors.veryHigh(false))
    }

    @Test
    fun overrideWinsOverPresetAndAppliesToBothThemes() {
        GlucoseRangeColors.setPalette(Palette.VIBRANT)
        val custom = 0xFF123456.toInt()
        GlucoseRangeColors.setOverride(Band.IN_RANGE, custom)
        assertEquals(custom, GlucoseRangeColors.inRange(false))
        assertEquals(custom, GlucoseRangeColors.inRange(true))
        // Non-overridden bands still follow the active preset.
        assertEquals(
            GlucoseRangeColors.presetColor(Palette.VIBRANT, Band.HIGH, false),
            GlucoseRangeColors.high(false)
        )
    }

    @Test
    fun veryLowOverrideResolvesForBothThemes() {
        val custom = 0xFF9CCC8A.toInt()
        GlucoseRangeColors.setOverride(Band.VERY_LOW, custom)
        assertEquals(custom, GlucoseRangeColors.veryLow(false))
        assertEquals(custom, GlucoseRangeColors.veryLow(true))
    }

    @Test
    fun targetBackgroundFollowsInRangeUntilOverridden() {
        GlucoseRangeColors.setPalette(Palette.AURORA)
        assertEquals(
            GlucoseRangeColors.inRange(false),
            GlucoseRangeColors.targetBackground(false)
        )

        val custom = 0xFF345678.toInt()
        GlucoseRangeColors.setTargetBackgroundOverride(custom)
        assertEquals(custom, GlucoseRangeColors.targetBackground(false))
        assertTrue(GlucoseRangeColors.hasAnyOverride())

        GlucoseRangeColors.clearOverrides()
        assertEquals(
            GlucoseRangeColors.inRange(false),
            GlucoseRangeColors.targetBackground(false)
        )
    }

    @Test
    fun clearingOverrideFallsBackToPreset() {
        GlucoseRangeColors.setPalette(Palette.GDH_LIKE)
        GlucoseRangeColors.setOverride(Band.LOW, 0xFF00FF00.toInt())
        assertEquals(0xFF00FF00.toInt(), GlucoseRangeColors.low(false))
        GlucoseRangeColors.clearOverride(Band.LOW)
        assertEquals(0xFFFFDC00.toInt(), GlucoseRangeColors.low(false))
    }

    @Test
    fun hasAnyOverrideTracksState() {
        assertFalse(GlucoseRangeColors.hasAnyOverride())
        GlucoseRangeColors.setOverride(Band.HIGH, 0xFF010203.toInt())
        assertTrue(GlucoseRangeColors.hasAnyOverride())
        GlucoseRangeColors.clearOverrides()
        assertFalse(GlucoseRangeColors.hasAnyOverride())
    }

    @Test
    fun valueTrafficColorsFollowThePreset() {
        // MUTED keeps the historical traffic tones (regression guarantee).
        GlucoseRangeColors.setPalette(Palette.MUTED)
        assertEquals(0xFF2E7D32.toInt(), GlucoseRangeColors.valueInRange(false))
        assertEquals(0xFFC62828.toInt(), GlucoseRangeColors.valueOut(false))

        // GDH_LIKE drives the value/arrow colouring to GDH's pure primaries.
        GlucoseRangeColors.setPalette(Palette.GDH_LIKE)
        assertEquals(0xFF00FF00.toInt(), GlucoseRangeColors.valueInRange(false))
        assertEquals(0xFFFFDC00.toInt(), GlucoseRangeColors.valueBorderline(false))
        assertEquals(0xFFFF0000.toInt(), GlucoseRangeColors.valueOut(false))

        // Vibrant differs from muted for the value colouring too.
        GlucoseRangeColors.setPalette(Palette.VIBRANT)
        assertNotEquals(0xFF2E7D32.toInt(), GlucoseRangeColors.valueInRange(false))
        assertNotEquals(0xFFC62828.toInt(), GlucoseRangeColors.valueOut(false))
    }

    @Test
    fun inRangeOverrideFlowsToValueInRange() {
        GlucoseRangeColors.setPalette(Palette.MUTED)
        GlucoseRangeColors.setOverride(Band.IN_RANGE, 0xFF0000FF.toInt())
        assertEquals(0xFF0000FF.toInt(), GlucoseRangeColors.valueInRange(false))
        assertEquals(0xFF0000FF.toInt(), GlucoseRangeColors.valueInRange(true))
    }

    @Test
    fun changeListenerFiresOnEveryEffectiveChange() {
        var count = 0
        GlucoseRangeColors.setChangeListener { count++ }
        GlucoseRangeColors.setPalette(Palette.VIBRANT)        // 1
        GlucoseRangeColors.setPalette(Palette.VIBRANT)        // no-op, no fire
        GlucoseRangeColors.setOverride(Band.LOW, 0xFF111111.toInt()) // 2
        GlucoseRangeColors.clearOverrides()                   // 3
        assertEquals(3, count)
    }
}
