// JugglucoNG — iCanHealth (Sinocare iCan i3/i6/i7) Driver
// ICanHealthCorrectCode.kt — encodes the vendor "correctCode" calibration table into
// the three encrypted `[F2 10]` chunks sent after start and the session clock.
//
// Cipher and layout reverse-engineered from the vendor SDK's packer (bean.a/g.a,
// NCall/VMP-protected) and validated byte-for-byte against two independent device
// encode sessions (different BLE challenges), the full value-perturbation sweep and
// all challenge-slot probes — 69 recorded encode lines in total. See
// docs/superpowers/plans/2026-10-19-ican-calibration-plan.md §7.8.
//
// Each chunk is one 18-byte write: `[F2][0x10][16-byte ciphertext]`, acknowledged by
// `1C F2 01`. The three chunks share one 48-byte plaintext:
//
//   plaintext = aux(challenge) XOR data
//
// data is the calibration table itself, 15 values as uint16-LE, always
// trunc(value * 1000f) after float32 multiplication (x1..x15):
//   x1..x5   -> block0[4..13]         x6..x11  -> block1[1..12]
//   x12 low  -> block1[13]            x12 high -> block2[1]
//   x13      -> block2[2..3]          x14      -> block2[4..5]
//   x15      -> block2[6..7]
// The SDK list builder replaces the server's x13 with the decimal box-serial
// substring [9,11), divided by 100f, before applying this conversion.
//
// aux embeds the 4 auth-challenge bytes c0..c3 (wire order of the F3 response,
// `06 F3 01 c0 c1 c2 c3`) at the same rule positions the F4 auth IV uses:
//   per block j (0/1/2), c1 at [6], c2 at [10], c0 at [1], c3 at [15], XOR-masked
//   per block; block 0 prefixes carry 0x0E/0xAA and the data length, block 2 carries
//   an 0x08 tail run. Challenge-independence of every other byte was proven by
//   single-byte challenge probes.

package tk.glucodata.drivers.icanhealth

import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

object ICanHealthCorrectCode {
    /** Values per calibration table (vendor x1..x15 layout). */
    const val TABLE_VALUES = 15

    /** AES-128-ECB blocks per table. Encrypted independently (ECB is stateless). */
    const val BLOCKS_PER_TABLE = 3

    /** Cleartext chunk header: opcode 0xF2, payload length 0x10. */
    val CHUNK_HEADER = byteArrayOf(0xF2.toByte(), 0x10)

    private const val AES_BLOCK = 16

    /** Value 12 (vendor x12) splits: low byte in block 1 tail, high byte in block 2 head. */
    private const val SPLIT_VALUE_INDEX = 11

    /** Table values as uint16 after the vendor scaling, or null when the table is incomplete. */
    @JvmStatic
    fun valuesFromCorrectCode(correctCode: Map<String, Float>): IntArray? {
        val values = IntArray(TABLE_VALUES)
        for (index in 1..TABLE_VALUES) {
            val raw = correctCode["x$index"] ?: return null
            if (!raw.isFinite()) return null
            values[index - 1] = (raw * 1000f).toInt() and 0xFFFF
        }
        return values
    }

    /**
     * One wired chunk per AES block, or null for malformed inputs. The [challenge] is the
     * four bytes as they followed `06 F3 01` in the F3 response.
     */
    @JvmStatic
    fun encodeTable(values: IntArray, challenge: ByteArray): List<ByteArray>? {
        if (values.size != TABLE_VALUES || challenge.size != 4) return null
        val plaintext = buildAuxPlaintext(values.size, challenge)
        placeData(plaintext, values)
        val key = ICanHealthConstants.DEFAULT_CORRECT_CODE_AES_KEY_ASCII
            .toByteArray(Charsets.US_ASCII)
        val encrypted = runCatching {
            val cipher = Cipher.getInstance("AES/ECB/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
            cipher.doFinal(plaintext)
        }.getOrNull() ?: return null
        return List(BLOCKS_PER_TABLE) { block ->
            ByteArray(CHUNK_HEADER.size + AES_BLOCK).apply {
                CHUNK_HEADER.copyInto(this)
                encrypted.copyInto(
                    this, CHUNK_HEADER.size,
                    block * AES_BLOCK, (block + 1) * AES_BLOCK,
                )
            }
        }
    }

    /** Build the SDK's per-box list before quantization; never mutate the cached server table. */
    @JvmStatic
    fun buildCorrectCodeChunks(
        correctCode: Map<String, Float>,
        challenge: ByteArray,
        factorySerial: String,
    ): List<ByteArray>? {
        val boxCode = ICanHealthProvisioningPolicy.factoryCode(factorySerial) ?: return null
        if (ICanHealthProvisioningPolicy.isGenericTable(correctCode)) return null
        val perBox = correctCode.toMutableMap()
        perBox["x13"] = boxCode
        return encodeTable(valuesFromCorrectCode(perBox) ?: return null, challenge)
    }

    /** Concatenates chunks for a single ATT write when the MTU allows it (3 × 18 bytes). */
    @JvmStatic
    fun chunksToBytes(chunks: List<ByteArray>): ByteArray? {
        if (chunks.size != BLOCKS_PER_TABLE) return null
        if (chunks.any { it.size != CHUNK_HEADER.size + AES_BLOCK }) return null
        return ByteArray(chunks.size * (CHUNK_HEADER.size + AES_BLOCK)).apply {
            var offset = 0
            for (chunk in chunks) {
                chunk.copyInto(this, offset)
                offset += chunk.size
            }
        }
    }

    /**
     * The fixed 48-byte aux layer. `challenge[0..3]` are c0..c3 in wire order; every byte
     * below was tied to vendor output by the challenge-slot probes and the value sweep.
     */
    internal fun buildAuxPlaintext(valueCount: Int, challenge: ByteArray): ByteArray {
        val c0 = challenge[0].toInt() and 0xFF
        val c1 = challenge[1].toInt() and 0xFF
        val c2 = challenge[2].toInt() and 0xFF
        val c3 = challenge[3].toInt() and 0xFF
        val dataLength = (valueCount * 2) and 0xFF
        val aux = ByteArray(BLOCKS_PER_TABLE * AES_BLOCK)
        for (block in 0 until BLOCKS_PER_TABLE) {
            val base = block * AES_BLOCK
            aux[base] = (if (block < 2) 0x0E else 0x08).toByte()
            aux[base + 1] = c0.toByte()
            aux[base + 6] = c1.toByte()
            aux[base + 10] = (c2 xor (if (block == 2) 0x08 else 0x00)).toByte()
            aux[base + 14] = (if (block < 2) 0x02 else 0x08).toByte()
            aux[base + 15] = (c3 xor (if (block < 2) 0x02 else 0x08)).toByte()
        }
        aux[1] = (c0 xor 0x55).toByte()
        aux[2] = 0xAA.toByte()
        aux[3] = dataLength.toByte()
        aux[32 + 8] = 0x08
        aux[32 + 9] = 0x08
        aux[32 + 11] = 0x08
        aux[32 + 12] = 0x08
        aux[32 + 13] = 0x08
        return aux
    }

    /** XORs the 30 scaled data bytes into the aux plaintext at their fixed slots. */
    internal fun placeData(aux: ByteArray, values: IntArray) {
        fun xor16(base: Int, offset: Int, value: Int) {
            aux[base + offset] = (aux[base + offset].toInt() xor (value and 0xFF)).toByte()
            aux[base + offset + 1] =
                (aux[base + offset + 1].toInt() xor ((value ushr 8) and 0xFF)).toByte()
        }
        for (index in 0..4) xor16(0, 4 + 2 * index, values[index])
        for (index in 0..5) xor16(AES_BLOCK, 1 + 2 * index, values[5 + index])
        val splitLow = AES_BLOCK + 13
        aux[splitLow] = (aux[splitLow].toInt() xor (values[SPLIT_VALUE_INDEX] and 0xFF)).toByte()
        val splitHigh = 2 * AES_BLOCK + 1
        aux[splitHigh] =
            (aux[splitHigh].toInt() xor ((values[SPLIT_VALUE_INDEX] ushr 8) and 0xFF)).toByte()
        for ((index, offset) in listOf(12 to 2, 13 to 4, 14 to 6)) {
            xor16(2 * AES_BLOCK, offset, values[index])
        }
    }
}
