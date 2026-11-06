package tk.glucodata.share.lumiflex

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = LumiflexCloudTestApplication::class)
class LumiflexCloudRoleTests {
    private val settings get() = LumiflexCloudSettings(RuntimeEnvironment.getApplication())
    @Before fun reset() { settings.clear(); settings.startSession("old", "token", "user"); settings.enabled = true }
    private fun client(body: String, status: Int = 200, beforeReply: () -> Unit = {}): LumiflexCloudClient =
        LumiflexCloudClient(object : LumiflexCloudClient.Transport {
            override fun post(url: String, body: String, headers: Map<String, String>): LumiflexCloudClient.HttpResult = error("unexpected write")
            override fun get(url: String, headers: Map<String, String>): LumiflexCloudClient.HttpResult {
                assertTrue(url.contains("type=1")); assertEquals("token", headers["x-token"])
                beforeReply()
                return LumiflexCloudClient.HttpResult(status, body)
            }
        }, encrypt = { it }, decrypt = { it })

    @Test fun discoveryReplyFromAnOldAccountCannotPrepareTheReplacementForUpload() {
        val result = LumiflexCloudRole.prepare(settings, client("""{"code":200,"data":[]}""") {
            settings.startSession("replacement", "replacement-token", "replacement-user")
        }) { error("stale lookup must stop before pairing inspection") }
        assertSame(LumiflexCloudRole.Preparation.Stop, result)
        assertFalse(settings.modeChosen)
        assertEquals("replacement-token", settings.token)
    }

    @Test fun permanentSharingRejectionDoesNotBlockAnExistingUploaderButCannotPromoteAReader() {
        val rejected = client("""{"code":400,"msg":"Not supported"}""")
        val upload = LumiflexCloudRole.prepare(settings, rejected) { false }
        assertTrue(upload is LumiflexCloudRole.Preparation.Ready)
        assertTrue(settings.modeChosen); assertTrue(settings.uploadEnabled)
        assertEquals(settings.session(), (upload as LumiflexCloudRole.Preparation.Ready).session)
        settings.selectMode(true)
        settings.startSession("another-reader", "token", "reader")
        assertSame(LumiflexCloudRole.Preparation.Stop, LumiflexCloudRole.prepare(settings, rejected) { true })
        assertTrue(settings.followMode); assertFalse(settings.uploadEnabled)
    }

    @Test fun transientDiscoveryFailuresRetryAndExpiredSessionsStop() {
        assertSame(LumiflexCloudRole.Preparation.Retry,
            LumiflexCloudRole.prepare(settings, client("Unavailable", 503)) { false })
        assertFalse(settings.modeChosen); assertTrue(settings.syncStatus().retrying)
        assertSame(LumiflexCloudRole.Preparation.Stop,
            LumiflexCloudRole.prepare(settings, client("""{"code":802}""")) { false })
        assertFalse(settings.isSignedIn())
    }

    @Test fun objectResponsesRequireTheObservedBusinessCodeAndMalformedListsRemainRetryable() {
        for (body in listOf("""{"data":[]}""", """{"code":200,"data":{}}""", "garbage")) {
            val reply = client(body).authorizations("token", false, "user") as LumiflexCloudClient.AuthorizationList.Failed
            assertEquals(LumiflexCloudError.Kind.TRANSIENT, reply.kind)
        }
        assertEquals(emptyList<FollowerInfo>(),
            (client("""{"code":200,"data":[]}""").authorizations("token", false, "user") as LumiflexCloudClient.AuthorizationList.Ok).rows)
    }
}
