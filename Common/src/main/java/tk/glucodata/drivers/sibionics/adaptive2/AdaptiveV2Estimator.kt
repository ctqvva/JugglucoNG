package tk.glucodata.drivers.sibionics.adaptive2

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** One minute of sensor-specific input. Note the absence of any stock glucose field. */
internal data class AdaptiveV2Sample(
    /**
     * Vendor-calibrated observation on the absolute glucose scale: the front-end
     * signal with the manufacturer's sensor-state compensation applied, but
     * before the display filtering and deconvolution V2 replaces.
     */
    val calibratedMmol: Float,
    /** Factory/QR sensitivity decoded at pairing. */
    val factorySensitivity: Float,
    /** The vendor's running sensitivity estimate, which tracks drift. */
    val activeSensitivity: Float,
    /** Absolute sensor-state compensation the vendor applied, for diagnostics. */
    val sensorStateCompensationMmol: Float,
    val temperatureC: Float,
    val impedance: Float,
    val qualityFlags: Int,
    /** Sensor minute index; doubles as sensor age. */
    val index: Int,
    val timestampMs: Long,
) {
    /**
     * How far the vendor's own sensitivity estimate has drifted from the
     * factory value, in log units.
     *
     * Diagnostics only, and deliberately *not* a shrinkage target. Because the
     * observation arrives already normalised by the vendor's active
     * sensitivity, [V2.LOG_S] is a residual: zero means "the manufacturer's
     * current estimate is right". Shrinking it toward zero therefore shrinks
     * toward the vendor's live belief, which moves on its own — so the state
     * only ever has to explain the small deviation the vendor has not captured,
     * rather than inventing a whole calibration from one scalar per minute.
     */
    val vendorSensitivityDrift: Double
        get() = if (
            factorySensitivity.isFinite() && factorySensitivity > 0f &&
            activeSensitivity.isFinite() && activeSensitivity > 0f
        ) {
            ln(activeSensitivity.toDouble() / factorySensitivity.toDouble())
        } else {
            0.0
        }
}

/** A validated external reference measurement, e.g. a fingerstick. */
internal data class AdaptiveV2Reference(
    val glucoseMmol: Float,
    val timestampMs: Long,
)

/**
 * Adaptive V2: an independent probabilistic estimator of sensor state.
 *
 * It does not use final Sibionics stock glucose as a target, anchor,
 * regulariser or hidden truth source. Its glucose observation is the vendor's
 * sensor-state-calibrated signal — the front end with the manufacturer's
 * sensitivity, drift and compensation knowledge applied, but *before* the
 * display filtering and deconvolution this model replaces — plus genuine sensor
 * metadata (factory and active sensitivity, algorithm family, sensor age,
 * temperature, impedance, front-end quality flags) and, when the user provides
 * them, external calibration references.
 *
 * Keeping the manufacturer's sensor-state compensation is not a compromise of
 * independence, it is a precondition for it: an earlier version observed the
 * raw pre-compensation signal and reported roughly 2 mmol/L low on every real
 * sensor, turning ordinary days into hours of fictitious hypoglycaemia.
 *
 * Structure: an interacting-multiple-model (IMM) bank of four
 * [AdaptiveV2Mode]s, each an EKF over the state in [V2], sharing a robust
 * Student-t observation likelihood. The output is the resulting Gaussian
 * mixture — kept as a mixture, so the credible interval can be genuinely
 * asymmetric when the estimator is torn between "real low" and "sensor
 * artifact".
 *
 * There is no artificial low floor anywhere in this class. Values below
 * 3 mmol/L are reachable; they are simply expensive, because reaching them
 * requires the rate and acceleration states to move, and their process noise
 * makes an implausible trajectory improbable rather than forbidden.
 */
internal class AdaptiveV2Estimator {

    private val modes = Array(AdaptiveV2Mode.COUNT) { AdaptiveV2Gaussian() }
    private val mixed = Array(AdaptiveV2Mode.COUNT) { AdaptiveV2Gaussian() }
    private val modeProbability = FloatArray(AdaptiveV2Mode.COUNT)

    private val telemetryModel = AdaptiveV2TelemetryModel()
    private val noiseModel = AdaptiveV2NoiseModel()
    private val lagEstimator = AdaptiveV2LagEstimator()

    private val transitionMatrix = DoubleArray(V2.N * V2.N)
    private val processNoise = DoubleArray(V2.N)
    private val glucoseNoiseBlock = DoubleArray(9)
    private val controlInput = DoubleArray(V2.N)
    private val jacobian = DoubleArray(V2.N)
    private val transitionRow = DoubleArray(AdaptiveV2Mode.COUNT)
    private val mixingWeights = DoubleArray(AdaptiveV2Mode.COUNT * AdaptiveV2Mode.COUNT)
    private val modeLogLikelihood = DoubleArray(AdaptiveV2Mode.COUNT)
    private val predictedMode = DoubleArray(AdaptiveV2Mode.COUNT)

    private val glucoseWeights = FloatArray(AdaptiveV2Mode.COUNT)
    private val glucoseMeans = FloatArray(AdaptiveV2Mode.COUNT)
    private val glucoseVariances = FloatArray(AdaptiveV2Mode.COUNT)
    private val rateMeans = FloatArray(AdaptiveV2Mode.COUNT)
    private val rateVariances = FloatArray(AdaptiveV2Mode.COUNT)

    private var factorySensitivity = DEFAULT_SENSITIVITY
    private var initialized = false
    private var lastIndex = -1
    private var lastTimestampMs = 0L
    private var lastReferenceTimestampMs = 0L
    private var lastInnovation = 0.0
    private var lastMeasurementVariance = 0.0

    /** Vendor sensitivity drift for the diagnostics trace; see [AdaptiveV2Sample.vendorSensitivityDrift]. */
    private var vendorSensitivityDrift = 0.0

    /**
     * Shrinkage targets for the slow sensor states.
     *
     * These start at zero — "the manufacturer's live estimate is right" — and
     * are pulled toward whatever an external reference established. Without
     * them a valid fingerstick correction evaporates over a day or two as
     * [V2.BIAS] and [V2.LOG_S] mean-revert to a factory value the reference
     * just demonstrated to be wrong.
     */
    private var biasPrior = 0.0
    private var logSensitivityPrior = 0.0

    /** Last emitted posterior, exposed for diagnostics and probability queries. */
    var latestGlucoseMixture: GaussianMixture1D? = null
        private set
    var latestEstimate: ProbabilisticGlucoseEstimate? = null
        private set
    var latestDiagnostics: AdaptiveV2Diagnostics? = null
        private set

    fun configure(sensitivity: Float) {
        val normalized = sensitivity.takeIf { it.isFinite() && it in 0.5f..3.5f } ?: DEFAULT_SENSITIVITY
        if (abs(normalized - factorySensitivity) > 1e-6f) {
            factorySensitivity = normalized
            reset()
        }
    }

    fun reset() {
        modes.forEach { it.reset() }
        mixed.forEach { it.reset() }
        modeProbability.fill(0f)
        telemetryModel.reset()
        noiseModel.reset()
        lagEstimator.reset()
        initialized = false
        lastIndex = -1
        lastTimestampMs = 0L
        lastReferenceTimestampMs = 0L
        lastInnovation = 0.0
        lastMeasurementVariance = 0.0
        vendorSensitivityDrift = 0.0
        biasPrior = 0.0
        logSensitivityPrior = 0.0
        latestGlucoseMixture = null
        latestEstimate = null
        latestDiagnostics = null
    }

    /** Last sample index represented by the current state, or null when uninitialised. */
    fun continuationIndex(): Int? = lastIndex.takeIf { initialized && it >= 0 }

    /**
     * Advances the estimator by one sample.
     *
     * @param stockComparisonMmol recorded in diagnostics only; never read by the model.
     * @return the posterior estimate, or null when the sample cannot be used.
     */
    fun process(
        sample: AdaptiveV2Sample,
        references: List<AdaptiveV2Reference> = emptyList(),
        stockComparisonMmol: Float = Float.NaN,
        adaptiveV1ComparisonMmol: Float = Float.NaN,
    ): ProbabilisticGlucoseEstimate? {
        val observation = sample.calibratedMmol
        if (!observation.isFinite() || observation <= 0f) return null

        val telemetry = telemetryModel.evaluate(sample.temperatureC, sample.impedance, sample.qualityFlags)

        if (!initialized || sample.index <= lastIndex) {
            initialize(observation, sample)
            telemetryModel.advance(sample.temperatureC, sample.impedance)
            return latestEstimate
        }

        val elapsed = elapsedMinutes(sample)
        if (elapsed.reset) {
            // Beyond the reinitialisation threshold the propagated posterior is
            // so wide it carries no information, and the sensor may have been
            // reinserted. Starting clean is honest; pretending to have tracked
            // through it is not.
            initialize(observation, sample)
            telemetryModel.advance(sample.temperatureC, sample.impedance)
            return latestEstimate
        }
        // A gap is propagated in bounded substeps rather than as one long jump.
        // The transition matrix, the process noise and the mode-transition
        // matrix are all defined per minute, and none of the three composes
        // correctly over a long step: Q(dt) != Q(1)*dt for coupled B/v/a, and a
        // 20-minute gap must not leave mode identity as persistent as one
        // minute would.
        var remaining = elapsed.minutes
        while (remaining > 1e-6) {
            val step = min(remaining, MAX_SUBSTEP_MINUTES)
            mix(telemetry, step)
            predict(step)
            remaining -= step
        }

        val observationVariance = noiseModel.observationVariance(telemetry, sample.index)
        vendorSensitivityDrift = sample.vendorSensitivityDrift
        updateWithChemical(observation.toDouble(), observationVariance)
        applyReferences(references, sample)
        normalizeModeProbabilities()

        val estimate = combine()
        lagEstimator.update(
            innovation = lastInnovation,
            rate = estimate.rateMmolPerMin.toDouble(),
            dtMinutes = elapsed.minutes,
            trust = (1.0 - estimate.artifactProbability.toDouble()).coerceIn(0.0, 1.0),
        )
        noiseModel.adapt(
            innovation = lastInnovation,
            priorVariance = lastMeasurementVariance,
            artifactProbability = estimate.artifactProbability,
            learningEnabled = !telemetry.severe,
        )

        telemetryModel.advance(sample.temperatureC, sample.impedance)
        lastIndex = sample.index
        lastTimestampMs = sample.timestampMs
        latestDiagnostics = buildDiagnostics(
            sample, estimate, telemetry, stockComparisonMmol, adaptiveV1ComparisonMmol,
        )
        return estimate
    }

    // ── IMM cycle ──────────────────────────────────────────────────────────

    private fun mix(telemetry: AdaptiveV2Telemetry, dtMinutes: Double) {
        // Predicted mode probabilities and mixing weights.
        predictedMode.fill(0.0)
        for (from in 0 until AdaptiveV2Mode.COUNT) {
            AdaptiveV2ModeModel.transition(
                AdaptiveV2Mode.ALL[from],
                telemetry.impedanceDisturbance,
                telemetry.vendorArtifactHint,
                dtMinutes,
                transitionRow,
            )
            for (to in 0 until AdaptiveV2Mode.COUNT) {
                val weight = modeProbability[from] * transitionRow[to]
                mixingWeights[from * AdaptiveV2Mode.COUNT + to] = weight
                predictedMode[to] += weight
            }
        }
        for (to in 0 until AdaptiveV2Mode.COUNT) {
            val total = max(predictedMode[to], MIN_PROBABILITY)
            val target = mixed[to]
            target.reset()
            for (from in 0 until AdaptiveV2Mode.COUNT) {
                val weight = mixingWeights[from * AdaptiveV2Mode.COUNT + to] / total
                if (weight <= 0.0) continue
                for (i in 0 until V2.N) target.x[i] += weight * modes[from].x[i]
            }
            for (from in 0 until AdaptiveV2Mode.COUNT) {
                val weight = mixingWeights[from * AdaptiveV2Mode.COUNT + to] / total
                if (weight <= 0.0) continue
                val source = modes[from]
                for (row in 0 until V2.N) {
                    val dRow = source.x[row] - target.x[row]
                    for (column in 0 until V2.N) {
                        val dColumn = source.x[column] - target.x[column]
                        target.p[row * V2.N + column] += weight *
                            (source.p[row * V2.N + column] + dRow * dColumn)
                    }
                }
            }
            target.symmetrize()
        }
    }

    private fun predict(dtMinutes: Double) {
        AdaptiveV2Transition.build(transitionMatrix, dtMinutes, lagEstimator.lagMinutes)
        // The sensor states relax toward their learned priors rather than zero.
        controlInput.fill(0.0)
        controlInput[V2.BIAS] =
            (1.0 - transitionMatrix[V2.BIAS * V2.N + V2.BIAS]) * biasPrior
        controlInput[V2.LOG_S] =
            (1.0 - transitionMatrix[V2.LOG_S * V2.N + V2.LOG_S]) * logSensitivityPrior
        for (index in 0 until AdaptiveV2Mode.COUNT) {
            AdaptiveV2ModeModel.processNoise(AdaptiveV2Mode.ALL[index], dtMinutes, processNoise)
            AdaptiveV2ModeModel.glucoseBlock(AdaptiveV2Mode.ALL[index], dtMinutes, glucoseNoiseBlock)
            modes[index].copyFrom(mixed[index])
            modes[index].predict(transitionMatrix, processNoise, glucoseNoiseBlock, controlInput)
        }
    }

    private fun updateWithChemical(observation: Double, observationVariance: Double) {
        var representativeInnovation = 0.0
        var representativeVariance = 0.0
        for (index in 0 until AdaptiveV2Mode.COUNT) {
            val mode = modes[index]
            AdaptiveV2ObservationModel.jacobian(jacobian, mode.x)
            val innovation = observation - AdaptiveV2ObservationModel.predicted(mode.x)

            var priorVariance = observationVariance
            for (row in 0 until V2.N) {
                var sum = 0.0
                for (column in 0 until V2.N) sum += mode.p[row * V2.N + column] * jacobian[column]
                priorVariance += jacobian[row] * sum
            }
            priorVariance = max(priorVariance, MIN_VARIANCE)

            // One IRLS step of the Student-t likelihood: down-weight, do not reject.
            val weight = noiseModel.robustWeight(innovation * innovation / priorVariance)
            val effectiveVariance = observationVariance / max(weight, MIN_ROBUST_WEIGHT)
            val innovationVariance = mode.update(jacobian, innovation, effectiveVariance)
            AdaptiveV2ObservationModel.clampSensorStates(mode.x)

            modeLogLikelihood[index] = noiseModel.logLikelihood(innovation, priorVariance)
            val probabilityWeight = modeProbability[index].toDouble()
            representativeInnovation += probabilityWeight * innovation
            representativeVariance += probabilityWeight * innovationVariance
        }
        lastInnovation = representativeInnovation
        lastMeasurementVariance = max(representativeVariance, MIN_VARIANCE)

        // Mode posterior ∝ predicted prior × likelihood.
        var maximum = Double.NEGATIVE_INFINITY
        for (index in 0 until AdaptiveV2Mode.COUNT) maximum = max(maximum, modeLogLikelihood[index])
        var total = 0.0
        for (index in 0 until AdaptiveV2Mode.COUNT) {
            val value = max(predictedMode[index], MIN_PROBABILITY) *
                exp(modeLogLikelihood[index] - maximum)
            modeProbability[index] = value.toFloat()
            total += value
        }
        if (total <= 0.0 || !total.isFinite()) {
            modeProbability.fill(1f / AdaptiveV2Mode.COUNT)
        } else {
            for (index in 0 until AdaptiveV2Mode.COUNT) {
                modeProbability[index] = (modeProbability[index] / total.toFloat())
            }
        }
    }

    /**
     * Reference anchors observe the latent blood-equivalent state directly.
     *
     * They are never applied by first calibrating a stock value: a fingerstick
     * is an observation of glucose, so it enters as one. Because it updates the
     * posterior rather than adding an offset, a consistent anchor also tightens
     * the interval and helps identify sensitivity and bias, and its influence
     * decays naturally through later state evolution instead of being
     * explicitly aged out.
     */
    private fun applyReferences(references: List<AdaptiveV2Reference>, sample: AdaptiveV2Sample) {
        if (references.isEmpty()) return
        val pending = references.filter {
            it.glucoseMmol.isFinite() && it.glucoseMmol in 1f..35f &&
                it.timestampMs > lastReferenceTimestampMs &&
                it.timestampMs <= sample.timestampMs + REFERENCE_FUTURE_TOLERANCE_MS
        }.sortedBy { it.timestampMs }
        if (pending.isEmpty()) return

        AdaptiveV2ObservationModel.referenceJacobian(jacobian)
        pending.forEach { reference ->
            for (index in 0 until AdaptiveV2Mode.COUNT) {
                val mode = modes[index]
                val innovation = reference.glucoseMmol - mode.x[V2.B]
                var priorVariance = REFERENCE_VARIANCE
                for (row in 0 until V2.N) {
                    priorVariance += jacobian[row] * mode.p[row * V2.N + V2.B]
                }
                priorVariance = max(priorVariance, MIN_VARIANCE)
                // A reference is evidence about which hypothesis is true, not
                // only about where the state is. If the dynamic mode predicted
                // 3.0 and the artifact mode predicted 4.3, a fingerstick of 3.0
                // should make the dynamic hypothesis much more probable —
                // otherwise every mode is pulled onto the reference and the
                // disagreement that made it informative is thrown away.
                modeLogLikelihood[index] = noiseModel.logLikelihood(innovation, priorVariance)

                // An inconsistent anchor is down-weighted, not obeyed. Nothing
                // here forces an instantaneous discontinuity.
                val weight = noiseModel.robustWeight(innovation * innovation / priorVariance)
                mode.update(jacobian, innovation, REFERENCE_VARIANCE / max(weight, MIN_ROBUST_WEIGHT))
                AdaptiveV2ObservationModel.clampSensorStates(mode.x)
            }
            reweightModesFromLikelihood()
            lastReferenceTimestampMs = reference.timestampMs
        }
        adoptSensorStateFromReferences()
    }

    /**
     * Moves the shrinkage targets toward the sensor state a reference just
     * established.
     *
     * Only a fraction is adopted per reference: one fingerstick is evidence,
     * not proof, and a mistimed or mis-entered one should not permanently
     * redefine the sensor. Repeated consistent references converge the prior;
     * a single outlier moves it a little and is then out-voted.
     */
    private fun adoptSensorStateFromReferences() {
        var bias = 0.0
        var logSensitivity = 0.0
        for (index in 0 until AdaptiveV2Mode.COUNT) {
            val weight = modeProbability[index].toDouble()
            bias += weight * modes[index].x[V2.BIAS]
            logSensitivity += weight * modes[index].x[V2.LOG_S]
        }
        biasPrior += REFERENCE_PRIOR_ADOPTION * (bias - biasPrior)
        logSensitivityPrior += REFERENCE_PRIOR_ADOPTION * (logSensitivity - logSensitivityPrior)
    }

    /** Bayesian mode update from the likelihoods currently in [modeLogLikelihood]. */
    private fun reweightModesFromLikelihood() {
        var maximum = Double.NEGATIVE_INFINITY
        for (index in 0 until AdaptiveV2Mode.COUNT) maximum = max(maximum, modeLogLikelihood[index])
        if (!maximum.isFinite()) return
        var total = 0.0
        for (index in 0 until AdaptiveV2Mode.COUNT) {
            val value = max(modeProbability[index].toDouble(), MIN_PROBABILITY) *
                exp(modeLogLikelihood[index] - maximum)
            modeProbability[index] = value.toFloat()
            total += value
        }
        if (total <= 0.0 || !total.isFinite()) {
            modeProbability.fill(1f / AdaptiveV2Mode.COUNT)
            return
        }
        for (index in 0 until AdaptiveV2Mode.COUNT) {
            modeProbability[index] = (modeProbability[index] / total.toFloat())
        }
    }

    private fun normalizeModeProbabilities() {
        var total = 0f
        for (index in 0 until AdaptiveV2Mode.COUNT) {
            if (!modeProbability[index].isFinite() || modeProbability[index] < 0f) {
                modeProbability[index] = MIN_PROBABILITY.toFloat()
            }
            modeProbability[index] = max(modeProbability[index], MIN_PROBABILITY.toFloat())
            total += modeProbability[index]
        }
        if (total <= 0f) {
            modeProbability.fill(1f / AdaptiveV2Mode.COUNT)
            return
        }
        for (index in 0 until AdaptiveV2Mode.COUNT) modeProbability[index] /= total
    }

    private fun combine(): ProbabilisticGlucoseEstimate {
        for (index in 0 until AdaptiveV2Mode.COUNT) {
            val mode = modes[index]
            glucoseWeights[index] = modeProbability[index]
            glucoseMeans[index] = mode.x[V2.B].toFloat()
            // First-order propagation of lag-parameter uncertainty: B is
            // reconstructed roughly as I + tau*v, so an uncertain tau costs
            // v^2*var(tau) of glucose variance. Without this the interval stays
            // narrow exactly when the median is extrapolating hardest.
            val rate = mode.x[V2.V]
            val lagTerm = rate * rate * lagEstimator.lagVariance
            glucoseVariances[index] =
                max(mode.at(V2.B, V2.B) + lagTerm, MIN_VARIANCE).toFloat()
            rateMeans[index] = mode.x[V2.V].toFloat()
            rateVariances[index] = max(mode.at(V2.V, V2.V), MIN_VARIANCE).toFloat()
        }
        val glucoseMixture = GaussianMixture1D(
            glucoseWeights.copyOf(),
            glucoseMeans.copyOf(),
            glucoseVariances.copyOf(),
        )
        val rateMixture = GaussianMixture1D(
            glucoseWeights.copyOf(),
            rateMeans.copyOf(),
            rateVariances.copyOf(),
        )

        val lower = glucoseMixture.quantile(LOWER_QUANTILE)
        val upper = glucoseMixture.quantile(UPPER_QUANTILE)
        val central = glucoseMixture.median()
        val width = (upper - lower).coerceAtLeast(0f)

        // The posterior is a Gaussian mixture and its tails are unbounded, so a
        // wide component can place the 5% quantile at or below zero. Glucose
        // cannot be negative, so the *reported* bounds are clipped to the same
        // physiological range the state itself is confined to. The internal
        // posterior is unchanged; this is a display-domain truncation and the
        // two are deliberately allowed to differ, because rendering a negative
        // lower bound would be worse than either.
        val floor = AdaptiveV2ObservationModel.MIN_GLUCOSE.toFloat()
        val ceiling = AdaptiveV2ObservationModel.MAX_GLUCOSE.toFloat()
        val boundedCentral = central.coerceIn(floor, ceiling)
        val estimate = ProbabilisticGlucoseEstimate(
            glucoseMmol = boundedCentral,
            lower90Mmol = min(lower, boundedCentral).coerceIn(floor, ceiling),
            upper90Mmol = max(upper, boundedCentral).coerceIn(floor, ceiling),
            rateMmolPerMin = rateMixture.mean,
            rateUncertainty = rateMixture.standardDeviation,
            fallingProbability = rateMixture.cdf(0.0).toFloat(),
            steadyProbability = modeProbability[AdaptiveV2Mode.STEADY.ordinal],
            dynamicProbability = modeProbability[AdaptiveV2Mode.DYNAMIC.ordinal],
            artifactProbability = modeProbability[AdaptiveV2Mode.ARTIFACT.ordinal],
            driftProbability = modeProbability[AdaptiveV2Mode.DRIFT.ordinal],
            // Confidence is interval sharpness, not a separate belief: a wide
            // credible interval *is* low confidence.
            confidence = (1f / (1f + width / CONFIDENCE_WIDTH_SCALE)).coerceIn(0f, 1f),
        )
        latestGlucoseMixture = glucoseMixture
        latestEstimate = estimate
        return estimate
    }

    // ── Initialisation ─────────────────────────────────────────────────────

    private fun initialize(observation: Float, sample: AdaptiveV2Sample) {
        val start = observation.toDouble().coerceIn(
            AdaptiveV2ObservationModel.MIN_GLUCOSE,
            AdaptiveV2ObservationModel.MAX_GLUCOSE,
        )
        noiseModel.reset()
        lagEstimator.reset()
        for (index in 0 until AdaptiveV2Mode.COUNT) {
            val mode = modes[index]
            mode.reset()
            mode.x[V2.B] = start
            mode.x[V2.I] = start
            mode.setAt(V2.B, V2.B, INITIAL_GLUCOSE_VARIANCE)
            mode.setAt(V2.I, V2.I, INITIAL_GLUCOSE_VARIANCE)
            mode.setAt(V2.B, V2.I, INITIAL_GLUCOSE_VARIANCE * 0.9)
            mode.setAt(V2.I, V2.B, INITIAL_GLUCOSE_VARIANCE * 0.9)
            mode.setAt(V2.V, V2.V, INITIAL_RATE_VARIANCE)
            mode.setAt(V2.ACC, V2.ACC, INITIAL_ACCELERATION_VARIANCE)
            // Factory calibration is the prior, and it is a good one: the
            // chemical signal is already normalised by the decoded sensitivity.
            mode.setAt(V2.LOG_S, V2.LOG_S, INITIAL_LOG_SENSITIVITY_VARIANCE)
            mode.setAt(V2.BIAS, V2.BIAS, INITIAL_BIAS_VARIANCE)
            mode.setAt(V2.ARTIFACT, V2.ARTIFACT, INITIAL_ARTIFACT_VARIANCE)
        }
        modeProbability[AdaptiveV2Mode.STEADY.ordinal] = 0.55f
        modeProbability[AdaptiveV2Mode.DYNAMIC.ordinal] = 0.25f
        modeProbability[AdaptiveV2Mode.ARTIFACT.ordinal] = 0.10f
        modeProbability[AdaptiveV2Mode.DRIFT.ordinal] = 0.10f
        initialized = true
        lastIndex = sample.index
        lastTimestampMs = sample.timestampMs
        lastInnovation = 0.0
        lastMeasurementVariance = noiseModel.measurementVariance
        vendorSensitivityDrift = sample.vendorSensitivityDrift
        combine()
        latestDiagnostics = null
    }

    private class Elapsed(val minutes: Double, val reset: Boolean)

    /**
     * True elapsed time since the last processed sample.
     *
     * Nothing is silently discarded here. An earlier version clamped the gap to
     * 60 minutes and then advanced `lastTimestampMs` to the real timestamp, so
     * a four-hour hole propagated as one hour and the posterior came back far
     * narrower than the data justified. Long gaps now either propagate in full
     * or trigger an explicit reinitialisation.
     */
    private fun elapsedMinutes(sample: AdaptiveV2Sample): Elapsed {
        val byTime = if (sample.timestampMs > lastTimestampMs && lastTimestampMs > 0L) {
            (sample.timestampMs - lastTimestampMs) / 60_000.0
        } else {
            Double.NaN
        }
        val byIndex = (sample.index - lastIndex).toDouble()
        val elapsed = if (byTime.isFinite() && byTime > 0.0) byTime else byIndex
        if (!elapsed.isFinite() || elapsed >= REINITIALISE_GAP_MINUTES) {
            return Elapsed(0.0, reset = true)
        }
        // Missing samples propagate the state forward over the true gap, which
        // widens the posterior. No pseudo-measurement is fabricated.
        return Elapsed(elapsed.coerceAtLeast(MIN_STEP_MINUTES), reset = false)
    }

    private fun buildDiagnostics(
        sample: AdaptiveV2Sample,
        estimate: ProbabilisticGlucoseEstimate,
        telemetry: AdaptiveV2Telemetry,
        stockComparisonMmol: Float,
        adaptiveV1ComparisonMmol: Float,
    ): AdaptiveV2Diagnostics {
        var interstitial = 0.0
        var artifact = 0.0
        var sensitivity = 0.0
        var bias = 0.0
        for (index in 0 until AdaptiveV2Mode.COUNT) {
            val weight = modeProbability[index].toDouble()
            interstitial += weight * modes[index].x[V2.I]
            artifact += weight * modes[index].x[V2.ARTIFACT]
            sensitivity += weight * AdaptiveV2ObservationModel.sensitivityOf(modes[index].x)
            bias += weight * modes[index].x[V2.BIAS]
        }
        return AdaptiveV2Diagnostics(
            index = sample.index,
            timestampMs = sample.timestampMs,
            chemicalMmol = sample.calibratedMmol,
            glucoseMmol = estimate.glucoseMmol,
            lower90Mmol = estimate.lower90Mmol,
            upper90Mmol = estimate.upper90Mmol,
            rateMmolPerMin = estimate.rateMmolPerMin,
            rateUncertainty = estimate.rateUncertainty,
            steadyProbability = estimate.steadyProbability,
            dynamicProbability = estimate.dynamicProbability,
            artifactProbability = estimate.artifactProbability,
            driftProbability = estimate.driftProbability,
            sensitivity = sensitivity.toFloat(),
            biasMmol = bias.toFloat(),
            lagMinutes = lagEstimator.lagMinutes.toFloat(),
            measurementNoise = noiseModel.measurementVariance.toFloat(),
            innovation = lastInnovation.toFloat(),
            temperatureQuality = telemetry.temperatureQuality,
            impedanceQuality = telemetry.impedanceQuality,
            interstitialMmol = interstitial.toFloat(),
            artifactMmol = artifact.toFloat(),
            sensorStateCompensationMmol = sample.sensorStateCompensationMmol,
            activeSensitivity = sample.activeSensitivity,
            stockMmol = stockComparisonMmol,
            adaptiveV1Mmol = adaptiveV1ComparisonMmol,
        )
    }

    /** Posterior probability that current glucose is below [thresholdMmol]. */
    fun probabilityBelow(thresholdMmol: Float): Float =
        latestGlucoseMixture?.cdf(thresholdMmol.toDouble())?.toFloat() ?: Float.NaN

    // ── Serialisation ──────────────────────────────────────────────────────

    internal fun writeTo(output: java.io.DataOutputStream) {
        output.writeFloat(factorySensitivity)
        output.writeBoolean(initialized)
        output.writeInt(lastIndex)
        output.writeLong(lastTimestampMs)
        output.writeLong(lastReferenceTimestampMs)
        output.writeDouble(lastInnovation)
        output.writeDouble(lastMeasurementVariance)
        output.writeDouble(biasPrior)
        output.writeDouble(logSensitivityPrior)
        for (value in modeProbability) output.writeFloat(value)
        modes.forEach { it.writeTo(output) }
        telemetryModel.writeTo(output)
        noiseModel.writeTo(output)
        lagEstimator.writeTo(output)
    }

    internal fun readFrom(input: java.io.DataInputStream): Boolean {
        val savedSensitivity = input.readFloat()
        if (!savedSensitivity.isFinite() || abs(savedSensitivity - factorySensitivity) > 1e-4f) {
            return false
        }
        initialized = input.readBoolean()
        lastIndex = input.readInt()
        lastTimestampMs = input.readLong()
        lastReferenceTimestampMs = input.readLong()
        lastInnovation = input.readDouble()
        lastMeasurementVariance = input.readDouble()
        biasPrior = input.readDouble()
        logSensitivityPrior = input.readDouble()
        for (index in modeProbability.indices) modeProbability[index] = input.readFloat()
        modes.forEach { it.readFrom(input) }
        telemetryModel.readFrom(input)
        noiseModel.readFrom(input)
        lagEstimator.readFrom(input)
        if (!isStateValid()) return false
        if (initialized) combine()
        return true
    }

    private fun isStateValid(): Boolean {
        if (!initialized) return true
        if (lastIndex < 0) return false
        var total = 0f
        for (value in modeProbability) {
            if (!value.isFinite() || value < 0f || value > 1f) return false
            total += value
        }
        if (abs(total - 1f) > PROBABILITY_TOLERANCE) return false
        if (!biasPrior.isFinite() || !logSensitivityPrior.isFinite()) return false
        for (mode in modes) {
            if (!mode.isFinite()) return false
            if (!AdaptiveV2ObservationModel.isStateValid(mode.x)) return false
            for (i in 0 until V2.N) if (mode.at(i, i) < 0.0) return false
        }
        return telemetryModel.isValid() && noiseModel.isValid() && lagEstimator.isValid()
    }

    companion object {
        private const val DEFAULT_SENSITIVITY = 1.27f

        private const val LOWER_QUANTILE = 0.05
        private const val UPPER_QUANTILE = 0.95

        private const val MIN_PROBABILITY = 1e-4
        private const val MIN_VARIANCE = 1e-8
        private const val MIN_ROBUST_WEIGHT = 0.02
        private const val PROBABILITY_TOLERANCE = 1e-3f

        private const val MIN_STEP_MINUTES = 0.25
        /** Substep size for gap propagation; the models are all defined per minute. */
        private const val MAX_SUBSTEP_MINUTES = 1.0
        /** Past this the posterior carries no information and the sensor may have been reinserted. */
        private const val REINITIALISE_GAP_MINUTES = 180.0

        private const val INITIAL_GLUCOSE_VARIANCE = 0.55
        private const val INITIAL_RATE_VARIANCE = 0.02
        private const val INITIAL_ACCELERATION_VARIANCE = 0.004
        /**
         * ~2.5% one-sigma. Tight on purpose: the observation is already
         * normalised by the vendor's live sensitivity estimate, so this state
         * only carries the residual the manufacturer has not captured. A loose
         * prior here would let it re-absorb the calibration the vendor already
         * did, which is the identifiability trap this design avoids.
         */
        private const val INITIAL_LOG_SENSITIVITY_VARIANCE = 0.0006
        private const val INITIAL_BIAS_VARIANCE = 0.06
        private const val INITIAL_ARTIFACT_VARIANCE = 0.02

        /** Fingerstick meters are themselves ~±0.4 mmol/L one-sigma at normal ranges. */
        private const val REFERENCE_VARIANCE = 0.16
        private const val REFERENCE_FUTURE_TOLERANCE_MS = 60_000L
        private const val REFERENCE_PRIOR_ADOPTION = 0.6

        private const val CONFIDENCE_WIDTH_SCALE = 2.2f
    }
}
