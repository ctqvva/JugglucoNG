package tk.glucodata.drivers.aidex

import java.security.KeyFactory
import java.security.interfaces.RSAPublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiDexCnProtocolTest {
    private val responsePublicKey =
        "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA5f07QXf86Sw/F6ITRONw+N7Yrwk9XfKaAapV+2Y2XtM/jCT2PYpXSaDM4lPBDsS9eIgqXIz2AgVtNd1yShLjEWRegqPXNtE2QMXxCU6rSl6Gf8Jr2twjtTPLKILx8oAzDq6lXZK0QyHzjZKjxWXCypIa0GkFLIa0xTe5YxIHg+BRyvxXh0XGsKu6GWd3pIxCk6BTb3F5pHqiNSHtibULudmvTV6VrRqhUKDXRwCLEBikJUN2SEBKfNVw4qdQ+Q7zFjyavHXyJZsdLaIv/K2dJrDUZDxwRI91rSQsVu8wS8DoBn9noQ8M/bDSAKFiudIscHz0bY5/Mrsg3erY/dcIBQIDAQAB"

    @Test
    fun mainlandPhoneNormalizationMatchesLoginForm() {
        assertEquals("13800138000", AiDexCnCloudClient.normalizeCnPhone("13800138000"))
        assertEquals("13800138000", AiDexCnCloudClient.normalizeCnPhone("+86 13800138000"))
        assertEquals("13800138000", AiDexCnCloudClient.normalizeCnPhone("0086 13800138000"))
        assertNull(AiDexCnCloudClient.normalizeCnPhone("12800138000"))
        assertNull(AiDexCnCloudClient.normalizeCnPhone("1380013800"))
    }

    @Test
    fun passwordHashMatchesOfficialLowercaseMd5() {
        assertEquals("5f4dcc3b5aa765d61d8327deb882cf99", AiDexCnProtocol.md5Hex("password"))
    }

    @Test
    fun snConfigIntegritySignatureUsesSortedVendorContract() {
        assertEquals(
            "09C701F33BC95896881CDFA9CDBCADAB",
            AiDexCnProtocol.integritySign(
                deviceSn = "22222FZXKT",
                randomStr = "0011223344556677",
                timestamp = "1788210000000",
            ),
        )
        val body = AiDexCnProtocol.signedSnConfigBody(
            deviceSn = "22222FZXKT",
            randomStr = "0011223344556677",
            timestamp = "1788210000000",
        )
        assertEquals("22222FZXKT", body.getJSONObject("body").getString("deviceSn"))
        assertEquals(
            "09C701F33BC95896881CDFA9CDBCADAB",
            body.getJSONObject("integrityData").getString("sign"),
        )
    }

    @Test
    fun materialDecoderAcceptsOnlyCompleteBase64OrHexKeys() {
        val expected = ByteArray(16) { it.toByte() }
        assertArrayEquals(
            expected,
            AiDexCnProtocol.decodeMaterial(Base64.getEncoder().encodeToString(expected)),
        )
        assertArrayEquals(
            expected,
            AiDexCnProtocol.decodeMaterial("000102030405060708090a0b0c0d0e0f"),
        )
        assertNull(AiDexCnProtocol.decodeMaterial(Base64.getEncoder().encodeToString(ByteArray(15))))
        assertNull(AiDexCnProtocol.decodeMaterial("not material"))
    }

    @Test
    fun requestEnvelopeIsRsaEncryptedAndRandomized() {
        val plain = "{\"phone\":\"13800138000\"}"
        val first = JSONObject(AiDexCnProtocol.encryptEnvelope(plain)).getString("encryptData")
        val second = JSONObject(AiDexCnProtocol.encryptEnvelope(plain)).getString("encryptData")
        val ciphertext = Base64.getDecoder().decode(first)

        assertTrue(ciphertext.isNotEmpty())
        assertEquals(0, ciphertext.size % 256)
        assertNotEquals(first, second)
    }

    @Test
    fun unencryptedErrorResponseRemainsReadable() {
        val response = "{\"code\":800,\"msg\":\"expired\"}"
        assertEquals(response, AiDexCnProtocol.decryptEnvelope(response))
    }

    @Test
    fun encryptedVendorResponseIsDecrypted() {
        val plain = "{\"code\":200,\"data\":{\"publicKey\":\"test\"}}"
        val key = KeyFactory.getInstance("RSA").generatePublic(
            X509EncodedKeySpec(Base64.getDecoder().decode(responsePublicKey)),
        ) as RSAPublicKey
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val encrypted = Base64.getEncoder().encodeToString(cipher.doFinal(plain.toByteArray()))
        val envelope = JSONObject().put("encryptData", encrypted).toString()

        assertEquals(plain, AiDexCnProtocol.decryptEnvelope(envelope))
    }
}
