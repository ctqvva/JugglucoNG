package tk.glucodata

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import tk.glucodata.GlucoseRangeColors.Band
import tk.glucodata.GlucoseRangeColors.Palette

/**
 * The band tint shared by the phone hero and the watch. The watch used to run
 * its own approximation and coloured in-range readings too, so the rule that
 * matters most here is that in range means no tint at all.
 */
class GlucoseValueToneTests {

    @Before
    @After
    fun reset() {
        GlucoseRangeColors.setChangeListener(null)
        GlucoseRangeColors.setPalette(Palette.MUTED)
        GlucoseRangeColors.clearOverrides()
    }

    private fun tone(value: Float, isMmol: Boolean = false) = GlucoseValueTone.heroTone(
        value = value,
        isDark = true,
        isMmol = isMmol,
        targetLow = 70f,
        targetHigh = 180f,
        veryLowThreshold = 54f,
        veryHighThreshold = 250f,
    )

    @Test
    fun inRangeCarriesNoTint() {
        assertNull(tone(70f))
        assertNull(tone(120f))
        assertNull(tone(180f))
    }

    @Test
    fun unusableValuesCarryNoTint() {
        assertNull(tone(0f))
        assertNull(tone(-5f))
        assertNull(tone(Float.NaN))
        assertNull(GlucoseValueTone.heroTone(null, true, false, 70f, 180f, 54f, 250f))
    }

    @Test
    fun staleDataCarriesNoTint() {
        assertNull(
            GlucoseValueTone.heroTone(
                value = 300f, isDark = true, isMmol = false,
                targetLow = 70f, targetHigh = 180f,
                veryLowThreshold = 54f, veryHighThreshold = 250f,
                isFreshData = false,
            )
        )
    }

    @Test
    fun bandsMapToTheirSideOfRange() {
        assertEquals(GlucoseValueTone.Band.VERY_LOW, tone(50f)!!.band)
        assertEquals(GlucoseValueTone.Band.LOW, tone(65f)!!.band)
        assertEquals(GlucoseValueTone.Band.HIGH, tone(200f)!!.band)
        assertEquals(GlucoseValueTone.Band.VERY_HIGH, tone(260f)!!.band)
    }

    @Test
    fun tintDeepensAsTheValueGetsWorse() {
        val mild = tone(175.1f) ?: tone(185f)!!
        val severe = tone(249f)!!
        assertTrue(
            "a value near the alarm bound should blend more strongly than one just out of range",
            severe.blendFraction > mild.blendFraction
        )
    }

    @Test
    fun tintFollowsTheActivePalette() {
        val muted = tone(260f)!!.tintArgb
        GlucoseRangeColors.setPalette(Palette.VIBRANT)
        val vibrant = tone(260f)!!.tintArgb
        assertNotEquals(muted, vibrant)
        assertEquals(GlucoseRangeColors.veryHigh(true), vibrant)
    }

    @Test
    fun bandOverrideWins() {
        val custom = 0xFF123456.toInt()
        GlucoseRangeColors.setOverride(Band.HIGH, custom)
        assertEquals(custom, tone(200f)!!.tintArgb)
    }

    @Test
    fun valueColourIsNeutralUntilTheUserAsksForIt() {
        val neutral = 0xFFE3E2DE.toInt()
        assertEquals(
            neutral,
            GlucoseValueTone.valueColorArgb(
                value = 300f, isDark = true, isMmol = false,
                targetLow = 70f, targetHigh = 180f,
                veryLowThreshold = 54f, veryHighThreshold = 250f,
                fallbackArgb = neutral, enabled = false,
            )
        )
        assertEquals(
            GlucoseRangeColors.valueOut(true),
            GlucoseValueTone.valueColorArgb(
                value = 300f, isDark = true, isMmol = false,
                targetLow = 70f, targetHigh = 180f,
                veryLowThreshold = 54f, veryHighThreshold = 250f,
                fallbackArgb = neutral, enabled = true,
            )
        )
    }

    @Test
    fun mmolThresholdsUseTheMmolDefaultsWhenUnset() {
        // 3.0 is the mmol very-low default; with no thresholds supplied the
        // tone must still land below range rather than treat 3.0 as mg/dL.
        val tone = GlucoseValueTone.heroTone(
            value = 2.9f, isDark = true, isMmol = true,
            targetLow = Float.NaN, targetHigh = Float.NaN,
            veryLowThreshold = Float.NaN, veryHighThreshold = Float.NaN,
        )
        assertEquals(GlucoseValueTone.Band.VERY_LOW, tone!!.band)
    }
}
