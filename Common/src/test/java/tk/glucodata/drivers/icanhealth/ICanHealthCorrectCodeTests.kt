package tk.glucodata.drivers.icanhealth

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The 25-value correctCode table recovered from the vendor app.
 *
 * TEST-ONLY FIXTURE. It deliberately lives in the test source set: the real
 * table is per-sensor and only the vendor cloud has it, so nothing in a shipped
 * build may use this recovered table as a default or fallback. A bench session
 * pastes it into `ICanHealthExperimental.CORRECT_CODE_WRITE_TABLE_JSON` by hand
 * together with flipping `ENABLE_CORRECT_CODE_WRITE`.
 */
internal const val TEST_CORRECT_CODE_JSON =
    """{"x1":0.015,"x10":1.2,"x11":13.0,"x12":1.4,"x13":0.4,"x14":3.5,"x15":15.0,"x16":15.0,"x17":0.1,"x18":0.6,"x19":0.2,"x2":32.0,"x20":0.6,"x21":0.1,"x22":16.0,"x23":1.0,"x24":2.1,"x25":2.2,"x3":25.0,"x4":1.0,"x5":0.8,"x6":0.3,"x7":40.0,"x8":0.5,"x9":0.5}"""

/**
 * JVM tests for the debug-gated correctCode write path.
 *
 * Everything testable without hardware lives here: the vendor JSON -> float
 * table parse, the unverified key-input candidates, the framing function's
 * determinism and sizes, the MTU fit check, the batch transaction state machine,
 * and the F6 gate that must never write over an existing table or twice for the
 * same serial. The wire framing itself is unverified by definition and is
 * checked against a sensor's F6 read-back, not from here.
 */
class ICanHealthCorrectCodeTests {

    private val key = ByteArray(16) { (it + 1).toByte() }
    private val iv = ByteArray(16) { (it * 3 + 5).toByte() }
    private val table = requireNotNull(
        ICanHealthCorrectCode.parseCorrectCodeTable(TEST_CORRECT_CODE_JSON)
    )

    /** The 7-byte `06 F3 01 <challenge>` notification the driver now keeps whole. */
    private val f3Notification = byteArrayOf(0x06, 0xF3.toByte(), 0x01, 0x11, 0x22, 0x33, 0x44)

    private val challenge = f3Notification.copyOfRange(3, f3Notification.size)

    private fun buildChunks(
        tableArg: FloatArray = table,
        keyArg: ByteArray = key,
        ivArg: ByteArray = iv,
        path: CorrectCodeProvisioningPath = CorrectCodeProvisioningPath.SERVER_TABLE,
        launcherVersionFlag: Boolean = true,
        layout: CorrectCodeLayout = CorrectCodeLayout.CBC_SINGLE_BLOB,
    ): List<ByteArray>? = ICanHealthCorrectCode.buildCorrectCodeChunks(
        tableArg,
        keyArg,
        ivArg,
        path,
        launcherVersionFlag,
        layout,
    )

    private fun packets(
        path: CorrectCodeProvisioningPath = CorrectCodeProvisioningPath.SERVER_TABLE,
        launcherVersionFlag: Boolean = true,
        layout: CorrectCodeLayout = CorrectCodeLayout.CBC_SINGLE_BLOB,
    ): List<ByteArray> = requireNotNull(
        buildChunks(
            path = path,
            launcherVersionFlag = launcherVersionFlag,
            layout = layout,
        )
    )

    private fun payloads(packets: List<ByteArray>): ByteArray =
        packets.fold(ByteArray(0)) { acc, packet -> acc + packet.copyOfRange(2, packet.size) }

    private fun plan(
        f6Value: Int? = ICanHealthConstants.CORRECT_CODE_ABSENT_VALUE,
        isAuthenticated: Boolean = true,
        launcherState: Int = ICanHealthConstants.LAUNCHER_STATE_IDLE,
        writeAttempted: Boolean = false,
    ): CorrectCodePlan? = ICanHealthCorrectCode.planCorrectCodeWrite(
        f6Value = f6Value,
        isAuthenticated = isAuthenticated,
        launcherState = launcherState,
        writeAttempted = writeAttempted,
        table = table,
        key = key,
        iv = iv,
        path = CorrectCodeProvisioningPath.SERVER_TABLE,
        launcherVersionFlag = true,
        layout = CorrectCodeLayout.CBC_SINGLE_BLOB,
    )

    // ---- vendor JSON -> ordered float table ----

    @Test
    fun parseReadsTheRecoveredTestTableInOrder() {
        assertEquals(25, table.size)
        assertEquals(0.015f, table[0], 1e-6f)   // x1
        assertEquals(32.0f, table[1], 1e-6f)    // x2
        assertEquals(25.0f, table[2], 1e-6f)    // x3
        assertEquals(1.2f, table[9], 1e-6f)     // x10
        assertEquals(2.2f, table[24], 1e-6f)    // x25
    }

    @Test
    fun parseSupportsTheSnDerivedFifteenValueShape() {
        // A shape, not a writable path: the SN_DERIVED list is built inside the
        // vendor SDK and is never substituted here, but parsing its length keeps
        // the distinction explicit and testable.
        val fifteen = ICanHealthCorrectCode.parseCorrectCodeTable(
            TEST_CORRECT_CODE_JSON,
            ICanHealthCorrectCode.TABLE_VALUE_COUNT_SN_DERIVED,
        )
        assertNotNull(fifteen)
        assertEquals(15, fifteen!!.size)
        assertEquals(0.015f, fifteen[0], 1e-6f)
        assertEquals(15.0f, fifteen[14], 1e-6f) // x15
    }

    @Test
    fun parseRejectsMalformedPartialOrNonFiniteJson() {
        assertNull(ICanHealthCorrectCode.parseCorrectCodeTable("not json"))
        assertNull(ICanHealthCorrectCode.parseCorrectCodeTable("{}"))
        assertNull(
            ICanHealthCorrectCode.parseCorrectCodeTable(
                """{"x1":1.0,"x3":2.0}"""
            )
        )
        // A table with one key missing at the end must not be half-filled.
        val missingLast = (1..24).joinToString(",") { "\"x$it\":1.5" }
        assertNull(ICanHealthCorrectCode.parseCorrectCodeTable("{$missingLast}"))
        // A value that is not a number must be refused, not coerced.
        assertNull(
            ICanHealthCorrectCode.parseCorrectCodeTable(
                (1..25).joinToString(",", "{", "}") { "\"x$it\":${if (it == 7) "\"abc\"" else "1.5"}" }
            )
        )
        // NaN/Infinity (and a finite Double that overflows Float) are not a table.
        assertNull(
            ICanHealthCorrectCode.parseCorrectCodeTable(
                (1..25).joinToString(",", "{", "}") { "\"x$it\":${if (it == 3) "\"NaN\"" else "1.5"}" }
            )
        )
        assertNull(
            ICanHealthCorrectCode.parseCorrectCodeTable(
                (1..25).joinToString(",", "{", "}") { "\"x$it\":${if (it == 3) "\"Infinity\"" else "1.5"}" }
            )
        )
        assertNull(
            ICanHealthCorrectCode.parseCorrectCodeTable(
                (1..25).joinToString(",", "{", "}") { "\"x$it\":${if (it == 3) "1e300" else "1.5"}" }
            )
        )
    }

    // ---- F3 input -> 16-byte AES key candidate ----

    @Test
    fun keyDerivationZeroPadsTheFullNotificationCandidate() {
        val derived = requireNotNull(
            ICanHealthCorrectCode.buildCorrectCodeKey(
                f3Notification = f3Notification,
                authChallenge = challenge,
                derivation = CorrectCodeKeyDerivation.FULL_NOTIFICATION_ZERO_PAD,
            )
        )
        assertEquals(16, derived.size)
        assertArrayEquals(f3Notification, derived.copyOfRange(0, 7))
        assertArrayEquals(ByteArray(9), derived.copyOfRange(7, 16))
    }

    @Test
    fun keyDerivationZeroPadsTheChallengeCandidate() {
        val derived = requireNotNull(
            ICanHealthCorrectCode.buildCorrectCodeKey(
                f3Notification = f3Notification,
                authChallenge = challenge,
                derivation = CorrectCodeKeyDerivation.CHALLENGE_ZERO_PAD,
            )
        )
        assertEquals(16, derived.size)
        assertArrayEquals(challenge, derived.copyOfRange(0, 4))
        assertArrayEquals(ByteArray(12), derived.copyOfRange(4, 16))
    }

    @Test
    fun keyDerivationCandidatesDifferForTheSameAttempt() {
        val full = requireNotNull(
            ICanHealthCorrectCode.buildCorrectCodeKey(
                f3Notification,
                challenge,
                CorrectCodeKeyDerivation.FULL_NOTIFICATION_ZERO_PAD,
            )
        )
        val challengeOnly = requireNotNull(
            ICanHealthCorrectCode.buildCorrectCodeKey(
                f3Notification,
                challenge,
                CorrectCodeKeyDerivation.CHALLENGE_ZERO_PAD,
            )
        )
        assertFalse(full.contentEquals(challengeOnly))
    }

    @Test
    fun keyDerivationPassesSixteenByteInputsThroughUnchanged() {
        val payload = ByteArray(16) { (it * 5).toByte() }
        assertArrayEquals(
            payload,
            ICanHealthCorrectCode.buildCorrectCodeKey(
                payload,
                challenge,
                CorrectCodeKeyDerivation.FULL_NOTIFICATION_ZERO_PAD,
            ),
        )
    }

    @Test
    fun keyDerivationRejectsMissingInputs() {
        assertNull(
            ICanHealthCorrectCode.buildCorrectCodeKey(
                null,
                challenge,
                CorrectCodeKeyDerivation.FULL_NOTIFICATION_ZERO_PAD,
            )
        )
        assertNull(
            ICanHealthCorrectCode.buildCorrectCodeKey(
                ByteArray(0),
                challenge,
                CorrectCodeKeyDerivation.FULL_NOTIFICATION_ZERO_PAD,
            )
        )
        assertNull(
            ICanHealthCorrectCode.buildCorrectCodeKey(
                f3Notification,
                null,
                CorrectCodeKeyDerivation.CHALLENGE_ZERO_PAD,
            )
        )
        assertNull(
            ICanHealthCorrectCode.buildCorrectCodeKey(
                f3Notification,
                ByteArray(0),
                CorrectCodeKeyDerivation.CHALLENGE_ZERO_PAD,
            )
        )
    }

    // ---- framing function ----

    @Test
    fun singleBlobChunksAreDeterministic() {
        val first = packets()
        val second = packets()
        assertEquals(first.size, second.size)
        first.zip(second).forEach { (a, b) -> assertArrayEquals(a, b) }
    }

    @Test
    fun singleBlobChunksAreF2PacketsSizedForTheServerTable() {
        val chunks = packets()
        // 100 B plaintext -> 112 B ciphertext -> 5 x 20 B + 12 B.
        assertEquals(listOf(22, 22, 22, 22, 22, 14), chunks.map { it.size })
        chunks.forEach { packet ->
            assertEquals(ICanHealthConstants.CORRECT_CODE_CHUNK_OPCODE, packet[0])
            assertEquals(packet.size - 2, packet[1].toInt())
            assertTrue(packet.size <= 2 + ICanHealthCorrectCode.CHUNK_PAYLOAD_BYTES)
        }
    }

    @Test
    fun singleBlobRoundTripsThroughCbcPkcs7ToTheLittleEndianTable() {
        val decrypted = requireNotNull(
            ICanHealthCrypto.decryptCbcPkcs7(payloads(packets()), key, iv)
        )
        assertEquals(25 * 4, decrypted.size)
        assertEquals(0.015f, leFloat(decrypted, 0), 1e-6f)
        assertEquals(32.0f, leFloat(decrypted, 1), 1e-6f)
        assertEquals(2.2f, leFloat(decrypted, 24), 1e-6f)
    }

    @Test
    fun perChunkLayoutEncryptsEachSliceSeparately() {
        val chunks = packets(layout = CorrectCodeLayout.CBC_PER_CHUNK)
        // Five 20 B plaintext slices -> five 32 B ciphertexts (PKCS7 pads each).
        assertEquals(List(5) { 34 }, chunks.map { it.size })
        // Each packet decrypts on its own to its own slice of the table.
        chunks.forEachIndexed { index, packet ->
            val decrypted = requireNotNull(
                ICanHealthCrypto.decryptCbcPkcs7(packet.copyOfRange(2, packet.size), key, iv)
            )
            assertEquals(20, decrypted.size)
            assertEquals(table[index * 5], leFloat(decrypted, 0), 1e-6f)
        }
    }

    @Test
    fun launcherVersionFlagIsSeparateFromTheServerTableLength() {
        // The chunker's 3rd argument is kept as its own knob; in this model it does
        // not change the bytes, and it must never select the table length (that is
        // the provisioning path's business).
        val flagTrue = packets(launcherVersionFlag = true)
        val flagFalse = packets(launcherVersionFlag = false)
        assertEquals(flagTrue.size, flagFalse.size)
        flagTrue.zip(flagFalse).forEach { (a, b) -> assertArrayEquals(a, b) }
        val decrypted = requireNotNull(
            ICanHealthCrypto.decryptCbcPkcs7(payloads(flagFalse), key, iv)
        )
        assertEquals(ICanHealthCorrectCode.TABLE_VALUE_COUNT_SERVER_TABLE * 4, decrypted.size)
    }

    @Test
    fun snDerivedPathIsNeverSubstituted() {
        // Not implemented, and the framing function must refuse it loudly rather
        // than build a guessed serial-derived table.
        assertNull(
            buildChunks(path = CorrectCodeProvisioningPath.SN_DERIVED)
        )
    }

    @Test
    fun theSnDerivedFifteenValueShapeIsNotWritableAsAServerTable() {
        val fifteen = requireNotNull(
            ICanHealthCorrectCode.parseCorrectCodeTable(
                TEST_CORRECT_CODE_JSON,
                ICanHealthCorrectCode.TABLE_VALUE_COUNT_SN_DERIVED,
            )
        )
        assertNull(buildChunks(tableArg = fifteen))
    }

    @Test
    fun framingRejectsShortTablesAndBadKeyMaterial() {
        assertNull(buildChunks(tableArg = FloatArray(24)))
        assertNull(buildChunks(keyArg = ByteArray(15)))
        assertNull(buildChunks(ivArg = ByteArray(15)))
    }

    @Test
    fun commitPacketIsTheSingleF7Opcode() {
        assertArrayEquals(
            byteArrayOf(ICanHealthConstants.CORRECT_CODE_COMMIT_OPCODE),
            ICanHealthCorrectCode.buildCommitPacket(),
        )
    }

    // ---- MTU fit ----

    @Test
    fun planFitsMtuRefusesTheFallbackLinkAndAcceptsTheTargetLink() {
        val planned = requireNotNull(plan())
        // MTU 23 (negotiation refused): 22-byte F2 packets do not fit 23 - 3 = 20.
        assertFalse(ICanHealthCorrectCode.planFitsMtu(planned, 23))
        // MTU 517 (the driver's target): everything fits 514.
        assertTrue(ICanHealthCorrectCode.planFitsMtu(planned, 517))
        // MTU 25 is the smallest link where the default framing fits.
        assertTrue(ICanHealthCorrectCode.planFitsMtu(planned, 25))
        assertFalse(ICanHealthCorrectCode.planFitsMtu(planned, 24))
        // A degenerate link cannot carry even the F7 commit byte.
        assertFalse(ICanHealthCorrectCode.planFitsMtu(planned, 3))
    }

    @Test
    fun planFitsMtuChecksChunksAndCommitAtTheExactBoundary() {
        val exact = CorrectCodePlan(
            writePackets = listOf(ByteArray(20), ByteArray(20)),
            commitPacket = ByteArray(1),
            path = CorrectCodeProvisioningPath.SERVER_TABLE,
            launcherVersionFlag = true,
            layout = CorrectCodeLayout.CBC_SINGLE_BLOB,
        )
        assertTrue(ICanHealthCorrectCode.planFitsMtu(exact, 23))
        assertFalse(
            ICanHealthCorrectCode.planFitsMtu(
                exact.copy(writePackets = listOf(ByteArray(21))),
                23,
            )
        )
        assertFalse(ICanHealthCorrectCode.planFitsMtu(exact.copy(commitPacket = ByteArray(21)), 23))
    }

    // ---- batch transaction state machine ----

    @Test
    fun batchSendsTheCommitOnlyAfterEveryChunkAcked() {
        val batch = CorrectCodeBatch(chunkCount = 2)
        assertEquals(CorrectCodeBatch.Phase.CHUNKS, batch.phase)
        assertEquals(0, batch.chunkInFlight)
        assertEquals(CorrectCodeBatch.Next.SEND_CHUNK, batch.onChunkWriteResult(0))
        assertEquals(1, batch.chunkInFlight)
        assertEquals(CorrectCodeBatch.Next.SEND_COMMIT, batch.onChunkWriteResult(0))
        assertEquals(CorrectCodeBatch.Phase.COMMIT, batch.phase)
        assertEquals(2, batch.chunksAcked)
        assertEquals(CorrectCodeBatch.Next.SEND_READBACK, batch.onCommitWriteResult(0))
        assertTrue(batch.commitSent)
        assertEquals(0, batch.commitGattStatus)
        assertEquals(CorrectCodeBatch.Phase.READBACK, batch.phase)
        assertFalse(batch.readbackRequested)
        batch.onReadbackRequestWritten()
        assertTrue(batch.readbackRequested)
    }

    @Test
    fun batchAbortsOnTheFirstFailedChunkAndNeverSendsTheCommit() {
        val batch = CorrectCodeBatch(chunkCount = 6)
        assertEquals(CorrectCodeBatch.Next.SEND_CHUNK, batch.onChunkWriteResult(0)) // F2[0] ok
        assertEquals(CorrectCodeBatch.Next.SEND_READBACK, batch.onChunkWriteResult(133)) // F2[1] failed
        assertEquals(1, batch.abortedAtChunk)
        assertEquals(CorrectCodeBatch.Phase.READBACK, batch.phase)
        assertFalse(batch.commitSent)
        assertNull(batch.commitGattStatus)
        // Stray callbacks after the abort must stay inert: the F7 commit in
        // particular must never be sent behind a partially transmitted batch.
        assertEquals(CorrectCodeBatch.Next.NONE, batch.onCommitWriteResult(0))
        assertEquals(CorrectCodeBatch.Next.NONE, batch.onChunkWriteResult(0))
        assertTrue(batch.gattOutcomeSummary().startsWith("GATT_ABORTED after F2[1]"))
    }

    @Test
    fun batchCommitFailureStillRequestsTheReadbackStateRecord() {
        val batch = CorrectCodeBatch(chunkCount = 1)
        assertEquals(CorrectCodeBatch.Next.SEND_COMMIT, batch.onChunkWriteResult(0))
        assertEquals(CorrectCodeBatch.Next.SEND_READBACK, batch.onCommitWriteResult(137))
        assertTrue(batch.commitSent)
        assertEquals(137, batch.commitGattStatus)
        assertTrue(batch.gattOutcomeSummary().startsWith("GATT_COMMIT_FAILED status=137"))
    }

    @Test
    fun batchRequiresAtLeastOneChunk() {
        assertThrows(IllegalArgumentException::class.java) { CorrectCodeBatch(0) }
    }

    // ---- F6 parsing and the write gate ----

    @Test
    fun f6ValueDecodesLittleEndian() {
        assertEquals(
            0xFFFF,
            ICanHealthCorrectCode.parseF6Value(
                byteArrayOf(0x1C, 0xF6.toByte(), 0x01, 0xFF.toByte(), 0xFF.toByte())
            ),
        )
        assertEquals(
            500,
            ICanHealthCorrectCode.parseF6Value(
                byteArrayOf(0x1C, 0xF6.toByte(), 0x01, 0xF4.toByte(), 0x01)
            ),
        )
        assertEquals(
            600,
            ICanHealthCorrectCode.parseF6Value(
                byteArrayOf(0x1C, 0xF6.toByte(), 0x01, 0x58, 0x02)
            ),
        )
        // A real provisioned iCGM-t3 answers 1C F6 01 3A 02 = 570.
        assertEquals(
            570,
            ICanHealthCorrectCode.parseF6Value(
                byteArrayOf(0x1C, 0xF6.toByte(), 0x01, 0x3A, 0x02)
            ),
        )
    }

    @Test
    fun f6ValueRejectsNonF6AndShortAnswers() {
        assertNull(ICanHealthCorrectCode.parseF6Value(ByteArray(0)))
        assertNull(
            ICanHealthCorrectCode.parseF6Value(
                byteArrayOf(0x1C, 0xF4.toByte(), 0x01, 0x00, 0x00)
            )
        )
        assertNull(
            ICanHealthCorrectCode.parseF6Value(byteArrayOf(0x1C, 0xF6.toByte(), 0x01, 0xFF.toByte()))
        )
    }

    @Test
    fun gateNeverWritesWhenTheSensorAlreadyReportsATable() {
        for (f6Value in listOf(500, 510, 530, 570, 600)) {
            assertNull("f6=0x${"%04X".format(f6Value)} must not be written", plan(f6Value = f6Value))
        }
    }

    @Test
    fun gateAllowsTheWriteOnlyWhenAbsentAuthenticatedIdleAndUnattempted() {
        val allowed = plan()
        assertNotNull(allowed)
        assertEquals(6, allowed!!.writePackets.size)
        assertArrayEquals(
            byteArrayOf(ICanHealthConstants.CORRECT_CODE_COMMIT_OPCODE),
            allowed.commitPacket,
        )
        assertEquals(CorrectCodeProvisioningPath.SERVER_TABLE, allowed.path)
        assertTrue(allowed.launcherVersionFlag)
        assertEquals(CorrectCodeLayout.CBC_SINGLE_BLOB, allowed.layout)

        assertNull(plan(isAuthenticated = false))
        assertNull(plan(launcherState = ICanHealthConstants.LAUNCHER_STATE_WARMUP))
        assertNull(plan(launcherState = ICanHealthConstants.LAUNCHER_STATE_RUNNING))
        assertNull(plan(launcherState = ICanHealthConstants.LAUNCHER_STATE_ENDED))
        assertNull(plan(launcherState = -1))
        assertNull(plan(f6Value = null))
    }

    @Test
    fun gateRefusesOnceTheOneShotRecordExists() {
        assertNull(plan(writeAttempted = true))
        assertNotNull(
            ICanHealthCorrectCode.gateSkipReason(
                f6Value = ICanHealthConstants.CORRECT_CODE_ABSENT_VALUE,
                isAuthenticated = true,
                launcherState = ICanHealthConstants.LAUNCHER_STATE_IDLE,
                writeAttempted = true,
            )
        )
        // The record is the first refusal after the F6 table check: even an
        // unauthenticated sensor with the record set reports the one-shot gate.
        assertNotNull(
            ICanHealthCorrectCode.gateSkipReason(
                f6Value = ICanHealthConstants.CORRECT_CODE_ABSENT_VALUE,
                isAuthenticated = false,
                launcherState = ICanHealthConstants.LAUNCHER_STATE_IDLE,
                writeAttempted = true,
            )
        )
    }

    @Test
    fun planRefusesWithoutTableKeyOrIv() {
        fun planWith(
            tableArg: FloatArray?,
            keyArg: ByteArray?,
            ivArg: ByteArray?,
        ): CorrectCodePlan? = ICanHealthCorrectCode.planCorrectCodeWrite(
            f6Value = ICanHealthConstants.CORRECT_CODE_ABSENT_VALUE,
            isAuthenticated = true,
            launcherState = ICanHealthConstants.LAUNCHER_STATE_IDLE,
            writeAttempted = false,
            table = tableArg,
            key = keyArg,
            iv = ivArg,
            path = CorrectCodeProvisioningPath.SERVER_TABLE,
            launcherVersionFlag = true,
            layout = CorrectCodeLayout.CBC_SINGLE_BLOB,
        )

        assertNull(planWith(null, key, iv))
        assertNull(planWith(table, null, iv))
        assertNull(planWith(table, key, null))
    }

    @Test
    fun shippedDefaultsKeepTheUnverifiedPathOff() {
        // This is not a bug to fix in passing: enabling the correctCode write in
        // a shipped build pushes an unverified write into every user's sensor.
        // Flipping the flag is a deliberate field decision, pinned here.
        assertFalse(ICanHealthExperimental.ENABLE_CORRECT_CODE_WRITE)
        // And even with the flag flipped there is no table to write: the recovered
        // fixture is test-only, never a shipped default or fallback.
        assertNull(ICanHealthExperimental.CORRECT_CODE_WRITE_TABLE_JSON)
        assertEquals(
            CorrectCodeProvisioningPath.SERVER_TABLE,
            ICanHealthExperimental.CORRECT_CODE_PROVISIONING_PATH,
        )
        assertEquals(
            CorrectCodeKeyDerivation.FULL_NOTIFICATION_ZERO_PAD,
            ICanHealthExperimental.CORRECT_CODE_WRITE_KEY_DERIVATION,
        )
    }

    private fun leFloat(bytes: ByteArray, floatIndex: Int): Float {
        val offset = floatIndex * 4
        val bits = (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)
        return Float.fromBits(bits)
    }
}
