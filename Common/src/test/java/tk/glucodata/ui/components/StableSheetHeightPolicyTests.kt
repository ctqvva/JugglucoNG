package tk.glucodata.ui.components

import tk.glucodata.ui.components.StableSheetHeightPolicy.Companion.FREE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The policy exists for one failure: Material cancels and restarts an in-flight
 * drag whenever the sheet's anchors change, and the sheet's anchors come from
 * its measured height. A sheet that resizes under the finger loses the gesture
 * and settles back to expanded instead of dismissing.
 *
 * So it holds the height for a finger drag and for nothing else. At rest and
 * under the framework's own animations the sheet is measured normally, which is
 * what lets a row that was added or removed change its height.
 */
class StableSheetHeightPolicyTests {

    /** Convenience: a resting pass at [height], which is what arms the hold. */
    private fun StableSheetHeightPolicy.settleAt(height: Int, offset: Float = 0f) {
        heightForPass(sheetOffset = offset, isAnimating = false, hasBoundedHeight = true)
        recordFreeHeight(height)
    }

    @Test
    fun theFirstPassHasNoOffsetToCompareAndMeasuresFreely() {
        val policy = StableSheetHeightPolicy()

        val held = policy.heightForPass(
            sheetOffset = Float.NaN, isAnimating = false, hasBoundedHeight = true,
        )

        assertFalse(policy.isHoldingHeight)
        assertEquals(FREE, held)
    }

    @Test
    fun aStillSheetMeasuresFreely() {
        val policy = StableSheetHeightPolicy()
        policy.settleAt(height = 1400)

        val held = policy.heightForPass(
            sheetOffset = 0f, isAnimating = false, hasBoundedHeight = true,
        )

        assertFalse(policy.isHoldingHeight)
        assertEquals(FREE, held)
    }

    @Test
    fun aMovingOffsetUnderAFingerHoldsTheRestingHeight() {
        val policy = StableSheetHeightPolicy()
        policy.settleAt(height = 1400)

        val held = policy.heightForPass(
            sheetOffset = 24f, isAnimating = false, hasBoundedHeight = true,
        )

        assertTrue(policy.isHoldingHeight)
        assertEquals(1400, held)
    }

    /**
     * The dismiss regression. Every frame of the swipe has to report the same
     * height, or the anchors move, the drag is torn down and the sheet snaps
     * back to expanded instead of going away.
     */
    @Test
    fun aLongSwipeNeverReleasesTheHold() {
        val policy = StableSheetHeightPolicy()
        policy.settleAt(height = 1400)

        listOf(30f, 120f, 400f, 900f, 1380f).forEach { offset ->
            val held = policy.heightForPass(
                sheetOffset = offset, isAnimating = false, hasBoundedHeight = true,
            )
            assertTrue("released at offset $offset", policy.isHoldingHeight)
            assertEquals(1400, held)
        }
    }

    /**
     * Show, hide and settle are the framework moving the sheet itself. Those
     * passes measure freely: holding through them would freeze the height a
     * sheet was opened at, and an open animation starts before the sheet's
     * insets have settled.
     */
    @Test
    fun theFrameworksOwnAnimationsMeasureFreely() {
        val policy = StableSheetHeightPolicy()
        policy.settleAt(height = 1400)

        val held = policy.heightForPass(
            sheetOffset = 600f, isAnimating = true, hasBoundedHeight = true,
        )

        assertFalse(policy.isHoldingHeight)
        assertEquals(FREE, held)
    }

    /**
     * Leaving Adaptive V2 removes the ribbon row while the sheet sits still. A
     * height held from before would leave the removed row's space on screen as
     * a gap above the Close button.
     */
    @Test
    fun contentRemovedAtRestTakesTheNewHeight() {
        val policy = StableSheetHeightPolicy()
        policy.settleAt(height = 1400)

        val held = policy.heightForPass(
            sheetOffset = 0f, isAnimating = false, hasBoundedHeight = true,
        )
        assertEquals(FREE, held)
        policy.recordFreeHeight(880)

        // ...and the next drag holds what the shorter sheet actually measured.
        val duringDrag = policy.heightForPass(
            sheetOffset = 40f, isAnimating = false, hasBoundedHeight = true,
        )
        assertEquals(880, duringDrag)
    }

    /** Nothing is invented before there is a measurement to repeat. */
    @Test
    fun aDragBeforeAnyMeasurementHoldsNothing() {
        val policy = StableSheetHeightPolicy()
        policy.heightForPass(sheetOffset = 0f, isAnimating = false, hasBoundedHeight = true)

        val held = policy.heightForPass(
            sheetOffset = 90f, isAnimating = false, hasBoundedHeight = true,
        )

        assertFalse(policy.isHoldingHeight)
        assertEquals(FREE, held)
    }

    @Test
    fun unboundedMeasurementIsNeverHeld() {
        val policy = StableSheetHeightPolicy()
        policy.settleAt(height = 1400)

        val held = policy.heightForPass(
            sheetOffset = 300f, isAnimating = false, hasBoundedHeight = false,
        )

        assertFalse(policy.isHoldingHeight)
        assertEquals(FREE, held)
    }

    /**
     * The offset still has to be tracked through a pass that cannot hold, or
     * the pass after an unbounded or animated one compares against a stale
     * position and reads as still.
     */
    @Test
    fun anUnheldPassStillAdvancesTheOffset() {
        val policy = StableSheetHeightPolicy()
        policy.settleAt(height = 1400)
        policy.heightForPass(sheetOffset = 300f, isAnimating = true, hasBoundedHeight = true)

        val held = policy.heightForPass(
            sheetOffset = 300f, isAnimating = false, hasBoundedHeight = true,
        )

        assertFalse(policy.isHoldingHeight)
        assertEquals(FREE, held)
    }
}
