package tk.glucodata.drivers.icanhealth

import android.content.Context
import tk.glucodata.Applic
import tk.glucodata.Natives
import tk.glucodata.SensorBluetooth
import tk.glucodata.SuperGattCallback
import tk.glucodata.drivers.ManagedSensorIdentityAdapter
import tk.glucodata.drivers.ManagedSensorUiFamily

object ICanHealthManagedSensorIdentityAdapter : ManagedSensorIdentityAdapter {
    override val uiFamily = ManagedSensorUiFamily.ICAN

    private val STABLE_HEX_SENSOR_ID = Regex("^[0-9A-F]{16,32}$", RegexOption.IGNORE_CASE)
    private val STABLE_LETTERED_SENSOR_ID = Regex("^P\\d{9}[A-Z]{3}$", RegexOption.IGNORE_CASE)
    private val STABLE_LT_SENSOR_ID = Regex("^LT\\d{6,}[A-Z]{2,3}$", RegexOption.IGNORE_CASE)

    override fun matchesCallbackId(callbackId: String?, sensorId: String): Boolean =
        run {
            val normalizedCallbackId = callbackId?.trim().takeIf { !it.isNullOrEmpty() } ?: return@run false
            if (normalizedCallbackId.equals(sensorId, ignoreCase = true) ||
                ICanHealthConstants.matchesCanonicalOrKnownNativeAlias(normalizedCallbackId, sensorId)
            ) {
                return@run true
            }
            SensorBluetooth.mygatts().any { callback ->
                val iCan = callback as? ICanHealthBleManager ?: return@any false
                iCan.matchesManagedSensorId(normalizedCallbackId) &&
                    iCan.matchesManagedSensorId(sensorId)
            }
        }

    override fun resolveCanonicalSensorId(sensorId: String?): String? {
        val raw = sensorId?.trim().takeIf { !it.isNullOrEmpty() } ?: return null
        if (ICanHealthConstants.isProvisionalSensorId(raw)) {
            return raw
        }
        val normalized = ICanHealthConstants.canonicalSensorId(raw)
        if (normalized.isEmpty()) {
            return null
        }

        runCatching { ICanHealthRegistry.resolveCanonicalSensorId(Applic.app, normalized) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }

        SensorBluetooth.mygatts()
            .firstOrNull { callback ->
                val iCan = callback as? ICanHealthBleManager ?: return@firstOrNull false
                iCan.matchesManagedSensorId(raw)
            }
            ?.SerialNumber
            ?.takeIf { it.isNotBlank() }
            ?.let(ICanHealthConstants::canonicalSensorId)
            ?.takeIf { it.isNotEmpty() && !ICanHealthConstants.isProvisionalSensorId(it) }
            ?.let { return it }

        SensorBluetooth.mygatts()
            .firstOrNull { callback ->
                val iCan = callback as? ICanHealthBleManager ?: return@firstOrNull false
                val callbackId = iCan.SerialNumber ?: return@firstOrNull false
                iCan.matchesManagedSensorId(normalized) ||
                    ICanHealthConstants.matchesCanonicalOrKnownNativeAlias(callbackId, normalized)
            }
            ?.SerialNumber
            ?.takeIf { it.isNotBlank() }
            ?.let(ICanHealthConstants::canonicalSensorId)
            ?.takeIf { it.isNotEmpty() }
            ?.let { return it }

        return null
    }

    override fun resolveStableStorageSensorId(sensorId: String?): String? {
        val raw = sensorId?.trim().takeIf { !it.isNullOrEmpty() } ?: return null
        resolveCanonicalSensorId(raw)?.takeIf { it.isNotBlank() }?.let { return it }

        val canonical = ICanHealthConstants.canonicalSensorId(raw)
        return canonical.takeIf {
            ICanHealthConstants.isProvisionalSensorId(it) ||
                STABLE_HEX_SENSOR_ID.matches(it) ||
                STABLE_LETTERED_SENSOR_ID.matches(it) ||
                STABLE_LT_SENSOR_ID.matches(it)
        }
    }

    override fun resolveNativeSensorName(sensorId: String?): String? {
        val raw = sensorId?.trim().takeIf { !it.isNullOrEmpty() } ?: return null
        val canonical = resolveCanonicalSensorId(raw) ?: return null
        val record = ICanHealthRegistry.findRecord(Applic.app, canonical) ?: return null

        Natives.activeSensors()
            ?.firstOrNull { record.matchesId(it) }
            ?.takeIf { !it.isNullOrBlank() }
            ?.let { return it }

        val driver = SensorBluetooth.mygatts()
            .firstOrNull { callback ->
                val iCan = callback as? ICanHealthBleManager ?: return@firstOrNull false
                val callbackId = iCan.SerialNumber ?: return@firstOrNull false
                iCan.matchesManagedSensorId(canonical) ||
                    ICanHealthConstants.matchesCanonicalOrKnownNativeAlias(callbackId, canonical)
            } as? ICanHealthBleManager

        if (driver?.hasNativeSensorBacking() == true) {
            runCatching { Natives.lastsensorname() }
                .getOrNull()
                ?.takeIf { record.matchesId(it) }
                ?.let { return it }
        }

        runCatching { Natives.resolveFullSensorName(canonical) }
            .getOrNull()
            ?.trim()
            ?.takeIf { ICanHealthConstants.matchesCanonicalOrKnownNativeAlias(it, canonical) }
            ?.let { return it }

        return canonical.takeIf { ICanHealthConstants.isLikelyPersistedSensorName(it) }
    }

    override fun hasPersistedManagedRecord(sensorId: String?): Boolean {
        val normalized = sensorId?.trim().takeIf { !it.isNullOrEmpty() } ?: return false
        return ICanHealthRegistry.findRecord(Applic.app, normalized) != null
    }

    override fun resolveCallbackDataptr(sensorId: String?): Long? =
        resolveCanonicalSensorId(sensorId)
            ?.let { ICanHealthRegistry.findRecord(Applic.app, it) }
            ?.let { 0L }

    override fun persistedSensorIds(context: Context): List<String> =
        ICanHealthRegistry.persistedRecords(context).map { it.sensorId }

    override fun createManagedCallback(context: Context, sensorId: String, dataptr: Long): SuperGattCallback? {
        ICanHealthRegistry.createRestoredCallback(context, sensorId, dataptr)?.let { return it }
        return null
    }

    override fun removePersistedSensor(context: Context, sensorId: String?) {
        ICanHealthRegistry.removeSensor(context, sensorId)
    }

    override fun isExternallyManagedBleSensor(sensorId: String?): Boolean =
        resolveCanonicalSensorId(sensorId)
            ?.let { ICanHealthRegistry.findRecord(Applic.app, it) } != null

    override fun usesNativeDirectStreamShell(sensorId: String?): Boolean =
        resolveCanonicalSensorId(sensorId)
            ?.let { ICanHealthRegistry.findRecord(Applic.app, it) } != null

    override fun hasNativeSensorBacking(sensorId: String?): Boolean? {
        val canonical = resolveCanonicalSensorId(sensorId) ?: return null
        val record = ICanHealthRegistry.findRecord(Applic.app, canonical) ?: return null
        val driver = SensorBluetooth.mygatts()
            .asSequence()
            .mapNotNull { it as? ICanHealthBleManager }
            .firstOrNull { it.matchesManagedSensorId(record.sensorId) }
        return driver?.hasNativeSensorBacking() ?: true
    }

    override fun shouldUseNativeHistorySync(sensorId: String?): Boolean? {
        val canonical = resolveCanonicalSensorId(sensorId) ?: return null
        val hasPersistedRecord = ICanHealthRegistry.findRecord(Applic.app, canonical) != null
        return !hasPersistedRecord
    }
}
