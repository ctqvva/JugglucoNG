// JugglucoNG — Ottai driver
// OttaiBleAuth.kt — BLE Auth V2 crypto (secp256r1 ECDH + SHA-256 signatures +
// session-key extraction).
//
// Source of truth: BleAuth.Companion a()/b()/c()/d() in the 1.1.0 watch decompile
// (see AGENTS/ottai-phase0-confirmed.md "BLE Auth V2"). Pure/offline + unit
// tested. The BLE state machine (read device param, write app param/sign, read
// flag) lives in OttaiBleManager (Phase 3); this module is just the math so it
// can be validated without a sensor.
//
// Field encodings (from kotlin/reflect/p.java):
//   p.w  bytes->int   : little-endian int32 of the first 4 bytes
//   p.l0 int->bytes   : 4 bytes little-endian
//   p.r  BigInt->bytes: toByteArray, drop leading 0x00 sign byte, min-24 left-pad
//   shared-secret hex : the fixed 32-byte big-endian X, leading zeros kept — the
//                       hex length feeds the char-pick, so it must not vary
//                       (see deriveSessionKey).

package tk.glucodata.drivers.ottai

import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import javax.crypto.KeyAgreement

object OttaiBleAuth {

    const val CURVE = "secp256r1"

    /** secp256r1 key pair (the app does `KeyPairGenerator("EC").initialize(256)`). */
    fun generateKeyPair(): KeyPair {
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(256)
        return kpg.generateKeyPair()
    }

    /** P-256 coordinate: strip the BigInteger sign byte and left-pad to exactly 32 bytes. */
    fun coordBytes(value: BigInteger): ByteArray {
        var b = value.toByteArray()
        if (b.isNotEmpty() && b[0] == 0.toByte()) {
            b = b.copyOfRange(1, b.size)
        }
        if (b.size >= 32) return b
        val out = ByteArray(32)
        System.arraycopy(b, 0, out, 32 - b.size, b.size)
        return out
    }

    fun publicCoords(pub: ECPublicKey): Pair<ByteArray, ByteArray> =
        coordBytes(pub.w.affineX) to coordBytes(pub.w.affineY)

    /** p.w — little-endian int32 from the first 4 bytes. */
    fun bytesToIntLE(b: ByteArray): Int {
        require(b.size >= 4) { "need >=4 bytes" }
        return (b[0].toInt() and 0xFF) or
            ((b[1].toInt() and 0xFF) shl 8) or
            ((b[2].toInt() and 0xFF) shl 16) or
            ((b[3].toInt() and 0xFF) shl 24)
    }

    /** p.l0 — int to 4 bytes little-endian. */
    fun intToBytesLE(i: Int): ByteArray = byteArrayOf(
        (i and 0xFF).toByte(),
        ((i ushr 8) and 0xFF).toByte(),
        ((i ushr 16) and 0xFF).toByte(),
        ((i ushr 24) and 0xFF).toByte(),
    )

    /**
     * Build the 3-byte app auth time from the device's current-time bytes.
     * The official client parses the bytes as one big-endian integer, adds one,
     * then emits the low three bytes little-endian.
     */
    fun appTime3(deviceTimeBytes: ByteArray): ByteArray {
        val inc = BigInteger(1, deviceTimeBytes).add(BigInteger.ONE)
        return byteArrayOf(
            inc.and(BigInteger.valueOf(0xFF)).toByte(),
            inc.shiftRight(8).and(BigInteger.valueOf(0xFF)).toByte(),
            inc.shiftRight(16).and(BigInteger.valueOf(0xFF)).toByte(),
        )
    }

    /**
     * App auth parameter written to char 1756ef6e:
     * `selectedIndex(1) || time3(3) || appPubX || appPubY`.
     */
    fun appAuthParameter(selectedIndex: Int, time3: ByteArray, pubX: ByteArray, pubY: ByteArray): ByteArray =
        byteArrayOf((selectedIndex and 0xFF).toByte()) + time3 + pubX + pubY

    /**
     * Auth signature (written to char 785022c6 / verified for the device):
     * `SHA256( authKey || mac || pubX || pubY || time3 )`.
     */
    fun authSign(authKey: ByteArray, mac: ByteArray, pubX: ByteArray, pubY: ByteArray, time3: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(authKey)
        md.update(mac)
        md.update(pubX)
        md.update(pubY)
        md.update(time3)
        return md.digest()
    }

    /** Convenience overload taking hex auth key + hex mac (as the app stores them). */
    fun authSignHex(authKeyHex: String, macHex: String, pubX: ByteArray, pubY: ByteArray, time3: ByteArray): ByteArray =
        authSign(OttaiCrypto.hexToBytes(authKeyHex), OttaiCrypto.hexToBytes(macHex), pubX, pubY, time3)

    /**
     * Verify the device's signature read from char 785022c6:
     * `deviceSign == SHA256( authKeys[devIdx] || mac || devPubX || devPubY || devTime )`.
     */
    fun verifyDeviceSign(
        deviceSign: ByteArray,
        authKeyHex: String,
        macHex: String,
        devPubX: ByteArray,
        devPubY: ByteArray,
        devTime: ByteArray,
    ): Boolean {
        val expect = authSign(
            OttaiCrypto.hexToBytes(authKeyHex), OttaiCrypto.hexToBytes(macHex),
            devPubX, devPubY, devTime,
        )
        return expect.contentEquals(deviceSign)
    }

    /**
     * ECDH session key. Computes the shared point X with the device's public key
     * and our private key, hex-encodes X as the fixed-length big-endian field
     * element, then runs the char-pick. Returns the session-key string (>=32
     * chars) or null if the pick is too short.
     */
    fun deriveSessionKey(devPubXHex: String, devPubYHex: String, ourPrivate: ECPrivateKey): String? {
        val x = BigInteger(devPubXHex, 16)
        val y = BigInteger(devPubYHex, 16)
        return deriveSessionKey(x, y, ourPrivate)
    }

    fun deriveSessionKey(devPubX: BigInteger, devPubY: BigInteger, ourPrivate: ECPrivateKey): String? {
        val params = AlgorithmParameters.getInstance("EC").apply { init(ECGenParameterSpec(CURVE)) }
        val ecSpec = params.getParameterSpec(ECParameterSpec::class.java)
        val devPub = KeyFactory.getInstance("EC")
            .generatePublic(ECPublicKeySpec(ECPoint(devPubX, devPubY), ecSpec)) as ECPublicKey
        val ka = KeyAgreement.getInstance("ECDH")
        ka.init(ourPrivate)
        ka.doPhase(devPub, true)
        // generateSecret() already returns X as the fixed-length big-endian field
        // element (SEC1/RFC 5903), leading zeros kept — the form the sensor derives
        // too; fieldLen only guards a provider that trims. Do NOT round-trip
        // through BigInteger the way the vendor app does: toByteArray drops the
        // leading 0x00 whenever X < 2^247 (1 handshake in 512), leaving 62 hex
        // chars, so the char-pick yields 30 and the key comes out empty — observed
        // 2 of 459 handshakes over 16 days, each costing a dead link until the
        // sensor gave up (status=19). Every X that fills the field encodes
        // identically either way, so only the case that always failed changes.
        val fieldLen = (ecSpec.curve.field.fieldSize + 7) / 8 // 32 for secp256r1
        val sharedHex = OttaiCrypto.bytesToHex(fixedFieldBytes(ka.generateSecret(), fieldLen))
        return sessionKeyPick(sharedHex)
    }

    /** Big-endian field element of exactly [fieldLen] bytes: left-pad, never strip. */
    fun fixedFieldBytes(value: ByteArray, fieldLen: Int): ByteArray {
        if (value.size == fieldLen) return value
        val out = ByteArray(fieldLen)
        if (value.size < fieldLen) {
            System.arraycopy(value, 0, out, fieldLen - value.size, value.size)
        } else {
            // over-long can only be a sign-prefixed BigInteger encoding: keep the low bytes
            System.arraycopy(value, value.size - fieldLen, out, 0, fieldLen)
        }
        return out
    }

    /**
     * Session-key char-pick from the shared-secret hex (BleAuth.c):
     * start at pos=2, append hex[pos], advance +1/+3 alternating
     * (positions 2,3,6,7,10,11,...). Require >=32 chars.
     */
    fun sessionKeyPick(sharedHex: String): String? {
        if (sharedHex.isEmpty()) return null
        val sb = StringBuilder()
        var pos = 2
        var toggle = 1
        var i = 2
        while (i < sharedHex.length) {
            if (pos < sharedHex.length) {
                sb.append(sharedHex[pos])
                pos += if (toggle != 0) 1 else 3
                toggle = toggle xor 1
            }
            i++
        }
        val out = sb.toString()
        return if (out.length >= 32) out else null
    }
}
