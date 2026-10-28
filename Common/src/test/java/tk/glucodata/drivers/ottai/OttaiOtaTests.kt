package tk.glucodata.drivers.ottai

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.zip.CRC32
import org.junit.Assert.*
import org.junit.Test

internal fun testGbl(payloadSize: Int = 600): ByteArray {
    // Independent GBL v3 fixture: header, program data, end with CRC over preceding bytes.
    val out = ByteArrayOutputStream()
    fun word(value: Long) { out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value.toInt()).array()) }
    word(0x03a617eb); word(8); word(0x03000000); word(0)
    word(0xfe0101fe); word(payloadSize.toLong())
    out.write(ByteArray(payloadSize) { it.toByte() })
    word(0xfc0404fc); word(4)
    word(CRC32().apply { update(out.toByteArray()) }.value)
    return out.toByteArray()
}

internal const val TEST_OTTAI_COMPOSITE_REVISION = "E1.1.4(V1.7.S2530.1),STDRD"

class OttaiOtaTests {
    @Test fun gblAcceptsBoundedEnvelopeAndErasedTail() {
        val bytes = testGbl()
        assertEquals(bytes.size, OttaiOtaPackage.parse(bytes).size)
        assertEquals(bytes.size + 4, OttaiOtaPackage.parse(bytes + ByteArray(4) { 0xff.toByte() }).size)
        bytes[30] = (bytes[30].toInt() xor 1).toByte()
        assertThrows(IllegalArgumentException::class.java) { OttaiOtaPackage.parse(bytes) }
    }

    @Test fun truncatedOverflowMissingEndAndGarbageAreRejected() {
        val valid = testGbl()
        listOf(valid.copyOf(15), valid.copyOf(valid.size - 1), valid + byteArrayOf(0),
            valid.copyOf().also { it[20] = 0xff.toByte(); it[21] = 0xff.toByte(); it[22] = 0xff.toByte(); it[23] = 0x7f },
        ).forEach { assertThrows(IllegalArgumentException::class.java) { OttaiOtaPackage.parse(it) } }
    }

    @Test fun mtuBoundHoldsAcrossAndroidNegotiationRange() {
        for (mtu in 23..517) {
            val chunk = OttaiOtaProtocol.chunkSize(mtu)
            assertTrue(chunk in 1..244 && chunk <= mtu - 3 && chunk % 4 == 0)
        }
        assertEquals(244, OttaiOtaProtocol.chunkSize(247))
        assertEquals(244, OttaiOtaProtocol.chunkSize(256))
        assertEquals(20, OttaiOtaProtocol.chunkSize(23))
    }

    @Test fun offsetsAdvanceOnlyOnAcknowledgementsAndReconstructTheExactFile() {
        val image = OttaiOtaPackage.parse(testGbl())
        val transfer = OttaiOtaTransfer(image, 247)
        val bytes = ByteArrayOutputStream()
        while (!transfer.complete) {
            val before = transfer.acknowledgedBytes
            val chunk = transfer.nextChunk()
            assertEquals(before, transfer.acknowledgedBytes)
            assertThrows(IllegalStateException::class.java) { transfer.nextChunk() }
            bytes.write(chunk)
            transfer.acknowledge()
            assertEquals(before + chunk.size, transfer.acknowledgedBytes)
            assertThrows(IllegalStateException::class.java) { transfer.acknowledge() }
        }
        assertArrayEquals(image.bytes(), bytes.toByteArray())
    }

    @Test fun provisioningAcceptsOmittedMetadataButRejectsConflictingBuildsAndVariants() {
        assertTrue(OttaiOtaProtocol.materialsVersionMatches(TEST_OTTAI_COMPOSITE_REVISION, "E1.1.4(V1.7.S2530.1)"))
        assertTrue(OttaiOtaProtocol.materialsVersionMatches(TEST_OTTAI_COMPOSITE_REVISION, "V1.7.S2530.1"))
        assertTrue(OttaiOtaProtocol.materialsVersionMatches(TEST_OTTAI_COMPOSITE_REVISION, "V1.7"))
        assertFalse(OttaiOtaProtocol.materialsVersionMatches(TEST_OTTAI_COMPOSITE_REVISION, "V1.7.S2530.2"))
        assertFalse(OttaiOtaProtocol.materialsVersionMatches(TEST_OTTAI_COMPOSITE_REVISION, "E1.1.4(V1.7.S2530.1),OTHER"))
        assertFalse(OttaiOtaProtocol.materialsVersionMatches(TEST_OTTAI_COMPOSITE_REVISION, "E1.1.5(V1.7.S2530.1)"))
        assertFalse(OttaiOtaProtocol.materialsVersionMatches(TEST_OTTAI_COMPOSITE_REVISION, "V1.8"))
    }

    @Test fun coefficientPacketMatchesIndependentModbusVector() {
        // 09 02 + LE float(1) + LE float(2). Verify the full packet with a bit-at-a-time oracle.
        val packet = OttaiOtaProtocol.coefficientPacket("1,2")
        assertArrayEquals(byteArrayOf(9, 2, 0, 0, 0x80.toByte(), 0x3f, 0, 0, 0, 0x40), packet.copyOf(10))
        var crc = 0xffff
        for (byte in packet) {
            for (bit in 0..7) {
                val low = (crc xor (byte.toInt() ushr bit)) and 1
                crc = crc ushr 1
                if (low != 0) crc = crc xor 0xa001
            }
        }
        assertEquals(0, crc)
        listOf("", "1,,2", "NaN", "Infinity", "1e1000").forEach {
            assertThrows(IllegalArgumentException::class.java) { OttaiOtaProtocol.coefficientPacket(it) }
        }
    }

    @Test fun calibrationMissingIsNotReplacedWithInventedDefaults() {
        val key = "00112233445566778899aabbccddeeff"
        assertThrows(IllegalArgumentException::class.java) { OttaiOtaProtocol.calibrationPacket(null, 0.0, key) }
        assertThrows(IllegalArgumentException::class.java) { OttaiOtaProtocol.calibrationPacket(1.0, Double.NaN, key) }
        val packet = OttaiOtaProtocol.calibrationPacket(1.0, -2.0, key)
        assertEquals(17, packet.size)
        assertEquals(11.toByte(), packet[0])
        val cipher = javax.crypto.Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(javax.crypto.Cipher.DECRYPT_MODE, javax.crypto.spec.SecretKeySpec(OttaiCrypto.hexToBytes(key), "AES"))
        assertArrayEquals(ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).putDouble(1.0).putDouble(-2.0).array(),
            cipher.doFinal(packet.copyOfRange(1, 17)))
    }

    @Test fun revisionSyntaxDoesNotWhitelistFirmwareFamiliesOrRequireAPrefixOrPatch() {
        listOf("V1.2.0", "V1.9.0", "V2.0.0", "V1.5", "1.5.0", "v1.5.0", "V1.5.0_CN",
            "V1.5.0-beta", "1", "1.5.0+build.2").forEach {
            assertEquals(it, OttaiOtaProtocol.version(" \u0000$it\u0000 \u0000"))
        }
        listOf(TEST_OTTAI_COMPOSITE_REVISION, "vE1.2.3(V1.7.SH2542.1)", "future-vendor-revision").forEach {
            assertEquals(it, OttaiOtaProtocol.version(it))
        }
        listOf("", "V1.5\n.0", "V1.5\u0000.0", "1".repeat(513)).forEach {
            assertNull(OttaiOtaProtocol.version(it))
        }
    }

    @Test fun upgradeOrderingHandlesVariantsFutureVersionsAndLargeNumericComponents() {
        assertTrue(OttaiOtaProtocol.isUpgrade("v1.2", "1.3.0_CN"))
        assertTrue(OttaiOtaProtocol.isUpgrade("V1.9.0", "V1.10.0"))
        assertTrue(OttaiOtaProtocol.isUpgrade("V1.99", "V2.0"))
        assertTrue(OttaiOtaProtocol.isUpgrade("V1.2147483648", "V1.2147483649"))
        assertFalse(OttaiOtaProtocol.isUpgrade("1.10", "V1.9.99"))
        assertTrue(OttaiOtaProtocol.isUpgrade("v1.8", "V1.8.0_CN"))
    }

    @Test fun readbackComparisonNormalizesNumericSpellingButRetainsBuildIdentity() {
        assertTrue(OttaiOtaProtocol.sameVersion("v1.8", "V1.8.0"))
        assertTrue(OttaiOtaProtocol.sameVersion("1.08.0_CN", "V1.8_CN"))
        assertFalse(OttaiOtaProtocol.sameVersion("V1.8_CN", "V1.8_EU"))
        assertFalse(OttaiOtaProtocol.sameVersion("V1.8", "V1.8.1"))
        assertEquals(9, OttaiOtaProtocol.minor("1.9"))
        assertEquals(-1, OttaiOtaProtocol.minor("V2.8"))
    }

    @Test fun compositeRevisionsUseTheEmbeddedApplicationProfileWithoutLosingRawIdentity() {
        assertEquals(7, OttaiOtaProtocol.minor(TEST_OTTAI_COMPOSITE_REVISION))
        assertEquals(7, OttaiOtaProtocol.minor("vE1.2.3(V1.7.SH2542.1)"))
        assertTrue(OttaiOtaProtocol.isUpgrade(TEST_OTTAI_COMPOSITE_REVISION, "V1.8.0"))
        assertTrue(OttaiOtaProtocol.isUpgrade(TEST_OTTAI_COMPOSITE_REVISION, "V1.7.S2600.1"))
        assertFalse(OttaiOtaProtocol.isUpgrade(TEST_OTTAI_COMPOSITE_REVISION, "V1.7.S2530.1"))
        assertFalse(OttaiOtaProtocol.isUpgrade(TEST_OTTAI_COMPOSITE_REVISION, "V1.6.0"))
        assertFalse(OttaiOtaProtocol.sameVersion(TEST_OTTAI_COMPOSITE_REVISION, "V1.7"))
        assertFalse(OttaiOtaProtocol.sameVersion(TEST_OTTAI_COMPOSITE_REVISION,
            "E1.1.4(V1.7.S2530.1),OTHER"))
        assertTrue(OttaiOtaProtocol.matchesTarget(TEST_OTTAI_COMPOSITE_REVISION, "V1.7"))
        assertTrue(OttaiOtaProtocol.matchesTarget(TEST_OTTAI_COMPOSITE_REVISION, "V1.7.S2530.1"))
        assertFalse(OttaiOtaProtocol.matchesTarget(TEST_OTTAI_COMPOSITE_REVISION, "V1.7.S2531.1"))
        assertFalse(OttaiOtaProtocol.matchesTarget(TEST_OTTAI_COMPOSITE_REVISION, "V1.8"))
        assertFalse(OttaiOtaProtocol.matchesTarget("V1.7", TEST_OTTAI_COMPOSITE_REVISION))
    }

    @Test fun unfamiliarPrintableSourceRevisionsCanUseTheCloudOffer() {
        assertEquals(-1, OttaiOtaProtocol.minor("future-vendor-revision"))
        assertTrue(OttaiOtaProtocol.isUpgrade("future-vendor-revision", "V1.8.0"))
        assertTrue(OttaiOtaProtocol.sameVersion("future-vendor-revision", "future-vendor-revision"))
        assertFalse(OttaiOtaProtocol.matchesTarget("future-vendor-revision", "V1.8.0"))
    }

    @Test fun onlyHttpsAndVersionedGblNamesAreAccepted() {
        assertTrue(OttaiOtaProtocol.isUpgrade("V1.7.9", "V1.8.0"))
        assertTrue(OttaiOtaProtocol.isUpgrade("V1.7.9", "V1.7.10"))
        assertFalse(OttaiOtaProtocol.isUpgrade("V1.8.0", "V1.7.9"))
        assertFalse(OttaiOtaProtocol.isUpgrade("V1.8.0", "V1.8.0.0"))
        assertEquals("V1.8.2", OttaiOtaProtocol.targetVersion("https://cdn.example/V1.8.2.GBL?token=private"))
        assertEquals("V1.9.0", OttaiOtaProtocol.targetVersion("https://cdn.example/V1.9.0.gbl"))
        assertEquals("2.0_CN", OttaiOtaProtocol.targetVersion("https://cdn.example/2.0_CN.gbl"))
        assertEquals("E1.2.0(V1.8.S2600.1),STDRD",
            OttaiOtaProtocol.targetVersion("https://cdn.example/E1.2.0%28V1.8.S2600.1%29%2CSTDRD.gbl"))
        listOf("http://cdn.example/V1.8.2.gbl", "https://user:pass@cdn.example/V1.8.2.gbl",
            "https://cdn.example/random.gbl", "https://cdn.example/V1..8.gbl").forEach {
            assertThrows(IllegalArgumentException::class.java) { OttaiOtaProtocol.targetVersion(it) }
        }
    }
}

class OttaiFirmwareControllerTests {
    private val materials = OttaiRegistry.DeviceMaterials("00".repeat(96), "V0", "1,2", 0, "V1.7.0", 1)
    private val image = OttaiFirmwareImage("V1.7.0", "V1.8.0", OttaiOtaPackage.parse(testGbl()))

    private class FakeIo(val image: OttaiFirmwareImage) : OttaiFirmwareIo {
        val writes = mutableListOf<Pair<UUID, ByteArray>>()
        val scheduled = ArrayDeque<() -> Unit>()
        val logs = mutableListOf<String>()
        var connected: ((Result<OttaiFirmwareGatt>) -> Unit)? = null
        var ack: ((Result<Unit>) -> Unit)? = null
        var saved = false
        var closed = false
        var closedSuccessfully = false
        var readAnswer: ((UUID) -> ByteArray)? = null
        var fresh: OttaiRegistry.DeviceMaterials? = null
        var refresh: ((Result<OttaiRegistry.DeviceMaterials>) -> Unit)? = null
        var reported = false
        var cleared = false
        var journal: OttaiFirmwareImage? = null
        var journalFailure: String? = null
        var onWrite: (UUID, ByteArray) -> Unit = { _, _ -> }
        var commandStatus = 0
        var durationSeconds = 28L * 86_400
        var dataNo = 720
        var optionalUnavailable = false
        var offerFailure: String? = null
        var offerRequests = 0
        val offerVersions = mutableListOf<String>()
        override fun connect(done: (Result<OttaiFirmwareGatt>) -> Unit) { connected = done }
        override fun rediscover(done: (Result<OttaiFirmwareGatt>) -> Unit) {
            done(Result.success(OttaiFirmwareGatt(247, bootloaderOnly = false, hasOta = true)))
        }
        override fun read(service: UUID, characteristic: UUID, done: (Result<ByteArray>) -> Unit) {
            done(Result.success(requireNotNull(readAnswer)(characteristic)))
        }
        override fun readOptional(service: UUID, characteristic: UUID, done: (Result<ByteArray>) -> Unit) {
            if (optionalUnavailable) done(Result.failure(IllegalStateException("not_supported")))
            else read(service, characteristic, done)
        }
        override fun write(service: UUID, characteristic: UUID, value: ByteArray, done: (Result<Unit>) -> Unit) {
            check(ack == null)
            writes += characteristic to value.copyOf()
            onWrite(characteristic, value)
            ack = done
        }
        fun acknowledge(result: Result<Unit> = Result.success(Unit)) {
            val callback = requireNotNull(ack)
            ack = null
            callback(result)
        }
        override fun after(delayMs: Long, action: () -> Unit) { scheduled += action }
        fun tick() { scheduled.removeFirst().invoke() }
        override fun prepareImage(version: String, done: (Result<OttaiFirmwareImage?>) -> Unit) {
            offerRequests++
            offerVersions += version
            done(offerFailure?.let { Result.failure(IllegalStateException(it)) } ?: Result.success(image))
        }
        override fun refreshMaterials(version: String, done: (Result<OttaiRegistry.DeviceMaterials>) -> Unit) {
            refresh = done
            fresh?.let { done(Result.success(it)) }
        }
        override fun savedImage(): OttaiFirmwareImage? {
            journalFailure?.let { error(it) }
            return journal
        }
        override fun markTransferStarted(image: OttaiFirmwareImage) { saved = true; journal = image }
        override fun reportVerified(image: OttaiFirmwareImage) { reported = true }
        override fun clearRecovery() { cleared = true; journal = null }
        override fun log(message: String) { logs += message }
        override fun disconnect() {}
        override fun close(completed: Boolean) { closed = true; closedSuccessfully = completed }
    }

    private fun normal(version: String = "V1.7.0", command: Int = 0, badSignature: Boolean = false,
        mode: OttaiFirmwareMode = OttaiFirmwareMode.PREACTIVATION,
        target: String = "V1.8.1",
        revisionBytes: ByteArray? = null,
        configure: (FakeIo) -> Unit = {}): Pair<OttaiFirmwareController, FakeIo> {
        val io = FakeIo(image.copy(source = version, target = target))
        io.commandStatus = command
        configure(io)
        val device = OttaiBleAuth.generateKeyPair()
        val (x, y) = OttaiBleAuth.publicCoords(device.public as java.security.interfaces.ECPublicKey)
        val time = byteArrayOf(1, 2, 3)
        var session = ""
        io.onWrite = { ch, value ->
            if (ch == OttaiConstants.CHAR_AUTH_APP_PARAM) {
                session = requireNotNull(OttaiBleAuth.deriveSessionKey(
                    OttaiCrypto.bytesToHex(value.copyOfRange(4, 36)), OttaiCrypto.bytesToHex(value.copyOfRange(36, 68)),
                    device.private as java.security.interfaces.ECPrivateKey))
            }
        }
        io.readAnswer = { ch ->
            when (ch) {
                OttaiConstants.CHAR_SOFTWARE_VERSION -> revisionBytes ?: version.toByteArray()
                OttaiConstants.CHAR_CURRENT_TIME -> byteArrayOf(1, 2, 3, 4)
                OttaiConstants.CHAR_AUTH_DEVICE_PARAM -> byteArrayOf(0) + time + x + y
                OttaiConstants.CHAR_AUTH_SIGN -> if (badSignature) ByteArray(32) else
                    OttaiBleAuth.authSign(ByteArray(16), OttaiCrypto.hexToBytes("AABBCCDDEEFF"), x, y, time)
                OttaiConstants.CHAR_COMMAND -> byteArrayOf(io.commandStatus.toByte())
                OttaiConstants.CHAR_MAX_ACTIVE_TIME -> requireNotNull(OttaiCrypto.encryptPayload(
                    ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(io.durationSeconds).array(), session))
                OttaiConstants.CHAR_GLUCOSE_LIVE -> {
                    val frame = ByteArray(16)
                    frame[4] = io.dataNo.toByte(); frame[5] = (io.dataNo ushr 8).toByte()
                    val runtime = io.dataNo * 60
                    frame[8] = 100; frame[9] = (runtime ushr 16).toByte()
                    frame[10] = runtime.toByte(); frame[11] = (runtime ushr 8).toByte()
                    frame[12] = 50; frame[14] = 0x80.toByte(); frame[15] = 0x0c
                    requireNotNull(OttaiCrypto.encryptPayload(frame, session))
                }
                else -> error("unexpected characteristic")
            }
        }
        val controller = OttaiFirmwareController("AABBCCDDEEFF", materials.copy(deviceVersion = version), io, mode) {}
        controller.start()
        io.connected!!(Result.success(OttaiFirmwareGatt(247, bootloaderOnly = false, hasOta = true)))
        if (!badSignature && controller.state.stage == OttaiFirmwareStage.AUTHENTICATING) repeat(2) { io.tick(); io.acknowledge() }
        return controller to io
    }

    @Test fun rejectedRevisionIsVisibleWithoutAllowingWritesOrCloudRequests() {
        val io = FakeIo(image)
        io.readAnswer = { ch ->
            assertEquals(OttaiConstants.CHAR_SOFTWARE_VERSION, ch)
            "rel\u0001ease\u0000".toByteArray()
        }
        val controller = OttaiFirmwareController("AABBCCDDEEFF", materials, io) {}
        controller.start()
        io.connected!!(Result.success(OttaiFirmwareGatt(247, bootloaderOnly = false, hasOta = true)))
        assertEquals(OttaiFirmwareStage.FAILED, controller.state.stage)
        assertEquals("firmware_profile", controller.state.errorCode)
        assertEquals("rel?ease", controller.state.firmware)
        assertTrue(io.logs.contains("software_revision length=9 hex=72656c016561736500"))
        assertTrue(io.closed)
        assertTrue(io.writes.isEmpty())
        assertEquals(0, io.offerRequests)
    }

    @Test fun malformedRevisionDiagnosticsAreBoundedAndCannotInjectLogLines() {
        val bytes = byteArrayOf(10, 0xff.toByte()) + ByteArray(100) { 'A'.code.toByte() }
        val io = FakeIo(image).also { it.readAnswer = { bytes } }
        val controller = OttaiFirmwareController("AABBCCDDEEFF", materials, io) {}
        controller.start()
        io.connected!!(Result.success(OttaiFirmwareGatt(247, bootloaderOnly = false, hasOta = true)))
        assertEquals(OttaiFirmwareStage.FAILED, controller.state.stage)
        assertEquals("??" + "A".repeat(62), controller.state.firmware)
        assertTrue(io.logs.contains("software_revision length=102 hex=0aff" + "41".repeat(62)))
        assertTrue(io.logs.none { '\n' in it || '\r' in it })
        assertTrue(io.writes.isEmpty())
        assertEquals(0, io.offerRequests)
    }

    @Test fun exactDeviceTraceRevisionReachesTheCloudOfferWithBothNullPaddingBytesRemoved() {
        val bytes = OttaiCrypto.hexToBytes("45312e312e342856312e372e53323533302e31292c53544452440000")
        val (controller, io) = normal(version = TEST_OTTAI_COMPOSITE_REVISION, command = 3,
            mode = OttaiFirmwareMode.STARTED_SENSOR, revisionBytes = bytes)
        assertEquals(OttaiFirmwareStage.READY, controller.state.stage)
        assertEquals(TEST_OTTAI_COMPOSITE_REVISION, controller.state.firmware)
        assertEquals(listOf(TEST_OTTAI_COMPOSITE_REVISION), io.offerVersions)
        assertEquals(2, io.writes.size)
        assertFalse(io.saved)
    }

    @Test fun exactGlobalTraceRevisionStopsAtTheNullTerminatorBeforeTheCloudOffer() {
        val bytes = OttaiCrypto.hexToBytes("45312e322e362856312e372e5348323631362e31292c53544452440047")
        val revision = "E1.2.6(V1.7.SH2616.1),STDRD"
        val (controller, io) = normal(version = revision, command = 3,
            mode = OttaiFirmwareMode.STARTED_SENSOR, revisionBytes = bytes)
        assertEquals(OttaiFirmwareStage.READY, controller.state.stage)
        assertEquals(revision, controller.state.firmware)
        assertEquals(listOf(revision), io.offerVersions)
        assertTrue(io.logs.contains("software_revision length=29 hex=45312e322e362856312e372e5348323631362e31292c53544452440047"))
        assertEquals(2, io.writes.size) // Authentication only; installation still requires confirmation.
        assertFalse(io.saved)
    }

    @Test fun terminatedRevisionWithBufferTailCanVerifyTheInstalledApplicationBuild() {
        val (controller, io) = normal(version = "E1.2.6(V1.7.SH2616.1),STDRD", command = 3,
            mode = OttaiFirmwareMode.STARTED_SENSOR, target = "V1.8.SH2700.1")
        controller.install()
        while (controller.state.stage == OttaiFirmwareStage.INSTALLING) { io.tick(); io.acknowledge() }
        val answer = requireNotNull(io.readAnswer)
        val readback = "E1.2.7(V1.8.SH2700.1),STDRD"
        io.readAnswer = { ch -> if (ch == OttaiConstants.CHAR_SOFTWARE_VERSION)
            readback.toByteArray() + byteArrayOf(0, 'G'.code.toByte()) else answer(ch) }
        io.tick()
        io.connected!!(Result.success(OttaiFirmwareGatt(247, bootloaderOnly = false, hasOta = true)))
        repeat(2) { io.tick(); io.acknowledge() }
        assertEquals(OttaiFirmwareStage.COMPLETE, controller.state.stage)
        assertEquals(readback, controller.state.firmware)
        assertTrue(io.reported)
        assertTrue(io.cleared)
    }

    @Test fun emptyOrMalformedPrefixCannotUseBytesAfterTheNullTerminatorAsARevision() {
        listOf("\u0000V1.7", "V1.\u00017\u0000G").forEach { raw ->
            val (controller, io) = normal(command = 3, mode = OttaiFirmwareMode.STARTED_SENSOR,
                revisionBytes = raw.toByteArray())
            assertEquals(OttaiFirmwareStage.FAILED, controller.state.stage)
            assertEquals("firmware_profile", controller.state.errorCode)
            assertEquals(0, io.offerRequests)
            assertTrue(io.writes.isEmpty())
            assertFalse(io.saved)
        }
    }

    @Test fun compositeSourceRecoveryPreservesTheFullRevisionWithoutAnotherOffer() {
        val (controller, io) = normal(version = TEST_OTTAI_COMPOSITE_REVISION) {
            it.journal = image.copy(source = TEST_OTTAI_COMPOSITE_REVISION)
        }
        assertEquals(OttaiFirmwareStage.READY, controller.state.stage)
        assertTrue(controller.state.recovery)
        assertEquals(0, io.offerRequests)
    }

    @Test fun compositeRevisionFactoryPreparationUsesTheEmbeddedV1Profile() {
        val (controller, io) = normal(version = TEST_OTTAI_COMPOSITE_REVISION)
        io.fresh = materials.copy(deviceVersion = TEST_OTTAI_COMPOSITE_REVISION)
        controller.prepareOnly()
        io.tick(); io.acknowledge()
        assertEquals(OttaiFirmwareStage.COMPLETE, controller.state.stage)
        assertEquals(listOf(9.toByte()), io.writes.filter { it.first == OttaiConstants.CHAR_DESTRUCTIVE }.map { it.second[0] })
    }

    @Test fun compositeReadbackVerifiesTheOfferedApplicationProfileAfterTransfer() {
        val (controller, io) = normal(version = TEST_OTTAI_COMPOSITE_REVISION, command = 3,
            mode = OttaiFirmwareMode.STARTED_SENSOR, target = "V1.8.0")
        controller.install()
        while (controller.state.stage == OttaiFirmwareStage.INSTALLING) { io.tick(); io.acknowledge() }
        val answer = requireNotNull(io.readAnswer)
        val readback = "E1.2.0(V1.8.S2600.1),STDRD"
        io.readAnswer = { ch -> if (ch == OttaiConstants.CHAR_SOFTWARE_VERSION) readback.toByteArray() else answer(ch) }
        io.tick()
        io.connected!!(Result.success(OttaiFirmwareGatt(247, bootloaderOnly = false, hasOta = true)))
        repeat(2) { io.tick(); io.acknowledge() }
        assertEquals(OttaiFirmwareStage.COMPLETE, controller.state.stage)
        assertEquals(readback, controller.state.firmware)
        assertTrue(io.reported)
        assertTrue(io.cleared)
    }

    @Test fun unchangedCompositeReadbackCannotVerifyANewerOfferedProfile() {
        val (controller, io) = normal(version = TEST_OTTAI_COMPOSITE_REVISION, command = 3,
            mode = OttaiFirmwareMode.STARTED_SENSOR, target = "V1.8.0")
        controller.install()
        while (controller.state.stage == OttaiFirmwareStage.INSTALLING) { io.tick(); io.acknowledge() }
        io.tick()
        io.connected!!(Result.success(OttaiFirmwareGatt(247, bootloaderOnly = false, hasOta = true)))
        assertEquals(OttaiFirmwareStage.FAILED, controller.state.stage)
        assertEquals("version_readback_mismatch", controller.state.errorCode)
        assertFalse(io.reported)
        assertFalse(io.cleared)
    }

    @Test fun formerlyRejectedRevisionsReachTheCloudOfferAndReadyState() {
        listOf("V1.2.0", "V1.9.0", "V2.0.0", "V1.5", "1.5.0", "v1.5.0", "V1.5.0_CN",
            "future-vendor-revision").forEach { version ->
            val (controller, io) = normal(version = version, command = 3,
                mode = OttaiFirmwareMode.STARTED_SENSOR, target = "V2.1.0")
            assertEquals(version, controller.state.firmware)
            assertEquals(OttaiFirmwareStage.READY, controller.state.stage)
            assertEquals(1, io.offerRequests)
            assertEquals(version, io.image.source)
            assertEquals(2, io.writes.size)
        }
    }

    @Test fun recoveryAcceptsEquivalentSourceRevisionSpellingWithoutDownloadingAgain() {
        val (controller, io) = normal(version = "1.7") { it.journal = image }
        assertEquals(OttaiFirmwareStage.READY, controller.state.stage)
        assertTrue(controller.state.recovery)
        assertEquals(0, io.offerRequests)
    }

    @Test fun unreadableJournalCannotBecomeAFreshOfferInTheNormalApplication() {
        val (controller, io) = normal { it.journalFailure = "recovery_image" }
        controller.install()
        controller.prepareOnly()
        assertEquals(OttaiFirmwareStage.FAILED, controller.state.stage)
        assertEquals("recovery_image", controller.state.errorCode)
        assertEquals(0, io.offerRequests)
        assertTrue(io.writes.isEmpty())
        assertFalse(io.saved)
        assertFalse(io.cleared)
    }

    @Test fun savedPackageCannotBeReplacedWhenTheRunningRevisionMatchesNeitherSourceNorTarget() {
        val (controller, io) = normal(version = "V1.9.0") { it.journal = image }
        controller.install()
        assertEquals(OttaiFirmwareStage.FAILED, controller.state.stage)
        assertEquals("recovery_version", controller.state.errorCode)
        assertEquals(0, io.offerRequests)
        assertTrue(io.writes.isEmpty())
        assertSame(image, io.journal)
        assertFalse(io.saved)
    }

    @Test fun verifiedTargetRecoveryAcceptsEquivalentReadbackSpelling() {
        val (controller, io) = normal(version = "v1.8", command = 3, mode = OttaiFirmwareMode.STARTED_SENSOR) {
            it.journal = image.copy(mode = OttaiFirmwareMode.STARTED_SENSOR)
        }
        assertEquals(OttaiFirmwareStage.COMPLETE, controller.state.stage)
        assertFalse(io.reported) // Reopening verifies, but does not resend an advisory report.
        assertTrue(io.cleared)
        assertEquals(0, io.offerRequests)
        assertEquals(2, io.writes.size)
    }

    @Test fun installedFirmwareCanReadBackWithoutTheFilenamePrefix() {
        val (controller, io) = normal(command = 3, mode = OttaiFirmwareMode.STARTED_SENSOR)
        controller.install()
        while (controller.state.stage == OttaiFirmwareStage.INSTALLING) { io.tick(); io.acknowledge() }
        val answer = requireNotNull(io.readAnswer)
        io.readAnswer = { ch -> if (ch == OttaiConstants.CHAR_SOFTWARE_VERSION) "1.8.1".toByteArray() else answer(ch) }
        io.tick()
        io.connected!!(Result.success(OttaiFirmwareGatt(247, bootloaderOnly = false, hasOta = true)))
        repeat(2) { io.tick(); io.acknowledge() }
        assertEquals(OttaiFirmwareStage.COMPLETE, controller.state.stage)
        assertEquals("1.8.1", controller.state.firmware)
        assertTrue(io.reported)
        assertTrue(io.cleared)
    }

    @Test fun anotherMajorRevisionDoesNotInheritV1FactoryWrites() {
        val (controller, io) = normal(version = "V2.0", target = "V2.1")
        assertEquals(OttaiFirmwareStage.READY, controller.state.stage)
        controller.prepareOnly()
        assertEquals(OttaiFirmwareStage.FAILED, controller.state.stage)
        assertEquals("factory_parameter_profile", controller.state.errorCode)
        assertNull(io.refresh)
        assertEquals(2, io.writes.size)
    }

    @Test fun freshInstallRejectsAnUnsupportedPreparationTargetBeforeJournalingOrOtaWrites() {
        for (target in listOf("V2.0", "E2.0(V2.1.S2600.1),STDRD")) {
            val (controller, io) = normal(target = target)
            assertEquals(OttaiFirmwareStage.READY, controller.state.stage)
            controller.install()
            assertEquals(OttaiFirmwareStage.FAILED, controller.state.stage)
            assertEquals("factory_parameter_profile", controller.state.errorCode)
            assertFalse(io.saved)
            assertNull(io.journal)
            assertNull(io.refresh)
            assertEquals(2, io.writes.size) // Authentication only.
            assertTrue(io.scheduled.isEmpty())
        }
    }

    @Test fun freshRecoveryCannotBypassTheTargetPreparationCheck() {
        for (bootloader in listOf(false, true)) {
            val saved = image.copy(target = "V2.0")
            val (controller, io) = if (bootloader) {
                val io = FakeIo(saved).also { it.journal = saved }
                val controller = OttaiFirmwareController("AABBCCDDEEFF", materials, io) {}
                controller.start()
                io.connected!!(Result.success(OttaiFirmwareGatt(247, bootloaderOnly = true, hasOta = true)))
                controller to io
            } else normal { it.journal = saved }
            val writesBeforeInstall = io.writes.size
            controller.install()
            assertEquals(OttaiFirmwareStage.FAILED, controller.state.stage)
            assertEquals("factory_parameter_profile", controller.state.errorCode)
            assertFalse(io.saved)
            assertEquals(saved, io.journal) // An existing journal must not be deleted or replaced.
            assertEquals(writesBeforeInstall, io.writes.size)
            assertTrue(io.scheduled.isEmpty())
        }
    }

    @Test fun startedModeCanInstallAnotherMajorWithoutFreshPreparation() {
        val (controller, io) = normal(command = 3, mode = OttaiFirmwareMode.STARTED_SENSOR, target = "V2.0")
        controller.install()
        assertTrue(io.saved)
        while (controller.state.stage == OttaiFirmwareStage.INSTALLING) { io.tick(); io.acknowledge() }
        val previous = requireNotNull(io.readAnswer)
        io.readAnswer = { ch -> if (ch == OttaiConstants.CHAR_SOFTWARE_VERSION) "V2.0".toByteArray() else previous(ch) }
        io.tick()
        io.connected!!(Result.success(OttaiFirmwareGatt(247, bootloaderOnly = false, hasOta = true)))
        repeat(2) { io.tick(); io.acknowledge() }
        assertEquals(OttaiFirmwareStage.COMPLETE, controller.state.stage)
        assertTrue(io.reported)
        assertTrue(io.cleared)
        assertNull(io.refresh)
        assertTrue(io.writes.none { it.first == OttaiConstants.CHAR_DESTRUCTIVE })
    }

    @Test fun normalCheckStopsBeforeAnyFirmwareOrActivationWrite() {
        val (controller, io) = normal()
        assertEquals(OttaiFirmwareStage.READY, controller.state.stage)
        assertEquals(listOf(OttaiConstants.CHAR_AUTH_APP_PARAM, OttaiConstants.CHAR_AUTH_SIGN), io.writes.map { it.first })
        assertFalse(io.saved)
        assertFalse(io.reported)
        assertFalse(io.cleared)
    }

    @Test fun oldApplicationRecoveryReusesSavedPackageWhenFirmwareHostIsUnavailable() {
        val (controller, io) = normal {
            it.journal = image
            it.offerFailure = "download_status"
        }
        assertEquals(OttaiFirmwareStage.READY, controller.state.stage)
        assertEquals(0, io.offerRequests)
        assertTrue(controller.state.recovery)
        assertTrue(controller.state.canPrepareParameters)
        assertEquals(image.image.sha256, controller.state.sha256)
        io.fresh = materials
        controller.prepareOnly()
        io.tick(); io.acknowledge()
        assertEquals(OttaiFirmwareStage.COMPLETE, controller.state.stage)
        assertTrue(io.cleared)
        assertFalse(io.writes.any { it.first == OttaiConstants.CHAR_OTA_CONTROL || it.first == OttaiConstants.CHAR_OTA_DATA })
    }

    @Test fun authenticatedParameterPreparationSurvivesDownloadFailure() {
        val (controller, io) = normal { it.offerFailure = "download_status" }
        assertEquals(OttaiFirmwareStage.NO_UPDATE, controller.state.stage)
        assertEquals("download_status", controller.state.errorCode)
        assertTrue(controller.state.canPrepareParameters)
        io.fresh = materials
        controller.prepareOnly()
        io.tick(); io.acknowledge()
        assertEquals(OttaiFirmwareStage.COMPLETE, controller.state.stage)
        assertTrue(io.cleared)
    }

    @Test fun firmwareDownloadFailureDoesNotEnableProvisioningForStartedSensors() {
        val (controller, io) = normal(command = 3, mode = OttaiFirmwareMode.STARTED_SENSOR) {
            it.offerFailure = "download_status"
        }
        assertEquals(OttaiFirmwareStage.FAILED, controller.state.stage)
        assertFalse(controller.state.canPrepareParameters)
        controller.prepareOnly()
        assertNull(io.refresh)
    }

    @Test fun failedParameterWriteLeavesTheRecoveryJournalBlockingActivation() {
        val (controller, io) = normal { it.journal = image }
        io.fresh = materials
        controller.prepareOnly()
        io.tick()
        io.acknowledge(Result.failure(IllegalStateException("att_128")))
        assertEquals(OttaiFirmwareStage.FAILED, controller.state.stage)
        assertFalse(io.cleared)
        assertNotNull(io.journal)
    }

    @Test fun bootloaderRecoveryStillCannotWriteFactoryParameters() {
        val (controller, io) = recovery()
        assertFalse(controller.state.canPrepareParameters)
        controller.prepareOnly()
        assertNull(io.refresh)
        assertTrue(io.writes.isEmpty())
        assertEquals(OttaiFirmwareStage.READY, controller.state.stage)
    }

    @Test fun startedModeChecksAndRecordsBaselineWithoutWritingParameters() {
        val (controller, io) = normal(command = 3, mode = OttaiFirmwareMode.STARTED_SENSOR)
        assertEquals(OttaiFirmwareStage.READY, controller.state.stage)
        assertEquals(3, controller.state.before?.commandStatus)
        assertEquals(28L * 86_400, controller.state.before?.maxActiveSeconds)
        assertEquals(720, controller.state.before?.latestDataNo)
        assertNotNull(controller.state.before?.rtcSeconds)
        controller.prepareOnly()
        assertEquals(OttaiFirmwareStage.READY, controller.state.stage)
        assertNull(io.refresh)
        assertEquals(2, io.writes.size)
    }

    @Test fun startedModeAcceptsEveryKnownPostUpdateStateAndNeverRestoresOrReactivates() {
        for (status in 0..4) {
            val (controller, io) = normal(command = 3, mode = OttaiFirmwareMode.STARTED_SENSOR)
            controller.install()
            assertEquals(OttaiFirmwareMode.STARTED_SENSOR, io.journal?.mode)
            assertEquals(controller.state.before, io.journal?.before)
            while (controller.state.stage == OttaiFirmwareStage.INSTALLING) { io.tick(); io.acknowledge() }
            val oldAnswer = requireNotNull(io.readAnswer)
            io.readAnswer = { ch -> if (ch == OttaiConstants.CHAR_SOFTWARE_VERSION) "V1.8.1".toByteArray() else oldAnswer(ch) }
            io.commandStatus = status
            io.durationSeconds = if (status == 3) 28L * 86_400 else 0
            io.dataNo = if (status == 3) 725 else 0
            io.tick()
            io.connected!!(Result.success(OttaiFirmwareGatt(247, bootloaderOnly = false, hasOta = true)))
            repeat(2) { io.tick(); io.acknowledge() }
            assertEquals(OttaiFirmwareStage.COMPLETE, controller.state.stage)
            assertEquals(status, controller.state.after?.commandStatus)
            assertEquals(io.durationSeconds, controller.state.after?.maxActiveSeconds)
            assertEquals(io.dataNo, controller.state.after?.latestDataNo)
            assertEquals(720, controller.state.before?.latestDataNo)
            assertTrue(io.reported && io.cleared)
            assertTrue(io.closedSuccessfully)
            assertNull(io.refresh)
            assertFalse(io.writes.any { it.first in setOf(OttaiConstants.CHAR_COMMAND,
                OttaiConstants.CHAR_CURRENT_TIME, OttaiConstants.CHAR_MAX_ACTIVE_TIME, OttaiConstants.CHAR_DESTRUCTIVE) })
        }
    }

    @Test fun optionalObservationFailuresDoNotPreventStartedUpdateVerification() {
        val (controller, io) = normal(command = 3, mode = OttaiFirmwareMode.STARTED_SENSOR)
        controller.install()
        while (controller.state.stage == OttaiFirmwareStage.INSTALLING) { io.tick(); io.acknowledge() }
        val oldAnswer = requireNotNull(io.readAnswer)
        io.readAnswer = { ch -> if (ch == OttaiConstants.CHAR_SOFTWARE_VERSION) "V1.8.1".toByteArray() else oldAnswer(ch) }
        io.optionalUnavailable = true
        io.tick()
        io.connected!!(Result.success(OttaiFirmwareGatt(247, bootloaderOnly = false, hasOta = true)))
        repeat(2) { io.tick(); io.acknowledge() }
        assertEquals(OttaiFirmwareStage.COMPLETE, controller.state.stage)
        assertNull(controller.state.after?.maxActiveSeconds)
        assertNull(controller.state.after?.latestDataNo)
    }

    @Test fun startedRecoveryRecognizesAlreadyBootedTargetAndRetainsOriginalBaseline() {
        val (controller, io) = normal(command = 3, mode = OttaiFirmwareMode.STARTED_SENSOR)
        controller.install()
        while (controller.state.stage == OttaiFirmwareStage.INSTALLING) { io.tick(); io.acknowledge() }
        controller.close()
        io.scheduled.clear()
        val oldAnswer = requireNotNull(io.readAnswer)
        io.readAnswer = { ch -> if (ch == OttaiConstants.CHAR_SOFTWARE_VERSION) "V1.8.1".toByteArray() else oldAnswer(ch) }
        io.commandStatus = 0
        val transfers = io.writes.count { it.first == OttaiConstants.CHAR_OTA_CONTROL }
        val resumed = OttaiFirmwareController("AABBCCDDEEFF", materials, io, OttaiFirmwareMode.STARTED_SENSOR) {}
        resumed.start()
        io.connected!!(Result.success(OttaiFirmwareGatt(247, bootloaderOnly = false, hasOta = true)))
        repeat(2) { io.tick(); io.acknowledge() }
        assertEquals(OttaiFirmwareStage.COMPLETE, resumed.state.stage)
        assertEquals(3, resumed.state.before?.commandStatus)
        assertEquals(0, resumed.state.after?.commandStatus)
        assertEquals(transfers, io.writes.count { it.first == OttaiConstants.CHAR_OTA_CONTROL })
    }

    @Test fun startedModeRefusesFreshEndedAndUnknownEntryStates() {
        for (status in listOf(0, 1, 2, 4, 255)) {
            val (controller, _) = normal(command = status, mode = OttaiFirmwareMode.STARTED_SENSOR)
            assertEquals(OttaiFirmwareStage.FAILED, controller.state.stage)
            assertEquals("started_sensor_status", controller.state.errorCode)
        }
    }

    @Test fun recoveryCannotSilentlyChangeExperimentMode() {
        val io = FakeIo(image)
        io.journal = image.copy(mode = OttaiFirmwareMode.STARTED_SENSOR,
            before = OttaiFirmwareObservation(3, 28L * 86_400, 720, 100))
        val controller = OttaiFirmwareController("AABBCCDDEEFF", materials, io) {}
        controller.start()
        io.connected!!(Result.success(OttaiFirmwareGatt(247, bootloaderOnly = true, hasOta = true)))
        assertEquals(OttaiFirmwareStage.FAILED, controller.state.stage)
        assertEquals("recovery_mode", controller.state.errorCode)
        assertTrue(io.writes.isEmpty())
    }

    @Test fun activeEndedAndWrongIdentitySensorsCannotReachTheOfferOrFlash() {
        for (command in listOf(3, 4, 255)) {
            val (controller, io) = normal(command = command)
            assertEquals(OttaiFirmwareStage.FAILED, controller.state.stage)
            assertEquals("sensor_already_active_or_ended", controller.state.errorCode)
            assertEquals(2, io.writes.size)
        }
        val (controller, io) = normal(badSignature = true)
        assertEquals(OttaiFirmwareStage.FAILED, controller.state.stage)
        assertTrue(io.writes.isEmpty())
    }

    @Test fun v17PreparationWritesCoefficientsAndNeverLifetimeOrActivation() {
        val (controller, io) = normal()
        io.fresh = materials
        controller.prepareOnly()
        io.tick(); io.acknowledge()
        assertEquals(OttaiFirmwareStage.COMPLETE, controller.state.stage)
        assertEquals(3, io.writes.size)
        assertEquals(OttaiConstants.CHAR_DESTRUCTIVE, io.writes.last().first)
        assertEquals(9.toByte(), io.writes.last().second[0])
        assertTrue(io.cleared)
        assertFalse(io.reported)
    }

    @Test fun reopenedFreshTargetWaitsForExplicitPreparationAndNeverResendsReport() {
        repeat(2) {
            val (controller, io) = normal(version = "V1.8.0") { it.journal = image }
            assertEquals(OttaiFirmwareStage.PREPARATION_READY, controller.state.stage)
            assertFalse(io.reported)
            assertFalse(io.cleared)
            assertNull(io.refresh)
            assertEquals(2, io.writes.size)
            assertTrue(io.scheduled.isEmpty())
            if (it == 0) controller.close()
            else {
                io.fresh = materials.copy(deviceVersion = "V1.8", calibrationValueK = 1.0, calibrationValueB = 0.0)
                controller.prepareOnly()
                repeat(2) { io.tick(); io.acknowledge() }
                assertEquals(OttaiFirmwareStage.COMPLETE, controller.state.stage)
                assertTrue(io.cleared)
            }
        }
    }

    @Test fun failedPreparationCanBeDiscardedWithoutLateWritesOrJournalRecreation() {
        val (controller, io) = normal { it.journal = image }
        controller.prepareOnly()
        val late = requireNotNull(io.refresh)
        assertFalse(controller.discardRecovery()) // A factory write transaction is still owned.
        controller.fail("materials_refresh")
        assertTrue(controller.discardRecovery())
        assertTrue(io.cleared)
        assertNull(io.journal)
        late(Result.success(materials))
        controller.install()
        assertEquals(2, io.writes.size)
        assertTrue(io.scheduled.isEmpty())
    }

    @Test fun failedIdentityAndMissingCalibrationRecoveryCanBeDiscarded() {
        val (identity, first) = normal(version = "V1.8.0", badSignature = true) { it.journal = image }
        assertEquals(OttaiFirmwareStage.FAILED, identity.state.stage)
        assertTrue(identity.discardRecovery())
        assertTrue(first.cleared)
        assertTrue(first.writes.isEmpty())
        val (calibration, second) = normal(version = "V1.8.0") {
            it.journal = image
            it.fresh = materials.copy(deviceVersion = "V1.8")
        }
        calibration.prepareOnly()
        assertEquals("calibration_missing", calibration.state.errorCode)
        assertTrue(calibration.discardRecovery())
        assertTrue(second.cleared)
        assertEquals(2, second.writes.size)
    }

    @Test fun transferCannotBeDiscardedUntilItStops() {
        val (controller, io) = recovery()
        controller.install()
        assertFalse(controller.discardRecovery())
        assertNotNull(io.journal)
        io.tick()
        io.acknowledge(Result.failure(IllegalStateException("att_128")))
        assertTrue(controller.discardRecovery())
        assertNull(io.journal)
        assertEquals(1, io.writes.size)
    }

    @Test fun missingV18CalibrationStopsBeforeEitherFactoryWrite() {
        val (controller, io) = normal(version = "V1.8.0")
        io.fresh = materials.copy(deviceVersion = "V1.8.0")
        controller.prepareOnly()
        assertEquals(OttaiFirmwareStage.FAILED, controller.state.stage)
        assertEquals("calibration_missing", controller.state.errorCode)
        assertEquals(2, io.writes.size)
        assertFalse(io.cleared)
    }

    @Test fun staleMaterialsAndLateRefreshCannotWriteFactoryParameters() {
        val (controller, io) = normal()
        controller.prepareOnly()
        controller.close()
        io.refresh!!(Result.success(materials))
        assertTrue(io.scheduled.isEmpty())
        assertEquals(2, io.writes.size)
        val (another, second) = normal()
        second.fresh = materials.copy(deviceVersion = "V1.6.0")
        another.prepareOnly()
        assertEquals(OttaiFirmwareStage.FAILED, another.state.stage)
        assertEquals(2, second.writes.size)
    }

    @Test fun verifiedRebootRequiresFreshMaterialsAndBothParameterAcknowledgements() {
        val (controller, io) = normal()
        controller.install()
        while (controller.state.stage == OttaiFirmwareStage.INSTALLING) { io.tick(); io.acknowledge() }
        assertFalse(io.reported)
        val answer = requireNotNull(io.readAnswer)
        io.readAnswer = { ch -> if (ch == OttaiConstants.CHAR_SOFTWARE_VERSION) "V1.8.1".toByteArray() else answer(ch) }
        io.fresh = materials.copy(deviceVersion = "V1.8.1", calibrationValueK = 1.0, calibrationValueB = 0.0)
        io.tick()
        io.connected!!(Result.success(OttaiFirmwareGatt(247, bootloaderOnly = false, hasOta = true)))
        repeat(2) { io.tick(); io.acknowledge() }
        assertTrue(io.reported)
        assertFalse(io.cleared)
        assertEquals(OttaiFirmwareStage.PREPARATION_READY, controller.state.stage)
        assertNull(io.refresh)
        assertFalse(io.writes.any { it.first == OttaiConstants.CHAR_DESTRUCTIVE })
        controller.prepareOnly() // A second, explicit confirmation is required after verification.
        assertEquals(OttaiFirmwareStage.PREPARING, controller.state.stage)
        io.tick(); io.acknowledge()
        assertFalse(io.cleared)
        io.tick(); io.acknowledge()
        assertTrue(io.cleared)
        assertEquals(OttaiFirmwareStage.COMPLETE, controller.state.stage)
        val factory = io.writes.filter { it.first == OttaiConstants.CHAR_DESTRUCTIVE }
        assertEquals(listOf(9.toByte(), 11.toByte()), factory.map { it.second[0] })
        assertFalse(io.writes.any { it.first in setOf(OttaiConstants.CHAR_COMMAND,
            OttaiConstants.CHAR_CURRENT_TIME, OttaiConstants.CHAR_MAX_ACTIVE_TIME) })
    }

    @Test fun duplicateAckDoesNotQueueTwoChunks() {
        val (controller, io) = recovery()
        controller.install(); io.tick()
        val callback = requireNotNull(io.ack)
        io.acknowledge()
        callback(Result.success(Unit))
        assertEquals(1, io.scheduled.size)
    }

    private fun recovery(): Pair<OttaiFirmwareController, FakeIo> {
        val io = FakeIo(image)
        io.journal = image
        val controller = OttaiFirmwareController("AABBCCDDEEFF", materials, io) {}
        controller.start()
        io.connected!!(Result.success(OttaiFirmwareGatt(247, bootloaderOnly = true, hasOta = true)))
        assertEquals(OttaiFirmwareStage.READY, controller.state.stage)
        assertTrue(io.writes.isEmpty())
        return controller to io
    }

    @Test fun explicitRecoverySendsStartExactBytesFinishAndRequiresVersionReadback() {
        val (controller, io) = recovery()
        controller.install()
        assertTrue(io.saved)
        io.tick()
        assertArrayEquals(byteArrayOf(0), io.writes.last().second)
        io.acknowledge()
        while (controller.state.stage == OttaiFirmwareStage.INSTALLING) {
            io.tick()
            io.acknowledge()
        }
        assertEquals(OttaiFirmwareStage.RECONNECTING, controller.state.stage)
        assertEquals(OttaiConstants.CHAR_OTA_CONTROL, io.writes.last().first)
        assertArrayEquals(byteArrayOf(6), io.writes.last().second)
        assertArrayEquals(image.image.bytes(), io.writes.filter { it.first == OttaiConstants.CHAR_OTA_DATA }
            .fold(byteArrayOf()) { bytes, write -> bytes + write.second })
        io.tick()
        io.connected!!(Result.success(OttaiFirmwareGatt(247, bootloaderOnly = true, hasOta = true)))
        assertEquals(OttaiFirmwareStage.FAILED, controller.state.stage)
        assertEquals("still_in_bootloader", controller.state.errorCode)
    }

    @Test fun rejectedWriteNeverAdvancesOrRetriesOrActivates() {
        val (controller, io) = recovery()
        controller.install(); io.tick(); io.acknowledge()
        io.tick()
        io.acknowledge(Result.failure(IllegalStateException("att_128")))
        assertEquals(OttaiFirmwareStage.FAILED, controller.state.stage)
        assertEquals(0, controller.state.acknowledgedBytes)
        assertEquals(2, io.writes.size)
        assertTrue(io.scheduled.isEmpty())
        assertTrue(io.closed)
        assertFalse(io.closedSuccessfully)
        controller.install()
        assertEquals(2, io.writes.size)
    }

    @Test fun cancellationAndLateSuccessCannotStartOrContinueTransfer() {
        val (controller, io) = recovery()
        controller.install(); io.tick()
        controller.close()
        io.acknowledge()
        assertTrue(io.scheduled.isEmpty())
        assertEquals(1, io.writes.size)
        assertEquals(OttaiFirmwareStage.CLOSED, controller.state.stage)
    }

    @Test fun lateConnectionAndScheduledStartAreIgnoredAfterRetirement() {
        val (controller, io) = recovery()
        controller.install()
        controller.fail("disconnected")
        io.tick()
        io.connected!!(Result.success(OttaiFirmwareGatt(247, bootloaderOnly = true, hasOta = true)))
        assertTrue(io.writes.isEmpty())
        assertEquals(OttaiFirmwareStage.FAILED, controller.state.stage)
    }
}
