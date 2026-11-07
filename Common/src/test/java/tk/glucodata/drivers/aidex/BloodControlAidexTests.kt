package tk.glucodata.drivers.aidex

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import tk.glucodata.drivers.aidex.bloodcontrol.*
import java.io.File
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.MGF1ParameterSpec
import java.security.spec.PSSParameterSpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Synthetic credentials only. No request in this suite reaches the network. */
class BloodControlAidexTests {
    private val serial = "BCTEST0001"
    private val time = 1_799_999_999_123L
    private val base64 = Base64.getEncoder()
    private val decoder = Base64.getDecoder()
    private val credentials = BloodControlCredentials("synthetic-token", ByteArray(32) { it.toByte() },
        pair.private, pair.public)

    private class Cache : BloodControlCache {
        val values = mutableMapOf<String, Any>()
        override fun string(key: String) = values[key] as? String
        override fun long(key: String) = values[key] as? Long ?: 0L
        override fun put(key: String, value: String) { values[key] = value }
        override fun put(key: String, value: Long) { values[key] = value }
    }

    private fun response(status: Int, body: String): BloodControlResponse {
        val bytes = body.toByteArray()
        val signature = Signature.getInstance("RSASSA-PSS").apply {
            setParameter(PSSParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, 32, 1))
            initSign(pair.private); update(bytes)
        }.sign()
        return BloodControlResponse(status, bytes, base64.encodeToString(signature))
    }

    private fun keys(serial: String = this.serial) = JSONObject().put("serialNumber", serial)
        .put("publicKey", "000102030405060708090a0b0c0d0e0f")
        .put("communicationKey", base64.encodeToString(ByteArray(16) { (it + 16).toByte() })).toString()

    @Test fun envelopeSignsAndEncryptsExactlyTheSamePlaintext() {
        val stamp = BloodControlProtocol.timestamp(time)
        assertEquals("2027-01-15T07:59:59Z", stamp)
        val plain = BloodControlProtocol.plaintext("device", stamp, serial)
        val wrapper = JSONObject(String(BloodControlProtocol.wrap(plain, stamp, credentials)))
        assertEquals(stamp, wrapper.getString("timestampUtc"))
        assertEquals("android-client", wrapper.getString("clientId"))
        assertTrue(wrapper.getString("nonce").matches(Regex("[a-f0-9]{32}")))
        assertEquals(36, wrapper.getString("requestId").length)
        assertTrue(BloodControlProtocol.verify(plain, wrapper.getString("signatureBase64"), pair.public))
        val envelope = JSONObject(String(decoder.decode(wrapper.getString("envelopeBase64"))))
        val iv = decoder.decode(envelope.getString("ivBase64"))
        val tag = decoder.decode(envelope.getString("tagBase64"))
        assertEquals(12, iv.size); assertEquals(16, tag.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(credentials.aes, "AES"), GCMParameterSpec(128, iv))
        }
        assertArrayEquals(plain, cipher.doFinal(decoder.decode(envelope.getString("ciphertextBase64")) + tag))
        val data = JSONObject(String(plain))
        assertEquals(serial, data.getString("serialNumber"))
        assertEquals("device", data.getString("deviceId"))
        assertEquals(stamp, data.getString("requestedAt"))
        assertFalse(data.has("deviceID"))
        val allow = JSONObject(String(BloodControlProtocol.plaintext("device", stamp)))
        assertEquals("device", allow.getString("deviceID"))
        assertFalse(allow.has("deviceId"))
        assertNotEquals(String(BloodControlProtocol.wrap(plain, stamp, credentials)), wrapper.toString())
    }

    @Test fun signatureRejectsMissingHeaderOrChangedRawBytes() {
        val r = response(200, "{\"ok\":true}")
        assertTrue(BloodControlProtocol.verify(r.body, r.signature, pair.public))
        assertFalse(BloodControlProtocol.verify(r.body + 32.toByte(), r.signature, pair.public))
        assertFalse(BloodControlProtocol.verify(r.body, null, pair.public))
        assertFalse(BloodControlProtocol.verify(r.body, "invalid", pair.public))
    }

    @Test fun parserRejectsWrongSensorMissingAndWrongLengthKeys() {
        val parsed = BloodControlProtocol.parseKeys(JSONObject(keys()), serial)!!
        assertArrayEquals(ByteArray(16) { it.toByte() }, parsed.secret)
        assertArrayEquals(ByteArray(16) { (it + 16).toByte() }, parsed.iv)
        assertNull(BloodControlProtocol.parseKeys(JSONObject(keys("BCTEST0002")), serial))
        assertNull(BloodControlProtocol.parseKeys(JSONObject(keys()).put("serialNumber", ""), serial))
        assertNull(BloodControlProtocol.parseKeys(JSONObject(keys()).put("publicKey", base64.encodeToString(ByteArray(15))), serial))
        assertNull(BloodControlProtocol.parseKeys(JSONObject(keys()).apply { remove("communicationKey") }, serial))
        assertNull(BloodControlProtocol.parseKeys(JSONObject(keys()).apply { remove("serialNumber") }, serial))
    }

    @Test fun allowlistNormalizesAndCachesWithoutRequestingOtherSensors() {
        val paths = mutableListOf<String>()
        val cache = Cache()
        val provider = BloodControlAidexKeyProvider(credentials, BloodControlTransport { path, _ ->
            paths += path
            response(200, if (path == BloodControlAidexKeyProvider.ALLOWLIST)
                "{\"allowlistAidex\":[\"aidex:bc-test 0001\",\"1)\",\"16ШТ.\"]}" else keys())
        }, cache, { time })
        assertNotNull(provider.request("X-$serial").keys)
        assertEquals(listOf(BloodControlAidexKeyProvider.ALLOWLIST, BloodControlAidexKeyProvider.KEYS), paths)
        assertEquals("[\"BCTEST0001\"]", cache.string("allowlist"))
        paths.clear()
        assertNotNull(provider.request(serial).keys)
        assertEquals(listOf(BloodControlAidexKeyProvider.KEYS), paths)
        paths.clear()
        assertNull(provider.request("BCTEST0002").keys)
        assertTrue(paths.isEmpty())
    }

    @Test fun unsignedInvalidJsonDisablesSessionBeforeParsing() {
        var calls = 0
        val provider = BloodControlAidexKeyProvider(credentials, BloodControlTransport { _, _ ->
            calls++; BloodControlResponse(200, "not json".toByteArray(), null)
        }, Cache(), { time })
        assertNull(provider.request(serial).keys)
        assertNull(provider.request(serial).keys)
        assertEquals(1, calls)
    }

    @Test fun authenticationFailuresDisableEvenWithUnsignedBody() {
        for (status in listOf(401, 403)) {
            var calls = 0
            val provider = BloodControlAidexKeyProvider(credentials, BloodControlTransport { _, _ ->
                calls++; BloodControlResponse(status, byteArrayOf(), null)
            }, Cache(), { time })
            assertNull(provider.request(serial).keys)
            assertNull(provider.request(serial).keys)
            assertEquals(1, calls)
        }
    }

    @Test fun unavailableKeysAreNegativeCachedForAnHour() {
        var clock = time
        var keyCalls = 0
        val provider = BloodControlAidexKeyProvider(credentials, BloodControlTransport { path, _ ->
            if (path == BloodControlAidexKeyProvider.ALLOWLIST) response(200, "{\"allowlistAidex\":[\"$serial\"]}")
            else { keyCalls++; response(400, "{\"detail\":\"AIDEX_KEYS_NOT_AVAILABLE\"}") }
        }, Cache(), { clock })
        assertNull(provider.request(serial).keys)
        clock += 59 * 60_000
        assertNull(provider.request(serial).keys)
        assertEquals(1, keyCalls)
        clock += 60_001
        assertNull(provider.request(serial).keys)
        assertEquals(2, keyCalls)
    }

    @Test fun rateLimitAndServerFailuresUseIncreasingBackoff() {
        for (status in listOf(429, 500)) {
            var clock = time
            var calls = 0
            val cache = Cache()
            val provider = BloodControlAidexKeyProvider(credentials, BloodControlTransport { _, _ ->
                calls++; response(status, "{}")
            }, cache, { clock })
            provider.request(serial); provider.request(serial)
            assertEquals(1, calls)
            assertEquals(time + 15 * 60_000, cache.long("retry_global"))
            clock += 15 * 60_000
            provider.request(serial)
            assertEquals(2, calls)
            assertEquals(clock + 60 * 60_000, cache.long("retry_global"))
        }
    }

    @Test fun failedAllowlistWithoutCacheNeverRequestsKeys() {
        val paths = mutableListOf<String>()
        val provider = BloodControlAidexKeyProvider(credentials, BloodControlTransport { path, _ ->
            paths += path; throw ConnectException()
        }, Cache(), { time })
        assertNull(provider.request(serial).keys)
        assertEquals(listOf(BloodControlAidexKeyProvider.ALLOWLIST), paths)
    }

    @Test fun staleAllowlistIsUsedWhenRefreshCannotConnect() {
        val cache = Cache().apply {
            put("allowlist", "[\"$serial\"]")
            put("allowlist_at", time - 6 * 60_000)
        }
        val paths = mutableListOf<String>()
        val provider = BloodControlAidexKeyProvider(credentials, BloodControlTransport { path, _ ->
            paths += path
            if (path == BloodControlAidexKeyProvider.ALLOWLIST) throw ConnectException()
            response(200, keys())
        }, cache, { time })
        assertNotNull(provider.request(serial).keys)
        assertEquals(listOf(BloodControlAidexKeyProvider.ALLOWLIST, BloodControlAidexKeyProvider.KEYS), paths)
    }

    @Test fun serialNormalizationRejectsInvalidInputs() {
        assertEquals("22222FXYT3", BloodControlProtocol.normalizeSerial("F-22222FXYT3"))
        for (s in listOf(".", "(21)", "16ШТ.", "A", "ABCDEFG", "ABCDEFGHIJKLMNO", "ABC 123")) {
            assertNull(BloodControlProtocol.normalizeSerial(s))
        }
    }

    @Test fun transportFallsBackOnlyForConnectAndTlsAndRemembersTheRoute() {
        var clock = time
        val routes = mutableListOf<String>()
        val ca = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .map { File(it, "Common/src/main/res/raw/bloodcontrol_ca.pem") }.first(File::isFile).readBytes()
        val transport = BloodControlHttpsTransport(ca, "synthetic", { clock }) { route, _, _, _, _ ->
            routes += route
            if (route.endsWith("5.23.53.98")) throw ConnectException()
            response(200, "{}")
        }
        transport.post("/test", byteArrayOf())
        assertEquals(listOf("https://5.23.53.98", "https://178.253.22.116"), routes)
        routes.clear(); transport.post("/test", byteArrayOf())
        assertEquals(listOf("https://178.253.22.116"), routes)
        routes.clear(); clock += 15 * 60_000; transport.post("/test", byteArrayOf())
        assertEquals(2, routes.size)
        assertFalse(BloodControlHttpsTransport.canFallback(SocketTimeoutException("Read timed out")))
        assertTrue(BloodControlHttpsTransport.canFallback(SocketTimeoutException("connect timed out")))
        val readRoutes = mutableListOf<String>()
        val readTimeout = BloodControlHttpsTransport(ca, "synthetic", { clock }) { route, _, _, _, _ ->
            readRoutes += route; throw SocketTimeoutException("Read timed out")
        }
        try { readTimeout.post("/test", byteArrayOf()); fail() } catch (_: SocketTimeoutException) { }
        assertEquals(1, readRoutes.size)
    }

    companion object {
        private val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    }
}
