package tk.glucodata.drivers.instara

import android.content.Context
import tk.glucodata.Applic
import tk.glucodata.SuperGattCallback
import tk.glucodata.drivers.ManagedSensorIdentityAdapter
import tk.glucodata.drivers.ManagedSensorUiFamily

object InstaraIdentityAdapter : ManagedSensorIdentityAdapter {

    override val uiFamily: ManagedSensorUiFamily = ManagedSensorUiFamily.INSTARA

    private fun owned(sensorId: String?): Boolean {
        val normalized = sensorId?.trim().takeIf { !it.isNullOrEmpty() } ?: return false
        val stored = Applic.app?.let { InstaraPersistence.getSerialNumber(it) }.orEmpty()
        return stored.equals(normalized, ignoreCase = true)
    }

    override fun matchesCallbackId(callbackId: String?, sensorId: String): Boolean =
        callbackId?.trim()?.equals(sensorId.trim(), ignoreCase = true) == true

    override fun resolveCanonicalSensorId(sensorId: String?): String? =
        sensorId?.trim()?.takeIf { InstaraPersistence.isValidSn(it) }

    override fun hasPersistedManagedRecord(sensorId: String?): Boolean = owned(sensorId)

    override fun persistedSensorIds(context: Context): List<String> =
        InstaraPersistence.getSerialNumber(context).let { if (it.isBlank()) emptyList() else listOf(it) }

    override fun resolveCallbackDataptr(sensorId: String?): Long? = if (owned(sensorId)) 0L else null

    override fun createManagedCallback(
        context: Context,
        sensorId: String,
        dataptr: Long,
    ): SuperGattCallback? {
        if (!owned(sensorId)) return null
        return InstaraBleManager(sensorId, 0L).also {
            it.mActiveDeviceAddress = InstaraPersistence.getLastBtAddress(context)
                .takeIf { address -> address.isNotBlank() }
        }
    }

    override fun removePersistedSensor(context: Context, sensorId: String?) {
        val stored = InstaraPersistence.getSerialNumber(context)
        if (stored.isNotBlank() && stored.equals(sensorId?.trim(), ignoreCase = true)) {
            InstaraPersistence.clear(context)
        }
    }

    override fun isExternallyManagedBleSensor(sensorId: String?): Boolean = owned(sensorId)

    override fun usesNativeDirectStreamShell(sensorId: String?): Boolean = owned(sensorId)

    override fun hasNativeSensorBacking(sensorId: String?): Boolean? = if (owned(sensorId)) false else null

    override fun shouldUseNativeHistorySync(sensorId: String?): Boolean? = if (owned(sensorId)) false else null
}
