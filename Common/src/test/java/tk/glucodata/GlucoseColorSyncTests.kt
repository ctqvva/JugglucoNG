package tk.glucodata

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import tk.glucodata.GlucoseRangeColors.Band
import tk.glucodata.GlucoseRangeColors.Palette

/**
 * The wire format the watch mirrors the phone's palette with. The interesting
 * property is that "no override for this band" survives the trip: encoding an
 * absent override as 0 would paint the watch transparent black.
 */
class GlucoseColorSyncTests {

    @Before
    @After
    fun reset() {
        GlucoseRangeColors.setChangeListener(null)
        GlucoseRangeColors.setPalette(Palette.MUTED)
        GlucoseRangeColors.clearOverrides()
    }

    private fun scheme(
        palette: String = Palette.VIBRANT.name,
        overrides: List<Int?> = List(Band.values().size) { null },
        targetBackground: Int? = null,
        valueRangeColors: Boolean = false,
    ) = GlucoseColorSync.Scheme(palette, overrides, targetBackground, valueRangeColors)

    @Test
    fun roundTripsAPlainPreset() {
        val decoded = GlucoseColorSync.decodeScheme(GlucoseColorSync.encodeScheme(scheme()))
        assertEquals(scheme(), decoded)
    }

    @Test
    fun roundTripsOverridesAndKeepsAbsentOnesAbsent() {
        val overrides = listOf(0xFF102030.toInt(), null, null, 0xFF405060.toInt(), null)
        val original = scheme(
            palette = Palette.CUSTOM.name,
            overrides = overrides,
            targetBackground = 0xFF708090.toInt(),
            valueRangeColors = true,
        )
        val decoded = GlucoseColorSync.decodeScheme(GlucoseColorSync.encodeScheme(original))!!

        assertEquals(original, decoded)
        assertEquals(0xFF102030.toInt(), decoded.overrides[Band.VERY_LOW.ordinal])
        assertNull(decoded.overrides[Band.LOW.ordinal])
        assertNull(decoded.overrides[Band.IN_RANGE.ordinal])
        assertEquals(0xFF405060.toInt(), decoded.overrides[Band.HIGH.ordinal])
        assertNull(decoded.overrides[Band.VERY_HIGH.ordinal])
    }

    @Test
    fun negativeArgbSurvives() {
        // Every opaque colour is a negative Int; a parser that only took digits
        // would silently drop the user's whole custom palette.
        val original = scheme(overrides = List(Band.values().size) { 0xFFC7655C.toInt() })
        assertEquals(original, GlucoseColorSync.decodeScheme(GlucoseColorSync.encodeScheme(original)))
    }

    @Test
    fun rejectsPayloadsThatAreNotOurs() {
        assertNull(GlucoseColorSync.decodeScheme(null))
        assertNull(GlucoseColorSync.decodeScheme(ByteArray(0)))
        assertNull(GlucoseColorSync.decodeScheme("hello".toByteArray()))
        // No palette line: refuse rather than reset the watch to defaults.
        assertNull(GlucoseColorSync.decodeScheme("value_range_colors=true\n".toByteArray()))
    }

    @Test
    fun unknownFieldsAreIgnoredNotFatal() {
        val payload = GlucoseColorSync.encodeScheme(scheme(valueRangeColors = true))
            .toString(Charsets.UTF_8) + "some_future_key=42\n"
        val decoded = GlucoseColorSync.decodeScheme(payload.toByteArray())!!
        assertTrue(decoded.valueRangeColors)
        assertEquals(Palette.VIBRANT.name, decoded.palette)
    }
}
