package tk.glucodata

import android.content.Context
import androidx.annotation.Keep
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.UUID

@Keep
object OutboundApiSettings {
    const val PRESET_CUSTOM_JSON = "custom_json"
    const val PRESET_TELEGRAM_BOT = "telegram_bot"
    const val PRESET_GLUCO_WATCH_VK = "glucowatch_vk"
    const val PRESET_VK_MESSAGES = "vk_messages"

    private const val LEGACY_PROVIDER_WEBHOOK_JSON = "webhook_json"
    private const val LEGACY_PROVIDER_VK = "vk"

    private const val PREFS = "outbound_api"
    private const val KEY_DESTINATIONS_JSON = "destinations_json"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_PROVIDER = "provider"
    private const val KEY_URL = "url"
    private const val KEY_TOKEN = "token"
    private const val KEY_CHAT_ID = "chat_id"
    private const val KEY_API_VERSION = "api_version"
    private const val KEY_HEADERS = "headers"
    private const val KEY_MESSAGE_TEMPLATE = "message_template"
    private const val KEY_MIN_INTERVAL_MINUTES = "min_interval_minutes"
    private const val KEY_TRIGGER_MODE = "trigger_mode"
    private const val KEY_TRIGGER_LOW_MGDL = "trigger_low_mgdl"
    private const val KEY_TRIGGER_HIGH_MGDL = "trigger_high_mgdl"
    private const val KEY_LAST_QUEUED_EVENT_ID = "last_queued_event_id"
    private const val KEY_LAST_QUEUED_AT_MS = "last_queued_at_ms"
    private const val KEY_LAST_ATTEMPT_AT_MS = "last_attempt_at_ms"
    private const val KEY_LAST_SUCCESS_AT_MS = "last_success_at_ms"
    private const val KEY_LAST_RESPONSE_CODE = "last_response_code"
    private const val KEY_LAST_ERROR = "last_error"

    const val DEFAULT_VK_API_VERSION = "5.199"
    const val DEFAULT_MIN_INTERVAL_MINUTES = 5
    const val DEFAULT_CUSTOM_URL = ""
    const val DEFAULT_VK_URL = "https://api.vk.com/method/messages.send"
    const val TRIGGER_ALWAYS = "always"
    const val TRIGGER_AT_OR_BELOW = "at_or_below"
    const val TRIGGER_AT_OR_ABOVE = "at_or_above"
    const val TRIGGER_OUTSIDE_RANGE = "outside_range"
    const val DEFAULT_TRIGGER_LOW_MGDL = 70
    const val DEFAULT_TRIGGER_HIGH_MGDL = 180
    const val DEFAULT_CUSTOM_TEMPLATE =
        "{value} {unit} {trend_arrow} RAW:{raw} ({rate_mgdl} mg/dL/min) IOB:{iob} COB:{cob} {time}"
    const val DEFAULT_CHAT_TEMPLATE =
        "{value} {unit} {trend_arrow} {time}"
    const val DEFAULT_GLUCO_WATCH_TEMPLATE =
        "GV:{mmol}|RAW:{raw}|TR:{trend_arrow}|AL:{alarm}|RT:{rate_mmol}|IOB:{iob}|COB:{cob}|TS:{timestamp}"

    data class Config(
        val enabled: Boolean,
        val destinations: List<Destination>
    ) {
        fun activeDestinations(): List<Destination> =
            destinations.filter { it.isReady() }

        fun findDestination(id: String?): Destination? =
            destinations.firstOrNull { it.id == id }
    }

    data class Destination(
        val id: String,
        val enabled: Boolean,
        val name: String,
        val preset: String,
        val url: String,
        val token: String,
        val chatId: String,
        val apiVersion: String,
        val headers: String,
        val messageTemplate: String,
        val minIntervalMinutes: Int,
        val triggerMode: String,
        val triggerLowMgdl: Int,
        val triggerHighMgdl: Int,
        val lastQueuedEventId: String,
        val lastQueuedAtMs: Long,
        val lastAttemptAtMs: Long,
        val lastSuccessAtMs: Long,
        val lastResponseCode: Int,
        val lastError: String?
    ) {
        fun normalizedPreset(): String = normalizePreset(preset)

        fun normalizedTriggerMode(): String = normalizeTriggerMode(triggerMode)

        fun resolvedUrl(): String {
            val trimmed = url.trim()
            val replacementToken = if (normalizedPreset() == PRESET_TELEGRAM_BOT) {
                token.trim().removePrefix("bot")
            } else {
                token.trim()
            }
            if (trimmed.isNotEmpty()) {
                return trimmed.replace("{token}", replacementToken)
            }
            return defaultUrl(normalizedPreset(), token).replace("{token}", replacementToken)
        }

        fun resolvedTemplate(): String {
            val trimmed = messageTemplate.trim()
            if (trimmed.isNotEmpty()) return trimmed
            return defaultTemplate(normalizedPreset())
        }

        fun resolvedName(): String {
            val trimmed = name.trim()
            if (trimmed.isBlank()) return defaultName(normalizedPreset())
            if (normalizedPreset() == PRESET_GLUCO_WATCH_VK && trimmed == "GlucoWatch VK") {
                return defaultName(normalizedPreset())
            }
            return trimmed
        }

        fun recipients(): List<String> =
            chatId
                .split(',', ';', '\n')
                .map { it.trim() }
                .filter { it.isNotEmpty() }

        fun isReady(globalEnabled: Boolean = true): Boolean {
            if (!globalEnabled || !enabled) return false
            if (resolvedUrl().isBlank()) return false
            return when (normalizedPreset()) {
                PRESET_TELEGRAM_BOT -> token.isNotBlank() && recipients().isNotEmpty() &&
                    recipients().all(::isTelegramRecipient)
                PRESET_GLUCO_WATCH_VK,
                PRESET_VK_MESSAGES -> token.isNotBlank() && recipients().isNotEmpty() &&
                    recipients().all(::isVkRecipient)
                else -> true
            }
        }

        fun shouldSendForGlucose(mgdl: Int): Boolean {
            if (mgdl <= 0) return false
            val low = triggerLowMgdl.coerceIn(1, 600)
            val high = triggerHighMgdl.coerceIn(1, 600)
            return when (normalizedTriggerMode()) {
                TRIGGER_AT_OR_BELOW -> mgdl <= low
                TRIGGER_AT_OR_ABOVE -> mgdl >= high
                TRIGGER_OUTSIDE_RANGE -> mgdl <= low || mgdl >= high
                else -> true
            }
        }

        fun withPreset(nextPreset: String): Destination {
            val oldPreset = normalizedPreset()
            val oldDefaultUrl = defaultUrl(oldPreset, token)
            val oldDefaultTemplate = defaultTemplate(oldPreset)
            val normalized = normalizePreset(nextPreset)
            val oldDefaultName = defaultName(oldPreset)
            return copy(
                preset = normalized,
                url = if (url.isBlank() || url == oldDefaultUrl) defaultUrl(normalized, token) else url,
                messageTemplate = if (messageTemplate.isBlank() || messageTemplate == oldDefaultTemplate) {
                    defaultTemplate(normalized)
                } else {
                    messageTemplate
                },
                name = if (name.isBlank() || name == oldDefaultName || name == "GlucoWatch VK") {
                    defaultName(normalized)
                } else {
                    name
                }
            )
        }
    }

    @JvmStatic
    fun load(context: Context = Applic.app): Config {
        val prefs = prefs(context)
        val stored = prefs.getString(KEY_DESTINATIONS_JSON, null)
        if (!stored.isNullOrBlank()) {
            val enabled = prefs.getBoolean(KEY_ENABLED, true)
            val destinations = runCatching { decodeDestinations(stored) }.getOrDefault(emptyList())
            return Config(
                enabled = true,
                destinations = if (enabled) destinations else destinations.map { it.copy(enabled = false) }
            )
        }

        val migrated = migrateLegacy(context)
        if (migrated.destinations.isNotEmpty()) {
            save(context, migrated)
        }
        return migrated
    }

    @JvmStatic
    fun save(context: Context = Applic.app, config: Config) {
        prefs(context).edit()
            .putBoolean(KEY_ENABLED, true)
            .putString(KEY_DESTINATIONS_JSON, encodeDestinations(config.destinations).toString())
            .apply()
    }

    @JvmStatic
    fun isEnabled(context: Context = Applic.app): Boolean =
        load(context).activeDestinations().isNotEmpty()

    @JvmStatic
    fun createDestination(preset: String): Destination {
        val normalized = normalizePreset(preset)
        return Destination(
            id = UUID.randomUUID().toString(),
            enabled = true,
            name = defaultName(normalized),
            preset = normalized,
            url = defaultUrl(normalized, ""),
            token = "",
            chatId = "",
            apiVersion = DEFAULT_VK_API_VERSION,
            headers = "",
            messageTemplate = defaultTemplate(normalized),
            minIntervalMinutes = DEFAULT_MIN_INTERVAL_MINUTES,
            triggerMode = TRIGGER_ALWAYS,
            triggerLowMgdl = DEFAULT_TRIGGER_LOW_MGDL,
            triggerHighMgdl = DEFAULT_TRIGGER_HIGH_MGDL,
            lastQueuedEventId = "",
            lastQueuedAtMs = 0L,
            lastAttemptAtMs = 0L,
            lastSuccessAtMs = 0L,
            lastResponseCode = 0,
            lastError = null
        )
    }

    @JvmStatic
    fun defaultUrl(preset: String, token: String = ""): String =
        when (normalizePreset(preset)) {
            PRESET_TELEGRAM_BOT -> "https://api.telegram.org/bot{token}/sendMessage"
            PRESET_GLUCO_WATCH_VK,
            PRESET_VK_MESSAGES -> DEFAULT_VK_URL
            else -> DEFAULT_CUSTOM_URL
        }

    @JvmStatic
    fun defaultTemplate(preset: String): String =
        when (normalizePreset(preset)) {
            PRESET_GLUCO_WATCH_VK -> DEFAULT_GLUCO_WATCH_TEMPLATE
            PRESET_TELEGRAM_BOT,
            PRESET_VK_MESSAGES -> DEFAULT_CHAT_TEMPLATE
            else -> DEFAULT_CUSTOM_TEMPLATE
        }

    @JvmStatic
    fun defaultName(preset: String): String =
        when (normalizePreset(preset)) {
            PRESET_TELEGRAM_BOT -> "Telegram bot"
            PRESET_GLUCO_WATCH_VK -> "VK direct message"
            PRESET_VK_MESSAGES -> "VK text message"
            else -> "Custom JSON webhook"
        }

    fun shouldQueue(context: Context, destination: Destination, eventId: String, nowMs: Long): Boolean {
        if (eventId == destination.lastQueuedEventId) {
            return false
        }
        val minIntervalMs = destination.minIntervalMinutes.coerceAtLeast(0) * 60_000L
        val lastQueuedAt = destination.lastQueuedAtMs
        return minIntervalMs <= 0L || lastQueuedAt <= 0L || nowMs - lastQueuedAt >= minIntervalMs
    }

    fun recordQueued(context: Context, destinationId: String, eventId: String, nowMs: Long) {
        updateDestination(context, destinationId) {
            it.copy(lastQueuedEventId = eventId, lastQueuedAtMs = nowMs)
        }
    }

    fun recordAttempt(context: Context, destinationId: String, responseCode: Int, error: String?) {
        updateDestination(context, destinationId) {
            it.copy(
                lastAttemptAtMs = System.currentTimeMillis(),
                lastResponseCode = responseCode,
                lastError = error?.take(500)
            )
        }
    }

    fun recordSuccess(context: Context, destinationId: String, responseCode: Int) {
        val now = System.currentTimeMillis()
        updateDestination(context, destinationId) {
            it.copy(
                lastAttemptAtMs = now,
                lastSuccessAtMs = now,
                lastResponseCode = responseCode,
                lastError = null
            )
        }
    }

    fun updateDestination(
        context: Context,
        destinationId: String,
        transform: (Destination) -> Destination
    ) {
        val config = load(context)
        val updated = config.destinations.map { destination ->
            if (destination.id == destinationId) transform(destination) else destination
        }
        save(context, config.copy(destinations = updated))
    }

    private fun migrateLegacy(context: Context): Config {
        val prefs = prefs(context)
        if (!prefs.contains(KEY_PROVIDER) &&
            !prefs.contains(KEY_URL) &&
            !prefs.contains(KEY_TOKEN) &&
            !prefs.contains(KEY_CHAT_ID)
        ) {
            return Config(enabled = false, destinations = emptyList())
        }

        val provider = prefs.getString(KEY_PROVIDER, LEGACY_PROVIDER_WEBHOOK_JSON) ?: LEGACY_PROVIDER_WEBHOOK_JSON
        val preset = if (provider == LEGACY_PROVIDER_VK) PRESET_GLUCO_WATCH_VK else PRESET_CUSTOM_JSON
        return Config(
            enabled = true,
            destinations = listOf(
                Destination(
                    id = UUID.randomUUID().toString(),
                    enabled = prefs.getBoolean(KEY_ENABLED, false),
                    name = defaultName(preset),
                    preset = preset,
                    url = prefs.getString(KEY_URL, defaultUrl(preset)).orEmpty(),
                    token = prefs.getString(KEY_TOKEN, "").orEmpty(),
                    chatId = prefs.getString(KEY_CHAT_ID, "").orEmpty(),
                    apiVersion = prefs.getString(KEY_API_VERSION, DEFAULT_VK_API_VERSION).orEmpty()
                        .ifBlank { DEFAULT_VK_API_VERSION },
                    headers = prefs.getString(KEY_HEADERS, "").orEmpty(),
                    messageTemplate = prefs.getString(KEY_MESSAGE_TEMPLATE, defaultTemplate(preset)).orEmpty(),
                    minIntervalMinutes = prefs.getInt(
                        KEY_MIN_INTERVAL_MINUTES,
                        DEFAULT_MIN_INTERVAL_MINUTES
                    ).coerceAtLeast(0),
                    triggerMode = normalizeTriggerMode(
                        prefs.getString(KEY_TRIGGER_MODE, TRIGGER_ALWAYS).orEmpty()
                    ),
                    triggerLowMgdl = prefs.getInt(KEY_TRIGGER_LOW_MGDL, DEFAULT_TRIGGER_LOW_MGDL)
                        .coerceIn(1, 600),
                    triggerHighMgdl = prefs.getInt(KEY_TRIGGER_HIGH_MGDL, DEFAULT_TRIGGER_HIGH_MGDL)
                        .coerceIn(1, 600),
                    lastQueuedEventId = prefs.getString(KEY_LAST_QUEUED_EVENT_ID, "").orEmpty(),
                    lastQueuedAtMs = prefs.getLong(KEY_LAST_QUEUED_AT_MS, 0L),
                    lastAttemptAtMs = prefs.getLong(KEY_LAST_ATTEMPT_AT_MS, 0L),
                    lastSuccessAtMs = prefs.getLong(KEY_LAST_SUCCESS_AT_MS, 0L),
                    lastResponseCode = prefs.getInt(KEY_LAST_RESPONSE_CODE, 0),
                    lastError = prefs.getString(KEY_LAST_ERROR, null)
                )
            )
        )
    }

    private fun normalizePreset(preset: String): String =
        when (preset) {
            PRESET_TELEGRAM_BOT,
            PRESET_GLUCO_WATCH_VK,
            PRESET_VK_MESSAGES -> preset
            else -> PRESET_CUSTOM_JSON
        }

    fun normalizeTriggerMode(mode: String): String =
        when (mode) {
            TRIGGER_AT_OR_BELOW,
            TRIGGER_AT_OR_ABOVE,
            TRIGGER_OUTSIDE_RANGE -> mode
            else -> TRIGGER_ALWAYS
        }

    private fun isTelegramRecipient(recipient: String): Boolean =
        recipient.matches(Regex("-?\\d+")) ||
            (recipient.startsWith("@") && recipient.length > 1)

    private fun isVkRecipient(recipient: String): Boolean =
        recipient.matches(Regex("-?\\d+"))

    private fun encodeDestinations(destinations: List<Destination>): JSONArray =
        JSONArray().also { array ->
            destinations.forEach { destination ->
                array.put(
                    JSONObject()
                        .put("id", destination.id)
                        .put("enabled", destination.enabled)
                        .put("name", destination.name)
                        .put("preset", destination.normalizedPreset())
                        .put("url", destination.url)
                        .put("token", destination.token)
                        .put("chatId", destination.chatId)
                        .put("apiVersion", destination.apiVersion)
                        .put("headers", destination.headers)
                        .put("messageTemplate", destination.messageTemplate)
                        .put("minIntervalMinutes", destination.minIntervalMinutes)
                        .put("triggerMode", destination.normalizedTriggerMode())
                        .put("triggerLowMgdl", destination.triggerLowMgdl)
                        .put("triggerHighMgdl", destination.triggerHighMgdl)
                        .put("lastQueuedEventId", destination.lastQueuedEventId)
                        .put("lastQueuedAtMs", destination.lastQueuedAtMs)
                        .put("lastAttemptAtMs", destination.lastAttemptAtMs)
                        .put("lastSuccessAtMs", destination.lastSuccessAtMs)
                        .put("lastResponseCode", destination.lastResponseCode)
                        .put("lastError", destination.lastError)
                )
            }
        }

    private fun decodeDestinations(raw: String): List<Destination> {
        val array = JSONArray(raw)
        val destinations = ArrayList<Destination>(array.length())
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val preset = normalizePreset(item.optString("preset", PRESET_CUSTOM_JSON))
            destinations += Destination(
                id = item.optString("id").ifBlank { UUID.randomUUID().toString() },
                enabled = item.optBoolean("enabled", false),
                name = item.optString("name", defaultName(preset)),
                preset = preset,
                url = item.optString("url", defaultUrl(preset)),
                token = item.optString("token", ""),
                chatId = item.optString("chatId", ""),
                apiVersion = item.optString("apiVersion", DEFAULT_VK_API_VERSION)
                    .ifBlank { DEFAULT_VK_API_VERSION },
                headers = item.optString("headers", ""),
                messageTemplate = item.optString("messageTemplate", defaultTemplate(preset)),
                minIntervalMinutes = item.optInt(
                    "minIntervalMinutes",
                    DEFAULT_MIN_INTERVAL_MINUTES
                ).coerceAtLeast(0),
                triggerMode = normalizeTriggerMode(item.optString("triggerMode", TRIGGER_ALWAYS)),
                triggerLowMgdl = item.optInt("triggerLowMgdl", DEFAULT_TRIGGER_LOW_MGDL).coerceIn(1, 600),
                triggerHighMgdl = item.optInt("triggerHighMgdl", DEFAULT_TRIGGER_HIGH_MGDL).coerceIn(1, 600),
                lastQueuedEventId = item.optString("lastQueuedEventId", ""),
                lastQueuedAtMs = item.optLong("lastQueuedAtMs", 0L),
                lastAttemptAtMs = item.optLong("lastAttemptAtMs", 0L),
                lastSuccessAtMs = item.optLong("lastSuccessAtMs", 0L),
                lastResponseCode = item.optInt("lastResponseCode", 0),
                lastError = item.optString("lastError", "").ifBlank { null }
            )
        }
        return destinations.distinctBy { it.id.lowercase(Locale.US) }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
