package tk.glucodata.drivers.nightscout

import android.content.Context
import java.net.HttpURLConnection
import java.security.MessageDigest
import java.util.Locale
import tk.glucodata.ManagedCurrentSensor
import tk.glucodata.Natives
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
    private const val PREF_POLL_MINUTES = "nightscout_follower_poll_minutes"
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

    /** How often the follower asks, in minutes. Sanitized on the way out and on the way in. */
    fun loadPollMinutes(context: Context?): Int =
        context?.let {
            NightscoutFollowerPollPolicy.sanitizeMinutes(
                prefs(it).getInt(PREF_POLL_MINUTES, NightscoutFollowerPollPolicy.DEFAULT_MINUTES)
            )
        } ?: NightscoutFollowerPollPolicy.DEFAULT_MINUTES

    fun savePollMinutes(context: Context, minutes: Int) {
        prefs(context).edit()
            .putInt(PREF_POLL_MINUTES, NightscoutFollowerPollPolicy.sanitizeMinutes(minutes))
            .apply()
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

    /**
     * Restore the configured follower without requiring Bluetooth initialization.
     *
     * Application.onCreate runs before an alarm receiver. Restoring here means an alarm that
     * starts a fresh process has a callback to hand its wakelock to instead of ending the poll
     * chain. The list lock is the same one used by SensorBluetooth.mygatts().
     */
    @JvmOverloads
    fun restoreConfiguredFollower(
        context: Context,
        sensorId: String = loadConfig(context).sensorId,
    ): NightscoutFollowerManager? {
        val existing = findRunningFollower(sensorId)
        if (existing != null) {
            existing.updateApiVersion(loadConfig(context).useV3)
            return existing
        }

        var added = false
        val follower = synchronized(SensorBluetooth.gattcallbacks) {
            findRunningFollowerLocked(sensorId)?.also {
                it.updateApiVersion(loadConfig(context).useV3)
            } ?: run {
                val restored = createRestoredCallback(context, sensorId, 0L) as? NightscoutFollowerManager
                    ?: return@synchronized null
                SensorBluetooth.gattcallbacks.add(restored)
                Natives.setmaxsensors(SensorBluetooth.gattcallbacks.size)
                added = true
                restored
            }
        } ?: return null

        if (added) {
            SensorBluetooth.ensureCurrentSensorSelection()
            ManagedSensorUiSignals.markDeviceListDirty()
        }
        return follower
    }

    fun recoverOnNetworkAvailable(context: Context) {
        val config = loadConfig(context)
        if (!config.isUsable) return
        restoreConfiguredFollower(context, config.sensorId)
            ?.recoverIfNeeded("network", forceWhenIdle = true)
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
        NightscoutFollowerDeviceStatus.clear()
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
        val callback = restoreConfiguredFollower(context, sensorId) ?: return
        SensorBluetooth.ensureCurrentSensorSelection()
        callback.connectDevice(0)
        ManagedSensorUiSignals.markDeviceListDirty()
    }

    private fun findRunningFollower(sensorId: String): NightscoutFollowerManager? =
        SensorBluetooth.mygatts().firstOrNull { callback ->
            callback is NightscoutFollowerManager && callback.matchesManagedSensorId(sensorId)
        } as? NightscoutFollowerManager

    private fun findRunningFollowerLocked(sensorId: String): NightscoutFollowerManager? =
        SensorBluetooth.gattcallbacks.firstOrNull { callback ->
            callback is NightscoutFollowerManager && callback.matchesManagedSensorId(sensorId)
        } as? NightscoutFollowerManager

    fun matchesSensorId(candidate: String?, expected: String?): Boolean {
        val left = candidate?.trim().orEmpty()
        val right = expected?.trim().orEmpty()
        return left.isNotEmpty() && right.isNotEmpty() && left.equals(right, ignoreCase = true)
    }

    fun isFollowerSensorId(candidate: String?): Boolean =
        candidate?.trim()?.startsWith(SENSOR_PREFIX, ignoreCase = true) == true

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
