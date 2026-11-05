package tk.glucodata.share.lumiflex

import java.security.KeyPairGenerator
import java.util.Base64
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LumiflexCloudCryptoTests {

    private fun testKeyPair(): Pair<String, String> {
        val generator = KeyPairGenerator.getInstance("RSA")
        generator.initialize(1024)
        val pair = generator.generateKeyPair()
        val publicKey = Base64.getEncoder().encodeToString(pair.public.encoded)
        val privateKey = Base64.getEncoder().encodeToString(pair.private.encoded)
        return publicKey to privateKey
    }

    @Test
    fun envelopeRoundTripsSmallPayload() {
        val (publicKey, privateKey) = testKeyPair()
        val plain = """{"userName":"a@b.c","password":"pw"}"""
        val envelope = LumiflexCloudCrypto.encryptEnvelope(plain, publicKey)
        val encrypted = JSONObject(envelope).getString("encryptData")
        assertTrue(encrypted.isNotEmpty())
        assertEquals(plain, LumiflexCloudCrypto.decryptEnvelope(envelope, privateKey))
    }

    @Test
    fun envelopeChunksPayloadLargerThanOneBlock() {
        val (publicKey, privateKey) = testKeyPair()
        val plain = JSONObject().put("blob", "x".repeat(2000)).toString()
        assertEquals(
            plain,
            LumiflexCloudCrypto.decryptEnvelope(
                LumiflexCloudCrypto.encryptEnvelope(plain, publicKey),
                privateKey,
            ),
        )
    }

    @Test
    fun decryptReturnsNullForNonEnvelopeBody() {
        assertNull(LumiflexCloudCrypto.decryptEnvelope("""{"code":200,"data":{}}"""))
    }

    @Test
    fun vendorKeysParseAndProduceEnvelope() {
        val envelope = LumiflexCloudCrypto.encryptEnvelope("""{"a":1}""")
        assertTrue(JSONObject(envelope).getString("encryptData").isNotEmpty())
    }

    @Test
    fun versionCheckJsonCarriesVersionTimestampAndUser() {
        val json = JSONObject(LumiflexCloudCrypto.versionCheckJson("user-1", 1_700_000_000_000L))
        assertEquals("2.4.0", json.getString("version"))
        assertEquals(1_700_000_000_000L, json.getLong("timestamp"))
        assertEquals("user-1", json.getString("userId"))
    }
}
