package tk.glucodata.drivers.sibionics

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import kotlin.math.abs
import kotlin.math.max
import tk.glucodata.GlucoseUncertainty
import tk.glucodata.drivers.sibionics.adaptive2.AdaptiveV2Diagnostics
import tk.glucodata.drivers.sibionics.adaptive2.AdaptiveV2Reference
import tk.glucodata.drivers.sibionics.adaptive2.ProbabilisticGlucoseEstimate
import tk.glucodata.drivers.sibionics.adaptive2.SibionicsAdaptiveV2Context
import tk.glucodata.drivers.sibionics.v116a.SibionicsExactV116ACore

enum class SibionicsAlgorithmMode {
    LIVE,
    REPLAY,
}

internal data class SibionicsChemicalSignal(
    val mmol: Float,
    val qualityFlags: Int,
)

/**
 * Sensor-specific state extracted from the vendor front end, on the absolute
 * glucose scale, without using the vendor's final output.
 *
 * This exists because [SibionicsChemicalSignal] is *not* absolute glucose, and
 * treating it as such is what made Adaptive V2 read ~2 mmol/L low on real
 * sensors. The vendor pipeline continues past that point:
 *
 * ```
 *   chemical = compensated / activeSensitivity
 *   adjusted = (compensated - base) / activeSensitivity
 *   esa      = adjusted + mean(last five clip compensationSize values)   <-- absolute
 *   deconv   = 30-tap FIR(esa) * 6.7                                     <-- dynamics
 *   display  = deconv + calibrationCompensation
 * ```
 *
 * The ESA term is the manufacturer's own estimate of how far the chemical
 * signal sits from real glucose given sensor sensitivity, drift, age and
 * pathological state. On a realistic trace it averages +1.2 mmol/L and reaches
 * +2.8. Discarding it does not make an estimator independent, it makes it
 * wrong.
 *
 * [calibratedMmol] therefore stops one stage short of the vendor's estimator:
 * it keeps the sensor-state calibration and drops the five history filters, the
 * deconvolution FIR and the display rounding — which are exactly the parts
 * Adaptive V2 replaces. Final stock glucose is still never used.
 */
internal data class SibionicsSensorObservation(
    /** Vendor-calibrated observation in mmol/L, before display filtering and deconvolution. */
    val calibratedMmol: Float,
    /** The uncalibrated pre-compensation signal. Diagnostics only. */
    val chemicalMmol: Float,
    /** Absolute sensor-state compensation the vendor applies via ESA, in mmol/L. */
    val sensorStateCompensationMmol: Float,
    val qualityFlags: Int,
    /** Factory/QR sensitivity as decoded at pairing. */
    val factorySensitivity: Float,
    /** The vendor's current running sensitivity estimate, which tracks drift. */
    val activeSensitivity: Float,
    /** Sensor minute index; doubles as sensor age. */
    val sensorAgeMinutes: Int,
    /** Algorithm family that produced this observation (115 or 116). */
    val family: Int,
) {
    val isUsable: Boolean
        get() = calibratedMmol.isFinite() && calibratedMmol > 0f &&
            activeSensitivity.isFinite() && activeSensitivity > 0f
}

enum class SibionicsAlgorithmSelection(val storageId: Int) {
    STOCK(0),
    STOCK_CALIBRATED(1),
    STATE_MODEL(2),
    STATE_MODEL_CALIBRATED(3),
    BALANCED_TRACKER(4),
    BALANCED_TRACKER_CALIBRATED(5),
    RESPONSIVE_ESTIMATOR(6),
    RESPONSIVE_ESTIMATOR_CALIBRATED(7),
    ADAPTIVE_V2(8),
    ADAPTIVE_V2_CALIBRATED(9);

    val calibrationEnabled: Boolean get() = storageId and 1 != 0
    val model: SibionicsCustomAlgorithmModel
        get() = SibionicsCustomAlgorithmModel.fromStorage(storageId and MODEL_MASK)
    val customModelEnabled: Boolean get() = model != SibionicsCustomAlgorithmModel.STOCK

    fun withCalibration(enabled: Boolean): SibionicsAlgorithmSelection =
        fromStorage(if (enabled) storageId or CALIBRATION_BIT else storageId and CALIBRATION_BIT.inv())

    fun withModel(model: SibionicsCustomAlgorithmModel): SibionicsAlgorithmSelection =
        fromStorage(model.storageBase or (storageId and CALIBRATION_BIT))

    companion object {
        private const val CALIBRATION_BIT = 1

        /**
         * Model bits of [storageId]. Widened from 6 to 14 when Adaptive V2 was
         * added at base 8; the four legacy ids keep their exact values, so a
         * stored selection made before V2 existed still decodes to the same
         * model.
         */
        const val MODEL_MASK = 14
        val DEFAULT = STOCK_CALIBRATED

        fun fromStorage(value: Int): SibionicsAlgorithmSelection =
            entries.firstOrNull { it.storageId == value } ?: STOCK
    }
}

enum class SibionicsCustomAlgorithmModel(val storageBase: Int) {
    STOCK(0),
    STATE_MODEL(2),
    BALANCED_TRACKER(4),
    RESPONSIVE_ESTIMATOR(6),

    /**
     * Independent probabilistic estimator. Experimental, and deliberately not
     * the default: stock behaviour is unchanged for every existing selection.
     */
    ADAPTIVE_V2(8);

    /** True when the model reports a credible interval alongside its value. */
    val providesUncertainty: Boolean get() = this == ADAPTIVE_V2

    companion object {
        fun fromStorage(value: Int): SibionicsCustomAlgorithmModel =
            entries.firstOrNull { it.storageBase == value } ?: STOCK
    }
}

/**
 * Driver-facing Sibionics algorithm.
 *
 * Every valid one-minute packet produces a value. The exact core advances for
 * every packet and periodically refreshes the algorithm-to-raw correction;
 * packets between those refreshes use the latest correction, matching the
 * legacy driver cadence.
 */
class SibionicsAlgorithmContext(
    private val sensorId: String,
) {
    private var family = AlgorithmFamily.V115G
    private var configuredSensitivity = 1.27f
    private val v115Core = SibionicsExactV115GCore()
    private val v116Core = SibionicsExactV116ACore()
    private val adaptiveCore = SibionicsAdaptiveAlgorithmContext()
    private val balancedCore = SibionicsBalancedAlgorithmContext()
    private val responsiveCore = SibionicsResponsiveAlgorithmContext()
    private val adaptiveV2Core = SibionicsAdaptiveV2Context()
    private var latestV2Estimate: ProbabilisticGlucoseEstimate? = null

    private var liveDeltaMmol = Float.NaN
    private var replayDeltaMmol = Float.NaN
    private var selection = SibionicsAlgorithmSelection.STOCK

    fun configure(
        @Suppress("unused") shortCode: String,
        sensitivity: Float,
        variant: SibionicsConstants.Variant = SibionicsConstants.Variant.CHINESE,
        selection: SibionicsAlgorithmSelection = SibionicsAlgorithmSelection.STOCK,
    ) {
        val configuredFamily = if (variant.usesV116AAlgorithm) {
            AlgorithmFamily.V116A
        } else {
            AlgorithmFamily.V115G
        }
        if (configuredFamily != family) {
            family = configuredFamily
            resetWrapperState()
        }
        when (family) {
            AlgorithmFamily.V115G -> v115Core.configure(sensitivity)
            AlgorithmFamily.V116A -> v116Core.configure(sensitivity)
        }
        adaptiveCore.configure(sensitivity)
        balancedCore.configure(sensitivity)
        responsiveCore.configure(sensitivity)
        adaptiveV2Core.configure(sensitivity)
        configuredSensitivity = sensitivity
        this.selection = selection
    }

    fun setSelection(selection: SibionicsAlgorithmSelection) {
        if (this.selection == selection) return
        this.selection = selection
        resetCustomModels()
    }

    fun reset() {
        v115Core.reset()
        v116Core.reset()
        adaptiveCore.reset()
        balancedCore.reset()
        responsiveCore.reset()
        adaptiveV2Core.reset()
        latestV2Estimate = null
        resetWrapperState()
    }

    /**
     * Posterior of the most recent Adaptive V2 sample, or null for every other
     * model. Callers use this for uncertainty display and probability queries;
     * it is never required to produce a glucose value.
     */
    fun latestProbabilisticEstimate(): ProbabilisticGlucoseEstimate? =
        latestV2Estimate.takeIf { selection.model == SibionicsCustomAlgorithmModel.ADAPTIVE_V2 }

    /** Generic uncertainty for the most recent sample, in mmol/L. */
    fun latestUncertaintyMmol(): GlucoseUncertainty? =
        latestProbabilisticEstimate()?.takeIf { it.isUsable }?.let {
            GlucoseUncertainty(
                lower = it.lower90Mmol,
                upper = it.upper90Mmol,
                intervalMass = GlucoseUncertainty.DEFAULT_INTERVAL_MASS,
                confidence = it.confidence,
                artifactProbability = it.artifactProbability,
            )
        }

    /** Posterior P(glucose < threshold) for the most recent Adaptive V2 sample. */
    fun probabilityBelowMmol(thresholdMmol: Float): Float =
        if (selection.model == SibionicsCustomAlgorithmModel.ADAPTIVE_V2) {
            adaptiveV2Core.probabilityBelow(thresholdMmol)
        } else {
            Float.NaN
        }

    /** Enables the Adaptive V2 developer trace, retaining at most [capacity] rows. */
    fun enableV2Diagnostics(capacity: Int) = adaptiveV2Core.enableDiagnostics(capacity)

    fun v2Diagnostics(): List<AdaptiveV2Diagnostics> = adaptiveV2Core.diagnostics()

    fun v2DiagnosticsCsv(): String = adaptiveV2Core.diagnosticsCsv()

    fun process(
        rawMmol: Float,
        temperatureC: Float,
        index: Int,
        mode: SibionicsAlgorithmMode,
        impedance: Float = Float.NaN,
        eventTimeMs: Long = 0L,
        calibrationAnchors: List<SibionicsCalibrationAnchor> = emptyList(),
    ): Float {
        val stock = processStock(rawMmol, temperatureC, index, mode)
        if (!stock.isFinite()) return Float.NaN
        val measurement = if (selection.calibrationEnabled) {
            adaptiveCore.applyIntegratedCalibration(stock, eventTimeMs, calibrationAnchors)
        } else {
            stock
        }
        return processPreparedMeasurement(
            stockMmol = stock,
            measurementMmol = measurement,
            rawMmol = rawMmol,
            temperatureC = temperatureC,
            index = index,
            impedance = impedance,
            eventTimeMs = eventTimeMs,
            // Adaptive V2 consumes anchors as direct observations of its own
            // glucose state, so it needs them unmapped — the stock-calibrated
            // `measurement` above is not a calibration path it can use.
            calibrationAnchors = calibrationAnchors,
        )
    }

    /** Advances only the exact vendor-compatible chemical model. */
    fun processStock(
        rawMmol: Float,
        temperatureC: Float,
        index: Int,
        mode: SibionicsAlgorithmMode,
    ): Float {
        if (!rawMmol.isFinite() || rawMmol <= 0f) return Float.NaN

        val candidate = when (family) {
            AlgorithmFamily.V115G -> v115Core.process(rawMmol, temperatureC, index)
            AlgorithmFamily.V116A -> v116Core.process(rawMmol, temperatureC, index)
        }
        val display = when (mode) {
            SibionicsAlgorithmMode.LIVE -> liveValue(rawMmol, candidate)
            SibionicsAlgorithmMode.REPLAY -> replayValue(rawMmol, candidate)
        }

        if (!display.isFinite() || display > MAX_VALID_MMOL) {
            clearDelta(mode)
            return nativeRound(rawMmol)
        }

        return max(display, 0f)
    }

    /**
     * Feeds a prepared measurement through the optional adaptive state model.
     * The exact core is not advanced here, which makes a two-pass historical
     * calibration rebuild deterministic.
     */
    internal fun processPreparedMeasurement(
        stockMmol: Float,
        measurementMmol: Float,
        rawMmol: Float,
        temperatureC: Float,
        index: Int,
        impedance: Float = Float.NaN,
        eventTimeMs: Long = 0L,
        chemicalSignal: SibionicsChemicalSignal? = latestChemicalSignal(),
        calibrationAnchors: List<SibionicsCalibrationAnchor> = emptyList(),
        sensorObservation: SibionicsSensorObservation? = null,
    ): Float {
        if (!stockMmol.isFinite() || stockMmol <= 0f) return Float.NaN
        val measurement = measurementMmol.takeIf { it.isFinite() && it > 0f } ?: stockMmol
        if (selection.model != SibionicsCustomAlgorithmModel.ADAPTIVE_V2) latestV2Estimate = null
        return when (selection.model) {
            SibionicsCustomAlgorithmModel.STOCK -> nativeRound(measurement)
            SibionicsCustomAlgorithmModel.STATE_MODEL -> adaptiveCore.process(
                stockMmol = measurement,
                vendorStockMmol = stockMmol,
                rawMmol = rawMmol,
                chemicalMmol = chemicalSignal?.mmol ?: Float.NaN,
                chemicalQualityFlags = chemicalSignal?.qualityFlags ?: 0,
                temperatureC = temperatureC,
                impedance = impedance,
                index = index,
                eventTimeMs = eventTimeMs,
                anchors = emptyList(),
            )
            SibionicsCustomAlgorithmModel.BALANCED_TRACKER -> balancedCore.process(
                stockMmol = measurement,
                rawMmol = rawMmol,
                temperatureC = temperatureC,
                impedance = impedance,
                index = index,
                eventTimeMs = eventTimeMs,
                anchors = emptyList(),
            )
            SibionicsCustomAlgorithmModel.RESPONSIVE_ESTIMATOR -> responsiveCore.process(
                stockMmol = measurement,
                rawMmol = rawMmol,
                temperatureC = temperatureC,
                impedance = impedance,
                index = index,
                eventTimeMs = eventTimeMs,
                anchors = emptyList(),
            )
            SibionicsCustomAlgorithmModel.ADAPTIVE_V2 -> processAdaptiveV2(
                measurement = measurement,
                stockMmol = stockMmol,
                observation = sensorObservation ?: latestSensorObservation(),
                temperatureC = temperatureC,
                impedance = impedance,
                index = index,
                eventTimeMs = eventTimeMs,
                calibrationAnchors = calibrationAnchors,
            )
        }
    }

    /**
     * Adaptive V2 path.
     *
     * The observation is the vendor's sensor-state-calibrated signal, which
     * keeps the manufacturer's sensitivity/drift/compensation knowledge and
     * drops only the display filtering and deconvolution that V2 replaces.
     * [stockMmol] is forwarded solely as a diagnostics comparison column, and
     * [measurement] is used only as a catastrophic fallback when there is no
     * usable observation at all — a fallback that is emitted to the app and
     * deliberately never fed back into the estimator's state.
     */
    private fun processAdaptiveV2(
        measurement: Float,
        stockMmol: Float,
        observation: SibionicsSensorObservation?,
        temperatureC: Float,
        impedance: Float,
        index: Int,
        eventTimeMs: Long,
        calibrationAnchors: List<SibionicsCalibrationAnchor>,
    ): Float {
        if (observation == null || !observation.isUsable) {
            latestV2Estimate = null
            return nativeRound(measurement)
        }
        val references = if (selection.calibrationEnabled) {
            calibrationAnchors.mapNotNull { anchor ->
                anchor.referenceMmol
                    .takeIf { it.isFinite() && it in 1f..35f }
                    ?.let { AdaptiveV2Reference(it, anchor.timestampMs) }
            }
        } else {
            emptyList()
        }
        val estimate = adaptiveV2Core.process(
            observation = observation,
            temperatureC = temperatureC,
            impedance = impedance,
            eventTimeMs = eventTimeMs,
            references = references,
            stockComparisonMmol = stockMmol,
        )
        latestV2Estimate = estimate
        if (estimate == null || !estimate.isUsable) return nativeRound(measurement)
        return nativeRound(estimate.glucoseMmol)
    }

    internal fun latestChemicalSignal(): SibionicsChemicalSignal? = when (family) {
        AlgorithmFamily.V115G -> v115Core.latestChemicalSignal
        AlgorithmFamily.V116A -> v116Core.latestChemicalSignal
    }

    /**
     * Vendor sensor-state observation on the absolute glucose scale.
     *
     * Both families expose one. They arrive at it differently — V1.1.5G is a
     * structured port whose ESA stage can be read directly, while V1.1.6A is a
     * transliterated state machine where the same value is recovered from the
     * input the deconvolution stage recorded — but the quantity is identical:
     * sensor-state compensation applied, deconvolution not yet.
     */
    internal fun latestSensorObservation(): SibionicsSensorObservation? = when (family) {
        AlgorithmFamily.V115G -> v115Core.latestSensorObservation
        AlgorithmFamily.V116A -> v116Core.latestSensorObservation
    }

    /** Last one-minute input represented by the active custom-model snapshot. */
    internal fun customContinuationIndex(): Int? = when (selection.model) {
        SibionicsCustomAlgorithmModel.STOCK -> null
        SibionicsCustomAlgorithmModel.STATE_MODEL -> adaptiveCore.continuationIndex()
        SibionicsCustomAlgorithmModel.BALANCED_TRACKER -> balancedCore.continuationIndex()
        SibionicsCustomAlgorithmModel.RESPONSIVE_ESTIMATOR -> responsiveCore.continuationIndex()
        SibionicsCustomAlgorithmModel.ADAPTIVE_V2 -> adaptiveV2Core.continuationIndex()
    }

    internal fun hasExactContinuation(nextIndex: Int): Boolean =
        !selection.customModelEnabled || customContinuationIndex() == nextIndex - 1

    fun snapshot(): ByteArray = ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { output ->
            output.writeInt(SNAPSHOT_MAGIC)
            output.writeInt(SNAPSHOT_VERSION)
            output.writeInt(family.snapshotId)
            output.writeFloat(liveDeltaMmol)
            output.writeFloat(replayDeltaMmol)
            val coreState = when (family) {
                AlgorithmFamily.V115G -> v115Core.snapshot()
                AlgorithmFamily.V116A -> v116Core.snapshot()
            }
            output.writeInt(coreState.size)
            output.write(coreState)
            output.writeInt(selection.model.storageBase)
            val customState = activeCustomSnapshot()
            output.writeInt(customState.size)
            output.write(customState)
        }
        bytes.toByteArray()
    }

    fun restore(snapshot: ByteArray?): Boolean {
        if (snapshot == null || snapshot.isEmpty()) return false

        val restored = runCatching {
            DataInputStream(ByteArrayInputStream(snapshot)).use { input ->
                val magic = input.readInt()
                if (magic != SNAPSHOT_MAGIC) return@use restoreLegacyCoreSnapshot(snapshot)
                val version = input.readInt()
                when (version) {
                    SNAPSHOT_VERSION,
                    CHEMICAL_STATE_SNAPSHOT_VERSION,
                    EXACT_ONLY_SNAPSHOT_VERSION -> if (input.readInt() != family.snapshotId) return@use false
                    LEGACY_WRAPPER_SNAPSHOT_VERSION -> if (family != AlgorithmFamily.V115G) return@use false
                    else -> return@use false
                }

                val savedLiveDelta = input.readFloat()
                val savedReplayDelta = input.readFloat()
                val coreSize = input.readInt()
                if (!isStoredDeltaValid(savedLiveDelta) || !isStoredDeltaValid(savedReplayDelta)) return@use false
                if (coreSize !in 1..MAX_CORE_SNAPSHOT_BYTES) return@use false

                val coreState = ByteArray(coreSize)
                input.readFully(coreState)
                if (!restoreCore(coreState)) return@use false

                if (version == SNAPSHOT_VERSION) {
                    val savedModel = SibionicsCustomAlgorithmModel.fromStorage(input.readInt())
                    val adaptiveSize = input.readInt()
                    if (adaptiveSize !in 1..MAX_ADAPTIVE_SNAPSHOT_BYTES) return@use false
                    val adaptiveState = ByteArray(adaptiveSize)
                    input.readFully(adaptiveState)
                    if (savedModel == selection.model) {
                        if (!restoreActiveCustomSnapshot(adaptiveState)) return@use false
                    } else {
                        // Rebuilds intentionally transfer the fully advanced exact core from a
                        // temporary stock context, then replay prepared measurements through the
                        // newly selected custom model. A model mismatch invalidates only custom
                        // state; rejecting the whole snapshot would also discard the exact core.
                        resetCustomModels()
                    }
                } else if (version == CHEMICAL_STATE_SNAPSHOT_VERSION) {
                    val adaptiveSize = input.readInt()
                    if (adaptiveSize !in 1..MAX_ADAPTIVE_SNAPSHOT_BYTES) return@use false
                    val adaptiveState = ByteArray(adaptiveSize)
                    input.readFully(adaptiveState)
                    if (selection.model == SibionicsCustomAlgorithmModel.STATE_MODEL &&
                        !adaptiveCore.restore(adaptiveState)
                    ) return@use false
                    if (selection.model != SibionicsCustomAlgorithmModel.STATE_MODEL) resetCustomModels()
                } else {
                    resetCustomModels()
                }
                if (input.available() != 0) return@use false

                liveDeltaMmol = savedLiveDelta
                replayDeltaMmol = savedReplayDelta
                true
            }
        }.getOrDefault(false)

        if (!restored) reset()
        return restored
    }

    private fun liveValue(rawMmol: Float, candidate: Float?): Float {
        if (candidate != null && isUsableCandidate(candidate)) {
            liveDeltaMmol = candidate - rawMmol
            return candidate
        }

        val delta = when {
            isUsableDelta(liveDeltaMmol) -> liveDeltaMmol
            isUsableDelta(replayDeltaMmol) -> replayDeltaMmol
            else -> Float.NaN
        }
        if (!delta.isFinite()) return nativeRound(rawMmol)
        liveDeltaMmol = delta
        return nativeRound(rawMmol + delta)
    }

    private fun replayValue(rawMmol: Float, candidate: Float?): Float {
        if (candidate != null && isUsableCandidate(candidate)) {
            val delta = candidate - rawMmol
            replayDeltaMmol = delta
            // A replay sample is normally newer backfill received immediately before the
            // current live sample. Keep the live fallback aligned with that newer exact
            // correction; otherwise a reconnect can reuse an older live delta and create a
            // one- or two-minute spike that disappears when the same journal is rebuilt.
            liveDeltaMmol = delta
            return candidate
        }
        return if (isUsableDelta(replayDeltaMmol)) {
            nativeRound(rawMmol + replayDeltaMmol)
        } else {
            nativeRound(rawMmol)
        }
    }

    private fun restoreLegacyCoreSnapshot(snapshot: ByteArray): Boolean {
        if (!restoreCore(snapshot)) return false
        resetCustomModels()
        resetWrapperState()
        return true
    }

    private fun resetCustomModels() {
        adaptiveCore.reset()
        balancedCore.reset()
        responsiveCore.reset()
        adaptiveV2Core.reset()
        latestV2Estimate = null
    }

    private fun activeCustomSnapshot(): ByteArray = when (selection.model) {
        SibionicsCustomAlgorithmModel.STOCK -> ByteArray(1)
        SibionicsCustomAlgorithmModel.STATE_MODEL -> adaptiveCore.snapshot()
        SibionicsCustomAlgorithmModel.BALANCED_TRACKER -> balancedCore.snapshot()
        SibionicsCustomAlgorithmModel.RESPONSIVE_ESTIMATOR -> responsiveCore.snapshot()
        SibionicsCustomAlgorithmModel.ADAPTIVE_V2 -> adaptiveV2Core.snapshot()
    }

    private fun restoreActiveCustomSnapshot(snapshot: ByteArray): Boolean = when (selection.model) {
        SibionicsCustomAlgorithmModel.STOCK -> snapshot.size == 1
        SibionicsCustomAlgorithmModel.STATE_MODEL -> adaptiveCore.restore(snapshot)
        SibionicsCustomAlgorithmModel.BALANCED_TRACKER -> balancedCore.restore(snapshot)
        SibionicsCustomAlgorithmModel.RESPONSIVE_ESTIMATOR -> responsiveCore.restore(snapshot)
        SibionicsCustomAlgorithmModel.ADAPTIVE_V2 -> adaptiveV2Core.restore(snapshot)
    }

    private fun restoreCore(snapshot: ByteArray): Boolean = when (family) {
        AlgorithmFamily.V115G -> v115Core.restore(snapshot)
        AlgorithmFamily.V116A -> v116Core.restore(snapshot)
    }

    private fun clearDelta(mode: SibionicsAlgorithmMode) {
        when (mode) {
            SibionicsAlgorithmMode.LIVE -> liveDeltaMmol = Float.NaN
            SibionicsAlgorithmMode.REPLAY -> replayDeltaMmol = Float.NaN
        }
    }

    private fun resetWrapperState() {
        liveDeltaMmol = Float.NaN
        replayDeltaMmol = Float.NaN
    }

    private fun isUsableCandidate(candidate: Float): Boolean =
        candidate.isFinite() && candidate > MIN_ALGORITHM_MMOL && candidate <= MAX_VALID_MMOL

    private fun isUsableDelta(delta: Float): Boolean = delta.isFinite() && abs(delta) < MAX_DELTA_MMOL

    private fun isStoredDeltaValid(delta: Float): Boolean = delta.isNaN() || isUsableDelta(delta)

    private fun nativeRound(value: Float): Float {
        if (!value.isFinite()) return Float.NaN
        val scaled = value * 10f
        val rounded = if (value >= 0f) scaled + 0.5f else scaled
        return rounded.toInt() / 10f
    }

    private companion object {
        private const val SNAPSHOT_MAGIC = 0x5349_4234
        private const val SNAPSHOT_VERSION = 5
        private const val CHEMICAL_STATE_SNAPSHOT_VERSION = 4
        private const val EXACT_ONLY_SNAPSHOT_VERSION = 3
        private const val LEGACY_WRAPPER_SNAPSHOT_VERSION = 2
        private const val MAX_CORE_SNAPSHOT_BYTES = 64 * 1024
        private const val MAX_ADAPTIVE_SNAPSHOT_BYTES = 16 * 1024
        private const val MIN_ALGORITHM_MMOL = 1f
        private const val MAX_VALID_MMOL = SibionicsConstants.MAX_ALGORITHM_GLUCOSE_MMOL
        private const val MAX_DELTA_MMOL = 40f
    }

    private enum class AlgorithmFamily(val snapshotId: Int) {
        V115G(115),
        V116A(116),
    }
}
