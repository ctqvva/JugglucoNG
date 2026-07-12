package tk.glucodata.drivers

import tk.glucodata.Applic
import android.content.Context
import tk.glucodata.SensorIdentity
import tk.glucodata.SuperGattCallback
import tk.glucodata.drivers.aidex.AiDexManagedSensorIdentityAdapter
import tk.glucodata.drivers.api.ApiGlucoseSourceIdentityAdapter
import tk.glucodata.drivers.anytime.AnytimeManagedSensorIdentityAdapter
import tk.glucodata.drivers.icanhealth.ICanHealthManagedSensorIdentityAdapter
import tk.glucodata.drivers.mq.MQManagedSensorIdentityAdapter
import tk.glucodata.drivers.nightscout.NightscoutFollowerIdentityAdapter
import tk.glucodata.drivers.ottai.OttaiManagedSensorIdentityAdapter

object ManagedSensorIdentityRegistry {
    val all: List<ManagedSensorIdentityAdapter> = listOf(
        AiDexManagedSensorIdentityAdapter,
        AnytimeManagedSensorIdentityAdapter,
        ICanHealthManagedSensorIdentityAdapter,
        MQManagedSensorIdentityAdapter,
        NightscoutFollowerIdentityAdapter,
        ApiGlucoseSourceIdentityAdapter,
        OttaiManagedSensorIdentityAdapter,
    )

    private fun orderedAdapters(sensorId: String?, context: Context?): Sequence<ManagedSensorIdentityAdapter> {
        val normalized = sensorId?.trim().takeIf { !it.isNullOrEmpty() }
        if (normalized == null || context == null) {
            return all.asSequence()
        }
        val preferred = all.filter { adapter -> adapter.hasPersistedManagedRecord(normalized) }
        if (preferred.isEmpty()) {
            return all.asSequence()
        }
        return sequence {
            preferred.forEach { yield(it) }
            all.forEach { adapter ->
                if (!preferred.contains(adapter)) {
                    yield(adapter)
                }
            }
        }
    }

    fun persistedSensorIds(context: Context): List<String> =
        all.asSequence()
            .flatMap { it.persistedSensorIds(context).asSequence() }
            .distinct()
            .toList()

    fun createManagedCallback(context: Context, sensorId: String, dataptr: Long): SuperGattCallback? =
        orderedAdapters(sensorId, context)
            .mapNotNull { it.createManagedCallback(context, sensorId, dataptr) }
            .firstOrNull()

    fun resolveManagedCallbackDataptr(sensorId: String?): Long? =
        orderedAdapters(sensorId, Applic.app)
            .mapNotNull { it.resolveCallbackDataptr(sensorId) }
            .firstOrNull()

    fun resolveManagedNativeSensorName(sensorId: String?): String? =
        orderedAdapters(sensorId, Applic.app)
            .mapNotNull { it.resolveNativeSensorName(sensorId) }
            .firstOrNull { it.isNotBlank() }

    fun isExternallyManagedBleSensor(sensorId: String?): Boolean =
        orderedAdapters(sensorId, Applic.app).any { it.isExternallyManagedBleSensor(sensorId) }

    fun usesNativeDirectStreamShell(sensorId: String?): Boolean =
        orderedAdapters(sensorId, Applic.app).any { it.usesNativeDirectStreamShell(sensorId) }

    fun hasNativeSensorBacking(sensorId: String?): Boolean? =
        orderedAdapters(sensorId, Applic.app)
            .mapNotNull { it.hasNativeSensorBacking(sensorId) }
            .firstOrNull()

    fun shouldUseNativeHistorySync(sensorId: String?): Boolean? =
        orderedAdapters(sensorId, Applic.app)
            .mapNotNull { it.shouldUseNativeHistorySync(sensorId) }
            .firstOrNull()

    fun isExpired(sensorId: String?): Boolean =
        orderedAdapters(sensorId, Applic.app).any { it.isExpired(sensorId) }

    fun removePersistedSensor(context: Context, sensorId: String?) {
        val normalized = sensorId?.trim()?.takeIf { it.isNotEmpty() } ?: return
        val ownersBeforeRemoval = all.filter { it.hasPersistedManagedRecord(normalized) }
        if (ownersBeforeRemoval.isNotEmpty()) {
            ownersBeforeRemoval.first().removePersistedSensor(context, sensorId)
        } else {
            all.forEach { it.removePersistedSensor(context, sensorId) }
        }
        val stillPersisted = ownersBeforeRemoval.size > 1 ||
            all.any { it.hasPersistedManagedRecord(normalized) }
        if (!stillPersisted) {
            ManagedSensorViewModeStore.clear(context, sensorId)
            SensorIdentity.invalidateCaches()
        }
    }
}
