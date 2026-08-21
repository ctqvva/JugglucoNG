package tk.glucodata.drivers.nightscout

import android.content.Context
import java.net.HttpURLConnection
import java.security.MessageDigest
import java.util.Locale
import tk.glucodata.ManagedCurrentSensor
import tk.glucodata.SensorBluetooth
import tk.glucodata.SensorIdentity
import tk.glucodata.SuperGattCallback
import tk.glucodata.drivers.ManagedBluetoothSensorDriver
import tk.glucodata.drivers.ManagedSensorUiSignals

object NightscoutFollowerRegistry {
    private const val PREFS_NAME = "tk.glucodata_preferences"
    private const val PREF_ENABLED = "nightscout_follower_enabled"
    private const val PREF_URL = "nightscout_follower_url"
    private const val PREF_SECRET = "nightscout_follower_secret"
    //The uploader's v3 flag is an uploader setting and never reaches here: a follower may point
    //at an entirely different server than the one this phone uploads to.
    private const val PREF_USE_V3 = "nightscout_follower_v3"
    private const val PREF_COMPLETE_HISTORY_PREFIX = "nightscout_follower_complete_history_v1_"
    const val SENSOR_PREFIX = "NSF-"

    data class Config(
        val enabled: Boolean,
        val url: String,
        val secret: String,
        val useV3: Boolean = false,
    ) {
        val sensorId: String get() = deriveSensorId(url)
        val isUsable: Boolean get() = enabled && url.isNotBlank()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun normalizeUrl(url: String?): String =
        url?.trim()
            ?.removeSuffix("/")
            ?.takeIf { it.isNotEmpty() }
            ?.let { raw ->
                if (raw.startsWith("http://", ignoreCase = true) ||
                    raw.startsWith("https://", ignoreCase = true)
                ) {
                    raw
                } else {
                    "https://$raw"
                }
            }
            .orEmpty()

    fun deriveSensorId(url: String?): String {
        val normalized = normalizeUrl(url)
        if (normalized.isEmpty()) return SENSOR_PREFIX + "UNCONFIGURED"
        val digest = MessageDigest.getInstance("SHA-1")
            .digest(normalized.lowercase(Locale.US).toByteArray(Charsets.UTF_8))
            .take(6)
            .joinToString("") { "%02X".format(Locale.US, it) }
        return SENSOR_PREFIX + digest
    }

    fun loadConfig(context: Context): Config =
        Config(
            enabled = prefs(context).getBoolean(PREF_ENABLED, false),
            url = normalizeUrl(prefs(context).getString(PREF_URL, null)),
            secret = prefs(context).getString(PREF_SECRET, null).orEmpty(),
            useV3 = prefs(context).getBoolean(PREF_USE_V3, false),
        )

    fun saveConfig(
        context: Context,
        enabled: Boolean,
        url: String?,
        secret: String?,
        useV3: Boolean = loadConfig(context).useV3,
    ) {
        prefs(context).edit()
            .putBoolean(PREF_ENABLED, enabled)
            .putString(PREF_URL, normalizeUrl(url).takeIf { it.isNotEmpty() })
            .putString(PREF_SECRET, secret?.trim()?.takeIf { it.isNotEmpty() })
            .putBoolean(PREF_USE_V3, useV3)
            .apply()
        ManagedSensorUiSignals.markDeviceListDirty()
    }

    fun persistedSensorIds(context: Context): List<String> =
        loadConfig(context).takeIf { it.isUsable }?.let { listOf(it.sensorId) }.orEmpty()

    fun hasCompleteHistoryImport(context: Context?, sensorId: String): Boolean =
        context != null &&
            prefs(context).getBoolean(PREF_COMPLETE_HISTORY_PREFIX + sensorId.uppercase(Locale.US), false)

    fun markCompleteHistoryImport(context: Context?, sensorId: String) {
        if (context == null || sensorId.isBlank()) return
        prefs(context).edit()
            .putBoolean(PREF_COMPLETE_HISTORY_PREFIX + sensorId.uppercase(Locale.US), true)
            .apply()
    }

    fun createRestoredCallback(context: Context, sensorId: String, dataptr: Long): SuperGattCallback? {
        val config = loadConfig(context)
        if (!config.isUsable || !matchesSensorId(sensorId, config.sensorId)) return null
        return NightscoutFollowerManager(
            serial = config.sensorId,
            url = config.url,
            secret = config.secret,
            useV3 = config.useV3,
            dataptr = dataptr,
        )
    }

    fun enableFollowerSensor(
        context: Context,
        url: String?,
        secret: String?,
        connectNow: Boolean = true,
        useV3: Boolean = loadConfig(context).useV3,
    ): String? {
        val normalizedUrl = normalizeUrl(url)
        if (normalizedUrl.isEmpty()) return null
        saveConfig(context, enabled = true, url = normalizedUrl, secret = secret, useV3 = useV3)
        val sensorId = deriveSensorId(normalizedUrl)
        if (connectNow) {
            connectSensor(context, sensorId)
        }
        return sensorId
    }

    fun disableFollowerSensor(context: Context) {
        val sensorId = loadConfig(context).sensorId
        ManagedCurrentSensor.clearIfMatches(sensorId)
        saveConfig(context, enabled = false, url = loadConfig(context).url, secret = loadConfig(context).secret)
        SensorBluetooth.mygatts()
            .firstOrNull { SensorIdentity.matches(it.SerialNumber, sensorId) }
            ?.let { callback ->
                if (callback is ManagedBluetoothSensorDriver) {
                    callback.terminateManagedSensor(wipeData = false)
                }
                SensorBluetooth.sensorEnded(callback.SerialNumber)
            }
    }

    fun connectSensor(context: Context, sensorId: String) {
        val blue = SensorBluetooth.blueone ?: return
        val existing = SensorBluetooth.gattcallbacks.firstOrNull { callback ->
            SensorIdentity.matches(callback.SerialNumber, sensorId) ||
                ((callback as? ManagedBluetoothSensorDriver)?.matchesManagedSensorId(sensorId) == true)
        }
        val callback = existing ?: createRestoredCallback(context, sensorId, 0L)?.also {
            SensorBluetooth.gattcallbacks.add(it)
            tk.glucodata.Natives.setmaxsensors(SensorBluetooth.gattcallbacks.size)
        } ?: return
        SensorBluetooth.ensureCurrentSensorSelection()
        if (SensorBluetooth.blueone === blue) {
            callback.connectDevice(0)
        }
        ManagedSensorUiSignals.markDeviceListDirty()
    }

    fun matchesSensorId(candidate: String?, expected: String?): Boolean {
        val left = candidate?.trim().orEmpty()
        val right = expected?.trim().orEmpty()
        return left.isNotEmpty() && right.isNotEmpty() && left.equals(right, ignoreCase = true)
    }

    fun applyAuth(connection: HttpURLConnection, secret: String) {
        val trimmed = secret.trim()
        if (trimmed.isEmpty()) return
        if (trimmed.startsWith("Bearer ", ignoreCase = true)) {
            connection.setRequestProperty("Authorization", trimmed)
            return
        }
        if (trimmed.startsWith("token=", ignoreCase = true)) {
            connection.setRequestProperty("Authorization", "Bearer ${trimmed.substringAfter('=')}")
            return
        }
        connection.setRequestProperty(
            "api-secret",
            if (isSha1Hex(trimmed)) trimmed else sha1(trimmed)
        )
    }

    // Replaces Regex("^[0-9a-fA-F]{40}$") to avoid repeated ICU JNI allocation on
    // the NightscoutFollower HandlerThread.  On Samsung Android 15 with Scudo+MTE the
    // ReleaseIntArrayElements call inside MatcherNative_matchesImpl corrupts the chunk
    // header after many poll cycles, resulting in a fatal SIGABRT.
    private fun isSha1Hex(s: String): Boolean {
        if (s.length != 40) return false
        return s.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
    }

    private fun sha1(value: String): String =
        MessageDigest.getInstance("SHA-1")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(Locale.US, it) }
}
