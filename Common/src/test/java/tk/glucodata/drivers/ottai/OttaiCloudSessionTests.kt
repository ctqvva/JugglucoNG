package tk.glucodata.drivers.ottai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OttaiCloudSessionTests {

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
    fun errorStreamJsonIsNeverAPayload() {
        // A blank or missing `code` in an error body read as "no bound device" / unbind-ok and
        // made logout wipe the token while the sensor stayed bound.
        val (invalid, invalidFailure) = OttaiCloudClient.classifyResponse(
            401,
            """{"code":"AuthFailed_TokenInvalid","message":"token is invalid"}""",
        )
        assertNull(invalid)
        assertTrue(invalidFailure!!.isTokenInvalid)
        assertEquals("http=401 biz=AuthFailed_TokenInvalid token is invalid", invalidFailure.text)

        val (server, serverFailure) = OttaiCloudClient.classifyResponse(500, """{"code":"","data":{}}""")
        assertNull(server)
        assertEquals("http=500 biz=", serverFailure!!.text)
        assertEquals("", serverFailure.code)

        val (empty, emptyFailure) = OttaiCloudClient.classifyResponse(502, "")
        assertNull(empty)
        assertEquals("http=502 biz=", emptyFailure!!.text)
    }

    @Test
    fun successfulHttpWithABusinessErrorKeepsBodyAndFailure() {
        // Callers branch on the body's own code (AlreadyBinding, EndUsing), so the 2xx body stays.
        val (body, failure) = OttaiCloudClient.classifyResponse(
            200,
            """{"code":"AppUser_AlreadyBinding","message":null}""",
        )
        assertEquals(OttaiCloudClient.BIZ_ALREADY_BINDING, body!!.getString("code"))
        assertEquals(OttaiCloudClient.BIZ_ALREADY_BINDING, failure!!.code)
        // A JSON null message is not shown as the word "null".
        assertEquals("http=200 biz=AppUser_AlreadyBinding", failure.text)
    }

    @Test
    fun successfulResponsesClearTheFailure() {
        for (text in listOf("""{"code":200,"data":{}}""", """{"code":"OK"}""", """{"data":{}}""")) {
            val (body, failure) = OttaiCloudClient.classifyResponse(200, text)
            assertNotNull(text, body)
            assertNull(text, failure)
        }
        // A 2xx without a JSON body has nothing to parse but is not reported as a failure.
        for (text in listOf("", "not json")) {
            val (body, failure) = OttaiCloudClient.classifyResponse(200, text)
            assertNull(text, body)
            assertNull(text, failure)
        }
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
