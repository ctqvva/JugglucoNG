package tk.glucodata.drivers.mq

import java.math.BigDecimal
import java.math.RoundingMode

internal object MQBootstrapSeed {
    /** QR sensitivity is raw; the vendor normalizes it using the transmitter's BLE metadata. */
    fun normalizeSensitivity(raw: Float?, transmitter10: Int?): Float? {
        if (raw == null || !raw.isFinite() || raw <= 0f) return null
        return when (transmitter10) {
            0 -> raw
            1 -> BigDecimal(raw.toString()).multiply(BigDecimal.TEN)
                .setScale(1, RoundingMode.DOWN).toFloat()
                .takeIf { it.isFinite() && it > 0f }
            else -> null // An unknown scale is not a usable calibration seed.
        }
    }

    fun initialReference(
        algorithmVersion: Int,
        packetIndex: Int,
        sampleCurrent: Int,
        previousProcessed: Double,
        sensitivity: Double,
        packages: Int,
        multiplier: Double,
    ): MQAlgorithm.Result? {
        if (!sensitivity.isFinite() || sensitivity <= 0.0 ||
            packetIndex < MQConstants.ALGO_WARMUP_PACKET_THRESHOLD || sampleCurrent <= 0
        ) return null
        return MQAlgorithm.calculateResult(
            algorithmVersion = algorithmVersion,
            // The vendor's first-reference call uses 1, bypassing smoothing against empty history.
            initTimeMinutes = 1.0,
            packetIndex = packetIndex.toDouble(),
            sampleCurrent = sampleCurrent.toDouble(),
            previousReviseCurrent2 = previousProcessed,
            kValue = sensitivity,
            referenceBgTimes10Mmol = 0.0,
            bValue = 2.0,
            packages = packages.toDouble(),
            multiplier = multiplier,
        )
    }
}
