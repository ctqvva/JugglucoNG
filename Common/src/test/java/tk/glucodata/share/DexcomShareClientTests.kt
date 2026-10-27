package tk.glucodata.share

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tk.glucodata.share.DexcomShareClient.HttpResult
import tk.glucodata.share.DexcomShareClient.LoginResult
import tk.glucodata.share.DexcomShareClient.UploadResult

class DexcomShareClientTests {

    private val accountId = "1d2c3b4a-0000-4000-8000-00000000abcd"
    private val session = "session guid/1"

    private class FakeTransport(
        private val respond: (String) -> HttpResult
    ) : DexcomShareClient.Transport {
        val calls = mutableListOf<Pair<String, String?>>()

        override fun post(url: String, jsonBody: String?): HttpResult {
            calls.add(url to jsonBody)
            return respond(url)
        }
    }

    /** Path plus query, with the region's base URL removed, so assertions stay readable. */
    private fun path(url: String): String =
        url.removePrefix(DexcomShareRegion.OUS.baseUrl).removePrefix(DexcomShareRegion.US.baseUrl)

    private fun successfulLogin(): FakeTransport = FakeTransport { url ->
        when {
            url.contains("AuthenticatePublisherAccount") -> HttpResult(200, "\"$accountId\"")
            url.contains("LoginPublisherAccountById") -> HttpResult(200, "\"$session\"")
            url.contains("CheckMonitoredReceiverAssignmentStatus") -> HttpResult(200, "\"AssignedToYou\"")
            else -> HttpResult(200, "")
        }
    }

    @Test
    fun loginTradesTheNameForTheAccountIdThenLogsInById() {
        val transport = successfulLogin()
        val result = DexcomShareClient(DexcomShareRegion.OUS, transport)
            .login("kid_username", "secret", "SM00000000")

        assertTrue(result is LoginResult.Ok)
        assertEquals(session, (result as LoginResult.Ok).sessionId)
        assertEquals(
            listOf(
                "General/AuthenticatePublisherAccount",
                "General/LoginPublisherAccountById",
                "Publisher/StartRemoteMonitoringSession?sessionId=session+guid%2F1&serialNumber=SM00000000",
                "Publisher/CheckMonitoredReceiverAssignmentStatus?sessionId=session+guid%2F1&serialNumber=SM00000000",
            ),
            transport.calls.map { path(it.first) },
        )
        assertTrue(transport.calls[0].first.startsWith(DexcomShareRegion.OUS.baseUrl))

        val authBody = JSONObject(transport.calls[0].second!!)
        assertEquals("kid_username", authBody.getString("accountName"))
        assertEquals("secret", authBody.getString("password"))
        assertEquals(DexcomShareJson.APPLICATION_ID, authBody.getString("applicationId"))

        val loginBody = JSONObject(transport.calls[1].second!!)
        assertEquals(accountId, loginBody.getString("accountId"))
        assertEquals("secret", loginBody.getString("password"))
        assertEquals(DexcomShareJson.APPLICATION_ID, loginBody.getString("applicationId"))
        assertFalse(loginBody.has("accountName"))

        // The monitoring calls carry no body at all.
        assertEquals(null, transport.calls[2].second)
        assertEquals(null, transport.calls[3].second)
    }

    @Test
    fun theLegacyLoginByNameIsNeverCalled() {
        val transport = successfulLogin()
        DexcomShareClient(DexcomShareRegion.US, transport).login("a", "b", "SM00000000")
        assertTrue(transport.calls.none { it.first.contains("LoginPublisherAccountByName") })
    }

    @Test
    fun theReceiverIsReplacedOnlyWhenDexcomDoesNotReportAssignedToYou() {
        val assigned = successfulLogin()
        DexcomShareClient(DexcomShareRegion.OUS, assigned).login("a", "b", "SM00000000")
        assertTrue(assigned.calls.none { it.first.contains("ReplacePublisherAccountMonitoredReceiver") })

        val other = FakeTransport { url ->
            when {
                url.contains("AuthenticatePublisherAccount") -> HttpResult(200, "\"$accountId\"")
                url.contains("LoginPublisherAccountById") -> HttpResult(200, "\"$session\"")
                url.contains("CheckMonitoredReceiverAssignmentStatus") -> HttpResult(200, "NotAssigned")
                else -> HttpResult(200, "")
            }
        }
        DexcomShareClient(DexcomShareRegion.OUS, other).login("a", "b", "SM00000000")
        assertTrue(
            other.calls.last().first.contains("Publisher/ReplacePublisherAccountMonitoredReceiver")
        )
    }

    @Test
    fun aNullOrBlankAccountIdFailsBeforeAnyPasswordGoesToLoginById() {
        listOf("\"00000000-0000-0000-0000-000000000000\"", "", "null").forEach { body ->
            val transport = FakeTransport { url ->
                if (url.contains("AuthenticatePublisherAccount")) HttpResult(200, body) else HttpResult(200, "")
            }
            val result = DexcomShareClient(DexcomShareRegion.OUS, transport).login("a", "b", "SM00000000")

            assertTrue(body, result is LoginResult.Failed)
            assertEquals(body, DexcomShareError.Kind.BAD_CREDENTIALS, (result as LoginResult.Failed).kind)
            assertEquals(body, 1, transport.calls.size)
        }
    }

    @Test
    fun aNullGuidSessionCountsAsAFailedLogin() {
        listOf("\"00000000-0000-0000-0000-000000000000\"", "").forEach { body ->
            val transport = FakeTransport { url ->
                when {
                    url.contains("AuthenticatePublisherAccount") -> HttpResult(200, "\"$accountId\"")
                    else -> HttpResult(200, body)
                }
            }
            val result = DexcomShareClient(DexcomShareRegion.OUS, transport).login("a", "b", "SM00000000")

            assertTrue(body, result is LoginResult.Failed)
            assertEquals(body, DexcomShareError.Kind.BAD_CREDENTIALS, (result as LoginResult.Failed).kind)
            assertEquals(body, 2, transport.calls.size)
        }
    }

    @Test
    fun aRejectedPasswordOnTheAuthenticateStepStopsTheLogin() {
        val transport = FakeTransport {
            HttpResult(500, """{"Code":"AccountPasswordInvalid","Message":"Password is invalid"}""")
        }
        val result = DexcomShareClient(DexcomShareRegion.US, transport).login("a", "b", "SM00000000")

        assertTrue(result is LoginResult.Failed)
        result as LoginResult.Failed
        assertEquals(DexcomShareError.Kind.BAD_CREDENTIALS, result.kind)
        assertEquals(500, result.code)
        assertEquals("Password is invalid", result.message)
        assertEquals(1, transport.calls.size)
    }

    @Test
    fun badSsoCredentialsOnTheAuthenticateStepFailTheLogin() {
        val transport = FakeTransport {
            HttpResult(500, """{"Code":"SSO_AuthenticatePasswordInvalid","Message":"Password is invalid"}""")
        }
        val result = DexcomShareClient(DexcomShareRegion.OUS, transport).login("a", "b", "SM00000000")

        assertTrue(result is LoginResult.Failed)
        assertEquals(DexcomShareError.Kind.BAD_CREDENTIALS, (result as LoginResult.Failed).kind)
        assertEquals(1, transport.calls.size)
    }

    @Test
    fun aLockoutOnTheAuthenticateStepFailsTheLogin() {
        val transport = FakeTransport {
            HttpResult(500, """{"Code":"SSO_AuthenticateMaxAttemptsExceeed","Message":"locked"}""")
        }
        val result = DexcomShareClient(DexcomShareRegion.OUS, transport).login("a", "b", "SM00000000")

        assertTrue(result is LoginResult.Failed)
        assertEquals(DexcomShareError.Kind.LOCKED, (result as LoginResult.Failed).kind)
        assertEquals(1, transport.calls.size)
    }

    @Test
    fun aRejectedPasswordOnTheLoginByIdStepStopsTheLogin() {
        val transport = FakeTransport { url ->
            when {
                url.contains("AuthenticatePublisherAccount") -> HttpResult(200, "\"$accountId\"")
                else -> HttpResult(500, """{"Code":"AccountPasswordInvalid","Message":"Password is invalid"}""")
            }
        }
        val result = DexcomShareClient(DexcomShareRegion.OUS, transport).login("a", "b", "SM00000000")

        assertTrue(result is LoginResult.Failed)
        assertEquals(DexcomShareError.Kind.BAD_CREDENTIALS, (result as LoginResult.Failed).kind)
        assertEquals(2, transport.calls.size)
    }

    @Test
    fun uploadPostsTheBatchForTheSession() {
        val transport = FakeTransport { HttpResult(200, "") }
        val result = DexcomShareClient(DexcomShareRegion.US, transport).upload(
            session,
            "SM00000000",
            listOf(ShareEgv(1760870400000L, 123, 4)),
        )

        assertTrue(result is UploadResult.Ok)
        val (url, body) = transport.calls.single()
        assertEquals(
            "Publisher/PostReceiverEgvRecords?sessionId=session+guid%2F1",
            path(url),
        )
        val root = JSONObject(body!!)
        assertEquals("SM00000000", root.getString("SN"))
        assertEquals(123, root.getJSONArray("Egvs").getJSONObject(0).getInt("Value"))
    }

    @Test
    fun aFailedUploadCarriesTheClassifiedKind() {
        val transport = FakeTransport {
            HttpResult(500, """{"Code":"SessionIdNotFound","Message":"gone"}""")
        }
        val result = DexcomShareClient(DexcomShareRegion.US, transport).upload(
            session,
            "SM00000000",
            listOf(ShareEgv(1760870400000L, 123, 4)),
        )

        assertTrue(result is UploadResult.Failed)
        result as UploadResult.Failed
        assertEquals(DexcomShareError.Kind.SESSION_EXPIRED, result.kind)
        assertEquals("gone", result.message)
    }

    @Test
    fun aTransportFailureIsReportedAsTransient() {
        val transport = FakeTransport { HttpResult(-1, "connect timed out") }
        val result = DexcomShareClient(DexcomShareRegion.US, transport).upload(
            session,
            "SM00000000",
            listOf(ShareEgv(1760870400000L, 123, 4)),
        )

        assertTrue(result is UploadResult.Failed)
        assertEquals(DexcomShareError.Kind.TRANSIENT, (result as UploadResult.Failed).kind)
    }
}
