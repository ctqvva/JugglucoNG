package tk.glucodata.ui.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tk.glucodata.service.FloatingGlucoseService.CutoutEdge
import tk.glucodata.ui.overlay.FloatingIsland.Placement

class FloatingIslandTests {
    private val eps = 0.001f

    // A punch hole 64 x 111 px at the top middle of a 1080 px wide display, at 2.625 px per dp:
    // 111 px from the top edge in portrait; in landscape it sits on the left or right edge,
    // 111 px wide (across the edge) and 64 px tall (along it).
    private val density = 2.625f
    private val noCutoutGap = FloatingIsland.NO_CUTOUT_GAP_DP * density
    private val holeCentre = FloatingIsland.cutoutCentre(safeInset = 111, across = 111)

    // The cutout's middle

    @Test
    fun theCutoutsMiddleIsHalfItsExtentBackFromWhereItEnds() {
        assertEquals(55.5f, FloatingIsland.cutoutCentre(111, 111), eps)
        // A hole 32 px in from the edge and 56 px across: it ends at 88, its middle is at 60.
        assertEquals(60f, FloatingIsland.cutoutCentre(88, 56), eps)
    }

    @Test
    fun anUnknownOrInconsistentCutoutHasNoMiddle() {
        assertEquals(0f, FloatingIsland.cutoutCentre(0, 0), 0f)
        assertEquals(0f, FloatingIsland.cutoutCentre(0, 64), 0f)
        assertEquals(0f, FloatingIsland.cutoutCentre(111, 0), 0f)
        // A clipped or malformed inset shallower than half the rect's extent never goes negative.
        assertEquals(0f, FloatingIsland.cutoutCentre(20, 64), 0f)
    }

    @Test
    fun theCutoutsExtentsAlongAndAcrossTheEdgeSwapWithTheOrientation() {
        // Portrait: the rect is 64 wide, 111 tall; docked to the top, the width runs along the edge.
        assertEquals(64, FloatingIsland.alongEdge(CutoutEdge.TOP, 64, 111))
        assertEquals(111, FloatingIsland.acrossEdge(CutoutEdge.TOP, 64, 111))
        assertEquals(64, FloatingIsland.alongEdge(CutoutEdge.BOTTOM, 64, 111))
        assertEquals(111, FloatingIsland.acrossEdge(CutoutEdge.BOTTOM, 64, 111))
        assertEquals(64, FloatingIsland.alongEdge(CutoutEdge.NONE, 64, 111))
        // Landscape: 111 wide, 64 tall; on the left or right the height runs along the edge.
        // The label gap is 64 px there, not the 111 px of the width.
        assertEquals(64, FloatingIsland.alongEdge(CutoutEdge.LEFT, 111, 64))
        assertEquals(111, FloatingIsland.acrossEdge(CutoutEdge.LEFT, 111, 64))
        assertEquals(64, FloatingIsland.alongEdge(CutoutEdge.RIGHT, 111, 64))
        assertEquals(111, FloatingIsland.acrossEdge(CutoutEdge.RIGHT, 111, 64))
    }

    // The gap from the screen edge

    @Test
    fun anUprightPillOfTheDevicesHeightSitsAboutEightPixelsFromTheTop() {
        // Portrait: the pill is 96 px tall.
        assertEquals(7.5f, FloatingIsland.edgeGap(holeCentre, 0f, 96f, noCutoutGap), eps)
    }

    @Test
    fun aPillTooThickToCentreSitsAgainstTheEdge() {
        // Landscape: the pill is 118 px wide, 55.5 - 59 is below 0.
        assertEquals(0f, FloatingIsland.edgeGap(holeCentre, 0f, 118f, noCutoutGap), 0f)
        // Thicker than twice the distance to the middle, whatever the numbers.
        assertEquals(0f, FloatingIsland.edgeGap(30f, 0f, 100f, noCutoutGap), 0f)
        assertEquals(0f, FloatingIsland.edgeGap(30f, 0f, 60f, noCutoutGap), 0f)
        assertTrue(FloatingIsland.edgeGap(30f, 0f, 59f, noCutoutGap) > 0f)
    }

    @Test
    fun theGapFollowsThePillsThickness() {
        // A larger font, a digit more in landscape: the same cutout, another gap.
        val thin = FloatingIsland.edgeGap(holeCentre, 0f, 60f, noCutoutGap)
        val thick = FloatingIsland.edgeGap(holeCentre, 0f, 80f, noCutoutGap)
        assertEquals(25.5f, thin, eps)
        assertEquals(15.5f, thick, eps)
        // The pill's middle is the cutout's middle either way.
        assertEquals(holeCentre, thin + 60f / 2f, eps)
        assertEquals(holeCentre, thick + 80f / 2f, eps)
    }

    @Test
    fun theNudgeMovesThePillAwayFromTheEdgeOrTowardIt() {
        assertEquals(17.5f, FloatingIsland.edgeGap(holeCentre, 10f, 96f, noCutoutGap), eps)
        assertEquals(2.5f, FloatingIsland.edgeGap(holeCentre, -5f, 96f, noCutoutGap), eps)
        // Never past the edge.
        assertEquals(0f, FloatingIsland.edgeGap(holeCentre, -10f, 96f, noCutoutGap), 0f)
        // In landscape it has to make up the 3.5 px before the pill leaves the edge.
        assertEquals(0f, FloatingIsland.edgeGap(holeCentre, 3f, 118f, noCutoutGap), 0f)
        assertEquals(6.5f, FloatingIsland.edgeGap(holeCentre, 10f, 118f, noCutoutGap), eps)
    }

    @Test
    fun withoutACutoutTheGapIsTheFixedGapPlusTheNudge() {
        assertEquals(8f * density, FloatingIsland.edgeGap(0f, 0f, 96f, noCutoutGap), eps)
        // Whatever the pill's thickness.
        assertEquals(8f * density, FloatingIsland.edgeGap(0f, 0f, 400f, noCutoutGap), eps)
        assertEquals(8f * density + 5f, FloatingIsland.edgeGap(0f, 5f, 96f, noCutoutGap), eps)
        assertEquals(0f, FloatingIsland.edgeGap(0f, -8f * density - 1f, 96f, noCutoutGap), 0f)
    }

    // The window

    @Test
    fun landscapeGapStaysOverTheCameraWithABottomNavigationBar() {
        // Pixel 8 Pro display and camera bounds rotated to place the camera on the right.
        val displayHeight = 1344
        val navigationBarHeight = 72
        val cutoutTop = 616
        val cutoutBottom = 726
        val gap = cutoutBottom - cutoutTop
        val windowHeight = 280
        val gapStartInWindow = (windowHeight - gap) / 2
        val anchor = FloatingIsland.CutoutAnchor(centre = 671f, displayExtent = displayHeight)

        val insetGapStart = (displayHeight - navigationBarHeight - windowHeight) / 2 + gapStartInWindow
        assertTrue(insetGapStart + gap < cutoutBottom)

        val gapStart = (displayHeight - windowHeight) / 2 + anchor.windowOffset(windowHeight) + gapStartInWindow
        assertEquals(cutoutTop, gapStart)
        assertEquals(cutoutBottom, gapStart + gap)
    }

    @Test
    fun alongEdgeOffsetFollowsTheCutoutAndKeepsTheWindowOnScreen() {
        val displayWidth = 1080
        assertEquals(-70, FloatingIsland.CutoutAnchor(470f, displayWidth).windowOffset(300))
        assertEquals(72, FloatingIsland.CutoutAnchor(611.5f, displayWidth).windowOffset(301))
        assertEquals(-390, FloatingIsland.CutoutAnchor(50f, displayWidth).windowOffset(300))
        val nearEnd = FloatingIsland.CutoutAnchor(1030f, displayWidth)
        assertEquals(390, nearEnd.windowOffset(300))
        assertEquals(290, nearEnd.windowOffset(500))
    }

    @Test
    fun thePillsThicknessIsItsWidthBesideTheLeftOrRightEdgeAndItsHeightElse() {
        assertEquals(96, FloatingIsland.thickness(CutoutEdge.TOP, 300, 96))
        assertEquals(96, FloatingIsland.thickness(CutoutEdge.BOTTOM, 300, 96))
        assertEquals(96, FloatingIsland.thickness(CutoutEdge.NONE, 300, 96))
        assertEquals(118, FloatingIsland.thickness(CutoutEdge.LEFT, 118, 300))
        assertEquals(118, FloatingIsland.thickness(CutoutEdge.RIGHT, 118, 300))
    }

    @Test
    fun theGapIsOnTheScreenEdgeSideOfThePill() {
        assertEquals(Placement(300, 104, 0, 8), FloatingIsland.place(CutoutEdge.TOP, 300, 96, 8))
        assertEquals(Placement(300, 104, 0, 0), FloatingIsland.place(CutoutEdge.BOTTOM, 300, 96, 8))
        assertEquals(Placement(126, 300, 8, 0), FloatingIsland.place(CutoutEdge.LEFT, 118, 300, 8))
        assertEquals(Placement(126, 300, 0, 0), FloatingIsland.place(CutoutEdge.RIGHT, 118, 300, 8))
        // No cutout known yet: the top, as the pill docks to it by default.
        assertEquals(Placement(300, 104, 0, 8), FloatingIsland.place(CutoutEdge.NONE, 300, 96, 8))
    }

    @Test
    fun leftAndRightAreMirrorImages() {
        for (gap in listOf(0, 8, 40)) {
            val left = FloatingIsland.place(CutoutEdge.LEFT, 118, 300, gap)
            val right = FloatingIsland.place(CutoutEdge.RIGHT, 118, 300, gap)
            assertEquals(left.width, right.width)
            assertEquals(left.height, right.height)
            // The pill is the same distance from its own screen edge in both.
            assertEquals(gap, left.pillX)
            assertEquals(gap, right.width - (right.pillX + 118))
        }
    }

    @Test
    fun topAndBottomAreMirrorImages() {
        for (gap in listOf(0, 8, 40)) {
            val top = FloatingIsland.place(CutoutEdge.TOP, 300, 96, gap)
            val bottom = FloatingIsland.place(CutoutEdge.BOTTOM, 300, 96, gap)
            assertEquals(top.width, bottom.width)
            assertEquals(top.height, bottom.height)
            assertEquals(gap, top.pillY)
            assertEquals(gap, bottom.height - (bottom.pillY + 96))
        }
    }

    @Test
    fun onlyTheLeftAndRightEdgesAreSideEdges() {
        assertTrue(FloatingIsland.isSide(CutoutEdge.LEFT))
        assertTrue(FloatingIsland.isSide(CutoutEdge.RIGHT))
        assertFalse(FloatingIsland.isSide(CutoutEdge.TOP))
        assertFalse(FloatingIsland.isSide(CutoutEdge.BOTTOM))
        assertFalse(FloatingIsland.isSide(CutoutEdge.NONE))
    }
}
