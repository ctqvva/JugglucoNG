package tk.glucodata.share.lumiflex

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import org.json.JSONArray
import org.json.JSONObject

class LumiflexCloudClient(
    private val transport: Transport = HttpTransport,
    private val encrypt: (String) -> String = { LumiflexCloudCrypto.encryptEnvelope(it) },
    private val decrypt: (String) -> String? = { LumiflexCloudCrypto.decryptEnvelope(it) },
) {

    interface Transport {
        fun post(url: String, body: String, headers: Map<String, String>): HttpResult
        fun get(url: String, headers: Map<String, String>): HttpResult
    }

    data class HttpResult(val code: Int, val body: String)

    sealed class LoginResult {
        data class Ok(val userId: String) : LoginResult()
        data class Failed(
            val kind: LumiflexCloudError.Kind,
            val code: Int,
            val message: String,
        ) : LoginResult()
    }

    sealed class SyncResult {
        object Ok : SyncResult()
        data class Failed(
            val kind: LumiflexCloudError.Kind,
            val code: Int,
            val message: String,
        ) : SyncResult()
    }

    fun login(account: String, password: String, settings: LumiflexCloudSettings): LoginResult {
        settings.account = account
        val body = JSONObject()
            .put("userName", account)
            .put("password", LumiflexCloudCrypto.passwordHash(password))
            .toString()
        val response = post(LumiflexCloudConstants.PATH_LOGIN, body, token = "", userId = "")
        if (response.code == UNAUTHORIZED) {
            return LoginResult.Failed(
                LumiflexCloudError.Kind.UNAUTHORIZED,
                401,
                LumiflexCloudError.message(decoded(response.body)),
            )
        }
        if (response.code == TRANSPORT_FAILURE_CODE) {
            return LoginResult.Failed(
                LumiflexCloudError.Kind.TRANSIENT,
                response.code,
                response.body,
            )
        }
        val json = runCatching { JSONObject(decoded(response.body)) }.getOrNull()
        val code = json?.optInt("code", response.code) ?: response.code
        val data = json?.optJSONObject("data")
        val model = LoginModel.fromJson(data)
        if (response.code in 200..299 && code == 200 && model.token.isNotBlank()) {
            settings.token = model.token
            settings.userId = model.userId
            return LoginResult.Ok(model.userId)
        }
        return LoginResult.Failed(
            LumiflexCloudError.classify(response.code, response.body),
            code,
            LumiflexCloudError.message(decoded(response.body)),
        )
    }

    fun uploadGlucose(
        token: String,
        userId: String,
        records: List<CgmRecord>,
        nowMs: Long,
    ): SyncResult {
        if (records.isEmpty()) return SyncResult.Ok
        val zone = TimeZone.getDefault()
        val formatter = SimpleDateFormat(DATE_PATTERN, Locale.US).apply { timeZone = zone }
        val appTimeZone = zone.id
        val dstOffset = (zone.getOffset(nowMs) / 1000).toString()
        val appCreateTime = formatter.format(Date(nowMs))
        val array = JSONArray()
        records.forEach { record ->
            array.put(
                record.toJson(
                    userId = userId,
                    appTime = formatter.format(Date(record.timeMs)),
                    appTimeZone = appTimeZone,
                    appCreateTime = appCreateTime,
                    dstOffset = dstOffset,
                ),
            )
        }
        val body = JSONObject().put("records", array).toString()
        val response = post(LumiflexCloudConstants.PATH_CGM_SAVE, body, token, userId)
        return classifySync(response)
    }

    fun registerDevice(token: String, userId: String, serial: String): Boolean {
        if (serial.isBlank()) return false
        val body = JSONObject()
            .put("deviceSn", serial)
            .put("sensorId", serial)
            .put("sensorIndex", 0)
            .toString()
        return postOk(LumiflexCloudConstants.PATH_DEVICE_REGISTER, body, token, userId)
    }

    fun followerList(token: String, isShare: Boolean): List<FollowerInfo> {        val type = if (isShare) "share" else "follow"
        val url = url(LumiflexCloudConstants.PATH_FOLLOWER_LIST) + "?type=$type"
        val response = transport.get(url, headers(token, ""))
        if (response.code !in 200..299) return emptyList()
        val json = runCatching { JSONObject(decoded(response.body)) }.getOrNull()
        val array = json?.optJSONArray("data") ?: runCatching { JSONArray(decoded(response.body)) }.getOrNull()
        if (array == null) return emptyList()
        return (0 until array.length()).map { index ->
            FollowerInfo.fromJson(array.optJSONObject(index))
        }
    }

    fun addFollower(token: String, account: String, alias: String, userId: String = ""): FollowerInfo? {
        val body = JSONObject()
            .put("readerUserName", account)
            .put("readerAlias", alias)
            .put("hideState", 0)
            .put("normalPushState", 1)
            .put("emergePushState", 1)
            .toString()
        val response = post(LumiflexCloudConstants.PATH_FOLLOWER_SAVE, body, token, userId)
        if (response.code !in 200..299) return null
        val json = runCatching { JSONObject(decoded(response.body)) }.getOrNull() ?: return null
        if (json.optInt("code", 200) != 200) return null
        return FollowerInfo.fromJson(json.optJSONObject("data") ?: json)
    }

    fun updateFollower(
        token: String,
        id: String,
        displayHidden: Boolean? = null,
        normalPush: Boolean? = null,
        urgentPush: Boolean? = null,
        extremeHighPush: Boolean? = null,
        userId: String = "",
    ): Boolean {
        if (id.isBlank()) return false
        val body = JSONObject().put("userAuthorizationId", id)
        displayHidden?.let { body.put("hideState", if (it) 1 else 0) }
        normalPush?.let { body.put("normalPushState", if (it) 1 else 0) }
        urgentPush?.let { body.put("emergePushState", if (it) 1 else 0) }
        extremeHighPush?.let { body.put("extremeHighPushState", if (it) 1 else 0) }
        return postOk(LumiflexCloudConstants.PATH_FOLLOWER_UPDATE, body.toString(), token, userId)
    }

    fun renameFollower(token: String, id: String, alias: String, userId: String = ""): Boolean {
        if (id.isBlank()) return false
        val body = JSONObject()
            .put("userAuthorizationId", id)
            .put("readerAlias", alias)
            .toString()
        return postOk(LumiflexCloudConstants.PATH_FOLLOWER_UPDATE, body, token, userId)
    }

    fun removeFollower(token: String, id: String, userId: String = ""): Boolean {
        if (id.isBlank()) return false
        val body = JSONObject().put("ids", JSONArray().put(id)).toString()
        return postOk(LumiflexCloudConstants.PATH_FOLLOWER_DELETE, body, token, userId)
    }

    private fun postOk(path: String, body: String, token: String, userId: String): Boolean {
        val response = post(path, body, token, userId)
        if (response.code !in 200..299) return false
        val json = runCatching { JSONObject(decoded(response.body)) }.getOrNull() ?: return true
        return json.optInt("code", 200) == 200
    }

    private fun post(path: String, plainBody: String, token: String, userId: String): HttpResult =
        transport.post(
            url(path),
            encrypt(plainBody),
            headers(token, userId),
        )

    private fun classifySync(response: HttpResult): SyncResult {
        if (response.code == TRANSPORT_FAILURE_CODE) {
            return SyncResult.Failed(LumiflexCloudError.Kind.TRANSIENT, response.code, response.body)
        }
        if (response.code !in 200..299) {
            return SyncResult.Failed(
                LumiflexCloudError.classify(response.code, response.body),
                response.code,
                LumiflexCloudError.message(decoded(response.body)),
            )
        }
        val json = runCatching { JSONObject(decoded(response.body)) }.getOrNull()
        if (json == null) {
            if (response.body.isBlank()) return SyncResult.Ok
            return SyncResult.Failed(
                LumiflexCloudError.Kind.OTHER,
                response.code,
                "Unreadable server response",
            )
        }
        val code = json.optInt("code", 200)
        if (code == 200) return SyncResult.Ok
        return SyncResult.Failed(
            LumiflexCloudError.classify(code, response.body),
            code,
            LumiflexCloudError.message(decoded(response.body)),
        )
    }

    private fun decoded(body: String): String = decrypt(body) ?: body

    private fun url(path: String): String =
        LumiflexCloudConstants.BASE_URL + LumiflexCloudConstants.API_PREFIX + path

    private fun headers(token: String, userId: String): Map<String, String> {
        val headers = linkedMapOf(
            LumiflexCloudConstants.HEADER_VERSION to LumiflexCloudConstants.VERSION_HEADER_VALUE,
            LumiflexCloudConstants.HEADER_BRAND to LumiflexCloudConstants.BRAND,
            LumiflexCloudConstants.HEADER_APP_INFO to LumiflexCloudConstants.APP_INFO,
            LumiflexCloudConstants.HEADER_ENCRYPTION to LumiflexCloudConstants.ENCRYPTION_ENABLED,
            "Content-Type" to "application/json;charset=UTF-8",
            "Accept" to "application/json",
            LumiflexCloudConstants.HEADER_ACCEPT_LANGUAGE to Locale.getDefault().toLanguageTag(),
            LumiflexCloudConstants.HEADER_APP_VERSION_CHECK to
                LumiflexCloudCrypto.versionCheckHeader(userId, System.currentTimeMillis()),
        )
        if (token.isNotBlank()) headers[LumiflexCloudConstants.HEADER_TOKEN] = token
        return headers
    }

    companion object {
        internal const val TRANSPORT_FAILURE_CODE = -1
        private const val UNAUTHORIZED = 401
        private const val DATE_PATTERN = "yyyy-MM-dd HH:mm:ss"

        internal object HttpTransport : Transport {
            override fun post(url: String, body: String, headers: Map<String, String>): HttpResult =
                request("POST", url, body, headers)

            override fun get(url: String, headers: Map<String, String>): HttpResult =
                request("GET", url, "", headers)

            private fun request(
                method: String,
                url: String,
                body: String,
                headers: Map<String, String>,
            ): HttpResult {
                var connection: HttpURLConnection? = null
                return try {
                    connection = (URL(url).openConnection() as HttpURLConnection).apply {
                        requestMethod = method
                        connectTimeout = CONNECT_TIMEOUT_MS
                        readTimeout = READ_TIMEOUT_MS
                        headers.forEach { (key, value) -> setRequestProperty(key, value) }
                    }
                    if (method == "POST") {
                        val bytes = body.toByteArray(Charsets.UTF_8)
                        connection.doOutput = true
                        connection.setFixedLengthStreamingMode(bytes.size)
                        connection.outputStream.use { it.write(bytes) }
                    }
                    val code = connection.responseCode
                    HttpResult(code, readBody(connection, code))
                } catch (error: Throwable) {
                    HttpResult(TRANSPORT_FAILURE_CODE, error.message ?: error.javaClass.simpleName)
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

            private const val CONNECT_TIMEOUT_MS = 15_000
            private const val READ_TIMEOUT_MS = 20_000
        }
    }
}
