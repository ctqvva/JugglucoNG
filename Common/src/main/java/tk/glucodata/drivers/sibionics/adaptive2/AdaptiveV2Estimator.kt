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
     * Vendor chemical signal in glucose units, taken before the stock
     * five-filter / clip / ESA / deconvolution stages.
     */
    val chemicalMmol: Float,
    val temperatureC: Float,
    val impedance: Float,
    val qualityFlags: Int,
    /** Sensor minute index; doubles as sensor age. */
    val index: Int,
    val timestampMs: Long,
)

/** A validated external reference measurement, e.g. a fingerstick. */
internal data class AdaptiveV2Reference(
    val glucoseMmol: Float,
    val timestampMs: Long,
)

/**
 * Adaptive V2: an independent probabilistic estimator of sensor state.
 *
 * It does not use final Sibionics stock glucose as a target, anchor,
 * regulariser or hidden truth source. Its only glucose observation is the
 * vendor's own pre-filter chemical signal, plus genuine sensor metadata
 * (factory sensitivity, algorithm family, sensor age, temperature, impedance,
 * front-end quality flags) and, when the user provides them, external
 * calibration references.
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
    ): ProbabilisticGlucoseEstimate? {
        val chemical = sample.chemicalMmol
        if (!chemical.isFinite() || chemical <= 0f) return null

        val telemetry = telemetryModel.evaluate(sample.temperatureC, sample.impedance, sample.qualityFlags)

        if (!initialized || sample.index <= lastIndex) {
            initialize(chemical, sample)
            telemetryModel.advance(sample.temperatureC, sample.impedance)
            return latestEstimate
        }

        val dtMinutes = elapsedMinutes(sample)
        mix(telemetry)
        predict(dtMinutes)

        val observationVariance = noiseModel.observationVariance(telemetry, sample.index)
        updateWithChemical(chemical.toDouble(), observationVariance)
        applyReferences(references, sample)
        normalizeModeProbabilities()

        val estimate = combine()
        lagEstimator.update(
            innovation = lastInnovation,
            rate = estimate.rateMmolPerMin.toDouble(),
            dtMinutes = dtMinutes,
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
        latestDiagnostics = buildDiagnostics(sample, estimate, telemetry, stockComparisonMmol)
        return estimate
    }

    // ── IMM cycle ──────────────────────────────────────────────────────────

    private fun mix(telemetry: AdaptiveV2Telemetry) {
        // Predicted mode probabilities and mixing weights.
        predictedMode.fill(0.0)
        for (from in 0 until AdaptiveV2Mode.COUNT) {
            AdaptiveV2ModeModel.transition(
                AdaptiveV2Mode.ALL[from],
                telemetry.impedanceDisturbance,
                telemetry.vendorArtifactHint,
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
        for (index in 0 until AdaptiveV2Mode.COUNT) {
            AdaptiveV2ModeModel.processNoise(AdaptiveV2Mode.ALL[index], dtMinutes, processNoise)
            modes[index].copyFrom(mixed[index])
            modes[index].predict(transitionMatrix, processNoise)
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
                // An inconsistent anchor is down-weighted, not obeyed. Nothing
                // here forces an instantaneous discontinuity.
                val weight = noiseModel.robustWeight(
                    innovation * innovation / max(priorVariance, MIN_VARIANCE),
                )
                mode.update(jacobian, innovation, REFERENCE_VARIANCE / max(weight, MIN_ROBUST_WEIGHT))
                AdaptiveV2ObservationModel.clampSensorStates(mode.x)
            }
            lastReferenceTimestampMs = reference.timestampMs
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
            glucoseVariances[index] = max(mode.at(V2.B, V2.B), MIN_VARIANCE).toFloat()
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

        val estimate = ProbabilisticGlucoseEstimate(
            glucoseMmol = central.coerceIn(
                AdaptiveV2ObservationModel.MIN_GLUCOSE.toFloat(),
                AdaptiveV2ObservationModel.MAX_GLUCOSE.toFloat(),
            ),
            lower90Mmol = min(lower, central),
            upper90Mmol = max(upper, central),
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

    private fun initialize(chemical: Float, sample: AdaptiveV2Sample) {
        val start = chemical.toDouble().coerceIn(
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
        combine()
        latestDiagnostics = null
    }

    private fun elapsedMinutes(sample: AdaptiveV2Sample): Double {
        val byTime = if (sample.timestampMs > lastTimestampMs && lastTimestampMs > 0L) {
            (sample.timestampMs - lastTimestampMs) / 60_000.0
        } else {
            Double.NaN
        }
        val byIndex = (sample.index - lastIndex).toDouble()
        val elapsed = if (byTime.isFinite() && byTime > 0.0) byTime else byIndex
        // Missing samples propagate the state forward over the true gap, which
        // widens the posterior. No pseudo-measurement is fabricated.
        return elapsed.coerceIn(MIN_STEP_MINUTES, MAX_STEP_MINUTES)
    }

    private fun buildDiagnostics(
        sample: AdaptiveV2Sample,
        estimate: ProbabilisticGlucoseEstimate,
        telemetry: AdaptiveV2Telemetry,
        stockComparisonMmol: Float,
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
            chemicalMmol = sample.chemicalMmol,
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
            stockMmol = stockComparisonMmol,
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
        /** Beyond an hour the propagated posterior is so wide it carries no information. */
        private const val MAX_STEP_MINUTES = 60.0

        private const val INITIAL_GLUCOSE_VARIANCE = 0.55
        private const val INITIAL_RATE_VARIANCE = 0.02
        private const val INITIAL_ACCELERATION_VARIANCE = 0.004
        /** ~7% one-sigma on factory sensitivity: a manufacturer-grade prior, not a free parameter. */
        private const val INITIAL_LOG_SENSITIVITY_VARIANCE = 0.005
        private const val INITIAL_BIAS_VARIANCE = 0.06
        private const val INITIAL_ARTIFACT_VARIANCE = 0.02

        /** Fingerstick meters are themselves ~±0.4 mmol/L one-sigma at normal ranges. */
        private const val REFERENCE_VARIANCE = 0.16
        private const val REFERENCE_FUTURE_TOLERANCE_MS = 60_000L

        private const val CONFIDENCE_WIDTH_SCALE = 2.2f
    }
}
