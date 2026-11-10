package tk.glucodata

import org.junit.Assert.assertEquals
import org.junit.Test

class GlucoseNotificationTextTests {
    @Test fun titleCarriesValueAndTrendOnly() {
        assertEquals("9,0 ↗", GlucoseNotificationText.title("9,0", null, 1.5f))
        assertEquals("162 ↓", GlucoseNotificationText.title("162", emptyList(), -2.4f))
        assertEquals("5,7 · 5,5 →", GlucoseNotificationText.title("5,7", listOf("5,5", "", null), 0.2f))
    }

    @Test fun unknownTrendLeavesTheArrowOut() {
        assertEquals("9,0", GlucoseNotificationText.title("9,0", null, Float.NaN))
    }

    @Test fun noValueMeansNoTitle() {
        assertEquals("", GlucoseNotificationText.title("", null, 1f))
    }
}
