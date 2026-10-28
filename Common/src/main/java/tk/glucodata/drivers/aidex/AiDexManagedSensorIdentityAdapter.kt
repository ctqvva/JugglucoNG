package tk.glucodata.drivers.aidex

import android.content.Context
import tk.glucodata.Applic
import tk.glucodata.SensorIdentity
import tk.glucodata.SensorBluetooth
import tk.glucodata.drivers.ManagedSensorIdentityAdapter
import tk.glucodata.drivers.ManagedSensorUiFamily
import tk.glucodata.SuperGattCallback

object AiDexManagedSensorIdentityAdapter : ManagedSensorIdentityAdapter {
    override val uiFamily = ManagedSensorUiFamily.AIDEX

    private const val PREFIX = "X-"
    private const val PREFS_NAME = "tk.glucodata_preferences"
    private const val PREF_KEY = "aidex_sensors"

    private fun normalized(sensorId: String?): String? =
        sensorId?.trim()?.takeIf { it.isNotEmpty() }

    private fun readPersistedEntries(context: Context): LinkedHashSet<String> {
        return try {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getStringSet(PREF_KEY, linkedSetOf())
                ?.toCollection(LinkedHashSet())
                ?: linkedSetOf()
        } catch (_: Throwable) {
            linkedSetOf()
        }
    }

    private fun persistEntries(context: Context, entries: Set<String>) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(PREF_KEY, LinkedHashSet(entries))
            .commit()
        SensorIdentity.invalidateCaches()
    }

    fun isManagedSensorId(sensorId: String?): Boolean {
        val normalized = normalized(sensorId) ?: return false
        return normalized.startsWith(PREFIX, ignoreCase = true)
    }

    fun nativeAlias(sensorId: String?): String? {
        val normalized = normalized(sensorId) ?: return null
        return if (isManagedSensorId(normalized) && normalized.length > PREFIX.length) {
            normalized.substring(PREFIX.length)
        } else {
            null
        }
    }

    override fun matchesCallbackId(callbackId: String?, sensorId: String): Boolean {
        val normalizedCallbackId = normalized(callbackId) ?: return false
        return normalizedCallbackId.equals(sensorId, ignoreCase = true) ||
            nativeAlias(normalizedCallbackId)?.equals(sensorId, ignoreCase = true) == true
    }

    override fun resolveCanonicalSensorId(sensorId: String?): String? {
        val normalized = normalized(sensorId) ?: return null
        if (isManagedSensorId(normalized)) {
            val alias = nativeAlias(normalized) ?: return null
            return "$PREFIX${alias.uppercase()}"
        }
        SensorBluetooth.mygatts()
            .firstOrNull { callback -> matchesCallbackId(callback.SerialNumber, normalized) }
            ?.SerialNumber
            ?.takeIf { it.isNotBlank() && isManagedSensorId(it) }
            ?.let { return it }
        val context = Applic.app
        if (context != null) {
            // Retained native short names still belong to the managed writer
            // after the BLE registration and saved sensor entry are removed.
            val retained = "$PREFIX${normalized.uppercase()}"
            if (context.getSharedPreferences("AiDexNativePrefs", Context.MODE_PRIVATE)
                    .contains("algorithmAppliedMode_$retained") ||
                AiDexSourceJournalBackup.file(context.filesDir, retained).isFile
            ) return retained
            persistedSensorIds(context)
                .firstOrNull { matchesCallbackId(it, normalized) }
                ?.let { return it }
        }
        return null
    }

    override fun resolveStableStorageSensorId(sensorId: String?): String? {
        val normalized = normalized(sensorId) ?: return null
        if (isManagedSensorId(normalized)) {
            val alias = nativeAlias(normalized) ?: return null
            return "$PREFIX${alias.uppercase()}"
        }
        return resolveCanonicalSensorId(normalized)
    }

    override fun resolveNativeSensorName(sensorId: String?): String? {
        val canonical = resolveCanonicalSensorId(sensorId) ?: return null
        return canonical.takeIf { isManagedSensorId(it) }
    }

    override fun hasPersistedManagedRecord(sensorId: String?): Boolean {
        val normalized = normalized(sensorId) ?: return false
        val context = Applic.app ?: return false
        return persistedSensorIds(context).any { matchesCallbackId(it, normalized) }
    }

    override fun persistedSensorIds(context: Context): List<String> {
        return readPersistedEntries(context)
            .mapNotNull { entry ->
                entry.substringBefore('|').trim().takeIf { it.isNotEmpty() }
            }
            .distinct()
    }

    override fun createManagedCallback(context: Context, sensorId: String, dataptr: Long): SuperGattCallback? {
        if (!isManagedSensorId(sensorId)) {
            return null
        }
        return AiDexNativeFactory.createBleManager(sensorId, dataptr)
    }

    override fun removePersistedSensor(context: Context, sensorId: String?) {
        val canonical = resolveCanonicalSensorId(sensorId) ?: return
        val updated = readPersistedEntries(context).filterNot { entry ->
            val serial = entry.substringBefore('|').trim()
            matchesCallbackId(canonical, serial)
        }.toCollection(LinkedHashSet())
        persistEntries(context, updated)
    }

    override fun resolveNativeHistorySensorNames(sensorId: String?): List<String> {
        val canonical = resolveCanonicalSensorId(sensorId) ?: return emptyList()
        val alias = nativeAlias(canonical)
        return listOfNotNull(alias).distinct()
    }

    override fun isExternallyManagedBleSensor(sensorId: String?): Boolean =
        isManagedSensorId(sensorId)

    override fun shouldUseNativeHistorySync(sensorId: String?): Boolean? =
        if (resolveCanonicalSensorId(sensorId) != null) false else null
}
