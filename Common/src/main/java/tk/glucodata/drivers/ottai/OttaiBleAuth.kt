package tk.glucodata.drivers.ottai

import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import javax.crypto.KeyAgreement

object OttaiBleAuth {

    private const val CURVE = "secp256r1"
    private val random = SecureRandom()

    data class AuthSession(
        val sessionKeyHex: String,
        val appKeyIndex: Int,
        val appPublicX: ByteArray,
        val appPublicY: ByteArray,
        val appTime3: ByteArray,
        val devicePublicX: ByteArray,
        val devicePublicY: ByteArray,
        val deviceKeyIndex: Int,
        val deviceTime3: ByteArray,
    )

    fun generateKeyPair(): Pair<ECPrivateKey, ECPublicKey> {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec(CURVE), random)
        val keyPair = generator.generateKeyPair()
        return keyPair.private as ECPrivateKey to keyPair.public as ECPublicKey
    }

    fun publicKeyToBytes(publicKey: ECPublicKey): Pair<ByteArray, ByteArray> {
        val w = publicKey.w
        val x = bigIntegerToBytes(w.affineX, 32)
        val y = bigIntegerToBytes(w.affineY, 32)
        return x to y
    }

    fun computeEcdhSharedSecretX(privateKey: ECPrivateKey, publicKeyX: ByteArray, publicKeyY: ByteArray): BigInteger {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec(CURVE))
        val refPair = generator.generateKeyPair()
        val refPub = refPair.public as ECPublicKey
        val params = refPub.params

        val factory = KeyFactory.getInstance("EC")
        val remotePoint = ECPoint(BigInteger(1, publicKeyX), BigInteger(1, publicKeyY))
        val remotePub = factory.generatePublic(ECPublicKeySpec(remotePoint, params)) as ECPublicKey

        val agreement = KeyAgreement.getInstance("ECDH")
        agreement.init(privateKey)
        agreement.doPhase(remotePub, true)
        return BigInteger(1, agreement.generateSecret())
    }

    fun deriveSessionKey(sharedSecretX: BigInteger): String {
        val hex = sharedSecretX.toString(16).padStart(64, '0')
        val sb = StringBuilder()
        var pos = 2
        var toggle = 1
        while (pos < hex.length) {
            sb.append(hex[pos])
            pos += if (toggle != 0) 1 else 3
            toggle = toggle xor 1
        }
        val sessionKey = sb.toString()
        require(sessionKey.length >= 32) { "Session key too short: ${sessionKey.length}" }
        return sessionKey
    }

    fun buildAppTime3(currentTimeLeBytes: ByteArray): ByteArray {
        val inc = le4toInt(currentTimeLeBytes) + 1
        return byteArrayOf(
            ((inc shr 16) and 0xFF).toByte(),
            (inc and 0xFF).toByte(),
            ((inc shr 8) and 0xFF).toByte()
        )
    }

    fun buildDeviceSign(
        authKey: ByteArray,
        macBytes: ByteArray,
        devicePublicX: ByteArray,
        devicePublicY: ByteArray,
        deviceTime3: ByteArray,
    ): ByteArray {
        val input = authKey + macBytes + devicePublicX + devicePublicY + deviceTime3
        return OttaiCrypto.sha256(input)
    }

    fun buildAppSign(
        authKey: ByteArray,
        macBytes: ByteArray,
        appPublicX: ByteArray,
        appPublicY: ByteArray,
        appTime3: ByteArray,
    ): ByteArray {
        val input = authKey + macBytes + appPublicX + appPublicY + appTime3
        return OttaiCrypto.sha256(input)
    }

    fun buildAppAuthParam(index: Int, time3: ByteArray, pubX: ByteArray, pubY: ByteArray): ByteArray {
        return byteArrayOf(index.toByte()) + time3 + pubX + pubY
    }

    fun parseDeviceAuthParam(data: ByteArray): Triple<Int, ByteArray, Pair<ByteArray, ByteArray>> {
        require(data.size >= 68) { "Device auth param too short: ${data.size}" }
        val keyIndex = data[0].toInt() and 0xFF
        val time3 = data.copyOfRange(1, 4)
        val pubX = data.copyOfRange(4, 36)
        val pubY = data.copyOfRange(36, 68)
        return Triple(keyIndex, time3, pubX to pubY)
    }

    fun bigIntegerToBytes(value: BigInteger, expectedLen: Int): ByteArray {
        val bytes = value.toByteArray()
        if (bytes.size == expectedLen) return bytes
        if (bytes.size == expectedLen + 1 && bytes[0] == 0.toByte()) {
            return bytes.copyOfRange(1, bytes.size)
        }
        if (bytes.size < expectedLen) {
            return ByteArray(expectedLen - bytes.size) + bytes
        }
        return bytes.copyOfRange(bytes.size - expectedLen, bytes.size)
    }

    private fun le4toInt(bytes: ByteArray): Int {
        return (bytes[0].toInt() and 0xFF) or
            ((bytes[1].toInt() and 0xFF) shl 8) or
            ((bytes[2].toInt() and 0xFF) shl 16) or
            ((bytes[3].toInt() and 0xFF) shl 24)
    }

    fun intToLe4(value: Int): ByteArray {
        return byteArrayOf(
            (value and 0xFF).toByte(),
            ((value shr 8) and 0xFF).toByte(),
            ((value shr 16) and 0xFF).toByte(),
            ((value shr 24) and 0xFF).toByte(),
        )
    }

    fun longToLe8(value: Long): ByteArray {
        return byteArrayOf(
            (value and 0xFF).toByte(),
            ((value shr 8) and 0xFF).toByte(),
            ((value shr 16) and 0xFF).toByte(),
            ((value shr 24) and 0xFF).toByte(),
            ((value shr 32) and 0xFF).toByte(),
            ((value shr 40) and 0xFF).toByte(),
            ((value shr 48) and 0xFF).toByte(),
            ((value shr 56) and 0xFF).toByte(),
        )
    }

    fun le16toInt(b0: Byte, b1: Byte): Int {
        return (b0.toInt() and 0xFF) or ((b1.toInt() and 0xFF) shl 8)
    }
}
