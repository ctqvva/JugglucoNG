package tk.glucodata.drivers.aidex.adaptive2

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.zip.CRC32
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * Explicit choices for the missing AiDex factory conversion.
 * FACTORY_ASSISTED learns a slow raw-to-glucose curve from raw/factory pairs; it is
 * a hybrid, not a reconstruction of the sensor's proprietary factory algorithm.
 * REFERENCE_ONLY never reads factory glucose and waits for a timely reference.
 */
enum class AiDexNormalization { FACTORY_ASSISTED, REFERENCE_ONLY }

data class AiDexAdaptiveSample(
    val timestampMs: Long,
    /** Activation/reset identity supplied by the caller, not inferred from glucose. */
    val sessionStartMs: Long,
    /** The app's stored raw lane: i1 * 10. Its mg/dL label is not a factory calibration. */
    val rawProxyMgDl: Float,
    val factoryMgDl: Float = Float.NaN,
    val temperatureC: Float = Float.NaN,
)

data class AiDexReference(val timestampMs: Long, val glucoseMgDl: Float)

data class AiDexAdaptiveResult(
    val timestampMs: Long,
    val estimate: ProbabilisticGlucoseEstimate,
    val normalizedRawMmol: Float,
    val rawToGlucoseScale: Float,
    val normalization: AiDexNormalization,
)

/**
 * Replay/driver-facing AiDex V2. All arithmetic uses mmol/L; transport and
 * storage adapters pass mg/dL explicitly. No shared Sibionics state is touched.
 *
 * A slow robust normaliser replaces the factory compensation available to
 * Sibionics. It deliberately cannot identify scale, offset, sensor aging and
 * true glucose from raw alone. In factory-assisted mode a reference anchors
 * the filtered output, with later mapping changes evaluated at that reference's
 * raw level. The estimator's own sensor states never hold the correction.
 *
 * This is an experimental model. Mixture intervals are model uncertainty, not
 * validated clinical coverage. Selecting/publishing it in an app requires a
 * separate integration with one authoritative history/live/calibration lane.
 */
class AiDexAdaptiveV2(val normalization: AiDexNormalization = AiDexNormalization.FACTORY_ASSISTED) {
    private var estimator = AdaptiveV2Estimator().apply { configure(1f) }
    private var normalizer = AiDexSignalNormalizer(normalization)
    private var correction = AiDexReferenceCorrection()
    private var sessionStartMs = 0L
    private var lastTimestampMs = 0L
    private var lastIndex = -1
    private var mixture: GaussianMixture1D? = null

    fun reset() {
        estimator = AdaptiveV2Estimator().apply { configure(1f) }
        normalizer = AiDexSignalNormalizer(normalization)
        correction = AiDexReferenceCorrection()
        sessionStartMs = 0L
        lastTimestampMs = 0L
        lastIndex = -1
        mixture = null
    }

    /** Stale/duplicate/invalid rows never reset or mutate an existing session. */
    fun process(sample: AiDexAdaptiveSample, references: List<AiDexReference> = emptyList()): AiDexAdaptiveResult? {
        if (sample.sessionStartMs <= 0L || sample.timestampMs < sample.sessionStartMs ||
            !sample.rawProxyMgDl.isFinite() || sample.rawProxyMgDl <= 0f ||
            sample.rawProxyMgDl > 6_553.5f
        ) return null
        if (sessionStartMs > 0L && sample.sessionStartMs < sessionStartMs) return null
        val newSession = sample.sessionStartMs != sessionStartMs
        val indexLong = (sample.timestampMs - sample.sessionStartMs) / 60_000L
        if (indexLong > Int.MAX_VALUE) return null
        val index = indexLong.toInt()
        if (!newSession && (sample.timestampMs <= lastTimestampMs || index <= lastIndex)) return null
        if (newSession) {
            reset()
            sessionStartMs = sample.sessionStartMs
        }
        val timely = references.filter {
            it.glucoseMgDl.isFinite() && it.glucoseMgDl in MGDL_PER_MMOL..(35f * MGDL_PER_MMOL) &&
                it.timestampMs >= sessionStartMs &&
                abs(it.timestampMs - sample.timestampMs) <= 60_000L
        }.sortedBy { it.timestampMs }
        val factoryAssisted = normalization == AiDexNormalization.FACTORY_ASSISTED
        val bootstrapping = !normalizer.hasMapping
        val normalized = normalizer.process(sample, timely.firstOrNull())
        if (normalized == null) {
            // There is no displayed model value during bootstrap. Preserve a
            // timely reference against its factory observation, without letting
            // that reference initialise the factory-assisted normalizer itself.
            if (factoryAssisted && bootstrapping) normalizer.bootstrapMapping(sample)?.let { mapping ->
                correction.accept(timely, mapping.mmol(sample.rawProxyMgDl), sample.rawProxyMgDl, mapping)
            }
            return null
        }
        val core = estimator.process(
            AdaptiveV2Sample(
                calibratedMmol = normalized.mmol,
                temperatureC = sample.temperatureC,
                index = index,
                timestampMs = sample.timestampMs,
            ),
            if (factoryAssisted) emptyList()
            else timely.map { AdaptiveV2Reference(it.glucoseMgDl / MGDL_PER_MMOL, it.timestampMs) },
            stockComparisonMmol = sample.factoryMgDl / MGDL_PER_MMOL,
        ) ?: return null
        lastTimestampMs = sample.timestampMs
        lastIndex = index
        val source = estimator.latestGlucoseMixture ?: return null
        val mapping = normalizer.mapping ?: return null
        if (factoryAssisted) {
            // Compare with the unshifted mixture the user would actually see,
            // not with a factory target the mapping may take hours to reach.
            correction.accept(timely, source.median().toDouble(), sample.rawProxyMgDl, mapping)
        }
        // Absolute scale uncertainty belongs in every component. Ignoring it
        // would report a precise answer even when only the scale is unknown.
        val scaleVariance = normalized.mmol * normalized.mmol * normalized.logScaleVariance
        val shift = correction.offsetMmol(sample.timestampMs, mapping)
        val shiftVariance = correction.varianceMmol2(sample.timestampMs)
        mixture = GaussianMixture1D(
            source.weights.copyOf(),
            FloatArray(source.size) { (source.means[it] + shift).coerceIn(0.6f, 35f) },
            FloatArray(source.size) { source.variances[it] + scaleVariance + shiftVariance },
        )
        val posterior = mixture!!
        val lower = posterior.quantile(0.05).coerceIn(0.6f, 35f)
        val upper = posterior.quantile(0.95).coerceIn(0.6f, 35f)
        val center = posterior.median().coerceIn(lower, upper)
        return AiDexAdaptiveResult(
            sample.timestampMs,
            core.copy(
                glucoseMmol = center, lower90Mmol = lower, upper90Mmol = upper,
                confidence = minOf(core.confidence, exp(-(upper - lower) / 2f)),
            ),
            normalized.mmol, normalizer.scale.toFloat(), normalization,
        )
    }

    fun probabilityBelow(thresholdMmol: Float): Float =
        mixture?.cdf(thresholdMmol.toDouble())?.toFloat() ?: Float.NaN

    /** Full continuation state, versioned separately from Sibionics and checksummed. */
    fun snapshot(): ByteArray {
        val payload = ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use {
                it.writeInt(MAGIC)
                it.writeInt(VERSION)
                it.writeInt(normalization.ordinal)
                it.writeLong(sessionStartMs)
                it.writeLong(lastTimestampMs)
                it.writeInt(lastIndex)
                normalizer.writeTo(it)
                correction.writeTo(it)
                estimator.writeTo(it)
                val current = mixture
                it.writeBoolean(current != null)
                if (current != null) {
                    current.weights.forEach(it::writeFloat)
                    current.means.forEach(it::writeFloat)
                    current.variances.forEach(it::writeFloat)
                }
            }
            bytes.toByteArray()
        }
        return ByteArrayOutputStream().use { bytes ->
            bytes.write(payload)
            DataOutputStream(bytes).writeLong(CRC32().apply { update(payload) }.value)
            bytes.toByteArray()
        }
    }

    /** Failure resets cleanly; partial or foreign restores cannot leave half a filter. */
    fun restore(snapshot: ByteArray): Boolean {
        val candidate = AiDexAdaptiveV2(normalization)
        val valid = runCatching {
            require(snapshot.size in 32..MAX_SNAPSHOT_BYTES)
            val payload = snapshot.copyOf(snapshot.size - 8)
            val expected = DataInputStream(ByteArrayInputStream(snapshot, snapshot.size - 8, 8)).readLong()
            require(CRC32().apply { update(payload) }.value == expected)
            DataInputStream(ByteArrayInputStream(payload)).use { input ->
                require(input.readInt() == MAGIC && input.readInt() == VERSION)
                require(input.readInt() == normalization.ordinal)
                candidate.sessionStartMs = input.readLong()
                candidate.lastTimestampMs = input.readLong()
                candidate.lastIndex = input.readInt()
                candidate.normalizer.readFrom(input)
                candidate.correction.readFrom(input)
                require(candidate.estimator.readFrom(input))
                if (input.readBoolean()) {
                    candidate.mixture = GaussianMixture1D(
                        FloatArray(4) { input.readFloat() }, FloatArray(4) { input.readFloat() },
                        FloatArray(4) { input.readFloat() },
                    ).also { m ->
                        require(m.weights.all { it.isFinite() && it in 0f..1f } && abs(m.weights.sum() - 1f) < 0.001f)
                        require(m.means.all { it.isFinite() && it in 0.6f..35f })
                        require(m.variances.all { it.isFinite() && it >= 0f })
                    }
                }
                require(input.available() == 0)
                require(candidate.sessionStartMs >= 0L && candidate.lastIndex >= -1)
                require(candidate.lastTimestampMs == 0L || candidate.lastTimestampMs >= candidate.sessionStartMs)
                require(candidate.lastIndex == -1 ||
                    (candidate.lastTimestampMs - candidate.sessionStartMs) / 60_000L == candidate.lastIndex.toLong())
            }
        }.isSuccess
        reset()
        if (valid) {
            estimator = candidate.estimator
            normalizer = candidate.normalizer
            correction = candidate.correction
            sessionStartMs = candidate.sessionStartMs
            lastTimestampMs = candidate.lastTimestampMs
            lastIndex = candidate.lastIndex
            mixture = candidate.mixture
        }
        return valid
    }

    companion object {
        const val MGDL_PER_MMOL = 18.0182f
        private const val MAGIC = 0x4149_5632
        private const val VERSION = 4
        private const val MAX_SNAPSHOT_BYTES = 64 * 1024
    }
}

/**
 * Factory-assisted reference correction, applied at the output.
 *
 * Each reference measures error against the pre-correction filtered output.
 * Store an anchor at its raw level: target = reference - output + mapping(raw).
 * Later error = target - currentMapping(anchorRaw), so mapping catch-up removes
 * its share of the original error. Using the anchor's raw, not today's raw,
 * keeps subsequent glucose changes out of that drift calculation. The filter's
 * residual at the reference is preserved in the anchor, not mistaken for an
 * affine mapping change. Errors keep their existing age decay, prior and cap.
 */
internal class AiDexReferenceCorrection {
    private data class Anchor(val timeMs: Long, val rawProxyMgDl: Float, val targetMmol: Double)
    private val anchors = ArrayDeque<Anchor>()
    private var lastReferenceMs = 0L

    fun accept(references: List<AiDexReference>, outputMmol: Double, rawProxyMgDl: Float,
        mapping: AiDexSignalNormalizer.Mapping) {
        for (reference in references) {
            if (reference.timestampMs <= lastReferenceMs) continue
            val target = reference.glucoseMgDl / AiDexAdaptiveV2.MGDL_PER_MMOL - outputMmol + mapping.mmol(rawProxyMgDl)
            anchors.addLast(Anchor(reference.timestampMs, rawProxyMgDl, target))
            lastReferenceMs = reference.timestampMs
        }
        while (anchors.size > MAX_REFERENCES) anchors.removeFirst()
    }

    private fun weights(timeMs: Long, mapping: AiDexSignalNormalizer.Mapping? = null): Pair<Double, Double> {
        var total = 0.0
        var weighted = 0.0
        for ((time, raw, target) in anchors) {
            if (time > timeMs + 60_000L) continue
            val weight = exp(-(timeMs - time).coerceAtLeast(0L) / TIME_CONSTANT_MS)
            total += weight
            if (mapping != null) weighted += weight * (target - mapping.mmol(raw))
        }
        return total to weighted
    }

    fun offsetMmol(timeMs: Long, mapping: AiDexSignalNormalizer.Mapping): Float {
        val (total, weighted) = weights(timeMs, mapping)
        if (total < 1e-6) return 0f
        return (weighted / (PRIOR_WEIGHT + total)).coerceIn(-MAX_OFFSET_MMOL, MAX_OFFSET_MMOL).toFloat()
    }

    /** One reference's own noise, shared by the references still carrying weight. */
    fun varianceMmol2(timeMs: Long): Float {
        val (total, _) = weights(timeMs)
        if (total < 1e-6) return 0f
        return (REFERENCE_VARIANCE * min(1.0, total) / (PRIOR_WEIGHT + total)).toFloat()
    }

    fun writeTo(out: DataOutputStream) {
        out.writeLong(lastReferenceMs)
        out.writeInt(anchors.size)
        anchors.forEach { out.writeLong(it.timeMs); out.writeFloat(it.rawProxyMgDl); out.writeDouble(it.targetMmol) }
    }

    fun readFrom(input: DataInputStream) {
        lastReferenceMs = input.readLong()
        val count = input.readInt()
        require(lastReferenceMs >= 0L && count in 0..MAX_REFERENCES)
        anchors.clear()
        repeat(count) {
            val time = input.readLong()
            val raw = input.readFloat()
            val target = input.readDouble()
            require(time in 1..lastReferenceMs && raw.isFinite() && raw > 0f && raw <= 6_553.5f &&
                target.isFinite() && abs(target) < 70.0)
            require(anchors.isEmpty() || time > anchors.last().timeMs)
            anchors.addLast(Anchor(time, raw, target))
        }
    }

    companion object {
        /** The estimator's own bias time constant: about a day and a half. */
        private const val TIME_CONSTANT_MS = 1.5 * 24 * 60 * 60_000.0
        /** A fresh single reference corrects 80% of its error. */
        private const val PRIOR_WEIGHT = 0.25
        private const val MAX_OFFSET_MMOL = 2.5
        private const val REFERENCE_VARIANCE = 0.16
        private const val MAX_REFERENCES = 32
    }
}

/**
 * Robust slow raw-to-glucose curve; factory excursions never become glucose.
 *
 * glucose = scale * raw + offset. The scale tracks the factory/raw ratio over
 * an hour of stable pairs, as before, but starts from the median of the first
 * pairs rather than from one: a first pair is often a warm-up artifact. The
 * offset is the slow part an aging sensor develops, which a pure ratio turns
 * into exaggerated lows. It is fitted only over at least a day of pairs, with
 * a prior toward zero, so warm-up and short windows cannot invent one.
 */
internal class AiDexSignalNormalizer(private val mode: AiDexNormalization) {
    data class Signal(val mmol: Float, val logScaleVariance: Float)
    data class Mapping(val scale: Double, val offsetMgDl: Double) {
        fun mmol(rawProxyMgDl: Float): Double = (scale * rawProxyMgDl + offsetMgDl) / AiDexAdaptiveV2.MGDL_PER_MMOL
    }
    private var logScale = Double.NaN
    private var offset = 0.0
    private var offsetTarget = 0.0
    private var lastTime = 0L
    private var lastRaw = Float.NaN
    private var lastFactory = Float.NaN
    private var stableCount = 0L
    /** Squared log distance of the mapping from its target, smoothed: how unsettled the scale is. */
    private var calibrationSpread = Double.NaN
    /** Signed log gap between the factory and this mapping, smoothed over half an hour. */
    private var factoryDisagreement = 0.0
    private val pairs = ArrayDeque<Triple<Long, Float, Float>>()
    private val longPairs = ArrayDeque<Triple<Long, Float, Float>>()

    /** mg/dL of glucose per raw-proxy mg/dL at the current mapping. */
    val scale: Double get() = exp(logScale)
    val hasMapping: Boolean get() = logScale.isFinite()
    val mapping: Mapping? get() = if (hasMapping) Mapping(scale, offset) else null

    /** Only used when a reference arrives before any filtered output exists. */
    fun bootstrapMapping(sample: AiDexAdaptiveSample): Mapping? = sample.factoryMgDl
        .takeIf(::validFactory)?.let { Mapping(it.toDouble() / sample.rawProxyMgDl, 0.0) }

    fun process(sample: AiDexAdaptiveSample, firstReference: AiDexReference?): Signal? {
        if (lastTime > 0L && sample.timestampMs <= lastTime) return null
        val raw = sample.rawProxyMgDl
        if (mode == AiDexNormalization.REFERENCE_ONLY) {
            val candidate = if (logScale.isFinite()) logScale
                else firstReference?.glucoseMgDl?.let { ln(it.toDouble() / raw) } ?: return null
            val mmol = (raw * exp(candidate) / AiDexAdaptiveV2.MGDL_PER_MMOL).toFloat()
            if (!mmol.isFinite() || mmol <= 0f || mmol > 35f) return null
            logScale = candidate
            lastTime = sample.timestampMs
            lastRaw = raw
            return Signal(mmol, (DEFAULT_SCATTER * DEFAULT_SCATTER).toFloat())
        }
        // An unusable output must not move the continuation.
        if (logScale.isFinite() && !usable(exp(logScale) * raw + offset)) return null
        val factory = sample.factoryMgDl
        val validFactory = validFactory(factory)
        val elapsed = if (lastTime > 0L) (sample.timestampMs - lastTime) / 60_000.0 else 1.0
        while (pairs.isNotEmpty() && sample.timestampMs - pairs.first().first > WINDOW_MS) pairs.removeFirst()
        while (longPairs.isNotEmpty() && sample.timestampMs - longPairs.first().first > OFFSET_WINDOW_MS) longPairs.removeFirst()
        val scaleGuess = if (logScale.isFinite()) exp(logScale) else if (validFactory) factory.toDouble() / raw else Double.NaN
        // A factory derivative/overshoot must not train the curve.
        val stable = validFactory && lastRaw.isFinite() && lastFactory.isFinite() &&
            elapsed in 0.5..2.0 &&
            abs(factory - lastFactory) / elapsed < 1.8 &&
            abs(raw - lastRaw) * scaleGuess / elapsed < 1.8
        val step = elapsed.coerceAtMost(2.0)
        if (stable) {
            pairs.addLast(Triple(sample.timestampMs, raw, factory))
            if (stableCount++ % OFFSET_SUBSAMPLE == 0L) {
                longPairs.addLast(Triple(sample.timestampMs, raw, factory))
                while (longPairs.size > MAX_LONG_PAIRS) longPairs.removeFirst()
                fitOffset()
            }
        }
        offset += (offsetTarget - offset) * (1.0 - exp(-ln(2.0) * step / OFFSET_HALF_LIFE_MINUTES))
        val target = targetLogScale()
        if (target != null) {
            if (!logScale.isFinite()) {
                logScale = target
                calibrationSpread = bootstrapSpread()
            } else {
                val fraction = 1.0 - exp(-ln(2.0) * step / HALF_LIFE_MINUTES)
                logScale += ((target - logScale) * fraction).coerceIn(-MAX_LOG_STEP * elapsed, MAX_LOG_STEP * elapsed)
                // Distance of the whole mapping (scale and offset) from its target at this raw level.
                val distance = ln((exp(target) * raw + offsetTarget).coerceAtLeast(1.0) /
                    (exp(logScale) * raw + offset).coerceAtLeast(1.0))
                calibrationSpread += (distance * distance - calibrationSpread) * fraction
            }
        }
        if (logScale.isFinite() && validFactory) {
            val mapped = (exp(logScale) * raw + offset).coerceAtLeast(1.0)
            val gapFraction = 1.0 - exp(-ln(2.0) * step / DISAGREEMENT_HALF_LIFE_MINUTES)
            factoryDisagreement += (ln(factory / mapped) - factoryDisagreement) * gapFraction
        }
        lastTime = sample.timestampMs
        lastRaw = raw
        lastFactory = if (validFactory) factory else Float.NaN
        if (!logScale.isFinite()) return null
        val glucose = exp(logScale) * raw + offset
        if (!usable(glucose)) return null
        // The mapping is a slow average of a target that wanders with the factory
        // hour to hour; its uncertainty is that wander over the hours averaged,
        // not the wander itself, and not one minute's factory/raw disagreement.
        val averagedHours = 2.0 * HALF_LIFE_MINUTES / ln(2.0) / TARGET_CORRELATION_MINUTES
        // Where the factory persistently reads elsewhere, the level is genuinely
        // in doubt: the truth plausibly lies between the two, so half the gap.
        // Signed and smoothed, the factory's minute-scale trend lean mostly cancels.
        val disagreement = factoryDisagreement / 2.0
        return Signal((glucose / AiDexAdaptiveV2.MGDL_PER_MMOL).toFloat(),
            (max(MIN_CALIBRATION_SD * MIN_CALIBRATION_SD, calibrationSpread / averagedHours) +
                disagreement * disagreement).toFloat())
    }

    private fun ratios(): DoubleArray? {
        if (pairs.size < MIN_PAIRS) return null
        val values = pairs.mapNotNull { (_, r, f) -> (f - offsetTarget).takeIf { it > 0.0 }?.let { ln(it / r) } }
        return if (values.size < MIN_PAIRS) null else values.sorted().toDoubleArray()
    }

    private fun targetLogScale(): Double? = ratios()?.let { it[it.size / 2] }

    /**
     * Uncertainty of a median of the first pairs, not their scatter: one
     * minute's factory/raw disagreement (the factory's own trend lean) says
     * little about where the slow calibration is.
     */
    private fun bootstrapSpread(): Double {
        val ordered = ratios() ?: return DEFAULT_SCATTER * DEFAULT_SCATTER
        val median = ordered[ordered.size / 2]
        val residuals = DoubleArray(ordered.size) { abs(ordered[it] - median) }.apply { sort() }
        val sd = 1.4826 * residuals[residuals.size / 2]
        return 1.2533 * 1.2533 * sd * sd / ordered.size
    }

    /** Huber-weighted affine fit over the long window, with a zero-mean prior on the offset. */
    private fun fitOffset() {
        val n = longPairs.size
        if (n < 2 || longPairs.last().first - longPairs.first().first < MIN_OFFSET_SPAN_MS) return
        val weights = DoubleArray(n) { 1.0 }
        val residuals = DoubleArray(n)
        var noise = MIN_NOISE_MGDL * MIN_NOISE_MGDL
        var c = 0.0
        repeat(FIT_ITERATIONS) {
            var sw = 0.0; var sr = 0.0; var sf = 0.0; var srr = 0.0; var srf = 0.0
            longPairs.forEachIndexed { i, (_, r, f) ->
                val w = weights[i]
                sw += w; sr += w * r; sf += w * f; srr += w * r * r; srf += w * r * f
            }
            val prior = noise / (OFFSET_PRIOR_SD_MGDL * OFFSET_PRIOR_SD_MGDL)
            val det = srr * (sw + prior) - sr * sr
            if (det <= 1e-9) return
            var a = (srf * (sw + prior) - sr * sf) / det
            c = ((srr * sf - sr * srf) / det).coerceIn(-MAX_OFFSET_MGDL, MAX_OFFSET_MGDL)
            if (!a.isFinite() || !c.isFinite()) return
            a = (srf - c * sr) / srr
            if (a <= 0.0) return
            longPairs.forEachIndexed { i, (_, r, f) -> residuals[i] = f - (a * r + c) }
            val ordered = DoubleArray(n) { abs(residuals[it]) }.apply { sort() }
            val spread = max(MIN_NOISE_MGDL, 1.4826 * ordered[n / 2])
            noise = spread * spread
            val threshold = HUBER_K * spread
            for (i in 0 until n) weights[i] = if (abs(residuals[i]) <= threshold) 1.0 else threshold / abs(residuals[i])
        }
        offsetTarget = c
    }

    private fun usable(glucoseMgDl: Double): Boolean {
        val mmol = glucoseMgDl / AiDexAdaptiveV2.MGDL_PER_MMOL
        return mmol.isFinite() && mmol > 0.0 && mmol <= 35.0
    }

    private fun validFactory(factory: Float) = factory.isFinite() &&
        factory in (0.6f * AiDexAdaptiveV2.MGDL_PER_MMOL)..(35f * AiDexAdaptiveV2.MGDL_PER_MMOL)

    fun writeTo(out: DataOutputStream) {
        out.writeDouble(logScale)
        out.writeDouble(offset)
        out.writeDouble(offsetTarget)
        out.writeLong(lastTime)
        out.writeFloat(lastRaw)
        out.writeFloat(lastFactory)
        out.writeLong(stableCount)
        out.writeDouble(calibrationSpread)
        out.writeDouble(factoryDisagreement)
        for (deque in listOf(pairs, longPairs)) {
            out.writeInt(deque.size)
            deque.forEach { out.writeLong(it.first); out.writeFloat(it.second); out.writeFloat(it.third) }
        }
    }

    fun readFrom(input: DataInputStream) {
        logScale = input.readDouble()
        offset = input.readDouble()
        offsetTarget = input.readDouble()
        lastTime = input.readLong()
        lastRaw = input.readFloat()
        lastFactory = input.readFloat()
        stableCount = input.readLong()
        calibrationSpread = input.readDouble()
        factoryDisagreement = input.readDouble()
        require(logScale.isNaN() || (logScale.isFinite() && abs(logScale) < 20))
        require(offset.isFinite() && abs(offset) <= MAX_OFFSET_MGDL && offsetTarget.isFinite() && abs(offsetTarget) <= MAX_OFFSET_MGDL)
        require(lastTime >= 0L && (lastRaw.isNaN() || (lastRaw.isFinite() && lastRaw > 0f)))
        require(lastFactory.isNaN() || lastFactory.isFinite())
        require(stableCount >= 0L)
        require(factoryDisagreement.isFinite() && abs(factoryDisagreement) < 20.0)
        require(calibrationSpread.isNaN() || (calibrationSpread.isFinite() && calibrationSpread >= 0.0 && calibrationSpread < 100.0))
        for ((deque, limit) in listOf(pairs to MAX_PAIRS, longPairs to MAX_LONG_PAIRS)) {
            val count = input.readInt()
            require(count in 0..limit)
            deque.clear()
            repeat(count) {
                val time = input.readLong()
                val raw = input.readFloat()
                val factory = input.readFloat()
                require(time in 1..lastTime && raw.isFinite() && raw > 0f && factory.isFinite() && factory > 0f)
                require(deque.isEmpty() || time > deque.last().first)
                deque.addLast(Triple(time, raw, factory))
            }
        }
    }

    companion object {
        private const val WINDOW_MS = 60 * 60_000L
        private const val MAX_PAIRS = 120
        private const val MIN_PAIRS = 15
        private const val HALF_LIFE_MINUTES = 360.0
        private const val MAX_LOG_STEP = 0.002
        private const val OFFSET_WINDOW_MS = 48 * 60 * 60_000L
        private const val MIN_OFFSET_SPAN_MS = 24 * 60 * 60_000L
        private const val OFFSET_SUBSAMPLE = 5L
        private const val MAX_LONG_PAIRS = 600
        private const val OFFSET_HALF_LIFE_MINUTES = 12 * 60.0
        private const val FIT_ITERATIONS = 3
        private const val HUBER_K = 1.345
        private const val MIN_NOISE_MGDL = 3.0
        /** One mmol/L: an offset needs raw range across the window to be believed. */
        private const val OFFSET_PRIOR_SD_MGDL = 18.0
        private const val MAX_OFFSET_MGDL = 72.0
        private const val DEFAULT_SCATTER = 0.15
        /** A settled factory-assisted calibration is still only known to a few percent. */
        private const val MIN_CALIBRATION_SD = 0.04
        /** The window median moves with the factory's hour-scale lean; ~one window apart is independent. */
        private const val TARGET_CORRELATION_MINUTES = 60.0
        private const val DISAGREEMENT_HALF_LIFE_MINUTES = 30.0
    }
}
