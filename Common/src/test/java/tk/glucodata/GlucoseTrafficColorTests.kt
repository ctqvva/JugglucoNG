package tk.glucodata

import org.junit.Assert.assertEquals
import org.junit.Test

class GlucoseTrafficColorTests {

    private val fallback = 0x11223344

    private fun color(
        value: Float,
        targetLow: Float = 80f,
        targetHigh: Float = 160f,
        alarmLow: Float = 70f,
        alarmHigh: Float = 250f,
        dark: Boolean = true,
        isMmol: Boolean = false
    ) = GlucoseRangeColors.trafficColorForValue(
        value, targetLow, targetHigh, alarmLow, alarmHigh, dark, isMmol, fallback
    )

    @Test
    fun inRangeIsGreen() {
        assertEquals(GlucoseRangeColors.valueInRange(true), color(110f))
        assertEquals(GlucoseRangeColors.valueInRange(false), color(110f, dark = false))
    }

    @Test
    fun targetBoundariesCountAsInRange() {
        assertEquals(GlucoseRangeColors.valueInRange(true), color(80f))
        assertEquals(GlucoseRangeColors.valueInRange(true), color(160f))
    }

    @Test
    fun betweenTargetAndAlarmIsBorderline() {
        assertEquals(GlucoseRangeColors.valueBorderline(true), color(75f))
        assertEquals(GlucoseRangeColors.valueBorderline(true), color(200f))
        assertEquals(GlucoseRangeColors.valueBorderline(false), color(200f, dark = false))
    }

    @Test
    fun beyondAlarmsIsOut() {
        assertEquals(GlucoseRangeColors.valueOut(true), color(70f))
        assertEquals(GlucoseRangeColors.valueOut(true), color(55f))
        assertEquals(GlucoseRangeColors.valueOut(true), color(250f))
        assertEquals(GlucoseRangeColors.valueOut(true), color(320f))
        assertEquals(GlucoseRangeColors.valueOut(false), color(55f, dark = false))
    }

    @Test
    fun invalidValueFallsBack() {
        assertEquals(fallback, color(Float.NaN))
        assertEquals(fallback, color(0f))
        assertEquals(fallback, color(-5f))
    }

    @Test
    fun unsetAlarmsFallBackToDefaults() {
        // mg/dL defaults: very low 54, very high 250 act as the red bounds.
        assertEquals(GlucoseRangeColors.valueOut(true), color(50f, alarmLow = 0f, alarmHigh = 0f))
        assertEquals(GlucoseRangeColors.valueBorderline(true), color(60f, alarmLow = 0f, alarmHigh = 0f))
        assertEquals(GlucoseRangeColors.valueBorderline(true), color(200f, alarmLow = 0f, alarmHigh = 0f))
        assertEquals(GlucoseRangeColors.valueOut(true), color(260f, alarmLow = 0f, alarmHigh = 0f))
    }

    @Test
    fun mmolThresholdsWork() {
        val yellow = GlucoseRangeColors.trafficColorForValue(
            3.5f, 3.9f, 10f, 3.0f, 13.9f, true, true, fallback
        )
        assertEquals(GlucoseRangeColors.valueBorderline(true), yellow)
        val green = GlucoseRangeColors.trafficColorForValue(
            5.6f, 3.9f, 10f, 3.0f, 13.9f, true, true, fallback
        )
        assertEquals(GlucoseRangeColors.valueInRange(true), green)
    }
}
