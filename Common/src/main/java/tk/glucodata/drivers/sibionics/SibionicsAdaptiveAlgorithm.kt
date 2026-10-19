package tk.glucodata.drivers.sibionics

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

data class SibionicsCalibrationAnchor(
    val sensorMmol: Float,
    val referenceMmol: Float,
    val timestampMs: Long,
)

/**
 * Alternative one-minute Sibionics signal model.
 *
 * The vendor front end still owns sensor-specific current correction, QR
 * sensitivity and temperature compensation. This model starts from that
 * compensated chemical signal, before the vendor's five-stage filters,
 * clipping, ESA compensation and deconvolution. It then combines:
 *
 *  - a slowly time-varying affine sensor-drift model;
 *  - steady and dynamic constant-velocity Kalman models;
 *  - innovation-based model probabilities and robust outlier weighting;
 *  - temperature, impedance and vendor front-end quality signals; and
 *  - calibration corrections injected into the state observations.
 *
 * The exact stock result is an absolute/drift reference and safety fallback,
 * not the signal being smoothed. Every emitted value still requires a real
 * one-minute source sample.
 *
 * Divergence from that reference is evidence-gated rather than free. The model
 * may only leave the vendor trajectory as far as the chemical signal actually
 * supports, measured from fall persistence, physiological rate plausibility,
 * agreement with the vendor direction, innovation coherence and front-end
 * quality. When that evidence weakens the output fades back toward stock
 * instead of continuing an increasingly independent trajectory. There is no
 * absolute low floor: the allowance is relative to the reference, so a
 * coherent sustained fall still reaches genuinely low values.
 */
internal class SibionicsAdaptiveAlgorithmContext {
    private class MotionModel(private val processNoise: Float) {
        var level = Float.NaN
        var rate = 0f
        var p00 = 1f
        var p01 = 0f
        var p10 = 0f
        var p11 = 0.1f

        data class Update(val logLikelihood: Float)

        fun initialize(value: Float) {
            level = value
            rate = 0f
            p00 = 1f
            p01 = 0f
            p10 = 0f
            p11 = 0.1f
        }

        fun shiftLevel(delta: Float) {
            if (level.isFinite() && delta.isFinite()) level += delta
        }

        fun update(measurement: Float, measurementVariance: Float, dt: Float): Update {
            val predictedLevel = level + rate * dt
            val predictedRate = rate
            val dt2 = dt * dt
            val predictedP00 = p00 + dt * (p10 + p01) + dt2 * p11 + processNoise * dt * dt2 / 3f
            val predictedP01 = p01 + dt * p11 + processNoise * dt2 / 2f
            val predictedP10 = p10 + dt * p11 + processNoise * dt2 / 2f
            val predictedP11 = p11 + processNoise * dt

            val innovation = measurement - predictedLevel
            val initialVariance = max(predictedP00 + measurementVariance, MIN_VARIANCE)
            val robustScale = max(sqrt(initialVariance), MIN_ROBUST_SCALE)
            val robustWeight = min(1f, HUBER_SIGMA * robustScale / max(abs(innovation), 0.0001f))
            val effectiveMeasurementVariance = measurementVariance / (robustWeight * robustWeight)
            val innovationVariance = max(predictedP00 + effectiveMeasurementVariance, MIN_VARIANCE)
            val levelGain = predictedP00 / innovationVariance
            val rateGain = predictedP10 / innovationVariance

            level = predictedLevel + levelGain * innovation
            rate = (predictedRate + rateGain * innovation).coerceIn(-MAX_RATE, MAX_RATE)
            p00 = max((1f - levelGain) * predictedP00, MIN_VARIANCE)
            p01 = (1f - levelGain) * predictedP01
            p10 = predictedP10 - rateGain * predictedP00
            p11 = max(predictedP11 - rateGain * predictedP01, MIN_VARIANCE)

            val logLikelihood = -0.5f * (ln(innovationVariance) + innovation * innovation / innovationVariance)
            return Update(logLikelihood)
        }

        fun writeTo(output: DataOutputStream) {
            output.writeFloat(level)
            output.writeFloat(rate)
            output.writeFloat(p00)
            output.writeFloat(p01)
            output.writeFloat(p10)
            output.writeFloat(p11)
        }

        fun readFrom(input: DataInputStream) {
            level = input.readFloat()
            rate = input.readFloat()
            p00 = input.readFloat()
            p01 = input.readFloat()
            p10 = input.readFloat()
            p11 = input.readFloat()
        }

        fun isValid(): Boolean = level.isFinite() && level in 0f..50f && rate.isFinite() &&
            abs(rate) <= MAX_RATE && p00.isFinite() && p00 > 0f && p01.isFinite() &&
            p10.isFinite() && p11.isFinite() && p11 > 0f

        private companion object {
            private const val MIN_VARIANCE = 0.000001f
            private const val MIN_ROBUST_SCALE = 0.12f
            private const val HUBER_SIGMA = 2.5f
            private const val MAX_RATE = 0.65f
        }
    }

    private data class SignalQuality(
        val overall: Float,
        val severe: Boolean,
    )

    private var decodedSensitivity = DEFAULT_SENSITIVITY
    private var initialized = false
    private val steadyModel = MotionModel(STEADY_PROCESS_NOISE)
    private val dynamicModel = MotionModel(DYNAMIC_PROCESS_NOISE)
    private var dynamicProbability = INITIAL_DYNAMIC_PROBABILITY

    private var chemicalGain = 1f
    private var chemicalBias = 0f
    private var meanChemical = 0f
    private var meanStock = 0f
    private var chemicalVariance = INITIAL_MAPPING_VARIANCE
    private var chemicalStockCovariance = INITIAL_MAPPING_VARIANCE

    private var lastChemical = Float.NaN
    private var lastStock = Float.NaN
    private var lastReference = Float.NaN
    private var lastCalibrationDelta = Float.NaN
    private var lastTemperature = Float.NaN
    private var impedanceBaseline = Float.NaN
    private var lastTimestampMs = 0L
    private var lastIndex = -1
    private var samplesSinceInitialization = 0

    // Plausibility evidence. These describe how well the chemical signal
    // actually supports leaving the vendor trajectory, and are the only thing
    // standing between the model and a confident but unsupported excursion.
    private var chemicalRateEwma = 0f
    private var stockRateEwma = 0f
    private var fallSupport = 0f
    private var innovationEwma = 0f
    private var referenceTrust = INITIAL_REFERENCE_TRUST

    fun configure(sensitivity: Float) {
        decodedSensitivity = sensitivity.takeIf { it.isFinite() && it in 0.5f..3.5f }
            ?: DEFAULT_SENSITIVITY
    }

    fun reset() {
        initialized = false
        dynamicProbability = INITIAL_DYNAMIC_PROBABILITY
        chemicalGain = 1f
        chemicalBias = 0f
        meanChemical = 0f
        meanStock = 0f
        chemicalVariance = INITIAL_MAPPING_VARIANCE
        chemicalStockCovariance = INITIAL_MAPPING_VARIANCE
        lastChemical = Float.NaN
        lastStock = Float.NaN
        lastReference = Float.NaN
        lastCalibrationDelta = Float.NaN
        lastTemperature = Float.NaN
        impedanceBaseline = Float.NaN
        lastTimestampMs = 0L
        lastIndex = -1
        samplesSinceInitialization = 0
        chemicalRateEwma = 0f
        stockRateEwma = 0f
        fallSupport = 0f
        innovationEwma = 0f
        referenceTrust = INITIAL_REFERENCE_TRUST
    }

    fun process(
        stockMmol: Float,
        rawMmol: Float,
        temperatureC: Float,
        impedance: Float,
        index: Int,
        eventTimeMs: Long,
        anchors: List<SibionicsCalibrationAnchor>,
        chemicalMmol: Float = Float.NaN,
        chemicalQualityFlags: Int = 0,
        vendorStockMmol: Float = stockMmol,
    ): Float {
        if (!stockMmol.isFinite() || stockMmol <= 0f) return Float.NaN
        val reference = applyIntegratedCalibration(stockMmol, eventTimeMs, anchors)
        val vendorStock = vendorStockMmol.takeIf { it.isFinite() && it > 0f } ?: stockMmol

        if (index < CUSTOM_MODEL_WARMUP_INDEX || !chemicalMmol.isFinite() || chemicalMmol <= 0f) {
            updateTelemetry(temperatureC, impedance, index, eventTimeMs)
            return roundTenth(reference)
        }

        if (!initialized || index <= lastIndex) {
            reset()
            initialize(chemicalMmol, vendorStock, reference, temperatureC, impedance, index, eventTimeMs)
            return roundTenth(reference)
        }

        val elapsedMinutes = when {
            eventTimeMs > lastTimestampMs && lastTimestampMs > 0L ->
                ((eventTimeMs - lastTimestampMs) / 60_000f).coerceIn(0.5f, 3f)
            index > lastIndex -> (index - lastIndex).toFloat().coerceIn(1f, 3f)
            else -> 1f
        }
        val quality = signalQuality(temperatureC, impedance, chemicalQualityFlags)
        if (quality.severe) {
            alignToReference(reference)
            updateLastValues(chemicalMmol, vendorStock, reference, temperatureC, impedance, index, eventTimeMs)
            return roundTenth(reference)
        }

        updateChemicalMapping(
            chemical = chemicalMmol,
            vendorStock = vendorStock,
            elapsedMinutes = elapsedMinutes,
            quality = quality.overall,
        )
        val calibrationDelta = reference - vendorStock
        if (lastCalibrationDelta.isFinite()) {
            val calibrationShift = calibrationDelta - lastCalibrationDelta
            if (abs(calibrationShift) >= CALIBRATION_STATE_SHIFT_THRESHOLD) {
                steadyModel.shiftLevel(calibrationShift)
                dynamicModel.shiftLevel(calibrationShift)
            }
        }
        val chemicalObservation = chemicalGain * chemicalMmol + chemicalBias + calibrationDelta
        val measurementVariance = BASE_MEASUREMENT_VARIANCE /
            max(quality.overall * quality.overall, MIN_QUALITY * MIN_QUALITY)
        val predictedLevel = steadyModel.level * (1f - dynamicProbability) +
            dynamicModel.level * dynamicProbability
        val steadyUpdate = steadyModel.update(chemicalObservation, measurementVariance, elapsedMinutes)
        val dynamicUpdate = dynamicModel.update(chemicalObservation, measurementVariance, elapsedMinutes)
        val chemicalRate = (chemicalMmol - lastChemical) / elapsedMinutes
        updateDynamicProbability(
            steadyLogLikelihood = steadyUpdate.logLikelihood,
            dynamicLogLikelihood = dynamicUpdate.logLikelihood,
            chemicalRate = chemicalRate,
        )
        updateEvidence(
            chemicalRate = chemicalRate,
            stockRate = (vendorStock - lastStock) / elapsedMinutes,
            innovation = chemicalObservation - predictedLevel,
        )

        val level = steadyModel.level * (1f - dynamicProbability) +
            dynamicModel.level * dynamicProbability
        val rate = steadyModel.rate * (1f - dynamicProbability) +
            dynamicModel.rate * dynamicProbability
        val modelVariance = steadyModel.p00 * (1f - dynamicProbability) +
            dynamicModel.p00 * dynamicProbability
        val evidence = plausibility(quality.overall, modelVariance, level, rate)

        var output = level + leadOffset(rate, evidence)
        output = fadeTowardReference(output, reference, evidence)
        output = clampDivergence(output, reference, evidence)
        // Unchanged safety rule: when the vendor reference is already low the
        // model may not sit meaningfully above it and hide that low.
        if (reference < LOW_GLUCOSE_GUARD) {
            output = min(output, reference + LOW_FALL_ALLOWANCE)
        }
        output = output.coerceIn(MIN_OUTPUT_MMOL, MAX_OUTPUT_MMOL)

        samplesSinceInitialization++
        updateLastValues(chemicalMmol, vendorStock, reference, temperatureC, impedance, index, eventTimeMs)
        return roundTenth(output)
    }

    /**
     * How far the model may legitimately leave the vendor reference, how much
     * lead compensation is defensible, and how much of the independent estimate
     * to trust at all.
     */
    private data class Plausibility(
        val downwardAllowance: Float,
        val upwardAllowance: Float,
        val leadScale: Float,
        val trust: Float,
    )

    private fun updateEvidence(chemicalRate: Float, stockRate: Float, innovation: Float) {
        if (chemicalRate.isFinite()) {
            chemicalRateEwma += RATE_SMOOTHING * (chemicalRate - chemicalRateEwma)
        }
        if (stockRate.isFinite()) {
            stockRateEwma += STOCK_RATE_SMOOTHING * (stockRate - stockRateEwma)
        }
        // Persistence accumulates slower than it decays: several coherent
        // falling samples are needed to earn allowance, one recovery sample is
        // enough to lose most of it.
        val falling = if (chemicalRate.isFinite() && chemicalRate <= -FALL_RATE_THRESHOLD) 1f else 0f
        val persistenceRate = if (falling > 0f) FALL_SUPPORT_RISE else FALL_SUPPORT_DECAY
        fallSupport = (fallSupport + persistenceRate * (falling - fallSupport)).coerceIn(0f, 1f)
        if (innovation.isFinite()) {
            innovationEwma += INNOVATION_SMOOTHING * (abs(innovation) - innovationEwma)
        }
    }

    private fun plausibility(
        quality: Float,
        modelVariance: Float,
        level: Float,
        rate: Float,
    ): Plausibility {
        // Human glucose does not fall at arbitrary speed. A chemical rate well
        // past the physiological maximum is evidence of a sensor event, not of
        // a real excursion, so it buys less allowance rather than more.
        val ratePlausibility = if (abs(chemicalRateEwma) <= PLAUSIBLE_RATE_MMOL_PER_MIN) {
            1f
        } else {
            (PLAUSIBLE_RATE_MMOL_PER_MIN / abs(chemicalRateEwma)).coerceIn(0f, 1f)
        }
        // Does the vendor trajectory agree that we are falling? Stock lags, so
        // this is one weighted term and never a hard gate — persistence covers
        // the onset of a genuine fall before stock has moved.
        val fallAgreement = if (chemicalRateEwma < -FALL_RATE_THRESHOLD) {
            (stockRateEwma / chemicalRateEwma).coerceIn(0f, 1f)
        } else {
            0f
        }
        val coherence = (1f / (1f + innovationEwma / INNOVATION_SCALE)).coerceIn(0f, 1f)
        val qualityFactor = (QUALITY_FLOOR + (1f - QUALITY_FLOOR) * quality).coerceIn(0f, 1f)
        val certainty = (1f / (1f + modelVariance / MODEL_VARIANCE_SCALE)).coerceIn(0f, 1f)

        val support = PERSISTENCE_WEIGHT * fallSupport +
            AGREEMENT_WEIGHT * fallAgreement +
            COHERENCE_WEIGHT * coherence
        val downwardEvidence = (ratePlausibility * support * qualityFactor).coerceIn(0f, 1f)
        // Rises are gated the same way but start from a wider floor: an
        // unsupported high reads as noise, an unsupported low reads as an alarm.
        val upwardEvidence = (support * qualityFactor).coerceIn(0f, 1f)

        val targetTrust = (MIN_REFERENCE_TRUST +
            (1f - MIN_REFERENCE_TRUST) * qualityFactor * coherence * certainty)
            .coerceIn(MIN_REFERENCE_TRUST, 1f)
        referenceTrust += (targetTrust - referenceTrust).coerceIn(-TRUST_SLEW, TRUST_SLEW)
        referenceTrust = referenceTrust.coerceIn(MIN_REFERENCE_TRUST, 1f)

        // Extrapolation must not be what invents a low. Lead fades out as the
        // estimate approaches the low range on a falling trajectory, and with
        // poor quality, weak agreement or high model uncertainty.
        val lowTaper = if (rate < 0f) {
            ((level - LEAD_TAPER_END_MMOL) / (LEAD_TAPER_START_MMOL - LEAD_TAPER_END_MMOL))
                .coerceIn(0f, 1f)
        } else {
            1f
        }
        val directionAgreement = if (rate < 0f) {
            (LEAD_AGREEMENT_FLOOR + (1f - LEAD_AGREEMENT_FLOOR) * fallAgreement).coerceIn(0f, 1f)
        } else {
            1f
        }
        val leadScale = (lowTaper * directionAgreement * coherence * certainty * qualityFactor)
            .coerceIn(0f, 1f)

        return Plausibility(
            downwardAllowance = MIN_DOWNWARD_DIVERGENCE +
                (MAX_REFERENCE_DIVERGENCE - MIN_DOWNWARD_DIVERGENCE) * downwardEvidence,
            upwardAllowance = MIN_UPWARD_DIVERGENCE +
                (MAX_REFERENCE_DIVERGENCE - MIN_UPWARD_DIVERGENCE) * upwardEvidence,
            leadScale = leadScale,
            trust = referenceTrust,
        )
    }

    private fun leadOffset(rate: Float, evidence: Plausibility): Float {
        val leadMinutes = (MIN_LEAD_MINUTES +
            (MAX_LEAD_MINUTES - MIN_LEAD_MINUTES) * dynamicProbability) * evidence.leadScale
        val offset = rate * leadMinutes
        return if (offset < 0f) max(offset, -MAX_LEAD_DROP_MMOL) else offset
    }

    private fun fadeTowardReference(output: Float, reference: Float, evidence: Plausibility): Float =
        reference + (output - reference) * evidence.trust

    private fun clampDivergence(output: Float, reference: Float, evidence: Plausibility): Float {
        val divergence = output - reference
        return reference + divergence.coerceIn(-evidence.downwardAllowance, evidence.upwardAllowance)
    }

    private fun initialize(
        chemical: Float,
        vendorStock: Float,
        reference: Float,
        temperatureC: Float,
        impedance: Float,
        index: Int,
        eventTimeMs: Long,
    ) {
        initialized = true
        chemicalGain = 1f
        chemicalBias = vendorStock - chemical
        meanChemical = chemical
        meanStock = vendorStock
        chemicalVariance = INITIAL_MAPPING_VARIANCE
        chemicalStockCovariance = INITIAL_MAPPING_VARIANCE
        val calibrationDelta = reference - vendorStock
        val initial = chemicalGain * chemical + chemicalBias + calibrationDelta
        steadyModel.initialize(initial)
        dynamicModel.initialize(initial)
        dynamicProbability = INITIAL_DYNAMIC_PROBABILITY
        samplesSinceInitialization = 1
        updateLastValues(chemical, vendorStock, reference, temperatureC, impedance, index, eventTimeMs)
    }

    private fun updateChemicalMapping(
        chemical: Float,
        vendorStock: Float,
        elapsedMinutes: Float,
        quality: Float,
    ) {
        val chemicalRate = (chemical - lastChemical) / elapsedMinutes
        val stockRate = (vendorStock - lastStock) / elapsedMinutes
        val motionWeight = if (abs(chemicalRate) < MAPPING_MAX_CHEMICAL_RATE &&
            abs(stockRate) < MAPPING_MAX_STOCK_RATE
        ) 1f else MAPPING_DYNAMIC_WEIGHT
        val updateWeight = quality * motionWeight
        val decay = 1f - (1f - MAPPING_FORGETTING_FACTOR) * updateWeight
        val oldMeanChemical = meanChemical
        val oldMeanStock = meanStock
        meanChemical = decay * meanChemical + (1f - decay) * chemical
        meanStock = decay * meanStock + (1f - decay) * vendorStock
        chemicalVariance = decay * chemicalVariance +
            (1f - decay) * (chemical - oldMeanChemical) * (chemical - meanChemical)
        chemicalStockCovariance = decay * chemicalStockCovariance +
            (1f - decay) * (chemical - oldMeanChemical) * (vendorStock - meanStock)

        val targetGain = (chemicalStockCovariance / max(chemicalVariance, MIN_MAPPING_VARIANCE))
            .coerceIn(MIN_CHEMICAL_GAIN, MAX_CHEMICAL_GAIN)
        chemicalGain += MAPPING_GAIN_RATE * updateWeight * (targetGain - chemicalGain)

        val residual = vendorStock - (chemicalGain * chemical + chemicalBias)
        chemicalBias += MAPPING_BIAS_RATE * updateWeight *
            residual.coerceIn(-MAX_MAPPING_BIAS_STEP, MAX_MAPPING_BIAS_STEP)
        val targetBias = meanStock - chemicalGain * meanChemical
        chemicalBias += MAPPING_MEAN_BIAS_RATE * updateWeight *
            (targetBias - chemicalBias).coerceIn(-MAX_MAPPING_MEAN_STEP, MAX_MAPPING_MEAN_STEP)
        chemicalBias = chemicalBias.coerceIn(MIN_CHEMICAL_BIAS, MAX_CHEMICAL_BIAS)
    }

    private fun updateDynamicProbability(
        steadyLogLikelihood: Float,
        dynamicLogLikelihood: Float,
        chemicalRate: Float,
    ) {
        val priorDynamic = MODEL_PERSISTENCE * dynamicProbability +
            (1f - MODEL_PERSISTENCE) * INITIAL_DYNAMIC_PROBABILITY
        val maximum = max(steadyLogLikelihood, dynamicLogLikelihood)
        val steadyEvidence = (1f - priorDynamic) * exp(steadyLogLikelihood - maximum)
        val dynamicEvidence = priorDynamic * exp(dynamicLogLikelihood - maximum)
        val posterior = dynamicEvidence / max(steadyEvidence + dynamicEvidence, 0.000001f)
        val motionPrior = (abs(chemicalRate) / DYNAMIC_RATE_SCALE).coerceIn(0f, 1f)
        dynamicProbability = (LIKELIHOOD_WEIGHT * posterior + MOTION_WEIGHT * motionPrior)
            .coerceIn(MIN_DYNAMIC_PROBABILITY, MAX_DYNAMIC_PROBABILITY)
    }

    private fun signalQuality(
        temperatureC: Float,
        impedance: Float,
        chemicalQualityFlags: Int,
    ): SignalQuality {
        val frontEndSevere = chemicalQualityFlags and 0x30 != 0
        val temperatureJump = lastTemperature.isFinite() && temperatureC.isFinite() &&
            abs(temperatureC - lastTemperature) > TEMPERATURE_JUMP_C
        val temperatureQuality = when {
            !temperatureC.isFinite() -> 0.25f
            temperatureC in 28f..40f -> 1f
            temperatureC in 10f..42f -> 0.60f
            else -> 0.20f
        } * if (temperatureJump) 0.55f else 1f

        val impedanceDelta = if (impedance.isFinite() && impedance > 0f && impedanceBaseline.isFinite()) {
            abs(impedance - impedanceBaseline) / max(abs(impedanceBaseline), 1f)
        } else {
            0f
        }
        val impedanceQuality = (1f / (1f + impedanceDelta * IMPEDANCE_QUALITY_SLOPE))
            .coerceIn(MIN_QUALITY, 1f)
        val severe = frontEndSevere || temperatureC.isFinite() && temperatureC !in 10f..42f ||
            impedanceDelta > IMPEDANCE_SEVERE_FRACTION
        return SignalQuality(
            overall = (temperatureQuality * 0.68f + impedanceQuality * 0.32f)
                .coerceIn(MIN_QUALITY, 1f),
            severe = severe,
        )
    }

    private fun alignToReference(reference: Float) {
        if (!initialized) return
        steadyModel.initialize(reference)
        dynamicModel.initialize(reference)
        dynamicProbability = INITIAL_DYNAMIC_PROBABILITY
    }

    private fun updateLastValues(
        chemical: Float,
        vendorStock: Float,
        reference: Float,
        temperatureC: Float,
        impedance: Float,
        index: Int,
        eventTimeMs: Long,
    ) {
        lastChemical = chemical
        lastStock = vendorStock
        lastReference = reference
        lastCalibrationDelta = reference - vendorStock
        updateTelemetry(temperatureC, impedance, index, eventTimeMs)
    }

    private fun updateTelemetry(
        temperatureC: Float,
        impedance: Float,
        index: Int,
        eventTimeMs: Long,
    ) {
        if (impedance.isFinite() && impedance > 0f) {
            impedanceBaseline = if (impedanceBaseline.isFinite()) {
                val relativeDelta = abs(impedance - impedanceBaseline) / max(abs(impedanceBaseline), 1f)
                val rate = if (relativeDelta > IMPEDANCE_SEVERE_FRACTION) {
                    ANOMALOUS_IMPEDANCE_LEARNING_RATE
                } else {
                    IMPEDANCE_LEARNING_RATE
                }
                impedanceBaseline * (1f - rate) + impedance * rate
            } else {
                impedance
            }
        }
        if (temperatureC.isFinite()) lastTemperature = temperatureC
        lastTimestampMs = eventTimeMs
        lastIndex = index
    }

    fun snapshot(): ByteArray = ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { output ->
            output.writeInt(SNAPSHOT_MAGIC)
            output.writeInt(SNAPSHOT_VERSION)
            output.writeFloat(decodedSensitivity)
            output.writeBoolean(initialized)
            output.writeFloat(dynamicProbability)
            output.writeFloat(chemicalGain)
            output.writeFloat(chemicalBias)
            output.writeFloat(meanChemical)
            output.writeFloat(meanStock)
            output.writeFloat(chemicalVariance)
            output.writeFloat(chemicalStockCovariance)
            output.writeFloat(lastChemical)
            output.writeFloat(lastStock)
            output.writeFloat(lastReference)
            output.writeFloat(lastCalibrationDelta)
            output.writeFloat(lastTemperature)
            output.writeFloat(impedanceBaseline)
            output.writeLong(lastTimestampMs)
            output.writeInt(lastIndex)
            output.writeInt(samplesSinceInitialization)
            output.writeFloat(chemicalRateEwma)
            output.writeFloat(stockRateEwma)
            output.writeFloat(fallSupport)
            output.writeFloat(innovationEwma)
            output.writeFloat(referenceTrust)
            steadyModel.writeTo(output)
            dynamicModel.writeTo(output)
        }
        bytes.toByteArray()
    }

    fun restore(snapshot: ByteArray?): Boolean {
        val restored = runCatching {
            if (snapshot == null || snapshot.isEmpty()) return@runCatching false
            DataInputStream(ByteArrayInputStream(snapshot)).use { input ->
                if (input.readInt() != SNAPSHOT_MAGIC) return@use false
                val version = input.readInt()
                if (version != SNAPSHOT_VERSION) return@use false
                val savedSensitivity = input.readFloat()
                if (!savedSensitivity.isFinite() || abs(savedSensitivity - decodedSensitivity) > 0.0001f) {
                    return@use false
                }
                initialized = input.readBoolean()
                dynamicProbability = input.readFloat()
                chemicalGain = input.readFloat()
                chemicalBias = input.readFloat()
                meanChemical = input.readFloat()
                meanStock = input.readFloat()
                chemicalVariance = input.readFloat()
                chemicalStockCovariance = input.readFloat()
                lastChemical = input.readFloat()
                lastStock = input.readFloat()
                lastReference = input.readFloat()
                lastCalibrationDelta = input.readFloat()
                lastTemperature = input.readFloat()
                impedanceBaseline = input.readFloat()
                lastTimestampMs = input.readLong()
                lastIndex = input.readInt()
                samplesSinceInitialization = input.readInt()
                chemicalRateEwma = input.readFloat()
                stockRateEwma = input.readFloat()
                fallSupport = input.readFloat()
                innovationEwma = input.readFloat()
                referenceTrust = input.readFloat()
                steadyModel.readFrom(input)
                dynamicModel.readFrom(input)
                input.available() == 0 && isStateValid()
            }
        }.getOrDefault(false)
        if (!restored) reset()
        return restored
    }

    fun continuationIndex(): Int? = lastIndex.takeIf { initialized && it >= CUSTOM_MODEL_WARMUP_INDEX }

    private fun isStateValid(): Boolean {
        if (!initialized) return true
        return dynamicProbability.isFinite() && dynamicProbability in 0f..1f &&
            chemicalGain.isFinite() && chemicalGain in MIN_CHEMICAL_GAIN..MAX_CHEMICAL_GAIN &&
            chemicalBias.isFinite() && chemicalBias in MIN_CHEMICAL_BIAS..MAX_CHEMICAL_BIAS &&
            meanChemical.isFinite() && meanStock.isFinite() && chemicalVariance.isFinite() &&
            chemicalVariance > 0f && chemicalStockCovariance.isFinite() && lastChemical.isFinite() &&
            lastStock.isFinite() && lastReference.isFinite() && lastIndex >= CUSTOM_MODEL_WARMUP_INDEX &&
            samplesSinceInitialization > 0 && chemicalRateEwma.isFinite() && stockRateEwma.isFinite() &&
            fallSupport.isFinite() && fallSupport in 0f..1f && innovationEwma.isFinite() &&
            innovationEwma >= 0f && referenceTrust.isFinite() &&
            referenceTrust in MIN_REFERENCE_TRUST..1f &&
            steadyModel.isValid() && dynamicModel.isValid()
    }

    fun applyIntegratedCalibration(
        stockMmol: Float,
        eventTimeMs: Long,
        anchors: List<SibionicsCalibrationAnchor>,
    ): Float {
        val valid = anchors.filter { anchor ->
            anchor.sensorMmol.isFinite() && anchor.sensorMmol in 1f..35f &&
                anchor.referenceMmol.isFinite() && anchor.referenceMmol in 1f..35f &&
                (eventTimeMs <= 0L || anchor.timestampMs <= eventTimeMs + 60_000L)
        }
        if (valid.isEmpty()) return stockMmol

        val newestTimestamp = valid.maxOf { it.timestampMs }
        val ageMs = if (eventTimeMs > newestTimestamp) eventTimeMs - newestTimestamp else 0L
        val ageConfidence = exp(-ageMs.toDouble() / CALIBRATION_HALF_LIFE_MS).toFloat()
        if (valid.size == 1) {
            val correction = (valid[0].referenceMmol - valid[0].sensorMmol)
                .coerceIn(-MAX_CALIBRATION_OFFSET, MAX_CALIBRATION_OFFSET)
            return stockMmol + correction * SINGLE_ANCHOR_CONFIDENCE * ageConfidence
        }

        var weights = FloatArray(valid.size) { index ->
            val age = max(0L, newestTimestamp - valid[index].timestampMs)
            exp(-age.toDouble() / CALIBRATION_HALF_LIFE_MS).toFloat().coerceAtLeast(0.05f)
        }
        var gain = 1f
        var bias = weightedOffset(valid, weights)
        repeat(3) {
            val fit = weightedAffine(valid, weights)
            gain = fit.first
            bias = fit.second
            val residuals = FloatArray(valid.size) { index ->
                valid[index].referenceMmol - (gain * valid[index].sensorMmol + bias)
            }
            val center = median(residuals)
            val scale = max(0.18f, median(residuals.map { abs(it - center) }.toFloatArray()))
            weights = FloatArray(valid.size) { index ->
                val residual = abs(residuals[index] - center)
                val huber = min(1f, 1.5f * scale / max(residual, 0.001f))
                weights[index] * huber
            }
        }

        val mapped = gain * stockMmol + bias
        val spread = valid.maxOf { it.sensorMmol } - valid.minOf { it.sensorMmol }
        val countConfidence = (0.55f + valid.size * 0.08f).coerceAtMost(0.95f)
        val spreadConfidence = (0.65f + spread / 8f).coerceIn(0.65f, 1f)
        val confidence = countConfidence * spreadConfidence * ageConfidence
        val maxCorrection = max(0.8f, min(3f, stockMmol * 0.30f))
        val correction = (mapped - stockMmol).coerceIn(-maxCorrection, maxCorrection)
        return stockMmol + correction * confidence
    }

    private fun weightedAffine(
        anchors: List<SibionicsCalibrationAnchor>,
        weights: FloatArray,
    ): Pair<Float, Float> {
        val sumWeight = weights.sum().coerceAtLeast(0.001f)
        val meanX = anchors.indices.sumOf { weights[it].toDouble() * anchors[it].sensorMmol } / sumWeight
        val meanY = anchors.indices.sumOf { weights[it].toDouble() * anchors[it].referenceMmol } / sumWeight
        var covariance = 0.0
        var variance = 0.0
        anchors.indices.forEach { index ->
            val dx = anchors[index].sensorMmol - meanX
            covariance += weights[index] * dx * (anchors[index].referenceMmol - meanY)
            variance += weights[index] * dx * dx
        }
        val spread = anchors.maxOf { it.sensorMmol } - anchors.minOf { it.sensorMmol }
        val gain = if (spread < 1.2f || variance < 0.25) 1f else (covariance / variance).toFloat()
            .coerceIn(MIN_CALIBRATION_GAIN, MAX_CALIBRATION_GAIN)
        val bias = (meanY - gain * meanX).toFloat().coerceIn(-MAX_CALIBRATION_OFFSET, MAX_CALIBRATION_OFFSET)
        return gain to bias
    }

    private fun weightedOffset(anchors: List<SibionicsCalibrationAnchor>, weights: FloatArray): Float {
        val sumWeight = weights.sum().coerceAtLeast(0.001f)
        return (anchors.indices.sumOf { index ->
            weights[index].toDouble() * (anchors[index].referenceMmol - anchors[index].sensorMmol)
        } / sumWeight).toFloat().coerceIn(-MAX_CALIBRATION_OFFSET, MAX_CALIBRATION_OFFSET)
    }

    private fun median(values: FloatArray): Float {
        if (values.isEmpty()) return 0f
        val sorted = values.sortedArray()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 0) (sorted[middle - 1] + sorted[middle]) / 2f else sorted[middle]
    }

    private fun roundTenth(value: Float): Float = ((value * 10f) + 0.5f).toInt() / 10f

    private companion object {
        private const val SNAPSHOT_MAGIC = 0x5349_4241
        private const val SNAPSHOT_VERSION = 6
        private const val DEFAULT_SENSITIVITY = 1.27f
        private const val CUSTOM_MODEL_WARMUP_INDEX = 120

        private const val STEADY_PROCESS_NOISE = 0.00025f
        private const val DYNAMIC_PROCESS_NOISE = 0.008f
        private const val BASE_MEASUREMENT_VARIANCE = 0.035f
        private const val INITIAL_DYNAMIC_PROBABILITY = 0.18f
        private const val MIN_DYNAMIC_PROBABILITY = 0.05f
        private const val MAX_DYNAMIC_PROBABILITY = 0.95f
        private const val MODEL_PERSISTENCE = 0.94f
        private const val LIKELIHOOD_WEIGHT = 0.75f
        private const val MOTION_WEIGHT = 0.25f
        private const val DYNAMIC_RATE_SCALE = 0.12f
        private const val MIN_LEAD_MINUTES = 2f
        private const val MAX_LEAD_MINUTES = 4f

        private const val INITIAL_MAPPING_VARIANCE = 0.001f
        private const val MIN_MAPPING_VARIANCE = 0.02f
        private const val MAPPING_FORGETTING_FACTOR = 0.9993f
        private const val MAPPING_MAX_CHEMICAL_RATE = 0.45f
        private const val MAPPING_MAX_STOCK_RATE = 0.60f
        private const val MAPPING_DYNAMIC_WEIGHT = 0.15f
        private const val MAPPING_GAIN_RATE = 0.002f
        private const val MAPPING_BIAS_RATE = 0.0015f
        private const val MAPPING_MEAN_BIAS_RATE = 0.001f
        private const val MAX_MAPPING_BIAS_STEP = 0.25f
        private const val MAX_MAPPING_MEAN_STEP = 0.10f
        private const val MIN_CHEMICAL_GAIN = 0.75f
        private const val MAX_CHEMICAL_GAIN = 1.35f
        private const val MIN_CHEMICAL_BIAS = -8f
        private const val MAX_CHEMICAL_BIAS = 12f

        private const val CALIBRATION_STATE_SHIFT_THRESHOLD = 0.08f
        private const val MAX_REFERENCE_DIVERGENCE = 2.5f
        private const val LOW_GLUCOSE_GUARD = 4.2f
        private const val LOW_FALL_ALLOWANCE = 0.2f
        private const val MIN_OUTPUT_MMOL = 1.1f
        private const val MAX_OUTPUT_MMOL = 35f

        // Plausibility gating.
        //
        // The allowances below are divergences from the vendor reference, never
        // absolute glucose limits: with full evidence the model still reaches
        // MAX_REFERENCE_DIVERGENCE below a reference that is itself low, so real
        // values under 3 mmol/L stay reachable.
        private const val MIN_DOWNWARD_DIVERGENCE = 0.35f
        private const val MIN_UPWARD_DIVERGENCE = 1.2f
        // Roughly the fastest sustained fall seen physiologically (~4 mg/dL/min).
        // Faster chemical motion is treated as weaker, not stronger, evidence.
        private const val PLAUSIBLE_RATE_MMOL_PER_MIN = 0.22f
        private const val FALL_RATE_THRESHOLD = 0.02f
        private const val FALL_SUPPORT_RISE = 0.30f
        private const val FALL_SUPPORT_DECAY = 0.55f
        private const val RATE_SMOOTHING = 0.35f
        private const val STOCK_RATE_SMOOTHING = 0.30f
        private const val INNOVATION_SMOOTHING = 0.25f
        private const val INNOVATION_SCALE = 0.55f
        private const val MODEL_VARIANCE_SCALE = 0.35f
        private const val QUALITY_FLOOR = 0.55f
        private const val PERSISTENCE_WEIGHT = 0.45f
        private const val AGREEMENT_WEIGHT = 0.30f
        private const val COHERENCE_WEIGHT = 0.25f
        private const val INITIAL_REFERENCE_TRUST = 0.75f
        private const val MIN_REFERENCE_TRUST = 0.35f
        private const val TRUST_SLEW = 0.06f
        private const val LEAD_TAPER_START_MMOL = 5.5f
        private const val LEAD_TAPER_END_MMOL = 3.8f
        private const val LEAD_AGREEMENT_FLOOR = 0.35f
        private const val MAX_LEAD_DROP_MMOL = 0.6f

        private const val TEMPERATURE_JUMP_C = 1.2f
        private const val IMPEDANCE_QUALITY_SLOPE = 5f
        private const val IMPEDANCE_SEVERE_FRACTION = 0.75f
        private const val MIN_QUALITY = 0.20f
        private const val IMPEDANCE_LEARNING_RATE = 0.04f
        private const val ANOMALOUS_IMPEDANCE_LEARNING_RATE = 0.004f

        private const val SINGLE_ANCHOR_CONFIDENCE = 0.82f
        private const val MIN_CALIBRATION_GAIN = 0.78f
        private const val MAX_CALIBRATION_GAIN = 1.22f
        private const val MAX_CALIBRATION_OFFSET = 3f
        private const val CALIBRATION_HALF_LIFE_MS = 5.0 * 24.0 * 60.0 * 60.0 * 1000.0
    }
}
