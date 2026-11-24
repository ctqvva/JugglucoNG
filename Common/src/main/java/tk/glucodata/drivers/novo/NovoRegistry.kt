package tk.glucodata.drivers.novo

import android.content.Context
import android.content.SharedPreferences
import tk.glucodata.Log
import tk.glucodata.Natives
import tk.glucodata.SensorBluetooth

object NovoRegistry {
    private const val TAG = "NovoRegistry"
    private const val PREFS_NAME = "tk.glucodata_novo_prefs"
    private const val KEY_SENSOR_ID = "novo_sensor_id"
    private const val KEY_DEVICE_CODE = "novo_device_code"
    private const val KEY_BT_ADDRESS = "novo_bt_address"
    private const val KEY_K = "novo_conv_num"
    private const val KEY_B = "novo_verify_val"
    private const val KEY_USE_AT = "novo_use_at_ms"
    private const val KEY_PASSWORD_LO = "novo_password_lo"
    private const val KEY_PASSWORD_HI = "novo_password_hi"
    private const val KEY_LAST_FLASH_COUNT = "novo_last_flash_count"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun canonicalSensorId(deviceCode: String?): String {
        val raw = deviceCode?.trim().orEmpty()
        if (raw.isEmpty()) return ""
        val normalized = raw.uppercase()
        return if (normalized.startsWith(NovoConstants.SENSOR_ID_PREFIX)) normalized
        else NovoConstants.SENSOR_ID_PREFIX + normalized
    }

    fun isValidDeviceCode(input: String?): Boolean {
        val raw = input?.trim().orEmpty()
        return raw.length in 3..40 && raw.all { it.isLetterOrDigit() || it == '-' || it == '_' }
    }

    fun getSensorId(context: Context): String = prefs(context).getString(KEY_SENSOR_ID, "") ?: ""

    fun getDeviceCode(context: Context): String = prefs(context).getString(KEY_DEVICE_CODE, "") ?: ""

    fun getLastBtAddress(context: Context): String = prefs(context).getString(KEY_BT_ADDRESS, "") ?: ""

    fun setLastBtAddress(context: Context, address: String) {
        prefs(context).edit().putString(KEY_BT_ADDRESS, address).apply()
    }

    fun getCalibration(context: Context): NovoCalibration {
        val p = prefs(context)
        val k = p.getString(KEY_K, null)?.toDoubleOrNull() ?: NovoConstants.DEFAULT_K
        val b = p.getString(KEY_B, null)?.toDoubleOrNull() ?: NovoConstants.DEFAULT_B
        return NovoCalibration(k, b)
    }

    fun setCalibration(context: Context, calibration: NovoCalibration) {
        prefs(context).edit()
            .putString(KEY_K, calibration.k.toString())
            .putString(KEY_B, calibration.b.toString())
            .apply()
    }

    fun getUseAtMs(context: Context): Long = prefs(context).getLong(KEY_USE_AT, 0L)

    fun setUseAtMs(context: Context, useAtMs: Long) {
        prefs(context).edit().putLong(KEY_USE_AT, useAtMs).apply()
    }

    fun getPasswordBytes(context: Context): Pair<Byte, Byte> {
        val p = prefs(context)
        val lo = runCatching { p.getInt(KEY_PASSWORD_LO, 0).toByte() }.getOrDefault(0)
        val hi = runCatching { p.getInt(KEY_PASSWORD_HI, 0).toByte() }.getOrDefault(0)
        return lo to hi
    }

    fun setPasswordBytes(context: Context, lo: Byte, hi: Byte) {
        prefs(context).edit()
            .putInt(KEY_PASSWORD_LO, lo.toInt() and 0xFF)
            .putInt(KEY_PASSWORD_HI, hi.toInt() and 0xFF)
            .apply()
    }

    fun getLastFlashCount(context: Context): Int = prefs(context).getInt(KEY_LAST_FLASH_COUNT, 0)

    fun setLastFlashCount(context: Context, count: Int) {
        prefs(context).edit().putInt(KEY_LAST_FLASH_COUNT, count).apply()
    }

    @JvmStatic
    fun addSensor(
        context: Context,
        deviceCode: String,
        calibration: NovoCalibration,
        passwordLow: Byte = 0,
        passwordHigh: Byte = 0,
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
            setLastFlashCount(context, 0)
            runCatching { Natives.finishSensorById(previous) }
                .onFailure { Log.stack(TAG, "addSensor(finishSensorById)", it) }
        }
        prefs(context).edit()
            .putString(KEY_SENSOR_ID, sensorId)
            .putString(KEY_DEVICE_CODE, deviceCode.trim().uppercase())
            .apply()
        setCalibration(context, calibration)
        setPasswordBytes(context, passwordLow, passwordHigh)
        setUseAtMs(context, useAtMs)
        retireOtherDrivers(sensorId)
        val blue = SensorBluetooth.blueone
        if (!connectNow || blue == null) return sensorId
        val existing = findDriver(sensorId)
        val driver = existing ?: NovoBleManager(sensorId, 0L).also {
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
    fun findDriver(sensorId: String): NovoBleManager? =
        SensorBluetooth.gattcallbacks.firstNotNullOfOrNull { callback ->
            (callback as? NovoBleManager)?.takeIf { it.matchesManagedSensorId(sensorId) }
        }

    private fun retireOtherDrivers(sensorId: String) {
        SensorBluetooth.gattcallbacks
            .filterIsInstance<NovoBleManager>()
            .filterNot { it.matchesManagedSensorId(sensorId) }
            .forEach { stale ->
                runCatching { stale.terminateManagedSensor(wipeData = false) }
                    .onFailure { Log.stack(TAG, "retireOtherDrivers", it) }
                SensorBluetooth.gattcallbacks.remove(stale)
            }
    }

    fun isNovoSensorId(sensorId: String?): Boolean =
        !sensorId.isNullOrBlank() && sensorId.trim().startsWith(NovoConstants.SENSOR_ID_PREFIX, ignoreCase = true)

    fun clear(context: Context) = prefs(context).edit().clear().apply()
}
