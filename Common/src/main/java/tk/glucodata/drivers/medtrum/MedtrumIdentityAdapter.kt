// MedtrumIdentityAdapter.kt — Vendor identity adapter for the managed identity seam.

package tk.glucodata.drivers.medtrum

import android.content.Context
import tk.glucodata.Applic
import tk.glucodata.SuperGattCallback
import tk.glucodata.drivers.ManagedSensorIdentityAdapter
import tk.glucodata.drivers.ManagedSensorUiFamily

object MedtrumIdentityAdapter : ManagedSensorIdentityAdapter {

    override val uiFamily: ManagedSensorUiFamily = ManagedSensorUiFamily.MEDTRUM

    override fun matchesCallbackId(callbackId: String?, sensorId: String): Boolean {
        val normalized = callbackId?.trim().takeIf { !it.isNullOrEmpty() } ?: return false
        return normalized.equals(sensorId, ignoreCase = true) ||
            MedtrumSerialAlias.isShortForm(normalized, sensorId) ||
            MedtrumSerialAlias.isShortForm(sensorId, normalized)
    }

    override fun resolveCanonicalSensorId(sensorId: String?): String? {
        val raw = sensorId?.trim().takeIf { !it.isNullOrEmpty() } ?: return null
        return if (MedtrumPersistence.isMedtrumSensorId(raw)) raw else null
    }

    private fun isPersistedSensor(sensorId: String?, context: Context): Boolean {
        val normalized = sensorId?.trim().takeIf { !it.isNullOrEmpty() } ?: return false
        val storedId = MedtrumPersistence.getSensorId(context)
        if (storedId.isBlank()) return false
        return storedId.equals(normalized, ignoreCase = true)
    }

    /** The native mirror's shortened name, which must not become a callback of its own. */
    private fun isPersistedAlias(sensorId: String?, context: Context): Boolean =
        MedtrumSerialAlias.isShortForm(sensorId, MedtrumPersistence.getSensorId(context))

    override fun hasPersistedManagedRecord(sensorId: String?): Boolean {
        val ctx = Applic.app ?: return false
        return isPersistedSensor(sensorId, ctx) || isPersistedAlias(sensorId, ctx)
    }

    override fun persistedSensorIds(context: Context): List<String> {
        val stored = MedtrumPersistence.getSensorId(context)
        return if (stored.isNotBlank()) listOf(stored) else emptyList()
    }

    override fun resolveCallbackDataptr(sensorId: String?): Long? {
        return if (hasPersistedManagedRecord(sensorId)) 0L else null
    }

    override fun createManagedCallback(context: Context, sensorId: String, dataptr: Long): SuperGattCallback? {
        if (!isPersistedSensor(sensorId, context)) return null
        return MedtrumBleManager(sensorId, 0L).also {
            it.mActiveDeviceAddress = MedtrumPersistence.getLastBtAddress(context).takeIf { addr -> addr.isNotBlank() }
        }
    }

    override fun removePersistedSensor(context: Context, sensorId: String?) {
        val normalized = sensorId?.trim().takeIf { !it.isNullOrEmpty() } ?: return
        val stored = MedtrumPersistence.getSensorId(context)
        if (stored.isNotBlank() && stored.equals(normalized, ignoreCase = true)) {
            MedtrumPersistence.clear(context)
        }
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
