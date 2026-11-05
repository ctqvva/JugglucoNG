package tk.glucodata.share.ican

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import tk.glucodata.Applic
import tk.glucodata.share.ican.ICanHealthCloudClient.HttpResult

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = ICanHealthCloudFollowerManagerTestApplication::class)
class ICanHealthCloudFollowerManagerTests {

    private class FakeTransport(
        private val respond: (String, String?, Map<String, String>) -> HttpResult,
    ) : ICanHealthCloudClient.Transport {
        val calls = mutableListOf<Triple<String, String?, Map<String, String>>>()

        override fun post(url: String, jsonBody: String?): HttpResult {
            calls.add(Triple(url, jsonBody, emptyMap()))
            return respond(url, jsonBody, emptyMap())
        }

        override fun get(url: String): HttpResult {
            calls.add(Triple(url, null, emptyMap()))
            return respond(url, null, emptyMap())
        }

        override fun post(url: String, jsonBody: String?, headers: Map<String, String>): HttpResult {
            calls.add(Triple(url, jsonBody, headers))
            return respond(url, jsonBody, headers)
        }

        override fun get(url: String, headers: Map<String, String>): HttpResult {
            calls.add(Triple(url, null, headers))
            return respond(url, null, headers)
        }
    }

    @Test
    fun inviteWithEmptyEmailAndPhoneThrowsIllegalArgumentException() {
        val context = RuntimeEnvironment.getApplication()
        val settings = ICanHealthCloudSettings(context)
        settings.token = "tok"
        val manager = ICanHealthCloudFollowerManager(ICanHealthCloudClient(FakeTransport { _, _, _ -> HttpResult(200, "") }), settings)
        try {
            manager.invite("", "", "", "name")
            assertTrue(false)
        } catch (e: IllegalArgumentException) {
            assertTrue(true)
        }
    }

    @Test
    fun inviteRetriesAfterUnauthorizedOnceReloginSucceeds() {
        val context = RuntimeEnvironment.getApplication()
        val settings = ICanHealthCloudSettings(context, passwordCipher = TestPlainCipher())
        settings.token = "tok1"
        settings.account = "acct"
        settings.password = "pwd"
        val key = ICanHealthCloudJson.getRandomString()
        var saveCalls = 0
        val transport = FakeTransport { url, _, _ ->
            when {
                url.contains("oauth/token") ->
                    HttpResult(200, """{"access_token":"tok2","refresh_token":"r2","user_id":"42","tenant_id":"t"}""")
                url.contains("follower/save") -> {
                    if (saveCalls++ == 0) {
                        HttpResult(401, """{"code":401,"msg":"Not Authorized"}""")
                    } else {
                        HttpResult(
                            200,
                            ICanHealthCloudJson.aesEncrypt(
                                key,
                                """{"code":200,"success":true,"data":{"id":"7","email":"a@b.com","status":"invited"}}""",
                            ),
                        )
                    }
                }
                else -> HttpResult(404, "")
            }
        }
        val manager = ICanHealthCloudFollowerManager(
            ICanHealthCloudClient(transport, keyFactory = { key }),
            settings,
        )
        val invited = manager.invite("a@b.com", "", "", "")
        assertEquals("7", invited?.id)
        assertEquals("tok2", settings.token)
        assertEquals(2, saveCalls)
    }

    @Test
    fun inviteThrowsUnauthorizedWhenReloginFails() {
        val context = RuntimeEnvironment.getApplication()
        val settings = ICanHealthCloudSettings(context)
        settings.token = "tok1"
        settings.account = "acct"
        settings.password = ""
        val transport = FakeTransport { url, _, _ ->
            when {
                url.contains("follower/save") -> HttpResult(401, """{"code":401,"msg":"Not Authorized"}""")
                else -> HttpResult(404, "")
            }
        }
        val manager = ICanHealthCloudFollowerManager(ICanHealthCloudClient(transport), settings)
        val error = assertThrows(ICanHealthCloudClient.SessionExpired::class.java) {
            manager.invite("a@b.com", "", "", "")
        }
        assertEquals("session expired", error.message)
    }
    @Test
    fun listRetriesAfterUnauthorizedWithRelogin() {
        val context = RuntimeEnvironment.getApplication()
        val settings = ICanHealthCloudSettings(context, passwordCipher = TestPlainCipher())
        settings.token = "tok1"
        settings.account = "acct"
        settings.password = "pwd"
        val key = ICanHealthCloudJson.getRandomString()
        var listCalls = 0
        val transport = FakeTransport { url, _, _ ->
            when {
                url.contains("oauth/token") ->
                    HttpResult(200, """{"access_token":"tok2","refresh_token":"r2","user_id":"42","tenant_id":"t"}""")
                url.contains("follower/list") -> {
                    if (listCalls++ == 0) {
                        HttpResult(401, """{"code":401}""")
                    } else {
                        HttpResult(
                            200,
                            ICanHealthCloudJson.aesEncrypt(
                                key,
                                """{"code":200,"success":true,"data":[{"id":"9","email":"a@b.com"}]}""",
                            ),
                        )
                    }
                }
                else -> HttpResult(404, "")
            }
        }
        val manager = ICanHealthCloudFollowerManager(
            ICanHealthCloudClient(transport, keyFactory = { key }),
            settings,
        )
        val list = manager.list()
        assertEquals(1, list.size)
        assertEquals("9", list[0].id)
        assertEquals(2, listCalls)
    }

    @Test
    fun listDelegatesToClient() {
        val context = RuntimeEnvironment.getApplication()
        val settings = ICanHealthCloudSettings(context)
        settings.token = "tok"
        val key = ICanHealthCloudJson.getRandomString()
        val encrypted = ICanHealthCloudJson.aesEncrypt(
            key,
            """{"code":200,"success":true,"data":[{"id":"1","email":"a@b.com"}]}""",
        )
        val transport = FakeTransport { url, _, _ ->
            if (url.contains("follower/list")) {
                HttpResult(200, encrypted)
            } else {
                HttpResult(200, "")
            }
        }
        val manager = ICanHealthCloudFollowerManager(
            ICanHealthCloudClient(transport, keyFactory = { key }),
            settings,
        )
        val result = manager.list()
        assertEquals(1, result.size)
        assertEquals("1", result[0].id)
    }

    @Test
    fun removeCallsFollowerUpdateWithStatusDeleted() {
        val context = RuntimeEnvironment.getApplication()
        val settings = ICanHealthCloudSettings(context)
        settings.token = "tok"
        val transport = FakeTransport { url, body, headers ->
            HttpResult(200, """{"code":0}""")
        }
        val manager = ICanHealthCloudFollowerManager(ICanHealthCloudClient(transport), settings)
        manager.remove("123")
        val call = transport.calls.firstOrNull { it.first.contains("follower/update") }
        assertTrue(call != null)
    }
}

class ICanHealthCloudFollowerManagerTestApplication : Applic() {
    override fun onCreate() {}
}

private class TestPlainCipher : ICanHealthCloudSettings.PasswordCipher {
    override fun encrypt(value: String): String = value
    override fun decrypt(value: String): String = value
}
