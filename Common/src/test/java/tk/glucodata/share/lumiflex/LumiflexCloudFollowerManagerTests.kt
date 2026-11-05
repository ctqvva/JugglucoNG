package tk.glucodata.share.lumiflex

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = LumiflexCloudTestApplication::class)
class LumiflexCloudFollowerManagerTests {

    private class FakeTransport : LumiflexCloudClient.Transport {
        val bodies = mutableListOf<JSONObject>()
        val paths = mutableListOf<String>()

        override fun post(url: String, body: String, headers: Map<String, String>): LumiflexCloudClient.HttpResult {
            paths.add(url.substringAfter("/api-x"))
            bodies.add(JSONObject(body))
            return LumiflexCloudClient.HttpResult(200, """{"code":200,"data":{}}""")
        }

        override fun get(url: String, headers: Map<String, String>): LumiflexCloudClient.HttpResult =
            LumiflexCloudClient.HttpResult(200, """{"code":200,"data":[]}""")
    }

    private fun manager(transport: FakeTransport): LumiflexCloudFollowerManager {
        val context = RuntimeEnvironment.getApplication()
        val settings = LumiflexCloudSettings(context)
        settings.token = "tok"
        settings.userId = "u1"
        return LumiflexCloudFollowerManager(
            LumiflexCloudClient(transport, encrypt = { it }, decrypt = { it }),
            settings,
        )
    }

    @Test
    fun inviteSendsVendorFields() {
        val transport = FakeTransport()
        assertTrue(manager(transport).invite("f@b.c", "Ann"))
        assertEquals("/userAuthorization/saveOrUpdateUserAuthorization", transport.paths.first())
        val body = transport.bodies.first()
        assertEquals("f@b.c", body.getString("readerUserName"))
        assertEquals("Ann", body.getString("readerAlias"))
        assertEquals(1, body.getInt("normalPushState"))
        assertEquals(1, body.getInt("emergePushState"))
        assertEquals(0, body.getInt("hideState"))
    }

    @Test
    fun alertsAndDisplayFlagsMapToVendorNames() {
        val transport = FakeTransport()
        val manager = manager(transport)
        manager.setDisplayHidden("a1", true)
        manager.setAlerts("a1", normal = false, urgent = true, extremeHigh = true)
        assertEquals(1, transport.bodies[0].getInt("hideState"))
        assertEquals(0, transport.bodies[1].getInt("normalPushState"))
        assertEquals(1, transport.bodies[1].getInt("emergePushState"))
        assertEquals(1, transport.bodies[1].getInt("extremeHighPushState"))
        assertEquals("a1", transport.bodies[1].getString("userAuthorizationId"))
    }

    @Test
    fun removePostsIdsArray() {
        val transport = FakeTransport()
        assertTrue(manager(transport).remove("a1"))
        assertEquals("/userAuthorization/deleteByIds", transport.paths.first())
        assertEquals("a1", transport.bodies.first().getJSONArray("ids").getString(0))
    }
}
