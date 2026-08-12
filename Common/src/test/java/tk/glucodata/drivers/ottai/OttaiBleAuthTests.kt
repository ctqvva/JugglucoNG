package tk.glucodata.drivers.ottai

import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Offline tests for Ottai BLE Auth V2 crypto. Proves the mechanics (ECDH
 * symmetry, session-key char-pick, signature construction, time3 reorder) are
 * self-consistent. Validation against a real device is Phase 3.
 */
class OttaiBleAuthTests {

    @Test
    fun sessionKeyPick_followsPos2Then1or3Pattern() {
        // sharedHex[p] = hexdigit(p % 16). Picks at positions 2,3,6,7,10,11,...
        // -> digits 2,3,6,7,a,b,e,f repeating.
        val sharedHex = (0 until 64).joinToString("") { (it % 16).toString(16) }
        val key = OttaiBleAuth.sessionKeyPick(sharedHex)
        assertNotNull(key)
        assertEquals("2367abef2367abef2367abef2367abef", key)
        assertTrue(key!!.length >= 32)
    }

    @Test
    fun sessionKeyPick_tooShortReturnsNull() {
        assertEquals(null, OttaiBleAuth.sessionKeyPick("00"))
    }

    @Test
    fun ecdh_isSymmetric_andYieldsStableSessionKey() {
        val a = OttaiBleAuth.generateKeyPair()
        val b = OttaiBleAuth.generateKeyPair()
        val aPub = a.public as ECPublicKey
        val bPub = b.public as ECPublicKey

        val keyFromA = OttaiBleAuth.deriveSessionKey(bPub.w.affineX, bPub.w.affineY, a.private as ECPrivateKey)
        val keyFromB = OttaiBleAuth.deriveSessionKey(aPub.w.affineX, aPub.w.affineY, b.private as ECPrivateKey)

        assertNotNull(keyFromA)
        assertNotNull(keyFromB)
        assertEquals(keyFromA, keyFromB) // ECDH shared X identical both directions
        assertTrue(keyFromA!!.length >= 32)
    }

    @Test
    fun sharedSecretWithLeadingZero_keepsFullLengthKey() {
        // Shared X below 2^247: the fixed-length field encoding starts 0x00 and the
        // char-pick still yields 32 chars, while the vendor's BigInteger round-trip
        // drops that byte (31 bytes / 62 hex) and the pick falls to 30 -> null.
        val secret = ByteArray(32) { if (it == 0) 0 else (it * 7 + 1).toByte() }
        assertArrayEq(secret, OttaiBleAuth.fixedFieldBytes(secret, 32))
        val key = OttaiBleAuth.sessionKeyPick(OttaiCrypto.bytesToHex(secret))
        assertNotNull(key)
        assertEquals(32, key!!.length)

        val stripped = java.math.BigInteger(1, secret).toByteArray()
        assertEquals(31, stripped.size)
        assertEquals(null, OttaiBleAuth.sessionKeyPick(OttaiCrypto.bytesToHex(stripped)))
        assertArrayEq(secret, OttaiBleAuth.fixedFieldBytes(stripped, 32))
    }

    @Test
    fun deriveSessionKey_survivesLeadingZeroSharedSecret() {
        // ECDH against the curve generator gives shared X = X(d*G), so d selects the
        // shared secret directly. Scanning up from d=2, d=379 is the first whose
        // X < 2^247 (leading 0x00, second byte 0x55) — the 1-in-512 case that used
        // to derive an empty key mid-handshake. Fixed curve + fixed scalar, so the
        // secret is the same on every run and provider.
        val params = java.security.AlgorithmParameters.getInstance("EC")
            .apply { init(java.security.spec.ECGenParameterSpec(OttaiBleAuth.CURVE)) }
        val ecSpec = params.getParameterSpec(java.security.spec.ECParameterSpec::class.java)
        val kf = java.security.KeyFactory.getInstance("EC")
        val gPub = kf.generatePublic(
            java.security.spec.ECPublicKeySpec(ecSpec.generator, ecSpec),
        ) as ECPublicKey
        val priv = kf.generatePrivate(
            java.security.spec.ECPrivateKeySpec(java.math.BigInteger.valueOf(379), ecSpec),
        ) as ECPrivateKey

        val ka = javax.crypto.KeyAgreement.getInstance("ECDH")
        ka.init(priv)
        ka.doPhase(gPub, true)
        val shared = ka.generateSecret()
        assertEquals(32, shared.size) // provider hands back the padded field element
        assertEquals(0.toByte(), shared[0])
        assertEquals(31, java.math.BigInteger(1, shared).toByteArray().size) // would strip

        val key = OttaiBleAuth.deriveSessionKey(ecSpec.generator.affineX, ecSpec.generator.affineY, priv)
        assertNotNull(key)
        assertEquals(32, key!!.length)
        assertEquals(OttaiBleAuth.sessionKeyPick(OttaiCrypto.bytesToHex(shared)), key)
    }

    @Test
    fun bytesIntLE_roundTrip() {
        for (v in listOf(0, 1, 255, 256, 0x010203, 0x7FFFFFFF, -1)) {
            assertEquals(v, OttaiBleAuth.bytesToIntLE(OttaiBleAuth.intToBytesLE(v)))
        }
        // explicit little-endian layout
        assertEquals(1, OttaiBleAuth.bytesToIntLE(byteArrayOf(1, 0, 0, 0)))
        assertEquals(0x04030201, OttaiBleAuth.bytesToIntLE(byteArrayOf(1, 2, 3, 4)))
    }

    @Test
    fun appTime3_matchesOfficialBigEndianEncoding() {
        // Official transform: parse deviceTime BIG-endian, +1, then emit bytes [inc>>24, inc>>16, inc>>8].
        // deviceTime BE = 0x01000000 -> inc = 0x01000001 -> [0x01, 0x00, 0x00]
        val t3 = OttaiBleAuth.appTime3(byteArrayOf(1, 0, 0, 0))
        assertEquals(3, t3.size)
        assertArrayEq(byteArrayOf(1, 0, 0), t3)

        // deviceTime BE = 0xFFFF0000 -> inc = 0xFFFF0001 -> [0xFF, 0xFF, 0x00]
        val t3b = OttaiBleAuth.appTime3(byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0, 0))
        assertArrayEq(byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0), t3b)
    }

    @Test
    fun appAuthParameter_layoutAndLength() {
        val time3 = byteArrayOf(1, 2, 3)
        val x = ByteArray(32) { 0x11 }
        val y = ByteArray(32) { 0x22 }
        val p = OttaiBleAuth.appAuthParameter(5, time3, x, y)
        assertEquals(1 + 3 + 32 + 32, p.size)
        assertEquals(5.toByte(), p[0])
        assertArrayEq(time3, p.copyOfRange(1, 4))
        assertArrayEq(x, p.copyOfRange(4, 36))
        assertArrayEq(y, p.copyOfRange(36, 68))
    }

    @Test
    fun authSign_matchesIndependentSha256_andVerifies() {
        val authKeyHex = "00112233445566778899aabbccddeeff"
        val macHex = "00be44708301"
        val x = ByteArray(32) { (it + 1).toByte() }
        val y = ByteArray(32) { (it + 2).toByte() }
        val t3 = byteArrayOf(0x0a, 0x0b, 0x0c)

        val sign = OttaiBleAuth.authSignHex(authKeyHex, macHex, x, y, t3)
        // independent recomputation
        val md = java.security.MessageDigest.getInstance("SHA-256")
        md.update(OttaiCrypto.hexToBytes(authKeyHex))
        md.update(OttaiCrypto.hexToBytes(macHex))
        md.update(x); md.update(y); md.update(t3)
        assertArrayEq(md.digest(), sign)

        // verifyDeviceSign treats the same inputs as the device side
        assertTrue(OttaiBleAuth.verifyDeviceSign(sign, authKeyHex, macHex, x, y, t3))
        val tampered = sign.copyOf().also { it[0] = (it[0] + 1).toByte() }
        assertFalse(OttaiBleAuth.verifyDeviceSign(tampered, authKeyHex, macHex, x, y, t3))
    }

    @Test
    fun payload_encryptDecrypt_roundTripsWithTrailingZeroBlockTrim() {
        val sessionKey = "0123456789abcdef0123456789abcdef" // 32 hex = 16-byte AES key
        // 8-byte payload -> zero-padded to 16 (8 data + 8 zero) -> on decrypt the
        // trailing 8x00 block is dropped, recovering the original 8 bytes.
        val plain = byteArrayOf(0x40, 0x05, 0x00, 0x6D, 0x01, 0x32, 0x11, 0x22)
        val cipher = OttaiCrypto.encryptPayload(plain, sessionKey)
        assertNotNull(cipher)
        assertEquals(0, cipher!!.size % 16)
        val back = OttaiCrypto.decryptPayload(cipher, sessionKey)
        assertNotNull(back)
        assertArrayEq(plain, back!!)
    }

    @Test
    fun activateCmd_encryptsTo16Bytes_matchingZeroPadEncrypt() {
        val sessionKey = "0123456789abcdef0123456789abcdef"
        val enc = OttaiCrypto.encryptActivateCmd(byteArrayOf(0x03), sessionKey)
        assertNotNull(enc)
        assertEquals(16, enc!!.size) // {0x03} zero-padded to one AES block
        // encryptActivateCmd is exactly p.U(pad16(cmd), key) == encryptPayload here
        assertArrayEq(OttaiCrypto.encryptPayload(byteArrayOf(0x03), sessionKey)!!, enc)
    }

    private fun assertArrayEq(expected: ByteArray, actual: ByteArray) {
        assertEquals(OttaiCrypto.bytesToHex(expected), OttaiCrypto.bytesToHex(actual))
    }
}
