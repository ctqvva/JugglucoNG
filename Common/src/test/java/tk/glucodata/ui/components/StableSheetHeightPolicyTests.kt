package tk.glucodata.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StableSheetHeightPolicyTests {
    @Test
    fun shortSheetKeepsIntrinsicHeight() {
        val policy = StableSheetHeightPolicy()

        val floor = policy.resolveMinimumHeight(
            intrinsicHeight = 720,
            maxHeight = 1000,
            hasBoundedHeight = true,
        )

        assertFalse(policy.isViewportHeightLocked)
        assertEquals(0, floor)
    }

    @Test
    fun contentTallerThanViewportLocksToViewportHeight() {
        val policy = StableSheetHeightPolicy()

        val floor = policy.resolveMinimumHeight(
            intrinsicHeight = 1400,
            maxHeight = 1000,
            hasBoundedHeight = true,
        )

        assertTrue(policy.isViewportHeightLocked)
        assertEquals(1000, floor)
    }

    @Test
    fun lockedSheetTracksShrinkingViewport() {
        val policy = StableSheetHeightPolicy()
        policy.resolveMinimumHeight(
            intrinsicHeight = 1400,
            maxHeight = 1000,
            hasBoundedHeight = true,
        )

        val floor = policy.resolveMinimumHeight(
            intrinsicHeight = 1400,
            maxHeight = 720,
            hasBoundedHeight = true,
        )

        assertTrue(policy.isViewportHeightLocked)
        assertEquals(720, floor)
    }

    @Test
    fun keyboardViewportRoundTripDoesNotPinInitiallyShortSheet() {
        val policy = StableSheetHeightPolicy()

        assertEquals(
            0,
            policy.resolveMinimumHeight(
                intrinsicHeight = 720,
                maxHeight = 1000,
                hasBoundedHeight = true,
            ),
        )
        assertEquals(
            0,
            policy.resolveMinimumHeight(
                intrinsicHeight = 720,
                maxHeight = 600,
                hasBoundedHeight = true,
            ),
        )
        assertEquals(
            0,
            policy.resolveMinimumHeight(
                intrinsicHeight = 720,
                maxHeight = 1000,
                hasBoundedHeight = true,
            ),
        )

        assertFalse(policy.isViewportHeightLocked)
    }

    @Test
    fun substantiallyShorterContentReleasesViewportHeightLock() {
        val policy = StableSheetHeightPolicy()
        policy.resolveMinimumHeight(
            intrinsicHeight = 1400,
            maxHeight = 1000,
            hasBoundedHeight = true,
        )

        val floor = policy.resolveMinimumHeight(
            intrinsicHeight = 880,
            maxHeight = 1000,
            hasBoundedHeight = true,
        )

        assertFalse(policy.isViewportHeightLocked)
        assertEquals(0, floor)
    }

    @Test
    fun unboundedMeasurementDoesNotLockOrInventAHeight() {
        val policy = StableSheetHeightPolicy()

        val floor = policy.resolveMinimumHeight(
            intrinsicHeight = 1000,
            maxHeight = Int.MAX_VALUE,
            hasBoundedHeight = false,
        )

        assertFalse(policy.isViewportHeightLocked)
        assertEquals(0, floor)
    }
}
