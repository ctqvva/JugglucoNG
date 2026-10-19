package tk.glucodata

import org.junit.Assert.assertEquals
import org.junit.Test

class TrendProjectionTests {

    private fun classify(
        glucoseMgdl: Float,
        rateMgdlPerMin: Float,
        horizonMinutes: Int = 30,
        targetLowMgdl: Float = 80f,
        targetHighMgdl: Float = 160f,
        veryLowMgdl: Float = 54f,
        veryHighMgdl: Float = 250f
    ) = TrendProjection.classify(
        glucoseMgdl, rateMgdlPerMin, horizonMinutes,
        targetLowMgdl, targetHighMgdl, veryLowMgdl, veryHighMgdl
    )

    @Test
    fun steadyInRangeIsNone() {
        assertEquals(TrendProjection.NONE, classify(110f, 0f))
        assertEquals(TrendProjection.NONE, classify(110f, 1f)) // -> 140, still in range
    }

    @Test
    fun headingBelowTargetIsBorderline() {
        // 100 - 1.2*30 = 64: below target 80, above very low 54.
        assertEquals(TrendProjection.BORDERLINE, classify(100f, -1.2f))
    }

    @Test
    fun headingBelowVeryLowIsOut() {
        // 100 - 2*30 = 40: below very low.
        assertEquals(TrendProjection.OUT, classify(100f, -2f))
    }

    @Test
    fun headingAboveTargetIsBorderline() {
        // 150 + 1*30 = 180: above target 160, below very high 250.
        assertEquals(TrendProjection.BORDERLINE, classify(150f, 1f))
    }

    @Test
    fun headingAboveVeryHighIsOut() {
        // 180 + 3*30 = 270: above very high 250.
        assertEquals(TrendProjection.OUT, classify(180f, 3f))
    }

    @Test
    fun recoveringFromLowIsNone() {
        // Already low but rising: 65 + 1*30 = 95, back in range -> no warning.
        assertEquals(TrendProjection.NONE, classify(65f, 1f))
    }

    @Test
    fun invalidInputsAreNone() {
        assertEquals(TrendProjection.NONE, classify(Float.NaN, -2f))
        assertEquals(TrendProjection.NONE, classify(0f, -2f))
        assertEquals(TrendProjection.NONE, classify(110f, Float.NaN))
    }

    @Test
    fun staleDataNeverWarns() {
        // Crashing hard, but the newest reading is too old to trust a
        // forecast (reconnect gap, app restart): no tint instead of a red
        // arrow computed from outdated data.
        val staleAge = TrendProjection.MAX_DATA_AGE_MILLIS + 1L
        assertEquals(
            TrendProjection.NONE,
            TrendProjection.classify(100f, -3f, staleAge, 30, 80f, 160f, 54f, 250f)
        )
    }

    @Test
    fun freshDataClassifiesNormally() {
        assertEquals(
            TrendProjection.OUT,
            TrendProjection.classify(100f, -2f, 60_000L, 30, 80f, 160f, 54f, 250f)
        )
        // Exactly at the limit still counts as usable.
        assertEquals(
            TrendProjection.OUT,
            TrendProjection.classify(
                100f, -2f, TrendProjection.MAX_DATA_AGE_MILLIS, 30, 80f, 160f, 54f, 250f
            )
        )
    }

    @Test
    fun slightlyFutureTimestampCountsAsFresh() {
        // Live-augmented points can sit a few seconds ahead of the clock.
        assertEquals(
            TrendProjection.OUT,
            TrendProjection.classify(100f, -2f, -5_000L, 30, 80f, 160f, 54f, 250f)
        )
    }

    @Test
    fun invalidThresholdsFallBackToDefaults() {
        // Defaults 70/180 target, 54/250 very: 100 - 1.2*30 = 64 -> borderline.
        assertEquals(
            TrendProjection.BORDERLINE,
            classify(
                100f, -1.2f,
                targetLowMgdl = 0f, targetHighMgdl = 0f,
                veryLowMgdl = 0f, veryHighMgdl = 0f
            )
        )
    }
}
