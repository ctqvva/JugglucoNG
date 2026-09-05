package tk.glucodata.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StableSheetHeightPolicyTests {
    @Test
    fun shortSheetKeepsIntrinsicHeight() {
        val policy = StableSheetHeightPolicy()

        policy.onMeasured(
            measuredHeight = 720,
            maxHeight = 1000,
            hasBoundedHeight = true,
            isImeVisible = false,
        )

        assertFalse(policy.isViewportHeightLocked)
        assertEquals(0, policy.minimumHeight(maxHeight = 1000, hasBoundedHeight = true))
    }

    @Test
    fun viewportHeightSheetLocksForLaterMeasurements() {
        val policy = StableSheetHeightPolicy()

        policy.onMeasured(
            measuredHeight = 1000,
            maxHeight = 1000,
            hasBoundedHeight = true,
            isImeVisible = false,
        )

        assertTrue(policy.isViewportHeightLocked)
        assertEquals(1000, policy.minimumHeight(maxHeight = 1000, hasBoundedHeight = true))
    }

    @Test
    fun lockedSheetTracksShrinkingViewport() {
        val policy = StableSheetHeightPolicy()
        policy.onMeasured(
            measuredHeight = 1000,
            maxHeight = 1000,
            hasBoundedHeight = true,
            isImeVisible = false,
        )

        assertTrue(policy.isViewportHeightLocked)
        assertEquals(720, policy.minimumHeight(maxHeight = 720, hasBoundedHeight = true))
    }

    @Test
    fun keyboardViewportRoundTripDoesNotPinInitiallyShortSheet() {
        val policy = StableSheetHeightPolicy()

        policy.onMeasured(
            measuredHeight = 720,
            maxHeight = 1000,
            hasBoundedHeight = true,
            isImeVisible = false,
        )
        assertEquals(0, policy.minimumHeight(maxHeight = 600, hasBoundedHeight = true))

        policy.onMeasured(
            measuredHeight = 600,
            maxHeight = 600,
            hasBoundedHeight = true,
            isImeVisible = true,
        )
        assertEquals(
            0,
            policy.minimumHeight(maxHeight = 600, hasBoundedHeight = true),
        )

        policy.onMeasured(
            measuredHeight = 720,
            maxHeight = 1000,
            hasBoundedHeight = true,
            isImeVisible = false,
        )
        assertEquals(
            0,
            policy.minimumHeight(maxHeight = 1000, hasBoundedHeight = true),
        )

        assertFalse(policy.isViewportHeightLocked)
    }

    @Test
    fun alreadyLockedSheetStaysLockedWhileKeyboardIsVisible() {
        val policy = StableSheetHeightPolicy()
        policy.onMeasured(
            measuredHeight = 1000,
            maxHeight = 1000,
            hasBoundedHeight = true,
            isImeVisible = false,
        )

        policy.onMeasured(
            measuredHeight = 600,
            maxHeight = 600,
            hasBoundedHeight = true,
            isImeVisible = true,
        )

        assertTrue(policy.isViewportHeightLocked)
        assertEquals(600, policy.minimumHeight(maxHeight = 600, hasBoundedHeight = true))
    }

    @Test
    fun unboundedMeasurementDoesNotLockOrInventAHeight() {
        val policy = StableSheetHeightPolicy()

        policy.onMeasured(
            measuredHeight = 1000,
            maxHeight = Int.MAX_VALUE,
            hasBoundedHeight = false,
            isImeVisible = false,
        )

        assertFalse(policy.isViewportHeightLocked)
        assertEquals(
            0,
            policy.minimumHeight(maxHeight = Int.MAX_VALUE, hasBoundedHeight = false),
        )
    }
}
