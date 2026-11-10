package tk.glucodata.drivers.api

import android.content.Context
import tk.glucodata.Applic
import tk.glucodata.SensorBluetooth
import tk.glucodata.SuperGattCallback
import tk.glucodata.drivers.ManagedBluetoothSensorDriver
import tk.glucodata.drivers.ManagedSensorIdentityAdapter

object ApiGlucoseSourceIdentityAdapter : ManagedSensorIdentityAdapter {
    // Readings another app pushed in; no sensor family this app can vouch for.
    override val uiFamily = tk.glucodata.drivers.ManagedSensorUiFamily.GENERIC


    override fun matchesCallbackId(callbackId: String?, sensorId: String): Boolean {
        val normalized = callbackId?.trim().takeIf { !it.isNullOrEmpty() } ?: return false
        if (ApiGlucoseSourceRegistry.matchesSensorId(normalized, sensorId)) return true
        return SensorBluetooth.mygatts().any { callback ->
            val managed = callback as? ManagedBluetoothSensorDriver ?: return@any false
            managed.matchesManagedSensorId(normalized) && managed.matchesManagedSensorId(sensorId)
        }
    }

    override fun resolveCanonicalSensorId(sensorId: String?): String? {
        val raw = sensorId?.trim().takeIf { !it.isNullOrEmpty() } ?: return null
        SensorBluetooth.mygatts()
            .firstOrNull { callback ->
                val managed = callback as? ManagedBluetoothSensorDriver ?: return@firstOrNull false
                managed.matchesManagedSensorId(raw)
            }
            ?.SerialNumber
            ?.takeIf { it.startsWith(ApiGlucoseSourceRegistry.SENSOR_PREFIX, ignoreCase = true) }
            ?.let { return it }

        val config = Applic.app?.let(ApiGlucoseSourceRegistry::loadConfig)
        if (config?.isUsable == true && ApiGlucoseSourceRegistry.matchesSensorId(raw, config.sensorId)) {
            return config.sensorId
        }
        return raw.takeIf { it.startsWith(ApiGlucoseSourceRegistry.SENSOR_PREFIX, ignoreCase = true) }
    }

    override fun resolveStableStorageSensorId(sensorId: String?): String? =
        resolveCanonicalSensorId(sensorId)

    override fun resolveNativeSensorName(sensorId: String?): String? = null

    override fun hasPersistedManagedRecord(sensorId: String?): Boolean {
        val config = Applic.app?.let(ApiGlucoseSourceRegistry::loadConfig) ?: return false
        return config.isUsable && ApiGlucoseSourceRegistry.matchesSensorId(sensorId, config.sensorId)
    }

    override fun resolveCallbackDataptr(sensorId: String?): Long? =
        resolveCanonicalSensorId(sensorId)?.let { 0L }

    override fun persistedSensorIds(context: Context): List<String> =
        ApiGlucoseSourceRegistry.persistedSensorIds(context)

    override fun createManagedCallback(context: Context, sensorId: String, dataptr: Long): SuperGattCallback? =
        ApiGlucoseSourceRegistry.createRestoredCallback(context, sensorId, dataptr)

    override fun removePersistedSensor(context: Context, sensorId: String?) {
        val config = ApiGlucoseSourceRegistry.loadConfig(context)
        if (ApiGlucoseSourceRegistry.matchesSensorId(sensorId, config.sensorId)) {
            ApiGlucoseSourceRegistry.disableSourceSensor(context)
        }
    }

    override fun isExternallyManagedBleSensor(sensorId: String?): Boolean =
        resolveCanonicalSensorId(sensorId) != null

    override fun hasNativeSensorBacking(sensorId: String?): Boolean? =
        resolveCanonicalSensorId(sensorId)?.let { false }

    override fun shouldUseNativeHistorySync(sensorId: String?): Boolean? =
        resolveCanonicalSensorId(sensorId)?.let { false }
}
