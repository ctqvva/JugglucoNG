package tk.glucodata

import android.content.Context
import androidx.annotation.Keep
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.UUID
import tk.glucodata.sms.SmsPolicy

@Keep
object OutboundApiSettings {
    const val PRESET_CUSTOM_JSON = "custom_json"
    const val PRESET_TELEGRAM_BOT = "telegram_bot"
    const val PRESET_GLUCO_WATCH_VK = "glucowatch_vk"
    const val PRESET_VK_MESSAGES = "vk_messages"

    /**
     * SMS is a destination in the settings sense — a place readings can go — but it
     * is never fed by the reading stream. See [tk.glucodata.sms.SmsPolicy].
     */
    const val PRESET_SMS = "sms"

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
    const val DEFAULT_REFRESH_IN_PLACE_ENABLED = true
    const val DEFAULT_REFRESH_WINDOW_MINUTES = 15
    const val DEFAULT_SUPPRESS_DELTA_BELOW_MGDL = 1
    const val DEFAULT_STALE_ENABLED = true
    const val DEFAULT_STALE_THRESHOLD_MINUTES = 10
    const val DEFAULT_MISSED_THRESHOLD_MINUTES = 15
    const val STALE_CHECK_SLACK_MS = 10_000L
    const val TUNNEL_STATUS_IN_RANGE = "in_range"
    const val TUNNEL_STATUS_HIGH = "high"
    const val TUNNEL_STATUS_LOW = "low"
    const val TUNNEL_STATUS_STALE = "stale"
    const val TUNNEL_STATUS_MISSED = "missed"
    const val DEFAULT_CUSTOM_TEMPLATE =
        "{value} {unit} {trend_arrow} RAW:{raw} ({rate_mgdl} mg/dL/min) IOB:{iob} COB:{cob} {time}"
    const val DEFAULT_CHAT_TEMPLATE =
        "{status_emoji} {value} {unit} {trend_arrow} {time}"
    // Old default before status tokens were added — used to migrate stored templates on load.
    private const val LEGACY_CHAT_TEMPLATE = "{value} {unit} {trend_arrow} {time}"
    const val DEFAULT_GLUCO_WATCH_TEMPLATE =
        "GV:{mmol}|RAW:{raw}|TR:{trend_arrow}|AL:{alarm}|RT:{rate_mmol}|IOB:{iob}|COB:{cob}|TS:{timestamp}"
    // Deliberately terse: every extra token costs a paid SMS segment.
    const val DEFAULT_SMS_TEMPLATE = "{value} {unit} {trend_arrow} {time}"

    @Volatile
    private var cachedConfig: Config? = null

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
        val lastError: String?,
        val refreshInPlaceEnabled: Boolean = DEFAULT_REFRESH_IN_PLACE_ENABLED,
        val refreshWindowMinutes: Int = DEFAULT_REFRESH_WINDOW_MINUTES,
        val suppressDeltaBelowMgdl: Int = DEFAULT_SUPPRESS_DELTA_BELOW_MGDL,
        val staleEnabled: Boolean = DEFAULT_STALE_ENABLED,
        val staleThresholdMinutes: Int = DEFAULT_STALE_THRESHOLD_MINUTES,
        val missedThresholdMinutes: Int = DEFAULT_MISSED_THRESHOLD_MINUTES,
        val lastMessageIdByRecipient: Map<String, Long> = emptyMap(),
        val lastSentAtMsByRecipient: Map<String, Long> = emptyMap(),
        val lastSentMgdlByRecipient: Map<String, Int> = emptyMap(),
        val lastStaleAtMsByRecipient: Map<String, Long> = emptyMap(),
        val smsPolicy: SmsPolicy = SmsPolicy()
    ) {
        fun isSms(): Boolean = normalizedPreset() == PRESET_SMS

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

        fun recipients(): List<String> {
            if (isSms()) return smsPolicy.numbers()
            return chatId
                .split(',', ';', '\n')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
        }

        fun isReady(globalEnabled: Boolean = true): Boolean {
            if (!globalEnabled || !enabled) return false
            // SMS has no endpoint to configure — a contact is the whole requirement.
            if (isSms()) return smsPolicy.hasUsableContacts()
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

        fun rangeStatus(mgdl: Int): String {
            if (mgdl <= 0) return TUNNEL_STATUS_IN_RANGE
            val low = triggerLowMgdl.coerceIn(1, 600)
            val high = triggerHighMgdl.coerceIn(1, 600)
            return when {
                mgdl < low -> TUNNEL_STATUS_LOW
                mgdl > high -> TUNNEL_STATUS_HIGH
                else -> TUNNEL_STATUS_IN_RANGE
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
        cachedConfig?.let { return it }
        synchronized(this) {
            cachedConfig?.let { return it }
            return loadUncached(context).also { cachedConfig = it }
        }
    }

    private fun loadUncached(context: Context): Config {
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
        val normalized = config.copy(enabled = true)
        cachedConfig = normalized
        prefs(context).edit()
            .putBoolean(KEY_ENABLED, true)
            .putString(KEY_DESTINATIONS_JSON, encodeDestinations(normalized.destinations).toString())
            .apply()
        // Adding, editing or disabling an SMS destination has to take effect without
        // waiting for the next reading — the watchdog is what makes it do anything.
        runCatching { tk.glucodata.sms.SmsWatchdog.ensureRunning(context) }
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
            lastError = null,
            refreshInPlaceEnabled = DEFAULT_REFRESH_IN_PLACE_ENABLED,
            refreshWindowMinutes = DEFAULT_REFRESH_WINDOW_MINUTES,
            suppressDeltaBelowMgdl = DEFAULT_SUPPRESS_DELTA_BELOW_MGDL,
            staleEnabled = DEFAULT_STALE_ENABLED,
            staleThresholdMinutes = DEFAULT_STALE_THRESHOLD_MINUTES,
            missedThresholdMinutes = DEFAULT_MISSED_THRESHOLD_MINUTES,
            lastMessageIdByRecipient = emptyMap(),
            lastSentAtMsByRecipient = emptyMap(),
            lastSentMgdlByRecipient = emptyMap(),
            lastStaleAtMsByRecipient = emptyMap(),
            smsPolicy = SmsPolicy().sanitized()
        )
    }

    @JvmStatic
    fun defaultUrl(preset: String, token: String = ""): String =
        when (normalizePreset(preset)) {
            PRESET_TELEGRAM_BOT -> "https://api.telegram.org/bot{token}/sendMessage"
            PRESET_GLUCO_WATCH_VK,
            PRESET_VK_MESSAGES -> DEFAULT_VK_URL
            PRESET_SMS -> ""
            else -> DEFAULT_CUSTOM_URL
        }

    @JvmStatic
    fun defaultTemplate(preset: String): String =
        when (normalizePreset(preset)) {
            PRESET_GLUCO_WATCH_VK -> DEFAULT_GLUCO_WATCH_TEMPLATE
            PRESET_SMS -> DEFAULT_SMS_TEMPLATE
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
            PRESET_SMS -> "Emergency SMS"
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
        updateDestination(context, destinationId, persist = false) {
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

    fun recordReadingArrived(
        context: Context,
        destinationId: String,
        recipient: String,
        arrivedAtMs: Long
    ) {
        updateDestination(context, destinationId) { dest ->
            // Only update the timestamp; leave messageId and lastSentMgdl unchanged
            // so suppression delta is still computed from the last-sent value.
            dest.copy(
                lastSentAtMsByRecipient = dest.lastSentAtMsByRecipient + (recipient to arrivedAtMs)
            )
        }
    }

    fun recordBubbleSent(
        context: Context,
        destinationId: String,
        recipient: String,
        messageId: Long?,
        sentAtMs: Long,
        mgdl: Int
    ) {
        if (messageId == null || messageId <= 0L) return
        updateDestination(context, destinationId) { dest ->
            dest.copy(
                lastMessageIdByRecipient = dest.lastMessageIdByRecipient +
                    (recipient to messageId),
                lastSentAtMsByRecipient = dest.lastSentAtMsByRecipient +
                    (recipient to sentAtMs),
                lastSentMgdlByRecipient = dest.lastSentMgdlByRecipient +
                    (recipient to mgdl),
                lastStaleAtMsByRecipient = dest.lastStaleAtMsByRecipient - recipient
            )
        }
    }

    fun recordStaleAt(
        context: Context,
        destinationId: String,
        recipient: String,
        staleAtMs: Long
    ) {
        updateDestination(context, destinationId) { dest ->
            dest.copy(
                lastStaleAtMsByRecipient = dest.lastStaleAtMsByRecipient +
                    (recipient to staleAtMs)
            )
        }
    }

    fun clearRecipientState(
        context: Context,
        destinationId: String,
        recipient: String
    ) {
        updateDestination(context, destinationId) { dest ->
            dest.copy(
                lastMessageIdByRecipient = dest.lastMessageIdByRecipient - recipient,
                lastSentAtMsByRecipient = dest.lastSentAtMsByRecipient - recipient,
                lastSentMgdlByRecipient = dest.lastSentMgdlByRecipient - recipient,
                lastStaleAtMsByRecipient = dest.lastStaleAtMsByRecipient - recipient
            )
        }
    }

    fun updateDestination(
        context: Context,
        destinationId: String,
        persist: Boolean = true,
        transform: (Destination) -> Destination
    ) {
        val config = load(context)
        val updated = config.destinations.map { destination ->
            if (destination.id == destinationId) transform(destination) else destination
        }
        val updatedConfig = config.copy(destinations = updated)
        if (persist) {
            save(context, updatedConfig)
        } else {
            cachedConfig = updatedConfig
        }
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
            PRESET_VK_MESSAGES,
            PRESET_SMS -> preset
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
                        .put("refreshInPlaceEnabled", destination.refreshInPlaceEnabled)
                        .put("refreshWindowMinutes", destination.refreshWindowMinutes)
                        .put("suppressDeltaBelowMgdl", destination.suppressDeltaBelowMgdl)
                        .put("staleEnabled", destination.staleEnabled)
                        .put("staleThresholdMinutes", destination.staleThresholdMinutes)
                        .put("missedThresholdMinutes", destination.missedThresholdMinutes)
                        .put(
                            "lastMessageIdByRecipient",
                            encodeLongMap(destination.lastMessageIdByRecipient)
                        )
                        .put(
                            "lastSentAtMsByRecipient",
                            encodeLongMap(destination.lastSentAtMsByRecipient)
                        )
                        .put(
                            "lastSentMgdlByRecipient",
                            encodeIntMap(destination.lastSentMgdlByRecipient)
                        )
                        .put(
                            "lastStaleAtMsByRecipient",
                            encodeLongMap(destination.lastStaleAtMsByRecipient)
                        )
                        .put("smsPolicy", SmsPolicy.encode(destination.smsPolicy))
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
                messageTemplate = run {
                    val stored = item.optString("messageTemplate", defaultTemplate(preset))
                    // Migrate the old Telegram default (pre-status-emoji) to the current default.
                    if (stored == LEGACY_CHAT_TEMPLATE &&
                        preset == PRESET_TELEGRAM_BOT
                    ) DEFAULT_CHAT_TEMPLATE else stored
                },
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
                lastError = item.optString("lastError", "").ifBlank { null },
                refreshInPlaceEnabled = item.optBoolean(
                    "refreshInPlaceEnabled",
                    DEFAULT_REFRESH_IN_PLACE_ENABLED
                ),
                refreshWindowMinutes = item.optInt(
                    "refreshWindowMinutes",
                    DEFAULT_REFRESH_WINDOW_MINUTES
                ).coerceIn(1, 60),
                suppressDeltaBelowMgdl = item.optInt(
                    "suppressDeltaBelowMgdl",
                    DEFAULT_SUPPRESS_DELTA_BELOW_MGDL
                ).coerceIn(0, 100),
                staleEnabled = item.optBoolean("staleEnabled", DEFAULT_STALE_ENABLED),
                staleThresholdMinutes = item.optInt(
                    "staleThresholdMinutes",
                    DEFAULT_STALE_THRESHOLD_MINUTES
                ).coerceIn(1, 120),
                missedThresholdMinutes = item.optInt(
                    "missedThresholdMinutes",
                    DEFAULT_MISSED_THRESHOLD_MINUTES
                ).coerceIn(
                    item.optInt("staleThresholdMinutes", DEFAULT_STALE_THRESHOLD_MINUTES)
                        .coerceIn(1, 120) + 1,
                    240
                ),
                lastMessageIdByRecipient = decodeLongMap(item.optJSONObject("lastMessageIdByRecipient")),
                lastSentAtMsByRecipient = decodeLongMap(item.optJSONObject("lastSentAtMsByRecipient")),
                lastSentMgdlByRecipient = decodeIntMap(item.optJSONObject("lastSentMgdlByRecipient")),
                lastStaleAtMsByRecipient = decodeLongMap(item.optJSONObject("lastStaleAtMsByRecipient")),
                smsPolicy = SmsPolicy.decode(item.optJSONObject("smsPolicy"))
            )
        }
        return destinations.distinctBy { it.id.lowercase(Locale.US) }
    }

    private fun encodeLongMap(values: Map<String, Long>): JSONObject =
        JSONObject().also { obj ->
            values.forEach { (key, value) -> obj.put(key, value) }
        }

    private fun encodeIntMap(values: Map<String, Int>): JSONObject =
        JSONObject().also { obj ->
            values.forEach { (key, value) -> obj.put(key, value) }
        }

    private fun decodeLongMap(obj: JSONObject?): Map<String, Long> {
        if (obj == null) return emptyMap()
        val out = LinkedHashMap<String, Long>(obj.length())
        val keys = obj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            out[key] = obj.optLong(key, 0L)
        }
        return out
    }

    private fun decodeIntMap(obj: JSONObject?): Map<String, Int> {
        if (obj == null) return emptyMap()
        val out = LinkedHashMap<String, Int>(obj.length())
        val keys = obj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            out[key] = obj.optInt(key, 0)
        }
        return out
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
