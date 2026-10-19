package tk.glucodata.drivers.icanhealth

import android.content.Context
import tk.glucodata.Log
import tk.glucodata.Natives
import tk.glucodata.SensorIdentity
import tk.glucodata.SensorBluetooth
import tk.glucodata.SuperGattCallback

object ICanHealthRegistry {
    private const val TAG = ICanHealthConstants.TAG
    private const val PREFS_NAME = "tk.glucodata_preferences"

    data class SensorRecord(
        val sensorId: String,
        val address: String,
        val displayName: String,
    ) {
        val nativeAlias: String?
            get() = ICanHealthConstants.nativeShortSensorAlias(sensorId)

        fun matchesId(id: String?): Boolean {
            return ICanHealthConstants.matchesCanonicalOrKnownNativeAlias(sensorId, id)
        }
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun parseRecord(entry: String?): SensorRecord? {
        if (entry.isNullOrBlank()) return null
        val parts = entry.split('|')
        if (parts.isEmpty()) return null
        val address = parts.getOrNull(1)?.trim().orEmpty()
        val displayName = parts.getOrNull(2)?.trim().takeUnless { it.isNullOrEmpty() }
            ?: ICanHealthConstants.DEFAULT_DISPLAY_NAME
        val sensorId = ICanHealthConstants.normalizePersistedSensorId(parts[0], address, displayName)
        if (!ICanHealthConstants.isLikelyPersistedSensorName(sensorId)) return null
        return SensorRecord(sensorId = sensorId, address = address, displayName = displayName)
    }

    private fun encodeRecord(record: SensorRecord): String =
        "${record.sensorId}|${record.address}|${record.displayName}"

    private fun readRecords(context: Context): LinkedHashSet<SensorRecord> {
        val stored = try {
            prefs(context).getStringSet(ICanHealthConstants.PREF_SENSORS_KEY, emptySet())
        } catch (t: Throwable) {
            Log.stack(TAG, "readRecords", t)
            emptySet()
        }.orEmpty()
        val records = LinkedHashSet<SensorRecord>()
        for (entry in stored) {
            parseRecord(entry)?.let(records::add)
        }
        return records
    }

    private fun writeRecords(context: Context, records: Collection<SensorRecord>) {
        val encoded = LinkedHashSet<String>()
        records.forEach { encoded.add(encodeRecord(it)) }
        prefs(context).edit()
            .putStringSet(ICanHealthConstants.PREF_SENSORS_KEY, encoded)
            .commit()
        SensorIdentity.invalidateCaches()
    }

    @JvmStatic
    fun findRecord(context: Context?, sensorId: String?): SensorRecord? {
        if (context == null || sensorId.isNullOrBlank()) return null
        return readRecords(context).firstOrNull { it.matchesId(sensorId) }
    }

    @JvmStatic
    fun resolveCanonicalSensorId(context: Context?, sensorId: String?): String? {
        return findRecord(context, sensorId)?.sensorId
    }

    @JvmStatic
    fun persistedRecords(context: Context?): List<SensorRecord> {
        if (context == null) return emptyList()
        return readRecords(context).toList()
    }

    private fun resolvePersistedAuthUserId(context: Context, sensorId: String): String? {
        val prefs = prefs(context)
        val perSensor = ICanHealthConstants.normalizeConfiguredAuthUserId(
            prefs.getString("${ICanHealthConstants.PREF_AUTH_USER_ID_PREFIX}$sensorId", null)
        )
        if (perSensor.isNotEmpty()) return perSensor
        val global = ICanHealthConstants.normalizeConfiguredAuthUserId(
            prefs.getString(ICanHealthConstants.PREF_AUTH_USER_ID_GLOBAL, null)
        )
        return global.takeIf { it.isNotEmpty() }
    }

    private fun configureCallback(
        context: Context,
        callback: ICanHealthBleManager,
        sensorId: String,
        address: String,
        onboardingDeviceSn: String?,
        authUserId: String?,
        configuredAesKey: String?,
    ) {
        callback.mActiveDeviceAddress = address.takeIf { it.isNotBlank() }
        callback.setConfiguredAesKeyFromASCII(
            ICanHealthConstants.resolveConfiguredAesKey(configuredAesKey),
            explicit = configuredAesKey != null && configuredAesKey.length == ICanHealthConstants.AES_BLOCK_SIZE
        )
        callback.setOnboardingDeviceSn(onboardingDeviceSn)
        callback.setConfiguredAuthUserId(authUserId ?: resolvePersistedAuthUserId(context, sensorId))
    }

    @JvmStatic
    fun createRestoredCallback(context: Context?, sensorId: String, dataptr: Long): SuperGattCallback? {
        if (context == null) return null
        val record = findRecord(context, sensorId) ?: return null
        val callback = ICanHealthBleManager(record.sensorId, dataptr)
        val prefs = prefs(context)
        val onboardingDeviceSn = prefs.getString("${ICanHealthConstants.PREF_DEVICE_SN_PREFIX}${record.sensorId}", null)
        configureCallback(
            context = context,
            callback = callback,
            sensorId = record.sensorId,
            address = record.address,
            onboardingDeviceSn = onboardingDeviceSn,
            authUserId = null,
            configuredAesKey = prefs.getString("${ICanHealthConstants.PREF_AES_KEY_PREFIX}${record.sensorId}", null)
        )
        return callback
    }

    @JvmStatic
    fun addSensor(
        context: Context,
        displayName: String?,
        address: String?,
        aesKeyAscii: String?,
        onboardingDeviceSnOrCode: String?,
        authUserId: String?,
    ): String? {
        val normalizedAddress = address?.trim().orEmpty()
        val normalizedOnboardingSn = ICanHealthConstants.normalizeOnboardingDeviceSn(onboardingDeviceSnOrCode)
        if (normalizedAddress.isEmpty() && normalizedOnboardingSn.isEmpty()) {
            Log.w(TAG, "addSensor: missing address and onboarding SN")
            return null
        }
        val safeDisplayName = displayName?.trim().takeUnless { it.isNullOrEmpty() }
            ?: ICanHealthConstants.DEFAULT_DISPLAY_NAME
        val sensorId = ICanHealthConstants.deriveInitialSensorId(safeDisplayName, normalizedAddress, normalizedOnboardingSn)
        val normalizedAuthUserId = ICanHealthConstants.normalizeConfiguredAuthUserId(authUserId)
        val explicitAesKey = aesKeyAscii?.trim()?.takeIf { it.length == ICanHealthConstants.AES_BLOCK_SIZE }

        val prefs = prefs(context)
        val updated = LinkedHashSet<SensorRecord>()
        readRecords(context).forEach { record ->
            val matchesAddress = normalizedAddress.isNotEmpty() && normalizedAddress.equals(record.address, ignoreCase = true)
            if (record.sensorId != sensorId && !matchesAddress) {
                updated.add(record)
            }
        }
        updated.add(SensorRecord(sensorId = sensorId, address = normalizedAddress, displayName = safeDisplayName))
        val editor = prefs.edit()
        writeRecords(context, updated)
        if (normalizedOnboardingSn.isNotEmpty()) {
            editor.putString("${ICanHealthConstants.PREF_DEVICE_SN_PREFIX}$sensorId", normalizedOnboardingSn)
        }
        if (explicitAesKey != null) {
            editor.putString("${ICanHealthConstants.PREF_AES_KEY_PREFIX}$sensorId", explicitAesKey)
        }
        if (authUserId != null) {
            if (normalizedAuthUserId.isNotEmpty()) {
                editor.putString("${ICanHealthConstants.PREF_AUTH_USER_ID_PREFIX}$sensorId", normalizedAuthUserId)
                    .putString(ICanHealthConstants.PREF_AUTH_USER_ID_GLOBAL, normalizedAuthUserId)
            } else {
                editor.remove("${ICanHealthConstants.PREF_AUTH_USER_ID_PREFIX}$sensorId")
            }
        }
        editor.commit()

        val blue = SensorBluetooth.blueone ?: return sensorId
        val existing = SensorBluetooth.gattcallbacks.firstOrNull { callback ->
            val iCan = callback as? ICanHealthBleManager ?: return@firstOrNull false
            SensorIdentity.matches(iCan.SerialNumber, sensorId) ||
                iCan.matchesManagedSensorId(sensorId)
        }
        val callback = (existing as? ICanHealthBleManager) ?: ICanHealthBleManager(sensorId, 0L).also {
            SensorBluetooth.gattcallbacks.add(it)
            Natives.setmaxsensors(SensorBluetooth.gattcallbacks.size)
        }
        configureCallback(
            context = context,
            callback = callback,
            sensorId = sensorId,
            address = normalizedAddress,
            onboardingDeviceSn = normalizedOnboardingSn,
            authUserId = normalizedAuthUserId.takeIf { it.isNotEmpty() },
            configuredAesKey = explicitAesKey
        )
        if (SensorBluetooth.blueone === blue) {
            callback.connectDevice(0)
        }
        return sensorId
    }

    @JvmStatic
    fun removeSensor(context: Context?, sensorId: String?) {
        if (context == null || sensorId.isNullOrBlank()) return
        val record = findRecord(context, sensorId)
        val canonicalId = record?.sensorId ?: ICanHealthConstants.canonicalSensorId(sensorId)
        val updated = LinkedHashSet<SensorRecord>()
        readRecords(context).forEach { existing ->
            if (!existing.matchesId(sensorId) && !existing.matchesId(canonicalId)) {
                updated.add(existing)
            }
        }
        writeRecords(context, updated)

        if (canonicalId.isBlank()) {
            return
        }
        prefs(context).edit()
            .remove("${ICanHealthConstants.PREF_AES_KEY_PREFIX}$canonicalId")
            .remove("${ICanHealthConstants.PREF_DEVICE_SN_PREFIX}$canonicalId")
            .remove("${ICanHealthConstants.PREF_AUTH_USER_ID_PREFIX}$canonicalId")
            .remove("${ICanHealthConstants.PREF_RECOVERED_USER_ID_PREFIX}$canonicalId")
            .remove("${ICanHealthConstants.PREF_AUTH_BYPASS_UNTIL_PREFIX}$canonicalId")
            .remove("${ICanHealthConstants.PREF_HISTORY_EDGE_SEQUENCE_PREFIX}$canonicalId")
            .remove("${ICanHealthConstants.PREF_HISTORY_EDGE_TIMESTAMP_PREFIX}$canonicalId")
            .commit()
    }

    @JvmStatic
    fun promoteSensorIdentity(
        context: Context?,
        oldSensorId: String,
        newSensorId: String,
        address: String?,
        displayName: String?,
    ) {
        if (context == null || newSensorId.isBlank()) return
        val normalizedNewId = ICanHealthConstants.normalizePersistedSensorId(newSensorId, address, displayName)
        val normalizedOldId = ICanHealthConstants.canonicalSensorId(oldSensorId)
        val updated = LinkedHashSet<SensorRecord>()
        var matchedRecord: SensorRecord? = null
        readRecords(context).forEach { record ->
            val sameAddress = address?.isNotBlank() == true && address.equals(record.address, ignoreCase = true)
            if (record.sensorId == normalizedOldId || record.sensorId == normalizedNewId || sameAddress) {
                matchedRecord = record
            } else {
                updated.add(record)
            }
        }
        val promotedRecord = SensorRecord(
            sensorId = normalizedNewId,
            address = address?.trim().orEmpty().ifEmpty { matchedRecord?.address.orEmpty() },
            displayName = displayName?.trim().takeUnless { it.isNullOrEmpty() }
                ?: matchedRecord?.displayName
                ?: ICanHealthConstants.DEFAULT_DISPLAY_NAME
        )
        updated.add(promotedRecord)
        writeRecords(context, updated)

        val prefs = prefs(context)
        val editor = prefs.edit()
        listOf(
            ICanHealthConstants.PREF_AES_KEY_PREFIX,
            ICanHealthConstants.PREF_DEVICE_SN_PREFIX,
            ICanHealthConstants.PREF_AUTH_USER_ID_PREFIX,
            ICanHealthConstants.PREF_RECOVERED_USER_ID_PREFIX,
            ICanHealthConstants.PREF_AUTH_BYPASS_UNTIL_PREFIX,
        ).forEach { prefix ->
            val oldKey = "$prefix$normalizedOldId"
            val newKey = "$prefix$normalizedNewId"
            if (oldKey != newKey && prefs.contains(oldKey) && !prefs.contains(newKey)) {
                when (val value = prefs.all[oldKey]) {
                    is String -> editor.putString(newKey, value)
                    is Long -> editor.putLong(newKey, value)
                    is Int -> editor.putInt(newKey, value)
                    is Boolean -> editor.putBoolean(newKey, value)
                    is Float -> editor.putFloat(newKey, value)
                }
            }
            if (oldKey != newKey) {
                editor.remove(oldKey)
            }
        }
        editor.commit()
    }
}
