package tk.glucodata.drivers.aidex.bloodcontrol

import org.json.JSONArray
import org.json.JSONObject
import tk.glucodata.drivers.aidex.AiDexProvisionedKeys

internal interface BloodControlCache {
    fun string(key: String): String?
    fun long(key: String): Long
    fun put(key: String, value: String)
    fun put(key: String, value: Long)
}

internal class BloodControlKeyResult(val keys: AiDexProvisionedKeys? = null, val error: String = "")

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
        if (now() < cache.long("retry_global") || now() < cache.long("retry_$serial")) {
            return failure("Повторите запрос позже")
        }
        return try {
            var allowlist = cache.string("allowlist")?.let { runCatching { JSONArray(it) }.getOrNull() }
            if ((allowlist == null || now() - cache.long("allowlist_at") >= 5 * 60_000) &&
                now() >= cache.long("retry_allowlist")) {
                val response = post(ALLOWLIST)
                if (response != null) {
                    val entries = response.optJSONArray("allowlistAidex") ?: return failure("Некорректный ответ BloodControl")
                    val normalized = JSONArray()
                    for (i in 0 until entries.length()) {
                        val entry = BloodControlProtocol.normalizeAllowlistEntry(entries.optString(i))
                        if (BloodControlProtocol.normalizeSerial(entry) != null) normalized.put(entry)
                    }
                    cache.put("allowlist", normalized.toString())
                    cache.put("allowlist_at", now())
                    allowlist = normalized
                }
            }
            if (disabled || now() < cache.long("retry_global")) return failure("BloodControl API временно недоступен")
            val entries = allowlist ?: return failure("Не удалось проверить доступность сенсора в BloodControl")
            if ((0 until entries.length()).none { entries.optString(it) == serial }) {
                cache.put("retry_$serial", now() + 5 * 60_000)
                return failure("Сенсор отсутствует в списке BloodControl")
            }
            val response = post(KEYS, serial)
                ?: return failure("Ключи BloodControl недоступны. Повторите запрос позже")
            val keys = BloodControlProtocol.parseKeys(response, serial)
                ?: run {
                    disabledSensors.add(serial)
                    return failure("BloodControl вернул некорректные ключи")
                }
            cache.put("failures", 0L)
            // Cached material handles reconnects; bound re-fetches after sensor rejection too.
            cache.put("retry_$serial", now() + 60 * 60_000)
            BloodControlKeyResult(keys)
        } catch (_: Exception) {
            backoff()
            failure("Не удалось связаться с BloodControl")
        }
    }

    private fun post(path: String, serial: String? = null): JSONObject? {
        val time = BloodControlProtocol.timestamp(now())
        val deviceId = cache.string("device_id") ?: java.util.UUID.randomUUID().toString().also { cache.put("device_id", it) }
        val body = BloodControlProtocol.wrap(BloodControlProtocol.plaintext(deviceId, time, serial), time, credentials)
        val response = try { transport.post(path, body) } catch (_: Exception) {
            if (serial == null) cache.put("retry_allowlist", now() + 15 * 60_000) else backoff()
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
            return null
        }
        return JSONObject(response.body.toString(Charsets.UTF_8))
    }

    private fun backoff() {
        val failures = cache.long("failures").coerceIn(0, 3).toInt()
        val delay = longArrayOf(15 * 60_000, 60 * 60_000, 6 * 60 * 60_000, 24 * 60 * 60_000)[failures]
        cache.put("failures", failures + 1L)
        cache.put("retry_global", now() + delay)
    }

    private fun failure(message: String) = BloodControlKeyResult(error = message)

    companion object {
        const val ALLOWLIST = "/v2/clients/android/allowlist"
        const val KEYS = "$ALLOWLIST/aidex-keys"
    }
}
