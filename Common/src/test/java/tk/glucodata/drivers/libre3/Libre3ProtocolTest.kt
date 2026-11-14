package tk.glucodata.drivers.libre3

import java.math.BigInteger
import java.security.KeyPair
import java.security.SecureRandom
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cross-implementation vectors: the legacy native generator's compile-time checks
 * (dp_activation.hpp), RFC 3610, and RoundWhiteDiscKit's (MIT) synthetic-scalar identity vectors
 * and captured data-plane frames. No real credential appears here.
 */
class Libre3ProtocolTest {
    private fun h(s: String) = Libre3Bytes.parseHex(s)!!
    private fun seq(from: Int, until: Int) = ByteArray(until - from) { (from + it).toByte() }

    @Test fun activationBodyMatchesLegacyNativeVectors() {
        assertEquals("8c4286628d6d411fbc93", Libre3Bytes.hex(Libre3Nfc.activationBody(0x6286428dL, 0x1f416d8dL)))
        assertEquals("00000000000000003e31", Libre3Bytes.hex(Libre3Nfc.activationBody(1, 0)))
        assertEquals("080000000c0000006320", Libre3Bytes.hex(Libre3Nfc.activationBody(9, 12)))
        assertEquals("02a87a8c4286628d6d411fbc93",
            Libre3Bytes.hex(Libre3Nfc.activationCommand(Libre3Nfc.CMD_SWITCH_RECEIVER, 0x6286428dL, 0x1f416d8dL)))
    }

    @Test fun patchInfoAndActivationResponsesParse() {
        val info = Libre3Nfc.parsePatchInfo(h("00A500010001000000C04E1E0D0101040C043036385A4D524631382F97"))!!
        assertEquals("068ZMRF18", info.serial)
        assertEquals(4, info.state)
        assertEquals(20160, info.wearDurationMinutes)
        assertEquals(60, info.warmupMinutes)
        assertEquals(Libre3Nfc.CMD_SWITCH_RECEIVER, Libre3Nfc.chooseCommand(info))
        val ok = Libre3Nfc.parseActivationResponse(h("00A500111DB71932189A948F6E9F0A7562A2FA"), 1_700_000_000L)
            as Libre3Nfc.ActivationResult.Success
        assertEquals("18:32:19:B7:1D:11", ok.bleAddress)
        assertEquals("9a948f6e", Libre3Bytes.hex(ok.blePin))
        assertEquals(1651837599L, ok.activationTimeSeconds)
        val err = Libre3Nfc.parseActivationResponse(h("00A501B1"), 0) as Libre3Nfc.ActivationResult.SensorError
        assertTrue(err.isWrongReceiver)
        assertNull(Libre3Nfc.parsePatchInfo(h("00A500")))
    }

    @Test fun receiverIdFolds() {
        val account = "2977dec2-492a-11ea-9702-0242ac110002"
        assertEquals(0x1f416d8dL, Libre3ReceiverId.fromAccountId(account, Libre3ReceiverId.Derivation.FREESTYLE_LIBRE_3))
        assertEquals(0x8356f9c7L, Libre3ReceiverId.fromAccountId(account.uppercase(), Libre3ReceiverId.Derivation.LIBRE_BY_ABBOTT))
        assertEquals("8d6d411f", Libre3ReceiverId.toWireHex(0x1f416d8dL))
        assertEquals(0x1f416d8dL, Libre3ReceiverId.parseWireHex("8d6d411f"))
    }

    @Test fun ccmMatchesRfc3610AndRejectsTampering() {
        val ccm = Libre3AesCcm(h("C0C1C2C3C4C5C6C7C8C9CACBCCCDCECF"))
        val nonce = h("00000003020100A0A1A2A3A4A5")
        val aad = h("0001020304050607")
        val out = ccm.encrypt(nonce, h("08090A0B0C0D0E0F101112131415161718191A1B1C1D1E"), 8, aad)
        assertEquals("588c979a61c663d2f066d0c2c0f989806d5f6b61dac38417e8d12cfdf926e0", Libre3Bytes.hex(out))
        assertEquals("08090a0b0c0d0e0f101112131415161718191a1b1c1d1e", Libre3Bytes.hex(ccm.decrypt(nonce, out, 8, aad)))
        out[0] = (out[0].toInt() xor 1).toByte()
        assertTrue(runCatching { ccm.decrypt(nonce, out, 8, aad) }.isFailure)
    }

    @Test fun capturedDataPlaneFramesDecrypt() {
        val s = Libre3DataPlaneSession(h("7c536d2ec3c76c741a776de85596959d"), h("00000000bcc696a2"))
        val asm = Libre3Framing.GlucoseAssembler()
        assertNull(asm.feed(h("3c937752c72e2dfdcfe05a5dce315b")))
        val frame = asm.feed(h("6f3785f23dbc85066856d60027c5a41f45c90100"))!!
        val g = s.decrypt(Libre3Channel.GLUCOSE_DATA, frame)!!
        assertEquals(3, g.descriptorKind)
        val r = Libre3RealtimeReading.parse(g.plaintext)!!
        assertEquals(14554, r.lifeCount)
        assertEquals(103, r.currentMgdl)

        val st = Libre3PatchStatus.parse(s.decrypt(Libre3Channel.PATCH_STATUS, h("4e6fe9573b6365476193e9c42009acd20100"))!!.plaintext)!!
        assertEquals(14555, st.currentLifeCount)
        assertTrue(st.isActive)

        val page = Libre3HistoricalPage.parse(s.decrypt(Libre3Channel.HISTORIC_DATA, h("4637aa722274876d69a34f20096c49adced70100"))!!.plaintext)!!
        assertEquals(920, page.startLifeCount)
        assertEquals(listOf(109, 107, 105, 110, 106, 101), page.samples().map { it.second })
        assertNull(s.decrypt(Libre3Channel.PATCH_STATUS, h("4e6fe9573b6365476193e9c42009acd20101")))
    }

    @Test fun glucoseWordQualityGate() {
        assertEquals(98, Libre3Glucose.usableMgdl(0x0062))
        assertNull(Libre3Glucose.usableMgdl(0x8062))
        assertNull(Libre3Glucose.usableMgdl(0x2062))
        assertNull(Libre3Glucose.usableMgdl(38))
        assertNull(Libre3Glucose.usableMgdl(502))
        assertEquals("01000105000000", Libre3Bytes.hex(Libre3ControlCommand.historicalFrom(5)))
        assertEquals("01010101000000", Libre3Bytes.hex(Libre3ControlCommand.clinicalFrom(1)))
    }

    @Test fun capturedSensorCertificateVerifiesWithFamily1Key() {
        val cert = Libre3SensorCertificate(h(
            "0157259416c6007ae00550043e1f46f25d44b3d72a8c37dcfebc7c339ed01fc5668a6387458084ac9cafebe7438b649f76b81eeca9343287da162b07c5c07362997e40e13035df14cdf3d5d81a5fe9ffc84de8c8ecc33be609bf0d8ebf97bed6bae899e181bb32960c81e99d5aab30b2fc7a77b0d45eff9885d46722371cd9375e14352d890f60fa1e7159e6"))
        assertEquals(1, cert.verifiedSigningKeyIndex())
    }

    // RoundWhiteDiscKit AppIdentityTests: synthetic scalars 0x1111 / 0x4444, not real keys.
    private val certHex = "03000102030405060708090a0b0c0d0e0f1000005f92a79201000000000000000004a85b3aad6f3a346d85523141cb434e1caf4c642b2b3cc952cb07a635cb6a1df1bcbdadb66bae8eb25eca92f8b08b67be02cc736150dd7ffcd3f019c875f9ffde" + "0".repeat(128)
    private fun scalar(v: Int) = ByteArray(32).also { it[30] = (v shr 8).toByte(); it[31] = v.toByte() }
    private val authKey = "d321308aaf9d1d68132ff5192ecb67f4"
    private val sensorReply = "5e10ddf6634fcf8fce8e81bf67d47dd5a66995159a53f7cbc3939a75d34a565844e1be3b5ca66092a5127f362ae7c7278feb1184058ce9aed6a77f43b1b2b3b4b5b6b7"

    @Test fun identityKeyAgreementAndPhase5MatchReference() {
        val id = Libre3AppIdentity.create("test", scalar(0x4444), h(certHex))
        assertTrue(runCatching { Libre3AppIdentity.create("bad", scalar(0x5555), h(certHex)) }.isFailure)
        val k = Libre3Auth.authKey(
            id, Libre3P256.privateKey(scalar(0x1111)),
            h("04a62f048f367359809c2d46c2049d7d7bf268c3c073c472753cb18a24a8ad20b1caccf8104b666795c7f35dac9dc444b3c2c61978198c49859955b99956da5edb"),
            h("048570e95d85825286db92c78317679bdd8ffe3c90d0af84291bf64132b66fcc99c926f087212d75b1f4dbc5d4999b4c5605adf66db801a4de371cdad39ebc55e5"),
        )
        assertEquals(authKey, Libre3Bytes.hex(k))
        val p5 = Libre3Auth.phase5(k, Libre3Auth.Challenge(seq(0, 16), h("a1a2a3a4a5a6a7")), seq(0x40, 0x50), h("deadbeef"))
        assertEquals(54, p5.size)
        assertEquals("a4016be402aef5485155238b8dee3f36f564dd18819889ff25b35221a7f6f207b3512232816ce2c5",
            Libre3Bytes.hex(p5.copyOf(40)))
        val m = Libre3Auth.phase6(k, h(sensorReply), seq(0, 16), seq(0x40, 0x50))
        assertArrayEquals(seq(0x80, 0x90), m.kEnc)
        assertArrayEquals(seq(0x90, 0x98), m.ivEnc)
    }

    @Test fun cachedHandshakeRunsThroughStateMachine() {
        val fixedR2 = object : SecureRandom() {
            override fun nextBytes(bytes: ByteArray) { for (i in bytes.indices) bytes[i] = (0x40 + i).toByte() }
        }
        val hs = Libre3Handshake(Libre3Handshake.Mode.CACHED, h("deadbeef"), null, h(authKey), fixedR2,
            ephemeralFactory = { error("unused") as KeyPair })
        assertEquals(listOf(Libre3Handshake.Action.WriteCommand(0x11)), hs.start())
        assertTrue(hs.onCommandResponse(byteArrayOf(0x08, 23)).isEmpty())
        val challenge = seq(0, 16) + h("a1a2a3a4a5a6a7")
        assertTrue(hs.onChallengeData(byteArrayOf(0) + challenge.copyOf(19)).isEmpty())
        val p5 = hs.onChallengeData(byteArrayOf(1) + challenge.copyOfRange(19, 23))
        assertEquals(2, p5.size)
        assertEquals(Libre3Handshake.Action.WriteCommand(0x08), p5[1])
        assertTrue(hs.onCommandResponse(byteArrayOf(0x08, 67)).isEmpty())
        val reply = h(sensorReply)
        var last: List<Libre3Handshake.Action> = emptyList()
        var seqNo = 0
        for (off in reply.indices step 19) {
            last = hs.onChallengeData(byteArrayOf((seqNo++).toByte()) + reply.copyOfRange(off, minOf(off + 19, reply.size)))
        }
        val done = last.single() as Libre3Handshake.Action.Authenticated
        assertArrayEquals(seq(0x80, 0x90), done.session.kEnc)
        assertEquals(Libre3Handshake.Stage.DONE, hs.stage)
    }

    @Test fun p256PublicFromScalarMatchesJca() {
        val pair = Libre3P256.generateKeyPair()
        val d = (pair.private as java.security.interfaces.ECPrivateKey).s
        val raw = d.toByteArray().let { if (it.size > 32) it.copyOfRange(it.size - 32, it.size) else ByteArray(32 - it.size) + it }
        assertTrue(Libre3P256.matchesPrivateKey(raw, Libre3P256.encode(pair.public as java.security.interfaces.ECPublicKey)))
        val other = Libre3P256.generateKeyPair()
        assertFalse(Libre3P256.matchesPrivateKey(raw, Libre3P256.encode(other.public as java.security.interfaces.ECPublicKey)))
        assertTrue(BigInteger.ONE.signum() > 0)
    }

    @Test fun writeFragmentsAreOffsetPrefixed() {
        val frags = Libre3Framing.fragmentForWrite(ByteArray(54) { it.toByte() })
        assertEquals(3, frags.size)
        assertEquals("1200", Libre3Bytes.hex(frags[1].copyOf(2)))
        assertEquals(20, frags[2].size)
    }
}
