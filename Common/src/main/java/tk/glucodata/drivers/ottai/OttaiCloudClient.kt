package tk.glucodata.drivers.ottai

import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

data class DeviceMaterials(
    val mac: String,
    val keyABase64: String = "",
    val keyAPlaintext: String = "",
    val method: String = "",
    val coefficient: String = "",
    val produceTimeMs: Long = 0L,
    val activeTimeMs: Long = 0L,
    val activeExpireTimeMs: Long = 0L,
    val retainTimeMs: Long = OttaiConstants.DEFAULT_RETAIN_TIME_MS,
    val methodUpdateTimeMs: Long = 0L,
    val coeffUpdateTimeMs: Long = 0L,
    val deviceVersion: String = "",
    val authKeys: List<ByteArray> = emptyList(),
)

data class LoginResult(
    val accessToken: String,
    val glucoseSecretKey: String,
    val userId: String,
)

class OttaiCloudClient(private val apiBase: String = OttaiConstants.API_BASE) {

    private val deviceId = "juggluco0test001"
    private var accessToken: String? = null
    private var glucoseSecretKey: String? = null
    private var userId: String? = null

    fun getToken(): String? = accessToken
    fun getSecretKey(): String? = glucoseSecretKey
    fun getUserId(): String? = userId

    fun setCredentials(token: String, secretKey: String, id: String) {
        accessToken = token
        glucoseSecretKey = secretKey
        userId = id
    }

    // ---- SMS Login ----

    fun requestApiToken(): String? {
        val ts = nowMs()
        val sig = OttaiCrypto.signApiToken(deviceId, ts)
        val url = "$apiBase${OttaiConstants.API_PATH_PREFIX}${OttaiConstants.EP_API_TOKEN}?signature=$sig&timestamp=$ts"
        val resp = get(url, headers(ts))
        return resp?.optString("data", null)?.takeIf { it.isNotEmpty() }
    }

    fun requestSmsCode(phone: String, phoneCode: String, apiToken: String): String? {
        val ts = nowMs()
        val sig = OttaiCrypto.signSmsCode(deviceId, ts, phone, apiToken)
        val body = JSONObject().apply {
            put("phoneCode", phoneCode)
            put("phone", phone)
            put("apiToken", apiToken)
            put("smsType", 1)
            put("signature", sig)
        }
        val resp = post("${OttaiConstants.EP_SMS_CODE}", body, headers(ts))
        return resp?.optJSONObject("data")?.optString("requestId")?.takeIf { it.isNotEmpty() }
    }

    fun smsLogin(phone: String, phoneCode: String, code: String, requestId: String, apiToken: String): LoginResult? {
        val ts = nowMs()
        val sig = OttaiCrypto.signSmsLogin(deviceId, ts, requestId, phone, code)
        val body = JSONObject().apply {
            put("phoneCode", phoneCode)
            put("phone", phone)
            put("validCode", code)
            put("requestId", requestId)
            put("signature", sig)
        }
        val resp = post("${OttaiConstants.EP_SMS_LOGIN}", body, headers(ts))
        return parseLoginResponse(resp)
    }

    // ---- Password Login ----

    fun passwordLogin(username: String, password: String): LoginResult? {
        val apiToken = requestApiToken() ?: return null
        val ts = nowMs()
        val sig = OttaiCrypto.signAccountLogin(deviceId, ts, apiToken, username, password)
        val body = JSONObject().apply {
            put("apiToken", apiToken)
            put("username", username)
            put("password", password)
            put("signature", sig)
        }
        val resp = post("${OttaiConstants.EP_ACCOUNT_LOGIN}", body, headers(ts))
        return parseLoginResponse(resp)
    }

    // ---- Device Operations ----

    fun validateDevice(mac: String): JSONObject? {
        val ts = nowMs()
        val sig = OttaiCrypto.signValidateDevice(deviceId, ts, mac)
        val url = "$apiBase${OttaiConstants.API_PATH_PREFIX}${OttaiConstants.EP_VALIDATE_DEVICE}?mac=$mac&signature=$sig&timestamp=$ts"
        return get(url, headers(ts))
    }

    fun bindDevice(mac: String, deviceVersion: String, activeTimeMs: Long): JSONObject? {
        val ts = nowMs()
        val body = JSONObject().apply {
            put("mac", mac)
            put("deviceType", "cgm")
            put("deviceVersion", deviceVersion)
            put("activeTime", activeTimeMs)
            put("userId", userId ?: "")
            put("newBindType", 2)
        }
        return post("${OttaiConstants.EP_BIND_DEVICE}", body, headers(ts))
    }

    fun getBindDevice(): JSONObject? {
        val ts = nowMs()
        return get("$apiBase${OttaiConstants.API_PATH_PREFIX}${OttaiConstants.EP_GET_BIND_DEVICE}", headers(ts))
    }

    fun listDevices(): JSONObject? {
        val ts = nowMs()
        val url = "$apiBase${OttaiConstants.API_PATH_PREFIX}${OttaiConstants.EP_LIST_DEVICES}?pageSize=80&pageNumber=1"
        return get(url, headers(ts))
    }

    fun downloadGlucose(mac: String, startTimeMs: Long, endTimeMs: Long): JSONObject? {
        val ts = nowMs()
        val body = JSONObject().apply {
            put("mac", mac)
            put("startTime", startTimeMs)
            put("endTime", endTimeMs)
        }
        return post("${OttaiConstants.EP_DOWNLOAD_GLUCOSE}", body, headers(ts))
    }

    fun unbindDevice(mac: String): JSONObject? {
        val ts = nowMs()
        val body = JSONObject().apply {
            put("mac", mac)
            put("deviceType", "cgm")
            put("unbindType", 0)
        }
        return post("${OttaiConstants.EP_UNBIND_DEVICE}", body, headers(ts))
    }

    // ---- Decrypt device materials ----

    fun decryptMaterials(mac: String, resp: JSONObject): DeviceMaterials? {
        val data = resp.optJSONObject("data") ?: resp
        val keyAB64 = data.optString("keyA", "")
        val methodB64 = data.optString("method", "")
        val coeffB64 = data.optString("coefficient", "")
        val produceTime = data.optLong("produceTime", 0L)
        val activeTime = data.optLong("activeTime", 0L)
        val activeExpire = data.optLong("activeExpireTime", OttaiConstants.DEFAULT_ACTIVE_EXPIRE_MS)
        val retainTime = data.optLong("retainTime", 0L)
        val methodUpd = data.optLong("methodUpdateTime", 0L)
        val coeffUpd = data.optLong("coeffUpdateTime", 0L)
        val devVer = data.optString("deviceVersion", "")
        val gsk = glucoseSecretKey ?: return null

        val keyAPlain = if (keyAB64.isNotEmpty()) OttaiCrypto.decryptKeyA(keyAB64, gsk, produceTime, mac) else ""
        val methodPlain = if (methodB64.isNotEmpty()) OttaiCrypto.decryptMethod(methodB64, gsk, methodUpd, mac) else ""
        val coeffPlain = if (coeffB64.isNotEmpty()) OttaiCrypto.decryptCoefficient(coeffB64, gsk, coeffUpd, mac) else ""
        val authKeys = if (keyAPlain.isNotEmpty()) OttaiCrypto.splitKeyA(keyAPlain) else emptyList()

        return DeviceMaterials(
            mac = mac,
            keyABase64 = keyAB64,
            keyAPlaintext = keyAPlain,
            method = methodPlain,
            coefficient = coeffPlain,
            produceTimeMs = produceTime,
            activeTimeMs = activeTime,
            activeExpireTimeMs = activeExpire,
            retainTimeMs = retainTime,
            methodUpdateTimeMs = methodUpd,
            coeffUpdateTimeMs = coeffUpd,
            deviceVersion = devVer,
            authKeys = authKeys,
        )
    }

    // ---- HTTP helpers ----

    private fun headers(ts: Long): Map<String, String> = mapOf(
        "Authorization" to (accessToken ?: ""),
        "appName" to "ottai-watch",
        "versionName" to "1.0",
        "versionCode" to "1",
        "packageName" to "com.ottai.tag.watch",
        "ua" to "Android_Watch_Ottai_Arc",
        "timezone" to "10800",
        "timeZoneName" to "MSK",
        "language" to "ru",
        "traceId" to "test_${ts}",
        "timestamp" to ts.toString(),
        "country" to "zh_CN",
        "deviceId" to "ottai-watch:a:$deviceId",
        "X-Forwarded-For" to "114.114.114.114",
        "X-Real-IP" to "114.114.114.114",
        "CF-Connecting-IP" to "114.114.114.114",
        "True-Client-IP" to "114.114.114.114",
        "Content-Type" to "application/json",
    )

    private fun epUrl(path: String): String = "$apiBase${OttaiConstants.API_PATH_PREFIX}$path"

    private fun get(urlStr: String, headers: Map<String, String>): JSONObject? {
        return try {
            val conn = URL(urlStr).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 15000
            conn.readTimeout = 15000
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            readResponse(conn)
        } catch (e: Exception) {
            Log.e(OttaiConstants.TAG, "GET $urlStr failed: ${e.message}")
            null
        }
    }

    private fun post(path: String, body: JSONObject, headers: Map<String, String>): JSONObject? {
        return try {
            val url = epUrl(path)
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 15000
            conn.readTimeout = 15000
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            OutputStreamWriter(conn.outputStream).use { it.write(body.toString()) }
            readResponse(conn)
        } catch (e: Exception) {
            Log.e(OttaiConstants.TAG, "POST $path failed: ${e.message}")
            null
        }
    }

    private fun readResponse(conn: HttpURLConnection): JSONObject? {
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.let { BufferedReader(InputStreamReader(it)).readText() } ?: ""
        return try {
            JSONObject(text)
        } catch (e: Exception) {
            Log.e(OttaiConstants.TAG, "JSON parse error (HTTP $code): ${text.take(200)}")
            null
        }
    }

    private fun parseLoginResponse(resp: JSONObject?): LoginResult? {
        if (resp == null) return null
        val data = resp.optJSONObject("data") ?: return null
        val token = data.optString("accessToken", "")
        val gsk = data.optString("glucoseSecretKey", "")
        val uid = data.optString("userId", "")
        if (token.isEmpty() || gsk.isEmpty()) return null
        accessToken = token
        glucoseSecretKey = gsk
        userId = uid
        return LoginResult(token, gsk, uid)
    }

    private fun nowMs(): Long = System.currentTimeMillis()

    companion object {
        fun timestamp(): Long = System.currentTimeMillis()
    }
}
