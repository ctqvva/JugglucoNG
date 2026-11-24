package tk.glucodata.drivers.bioe

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class BioeApiTests {
    private val timestampMs = 1_700_000_000_000L
    private val token = "<user token>"

    private class FakeTransport(private val response: BioeHttpResult) : BioeApiTransport {
        var url: String = ""
        var body: String = ""
        var headers: Map<String, String> = emptyMap()

        override fun post(url: String, jsonBody: String, headers: Map<String, String>): BioeHttpResult {
            this.url = url
            this.body = jsonBody
            this.headers = headers
            return response
        }
    }

    private fun deviceJson(
        code: String,
        k: String,
        b: String,
        threshold: String = "450.25",
    ): String = """
        {
          "deviceCode": "$code",
          "convNum": "$k",
          "verifyVal": "$b",
          "lowCurrentThresholdNa": "$threshold",
          "btName": "BECGM-TEST",
          "btMac": "<BLE MAC>",
          "useAt": "1700000060000",
          "sensorLifetimeDays": 14,
          "algorithmStartMinutes": 60,
          "quickInitCount": 3,
          "quickInitInterval": 5
        }
    """.trimIndent()

    private fun listResponse(vararg devices: String): String =
        "{\"code\":0,\"result\":true,\"data\":{\"list\":[${devices.joinToString(",")}]}}"

    @Test
    fun signatureUsesVendorStaticSecret() {
        assertEquals("6ff5309f39e56fdb7948dd69699f454c", BioeApi.sign(timestampMs))
    }

    @Test
    fun signatureDoesNotDependOnDefaultLocale() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale("ar"))
            assertEquals("6ff5309f39e56fdb7948dd69699f454c", BioeApi.sign(timestampMs))
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test
    fun requestBodyCarriesTokenSignAndTimestamp() {
        val body = JSONObject(BioeApi.requestBody(token, timestampMs))
        assertEquals(token, body.getString("token"))
        assertEquals(BioeApi.sign(timestampMs), body.getString("sign"))
        assertEquals(timestampMs, body.getLong("nowTimestamp"))
    }

    @Test
    fun requestHeadersCarryVendorProtocolValues() {
        val headers = BioeApi.requestHeaders(token, timestampMs)
        assertEquals(token, headers["djs-api-token"])
        assertEquals("android", headers["djs-client-type"])
        assertEquals(timestampMs.toString(), headers["djs-timestamp"])
        assertEquals("scgm", headers["djs-product-name"])
        assertEquals("1.4.80(195)", headers["djs-app-version"])
        assertTrue(headers.getValue("djs-request-id").matches(Regex("[0-9a-f]{32}")))
    }

    @Test
    fun parseDeviceListKeepsFullPrecisionCoefficientsAndServerFields() {
        val devices = BioeApi.parseDeviceList(
            listResponse(deviceJson("TESTCODE", "0.03989725123456789", "-33.257442123456"))
        )
        assertEquals(1, devices.size)
        val device = devices.single()
        assertEquals("TESTCODE", device.deviceCode)
        assertEquals("0.03989725123456789", device.kRaw)
        assertEquals("-33.257442123456", device.bRaw)
        assertEquals("450.25", device.thresholdRaw)
        assertEquals(1_700_000_060_000L, device.useAtMs)
        assertEquals(14, device.sensorLifetimeDays)
        assertEquals(60, device.algorithmStartMinutes)
        assertEquals(3, device.quickInitCount)
        assertEquals(5, device.quickInitInterval)
        assertNotNull(device.calibration)
        assertEquals(450.25, device.calibration!!.lowCurrentThresholdNa, 0.0)
    }

    @Test
    fun parseDeviceListAcceptsNumericCoefficientsAndSourceAlias() {
        val body = """
            {"result":true,"source":{"list":[{
              "deviceCode":"SECOND","convNum":0.04,"verifyVal":-33.5,
              "lowCurrentThresholdNa":400,"useAt":1700000120000
            }]}}
        """.trimIndent()
        val device = BioeApi.parseDeviceList(body).single()
        assertEquals("0.04", device.kRaw)
        assertEquals("-33.5", device.bRaw)
        assertEquals("400", device.thresholdRaw)
        assertEquals(1_700_000_120_000L, device.useAtMs)
    }

    @Test
    fun parseDeviceListRejectsServerFailureAndMissingData() {
        val failed = BioeApi.fetchDevices(
            token,
            timestampMs,
            FakeTransport(BioeHttpResult(200, "{\"code\":401,\"msg\":\"bad token\"}")),
        )
        assertTrue(failed is BioeApiResult.ProtocolError)
        assertEquals("bad token", (failed as BioeApiResult.ProtocolError).message)

        val malformed = BioeApi.fetchDevices(
            token,
            timestampMs,
            FakeTransport(BioeHttpResult(200, "{\"code\":0}")),
        )
        assertTrue(malformed is BioeApiResult.ProtocolError)
    }

    @Test
    fun fetchDevicesPostsSignedBodyToDeviceListEndpoint() {
        val transport = FakeTransport(BioeHttpResult(200, listResponse()))
        val result = BioeApi.fetchDevices(token, timestampMs, transport)
        assertTrue(result is BioeApiResult.Success)
        assertEquals("https://cgm.bioecare.com/api/device/using/list", transport.url)
        assertEquals(BioeApi.sign(timestampMs), JSONObject(transport.body).getString("sign"))
        assertEquals(token, transport.headers["djs-api-token"])
    }

    @Test
    fun fetchDevicesClassifiesHttpAndTransportFailures() {
        val http = BioeApi.fetchDevices(token, timestampMs, FakeTransport(BioeHttpResult(401, "")))
        assertTrue(http is BioeApiResult.HttpError)
        assertEquals(401, (http as BioeApiResult.HttpError).code)

        val transport = BioeApi.fetchDevices(
            token,
            timestampMs,
            FakeTransport(BioeHttpResult(BioeApi.TRANSPORT_FAILURE_CODE, "timeout")),
        )
        assertTrue(transport is BioeApiResult.TransportError)
        assertEquals("timeout", (transport as BioeApiResult.TransportError).message)

        val blankToken = BioeApi.fetchDevices("   ", timestampMs, FakeTransport(BioeHttpResult(200, "{}")))
        assertTrue(blankToken is BioeApiResult.ProtocolError)
    }

    @Test
    fun selectDevicePrefersRequestedCodeAndFallsBackToFirstUsable() {
        val devices = BioeApi.parseDeviceList(
            listResponse(
                deviceJson("FIRST", "0.04", "-33.0"),
                deviceJson("SECOND", "0.05", "-34.0"),
                deviceJson("BROKEN", "0", "-34.0"),
            )
        )
        assertEquals("SECOND", BioeApi.selectDevice(devices, "second")!!.deviceCode)
        assertEquals("FIRST", BioeApi.selectDevice(devices, "MISSING")!!.deviceCode)
        assertNull(BioeApi.selectDevice(listOf(devices.last()), null))
    }
}
