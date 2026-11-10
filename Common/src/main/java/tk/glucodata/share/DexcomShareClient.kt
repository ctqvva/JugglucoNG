package tk.glucodata.share

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import tk.glucodata.Log

/**
 * The Share publisher calls.
 *
 * This is an unofficial API. The login follows pydexcom, the maintained Share client: trade the
 * account name for the account id, then log in with that id. xDrip's older
 * `LoginPublisherAccountByName` is the path pydexcom moved away from, and username logins were
 * reported failing on it (ctqvva/JugglucoNG#674). After the login, the monitoring steps are
 * copied from xDrip `sharemodels/DexcomShare.java`: start a monitoring session, then check (and
 * if needed replace) the receiver assignment. Nothing here retries. The retry rules depend on
 * state the uploader holds, and a wrong guess here can lock the user's Dexcom account.
 */
class DexcomShareClient(
    private val region: DexcomShareRegion,
    private val transport: Transport = HttpTransport
) {

    /** The seam the tests use; the real one is [HttpTransport]. */
    fun interface Transport {
        fun post(url: String, jsonBody: String?): HttpResult
    }

    data class HttpResult(val code: Int, val body: String)

    sealed class LoginResult {
        data class Ok(val sessionId: String) : LoginResult()
        data class Failed(
            val kind: DexcomShareError.Kind,
            val code: Int,
            val message: String
        ) : LoginResult()
    }

    sealed class UploadResult {
        object Ok : UploadResult()
        data class Failed(
            val kind: DexcomShareError.Kind,
            val code: Int,
            val message: String
        ) : UploadResult()
    }

    fun login(account: String, password: String, serial: String): LoginResult {
        // Dexcom reports a wrong password, an unknown account and a lockout on this step.
        val authenticate = transport.post(
            url("General/AuthenticatePublisherAccount"),
            DexcomShareJson.authBody(account, password)
        )
        logStep("AuthenticatePublisherAccount", authenticate)
        if (authenticate.code !in 200..299) return failedLogin(authenticate)

        val accountId = DexcomShareJson.parseJsonString(authenticate.body)
        if (!isIssuedId(accountId)) {
            // A well-formed "no account" answer. Treating it as anything but bad credentials
            // would retry the same name and password until the account locks.
            if (Log.doLog) Log.i(LOG_ID, "AuthenticatePublisherAccount returned no account id")
            return LoginResult.Failed(
                DexcomShareError.Kind.BAD_CREDENTIALS,
                authenticate.code,
                "Dexcom found no account for this name in this region"
            )
        }

        val login = transport.post(
            url("General/LoginPublisherAccountById"),
            DexcomShareJson.loginByIdBody(accountId, password)
        )
        logStep("LoginPublisherAccountById", login)
        if (login.code !in 200..299) return failedLogin(login)

        val sessionId = DexcomShareJson.parseJsonString(login.body)
        if (!isIssuedId(sessionId)) {
            if (Log.doLog) Log.i(LOG_ID, "LoginPublisherAccountById returned no session")
            return LoginResult.Failed(
                DexcomShareError.Kind.BAD_CREDENTIALS,
                login.code,
                "Dexcom accepted no session for this account"
            )
        }

        // The monitoring steps are fire-and-forget in xDrip: their results are ignored.
        val start = transport.post(
            serverUrl("Publisher/StartRemoteMonitoringSession", sessionId, serial),
            null
        )
        logStep("StartRemoteMonitoringSession", start)

        val assignment = transport.post(
            serverUrl("Publisher/CheckMonitoredReceiverAssignmentStatus", sessionId, serial),
            null
        )
        logStep("CheckMonitoredReceiverAssignmentStatus", assignment)
        if (DexcomShareJson.parseJsonString(assignment.body) != ASSIGNED_TO_YOU) {
            val replace = transport.post(
                serverUrl("Publisher/ReplacePublisherAccountMonitoredReceiver", sessionId, serial),
                null
            )
            logStep("ReplacePublisherAccountMonitoredReceiver", replace)
        }
        if (Log.doLog) Log.i(LOG_ID, "login ok")
        return LoginResult.Ok(sessionId)
    }

    fun upload(sessionId: String, serial: String, egvs: List<ShareEgv>): UploadResult {
        val response = transport.post(
            url("Publisher/PostReceiverEgvRecords", "sessionId" to sessionId),
            DexcomShareJson.uploadBody(serial, egvs)
        )
        if (response.code in 200..299) return UploadResult.Ok
        return UploadResult.Failed(
            DexcomShareError.classify(response.code, response.body),
            response.code,
            DexcomShareError.message(response.body)
        )
    }

    private fun failedLogin(result: HttpResult) = LoginResult.Failed(
        DexcomShareError.classify(result.code, result.body),
        result.code,
        DexcomShareError.message(result.body)
    )

    private fun serverUrl(path: String, sessionId: String, serial: String): String =
        url(path, "sessionId" to sessionId, "serialNumber" to serial)

    /** Query parameters must be encoded; a session id is not always URL-safe. */
    private fun url(path: String, vararg parameters: Pair<String, String>): String {
        val query = parameters.joinToString("&") { (name, value) -> "${encode(name)}=${encode(value)}" }
        return region.baseUrl + path + if (query.isEmpty()) "" else "?$query"
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    /** An id Dexcom actually issued: blank and the all-zero UUID are its "nobody" answers. */
    private fun isIssuedId(id: String): Boolean = id.isNotBlank() && id != NULL_ID

    /**
     * One line per step, so a trace shows where a login stopped. Only the HTTP code and Dexcom's
     * error code: the account, the password, the ids and Dexcom's message text stay out.
     */
    private fun logStep(step: String, result: HttpResult) {
        if (!Log.doLog || result.code in 200..299) return
        val dexcomCode = DexcomShareError.codeOf(result.body)?.let { " $it" }.orEmpty()
        Log.i(LOG_ID, "$step answered with HTTP ${result.code}$dexcomCode")
    }

    companion object {
        private const val LOG_ID = "DexcomShare"
        private const val NULL_ID = "00000000-0000-0000-0000-000000000000"
        private const val ASSIGNED_TO_YOU = "AssignedToYou"

        /** 15 s to connect, 20 s to read — Dexcom's own client uses similar values. */
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 20_000

        /** An IOException never becomes an HTTP code; -1 is classified as transient. */
        internal const val TRANSPORT_FAILURE_CODE = -1
    }

    internal object HttpTransport : Transport {
        override fun post(url: String, jsonBody: String?): HttpResult {
            var connection: HttpURLConnection? = null
            return try {
                connection = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = READ_TIMEOUT_MS
                    setRequestProperty("User-Agent", DexcomShareJson.USER_AGENT)
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("Accept", "application/json")
                }
                val body = jsonBody?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(body.size)
                connection.outputStream.use { it.write(body) }

                val code = connection.responseCode
                HttpResult(code, readBody(connection, code))
            } catch (error: Throwable) {
                // Never throw on a transport problem: the caller classifies the failure.
                HttpResult(TRANSPORT_FAILURE_CODE, error.message ?: error.javaClass.simpleName)
            } finally {
                runCatching { connection?.disconnect() }
            }
        }

        /** The error body carries Dexcom's Code/Message, so it must be read on non-2xx too. */
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
