package tk.glucodata

import org.junit.Assert.assertEquals
import org.junit.Test

class IobCobRiskTests {

    private fun classify(
        iobUnits: Float,
        cobGrams: Float,
        glucoseMgdl: Float,
        carbRatio: Float = 10f,
        isf: Float = 50f,
        targetLowMgdl: Float = 80f,
        veryLowMgdl: Float = 54f
    ) = IobCobRisk.classify(iobUnits, cobGrams, carbRatio, isf, glucoseMgdl, targetLowMgdl, veryLowMgdl)

    @Test
    fun coveredInsulinIsNoRisk() {
        // 2U cover 20g at ratio 10; 30g on board -> fully covered.
        assertEquals(IobCobRisk.NONE, classify(iobUnits = 2f, cobGrams = 30f, glucoseMgdl = 110f))
    }

    @Test
    fun correctionBolusWhileHighIsNoRisk() {
        // 3U uncovered dropping 150 mg/dL from 250 lands at 100 - still in range.
        assertEquals(IobCobRisk.NONE, classify(iobUnits = 3f, cobGrams = 0f, glucoseMgdl = 250f))
    }

    @Test
    fun projectedBelowTargetIsBorderline() {
        // 1.6U uncovered * 50 = 80 drop from 150 -> 70: below target 80, above alarm 54.
        assertEquals(IobCobRisk.BORDERLINE, classify(iobUnits = 1.6f, cobGrams = 0f, glucoseMgdl = 150f))
    }

    @Test
    fun projectedBelowAlarmIsOut() {
        // 2U uncovered * 50 = 100 drop from 120 -> 20: below alarm.
        assertEquals(IobCobRisk.OUT, classify(iobUnits = 2f, cobGrams = 0f, glucoseMgdl = 120f))
    }

    @Test
    fun partialCoverageCountsOnlyUncoveredInsulin() {
        // 3U minus 20g/10 = 1U uncovered -> 50 drop from 130 lands exactly at 80 (target): fine.
        assertEquals(IobCobRisk.NONE, classify(iobUnits = 3f, cobGrams = 20f, glucoseMgdl = 130f))
        // Half a unit more uncovered projects to 55: below target 80, above alarm 54.
        assertEquals(IobCobRisk.BORDERLINE, classify(iobUnits = 3.5f, cobGrams = 20f, glucoseMgdl = 130f))
    }

    @Test
    fun carbSurplusNeverWarns() {
        assertEquals(IobCobRisk.NONE, classify(iobUnits = 1f, cobGrams = 80f, glucoseMgdl = 90f))
    }

    @Test
    fun invalidInputsAreNoRisk() {
        assertEquals(IobCobRisk.NONE, classify(iobUnits = Float.NaN, cobGrams = 0f, glucoseMgdl = 120f))
        assertEquals(IobCobRisk.NONE, classify(iobUnits = 0f, cobGrams = 0f, glucoseMgdl = 120f))
        assertEquals(IobCobRisk.NONE, classify(iobUnits = 2f, cobGrams = 0f, glucoseMgdl = Float.NaN))
        assertEquals(IobCobRisk.NONE, classify(iobUnits = 2f, cobGrams = 0f, glucoseMgdl = 0f))
    }

    @Test
    fun nanCobCountsAsZero() {
        assertEquals(IobCobRisk.OUT, classify(iobUnits = 2f, cobGrams = Float.NaN, glucoseMgdl = 120f))
    }

    @Test
    fun invalidSettingsFallBackToDefaults() {
        // isf<=0 falls back to 54: 1U * 54 = 54 drop from 100 -> 46: below default alarm 54.
        assertEquals(
            IobCobRisk.OUT,
            classify(iobUnits = 1f, cobGrams = 0f, glucoseMgdl = 100f, isf = 0f, veryLowMgdl = 0f)
        )
        // ratio<=0 falls back to 10: 30g cover 3U -> nothing uncovered.
        assertEquals(
            IobCobRisk.NONE,
            classify(iobUnits = 3f, cobGrams = 30f, glucoseMgdl = 100f, carbRatio = 0f)
        )
    }
}
