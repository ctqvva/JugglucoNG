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
import tk.glucodata.share.ican.ICanHealthCloudClient.UploadResult

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = ICanHealthCloudUploaderTestApplication::class)
class ICanHealthCloudUploaderTests {

    private class FakeTransport(private val respond: (String) -> HttpResult) : ICanHealthCloudClient.Transport {
        val calls = mutableListOf<String>()
        override fun post(url: String, jsonBody: String?): HttpResult { calls.add(url); return respond(url) }
        override fun get(url: String): HttpResult { calls.add(url); return respond(url) }
        override fun post(url: String, jsonBody: String?, headers: Map<String, String>): HttpResult { calls.add(url); return respond(url) }
        override fun get(url: String, headers: Map<String, String>): HttpResult { calls.add(url); return respond(url) }
    }

    @Test
    fun flushUploadsAndClearsQueue() {
        val app = RuntimeEnvironment.getApplication() as ICanHealthCloudUploaderTestApplication
        val context = app
        val transport = FakeTransport { HttpResult(200, """{"code":0}""") }
        val client = ICanHealthCloudClient(transport)
        val settings = ICanHealthCloudSettings(context)
        settings.token = "tok"
        val queue = ICanHealthCloudQueue(context)
        queue.remove(queue.peek())
        queue.enqueue(EgvUpload(serial = "SN1", timeMs = 1L, mgdl = 100, trend = 1))
        val uploader = ICanHealthCloudUploader(client, settings, queue)
        val result = uploader.flush()
        assertTrue(result is ICanHealthCloudUploader.FlushResult.Success || result is ICanHealthCloudUploader.FlushResult.Ok)
        assertEquals(0, queue.size())
    }

    @Test
    fun flush401StopsAndClearsToken() {
        val app = RuntimeEnvironment.getApplication() as ICanHealthCloudUploaderTestApplication
        val context = app
        val transport = FakeTransport { HttpResult(401, """{"code":401}""") }
        val client = ICanHealthCloudClient(transport)
        val settings = ICanHealthCloudSettings(context)
        settings.token = "tok"
        val queue = ICanHealthCloudQueue(context)
        queue.remove(queue.peek())
        queue.enqueue(EgvUpload(serial = "SN1", timeMs = 1L, mgdl = 100, trend = 1))
        val uploader = ICanHealthCloudUploader(client, settings, queue)
        val result = uploader.flush()
        assertTrue(result is ICanHealthCloudUploader.FlushResult.Unauthorized || result is ICanHealthCloudUploader.FlushResult.AuthFailed || result is ICanHealthCloudUploader.FlushResult.Unauthenticated)
        assertEquals("", settings.token)
        assertEquals(1, queue.size())
    }

    @Test
    fun flush5xxRetriesThenStops() {
        val app = RuntimeEnvironment.getApplication() as ICanHealthCloudUploaderTestApplication
        val context = app
        val transport = FakeTransport { HttpResult(500, """{"code":500}""") }
        val client = ICanHealthCloudClient(transport)
        val settings = ICanHealthCloudSettings(context)
        settings.token = "tok"
        val queue = ICanHealthCloudQueue(context)
        queue.remove(queue.peek())
        queue.enqueue(EgvUpload(serial = "SN1", timeMs = 1L, mgdl = 100, trend = 1))
        val uploader = ICanHealthCloudUploader(client, settings, queue)
        val result = uploader.flush()
        assertTrue(result is ICanHealthCloudUploader.FlushResult.Failed || result is ICanHealthCloudUploader.FlushResult.Error || result is ICanHealthCloudUploader.FlushResult.Transient)
        assertTrue(transport.calls.size >= 2)
        assertEquals(1, queue.size())
    }
}

class ICanHealthCloudUploaderTestApplication : Applic() {
    override fun onCreate() {}
}