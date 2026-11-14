// Libre3P256.kt — the NIST P-256 operations the Libre 3 handshake needs, on the platform JCA.
//
// Wire format everywhere is the 65-byte uncompressed point (04 || X || Y) and raw r || s ECDSA
// signatures; JCA wants X.509/DER objects, so this file converts at the boundary. ECDH, ECDSA
// and key generation use the platform provider. Imported key pairs are checked by signing and
// verifying a random challenge. The only local curve arithmetic checks public peer points.

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

    /** Prove the imported pair matches using provider-backed ECDSA, without deriving d·G here. */
    fun matchesPrivateKey(scalar: ByteArray, point: ByteArray): Boolean {
        val challenge = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(privateKey(scalar))
            update(challenge)
            sign()
        }
        return Signature.getInstance("SHA256withECDSA").run {
            initVerify(publicKey(point))
            update(challenge)
            verify(signature)
        }
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

}
