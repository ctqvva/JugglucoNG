package tk.glucodata.drivers.libre2

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Known-answer and simulated-sensor tests. The Gen2 vectors are synthetic
 * (reverse-engineering notes plus Juggluco's cipher self test); the Gen1
 * vectors are the vendor-library fixtures under `Common/src/main/cpp`.
 * Nothing here is evidence of interoperability with a physical sensor.
 */
class Libre2ManagedDriverTest {
    private fun hex(text: String): ByteArray =
        ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

    private val uid = hex("1122334455667788")

    // ---- Gen2 primitives ----

    @Test
    fun speckBlockMatchesSelfTestVector() {
        val roundKeys = Libre2Gen2Crypto.expandKey(hex("0001020308090a0b1011121318191a1b"))
        assertEquals("8b024e4548a56f8c", Libre2Gen2Crypto.encryptBlock(roundKeys, hex("2d4375747465723b")).hex())
    }

    @Test
    fun sensorKeyUsesUidWithoutLastTwoBytes() {
        assertEquals("91300e5485a4e889357eb96976adde47", Libre2Gen2Crypto.deriveSensorKey(1, uid)!!.hex())
        assertEquals(
            Libre2Gen2Crypto.deriveSensorKey(1, uid)!!.hex(),
            Libre2Gen2Crypto.deriveSensorKey(1, hex("112233445566ffff"))!!.hex(),
        )
    }

    @Test
    fun rootSlotsGiveDifferentKeysAndUnknownSlotsNone() {
        val slot2 = Libre2Gen2Crypto.deriveSensorKey(2, uid)!!
        assertFalse(slot2.contentEquals(Libre2Gen2Crypto.deriveSensorKey(1, uid)))
        assertNull(Libre2Gen2Crypto.deriveSensorKey(0, uid))
        assertNull(Libre2Gen2Crypto.deriveSensorKey(3, uid))
        assertNull(Libre2Gen2Crypto.deriveSensorKey(1, ByteArray(1)))
        assertNull(Libre2Gen2Crypto.deriveSessionKey(1, ByteArray(15)))
    }

    @Test
    fun crcPadsOddLengthsAndReversesBits() {
        assertEquals(0x87d1, Libre2Gen2Crypto.crc16(ByteArray(23) { it.toByte() }))
        assertEquals(0x2884, Libre2Gen2Crypto.crc16(ByteArray(23) { (0x30 + it).toByte() }))
    }

    @Test
    fun ctrCounterWrapsInByteZeroWithoutCarry() {
        val key = ByteArray(16) { it.toByte() }
        val nonce = hex("ff00000000000000")
        val stream = Libre2Gen2Crypto.cryptCtr(key, nonce, ByteArray(16))
        val second = Libre2Gen2Crypto.encryptBlock(Libre2Gen2Crypto.expandKey(key), ByteArray(8))
        assertArrayEquals(second, stream.copyOfRange(8, 16))
        assertEquals("ff00000000000000", nonce.hex())
    }

    // ---- Gen2 BLE ----

    private val provisioningRecord = hex("0102030405060708090a0100")

    @Test
    fun bleLoginRequestMatchesSyntheticVector() {
        val exchange = Libre2Gen2Exchange.open(1, uid, ble = true)!!
        val request = exchange.buildBleRequest(
            Libre2Gen2Provisioning.parse(provisioningRecord)!!,
            hex("101112131415161718191a1b1c1d"),
            hex("00010203040506"),
        )
        assertEquals("1f9721b451ec4de33dfdfdf3e2b4d8c669ead6", request!!.hex())
    }

    @Test
    fun provisioningAcceptsBothRecordSizesOnly() {
        assertEquals(1, Libre2Gen2Provisioning.parse(provisioningRecord.copyOf(10))!!.rootSlot)
        assertNull(Libre2Gen2Provisioning.parse(provisioningRecord.copyOf(10))!!.storedRootSlot)
        assertEquals(2, Libre2Gen2Provisioning.parse(hex("0102030405060708090a0200"))!!.rootSlot)
        assertEquals(1, Libre2Gen2Provisioning.parse(hex("0102030405060708090a0000"))!!.rootSlot)
        assertNull(Libre2Gen2Provisioning.parse(hex("0102030405060708090a0300"))!!.rootSlot)
        assertNull(Libre2Gen2Provisioning.parse(provisioningRecord.copyOf(11)))
        assertNull(Libre2Gen2Provisioning.parse(null))
        assertArrayEquals(provisioningRecord, Libre2Gen2Provisioning.parse(provisioningRecord)!!.toBytes())
        assertEquals(10, Libre2Gen2Provisioning.parse(provisioningRecord.copyOf(10))!!.toBytes().size)
    }

    /** A sensor that follows the same protocol description, for round trips only. */
    private inner class FakeSensor(val rootSlot: Int) {
        val sensorKey = Libre2Gen2Crypto.deriveSensorKey(rootSlot, uid)!!
        val sensorRandom = hex("a1a2a3a4a5a6a7")
        val counter = 0x01020304
        val salt = hex("5152d3")
        val prefix = hex("61626364")
        val epoch = hex("7172")
        val sessionContext = ByteArray(16) { (0x80 + it).toByte() }
        val sessionKey = Libre2Gen2Crypto.deriveSessionKey(rootSlot, sessionContext)!!
        val bleChallenge = sensorRandom + hex("04030201") + hex("000000")
        val record = prefix + salt + hex("0a0b0c") + byteArrayOf(rootSlot.toByte(), 0)

        fun nfcChallenge(): ByteArray {
            val body = sensorRandom + hex("04030201") + hex("000000") + salt + prefix + epoch
            val crc = Libre2Gen2Crypto.crc16(body)
            return body + byteArrayOf(crc.toByte(), (crc ushr 8).toByte())
        }

        fun hostRandom(request: ByteArray, ble: Boolean): ByteArray {
            val header = if (ble) 1 else 4
            val nonce = Libre2Gen2Protocol.authNonce(salt, counter, ble, 0)
            return Libre2Gen2Crypto.cryptCtr(sensorKey, nonce, request, header, request.size - header).copyOf(7)
        }

        fun bleLoginReply(request: ByteArray): ByteArray {
            val nonce = Libre2Gen2Protocol.authNonce(salt, counter, true, 1)
            return Libre2Gen2Crypto.cryptCtr(sensorKey, nonce, hostRandom(request, true) + sessionContext) + hex("0000")
        }

        fun bleFrame(plain: ByteArray): ByteArray {
            val nonce = hex("0000") + prefix.copyOf(3) + byteArrayOf((prefix[3].toInt() or 0x80).toByte(), plain[0], plain[1])
            return plain.copyOf(2) + Libre2Gen2Crypto.cryptCtr(sessionKey, nonce, plain, 2, 44)
        }

        /** Reply after the status byte: 8 framing bytes, ciphertext, CRC. */
        fun nfcReply(plain: ByteArray): ByteArray {
            val nonce = Libre2Gen2Protocol.authNonce(salt, counter, false, 1)
            val encrypted = Libre2Gen2Crypto.cryptCtr(sensorKey, nonce, plain)
            val crc = Libre2Gen2Crypto.crc16(encrypted)
            return ByteArray(8) { 0xa5.toByte() } + encrypted + byteArrayOf(crc.toByte(), (crc ushr 8).toByte())
        }

        fun framBlock(block: Int, plain: ByteArray): ByteArray {
            val nonce = hex("0000") + prefix.copyOf(3) + byteArrayOf((prefix[3].toInt() and 0x7f).toByte()) + epoch
            return Libre2Gen2Crypto.cryptBlocks(sessionKey, nonce, block, plain)
        }
    }

    private fun login(sensor: FakeSensor): Pair<Libre2Gen2BleLogin, ByteArray> {
        val login = Libre2Gen2BleLogin { it.fill(0x42) }
        val request = login.answerChallenge(uid, sensor.record, sensor.bleChallenge)!!
        return login to sensor.bleLoginReply(request)
    }

    @Test
    fun bleLoginDerivesSessionKeyAndDecryptsFramesForBothRootSlots() {
        for (slot in 1..2) {
            val sensor = FakeSensor(slot)
            val (login, reply) = login(sensor)
            assertNull(login.decryptFrame(ByteArray(46)))
            assertEquals(Libre2Gen2BleLogin.Reply.NEED_MORE, login.onReplyPart(reply.copyOf(7)))
            assertEquals(Libre2Gen2BleLogin.Reply.ACCEPTED, login.onReplyPart(reply.copyOfRange(7, 25)))
            assertTrue(login.authenticated)
            val plain = ByteArray(46) { (it * 3).toByte() }
            assertArrayEquals(plain, login.decryptFrame(sensor.bleFrame(plain)))
            assertNull(login.decryptFrame(ByteArray(47)))
            assertNull(login.decryptFrame(ByteArray(45)))
            login.close()
            assertNull(login.decryptFrame(sensor.bleFrame(plain)))
        }
    }

    @Test
    fun bleLoginRejectsWrongEchoOrderAndLength() {
        val sensor = FakeSensor(1)
        val (wrongEcho, reply) = login(sensor)
        reply[0] = (reply[0].toInt() xor 1).toByte()
        wrongEcho.onReplyPart(reply.copyOf(7))
        assertEquals(Libre2Gen2BleLogin.Reply.REJECTED, wrongEcho.onReplyPart(reply.copyOfRange(7, 25)))
        assertFalse(wrongEcho.authenticated)

        val (outOfOrder, reply2) = login(sensor)
        assertEquals(Libre2Gen2BleLogin.Reply.REJECTED, outOfOrder.onReplyPart(reply2.copyOfRange(7, 25)))

        val (badLength, _) = login(sensor)
        assertEquals(Libre2Gen2BleLogin.Reply.REJECTED, badLength.onReplyPart(ByteArray(20)))

        // A sensor provisioned under the other root slot must not log in.
        val other = FakeSensor(2)
        val mismatched = Libre2Gen2BleLogin { it.fill(0x42) }
        val request = mismatched.answerChallenge(uid, sensor.record, other.bleChallenge)!!
        val otherReply = other.bleLoginReply(request)
        mismatched.onReplyPart(otherReply.copyOf(7))
        assertEquals(Libre2Gen2BleLogin.Reply.REJECTED, mismatched.onReplyPart(otherReply.copyOfRange(7, 25)))
    }

    @Test
    fun bleLoginRefusesMissingInputsAndUnknownRootSlot() {
        val login = Libre2Gen2BleLogin { it.fill(1) }
        assertNull(login.answerChallenge(null, provisioningRecord, ByteArray(14)))
        assertNull(login.answerChallenge(uid, ByteArray(9), ByteArray(14)))
        assertNull(login.answerChallenge(uid, hex("0102030405060708090a0300"), ByteArray(14)))
        assertNotNull(login.failure)
        assertNull(login.answerChallenge(uid, provisioningRecord, ByteArray(13)))
        assertEquals(Libre2Gen2BleLogin.Reply.REJECTED, login.onReplyPart(ByteArray(7)))
    }

    @Test
    fun newChallengeDiscardsThePreviousSession() {
        val sensor = FakeSensor(1)
        val (login, reply) = login(sensor)
        login.onReplyPart(reply.copyOf(7))
        // The link dropped before the second half; a new challenge arrives.
        login.answerChallenge(uid, sensor.record, sensor.bleChallenge)
        assertEquals(Libre2Gen2BleLogin.Reply.REJECTED, login.onReplyPart(reply.copyOfRange(7, 25)))
    }

    // ---- Gen2 NFC ----

    @Test
    fun nfcEnableRequestMatchesSyntheticVector() {
        val exchange = Libre2Gen2Exchange.open(1, uid, ble = false)!!
        val challenge = hex("000102030405060708090a0b0c0d0e0f10111213141516d187")
        val request = exchange.buildNfcRequest(0x1e, challenge, hex("a0a1a2a3a4a5a6"))!!
        // All 15 encrypted bytes are sent; the earlier wrapper zeroed the last one.
        assertEquals("02a1071efd04f2f1af56c912988f5a67981022", request.hex())
        challenge[0] = 1
        assertNull(exchange.buildNfcRequest(0x1e, challenge, hex("a0a1a2a3a4a5a6")))
    }

    @Test
    fun nfcReplyKeystreamMatchesSyntheticVector() {
        val key = Libre2Gen2Crypto.deriveSensorKey(1, uid)!!
        val nonce = Libre2Gen2Protocol.authNonce(hex("0e0f10"), 0x0a090807, false, 1)
        val plain = Libre2Gen2Crypto.cryptCtr(key, nonce, hex("303132333435363738393a3b3c3d3e3f40414243444546"))
        assertEquals("bb4689f6809212f95d4ee9ad4d87d9187bb3a227f6649c", plain.hex())
    }

    /** Scripted tag: answers the root-slot and challenge reads, then hands the request to [onCommand]. */
    private fun tag(sensor: FakeSensor, reportedSlot: Int = sensor.rootSlot, onCommand: (ByteArray) -> ByteArray?) =
        Libre2NfcTransceiver { command ->
            when (command[3].toInt()) {
                0x22 -> byteArrayOf(0, 0, 0, reportedSlot.toByte(), (reportedSlot ushr 8).toByte())
                0x20 -> byteArrayOf(0) + sensor.nfcChallenge()
                else -> onCommand(command)
            }
        }

    private fun nfc(transceiver: Libre2NfcTransceiver) = Libre2Gen2Nfc(transceiver) { it.fill(0x33) }

    @Test
    fun nfcEnableStreamingReturnsAddressAndProvisioning() {
        val sensor = FakeSensor(2)
        val payload = hex("c1c2c3c4c5c6" + "0a0b0c")
        val grant = nfc(tag(sensor) { request ->
            assertEquals(19, request.size)
            assertEquals(0x1e, request[3].toInt())
            byteArrayOf(0) + sensor.nfcReply(sensor.sensorRandom + payload + sensor.hostRandom(request, false))
        }).enableStreaming(uid)!!
        assertEquals("c1c2c3c4c5c6", grant.bleAddress.hex())
        assertArrayEquals(sensor.record, grant.provisioning.toBytes())
    }

    @Test
    fun nfcRepliesWithWrongEchoCrcOrSizeAreRejected() {
        val sensor = FakeSensor(1)
        val payload = ByteArray(9)
        val wrongHostEcho = nfc(tag(sensor) { byteArrayOf(0) + sensor.nfcReply(sensor.sensorRandom + payload + ByteArray(7)) })
        assertNull(wrongHostEcho.enableStreaming(uid))
        assertNotNull(wrongHostEcho.lastFailure)

        assertNull(nfc(tag(sensor) { request ->
            byteArrayOf(0) + sensor.nfcReply(ByteArray(7) + payload + sensor.hostRandom(request, false))
        }).enableStreaming(uid))

        assertNull(nfc(tag(sensor) { request ->
            val reply = sensor.nfcReply(sensor.sensorRandom + payload + sensor.hostRandom(request, false))
            reply[reply.size - 1] = (reply[reply.size - 1].toInt() xor 1).toByte()
            byteArrayOf(0) + reply
        }).enableStreaming(uid))

        assertNull(nfc(tag(sensor) { request ->
            byteArrayOf(0) + sensor.nfcReply(sensor.sensorRandom + payload + sensor.hostRandom(request, false)) + 0
        }).enableStreaming(uid))

        assertNull(nfc(tag(sensor) { byteArrayOf(1) }).enableStreaming(uid))
        assertNull(nfc(tag(sensor) { null }).enableStreaming(uid))
    }

    @Test
    fun nfcActivationNeedsAValidFiveByteReply() {
        val sensor = FakeSensor(1)
        assertTrue(nfc(tag(sensor) { request ->
            assertEquals(0x1b, request[3].toInt())
            byteArrayOf(0) + sensor.nfcReply(sensor.sensorRandom + ByteArray(5) + sensor.hostRandom(request, false))
        }).activate(uid))
        assertFalse(nfc(tag(sensor) { request ->
            byteArrayOf(0) + sensor.nfcReply(sensor.sensorRandom + ByteArray(9) + sensor.hostRandom(request, false))
        }).activate(uid))
    }

    @Test
    fun nfcRootSlotZeroUsesSlotOneAndUnknownSlotsStop() {
        val sensor = FakeSensor(1)
        val grant = nfc(tag(sensor, reportedSlot = 0) { request ->
            byteArrayOf(0) + sensor.nfcReply(sensor.sensorRandom + ByteArray(9) + sensor.hostRandom(request, false))
        }).enableStreaming(uid)!!
        assertEquals(0, grant.provisioning.storedRootSlot)

        var commandSent = false
        val refused = nfc(tag(sensor, reportedSlot = 3) { commandSent = true; null })
        assertNull(refused.enableStreaming(uid))
        assertFalse(commandSent)
        assertEquals("unsupported root slot 3", refused.lastFailure)
    }

    @Test
    fun nfcFramReadDecryptsBlocksAcrossRequests() {
        val sensor = FakeSensor(1)
        val fram = ByteArray(344) { (it * 7 + 1).toByte() }
        val requestedBlocks = mutableListOf<Pair<Int, Int>>()
        val driver = nfc(tag(sensor) { request ->
            when (request[3].toInt()) {
                0x1f -> byteArrayOf(0) + sensor.nfcReply(sensor.hostRandom(request, false) + sensor.sessionContext)
                0x21 -> {
                    val first = request[4].toInt() and 0xff
                    val count = (request[5].toInt() and 0xff) + 1
                    requestedBlocks += first to count
                    byteArrayOf(0) + ByteArray(8) +
                        sensor.framBlock(first, fram.copyOfRange(first * 8, (first + count) * 8))
                }
                else -> null
            }
        })
        assertArrayEquals(fram, driver.readFram(uid, 0, 344))
        assertEquals(15, requestedBlocks.size)
        assertEquals(0 to 3, requestedBlocks.first())
        assertEquals(42 to 1, requestedBlocks.last())
        assertArrayEquals(fram.copyOfRange(13, 30), driver.readFram(uid, 13, 17))
        assertNull(driver.readFram(uid, -1, 8))
    }

    @Test
    fun nfcFramReadStopsWhenAuthenticationOrABlockFails() {
        val sensor = FakeSensor(1)
        var blockReads = 0
        assertNull(nfc(tag(sensor) { request ->
            when (request[3].toInt()) {
                0x1f -> byteArrayOf(0) + sensor.nfcReply(ByteArray(7) + sensor.sessionContext)
                else -> { blockReads++; null }
            }
        }).readFram(uid, 0, 344))
        assertEquals(0, blockReads)

        assertNull(nfc(tag(sensor) { request ->
            when (request[3].toInt()) {
                0x1f -> byteArrayOf(0) + sensor.nfcReply(sensor.hostRandom(request, false) + sensor.sessionContext)
                else -> byteArrayOf(0) + ByteArray(10)
            }
        }).readFram(uid, 0, 344))
    }

    // ---- Gen1 (vendor-library fixtures: ident.h, info.h, get*Payload.h, abbotttest.cpp) ----

    private val gen1Uid = hex("eacc040200a407e0")
    private val gen1Info = hex("9d083001b531")

    @Test
    fun gen1PayloadsMatchVendorFixtures() {
        val enableTime = (1617377114L - Libre2Gen1Crypto.EPOCH_OFFSET_SECONDS).toInt()
        assertEquals("1e5afc2915639e4173", Libre2Gen1Crypto.enableStreamingPayload(gen1Uid, gen1Info, enableTime)!!.hex())
        assertEquals("1ba0144f2c", Libre2Gen1Crypto.activationPayload(gen1Uid)!!.hex())
        val unlockTime = (1617028422L - Libre2Gen1Crypto.EPOCH_OFFSET_SECONDS).toInt()
        assertEquals(
            "cebd241507cda9dfca0c314a",
            Libre2Gen1Crypto.streamingUnlockPayload(gen1Uid, gen1Info, unlockTime, 5000)!!.hex(),
        )
    }

    @Test
    fun gen1RejectsShortUid() {
        assertNull(Libre2Gen1Crypto.streamingUnlockPayload(ByteArray(5), gen1Info, 0, 0))
    }

    @Test
    fun gen1BleFrameDecryptsAndFailsClosed() {
        val frame = hex(
            "cf0470f68a71ba9aa4cafe6b1a78a83dd390e1633dabcf45481078ee474ccb546e378438e2d505cee8b1e7314b7d"
        )
        assertEquals(
            "4f44c00d6904c10947c4c10d4944c30940c4c30d4fc4c50925c4c7094504c4093544cd095604d7055e228a10",
            Libre2Gen1Crypto.decryptBle(gen1Uid, frame)!!.hex(),
        )
        frame[10] = (frame[10].toInt() xor 1).toByte()
        assertNull(Libre2Gen1Crypto.decryptBle(gen1Uid, frame))
        assertNull(Libre2Gen1Crypto.decryptBle(gen1Uid, frame.copyOf(45)))
    }
}
