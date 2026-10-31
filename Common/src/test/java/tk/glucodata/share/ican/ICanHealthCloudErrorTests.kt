package tk.glucodata.share.ican

import org.junit.Assert.assertEquals
import org.junit.Test
import tk.glucodata.share.ican.ICanHealthCloudError.Kind

class ICanHealthCloudErrorTests {

    private fun body(code: Int, msg: String = ""): String =
        """{"code":$code,"success":false,"msg":"$msg","ts":1700000000000}"""

    @Test
    fun httpUnauthorizedIsUnauthorized() {
        assertEquals(Kind.UNAUTHORIZED, ICanHealthCloudError.classify(401, ""))
        assertEquals(Kind.UNAUTHORIZED, ICanHealthCloudError.classify(401, body(401, "token expired")))
    }

    @Test
    fun bodyUnauthorizedCodeIsUnauthorized() {
        assertEquals(Kind.UNAUTHORIZED, ICanHealthCloudError.classify(200, body(401, "token expired")))
    }

    @Test
    fun activateAccountCodeIsActivateAccount() {
        assertEquals(Kind.ACTIVATE_ACCOUNT, ICanHealthCloudError.classify(200, body(30, "not activated")))
        assertEquals(Kind.ACTIVATE_ACCOUNT, ICanHealthCloudError.classify(400, body(30, "")))
    }

    @Test
    fun badCredentialsCodeIsBadCredentials() {
        assertEquals(Kind.BAD_CREDENTIALS, ICanHealthCloudError.classify(200, body(13, "wrong password")))
    }

    @Test
    fun bodyCodeWinsOverTheHttpClass() {
        assertEquals(Kind.BAD_CREDENTIALS, ICanHealthCloudError.classify(500, body(13, "wrong password")))
        assertEquals(Kind.ACTIVATE_ACCOUNT, ICanHealthCloudError.classify(503, body(30, "not activated")))
    }

    @Test
    fun aLockedAccountIsLocked() {
        assertEquals(Kind.LOCKED, ICanHealthCloudError.classify(200, body(31, "Account locked")))
        assertEquals(Kind.LOCKED, ICanHealthCloudError.classify(200, body(31, "Учетная запись заблокирована")))
    }

    @Test
    fun serverErrorsAreTransient() {
        assertEquals(Kind.TRANSIENT, ICanHealthCloudError.classify(500, ""))
        assertEquals(Kind.TRANSIENT, ICanHealthCloudError.classify(503, body(500, "boom")))
    }

    @Test
    fun transportFailuresAreTransient() {
        assertEquals(Kind.TRANSIENT, ICanHealthCloudError.classify(-1, "connect timed out"))
    }

    @Test
    fun timeoutAndRateLimitAreTransient() {
        assertEquals(Kind.TRANSIENT, ICanHealthCloudError.classify(408, ""))
        assertEquals(Kind.TRANSIENT, ICanHealthCloudError.classify(429, "Too Many Requests"))
    }

    @Test
    fun otherClientErrorsAreOther() {
        assertEquals(Kind.OTHER, ICanHealthCloudError.classify(400, ""))
        assertEquals(Kind.OTHER, ICanHealthCloudError.classify(404, body(404, "not found")))
        assertEquals(Kind.OTHER, ICanHealthCloudError.classify(200, body(0, "")))
    }

    @Test
    fun aBrokenBodyDoesNotBreakTheClassification() {
        assertEquals(Kind.TRANSIENT, ICanHealthCloudError.classify(500, "{not json"))
        assertEquals(Kind.OTHER, ICanHealthCloudError.classify(400, """{"code":"""))
    }

    @Test
    fun aCodeSentAsTextIsStillAcode() {
        assertEquals(Kind.UNAUTHORIZED, ICanHealthCloudError.classify(200, """{"code":"401","msg":"expired"}"""))
        assertEquals(Kind.ACTIVATE_ACCOUNT, ICanHealthCloudError.classify(200, """{"code":"30","msg":""}"""))
    }

    @Test
    fun messagePrefersTheVendorMsg() {
        assertEquals("account not activated", ICanHealthCloudError.message(body(30, "account not activated")))
    }

    @Test
    fun messageFallsBackToTheRawBody() {
        assertEquals("not json at all", ICanHealthCloudError.message("not json at all"))
        assertEquals("", ICanHealthCloudError.message(""))
        assertEquals("", ICanHealthCloudError.message(null))
    }

    @Test
    fun messageTruncatesALongBody() {
        assertEquals(160, ICanHealthCloudError.message("x".repeat(500)).length)
    }

    @Test
    fun theErrorTypesCarryWhatTheyNeed() {
        val transport = ICanHealthCloudError.Transport(-1, "timeout")
        assertEquals(-1, transport.code)
        assertEquals("timeout", transport.message)
        assertEquals(ICanHealthCloudError.Unauthorized(), ICanHealthCloudError.Unauthorized())
        assertEquals(ICanHealthCloudError.ActivateAccount(), ICanHealthCloudError.ActivateAccount())
        assertEquals(ICanHealthCloudError.Decrypt(), ICanHealthCloudError.Decrypt())
    }

    @Test
    fun theTopLevelHelpersBehaveLikeTheCompanionOnes() {
        assertEquals(Kind.UNAUTHORIZED, classify(401, ""))
        assertEquals("account not activated", message(body(30, "account not activated")))
    }
}
