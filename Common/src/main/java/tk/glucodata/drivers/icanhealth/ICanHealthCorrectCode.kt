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
//   `1C <opcode> <status> <payload…>` (the F3 challenge answer is its own
//   `06 F3 01 <4-byte challenge>` shape)
// - F6 answers `1C F6 01 <lo> <hi>`: 0xFFFF = no table; 500/510/530/600/570 have
//   been observed from provisioned sensors
// - the SDK has two writes that share its native chunker: `writeCorrectCode`
//   (a `CodeValueBean` server table, chunker 4th arg `false`) and
//   `writeInitParameter` (a serial-derived list, chunker 4th arg `true`). Only
//   the server-table path is modelled here; see
//   [CorrectCodeProvisioningPath.SN_DERIVED] for why the other one must not be
//   guessed at.
// - `j2.java` case 243 stores the whole 7-byte F3 notification
//   (`if (value.length >= 7) { f4759m = value; }`, log `encryptRandomResponse:
//   06F301…`) and hands *that* to the chunker, not the 4-byte challenge. How the
//   chunker expands it into a 16-byte AES key is inside the native half, so the
//   expansion here is an explicitly unverified candidate
//   ([CorrectCodeKeyDerivation]).
//
// Unknown: the chunk framing — the serialisation of the float list, the IV
// handling, and how each F2 packet is laid out. That guess is isolated in
// ICanHealthCorrectCode.buildCorrectCodeChunks; everything else in this file is
// gating, transaction bookkeeping, logging, and testable arithmetic. Field
// iteration flips one constant in ICanHealthExperimental and watches the F6
// read-back.

package tk.glucodata.drivers.icanhealth

import android.content.Context
import android.content.SharedPreferences
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
 * Which vendor write the experimental path models.
 *
 * The two writes share the vendor's native chunker and differ only in what is
 * fed into it. They are not interchangeable: the chunker's 4th argument is
 * `false` for the server table and `true` for the serial-derived list, and a
 * wrong list is written into a sensor the user is already wearing.
 */
internal enum class CorrectCodeProvisioningPath {
    /**
     * The vendor SDK's `writeCorrectCode`: a 25-value `CodeValueBean` JSON table
     * from the vendor cloud, handed to the native chunker with its 4th argument
     * `false`. The only path implemented here.
     */
    SERVER_TABLE,

    /**
     * The vendor SDK's `writeInitParameter`: a serial-derived value list handed
     * to the same chunker with its 4th argument `true`.
     *
     * NOT implemented: the list is built inside the vendor SDK from
     * launcher/serial data we have not reproduced, so anything we could send
     * would be a guess at a per-sensor table. It must never be selected as a
     * stand-in for [SERVER_TABLE], and [ICanHealthCorrectCode.buildCorrectCodeChunks]
     * refuses it with a loud log rather than substituting anything.
     */
    SN_DERIVED,
}

/**
 * Candidate inputs for the correctCode key material.
 *
 * The vendor SDK stores the whole F3 notification (`j2.java` case 243) and hands
 * it to the native chunker, which expands it to a 16-byte AES key; how is not
 * known. Both candidates therefore zero-pad (or truncate) their input to
 * [ICanHealthConstants.AES_BLOCK_SIZE] — an explicit guess, not a derivation
 * anyone has reproduced. [ICanHealthExperimental.CORRECT_CODE_WRITE_KEY_DERIVATION]
 * picks one per field run, and every attempt logs the raw input hex and the
 * candidate so two runs can be told apart from the log alone.
 */
internal enum class CorrectCodeKeyDerivation {
    /** The full `06 F3 01 <challenge>` notification, zero-padded to 16 bytes. */
    FULL_NOTIFICATION_ZERO_PAD,

    /** Just the 4-byte challenge (what the old driver kept), zero-padded to 16 bytes. */
    CHALLENGE_ZERO_PAD,
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
     * Which vendor write is modelled. Only
     * [CorrectCodeProvisioningPath.SERVER_TABLE] is implemented; the SN-derived
     * path is refused rather than substituted (see the enum).
     */
    val CORRECT_CODE_PROVISIONING_PATH: CorrectCodeProvisioningPath =
        CorrectCodeProvisioningPath.SERVER_TABLE

    /**
     * The native chunker's 3rd argument ("launcher version flag").
     *
     * Kept separate from both [CORRECT_CODE_PROVISIONING_PATH] and the table
     * length. What it changes on the wire is unknown, so the framing model does
     * not alter the bytes with it; it rides along in the plan and the attempt log
     * so a field session can vary it independently of the other knobs.
     */
    const val CORRECT_CODE_WRITE_LAUNCHER_VERSION_FLAG = true

    /**
     * The single framing knob: change this one line, rebuild, repeat. Every
     * candidate is logged with the hex it puts on the wire so two runs can be
     * diffed without re-reading this file.
     */
    val CORRECT_CODE_WRITE_LAYOUT: CorrectCodeLayout = CorrectCodeLayout.CBC_SINGLE_BLOB

    /**
     * Which unverified key-input candidate is on the wire. The raw input hex and
     * the candidate are logged on every attempt.
     */
    val CORRECT_CODE_WRITE_KEY_DERIVATION: CorrectCodeKeyDerivation =
        CorrectCodeKeyDerivation.FULL_NOTIFICATION_ZERO_PAD

    /**
     * Explicit table input for a hand-run field session; `null` in any shipped
     * build, and nothing in the app sets it.
     *
     * The 25-value JSON recovered from the vendor app is a test-only fixture (see
     * `ICanHealthCorrectCodeTests`), not a production fallback: the connected
     * sensor's real table is per-sensor and only the vendor cloud has it. With
     * this null the write path refuses, which is the shipped behaviour. A bench
     * session sets it together with [ENABLE_CORRECT_CODE_WRITE] and, if the
     * batch is aborted before it is sent, still wants
     * [resetWriteAttempt] before another run.
     */
    @Volatile
    var CORRECT_CODE_WRITE_TABLE_JSON: String? = null

    /** SharedPreferences file the driver already uses for per-sensor state. */
    private const val PREFS_NAME = "tk.glucodata_preferences"

    /**
     * Read the persisted one-shot record for [serial].
     *
     * Fails closed: a null context or blank serial cannot prove the serial is
     * clean, so this reports `true` and the write gate refuses.
     */
    fun hasWriteAttempted(context: Context?, serial: String?): Boolean {
        val prefs = attemptPrefs(context) ?: return true
        val key = correctCodeAttemptStorageKey(serial) ?: return true
        return prefs.getBoolean(key, false)
    }

    /**
     * Persist that a batch for [serial] has been sent. Returns whether the record
     * was stored; the caller must refuse to send when it was not, because that is
     * the only guarantee of at-most-once.
     */
    fun markWriteAttempted(context: Context?, serial: String?): Boolean {
        val prefs = attemptPrefs(context) ?: return false
        val key = correctCodeAttemptStorageKey(serial) ?: return false
        val stored = prefs.edit().putBoolean(key, true).commit()
        if (stored) {
            Log.i(
                ICanHealthConstants.TAG,
                "correctCode one-shot record stored for serial=$serial"
            )
        }
        return stored
    }

    /**
     * Deliberate developer reset of the one-shot record for [serial].
     *
     * There is no automatic reset — not on reconnect, not on app update. This is
     * the only way to make a serial writable again, and it is meant to be called
     * by hand (bench build, debugger, or a temporary call site) when another
     * field run with different framing constants is wanted. Returns whether a
     * record existed.
     */
    fun resetWriteAttempt(context: Context?, serial: String?): Boolean {
        val prefs = attemptPrefs(context) ?: return false
        val key = correctCodeAttemptStorageKey(serial) ?: return false
        val existed = prefs.contains(key)
        prefs.edit().remove(key).commit()
        if (existed) {
            Log.w(
                ICanHealthConstants.TAG,
                "correctCode one-shot record RESET for serial=$serial — the next batch will be sent"
            )
        }
        return existed
    }

    private fun attemptPrefs(context: Context?): SharedPreferences? =
        context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}

/**
 * Storage key for the one-shot record of [serial], or null when the serial is
 * not usable as one (null/blank).
 *
 * A null key always means "cannot prove this serial is clean" — the callers
 * treat it as fail-closed, never as "never attempted". Kept a pure function so
 * the key rule and the gate it feeds are testable without Android.
 */
internal fun correctCodeAttemptStorageKey(serial: String?): String? =
    serial?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?.let { ICanHealthConstants.PREF_CORRECT_CODE_ATTEMPTED_PREFIX + it }

/**
 * One fully-built correctCode write: the F2 packets, the F7 commit, and the
 * experiment knobs that produced them.
 *
 * The knobs ride along so the attempt log (and the eventual F6 read-back record)
 * can say exactly which candidate combination was on the wire without re-reading
 * the constants file.
 */
internal data class CorrectCodePlan(
    val writePackets: List<ByteArray>,
    val commitPacket: ByteArray,
    val path: CorrectCodeProvisioningPath,
    val launcherVersionFlag: Boolean,
    val layout: CorrectCodeLayout,
)

/**
 * Pure state machine for one correctCode batch, driven by GATT write callbacks.
 *
 * It exists to pin the transaction rule the BLE layer must obey: an F2 chunk
 * whose GATT write fails aborts the batch, so the F7 commit is never sent after
 * a partially transmitted table. The manager follows [Next]; this class holds no
 * bytes and no Android state, so the rule is unit-testable. Vendor-level success
 * is a separate matter — that is whatever the post-batch F6 read-back reports.
 */
internal class CorrectCodeBatch(val chunkCount: Int) {
    init {
        require(chunkCount > 0) { "correctCode batch needs at least one F2 chunk" }
    }

    enum class Phase {
        CHUNKS,
        COMMIT,
        READBACK,
    }

    /** What the caller may queue next; [NONE] means nothing. */
    enum class Next {
        SEND_CHUNK,
        SEND_COMMIT,
        SEND_READBACK,
        NONE,
    }

    var phase: Phase = Phase.CHUNKS
        private set

    /** Index of the F2 chunk whose write callback has not arrived yet. */
    var chunkInFlight: Int = 0
        private set

    var chunksAcked: Int = 0
        private set

    /** Index of the first F2 chunk whose write failed, or -1 when none did. */
    var abortedAtChunk: Int = -1
        private set

    var commitSent: Boolean = false
        private set

    var commitGattStatus: Int? = null
        private set

    /** True once the post-batch F6 request write itself completed at the GATT layer. */
    var readbackRequested: Boolean = false
        private set

    /**
     * One F2 write finished with [status]. A failure ends the transmission: the
     * phase moves straight to the read-back and no commit can follow.
     */
    fun onChunkWriteResult(status: Int): Next {
        if (phase != Phase.CHUNKS) return Next.NONE
        if (status != ICanHealthCorrectCode.GATT_SUCCESS) {
            abortedAtChunk = chunkInFlight
            phase = Phase.READBACK
            return Next.SEND_READBACK
        }
        chunksAcked++
        val next = chunkInFlight + 1
        if (next < chunkCount) {
            chunkInFlight = next
            return Next.SEND_CHUNK
        }
        phase = Phase.COMMIT
        return Next.SEND_COMMIT
    }

    fun onCommitWriteResult(status: Int): Next {
        if (phase != Phase.COMMIT) return Next.NONE
        commitSent = true
        commitGattStatus = status
        phase = Phase.READBACK
        return Next.SEND_READBACK
    }

    fun onReadbackRequestWritten() {
        readbackRequested = true
    }

    /** GATT-level outcome only; the vendor-level outcome is the F6 value. */
    fun gattOutcomeSummary(): String = when {
        abortedAtChunk >= 0 ->
            "GATT_ABORTED after F2[$abortedAtChunk] (${chunksAcked}/${chunkCount} F2 acked, F7 never sent)"
        commitGattStatus != null && commitGattStatus != ICanHealthCorrectCode.GATT_SUCCESS ->
            "GATT_COMMIT_FAILED status=$commitGattStatus after ${chunksAcked}/${chunkCount} F2"
        commitSent -> "GATT_SUCCESS (${chunksAcked}/${chunkCount} F2 + F7 commit)"
        else -> "GATT_INCOMPLETE (${chunksAcked}/${chunkCount} F2, no commit)"
    }
}

/**
 * CorrectCode table arithmetic, gating, and the (single) framing function.
 *
 * The gate is deliberately paranoid: the write only happens when the sensor
 * itself said F6 = 0xFFFF (so there is no existing table to clobber), the vendor
 * auth handshake completed, the sensor is idle, and this serial has never had a
 * batch sent for it. Every refusal and every packet goes to the log. The MTU fit
 * of each packet is checked by the caller with [planFitsMtu] before anything is
 * queued.
 */
internal object ICanHealthCorrectCode {

    private const val TAG = "ICanHealthCorrectCode"

    /**
     * `android.bluetooth.BluetoothGatt.GATT_SUCCESS`, duplicated so the batch
     * state machine above stays plain-JVM testable.
     */
    const val GATT_SUCCESS = 0

    /** Number of floats in the vendor `CodeValueBean` server table (x1..x25). */
    const val TABLE_VALUE_COUNT_SERVER_TABLE = 25

    /**
     * Length of the serial-derived list written by the vendor's
     * `writeInitParameter` (chunker 4th arg `true`). This is an
     * [CorrectCodeProvisioningPath.SN_DERIVED]-path shape, not a selectable
     * layout for the server table, and the path itself is not implemented.
     */
    const val TABLE_VALUE_COUNT_SN_DERIVED = 15

    /**
     * F2 payload size per BLE write.
     *
     * Conservative on purpose: the default ATT MTU of 23 leaves 20 bytes of
     * payload, so a packet built at this size works on a link where MTU
     * negotiation did not happen. It does not work for the F2 header itself
     * (2 bytes) on such a link — that is exactly why
     * [planFitsMtu] has to refuse the whole batch instead of letting the write
     * silently truncate.
     */
    const val CHUNK_PAYLOAD_BYTES = 20

    /**
     * Fixed overhead of an ATT write: 1 opcode byte + 2 handle bytes, so a write
     * on a link with MTU `m` can carry `m - 3` bytes.
     */
    const val ATT_WRITE_OVERHEAD_BYTES = 3

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
     * gives its chunk builder. A missing key, malformed JSON, a non-numeric
     * value, or a non-finite value returns null: a partially-filled or NaN table
     * must never be written.
     */
    fun parseCorrectCodeTable(
        json: String,
        expectedCount: Int = TABLE_VALUE_COUNT_SERVER_TABLE,
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
            val value = try {
                obj.getDouble(key)
            } catch (e: Exception) {
                Log.w(TAG, "correctCode JSON $key is not a number: ${e.message}")
                return null
            }
            val asFloat = value.toFloat()
            // Reject NaN/infinity both as a Double and as the Float that goes on
            // the wire (a finite Double can still overflow Float).
            if (!value.isFinite() || !asFloat.isFinite()) {
                Log.w(TAG, "correctCode JSON $key is not finite: $value")
                return null
            }
            table[index] = asFloat
        }
        return table
    }

    /**
     * Build the 16-byte AES key candidate from the sensor's own F3 data.
     *
     * [derivation] selects the input ([CorrectCodeKeyDerivation]); the expansion
     * itself happens inside the vendor's native chunker and is not reproduced, so
     * this zero-pads (or truncates) — an explicitly unverified candidate. The raw
     * input hex and the candidate are logged on every attempt so two field runs
     * can be compared from the log alone.
     */
    fun buildCorrectCodeKey(
        f3Notification: ByteArray?,
        authChallenge: ByteArray?,
        derivation: CorrectCodeKeyDerivation,
    ): ByteArray? {
        val raw = when (derivation) {
            CorrectCodeKeyDerivation.FULL_NOTIFICATION_ZERO_PAD -> f3Notification
            CorrectCodeKeyDerivation.CHALLENGE_ZERO_PAD -> authChallenge
        }
        if (raw == null || raw.isEmpty()) {
            Log.w(
                TAG,
                "correctCode key derivation ${derivation.name}: no input " +
                    "(F3 notification / challenge missing)"
            )
            return null
        }
        Log.i(
            TAG,
            "correctCode key derivation candidate=${derivation.name} raw=${raw.toHexString()} " +
                "(${raw.size}B) → zero-pad/truncate to ${ICanHealthConstants.AES_BLOCK_SIZE}B " +
                "(UNVERIFIED: the native expansion is unknown)"
        )
        return ByteArray(ICanHealthConstants.AES_BLOCK_SIZE).apply {
            System.arraycopy(raw, 0, this, 0, minOf(raw.size, size))
        }
    }

    /**
     * THE framing function: the one place the unknown wire shape lives.
     *
     * The only implemented path is [CorrectCodeProvisioningPath.SERVER_TABLE]: the
     * vendor's `writeCorrectCode`, its 25-value `CodeValueBean` JSON table, and
     * the native chunker's 4th argument `false`.
     * [CorrectCodeProvisioningPath.SN_DERIVED] (the vendor's `writeInitParameter`,
     * whose serial-derived list we cannot reproduce) is refused with a loud log
     * and never silently substituted.
     *
     * [launcherVersionFlag] is the chunker's 3rd argument, a knob separate from
     * [path] and from the table length. Its byte-level effect is unknown, so it
     * does not alter the bytes this model emits; it is recorded in the plan and
     * logged with every attempt.
     *
     * Within [CorrectCodeLayout] the two candidate wire shapes are:
     *
     * 1. [CorrectCodeLayout.CBC_SINGLE_BLOB] (default): 25 floats little-endian =
     *    100 bytes, one AES-CBC/PKCS7 blob, sliced into 20-byte F2 packets.
     * 2. [CorrectCodeLayout.CBC_PER_CHUNK]: the same 100 bytes sliced first, each
     *    20-byte slice encrypted on its own.
     *
     * Each F2 packet is framed like the other vendor writes on the same
     * characteristic (`F4 10 <token>`, `FC <len> <cipher>`): opcode, payload
     * length, payload. That header is itself part of the guess and is logged.
     *
     * @return the F2 packets to write in order, or null when the path is not
     *   implemented, the table is too short, or the key/IV are not AES-128
     *   sized. Never throws.
     */
    fun buildCorrectCodeChunks(
        table: FloatArray,
        key: ByteArray,
        iv: ByteArray,
        path: CorrectCodeProvisioningPath,
        launcherVersionFlag: Boolean,
        layout: CorrectCodeLayout,
    ): List<ByteArray>? {
        if (path != CorrectCodeProvisioningPath.SERVER_TABLE) {
            Log.e(
                TAG,
                "correctCode path=$path is not implemented (no reproducible source for the " +
                    "serial-derived list) — refusing to substitute a guessed table"
            )
            return null
        }
        val valueCount = TABLE_VALUE_COUNT_SERVER_TABLE
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
            "correctCode framing path=$path launcherVersionFlag=$launcherVersionFlag " +
                "layout=$layout values=$valueCount plaintext=${plaintext.size}B packets=${packets.size} " +
                "sizes=${packets.joinToString(",") { it.size.toString() }}"
        )
        Log.i(TAG, "correctCode packets=${packets.joinToString(" ") { it.toHexString() }}")
        return packets
    }

    /** F7 commit packet: a single opcode byte, like F3/F6 requests. */
    fun buildCommitPacket(): ByteArray = byteArrayOf(ICanHealthConstants.CORRECT_CODE_COMMIT_OPCODE)

    /**
     * True when every packet of [plan] fits one ATT write on a link with [mtu].
     *
     * A GATT write carries `mtu - 3` bytes (1 opcode + 2 handle). The driver asks
     * for MTU 517 but falls back to 23 when negotiation is refused, where a
     * 22-byte F2 packet cannot fit; a `writeCharacteristic` at that size is
     * truncated or rejected by the stack with nothing in the log saying so. The
     * caller checks this before queueing anything, so a batch that does not fit
     * is never partially transmitted.
     */
    fun planFitsMtu(plan: CorrectCodePlan, mtu: Int): Boolean {
        val maxWriteBytes = mtu - ATT_WRITE_OVERHEAD_BYTES
        if (maxWriteBytes < 1) return false
        return plan.writePackets.all { it.size <= maxWriteBytes } &&
            plan.commitPacket.size <= maxWriteBytes
    }

    /**
     * Why a write is not allowed right now, or null when it is.
     *
     * The F6 value is the primary gate: F2/F7 must never be sent to a sensor
     * that already reports a table (anything but 0xFFFF), because a second
     * table would clobber one that may have come from the vendor app. Then comes
     * the one-shot per-serial record: a batch is sent at most once for a given
     * sensor serial, ever, across reconnects and app updates, because the write
     * is irreversible and a repeated failure is just BLE noise against a sensor
     * someone is wearing.
     */
    fun gateSkipReason(
        f6Value: Int?,
        isAuthenticated: Boolean,
        launcherState: Int,
        writeAttempted: Boolean,
    ): String? = when {
        f6Value == null -> "F6 unreadable"
        f6Value != ICanHealthConstants.CORRECT_CODE_ABSENT_VALUE ->
            "F6=0x${"%04X".format(f6Value)} (table already present, never overwrite)"
        writeAttempted ->
            "one-shot attempt record already exists for this sensor serial " +
                "(developer reset: ICanHealthExperimental.resetWriteAttempt)"
        !isAuthenticated -> "vendor auth not completed"
        launcherState != ICanHealthConstants.LAUNCHER_STATE_IDLE ->
            "launcherState=0x${"%02X".format(launcherState and 0xFF)} (not idle)"
        else -> null
    }

    /**
     * Build the full write plan, or null when the gate refuses or the framing
     * cannot be built. Pure: no BLE, no persistence, no state. The caller owns
     * the one-shot record and the MTU fit check.
     */
    fun planCorrectCodeWrite(
        f6Value: Int?,
        isAuthenticated: Boolean,
        launcherState: Int,
        writeAttempted: Boolean,
        table: FloatArray?,
        key: ByteArray?,
        iv: ByteArray?,
        path: CorrectCodeProvisioningPath,
        launcherVersionFlag: Boolean,
        layout: CorrectCodeLayout,
    ): CorrectCodePlan? {
        gateSkipReason(f6Value, isAuthenticated, launcherState, writeAttempted)?.let { reason ->
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
            path = path,
            launcherVersionFlag = launcherVersionFlag,
            layout = layout,
        ) ?: return null
        if (writePackets.isEmpty()) {
            Log.e(TAG, "correctCode framing produced no F2 packets — refusing to commit an empty batch")
            return null
        }
        return CorrectCodePlan(
            writePackets = writePackets,
            commitPacket = buildCommitPacket(),
            path = path,
            launcherVersionFlag = launcherVersionFlag,
            layout = layout,
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
