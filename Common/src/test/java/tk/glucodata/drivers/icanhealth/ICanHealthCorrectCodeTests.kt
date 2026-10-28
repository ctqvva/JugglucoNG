// JugglucoNG — iCan correctCode encoder tests. Fully synthetic values and
// challenges; no vendor captures, no sensor serials, no account data.
//
// Reference vectors were generated with an independent Python/openssl replica of
// the recovered algorithm. That replica reproduces the vendor device output
// byte-for-byte (two encode sessions under different challenges plus 69 recorded
// sweep lines), so the pinned vectors here anchor the implementation to the
// validated model rather than to itself.
package tk.glucodata.drivers.icanhealth

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.json.JSONArray
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

class ICanHealthCorrectCodeTests {
    private fun challenge(vararg bytes: Int): ByteArray =
        bytes.map { it.toByte() }.toByteArray()

    /** Decrypts the three chunks back to the 48-byte plaintext (aux XOR data). */
    private fun decryptToPlaintext(chunks: List<ByteArray>): ByteArray {
        assertEquals(3, chunks.size)
        val ciphertext = ByteArray(48)
        for ((index, chunk) in chunks.withIndex()) {
            assertEquals(18, chunk.size)
            assertEquals(0xF2.toByte(), chunk[0])
            assertEquals(0x10.toByte(), chunk[1])
            chunk.copyInto(ciphertext, index * 16, 2, 18)
        }
        val key = ICanHealthConstants.DEFAULT_CORRECT_CODE_AES_KEY_ASCII
            .toByteArray(Charsets.US_ASCII)
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"))
        return cipher.doFinal(ciphertext)
    }

    @Test
    fun valuesScaleByThousandInFloat32TruncateAndWrap() {
        val code = mapOf(
            "x1" to 0.015f, "x2" to 32.0f, "x3" to 0.555f, "x4" to 40.0f,
            "x5" to 0.444f, "x6" to 0.3f, "x7" to 70.0f, "x8" to 0.5f,
            "x9" to 0.5f, "x10" to 1.2f, "x11" to 13.0f, "x12" to 1.4f,
            "x13" to 0.59f, "x14" to 3.5f, "x15" to 15.0f,
        )
        val values = ICanHealthCorrectCode.valuesFromCorrectCode(code)
        assertNotNull(values)
        values!!
        assertEquals(15, values.size)
        assertEquals(15, values[0])
        assertEquals(32000, values[1])
        assertEquals(555, values[2])
        assertEquals(40000, values[3])
        assertEquals(444, values[4])
        // wrap rule: uint16 storage, verified in the vendor sweep (mod 65536)
        assertEquals(70000 and 0xFFFF, values[6])
        assertEquals(590, values[12])
    }

    @Test
    fun valuesRejectIncompleteTable() {
        val short = mapOf("x1" to 0.015f, "x2" to 32.0f)
        assertNull(ICanHealthCorrectCode.valuesFromCorrectCode(short))
    }

    @Test
    fun boxSerialReplacesOnlyX13BeforeEncoding() {
        val table = (1..25).associate { "x$it" to 1f }.toMutableMap()
        table["x13"] = 0.4f // Synthetic server placeholder, not this box's coefficient.
        val f3 = challenge(0x11, 0x22, 0x33, 0x44)
        for (serial in listOf("ABC0000005900", "abc0000005900")) {
            val actual = ICanHealthCorrectCode.buildCorrectCodeChunks(table, f3, serial)!!
            val expected = ICanHealthCorrectCode.encodeTable(IntArray(15) { 1000 }.also { it[12] = 590 }, f3)!!
            actual.zip(expected).forEach { (a, b) -> assertArrayEquals(b, a) }
        }
        val another = ICanHealthCorrectCode.buildCorrectCodeChunks(table, f3, "ABC0000005800")!!
        val anotherExpected = ICanHealthCorrectCode.encodeTable(IntArray(15) { 1000 }.also { it[12] = 580 }, f3)!!
        another.zip(anotherExpected).forEach { (a, b) -> assertArrayEquals(b, a) }
        assertEquals(0.4f, table["x13"]!!, 0f)
        assertEquals(1f, table["x12"]!!, 0f)
        assertEquals(1f, table["x14"]!!, 0f)
    }

    @Test
    fun missingOrInvalidFactorySerialCannotProduceProvisioningChunks() {
        val table = (1..25).associate { "x$it" to 1f }
        for (serial in listOf("", "ABC000000590", "0123456789ABCDEF012345", "ABC000000XX00", "ABC000000５９00")) {
            assertNull(ICanHealthCorrectCode.buildCorrectCodeChunks(table, challenge(1, 2, 3, 4), serial))
        }
    }

    @Test
    fun quantizationAndPacketsMatchTheSdkBoundaryOracle() {
        // Actual SDK outputs from synthetic lists/challenges, not a replica of this encoder.
        val json = javaClass.getResourceAsStream("/ican/provisioning-boundaries.json")!!
            .bufferedReader().use { it.readText() }
        val cases = JSONArray(json)
        assertEquals(10, cases.length())
        repeat(cases.length()) { index ->
            val case = cases.getJSONObject(index)
            val table = (1..15).associate { "x$it" to 1f }.toMutableMap()
            table["x1"] = case.getDouble("value").toFloat()
            val values = ICanHealthCorrectCode.valuesFromCorrectCode(table)!!
            val chunks = ICanHealthCorrectCode.encodeTable(values, challenge(0x11, 0x22, 0x33, 0x44))!!
            chunks.forEachIndexed { chunk, bytes ->
                assertEquals("SDK case $index chunk $chunk", case.getJSONArray("packets").getString(chunk),
                    bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) })
            }
        }
    }

    @Test
    fun encodeMatchesIndependentModelVector() {
        // Synthetic table; chunks pinned from the independently computed model.
        val values = intArrayOf(
            15, 32000, 555, 40000, 444, 300, 65000, 500, 500, 1200, 13000, 1400, 590, 3500, 15000,
        )
        val chunks = ICanHealthCorrectCode.encodeTable(values, challenge(0xAB, 0xCD, 0xEF, 0x12))
        assertNotNull(chunks)
        val hex = chunks!!.joinToString("") { chunk ->
            assertEquals(0xF2.toByte(), chunk[0])
            assertEquals(0x10.toByte(), chunk[1])
            chunk.drop(2).joinToString("") { "%02x".format(it) }
        }
        assertEquals(
            "42cda3fdb85e78db1e12483d7661006c" +
                "ba562b1986a47071e9b8198506d10eb4" +
                "8229ce49096b9982c9d0a3cd08f6fac5",
            hex,
        )
    }

    @Test
    fun auxStructureFollowsRuleSlots() {
        // Zero challenge and zero-ish data expose the fixed aux bytes directly.
        val values = IntArray(15)
        val plaintext = decryptToPlaintext(
            ICanHealthCorrectCode.encodeTable(values, challenge(0, 0, 0, 0))!!,
        )
        assertEquals(
            "0e55aa1e000000000000000000000202" +
                "0e000000000000000000000000000202" +
                "08000000000000000808080808080808",
            plaintext.joinToString("") { "%02x".format(it) },
        )
        // The data-length byte tracks the value count: 25 values -> 0x32.
        val extended = ByteArray(48)
        val longAux = ICanHealthCorrectCode.buildAuxPlaintext(25, challenge(0, 0, 0, 0))
        longAux.copyInto(extended, 0, 0, 16)
        assertEquals(0x32, extended[3].toInt() and 0xFF)
    }

    @Test
    fun challengeBytesLandOnRuleSlotsOnly() {
        val values = IntArray(15)
        val withC0 = decryptToPlaintext(
            ICanHealthCorrectCode.encodeTable(values, challenge(0x7A.toByte().toInt(), 0, 0, 0))!!,
        )
        val without = decryptToPlaintext(
            ICanHealthCorrectCode.encodeTable(values, challenge(0, 0, 0, 0))!!,
        )
        for (position in without.indices) {
            val diff = withC0[position].toInt().xor(without[position].toInt()) and 0xFF
            when (position) {
                // the 0x55 mask cancels in the comparison: the delta is c0 itself
                1 -> assertEquals(0x7A, diff)     // block0 c0 (under the 0x55 mask)
                17 -> assertEquals(0x7A, diff)    // block1 c0
                33 -> assertEquals(0x7A, diff)    // block2 c0
                else -> assertEquals("slot $position", 0, diff)
            }
        }
    }

    @Test
    fun splitValueSpansBlockBoundary() {
        val values = IntArray(15)
        values[11] = 0x0578 // 1400: low 0x78 -> block1[13], high 0x05 -> block2[1]
        val plaintext = decryptToPlaintext(
            ICanHealthCorrectCode.encodeTable(values, challenge(0, 0, 0, 0))!!,
        )
        assertEquals(0x78, plaintext[16 + 13].toInt() and 0xFF)
        assertEquals(0x05, plaintext[32 + 1].toInt() and 0xFF)
    }

    @Test
    fun c1RidesOnTopOfDataSlot() {
        // c1 lands on [6] of every block, XOR-mixed with the value bytes beneath it.
        val values = IntArray(15)
        values[1] = 0x7D00 // low byte 0x00 sits under c1 at block0[6]
        val plaintext = decryptToPlaintext(
            ICanHealthCorrectCode.encodeTable(values, challenge(0, 0x3F.toByte().toInt(), 0, 0))!!,
        )
        assertEquals(0x3F, plaintext[6].toInt() and 0xFF)
        assertEquals(0x3F, plaintext[16 + 6].toInt() and 0xFF)
        assertEquals(0x3F, plaintext[32 + 6].toInt() and 0xFF)
    }

    @Test
    fun chunksConcatenateForSingleWrite() {
        val chunks = ICanHealthCorrectCode.encodeTable(
            IntArray(15),
            challenge(0, 0, 0, 0),
        )!!
        val wire = ICanHealthCorrectCode.chunksToBytes(chunks)
        assertNotNull(wire)
        wire!!
        assertEquals(54, wire.size)
        assertEquals(0xF2.toByte(), wire[0])
        assertEquals(0xF2.toByte(), wire[18])
        assertEquals(0xF2.toByte(), wire[36])
        assertArrayEquals(chunks[0], wire.copyOfRange(0, 18))
    }

    @Test
    fun malformedInputsAreRejected() {
        assertNull(ICanHealthCorrectCode.encodeTable(IntArray(14), challenge(0, 0, 0, 0)))
        assertNull(ICanHealthCorrectCode.encodeTable(IntArray(15), byteArrayOf(1, 2, 3)))
        val chunks = ICanHealthCorrectCode.encodeTable(IntArray(15), challenge(0, 0, 0, 0))!!
        assertNull(ICanHealthCorrectCode.chunksToBytes(chunks.take(2)))
        assertNull(
            ICanHealthCorrectCode.chunksToBytes(
                listOf(ByteArray(17), chunks[1], chunks[2]),
            ),
        )
    }
}
