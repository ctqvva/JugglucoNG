package tk.glucodata.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class ExpressiveHueWheelPickerTests {
    @Test
    fun cardinalPositionsFollowHsvHueOrderClockwiseFromTop() {
        assertEquals(0f, hueForWheelVector(dx = 0f, dy = -1f), 0.001f)
        assertEquals(90f, hueForWheelVector(dx = 1f, dy = 0f), 0.001f)
        assertEquals(180f, hueForWheelVector(dx = 0f, dy = 1f), 0.001f)
        assertEquals(270f, hueForWheelVector(dx = -1f, dy = 0f), 0.001f)
    }

    @Test
    fun hueZeroAndSweepStartAtTopOfWheel() {
        assertEquals(HUE_WHEEL_START_ANGLE_DEGREES, wheelAngleForHue(0f), 0.001f)
        assertEquals(-90f, HUE_WHEEL_START_ANGLE_DEGREES, 0.001f)
    }
}
