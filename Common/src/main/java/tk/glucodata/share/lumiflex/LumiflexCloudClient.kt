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
        data class Ok(
            val userId: String,
            val token: String = "",
            val account: String = "",
        ) : LoginResult()
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

    /** Device lookup outcome, kept apart from an empty list so a failure cannot look like "no sensors". */
    sealed class DeviceList {
        data class Ok(val sensorIdBySerial: Map<String, String>) : DeviceList()
        object Unauthorized : DeviceList()
        data class Failed(val code: Int, val message: String) : DeviceList()
    }

    /**
     * Signs in and persists the session. Prefer the settings-free overload when the caller has to
     * decide whether a reply is still wanted (for example after a sign-out).
     */
    fun login(account: String, password: String, settings: LumiflexCloudSettings): LoginResult {
        val result = login(account, password)
        if (result is LoginResult.Ok) {
            settings.startSession(result.account, result.token, result.userId)
        }
        return result
    }

    /** Network-only sign-in: stores nothing, so a cancelled attempt cannot resurrect a session. */
    fun login(account: String, password: String): LoginResult {
        val loginAccount = normalizeLoginAccount(account)
        val body = JSONObject()
            .put("userName", loginAccount)
            .put("password", LumiflexCloudCrypto.passwordHash(password))
            .toString()
        return loginRequest(LumiflexCloudConstants.PATH_LOGIN, body, loginAccount)
    }

    fun requestRegistrationCode(phone: String): SyncResult {
        val normalized = registrationPhone(phone)
            ?: return SyncResult.Failed(LumiflexCloudError.Kind.OTHER, 400, "Invalid Russian phone number")
        return classifySync(post(
            LumiflexCloudConstants.PATH_SEND_REGISTRATION_CODE,
            // Russian numbers use the overseas SMS gateway, with the dialling code separate.
            JSONObject().put("zoneNumber", "7").put("phone", normalized.drop(1)).toString(), "", "",
        ))
    }

    /** The official mobile flow creates an account or signs in an existing one after SMS proof. */
    fun registerWithCode(phone: String, code: String): LoginResult {
        val normalized = registrationPhone(phone)
            ?: return LoginResult.Failed(LumiflexCloudError.Kind.OTHER, 400, "Invalid Russian phone number")
        if (code.isBlank()) return LoginResult.Failed(LumiflexCloudError.Kind.OTHER, 400, "Verification code required")
        return loginRequest(
            LumiflexCloudConstants.PATH_REGISTER_WITH_CODE,
            JSONObject().put("phone", normalized).put("code", code.trim()).toString(),
            normalized,
        )
    }

    fun setPassword(token: String, userId: String, password: String): SyncResult = classifySync(post(
        LumiflexCloudConstants.PATH_SET_PASSWORD,
        JSONObject().put("password", LumiflexCloudCrypto.passwordHash(password)).toString(), token, userId,
    ))

    /** A persisted token is not proof that the vendor still accepts this session. */
    fun checkSession(token: String, userId: String): SyncResult {
        val response = transport.get(url(LumiflexCloudConstants.PATH_USER_INFO), headers(token, userId))
        val verdict = classifySync(response)
        if (verdict is SyncResult.Failed) return verdict
        val data = runCatching { JSONObject(decoded(response.body).orEmpty()).optJSONObject("data") }.getOrNull()
        if (userId.isBlank() || data?.optString("userId") != userId) {
            return SyncResult.Failed(LumiflexCloudError.Kind.OTHER, response.code, "Unable to verify account")
        }
        return SyncResult.Ok
    }

    private fun loginRequest(path: String, body: String, account: String): LoginResult {
        val response = post(path, body, token = "", userId = "")
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
        val json = runCatching { JSONObject(decoded(response.body).orEmpty()) }.getOrNull()
        if (json != null && !json.has("code")) {
            return LoginResult.Failed(LumiflexCloudError.Kind.OTHER, response.code, "The server did not confirm sign-in")
        }
        val code = json?.optInt("code", response.code) ?: response.code
        val data = json?.optJSONObject("data")
        val model = LoginModel.fromJson(data)
        if (response.code in 200..299 && code == 200 && model.token.isNotBlank() && model.userId.isNotBlank()) {
            return LoginResult.Ok(model.userId, model.token, account)
        }
        if (response.code in 200..299 && code == 200) {
            return LoginResult.Failed(LumiflexCloudError.Kind.OTHER, code, "The server did not return a complete session")
        }
        return LoginResult.Failed(
            LumiflexCloudError.classify(code, decoded(response.body)),
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
        val createTimeFormatter = SimpleDateFormat(CREATE_TIME_PATTERN, Locale.US).apply { timeZone = zone }
        val appTimeZone = zone.id
        val dstOffset = (zone.getDSTSavings() / 1000).toString()
        val appCreateTime = createTimeFormatter.format(Date(nowMs))
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

    fun followerList(token: String, isShare: Boolean, userId: String): List<FollowerInfo>? {
        val url = url(LumiflexCloudConstants.PATH_FOLLOWER_LIST) +
            "?type=0&pageNum=1&pageSize=500"
        val response = transport.get(url, headers(token, ""))
        if (response.code !in 200..299) return null
        val plain = decoded(response.body) ?: return null
        val json = runCatching { JSONObject(plain) }.getOrNull()
        if (json != null && json.optInt("code", 200) != 200) return null
        val array = json?.optJSONArray("data") ?: runCatching { JSONArray(plain) }.getOrNull()
        if (array == null) return null
        val rows = (0 until array.length()).map { index ->
            FollowerInfo.fromJson(array.optJSONObject(index))
        }
        return rows.filter { row ->
            if (isShare) {
                row.dataProviderId.equals(userId, ignoreCase = true)
            } else {
                row.dataReaderId.equals(userId, ignoreCase = true)
            }
        }
    }

    fun devicesBySensorSerial(token: String): DeviceList {
        val response = transport.get(
            url(LumiflexCloudConstants.PATH_FIRST_LAST_DEVICE),
            headers(token, ""),
        )
        if (response.code == UNAUTHORIZED) return DeviceList.Unauthorized
        if (response.code == TRANSPORT_FAILURE_CODE) {
            return DeviceList.Failed(response.code, response.body.ifBlank { "Network error" })
        }
        if (response.code !in 200..299) {
            val message = LumiflexCloudError.message(decoded(response.body))
            if (LumiflexCloudError.classify(response.code, message) ==
                LumiflexCloudError.Kind.UNAUTHORIZED
            ) {
                return DeviceList.Unauthorized
            }
            return DeviceList.Failed(response.code, message)
        }
        val plain = decoded(response.body)
            ?: return DeviceList.Failed(response.code, "Unreadable device list")
        val json = runCatching { JSONObject(plain) }.getOrNull()
            ?: return DeviceList.Failed(response.code, "Unreadable device list")
        val code = json.optInt("code", 200)
        if (code != 200) {
            if (LumiflexCloudError.classify(code, plain) == LumiflexCloudError.Kind.UNAUTHORIZED) {
                return DeviceList.Unauthorized
            }
            return DeviceList.Failed(code, LumiflexCloudError.message(plain))
        }
        if (!json.has("data")) {
            return DeviceList.Failed(code, "Device list data is missing")
        }
        val data = json.optJSONObject("data") ?: return DeviceList.Ok(emptyMap())
        val result = LinkedHashMap<String, String>()
        listOf("firstList", "lastList").forEach { key ->
            val array = data.optJSONArray(key) ?: return@forEach
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                if (item.optString("unregisterTime", "").let { it.isNotBlank() && it != "null" }) continue
                val sn = item.optString("deviceSn", "")
                val sensorId = item.optString("sensorId", "")
                if (sn.isNotBlank() && sensorId.isNotBlank()) result[sn] = sensorId
            }
        }
        return DeviceList.Ok(result)
    }

    fun registerSensor(token: String, userId: String, sensor: LumiflexCloudSensor): SyncResult {
        if (!sensor.complete || userId.isBlank() || token.isBlank()) return SyncResult.Failed(LumiflexCloudError.Kind.OTHER, 400, "Incomplete sensor pairing")
        return classifySync(post(LumiflexCloudConstants.PATH_REGISTER_DEVICE,
            sensor.registrationJson(userId, System.currentTimeMillis()).toString(), token, userId))
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
        val json = runCatching { JSONObject(decoded(response.body).orEmpty()) }.getOrNull() ?: return null
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
        val plain = decoded(response.body) ?: return false
        val json = runCatching { JSONObject(plain) }.getOrNull() ?: return false
        return json.optInt("code", 0) == 200
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
        val plain = decoded(response.body)
            ?: return SyncResult.Failed(
                LumiflexCloudError.Kind.TRANSIENT,
                response.code,
                "Unreadable server response",
            )
        val json = runCatching { JSONObject(plain) }.getOrNull()
        if (json == null) {
            return SyncResult.Failed(
                LumiflexCloudError.Kind.TRANSIENT,
                response.code,
                "Unreadable server response",
            )
        }
        if (!json.has("code")) return SyncResult.Failed(
            LumiflexCloudError.Kind.TRANSIENT, response.code, "The server did not confirm the request",
        )
        val code = json.optInt("code", 0)
        if (code == 200) return SyncResult.Ok
        return SyncResult.Failed(
            LumiflexCloudError.classify(code, response.body),
            code,
            LumiflexCloudError.message(decoded(response.body)),
        )
    }

    /** Plain JSON stays as-is; an encrypted envelope that will not decrypt returns null. */
    private fun decoded(body: String): String? {
        val parsed = runCatching { JSONObject(body) }.getOrNull()
        if (parsed == null) return body
        if (!parsed.has("encryptData")) return body
        return decrypt(body)
    }

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
        fun registrationPhone(account: String): String? = normalizeLoginAccount(account)
            .takeIf { it.length == 11 && it.startsWith("7") && it.all { char -> char in '0'..'9' } }

        /** The vendor expects country-code digits, without '+' or phone display formatting. */
        private fun normalizeLoginAccount(account: String): String {
            val trimmed = account.trim()
            // Do not remove '+' (or any other character) from an email address or username.
            val phone = trimmed.removePrefix("+")
            return if (phone.any { it in '0'..'9' } &&
                phone.all { it in '0'..'9' || it.isWhitespace() || it in "()-" }
            ) {
                phone.filter { it in '0'..'9' }
            } else {
                trimmed
            }
        }

        internal const val TRANSPORT_FAILURE_CODE = -1
        private const val UNAUTHORIZED = 401
        private const val DATE_PATTERN = "yyyy-MM-dd HH:mm:ss"
        private const val CREATE_TIME_PATTERN = "yyyy-MM-dd HH:mm:ssZ"

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
