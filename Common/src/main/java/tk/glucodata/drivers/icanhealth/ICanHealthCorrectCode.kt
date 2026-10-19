// JugglucoNG — iCanHealth (Sinocare iCan i3/i6/i7) Driver
// ICanHealthCorrectCode.kt — experimental correctCode calibration-table write
//
// A fresh sensor started by our own driver reports F6 = 0xFFFF ("no calibration
// table"). The vendor app writes a 25-value correctCode table at activation;
// without it the sensor's values and history come out wrong. The goal of this
// file: our driver writes that table during/after its own activation, then
// verifies the write by reading F6 back.
//
// Verified against the vendor SDK (decompiled plaintext half):
// - vendor opcodes go to characteristic 0x2AAC; answers arrive as
//   `1C <opcode> <status> <payload…>`
// - F6 answers `1C F6 01 <lo> <hi>`: 0xFFFF = no table, 500/510/530/600 = written
// - the SDK's sequence is: build the float list from a `CodeValueBean` JSON →
//   serialise/encrypt with AES-128-CBC/PKCS7 using the F3 payload ("random
//   command code", which we already receive as lastAuthChallenge) as key
//   material → one BLE write per chunk (F2) → F7 commit
//
// Unknown: the chunk framing — the serialisation of the float list, the IV
// handling, and how each F2 packet is laid out. That guess is isolated in the
// single function ICanHealthCorrectCode.buildCorrectCodeChunks; everything else
// in this file is gating, logging, and testable arithmetic. Field iteration
// flips one constant in ICanHealthExperimental and watches the F6 read-back.

package tk.glucodata.drivers.icanhealth

import org.json.JSONObject
import tk.glucodata.Log

/**
 * Framing candidates for the correctCode write.
 *
 * The vendor SDK serialises the float table and hands the buffer to its chunk
 * builder; what that builder does is the one part of the write path that is not
 * known. Each entry is one guess at it. The plaintext is the same little-endian
 * float array in every case; only the framing differs. The layout is a field
 * knob: see [ICanHealthExperimental.CORRECT_CODE_WRITE_LAYOUT].
 */
internal enum class CorrectCodeLayout {
    /**
     * Serialise the whole table (100 bytes for 25 floats), encrypt it as one
     * AES-CBC/PKCS7 blob, then slice the ciphertext into
     * [ICanHealthCorrectCode.CHUNK_PAYLOAD_BYTES]-byte F2 packets.
     */
    CBC_SINGLE_BLOB,

    /**
     * Slice the plaintext into [ICanHealthCorrectCode.CHUNK_PAYLOAD_BYTES]-byte chunks
     * first and encrypt each chunk separately. The vendor SDK wrote one BLE
     * write per chunk; this is the reading where "chunk" existed before
     * encryption.
     */
    CBC_PER_CHUNK,
}

/**
 * Field-iteration switches for the experimental correctCode write path.
 *
 * Nothing in this path may run by accident. A wrong table, or a correct table
 * framed wrongly, is written into a sensor that is already being worn: the
 * sensor will keep measuring and reporting through the wrong curve, and no
 * amount of host-side code can tell until the sensor itself is re-read (the F6
 * oracle). So the whole path is dead code unless [ENABLE_CORRECT_CODE_WRITE] is
 * flipped by hand for a bench/field session, and every step it takes is logged.
 */
internal object ICanHealthExperimental {
    /**
     * Master switch for writing the correctCode table.
     *
     * Deliberately `false` in shipped builds: the wire framing is still unknown
     * (see [ICanHealthCorrectCode.buildCorrectCodeChunks]), so enabling this in a
     * release would push an unverified write into every user's sensor. Flip it
     * locally, run against a sensor you can afford to lose, read the F6 oracle,
     * and put it back.
     */
    const val ENABLE_CORRECT_CODE_WRITE = false

    /**
     * The vendor SDK's own `versionFlag` branch selector. `true` = the 25-value
     * table, `false` = the i3/h3 15-value branch. Which family reports which is
     * itself a guess, so it is a knob rather than a per-device decision.
     */
    const val CORRECT_CODE_WRITE_VERSION_FLAG = true

    /**
     * The single framing knob: change this one line, rebuild, repeat. Every
     * candidate is logged with the hex it puts on the wire so two runs can be
     * diffed without re-reading this file.
     */
    val CORRECT_CODE_WRITE_LAYOUT: CorrectCodeLayout = CorrectCodeLayout.CBC_SINGLE_BLOB
}

/** What was decided for one write attempt: the F2 packets and the F7 commit. */
internal data class CorrectCodePlan(
    val writePackets: List<ByteArray>,
    val commitPacket: ByteArray,
)

/**
 * CorrectCode table arithmetic, gating, and the (single) framing function.
 *
 * The gate is deliberately paranoid: the write only happens when the sensor
 * itself said F6 = 0xFFFF (so there is no existing table to clobber), the
 * vendor auth handshake completed, the sensor is idle, and the attempt cap has
 * not been reached. Every refusal and every packet goes to the log.
 */
internal object ICanHealthCorrectCode {

    private const val TAG = "ICanHealthCorrectCode"

    /** Number of floats in the full vendor `CodeValueBean` table (x1..x25). */
    const val TABLE_VALUE_COUNT_FULL = 25

    /** i3/h3 branch size the vendor SDK's `versionFlag` may select (x1..x15). */
    const val TABLE_VALUE_COUNT_I3H3 = 15

    /**
     * F2 payload size per BLE write.
     *
     * Conservative on purpose: the default ATT MTU of 23 leaves 20 bytes of
     * payload, so this framing works before any MTU negotiation. If the real
     * vendor framing turns out to use the negotiated MTU, that is a candidate
     * to add rather than a value to raise blindly.
     */
    const val CHUNK_PAYLOAD_BYTES = 20

    /**
     * Write attempts per connection. A wrong framing fails silently at the
     * sensor, so a retry only makes sense while the attempt budget lasts; a
     * sensor that rejects the same bytes twice will reject them the third time,
     * and the cap keeps a faulty framing from becoming a BLE storm.
     */
    const val MAX_WRITE_ATTEMPTS_PER_CONNECTION = 3

    /**
     * CorrectCode table recovered from the vendor app, used as the default table
     * for field experiments.
     *
     * This is TEST DATA, not the connected sensor's factory table: it carries the
     * 25 floats (`x1`..`x25`) a vendor `CodeValueBean` holds. The real table is
     * per-sensor and only the vendor cloud/app has it; writing this one into a
     * sensor is precisely what this debug-gated experiment exists to try.
     */
    const val TEST_CORRECT_CODE_JSON =
        """{"x1":0.015,"x10":1.2,"x11":13.0,"x12":1.4,"x13":0.4,"x14":3.5,"x15":15.0,"x16":15.0,"x17":0.1,"x18":0.6,"x19":0.2,"x2":32.0,"x20":0.6,"x21":0.1,"x22":16.0,"x23":1.0,"x24":2.1,"x25":2.2,"x3":25.0,"x4":1.0,"x5":0.8,"x6":0.3,"x7":40.0,"x8":0.5,"x9":0.5}"""

    /**
     * Decode the calibration-table field of an F6 (`0x1C F6 01 <lo> <hi>`) answer.
     *
     * The sensor reports the table's own checksum/value; 0xFFFF means no table
     * was ever written. Returns null when [data] is not an F6 answer or is too
     * short, so a caller cannot mistake a different vendor response for it.
     */
    fun parseF6Value(data: ByteArray): Int? {
        if (!ICanHealthParser.isSensorInfoResponse(data) || data.size < 5) return null
        return (data[3].toInt() and 0xFF) or ((data[4].toInt() and 0xFF) shl 8)
    }

    /**
     * Parse the vendor's `CodeValueBean` JSON into the ordered float table.
     *
     * `x1`..`xN` map to list elements 0..N-1, which is the order the vendor
     * gives its chunk builder. A missing key or malformed JSON returns null: a
     * partially-filled table must never be written.
     */
    fun parseCorrectCodeTable(
        json: String,
        expectedCount: Int = TABLE_VALUE_COUNT_FULL,
    ): FloatArray? {
        if (expectedCount <= 0) return null
        val obj = try {
            JSONObject(json)
        } catch (e: Exception) {
            Log.w(TAG, "correctCode JSON not parseable: ${e.message}")
            return null
        }
        val table = FloatArray(expectedCount)
        for (index in 0 until expectedCount) {
            val key = "x${index + 1}"
            if (!obj.has(key)) {
                Log.w(TAG, "correctCode JSON missing $key")
                return null
            }
            table[index] = obj.getDouble(key).toFloat()
        }
        return table
    }

    /**
     * Derive the 16-byte AES key from the sensor's own F3 payload.
     *
     * The vendor SDK calls the F3 payload the "random command code" and uses it
     * as the key material for the correctCode writes; our driver receives the
     * F3 notification on every connect. When the payload is not exactly 16
     * bytes it is zero-padded (or truncated), which is a guess that has to be
     * checked against hardware like everything else in this file.
     */
    fun buildCorrectCodeKey(f3Payload: ByteArray?): ByteArray? {
        if (f3Payload == null || f3Payload.isEmpty()) {
            return null
        }
        if (f3Payload.size == ICanHealthConstants.AES_BLOCK_SIZE) {
            return f3Payload.copyOf()
        }
        Log.w(
            TAG,
            "F3 payload is ${f3Payload.size} bytes, not ${ICanHealthConstants.AES_BLOCK_SIZE} — " +
                "zero-padding to AES-128 key (guess, unverified)"
        )
        return ByteArray(ICanHealthConstants.AES_BLOCK_SIZE).apply {
            System.arraycopy(f3Payload, 0, this, 0, minOf(f3Payload.size, size))
        }
    }

    /**
     * THE framing function: the one place the unknown wire shape lives.
     *
     * Every other part of the write path (gate, attempts, logging, read-back)
     * is independent of the layout, so a field session can flip
     * [ICanHealthExperimental.CORRECT_CODE_WRITE_LAYOUT] (and, for the i3/h3
     * branch, [ICanHealthExperimental.CORRECT_CODE_WRITE_VERSION_FLAG]) without
     * touching the driver. The three candidate combinations are:
     *
     * 1. `versionFlag = true` + [CorrectCodeLayout.CBC_SINGLE_BLOB] (default):
     *    25 floats little-endian = 100 bytes, one AES-CBC/PKCS7 blob, sliced
     *    into 20-byte F2 packets.
     * 2. `versionFlag = true` + [CorrectCodeLayout.CBC_PER_CHUNK]: same 100
     *    bytes sliced first, each 20-byte slice encrypted on its own.
     * 3. `versionFlag = false` + [CorrectCodeLayout.CBC_SINGLE_BLOB]: the i3/h3
     *    15-value branch, 60 bytes, one blob, sliced.
     *
     * Each F2 packet is framed like the other vendor writes on the same
     * characteristic (`F4 10 <token>`, `FC <len> <cipher>`): opcode, payload
     * length, payload. That header is itself part of the guess and is logged.
     *
     * @return the F2 packets to write in order, or null when the table is too
     *   short or the key/IV are not AES-128 sized. Never throws.
     */
    fun buildCorrectCodeChunks(
        table: FloatArray,
        key: ByteArray,
        iv: ByteArray,
        versionFlag: Boolean,
        layout: CorrectCodeLayout,
    ): List<ByteArray>? {
        val valueCount = if (versionFlag) TABLE_VALUE_COUNT_FULL else TABLE_VALUE_COUNT_I3H3
        if (table.size < valueCount) {
            Log.w(TAG, "correctCode table has ${table.size} values, need $valueCount")
            return null
        }
        if (key.size != ICanHealthConstants.AES_BLOCK_SIZE || iv.size != ICanHealthConstants.AES_BLOCK_SIZE) {
            Log.w(TAG, "correctCode key/iv sizes are ${key.size}/${iv.size}, need 16/16")
            return null
        }
        val plaintext = encodeFloatsLe(table, valueCount)
        val payloads: List<ByteArray> = when (layout) {
            CorrectCodeLayout.CBC_SINGLE_BLOB -> {
                val ciphertext = ICanHealthCrypto.encryptCbcPkcs7(plaintext, key, iv) ?: run {
                    Log.e(TAG, "correctCode AES-CBC encryption failed")
                    return null
                }
                ciphertext.splitIntoChunks(CHUNK_PAYLOAD_BYTES)
            }

            CorrectCodeLayout.CBC_PER_CHUNK -> {
                plaintext.splitIntoChunks(CHUNK_PAYLOAD_BYTES).map { slice ->
                    ICanHealthCrypto.encryptCbcPkcs7(slice, key, iv) ?: run {
                        Log.e(TAG, "correctCode AES-CBC encryption failed for a chunk")
                        return null
                    }
                }
            }
        }
        val packets = payloads.map { it.toF2Packet() }
        Log.i(
            TAG,
            "correctCode framing layout=$layout versionFlag=$versionFlag values=$valueCount " +
                "plaintext=${plaintext.size}B packets=${packets.size} " +
                "sizes=${packets.joinToString(",") { it.size.toString() }}"
        )
        Log.i(TAG, "correctCode packets=${packets.joinToString(" ") { it.toHexString() }}")
        return packets
    }

    /** F7 commit packet: a single opcode byte, like F3/F6 requests. */
    fun buildCommitPacket(): ByteArray = byteArrayOf(ICanHealthConstants.CORRECT_CODE_COMMIT_OPCODE)

    /**
     * Why a write is not allowed right now, or null when it is.
     *
     * The F6 value is the primary gate: F2/F7 must never be sent to a sensor
     * that already reports a table (anything but 0xFFFF), because a second
     * table would clobber one that may have come from the vendor app.
     */
    fun gateSkipReason(
        f6Value: Int?,
        isAuthenticated: Boolean,
        launcherState: Int,
        attemptsUsed: Int,
    ): String? = when {
        f6Value == null -> "F6 unreadable"
        f6Value != ICanHealthConstants.CORRECT_CODE_ABSENT_VALUE ->
            "F6=0x${"%04X".format(f6Value)} (table already present, never overwrite)"
        !isAuthenticated -> "vendor auth not completed"
        launcherState != ICanHealthConstants.LAUNCHER_STATE_IDLE ->
            "launcherState=0x${"%02X".format(launcherState and 0xFF)} (not idle)"
        attemptsUsed >= MAX_WRITE_ATTEMPTS_PER_CONNECTION ->
            "attempt cap reached ($attemptsUsed/$MAX_WRITE_ATTEMPTS_PER_CONNECTION)"
        else -> null
    }

    /**
     * Build the full write plan, or null when the gate refuses or the framing
     * cannot be built. Pure: no BLE, no state. The caller owns attempt counting.
     */
    fun planCorrectCodeWrite(
        f6Value: Int?,
        isAuthenticated: Boolean,
        launcherState: Int,
        attemptsUsed: Int,
        table: FloatArray?,
        key: ByteArray?,
        iv: ByteArray?,
        versionFlag: Boolean,
        layout: CorrectCodeLayout,
    ): CorrectCodePlan? {
        gateSkipReason(f6Value, isAuthenticated, launcherState, attemptsUsed)?.let { reason ->
            Log.i(TAG, "correctCode write skipped: $reason")
            return null
        }
        val safeTable = table ?: run {
            Log.w(TAG, "correctCode write skipped: no table parsed")
            return null
        }
        val safeKey = key ?: run {
            Log.w(TAG, "correctCode write skipped: no F3 key material")
            return null
        }
        val safeIv = iv ?: run {
            Log.w(TAG, "correctCode write skipped: no IV (auth challenge)")
            return null
        }
        val writePackets = buildCorrectCodeChunks(
            table = safeTable,
            key = safeKey,
            iv = safeIv,
            versionFlag = versionFlag,
            layout = layout,
        ) ?: return null
        return CorrectCodePlan(
            writePackets = writePackets,
            commitPacket = buildCommitPacket(),
        )
    }

    /** Little-endian IEEE-754 encoding of the first [count] floats. */
    private fun encodeFloatsLe(values: FloatArray, count: Int): ByteArray {
        val out = ByteArray(count * 4)
        var offset = 0
        for (index in 0 until count) {
            val bits = values[index].toBits()
            out[offset] = (bits and 0xFF).toByte()
            out[offset + 1] = ((bits ushr 8) and 0xFF).toByte()
            out[offset + 2] = ((bits ushr 16) and 0xFF).toByte()
            out[offset + 3] = ((bits ushr 24) and 0xFF).toByte()
            offset += 4
        }
        return out
    }

    private fun ByteArray.splitIntoChunks(chunkSize: Int): List<ByteArray> {
        if (chunkSize <= 0 || isEmpty()) return emptyList()
        val chunks = ArrayList<ByteArray>((size + chunkSize - 1) / chunkSize)
        var offset = 0
        while (offset < size) {
            val end = minOf(offset + chunkSize, size)
            chunks += copyOfRange(offset, end)
            offset = end
        }
        return chunks
    }

    /** `F2 <length> <payload>` — same opcode/length shape as the F4/FC writes. */
    private fun ByteArray.toF2Packet(): ByteArray {
        val payload = this
        return ByteArray(2 + payload.size).apply {
            this[0] = ICanHealthConstants.CORRECT_CODE_CHUNK_OPCODE
            this[1] = payload.size.toByte()
            System.arraycopy(payload, 0, this, 2, payload.size)
        }
    }
}
