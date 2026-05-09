// AnytimeRegistry.kt — SharedPreferences-backed persistence for Anytime sensors.
//
// Records:
//   anytime_sensors          → "id|address|displayName" newline-separated set
//   anytime_qr_<id>          → raw QR string (for re-decode after upgrade)
//   anytime_k_<id>           → factory K (Float bits as Int)
//   anytime_r_<id>           → factory R (Float bits as Int)
//   anytime_lifetime_<id>    → days
//   anytime_last_id_<id>     → highest seen glucose ID
//   anytime_started_at_<id>  → session start ms (epoch)
//   anytime_warmup_at_<id>   → warmup start ms (== session start, kept separate
//                              for compatibility with the official UI semantics)
//   anytime_voltage_<id>     → 0 / 1 (CT4 voltage flag)
//   anytime_device_name_<id> → advertised name (for family resolve after restart)
//   anytime_tx_version_<id>  → firmware version string (e.g. "V1300")
//   anytime_bound_<id>       → 1/0 ("known bound from previous session")

package tk.glucodata.drivers.anytime

import android.content.Context
import android.content.SharedPreferences
import tk.glucodata.Log
import tk.glucodata.Natives
import tk.glucodata.SensorBluetooth
import tk.glucodata.SensorIdentity
import tk.glucodata.SuperGattCallback
import tk.glucodata.drivers.ManagedSensorUiSignals

object AnytimeRegistry {
    private const val TAG = AnytimeConstants.TAG
    private const val PREFS_NAME = "tk.glucodata_preferences"

    data class SensorRecord(
        val sensorId: String,
        val address: String,
        val displayName: String,
    ) {
        fun matchesId(id: String?): Boolean =
            AnytimeConstants.matchesCanonicalOrKnownNativeAlias(sensorId, id)
    }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ---- Record set ----

    @JvmStatic
    fun ensureSensorRecord(
        context: Context,
        sensorId: String,
        address: String,
        displayName: String,
    ) {
        val canonical = AnytimeConstants.canonicalSensorId(sensorId).ifEmpty { sensorId }
        val records = persistedRecords(context).toMutableList()
        val existing = records.indexOfFirst { it.matchesId(canonical) }
        val existingRecord = existing.takeIf { it >= 0 }?.let { records[it] }
        val effectiveAddress = address.ifBlank {
            existingRecord?.address?.takeIf { it.isNotBlank() }
                ?: AnytimeConstants.macAddressFromSensorId(canonical).orEmpty()
        }
        val effectiveName = displayName.ifBlank { existingRecord?.displayName ?: canonical }
        val record = SensorRecord(canonical, effectiveAddress, effectiveName)
        if (existing >= 0) records[existing] = record else records.add(record)
        writeRecords(context, records)
    }

    @JvmStatic
    fun persistedRecords(context: Context): List<SensorRecord> {
        val raw = prefs(context).getStringSet(AnytimeConstants.PREF_SENSORS_KEY, emptySet()) ?: return emptyList()
        return raw.mapNotNull { line ->
            val parts = line.split('|')
            if (parts.size < 3) return@mapNotNull null
            val canonical = AnytimeConstants.canonicalSensorId(parts[0]).ifEmpty { parts[0] }
            val address = parts[1].ifBlank { AnytimeConstants.macAddressFromSensorId(canonical).orEmpty() }
            SensorRecord(canonical, address, parts[2])
        }
    }

    @JvmStatic
    fun findRecord(context: Context?, sensorId: String?): SensorRecord? {
        val ctx = context ?: return null
        val id = sensorId?.trim().takeIf { !it.isNullOrBlank() } ?: return null
        return persistedRecords(ctx).firstOrNull { it.matchesId(id) }
    }

    @JvmStatic
    fun resolveCanonicalSensorId(context: Context?, sensorId: String?): String? {
        val rec = findRecord(context, sensorId) ?: return null
        return rec.sensorId
    }

    @JvmStatic
    fun removeSensor(context: Context, sensorId: String?) {
        val id = sensorId?.trim() ?: return
        val canonical = AnytimeConstants.canonicalSensorId(id).ifEmpty { id }
        val remaining = persistedRecords(context).filter { !it.matchesId(canonical) }
        writeRecords(context, remaining)
        clearPerSensorState(context, canonical)
    }

    private fun writeRecords(context: Context, records: List<SensorRecord>) {
        val set = records.map { "${it.sensorId}|${it.address}|${it.displayName}" }.toSet()
        prefs(context).edit().putStringSet(AnytimeConstants.PREF_SENSORS_KEY, set).apply()
    }

    // ---- Per-sensor state ----

    @JvmStatic fun loadQrContent(c: Context, id: String): String =
        prefs(c).getString(AnytimeConstants.PREF_QR_CONTENT_PREFIX + id, null).orEmpty()
    @JvmStatic fun saveQrContent(c: Context, id: String, qr: String) {
        prefs(c).edit().putString(AnytimeConstants.PREF_QR_CONTENT_PREFIX + id, qr).apply()
    }

    @JvmStatic fun loadKValue(c: Context, id: String): Float =
        prefs(c).getFloat(AnytimeConstants.PREF_K_PREFIX + id, 0f)
    @JvmStatic fun saveKValue(c: Context, id: String, v: Float) {
        prefs(c).edit().putFloat(AnytimeConstants.PREF_K_PREFIX + id, v).apply()
    }

    @JvmStatic fun loadRValue(c: Context, id: String): Float =
        prefs(c).getFloat(AnytimeConstants.PREF_R_PREFIX + id, 0f)
    @JvmStatic fun saveRValue(c: Context, id: String, v: Float) {
        prefs(c).edit().putFloat(AnytimeConstants.PREF_R_PREFIX + id, v).apply()
    }

    @JvmStatic fun loadLifetimeDays(c: Context, id: String): Int =
        prefs(c).getInt(AnytimeConstants.PREF_LIFETIME_PREFIX + id, AnytimeConstants.DEFAULT_RATED_LIFETIME_DAYS)
    @JvmStatic fun saveLifetimeDays(c: Context, id: String, days: Int) {
        prefs(c).edit().putInt(AnytimeConstants.PREF_LIFETIME_PREFIX + id, days).apply()
    }

    @JvmStatic fun loadLastGlucoseId(c: Context, id: String): Int =
        prefs(c).getInt(AnytimeConstants.PREF_LAST_GLUCOSE_ID_PREFIX + id, -1)
    @JvmStatic fun saveLastGlucoseId(c: Context, id: String, gid: Int) {
        prefs(c).edit().putInt(AnytimeConstants.PREF_LAST_GLUCOSE_ID_PREFIX + id, gid).apply()
    }

    @JvmStatic fun loadSensorStartAt(c: Context, id: String): Long =
        prefs(c).getLong(AnytimeConstants.PREF_SENSOR_START_AT_PREFIX + id, 0L)
    @JvmStatic fun saveSensorStartAt(c: Context, id: String, ms: Long) {
        prefs(c).edit().putLong(AnytimeConstants.PREF_SENSOR_START_AT_PREFIX + id, ms).apply()
    }

    @JvmStatic fun loadWarmupStartedAt(c: Context, id: String): Long =
        prefs(c).getLong(AnytimeConstants.PREF_WARMUP_STARTED_AT_PREFIX + id, 0L)
    @JvmStatic fun saveWarmupStartedAt(c: Context, id: String, ms: Long) {
        prefs(c).edit().putLong(AnytimeConstants.PREF_WARMUP_STARTED_AT_PREFIX + id, ms).apply()
    }

    @JvmStatic fun loadVoltageFlag(c: Context, id: String): Int =
        prefs(c).getInt(AnytimeConstants.PREF_VOLTAGE_PREFIX + id, 0)
    @JvmStatic fun saveVoltageFlag(c: Context, id: String, v: Int) {
        prefs(c).edit().putInt(AnytimeConstants.PREF_VOLTAGE_PREFIX + id, v).apply()
    }

    @JvmStatic fun loadDeviceName(c: Context, id: String): String =
        prefs(c).getString(AnytimeConstants.PREF_DEVICE_NAME_PREFIX + id, null).orEmpty()
    @JvmStatic fun saveDeviceName(c: Context, id: String, name: String) {
        prefs(c).edit().putString(AnytimeConstants.PREF_DEVICE_NAME_PREFIX + id, name).apply()
    }

    @JvmStatic fun loadTransmitterVersion(c: Context, id: String): String =
        prefs(c).getString(AnytimeConstants.PREF_TRANSMITTER_VERSION_PREFIX + id, null).orEmpty()
    @JvmStatic fun saveTransmitterVersion(c: Context, id: String, v: String) {
        prefs(c).edit().putString(AnytimeConstants.PREF_TRANSMITTER_VERSION_PREFIX + id, v).apply()
    }

    @JvmStatic fun loadBound(c: Context, id: String): Boolean =
        prefs(c).getBoolean(AnytimeConstants.PREF_BOUND_PREFIX + id, false)
    @JvmStatic fun saveBound(c: Context, id: String, bound: Boolean) {
        prefs(c).edit().putBoolean(AnytimeConstants.PREF_BOUND_PREFIX + id, bound).apply()
    }

    private fun clearPerSensorState(context: Context, sensorId: String) {
        prefs(context).edit().apply {
            remove(AnytimeConstants.PREF_QR_CONTENT_PREFIX + sensorId)
            remove(AnytimeConstants.PREF_K_PREFIX + sensorId)
            remove(AnytimeConstants.PREF_R_PREFIX + sensorId)
            remove(AnytimeConstants.PREF_LIFETIME_PREFIX + sensorId)
            remove(AnytimeConstants.PREF_LAST_GLUCOSE_ID_PREFIX + sensorId)
            remove(AnytimeConstants.PREF_SENSOR_START_AT_PREFIX + sensorId)
            remove(AnytimeConstants.PREF_WARMUP_STARTED_AT_PREFIX + sensorId)
            remove(AnytimeConstants.PREF_VOLTAGE_PREFIX + sensorId)
            remove(AnytimeConstants.PREF_DEVICE_NAME_PREFIX + sensorId)
            remove(AnytimeConstants.PREF_TRANSMITTER_VERSION_PREFIX + sensorId)
            remove(AnytimeConstants.PREF_BOUND_PREFIX + sensorId)
        }.apply()
    }

    // ---- Restore (called by the identity adapter) ----

    @JvmStatic
    fun createRestoredCallback(
        context: Context,
        sensorId: String,
        dataptr: Long,
    ): SuperGattCallback? {
        val canonical = AnytimeConstants.canonicalSensorId(sensorId).ifEmpty { sensorId }
        val record = findRecord(context, canonical) ?: return null
        return runCatching {
            AnytimeBleManager(record.sensorId, dataptr).also {
                it.mActiveDeviceAddress = record.address.takeIf { address -> address.isNotBlank() }
                it.restoreFromPersistence(context)
            }
        }.onFailure { Log.stack(TAG, "createRestoredCallback", it) }.getOrNull()
    }

    // ---- Wizard-facing API ----

    /**
     * Register a new Anytime sensor. Returns the canonical sensor id, or null
     * if neither an address nor a QR string was supplied.
     *
     * If [qrCodeContent] is set, we run the QR through the algorithm SDK and
     * persist K/R + chemistry IDs for later use during the BLE handshake.
     *
     * If [connectNow] is true, immediately schedule a BLE connect attempt.
     */
    @JvmStatic
    @JvmOverloads
    fun addSensor(
        context: Context,
        displayName: String?,
        address: String?,
        deviceName: String? = null,
        qrCodeContent: String? = null,
        connectNow: Boolean = true,
    ): String? {
        val normalizedAddress = address?.trim().orEmpty()
        val normalizedQr = qrCodeContent?.trim().orEmpty()
        if (normalizedAddress.isEmpty() && normalizedQr.isEmpty() && deviceName.isNullOrBlank()) {
            Log.w(TAG, "addSensor: no address, QR, or device name provided")
            return null
        }
        val safeName = displayName?.trim().takeUnless { it.isNullOrEmpty() }
            ?: deviceName?.trim().takeUnless { it.isNullOrEmpty() }
            ?: AnytimeConstants.DEFAULT_DISPLAY_NAME
        val sensorId = AnytimeConstants.deriveInitialSensorId(safeName, normalizedAddress)

        ensureSensorRecord(
            context = context,
            sensorId = sensorId,
            address = normalizedAddress,
            displayName = safeName,
        )

        deviceName?.takeIf { it.isNotBlank() }?.let { saveDeviceName(context, sensorId, it) }

        if (normalizedQr.isNotEmpty()) {
            saveQrContent(context, sensorId, normalizedQr)
            val parsed = AnytimeAlgorithm.decodeQr(normalizedQr)
            if (parsed != null) {
                saveKValue(context, sensorId, parsed.k)
                saveRValue(context, sensorId, parsed.r)
                saveLifetimeDays(context, sensorId, parsed.lifeTime)
                saveVoltageFlag(context, sensorId, parsed.voltageFlag)
                Log.i(TAG, "Anytime sensor $sensorId QR decoded: K=${parsed.k} R=${parsed.r} life=${parsed.lifeTime}d")
            } else {
                Log.w(TAG, "Anytime sensor $sensorId QR decode failed: $normalizedQr")
            }
        }

        if (connectNow) connectSensor(context, sensorId)
        ManagedSensorUiSignals.markDeviceListDirty()
        return sensorId
    }

    /** Spin up a BLE callback for the given sensor and request a connect. */
    @JvmStatic
    fun connectSensor(context: Context, sensorId: String) {
        val blue = SensorBluetooth.blueone ?: return
        val record = findRecord(context, sensorId) ?: return
        val existing = SensorBluetooth.gattcallbacks.firstOrNull { cb ->
            val driver = cb as? AnytimeDriver ?: return@firstOrNull false
            SensorIdentity.matches(cb.SerialNumber, sensorId) ||
                driver.matchesManagedSensorId(sensorId)
        }
        val callback = existing ?: createRestoredCallback(context, record.sensorId, 0L)?.also {
            SensorBluetooth.gattcallbacks.add(it)
            runCatching { Natives.setmaxsensors(SensorBluetooth.gattcallbacks.size) }
        } ?: return
        if (callback is AnytimeBleManager) {
            callback.mActiveDeviceAddress = record.address.takeIf { it.isNotBlank() }
            callback.restoreFromPersistence(context)
        }
        runCatching { SensorBluetooth.ensureCurrentSensorSelection() }
        if (SensorBluetooth.blueone === blue) {
            callback.connectDevice(0)
        }
        ManagedSensorUiSignals.markDeviceListDirty()
    }
}
