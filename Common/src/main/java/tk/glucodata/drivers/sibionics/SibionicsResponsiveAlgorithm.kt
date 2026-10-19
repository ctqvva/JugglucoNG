package tk.glucodata.drivers.sibionics

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.ArrayDeque
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * Experimental one-minute sensor model layered around, rather than after, the
 * exact Sibionics core. The exact core remains the per-sensor chemical model;
 * this context treats its result as a measurement and combines it with raw
 * current direction, temperature/impedance quality and robust calibration
 * anchors in a persistent glucose/velocity state estimator.
 */
internal class SibionicsResponsiveAlgorithmContext {
    private var decodedSensitivity = DEFAULT_SENSITIVITY
    private var initialized = false
    private var glucose = Float.NaN
    private var velocity = 0f
    private var lastMeasurement = Float.NaN
    private var lastRaw = Float.NaN
    private var lastTemperature = Float.NaN
    private var impedanceBaseline = Float.NaN
    private var lastTimestampMs = 0L
    private var lastIndex = -1
    private val innovations = ArrayDeque<Float>(INNOVATION_WINDOW)

    fun configure(sensitivity: Float) {
        decodedSensitivity = sensitivity.takeIf { it.isFinite() && it in 0.5f..3.5f }
            ?: DEFAULT_SENSITIVITY
    }

    fun reset() {
        initialized = false
        glucose = Float.NaN
        velocity = 0f
        lastMeasurement = Float.NaN
        lastRaw = Float.NaN
        lastTemperature = Float.NaN
        impedanceBaseline = Float.NaN
        lastTimestampMs = 0L
        lastIndex = -1
        innovations.clear()
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
        if (!initialized || index <= 1 || index <= lastIndex || !glucose.isFinite()) {
            initialized = true
            glucose = measurement
            velocity = 0f
            updateTelemetry(rawMmol, temperatureC, impedance, index, eventTimeMs, measurement)
            return roundTenth(measurement)
        }

        val elapsedMinutes = when {
            eventTimeMs > lastTimestampMs && lastTimestampMs > 0L ->
                ((eventTimeMs - lastTimestampMs) / 60_000f).coerceIn(0.5f, 3f)
            index > lastIndex -> (index - lastIndex).toFloat().coerceIn(1f, 3f)
            else -> 1f
        }
        val quality = signalQuality(temperatureC, impedance)
        val predicted = glucose + velocity * elapsedMinutes
        var innovation = measurement - predicted

        if (innovations.size >= 5) {
            val median = median(innovations.toFloatArray())
            val deviations = innovations.map { abs(it - median) }.toFloatArray()
            val scale = max(MIN_INNOVATION_SCALE, median(deviations))
            val robustLimit = max(MIN_INNOVATION_LIMIT, scale * 3f)
            if (quality < 0.75f && abs(innovation - median) > robustLimit) {
                innovation = median + (innovation - median).coerceIn(-robustLimit, robustLimit)
            }
        }
        innovation = innovation.coerceIn(-MAX_INNOVATION, MAX_INNOVATION)

        val rapidChange = (abs(innovation) / 1.2f).coerceIn(0f, 1f)
        val rawVelocity = if (rawMmol.isFinite() && lastRaw.isFinite()) {
            ((rawMmol - lastRaw) / decodedSensitivity / elapsedMinutes)
                .coerceIn(-MAX_VELOCITY, MAX_VELOCITY)
        } else {
            0f
        }
        val directionAgreement = when {
            abs(rawVelocity) < 0.015f || abs(innovation) < 0.08f -> 0f
            rawVelocity * innovation > 0f -> 1f
            else -> -1f
        }
        val alpha = (0.34f + quality * 0.30f + rapidChange * 0.18f + directionAgreement * 0.06f)
            .coerceIn(0.18f, 0.84f)
        val beta = (0.055f + quality * 0.12f + rapidChange * 0.07f)
            .coerceIn(0.04f, 0.24f)

        glucose = predicted + alpha * innovation
        val innovationVelocity = beta * innovation / elapsedMinutes
        velocity = (velocity * 0.72f + innovationVelocity + rawVelocity * quality * 0.20f)
            .coerceIn(-MAX_VELOCITY, MAX_VELOCITY)

        val lead = (velocity * (0.25f + quality * 0.25f)).coerceIn(-MAX_TREND_LEAD, MAX_TREND_LEAD)
        // A trustworthy stock measurement keeps the adaptive estimate close to
        // the sensor chemistry model. Low-quality telemetry must widen this
        // guard, otherwise an anomalous measurement would override the robust
        // innovation clamp above and drag the output toward itself.
        val safetyDistance = max(MIN_OUTPUT_DISTANCE, measurement * MAX_OUTPUT_DISTANCE_FRACTION) *
            (1f + (1f - quality) * LOW_QUALITY_SAFETY_EXPANSION)
        val output = (glucose + lead).coerceIn(
            measurement - safetyDistance,
            measurement + safetyDistance,
        )

        innovations.addLast(innovation)
        while (innovations.size > INNOVATION_WINDOW) innovations.removeFirst()
        updateTelemetry(rawMmol, temperatureC, impedance, index, eventTimeMs, measurement)
        return roundTenth(max(output, 0f))
    }

    fun snapshot(): ByteArray = ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { output ->
            output.writeInt(SNAPSHOT_MAGIC)
            output.writeInt(SNAPSHOT_VERSION)
            output.writeFloat(decodedSensitivity)
            output.writeBoolean(initialized)
            output.writeFloat(glucose)
            output.writeFloat(velocity)
            output.writeFloat(lastMeasurement)
            output.writeFloat(lastRaw)
            output.writeFloat(lastTemperature)
            output.writeFloat(impedanceBaseline)
            output.writeLong(lastTimestampMs)
            output.writeInt(lastIndex)
            output.writeInt(innovations.size)
            innovations.forEach(output::writeFloat)
        }
        bytes.toByteArray()
    }

    fun restore(snapshot: ByteArray?): Boolean = runCatching {
        if (snapshot == null || snapshot.isEmpty()) return false
        DataInputStream(ByteArrayInputStream(snapshot)).use { input ->
            if (input.readInt() != SNAPSHOT_MAGIC || input.readInt() != SNAPSHOT_VERSION) return false
            val savedSensitivity = input.readFloat()
            if (!savedSensitivity.isFinite() || abs(savedSensitivity - decodedSensitivity) > 0.0001f) return false
            initialized = input.readBoolean()
            glucose = input.readFloat()
            velocity = input.readFloat()
            lastMeasurement = input.readFloat()
            lastRaw = input.readFloat()
            lastTemperature = input.readFloat()
            impedanceBaseline = input.readFloat()
            lastTimestampMs = input.readLong()
            lastIndex = input.readInt()
            val count = input.readInt()
            if (count !in 0..INNOVATION_WINDOW) return false
            innovations.clear()
            repeat(count) { innovations.addLast(input.readFloat()) }
            if (input.available() != 0 || !isStateValid()) return false
            true
        }
    }.getOrDefault(false).also { restored -> if (!restored) reset() }

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

    private fun signalQuality(temperatureC: Float, impedance: Float): Float {
        val temperatureQuality = when {
            !temperatureC.isFinite() -> 0.35f
            temperatureC in 28f..40f -> 1f
            temperatureC in 10f..42f -> 0.65f
            else -> 0.25f
        } * if (lastTemperature.isFinite() && abs(temperatureC - lastTemperature) > 1.2f) 0.65f else 1f

        val impedanceQuality = if (impedance.isFinite() && impedance > 0f && impedanceBaseline.isFinite()) {
            val relativeDelta = abs(impedance - impedanceBaseline) / max(abs(impedanceBaseline), 1f)
            (1f / (1f + relativeDelta * 5f)).coerceIn(0.2f, 1f)
        } else {
            1f
        }
        return (temperatureQuality * 0.7f + impedanceQuality * 0.3f).coerceIn(0.2f, 1f)
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
                impedanceBaseline * 0.96f + impedance * 0.04f
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
        return glucose.isFinite() && glucose in 0f..50f &&
            velocity.isFinite() && abs(velocity) <= MAX_VELOCITY &&
            lastIndex >= 0
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
        private const val SNAPSHOT_VERSION = 1
        private const val DEFAULT_SENSITIVITY = 1.27f
        private const val INNOVATION_WINDOW = 7
        private const val MIN_INNOVATION_SCALE = 0.12f
        private const val MIN_INNOVATION_LIMIT = 0.55f
        private const val MAX_INNOVATION = 2.5f
        private const val MAX_VELOCITY = 0.30f
        private const val MAX_TREND_LEAD = 0.35f
        private const val MIN_OUTPUT_DISTANCE = 0.65f
        private const val MAX_OUTPUT_DISTANCE_FRACTION = 0.16f
        private const val LOW_QUALITY_SAFETY_EXPANSION = 2f
        private const val SINGLE_ANCHOR_CONFIDENCE = 0.82f
        private const val MIN_CALIBRATION_GAIN = 0.78f
        private const val MAX_CALIBRATION_GAIN = 1.22f
        private const val MAX_CALIBRATION_OFFSET = 3f
        private const val CALIBRATION_HALF_LIFE_MS = 5.0 * 24.0 * 60.0 * 60.0 * 1000.0
    }
}
