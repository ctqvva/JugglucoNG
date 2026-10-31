package tk.glucodata.share.ican

import android.content.Context
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import tk.glucodata.Applic
import tk.glucodata.share.ican.ICanHealthCloudClient.HttpResult
import tk.glucodata.share.ican.ICanHealthCloudClient.LoginResult
import tk.glucodata.share.ican.ICanHealthCloudClient.UploadResult

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = ICanHealthCloudSmokeTestApplication::class)
class ICanHealthCloudSmokeTests {

    private class FakeTransport(
        private val respond: (String) -> HttpResult,
    ) : ICanHealthCloudClient.Transport {
        val calls = mutableListOf<Pair<String, String?>>()

        override fun post(url: String, jsonBody: String?): HttpResult {
            calls.add(url to jsonBody)
            return respond(url)
        }

        override fun get(url: String): HttpResult {
            calls.add(url to null)
            return respond(url)
        }

        override fun post(url: String, jsonBody: String?, headers: Map<String, String>): HttpResult {
            calls.add(url to jsonBody)
            return respond(url)
        }

        override fun get(url: String, headers: Map<String, String>): HttpResult {
            calls.add(url to null)
            return respond(url)
        }
    }

    @Test
    fun endToEndSmokeLoginEnqueueThreeFlush() {
        val app = RuntimeEnvironment.getApplication() as ICanHealthCloudSmokeTestApplication
        val context = app.applicationContext

        val transport = FakeTransport { url ->
            if (url.contains("oauth/token")) {
                HttpResult(200, """{"code":0,"success":true,"msg":"","data":{"access_token":"tok123"}}""")
            } else {
                HttpResult(200, """{"code":0}""")
            }
        }

        val client = ICanHealthCloudClient(transport)
        val loginResult = client.login("acct", "pwd")
        assertTrue(loginResult is LoginResult.Ok)
        val token = (loginResult as LoginResult.Ok).sessionId

        val settings = ICanHealthCloudSettings(context)
        settings.clear()
        settings.token = token

        val queue = ICanHealthCloudQueue(context)
        queue.remove(queue.peek())

        queue.enqueue(EgvUpload(serial = "SN1", timeMs = 1000L, mgdl = 120, trend = 1))
        queue.enqueue(EgvUpload(serial = "SN1", timeMs = 2000L, mgdl = 125, trend = 2))
        queue.enqueue(EgvUpload(serial = "SN1", timeMs = 3000L, mgdl = 130, trend = 1))

        val uploader = ICanHealthCloudUploader(client, settings, queue)
        val result = uploader.flush()
        assertTrue(result.success)

        val postCalls = transport.calls.filter { it.first.contains("save") }
        assertTrue(postCalls.isNotEmpty())
        val body = postCalls.first().second!!
        val json = JSONObject(body)
        assertTrue(json.has("data"))
        assertTrue(json.has("key"))

        assertEquals(0, queue.size())
    }
}

class ICanHealthCloudSmokeTestApplication : Applic() {
    override fun onCreate() {}
}
