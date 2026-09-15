package tk.glucodata.alerts

import tk.glucodata.TrendArrowAngle

/**
 * Suppression rule for the high alerts that are about a value not coming down:
 * PERSISTENT_HIGH, which says "your correction was not enough", and HIGH, which says
 * "you are high". A visible fall is the direct proof that the correction is working, so
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
 * correction is working. With the value FALLING the opted-in alert stays
 * silent; with the value STAGNANT above threshold it still fires.
 */
internal object FallSuppressionPolicy {

    /**
     * HIGH is off unless a positive value was saved, preserving the alarm for users who never
     * opted in. Once enabled, its boundary is the renderer's own boundary: if the rate paints
     * a downward arrow on the alarm screen, HIGH stays quiet. Treat the stored positive value
     * as the opt-in marker so existing non-zero settings acquire the promised semantics without
     * a preference migration.
     */
    fun highFallingSuppresses(rate: Float, fallRateSuppress: Float?): Boolean {
        fallRateSuppress?.takeIf { it.isFinite() && it > 0f } ?: return false
        return TrendArrowAngle.rotationDegrees(rate) > 0f
    }

    /**
     * PERSISTENT_HIGH is quiet while the value falls at least as fast as the configured
     * magnitude (mg/dl per minute; the rate estimate is unit-independent). 0 disables it;
     * unset uses that alert's default.
     */
    fun fallingSuppresses(rate: Float, fallRateSuppress: Float?): Boolean {
        val limit = fallRateSuppress ?: AlertDefaults.FALL_RATE_SUPPRESS_MGDL_PER_MIN
        if (!limit.isFinite() || limit <= 0f) {
            return false
        }
        return rate.isFinite() && rate <= -limit
    }
}
