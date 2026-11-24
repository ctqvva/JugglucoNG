package tk.glucodata.drivers.bioe

import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import tk.glucodata.BuildConfig

data class BioeServerDevice(
    val deviceCode: String,
    val kRaw: String,
    val bRaw: String,
    val thresholdRaw: String,
    val btName: String,
    val btMac: String,
    val useAtMs: Long,
    val sensorLifetimeDays: Int,
    val algorithmStartMinutes: Int,
    val quickInitCount: Int,
    val quickInitInterval: Int,
) {
    val calibration: BioeCalibration?
        get() {
            val k = kRaw.toDoubleOrNull() ?: return null
            val b = bRaw.toDoubleOrNull() ?: return null
            val threshold = thresholdRaw.toDoubleOrNull() ?: BioeConstants.DEFAULT_LOW_CURRENT_NA
            val result = BioeCalibration(k, b, threshold)
            return result.takeIf { it.isUsable }
        }
}

sealed class BioeApiResult<out T> {
    data class Success<out T>(val value: T) : BioeApiResult<T>()
    data class HttpError(val code: Int) : BioeApiResult<Nothing>()
    data class TransportError(val message: String) : BioeApiResult<Nothing>()
    data class ProtocolError(val message: String) : BioeApiResult<Nothing>()
}

data class BioeHttpResult(val code: Int, val body: String)

interface BioeApiTransport {
    fun post(url: String, jsonBody: String, headers: Map<String, String>): BioeHttpResult
}

internal class BioeApiProtocolException(message: String) : Exception(message)

object BioeApi {
    internal const val TRANSPORT_FAILURE_CODE = -1

    fun md5(value: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 0xFF) }
    }

    fun sign(timestampMs: Long): String = md5(
        BioeConstants.SIGN_PREFIX +
            timestampMs +
            BioeConstants.SIGN_SECRET +
            BioeConstants.SIGN_SUFFIX
    )

    fun requestBody(token: String, timestampMs: Long): String = JSONObject()
        .put("token", token)
        .put("sign", sign(timestampMs))
        .put("nowTimestamp", timestampMs)
        .toString()

    fun requestHeaders(token: String, timestampMs: Long): Map<String, String> {
        val requestId = md5(timestampMs.toString() + UUID.randomUUID().toString())
        return linkedMapOf(
            "djs-api-token" to token,
            "djs-client-type" to BioeConstants.API_CLIENT_TYPE,
            "djs-timestamp" to timestampMs.toString(),
            "djs-client-version-no" to BuildConfig.VERSION_CODE.toString(),
            "djs-request-id" to requestId,
            "djs-client-language" to Locale.getDefault().language,
            "djs-product-name" to BioeConstants.API_PRODUCT_NAME,
            "djs-app-version" to BioeConstants.API_APP_VERSION,
            "Content-Type" to "application/json",
            "Accept" to "application/json",
        )
    }

    fun fetchDevices(
        token: String,
        timestampMs: Long = System.currentTimeMillis(),
        transport: BioeApiTransport = HttpTransport,
    ): BioeApiResult<List<BioeServerDevice>> {
        if (token.isBlank()) return BioeApiResult.ProtocolError("empty token")
        val trimmedToken = token.trim()
        return try {
            val response = transport.post(
                BioeConstants.API_BASE_URL + BioeConstants.API_DEVICE_LIST_PATH,
                requestBody(trimmedToken, timestampMs),
                requestHeaders(trimmedToken, timestampMs),
            )
            when {
                response.code == TRANSPORT_FAILURE_CODE ->
                    BioeApiResult.TransportError(response.body.ifBlank { "network unavailable" })
                response.code !in 200..299 -> BioeApiResult.HttpError(response.code)
                else -> BioeApiResult.Success(parseDeviceList(response.body))
            }
        } catch (error: BioeApiProtocolException) {
            BioeApiResult.ProtocolError(error.message ?: "invalid server response")
        } catch (error: Throwable) {
            BioeApiResult.TransportError(error.message ?: error.javaClass.simpleName)
        }
    }

    fun selectDevice(devices: List<BioeServerDevice>, deviceCode: String?): BioeServerDevice? {
        val code = deviceCode?.trim()?.uppercase(Locale.ROOT).orEmpty()
        return devices.firstOrNull {
            it.deviceCode.equals(code, ignoreCase = true) && it.calibration != null
        } ?: devices.firstOrNull { it.calibration != null }
    }

    fun selectDeviceExact(devices: List<BioeServerDevice>, deviceCode: String?): BioeServerDevice? {
        val code = deviceCode?.trim()?.uppercase(Locale.ROOT).orEmpty()
        if (code.isEmpty()) return devices.firstOrNull { it.calibration != null }
        return devices.firstOrNull {
            it.deviceCode.trim().uppercase(Locale.ROOT) == code && it.calibration != null
        }
    }

    fun parseDeviceList(body: String): List<BioeServerDevice> {
        val root = JSONObject(body)
        val succeeded = root.optBoolean("result", false) || root.optInt("code", -1) == 0
        if (!succeeded) {
            throw BioeApiProtocolException(root.optString("msg").ifBlank { "server returned failure" })
        }
        val data = root.optJSONObject("data") ?: root.optJSONObject("source")
            ?: throw BioeApiProtocolException("missing data object")
        val list = data.optJSONArray("list") ?: return emptyList()
        return (0 until list.length()).mapNotNull { index ->
            list.optJSONObject(index)?.let(::parseDevice)
        }
    }

    private fun parseDevice(item: JSONObject): BioeServerDevice = BioeServerDevice(
        deviceCode = text(item, "deviceCode"),
        kRaw = text(item, "convNum"),
        bRaw = text(item, "verifyVal"),
        thresholdRaw = text(item, "lowCurrentThresholdNa"),
        btName = text(item, "btName"),
        btMac = text(item, "btMac"),
        useAtMs = longValue(item, "useAt"),
        sensorLifetimeDays = intValue(item, "sensorLifetimeDays"),
        algorithmStartMinutes = intValue(item, "algorithmStartMinutes"),
        quickInitCount = intValue(item, "quickInitCount"),
        quickInitInterval = intValue(item, "quickInitInterval"),
    )

    private fun text(item: JSONObject, key: String): String {
        if (item.isNull(key)) return ""
        return when (val value = item.opt(key) ?: return "") {
            is String -> value.trim()
            else -> value.toString().trim()
        }
    }

    private fun longValue(item: JSONObject, key: String): Long =
        text(item, key).toLongOrNull() ?: 0L

    private fun intValue(item: JSONObject, key: String): Int =
        text(item, key).toIntOrNull() ?: 0

    internal object HttpTransport : BioeApiTransport {
        override fun post(url: String, jsonBody: String, headers: Map<String, String>): BioeHttpResult {
            var connection: HttpURLConnection? = null
            return try {
                connection = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = BioeConstants.API_CONNECT_TIMEOUT_MS
                    readTimeout = BioeConstants.API_READ_TIMEOUT_MS
                    doOutput = true
                    headers.forEach { (name, value) -> setRequestProperty(name, value) }
                }
                val bytes = jsonBody.toByteArray(Charsets.UTF_8)
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { it.write(bytes) }
                val code = connection.responseCode
                BioeHttpResult(code, readBody(connection, code))
            } catch (error: Throwable) {
                BioeHttpResult(TRANSPORT_FAILURE_CODE, error.message ?: "network unavailable")
            } finally {
                runCatching { connection?.disconnect() }
            }
        }

        private fun readBody(connection: HttpURLConnection, code: Int): String {
            val stream = if (code in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream ?: runCatching { connection.inputStream }.getOrNull()
            } ?: return ""
            return BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { reader ->
                buildString {
                    var line = reader.readLine()
                    while (line != null) {
                        append(line)
                        line = reader.readLine()
                    }
                }
            }
        }
    }
}
