package tk.glucodata.share.ican

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tk.glucodata.share.ican.ICanHealthCloudClient.HttpResult
import tk.glucodata.share.ican.ICanHealthCloudClient.LoginResult
import tk.glucodata.share.ican.ICanHealthCloudClient.UploadResult

class ICanHealthCloudClientTests {

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
    }

    @Test
    fun loginSuccessReturnsOkWithToken() {
        val transport = FakeTransport { url ->
            if (url.contains("oauth/token")) HttpResult(200, """{"code":0,"success":true,"msg":"","data":{"access_token":"tok123"}}""")
            else HttpResult(404, "")
        }
        val result = ICanHealthCloudClient(transport).login("acct", "pwd")
        assertTrue(result is LoginResult.Ok)
        assertEquals("tok123", (result as LoginResult.Ok).sessionId)
    }

    @Test
    fun loginCode30ReturnsActivateAccount() {
        val transport = FakeTransport { url ->
            HttpResult(200, """{"code":30,"success":false,"msg":"not activated","data":null}""")
        }
        val result = ICanHealthCloudClient(transport).login("acct", "pwd")
        assertTrue(result is LoginResult.Failed)
        assertEquals(ICanHealthCloudError.Kind.ACTIVATE_ACCOUNT, (result as LoginResult.Failed).kind)
    }

    @Test
    fun login401ReturnsUnauthorized() {
        val transport = FakeTransport { url ->
            HttpResult(401, """{"code":401,"msg":"expired"}""")
        }
        val result = ICanHealthCloudClient(transport).login("acct", "pwd")
        assertTrue(result is LoginResult.Failed)
        assertEquals(ICanHealthCloudError.Kind.UNAUTHORIZED, (result as LoginResult.Failed).kind)
    }

    @Test
    fun loginTransportFailureReturnsTransient() {
        val transport = FakeTransport { url ->
            HttpResult(-1, "connect timed out")
        }
        val result = ICanHealthCloudClient(transport).login("acct", "pwd")
        assertTrue(result is LoginResult.Failed)
        assertEquals(ICanHealthCloudError.Kind.TRANSIENT, (result as LoginResult.Failed).kind)
    }

    @Test
    fun encryptedPostSendsDataAndKey() {
        val transport = FakeTransport { url -> HttpResult(200, """{"code":0}""") }
        val client = ICanHealthCloudClient(transport)
        val result = client.uploadGlucose("tok", "SN1", listOf(EgvUpload(serial = "SN1", timeMs = 1L, mgdl = 100, trend = 1)))
        assertTrue(result is UploadResult.Ok)
        val body = transport.calls.single().second!!
        val json = JSONObject(body)
        assertTrue(json.has("data"))
        assertTrue(json.has("key"))
    }

    @Test
    fun followerSavePayloadHasTheRightKeys() {
        val client = ICanHealthCloudClient(FakeTransport { HttpResult(200, """{"code":0,"data":{}}""") })
        val payload = client.buildFollowerSavePayload("a@b.com", "123", "7", "name")
        assertEquals(6, payload.size)
        assertEquals("a@b.com", payload["email"])
        assertEquals("123", payload["phone"])
        assertEquals("7", payload["phoneAreaCode"])
        assertEquals("name", payload["name"])
        assertTrue(payload.containsKey("id"))
        assertTrue(payload.containsKey("invitedTime"))
    }

    @Test
    fun followerSaveSendsTheRightKeys() {
        val transport = FakeTransport { url -> HttpResult(200, """{"code":0,"data":{}}""") }
        val client = ICanHealthCloudClient(transport)
        client.followerSave("tok", "a@b.com", "123", "7", "name")
        val body = transport.calls.single().second!!
        val json = JSONObject(body)
        assertTrue(json.has("data"))
        assertTrue(json.has("key"))
    }

    @Test
    fun uploadRejectsBodyCode401EvenOnHttp200() {
        val transport = FakeTransport { _ -> HttpResult(200, """{"code":401,"msg":"expired"}""") }
        val client = ICanHealthCloudClient(transport)
        val result = client.uploadGlucose("tok", "SN", emptyList())
        assertTrue(result is ICanHealthCloudClient.UploadResult.Failed)
        assertEquals(
            tk.glucodata.share.ican.ICanHealthCloudError.Kind.UNAUTHORIZED,
            (result as ICanHealthCloudClient.UploadResult.Failed).kind,
        )
    }

    @Test
    fun followerUpdateReportsHttpFailure() {
        val transport = FakeTransport { _ -> HttpResult(500, "boom") }
        val client = ICanHealthCloudClient(transport)
        assertFalse(client.followerUpdate("tok", "1", "deleted", ""))
    }
}
