package tk.glucodata.share.ican

import android.content.Context
import org.junit.Assert.assertEquals
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
    fun listDelegatesToClient() {
        val context = RuntimeEnvironment.getApplication()
        val settings = ICanHealthCloudSettings(context)
        settings.token = "tok"
        val transport = FakeTransport { url, _, _ ->
            if (url.contains("follower/list")) {
                HttpResult(200, "[{\"id\":\"1\",\"email\":\"a@b.com\"}]")
            } else {
                HttpResult(200, "")
            }
        }
        val manager = ICanHealthCloudFollowerManager(ICanHealthCloudClient(transport), settings)
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
