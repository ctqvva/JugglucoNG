package tk.glucodata.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import tk.glucodata.ui.overlay.OverlayPosition
import tk.glucodata.ui.overlay.OverlayScreenArea
import tk.glucodata.ui.overlay.clampOverlayPixels
import tk.glucodata.ui.overlay.overlayPixels
import tk.glucodata.ui.overlay.overlayPosition

/**
 * #685: the freeform overlay window has no layout limits, so a drag or a rotation
 * could leave it off-screen. Placement now always resolves on the display and is
 * stored screen-relative. The status bar is part of the display: the pill may sit there.
 */
class FloatingOverlayPlacementTests {
    private val portrait = OverlayScreenArea(left = 0, top = 0, right = 1080, bottom = 2400)
    private val landscape = OverlayScreenArea(left = 0, top = 0, right = 2400, bottom = 1080)
    private val width = 300
    private val height = 120

    @Test
    fun overlayMaySitOverTheStatusBar() {
        assertEquals(400 to 20, clampOverlayPixels(400, 20, portrait, width, height))
        assertEquals(400 to 0, clampOverlayPixels(400, -40, portrait, width, height))
    }

    @Test
    fun draggingPastAnyEdgeKeepsTheWholeOverlayOnScreen() {
        assertEquals(780 to 2280, clampOverlayPixels(5000, 5000, portrait, width, height))
        assertEquals(0 to 0, clampOverlayPixels(-5000, -5000, portrait, width, height))
    }

    @Test
    fun legacyPixelsKeepTheirSpot() {
        val migrated = overlayPosition(390, 20, portrait, width, height, OverlayPosition.CENTRE)
        assertEquals(390 to 20, overlayPixels(migrated, portrait, width, height))
    }

    @Test
    fun bottomRightStaysBottomRightAcrossRotation() {
        val position = overlayPosition(780, 2280, portrait, width, height, OverlayPosition.CENTRE)
        // In pixels this would be (780, 2280): far below a 1080 px tall landscape screen.
        assertEquals(2100 to 960, overlayPixels(position, landscape, width, height))
        val back = overlayPosition(2100, 960, landscape, width, height, position)
        assertEquals(780 to 2280, overlayPixels(back, portrait, width, height))
    }

    @Test
    fun resetLandsInTheMiddle() {
        assertEquals(390 to 1140, overlayPixels(OverlayPosition.CENTRE, portrait, width, height))
    }

    @Test
    fun growingPillStaysDockedToItsEdge() {
        val rightEdge = OverlayPosition(1f, 0.5f)
        val (narrowX, _) = overlayPixels(rightEdge, portrait, width, height)
        val (wideX, _) = overlayPixels(rightEdge, portrait, width + 200, height)
        assertEquals(1080, narrowX + width)
        assertEquals(1080, wideX + width + 200)
    }

    @Test
    fun overlayLargerThanTheAreaPinsToItsStart() {
        assertEquals(0 to 0, overlayPixels(OverlayPosition(1f, 1f), portrait, 1500, 3000))
        val kept = overlayPosition(0, 0, portrait, 1500, height, OverlayPosition(0.7f, 0.2f))
        assertEquals(0.7f, kept.x, 0f)
    }

    @Test
    fun corruptStoredValuesStayOnScreen() {
        assertEquals(780 to 2280, overlayPixels(OverlayPosition(7f, 9f), portrait, width, height))
        assertEquals(0 to 0, overlayPixels(OverlayPosition(Float.NaN, -3f), portrait, width, height))
    }
}
