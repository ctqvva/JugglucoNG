package tk.glucodata.data.journal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class JournalDoseCalculatorTests {
    @Test
    fun carbsUseTheConfiguredRatioAndRoundToHalfAUnit() {
        val suggestion = JournalDoseCalculator.insulinForCarbs(
            carbsGrams = 43f,
            proteinGrams = null,
            fatGrams = null,
            macrosEnabled = false,
            glucoseMgDl = null,
            carbRatioGramsPerUnit = 10f,
            insulinSensitivityMgDlPerUnit = 50f,
            targetMgDl = 120f,
            activeInsulinUnits = 0f
        )

        assertEquals(4.3f, suggestion!!.foodInsulinUnits, 0.0001f)
        assertEquals(4.5f, suggestion.totalInsulinUnits, 0.0001f)
    }

    @Test
    fun activeInsulinCreditsOnlyTheHighGlucoseCorrection() {
        val suggestion = JournalDoseCalculator.insulinForCarbs(
            carbsGrams = 40f,
            proteinGrams = null,
            fatGrams = null,
            macrosEnabled = false,
            glucoseMgDl = 220f,
            carbRatioGramsPerUnit = 10f,
            insulinSensitivityMgDlPerUnit = 50f,
            targetMgDl = 120f,
            activeInsulinUnits = 1.25f
        )

        assertEquals(4f, suggestion!!.foodInsulinUnits, 0.0001f)
        assertEquals(0.75f, suggestion.correctionInsulinUnits, 0.0001f)
        assertEquals(1.25f, suggestion.activeInsulinCreditUnits, 0.0001f)
        assertEquals(5f, suggestion.totalInsulinUnits, 0.0001f)
    }

    @Test
    fun theDoseTargetSetsHowFarACorrectionReachesDown() {
        // 220 mg/dL, ISF 50: correcting to 120 is 2 U, correcting to the 90 default is 2.6 U.
        fun correctionAt(target: Float) = JournalDoseCalculator.insulinForCarbs(
            carbsGrams = 10f,
            proteinGrams = null,
            fatGrams = null,
            macrosEnabled = false,
            glucoseMgDl = 220f,
            carbRatioGramsPerUnit = 10f,
            insulinSensitivityMgDlPerUnit = 50f,
            targetMgDl = target,
            activeInsulinUnits = 0f
        )!!.correctionInsulinUnits

        assertEquals(2f, correctionAt(120f), 0.0001f)
        assertEquals(2.6f, correctionAt(90f), 0.0001f)
    }

    @Test
    fun correctionIsRemovedBeforeCalculatingCoveredCarbs() {
        val coveredCarbs = JournalDoseCalculator.carbsCoveredByInsulin(
            insulinUnits = 4f,
            glucoseMgDl = 220f,
            carbRatioGramsPerUnit = 10f,
            insulinSensitivityMgDlPerUnit = 50f,
            targetMgDl = 120f,
            activeInsulinUnits = 0.5f
        )

        assertEquals(25f, coveredCarbs!!, 0f)
    }

    @Test
    fun invalidDoseInputsProduceNoRecommendation() {
        assertNull(
            JournalDoseCalculator.carbsCoveredByInsulin(
                insulinUnits = 2f,
                glucoseMgDl = Float.NaN,
                carbRatioGramsPerUnit = 10f,
                insulinSensitivityMgDlPerUnit = 50f,
                targetMgDl = 120f,
                activeInsulinUnits = 0f
            )
        )
        assertNull(
            JournalDoseCalculator.insulinForCarbs(
                carbsGrams = 30f,
                proteinGrams = null,
                fatGrams = null,
                macrosEnabled = false,
                glucoseMgDl = null,
                carbRatioGramsPerUnit = Float.NaN,
                insulinSensitivityMgDlPerUnit = 50f,
                targetMgDl = 120f,
                activeInsulinUnits = 0f
            )
        )
    }
}
