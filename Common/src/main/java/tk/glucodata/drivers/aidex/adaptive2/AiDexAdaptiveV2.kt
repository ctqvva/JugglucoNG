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

/**
 * Explicit choices for the missing AiDex factory conversion.
 * FACTORY_ASSISTED learns a slow scale from raw/factory pairs; it is a hybrid,
 * not a reconstruction of the sensor's proprietary factory algorithm.
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
 * A slow robust normalizer replaces the factory compensation available to
 * Sibionics. It deliberately cannot identify scale, offset, sensor aging and
 * true glucose from raw alone. References constrain the residual sensor states
 * in the IMM; factory-assisted mode retains dependence on the factory scale.
 *
 * This is an experimental model. Mixture intervals are model uncertainty, not
 * validated clinical coverage. Selecting/publishing it in an app requires a
 * separate integration with one authoritative history/live/calibration lane.
 */
class AiDexAdaptiveV2(val normalization: AiDexNormalization = AiDexNormalization.FACTORY_ASSISTED) {
    private var estimator = AdaptiveV2Estimator().apply { configure(1f) }
    private var normalizer = AiDexSignalNormalizer(normalization)
    private var sessionStartMs = 0L
    private var lastTimestampMs = 0L
    private var lastIndex = -1
    private var mixture: GaussianMixture1D? = null

    fun reset() {
        estimator = AdaptiveV2Estimator().apply { configure(1f) }
        normalizer = AiDexSignalNormalizer(normalization)
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
        val normalized = normalizer.process(sample, timely.firstOrNull()) ?: return null
        val core = estimator.process(
            AdaptiveV2Sample(
                calibratedMmol = normalized.mmol,
                temperatureC = sample.temperatureC,
                index = index,
                timestampMs = sample.timestampMs,
            ),
            timely.map { AdaptiveV2Reference(it.glucoseMgDl / MGDL_PER_MMOL, it.timestampMs) },
            stockComparisonMmol = sample.factoryMgDl / MGDL_PER_MMOL,
        ) ?: return null
        lastTimestampMs = sample.timestampMs
        lastIndex = index
        val source = estimator.latestGlucoseMixture ?: return null
        // Absolute scale uncertainty belongs in every component. Ignoring it
        // would report a precise answer even when only the scale is unknown.
        val scaleVariance = normalized.mmol * normalized.mmol * normalized.logScaleVariance
        mixture = GaussianMixture1D(
            source.weights.copyOf(), source.means.copyOf(),
            FloatArray(source.size) { source.variances[it] + scaleVariance },
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
        private const val VERSION = 1
        private const val MAX_SNAPSHOT_BYTES = 64 * 1024
    }
}

/** Robust slow scale; current factory excursions never become current glucose. */
internal class AiDexSignalNormalizer(private val mode: AiDexNormalization) {
    data class Signal(val mmol: Float, val logScaleVariance: Float)
    private var logScale = Double.NaN
    private var lastTime = 0L
    private var lastRaw = Float.NaN
    private var lastFactory = Float.NaN
    private val ratios = ArrayDeque<Pair<Long, Double>>()
    val scale: Double get() = exp(logScale)

    fun process(sample: AiDexAdaptiveSample, firstReference: AiDexReference?): Signal? {
        val factory = sample.factoryMgDl
        val validFactory = factory.isFinite() && factory in (0.6f * AiDexAdaptiveV2.MGDL_PER_MMOL)..(35f * AiDexAdaptiveV2.MGDL_PER_MMOL)
        if (!logScale.isFinite()) {
            val anchor = when (mode) {
                AiDexNormalization.FACTORY_ASSISTED -> factory.takeIf { validFactory }
                AiDexNormalization.REFERENCE_ONLY -> firstReference?.glucoseMgDl
            } ?: return null
            logScale = ln(anchor.toDouble() / sample.rawProxyMgDl)
        }
        val preliminaryMmol = sample.rawProxyMgDl * scale / AiDexAdaptiveV2.MGDL_PER_MMOL
        if (!preliminaryMmol.isFinite() || preliminaryMmol <= 0.0 || preliminaryMmol > 35.0) return null
        val elapsed = if (lastTime > 0L) (sample.timestampMs - lastTime) / 60_000.0 else 1.0
        while (ratios.isNotEmpty() && sample.timestampMs - ratios.first().first > WINDOW_MS) ratios.removeFirst()
        if (mode == AiDexNormalization.FACTORY_ASSISTED) {
            // A factory derivative/overshoot must not train the absolute scale.
            val stable = validFactory && lastRaw.isFinite() && lastFactory.isFinite() &&
                elapsed in 0.5..2.0 &&
                abs(factory - lastFactory) / elapsed < 1.8 &&
                abs(sample.rawProxyMgDl - lastRaw) * scale / elapsed < 1.8
            if (stable) ratios.addLast(sample.timestampMs to ln(factory.toDouble() / sample.rawProxyMgDl))
            if (ratios.size >= MIN_PAIRS) {
                val ordered = ratios.map { it.second }.sorted()
                val median = ordered[ordered.size / 2]
                val fraction = 1.0 - exp(-ln(2.0) * elapsed.coerceAtMost(2.0) / HALF_LIFE_MINUTES)
                logScale += ((median - logScale) * fraction).coerceIn(-0.002 * elapsed, 0.002 * elapsed)
            }
        }
        lastTime = sample.timestampMs
        lastRaw = sample.rawProxyMgDl
        lastFactory = if (validFactory) factory else Float.NaN
        val mmol = (sample.rawProxyMgDl * scale / AiDexAdaptiveV2.MGDL_PER_MMOL).toFloat()
        if (!mmol.isFinite() || mmol <= 0f || mmol > 35f) return null
        val scatter = if (ratios.size >= MIN_PAIRS) {
            val residuals = ratios.map { abs(it.second - logScale) }.sorted()
            max(0.08, 1.4826 * residuals[residuals.size / 2])
        } else 0.15
        return Signal(mmol, (scatter * scatter).toFloat())
    }

    fun writeTo(out: DataOutputStream) {
        out.writeDouble(logScale)
        out.writeLong(lastTime)
        out.writeFloat(lastRaw)
        out.writeFloat(lastFactory)
        out.writeInt(ratios.size)
        ratios.forEach { out.writeLong(it.first); out.writeDouble(it.second) }
    }

    fun readFrom(input: DataInputStream) {
        logScale = input.readDouble()
        lastTime = input.readLong()
        lastRaw = input.readFloat()
        lastFactory = input.readFloat()
        require(logScale.isNaN() || (logScale.isFinite() && abs(logScale) < 20))
        require(lastTime >= 0L && (lastRaw.isNaN() || (lastRaw.isFinite() && lastRaw > 0f)))
        require(lastFactory.isNaN() || lastFactory.isFinite())
        val count = input.readInt()
        require(count in 0..120)
        ratios.clear()
        repeat(count) {
            val time = input.readLong()
            val ratio = input.readDouble()
            require(time in 1..lastTime && ratio.isFinite() && abs(ratio) < 20)
            require(ratios.isEmpty() || time > ratios.last().first)
            ratios.addLast(time to ratio)
        }
    }

    companion object {
        private const val WINDOW_MS = 60 * 60_000L
        private const val MIN_PAIRS = 15
        private const val HALF_LIFE_MINUTES = 360.0
    }
}
