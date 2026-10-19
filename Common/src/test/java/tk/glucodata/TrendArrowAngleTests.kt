package tk.glucodata

import org.junit.Assert.assertEquals
import org.junit.Test

class TrendArrowAngleTests {

    @Test
    fun slowDriftRendersFlat() {
        // Below the Flat trend state (|v| < 0.5 mg/dL/min) the sign of the
        // rate is noise; two estimators can disagree on it (native -0.2 vs
        // regression +0.3 for the same data). The arrow must not pick a side.
        assertEquals(0f, TrendArrowAngle.rotationDegrees(0.3f), 0.001f)
        assertEquals(0f, TrendArrowAngle.rotationDegrees(-0.3f), 0.001f)
        assertEquals(0f, TrendArrowAngle.rotationDegrees(0.49f), 0.001f)
        assertEquals(0f, TrendArrowAngle.rotationDegrees(0f), 0.001f)
    }

    @Test
    fun realTrendsRotateAtCgmConvention() {
        // 45 degrees per mg/dL/min, negative rate points down (positive
        // rotation in screen coordinates handled by callers via the sign).
        assertEquals(-45f, TrendArrowAngle.rotationDegrees(1f), 0.001f)
        assertEquals(45f, TrendArrowAngle.rotationDegrees(-1f), 0.001f)
        assertEquals(0f, TrendArrowAngle.rotationDegrees(0.5f), 0.001f)
    }
    @Test
    fun rampEasesOutOfTheDeadZone() {
        assertEquals(-9f, TrendArrowAngle.rotationDegrees(0.6f), 0.001f)
        assertEquals(9f, TrendArrowAngle.rotationDegrees(-0.6f), 0.001f)

        assertEquals(-27f, TrendArrowAngle.rotationDegrees(0.8f), 0.001f)
        assertEquals(27f, TrendArrowAngle.rotationDegrees(-0.8f), 0.001f)
    }

    @Test
    fun mappingAboveRampIsUnchanged() {
        assertEquals(-45f, TrendArrowAngle.rotationDegrees(1f), 0.001f)
        assertEquals(45f, TrendArrowAngle.rotationDegrees(-1f), 0.001f)

        assertEquals(-67.5f, TrendArrowAngle.rotationDegrees(1.5f), 0.001f)
        assertEquals(67.5f, TrendArrowAngle.rotationDegrees(-1.5f), 0.001f)

        assertEquals(-90f, TrendArrowAngle.rotationDegrees(2f), 0.001f)
        assertEquals(90f, TrendArrowAngle.rotationDegrees(-2f), 0.001f)
    }

    @Test
    fun clampsAtVertical() {
        assertEquals(-90f, TrendArrowAngle.rotationDegrees(2f), 0.001f)
        assertEquals(-90f, TrendArrowAngle.rotationDegrees(3.7f), 0.001f)
        assertEquals(90f, TrendArrowAngle.rotationDegrees(-5f), 0.001f)
    }

    @Test
    fun nonFiniteRendersFlat() {
        assertEquals(0f, TrendArrowAngle.rotationDegrees(Float.NaN), 0.001f)
    }
}
