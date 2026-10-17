package tk.glucodata.drivers.medtrum

import android.content.Context
import android.content.SharedPreferences

object MedtrumPersistence {

    private const val PREFS_NAME = "tk.glucodata_medtrum_prefs"
    private const val KEY_SERIAL_NUMBER = "medtrum_serial_number"
    private const val KEY_BT_ADDRESS = "medtrum_bt_address"
    private const val KEY_SENSOR_ID = "medtrum_sensor_id"
    private const val KEY_LEGACY_AUTH = "medtrum_legacy_auth"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getSerialNumber(context: Context): Long =
        prefs(context).getLong(KEY_SERIAL_NUMBER, 0L)

    fun setSerialNumber(context: Context, sn: Long) {
        prefs(context).edit().putLong(KEY_SERIAL_NUMBER, sn).apply()
    }

    fun getLastBtAddress(context: Context): String =
        prefs(context).getString(KEY_BT_ADDRESS, "") ?: ""

    fun setLastBtAddress(context: Context, address: String) {
        prefs(context).edit().putString(KEY_BT_ADDRESS, address).apply()
    }

    fun getSensorId(context: Context): String =
        prefs(context).getString(KEY_SENSOR_ID, "") ?: ""

    fun setSensorId(context: Context, id: String) {
        prefs(context).edit().putString(KEY_SENSOR_ID, id).apply()
    }

    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
    }

    /**
     * Which auth dialect the patch accepted. The vendor app picks role/key from the
     * advertised firmware byte, which this driver's scan seam does not expose, so it
     * learns the dialect instead: modern first, legacy after a failed AUTH.
     */
    fun isLegacyAuth(context: Context): Boolean =
        prefs(context).getBoolean(KEY_LEGACY_AUTH, false)

    fun setLegacyAuth(context: Context, legacy: Boolean) {
        prefs(context).edit().putBoolean(KEY_LEGACY_AUTH, legacy).apply()
    }

    /** Canonical sensor ID = decimal string of the serial number, e.g. "2329895446" */
    fun canonicalSensorId(serialNumber: Long): String = serialNumber.toString()

    /**
     * Parse the serial printed on the patch. The label is hexadecimal ("8ADF6216"), so the
     * input is read as hex, not decimal: 1-8 hex digits, value 1..0xFFFFFFFF.
     */
    fun parseSerialNumber(input: String?): Long? {
        val trimmed = input?.trim().orEmpty()
        if (trimmed.isEmpty() || trimmed.length > 8) return null
        if (!trimmed.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) return null
        val value = trimmed.toLongOrNull(16) ?: return null
        return value.takeIf { it in 1..0xFFFFFFFFL }
    }

    /** The label form of a serial number, for echoing back what was entered. */
    fun formatSerialHex(serialNumber: Long): String = "%08X".format(serialNumber)

    /** True if sensorId looks like a Medtrum serial (decimal form of a 32-bit serial) */
    fun isMedtrumSensorId(sensorId: String?): Boolean {
        if (sensorId.isNullOrBlank()) return false
        return sensorId.all { it.isDigit() } && sensorId.length in 6..12
    }
}
