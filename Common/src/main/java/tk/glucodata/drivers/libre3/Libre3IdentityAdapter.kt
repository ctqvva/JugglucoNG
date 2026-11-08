// Libre3IdentityAdapter.kt — managed identity seam for Kotlin-driven Libre 3 sensors.
//
// A managed Libre 3 is identified by its 9-character printed serial (e.g. "0D1234ABC"). The legacy
// native path names the same sensor "E07A-XX" + serial; the two never share a record, and this
// adapter deliberately does not claim the legacy name, so removing one can never remove the other.

package tk.glucodata.drivers.libre3

import android.content.Context
import tk.glucodata.Applic
import tk.glucodata.SuperGattCallback
import tk.glucodata.drivers.ManagedSensorIdentityAdapter
import tk.glucodata.drivers.ManagedSensorUiFamily

object Libre3IdentityAdapter : ManagedSensorIdentityAdapter {

    /** The legacy native record name for the same physical sensor (Sensoren::namelibre3). */
    fun legacyNativeName(serial: String): String = "E07A-XX$serial"

    fun sameSensor(a: String?, b: String?): Boolean {
        val x = a?.trim().takeIf { !it.isNullOrEmpty() } ?: return false
        val y = b?.trim().takeIf { !it.isNullOrEmpty() } ?: return false
        return x.equals(y, ignoreCase = true)
    }

    override val uiFamily: ManagedSensorUiFamily = ManagedSensorUiFamily.LIBRE3

    private fun stored(sensorId: String?): String? {
        val ctx = Applic.app ?: return null
        val id = sensorId?.trim().takeIf { !it.isNullOrEmpty() } ?: return null
        return Libre3Persistence.sensorIds(ctx).firstOrNull { it.equals(id, ignoreCase = true) }
    }

    override fun matchesCallbackId(callbackId: String?, sensorId: String): Boolean = sameSensor(callbackId, sensorId)

    override fun resolveCanonicalSensorId(sensorId: String?): String? = stored(sensorId)

    override fun hasPersistedManagedRecord(sensorId: String?): Boolean = stored(sensorId) != null

    override fun persistedSensorIds(context: Context): List<String> = Libre3Persistence.sensorIds(context)

    override fun resolveCallbackDataptr(sensorId: String?): Long? = if (stored(sensorId) != null) 0L else null

    override fun createManagedCallback(context: Context, sensorId: String, dataptr: Long): SuperGattCallback? {
        val id = Libre3Persistence.sensorIds(context).firstOrNull { it.equals(sensorId, ignoreCase = true) } ?: return null
        if (Libre3Persistence.loadSensor(context, id) == null) return null
        return Libre3BleManager(id)
    }

    override fun removePersistedSensor(context: Context, sensorId: String?) {
        val wanted = sensorId?.trim().takeIf { !it.isNullOrEmpty() } ?: return
        val id = Libre3Persistence.sensorIds(context).firstOrNull { it.equals(wanted, ignoreCase = true) } ?: return
        Libre3Persistence.removeSensor(context, id)
    }

    override fun isExternallyManagedBleSensor(sensorId: String?): Boolean = hasPersistedManagedRecord(sensorId)

    override fun usesNativeDirectStreamShell(sensorId: String?): Boolean = hasPersistedManagedRecord(sensorId)

    override fun hasNativeSensorBacking(sensorId: String?): Boolean? =
        if (hasPersistedManagedRecord(sensorId)) false else null

    override fun shouldUseNativeHistorySync(sensorId: String?): Boolean? =
        if (hasPersistedManagedRecord(sensorId)) false else null
}
