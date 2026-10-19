package tk.glucodata.drivers.sibionics.adaptive2

import java.io.DataInputStream
import java.io.DataOutputStream
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * Front-end telemetry condensed into the two things it is allowed to influence:
 * how much to trust this observation, and how likely a sensor artifact is.
 *
 * Deliberately absent: any direct glucose correction. A resistance reading that
 * changed is not evidence that glucose is 0.3 mmol/L lower; it is evidence that
 * the sensor is less reliable right now.
 */
internal data class AdaptiveV2Telemetry(
    val temperatureQuality: Float,
    val impedanceQuality: Float,
    val impedanceDisturbance: Float,
    val vendorArtifactHint: Float,
    /** Multiplier applied to measurement variance; ≥ 1. */
    val noiseInflation: Float,
    val severe: Boolean,
)

/**
 * Derives [AdaptiveV2Telemetry] from raw front-end signals and tracks the
 * baselines they are measured against.
 */
internal class AdaptiveV2TelemetryModel {
    private var lastTemperature = Float.NaN
    private var impedanceBaseline = Float.NaN

    fun reset() {
        lastTemperature = Float.NaN
        impedanceBaseline = Float.NaN
    }

    fun evaluate(temperatureC: Float, impedance: Float, qualityFlags: Int): AdaptiveV2Telemetry {
        val temperatureStep = if (lastTemperature.isFinite() && temperatureC.isFinite()) {
            abs(temperatureC - lastTemperature)
        } else {
            0f
        }
        val temperatureQuality = when {
            !temperatureC.isFinite() -> 0.25f
            temperatureC in 28f..40f -> 1f
            temperatureC in 10f..42f -> 0.60f
            else -> 0.20f
        } * (1f / (1f + temperatureStep / TEMPERATURE_STEP_SCALE))

        val impedanceDisturbance = if (
            impedance.isFinite() && impedance > 0f && impedanceBaseline.isFinite()
        ) {
            (abs(impedance - impedanceBaseline) / max(abs(impedanceBaseline), 1f) /
                IMPEDANCE_DISTURBANCE_SCALE).coerceIn(0f, 1f)
        } else {
            0f
        }
        val impedanceQuality = (1f - IMPEDANCE_QUALITY_WEIGHT * impedanceDisturbance)
            .coerceIn(MIN_QUALITY, 1f)

        val vendorSevere = qualityFlags and VENDOR_SEVERE_MASK != 0
        val vendorWarning = qualityFlags and VENDOR_WARNING_MASK != 0
        val vendorArtifactHint = when {
            vendorSevere -> 1f
            vendorWarning -> 0.5f
            else -> 0f
        }

        // A temperature transient must raise measurement noise, not move
        // glucose: the front end's own compensation is momentarily unreliable.
        val noiseInflation = (
            (1f / max(temperatureQuality, MIN_QUALITY)) *
                (1f / max(impedanceQuality, MIN_QUALITY)) *
                (1f + VENDOR_NOISE_WEIGHT * vendorArtifactHint)
            ).coerceIn(1f, MAX_NOISE_INFLATION)

        return AdaptiveV2Telemetry(
            temperatureQuality = temperatureQuality.coerceIn(MIN_QUALITY, 1f),
            impedanceQuality = impedanceQuality,
            impedanceDisturbance = impedanceDisturbance,
            vendorArtifactHint = vendorArtifactHint,
            noiseInflation = noiseInflation,
            severe = vendorSevere || (temperatureC.isFinite() && temperatureC !in 10f..42f),
        )
    }

    /** Advances the baselines. Anomalous impedance is learned slowly so a real disturbance stays visible. */
    fun advance(temperatureC: Float, impedance: Float) {
        if (temperatureC.isFinite()) lastTemperature = temperatureC
        if (impedance.isFinite() && impedance > 0f) {
            impedanceBaseline = if (impedanceBaseline.isFinite()) {
                val relative = abs(impedance - impedanceBaseline) / max(abs(impedanceBaseline), 1f)
                val rate = if (relative > ANOMALY_FRACTION) SLOW_LEARNING_RATE else LEARNING_RATE
                impedanceBaseline * (1f - rate) + impedance * rate
            } else {
                impedance
            }
        }
    }

    fun writeTo(output: DataOutputStream) {
        output.writeFloat(lastTemperature)
        output.writeFloat(impedanceBaseline)
    }

    fun readFrom(input: DataInputStream) {
        lastTemperature = input.readFloat()
        impedanceBaseline = input.readFloat()
    }

    fun isValid(): Boolean = (lastTemperature.isNaN() || lastTemperature in -40f..90f) &&
        (impedanceBaseline.isNaN() || impedanceBaseline > 0f)

    private companion object {
        private const val TEMPERATURE_STEP_SCALE = 1.4f
        private const val IMPEDANCE_DISTURBANCE_SCALE = 0.35f
        private const val IMPEDANCE_QUALITY_WEIGHT = 0.75f
        private const val VENDOR_NOISE_WEIGHT = 3f
        private const val MAX_NOISE_INFLATION = 24f
        private const val MIN_QUALITY = 0.12f
        private const val ANOMALY_FRACTION = 0.5f
        private const val LEARNING_RATE = 0.04f
        private const val SLOW_LEARNING_RATE = 0.004f
        private const val VENDOR_SEVERE_MASK = 0x30
        private const val VENDOR_WARNING_MASK = 0x0F
    }
}

/**
 * Robust observation likelihood and bounded online noise adaptation.
 *
 * Sensor noise is not Gaussian — isolated bad samples are a normal part of CGM
 * operation. A Student-t likelihood keeps them influential-but-discounted
 * rather than either blindly accepted or thrown away, and, unlike a hard
 * rejection gate, it degrades smoothly as an outlier grows.
 */
internal class AdaptiveV2NoiseModel {
    /** Adapted baseline measurement variance, mmol²/L². */
    var measurementVariance = INITIAL_VARIANCE
        private set

    fun reset() {
        measurementVariance = INITIAL_VARIANCE
    }

    /**
     * Effective variance for this sample, before the robust weight.
     *
     * Sensor age matters: a fresh sensor is genuinely noisier and its factory
     * calibration is least settled, so early samples are trusted less.
     */
    fun observationVariance(telemetry: AdaptiveV2Telemetry, sensorAgeMinutes: Int): Double {
        val warmupFactor = if (sensorAgeMinutes >= WARMUP_MINUTES) {
            1.0
        } else {
            val progress = sensorAgeMinutes.toDouble() / WARMUP_MINUTES
            1.0 + WARMUP_NOISE_MULTIPLIER * (1.0 - progress)
        }
        return measurementVariance * telemetry.noiseInflation * warmupFactor
    }

    /**
     * Student-t robust weight w = (ν+1)/(ν+d²), where d² is the squared
     * normalised innovation. Dividing the variance by w is one IRLS step of the
     * t-likelihood and reduces to the Gaussian update when the residual is small.
     */
    fun robustWeight(normalisedSquared: Double): Double =
        (DEGREES_OF_FREEDOM + 1.0) / (DEGREES_OF_FREEDOM + max(normalisedSquared, 0.0))

    /** Log density of a Student-t innovation; used for the mode likelihoods. */
    fun logLikelihood(innovation: Double, innovationVariance: Double): Double {
        val s = max(innovationVariance, MIN_VARIANCE)
        val normalisedSquared = innovation * innovation / s
        return LOG_T_NORMALISER - 0.5 * ln(s) -
            0.5 * (DEGREES_OF_FREEDOM + 1.0) * ln(1.0 + normalisedSquared / DEGREES_OF_FREEDOM)
    }

    /**
     * Adapts the baseline variance toward the observed innovation statistics.
     *
     * Two guards matter more than the rate. The bounds stop the model from
     * learning an enormous variance and going permanently deaf; and adaptation
     * is suppressed while the artifact hypothesis is strong, so a transient
     * event is not mistaken for a permanent change in sensor noise.
     */
    fun adapt(
        innovation: Double,
        priorVariance: Double,
        artifactProbability: Float,
        learningEnabled: Boolean,
    ) {
        if (!learningEnabled || !innovation.isFinite() || !priorVariance.isFinite()) return
        val suppression = (1.0 - artifactProbability.toDouble()).coerceIn(0.0, 1.0)
        if (suppression < MIN_ADAPT_SUPPRESSION) return
        val residual = innovation * innovation - priorVariance
        val target = (measurementVariance + residual).coerceIn(MIN_VARIANCE, MAX_VARIANCE)
        val rate = ADAPT_RATE * suppression
        measurementVariance = (measurementVariance + rate * (target - measurementVariance))
            .coerceIn(MIN_VARIANCE, MAX_VARIANCE)
    }

    fun writeTo(output: DataOutputStream) = output.writeDouble(measurementVariance)

    fun readFrom(input: DataInputStream) {
        measurementVariance = input.readDouble()
    }

    fun isValid(): Boolean = measurementVariance.isFinite() &&
        measurementVariance in MIN_VARIANCE..MAX_VARIANCE

    companion object {
        /** ν = 4: heavy enough to survive real CGM outliers, light enough to stay efficient. */
        const val DEGREES_OF_FREEDOM = 4.0

        const val INITIAL_VARIANCE = 0.030
        const val MIN_VARIANCE = 0.004
        const val MAX_VARIANCE = 1.6

        private const val ADAPT_RATE = 0.012
        private const val MIN_ADAPT_SUPPRESSION = 0.45
        private const val WARMUP_MINUTES = 120
        private const val WARMUP_NOISE_MULTIPLIER = 3.0

        // log Γ((ν+1)/2) − log Γ(ν/2) − ½log(νπ) for ν = 4.
        private val LOG_T_NORMALISER = run {
            val nu = DEGREES_OF_FREEDOM
            lnGamma((nu + 1.0) / 2.0) - lnGamma(nu / 2.0) - 0.5 * ln(nu * Math.PI)
        }

        /** Lanczos approximation; evaluated once for the fixed ν above. */
        private fun lnGamma(x: Double): Double {
            val coefficients = doubleArrayOf(
                76.18009172947146, -86.50532032941677, 24.01409824083091,
                -1.231739572450155, 0.1208650973866179e-2, -0.5395239384953e-5,
            )
            var y = x
            val tmp = x + 5.5 - (x + 0.5) * ln(x + 5.5)
            var series = 1.000000000190015
            for (coefficient in coefficients) {
                y += 1.0
                series += coefficient / y
            }
            return -tmp + ln(2.5066282746310005 * series / x)
        }
    }
}

/**
 * Bounded estimate of the lag between the vendor-calibrated observation and
 * blood-equivalent glucose.
 *
 * τ is adapted, not filtered as a state, and only from evidence that can
 * actually identify it: a persistent signed correlation between the innovation
 * and the current glucose rate means the model is systematically early or late.
 * Everything else leaves τ at its prior.
 *
 * **The prior is measured, not assumed.** Driving the vendor core with steady
 * ramps and comparing its final output against the calibrated observation gives
 * its own effective lead: about 1.7–2.6 minutes on falling trajectories and
 * 5.8–7.3 on rising ones — deliberately asymmetric, because over-anticipating a
 * low is the dangerous direction. An earlier version of this model assumed a
 * physiological 8-minute blood↔interstitial lag and inverted it in full, which
 * is a four-fold over-extrapolation on falls: at 0.1 mmol/L/min it manufactured
 * 0.8 mmol/L of anticipatory drop where the manufacturer applies 0.2. The prior
 * and bounds below track the measured falling-direction lead, so V2 does no
 * more lag compensation than the vendor does, and the remaining uncertainty is
 * carried by the interval rather than by the median.
 */
internal class AdaptiveV2LagEstimator {
    var lagMinutes = PRIOR_LAG_MINUTES
        private set

    private var correlation = 0.0

    fun reset() {
        lagMinutes = PRIOR_LAG_MINUTES
        correlation = 0.0
    }

    /**
     * Deliberately does nothing to [lagMinutes].
     *
     * The previous rule adapted tau from the correlation between innovation and
     * glucose rate, reasoning that innovation running with the rate meant the
     * assumed lag was too long. That reasoning is wrong, and measurably so.
     * Innovation correlating with rate means the *interstitial state* is
     * trailing the observation, which is a Kalman-gain deficiency — a question
     * about Q against R — not a statement about tau. Reducing tau in response
     * removes the lead compensation and makes the output trail further, which
     * feeds the same correlation back in.
     *
     * On 31,773 real device samples it did exactly that: tau sat at 1.92
     * against a 5.5 prior, pinned to its floor, and V2 ran four minutes behind
     * both raw and the vendor output.
     *
     * Tau is not identifiable from one scalar observation per minute — it
     * trades off against sensitivity, offset and the filter's own bandwidth.
     * So it is held at the prior measured from the vendor's own effective
     * lead, and [lagVariance] carries the resulting uncertainty into the
     * posterior instead of pretending the value was learned.
     */
    fun update(
        @Suppress("UNUSED_PARAMETER") innovation: Double,
        @Suppress("UNUSED_PARAMETER") rate: Double,
        @Suppress("UNUSED_PARAMETER") dtMinutes: Double,
        @Suppress("UNUSED_PARAMETER") trust: Double,
    ) = Unit

    fun writeTo(output: DataOutputStream) {
        output.writeDouble(lagMinutes)
        output.writeDouble(correlation)
    }

    fun readFrom(input: DataInputStream) {
        lagMinutes = input.readDouble()
        correlation = input.readDouble()
    }

    fun isValid(): Boolean = lagMinutes.isFinite() && lagMinutes in MIN_LAG_MINUTES..MAX_LAG_MINUTES &&
        correlation.isFinite() && abs(correlation) <= CORRELATION_CLAMP * 1.001

    /**
     * Variance of the lag estimate, in minutes squared.
     *
     * Propagated into the reported glucose variance as v²·var(τ), so a fast
     * trajectory under a poorly-known lag widens the interval instead of
     * letting the median make a confident anticipatory excursion.
     */
    val lagVariance: Double
        get() {
            val span = MAX_LAG_MINUTES - MIN_LAG_MINUTES
            return (PRIOR_LAG_UNCERTAINTY * span) * (PRIOR_LAG_UNCERTAINTY * span)
        }

    companion object {
        /** Matches the vendor's measured falling-direction lead; see the class doc. */
        /**
         * The vendor deconvolution's own effective lead over the calibrated
         * observation, regressed from (stock - calibrated) against
         * d(calibrated)/dt on content with realistic sharp excursions: 5.76 min
         * on V1.1.5G and 7.62 on V1.1.6A. An earlier value of 3.0 came from
         * fitting slow ramps, where the deconvolution barely engages.
         */
        const val PRIOR_LAG_MINUTES = 5.5
        const val MIN_LAG_MINUTES = 2.0
        const val MAX_LAG_MINUTES = 12.0
        /** Fraction of the admissible span treated as one-sigma uncertainty on τ. */
        private const val PRIOR_LAG_UNCERTAINTY = 0.22

        private const val MIN_RATE_FOR_IDENTIFIABILITY = 0.02
        private const val CORRELATION_SMOOTHING = 0.05
        private const val CORRELATION_DECAY = 0.98
        private const val CORRELATION_CLAMP = 0.5
        private const val ADAPT_RATE = 6.0
        private const val SHRINK_RATE = 0.004
    }
}

/**
 * The sensor observation model: z = s·I + b + A + ε.
 *
 * The chemical signal arriving from the vendor front end is already normalised
 * into glucose units by the decoded factory sensitivity, so [V2.LOG_S] is
 * defined *around* that — zero means "the factory value is right" — rather than
 * re-applying the scaling. Its prior is therefore tight by construction.
 */
internal object AdaptiveV2ObservationModel {
    /**
     * The calibrated observation constrains the *displayed* level directly.
     *
     * It used to read [V2.I], the interstitial compartment, which sits behind
     * [V2.B] through a first-order lag. That put a low-pass stage in front of
     * the output: the observation could only reach the displayed state through
     * the B/I covariance, so each minute's movement arrived attenuated and the
     * remainder arrived later. Measured on the device trace, the median
     * same-minute step response was 0.85 and a fifth of all moving minutes
     * delivered under half their move — the temporal smearing seen at 14:18,
     * where the observation moved +0.46 and the estimate +0.24.
     *
     * No smoothing was ever asked for. Reading [V2.B] makes a valid new minute
     * constrain the level on that minute, at full gain. Drift correction,
     * artifact rejection and uncertainty all still act here; what is gone is
     * the compartment that made a current observation arrive late.
     *
     * [V2.I] remains in the state as the trailing interstitial estimate, which
     * is what a lead correction is computed against — a correction applied on
     * top of a current level, never a filter in front of it.
     */
    fun jacobian(out: DoubleArray, state: DoubleArray) {
        val sensitivity = exp(state[V2.LOG_S].coerceIn(MIN_LOG_S, MAX_LOG_S))
        out.fill(0.0)
        out[V2.B] = sensitivity
        out[V2.LOG_S] = sensitivity * state[V2.B]
        out[V2.BIAS] = 1.0
        out[V2.ARTIFACT] = 1.0
    }

    fun predicted(state: DoubleArray): Double {
        val sensitivity = exp(state[V2.LOG_S].coerceIn(MIN_LOG_S, MAX_LOG_S))
        return sensitivity * state[V2.B] + state[V2.BIAS] + state[V2.ARTIFACT]
    }

    /** Reference (fingerstick) anchors observe blood-equivalent glucose directly. */
    fun referenceJacobian(out: DoubleArray) {
        out.fill(0.0)
        out[V2.B] = 1.0
    }

    fun clampSensorStates(state: DoubleArray) {
        state[V2.LOG_S] = state[V2.LOG_S].coerceIn(MIN_LOG_S, MAX_LOG_S)
        state[V2.BIAS] = state[V2.BIAS].coerceIn(MIN_BIAS, MAX_BIAS)
        state[V2.ARTIFACT] = state[V2.ARTIFACT].coerceIn(MIN_ARTIFACT, MAX_ARTIFACT)
        state[V2.B] = state[V2.B].coerceIn(MIN_GLUCOSE, MAX_GLUCOSE)
        state[V2.I] = state[V2.I].coerceIn(MIN_GLUCOSE, MAX_GLUCOSE)
        state[V2.V] = state[V2.V].coerceIn(-MAX_RATE, MAX_RATE)
        state[V2.ACC] = state[V2.ACC].coerceIn(-MAX_ACCELERATION, MAX_ACCELERATION)
    }

    /** ±25% sensitivity: beyond this the sensor is not usable, not mis-calibrated. */
    private const val MIN_LOG_S = -0.29
    private const val MAX_LOG_S = 0.22
    private const val MIN_BIAS = -2.5
    private const val MAX_BIAS = 2.5
    private const val MIN_ARTIFACT = -8.0
    private const val MAX_ARTIFACT = 8.0

    const val MIN_GLUCOSE = 0.6
    const val MAX_GLUCOSE = 35.0
    private const val MAX_RATE = 0.8
    private const val MAX_ACCELERATION = 0.25

    fun isStateValid(state: DoubleArray): Boolean {
        for (value in state) if (!value.isFinite()) return false
        return state[V2.B] in MIN_GLUCOSE..MAX_GLUCOSE &&
            state[V2.I] in MIN_GLUCOSE..MAX_GLUCOSE &&
            abs(state[V2.V]) <= MAX_RATE * 1.001 &&
            state[V2.LOG_S] in MIN_LOG_S * 1.001..MAX_LOG_S * 1.001 &&
            state[V2.BIAS] in MIN_BIAS * 1.001..MAX_BIAS * 1.001
    }

    fun sensitivityOf(state: DoubleArray): Float =
        exp(state[V2.LOG_S].coerceIn(MIN_LOG_S, MAX_LOG_S)).toFloat()

    fun softLimit(value: Double, limit: Double): Double = min(abs(value), limit) * (if (value < 0) -1.0 else 1.0)
}
