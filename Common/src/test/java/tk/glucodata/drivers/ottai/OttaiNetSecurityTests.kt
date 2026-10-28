package tk.glucodata.drivers.ottai

import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPair
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPrivateKeySpec
import java.security.spec.ECPublicKeySpec
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class OttaiNetSecurityTests {
    private val fixture = JSONObject(requireNotNull(javaClass.getResourceAsStream("/ottai-net-security.json"))
        .bufferedReader().use { it.readText() })
    private fun fixedKey(): KeyPair {
        // Public test-vector scalar 1. No live key material is saved in this fixture.
        val params = AlgorithmParameters.getInstance("EC").apply { init(ECGenParameterSpec("secp256r1")) }
            .getParameterSpec(ECParameterSpec::class.java)
        val factory = KeyFactory.getInstance("EC")
        return KeyPair(factory.generatePublic(ECPublicKeySpec(params.generator, params)),
            factory.generatePrivate(ECPrivateKeySpec(BigInteger.ONE, params)))
    }
    private fun same(expected: JSONObject, actual: JSONObject) {
        assertEquals(expected.keys().asSequence().toSet(), actual.keys().asSequence().toSet())
        expected.keys().forEach { assertEquals(it, expected.get(it).toString(), actual.get(it).toString()) }
    }
    private inner class Harness {
        var time = fixture.getLong("now")
        var exchanges = 0
        var sends = 0
        var exchangeReply = JSONObject(fixture.getJSONObject("exchangeResponse").toString())
        var reply = JSONObject(fixture.getJSONObject("response").toString())
        var status = 200
        var randomCall = 0
        var validateExchange = true
        var interruptAfterExchange = false
        var sent: (String, String, Map<String, String>, Map<String, String>, String?) -> Unit = { _, _, _, _, _ -> }
        val client = OttaiNetSecurity(fixture.getString("deviceId"),
            exchange = { request ->
                exchanges++
                if (validateExchange) same(fixture.getJSONObject("exchangeRequest"), request)
                if (interruptAfterExchange) Thread.currentThread().interrupt()
                OttaiNetSecurity.Reply(200, exchangeReply)
            },
            send = { method, path, headers, query, body ->
                sends++; sent(method, path, headers, query, body)
                OttaiNetSecurity.Reply(status, reply)
            }, clock = { time }, keyPair = ::fixedKey,
            randomBytes = { count -> randomCall++; ByteArray(count) { (randomCall * 16 + it).toByte() } })
        fun get(): JSONObject = client.request("GET", "/ota/upgradeVersion/judge",
            mapOf("deviceVersion" to fixture.getJSONObject("query").getString("deviceVersion")))
    }

    @Test fun exactIndependentExchangeQueryAndResponseVectorsPass() {
        val h = Harness()
        h.sent = { method, path, headers, query, body ->
            assertEquals("GET", method)
            assertEquals("/ota/upgradeVersion/judge", path)
            same(fixture.getJSONObject("get").getJSONObject("headers"), JSONObject(headers))
            same(fixture.getJSONObject("get").getJSONObject("envelope"), JSONObject(query))
            assertNull(body)
        }
        val result = h.get()
        assertEquals("OK", result.getString("code"))
        assertFalse(result.getJSONObject("data").getBoolean("requiredUpgrade"))
        assertEquals(1, h.exchanges)
        assertEquals(1, h.sends)
    }

    @Test fun reportUsesCachedSecretNextSequenceAndAnEncryptedBody() {
        val h = Harness(); h.get()
        h.sent = { method, path, headers, query, body ->
            assertEquals("POST", method)
            assertEquals("/device/ota/upgrade", path)
            same(fixture.getJSONObject("post").getJSONObject("headers"), JSONObject(headers))
            same(fixture.getJSONObject("post").getJSONObject("envelope"), JSONObject(requireNotNull(body)))
            assertTrue(query.isEmpty())
        }
        h.client.request("POST", "/device/ota/upgrade", body = fixture.getString("report"))
        assertEquals(1, h.exchanges)
        assertEquals(2, h.sends)
    }

    @Test fun wrongServerSignatureCannotSendARequest() {
        val h = Harness()
        h.exchangeReply.getJSONObject("data").put("serverSignature", "00".repeat(32))
        assertEquals("ota_exchange_crypto", assertThrows(OttaiOtaCloudException::class.java) { h.get() }.diagnostic)
        assertEquals(0, h.sends)
    }

    @Test fun unauthenticatedExchangePayloadCannotSendARequest() {
        val h = Harness()
        h.exchangeReply.getJSONObject("data").put("authTag", "AA==")
        assertEquals("ota_exchange_crypto", assertThrows(OttaiOtaCloudException::class.java) { h.get() }.diagnostic)
        assertEquals(0, h.sends)
    }

    @Test fun changedResponseTagAndSecretIdentityAreRejected() {
        for (field in listOf("cipherMac", "cipherSecretId")) {
            val h = Harness()
            h.reply.put(field, "changed")
            assertEquals("ota_response_cipher", assertThrows(OttaiOtaCloudException::class.java) { h.get() }.diagnostic)
            assertEquals(1, h.sends)
        }
    }

    @Test fun plaintextSuccessCannotStandInForAuthenticatedData() {
        val h = Harness()
        h.reply = JSONObject("{\"code\":\"OK\",\"data\":{\"allowUpgrade\":true}}")
        assertEquals("ota_response_cipher", assertThrows(OttaiOtaCloudException::class.java) { h.get() }.diagnostic)
    }

    @Test fun serverBusinessCodeSurvivesWithoutExposingMessagesOrRetrying() {
        val h = Harness()
        h.status = 403
        h.reply = JSONObject().put("code", "NET_SECURITY_PLAINTEXT_ROUTE_FORBIDDEN")
            .put("msg", "private-token https://host/private-signed-url")
        val error = assertThrows(OttaiOtaCloudException::class.java) { h.get() }
        assertEquals("ota_request_NET_SECURITY_PLAINTEXT_ROUTE_FORBIDDEN", error.diagnostic)
        assertEquals(1, h.exchanges)
        assertEquals(1, h.sends)
    }

    @Test fun missingExchangeFieldsProduceABoundedDiagnostic() {
        val h = Harness(); h.exchangeReply = JSONObject("{\"code\":\"OK\"}")
        assertEquals("ota_exchange_response", assertThrows(OttaiOtaCloudException::class.java) { h.get() }.diagnostic)
        assertEquals(0, h.sends)
    }

    @Test fun closingTheClientForcesANewExchange() {
        val h = Harness(); h.get(); h.client.close(); h.get()
        assertEquals(2, h.exchanges)
        assertEquals(2, h.sends)
    }

    @Test fun expiredSecretCannotBeReusedWhenReplacementExchangeIsStale() {
        val h = Harness(); h.get()
        h.time = fixture.getLong("expires") + 1
        h.validateExchange = false
        assertEquals("ota_exchange_crypto", assertThrows(OttaiOtaCloudException::class.java) { h.get() }.diagnostic)
        assertEquals(2, h.exchanges)
        assertEquals(1, h.sends)
    }

    @Test fun exchangeCanCorrectClockOffsetOnceBeforeEncrypting() {
        var exchanges = 0
        val client = OttaiNetSecurity(fixture.getString("deviceId"), exchange = { request ->
            exchanges++
            if (exchanges == 1) {
                assertEquals(fixture.getLong("now") - 10_000, request.getLong("clientTimestamp"))
                OttaiNetSecurity.Reply(200, JSONObject().put("code", "CLIENT_CLOCK_OFFSET").put("currentTime", fixture.getLong("now")))
            } else {
                same(fixture.getJSONObject("exchangeRequest"), request)
                OttaiNetSecurity.Reply(200, fixture.getJSONObject("exchangeResponse"))
            }
        }, send = { _, _, headers, _, _ ->
            assertEquals(fixture.getLong("now").toString(), headers["cipherTimeNow"])
            OttaiNetSecurity.Reply(200, fixture.getJSONObject("response"))
        }, clock = { fixture.getLong("now") - 10_000 }, keyPair = ::fixedKey,
            randomBytes = { size -> ByteArray(size) { (16 + it).toByte() } })
        assertEquals("OK", client.request("GET", "/ota/upgradeVersion/judge", mapOf("deviceVersion" to "V1.7")).getString("code"))
        assertEquals(2, exchanges)
    }

    @Test fun cancellationStopsBeforeExchangeOrBeforeTheApplicationRequest() {
        for (duringExchange in listOf(false, true)) {
            val h = Harness()
            try {
                h.interruptAfterExchange = duringExchange
                if (!duringExchange) Thread.currentThread().interrupt()
                assertEquals("cancelled", assertThrows(OttaiOtaCloudException::class.java) { h.get() }.diagnostic)
                assertEquals(if (duringExchange) 1 else 0, h.exchanges)
                assertEquals(0, h.sends)
            } finally { Thread.interrupted(); h.client.close() }
        }
    }
}
