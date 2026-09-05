package tk.glucodata.alerts

/**
 * Coverage decision for PRE_HIGH: is the remaining effect of the insulin on
 * board already enough to absorb the projected overshoot? A forecast-high
 * alert that announces a rise the user has just treated is arithmetically
 * correct and still worthless - what it warns about is already handled.
 *
 * The quantity is CLASSIC IOB (remaining future action), not eIOB: eIOB
 * weights by the current activity level and is zero before the action curve's
 * onset, so it would exclude exactly the just-injected bolus whose effect is
 * still fully ahead - the field case this exists for (IOB 3.0 U at eIOB
 * 1.3 U, the last 2.5 U not yet visible in the curve).
 *
 * This is an ALARM decision on top of the projection, never a change to it:
 * the projection stays insulin-free and keeps answering "where is the value
 * heading".
 *
 * Asymmetry, the central rule: this applies to PRE_HIGH ONLY. For PRE_LOW,
 * insulin on board works the other way around - it makes a predicted low MORE
 * likely, not less. A shared rule for both forecast directions would be a
 * dangerous mistake. As defence in depth the arithmetic also degenerates
 * safely: a PRE_LOW-shaped projection sits below its threshold, the overshoot
 * is negative, and [covered] is false regardless of wiring.
 */
internal object ForecastIobCoverage {
    private const val MGDL_PER_MMOL = 18.0182f

    fun covered(
        projectedValue: Float,
        threshold: Float,
        isMmol: Boolean,
        iobUnits: Float,
        insulinSensitivityMgdlPerUnit: Float,
        coverageFactor: Float
    ): Boolean {
        if (!coverageFactor.isFinite() || coverageFactor <= 0f) {
            // 0 switches the feature off - regression to today's behaviour.
            return false
        }
        if (!iobUnits.isFinite() || iobUnits <= 0f) {
            return false
        }
        if (!insulinSensitivityMgdlPerUnit.isFinite() || insulinSensitivityMgdlPerUnit <= 0f) {
            return false
        }
        if (!projectedValue.isFinite() || !threshold.isFinite()) {
            return false
        }
        val overshootMgdl = (projectedValue - threshold) * (if (isMmol) MGDL_PER_MMOL else 1f)
        if (overshootMgdl <= 0f) {
            return false
        }
        val remainingEffectMgdl = iobUnits * insulinSensitivityMgdlPerUnit
        return remainingEffectMgdl >= overshootMgdl * coverageFactor
    }
}
