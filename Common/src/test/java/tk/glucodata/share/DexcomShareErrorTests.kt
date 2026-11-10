package tk.glucodata.share

import org.junit.Assert.assertEquals
import org.junit.Test
import tk.glucodata.share.DexcomShareError.Kind

class DexcomShareErrorTests {

    private fun body(code: String, message: String = ""): String =
        """{"Code":"$code","Message":"$message"}"""

    @Test
    fun sessionCodesExpireTheSession() {
        assertEquals(Kind.SESSION_EXPIRED, DexcomShareError.classify(500, body("SessionIdNotFound")))
        assertEquals(Kind.SESSION_EXPIRED, DexcomShareError.classify(500, body("SessionNotValid")))
    }

    @Test
    fun passwordCodesBlockTheAccount() {
        assertEquals(
            Kind.BAD_CREDENTIALS,
            DexcomShareError.classify(500, body("AccountPasswordInvalid")),
        )
        assertEquals(
            Kind.BAD_CREDENTIALS,
            DexcomShareError.classify(500, body("SSO_AuthenticatePasswordInvalid")),
        )
        assertEquals(
            Kind.BAD_CREDENTIALS,
            DexcomShareError.classify(500, body("SSO_AuthenticateAccountNotFound")),
        )
        assertEquals(
            Kind.BAD_CREDENTIALS,
            DexcomShareError.classify(500, body("SSO_InternalError", "The password is wrong")),
        )
    }

    @Test
    fun anSsoInternalErrorWithoutAPasswordMessageIsTransient() {
        assertEquals(
            Kind.TRANSIENT,
            DexcomShareError.classify(500, body("SSO_InternalError", "Service unavailable")),
        )
    }

    @Test
    fun lockoutCodesAreLockedAndNeverRetried() {
        assertEquals(
            Kind.LOCKED,
            DexcomShareError.classify(500, body("SSO_AuthenticateMaxAttemptsExceeed")),
        )
        assertEquals(Kind.LOCKED, DexcomShareError.classify(500, body("AccountLocked")))
    }

    @Test
    fun otherServerErrorsAreTransient() {
        assertEquals(Kind.TRANSIENT, DexcomShareError.classify(503, ""))
        assertEquals(Kind.TRANSIENT, DexcomShareError.classify(502, null))
    }

    @Test
    fun transportFailuresAreTransient() {
        assertEquals(Kind.TRANSIENT, DexcomShareError.classify(-1, "connect timed out"))
    }

    @Test
    fun anUnrecognisedInternalServerErrorAsksForOneRelogin() {
        assertEquals(Kind.SESSION_EXPIRED, DexcomShareError.classify(500, ""))
        assertEquals(Kind.SESSION_EXPIRED, DexcomShareError.classify(500, null))
        assertEquals(
            Kind.SESSION_EXPIRED,
            DexcomShareError.classify(500, body("SomethingDexcomInvented")),
        )
    }

    @Test
    fun otherClientErrorsAreRejected() {
        assertEquals(Kind.REJECTED, DexcomShareError.classify(400, ""))
        assertEquals(Kind.REJECTED, DexcomShareError.classify(404, body("NotFound")))
    }

    @Test
    fun aTimeoutOrRateLimitIsRetriedNotDropped() {
        assertEquals(Kind.TRANSIENT, DexcomShareError.classify(408, ""))
        assertEquals(Kind.TRANSIENT, DexcomShareError.classify(429, "Too Many Requests"))
    }

    @Test
    fun messagePrefersDexcomsMessageThenItsCode() {
        assertEquals("Password is invalid", DexcomShareError.message(body("Code1", "Password is invalid")))
        assertEquals("Code2", DexcomShareError.message(body("Code2")))
        assertEquals("not json at all", DexcomShareError.message("not json at all"))
        assertEquals("", DexcomShareError.message(null))
    }

    @Test
    fun messageTruncatesAnUnstructuredBody() {
        val longBody = "x".repeat(500)
        assertEquals(160, DexcomShareError.message(longBody).length)
    }
}
