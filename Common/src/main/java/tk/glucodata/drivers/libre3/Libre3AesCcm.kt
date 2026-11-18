// Libre3AesCcm.kt — AES-128-CCM (NIST SP 800-38C / RFC 3610) on top of the platform AES block cipher.
//
// Android has no general AES/CCM Cipher (Keystore offers GCM, not CCM, and the conscrypt provider
// does not register CCM), and Libre 3 needs CCM with a 4-byte tag for both the Phase 5/6 handshake
// and every data-plane frame. Building CCM from "AES/ECB/NoPadding" works on every API level the
// app supports and on the host JVM, so the same code is what the unit tests run.

package tk.glucodata.drivers.libre3

import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

class Libre3AesCcm(key: ByteArray) {

    class AuthenticationException(message: String) : Exception(message)

    private val cipher: Cipher = Cipher.getInstance("AES/ECB/NoPadding").apply {
        require(key.size == 16) { "AES-128 key must be 16 bytes, got ${key.size}" }
        init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
    }

    private fun block(input: ByteArray): ByteArray = synchronized(cipher) { cipher.doFinal(input) }

    /** Returns ciphertext || tag. */
    fun encrypt(nonce: ByteArray, plaintext: ByteArray, tagLength: Int, aad: ByteArray = EMPTY): ByteArray {
        check(nonce, tagLength, plaintext.size)
        val mac = cbcMac(nonce, aad, plaintext, tagLength)
        val (ct, s0) = ctr(nonce, plaintext)
        val out = ct.copyOf(ct.size + tagLength)
        for (i in 0 until tagLength) out[ct.size + i] = (mac[i].toInt() xor s0[i].toInt()).toByte()
        return out
    }

    /** Verifies the trailing [tagLength]-byte tag and returns the plaintext. */
    fun decrypt(nonce: ByteArray, ciphertextAndTag: ByteArray, tagLength: Int, aad: ByteArray = EMPTY): ByteArray {
        if (ciphertextAndTag.size < tagLength) throw AuthenticationException("frame shorter than its tag")
        val ctLen = ciphertextAndTag.size - tagLength
        check(nonce, tagLength, ctLen)
        val ct = ciphertextAndTag.copyOfRange(0, ctLen)
        val tag = ciphertextAndTag.copyOfRange(ctLen, ciphertextAndTag.size)
        val (pt, s0) = ctr(nonce, ct)
        val mac = cbcMac(nonce, aad, pt, tagLength)
        val expected = ByteArray(tagLength) { (mac[it].toInt() xor s0[it].toInt()).toByte() }
        if (!Libre3Bytes.constantTimeEquals(expected, tag)) throw AuthenticationException("CCM tag mismatch")
        return pt
    }

    private fun check(nonce: ByteArray, tagLength: Int, length: Int) {
        val l = 15 - nonce.size
        require(l in 2..8) { "CCM nonce must be 7..13 bytes" }
        require(tagLength in TAG_LENGTHS) { "CCM tag length $tagLength not allowed" }
        if (l < 8) require(length.toLong() < (1L shl (8 * l))) { "CCM payload too long for nonce" }
    }

    private fun cbcMac(nonce: ByteArray, aad: ByteArray, payload: ByteArray, tagLength: Int): ByteArray {
        val l = 15 - nonce.size
        val b0 = ByteArray(16)
        val aFlag = if (aad.isEmpty()) 0 else 0x40
        b0[0] = (aFlag or (((tagLength - 2) / 2) shl 3) or (l - 1)).toByte()
        System.arraycopy(nonce, 0, b0, 1, nonce.size)
        var q = payload.size.toLong()
        for (i in 0 until l) {
            b0[15 - i] = (q and 0xFF).toByte()
            q = q ushr 8
        }
        val stream = java.io.ByteArrayOutputStream()
        stream.write(b0)
        if (aad.isNotEmpty()) {
            val header = java.io.ByteArrayOutputStream()
            require(aad.size < 0xFF00) { "AAD too long" }
            header.write(aad.size ushr 8)
            header.write(aad.size and 0xFF)
            header.write(aad)
            while (header.size() % 16 != 0) header.write(0)
            stream.write(header.toByteArray())
        }
        stream.write(payload)
        while (stream.size() % 16 != 0) stream.write(0)
        val all = stream.toByteArray()
        var y = ByteArray(16)
        var off = 0
        while (off < all.size) {
            val x = ByteArray(16) { (y[it].toInt() xor all[off + it].toInt()).toByte() }
            y = block(x)
            off += 16
        }
        return y
    }

    private fun ctr(nonce: ByteArray, input: ByteArray): Pair<ByteArray, ByteArray> {
        val l = 15 - nonce.size
        val a = ByteArray(16)
        a[0] = (l - 1).toByte()
        System.arraycopy(nonce, 0, a, 1, nonce.size)
        val s0 = block(a.copyOf())
        val out = ByteArray(input.size)
        var counter = 1L
        var idx = 0
        while (idx < input.size) {
            val ai = a.copyOf()
            var c = counter
            for (j in 0 until l) {
                ai[15 - j] = (c and 0xFF).toByte()
                c = c ushr 8
            }
            val s = block(ai)
            val take = minOf(16, input.size - idx)
            for (k in 0 until take) out[idx + k] = (input[idx + k].toInt() xor s[k].toInt()).toByte()
            idx += take
            counter++
        }
        return out to s0
    }

    companion object {
        private val EMPTY = ByteArray(0)
        private val TAG_LENGTHS = setOf(4, 6, 8, 10, 12, 14, 16)
    }
}
