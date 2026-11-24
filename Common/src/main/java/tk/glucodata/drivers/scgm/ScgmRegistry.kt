package tk.glucodata.drivers.scgm

import android.content.Context
import android.content.SharedPreferences
import java.util.Locale
import tk.glucodata.Log
import tk.glucodata.Natives
import tk.glucodata.SensorBluetooth

object ScgmRegistry {
    private const val TAG = "ScgmRegistry"
    private const val PREFS_NAME = "tk.glucodata_scgm_prefs"
    private const val KEY_SENSOR_ID = "scgm_sensor_id"
    private const val KEY_DEVICE_CODE = "scgm_device_code"
    private const val KEY_BT_ADDRESS = "scgm_bt_address"
    private const val KEY_K = "scgm_conv_num"
    private const val KEY_B = "scgm_verify_val"
    private const val KEY_THRESHOLD = "scgm_low_current_na"
    private const val KEY_API_TOKEN = "scgm_api_token"
    private const val KEY_USE_AT = "scgm_use_at_ms"
    private const val KEY_BOOT_AT_SEC = "scgm_boot_at_sec"
    private const val KEY_LAST_PID = "scgm_last_pid"
    private const val KEY_LAST_TIME_MS = "scgm_last_time_ms"
    private const val KEY_NEXT_BROKE = "scgm_next_broke_position"
    private const val KEY_FIRMWARE = "scgm_firmware"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun canonicalSensorId(deviceCode: String?): String {
        val raw = deviceCode?.trim().orEmpty()
        if (raw.isEmpty()) return ""
        val normalized = raw.uppercase(Locale.ROOT)
        return if (normalized.startsWith(ScgmConstants.SENSOR_ID_PREFIX)) normalized
        else ScgmConstants.SENSOR_ID_PREFIX + normalized
    }

    fun isValidDeviceCode(input: String?): Boolean {
        val raw = input?.trim().orEmpty()
        return raw.length in 4..40 && raw.all { it.isDigit() || it.lowercase(Locale.ROOT) in "abcdef" }
    }

    fun getSensorId(context: Context): String = prefs(context).getString(KEY_SENSOR_ID, "") ?: ""

    fun getDeviceCode(context: Context): String = prefs(context).getString(KEY_DEVICE_CODE, "") ?: ""

    fun getLastBtAddress(context: Context): String = prefs(context).getString(KEY_BT_ADDRESS, "") ?: ""

    fun setLastBtAddress(context: Context, address: String) {
        prefs(context).edit().putString(KEY_BT_ADDRESS, address).apply()
    }

    fun getCalibration(context: Context): ScgmCalibration {
        val p = prefs(context)
        val k = p.getString(KEY_K, null)?.toDoubleOrNull() ?: ScgmConstants.DEFAULT_K
        val b = p.getString(KEY_B, null)?.toDoubleOrNull() ?: ScgmConstants.DEFAULT_B
        val threshold = p.getString(KEY_THRESHOLD, null)?.toDoubleOrNull()
            ?: ScgmConstants.DEFAULT_LOW_CURRENT_NA
        return ScgmCalibration(k, b, threshold)
    }

    fun setCalibration(context: Context, calibration: ScgmCalibration) {
        prefs(context).edit()
            .putString(KEY_K, calibration.k.toString())
            .putString(KEY_B, calibration.b.toString())
            .putString(KEY_THRESHOLD, calibration.lowCurrentThresholdNa.toString())
            .apply()
    }

    fun getApiToken(context: Context): String = prefs(context).getString(KEY_API_TOKEN, "") ?: ""

    fun setApiToken(context: Context, token: String) {
        prefs(context).edit().putString(KEY_API_TOKEN, token.trim()).apply()
    }

    fun getUseAtMs(context: Context): Long = prefs(context).getLong(KEY_USE_AT, 0L)

    fun setUseAtMs(context: Context, useAtMs: Long) {
        prefs(context).edit().putLong(KEY_USE_AT, useAtMs).apply()
    }

    fun getBootAtSeconds(context: Context): Long = prefs(context).getLong(KEY_BOOT_AT_SEC, 0L)

    fun setBootAtSeconds(context: Context, bootAtSeconds: Long) {
        prefs(context).edit().putLong(KEY_BOOT_AT_SEC, bootAtSeconds).apply()
    }

    fun getLastPid(context: Context): Int = prefs(context).getInt(KEY_LAST_PID, 0)

    fun setLastPid(context: Context, pid: Int) {
        prefs(context).edit().putInt(KEY_LAST_PID, pid).apply()
    }

    fun getLastTimeMs(context: Context): Long = prefs(context).getLong(KEY_LAST_TIME_MS, 0L)

    fun setLastTimeMs(context: Context, timeMs: Long) {
        prefs(context).edit().putLong(KEY_LAST_TIME_MS, timeMs).apply()
    }

    fun getNextBrokePosition(context: Context): Int = prefs(context).getInt(KEY_NEXT_BROKE, 0)

    fun setNextBrokePosition(context: Context, position: Int) {
        prefs(context).edit().putInt(KEY_NEXT_BROKE, position).apply()
    }

    fun getFirmware(context: Context): String = prefs(context).getString(KEY_FIRMWARE, "") ?: ""

    fun setFirmware(context: Context, firmware: String) {
        prefs(context).edit().putString(KEY_FIRMWARE, firmware).apply()
    }

    @JvmStatic
    fun addSensor(
        context: Context,
        deviceCode: String,
        calibration: ScgmCalibration,
        useAtMs: Long = 0L,
        connectNow: Boolean = true,
    ): String? {
        if (!isValidDeviceCode(deviceCode) || !calibration.isUsable) {
            Log.w(TAG, "addSensor: invalid device code or calibration")
            return null
        }
        val sensorId = canonicalSensorId(deviceCode)
        val previous = getSensorId(context)
        if (previous != sensorId && previous.isNotBlank()) {
            setLastBtAddress(context, "")
            setBootAtSeconds(context, 0L)
            setLastPid(context, 0)
            setLastTimeMs(context, 0L)
            setNextBrokePosition(context, 0)
            runCatching { Natives.finishSensorById(previous) }
                .onFailure { Log.stack(TAG, "addSensor(finishSensorById)", it) }
        }
        prefs(context).edit()
            .putString(KEY_SENSOR_ID, sensorId)
            .putString(KEY_DEVICE_CODE, deviceCode.trim().lowercase(Locale.ROOT))
            .apply()
        setCalibration(context, calibration)
        setUseAtMs(context, useAtMs)
        retireOtherDrivers(sensorId)
        val blue = SensorBluetooth.blueone
        if (!connectNow || blue == null) return sensorId
        val existing = findDriver(sensorId)
        val driver = existing ?: ScgmBleManager(sensorId, 0L).also {
            it.applyRegistrySettings(context)
            it.mActiveDeviceAddress = getLastBtAddress(context).takeIf { addr -> addr.isNotBlank() }
            SensorBluetooth.gattcallbacks.add(it)
            runCatching { Natives.setmaxsensors(SensorBluetooth.gattcallbacks.size) }
        }
        existing?.applyRegistrySettings(context)
        driver.connectDevice(0L)
        return sensorId
    }

    @JvmStatic
    fun findDriver(sensorId: String): ScgmBleManager? =
        SensorBluetooth.gattcallbacks.firstNotNullOfOrNull { callback ->
            (callback as? ScgmBleManager)?.takeIf { it.matchesManagedSensorId(sensorId) }
        }

    private fun retireOtherDrivers(sensorId: String) {
        SensorBluetooth.gattcallbacks
            .filterIsInstance<ScgmBleManager>()
            .filterNot { it.matchesManagedSensorId(sensorId) }
            .forEach { stale ->
                runCatching { stale.terminateManagedSensor(wipeData = false) }
                    .onFailure { Log.stack(TAG, "retireOtherDrivers", it) }
                SensorBluetooth.gattcallbacks.remove(stale)
            }
    }

    fun isScgmSensorId(sensorId: String?): Boolean =
        !sensorId.isNullOrBlank() && sensorId.trim().startsWith(ScgmConstants.SENSOR_ID_PREFIX, ignoreCase = true)

    fun clear(context: Context) = prefs(context).edit().clear().apply()
}
