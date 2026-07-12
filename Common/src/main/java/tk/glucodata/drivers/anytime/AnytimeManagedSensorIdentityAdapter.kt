// AnytimeManagedSensorIdentityAdapter.kt — Vendor adapter for the managed
// identity seam.

package tk.glucodata.drivers.anytime

import android.content.Context
import tk.glucodata.Applic
import tk.glucodata.SensorBluetooth
import tk.glucodata.SuperGattCallback
import tk.glucodata.drivers.ManagedSensorIdentityAdapter

object AnytimeManagedSensorIdentityAdapter : ManagedSensorIdentityAdapter {

    private val STABLE_HEX_SENSOR_ID = Regex("^[0-9A-F]{12,16}$", RegexOption.IGNORE_CASE)
    private val STABLE_MAC_SENSOR_ID = Regex("^(?:[0-9A-F]{2}:){5}[0-9A-F]{2}$", RegexOption.IGNORE_CASE)
    private val NATIVE_SHORT_HEX_ALIAS = Regex("^[0-9A-F]{7,16}$", RegexOption.IGNORE_CASE)

    override fun matchesCallbackId(callbackId: String?, sensorId: String): Boolean {
        val normalized = callbackId?.trim().takeIf { !it.isNullOrEmpty() } ?: return false
        if (normalized.equals(sensorId, ignoreCase = true) ||
            AnytimeConstants.matchesCanonicalOrKnownNativeAlias(normalized, sensorId)
        ) {
            return true
        }
        if (mayBeAnytimeAlias(normalized) || mayBeAnytimeAlias(sensorId)) {
            val normalizedCanonical = resolveCanonicalSensorId(normalized)
                ?: AnytimeConstants.canonicalSensorId(normalized)
            val sensorCanonical = resolveCanonicalSensorId(sensorId)
                ?: AnytimeConstants.canonicalSensorId(sensorId)
            if (normalizedCanonical.isNotBlank() &&
                sensorCanonical.isNotBlank() &&
                normalizedCanonical.equals(sensorCanonical, ignoreCase = true)
            ) {
                return true
            }
        }
        return SensorBluetooth.mygatts().any { callback ->
            val driver = callback as? AnytimeDriver ?: return@any false
            driver.matchesManagedSensorId(normalized) && driver.matchesManagedSensorId(sensorId)
        }
    }

    private fun mayBeAnytimeAlias(sensorId: String?): Boolean {
        val raw = sensorId?.trim().takeIf { !it.isNullOrBlank() } ?: return false
        return AnytimeConstants.isProvisionalSensorId(raw) ||
            AnytimeConstants.isLikelyPersistedSensorName(raw) ||
            AnytimeConstants.resolveFamily(raw).family != AnytimeConstants.Family.UNKNOWN ||
            NATIVE_SHORT_HEX_ALIAS.matches(raw)
    }

    override fun resolveCanonicalSensorId(sensorId: String?): String? {
        val raw = sensorId?.trim().takeIf { !it.isNullOrEmpty() } ?: return null
        if (AnytimeConstants.isProvisionalSensorId(raw)) return raw
        val normalized = AnytimeConstants.canonicalSensorId(raw)
        if (normalized.isEmpty()) return null

        runCatching { AnytimeRegistry.resolveCanonicalSensorId(Applic.app, normalized) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }

        SensorBluetooth.mygatts()
            .firstOrNull { cb ->
                val a = cb as? AnytimeDriver ?: return@firstOrNull false
                a.matchesManagedSensorId(raw)
            }
            ?.SerialNumber
            ?.takeIf { it.isNotBlank() }
            ?.let(AnytimeConstants::canonicalSensorId)
            ?.takeIf { it.isNotEmpty() && !AnytimeConstants.isProvisionalSensorId(it) }
            ?.let { return it }

        return null
    }

    override fun resolveStableStorageSensorId(sensorId: String?): String? {
        val raw = sensorId?.trim().takeIf { !it.isNullOrEmpty() } ?: return null
        resolveCanonicalSensorId(raw)?.takeIf { it.isNotBlank() }?.let { return it }
        val canonical = AnytimeConstants.canonicalSensorId(raw)
        return canonical.takeIf {
            AnytimeConstants.isProvisionalSensorId(it) ||
                STABLE_HEX_SENSOR_ID.matches(raw) ||
                STABLE_MAC_SENSOR_ID.matches(raw)
        }
    }

    override fun resolveNativeSensorName(sensorId: String?): String? {
        val raw = sensorId?.trim().takeIf { !it.isNullOrEmpty() } ?: return null
        val canonical = resolveCanonicalSensorId(raw) ?: return null
        return AnytimeRegistry.findRecord(Applic.app, canonical)?.let { canonical }
    }

    override fun hasPersistedManagedRecord(sensorId: String?): Boolean {
        val normalized = sensorId?.trim().takeIf { !it.isNullOrEmpty() } ?: return false
        return AnytimeRegistry.findRecord(Applic.app, normalized) != null
    }

    override fun resolveCallbackDataptr(sensorId: String?): Long? =
        resolveCanonicalSensorId(sensorId)
            ?.let { AnytimeRegistry.findRecord(Applic.app, it) }
            ?.let { 0L }

    override fun persistedSensorIds(context: Context): List<String> =
        AnytimeRegistry.persistedRecords(context).map { it.sensorId }

    override fun createManagedCallback(context: Context, sensorId: String, dataptr: Long): SuperGattCallback? {
        AnytimeRegistry.createRestoredCallback(context, sensorId, dataptr)?.let { return it }
        val canonical = resolveCanonicalSensorId(sensorId)
            ?.takeIf { it.isNotBlank() && !AnytimeConstants.isProvisionalSensorId(it) }
            ?: return null
        AnytimeRegistry.findRecord(context, canonical) ?: return null
        return AnytimeBleManager(canonical, dataptr).also { it.restoreFromPersistence(context) }
    }

    override fun removePersistedSensor(context: Context, sensorId: String?) {
        AnytimeRegistry.removeSensor(context, sensorId)
    }

    override fun isExternallyManagedBleSensor(sensorId: String?): Boolean =
        resolveCanonicalSensorId(sensorId)?.let { AnytimeRegistry.findRecord(Applic.app, it) } != null

    override fun usesNativeDirectStreamShell(sensorId: String?): Boolean =
        resolveCanonicalSensorId(sensorId)?.let { AnytimeRegistry.findRecord(Applic.app, it) } != null

    override fun hasNativeSensorBacking(sensorId: String?): Boolean? {
        resolveCanonicalSensorId(sensorId) ?: return null
        return true
    }

    override fun shouldUseNativeHistorySync(sensorId: String?): Boolean? {
        resolveCanonicalSensorId(sensorId) ?: return null
        // The driver owns Room storage end-to-end (mirrors MQ semantics).
        return false
    }

    override fun isExpired(sensorId: String?): Boolean {
        val canonical = resolveCanonicalSensorId(sensorId) ?: return false
        SensorBluetooth.mygatts()
            .mapNotNull { it as? AnytimeDriver }
            .firstOrNull { it.matchesManagedSensorId(canonical) }
            ?.let { return it.isSensorExpired() }

        val context = Applic.app ?: return false
        val record = AnytimeRegistry.findRecord(context, canonical) ?: return false
        val startAtMs = AnytimeRegistry.loadSensorStartAt(context, record.sensorId)
        if (startAtMs <= 0L) return false
        return System.currentTimeMillis() >=
            startAtMs + AnytimeProfileResolver.resolve(record.displayName).ratedLifetimeMs()
    }
}
