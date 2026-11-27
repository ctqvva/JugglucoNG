package tk.glucodata.drivers.instara

import android.content.Context
import android.content.SharedPreferences

object InstaraPersistence {

    private const val PREFS_NAME = "tk.glucodata_instara_prefs"
    private const val KEY_SN = "instara_sn"
    private const val KEY_BT_ADDRESS = "instara_bt_address"
    private const val KEY_SENSOR_START_MS = "instara_sensor_start_ms"
    private const val KEY_LAST_SEQUENCE_ID_PREFIX = "instara_last_sequence_id_"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isValidSn(sn: String?): Boolean {
        val trimmed = sn?.trim().orEmpty()
        return trimmed.length == 8 && trimmed.all { it in '0'..'9' || it in 'a'..'z' || it in 'A'..'Z' }
    }

    fun canonicalSensorId(sn: String?): String = sn?.trim().orEmpty()

    fun getSerialNumber(context: Context): String = prefs(context).getString(KEY_SN, "") ?: ""

    fun setSerialNumber(context: Context, sn: String) {
        prefs(context).edit().putString(KEY_SN, canonicalSensorId(sn)).apply()
    }

    fun getLastBtAddress(context: Context): String = prefs(context).getString(KEY_BT_ADDRESS, "") ?: ""

    fun setLastBtAddress(context: Context, address: String) {
        prefs(context).edit().putString(KEY_BT_ADDRESS, address).apply()
    }

    fun getSensorStartMs(context: Context): Long = prefs(context).getLong(KEY_SENSOR_START_MS, 0L)

    fun setSensorStartMs(context: Context, startMs: Long) {
        prefs(context).edit().putLong(KEY_SENSOR_START_MS, startMs).apply()
    }

    /** Cursor is per sensor id: replacing a sensor can never inherit another sensor's history. */
    private fun sequenceKey(sensorId: String): String = KEY_LAST_SEQUENCE_ID_PREFIX + sensorId

    fun getLastSequenceId(context: Context, sensorId: String): Int =
        prefs(context).getInt(sequenceKey(sensorId), 0)

    fun setLastSequenceId(context: Context, sensorId: String, sequenceId: Int) {
        prefs(context).edit().putInt(sequenceKey(sensorId), sequenceId).apply()
    }

    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
    }
}
