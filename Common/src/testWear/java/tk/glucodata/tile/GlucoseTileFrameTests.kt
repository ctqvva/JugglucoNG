package tk.glucodata.tile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

class GlucoseTileFrameTests {
    @Test fun pictureFitsInsideARoundScreen() {
        val density = 2f
        val screenDp = 216
        val size = GlucoseTileFrame.chartSize(screenDp, screenDp, density)
        val diagonalDp = hypot(size.widthPx / density, size.heightPx / density)
        assertTrue("corners must stay on the dial", diagonalDp < screenDp)
    }

    @Test fun usesTheShorterSideOfANonSquareScreen() {
        val wide = GlucoseTileFrame.chartSize(300, 200, 2f)
        assertEquals(GlucoseTileFrame.chartSize(200, 200, 2f), wide)
    }

    @Test fun capsWidthOnVeryDenseScreens() {
        val size = GlucoseTileFrame.chartSize(450, 450, 4f)
        assertEquals(640, size.widthPx)
    }

    @Test fun fallsBackToAStockWatchWhenTheRequestCarriesNoDevice() {
        val size = GlucoseTileFrame.chartSize(0, 0, 0f)
        assertTrue(size.widthPx > 0 && size.heightPx > 0)
        assertEquals(GlucoseTileFrame.chartSize(192, 192, 2f), size)
    }

    @Test fun dpIsPixelsOverDensity() {
        assertEquals(100f, GlucoseTileFrame.dp(200, 2f), 0f)
    }

    private val size = GlucoseTileFrame.Size(300, 180)
    private fun version(
        text: String? = "5.6", time: Long = 1_000L, rate: Float = 0f,
        mmol: Boolean = true, now: Long = 120_000L, size: GlucoseTileFrame.Size = this.size,
    ) = GlucoseTileFrame.version(size, text, time, rate, mmol, now)

    @Test fun versionIsStableWhileNothingChanges() {
        assertEquals(version(now = 120_000L), version(now = 179_999L))
    }

    @Test fun versionChangesWithEverythingThePictureShows() {
        val base = version()
        assertNotEquals(base, version(text = "5.7"))
        assertNotEquals(base, version(time = 2_000L))
        assertNotEquals(base, version(rate = 1f))
        assertNotEquals(base, version(mmol = false))
        assertNotEquals(base, version(size = GlucoseTileFrame.Size(320, 192)))
        assertNotEquals("the trace slides with the clock", base, version(now = 180_000L))
    }

    @Test fun versionToleratesAMissingReading() {
        assertNotEquals(version(), version(text = null, time = 0L, rate = Float.NaN))
    }
}
