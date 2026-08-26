package tk.glucodata.drivers.sibionics.adaptive2

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * Driver-facing wrapper around [AdaptiveV2Estimator].
 *
 * Owns snapshot versioning, restore validation and the diagnostics ring buffer,
 * keeping the estimator itself free of storage concerns.
 *
 * Persistence contract: every field required to continue the filter exactly —
 * all four mode state vectors and covariances, the mode probabilities, adapted
 * measurement noise, lag estimate, sensor drift and artifact states, and the
 * telemetry baselines — round-trips through [snapshot]/[restore]. Replaying the
 * same inputs after a restore reproduces identical estimates and intervals.
 */
internal class SibionicsAdaptiveV2Context {
    private val estimator = AdaptiveV2Estimator()
    private var factorySensitivity = Float.NaN

    /** Bounded developer trace; empty unless explicitly enabled. */
    private var diagnosticsCapacity = 0
    private val diagnostics = ArrayDeque<AdaptiveV2Diagnostics>()

    fun configure(sensitivity: Float) {
        factorySensitivity = sensitivity
        estimator.configure(sensitivity)
    }

    fun reset() {
        estimator.reset()
        diagnostics.clear()
    }

    /** Enables the per-sample diagnostics trace, keeping at most [capacity] rows. */
    fun enableDiagnostics(capacity: Int) {
        diagnosticsCapacity = capacity.coerceAtLeast(0)
        while (diagnostics.size > diagnosticsCapacity) diagnostics.removeFirst()
    }

    fun diagnostics(): List<AdaptiveV2Diagnostics> = diagnostics.toList()

    fun diagnosticsCsv(): String = buildString {
        appendLine(AdaptiveV2Diagnostics.CSV_HEADER)
        diagnostics.forEach { appendLine(it.toCsvRow()) }
    }

    fun latestEstimate(): ProbabilisticGlucoseEstimate? = estimator.latestEstimate

    /** Most recent per-sample diagnostics row, independent of the ring buffer. */
    fun latestDiagnostics(): AdaptiveV2Diagnostics? = estimator.latestDiagnostics

    fun probabilityBelow(thresholdMmol: Float): Float = estimator.probabilityBelow(thresholdMmol)

    fun continuationIndex(): Int? = estimator.continuationIndex()

    /**
     * Processes one sample.
     *
     * @param stockComparisonMmol comparison trace for diagnostics only. It is
     *   passed straight to the diagnostics row and never reaches the estimator.
     */
    fun process(
        observation: tk.glucodata.drivers.sibionics.SibionicsSensorObservation,
        temperatureC: Float,
        impedance: Float,
        eventTimeMs: Long,
        references: List<AdaptiveV2Reference> = emptyList(),
        stockComparisonMmol: Float = Float.NaN,
        adaptiveV1ComparisonMmol: Float = Float.NaN,
    ): ProbabilisticGlucoseEstimate? {
        val estimate = estimator.process(
            sample = AdaptiveV2Sample(
                calibratedMmol = observation.calibratedMmol,
                factorySensitivity = observation.factorySensitivity,
                activeSensitivity = observation.activeSensitivity,
                sensorStateCompensationMmol = observation.sensorStateCompensationMmol,
                temperatureC = temperatureC,
                impedance = impedance,
                qualityFlags = observation.qualityFlags,
                index = observation.sensorAgeMinutes,
                timestampMs = eventTimeMs,
            ),
            references = references,
            stockComparisonMmol = stockComparisonMmol,
            adaptiveV1ComparisonMmol = adaptiveV1ComparisonMmol,
        )
        if (diagnosticsCapacity > 0) {
            estimator.latestDiagnostics?.let { row ->
                diagnostics.addLast(row)
                while (diagnostics.size > diagnosticsCapacity) diagnostics.removeFirst()
            }
        }
        return estimate
    }

    fun snapshot(): ByteArray = ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { output ->
            output.writeInt(SNAPSHOT_MAGIC)
            output.writeInt(SNAPSHOT_VERSION)
            estimator.writeTo(output)
        }
        bytes.toByteArray()
    }

    /**
     * Restores estimator state. An unreadable, truncated, foreign-version or
     * internally inconsistent snapshot fails safe: the estimator reinitialises
     * from the next sample rather than continuing from a state it cannot vouch
     * for.
     */
    fun restore(snapshot: ByteArray?): Boolean {
        val restored = runCatching {
            if (snapshot == null || snapshot.isEmpty()) return@runCatching false
            DataInputStream(ByteArrayInputStream(snapshot)).use { input ->
                if (input.readInt() != SNAPSHOT_MAGIC) return@use false
                if (input.readInt() != SNAPSHOT_VERSION) return@use false
                if (!estimator.readFrom(input)) return@use false
                input.available() == 0
            }
        }.getOrDefault(false)
        if (!restored) reset()
        return restored
    }

    private companion object {
        private const val SNAPSHOT_MAGIC = 0x5349_4256
        private const val SNAPSHOT_VERSION = 1
    }
}
