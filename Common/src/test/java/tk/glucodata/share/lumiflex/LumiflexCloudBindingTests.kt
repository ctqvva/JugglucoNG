package tk.glucodata.share.lumiflex

import java.util.Base64
import java.util.TimeZone
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = LumiflexCloudTestApplication::class)
class LumiflexCloudBindingTests {
    private val context get() = RuntimeEnvironment.getApplication()
    private val settings get() = LumiflexCloudSettings(context)
    private val queue get() = LumiflexCloudQueue(context)
    private val start = 1_700_000_000_000L
    private val key = ByteArray(16) { (it + 1).toByte() }
    private fun sensor() = LumiflexCloudSensor("X-1111222233", "01:23:45:67:89:AB", start, key, 15)

    @Before fun reset() {
        settings.clear()
        settings.startSession("account", "token", "user")
        settings.enabled = true
        queue.clear()
    }

    private class Transport : LumiflexCloudClient.Transport {
        var registered = false
        var registrationCalls = 0
        var uploads = 0
        var registrationBody = ""
        var registrationResponse = LumiflexCloudClient.HttpResult(200, """{"code":200,"data":{"deviceId":"accepted"}}""")
        var onRegistration: () -> Unit = {}
        override fun get(url: String, headers: Map<String, String>) = LumiflexCloudClient.HttpResult(200,
            if (registered) """{"code":200,"data":{"firstList":[{"deviceSn":"1111222233","sensorId":"server-sensor-id"}]}}"""
            else """{"code":200,"data":{"firstList":[]}}""",
        )
        override fun post(url: String, body: String, headers: Map<String, String>): LumiflexCloudClient.HttpResult {
            if (url.endsWith("userDeviceRegister")) {
                registrationCalls++
                registrationBody = body
                registered = true
                onRegistration()
                return registrationResponse
            }
            assertTrue(url.endsWith("saveCgmRecord"))
            val record = JSONObject(body).getJSONArray("records").getJSONObject(0)
            assertEquals("server-sensor-id", record.getString("sensorId"))
            uploads++
            return LumiflexCloudClient.HttpResult(200, """{"code":200}""")
        }
    }

    private fun uploader(transport: Transport, sensors: List<LumiflexCloudSensor> = listOf(sensor())): LumiflexCloudUploader {
        val client = LumiflexCloudClient(transport, encrypt = { it })
        return LumiflexCloudUploader(client, settings, queue, sleep = {},
            sensorIds = client::devicesBySensorSerial, bindingSensors = { sensors })
    }

    private fun enqueue() { queue.enqueue(CgmRecord("X-1111222233", "X-1111222233", 0, 1, start + 60_000, 100, 0), "account") }

    @Test
    fun bindsFromActualPairingMetadataAndUploadsOnlyWithTheServerConfirmedSensorId() {
        enqueue()
        val transport = Transport()
        assertEquals(LumiflexCloudUploader.FlushResult.Success(1), uploader(transport).flush())
        assertEquals(1, transport.registrationCalls)
        assertEquals(1, transport.uploads)
        val body = JSONObject(transport.registrationBody)
        assertEquals("1111222233", body.getString("deviceSn"))
        assertEquals("01:23:45:67:89:AB", body.getString("deviceMac"))
        assertEquals(Base64.getEncoder().encodeToString(key), body.getString("deviceKey"))
        assertNotEquals("server-sensor-id", body.getString("sensorId"))
        assertEquals(listOf("1111222233"), settings.syncStatus().boundSensors)
        assertEquals(0, queue.size())
    }

    @Test
    fun bindingWorksBeforeTheFirstReadingAndDoesNotPretendItWasAnUpload() {
        val transport = Transport()
        assertEquals(LumiflexCloudUploader.FlushResult.Success(0), uploader(transport).flush())
        assertEquals(1, transport.registrationCalls)
        assertEquals(0, transport.uploads)
        assertEquals(listOf("1111222233"), settings.syncStatus().boundSensors)
        assertEquals(0L, settings.lastSyncMs)
    }

    @Test
    fun aLostRegistrationReplyIsRecoveredByLookupWithoutDuplicateBinding() {
        enqueue()
        val transport = Transport().apply { registrationResponse = LumiflexCloudClient.HttpResult(-1, "Response lost") }
        assertTrue(uploader(transport).flush() is LumiflexCloudUploader.FlushResult.Failed)
        assertEquals(1, queue.size())
        assertEquals(LumiflexCloudUploader.FlushResult.Success(1), uploader(transport).flush())
        assertEquals(1, transport.registrationCalls)
        assertEquals(1, transport.uploads)
    }

    @Test
    fun anExistingBindingDoesNotSendThePairKeyAgain() {
        enqueue()
        val transport = Transport().apply { registered = true }
        assertEquals(LumiflexCloudUploader.FlushResult.Success(1), uploader(transport).flush())
        assertEquals(0, transport.registrationCalls)
        assertEquals(1, transport.uploads)
    }

    @Test
    fun incompletePairingIsNeverReplacedWithInventedCredentials() {
        enqueue()
        val transport = Transport()
        val incomplete = LumiflexCloudSensor("1111222233", "", start, ByteArray(0))
        assertEquals(LumiflexCloudUploader.FlushResult.WaitingForSensor(0), uploader(transport, listOf(incomplete)).flush())
        assertEquals(0, transport.registrationCalls)
        assertEquals(1, queue.size())
    }

    @Test
    fun aBindingReplyAfterAccountReplacementCannotPublishStatusOrUploadToTheNewAccount() {
        enqueue()
        val transport = Transport().apply {
            onRegistration = {
                settings.startSession("new-account", "new-token", "new-user")
                queue.enqueue(CgmRecord("9999000011", "9999000011", 0, 1, start + 60_000, 120, 0), "new-account")
            }
        }
        uploader(transport).flush()
        assertTrue(settings.syncStatus().boundSensors.isEmpty())
        assertEquals("new-account", queue.owner())
        assertEquals("9999000011", queue.peek().single().serial)
        assertEquals(0, transport.uploads)
    }

    @Test
    fun requestIdentityIsStablePerAccountAndActivationAndUsesTheRealActivationInstant() {
        val first = sensor().registrationJson("user", start + 60_000, TimeZone.getTimeZone("UTC"))
        val retry = sensor().registrationJson("user", start + 120_000, TimeZone.getTimeZone("UTC"))
        assertEquals(first.getString("deviceId"), retry.getString("deviceId"))
        assertEquals(first.getString("sensorId"), retry.getString("sensorId"))
        assertEquals("2023-11-14 22:13:20+0000", first.getString("sensorStartUp"))
        assertNotEquals(first.getString("sensorId"), sensor().registrationJson("another-user", start).getString("sensorId"))
        assertNotEquals(first.getString("sensorId"),
            LumiflexCloudSensor("1111222233", "01:23:45:67:89:AB", start + 60_000, key).registrationJson("user", start).getString("sensorId"))
    }
}
