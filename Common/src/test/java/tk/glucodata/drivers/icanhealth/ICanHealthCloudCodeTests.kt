package tk.glucodata.drivers.icanhealth

import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.KeyFactory
import java.security.SecureRandom
import java.security.interfaces.RSAPublicKey
import java.security.spec.RSAPublicKeySpec
import java.util.Base64
import java.util.zip.GZIPOutputStream
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure tests for the vendor calibration-code retrieval: sign scheme, request URL, response
 * envelope crypto and the parameterCode JSON parser. Everything runs on synthetic keys; real
 * vendor credentials or sensor tables are never part of this suite.
 */
class ICanHealthCloudCodeTests {

    // Synthetic tenant values; real credentials are never committed or exercised in tests.
    private val appId = "100001"
    private val appSecret = "000102030405060708090a0b0c0d0e0f"
    private val deviceSn = "ABCDEFGH1234"
    private val timestampMs = "1700000000000"

    @Test
    fun signMatchesVendorScheme() {
        val sign = ICanHealthCloudCodeProtocol.sign(appId, appSecret, deviceSn, timestampMs)
        assertEquals("3BC9B99531E06B4CC3003D86781D6935", sign)
    }

    @Test
    fun buildRequestUrlCarriesParamsAndSign() {
        val creds = ICanCloudCreds(appId, appSecret, "https://iot.example.test/api/")
        val url = ICanHealthCloudCodeProtocol.buildRequestUrl(creds, deviceSn, timestampMs)
        assertTrue(url.startsWith("https://iot.example.test/api/sino-deviceaccess/sdk/cgm-code-encrypt?"))
        assertTrue(url.contains("appId=100001"))
        assertTrue(url.contains("deviceSn=ABCDEFGH1234"))
        assertTrue(url.contains("timestamp=1700000000000"))
        assertTrue(url.endsWith("sign=3BC9B99531E06B4CC3003D86781D6935"))
    }

    @Test
    fun buildRequestUrlAddsMissingTrailingSlash() {
        val creds = ICanCloudCreds(appId, appSecret, "https://iot.example.test/api")
        val url = ICanHealthCloudCodeProtocol.buildRequestUrl(creds, deviceSn, timestampMs)
        assertTrue(url.contains("/api/sino-deviceaccess/sdk/cgm-code-encrypt?"))
    }

    @Test
    fun decodeResponseRoundTripsType1Envelope() {
        val (spki, values) = rsaTestPair()
        val aesKey = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        val inner = """{"x1":"0.4","x2":"0.6"}"""
        val outer = """{"code":200,"data":{"code":"{\"x1\":\"0.4\",\"x2\":\"0.6\"}"}}"""
        val plaintext = padToBlock(outer.toByteArray())
        val v = aesCbcEncrypt(plaintext, aesKey.toByteArray(), aesKey.toByteArray().copyOf(16))
        val k = rsaWrapType1(aesKey.toByteArray(), values.first, values.second, gzip = false)
        assertEquals(inner, ICanHealthCloudCodeProtocol.decodeResponse(k, v, spki))
    }

    @Test
    fun decodeResponseSupportsGzipAndType2Envelope() {
        val (spki, values) = rsaTestPair()
        val aesKey = "abcdefghijklmnopqrstuvwxyz012345"
        val inner = """{"x1":"1.2"}"""
        val json = """{"code":200,"data":{"code":"{\"x1\":\"1.2\"}"}}"""
        val gzipped = gzip(padToBlock(json.toByteArray()))
        val v = aesCbcEncrypt(gzipped, aesKey.toByteArray(), aesKey.toByteArray().copyOf(16))
        val k = rsaWrapType2(aesKey.toByteArray(), values.first, values.second)
        assertEquals(inner, ICanHealthCloudCodeProtocol.decodeResponse(k, v, spki))
    }

    @Test
    fun decodeResponseHandlesVendorLeadingZeroCiphertext() {
        val (spki, values) = rsaTestPair()
        val aesKey = "abcdefghijklmnopqrstuvwxyz234567"
        val inner = "{}"
        val json = """{"code":200,"data":{"code":"{}"}}"""
        val v = aesCbcEncrypt(padToBlock(json.toByteArray()), aesKey.toByteArray(), aesKey.toByteArray().copyOf(16))
        val k = rsaWrapType1(aesKey.toByteArray(), values.first, values.second, gzip = false, leadingZero = true)
        assertEquals(inner, ICanHealthCloudCodeProtocol.decodeResponse(k, v, spki))
    }

    @Test
    fun decodeResponseRejectsUnexpectedKeyLength() {
        val (spki, values) = rsaTestPair()
        val k = rsaWrapType1(ByteArray(3), values.first, values.second, gzip = false)
        assertNull(ICanHealthCloudCodeProtocol.decodeResponse(k, "AAAA", spki))
    }

    @Test
    fun parseEnvelopeExtractsInnerCode() {
        val ok = """{"code":200,"data":{"code":"{\"x1\":1}"}}""".toByteArray() + "  ".toByteArray()
        assertEquals("""{"x1":1}""", ICanHealthCloudCodeProtocol.parseEnvelope(ok))
        assertNull(ICanHealthCloudCodeProtocol.parseEnvelope("""{"code":400}""".toByteArray()))
        assertNull(ICanHealthCloudCodeProtocol.parseEnvelope("""{"code":200}""".toByteArray()))
        assertNull(ICanHealthCloudCodeProtocol.parseEnvelope("not json".toByteArray()))
    }

    @Test
    fun parseCodeFloatsReadsX1ToX25() {
        val fields = (1..25).joinToString(",") { "\"x$it\":\"$it.5\"" }
        val floats = ICanHealthCloudCodeProtocol.parseCodeFloats("{$fields}")
        assertEquals(25, floats?.size)
        assertEquals(2.5f, floats?.get("x2") ?: 0f)
    }

    @Test
    fun parseCodeFloatsRejectsIncompleteJson() {
        assertNull(ICanHealthCloudCodeProtocol.parseCodeFloats("""{"x1":"1"}"""))
        assertNull(ICanHealthCloudCodeProtocol.parseCodeFloats("""{"x1":"NaN","x2":"1"}"""))
        assertNull(ICanHealthCloudCodeProtocol.parseCodeFloats("not json"))
    }

    @Test
    fun sensorInfoMarkerParsing() {
        val fresh = byteArrayOf(0x1C.toByte(), 0xF6.toByte(), 0x01, 0xFF.toByte(), 0xFF.toByte())
        assertEquals(0xFFFF, ICanHealthParser.parseSensorInfoMarker(fresh))
        assertTrue(ICanHealthParser.isSensorInfoMissingCalibration(fresh))

        val provisioned = byteArrayOf(0x1C.toByte(), 0xF6.toByte(), 0x01, 0x4E.toByte(), 0x02)
        assertEquals(0x024E, ICanHealthParser.parseSensorInfoMarker(provisioned))
        assertFalse(ICanHealthParser.isSensorInfoMissingCalibration(provisioned))

        assertNull(ICanHealthParser.parseSensorInfoMarker(fresh.copyOf(3)))
        assertNull(
            ICanHealthParser.parseSensorInfoMarker(
                byteArrayOf(0x1C.toByte(), 0xF7.toByte(), 0x01, 0xFF.toByte(), 0xFF.toByte()),
            ),
        )
    }

    @Test
    fun maskSnNeverExposesTheFullSerial() {
        assertTrue(ICanHealthCloudCode.maskSn("84F01FF00000A").startsWith("len=13, suffix="))
        assertEquals("<empty>", ICanHealthCloudCode.maskSn(""))
        assertEquals("<empty>", ICanHealthCloudCode.maskSn(null))
    }

    // --- vendor creds candidates -------------------------------------------

    @Test
    fun bundledVendorKeyPairIsPinned() {
        // The vendor SDK cloud pair is deliberately pinned: any change to it must be a
        // conscious edit (it is the same pair every iCan/Luch vendor build carries).
        assertEquals("610001", ICanCloudCredsLoader.BUNDLED_CLOUD_APP_ID)
        assertEquals("1395554be35742351fe165a69b50e0f8", ICanCloudCredsLoader.BUNDLED_CLOUD_APP_SECRET)
    }

    @Test
    fun credsJsonParsesBothSingleObjectAndArray() {
        val single = ICanCloudCredsLoader.parseCredsJson(
            """{"appId":"610001","appSecret":"s1","base":"https://iot.ru.sinocareintl.com/api/"}""",
        )
        assertEquals(1, single?.size)
        assertEquals("s1", single?.single()?.appSecret)
        assertEquals("https://iot.ru.sinocareintl.com/api/", single?.single()?.baseUrl)

        val pair = ICanCloudCredsLoader.parseCredsJson(
            """[
                 {"appId":"a1","appSecret":"s1","base":"https://first/api/"},
                 {"appId":"","appSecret":"dropped-entry"},
                 {"appId":"a2","appSecret":"s2"}
               ]""",
        )
        assertEquals(2, pair?.size)
        assertEquals(arrayOf("a1", "a2"), pair?.map { it.appId }?.toTypedArray())

        assertNull(ICanCloudCredsLoader.parseCredsJson("not json"))
        assertNull(ICanCloudCredsLoader.parseCredsJson("""{"appSecret":"no-appId"}"""))
    }

    @Test
    fun credsTryInOrderUsesFirstSuccessAndSkipsFailures() {
        val a = ICanCloudCreds("a", "sa", "")
        val b = ICanCloudCreds("b", "sb", "")
        val seen = mutableListOf<String>()
        @Suppress("UNCHECKED_CAST")
        val ok = ICanCloudCredsLoader.tryInOrder(listOf(a, b)) { creds ->
            seen.add(creds.appId)
            if (creds.appId == "b") "second" else null
        } as String?
        assertEquals("second", ok)
        assertEquals(listOf("a", "b"), seen)

        seen.clear()
        val none = ICanCloudCredsLoader.tryInOrder(listOf(a, b)) { creds ->
            seen.add(creds.appId)
            null
        }
        assertNull(none)
        assertEquals(listOf("a", "b"), seen)

        val first = ICanCloudCredsLoader.tryInOrder(listOf(a, b)) { creds ->
            seen.add(creds.appId)
            "first"
        }
        assertEquals("first", first)
    }

    // --- offline ladder: vendor default table + decision ---------------------

    @Test
    fun bundledDefaultCorrectCodeMatchesProbedFallback() {
        // The vendor cloud returns this exact table for SNs it does not know, stably,
        // byte-identical across repeat requests (2026-10-23 blind probes). Pinned so a
        // change to the offline fallback is a conscious edit, like the credentials pair.
        val default = ICanHealthCloudCode.defaultCorrectCode()
        listOf(
            "x1" to 0.015f,
            "x2" to 32.0f,
            "x3" to 25.0f,
            "x4" to 1.0f,
            "x5" to 0.8f,
            "x6" to 0.0f,
            "x7" to 40.0f,
            "x8" to 0.3f,
            "x9" to 1.0f,
            "x10" to 1.2f,
            "x11" to 13.0f,
            "x12" to 1.5f,
            "x13" to 0.4f,
            "x14" to 3.5f,
            "x15" to 15.0f,
        ).forEach { (key, expected) -> assertEquals(expected, default[key]) }
        for (index in 16..25) {
            assertTrue(default.containsKey("x$index"))
        }
    }

    @Test
    fun bundledDefaultReferenceCannotProduceProvisioningChunks() {
        // Reference parsing remains possible, but the write API rejects this server fallback.
        val values = ICanHealthCorrectCode.valuesFromCorrectCode(
            ICanHealthCloudCode.defaultCorrectCode(),
        )
        assertEquals(15, values?.size)
        assertEquals(0, values?.get(5))

        val chunks = ICanHealthCorrectCode.buildCorrectCodeChunks(
            ICanHealthCloudCode.defaultCorrectCode(),
            byteArrayOf(0, 0, 0, 0),
            "ABC0000004000",
        )
        assertNull(chunks)
    }

    @Test
    fun offlineLadderPrefersCacheWaitsForFetchThenDefaults() {
        val any1 = mapOf("x1" to 1.0f)
        val any2 = mapOf("x1" to 2.0f)

        assertEquals(
            ICanCloudOfflineLadder.Source.USE_CACHE,
            ICanCloudOfflineLadder.decide(cachedTable = any1, fetchInFlight = false, defaultTable = any2).source,
        )
        assertEquals(any1, ICanCloudOfflineLadder.decide(cachedTable = any1, fetchInFlight = false, defaultTable = any2).table)

        assertEquals(
            ICanCloudOfflineLadder.Source.WAIT_FETCH,
            ICanCloudOfflineLadder.decide(cachedTable = null, fetchInFlight = true, defaultTable = any2).source,
        )

        val withDefault = ICanCloudOfflineLadder.decide(cachedTable = null, fetchInFlight = false, defaultTable = any2)
        assertEquals(ICanCloudOfflineLadder.Source.USE_DEFAULT, withDefault.source)
        assertEquals(any2, withDefault.table)
    }

    // --- manual table file fallback -----------------------------------------

    @Test
    fun tableFileParsesRawJsonEnvelopeAndRejectsIncomplete() {
        val raw = (1..25).joinToString(",", prefix = "{", postfix = "}") { index ->
            "\"x$index\":\"$index.5\""
        }
        System.err.println("RAW=[$raw]")
        assertEquals(raw, ICanHealthCloudCode.parseTableFileContent(raw))

        val envelope = "{\"code\":200,\"success\":true,\"data\":{\"code\":\"" + raw.replace("\"", "\\\"") + "\"}}"
        assertEquals(raw, ICanHealthCloudCode.parseTableFileContent(envelope))

        val missingX15 = raw.replace(""""x15":"15.5",""", "")
        assertNull(ICanHealthCloudCode.parseTableFileContent(missingX15))
        assertNull(ICanHealthCloudCode.parseTableFileContent("not json"))
        assertNull(ICanHealthCloudCode.parseTableFileContent(""))
        assertNull(ICanHealthCloudCode.parseTableFileContent("""{"code":400,"data":{}}"""))
    }

    @Test
    fun tableFileNameUsesShortBoxSerial() {
        assertEquals("ican-cgm-code-84F01FF00000A.json", ICanHealthCloudCode.tableFileName("84F01FF00000A"))
    }

    // --- helpers ------------------------------------------------------------

    /**
     * Deterministic synthetic RSA pair. Any prime modulus with d = e^-1 mod (n-1) works for a
     * public-op round trip (m^(ed) = m by Fermat), so a test-only prime stands in for the
     * vendor key.
     */
    private fun rsaTestPair(): Pair<String, Pair<BigInteger, BigInteger>> {
        val random = SecureRandom(byteArrayOf(0x11, 0x22, 0x33))
        val e = BigInteger.valueOf(65537)
        for (attempt in 0 until 128) {
            val modulus = BigInteger.probablePrime(1024, random)
            if (e.gcd(modulus - BigInteger.ONE) != BigInteger.ONE) continue
            val d = e.modInverse(modulus - BigInteger.ONE)
            val spec = RSAPublicKeySpec(modulus, e)
            val public = KeyFactory.getInstance("RSA").generatePublic(spec) as RSAPublicKey
            check(public.modulus == modulus)
            return Base64.getEncoder().encodeToString(public.encoded) to (modulus to d)
        }
        error("no synthetic RSA pair")
    }

    /** Private-op encrypt (the role the vendor server plays), PKCS#1 type-1 padded block. */
    private fun rsaWrapType1(
        data: ByteArray,
        modulus: BigInteger,
        d: BigInteger,
        gzip: Boolean,
        leadingZero: Boolean = false,
    ): String {
        check(data.size <= 125) { "type-1 payload too large" }
        val block = ByteArray(128)
        block[0] = 0x00
        block[1] = 0x01
        for (index in 2..(block.size - data.size - 2)) block[index] = 0xFF.toByte()
        block[block.size - data.size - 1] = 0x00
        System.arraycopy(data, 0, block, block.size - data.size, data.size)
        return rsaPrivateOp(block, modulus, d, leadingZero)
    }

    /** Private-op encrypt, PKCS#1 type-2 padded block. */
    private fun rsaWrapType2(data: ByteArray, modulus: BigInteger, d: BigInteger): String {
        check(data.size <= 125) { "type-2 payload too large" }
        val block = ByteArray(128)
        block[0] = 0x00
        block[1] = 0x02
        for (index in 2 until block.size - data.size - 1) {
            var value = 0
            while (value == 0) value = ICanHealthCloudCodeTestSupport.randomByte()
            block[index] = value.toByte()
        }
        block[block.size - data.size - 1] = 0x00
        System.arraycopy(data, 0, block, block.size - data.size, data.size)
        return rsaPrivateOp(block, modulus, d, leadingZero = false)
    }

    private fun rsaPrivateOp(block: ByteArray, modulus: BigInteger, d: BigInteger, leadingZero: Boolean): String {
        val m = BigInteger(1, block).modPow(d, modulus)
        val raw = m.toByteArray() // two's complement, often with a sign 0x00 in front
        val bytes = if (leadingZero) byteArrayOf(0x00) + raw else raw
        return Base64.getEncoder().withoutPadding().encodeToString(bytes)
    }

    companion object {
        private fun padToBlock(bytes: ByteArray): ByteArray {
            val paddedLength = ((bytes.size + 15) / 16) * 16
            val padded = bytes.copyOf(paddedLength)
            for (index in bytes.size until paddedLength) padded[index] = 0x20
            return padded
        }

        private fun aesCbcEncrypt(plaintext: ByteArray, key: ByteArray, iv: ByteArray): String {
            val cipher = Cipher.getInstance("AES/CBC/NoPadding")
            // NoPadding takes exactly full blocks; the server zero-pads the final partial block
            // (the gzip trailer makes lingering padding bytes harmless on the decode side).
            val padded = if (plaintext.size % 16 == 0) {
                plaintext
            } else {
                val paddedLength = ((plaintext.size + 15) / 16) * 16
                ByteArray(paddedLength).also { System.arraycopy(plaintext, 0, it, 0, plaintext.size) }
            }
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            return Base64.getEncoder().withoutPadding().encodeToString(cipher.doFinal(padded))
        }

        private fun gzip(bytes: ByteArray): ByteArray {
            val sink = ByteArrayOutputStream()
            GZIPOutputStream(sink).use { stream ->
                stream.write(bytes)
                stream.close()
            }
            return sink.toByteArray()
        }
    }
}

/** Deterministic, dependency-free byte source for PKCS#1 type-2 padding in tests. */
internal object ICanHealthCloudCodeTestSupport {
    private var state = 0x5A5A5A5A
    fun randomByte(): Int {
        state = state * 1103515245 + 12345
        return (state ushr 16) and 0xFF
    }
}
