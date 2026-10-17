package tk.glucodata.drivers

import tk.glucodata.Applic
import android.content.Context
import tk.glucodata.Log
import tk.glucodata.SensorIdentity
import tk.glucodata.SuperGattCallback
import tk.glucodata.drivers.aidex.AiDexManagedSensorIdentityAdapter
import tk.glucodata.drivers.api.ApiGlucoseSourceIdentityAdapter
import tk.glucodata.drivers.anytime.AnytimeManagedSensorIdentityAdapter
import tk.glucodata.drivers.icanhealth.ICanHealthManagedSensorIdentityAdapter
import tk.glucodata.drivers.medtrum.MedtrumIdentityAdapter
import tk.glucodata.drivers.mq.MQManagedSensorIdentityAdapter
import tk.glucodata.drivers.ottai.OttaiManagedSensorIdentityAdapter
import tk.glucodata.drivers.sibionics.SibionicsManagedSensorIdentityAdapter
import tk.glucodata.drivers.nightscout.NightscoutFollowerIdentityAdapter

object ManagedSensorIdentityRegistry {
    private const val TAG = "ManagedSensorIdentity"

    val all: List<ManagedSensorIdentityAdapter> = listOf(
        AiDexManagedSensorIdentityAdapter,
        AnytimeManagedSensorIdentityAdapter,
        ICanHealthManagedSensorIdentityAdapter,
        MQManagedSensorIdentityAdapter,
        MedtrumIdentityAdapter,
        OttaiManagedSensorIdentityAdapter,
        SibionicsManagedSensorIdentityAdapter,
        NightscoutFollowerIdentityAdapter,
        ApiGlucoseSourceIdentityAdapter,
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

    /**
     * The family whose persisted record owns [sensorId], or null when no
     * adapter claims it. Reads only the adapters' own records: no driver needs
     * to be running, and no SensorBluetooth lock is taken, so this is safe on
     * the reading broadcast path.
     */
    @JvmStatic
    fun resolvePersistedFamily(sensorId: String?): ManagedSensorUiFamily? {
        val normalized = sensorId?.trim().takeIf { !it.isNullOrEmpty() } ?: return null
        return all.firstOrNull { adapter ->
            runCatching { adapter.hasPersistedManagedRecord(normalized) }.getOrDefault(false)
        }?.uiFamily
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

    fun removePersistedSensor(context: Context, sensorId: String?) {
        val normalized = sensorId?.trim().takeIf { !it.isNullOrEmpty() }
        if (normalized != null) {
            val exactOwners = all.filter { adapter ->
                adapter.persistedSensorIds(context).any { it.equals(normalized, ignoreCase = true) }
            }
            val owners = if (exactOwners.isNotEmpty()) {
                exactOwners
            } else {
                all.filter { it.hasPersistedManagedRecord(normalized) }
            }
            when (owners.size) {
                0 -> Unit
                1 -> owners.single().removePersistedSensor(context, normalized)
                else -> Log.e(
                    TAG,
                    "Refusing ambiguous persisted sensor removal for $normalized: " +
                        owners.joinToString { it.javaClass.simpleName }
                )
            }
        }
        ManagedSensorViewModeStore.clear(context, sensorId)
        SensorIdentity.invalidateCaches()
        // Removals never propagated to the watch: its handoff-synced record
        // kept the sensor listed as "Connected" and hijacked current selection.
        tk.glucodata.WearSync2.pushRemoval(sensorId)
    }
}
