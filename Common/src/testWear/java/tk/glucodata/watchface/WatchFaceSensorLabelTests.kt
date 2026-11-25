package tk.glucodata.watchface

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test
    fun truncationDoesNotSplitUtf16SurrogatePairs() {
        // "AB" (2 chars) + U+1F600 (2 UTF-16 code units) + "CD" (2 chars) = 6 chars;
        // a 4-char budget allows 3 chars + ellipsis, which would cut mid-surrogate without the guard.
        val label = "AB\uD83D\uDE00CD"
        val fitted = fitWatchFaceSensorLabel(label, 40f, fixedWidth)
        assertEquals("AB…", fitted)
        assertFalse(
            "must not contain a lone high surrogate",
            fitted.any { Character.isHighSurrogate(it) },
        )
    }
}
