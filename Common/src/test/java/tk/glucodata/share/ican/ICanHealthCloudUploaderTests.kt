package tk.glucodata.share.ican

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
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

    @Before
    fun resetSessionAndQueue() {
        val context = RuntimeEnvironment.getApplication()
        ICanHealthCloudSettings(context).clear()
        context.filesDir.resolve("ican_cloud_queue.json").delete()
    }

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
        settings.enabled = true
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
        settings.enabled = true
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
    fun flush401ReloginsWithStoredPasswordAndRetries() {
        val app = RuntimeEnvironment.getApplication() as ICanHealthCloudUploaderTestApplication
        val context = app
        var uploadCalls = 0
        val transport = object : ICanHealthCloudClient.Transport {
            val calls = mutableListOf<String>()
            override fun post(url: String, jsonBody: String?): HttpResult { calls.add(url); return respond(url) }
            override fun get(url: String): HttpResult { calls.add(url); return HttpResult(404, "") }
            override fun post(url: String, jsonBody: String?, headers: Map<String, String>): HttpResult { calls.add(url); return respond(url) }
            override fun get(url: String, headers: Map<String, String>): HttpResult = get(url)
            private fun respond(url: String): HttpResult = when {
                url.contains("oauth/token") -> HttpResult(200, """{"code":0,"data":{"access_token":"newtok"}}""")
                uploadCalls++ == 0 -> HttpResult(401, """{"code":401}""")
                else -> HttpResult(200, """{"code":0}""")
            }
        }
        val client = ICanHealthCloudClient(transport)
        val settings = ICanHealthCloudSettings(context, passwordCipher = TestB64Cipher())
        settings.token = "old"
        settings.account = "a@b.com"
        settings.password = "pwd"
        settings.enabled = true
        val queue = ICanHealthCloudQueue(context)
        queue.remove(queue.peek())
        queue.enqueue(EgvUpload(serial = "SN1", timeMs = 1L, mgdl = 100, trend = 1))
        val uploader = ICanHealthCloudUploader(client, settings, queue)
        val result = uploader.flush()
        assertTrue(result is ICanHealthCloudUploader.FlushResult.Success)
        assertEquals("newtok", settings.token)
        assertEquals(0, queue.size())
        assertTrue(transport.calls.any { it.contains("oauth/token") })
    }

    @Test
    fun flush401WithoutPasswordClearsToken() {
        val app = RuntimeEnvironment.getApplication() as ICanHealthCloudUploaderTestApplication
        val context = app
        val transport = FakeTransport { HttpResult(401, """{"code":401}""") }
        val client = ICanHealthCloudClient(transport)
        val settings = ICanHealthCloudSettings(context)
        settings.token = "tok"
        settings.account = "a@b.com"
        settings.password = ""
        settings.enabled = true
        val queue = ICanHealthCloudQueue(context)
        queue.remove(queue.peek())
        queue.enqueue(EgvUpload(serial = "SN1", timeMs = 1L, mgdl = 100, trend = 1))
        val uploader = ICanHealthCloudUploader(client, settings, queue)
        val result = uploader.flush()
        assertTrue(result is ICanHealthCloudUploader.FlushResult.Unauthorized)
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
        settings.enabled = true
        val queue = ICanHealthCloudQueue(context)
        queue.remove(queue.peek())
        queue.enqueue(EgvUpload(serial = "SN1", timeMs = 1L, mgdl = 100, trend = 1))
        val uploader = ICanHealthCloudUploader(client, settings, queue)
        val result = uploader.flush()
        assertTrue(result is ICanHealthCloudUploader.FlushResult.Failed || result is ICanHealthCloudUploader.FlushResult.Error || result is ICanHealthCloudUploader.FlushResult.Transient)
        assertTrue(transport.calls.size >= 2)
        assertEquals(1, queue.size())
    }
    private fun signedIn(): Pair<ICanHealthCloudSettings, ICanHealthCloudQueue> {
        val context = RuntimeEnvironment.getApplication()
        val settings = ICanHealthCloudSettings(context, TestB64Cipher())
        settings.installLogin("a@example.test", "pwd", ICanHealthCloudClient.LoginResult.Ok("token-a", LoginModel(userId = "A")))
        settings.enabled = true
        return settings to ICanHealthCloudQueue(context)
    }

    private fun switchAccount(settings: ICanHealthCloudSettings) {
        settings.installLogin("b@example.test", "pwd", ICanHealthCloudClient.LoginResult.Ok("token-b", LoginModel(userId = "B")))
    }

    @Test
    fun initializedUploaderNeverSendsPreviousAccountsBacklog() {
        val (settings, queue) = signedIn()
        queue.enqueue(EgvUpload("SN1", 1L, 100, 1))
        val transport = FakeTransport { HttpResult(200, """{"code":0}""") }
        val uploader = ICanHealthCloudUploader(ICanHealthCloudClient(transport), settings, queue)
        switchAccount(settings)
        assertEquals(0, uploader.flush().uploaded)
        assertTrue(transport.calls.isEmpty())
        // The same cached instance must also see B's new work, never reload A's backlog.
        ICanHealthCloudQueue(RuntimeEnvironment.getApplication()).enqueue(EgvUpload("SN2", 2L, 120, 1))
        assertEquals(1, uploader.flush().uploaded)
        assertEquals(1, transport.calls.size)
    }

    @Test
    fun disablingDuringBackoffPreventsAnotherRequest() {
        val (settings, queue) = signedIn()
        queue.enqueue(EgvUpload("SN1", 1L, 100, 1))
        val transport = FakeTransport { HttpResult(500, "") }
        val uploader = ICanHealthCloudUploader(ICanHealthCloudClient(transport), settings, queue) { settings.enabled = false }
        assertTrue(uploader.flush() is ICanHealthCloudUploader.FlushResult.Disabled)
        assertEquals(1, transport.calls.size)
        assertEquals(1, queue.size())
    }

    @Test
    fun accountChangeDuringBackoffDoesNotSendOldBatchWithNewToken() {
        val (settings, queue) = signedIn()
        queue.enqueue(EgvUpload("SN1", 1L, 100, 1))
        val tokens = mutableListOf<String?>()
        val transport = object : ICanHealthCloudClient.Transport {
            override fun post(url: String, jsonBody: String?) = HttpResult(500, "")
            override fun get(url: String) = HttpResult(404, "")
            override fun post(url: String, jsonBody: String?, headers: Map<String, String>): HttpResult {
                tokens += headers[ICanHealthCloudConstants.HEADER_SINO_AUTH]
                return HttpResult(500, "")
            }
        }
        val uploader = ICanHealthCloudUploader(ICanHealthCloudClient(transport), settings, queue) { switchAccount(settings) }
        assertTrue(uploader.flush() is ICanHealthCloudUploader.FlushResult.Unauthorized)
        assertEquals(listOf("token-a"), tokens)
        assertEquals("token-b", settings.token)
    }

    @Test
    fun oldSuccessfulResponseCannotRemoveNewAccountsMatchingReading() {
        val (settings, queue) = signedIn()
        val reading = EgvUpload("SN1", 1L, 100, 1)
        queue.enqueue(reading)
        val transport = FakeTransport {
            switchAccount(settings)
            ICanHealthCloudQueue(RuntimeEnvironment.getApplication()).enqueue(reading)
            HttpResult(200, """{"code":0}""")
        }
        assertTrue(ICanHealthCloudUploader(ICanHealthCloudClient(transport), settings, queue).flush() is ICanHealthCloudUploader.FlushResult.Unauthorized)
        assertEquals(listOf(reading), queue.peek())
        assertEquals(0L, settings.lastSyncMs)
    }

    @Test
    fun permanentFailureStopsImmediatelyAndKeepsAcknowledgedProgress() {
        val (settings, queue) = signedIn()
        queue.enqueueAll((1L..61L).map { EgvUpload("SN1", it, 100, 1) })
        var calls = 0
        val transport = FakeTransport {
            if (calls++ == 0) HttpResult(200, """{"code":0}""") else HttpResult(400, "")
        }
        val result = ICanHealthCloudUploader(ICanHealthCloudClient(transport), settings, queue) { error("Permanent failures must not retry") }.flush()
        assertTrue(result is ICanHealthCloudUploader.FlushResult.Failed)
        assertEquals(60, result.uploaded)
        assertEquals(60L, settings.lastSyncMs)
        assertEquals(listOf(61L), queue.peek().map { it.timeMs })
        assertEquals(2, calls)
    }

    @Test
    fun repeatedUnauthorizedResponsesOnlyReloginOnce() {
        val (settings, queue) = signedIn()
        queue.enqueue(EgvUpload("SN1", 1L, 100, 1))
        var logins = 0
        var uploads = 0
        val transport = FakeTransport { url ->
            if (url.contains("oauth/token")) {
                logins++
                HttpResult(200, """{"code":0,"data":{"access_token":"renewed"}}""")
            } else {
                uploads++
                check(uploads <= 2) { "Unbounded automatic login" }
                HttpResult(401, "")
            }
        }
        assertTrue(ICanHealthCloudUploader(ICanHealthCloudClient(transport), settings, queue).flush() is ICanHealthCloudUploader.FlushResult.Unauthorized)
        assertEquals(1, logins)
        assertEquals(2, uploads)
        assertEquals(1, queue.size())
    }

    @Test
    fun lateUnauthorizedResponseCannotEraseNewLogin() {
        val (settings, queue) = signedIn()
        queue.enqueue(EgvUpload("SN1", 1L, 100, 1))
        val transport = FakeTransport {
            switchAccount(settings)
            HttpResult(401, "")
        }
        assertTrue(ICanHealthCloudUploader(ICanHealthCloudClient(transport), settings, queue).flush() is ICanHealthCloudUploader.FlushResult.Unauthorized)
        assertEquals("token-b", settings.token)
        assertEquals(1, transport.calls.size)
    }

    @Test
    fun olderAcknowledgementCannotMoveSyncProgressBackwards() {
        val (settings, queue) = signedIn()
        settings.lastSyncMs = 100L
        queue.enqueue(EgvUpload("SN1", 1L, 100, 1))
        val transport = FakeTransport { HttpResult(200, """{"code":0}""") }
        assertEquals(1, ICanHealthCloudUploader(ICanHealthCloudClient(transport), settings, queue).flush().uploaded)
        assertEquals(100L, settings.lastSyncMs)
    }

    @Test
    fun flush4xxIsNotRetriedForever() {
        val app = RuntimeEnvironment.getApplication() as ICanHealthCloudUploaderTestApplication
        val context = app
        val transport = FakeTransport { HttpResult(403, """{"code":403}""") }
        val client = ICanHealthCloudClient(transport)
        val settings = ICanHealthCloudSettings(context)
        settings.token = "tok"
        settings.enabled = true
        val queue = ICanHealthCloudQueue(context)
        queue.remove(queue.peek())
        queue.enqueue(EgvUpload(serial = "SN1", timeMs = 1L, mgdl = 100, trend = 1))
        val uploader = ICanHealthCloudUploader(client, settings, queue)
        val result = uploader.flush()
        assertTrue(result is ICanHealthCloudUploader.FlushResult.Failed)
        assertEquals(1, transport.calls.size)
        assertEquals(1, queue.size())
    }

    @Test
    fun flushPartialProgressPreservedAcrossFailure() {
        val app = RuntimeEnvironment.getApplication() as ICanHealthCloudUploaderTestApplication
        val context = app
        var calls = 0
        val transport = object : ICanHealthCloudClient.Transport {
            override fun post(url: String, jsonBody: String?): HttpResult = HttpResult(200, """{"code":0}""")
            override fun get(url: String): HttpResult = HttpResult(404, "")
            override fun post(url: String, jsonBody: String?, headers: Map<String, String>): HttpResult {
                calls++
                return if (calls == 1) HttpResult(200, """{"code":0}""") else HttpResult(500, """{"code":500}""")
            }
            override fun get(url: String, headers: Map<String, String>): HttpResult = get(url)
        }
        val client = ICanHealthCloudClient(transport)
        val settings = ICanHealthCloudSettings(context)
        settings.token = "tok"
        settings.enabled = true
        val queue = ICanHealthCloudQueue(context)
        queue.remove(queue.peek())
        queue.enqueue(EgvUpload(serial = "SN1", timeMs = 1L, mgdl = 100, trend = 1))
        queue.enqueue(EgvUpload(serial = "SN1", timeMs = 2L, mgdl = 110, trend = 1))
        val uploader = ICanHealthCloudUploader(client, settings, queue)
        val result = uploader.flush()
        assertTrue(result is ICanHealthCloudUploader.FlushResult.Failed || result is ICanHealthCloudUploader.FlushResult.Success)
    }


}

private class TestB64Cipher : ICanHealthCloudSettings.PasswordCipher {
    override fun encrypt(value: String): String =
        java.util.Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8))
    override fun decrypt(value: String): String =
        String(java.util.Base64.getDecoder().decode(value), Charsets.UTF_8)
}

class ICanHealthCloudUploaderTestApplication : Applic() {
    override fun onCreate() {}
}