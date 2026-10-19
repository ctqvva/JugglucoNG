package tk.glucodata.share

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tk.glucodata.share.DexcomShareClient.HttpResult
import tk.glucodata.share.DexcomShareClient.LoginResult
import tk.glucodata.share.DexcomShareClient.UploadResult

class DexcomShareClientTests {

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
            url.contains("LoginPublisherAccountByName") -> HttpResult(200, "\"$session\"")
            url.contains("CheckMonitoredReceiverAssignmentStatus") -> HttpResult(200, "\"AssignedToYou\"")
            else -> HttpResult(200, "")
        }
    }

    @Test
    fun loginWalksTheEndpointsInOrderAndEncodesTheQuery() {
        val transport = successfulLogin()
        val result = DexcomShareClient(DexcomShareRegion.OUS, transport)
            .login("someone@example.com", "secret", "SM00000000")

        assertTrue(result is LoginResult.Ok)
        assertEquals(session, (result as LoginResult.Ok).sessionId)
        assertEquals(
            listOf(
                "General/LoginPublisherAccountByName",
                "General/AuthenticatePublisherAccount?sessionId=session+guid%2F1&serialNumber=SM00000000",
                "Publisher/StartRemoteMonitoringSession?sessionId=session+guid%2F1&serialNumber=SM00000000",
                "Publisher/CheckMonitoredReceiverAssignmentStatus?sessionId=session+guid%2F1&serialNumber=SM00000000",
            ),
            transport.calls.map { path(it.first) },
        )
        assertTrue(transport.calls[0].first.startsWith(DexcomShareRegion.OUS.baseUrl))

        val authBody = JSONObject(transport.calls[0].second!!)
        assertEquals("someone@example.com", authBody.getString("accountName"))
        assertEquals("secret", authBody.getString("password"))
        assertEquals(DexcomShareJson.APPLICATION_ID, authBody.getString("applicationId"))
        // The second step repeats the credentials, exactly as xDrip does.
        assertEquals(authBody.getString("password"), JSONObject(transport.calls[1].second!!).getString("password"))
        // The session and monitoring calls carry no body at all.
        assertEquals(null, transport.calls[2].second)
        assertEquals(null, transport.calls[3].second)
    }

    @Test
    fun theReceiverIsReplacedOnlyWhenDexcomDoesNotReportAssignedToYou() {
        val assigned = successfulLogin()
        DexcomShareClient(DexcomShareRegion.OUS, assigned).login("a", "b", "SM00000000")
        assertTrue(assigned.calls.none { it.first.contains("ReplacePublisherAccountMonitoredReceiver") })

        val other = FakeTransport { url ->
            when {
                url.contains("LoginPublisherAccountByName") -> HttpResult(200, "\"$session\"")
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
    fun aNullGuidSessionCountsAsAFailedLogin() {
        val nullGuid = FakeTransport { HttpResult(200, "\"00000000-0000-0000-0000-000000000000\"") }
        val result = DexcomShareClient(DexcomShareRegion.OUS, nullGuid).login("a", "b", "SM00000000")

        assertTrue(result is LoginResult.Failed)
        assertEquals(DexcomShareError.Kind.BAD_CREDENTIALS, (result as LoginResult.Failed).kind)

        val blank = FakeTransport { HttpResult(200, "") }
        val blankResult = DexcomShareClient(DexcomShareRegion.OUS, blank).login("a", "b", "SM00000000")
        assertTrue(blankResult is LoginResult.Failed)
    }

    @Test
    fun aFailedLoginCarriesTheClassifiedKind() {
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

    @Test
    fun badSsoCredentialsOnTheAuthenticateStepFailTheLogin() {
        // xDrip ignores this step, but Dexcom reports bad SSO credentials and lockouts here.
        // Accepting the session anyway would retry a wrong password on every flush.
        val transport = FakeTransport { url ->
            when {
                url.contains("LoginPublisherAccountByName") -> HttpResult(200, "\"$session\"")
                url.contains("AuthenticatePublisherAccount") ->
                    HttpResult(
                        500,
                        """{"Code":"SSO_AuthenticatePasswordInvalid","Message":"Password is invalid"}""",
                    )
                else -> HttpResult(200, "")
            }
        }

        val result = DexcomShareClient(DexcomShareRegion.OUS, transport)
            .login("someone@example.com", "secret", "SM00000000")

        assertTrue(result is LoginResult.Failed)
        result as LoginResult.Failed
        assertEquals(DexcomShareError.Kind.BAD_CREDENTIALS, result.kind)
        assertEquals("Password is invalid", result.message)
        assertEquals(2, transport.calls.size)
    }

    @Test
    fun aLockoutOnTheAuthenticateStepFailsTheLogin() {
        val transport = FakeTransport { url ->
            when {
                url.contains("LoginPublisherAccountByName") -> HttpResult(200, "\"$session\"")
                url.contains("AuthenticatePublisherAccount") ->
                    HttpResult(500, """{"Code":"SSO_AuthenticateMaxAttemptsExceeed","Message":"locked"}""")
                else -> HttpResult(200, "")
            }
        }

        val result = DexcomShareClient(DexcomShareRegion.OUS, transport)
            .login("someone@example.com", "secret", "SM00000000")

        assertTrue(result is LoginResult.Failed)
        assertEquals(DexcomShareError.Kind.LOCKED, (result as LoginResult.Failed).kind)
        assertEquals(2, transport.calls.size)
    }

    @Test
    fun anOrdinaryAuthenticateResponseIsStillIgnored() {
        val transport = successfulLogin()
        val result = DexcomShareClient(DexcomShareRegion.OUS, transport).login("a", "b", "SM00000000")

        assertTrue(result is LoginResult.Ok)
        assertEquals(4, transport.calls.size)
    }
}
