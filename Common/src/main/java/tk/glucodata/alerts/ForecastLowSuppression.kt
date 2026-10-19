package tk.glucodata.alerts

import tk.glucodata.TrendArrowAngle

/** Delivery gates for PRE_LOW only; measured LOW and VERY_LOW remain independent. */
internal object ForecastLowSuppression {
    fun hasDownwardArrow(rate: Float): Boolean = TrendArrowAngle.rotationDegrees(rate) > 0f

    /**
     * Remaining COB can potentially fill the forecast's shortfall. Use the same
     * carb ratio and sensitivity as the prediction profile. This estimates total
     * remaining effect, not a guarantee of absorption within the forecast horizon.
     * It changes alert eligibility, never the underlying trend projection.
     */
    fun cobCoversLow(
        projectedValue: Float,
        threshold: Float,
        isMmol: Boolean,
        cobGrams: Float,
        carbRatioGramsPerUnit: Float,
        insulinSensitivityMgdlPerUnit: Float
    ): Boolean {
        if (!cobGrams.isFinite() || cobGrams <= 0f ||
            !carbRatioGramsPerUnit.isFinite() || carbRatioGramsPerUnit <= 0f ||
            !insulinSensitivityMgdlPerUnit.isFinite() || insulinSensitivityMgdlPerUnit <= 0f ||
            !projectedValue.isFinite() || !threshold.isFinite() || threshold <= 0f
        ) return false

        // An active episode can outlive its projected crossing. Positive COB
        // also covers a zero shortfall; absent COB does not grant suppression.
        val shortfallMgdl = (threshold.toDouble() - projectedValue).coerceAtLeast(0.0) *
            (if (isMmol) 18.0182 else 1.0)
        val remainingRiseMgdl = cobGrams.toDouble() / carbRatioGramsPerUnit * insulinSensitivityMgdlPerUnit
        return remainingRiseMgdl >= shortfallMgdl
    }
}
