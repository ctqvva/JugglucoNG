// MQManagedSensorIdentityAdapter.kt — Vendor adapter for the managed identity seam.

package tk.glucodata.drivers.mq

import android.content.Context
import tk.glucodata.Applic
import tk.glucodata.SensorBluetooth
import tk.glucodata.SuperGattCallback
import tk.glucodata.drivers.ManagedSensorIdentityAdapter

object MQManagedSensorIdentityAdapter : ManagedSensorIdentityAdapter {
    private val STABLE_HEX_SENSOR_ID = Regex("^[0-9A-F]{12,16}$", RegexOption.IGNORE_CASE)
    private val STABLE_MAC_SENSOR_ID = Regex("^(?:[0-9A-F]{2}:){5}[0-9A-F]{2}$", RegexOption.IGNORE_CASE)

    override fun matchesCallbackId(callbackId: String?, sensorId: String): Boolean {
        val normalized = callbackId?.trim().takeIf { !it.isNullOrEmpty() } ?: return false
        if (normalized.equals(sensorId, ignoreCase = true) ||
            MQConstants.matchesCanonicalOrKnownNativeAlias(normalized, sensorId)
        ) {
            return true
        }
        return SensorBluetooth.mygatts().any { callback ->
            val mq = callback as? MQDriver ?: return@any false
            mq.matchesManagedSensorId(normalized) && mq.matchesManagedSensorId(sensorId)
        }
    }

    override fun resolveCanonicalSensorId(sensorId: String?): String? {
        val raw = sensorId?.trim().takeIf { !it.isNullOrEmpty() } ?: return null
        if (MQConstants.isProvisionalSensorId(raw)) return raw
        val normalized = MQConstants.canonicalSensorId(raw)
        if (normalized.isEmpty()) return null

        runCatching { MQRegistry.resolveCanonicalSensorId(Applic.app, normalized) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }

        SensorBluetooth.mygatts()
            .firstOrNull { cb ->
                val mq = cb as? MQDriver ?: return@firstOrNull false
                mq.matchesManagedSensorId(raw)
            }
            ?.SerialNumber
            ?.takeIf { it.isNotBlank() }
            ?.let(MQConstants::canonicalSensorId)
            ?.takeIf { it.isNotEmpty() && !MQConstants.isProvisionalSensorId(it) }
            ?.let { return it }

        return null
    }

    override fun resolveStableStorageSensorId(sensorId: String?): String? {
        val raw = sensorId?.trim().takeIf { !it.isNullOrEmpty() } ?: return null
        resolveCanonicalSensorId(raw)?.takeIf { it.isNotBlank() }?.let { return it }

        val canonical = MQConstants.canonicalSensorId(raw)
        return canonical.takeIf {
            MQConstants.isProvisionalSensorId(it) ||
                MQConstants.isFollowerSensorId(it) ||
                STABLE_HEX_SENSOR_ID.matches(raw) ||
                STABLE_MAC_SENSOR_ID.matches(raw)
        }
    }

    override fun resolveNativeSensorName(sensorId: String?): String? {
        val raw = sensorId?.trim().takeIf { !it.isNullOrEmpty() } ?: return null
        val canonical = resolveCanonicalSensorId(raw) ?: return null
        val record = MQRegistry.findRecord(Applic.app, canonical) ?: return null
        return canonical.takeIf { record.mode == MQRegistry.SensorRecordMode.LOCAL }
    }

    override fun hasPersistedManagedRecord(sensorId: String?): Boolean {
        val normalized = sensorId?.trim().takeIf { !it.isNullOrEmpty() } ?: return false
        return MQRegistry.findRecord(Applic.app, normalized) != null
    }

    override fun resolveCallbackDataptr(sensorId: String?): Long? =
        resolveCanonicalSensorId(sensorId)
            ?.let { MQRegistry.findRecord(Applic.app, it) }
            ?.takeIf { it.mode == MQRegistry.SensorRecordMode.LOCAL }
            ?.let { 0L }

    override fun persistedSensorIds(context: Context): List<String> =
        MQRegistry.persistedRecords(context).map { it.sensorId }

    override fun createManagedCallback(context: Context, sensorId: String, dataptr: Long): SuperGattCallback? {
        MQRegistry.createRestoredCallback(context, sensorId, dataptr)?.let { return it }

        val canonical = resolveCanonicalSensorId(sensorId)
            ?.takeIf { it.isNotBlank() && !MQConstants.isProvisionalSensorId(it) }
            ?: return null
        val record = MQRegistry.findRecord(context, canonical) ?: return null
        return when (record.mode) {
            MQRegistry.SensorRecordMode.LOCAL ->
                MQBleManager(canonical, dataptr).also { it.restoreFromPersistence(context) }

            MQRegistry.SensorRecordMode.FOLLOWER ->
                MQFollowerManager(
                    serial = canonical,
                    followerAccount = record.followerAccount,
                    initialDisplayName = record.displayName,
                    dataptr = dataptr,
                )
        }
    }

    override fun removePersistedSensor(context: Context, sensorId: String?) {
        MQRegistry.removeSensor(context, sensorId)
    }

    override fun isExternallyManagedBleSensor(sensorId: String?): Boolean =
        resolveCanonicalSensorId(sensorId)
            ?.let { MQRegistry.findRecord(Applic.app, it) }
            ?.mode == MQRegistry.SensorRecordMode.LOCAL

    override fun usesNativeDirectStreamShell(sensorId: String?): Boolean =
        resolveCanonicalSensorId(sensorId)
            ?.let { MQRegistry.findRecord(Applic.app, it) }
            ?.mode == MQRegistry.SensorRecordMode.LOCAL

    override fun hasNativeSensorBacking(sensorId: String?): Boolean? {
        val canonical = resolveCanonicalSensorId(sensorId) ?: return null
        val record = MQRegistry.findRecord(Applic.app, canonical) ?: return null
        return !record.isFollower
    }

    override fun shouldUseNativeHistorySync(sensorId: String?): Boolean? {
        val canonical = resolveCanonicalSensorId(sensorId) ?: return null
        val record = MQRegistry.findRecord(Applic.app, canonical) ?: return null
        return if (record.isFollower) {
            false
        } else {
            // Match iCanHealth semantics: persisted managed records own Room
            // history; the native path should stay out of the way.
            false
        }
    }

    override fun isExpired(sensorId: String?): Boolean {
        val canonical = resolveCanonicalSensorId(sensorId) ?: return false
        SensorBluetooth.mygatts()
            .mapNotNull { it as? MQDriver }
            .firstOrNull { it.matchesManagedSensorId(canonical) }
            ?.let { return it.isSensorExpired() }

        val context = Applic.app ?: return false
        val record = MQRegistry.findRecord(context, canonical) ?: return false
        val startAtMs = MQRegistry.loadSensorStartAt(context, record.sensorId)
        if (startAtMs <= 0L) return false
        return System.currentTimeMillis() >= startAtMs + MQProfileResolver.resolve().ratedLifetimeMs()
    }
}
