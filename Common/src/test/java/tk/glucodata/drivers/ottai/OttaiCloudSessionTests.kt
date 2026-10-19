package tk.glucodata.drivers.ottai

import org.junit.Assert.assertFalse
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
    fun webOnlySessionIsNotAUsableLogin() {
        // A web JWT without the mobile decrypt root is exactly what sign-up used to persist; the
        // wizard now gates on ok, so it must stay false until accountLogin supplied both parts.
        assertFalse(OttaiCloudClient.LoginResult("42", "web-jwt", "").ok)
        assertFalse(OttaiCloudClient.LoginResult("42", "", "secret").ok)
        assertTrue(OttaiCloudClient.LoginResult("42", "mobile-token", "secret").ok)
    }
}
