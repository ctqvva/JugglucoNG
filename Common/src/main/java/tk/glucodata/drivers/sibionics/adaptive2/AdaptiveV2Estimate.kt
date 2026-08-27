package tk.glucodata.drivers.sibionics.adaptive2

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * One emitted Adaptive V2 sample: where glucose most likely is, and how sure
 * the estimator is about that.
 *
 * The interval is a genuine mixture quantile pair and is allowed to be
 * asymmetric around [glucoseMmol] — when the estimator is torn between "this
 * dip is an artifact" and "this dip is real", the honest posterior is
 * lopsided, and flattening it to `mean ± 1.645σ` would throw away exactly the
 * information the interval exists to carry.
 */
data class ProbabilisticGlucoseEstimate(
    /** Central estimate: the posterior *median* of the mixture. See [GaussianMixture1D.median]. */
    val glucoseMmol: Float,
    val lower90Mmol: Float,
    val upper90Mmol: Float,
    val rateMmolPerMin: Float,
    /** Posterior standard deviation of the rate, in mmol/L/min. */
    val rateUncertainty: Float,
    /** P(rate < 0) under the posterior; directional confidence, not a sign test. */
    val fallingProbability: Float,
    val steadyProbability: Float,
    val dynamicProbability: Float,
    val artifactProbability: Float,
    val driftProbability: Float,
    /** Overall confidence in [0,1], derived from interval sharpness. */
    val confidence: Float,
) {
    val isUsable: Boolean
        get() = glucoseMmol.isFinite() && glucoseMmol > 0f &&
            lower90Mmol.isFinite() && upper90Mmol.isFinite() &&
            upper90Mmol >= lower90Mmol

    /** Posterior probability that glucose is below [thresholdMmol]. */
    fun probabilityBelow(thresholdMmol: Float, mixture: GaussianMixture1D): Float =
        mixture.cdf(thresholdMmol.toDouble()).toFloat()
}

/**
 * A one-dimensional Gaussian mixture — the marginal of the IMM posterior over a
 * single state element.
 *
 * The mixture is kept intact rather than moment-matched because the interesting
 * case is exactly the multimodal one: "70% the glucose is around 4.0 and the dip
 * is an artifact, 30% it is genuinely 2.9". A moment-matched Gaussian reports a
 * mean nobody believes in, sitting in the empty valley between the two modes.
 */
class GaussianMixture1D(
    val weights: FloatArray,
    val means: FloatArray,
    val variances: FloatArray,
) {
    init {
        require(weights.size == means.size && means.size == variances.size) {
            "mixture component arrays must agree in size"
        }
    }

    val size: Int get() = weights.size

    val mean: Float
        get() {
            var sum = 0.0
            for (i in weights.indices) sum += weights[i] * means[i]
            return sum.toFloat()
        }

    /** Total variance including the between-component spread. */
    val variance: Float
        get() {
            val m = mean.toDouble()
            var sum = 0.0
            for (i in weights.indices) {
                val d = means[i] - m
                sum += weights[i] * (variances[i] + d * d)
            }
            return sum.toFloat()
        }

    val standardDeviation: Float get() = sqrt(max(variance, 0f))

    fun cdf(x: Double): Double {
        var sum = 0.0
        for (i in weights.indices) {
            val sd = sqrt(max(variances[i].toDouble(), MIN_VARIANCE))
            sum += weights[i] * normalCdf((x - means[i]) / sd)
        }
        return sum.coerceIn(0.0, 1.0)
    }

    /**
     * Mixture quantile by bisection on the CDF.
     *
     * A closed form does not exist for a mixture; bisection over a bracket
     * derived from the component supports converges to well below display
     * resolution in a fixed, allocation-free number of steps.
     */
    fun quantile(p: Double): Float {
        if (size == 0) return Float.NaN
        if (size == 1) {
            val sd = sqrt(max(variances[0].toDouble(), MIN_VARIANCE))
            return (means[0] + sd * normalQuantile(p)).toFloat()
        }
        var low = Double.MAX_VALUE
        var high = -Double.MAX_VALUE
        for (i in weights.indices) {
            val sd = sqrt(max(variances[i].toDouble(), MIN_VARIANCE))
            low = min(low, means[i] - BRACKET_SIGMA * sd)
            high = max(high, means[i] + BRACKET_SIGMA * sd)
        }
        repeat(QUANTILE_ITERATIONS) {
            val mid = 0.5 * (low + high)
            if (cdf(mid) < p) low = mid else high = mid
        }
        return (0.5 * (low + high)).toFloat()
    }

    /**
     * Posterior median. Preferred over the mean as the reported value: for a
     * bimodal posterior the median lands inside whichever hypothesis currently
     * carries most of the mass, while the mean lands between them on a value
     * the model assigns little probability to.
     */
    fun median(): Float = quantile(0.5)

    companion object {
        private const val MIN_VARIANCE = 1e-8
        private const val BRACKET_SIGMA = 8.0
        private const val QUANTILE_ITERATIONS = 44

        fun normalCdf(z: Double): Double = 0.5 * (1.0 + erf(z / SQRT2))

        /** Abramowitz & Stegun 7.1.26; max absolute error 1.5e-7, ample here. */
        fun erf(x: Double): Double {
            val sign = if (x < 0) -1.0 else 1.0
            val a = abs(x)
            val t = 1.0 / (1.0 + 0.3275911 * a)
            val y = 1.0 - (((((1.061405429 * t - 1.453152027) * t) + 1.421413741) * t -
                0.284496736) * t + 0.254829592) * t * exp(-a * a)
            return sign * y
        }

        /** Acklam's inverse normal CDF approximation; used only for the single-component path. */
        fun normalQuantile(p: Double): Double {
            val clamped = p.coerceIn(1e-9, 1.0 - 1e-9)
            val a = doubleArrayOf(
                -3.969683028665376e+01, 2.209460984245205e+02, -2.759285104469687e+02,
                1.383577518672690e+02, -3.066479806614716e+01, 2.506628277459239e+00,
            )
            val b = doubleArrayOf(
                -5.447609879822406e+01, 1.615858368580409e+02, -1.556989798598866e+02,
                6.680131188771972e+01, -1.328068155288572e+01,
            )
            val c = doubleArrayOf(
                -7.784894002430293e-03, -3.223964580411365e-01, -2.400758277161838e+00,
                -2.549732539343734e+00, 4.374664141464968e+00, 2.938163982698783e+00,
            )
            val d = doubleArrayOf(
                7.784695709041462e-03, 3.224671290700398e-01, 2.445134137142996e+00,
                3.754408661907416e+00,
            )
            val pLow = 0.02425
            return when {
                clamped < pLow -> {
                    val q = sqrt(-2.0 * kotlin.math.ln(clamped))
                    (((((c[0] * q + c[1]) * q + c[2]) * q + c[3]) * q + c[4]) * q + c[5]) /
                        ((((d[0] * q + d[1]) * q + d[2]) * q + d[3]) * q + 1.0)
                }
                clamped > 1.0 - pLow -> -normalQuantile(1.0 - clamped)
                else -> {
                    val q = clamped - 0.5
                    val r = q * q
                    (((((a[0] * r + a[1]) * r + a[2]) * r + a[3]) * r + a[4]) * r + a[5]) * q /
                        (((((b[0] * r + b[1]) * r + b[2]) * r + b[3]) * r + b[4]) * r + 1.0)
                }
            }
        }

        private val SQRT2 = sqrt(2.0)
    }
}

/**
 * Per-sample developer trace. Stock glucose appears here strictly as a
 * comparison column — it is never an input to the estimator.
 */
data class AdaptiveV2Diagnostics(
    val index: Int,
    val timestampMs: Long,
    val chemicalMmol: Float,
    val glucoseMmol: Float,
    val lower90Mmol: Float,
    val upper90Mmol: Float,
    val rateMmolPerMin: Float,
    val rateUncertainty: Float,
    val steadyProbability: Float,
    val dynamicProbability: Float,
    val artifactProbability: Float,
    val driftProbability: Float,
    val sensitivity: Float,
    val biasMmol: Float,
    val lagMinutes: Float,
    val measurementNoise: Float,
    val innovation: Float,
    val temperatureQuality: Float,
    val impedanceQuality: Float,
    val interstitialMmol: Float,
    val artifactMmol: Float,
    /** Vendor absolute sensor-state compensation applied to this observation. */
    val sensorStateCompensationMmol: Float,
    /** Vendor active sensitivity, which tracks drift. */
    val activeSensitivity: Float,
    /** Comparison trace only. Never fed back into the estimator. */
    val stockMmol: Float,
) {
    fun toCsvRow(): String = listOf(
        index, timestampMs, chemicalMmol, glucoseMmol, lower90Mmol, upper90Mmol,
        rateMmolPerMin, rateUncertainty, steadyProbability, dynamicProbability,
        artifactProbability, driftProbability, sensitivity, biasMmol, lagMinutes,
        measurementNoise, innovation, temperatureQuality, impedanceQuality,
        interstitialMmol, artifactMmol, sensorStateCompensationMmol, activeSensitivity,
        stockMmol,
    ).joinToString(",")

    companion object {
        const val CSV_HEADER =
            "index,timestampMs,chemical,glucose,lower90,upper90,rate,rateSd," +
                "pSteady,pDynamic,pArtifact,pDrift,sensitivity,bias,lagMinutes," +
                "measurementNoise,innovation,tempQuality,impedanceQuality," +
                "interstitial,artifact,sensorStateCompensation,activeSensitivity," +
                "stockComparison"
    }
}
