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
    /**
     * Posterior mass the interval carries, e.g. 0.9.
     *
     * This is the estimator's *model* probability, not a validated empirical
     * coverage rate — a mathematically computed 90% posterior interval only
     * contains the truth 90% of the time if the model is well specified, which
     * has not been established against paired reference data on device. User-
     * facing copy says "likely range" rather than "90% confidence" for exactly
     * this reason, and should keep doing so until validation exists.
     */
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

    /**
     * Moves the interval without changing its width.
     *
     * For use when the value it describes is displaced by a later stage —
     * calibration, graph smoothing — so the band keeps surrounding the line
     * actually drawn. The width is the estimator's claim and is preserved; only
     * its position follows the value.
     */
    fun shifted(delta: Float): GlucoseUncertainty =
        if (delta == 0f || !delta.isFinite()) this else copy(lower = lower + delta, upper = upper + delta)

    companion object {
        const val DEFAULT_INTERVAL_MASS = 0.9f
    }
}
