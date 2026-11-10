// ICanHealthCloudCode.kt — per-sensor calibration-code retrieval from the Sinocare iot cloud.

package tk.glucodata.drivers.icanhealth

import android.content.Context
import android.content.SharedPreferences
import java.io.ByteArrayInputStream
import java.math.BigInteger
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.interfaces.RSAPublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.GZIPInputStream
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.json.JSONArray
import org.json.JSONObject
import tk.glucodata.Log

/**
 * Result of one per-sensor calibration-code retrieval.
 *
 * [parameterCodeJson] is the vendor `parameterCode` JSON string (the 25-float `correctCode`
 * payload with keys x1..x25); [codeFloats] is the same payload parsed into floats.
 */
internal data class ICanCloudCodeResult(
    val deviceSn: String,
    val parameterCodeJson: String,
    val codeFloats: Map<String, Float>,
)

/**
 * Vendor iot-cloud credentials for the calibration-code endpoint.
 *
 * The vendor SDK pair is bundled below. Runtime overrides are read in priority order:
 *
 * 1. SharedPreferences keys [ICanHealthConstants.PREF_CLOUD_APP_ID],
 *    [ICanHealthConstants.PREF_CLOUD_APP_SECRET], [ICanHealthConstants.PREF_CLOUD_BASE_URL] —
 *    in the app's main preference file, e.g. set via an adb shell on a debug build.
 * 2. `<filesDir>/ican-cloud-creds.json` with fields `appId`, `appSecret`, `base`.
 * 3. The bundled SDK tenant configuration.
 *
 * These are the same values the vendor SDK itself carries in
 * `com.sinocare.cgm.cgm_sdk.cgm.EnvConfigEnum`; `base` defaults to the RU tenant when omitted.
 */
internal data class ICanCloudCreds(val appId: String, val appSecret: String, val baseUrl: String)

internal object ICanHealthCloudCode {
    private const val TAG = "ICanHealthCloud"
    private const val PREFS_NAME = "tk.glucodata_preferences"
    private const val RETRY_THROTTLE_MS = 10L * 60L * 1000L

    private val executor = java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable).apply { name = "ICanCloudCode"; isDaemon = true }
    }
    private val attemptsInFlight = ConcurrentHashMap.newKeySet<String>()
    private val lastAttemptMs = ConcurrentHashMap<String, Long>()
    private val retryCounts = ConcurrentHashMap<String, Int>()
    private val retryScheduled = ConcurrentHashMap.newKeySet<String>()
    private val retryFutures = ConcurrentHashMap<String, java.util.concurrent.ScheduledFuture<*>>()
    private val fetchOwnership = ICanHealthCloudFetchOwnership()
    private val retryExecutor = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable).apply { name = "ICanCloudRetry"; isDaemon = true }
    }

    /** Enqueue a fetch for `sn` on the background worker. Never throws, never blocks. */
    @JvmStatic
    fun fetchAndCacheAsync(context: Context?, sn: String?, force: Boolean = false): Boolean {
        val normalizedSn = (sn ?: "").trim()
        if (context == null || ICanHealthProvisioningPolicy.factoryCode(normalizedSn) == null) return false
        val ticket = fetchOwnership.begin(normalizedSn)
        return enqueueFetch(context, normalizedSn, force, ticket)
    }

    private fun enqueueFetch(context: Context, normalizedSn: String, force: Boolean,
                             ticket: ICanHealthCloudFetchOwnership.Ticket): Boolean {
        if (!fetchOwnership.current(ticket)) return false
        val prefs = runCatching {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }.getOrNull() ?: return false
        if (cachedCode(context, normalizedSn) != null) {
            cancelRetry(normalizedSn)
            return false
        }
        val now = System.currentTimeMillis()
        val last = lastAttemptMs[normalizedSn] ?: 0L
        if (!force && now - last < RETRY_THROTTLE_MS) return false
        if (!attemptsInFlight.add(normalizedSn)) return false
        lastAttemptMs[normalizedSn] = now
        executor.execute {
            try {
                if (!fetchOwnership.current(ticket)) return@execute
                val candidates = ICanCloudCredsLoader.load(context, prefs)
                val result = ICanCloudCredsLoader.tryInOrder(candidates) { creds ->
                    if (candidates.size > 1) {
                        Log.i(TAG, "Trying vendor cloud candidate ${creds.appId} (${creds.baseUrl})")
                    }
                    fetch(context, creds, normalizedSn)
                }
                if (!fetchOwnership.current(ticket)) return@execute
                if (result != null) {
                    val committed = fetchOwnership.commitIfCurrent(ticket) {
                        prefs.edit()
                            .putString(ICanHealthConstants.PREF_CGM_CODE_PREFIX + normalizedSn, result.parameterCodeJson)
                            .commit()
                    }
                    if (!committed) {
                        scheduleRetry(context, normalizedSn, ticket)
                        return@execute
                    }
                    Log.i(TAG, "Cached vendor calibration code for ${maskSn(normalizedSn)} (${result.codeFloats.size} floats)")
                    retryCounts.remove(normalizedSn)
                    retryScheduled.remove(normalizedSn)
                } else {
                    scheduleRetry(context, normalizedSn, ticket)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Cloud fetch for ${maskSn(normalizedSn)} failed: ${t.message ?: t.javaClass.simpleName}")
                if (fetchOwnership.current(ticket)) scheduleRetry(context, normalizedSn, ticket)
            } finally {
                attemptsInFlight.remove(normalizedSn)
            }
        }
        return true
    }

    private fun scheduleRetry(context: Context, sn: String, ticket: ICanHealthCloudFetchOwnership.Ticket) {
        if (!fetchOwnership.current(ticket)) return
        val attempt = retryCounts.merge(sn, 1, Int::plus) ?: 1
        val delayMs = (30_000L * (1L shl (attempt - 1).coerceAtMost(4))).coerceAtMost(RETRY_THROTTLE_MS)
        if (retryScheduled.contains(sn)) return
        retryScheduled.add(sn)
        Log.i(TAG, "Scheduling cloud fetch retry for ${maskSn(sn)} in ${delayMs / 1000}s (attempt $attempt)")
        val future = retryExecutor.schedule({
            retryScheduled.remove(sn)
            retryFutures.remove(sn)
            if (!fetchOwnership.current(ticket) || !ICanHealthRegistry.isDeviceSnRegistered(context, sn)) {
                Log.i(TAG, "Cloud fetch retry skipped: sensor for ${maskSn(sn)} no longer registered")
                retryCounts.remove(sn)
                return@schedule
            }
            enqueueFetch(context, sn, true, ticket)
        }, delayMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        retryFutures[sn] = future
    }

    /** Cancel any pending retry for `sn` (e.g. when its sensor is removed). */
    @JvmStatic
    fun cancelRetry(sn: String?) {
        val normalized = (sn ?: "").trim()
        if (normalized.isEmpty()) return
        fetchOwnership.cancel(normalized)
        retryScheduled.remove(normalized)
        retryCounts.remove(normalized)
        retryFutures.remove(normalized)?.cancel(false)
    }

    /** Cached parameterCode JSON for `sn`, or null. Prefers the cloud cache; falls back
     *  to a manually pushed table file (see [tableFileName]). */
    @JvmStatic
    fun cachedCode(context: Context, sn: String): String? {
        val normalized = (sn ?: "").trim()
        val prefs = runCatching {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }.getOrNull() ?: return null
        val stored = try {
            prefs.getString(ICanHealthConstants.PREF_CGM_CODE_PREFIX + normalized, null)
        } catch (t: Throwable) {
            Log.stack(TAG, "cachedCode", t)
            null
        }
        if (!stored.isNullOrBlank()) {
            if (ICanHealthCloudCodeProtocol.parseProvisioningCode(stored) != null) return stored
            // Older builds may have cached the server fallback as a genuine per-SN row.
            prefs.edit().remove(ICanHealthConstants.PREF_CGM_CODE_PREFIX + normalized).commit()
        }
        val file = java.io.File(context.filesDir, tableFileName(normalized))
        if (!file.isFile) return null
        return runCatching { parseTableFileContent(file.readText()) }.getOrNull()
    }

    /** Table file pushed from a PC (see [parseTableFileContent]); `sn` is the short box serial. */
    internal fun tableFileName(sn: String): String = "ican-cgm-code-$sn.json"

    /**
     * Manual fallback source for the per-SN table when the phone's own network cannot
     * reach the vendor cloud: the table is fetched on a PC with
     * `tools/ican-capture/fetch_cgm_code.py` and pushed to `<filesDir>/ican-cgm-code-<sn>.json`.
     * Accepts the raw parameterCode JSON or the full response envelope; the content must
     * parse into a complete x1..x15 table.
     */
    internal fun parseTableFileContent(text: String): String? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        val candidate = runCatching {
            val json = JSONObject(trimmed)
            json.optJSONObject("data")?.optString("code")?.takeIf { it.isNotBlank() } ?: trimmed
        }.getOrDefault(trimmed)
        val floats = ICanHealthCloudCodeProtocol.parseProvisioningCode(candidate) ?: return null
        for (index in 1..ICanHealthCorrectCode.TABLE_VALUES) {
            if (!floats.containsKey("x$index")) return null
        }
        return candidate
    }

    /** True while a fetch worker attempt for `sn` is queued or running. */
    @JvmStatic
    fun isFetchInFlight(sn: String): Boolean = attemptsInFlight.contains(sn.trim())

    /**
     * The vendor cloud's own answer for a device SN it has no row for, captured 2026-10-23
     * with blind probes (two different unknown SNs returned this table byte-identically,
     * stable across repeat requests). Retained as reference data; this row is rejected at
     * every provisioning input. The SDK list builder derives x13 from the box serial.
     */
    @JvmStatic
    fun defaultCorrectCode(): Map<String, Float> = ICanHealthProvisioningPolicy.defaultTable

    /** Synchronous fetch; must be called off the main thread (mirror AiDexCnCloudClient). */
    @JvmStatic
    fun fetch(context: Context, creds: ICanCloudCreds?, sn: String): ICanCloudCodeResult? {
        if (creds == null) {
            Log.w(TAG, "No vendor cloud credentials configured; fetch unavailable")
            return null
        }
        val response = try {
            ICanHealthCloudCodeProtocol.getResponse(creds, sn)
        } catch (t: Throwable) {
            Log.w(TAG, "request failed: ${t.message ?: t.javaClass.simpleName}")
            return null
        } ?: return null
        val codeJson = try {
            ICanHealthCloudCodeProtocol.decodeResponse(response.k, response.v)
        } catch (t: Throwable) {
            Log.w(TAG, "envelope decode failed: ${t.message ?: t.javaClass.simpleName}")
            return null
        } ?: return null
        val floats = ICanHealthCloudCodeProtocol.parseProvisioningCode(codeJson) ?: run {
            Log.w(TAG, "Parameter row rejected: incomplete or generic; verify the factory serial")
            return null
        }
        return ICanCloudCodeResult(deviceSn = sn, parameterCodeJson = codeJson, codeFloats = floats)
    }

    /** Serial masked for logs: length plus a short tail, mirroring the driver's masking. */
    internal fun maskSn(sn: String?): String {
        val normalized = (sn ?: "").trim()
        if (normalized.isEmpty()) return "<empty>"
        return "len=${normalized.length}, suffix=${normalized.takeLast(3)}"
    }
}

/**
 * HTTP socket + envelope plumbing, extracted so the pure parts stay testable without Android.
 */
internal object ICanHealthCloudCodeProtocol {
    private const val TAG = "ICanHealthCloud"
    private const val CODE_PATH = "sino-deviceaccess/sdk/cgm-code-encrypt"
    private const val DEFAULT_BASE_URL = "https://iot.ru.sinocareintl.com/api/"
    private const val TIMEOUT_MS = 90_000

    /**
     * 1024-bit RSA public key shipped inside the vendor SDK dex (`RSAUtils`, f4812d key). The
     * server signs/encrypts the AES key of every response with its private counterpart, so
     * every client carries and uses this key to recover it. It opens nothing and is not a secret.
     */
    private const val RESPONSE_RSA_PUBLIC_B64 = (
        "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQC+RO5/amIJ96LEzlVwWE8ZbIvmdzquqcKNgHRzivSX7"
            + "+AtQYT52hgmcEWbP2dgNvElxgOadrBkw+xEYwuglcBoPubirpQJyKGgTbUSDNt357vOEOLiUT4pcMe"
            + "Qix/aDY8INulFWMuo7+SPrGyBtTVD9US1k7k6bEQ3MXFjh19hbQIDAQAB"
        )

    data class ResponseEnvelope(val k: String, val v: String)

    /**
     * The vendor sign scheme, confirmed against the SDK's own signer on-device:
     * `uppercase(MD5("appId=<a>&deviceSn=<sn>&timestamp=<ms>&key=<secret>"))`.
     */
    fun sign(appId: String, appSecret: String, deviceSn: String, timestampMs: String): String {
        val signString = "appId=$appId&deviceSn=$deviceSn&timestamp=$timestampMs&key=$appSecret"
        return md5Hex(signString).uppercase(Locale.US)
    }

    fun md5Hex(value: String): String =
        MessageDigest.getInstance("MD5").digest(value.toByteArray(Charsets.UTF_8)).toHexLower()

    private fun ByteArray.toHexLower(): String = joinToString("") { "%02x".format(it) }

    fun buildRequestUrl(creds: ICanCloudCreds, deviceSn: String, timestampMs: String): String {
        val base = creds.baseUrl.ifBlank { DEFAULT_BASE_URL }
        val separator = if (base.endsWith("/")) "" else "/"
        val appId = urlEncode(creds.appId)
        val sn = urlEncode(deviceSn)
        val timestamp = urlEncode(timestampMs)
        val signature = urlEncode(sign(creds.appId, creds.appSecret, deviceSn, timestampMs))
        return "$base$separator$CODE_PATH?appId=$appId&deviceSn=$sn&timestamp=$timestamp&sign=$signature"
    }

    private fun urlEncode(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")

    /**
     * One complete HTTP round trip. Returns the raw envelope fields, or null with the reason
     * logged; never throws for transport problems.
     */
    fun getResponse(creds: ICanCloudCreds, deviceSn: String): ResponseEnvelope? =
        request(buildRequestUrl(creds, deviceSn, System.currentTimeMillis().toString()))

    private fun request(url: String): ResponseEnvelope? {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
            }
            val httpCode = connection.responseCode
            val stream = if (httpCode in 200..299) connection.inputStream else connection.errorStream
            val responseText = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (httpCode !in 200..299) {
                Log.w(TAG, "http=$httpCode ${responseText.take(160)}")
                return null
            }
            val json = JSONObject(responseText)
            val data = json.optJSONObject("data")
            val k = data?.optString("k").orEmpty()
            val v = data?.optString("v").orEmpty()
            if (k.isBlank() || v.isBlank()) {
                Log.w(TAG, "response without envelope fields")
                return null
            }
            ResponseEnvelope(k, v)
        } catch (t: Throwable) {
            Log.w(TAG, "GET failed: ${t.message ?: t.javaClass.simpleName}")
            null
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * Decode the response envelope offline: `k` is the AES key encrypted with the vendor's
     * RSA-1024 pair (recovered with a public-op against the SDK's public key), `v` is the
     * AES-256-CBC payload with IV = the first 16 ASCII chars of the key. The plaintext may be
     * gzip-compressed; the unpadding follows the vendor's type-1/type-2/raw variants.
     */
    fun decodeResponse(kB64: String, vB64: String, responsePublicKeyB64: String = RESPONSE_RSA_PUBLIC_B64): String? {
        val publicKey = publicKey(responsePublicKeyB64) ?: return null
        val aesKey = rsaPublicOp(publicKey, decodeBase64(kB64) ?: return null)
        if (aesKey.size != 32) {
            Log.w(TAG, "unexpected AES key length ${aesKey.size}")
            return null
        }
        val key = aesKey.toString(Charsets.ISO_8859_1)
        val plaintext = decryptAesCbcNoPad(decodeBase64(vB64) ?: return null, aesKey, aesKey.copyOfRange(0, 16))
            ?: return null
        val body = maybeGunzip(plaintext) ?: return null
        return parseEnvelope(body)
    }

    private fun publicKey(b64: String): RSAPublicKey? = runCatching {
        val spec = X509EncodedKeySpec(decodeBase64(b64)!!)
        KeyFactory.getInstance("RSA").generatePublic(spec) as RSAPublicKey
    }.getOrNull()

    /**
     * Vendor's unpadding, ported from `RSAUtils`: a type-1 PKCS#1 block splits on the first
     * 0x00 after the padding, a type-2 block on the last 0x00, and any other shape is stripped
     * of leading zeros.
     */
    private fun rsaPublicOp(publicKey: RSAPublicKey, ciphertext: ByteArray): ByteArray {
        val modulus = publicKey.modulus
        val exponent = publicKey.publicExponent
        val blockLength = (modulus.bitLength() + 7) / 8
        var block = ciphertext
        while (block.size > blockLength) block = block.copyOfRange(1, block.size)
        val padded = BigInteger(1, block).modPow(exponent, modulus).toFixedLength(blockLength)
        return when {
            padded[0] == 0.toByte() && padded[1] == 1.toByte() -> {
                val sep = padded.drop(2).indexOf(0.toByte()) + 2
                padded.copyOfRange(sep + 1, padded.size)
            }
            padded[0] == 0.toByte() && padded[1] == 2.toByte() -> {
                val sep = padded.lastIndexOf(0.toByte())
                padded.copyOfRange(sep + 1, padded.size)
            }
            else -> padded.dropWhile { it == 0.toByte() }.toByteArray()
        }
    }

    private fun BigInteger.toFixedLength(length: Int): ByteArray {
        val raw = toByteArray()
        return when {
            raw.size == length -> raw
            raw.size == length + 1 && raw[0] == 0.toByte() -> raw.copyOfRange(1, raw.size)
            raw.size <= length -> ByteArray(length).also { System.arraycopy(raw, 0, it, length - raw.size, raw.size) }
            else -> throw IllegalStateException("RSA block longer than modulus")
        }
    }

    private fun decryptAesCbcNoPad(
        ciphertext: ByteArray,
        key: ByteArray,
        iv: ByteArray,
    ): ByteArray? = runCatching {
        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        cipher.doFinal(ciphertext)
    }.getOrNull()

    private fun maybeGunzip(bytes: ByteArray): ByteArray? = if (bytes.size > 2 &&
        bytes[0] == 0x1F.toByte() && bytes[1] == 0x8B.toByte()
    ) {
        runCatching { GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() } }
            .getOrNull()
    } else {
        bytes
    }

    /**
     * `{"code":200,"data":{"code":"<parameterCode JSON string>"}}` — the inner code is JSON
     * encoded again. Trailing bytes after the outer envelope are tolerated, like the vendor's
     * streaming JSON parser.
     */
    internal fun parseEnvelope(body: ByteArray): String? = runCatching {
        val json = JSONObject(String(body, Charsets.UTF_8))
        if (json.optInt("code", 0) != 200) return null
        val data = json.optJSONObject("data") ?: return null
        val code = data.optString("code").let { if (it.isEmpty()) null else it }
            ?: data.opt("code")?.toString()
            ?: return null
        code.ifBlank { null }
    }.getOrNull()

    /** The `correctCode` JSON: `{"x1":"0.4",..., "x25":"…" }` in string or number form. */
    internal fun parseCodeFloats(codeJson: String): Map<String, Float>? = runCatching {
        val json = JSONObject(codeJson)
        val result = LinkedHashMap<String, Float>()
        for (index in 1..25) {
            val raw = json.opt("x$index") ?: return null
            val value = when (raw) {
                is String -> raw.toFloat()
                is Number -> raw.toFloat()
                else -> return null
            }
            if (!value.isFinite()) return null
            result["x$index"] = value
        }
        result
    }.getOrNull()

    /** Shared by fetched responses, old cache entries and manually supplied files. */
    internal fun parseProvisioningCode(codeJson: String): Map<String, Float>? =
        parseCodeFloats(codeJson)?.takeUnless { ICanHealthProvisioningPolicy.isGenericTable(it) }

    private fun decodeBase64(encoded: String): ByteArray? = runCatching {
        val normalized = encoded.trim().replace("\\s", "")
        val padding = (4 - normalized.length % 4) % 4
        java.util.Base64.getDecoder().decode(normalized + "=".repeat(padding))
    }.getOrNull()

    internal val defaultBaseUrl: String get() = DEFAULT_BASE_URL
}

/**
 * Runtime credential resolution. Order: prefs override, `<filesDir>/ican-cloud-creds.json`
 * (single object or an array of them), then the bundled vendor SDK pair below. The first
 * candidate the fetch worker succeeds with wins.
 *
 * The bundled pair is the vendor SDK's own cloud key pair, identical in every iCan/Luch
 * vendor build (CE and RU alike); it was recovered at runtime from the plaintext-side
 * `RUSSIA_ENV` of `ru.luch.testcgm` and is published deliberately as a bundled vendor key,
 * mirroring `ICanHealthConstants.DEFAULT_CORRECT_CODE_AES_KEY_ASCII`. It is not user data.
 */
internal object ICanCloudCredsLoader {
    internal const val CREDS_FILE_NAME = "ican-cloud-creds.json"

    /** Vendor SDK cloud key pair; see the object kdoc for provenance. */
    internal const val BUNDLED_CLOUD_APP_ID = "610001"
    internal const val BUNDLED_CLOUD_APP_SECRET = "1395554be35742351fe165a69b50e0f8"

    fun load(context: Context, prefsOverride: SharedPreferences): List<ICanCloudCreds> {
        val candidates = ArrayList<ICanCloudCreds>(3)
        prefsOverride.also { prefs ->
            val appId = prefs.getString(ICanHealthConstants.PREF_CLOUD_APP_ID, null)?.trim()
            val secret = prefs.getString(ICanHealthConstants.PREF_CLOUD_APP_SECRET, null)?.trim()
            val base = prefs.getString(ICanHealthConstants.PREF_CLOUD_BASE_URL, null)?.trim()
            if (!appId.isNullOrBlank() && !secret.isNullOrBlank()) {
                candidates.add(ICanCloudCreds(appId, secret, base.orEmpty()))
            }
        }
        val file = java.io.File(context.filesDir, CREDS_FILE_NAME)
        if (file.isFile) {
            runCatching { file.readText() }.getOrNull()?.let { parseCredsJson(it)?.let { entries -> candidates.addAll(entries) } }
        }
        candidates.add(ICanCloudCreds(BUNDLED_CLOUD_APP_ID, BUNDLED_CLOUD_APP_SECRET, ""))
        return candidates.distinct()
    }

    /**
     * Fetch by trying candidates in order; the first non-null result wins. Pure helper so
     * the ordering policy is testable without network access.
     */
    internal fun <T> tryInOrder(
        candidates: List<ICanCloudCreds>,
        attempt: (ICanCloudCreds) -> T?,
    ): T? {
        for (creds in candidates) {
            val result = attempt(creds) ?: continue
            return result
        }
        return null
    }

    /** Accepts a single creds object or an array of them, tolerating per-entry blanks. */
    internal fun parseCredsJson(text: String): List<ICanCloudCreds>? {
        return runCatching {
            val trimmed = text.trim()
            if (trimmed.startsWith("[")) {
                val array = JSONArray(trimmed)
                (0 until array.length()).mapNotNull { i ->
                    entry(array.getJSONObject(i))
                }
            } else {
                val only = entry(JSONObject(trimmed)) ?: return null
                listOf(only)
            }
        }.getOrNull()
    }

    private fun entry(json: JSONObject): ICanCloudCreds? {
        val appId = json.optString("appId").trim()
        val secret = json.optString("appSecret").trim()
        if (appId.isEmpty() || secret.isEmpty()) return null
        return ICanCloudCreds(appId, secret, json.optString("base", "").trim())
    }
}

/**
 * The offline fallback ladder for the correctCode provisioning write, decided at
 * `scheduleCorrectCodeWrite` time:
 *
 * 1. [`USE_CACHE`] — the per-SN table the cloud delivered earlier (the real per-sensor
 *    table; the encoder's list builder derives x13 from the box serial).
 * 2. [`WAIT_FETCH`] — the cache is empty but a fetch worker attempt is in flight for
 *    this SN; defer the decision briefly so an online sensor gets its real table
 *    instead of the fallback.
 * 3. [`USE_DEFAULT`] — an explicitly supplied fallback, or null when none is allowed.
 *    The production caller supplies null, so missing genuine parameters skip the write.
 */
internal object ICanCloudOfflineLadder {
    internal enum class Source { USE_CACHE, WAIT_FETCH, USE_DEFAULT }

    internal data class Decision(val source: Source, val table: Map<String, Float>?)

    internal fun decide(
        cachedTable: Map<String, Float>?,
        fetchInFlight: Boolean,
        defaultTable: Map<String, Float>?,
    ): Decision = when {
        !cachedTable.isNullOrEmpty() -> Decision(Source.USE_CACHE, cachedTable)
        fetchInFlight -> Decision(Source.WAIT_FETCH, null)
        !defaultTable.isNullOrEmpty() -> Decision(Source.USE_DEFAULT, defaultTable)
        else -> Decision(Source.USE_DEFAULT, null)
    }
}
