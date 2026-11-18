package tk.glucodata

import androidx.health.connect.client.records.metadata.Metadata
import tk.glucodata.drivers.ManagedSensorUiFamily

/** Idempotent Libre 3 replay: one client record per native minute slot. */
object HealthConnectRecordMetadata {
    /** Pairing removal keeps native history, so it must also keep that history's client IDs. */
    internal fun prefixForSensor(sensorPtr: Long, sensorName: String, startSeconds: Long): String? =
        if (Natives.getSensorManagedFamilyFromSensorptr(sensorPtr) == ManagedSensorUiFamily.LIBRE3.nativeCode)
            "libre3:$sensorName:$startSeconds:" else null

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
