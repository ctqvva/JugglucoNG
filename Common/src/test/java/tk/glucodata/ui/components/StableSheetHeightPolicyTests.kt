package tk.glucodata.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StableSheetHeightPolicyTests {
    @Test
    fun shortSheetKeepsIntrinsicHeight() {
        val policy = StableSheetHeightPolicy()

        val floor = policy.resolveMinimumHeight(720, maxHeight = 1000, hasBoundedHeight = true)

        assertFalse(policy.isViewportHeightLocked)
        assertEquals(0, floor)
    }

    @Test
    fun viewportHeightSheetLocksForLaterMeasurements() {
        val policy = StableSheetHeightPolicy()

        val floor = policy.resolveMinimumHeight(1000, maxHeight = 1000, hasBoundedHeight = true)

        assertTrue(policy.isViewportHeightLocked)
        assertEquals(1000, floor)
    }

    @Test
    fun lockedSheetTracksAChangedViewportHeight() {
        val policy = StableSheetHeightPolicy()
        policy.resolveMinimumHeight(1000, maxHeight = 1000, hasBoundedHeight = true)

        assertEquals(720, policy.resolveMinimumHeight(1000, maxHeight = 720, hasBoundedHeight = true))
    }

    /**
     * The dismiss regression. A swipe collapses the viewport a long way while
     * the content is untouched; the pin must survive all of it.
     */
    @Test
    fun aDragCannotReleaseThePin() {
        val policy = StableSheetHeightPolicy()
        policy.resolveMinimumHeight(1400, maxHeight = 1000, hasBoundedHeight = true)

        listOf(900, 700, 500, 300, 140, 40).forEach { viewport ->
            val floor = policy.resolveMinimumHeight(1400, maxHeight = viewport, hasBoundedHeight = true)
            assertTrue("released at viewport \$viewport", policy.isViewportHeightLocked)
            assertEquals(viewport, floor)
        }
    }

    /** Removing a control row must let the sheet close the gap. */
    @Test
    fun removingContentReleasesThePin() {
        val policy = StableSheetHeightPolicy()
        policy.resolveMinimumHeight(1400, maxHeight = 1000, hasBoundedHeight = true)
        assertTrue(policy.isViewportHeightLocked)

        val floor = policy.resolveMinimumHeight(880, maxHeight = 1000, hasBoundedHeight = true)

        assertFalse("stayed pinned after content shrank", policy.isViewportHeightLocked)
        assertEquals(0, floor)
    }

    @Test
    fun viewportHeightLockDoesNotReleaseWhenContentLaterMeasuresShorter() {
        val policy = StableSheetHeightPolicy()
        policy.resolveMinimumHeight(1400, maxHeight = 1000, hasBoundedHeight = true)

        policy.resolveMinimumHeight(1380, maxHeight = 1000, hasBoundedHeight = true)

        assertTrue(policy.isViewportHeightLocked)
        assertEquals(1000, policy.resolveMinimumHeight(1400, 1000, true))
    }

    @Test
    fun unboundedMeasurementDoesNotLockOrInventAHeight() {
        val policy = StableSheetHeightPolicy()

        val floor = policy.resolveMinimumHeight(1000, Int.MAX_VALUE, hasBoundedHeight = false)

        assertFalse(policy.isViewportHeightLocked)
        assertEquals(0, floor)
    }
}
