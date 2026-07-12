package tk.glucodata.drivers.ottai

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import tk.glucodata.Natives

data class OttaiSensorRecord(
    val sensorId: String,
    val mac: String,
    val deviceVersion: String = "",
    val accessToken: String = "",
    val glucoseSecretKey: String = "",
    val userId: String = "",
    val keyAPlaintext: String = "",
    val method: String = "",
    val coefficient: String = "",
    val activeTimeMs: Long = 0L,
    val activeExpireTimeMs: Long = 0L,
    val retainTimeMs: Long = OttaiConstants.DEFAULT_RETAIN_TIME_MS,
    val produceTimeMs: Long = 0L,
    val methodUpdateTimeMs: Long = 0L,
    val coeffUpdateTimeMs: Long = 0L,
    val sessionKeyHex: String = "",
    val lastDataNo: Int = -1,
    val apiBase: String = OttaiConstants.API_BASE,
    val provisionalActiveTimeMs: Long = 0L,
)

object OttaiRegistry {

    private const val PREFS_NAME = "tk.glucodata_preferences"

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun addSensor(ctx: Context, record: OttaiSensorRecord) {
        val ids = sensorIds(ctx).toMutableSet()
        ids.add(record.sensorId)
        prefs(ctx).edit().putString(OttaiConstants.PREF_SENSORS_KEY, ids.joinToString(",")).apply()
        saveRecord(ctx, record)
    }

    fun saveRecord(ctx: Context, r: OttaiSensorRecord) {
        val e = prefs(ctx).edit()
        e.putString(OttaiConstants.PREF_TOKEN_PREFIX + r.sensorId, r.accessToken)
        e.putString(OttaiConstants.PREF_GSK_PREFIX + r.sensorId, r.glucoseSecretKey)
        e.putString(OttaiConstants.PREF_USER_ID_PREFIX + r.sensorId, r.userId)
        e.putString(OttaiConstants.PREF_AUTH_KEYS_PREFIX + r.sensorId, r.keyAPlaintext)
        e.putString(OttaiConstants.PREF_METHOD_PREFIX + r.sensorId, r.method)
        e.putString(OttaiConstants.PREF_COEFFICIENT_PREFIX + r.sensorId, r.coefficient)
        e.putLong(OttaiConstants.PREF_ACTIVE_TIME_PREFIX + r.sensorId, r.activeTimeMs)
        e.putLong(OttaiConstants.PREF_ACTIVE_EXPIRE_TIME_PREFIX + r.sensorId, r.activeExpireTimeMs)
        e.putLong(OttaiConstants.PREF_RETAIN_TIME_PREFIX + r.sensorId, r.retainTimeMs)
        e.putLong(OttaiConstants.PREF_PRODUCE_TIME_PREFIX + r.sensorId, r.produceTimeMs)
        e.putLong(OttaiConstants.PREF_METHOD_UPDATE_TIME_PREFIX + r.sensorId, r.methodUpdateTimeMs)
        e.putLong(OttaiConstants.PREF_COEFF_UPDATE_TIME_PREFIX + r.sensorId, r.coeffUpdateTimeMs)
        e.putString(OttaiConstants.PREF_SESSION_KEY_PREFIX + r.sensorId, r.sessionKeyHex)
        e.putString(OttaiConstants.PREF_DEVICE_VERSION_PREFIX + r.sensorId, r.deviceVersion)
        e.putInt(OttaiConstants.PREF_LAST_DATA_NO_PREFIX + r.sensorId, r.lastDataNo)
        e.putString(OttaiConstants.PREF_API_BASE_PREFIX + r.sensorId, r.apiBase)
        e.apply()
    }

    fun loadMacRecord(ctx: Context, mac: String, apiBase: String = OttaiConstants.API_BASE): OttaiSensorRecord {
        val p = prefs(ctx)
        return OttaiSensorRecord(
            sensorId = mac,
            mac = mac,
            accessToken = p.getString(OttaiConstants.PREF_TOKEN_PREFIX + mac, "") ?: "",
            glucoseSecretKey = p.getString(OttaiConstants.PREF_GSK_PREFIX + mac, "") ?: "",
            userId = p.getString(OttaiConstants.PREF_USER_ID_PREFIX + mac, "") ?: "",
            keyAPlaintext = p.getString(OttaiConstants.PREF_AUTH_KEYS_PREFIX + mac, "") ?: "",
            method = p.getString(OttaiConstants.PREF_METHOD_PREFIX + mac, "") ?: "",
            coefficient = p.getString(OttaiConstants.PREF_COEFFICIENT_PREFIX + mac, "") ?: "",
            activeTimeMs = p.getLong(OttaiConstants.PREF_ACTIVE_TIME_PREFIX + mac, 0L),
            activeExpireTimeMs = p.getLong(OttaiConstants.PREF_ACTIVE_EXPIRE_TIME_PREFIX + mac, OttaiConstants.DEFAULT_ACTIVE_EXPIRE_MS),
            retainTimeMs = p.getLong(OttaiConstants.PREF_RETAIN_TIME_PREFIX + mac, OttaiConstants.DEFAULT_RETAIN_TIME_MS),
            produceTimeMs = p.getLong(OttaiConstants.PREF_PRODUCE_TIME_PREFIX + mac, 0L),
            methodUpdateTimeMs = p.getLong(OttaiConstants.PREF_METHOD_UPDATE_TIME_PREFIX + mac, 0L),
            coeffUpdateTimeMs = p.getLong(OttaiConstants.PREF_COEFF_UPDATE_TIME_PREFIX + mac, 0L),
            sessionKeyHex = p.getString(OttaiConstants.PREF_SESSION_KEY_PREFIX + mac, "") ?: "",
            deviceVersion = p.getString(OttaiConstants.PREF_DEVICE_VERSION_PREFIX + mac, "") ?: "",
            lastDataNo = p.getInt(OttaiConstants.PREF_LAST_DATA_NO_PREFIX + mac, -1),
            apiBase = p.getString(OttaiConstants.PREF_API_BASE_PREFIX + mac, apiBase) ?: apiBase,
        )
    }

    fun updateSessionKey(ctx: Context, sensorId: String, sessionKey: String) {
        prefs(ctx).edit().putString(OttaiConstants.PREF_SESSION_KEY_PREFIX + sensorId, sessionKey).apply()
    }

    fun updateLastDataNo(ctx: Context, sensorId: String, dataNo: Int) {
        prefs(ctx).edit().putInt(OttaiConstants.PREF_LAST_DATA_NO_PREFIX + sensorId, dataNo).apply()
    }

    fun updateActiveTime(ctx: Context, sensorId: String, activeTimeMs: Long) {
        prefs(ctx).edit().putLong(OttaiConstants.PREF_ACTIVE_TIME_PREFIX + sensorId, activeTimeMs).apply()
    }

    fun findRecord(ctx: Context, sensorId: String): OttaiSensorRecord? {
        val ids = sensorIds(ctx)
        val match = ids.firstOrNull { it.equals(sensorId, ignoreCase = true) } ?: return null
        return loadMacRecord(ctx, match)
    }

    fun persistedRecords(ctx: Context): List<OttaiSensorRecord> =
        sensorIds(ctx).map { loadMacRecord(ctx, it) }

    fun removeSensor(ctx: Context, sensorId: String?) {
        if (sensorId == null) return
        val ids = sensorIds(ctx).toMutableSet()
        ids.remove(sensorId)
        prefs(ctx).edit().putString(OttaiConstants.PREF_SENSORS_KEY, ids.joinToString(",")).apply()
    }

    fun createRestoredCallback(ctx: Context, sensorId: String, dataptr: Long): OttaiBleManager? {
        val record = findRecord(ctx, sensorId) ?: return null
        return OttaiBleManager(sensorId, dataptr).also { it.restoreFromPersistence(ctx) }
    }

    fun resolveCanonicalSensorId(ctx: Context, sensorId: String): String? {
        val ids = sensorIds(ctx)
        return ids.firstOrNull { it.equals(sensorId, ignoreCase = true) }
            ?: ids.firstOrNull { s -> sensorId.uppercase().let { it == s.uppercase() || s.uppercase().endsWith(it.takeLast(12)) } }
    }

    fun saveApiBase(ctx: Context, sensorId: String, apiBase: String) {
        prefs(ctx).edit().putString(OttaiConstants.PREF_API_BASE_PREFIX + sensorId, apiBase).apply()
    }

    private fun sensorIds(ctx: Context): List<String> {
        val raw = prefs(ctx).getString(OttaiConstants.PREF_SENSORS_KEY, "") ?: ""
        return raw.split(",").filter { it.isNotBlank() }
    }
}
