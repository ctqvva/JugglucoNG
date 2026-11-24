package tk.glucodata.drivers.scgm

import android.content.Context
import tk.glucodata.Applic
import tk.glucodata.SuperGattCallback
import tk.glucodata.drivers.ManagedSensorIdentityAdapter
import tk.glucodata.drivers.ManagedSensorUiFamily

object ScgmIdentityAdapter : ManagedSensorIdentityAdapter {

    override val uiFamily: ManagedSensorUiFamily = ManagedSensorUiFamily.SCGM

    override fun matchesCallbackId(callbackId: String?, sensorId: String): Boolean {
        val candidate = callbackId?.trim().takeIf { !it.isNullOrEmpty() } ?: return false
        if (candidate.equals(sensorId, ignoreCase = true)) return true
        val canonical = ScgmRegistry.canonicalSensorId(candidate)
        return canonical.isNotBlank() && canonical.equals(sensorId, ignoreCase = true)
    }

    override fun resolveCanonicalSensorId(sensorId: String?): String? {
        val raw = sensorId?.trim().takeIf { !it.isNullOrEmpty() } ?: return null
        return if (ScgmRegistry.isScgmSensorId(raw)) raw.uppercase() else null
    }

    private fun isPersistedSensor(sensorId: String?, context: Context): Boolean {
        val normalized = sensorId?.trim().takeIf { !it.isNullOrEmpty() } ?: return false
        val stored = ScgmRegistry.getSensorId(context)
        return stored.isNotBlank() && stored.equals(normalized, ignoreCase = true)
    }

    override fun hasPersistedManagedRecord(sensorId: String?): Boolean {
        val context = Applic.app ?: return false
        return isPersistedSensor(sensorId, context)
    }

    override fun persistedSensorIds(context: Context): List<String> {
        val stored = ScgmRegistry.getSensorId(context)
        return if (stored.isBlank()) emptyList() else listOf(stored)
    }

    override fun resolveCallbackDataptr(sensorId: String?): Long? =
        if (hasPersistedManagedRecord(sensorId)) 0L else null

    override fun createManagedCallback(context: Context, sensorId: String, dataptr: Long): SuperGattCallback? {
        if (!isPersistedSensor(sensorId, context)) return null
        return ScgmBleManager(sensorId, 0L).also {
            it.mActiveDeviceAddress = ScgmRegistry.getLastBtAddress(context)
                .takeIf { address -> address.isNotBlank() }
        }
    }

    override fun removePersistedSensor(context: Context, sensorId: String?) {
        if (isPersistedSensor(sensorId, context)) ScgmRegistry.clear(context)
    }

    override fun isExternallyManagedBleSensor(sensorId: String?): Boolean =
        hasPersistedManagedRecord(sensorId)

    override fun usesNativeDirectStreamShell(sensorId: String?): Boolean =
        hasPersistedManagedRecord(sensorId)

    override fun hasNativeSensorBacking(sensorId: String?): Boolean? =
        if (hasPersistedManagedRecord(sensorId)) false else null

    override fun shouldUseNativeHistorySync(sensorId: String?): Boolean? =
        if (hasPersistedManagedRecord(sensorId)) false else null
}
