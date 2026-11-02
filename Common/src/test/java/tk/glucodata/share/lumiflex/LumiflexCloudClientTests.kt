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
class LumiflexCloudClientTests {

    private class FakeTransport(var handler: (String, String) -> LumiflexCloudClient.HttpResult) :
        LumiflexCloudClient.Transport {
        val urls = mutableListOf<String>()
        val headers = mutableListOf<Map<String, String>>()

        override fun post(url: String, body: String, headers: Map<String, String>): LumiflexCloudClient.HttpResult {
            urls.add(url); this.headers.add(headers); return handler(url, body)
        }

        override fun get(url: String, headers: Map<String, String>): LumiflexCloudClient.HttpResult {
            urls.add(url); this.headers.add(headers); return handler(url, "")
        }
    }

    private val keys: Pair<String, String> by lazy {
        val generator = java.security.KeyPairGenerator.getInstance("RSA")
        generator.initialize(1024)
        val pair = generator.generateKeyPair()
        java.util.Base64.getEncoder().encodeToString(pair.public.encoded) to
            java.util.Base64.getEncoder().encodeToString(pair.private.encoded)
    }

    private fun newClient(transport: LumiflexCloudClient.Transport): LumiflexCloudClient =
        LumiflexCloudClient(
            transport = transport,
            encrypt = { LumiflexCloudCrypto.encryptEnvelope(it, keys.first) },
            decrypt = { LumiflexCloudCrypto.decryptEnvelope(it, keys.second) },
        )

    private fun settings(): LumiflexCloudSettings {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("lumiflex_cloud", android.content.Context.MODE_PRIVATE)
            .edit().clear().commit()
        return LumiflexCloudSettings(context)
    }

    private fun envelope(json: String): String = LumiflexCloudCrypto.encryptEnvelope(json, keys.first)

    @Test
    fun loginStoresTokenAndUserIdAndSendsEnvelopeAndHeaders() {
        val transport = FakeTransport { _, body ->
            val plain = LumiflexCloudCrypto.decryptEnvelope(body, keys.second)!!
            assertTrue(plain.contains("\"userName\":\"a@b.c\""))
            LumiflexCloudClient.HttpResult(
                200,
                envelope("""{"code":200,"data":{"token":"tok","userId":"u1","email":"a@b.c","phone":""}}"""),
            )
        }
        val client = newClient(transport)
        val s = settings()
        val result = client.login("a@b.c", "pw", s)
        assertTrue(result is LumiflexCloudClient.LoginResult.Ok)
        assertEquals("tok", s.token)
        assertEquals("u1", s.userId)
        assertEquals("https://cloud.lumiflex.ru/api-x/user/loginByPassword", transport.urls.first())
        assertEquals("lumi", transport.headers.first()["brand"])
        assertEquals("ru", transport.headers.first()["version"])
        assertEquals("enabled", transport.headers.first()["encryption"])
        assertTrue(transport.headers.first().containsKey("app-version-check"))
    }

    @Test
    fun loginSendsMd5OfPassword() {
        var plainSeen = ""
        val transport = FakeTransport { _, body ->
            plainSeen = LumiflexCloudCrypto.decryptEnvelope(body, keys.second)!!
            LumiflexCloudClient.HttpResult(
                200,
                envelope("{\"code\":200,\"data\":{\"token\":\"tok\",\"userId\":\"u1\"}}"),
            )
        }
        newClient(transport).login("a@b.c", "pw", settings())
        val json = JSONObject(plainSeen)
        assertEquals("8fe4c11451281c094a6578e6ddbf5eed", json.getString("password"))
    }

    @Test
    fun loginWithoutSettingsStoresNothing() {
        val transport = FakeTransport { _, _ ->
            LumiflexCloudClient.HttpResult(
                200,
                envelope("{\"code\":200,\"data\":{\"token\":\"tok\",\"userId\":\"u1\"}}"),
            )
        }
        val s = settings()
        val result = newClient(transport).login("a@b.c", "pw")
        assertTrue(result is LumiflexCloudClient.LoginResult.Ok)
        assertEquals("", s.token)
        assertEquals("", s.account)
    }

    @Test
    fun login401ClassifiesUnauthorized() {
        val transport = FakeTransport { _, _ -> LumiflexCloudClient.HttpResult(401, "") }
        val result = newClient(transport).login("a@b.c", "pw", settings())
        assertTrue(result is LumiflexCloudClient.LoginResult.Failed)
        assertEquals(
            LumiflexCloudError.Kind.UNAUTHORIZED,
            (result as LumiflexCloudClient.LoginResult.Failed).kind,
        )
    }

    @Test
    fun uploadSendsRecordsArray() {
        var plainSeen = ""
        val transport = FakeTransport { _, body ->
            plainSeen = LumiflexCloudCrypto.decryptEnvelope(body, keys.second)!!
            LumiflexCloudClient.HttpResult(200, envelope("""{"code":200,"data":{}}"""))
        }
        val client = newClient(transport)
        val result = client.uploadGlucose(
            token = "tok",
            userId = "u1",
            records = listOf(CgmRecord("SN1", "SN1", 0, 5, 1_700_000_300_000L, 100, 1)),
            nowMs = 1_700_000_300_000L,
        )
        assertTrue(result is LumiflexCloudClient.SyncResult.Ok)
        val json = JSONObject(plainSeen)
        assertEquals(1, json.getJSONArray("records").length())
        assertEquals("tok", transport.headers.first()["x-token"])
    }

    @Test
    fun upload5xxIsTransient() {
        val transport = FakeTransport { _, _ -> LumiflexCloudClient.HttpResult(503, "") }
        val result = newClient(transport)
            .uploadGlucose(
                "tok",
                "u1",
                listOf(CgmRecord("SN1", "SN1", 0, 5, 1_700_000_300_000L, 100, 1)),
                1_700_000_300_000L,
            )
        assertEquals(
            LumiflexCloudError.Kind.TRANSIENT,
            (result as LumiflexCloudClient.SyncResult.Failed).kind,
        )
    }

    @Test
    fun followerListUsesNumericType() {
        val transport = FakeTransport { _, _ ->
            LumiflexCloudClient.HttpResult(200, envelope("{\"code\":200,\"data\":[]}"))
        }
        newClient(transport).followerList("tok", isShare = false, userId = "u1")
        assertTrue(transport.urls.first().contains("?type=0"))
    }

    @Test
    fun devicesBySensorSerialMapsDeviceSnToSensorId() {
        val transport = FakeTransport { _, _ ->
            LumiflexCloudClient.HttpResult(
                200,
                envelope("{\"code\":200,\"data\":{\"firstList\":[{\"deviceSn\":\"SN1\",\"sensorId\":\"SID1\"}]}}"),
            )
        }
        val map = newClient(transport).devicesBySensorSerial("tok")
        assertEquals("SID1", map?.get("SN1"))
    }

    @Test
    fun uploadNonJsonSuccessBodyIsFailure() {
        val transport = FakeTransport { _, _ -> LumiflexCloudClient.HttpResult(200, "oops") }
        val result = newClient(transport).uploadGlucose(
            "tok",
            "u1",
            listOf(CgmRecord("SN1", "SN1", 0, 5, 1_700_000_300_000L, 100, 1)),
            1_700_000_300_000L,
        )
        assertTrue(result is LumiflexCloudClient.SyncResult.Failed)
    }

    @Test
    fun registerDevicePostsVendorPayload() {
        var bodySeen = ""
        val transport = FakeTransport { _, body ->
            bodySeen = LumiflexCloudCrypto.decryptEnvelope(body, keys.second)!!
            LumiflexCloudClient.HttpResult(200, envelope("{\"code\":200,\"data\":{}}"))
        }
        val ok = newClient(transport).registerDevice("tok", "u1", "SN1")
        assertTrue(ok)
        assertTrue(transport.urls.first().endsWith("/cgmDevice/userDeviceRegister"))
        val json = JSONObject(bodySeen)
        assertEquals("SN1", json.getString("deviceSn"))
        assertEquals("SN1", json.getString("sensorId"))
    }

    @Test
    fun followerListParsesDataArray() {
        val transport = FakeTransport { _, _ ->
            LumiflexCloudClient.HttpResult(
                200,
                envelope("""{"code":200,"data":[{"userAuthorizationId":"a1","readerUserName":"f@b.c","dataProviderId":"u1","dataReaderId":"u2"}]}"""),
            )
        }
        val list = newClient(transport).followerList("tok", isShare = true, userId = "u1")
        assertEquals(1, list?.size)
        assertEquals("a1", list?.first()?.userAuthorizationId)
        assertTrue(transport.urls.first().contains("/userAuthorization/findUserAuthorizationList?type=0"))
    }
}
