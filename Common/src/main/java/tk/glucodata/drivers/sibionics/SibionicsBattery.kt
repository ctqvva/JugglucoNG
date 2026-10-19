package tk.glucodata.drivers.sibionics

import java.util.UUID

internal object SibionicsBattery {
    const val REQUIRED_STABLE_SAMPLES = 3
    const val MAX_STABILIZATION_ATTEMPTS = 5
    const val STABLE_RANGE_PERCENT = 2

    val SERVICE: UUID = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")
    val LEVEL_CHARACTERISTIC: UUID = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")

    enum class StabilizationDecision {
        CONTINUE,
        STABLE,
        EXHAUSTED,
    }

    /** Bluetooth SIG Battery Level is an unsigned, one-byte percentage. */
    fun parsePercent(value: ByteArray): Int? =
        value.singleOrNull()
            ?.toInt()
            ?.and(0xFF)
            ?.takeIf { it in 0..100 }

    fun stabilizationDecision(
        attemptCount: Int,
        successfulSamples: List<Int>,
    ): StabilizationDecision {
        val recent = successfulSamples.takeLast(REQUIRED_STABLE_SAMPLES)
        if (recent.size == REQUIRED_STABLE_SAMPLES &&
            recent.max() - recent.min() <= STABLE_RANGE_PERCENT
        ) {
            return StabilizationDecision.STABLE
        }
        return if (attemptCount >= MAX_STABILIZATION_ATTEMPTS) {
            StabilizationDecision.EXHAUSTED
        } else {
            StabilizationDecision.CONTINUE
        }
    }
}
