// Libre3P256.kt — the NIST P-256 operations the Libre 3 handshake needs, on the platform JCA.
//
// Wire format everywhere is the 65-byte uncompressed point (04 || X || Y) and raw r || s ECDSA
// signatures; JCA wants X.509/DER objects, so this file converts at the boundary. ECDH, ECDSA
// and key generation are the platform's; the only arithmetic done here is deriving a public
// point from an imported private scalar, to check an imported identity's key matches its
// certificate (JCA exposes no API for that), and an on-curve check for peer points.

package tk.glucodata.drivers.libre3

import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPrivateKeySpec
import java.security.spec.ECPublicKeySpec
import javax.crypto.KeyAgreement

object Libre3P256 {

    class InvalidPointException(message: String) : Exception(message)

    private val P = BigInteger("ffffffff00000001000000000000000000000000ffffffffffffffffffffffff", 16)
    private val A = P.subtract(BigInteger.valueOf(3))
    private val B = BigInteger("5ac635d8aa3a93e7b3ebbd55769886bc651d06b0cc53b0f63bce3c3e27d2604b", 16)
    private val N = BigInteger("ffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632551", 16)
    private val GX = BigInteger("6b17d1f2e12c4247f8bce6e563a440f277037d812deb33a0f4a13945d898c296", 16)
    private val GY = BigInteger("4fe342e2fe1a7f9b8ee7eb4a7c0f9e162bce33576b315ececbb6406837bf51f5", 16)

    val params: ECParameterSpec by lazy {
        AlgorithmParameters.getInstance("EC").run {
            init(ECGenParameterSpec("secp256r1"))
            getParameterSpec(ECParameterSpec::class.java)
        }
    }

    fun generateKeyPair(random: SecureRandom = SecureRandom()): KeyPair =
        KeyPairGenerator.getInstance("EC").run {
            initialize(ECGenParameterSpec("secp256r1"), random)
            generateKeyPair()
        }

    fun privateKey(scalar: ByteArray): ECPrivateKey {
        val d = BigInteger(1, scalar)
        if (d.signum() <= 0 || d >= N) throw InvalidPointException("private scalar out of range")
        return KeyFactory.getInstance("EC").generatePrivate(ECPrivateKeySpec(d, params)) as ECPrivateKey
    }

    fun publicKey(uncompressed: ByteArray): ECPublicKey {
        val (x, y) = decodePoint(uncompressed)
        return KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ECPoint(x, y), params)) as ECPublicKey
    }

    fun encode(key: ECPublicKey): ByteArray = encodePoint(key.w.affineX, key.w.affineY)

    /** Raw 32-byte X coordinate of the shared point. */
    fun ecdh(privateKey: ECPrivateKey, peer: ECPublicKey): ByteArray =
        KeyAgreement.getInstance("ECDH").run {
            init(privateKey)
            doPhase(peer, true)
            fixed32(generateSecret())
        }

    /** ECDSA-P256/SHA-256 verify with a raw 64-byte r || s signature. */
    fun verify(publicKeyUncompressed: ByteArray, message: ByteArray, rawSignature: ByteArray): Boolean {
        if (rawSignature.size != 64) return false
        val r = BigInteger(1, rawSignature.copyOfRange(0, 32))
        val s = BigInteger(1, rawSignature.copyOfRange(32, 64))
        if (r.signum() == 0 || s.signum() == 0 || r >= N || s >= N) return false
        return runCatching {
            Signature.getInstance("SHA256withECDSA").run {
                initVerify(publicKey(publicKeyUncompressed))
                update(message)
                verify(derSignature(r, s))
            }
        }.getOrDefault(false)
    }

    /** d·G as an uncompressed point; used only to check an imported key against its certificate. */
    fun publicPointFromScalar(scalar: ByteArray): ByteArray {
        val d = BigInteger(1, scalar)
        if (d.signum() <= 0 || d >= N) throw InvalidPointException("private scalar out of range")
        val (x, y) = multiply(GX, GY, d) ?: throw InvalidPointException("point at infinity")
        return encodePoint(x, y)
    }

    fun decodePoint(uncompressed: ByteArray): Pair<BigInteger, BigInteger> {
        if (uncompressed.size != 65 || uncompressed[0].toInt() != 0x04) {
            throw InvalidPointException("expected a 65-byte uncompressed point")
        }
        val x = BigInteger(1, uncompressed.copyOfRange(1, 33))
        val y = BigInteger(1, uncompressed.copyOfRange(33, 65))
        if (x >= P || y >= P || !isOnCurve(x, y)) throw InvalidPointException("point is not on P-256")
        return x to y
    }

    fun encodePoint(x: BigInteger, y: BigInteger): ByteArray =
        byteArrayOf(0x04) + fixed32(x.toByteArray()) + fixed32(y.toByteArray())

    private fun isOnCurve(x: BigInteger, y: BigInteger): Boolean =
        y.multiply(y).mod(P) == x.pow(3).add(A.multiply(x)).add(B).mod(P)

    private fun fixed32(bytes: ByteArray): ByteArray = when {
        bytes.size == 32 -> bytes
        bytes.size > 32 -> bytes.copyOfRange(bytes.size - 32, bytes.size)
        else -> ByteArray(32 - bytes.size) + bytes
    }

    private fun derSignature(r: BigInteger, s: BigInteger): ByteArray {
        val rb = r.toByteArray()
        val sb = s.toByteArray()
        val body = byteArrayOf(0x02, rb.size.toByte()) + rb + byteArrayOf(0x02, sb.size.toByte()) + sb
        return byteArrayOf(0x30, body.size.toByte()) + body
    }

    // Affine double-and-add; only ever run on the user's own key at import time.
    private fun multiply(x: BigInteger, y: BigInteger, k: BigInteger): Pair<BigInteger, BigInteger>? {
        var result: Pair<BigInteger, BigInteger>? = null
        var addend: Pair<BigInteger, BigInteger>? = x to y
        for (i in 0 until k.bitLength()) {
            if (k.testBit(i)) result = add(result, addend)
            addend = add(addend, addend)
        }
        return result
    }

    private fun add(p1: Pair<BigInteger, BigInteger>?, p2: Pair<BigInteger, BigInteger>?): Pair<BigInteger, BigInteger>? {
        if (p1 == null) return p2
        if (p2 == null) return p1
        val (x1, y1) = p1
        val (x2, y2) = p2
        val lambda = if (x1 == x2) {
            if (y1.add(y2).mod(P).signum() == 0) return null
            x1.multiply(x1).multiply(THREE).add(A).multiply(y1.shiftLeft(1).modInverse(P)).mod(P)
        } else {
            y2.subtract(y1).multiply(x2.subtract(x1).modInverse(P)).mod(P)
        }
        val x3 = lambda.multiply(lambda).subtract(x1).subtract(x2).mod(P)
        val y3 = lambda.multiply(x1.subtract(x3)).subtract(y1).mod(P)
        return x3 to y3
    }

    private val THREE = BigInteger.valueOf(3)
}
