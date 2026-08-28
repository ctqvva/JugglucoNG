// JugglucoNG — AiDex Native Kotlin Driver
// AiDexPreSessionRecovery.kt — one-shot pre-session 0xF3 probe for wedged F-generation sensors
//
// Diagnostic. The wire framing here is NOT confirmed by disassembly.

package tk.glucodata.drivers.aidex.native.protocol

import tk.glucodata.drivers.aidex.native.crypto.AesCfb128
import tk.glucodata.drivers.aidex.native.crypto.Crc16CcittFalse

/**
 * A single pre-session `0xF3` probe for an F-generation sensor that answers the F001 challenge
 * with a bare `00` instead of a PAIR key.
 *
 * Background: a sensor in that state is unreachable by the vendor app too, so the driver has no
 * session key and therefore cannot build the normal encrypted `0xF3`. Sensor firmware is reported
 * to return the existing PAIR key rather than wiping when `0xF3` arrives after the sensor has been
 * running for an hour, which would make it a lost-key recovery rather than a reset.
 *
 * That report is the entire basis for this probe and it is unverified, so the scope is kept as
 * small as it can be while still being able to succeed:
 *
 * - It is NOT the Reset lifecycle path. It never sends RESET (`0xF0`) or DELETE_BOND (`0xF2`),
 *   never removes the Android bond, and never clears locally stored history.
 * - It fires at most once per connection, only for F-generation hardware, and only after an
 *   explicit one-byte `00` rejection — never on a timeout or a generic failure.
 * - Nothing it receives is stored directly. A response only matters if it re-enters the normal
 *   key exchange as a full-width PAIR key and survives BOND CRC-8 validation.
 *
 * Both candidate framings are built from material derivable from the serial alone, because that
 * is all that exists before a session: the bare plaintext frame, and the same frame keyed by the
 * SN secret that the F001 challenge already exposes.
 */
class AiDexPreSessionRecovery {

    /** Which framing a probe frame uses. Reported in logs so a trace identifies what was sent. */
    enum class Candidate {
        /** `[0xF3, CRC16_lo, CRC16_hi]` with no encryption layer. */
        PLAINTEXT,

        /** The plaintext frame, AES-128-CFB encrypted under the SN secret with the SN IV. */
        SN_SECRET,
    }

    data class Frame(val candidate: Candidate, val bytes: ByteArray) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Frame) return false
            return candidate == other.candidate && bytes.contentEquals(other.bytes)
        }

        override fun hashCode(): Int = 31 * candidate.hashCode() + bytes.contentHashCode()
    }

    private var started = false
    private var nextIndex = 0

    /** True once this connection has begun a probe, whether or not it produced anything. */
    val hasStarted: Boolean get() = started

    /** Clear the one-shot. Called wherever the key exchange itself is reset. */
    fun reset() {
        started = false
        nextIndex = 0
    }

    /**
     * Whether an F001 notification is the specific rejection this probe targets.
     *
     * Only a single `0x00` on an outstanding challenge qualifies. A short payload that is not
     * `00`, or one arriving once a PAIR key already exists, is a different failure and is left
     * for the normal paths to handle.
     */
    fun shouldAttempt(
        isFGeneration: Boolean,
        challengeWritten: Boolean,
        hasPairKey: Boolean,
        response: ByteArray,
    ): Boolean {
        if (started) return false
        if (!isFGeneration || !challengeWritten || hasPairKey) return false
        return response.size == 1 && (response[0].toInt() and 0xFF) == 0x00
    }

    /** Latch the one-shot. Returns false if a probe already ran on this connection. */
    fun begin(): Boolean {
        if (started) return false
        started = true
        nextIndex = 0
        return true
    }

    /**
     * The candidate frames in send order. The SN-secret framing is omitted when the serial does
     * not yield usable 16-byte key material.
     */
    fun frames(snSecret: ByteArray, snIv: ByteArray): List<Frame> {
        val plaintext = Crc16CcittFalse.makeCommand(AiDexOpcodes.CLEAR_STORAGE)
        val frames = mutableListOf(Frame(Candidate.PLAINTEXT, plaintext))
        AesCfb128.encrypt(plaintext, snSecret, snIv)?.let {
            frames += Frame(Candidate.SN_SECRET, it)
        }
        return frames
    }

    /**
     * Pop the next frame to send, or null once every candidate has been tried.
     * The caller sends these one at a time and stops as soon as the sensor answers.
     */
    fun nextFrame(snSecret: ByteArray, snIv: ByteArray): Frame? {
        val all = frames(snSecret, snIv)
        if (nextIndex >= all.size) return null
        return all[nextIndex++]
    }
}
