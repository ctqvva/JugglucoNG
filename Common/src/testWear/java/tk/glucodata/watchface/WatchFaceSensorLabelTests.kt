package tk.glucodata.watchface

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchFaceSensorLabelTests {
    private val fixedWidth: (String) -> Float = { it.length * 10f }

    @Test
    fun shortLabelIsReturnedUntouched() {
        assertEquals("DXCMAA", fitWatchFaceSensorLabel("DXCMAA", 120f, fixedWidth))
        assertEquals("", fitWatchFaceSensorLabel("   ", 120f, fixedWidth))
    }

    @Test
    fun longLabelIsEllipsizedWithinBudgetWithoutTrailingSpace() {
        val fitted = fitWatchFaceSensorLabel("Medtrum TouchCare S9 MD3660", 180f, fixedWidth)
        assertTrue("fits within width budget: $fitted", fixedWidth(fitted) <= 180f)
        assertTrue("ends with ellipsis: $fitted", fitted.endsWith("…"))
        assertEquals("Medtrum TouchCare…", fitted)
    }

    @Test
    fun veryTightBudgetReturnsEllipsis() {
        assertEquals("…", fitWatchFaceSensorLabel("Nightscout follower", 10f, fixedWidth))
    }
}
