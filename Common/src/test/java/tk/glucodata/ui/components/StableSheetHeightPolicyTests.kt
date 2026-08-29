package tk.glucodata.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The policy has to separate two things that both look like "the sheet got
 * shorter": the viewport shrinking under a drag, where it must hold its height
 * so its own expanded anchor cannot move, and content genuinely being removed,
 * where it must let go or the removed row's space stays on screen as a gap.
 *
 * It decides from what the content *wants* — its intrinsic height — because
 * that is the only one of the two signals that tells them apart.
 */
class StableSheetHeightPolicyTests {

    @Test
    fun shortSheetKeepsIntrinsicHeight() {
        val policy = StableSheetHeightPolicy()

        val floor = policy.resolveMinimumHeight(
            intrinsicHeight = 720, maxHeight = 1000, hasBoundedHeight = true,
        )

        assertFalse(policy.isViewportHeightLocked)
        assertEquals(0, floor)
    }

    @Test
    fun contentTallerThanTheViewportPinsTheSheetToIt() {
        val policy = StableSheetHeightPolicy()

        val floor = policy.resolveMinimumHeight(
            intrinsicHeight = 1400, maxHeight = 1000, hasBoundedHeight = true,
        )

        assertTrue(policy.isViewportHeightLocked)
        assertEquals(1000, floor)
    }

    @Test
    fun contentExactlyFillingTheViewportStillPins() {
        val policy = StableSheetHeightPolicy()

        val floor = policy.resolveMinimumHeight(
            intrinsicHeight = 1000, maxHeight = 1000, hasBoundedHeight = true,
        )

        assertTrue(policy.isViewportHeightLocked)
        assertEquals(1000, floor)
    }

    /**
     * A drag: the framework consumes the top inset, so the space offered
     * shrinks while the content still wants more than fits. Holding the new
     * viewport height is what keeps the expanded anchor still.
     */
    @Test
    fun aShrinkingViewportKeepsThePinAndTracksIt() {
        val policy = StableSheetHeightPolicy()
        policy.resolveMinimumHeight(1400, maxHeight = 1000, hasBoundedHeight = true)

        val floor = policy.resolveMinimumHeight(
            intrinsicHeight = 1400, maxHeight = 720, hasBoundedHeight = true,
        )

        assertTrue(policy.isViewportHeightLocked)
        assertEquals(720, floor)
    }

    /**
     * The dismiss regression. A swipe shrinks the viewport a long way while the
     * content is untouched; the pin must not be re-decided against the smaller
     * viewport, or it releases mid-gesture and the sheet fights the finger.
     */
    @Test
    fun aDragDoesNotReleaseThePinEvenAsTheViewportCollapses() {
        val policy = StableSheetHeightPolicy()
        policy.resolveMinimumHeight(1400, maxHeight = 1000, hasBoundedHeight = true)

        listOf(900, 700, 500, 300, 140).forEach { viewport ->
            val floor = policy.resolveMinimumHeight(
                intrinsicHeight = 1400, maxHeight = viewport, hasBoundedHeight = true,
            )
            assertTrue("released at viewport $viewport", policy.isViewportHeightLocked)
            assertEquals(viewport, floor)
        }
    }

    /** Frame-to-frame wobble is not a content change. */
    @Test
    fun subPixelWobbleDoesNotRedecide() {
        val policy = StableSheetHeightPolicy()
        policy.resolveMinimumHeight(1400, maxHeight = 1000, hasBoundedHeight = true)

        policy.resolveMinimumHeight(1397, maxHeight = 1000, hasBoundedHeight = true)

        assertTrue(policy.isViewportHeightLocked)
    }

    /**
     * The regression this rewrite exists for. Leaving Adaptive V2 removes the
     * ribbon row, so the content stops wanting the whole viewport. The previous
     * policy latched on measured height and never released, and the row's space
     * stayed behind as dead space above the Close button.
     */
    @Test
    fun removingContentReleasesThePin() {
        val policy = StableSheetHeightPolicy()
        policy.resolveMinimumHeight(1400, maxHeight = 1000, hasBoundedHeight = true)
        assertTrue(policy.isViewportHeightLocked)

        val floor = policy.resolveMinimumHeight(
            intrinsicHeight = 880, maxHeight = 1000, hasBoundedHeight = true,
        )

        assertFalse("the sheet stayed pinned after its content shrank", policy.isViewportHeightLocked)
        assertEquals(0, floor)
    }

    @Test
    fun unboundedMeasurementDoesNotLockOrInventAHeight() {
        val policy = StableSheetHeightPolicy()

        val floor = policy.resolveMinimumHeight(
            intrinsicHeight = 1000, maxHeight = Int.MAX_VALUE, hasBoundedHeight = false,
        )

        assertFalse(policy.isViewportHeightLocked)
        assertEquals(0, floor)
    }
}
