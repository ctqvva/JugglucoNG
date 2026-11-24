package tk.glucodata.drivers.bioe

import android.content.Context
import android.content.SharedPreferences
import tk.glucodata.Log
import tk.glucodata.Natives
import tk.glucodata.SensorBluetooth

object BioeRegistry {
    private const val TAG = "BioeRegistry"
    private const val PREFS_NAME = "tk.glucodata_bioe_prefs"
    private const val KEY_SENSOR_ID = "bioe_sensor_id"
    private const val KEY_DEVICE_CODE = "bioe_device_code"
    private const val KEY_BT_ADDRESS = "bioe_bt_address"
    private const val KEY_K = "bioe_conv_num"
    private const val KEY_B = "bioe_verify_val"
    private const val KEY_THRESHOLD = "bioe_low_current_na"
    private const val KEY_USE_AT = "bioe_use_at_ms"
    private const val KEY_API_TOKEN = "bioe_api_token"
    private const val KEY_LAST_FLASH_INDEX = "bioe_last_flash_index"
    private const val KEY_LAST_FLASH_COUNT = "bioe_last_flash_count"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun canonicalSensorId(deviceCode: String?): String {
        val raw = deviceCode?.trim().orEmpty()
        if (raw.isEmpty()) return ""
        val normalized = raw.uppercase()
        return if (normalized.startsWith(BioeConstants.SENSOR_ID_PREFIX)) normalized
        else BioeConstants.SENSOR_ID_PREFIX + normalized
    }

    fun isValidDeviceCode(input: String?): Boolean {
        val raw = input?.trim().orEmpty()
        return raw.length in 4..40 && raw.all { it.isLetterOrDigit() || it == '-' || it == '_' }
    }

    fun getSensorId(context: Context): String = prefs(context).getString(KEY_SENSOR_ID, "") ?: ""

    fun getDeviceCode(context: Context): String = prefs(context).getString(KEY_DEVICE_CODE, "") ?: ""

    fun getLastBtAddress(context: Context): String = prefs(context).getString(KEY_BT_ADDRESS, "") ?: ""

    fun setLastBtAddress(context: Context, address: String) {
        prefs(context).edit().putString(KEY_BT_ADDRESS, address).apply()
    }

    fun getCalibration(context: Context): BioeCalibration {
        val p = prefs(context)
        val k = p.getString(KEY_K, null)?.toDoubleOrNull() ?: BioeConstants.DEFAULT_K
        val b = p.getString(KEY_B, null)?.toDoubleOrNull() ?: BioeConstants.DEFAULT_B
        val threshold = p.getString(KEY_THRESHOLD, null)?.toDoubleOrNull()
            ?: BioeConstants.DEFAULT_LOW_CURRENT_NA
        return BioeCalibration(k, b, threshold)
    }

    fun setCalibration(context: Context, calibration: BioeCalibration) {
        prefs(context).edit()
            .putString(KEY_K, calibration.k.toString())
            .putString(KEY_B, calibration.b.toString())
            .putString(KEY_THRESHOLD, calibration.lowCurrentThresholdNa.toString())
            .apply()
    }

    fun getUseAtMs(context: Context): Long = prefs(context).getLong(KEY_USE_AT, 0L)

    fun setUseAtMs(context: Context, useAtMs: Long) {
        prefs(context).edit().putLong(KEY_USE_AT, useAtMs).apply()
    }

    fun getApiToken(context: Context): String = prefs(context).getString(KEY_API_TOKEN, "") ?: ""

    fun setApiToken(context: Context, token: String) {
        prefs(context).edit().putString(KEY_API_TOKEN, token.trim()).apply()
    }

    fun getLastFlashIndex(context: Context): Int = prefs(context).getInt(KEY_LAST_FLASH_INDEX, 0)

    fun setLastFlashIndex(context: Context, index: Int) {
        prefs(context).edit().putInt(KEY_LAST_FLASH_INDEX, index).apply()
    }

    fun getLastFlashCount(context: Context): Int = prefs(context).getInt(KEY_LAST_FLASH_COUNT, 0)

    fun setLastFlashCount(context: Context, count: Int) {
        prefs(context).edit().putInt(KEY_LAST_FLASH_COUNT, count).apply()
    }

    @JvmStatic
    fun addSensor(
        context: Context,
        deviceCode: String,
        calibration: BioeCalibration,
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
            setLastFlashIndex(context, 0)
            setLastFlashCount(context, 0)
            runCatching { Natives.finishSensorById(previous) }
                .onFailure { Log.stack(TAG, "addSensor(finishSensorById)", it) }
        }
        prefs(context).edit()
            .putString(KEY_SENSOR_ID, sensorId)
            .putString(KEY_DEVICE_CODE, deviceCode.trim().uppercase())
            .apply()
        setCalibration(context, calibration)
        setUseAtMs(context, useAtMs)
        retireOtherDrivers(sensorId)
        val blue = SensorBluetooth.blueone
        if (!connectNow || blue == null) return sensorId
        val existing = findDriver(sensorId)
        val driver = existing ?: BioeBleManager(sensorId, 0L).also {
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
    fun findDriver(sensorId: String): BioeBleManager? =
        SensorBluetooth.gattcallbacks.firstNotNullOfOrNull { callback ->
            (callback as? BioeBleManager)?.takeIf { it.matchesManagedSensorId(sensorId) }
        }

    private fun retireOtherDrivers(sensorId: String) {
        SensorBluetooth.gattcallbacks
            .filterIsInstance<BioeBleManager>()
            .filterNot { it.matchesManagedSensorId(sensorId) }
            .forEach { stale ->
                runCatching { stale.terminateManagedSensor(wipeData = false) }
                    .onFailure { Log.stack(TAG, "retireOtherDrivers", it) }
                SensorBluetooth.gattcallbacks.remove(stale)
            }
    }

    fun isBioeSensorId(sensorId: String?): Boolean =
        !sensorId.isNullOrBlank() && sensorId.trim().startsWith(BioeConstants.SENSOR_ID_PREFIX, ignoreCase = true)

    fun clear(context: Context) = prefs(context).edit().clear().apply()
}
