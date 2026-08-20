package tk.glucodata.alerts

/**
 * Suppression rule for PERSISTENT_HIGH. The alert exists for the statement
 * "your correction was not enough" - a steep fall is the direct proof that it
 * was, so firing during one announces the opposite of what the curve shows
 * (field case: "persistent high" at 226 mg/dl under a double down arrow).
 *
 * Deliberately rate-based, no insulin arithmetic: unlike the PRE_HIGH
 * coverage check ([ForecastIobCoverage]), which reasons about a PREDICTION,
 * the observed direction suffices here - the falling value already proves the
 * insulin is working. With IOB active and the value FALLING the alert stays
 * silent; with IOB active and the value STAGNANT above threshold it still
 * fires - that is exactly what it is for.
 */
internal object PersistentHighPolicy {

    /**
     * True while the value falls at least as fast as the configured magnitude
     * (mg/dl per minute; the rate estimate is unit-independent). 0 or unset
     * limit disables the suppression - regression to today's behaviour.
     */
    fun fallingSuppresses(rate: Float, fallRateSuppress: Float?): Boolean {
        val limit = fallRateSuppress ?: AlertDefaults.PERSISTENT_HIGH_FALL_RATE_MGDL_PER_MIN
        if (!limit.isFinite() || limit <= 0f) {
            return false
        }
        return rate.isFinite() && rate <= -limit
    }
}
