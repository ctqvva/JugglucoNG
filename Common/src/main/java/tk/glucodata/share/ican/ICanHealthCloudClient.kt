package tk.glucodata.share.ican

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import org.json.JSONArray
import org.json.JSONObject
import tk.glucodata.Log

class ICanHealthCloudClient(
    private val transport: Transport = HttpTransport,
) {

    interface Transport {
        fun post(url: String, jsonBody: String?): HttpResult
        fun get(url: String): HttpResult
        fun post(url: String, jsonBody: String?, headers: Map<String, String>): HttpResult =
            post(url, jsonBody)
        fun get(url: String, headers: Map<String, String>): HttpResult = get(url)
    }

    data class HttpResult(val code: Int, val body: String)

    sealed class LoginResult {
        data class Ok(val sessionId: String, val model: LoginModel = LoginModel()) : LoginResult()
        data class Failed(val kind: ICanHealthCloudError.Kind, val code: Int, val message: String) :
            LoginResult()
    }

    sealed class UploadResult {
        object Ok : UploadResult()
        data class Failed(val kind: ICanHealthCloudError.Kind, val code: Int, val message: String) :
            UploadResult()
    }

    fun login(account: String, password: String): LoginResult {
        val url = ICanHealthCloudConstants.BASE_URL + ICanHealthCloudConstants.APP_HOST_FIRST_PATH + "sino-auth/oauth/token"
        val body = buildLoginForm(account, password)
        val response = transport.post(url, body)
        if (response.code == 401) {
            return LoginResult.Failed(
                ICanHealthCloudError.Kind.UNAUTHORIZED,
                401,
                ICanHealthCloudError.message(response.body),
            )
        }
        if (response.code == TRANSPORT_FAILURE_CODE) {
            return LoginResult.Failed(
                ICanHealthCloudError.Kind.TRANSIENT,
                response.code,
                response.body,
            )
        }
        val json = runCatching { JSONObject(response.body) }.getOrNull()
        val code = json?.optInt("code", response.code) ?: response.code
        if (code == 0) {
            val data = json?.optJSONObject("data")
            val token = data?.optString("access_token", "").orEmpty()
            if (token.isNotEmpty()) return LoginResult.Ok(token, LoginModel.fromJson(data))
        }
        return LoginResult.Failed(
            ICanHealthCloudError.classify(response.code, response.body),
            code,
            ICanHealthCloudError.message(response.body),
        )
    }

    fun uploadGlucose(sessionToken: String, serial: String, egvs: List<EgvUpload>): UploadResult {
        val url =
            ICanHealthCloudConstants.BASE_URL + ICanHealthCloudConstants.APP_HOST_FIRST_PATH + "sino-cgm/v2/user-apparatus/save"
        val payload = mapOf(
            "apparatusSn" to serial,
            "egvs" to egvs.map { it.toJson().toString() },
        )
        val key = ICanHealthCloudJson.getRandomString()
        val envelope = ICanHealthCloudJson.createEncryptedRequest(payload, key)
        val body = JSONObject(envelope).toString()
        val headers = mapOf(ICanHealthCloudConstants.HEADER_SINO_AUTH to sessionToken)
        val response = transport.post(url, body, headers)
        if (response.code == 401) {
            return UploadResult.Failed(
                ICanHealthCloudError.Kind.UNAUTHORIZED,
                401,
                ICanHealthCloudError.message(response.body),
            )
        }
        if (response.code !in 200..299) {
            return UploadResult.Failed(
                ICanHealthCloudError.classify(response.code, response.body),
                response.code,
                ICanHealthCloudError.message(response.body),
            )
        }
        val bodyCode = plainBodyCode(response.body)
        if (bodyCode != null && bodyCode != 0 && bodyCode != 200) {
            return UploadResult.Failed(
                ICanHealthCloudError.classify(bodyCode, response.body),
                bodyCode,
                ICanHealthCloudError.message(response.body),
            )
        }
        return UploadResult.Ok
    }

    private fun plainBodyCode(body: String): Int? {
        val trimmed = body.trim()
        if (!trimmed.startsWith("{")) return null
        return runCatching { org.json.JSONObject(trimmed).optInt("code", 0) }.getOrNull()
    }

    fun followerList(token: String): List<FollowerFriends> {
        val url =
            ICanHealthCloudConstants.BASE_URL + ICanHealthCloudConstants.APP_HOST_FIRST_PATH + "sino-ican/v1/follower/list"
        val response = transport.get(url, mapOf(ICanHealthCloudConstants.HEADER_SINO_AUTH to token))
        if (response.code == 401 || response.code !in 200..299) return emptyList()
        val body = response.body.trim()
        if (body.isEmpty()) return emptyList()
        val array = runCatching {
            val json = JSONObject(body)
            if (json.has("data")) json.optJSONArray("data") else null
        }.getOrNull() ?: runCatching { JSONArray(body) }.getOrNull()
        if (array == null) return emptyList()
        return (0 until array.length()).map { index ->
            FollowerFriends.fromJson(array.optJSONObject(index))
        }
    }

    fun buildFollowerSavePayload(
        email: String,
        phone: String,
        phoneAreaCode: String,
        name: String,
    ): Map<String, Any?> {
        return mapOf(
            "email" to email,
            "phone" to phone,
            "phoneAreaCode" to phoneAreaCode,
            "name" to name,
            "id" to "",
            "invitedTime" to "",
        )
    }

    fun followerSave(
        token: String,
        email: String,
        phone: String,
        phoneAreaCode: String,
        name: String,
    ): FollowerFriends {
        val url =
            ICanHealthCloudConstants.BASE_URL + ICanHealthCloudConstants.APP_HOST_FIRST_PATH + "sino-ican/v1/follower/save"
        val payload = buildFollowerSavePayload(email, phone, phoneAreaCode, name)
        val key = ICanHealthCloudJson.getRandomString()
        val envelope = ICanHealthCloudJson.createEncryptedRequest(payload, key)
        val body = JSONObject(envelope).toString()
        val response = transport.post(
            url,
            body,
            mapOf(ICanHealthCloudConstants.HEADER_SINO_AUTH to token),
        )
        if (response.code == 401 || response.code !in 200..299) return FollowerFriends()
        val decrypted = decryptResponse(key, response.body) ?: return FollowerFriends()
        val json = runCatching { JSONObject(decrypted) }.getOrNull()
        val data = json?.optJSONObject("data") ?: json
        return FollowerFriends.fromJson(data)
    }

    fun followerUpdate(token: String, id: String, status: String, nickname: String): Boolean {
        val url =
            ICanHealthCloudConstants.BASE_URL + ICanHealthCloudConstants.APP_HOST_FIRST_PATH + "sino-ican/v1/follower/update"
        val payload = linkedMapOf<String, Any?>(
            "id" to id,
            "status" to status,
        )
        if (nickname.isNotEmpty()) payload["nickName"] = nickname
        val key = ICanHealthCloudJson.getRandomString()
        val envelope = ICanHealthCloudJson.createEncryptedRequest(payload, key)
        val body = JSONObject(envelope).toString()
        val response = transport.post(
            url,
            body,
            mapOf(ICanHealthCloudConstants.HEADER_SINO_AUTH to token),
        )
        if (response.code == 401 || response.code !in 200..299) return false
        val bodyCode = plainBodyCode(response.body)
        return bodyCode == null || bodyCode == 0 || bodyCode == 200
    }

    fun followerGet(token: String, id: String): FollowerFriends {
        val url =
            ICanHealthCloudConstants.BASE_URL + ICanHealthCloudConstants.APP_HOST_FIRST_PATH + "sino-ican/v1/follower/get-follower"
        val payload = mapOf("id" to id)
        val key = ICanHealthCloudJson.getRandomString()
        val envelope = ICanHealthCloudJson.createEncryptedRequest(payload, key)
        val body = JSONObject(envelope).toString()
        val response = transport.post(
            url,
            body,
            mapOf(ICanHealthCloudConstants.HEADER_SINO_AUTH to token),
        )
        if (response.code == 401 || response.code !in 200..299) return FollowerFriends()
        val decrypted = decryptResponse(key, response.body) ?: return FollowerFriends()
        val json = runCatching { JSONObject(decrypted) }.getOrNull()
        val data = json?.optJSONObject("data") ?: json
        return FollowerFriends.fromJson(data)
    }

    fun hasFollower(token: String): Boolean {
        val url =
            ICanHealthCloudConstants.BASE_URL + ICanHealthCloudConstants.APP_HOST_FIRST_PATH + "sino-ican/v1/follower/has-follower"
        val response = transport.get(url, mapOf(ICanHealthCloudConstants.HEADER_SINO_AUTH to token))
        if (response.code == 401 || response.code !in 200..299) return false
        val body = response.body.trim()
        if (body.isEmpty()) return false
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return false
        return if (json.has("data")) json.optBoolean("data", false) else json.optBoolean("result", false)
    }

    fun basicConfigDetail(token: String): String {
        val url =
            ICanHealthCloudConstants.BASE_URL + ICanHealthCloudConstants.APP_HOST_FIRST_PATH + "sino-ican/v1/basic/config/detail"
        val response = transport.get(url, mapOf(ICanHealthCloudConstants.HEADER_SINO_AUTH to token))
        if (response.code == 401 || response.code !in 200..299) return ""
        return response.body
    }

    fun basicConfigFollowerUpdate(token: String, shareFollower: String): Boolean {
        val url =
            ICanHealthCloudConstants.BASE_URL + ICanHealthCloudConstants.APP_HOST_FIRST_PATH + "sino-ican/v1/basic/config/follower/update"
        val payload = mapOf("shareFollower" to shareFollower)
        val key = ICanHealthCloudJson.getRandomString()
        val envelope = ICanHealthCloudJson.createEncryptedRequest(payload, key)
        val body = JSONObject(envelope).toString()
        val response = transport.post(
            url,
            body,
            mapOf(ICanHealthCloudConstants.HEADER_SINO_AUTH to token),
        )
        if (response.code == 401 || response.code !in 200..299) return false
        val bodyCode = plainBodyCode(response.body)
        return bodyCode == null || bodyCode == 0 || bodyCode == 200
    }

    fun logout(token: String): Unit {
        val url =
            ICanHealthCloudConstants.BASE_URL + ICanHealthCloudConstants.APP_HOST_FIRST_PATH + "sino-cgm/v1/user/logout"
        transport.post(url, "", mapOf(ICanHealthCloudConstants.HEADER_SINO_AUTH to token))
    }

    private fun buildLoginForm(account: String, password: String): String {
        val enc = { v: String -> URLEncoder.encode(v, "UTF-8") }
        return "grant_type=password" +
            "&scope=all" +
            "&type=" +
            "&thirdAuth=" +
            "&country=${ICanHealthCloudConstants.COUNTRY}" +
            "&ids=" +
            "&tenantId=" +
            "&phone_num=${enc(account)}" +
            "&password=${enc(password)}" +
            "&thirdAuthKey="
    }

    private fun decryptResponse(requestKey: String, body: String): String? {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return null
        val json = if (trimmed.startsWith("{")) runCatching { JSONObject(trimmed) }.getOrNull() else null
        val effectiveKey = if (json != null) {
            val found = serverDeliveredKey(json)
            if (found.isNotEmpty()) found else requestKey
        } else {
            requestKey
        }
        val candidate = if (json != null) {
            json.optString("data", "").orEmpty()
                .ifEmpty { json.optString("msg", "").orEmpty() }
                .ifEmpty { json.optString("result", "").orEmpty() }
        } else {
            trimmed.removeSurrounding("\"")
        }
        if (candidate.isEmpty()) return null
        return runCatching { ICanHealthCloudJson.aesDecrypt(effectiveKey, candidate) }.getOrNull()
    }

    private fun serverDeliveredKey(json: JSONObject): String {
        for (field in listOf("aesKey", "key", "dataKey")) {
            val value = json.optString(field, "")
            if (value.isNotEmpty()) return value
        }
        val nested = json.optJSONObject("data")
        if (nested != null) {
            for (field in listOf("aesKey", "key", "dataKey")) {
                val value = nested.optString(field, "")
                if (value.isNotEmpty()) return value
            }
        }
        return ""
    }

    companion object {
        internal const val TRANSPORT_FAILURE_CODE = -1

        internal object HttpTransport : Transport {
            override fun post(url: String, jsonBody: String?): HttpResult = post(url, jsonBody, emptyMap())

            override fun get(url: String): HttpResult = get(url, emptyMap())

            override fun post(url: String, jsonBody: String?, headers: Map<String, String>): HttpResult {
                var connection: HttpURLConnection? = null
                return try {
                    connection = (URL(url).openConnection() as HttpURLConnection).apply {
                        requestMethod = "POST"
                        connectTimeout = CONNECT_TIMEOUT_MS
                        readTimeout = READ_TIMEOUT_MS
                        setRequestProperty(ICanHealthCloudConstants.HEADER_PLATFORM_TYPE, ICanHealthCloudConstants.PLATFORM_TYPE)
                        setRequestProperty(ICanHealthCloudConstants.HEADER_LANG_CODE, ICanHealthCloudConstants.LANG_CODE)
                        setRequestProperty(ICanHealthCloudConstants.HEADER_COUNTRY, ICanHealthCloudConstants.COUNTRY)
                        setRequestProperty(ICanHealthCloudConstants.HEADER_VERSION_CODE, ICanHealthCloudConstants.VERSION_CODE)
                        setRequestProperty(ICanHealthCloudConstants.HEADER_VERSION_NAME, ICanHealthCloudConstants.VERSION_NAME)
                        setRequestProperty(ICanHealthCloudConstants.HEADER_CLIENT_ID, ICanHealthCloudConstants.CLIENT_ID)
                        setRequestProperty(ICanHealthCloudConstants.HEADER_AUTHORIZATION, ICanHealthCloudConstants.AUTHORIZATION_BASIC)
                        setRequestProperty(ICanHealthCloudConstants.HEADER_TIME_ZONE, ICanHealthCloudConstants.TIME_ZONE)
                        setRequestProperty(
                            ICanHealthCloudConstants.HEADER_SINO_AUTH,
                            headers[ICanHealthCloudConstants.HEADER_SINO_AUTH].orEmpty(),
                        )
                        val contentType = if (url.contains("oauth/token")) {
                            "application/x-www-form-urlencoded"
                        } else {
                            "application/json"
                        }
                        setRequestProperty("Content-Type", contentType)
                        setRequestProperty("Accept", "application/json")
                    }
                    val body = jsonBody?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)
                    connection.doOutput = true
                    if (body.isNotEmpty()) {
                        connection.setFixedLengthStreamingMode(body.size)
                        connection.outputStream.use { it.write(body) }
                    }
                    val code = connection.responseCode
                    HttpResult(code, readBody(connection, code))
                } catch (error: Throwable) {
                    HttpResult(TRANSPORT_FAILURE_CODE, error.message ?: error.javaClass.simpleName)
                } finally {
                    runCatching { connection?.disconnect() }
                }
            }

            override fun get(url: String, headers: Map<String, String>): HttpResult {
                var connection: HttpURLConnection? = null
                return try {
                    connection = (URL(url).openConnection() as HttpURLConnection).apply {
                        requestMethod = "GET"
                        connectTimeout = CONNECT_TIMEOUT_MS
                        readTimeout = READ_TIMEOUT_MS
                        setRequestProperty(ICanHealthCloudConstants.HEADER_PLATFORM_TYPE, ICanHealthCloudConstants.PLATFORM_TYPE)
                        setRequestProperty(ICanHealthCloudConstants.HEADER_LANG_CODE, ICanHealthCloudConstants.LANG_CODE)
                        setRequestProperty(ICanHealthCloudConstants.HEADER_COUNTRY, ICanHealthCloudConstants.COUNTRY)
                        setRequestProperty(ICanHealthCloudConstants.HEADER_VERSION_CODE, ICanHealthCloudConstants.VERSION_CODE)
                        setRequestProperty(ICanHealthCloudConstants.HEADER_VERSION_NAME, ICanHealthCloudConstants.VERSION_NAME)
                        setRequestProperty(ICanHealthCloudConstants.HEADER_CLIENT_ID, ICanHealthCloudConstants.CLIENT_ID)
                        setRequestProperty(ICanHealthCloudConstants.HEADER_AUTHORIZATION, ICanHealthCloudConstants.AUTHORIZATION_BASIC)
                        setRequestProperty(ICanHealthCloudConstants.HEADER_TIME_ZONE, ICanHealthCloudConstants.TIME_ZONE)
                        setRequestProperty(
                            ICanHealthCloudConstants.HEADER_SINO_AUTH,
                            headers[ICanHealthCloudConstants.HEADER_SINO_AUTH].orEmpty(),
                        )
                        setRequestProperty("Accept", "application/json")
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
