package tk.glucodata.drivers.icanhealth

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the debug-gated correctCode write path.
 *
 * Everything testable without hardware lives here: the vendor JSON -> float
 * table parse, the key derivation, the framing function's determinism and
 * sizes, and the F6 gate that must never write over an existing table. The
 * wire framing itself is unverified by definition and is checked against a
 * sensor's F6 read-back, not from here.
 */
class ICanHealthCorrectCodeTests {

    private val key = ByteArray(16) { (it + 1).toByte() }
    private val iv = ByteArray(16) { (it * 3 + 5).toByte() }
    private val table = requireNotNull(
        ICanHealthCorrectCode.parseCorrectCodeTable(ICanHealthCorrectCode.TEST_CORRECT_CODE_JSON)
    )

    private fun chunks(
        versionFlag: Boolean = true,
        layout: CorrectCodeLayout = CorrectCodeLayout.CBC_SINGLE_BLOB,
    ): List<ByteArray> = requireNotNull(
        ICanHealthCorrectCode.buildCorrectCodeChunks(table, key, iv, versionFlag, layout)
    )

    private fun payloads(packets: List<ByteArray>): ByteArray =
        packets.fold(ByteArray(0)) { acc, packet -> acc + packet.copyOfRange(2, packet.size) }

    private fun plan(
        f6Value: Int? = ICanHealthConstants.CORRECT_CODE_ABSENT_VALUE,
        isAuthenticated: Boolean = true,
        launcherState: Int = ICanHealthConstants.LAUNCHER_STATE_IDLE,
        attemptsUsed: Int = 0,
    ): CorrectCodePlan? = ICanHealthCorrectCode.planCorrectCodeWrite(
        f6Value = f6Value,
        isAuthenticated = isAuthenticated,
        launcherState = launcherState,
        attemptsUsed = attemptsUsed,
        table = table,
        key = key,
        iv = iv,
        versionFlag = true,
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
    fun parseSupportsTheFifteenValueBranch() {
        val fifteen = ICanHealthCorrectCode.parseCorrectCodeTable(
            ICanHealthCorrectCode.TEST_CORRECT_CODE_JSON,
            ICanHealthCorrectCode.TABLE_VALUE_COUNT_I3H3,
        )
        assertNotNull(fifteen)
        assertEquals(15, fifteen!!.size)
        assertEquals(0.015f, fifteen[0], 1e-6f)
        assertEquals(15.0f, fifteen[14], 1e-6f) // x15
    }

    @Test
    fun parseRejectsMalformedOrPartialJson() {
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
    }

    // ---- F3 payload -> AES key ----

    @Test
    fun keyDerivationPadsShortF3PayloadsToSixteenBytes() {
        val derived = ICanHealthCorrectCode.buildCorrectCodeKey(byteArrayOf(1, 2, 3, 4))
        assertNotNull(derived)
        assertEquals(16, derived!!.size)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), derived.copyOfRange(0, 4))
        assertArrayEquals(ByteArray(12), derived.copyOfRange(4, 16))
    }

    @Test
    fun keyDerivationPassesSixteenBytePayloadsThrough() {
        val payload = ByteArray(16) { (it * 5).toByte() }
        assertArrayEquals(payload, ICanHealthCorrectCode.buildCorrectCodeKey(payload))
    }

    @Test
    fun keyDerivationRejectsMissingPayload() {
        assertNull(ICanHealthCorrectCode.buildCorrectCodeKey(null))
        assertNull(ICanHealthCorrectCode.buildCorrectCodeKey(ByteArray(0)))
    }

    // ---- framing function ----

    @Test
    fun singleBlobChunksAreDeterministic() {
        val first = chunks()
        val second = chunks()
        assertEquals(first.size, second.size)
        first.zip(second).forEach { (a, b) -> assertArrayEquals(a, b) }
    }

    @Test
    fun singleBlobChunksAreMtuSafeF2Packets() {
        val packets = chunks()
        // 100 B plaintext -> 112 B ciphertext -> 5 x 20 B + 12 B.
        assertEquals(listOf(22, 22, 22, 22, 22, 14), packets.map { it.size })
        packets.forEach { packet ->
            assertEquals(ICanHealthConstants.CORRECT_CODE_CHUNK_OPCODE, packet[0])
            assertEquals(packet.size - 2, packet[1].toInt())
            assertTrue(packet.size <= 2 + ICanHealthCorrectCode.CHUNK_PAYLOAD_BYTES)
        }
    }

    @Test
    fun singleBlobRoundTripsThroughCbcPkcs7ToTheLittleEndianTable() {
        val decrypted = requireNotNull(
            ICanHealthCrypto.decryptCbcPkcs7(payloads(chunks()), key, iv)
        )
        assertEquals(25 * 4, decrypted.size)
        assertEquals(0.015f, leFloat(decrypted, 0), 1e-6f)
        assertEquals(32.0f, leFloat(decrypted, 1), 1e-6f)
        assertEquals(2.2f, leFloat(decrypted, 24), 1e-6f)
    }

    @Test
    fun fifteenValueBranchSerialisesOnlyTheFirstFifteenValues() {
        val packets = chunks(versionFlag = false)
        // 60 B plaintext -> 64 B ciphertext -> 3 x 20 B + 4 B.
        assertEquals(listOf(22, 22, 22, 6), packets.map { it.size })
        val decrypted = requireNotNull(
            ICanHealthCrypto.decryptCbcPkcs7(payloads(packets), key, iv)
        )
        assertEquals(15 * 4, decrypted.size)
        assertEquals(15.0f, leFloat(decrypted, 14), 1e-6f) // x15
    }

    @Test
    fun perChunkLayoutEncryptsEachSliceSeparately() {
        val packets = chunks(layout = CorrectCodeLayout.CBC_PER_CHUNK)
        // Five 20 B plaintext slices -> five 32 B ciphertexts (PKCS7 pads each).
        assertEquals(List(5) { 34 }, packets.map { it.size })
        // Each packet decrypts on its own to its own slice of the table.
        packets.forEachIndexed { index, packet ->
            val decrypted = requireNotNull(
                ICanHealthCrypto.decryptCbcPkcs7(packet.copyOfRange(2, packet.size), key, iv)
            )
            assertEquals(20, decrypted.size)
            assertEquals(table[index * 5], leFloat(decrypted, 0), 1e-6f)
        }
    }

    @Test
    fun framingRejectsShortTablesAndBadKeyMaterial() {
        assertNull(
            ICanHealthCorrectCode.buildCorrectCodeChunks(
                FloatArray(24),
                key,
                iv,
                versionFlag = true,
                layout = CorrectCodeLayout.CBC_SINGLE_BLOB,
            )
        )
        assertNull(
            ICanHealthCorrectCode.buildCorrectCodeChunks(
                table,
                ByteArray(15),
                iv,
                versionFlag = true,
                layout = CorrectCodeLayout.CBC_SINGLE_BLOB,
            )
        )
        assertNull(
            ICanHealthCorrectCode.buildCorrectCodeChunks(
                table,
                key,
                ByteArray(15),
                versionFlag = true,
                layout = CorrectCodeLayout.CBC_SINGLE_BLOB,
            )
        )
    }

    @Test
    fun commitPacketIsTheSingleF7Opcode() {
        assertArrayEquals(
            byteArrayOf(ICanHealthConstants.CORRECT_CODE_COMMIT_OPCODE),
            ICanHealthCorrectCode.buildCommitPacket(),
        )
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
        for (f6Value in listOf(500, 510, 530, 600)) {
            assertNull("f6=0x${"%04X".format(f6Value)} must not be written", plan(f6Value = f6Value))
        }
    }

    @Test
    fun gateAllowsTheWriteOnlyWhenAbsentAuthenticatedAndIdle() {
        val allowed = plan()
        assertNotNull(allowed)
        assertEquals(6, allowed!!.writePackets.size)
        assertArrayEquals(
            byteArrayOf(ICanHealthConstants.CORRECT_CODE_COMMIT_OPCODE),
            allowed.commitPacket,
        )

        assertNull(plan(isAuthenticated = false))
        assertNull(plan(launcherState = ICanHealthConstants.LAUNCHER_STATE_WARMUP))
        assertNull(plan(launcherState = ICanHealthConstants.LAUNCHER_STATE_RUNNING))
        assertNull(plan(launcherState = ICanHealthConstants.LAUNCHER_STATE_ENDED))
        assertNull(plan(launcherState = -1))
        assertNull(plan(f6Value = null))
    }

    @Test
    fun gateStopsAtTheAttemptCap() {
        assertNotNull(plan(attemptsUsed = ICanHealthCorrectCode.MAX_WRITE_ATTEMPTS_PER_CONNECTION - 1))
        assertNull(plan(attemptsUsed = ICanHealthCorrectCode.MAX_WRITE_ATTEMPTS_PER_CONNECTION))
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
            attemptsUsed = 0,
            table = tableArg,
            key = keyArg,
            iv = ivArg,
            versionFlag = true,
            layout = CorrectCodeLayout.CBC_SINGLE_BLOB,
        )

        assertNull(planWith(null, key, iv))
        assertNull(planWith(table, null, iv))
        assertNull(planWith(table, key, null))
    }

    @Test
    fun shippedDefaultKeepsExperimentalWriteDisabled() {
        // This is not a bug to fix in passing: enabling the correctCode write in
        // a shipped build pushes an unverified write into every user's sensor.
        // Flipping the flag is a deliberate field decision with this test.
        assertFalse(ICanHealthExperimental.ENABLE_CORRECT_CODE_WRITE)
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
