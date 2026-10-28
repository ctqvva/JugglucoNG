package tk.glucodata.drivers.nightscout

import android.content.Context
import tk.glucodata.Applic
import tk.glucodata.SensorBluetooth
import tk.glucodata.SuperGattCallback
import tk.glucodata.drivers.ManagedBluetoothSensorDriver
import tk.glucodata.drivers.ManagedSensorIdentityAdapter

object NightscoutFollowerIdentityAdapter : ManagedSensorIdentityAdapter {
    override val uiFamily = tk.glucodata.drivers.ManagedSensorUiFamily.NIGHTSCOUT


    override fun matchesCallbackId(callbackId: String?, sensorId: String): Boolean {
        val normalized = callbackId?.trim().takeIf { !it.isNullOrEmpty() } ?: return false
        if (NightscoutFollowerRegistry.matchesSensorId(normalized, sensorId)) return true
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
            ?.takeIf { it.startsWith(NightscoutFollowerRegistry.SENSOR_PREFIX, ignoreCase = true) }
            ?.let { return it }

        val config = Applic.app?.let(NightscoutFollowerRegistry::loadConfig)
        if (config?.isUsable == true && NightscoutFollowerRegistry.matchesSensorId(raw, config.sensorId)) {
            return config.sensorId
        }
        return raw.takeIf { it.startsWith(NightscoutFollowerRegistry.SENSOR_PREFIX, ignoreCase = true) }
    }

    override fun resolveStableStorageSensorId(sensorId: String?): String? =
        resolveCanonicalSensorId(sensorId)

    override fun resolveNativeSensorName(sensorId: String?): String? = null

    override fun hasPersistedManagedRecord(sensorId: String?): Boolean {
        val config = Applic.app?.let(NightscoutFollowerRegistry::loadConfig) ?: return false
        return config.isUsable && NightscoutFollowerRegistry.matchesSensorId(sensorId, config.sensorId)
    }

    override fun resolveCallbackDataptr(sensorId: String?): Long? =
        resolveCanonicalSensorId(sensorId)?.let { 0L }

    override fun persistedSensorIds(context: Context): List<String> =
        NightscoutFollowerRegistry.persistedSensorIds(context)

    override fun createManagedCallback(context: Context, sensorId: String, dataptr: Long): SuperGattCallback? =
        NightscoutFollowerRegistry.createRestoredCallback(context, sensorId, dataptr)

    override fun removePersistedSensor(context: Context, sensorId: String?) {
        val config = NightscoutFollowerRegistry.loadConfig(context)
        if (NightscoutFollowerRegistry.matchesSensorId(sensorId, config.sensorId)) {
            NightscoutFollowerRegistry.disableFollowerSensor(context)
        }
    }

    override fun isExternallyManagedBleSensor(sensorId: String?): Boolean =
        resolveCanonicalSensorId(sensorId) != null

    override fun hasNativeSensorBacking(sensorId: String?): Boolean? =
        resolveCanonicalSensorId(sensorId)?.let { false }

    override fun shouldUseNativeHistorySync(sensorId: String?): Boolean? =
        resolveCanonicalSensorId(sensorId)?.let { false }
}
