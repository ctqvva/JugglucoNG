package tk.glucodata.combined

import android.content.Context
import tk.glucodata.Applic
import tk.glucodata.drivers.ManagedSensorIdentityAdapter
import tk.glucodata.drivers.ManagedSensorUiFamily

object CombinedSensorIdentityAdapter : ManagedSensorIdentityAdapter {
    override val uiFamily = ManagedSensorUiFamily.GENERIC
    override fun resolveCanonicalSensorId(sensorId: String?) = CombinedSensorRegistry.canonicalId(sensorId)
    override fun matchesCallbackId(callbackId: String?, sensorId: String) =
        resolveCanonicalSensorId(callbackId)?.let { it == resolveCanonicalSensorId(sensorId) } == true
    override fun resolveNativeSensorName(sensorId: String?) = resolveCanonicalSensorId(sensorId)
    override fun resolveNativeHistorySensorNames(sensorId: String?) =
        resolveCanonicalSensorId(sensorId)?.let { listOf(it, it.takeLast(8)) }.orEmpty()
    override fun resolveCallbackDataptr(sensorId: String?) = resolveCanonicalSensorId(sensorId)?.let { 0L }
    override fun hasNativeSensorBacking(sensorId: String?) = resolveCanonicalSensorId(sensorId)?.let { true }
    override fun shouldUseNativeHistorySync(sensorId: String?) = resolveCanonicalSensorId(sensorId)?.let { true }
    override fun usesNativeDirectStreamShell(sensorId: String?) = CombinedSensorRegistry.isCombined(sensorId)
    override fun isExternallyManagedBleSensor(sensorId: String?) = CombinedSensorRegistry.isCombined(sensorId)
    override fun hasPersistedManagedRecord(sensorId: String?) =
        CombinedSensorRegistry.config().let { it.enabled && it.sensorId == sensorId }
    override fun persistedSensorIds(context: Context) = CombinedSensorRegistry.config()
        .takeIf { it.enabled && !Applic.isWearable }?.let { listOf(it.sensorId) }.orEmpty()
    override fun createManagedCallback(context: Context, sensorId: String, dataptr: Long) =
        resolveCanonicalSensorId(sensorId)?.let { id ->
            val config = CombinedSensorRegistry.config()
            CombinedSensorManager(id, config.inputs.takeIf { config.enabled && config.sensorId == id }.orEmpty())
        }
}
