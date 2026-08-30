package tk.glucodata.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StableSheetHeightPolicyTests {
    @Test
    fun shortSheetKeepsIntrinsicHeight() {
        val policy = StableSheetHeightPolicy()

        policy.onMeasured(measuredHeight = 720, maxHeight = 1000, hasBoundedHeight = true)

        assertFalse(policy.isViewportHeightLocked)
        assertEquals(0, policy.minimumHeight(maxHeight = 1000, hasBoundedHeight = true))
    }

    @Test
    fun viewportHeightSheetLocksForLaterMeasurements() {
        val policy = StableSheetHeightPolicy()

        policy.onMeasured(measuredHeight = 1000, maxHeight = 1000, hasBoundedHeight = true)

        assertTrue(policy.isViewportHeightLocked)
        assertEquals(1000, policy.minimumHeight(maxHeight = 1000, hasBoundedHeight = true))
    }

    @Test
    fun lockedSheetTracksAChangedViewportHeight() {
        val policy = StableSheetHeightPolicy()
        policy.onMeasured(measuredHeight = 1000, maxHeight = 1000, hasBoundedHeight = true)

        assertEquals(720, policy.minimumHeight(maxHeight = 720, hasBoundedHeight = true))
    }

    @Test
    fun viewportHeightLockDoesNotReleaseWhenContentLaterMeasuresShorter() {
        val policy = StableSheetHeightPolicy()
        policy.onMeasured(measuredHeight = 1000, maxHeight = 1000, hasBoundedHeight = true)

        policy.onMeasured(measuredHeight = 960, maxHeight = 1000, hasBoundedHeight = true)

        assertTrue(policy.isViewportHeightLocked)
        assertEquals(1000, policy.minimumHeight(maxHeight = 1000, hasBoundedHeight = true))
    }

    @Test
    fun unboundedMeasurementDoesNotLockOrInventAHeight() {
        val policy = StableSheetHeightPolicy()

        policy.onMeasured(
            measuredHeight = 1000,
            maxHeight = Int.MAX_VALUE,
            hasBoundedHeight = false,
        )

        assertFalse(policy.isViewportHeightLocked)
        assertEquals(
            0,
            policy.minimumHeight(maxHeight = Int.MAX_VALUE, hasBoundedHeight = false),
        )
    }
}
