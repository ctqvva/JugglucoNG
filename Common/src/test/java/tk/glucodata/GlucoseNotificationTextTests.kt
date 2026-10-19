package tk.glucodata

import org.junit.Assert.assertEquals
import org.junit.Test

class GlucoseNotificationTextTests {
    @Test fun titleCarriesValueUnitAndTrend() {
        assertEquals("9,0 mmol/L ↗", GlucoseNotificationText.title("9,0", null, "mmol/L", 1.5f))
        assertEquals("162 mg/dL ↓", GlucoseNotificationText.title("162", emptyList(), "mg/dL", -2.4f))
        assertEquals("5,7 · 5,5 mmol/L →", GlucoseNotificationText.title("5,7", listOf("5,5", "", null), "mmol/L", 0.2f))
    }

    @Test fun unknownTrendLeavesTheArrowOut() {
        assertEquals("9,0 mmol/L", GlucoseNotificationText.title("9,0", null, "mmol/L", Float.NaN))
    }

    @Test fun noValueMeansNoTitle() {
        assertEquals("", GlucoseNotificationText.title("", null, "mmol/L", 1f))
    }

    @Test fun periodicTextAlwaysCarriesTheMeasurementTime() {
        assertEquals("Δ +0,2 · 12:40", GlucoseNotificationText.periodicText("+0,2", "12:40"))
        assertEquals("12:40", GlucoseNotificationText.periodicText("", "12:40"))
        assertEquals("12:40", GlucoseNotificationText.periodicText(null, "12:40"))
    }
}
