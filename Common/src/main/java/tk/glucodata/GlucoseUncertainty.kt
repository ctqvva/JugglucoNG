package tk.glucodata

/**
 * Sensor- and algorithm-agnostic uncertainty attached to a single glucose value.
 *
 * A CGM does not observe one perfectly known number every minute. Estimators
 * that can say how sure they are attach this; everything else leaves it null
 * and renders exactly as before.
 *
 * Bounds are in the same unit as the value they describe — mg/dL wherever the
 * app stores readings, display units only after conversion — and are
 * deliberately allowed to be asymmetric around the central value, because a
 * posterior that is unsure whether a dip is real is not symmetric.
 */
data class GlucoseUncertainty(
    /** Lower bound of the credible interval. */
    val lower: Float,
    /** Upper bound of the credible interval. */
    val upper: Float,
    /** Interval mass, e.g. 0.9 for a 90% credible interval. */
    val intervalMass: Float = DEFAULT_INTERVAL_MASS,
    /** Overall estimator confidence in [0,1], or null when not modelled. */
    val confidence: Float? = null,
    /** Posterior probability that the sample is dominated by a sensor artifact. */
    val artifactProbability: Float? = null,
) {
    val isUsable: Boolean
        get() = lower.isFinite() && upper.isFinite() && upper >= lower && lower > 0f

    /** Half-width of the interval; a convenience for renderers, not a sigma. */
    val halfWidth: Float get() = (upper - lower) / 2f

    fun scaled(factor: Float): GlucoseUncertainty =
        copy(lower = lower * factor, upper = upper * factor)

    companion object {
        const val DEFAULT_INTERVAL_MASS = 0.9f
    }
}
