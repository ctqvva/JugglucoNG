// JugglucoNG — Ottai (Chinese-market CGM) driver
// OttaiCrypto.kt — cloud secret-chain crypto.
//
// Source of truth: AGENTS/ottai-protocol.md ("Cloud Secret Chain"), derived from
// the readable 1.1.0 watch APK (DeviceManager.m1035a / m1033h, C2172p.m2339k).
//
// The account-level `glucoseSecretKey` is the root used to AES-decrypt three
// per-device cloud fields returned by device validation/binding:
//   - keyA        -> six 16-byte BLE auth keys (192 hex chars plaintext)
//   - method      -> RPN glucose expression (see OttaiFormula, not yet built)
//   - coefficient -> CSV of coefficients used by that expression
//
// Each field uses its own AES key string:
//   fieldKey = glucoseSecretKey + takeLast(10, <fieldTimestamp>) + takeLast(6, mac)
// where <fieldTimestamp> is produceTime (keyA), methodUpdateTime (method), or
// coeffUpdateTime (coefficient). The cipher is AES/ECB/PKCS5Padding over the
// base64-decoded field, with the UTF-8 bytes of fieldKey as the AES key and no IV.
//
// NOTE: This module is pure/offline and unit-tested. It does NOT prove the
// derivation is correct for live data — that is validated in Phase 1 against a
// real cloud validate response and in Phase 3/4 against sensor output. Do not
// store readings on the strength of this module alone.

package tk.glucodata.drivers.ottai

import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

object OttaiCrypto {

    /** Six 16-byte BLE auth keys decoded from a decrypted keyA group. */
    const val AUTH_KEY_COUNT = 6
    const val AUTH_KEY_BYTES = 16
    const val AUTH_KEY_HEX_LEN = AUTH_KEY_COUNT * AUTH_KEY_BYTES * 2 // 192

    /** Decompiled `takeLast(n, s)` (C2206n.m2409E1): last [n] chars of [s]. */
    fun takeLast(n: Int, s: String): String {
        if (n <= 0) return ""
        if (s.length <= n) return s
        return s.substring(s.length - n)
    }

    /**
     * Build the AES key string for a cloud field.
     *
     * @param glucoseSecretKey account secret from login (kept out of logs)
     * @param fieldTimestamp produceTime / methodUpdateTime / coeffUpdateTime
     * @param mac bound device MAC (any case/separator form; last 6 chars are taken
     *            verbatim as the app does — caller passes the same MAC string the
     *            app uses)
     */
    fun deriveFieldKey(glucoseSecretKey: String, fieldTimestamp: String, mac: String): String =
        glucoseSecretKey + takeLast(10, fieldTimestamp) + takeLast(6, mac)

    fun deriveFieldKey(glucoseSecretKey: String, fieldTimestamp: Long, mac: String): String =
        deriveFieldKey(glucoseSecretKey, fieldTimestamp.toString(), mac)

    /**
     * AES/ECB/PKCS5Padding decrypt of a base64-encoded cloud field.
     *
     * @return UTF-8 plaintext, or null on any failure (bad base64, wrong key
     *         length, padding error).
     */
    fun decryptCloudField(base64Cipher: String, fieldKey: String): String? {
        return try {
            val cipherBytes = base64Decode(base64Cipher) ?: return null
            val keyBytes = fieldKey.toByteArray(Charsets.UTF_8)
            val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"))
            val plain = cipher.doFinal(cipherBytes)
            String(plain, Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    /** AES/ECB/PKCS5Padding encrypt -> base64. Used only by tests/round-trips. */
    fun encryptCloudField(plaintext: String, fieldKey: String): String? {
        return try {
            val keyBytes = fieldKey.toByteArray(Charsets.UTF_8)
            val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, "AES"))
            val out = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
            base64Encode(out)
        } catch (_: Exception) {
            null
        }
    }

    /** Convenience: decrypt keyA using produceTime. */
    fun decryptKeyA(base64KeyA: String, glucoseSecretKey: String, produceTime: String, mac: String): String? =
        decryptCloudField(base64KeyA, deriveFieldKey(glucoseSecretKey, produceTime, mac))

    /** Convenience: decrypt method using methodUpdateTime. */
    fun decryptMethod(base64Method: String, glucoseSecretKey: String, methodUpdateTime: String, mac: String): String? =
        decryptCloudField(base64Method, deriveFieldKey(glucoseSecretKey, methodUpdateTime, mac))

    /** Convenience: decrypt coefficient using coeffUpdateTime. */
    fun decryptCoefficient(base64Coeff: String, glucoseSecretKey: String, coeffUpdateTime: String, mac: String): String? =
        decryptCloudField(base64Coeff, deriveFieldKey(glucoseSecretKey, coeffUpdateTime, mac))

    /**
     * Split a decrypted keyA hex string into six 16-byte auth keys.
     * Returns null if the plaintext is not exactly 192 hex chars.
     */
    fun parseAuthKeys(keyAHexPlain: String): List<ByteArray>? {
        val hex = keyAHexPlain.trim()
        if (hex.length != AUTH_KEY_HEX_LEN) return null
        if (!hex.all { it.isHexChar() }) return null
        return (0 until AUTH_KEY_COUNT).map { i ->
            hexToBytes(hex.substring(i * 32, i * 32 + 32))
        }
    }

    // ---- BLE payload / activate-command AES (session key) ----
    //
    // The app uses `AES/ECB/ZeroBytePadding` (p.U for encrypt; CgmMonitor for
    // decrypt) keyed by hex2bytes(sessionKey). ZeroBytePadding is not a portable
    // JCE transform (Android has it via the bundled provider, plain JVM does
    // not), so we implement it over `AES/ECB/NoPadding`: zero-pad the plaintext
    // up to a 16-byte multiple on encrypt; strip trailing 0x00 on decrypt. This
    // is byte-identical to the vendor behaviour and works in unit tests too.

    /**
     * Decrypt a BLE live/history payload with the hex session key.
     * Mirrors CgmMonitor: AES/ECB (zero-byte-padding via NoPadding), then — per
     * the decompiled trim (CgmMonitor.java:125) — drop the trailing 8 bytes ONLY
     * when the length is even and those last 8 bytes are all 0x00. Otherwise the
     * buffer is returned unchanged.
     */
    fun decryptPayload(cipher: ByteArray, sessionKeyHex: String): ByteArray? {
        val key = runCatching { hexToBytes(sessionKeyHex) }.getOrNull() ?: return null
        if (cipher.isEmpty() || cipher.size % 16 != 0) return null
        return try {
            val c = Cipher.getInstance("AES/ECB/NoPadding")
            c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"))
            trimTrailingZeroBlock(c.doFinal(cipher))
        } catch (_: Exception) {
            null
        }
    }

    /** Exact CgmMonitor trim: drop last 8 bytes iff len even and last 8 are 0x00. */
    private fun trimTrailingZeroBlock(data: ByteArray): ByteArray {
        if (data.size >= 8 && data.size % 2 == 0) {
            var allZero = true
            for (i in data.size - 8 until data.size) {
                if (data[i] != 0.toByte()) { allZero = false; break }
            }
            if (allZero) return data.copyOf(data.size - 8)
        }
        return data
    }

    /**
     * Build the activate command body: zero-pad [cmd] to 16 bytes then
     * AES/ECB encrypt with the hex session key. Equals the decompiled `p.U`.
     * For the standard activate this is `encryptActivateCmd(byteArrayOf(0x03), key)`.
     */
    fun encryptActivateCmd(cmd: ByteArray, sessionKeyHex: String): ByteArray? {
        val key = runCatching { hexToBytes(sessionKeyHex) }.getOrNull() ?: return null
        val padded = zeroPad16(cmd)
        return try {
            val c = Cipher.getInstance("AES/ECB/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
            c.doFinal(padded)
        } catch (_: Exception) {
            null
        }
    }

    /** Test/round-trip helper: AES/ECB zero-pad encrypt of an arbitrary payload. */
    fun encryptPayload(plain: ByteArray, sessionKeyHex: String): ByteArray? {
        val key = runCatching { hexToBytes(sessionKeyHex) }.getOrNull() ?: return null
        return try {
            val c = Cipher.getInstance("AES/ECB/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
            c.doFinal(zeroPad16(plain))
        } catch (_: Exception) {
            null
        }
    }

    private fun zeroPad16(data: ByteArray): ByteArray {
        if (data.isEmpty()) return ByteArray(16)
        val rem = data.size % 16
        if (rem == 0) return data
        return data.copyOf(data.size + (16 - rem))
    }

    // ---- hex ----

    private fun Char.isHexChar(): Boolean =
        this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

    fun hexToBytes(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "odd hex length" }
        val out = ByteArray(hex.length / 2)
        var i = 0
        while (i < out.size) {
            val hi = Character.digit(hex[i * 2], 16)
            val lo = Character.digit(hex[i * 2 + 1], 16)
            require(hi >= 0 && lo >= 0) { "bad hex char" }
            out[i] = ((hi shl 4) or lo).toByte()
            i++
        }
        return out
    }

    fun bytesToHex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append(HEX[v ushr 4])
            sb.append(HEX[v and 0x0F])
        }
        return sb.toString()
    }

    private val HEX = "0123456789abcdef".toCharArray()

    // ---- self-contained Base64 (standard alphabet, '=' padding) ----
    // Kept dependency-free so the cloud-secret chain is unit-testable on a plain
    // JVM without android.util.Base64.

    private const val B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    fun base64Encode(data: ByteArray): String {
        val sb = StringBuilder(((data.size + 2) / 3) * 4)
        var i = 0
        while (i < data.size) {
            val b0 = data[i].toInt() and 0xFF
            val b1 = if (i + 1 < data.size) data[i + 1].toInt() and 0xFF else -1
            val b2 = if (i + 2 < data.size) data[i + 2].toInt() and 0xFF else -1
            sb.append(B64[b0 ushr 2])
            if (b1 < 0) {
                sb.append(B64[(b0 and 0x03) shl 4])
                sb.append("==")
            } else if (b2 < 0) {
                sb.append(B64[((b0 and 0x03) shl 4) or (b1 ushr 4)])
                sb.append(B64[(b1 and 0x0F) shl 2])
                sb.append('=')
            } else {
                sb.append(B64[((b0 and 0x03) shl 4) or (b1 ushr 4)])
                sb.append(B64[((b1 and 0x0F) shl 2) or (b2 ushr 6)])
                sb.append(B64[b2 and 0x3F])
            }
            i += 3
        }
        return sb.toString()
    }

    fun base64Decode(s: String): ByteArray? {
        val clean = s.trim().filter { it != '\n' && it != '\r' }
        if (clean.isEmpty()) return ByteArray(0)
        return try {
            val inv = IntArray(128) { -1 }
            for (idx in B64.indices) inv[B64[idx].code] = idx
            val out = ArrayList<Byte>(clean.length / 4 * 3)
            var buffer = 0
            var bits = 0
            for (c in clean) {
                if (c == '=') break
                if (c.code >= 128) return null
                val v = inv[c.code]
                if (v < 0) return null
                buffer = (buffer shl 6) or v
                bits += 6
                if (bits >= 8) {
                    bits -= 8
                    out.add(((buffer ushr bits) and 0xFF).toByte())
                }
            }
            out.toByteArray()
        } catch (_: Exception) {
            null
        }
    }
}
