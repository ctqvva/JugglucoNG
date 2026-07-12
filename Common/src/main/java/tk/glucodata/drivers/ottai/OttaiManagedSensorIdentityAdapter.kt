package tk.glucodata.drivers.ottai

import android.content.Context
import tk.glucodata.Applic
import tk.glucodata.SensorBluetooth
import tk.glucodata.SuperGattCallback
import tk.glucodata.drivers.ManagedSensorIdentityAdapter

object OttaiManagedSensorIdentityAdapter : ManagedSensorIdentityAdapter {

    private val MAC_12_HEX = Regex("^[0-9A-F]{12}$", RegexOption.IGNORE_CASE)
    private val MAC_COLON = Regex("^(?:[0-9A-F]{2}:){5}[0-9A-F]{2}$", RegexOption.IGNORE_CASE)

    override fun matchesCallbackId(callbackId: String?, sensorId: String): Boolean {
        val normalized = callbackId?.trim().takeIf { !it.isNullOrEmpty() } ?: return false
        if (normalized.equals(sensorId, ignoreCase = true)) return true

        val cbCanonical = resolveCanonicalSensorId(normalized) ?: toPlainHex(normalized)
        val sensorCanonical = resolveCanonicalSensorId(sensorId) ?: toPlainHex(sensorId)
        if (cbCanonical.isNotBlank() && sensorCanonical.isNotBlank() &&
            cbCanonical.equals(sensorCanonical, ignoreCase = true)
        ) {
            return true
        }

        return SensorBluetooth.mygatts().any { cb ->
            val drv = cb as? OttaiDriver ?: return@any false
            drv.matchesManagedSensorId(normalized) && drv.matchesManagedSensorId(sensorId)
        }
    }

    override fun resolveCanonicalSensorId(sensorId: String?): String? {
        val raw = sensorId?.trim().takeIf { !it.isNullOrEmpty() } ?: return null
        val plain = toPlainHex(raw)
        if (plain.isBlank()) return null

        runCatching { OttaiRegistry.resolveCanonicalSensorId(Applic.app, plain) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }

        SensorBluetooth.mygatts()
            .firstOrNull { cb ->
                (cb as? OttaiDriver)?.matchesManagedSensorId(raw) == true
            }
            ?.SerialNumber
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }

        return null
    }

    override fun resolveStableStorageSensorId(sensorId: String?): String? {
        val raw = sensorId?.trim().takeIf { !it.isNullOrEmpty() } ?: return null
        resolveCanonicalSensorId(raw)?.takeIf { it.isNotBlank() }?.let { return it }
        val plain = toPlainHex(raw)
        return plain.takeIf { MAC_12_HEX.matches(it) }
    }

    override fun resolveNativeSensorName(sensorId: String?): String? {
        val raw = sensorId?.trim().takeIf { !it.isNullOrEmpty() } ?: return null
        val canonical = resolveCanonicalSensorId(raw) ?: return null
        return OttaiRegistry.findRecord(Applic.app, canonical)?.let { canonical }
    }

    override fun hasPersistedManagedRecord(sensorId: String?): Boolean {
        val normalized = sensorId?.trim().takeIf { !it.isNullOrEmpty() } ?: return false
        return OttaiRegistry.findRecord(Applic.app, normalized) != null
    }

    override fun resolveCallbackDataptr(sensorId: String?): Long? =
        resolveCanonicalSensorId(sensorId)
            ?.let { OttaiRegistry.findRecord(Applic.app, it) }
            ?.let { 0L }

    override fun persistedSensorIds(context: Context): List<String> =
        OttaiRegistry.persistedRecords(context).map { it.sensorId }

    override fun createManagedCallback(context: Context, sensorId: String, dataptr: Long): SuperGattCallback? {
        OttaiRegistry.createRestoredCallback(context, sensorId, dataptr)?.let { return it }
        val canonical = resolveCanonicalSensorId(sensorId)
            ?.takeIf { it.isNotBlank() && MAC_12_HEX.matches(it) }
            ?: return null
        OttaiRegistry.findRecord(context, canonical) ?: return null
        return OttaiBleManager(canonical, dataptr).also { it.restoreFromPersistence(context) }
    }

    override fun removePersistedSensor(context: Context, sensorId: String?) {
        OttaiRegistry.removeSensor(context, sensorId)
    }

    override fun isExternallyManagedBleSensor(sensorId: String?): Boolean =
        resolveCanonicalSensorId(sensorId)?.let { OttaiRegistry.findRecord(Applic.app, it) } != null

    override fun usesNativeDirectStreamShell(sensorId: String?): Boolean =
        resolveCanonicalSensorId(sensorId)?.let { OttaiRegistry.findRecord(Applic.app, it) } != null

    override fun hasNativeSensorBacking(sensorId: String?): Boolean? {
        resolveCanonicalSensorId(sensorId) ?: return null
        return true
    }

    override fun shouldUseNativeHistorySync(sensorId: String?): Boolean? {
        resolveCanonicalSensorId(sensorId) ?: return null
        return false
    }

    override fun isExpired(sensorId: String?): Boolean {
        val canonical = resolveCanonicalSensorId(sensorId) ?: return false
        SensorBluetooth.mygatts()
            .mapNotNull { it as? OttaiDriver }
            .firstOrNull { it.matchesManagedSensorId(canonical) }
            ?.let { return it.isSensorExpired() }

        val context = Applic.app ?: return false
        val record = OttaiRegistry.findRecord(context, canonical) ?: return false
        val startAtMs = record.activeTimeMs.takeIf { it > 0L } ?: record.provisionalActiveTimeMs
        val expireMs = record.activeExpireTimeMs.takeIf { it > 0L } ?: OttaiConstants.DEFAULT_ACTIVE_EXPIRE_MS
        return startAtMs > 0L && System.currentTimeMillis() >= startAtMs + expireMs
    }

    private fun toPlainHex(input: String): String {
        val stripped = input.replace(":", "").uppercase()
        return if (MAC_12_HEX.matches(stripped)) stripped else input.uppercase()
    }
}
