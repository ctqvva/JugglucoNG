package tk.glucodata.drivers.sibionics

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * Experimental one-minute sensor model layered around, rather than after, the
 * exact Sibionics core. The exact core remains the per-sensor chemical model;
 * this context treats its result as a measurement and combines it with raw
 * current direction, temperature/impedance quality and robust calibration
 * anchors in a persistent, bounded one-minute tracker. It deliberately does
 * not forecast beyond the exact model: the adaptive layer only attenuates
 * small quantisation noise and yields immediately on clinically important
 * motion.
 */
internal class SibionicsBalancedAlgorithmContext {
    private var decodedSensitivity = DEFAULT_SENSITIVITY
    private var initialized = false
    private var level = Float.NaN
    private var trend = 0f
    private var lastMeasurement = Float.NaN
    private var lastRaw = Float.NaN
    private var lastTemperature = Float.NaN
    private var impedanceBaseline = Float.NaN
    private var lastTimestampMs = 0L
    private var lastIndex = -1
    private var samplesSinceReset = 0

    fun configure(sensitivity: Float) {
        decodedSensitivity = sensitivity.takeIf { it.isFinite() && it in 0.5f..3.5f }
            ?: DEFAULT_SENSITIVITY
    }

    fun reset() {
        initialized = false
        level = Float.NaN
        trend = 0f
        lastMeasurement = Float.NaN
        lastRaw = Float.NaN
        lastTemperature = Float.NaN
        impedanceBaseline = Float.NaN
        lastTimestampMs = 0L
        lastIndex = -1
        samplesSinceReset = 0
    }

    fun process(
        stockMmol: Float,
        rawMmol: Float,
        temperatureC: Float,
        impedance: Float,
        index: Int,
        eventTimeMs: Long,
        anchors: List<SibionicsCalibrationAnchor>,
    ): Float {
        if (!stockMmol.isFinite() || stockMmol <= 0f) return Float.NaN

        val measurement = applyIntegratedCalibration(stockMmol, eventTimeMs, anchors)
        if (!initialized || index <= 1 || index <= lastIndex) {
            reset()
            initialized = true
            level = measurement
            samplesSinceReset = 1
            updateTelemetry(rawMmol, temperatureC, impedance, index, eventTimeMs, measurement)
            return roundTenth(measurement)
        }

        val elapsedMinutes = when {
            eventTimeMs > lastTimestampMs && lastTimestampMs > 0L ->
                ((eventTimeMs - lastTimestampMs) / 60_000f).coerceIn(0.5f, 3f)
            index > lastIndex -> (index - lastIndex).toFloat().coerceIn(1f, 3f)
            else -> 1f
        }
        val signal = signalQuality(temperatureC, impedance)
        val latestStep = if (lastMeasurement.isFinite()) measurement - lastMeasurement else 0f
        val predicted = level + trend * elapsedMinutes
        val innovation = measurement - predicted
        val rapidChange = (abs(innovation) / RAPID_INNOVATION_MMOL).coerceIn(0f, 1f)
        val alpha = BASE_LEVEL_GAIN + (MAX_LEVEL_GAIN - BASE_LEVEL_GAIN) * rapidChange
        level = predicted + alpha * innovation

        val rawTrend = if (rawMmol.isFinite() && lastRaw.isFinite()) {
            ((rawMmol - lastRaw) / decodedSensitivity / elapsedMinutes)
                .coerceIn(-MAX_TREND_RATE, MAX_TREND_RATE)
        } else {
            Float.NaN
        }
        val rawCoherent = rawTrend.isFinite() && (
            abs(latestStep) < MIN_DIRECTION_STEP || abs(rawTrend) < MIN_RAW_TREND_RATE ||
                latestStep * rawTrend > 0f
            )
        val rawWeight = if (rawCoherent) RAW_TREND_WEIGHT * signal.overall else 0f
        val innovationTrend = TREND_GAIN * innovation.coerceIn(-MAX_TREND_INNOVATION, MAX_TREND_INNOVATION) /
            elapsedMinutes
        val rawContribution = if (rawTrend.isFinite()) rawTrend * rawWeight else 0f
        trend = ((trend + innovationTrend) * (1f - rawWeight) +
            rawContribution)
            .coerceIn(-MAX_TREND_RATE, MAX_TREND_RATE)
        samplesSinceReset++

        val turning = abs(latestStep) >= TURN_STEP_MMOL && latestStep * trend < 0f
        val exactPassThrough = samplesSinceReset <= STARTUP_PASSTHROUGH_SAMPLES ||
            abs(latestStep) >= LARGE_STEP_MMOL || measurement <= LOW_EXACT_MMOL ||
            turning || signal.telemetryAnomaly
        var output = if (exactPassThrough) {
            level = measurement
            if (abs(latestStep) >= LARGE_STEP_MMOL || signal.telemetryAnomaly) trend = 0f
            measurement
        } else {
            level.coerceIn(
                measurement - MAX_TRACKING_DISTANCE_MMOL,
                measurement + MAX_TRACKING_DISTANCE_MMOL,
            )
        }
        if (measurement < LOW_FALL_GUARD_MMOL && latestStep < 0f) {
            output = min(output, measurement)
        }

        updateTelemetry(rawMmol, temperatureC, impedance, index, eventTimeMs, measurement)
        return roundTenth(max(output, 0f))
    }

    fun snapshot(): ByteArray = ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { output ->
            output.writeInt(SNAPSHOT_MAGIC)
            output.writeInt(SNAPSHOT_VERSION)
            output.writeFloat(decodedSensitivity)
            output.writeBoolean(initialized)
            output.writeFloat(level)
            output.writeFloat(trend)
            output.writeFloat(lastMeasurement)
            output.writeFloat(lastRaw)
            output.writeFloat(lastTemperature)
            output.writeFloat(impedanceBaseline)
            output.writeLong(lastTimestampMs)
            output.writeInt(lastIndex)
            output.writeInt(samplesSinceReset)
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
                level = input.readFloat()
                trend = input.readFloat()
                lastMeasurement = input.readFloat()
                lastRaw = input.readFloat()
                lastTemperature = input.readFloat()
                impedanceBaseline = input.readFloat()
                lastTimestampMs = input.readLong()
                lastIndex = input.readInt()
                samplesSinceReset = input.readInt()
                input.available() == 0 && isStateValid()
            }
        }.getOrDefault(false)
        if (!restored) reset()
        return restored
    }

    fun continuationIndex(): Int? = lastIndex.takeIf { initialized && it >= 0 }

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

    private data class SignalQuality(
        val overall: Float,
        val telemetryAnomaly: Boolean,
    )

    private fun signalQuality(temperatureC: Float, impedance: Float): SignalQuality {
        val temperatureJump = lastTemperature.isFinite() && temperatureC.isFinite() &&
            abs(temperatureC - lastTemperature) > TEMPERATURE_JUMP_C
        val temperatureQuality = when {
            !temperatureC.isFinite() -> 0.45f
            temperatureC in 28f..40f -> 1f
            temperatureC in 10f..42f -> 0.65f
            else -> 0.25f
        } * if (temperatureJump) 0.55f else 1f

        val impedanceDelta = if (impedance.isFinite() && impedance > 0f && impedanceBaseline.isFinite()) {
            abs(impedance - impedanceBaseline) / max(abs(impedanceBaseline), 1f)
        } else {
            0f
        }
        val impedanceQuality = (1f / (1f + impedanceDelta * IMPEDANCE_QUALITY_SLOPE))
            .coerceIn(MIN_SIGNAL_QUALITY, 1f)
        val anomaly = temperatureJump || temperatureC.isFinite() && temperatureC !in 10f..42f ||
            impedanceDelta > IMPEDANCE_ANOMALY_FRACTION
        return SignalQuality(
            overall = (temperatureQuality * 0.68f + impedanceQuality * 0.32f)
                .coerceIn(MIN_SIGNAL_QUALITY, 1f),
            telemetryAnomaly = anomaly,
        )
    }

    private fun updateTelemetry(
        rawMmol: Float,
        temperatureC: Float,
        impedance: Float,
        index: Int,
        eventTimeMs: Long,
        measurement: Float,
    ) {
        if (impedance.isFinite() && impedance > 0f) {
            impedanceBaseline = if (impedanceBaseline.isFinite()) {
                val relativeDelta = abs(impedance - impedanceBaseline) / max(abs(impedanceBaseline), 1f)
                val learningRate = if (relativeDelta > IMPEDANCE_ANOMALY_FRACTION) {
                    ANOMALOUS_IMPEDANCE_LEARNING_RATE
                } else {
                    IMPEDANCE_LEARNING_RATE
                }
                impedanceBaseline * (1f - learningRate) + impedance * learningRate
            } else {
                impedance
            }
        }
        if (rawMmol.isFinite()) lastRaw = rawMmol
        if (temperatureC.isFinite()) lastTemperature = temperatureC
        lastMeasurement = measurement
        lastTimestampMs = eventTimeMs
        lastIndex = index
    }

    private fun isStateValid(): Boolean {
        if (!initialized) return true
        return level.isFinite() && level in 0f..50f && trend.isFinite() &&
            abs(trend) <= MAX_TREND_RATE && lastMeasurement.isFinite() &&
            lastMeasurement in 0f..50f && lastIndex >= 0 && samplesSinceReset > 0
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
        private const val SNAPSHOT_VERSION = 4
        private const val DEFAULT_SENSITIVITY = 1.27f
        private const val BASE_LEVEL_GAIN = 0.35f
        private const val MAX_LEVEL_GAIN = 0.92f
        private const val RAPID_INNOVATION_MMOL = 0.8f
        private const val TREND_GAIN = 0.02f
        private const val RAW_TREND_WEIGHT = 0.05f
        private const val MAX_TREND_RATE = 0.35f
        private const val MAX_TREND_INNOVATION = 1f
        private const val MIN_DIRECTION_STEP = 0.05f
        private const val MIN_RAW_TREND_RATE = 0.02f
        private const val STARTUP_PASSTHROUGH_SAMPLES = 5
        private const val MAX_TRACKING_DISTANCE_MMOL = 0.2f
        private const val LARGE_STEP_MMOL = 1f
        private const val TURN_STEP_MMOL = 0.15f
        private const val LOW_EXACT_MMOL = 3.9f
        private const val LOW_FALL_GUARD_MMOL = 4.5f
        private const val TEMPERATURE_JUMP_C = 1.2f
        private const val IMPEDANCE_QUALITY_SLOPE = 5f
        private const val IMPEDANCE_ANOMALY_FRACTION = 0.75f
        private const val MIN_SIGNAL_QUALITY = 0.20f
        private const val IMPEDANCE_LEARNING_RATE = 0.04f
        private const val ANOMALOUS_IMPEDANCE_LEARNING_RATE = 0.004f
        private const val SINGLE_ANCHOR_CONFIDENCE = 0.82f
        private const val MIN_CALIBRATION_GAIN = 0.78f
        private const val MAX_CALIBRATION_GAIN = 1.22f
        private const val MAX_CALIBRATION_OFFSET = 3f
        private const val CALIBRATION_HALF_LIFE_MS = 5.0 * 24.0 * 60.0 * 60.0 * 1000.0
    }
}
