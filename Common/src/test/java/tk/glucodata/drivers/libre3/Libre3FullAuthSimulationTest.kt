package tk.glucodata.drivers.libre3

import java.math.BigInteger
import java.security.MessageDigest
import java.security.Signature
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Full (first-pair) authorization against a simulated sensor: its own patch-signing key, signed
 * certificate, static and ephemeral keys, and the sensor side of the kAuth schedule and Phase 5/6.
 * Every message goes through the same fragmentation the driver uses. Synthetic keys only.
 */
class Libre3FullAuthSimulationTest {
    private fun h(s: String) = Libre3Bytes.parseHex(s)!!
    private val certHex = "03000102030405060708090a0b0c0d0e0f1000005f92a79201000000000000000004a85b3aad6f3a346d85523141cb434e1caf4c642b2b3cc952cb07a635cb6a1df1bcbdadb66bae8eb25eca92f8b08b67be02cc736150dd7ffcd3f019c875f9ffde" + "0".repeat(128)
    private val pin = h("01020304")

    private fun raw32(v: BigInteger) = v.toByteArray().let { if (it.size > 32) it.copyOfRange(it.size - 32, it.size) else ByteArray(32 - it.size) + it }

    private fun derToRaw(der: ByteArray): ByteArray {
        var i = 2
        val rLen = der[i + 1].toInt(); val r = BigInteger(1, der.copyOfRange(i + 2, i + 2 + rLen)); i += 2 + rLen
        val sLen = der[i + 1].toInt(); val s = BigInteger(1, der.copyOfRange(i + 2, i + 2 + sLen))
        return raw32(r) + raw32(s)
    }

    private class Sensor(val signing: java.security.KeyPair, private val appStaticPub: ByteArray, private val pin: ByteArray) {
        val static = Libre3P256.generateKeyPair()
        val ephemeral = Libre3P256.generateKeyPair()
        val r1 = ByteArray(16) { (0xA0 + it).toByte() }
        val nonce5 = h7("b1b2b3b4b5b6b7")
        val nonce6 = h7("c1c2c3c4c5c6c7")
        val kEnc = ByteArray(16) { (0x10 + it).toByte() }
        val iv = ByteArray(8) { (0x30 + it).toByte() }
        var kAuth: ByteArray? = null
        var phoneEphemeral: ByteArray? = null

        companion object { fun h7(s: String) = Libre3Bytes.parseHex(s)!! }

        fun certificate(sign: (ByteArray) -> ByteArray): ByteArray {
            val body = byteArrayOf(0x01) + ByteArray(10) { it.toByte() } + Libre3P256.encode(static.public as ECPublicKey)
            return body + sign(body)
        }

        fun deriveKAuth() {
            val ze = Libre3P256.ecdh(ephemeral.private as ECPrivateKey, Libre3P256.publicKey(phoneEphemeral!!))
            val zs = Libre3P256.ecdh(static.private as ECPrivateKey, Libre3P256.publicKey(appStaticPub))
            kAuth = MessageDigest.getInstance("SHA-256").digest(byteArrayOf(0, 0, 0, 1) + ze + zs).copyOf(16)
        }

        fun phase6(phase5: ByteArray): ByteArray {
            val pt = Libre3AesCcm(kAuth!!).decrypt(nonce5, phase5.copyOf(40), 4)
            check(pt.copyOfRange(0, 16).contentEquals(r1)) { "R1 not echoed" }
            check(pt.copyOfRange(32, 36).contentEquals(pin)) { "PIN mismatch" }
            val r2 = pt.copyOfRange(16, 32)
            return Libre3AesCcm(kAuth!!).encrypt(nonce6, r2 + r1 + kEnc + iv, 4) + nonce6
        }
    }

    /** Sensor → phone notification framing: seq byte + 19 payload bytes. */
    private fun notify(message: ByteArray): List<ByteArray> =
        (message.indices step 19).mapIndexed { seq, off -> byteArrayOf(seq.toByte()) + message.copyOfRange(off, minOf(off + 19, message.size)) }

    /** Undo the driver's 20-byte offset-prefixed write fragments. */
    private fun reassemble(frags: List<ByteArray>): ByteArray =
        frags.fold(ByteArray(0)) { acc, f -> acc + f.copyOfRange(2, 20) }

    @Test fun firstPairCompletesAgainstSimulatedSensor() {
        val identity = Libre3AppIdentity.create("test", ByteArray(30) + byteArrayOf(0x44, 0x44), h(certHex))
        val signing = Libre3P256.generateKeyPair()
        val sensor = Sensor(signing, identity.staticPublicKey, pin)
        val cert = sensor.certificate { body ->
            derToRaw(Signature.getInstance("SHA256withECDSA").run { initSign(signing.private); update(body); sign() })
        }
        val hs = Libre3Handshake(
            Libre3Handshake.Mode.FULL, pin, identity, null,
            signingKeys = listOf(Libre3P256.encode(signing.public as ECPublicKey)),
        )

        val writes = ArrayList<Libre3Handshake.Action>()
        fun collect(a: List<Libre3Handshake.Action>) { writes += a }

        collect(hs.start())
        assertEquals(listOf(1, 2), writes.filterIsInstance<Libre3Handshake.Action.WriteCommand>().map { it.command }.take(2))
        val sentCert = writes.filterIsInstance<Libre3Handshake.Action.WriteMessage>().single().message
        assertArrayEquals(identity.certificate, reassemble(Libre3Framing.fragmentForWrite(sentCert)).copyOf(162))
        writes.clear()

        collect(hs.onCommandResponse(byteArrayOf(0x04)))
        assertEquals(Libre3Handshake.Action.WriteCommand(0x09), writes.single()); writes.clear()

        collect(hs.onCommandResponse(byteArrayOf(0x0A, 140.toByte())))
        notify(cert).forEach { collect(hs.onCertData(it)) }
        val eph = writes.filterIsInstance<Libre3Handshake.Action.WriteMessage>().single().message
        assertEquals(72, eph.size)
        sensor.phoneEphemeral = eph.copyOf(65)
        assertEquals(listOf(0x0D, 0x0E), writes.filterIsInstance<Libre3Handshake.Action.WriteCommand>().map { it.command })
        writes.clear()

        collect(hs.onCommandResponse(byteArrayOf(0x0F, 65)))
        notify(Libre3P256.encode(sensor.ephemeral.public as ECPublicKey)).forEach { collect(hs.onCertData(it)) }
        assertEquals(Libre3Handshake.Action.WriteCommand(0x11), writes.single()); writes.clear()
        sensor.deriveKAuth()

        collect(hs.onCommandResponse(byteArrayOf(0x08, 23)))
        notify(sensor.r1 + sensor.nonce5).forEach { collect(hs.onChallengeData(it)) }
        val phase5 = writes.filterIsInstance<Libre3Handshake.Action.WriteMessage>().single().message
        assertEquals(54, phase5.size)
        writes.clear()

        collect(hs.onCommandResponse(byteArrayOf(0x08, 67)))
        notify(sensor.phase6(phase5)).forEach { collect(hs.onChallengeData(it)) }
        val done = writes.single() as Libre3Handshake.Action.Authenticated
        assertArrayEquals(sensor.kEnc, done.session.kEnc)
        assertArrayEquals(sensor.iv, done.session.ivEnc)
        assertArrayEquals(sensor.kAuth, done.kAuth)
        assertTrue(!done.viaCache)
    }

    @Test fun certificateFromUnknownSignerIsRefused() {
        val identity = Libre3AppIdentity.create("test", ByteArray(30) + byteArrayOf(0x44, 0x44), h(certHex))
        val signing = Libre3P256.generateKeyPair()
        val sensor = Sensor(signing, identity.staticPublicKey, pin)
        val cert = sensor.certificate { body ->
            derToRaw(Signature.getInstance("SHA256withECDSA").run { initSign(signing.private); update(body); sign() })
        }
        val hs = Libre3Handshake(Libre3Handshake.Mode.FULL, pin, identity, null) // production signing keys
        hs.start(); hs.onCommandResponse(byteArrayOf(0x04)); hs.onCommandResponse(byteArrayOf(0x0A, 140.toByte()))
        val result = notify(cert).flatMap { hs.onCertData(it) }
        val failed = result.single() as Libre3Handshake.Action.Failed
        assertEquals(Libre3Handshake.FailureKind.SENSOR_CERTIFICATE, failed.kind)
    }
}
