package tk.glucodata.drivers.aidex.bloodcontrol

import org.json.JSONArray
import org.json.JSONObject
import tk.glucodata.drivers.aidex.AiDexProvisionedKeys
import java.io.IOException

internal interface BloodControlCache {
    fun string(key: String): String?
    fun long(key: String): Long
    fun put(key: String, value: String)
    fun put(key: String, value: Long)
}

internal class BloodControlKeyResult(
    val keys: AiDexProvisionedKeys? = null,
    val error: String = "",
    /** Absolute deadline for a retryable failure; null for terminal/negative-cache results. */
    val retryAtMillis: Long? = null,
)

/** Serial requests are serialized, cached and rate limited, including explicit UI requests. */
internal class BloodControlAidexKeyProvider(
    private val credentials: BloodControlCredentials,
    private val transport: BloodControlTransport,
    private val cache: BloodControlCache,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private var disabled = false
    private val disabledSensors = mutableSetOf<String>()

    @Synchronized
    fun request(serialInput: String): BloodControlKeyResult {
        val serial = BloodControlProtocol.normalizeSerial(serialInput)
            ?: return failure("Проверьте серийный номер")
        if (disabled || serial in disabledSensors) return failure("BloodControl API недоступен в этом сеансе")
        activeDeadline("retry_global")?.let {
            return failure("Повторите запрос позже", it)
        }
        if (now() < cache.long("retry_$serial")) {
            return failure("Повторите запрос позже")
        }
        return try {
            var allowlist = cache.string("allowlist")?.let { runCatching { JSONArray(it) }.getOrNull() }
            if ((allowlist == null || now() - cache.long("allowlist_at") >= 5 * 60_000) &&
                now() >= cache.long("retry_allowlist")) {
                val response = post(ALLOWLIST)
                if (response != null) {
                    val entries = response.optJSONArray("allowlistAidex") ?: run {
                        disabled = true
                        return failure("Некорректный ответ BloodControl")
                    }
                    val normalized = JSONArray()
                    for (i in 0 until entries.length()) {
                        val entry = BloodControlProtocol.normalizeAllowlistEntry(entries.optString(i))
                        if (BloodControlProtocol.normalizeSerial(entry) != null) normalized.put(entry)
                    }
                    cache.put("allowlist", normalized.toString())
                    cache.put("allowlist_at", now())
                    cache.put("allowlist_failures", 0L)
                    cache.put("retry_allowlist_transient", 0L)
                    allowlist = normalized
                }
            }
            if (disabled) return failure("BloodControl API временно недоступен")
            activeDeadline("retry_global")?.let {
                return failure("BloodControl API временно недоступен", it)
            }
            val entries = allowlist ?: return failure("Не удалось проверить доступность сенсора в BloodControl",
                activeDeadline("retry_allowlist_transient"))
            if ((0 until entries.length()).none { entries.optString(it) == serial }) {
                cache.put("retry_$serial", now() + 5 * 60_000)
                return failure("Сенсор отсутствует в списке BloodControl")
            }
            val response = post(KEYS, serial)
                ?: return failure("Ключи BloodControl недоступны. Повторите запрос позже",
                    activeDeadline("retry_global"))
            val keys = BloodControlProtocol.parseKeys(response, serial)
                ?: run {
                    disabledSensors.add(serial)
                    return failure("BloodControl вернул некорректные ключи")
                }
            cache.put("failures", 0L)
            // Fetching is not a commit: the caller may discard this result after its
            // exchange changes. Only committed encrypted material supplies a cache hit.
            BloodControlKeyResult(keys)
        } catch (_: Exception) {
            // Transport failures are handled in post(). Invalid envelopes/JSON are not
            // transient network failures and must not restart automatic provisioning.
            disabled = true
            failure("Некорректный ответ BloodControl")
        }
    }

    private fun post(path: String, serial: String? = null): JSONObject? {
        val time = BloodControlProtocol.timestamp(now())
        val deviceId = cache.string("device_id") ?: java.util.UUID.randomUUID().toString().also { cache.put("device_id", it) }
        val body = BloodControlProtocol.wrap(BloodControlProtocol.plaintext(deviceId, time, serial), time, credentials)
        val response = try { transport.post(path, body) } catch (_: IOException) {
            backoff(allowlistOnly = serial == null)
            return null
        }
        // Authentication failures can be unsigned. Never parse an unsigned body.
        if (response.status == 401) { disabled = true; return null }
        if (response.status == 403) {
            if (serial == null) disabled = true else disabledSensors.add(serial)
            return null
        }
        if (!BloodControlProtocol.verify(response.body, response.signature, credentials.serverKey)) {
            disabled = true
            return null
        }
        if (response.status == 429 || response.status >= 500) { backoff(); return null }
        if (response.status != 200) {
            if (serial != null) cache.put("retry_$serial", now() + 60 * 60_000)
            else {
                // Signed client/content failures are negative cached too. Otherwise an
                // empty cache lets every explicit UI attempt repeat this failed request.
                cache.put("retry_allowlist", now() + 60 * 60_000)
                cache.put("retry_allowlist_transient", 0L)
            }
            return null
        }
        return JSONObject(response.body.toString(Charsets.UTF_8))
    }

    private fun backoff(allowlistOnly: Boolean = false) {
        val failuresKey = if (allowlistOnly) "allowlist_failures" else "failures"
        val failures = cache.long(failuresKey).coerceIn(0, 3).toInt()
        val delay = longArrayOf(15 * 60_000, 60 * 60_000, 6 * 60 * 60_000, 24 * 60 * 60_000)[failures]
        cache.put(failuresKey, failures + 1L)
        val deadline = now() + delay
        if (allowlistOnly) {
            cache.put("retry_allowlist", deadline)
            cache.put("retry_allowlist_transient", deadline)
        } else cache.put("retry_global", deadline)
    }

    private fun activeDeadline(key: String): Long? = cache.long(key).takeIf { now() < it }

    private fun failure(message: String, retryAtMillis: Long? = null) =
        BloodControlKeyResult(error = message, retryAtMillis = retryAtMillis)

    companion object {
        const val ALLOWLIST = "/v2/clients/android/allowlist"
        const val KEYS = "$ALLOWLIST/aidex-keys"
    }
}
