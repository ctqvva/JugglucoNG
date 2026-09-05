package tk.glucodata.alerts

/**
 * Suppression rule for the high alerts that are about a value not coming down:
 * PERSISTENT_HIGH, which says "your correction was not enough", and HIGH, which says
 * "you are high". A steep fall is the direct proof that the correction is working, so
 * firing during one announces the opposite of what the curve shows (field cases:
 * "persistent high" at 226 mg/dl under a double down arrow, and a plain "high" arriving
 * while the value dropped eighteen in a minute).
 *
 * VERY_HIGH is deliberately not among them: there the number itself is the problem, and
 * which way it is moving does not change that.
 *
 * Deliberately rate-based, no insulin arithmetic: unlike the PRE_HIGH
 * coverage check ([ForecastIobCoverage]), which reasons about a PREDICTION,
 * the observed direction suffices here - the falling value already proves the
 * insulin is working. With IOB active and the value FALLING the alert stays
 * silent; with IOB active and the value STAGNANT above threshold it still
 * fires - that is exactly what it is for.
 */
internal object FallSuppressionPolicy {

    /**
     * True while the value falls at least as fast as the configured magnitude
     * (mg/dl per minute; the rate estimate is unit-independent). 0 or unset
     * limit disables the suppression - regression to today's behaviour.
     */
    /**
     * The same rule for HIGH, with two differences that matter for the alert somebody relies
     * on to hear about a high at all: it is off unless a limit was set, and no limit quieter
     * than [AlertDefaults.HIGH_FALL_RATE_FLOOR_MGDL_PER_MIN] counts, so a value merely
     * drifting can never take this alarm away.
     */
    fun highFallingSuppresses(rate: Float, fallRateSuppress: Float?): Boolean {
        val asked = fallRateSuppress?.takeIf { it.isFinite() && it > 0f } ?: return false
        return fallingSuppresses(rate, asked.coerceAtLeast(AlertDefaults.HIGH_FALL_RATE_FLOOR_MGDL_PER_MIN))
    }

    fun fallingSuppresses(rate: Float, fallRateSuppress: Float?): Boolean {
        val limit = fallRateSuppress ?: AlertDefaults.FALL_RATE_SUPPRESS_MGDL_PER_MIN
        if (!limit.isFinite() || limit <= 0f) {
            return false
        }
        return rate.isFinite() && rate <= -limit
    }
}
