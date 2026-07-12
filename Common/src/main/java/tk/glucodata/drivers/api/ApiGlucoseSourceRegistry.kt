package tk.glucodata.drivers.api

import android.content.Context
import java.security.MessageDigest
import java.util.Locale
import tk.glucodata.ManagedCurrentSensor
import tk.glucodata.SensorBluetooth
import tk.glucodata.SensorIdentity
import tk.glucodata.SuperGattCallback
import tk.glucodata.drivers.ManagedBluetoothSensorDriver
import tk.glucodata.drivers.ManagedSensorUiSignals

object ApiGlucoseSourceRegistry {
    private const val PREFS_NAME = "tk.glucodata_preferences"
    private const val PREF_ENABLED = "api_glucose_source_enabled"
    private const val PREF_PRESET = "api_glucose_source_preset"
    private const val PREF_URL = "api_glucose_source_url"
    private const val PREF_TOKEN = "api_glucose_source_token"
    private const val PREF_PEER_ID = "api_glucose_source_peer_id"
    private const val PREF_API_VERSION = "api_glucose_source_api_version"
    private const val PREF_HEADERS = "api_glucose_source_headers"
    private const val PREF_FORMAT = "api_glucose_source_format"
    private const val PREF_POLL_SECONDS = "api_glucose_source_poll_seconds"
    private const val PREF_TELEGRAM_UPDATE_OFFSET = "api_glucose_source_telegram_update_offset"

    const val SENSOR_PREFIX = "API-"
    const val PRESET_CUSTOM_JSON = "custom_json"
    const val PRESET_TELEGRAM_BOT = "telegram_bot"
    const val PRESET_VK_DIRECT = "vk_direct"
    const val FORMAT_OUTBOUND_JSON = "outbound_json"
    const val FORMAT_GLUCO_WATCH_TEXT = "gluco_watch_text"
    const val DEFAULT_VK_API_VERSION = "5.199"
    const val DEFAULT_TELEGRAM_UPDATES_URL = "https://api.telegram.org/bot{token}/getUpdates"
    const val DEFAULT_VK_HISTORY_URL = "https://api.vk.com/method/messages.getHistory"
    const val DEFAULT_POLL_SECONDS = 60

    data class Config(
        val enabled: Boolean,
        val preset: String,
        val url: String,
        val token: String,
        val peerId: String,
        val apiVersion: String,
        val headers: String,
        val format: String,
        val pollSeconds: Int,
    ) {
        val sensorId: String get() = deriveSensorId(resolvedUrl(), normalizedPreset, peerId)
        val isUsable: Boolean get() =
            enabled && when (normalizedPreset) {
                PRESET_TELEGRAM_BOT -> token.isNotBlank() && peerId.isNotBlank()
                PRESET_VK_DIRECT -> token.isNotBlank() && peerId.isNotBlank()
                else -> url.isNotBlank()
            }
        val normalizedPreset: String get() = normalizePreset(preset)
        val normalizedFormat: String get() = normalizeFormat(format)

        fun resolvedUrl(): String {
            val tokenValue = token.trim().removePrefix("bot")
            return url.ifBlank { defaultUrl(normalizedPreset) }
                .replace("{token}", tokenValue)
        }
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun normalizeUrl(url: String?): String =
        url?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { raw ->
                when {
                    raw.startsWith("https://", ignoreCase = true) -> raw
                    raw.startsWith("http://", ignoreCase = true) -> ""
                    else -> "https://$raw"
                }
            }
            .orEmpty()

    fun isSecureUrlInput(url: String?): Boolean {
        val raw = url?.trim().orEmpty()
        return raw.isEmpty() || !raw.startsWith("http://", ignoreCase = true)
    }

    fun normalizeFormat(format: String?): String =
        when (format) {
            FORMAT_GLUCO_WATCH_TEXT -> FORMAT_GLUCO_WATCH_TEXT
            else -> FORMAT_OUTBOUND_JSON
        }

    fun normalizePreset(preset: String?): String =
        when (preset) {
            PRESET_TELEGRAM_BOT -> PRESET_TELEGRAM_BOT
            PRESET_VK_DIRECT -> PRESET_VK_DIRECT
            else -> PRESET_CUSTOM_JSON
        }

    fun defaultUrl(preset: String?): String =
        when (normalizePreset(preset)) {
            PRESET_TELEGRAM_BOT -> DEFAULT_TELEGRAM_UPDATES_URL
            PRESET_VK_DIRECT -> DEFAULT_VK_HISTORY_URL
            else -> ""
        }

    fun defaultFormat(preset: String?): String =
        when (normalizePreset(preset)) {
            PRESET_TELEGRAM_BOT,
            PRESET_VK_DIRECT -> FORMAT_GLUCO_WATCH_TEXT
            else -> FORMAT_OUTBOUND_JSON
        }

    fun deriveSensorId(url: String?, preset: String?, peerId: String? = null): String {
        val normalized = normalizeUrl(url)
        val normalizedPreset = normalizePreset(preset)
        val identity = when (normalizedPreset) {
            PRESET_TELEGRAM_BOT -> "${defaultUrl(normalizedPreset)}|${peerId.orEmpty().trim()}"
            PRESET_VK_DIRECT -> "${defaultUrl(normalizedPreset)}|${peerId.orEmpty().trim()}"
            else -> normalized
        }
        if (identity.isEmpty()) return SENSOR_PREFIX + "UNCONFIGURED"
        val digest = MessageDigest.getInstance("SHA-1")
            .digest("${identity.lowercase(Locale.US)}|$normalizedPreset".toByteArray(Charsets.UTF_8))
            .take(6)
            .joinToString("") { "%02X".format(Locale.US, it) }
        return SENSOR_PREFIX + digest
    }

    fun loadConfig(context: Context): Config =
        Config(
            enabled = prefs(context).getBoolean(PREF_ENABLED, false),
            preset = normalizePreset(prefs(context).getString(PREF_PRESET, null)),
            url = normalizeUrl(prefs(context).getString(PREF_URL, null)),
            token = prefs(context).getString(PREF_TOKEN, null).orEmpty(),
            peerId = prefs(context).getString(PREF_PEER_ID, null).orEmpty(),
            apiVersion = prefs(context).getString(PREF_API_VERSION, DEFAULT_VK_API_VERSION)
                .orEmpty()
                .ifBlank { DEFAULT_VK_API_VERSION },
            headers = prefs(context).getString(PREF_HEADERS, null).orEmpty(),
            format = normalizeFormat(prefs(context).getString(PREF_FORMAT, null)),
            pollSeconds = prefs(context)
                .getInt(PREF_POLL_SECONDS, DEFAULT_POLL_SECONDS)
                .coerceAtLeast(30),
        )

    fun saveConfig(
        context: Context,
        enabled: Boolean,
        preset: String?,
        url: String?,
        token: String?,
        peerId: String?,
        apiVersion: String?,
        headers: String?,
        format: String?,
        pollSeconds: Int,
    ) {
        val normalizedPreset = normalizePreset(preset)
        prefs(context).edit()
            .putBoolean(PREF_ENABLED, enabled)
            .putString(PREF_PRESET, normalizedPreset)
            .putString(PREF_URL, normalizeUrl(url).takeIf { it.isNotEmpty() })
            .putString(PREF_TOKEN, token.orEmpty())
            .putString(PREF_PEER_ID, peerId.orEmpty())
            .putString(PREF_API_VERSION, apiVersion.orEmpty().ifBlank { DEFAULT_VK_API_VERSION })
            .putString(PREF_HEADERS, headers.orEmpty())
            .putString(PREF_FORMAT, normalizeFormat(format ?: defaultFormat(normalizedPreset)))
            .putInt(PREF_POLL_SECONDS, pollSeconds.coerceAtLeast(30))
            .apply()
        ManagedSensorUiSignals.markDeviceListDirty()
    }

    fun persistedSensorIds(context: Context): List<String> =
        loadConfig(context).takeIf { it.isUsable }?.let { listOf(it.sensorId) }.orEmpty()

    fun loadTelegramUpdateOffset(context: Context): Long =
        prefs(context).getLong(PREF_TELEGRAM_UPDATE_OFFSET, 0L).coerceAtLeast(0L)

    fun saveTelegramUpdateOffset(context: Context, offset: Long) {
        prefs(context).edit()
            .putLong(PREF_TELEGRAM_UPDATE_OFFSET, offset.coerceAtLeast(0L))
            .apply()
    }

    fun createRestoredCallback(context: Context, sensorId: String, dataptr: Long): SuperGattCallback? {
        val config = loadConfig(context)
        if (!config.isUsable || !matchesSensorId(sensorId, config.sensorId)) return null
        return ApiGlucoseSourceManager(
            serial = config.sensorId,
            preset = config.normalizedPreset,
            url = config.resolvedUrl(),
            token = config.token,
            peerId = config.peerId,
            apiVersion = config.apiVersion,
            headers = config.headers,
            format = config.normalizedFormat,
            pollSeconds = config.pollSeconds,
            dataptr = dataptr,
        )
    }

    fun enableSourceSensor(
        context: Context,
        preset: String?,
        url: String?,
        token: String?,
        peerId: String?,
        apiVersion: String?,
        headers: String?,
        format: String?,
        pollSeconds: Int,
    ): String? {
        val normalizedPreset = normalizePreset(preset)
        val normalizedUrl = normalizeUrl(url).ifBlank { defaultUrl(normalizedPreset) }
        val normalizedFormat = normalizeFormat(format ?: defaultFormat(normalizedPreset))
        if (normalizedPreset == PRESET_CUSTOM_JSON && normalizedUrl.isEmpty()) return null
        if ((normalizedPreset == PRESET_TELEGRAM_BOT || normalizedPreset == PRESET_VK_DIRECT) &&
            (token.isNullOrBlank() || peerId.isNullOrBlank())
        ) {
            return null
        }
        val previous = loadConfig(context)
        val nextSensorId = deriveSensorId(normalizedUrl, normalizedPreset, peerId)
        val sameSensor = previous.isUsable && matchesSensorId(previous.sensorId, nextSensorId)
        if (previous.isUsable && !sameSensor) {
            stopSensor(previous.sensorId)
        }
        if (sameSensor && sourceRuntimeConfigChanged(
                previous = previous,
                preset = normalizedPreset,
                url = normalizedUrl,
                token = token.orEmpty(),
                peerId = peerId.orEmpty(),
                apiVersion = apiVersion.orEmpty().ifBlank { DEFAULT_VK_API_VERSION },
                headers = headers.orEmpty(),
                format = normalizedFormat,
                pollSeconds = pollSeconds.coerceAtLeast(30)
            )
        ) {
            stopSensor(nextSensorId)
        }
        if (!matchesSensorId(previous.sensorId, nextSensorId) ||
            previous.token != token.orEmpty() ||
            previous.peerId != peerId.orEmpty() ||
            previous.normalizedPreset != normalizedPreset ||
            previous.urlTemplate() != normalizedUrl
        ) {
            saveTelegramUpdateOffset(context, 0L)
        }
        saveConfig(
            context = context,
            enabled = true,
            preset = normalizedPreset,
            url = normalizedUrl,
            token = token,
            peerId = peerId,
            apiVersion = apiVersion,
            headers = headers,
            format = normalizedFormat,
            pollSeconds = pollSeconds,
        )
        connectSensor(context, nextSensorId)
        return nextSensorId
    }

    private fun sourceRuntimeConfigChanged(
        previous: Config,
        preset: String,
        url: String,
        token: String,
        peerId: String,
        apiVersion: String,
        headers: String,
        format: String,
        pollSeconds: Int,
    ): Boolean =
        previous.normalizedPreset != preset ||
            previous.urlTemplate() != url ||
            previous.token != token ||
            previous.peerId != peerId ||
            previous.apiVersion != apiVersion ||
            previous.headers != headers ||
            previous.normalizedFormat != format ||
            previous.pollSeconds != pollSeconds

    private fun Config.urlTemplate(): String =
        url.ifBlank { defaultUrl(normalizedPreset) }

    fun disableSourceSensor(context: Context) {
        val config = loadConfig(context)
        val sensorId = config.sensorId
        ManagedCurrentSensor.clearIfMatches(sensorId)
        saveConfig(
            context = context,
            enabled = false,
            preset = config.normalizedPreset,
            url = config.url,
            token = config.token,
            peerId = config.peerId,
            apiVersion = config.apiVersion,
            headers = config.headers,
            format = config.normalizedFormat,
            pollSeconds = config.pollSeconds,
        )
        stopSensor(sensorId)
    }

    private fun stopSensor(sensorId: String) {
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
}
