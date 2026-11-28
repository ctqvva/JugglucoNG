package tk.glucodata.drivers.libre3

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wire captures from real Libre 3 sensors, published with xDrip4iOS 7.2.0's native Libre 3 driver
 * (xDrip Tests/Libre3, from RoundWhiteDiscKit, MIT; xdripswift f7447f1c70a1). That driver ships to
 * users with the same protocol and credential source, so these bytes are an independent check that
 * this driver's NFC commands, NFC replies, receiver IDs, key derivation and data-plane records
 * have the layouts real sensors use. No credential or private key appears here.
 */
class Libre3CapturedVectorsTest {
    private fun h(s: String) = Libre3Bytes.parseHex(s)!!
    private fun hex(b: ByteArray) = Libre3Bytes.hex(b)

    // ---- NFC A0 / A8 ----

    @Test fun activationAndSwitchCommandsMatchCapturedWire() {
        // xDrip passes the time it puts on the wire; this driver sends nowSeconds - 1 like the
        // legacy native path, so it is called with one second more.
        assertEquals("02a07afc2bee6978830d6fb471",
            hex(Libre3Nfc.activationCommand(Libre3Nfc.CMD_ACTIVATE, 1777216508L + 1, 0x6f0d8378L)))
        assertEquals("02a87a8f9cfb69231f25c3aac8",
            hex(Libre3Nfc.activationCommand(Libre3Nfc.CMD_SWITCH_RECEIVER, 1778097295L + 1, 0xc3251f23L)))
        assertEquals("02a87a75a8fb6978830d6f4cd4",
            hex(Libre3Nfc.activationCommand(Libre3Nfc.CMD_SWITCH_RECEIVER, 1778100341L + 1, 0x6f0d8378L)))
        assertEquals(0x71b4, Libre3Nfc.crc16(h("fc2bee6978830d6f")))
    }

    @Test fun patchInfoMatchesFreshAndActiveCaptures() {
        val fresh = Libre3Nfc.parsePatchInfo(h("00a50001000200010060541e020401040c0130525243393839415151ff17"))!!
        assertEquals("0RRC989AQ", fresh.serial)
        assertEquals(1, fresh.state)
        assertTrue(fresh.isNotActivated)
        assertEquals(21600, fresh.wearDurationMinutes)
        assertEquals("1.4.2.30", fresh.firmwareVersion)
        assertEquals(1, fresh.puckGeneration)
        assertEquals(4, fresh.productType)
        assertEquals(60, fresh.warmupMinutes)
        assertEquals(Libre3Nfc.CMD_ACTIVATE, Libre3Nfc.chooseCommand(fresh))

        val active = Libre3Nfc.parsePatchInfo(h("00a50001000200010060541e020401040c04305252433938394151c6ca"))!!
        assertEquals("0RRC989AQ", active.serial)
        assertEquals(4, active.state)
        assertEquals(21600, active.wearDurationMinutes)
        assertEquals(60, active.warmupMinutes)
        assertEquals(Libre3Nfc.CMD_SWITCH_RECEIVER, Libre3Nfc.chooseCommand(active))

        // Some readers repeat the A5 byte.
        val padded = Libre3Nfc.parsePatchInfo(h("00a5a5a5a5a5a5a5a50001000200010060541e020401040c04305252433938394151c6ca"))!!
        assertEquals(active, padded)
    }

    @Test fun activationRepliesUseTheCapturedPinBoundary() {
        val activated = Libre3Nfc.parseActivationResponse(h("00a50058f9b8df22cc3225ec7200000000ad06"), 1777216508L)
            as Libre3Nfc.ActivationResult.Success
        assertEquals("CC:22:DF:B8:F9:58", activated.bleAddress)
        assertEquals("3225ec72", hex(activated.blePin))
        // A fresh activation reports no time; the scan time stands in for it.
        assertFalse(activated.reportedActivationTime)
        assertEquals(1777216508L, activated.activationTimeSeconds)

        // A switch to the original receiver returns the time the sensor was started.
        val switched = Libre3Nfc.parseActivationResponse(h("00a50058f9b8df22cc02bafbbbfc2bee698e6e"), 1778097295L)
            as Libre3Nfc.ActivationResult.Success
        assertEquals("CC:22:DF:B8:F9:58", switched.bleAddress)
        assertEquals("02bafbbb", hex(switched.blePin))
        assertTrue(switched.reportedActivationTime)
        assertEquals(1777216508L, switched.activationTimeSeconds)
        assertEquals("CC22DFB8F958", Libre3Nfc.advertisedName(switched.bleAddress))
    }

    // ---- receiver IDs ----

    @Test fun receiverIdsMatchCapturedActivations() {
        // Receiver IDs of two activations real sensors accepted.
        val classic = Libre3ReceiverId.Derivation.FREESTYLE_LIBRE_3
        assertEquals(0x6f0d8378L, Libre3ReceiverId.fromAccountId("5abb0ad8-dc2e-4ede-9e2d-67472a3e630e", classic))
        assertEquals(0xc3251f23L, Libre3ReceiverId.fromAccountId("6147368e-c060-44a5-9e96-1c02333f43c0", classic))
        assertEquals("78830d6f", Libre3ReceiverId.toWireHex(0x6f0d8378L))
        assertEquals("231f25c3", Libre3ReceiverId.toWireHex(0xc3251f23L))
        assertEquals(0x6f0d8378L, Libre3ReceiverId.parseWireHex("0x78830d6f"))
    }

    @Test fun receiverIdFoldsMatchPublishedSampleAccount() {
        val sample = "2977dec2-492a-11ea-9702-0242ac110002" // DiaBLE's documented sample, not a real account
        assertEquals(524_381_581L, Libre3ReceiverId.fromAccountId(sample, Libre3ReceiverId.Derivation.FREESTYLE_LIBRE_3))
        assertEquals(2_203_515_335L, Libre3ReceiverId.fromAccountId(sample, Libre3ReceiverId.Derivation.LIBRE_BY_ABBOTT))
        assertEquals("c7f95683", Libre3ReceiverId.toWireHex(2_203_515_335L))
        for (d in Libre3ReceiverId.Derivation.entries) {
            assertEquals(Libre3ReceiverId.fromAccountId(sample, d), Libre3ReceiverId.fromAccountId(sample.uppercase(), d))
        }
        // A partial last word is left-padded.
        assertEquals((0x32393737L + 0x64656332L + 0x2d34L) and 0xFFFFFFFFL,
            Libre3ReceiverId.fromAccountId("2977dec2-4", Libre3ReceiverId.Derivation.LIBRE_BY_ABBOTT))
    }

    // ---- authorization ----

    @Test fun authKeyDerivationMatchesPublishedVector() {
        val ze = ByteArray(32) { 1 }
        val zs = ByteArray(32) { 2 }
        assertEquals("e97f08effaff1721038aa5cd96ffbe1e", hex(Libre3Auth.deriveAuthKey(ze, zs)))
        assertFalse(Libre3Auth.deriveAuthKey(ze, zs).contentEquals(Libre3Auth.deriveAuthKey(zs, ze)))
    }

    @Test fun phase6ReplyCarriesEchoesThenSessionKeys() {
        val kAuth = ByteArray(16) { 0x42 }
        val r2 = ByteArray(16) { 1 }
        val r1 = ByteArray(16) { 2 }
        val kEnc = ByteArray(16) { 3 }
        val ivEnc = ByteArray(8) { 4 }
        val nonce = ByteArray(7) { 5 }
        val wire = Libre3AesCcm(kAuth).encrypt(nonce, r2 + r1 + kEnc + ivEnc, Libre3Auth.TAG_SIZE) + nonce
        assertEquals(Libre3Auth.PHASE6_WIRE_SIZE, wire.size)
        val material = Libre3Auth.phase6(kAuth, wire, expectedR1 = r1, expectedR2 = r2)
        assertArrayEquals(kEnc, material.kEnc)
        assertArrayEquals(ivEnc, material.ivEnc)
    }

    // ---- data plane ----

    @Test fun nonceUsesSequenceDescriptorAndIv() {
        val s = Libre3DataPlaneSession(h("4bbce496a63cc9a435adeeb4f78e1617"), h("0000000067c72c01"))
        assertEquals("01004400000000000067c72c01", hex(s.nonce(0x0001, Libre3Channel.FACTORY_DATA.descriptorKind)))
    }

    @Test fun capturedPatchStatusDecrypts() {
        val s = Libre3DataPlaneSession(h("7c536d2ec3c76c741a776de85596959d"), h("00000000bcc696a2"))
        val d = s.decrypt(Libre3Channel.PATCH_STATUS, h("4e6fe9573b6365476193e9c42009acd20100"))!!
        assertEquals("9d280000e0000704db381607", hex(d.plaintext))
        val st = Libre3PatchStatus.parse(d.plaintext)!!
        assertEquals(10397, st.lifeCount)
        assertEquals(14555, st.currentLifeCount)
        assertEquals(22, st.stackDisconnectReason)
        assertEquals(7, st.appDisconnectReason)
    }

    @Test fun capturedRealtimeReadingsParse() {
        data class Case(val hex: String, val life: Int, val mgdl: Int, val rateRaw: Int, val temp: Int)
        for (c in listOf(
            Case("e3386200d6ff0000f023d1386c000b62006c00200a9e03113d03100000", 14563, 98, -42, 2592),
            Case("e4386200d6ff0000f023d1386c000b62006c001f0a9b03093d0b100000", 14564, 98, -42, 2591),
            Case("e5386100d9ff00008c23d1386c000b61006c001e0a9403053d12100000", 14565, 97, -39, 2590),
            Case("e6386100daff00008c23d1386c000b61006c001d0a9203003dfb0f0000", 14566, 97, -38, 2589),
        )) {
            val r = Libre3RealtimeReading.parse(h(c.hex))!!
            assertEquals(c.life, r.lifeCount)
            assertEquals(c.mgdl, r.currentMgdl)
            assertEquals(c.rateRaw, r.rateOfChangeRaw)
            assertEquals(c.rateRaw / 100f, r.rateMgdlPerMinute, 0.0001f)
            assertEquals(if (c.mgdl == 98) 9200 else 9100, r.projectedGlucoseRaw)
            assertEquals(14545, r.historicalLifeCount)
            assertEquals(108, r.historicalMgdl)
            assertEquals(3, r.trend)
            assertTrue(r.isActionable)
            assertFalse(r.dataQualityError)
            assertEquals(0, r.sensorCondition)
            assertEquals(c.mgdl, r.uncappedCurrentMgdl)
            assertEquals(108, r.uncappedHistoricalMgdl)
            assertEquals(c.temp, r.temperatureRaw)
        }
    }

    @Test fun capturedQualityBitsWithholdTheValue() {
        val base = h("e3386200d6ff0000f023d1386c000b62006c00200a9e03113d03100000")
        fun with(offset: Int, word: Int) = base.copyOf().also { Libre3Bytes.putU16le(it, offset, word) }
        // Data-quality error and sensor-condition bits on the current word.
        assertNull(Libre3RealtimeReading.parse(with(2, 0x8062))!!.currentMgdl)
        assertNull(Libre3RealtimeReading.parse(with(2, 0x2062))!!.currentMgdl)
        // Same bits on the historical word.
        assertNull(Libre3RealtimeReading.parse(with(12, 0x806c))!!.historicalMgdl)
        assertNull(Libre3RealtimeReading.parse(with(12, 0x406c))!!.historicalMgdl)
        assertEquals(98, Libre3RealtimeReading.parse(base)!!.currentMgdl)
    }

    @Test fun capturedPatchStatusParses() {
        val st = Libre3PatchStatus.parse(h("9d280000e0000704e4381300"))!!
        assertEquals(10397, st.lifeCount)
        assertEquals(0, st.errorData)
        assertEquals(224, st.eventDataRaw)
        assertEquals(7, st.eventIndex)
        assertEquals(4, st.patchState)
        assertTrue(st.isActive)
        assertEquals(14564, st.currentLifeCount)
        assertEquals(19, st.stackDisconnectReason)
        assertEquals(0, st.appDisconnectReason)
        assertFalse(st.isTerminated)
        assertEquals(Libre3PatchStatus.Attention.NONE, st.attention)

        fun error(word: String) = Libre3PatchStatus.parse(h("9d28${word}e0000704e4381300"))!!
        assertEquals(Libre3PatchStatus.Attention.INSERTION_FAILED, error("0300").attention)
        assertEquals(Libre3PatchStatus.Attention.ENDED, error("0500").attention)
        assertFalse(error("0500").isTerminated)
        assertTrue(error("0600").isTerminated)
        assertTrue(error("0800").isTerminated)
        assertFalse(error("0700").isTerminated)
    }

    @Test fun capturedHistoricalPagesParse() {
        val page = Libre3HistoricalPage.parse(h("8e0369006b006d006b0069006e00"))!!
        assertEquals(910, page.startLifeCount)
        assertEquals(935, page.lastLifeCount)
        assertEquals(listOf(910 to 105, 915 to 107, 920 to 109, 925 to 107, 930 to 105, 935 to 110), page.samples())
        assertEquals(940, Libre3HistoricalPage.parse(h("ac036f00710070006c006a006b00"))!!.startLifeCount)
        assertEquals(1000, Libre3HistoricalPage.parse(h("e803780079007a007b007c007d00"))!!.startLifeCount)
    }

    @Test fun patchControlPlaintextsMatchReferenceBuilders() {
        assertEquals("01000105000000", hex(Libre3ControlCommand.historicalFrom(5)))
        assertEquals("01010101000000", hex(Libre3ControlCommand.clinicalFrom(1)))
        assertEquals("0100018b030000", hex(Libre3ControlCommand.historicalFrom(907)))
        assertEquals("04010000000000", hex(Libre3ControlCommand.eventLogFrom(1)))
    }

    @Test fun controlWriteIsThirteenBytesAndDecryptsUnderDescriptorZero() {
        val kEnc = h("4bbce496a63cc9a435adeeb4f78e1617")
        val ivEnc = h("0000000067c72c01")
        val frame = Libre3DataPlaneSession(kEnc, ivEnc).encryptControl(Libre3ControlCommand.historicalFrom(5))
        assertEquals(13, frame.size)
        assertEquals(0x0001, Libre3Bytes.u16le(frame, 11))
        val nonce = Libre3DataPlaneSession(kEnc, ivEnc).nonce(1, 0)
        assertEquals("01000105000000", hex(Libre3AesCcm(kEnc).decrypt(nonce, frame.copyOf(11), Libre3Auth.TAG_SIZE)))
    }
}
