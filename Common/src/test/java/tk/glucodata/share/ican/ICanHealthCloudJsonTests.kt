package tk.glucodata.share.ican

import java.security.KeyPairGenerator
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ICanHealthCloudJsonTests {

    private val aesKey = "0123456789abcdefghijklmnopqrstuv"
    private val otherAesKey = "0123456789ABCDEFGHIJKLMNOPQRSTUV"

    @Test
    fun aesRoundTripReturnsThePlaintext() {
        val plaintext = """{"mgdl":123,"time":1700000000000,"note":"ünïcødé"}"""
        val ciphertext = ICanHealthCloudJson.aesEncrypt(aesKey, plaintext)
        assertEquals(plaintext, ICanHealthCloudJson.aesDecrypt(aesKey, ciphertext))
    }

    @Test
    fun aesEncryptUsesTheFirstSixteenKeyBytesAsTheIv() {
        val plaintext = "ican-health"
        val keyBytes = aesKey.toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(keyBytes, "AES"),
            IvParameterSpec(keyBytes.copyOfRange(0, 16)),
        )
        val expected = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8)))
        assertEquals(expected, ICanHealthCloudJson.aesEncrypt(aesKey, plaintext))
    }

    @Test
    fun aesCiphertextIsBase64UrlWithoutPadding() {
        val ciphertext = ICanHealthCloudJson.aesEncrypt(aesKey, """{"mgdl":123}""")
        assertFalse(ciphertext.contains('='))
        assertTrue(ciphertext.all { it.isLetterOrDigit() || it == '-' || it == '_' })
        assertEquals(0, ICanHealthCloudJson.base64UrlDecode(ciphertext).size % 16)
    }

    @Test
    fun aesDecryptWithTheWrongKeyFailsInsteadOfReturningGarbage() {
        val ciphertext = ICanHealthCloudJson.aesEncrypt(aesKey, """{"mgdl":123}""")
        assertThrows(Exception::class.java) {
            ICanHealthCloudJson.aesDecrypt(otherAesKey, ciphertext)
        }
    }

    @Test
    fun base64UrlEncodeHasNoPaddingAndUsesTheUrlAlphabet() {
        val encoded = ICanHealthCloudJson.base64UrlEncode(byteArrayOf(-1, -2, -3, 1, 2))
        assertFalse(encoded.contains('='))
        assertTrue(encoded.all { it.isLetterOrDigit() || it == '-' || it == '_' })
    }

    @Test
    fun base64UrlDecodeRoundTripsAndAcceptsPaddedInput() {
        val bytes = ByteArray(64) { it.toByte() }
        val encoded = ICanHealthCloudJson.base64UrlEncode(bytes)
        assertArrayEquals(bytes, ICanHealthCloudJson.base64UrlDecode(encoded))
        assertArrayEquals(bytes, ICanHealthCloudJson.base64UrlDecode(encoded + "=="))
    }

    @Test
    fun base64UrlDecodeRejectsGarbage() {
        assertThrows(IllegalArgumentException::class.java) {
            ICanHealthCloudJson.base64UrlDecode("!!!!")
        }
    }

    @Test
    fun getRandomStringIsThirtyTwoCharactersFromTheVendorAlphabet() {
        val value = ICanHealthCloudJson.getRandomString()
        assertEquals(32, value.length)
        assertTrue(value.all { ICanHealthCloudConstants.GET_RANDOM_STRING_ALPHABET.contains(it) })
    }

    @Test
    fun getRandomStringChangesBetweenCalls() {
        assertNotEquals(ICanHealthCloudJson.getRandomString(), ICanHealthCloudJson.getRandomString())
    }

    @Test
    fun rsaEncryptProducesOneOaepBlockForTheVendorKey() {
        val encrypted = ICanHealthCloudJson.rsaEncrypt(aesKey)
        assertEquals(256, ICanHealthCloudJson.base64UrlDecode(encrypted).size)
        assertFalse(encrypted.contains('='))
        assertTrue(encrypted.all { it.isLetterOrDigit() || it == '-' || it == '_' })
        assertNotEquals(aesKey, encrypted)
        assertNotEquals(encrypted, ICanHealthCloudJson.rsaEncrypt(aesKey))
    }

    @Test
    fun rsaEncryptRoundTripsThroughTheMatchingPrivateKey() {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val pem = "-----BEGIN PUBLIC KEY-----\n" +
            Base64.getMimeEncoder().encodeToString(pair.public.encoded) +
            "\n-----END PUBLIC KEY-----"
        val encrypted = ICanHealthCloudJson.rsaEncrypt(aesKey, pem)
        val cipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-1AndMGF1Padding")
        cipher.init(Cipher.DECRYPT_MODE, pair.private)
        val recovered = String(
            cipher.doFinal(ICanHealthCloudJson.base64UrlDecode(encrypted)),
            Charsets.UTF_8,
        )
        assertEquals(aesKey, recovered)
    }

    @Test
    fun createEncryptedRequestCarriesTheEncryptedPayloadAndTheWrappedKey() {
        val payload = mapOf("mgdl" to 123, "time" to 1700000000000L, "note" to null)
        val envelope = ICanHealthCloudJson.createEncryptedRequest(payload, aesKey)
        assertEquals(setOf("data", "key"), envelope.keys)
        assertEquals(256, ICanHealthCloudJson.base64UrlDecode(envelope.getValue("key")).size)
        assertNotEquals(aesKey, envelope.getValue("key"))
        val decoded = JSONObject(ICanHealthCloudJson.aesDecrypt(aesKey, envelope.getValue("data")))
        assertEquals(123, decoded.getInt("mgdl"))
        assertEquals(1700000000000L, decoded.getLong("time"))
        assertTrue(decoded.isNull("note"))
    }

    @Test
    fun createEncryptedRequestUsesAFreshKeyPerCall() {
        val first = ICanHealthCloudJson.createEncryptedRequest(mapOf("mgdl" to 123))
        val second = ICanHealthCloudJson.createEncryptedRequest(mapOf("mgdl" to 123))
        assertNotEquals(first.getValue("key"), second.getValue("key"))
        assertNotEquals(first.getValue("data"), second.getValue("data"))
    }

    @Test
    fun authBodyCarriesTheVendorLoginFields() {
        val root = JSONObject(ICanHealthCloudJson.authBody("someone@example.com", "secret"))
        assertEquals("password", root.getString("grant_type"))
        assertEquals("all", root.getString("scope"))
        assertEquals("RUS", root.getString("country"))
        assertEquals("someone@example.com", root.getString("phone_num"))
        assertEquals("secret", root.getString("password"))
    }

    @Test
    fun parseJsonStringHandlesQuotedUnquotedAndNull() {
        assertEquals("abc", ICanHealthCloudJson.parseJsonString("\"abc\""))
        assertEquals("abc", ICanHealthCloudJson.parseJsonString(" \"abc\" "))
        assertEquals("abc", ICanHealthCloudJson.parseJsonString("abc"))
        assertEquals("", ICanHealthCloudJson.parseJsonString(""))
        assertEquals("", ICanHealthCloudJson.parseJsonString("   "))
        assertEquals("", ICanHealthCloudJson.parseJsonString("null"))
        assertEquals("", ICanHealthCloudJson.parseJsonString("NULL"))
        assertEquals("", ICanHealthCloudJson.parseJsonString(" null "))
        assertEquals("", ICanHealthCloudJson.parseJsonString("\"null\""))
    }
}
