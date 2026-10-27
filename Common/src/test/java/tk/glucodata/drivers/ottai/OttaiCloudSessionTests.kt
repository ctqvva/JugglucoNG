package tk.glucodata.drivers.ottai

import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OttaiCloudSessionTests {

    @Test
    fun syaiSecureLoginReadsTheNestedJwtWithoutUsingTheRefreshToken() {
        val data = JSONObject("""{"jwtToken":{"accessToken":"web-jwt","refreshToken":"refresh-secret"}}""")
        assertEquals("web-jwt", OttaiCloudClient.webAccessToken(data))
        assertEquals("", OttaiCloudClient.webAccessToken(JSONObject("""{"jwtToken":{"refreshToken":"refresh-secret"}}""")))
    }

    @Test
    fun olderOttaiWebLoginStillReadsTheDirectToken() {
        assertEquals("web-jwt", OttaiCloudClient.webAccessToken(JSONObject("""{"accessToken":"web-jwt"}""")))
        assertEquals("", OttaiCloudClient.webAccessToken(JSONObject("""{"accessToken":null}""")))
    }

    @Test
    fun mobileLoginAcceptsEitherTokenEnvelopeAndStillRequiresTheSensorDecryptRoot() {
        for (token in listOf(""""accessToken":"mobile-token"""", """"jwtToken":{"accessToken":"mobile-token"}""")) {
            val data = JSONObject("""{"userId":"42",$token,"glucoseSecretKey":"sensor-root"}""")
            val result = OttaiCloudClient.mobileLoginResult(data)
            assertTrue(result.ok)
            assertEquals("42", result.userId)
            assertEquals("mobile-token", result.accessToken)
            assertEquals("sensor-root", result.glucoseSecretKey)
            data.remove("glucoseSecretKey")
            assertFalse(OttaiCloudClient.mobileLoginResult(data).ok)
        }
    }

    @Test
    fun nestedWebLoginUpgradesTheResolvedUsernameExactlyOnce() {
        var profileCalls = 0
        var loginCalls = 0
        val expected = OttaiCloudClient.LoginResult("42", "mobile-token", "sensor-root")
        val result = OttaiCloudClient.upgradeWebSession(
            JSONObject("""{"data":{"jwtToken":{"accessToken":"web-token"}}}"""),
            readProfile = { token ->
                profileCalls++
                assertEquals("web-token", token)
                JSONObject("""{"userName":"server-assigned-name"}""")
            },
            login = { name, token ->
                loginCalls++
                assertEquals("server-assigned-name", name)
                assertEquals("web-token", token)
                expected
            },
        )
        assertEquals(expected, result)
        assertEquals(1, profileCalls)
        assertEquals(1, loginCalls)
    }

    @Test
    fun rejectedPasswordDoesNotTriggerAnotherLoginAttempt() {
        var attempts = 0
        val result = OttaiCloudClient.upgradeWebSession(
            JSONObject("""{"data":{"accessToken":"web-token"}}"""),
            readProfile = { JSONObject("""{"userName":"server-name"}""") },
            login = { _, _ ->
                attempts++
                recordResponse("User_InvalidParams", 200)
                null
            },
        )
        assertNull(result)
        assertEquals(1, attempts)
        assertEquals("User_InvalidParams", OttaiCloudClient.lastFailure?.code)
    }

    @Test
    fun rejectedWebAuthorizationCanRetryOnceWithoutIt() {
        val tokens = mutableListOf<String>()
        val expected = OttaiCloudClient.LoginResult("42", "mobile-token", "sensor-root")
        val result = OttaiCloudClient.upgradeWebSession(
            JSONObject("""{"data":{"jwtToken":{"accessToken":"web-token"}}}"""),
            readProfile = { JSONObject("""{"userName":"server-name"}""") },
            login = { _, token ->
                tokens += token
                if (tokens.size == 1) {
                    recordResponse(OttaiCloudClient.BIZ_TOKEN_INVALID, 401)
                    null
                } else expected
            },
        )
        assertEquals(expected, result)
        assertEquals(listOf("web-token", ""), tokens)
    }

    private fun recordResponse(code: String, status: Int) = OttaiCloudClient.requestWithResult(
        "GET", "https://example.invalid/login", null, emptyMap(),
        openConnection = { url -> object : HttpURLConnection(URL(url)) {
            override fun connect() = Unit
            override fun usingProxy() = false
            override fun disconnect() = Unit
            override fun getResponseCode() = status
            override fun getInputStream() = ByteArrayInputStream("""{"code":"$code"}""".toByteArray())
            override fun getErrorStream() = inputStream
        } },
    )

    @Test
    fun tokenInvalidIsRecognisedFromTheBackendCode() {
        val failure = OttaiCloudClient.CloudFailure(
            "http=401 biz=AuthFailed_TokenInvalid token is invalid",
            OttaiCloudClient.BIZ_TOKEN_INVALID,
        )
        assertTrue(failure.isTokenInvalid)
        assertTrue(OttaiCloudClient.CloudFailure("", "authfailed_tokeninvalid").isTokenInvalid)
    }

    @Test
    fun otherFailuresDoNotSignTheAccountOut() {
        assertFalse(OttaiCloudClient.CloudFailure("apiToken failed").isTokenInvalid)
        assertFalse(
            OttaiCloudClient.CloudFailure("http=200 biz=AppUser_AlreadyBinding", OttaiCloudClient.BIZ_ALREADY_BINDING)
                .isTokenInvalid,
        )
        assertFalse(
            OttaiCloudClient.CloudFailure("http=200 biz=AppDevice_NotExist", "AppDevice_NotExist").isTokenInvalid,
        )
    }

    @Test
    fun webOnlySessionIsNotAUsableLogin() {
        // A web JWT without the mobile decrypt root is exactly what sign-up used to persist; the
        // wizard now gates on ok, so it must stay false until accountLogin supplied both parts.
        assertFalse(OttaiCloudClient.LoginResult("42", "web-jwt", "").ok)
        assertFalse(OttaiCloudClient.LoginResult("42", "", "secret").ok)
        assertTrue(OttaiCloudClient.LoginResult("42", "mobile-token", "secret").ok)
    }
}
