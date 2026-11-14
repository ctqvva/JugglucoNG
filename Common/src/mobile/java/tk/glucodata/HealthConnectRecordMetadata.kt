package tk.glucodata

import androidx.health.connect.client.records.metadata.Metadata

/** Idempotent Libre 3 replay: one client record per native minute slot. */
object HealthConnectRecordMetadata {
    @JvmStatic
    fun forReading(base: Metadata, prefix: String?, startSeconds: Long, timeSeconds: Long, version: Long): Metadata {
        if (prefix == null) return base
        val slot = (timeSeconds - startSeconds) / 60L
        return Metadata.unknownRecordingMethod(
            device = base.device,
            clientRecordId = prefix + slot,
            clientRecordVersion = version,
        )
    }
}
