package tk.glucodata.share.lumiflex

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun loginSendsPhoneDigitsAndPersistsTheAcceptedAccount() {
        for (account in listOf("+79991234567", "  +7 (999) 123-45-67  ", "79991234567")) {
            val transport = FakeTransport { _, body ->
                val request = JSONObject(LumiflexCloudCrypto.decryptEnvelope(body, keys.second)!!)
                assertEquals("79991234567", request.getString("userName"))
                LumiflexCloudClient.HttpResult(
                    200,
                    envelope("""{"code":200,"data":{"token":"tok","userId":"u1"}}"""),
                )
            }
            val s = settings()
            val result = newClient(transport).login(account, "pw", s)
            assertEquals(LumiflexCloudClient.LoginResult.Ok("u1", "tok", "79991234567"), result)
            assertEquals("79991234567", s.account)
        }
    }

    @Test
    fun loginTrimsEmailWhitespaceAndPreservesPlusAddressingAndPasswordWhitespace() {
        val transport = FakeTransport { _, body ->
            val request = JSONObject(LumiflexCloudCrypto.decryptEnvelope(body, keys.second)!!)
            assertEquals("patient+cloud@example.com", request.getString("userName"))
            assertEquals(LumiflexCloudCrypto.passwordHash(" pw "), request.getString("password"))
            LumiflexCloudClient.HttpResult(
                200,
                envelope("""{"code":200,"data":{"token":"tok","userId":"u1"}}"""),
            )
        }
        val result = newClient(transport).login("  patient+cloud@example.com  ", " pw ")
        assertEquals(LumiflexCloudClient.LoginResult.Ok("u1", "tok", "patient+cloud@example.com"), result)
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
    fun registrationUsesMobileSmsContractAndKeepsAccountCreationSeparateFromPasswordSetup() {
        val payloads = mutableListOf<JSONObject>()
        val transport = FakeTransport { url, body ->
            payloads += JSONObject(LumiflexCloudCrypto.decryptEnvelope(body, keys.second)!!)
            LumiflexCloudClient.HttpResult(200, envelope(
                if (url.endsWith("loginOrRegisterByVerificationCodeWithPhone"))
                    """{"code":200,"data":{"token":"verified-token","userId":"new-user"}}"""
                else """{"code":200}""",
            ))
        }
        val client = newClient(transport)
        assertEquals(LumiflexCloudClient.SyncResult.Ok, client.requestRegistrationCode("+7 (999) 123-45-67"))
        val login = client.registerWithCode("+79991234567", " 123456 ") as LumiflexCloudClient.LoginResult.Ok
        assertEquals("7", payloads[0].getString("zoneNumber"))
        assertEquals("9991234567", payloads[0].getString("phone"))
        assertEquals("79991234567", payloads[1].getString("phone"))
        assertEquals("123456", payloads[1].getString("code"))
        assertFalse(payloads[1].has("password"))
        assertEquals("/sms/sendRegisterOverseaPhoneVerificationCode", java.net.URL(transport.urls[0]).path.removePrefix("/api-x"))
        assertEquals("verified-token", login.token)
        assertEquals(LumiflexCloudClient.SyncResult.Ok, client.setPassword(login.token, login.userId, "pass1234"))
        assertEquals(LumiflexCloudCrypto.passwordHash("pass1234"), payloads[2].getString("password"))
        assertEquals("verified-token", transport.headers[2]["x-token"])
        assertTrue(transport.urls[2].endsWith("/user/setPassword"))
    }

    @Test
    fun invalidRegistrationInputDoesNotSendSms() {
        val transport = FakeTransport { _, _ -> error("Invalid numbers must not reach the service") }
        for (phone in listOf("", "a@b.c", "+12345678901", "7999", "+7wrong9991234567")) {
            assertTrue(newClient(transport).requestRegistrationCode(phone) is LumiflexCloudClient.SyncResult.Failed)
        }
        assertTrue(transport.urls.isEmpty())
    }

    @Test
    fun incompleteSignupReplyCannotClaimSuccessOrExposeItsToken() {
        val transport = FakeTransport { _, _ -> LumiflexCloudClient.HttpResult(
            200, envelope("""{"code":200,"data":{"token":"private-session"}}"""),
        ) }
        val result = newClient(transport).registerWithCode("79991234567", "123456")
            as LumiflexCloudClient.LoginResult.Failed
        assertFalse(result.message.contains("private-session"))
    }

    @Test
    fun passwordSetupAndSmsSendingRequireAnExplicitSuccessReply() {
        val transport = FakeTransport { _, _ -> LumiflexCloudClient.HttpResult(200, "{}") }
        assertTrue(newClient(transport).requestRegistrationCode("79991234567") is LumiflexCloudClient.SyncResult.Failed)
        assertTrue(newClient(transport).setPassword("token", "u1", "pass1234") is LumiflexCloudClient.SyncResult.Failed)
    }

    @Test
    fun accountValidationChecksTheReturnedIdentityAndExpiredVendorCodes() {
        var reply = """{"code":200,"data":{"userId":"u1"}}"""
        val transport = FakeTransport { _, _ -> LumiflexCloudClient.HttpResult(200, envelope(reply)) }
        val client = newClient(transport)
        assertEquals(LumiflexCloudClient.SyncResult.Ok, client.checkSession("token", "u1"))
        reply = """{"code":200,"data":{"userId":"another-user"}}"""
        assertTrue(client.checkSession("token", "u1") is LumiflexCloudClient.SyncResult.Failed)
        for (code in listOf(800, 801, 802)) {
            reply = """{"code":$code,"msg":"Session expired"}"""
            val failure = client.checkSession("token", "u1") as LumiflexCloudClient.SyncResult.Failed
            assertEquals(LumiflexCloudError.Kind.UNAUTHORIZED, failure.kind)
        }
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
        val list = newClient(transport).devicesBySensorSerial("tok")
        assertEquals("SID1", (list as LumiflexCloudClient.DeviceList.Ok).sensorIdBySerial["SN1"])
    }

    @Test
    fun devicesBySensorSerialReportsUnauthorizedSeparately() {
        val transport = FakeTransport { _, _ -> LumiflexCloudClient.HttpResult(401, "") }
        assertEquals(
            LumiflexCloudClient.DeviceList.Unauthorized,
            newClient(transport).devicesBySensorSerial("tok"),
        )
    }

    @Test
    fun devicesBySensorSerialReportsUnreadableReplyAsFailure() {
        val transport = FakeTransport { _, _ -> LumiflexCloudClient.HttpResult(200, "oops") }
        assertTrue(newClient(transport).devicesBySensorSerial("tok") is LumiflexCloudClient.DeviceList.Failed)
    }

    @Test
    fun devicesBySensorSerialReportsBusinessErrorAsFailure() {
        val transport = FakeTransport { _, _ ->
            LumiflexCloudClient.HttpResult(200, envelope("{\"code\":300003,\"message\":\"bad device\"}"))
        }
        val result = newClient(transport).devicesBySensorSerial("tok")
        assertTrue(result is LumiflexCloudClient.DeviceList.Failed)
        assertEquals(300003, (result as LumiflexCloudClient.DeviceList.Failed).code)
    }

    @Test
    fun devicesBySensorSerialTreatsMissingDataAsFailure() {
        val transport = FakeTransport { _, _ ->
            LumiflexCloudClient.HttpResult(200, envelope("{\"code\":200}"))
        }
        assertTrue(
            newClient(transport).devicesBySensorSerial("tok") is LumiflexCloudClient.DeviceList.Failed,
        )
    }

    @Test
    fun devicesBySensorSerialTreatsExplicitlyEmptyDataAsEmptyList() {
        val transport = FakeTransport { _, _ ->
            LumiflexCloudClient.HttpResult(200, envelope("{\"code\":200,\"data\":null}"))
        }
        val result = newClient(transport).devicesBySensorSerial("tok")
        assertEquals(
            LumiflexCloudClient.DeviceList.Ok(emptyMap()),
            result,
        )
    }

    @Test
    fun devicesBySensorSerialTreatsSessionReplacedCodeAsUnauthorized() {
        val transport = FakeTransport { _, _ ->
            LumiflexCloudClient.HttpResult(
                200,
                envelope("{\"code\":802,\"message\":\"logged in from other equipments\"}"),
            )
        }
        assertEquals(
            LumiflexCloudClient.DeviceList.Unauthorized,
            newClient(transport).devicesBySensorSerial("tok"),
        )
    }

    @Test
    fun uploadTreatsSessionReplacedCodeAsUnauthorized() {
        val transport = FakeTransport { _, _ ->
            LumiflexCloudClient.HttpResult(
                200,
                envelope("{\"code\":802,\"message\":\"logged in from other equipments\"}"),
            )
        }
        val result = newClient(transport).uploadGlucose(
            "tok",
            "u1",
            listOf(CgmRecord("SN1", "SN1", 0, 5, 1_700_000_300_000L, 100, 1)),
            1_700_000_300_000L,
        )
        assertEquals(
            LumiflexCloudError.Kind.UNAUTHORIZED,
            (result as LumiflexCloudClient.SyncResult.Failed).kind,
        )
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
    @Test
    fun httpFailuresRemainTransientEvenWhenTheirJsonHasNoCodeOrClaimsSuccess() {
        for (reply in listOf("{}", """{"code":200,"msg":"Unavailable"}""")) {
            val transport = FakeTransport { _, _ -> LumiflexCloudClient.HttpResult(503, reply) }
            val result = newClient(transport).login("79991234567", "pass1234") as LumiflexCloudClient.LoginResult.Failed
            assertEquals(LumiflexCloudError.Kind.TRANSIENT, result.kind)
            assertEquals(503, result.code)
        }
    }

    @Test
    fun malformedSuccessfulDeviceLookupsRemainRetryable() {
        for (reply in listOf("not-json", "{}", """{"code":200}""", """{"code":200,"data":[]}""")) {
            val lookup = object : LumiflexCloudClient.Transport {
                override fun get(url: String, headers: Map<String, String>) = LumiflexCloudClient.HttpResult(200, reply)
                override fun post(url: String, body: String, headers: Map<String, String>) = error("Unexpected POST")
            }
            val result = LumiflexCloudClient(lookup, encrypt = { it }).devicesBySensorSerial("token")
                as LumiflexCloudClient.DeviceList.Failed
            assertEquals(LumiflexCloudError.Kind.TRANSIENT, result.kind)
        }
    }

}
