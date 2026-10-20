package tk.glucodata.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import tk.glucodata.ui.overlay.OverlayPosition
import tk.glucodata.ui.overlay.OverlaySafeArea
import tk.glucodata.ui.overlay.clampOverlayPixels
import tk.glucodata.ui.overlay.overlayPixels
import tk.glucodata.ui.overlay.overlayPosition

/**
 * #685: the freeform overlay could be dragged, or rotated, under the status bar,
 * where the bar takes every touch and the overlay can never be grabbed again.
 * Placement now always resolves inside the safe area and is stored screen-relative.
 */
class FloatingOverlayPlacementTests {
    // 1080x2400 portrait: 110 px status bar, 66 px gesture bar.
    private val portrait = OverlaySafeArea(left = 0, top = 110, right = 1080, bottom = 2334)
    // Same phone turned: cutout and status bar on the left, gesture bar at the bottom.
    private val landscape = OverlaySafeArea(left = 110, top = 66, right = 2400, bottom = 1014)
    private val width = 300
    private val height = 120

    @Test
    fun draggingUnderTheStatusBarStopsBelowIt() {
        val (x, y) = clampOverlayPixels(400, -40, portrait, width, height)
        assertEquals(400, x)
        assertEquals(110, y)
    }

    @Test
    fun draggingPastAnyEdgeKeepsTheWholeOverlayOnScreen() {
        assertEquals(780 to 2214, clampOverlayPixels(5000, 5000, portrait, width, height))
        assertEquals(0 to 110, clampOverlayPixels(-5000, -5000, portrait, width, height))
    }

    @Test
    fun legacyPixelsUnderTheStatusBarMigrateToJustBelowIt() {
        // A pre-#685 position in the reporter's state: parked under the bar.
        val migrated = overlayPosition(390, 20, portrait, width, height, OverlayPosition.DEFAULT)
        assertEquals(0.5f, migrated.x, 0.001f)
        assertEquals(0f, migrated.y, 0.001f)
        assertEquals(390 to 110, overlayPixels(migrated, portrait, width, height))
    }

    @Test
    fun bottomRightStaysBottomRightAcrossRotation() {
        val position = overlayPosition(780, 2214, portrait, width, height, OverlayPosition.DEFAULT)
        // In pixels this would be (780, 2214): far below a 1080 px tall landscape screen.
        assertEquals(2100 to 894, overlayPixels(position, landscape, width, height))
        val back = overlayPosition(2100, 894, landscape, width, height, position)
        assertEquals(780 to 2214, overlayPixels(back, portrait, width, height))
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
        val (x, y) = overlayPixels(OverlayPosition(1f, 1f), portrait, 1500, 3000)
        assertEquals(0, x)
        assertEquals(110, y)
        val kept = overlayPosition(0, 110, portrait, 1500, height, OverlayPosition(0.7f, 0.2f))
        assertEquals(0.7f, kept.x, 0f)
    }

    @Test
    fun corruptStoredValuesStayReachable() {
        assertEquals(780 to 2214, overlayPixels(OverlayPosition(7f, 9f), portrait, width, height))
        assertEquals(0 to 110, overlayPixels(OverlayPosition(Float.NaN, -3f), portrait, width, height))
    }
}
