// Libre3DataPlane.kt — GATT fragmentation and the post-authorization encrypted data plane.
//
// Security writes:   offset_LE2 || up to 18 payload bytes (always sent as a 20-byte value, as the
//                    legacy callback has always written them; every message is a multiple of 18).
// Security notifies: seq_u8 || payload; the expected length is announced first on the command
//                    characteristic as [kind, length].
// Data frames:       AES-CCM-128(kEnc), 4-byte tag, trailer seq_LE2;
//                    nonce13 = seq_LE2 || descriptor3 || ivEnc8.
// Descriptor per channel is the legacy native mapping (bcrypt.cpp packetDescriptor[kind]),
// which RoundWhiteDiscKit's preferred kinds agree with for status/glucose/history.

package tk.glucodata.drivers.libre3

import java.util.UUID

object Libre3Gatt {
    private fun uuid(prefix: String): UUID = UUID.fromString("$prefix-ef89-11e9-81b4-2a2ae2dbcce4")

    val DATA_SERVICE: UUID = uuid("089810cc")
    val SECURITY_SERVICE: UUID = uuid("0898203a")

    val PATCH_CONTROL: UUID = uuid("08981338")
    val PATCH_STATUS: UUID = uuid("08981482")
    val EVENT_LOG: UUID = uuid("08981bee")
    val GLUCOSE_DATA: UUID = uuid("0898177a")
    val HISTORIC_DATA: UUID = uuid("0898195a")
    val CLINICAL_DATA: UUID = uuid("08981ab8")
    val FACTORY_DATA: UUID = uuid("08981d24")

    val SEC_COMMAND_RESPONSE: UUID = uuid("08982198")
    val SEC_CHALLENGE_DATA: UUID = uuid("089822ce")
    val SEC_CERT_DATA: UUID = uuid("089823fa")

    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    /** Security notifications, enabled in this order before the handshake. */
    val SECURITY_NOTIFY_ORDER: List<UUID> = listOf(SEC_COMMAND_RESPONSE, SEC_CERT_DATA, SEC_CHALLENGE_DATA)

    /**
     * Data notifications, enabled one CCCD at a time after Phase 6 in the order the legacy callback
     * has used on Android. The sensor does not stream until these are freshly enabled.
     */
    val DATA_NOTIFY_ORDER: List<UUID> =
        listOf(PATCH_CONTROL, EVENT_LOG, HISTORIC_DATA, CLINICAL_DATA, FACTORY_DATA, GLUCOSE_DATA, PATCH_STATUS)
}

object Libre3Framing {
    const val WRITE_CHUNK = 18
    const val WRITE_FRAGMENT_SIZE = 20

    fun fragmentForWrite(message: ByteArray): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        var offset = 0
        while (offset < message.size) {
            val take = minOf(WRITE_CHUNK, message.size - offset)
            val frag = ByteArray(WRITE_FRAGMENT_SIZE)
            Libre3Bytes.putU16le(frag, 0, offset)
            System.arraycopy(message, offset, frag, 2, take)
            out += frag
            offset += take
        }
        return out
    }

    /** Reassembles one announced security message from `seq || payload` notifications. */
    class SecurityAssembler(val expectedLength: Int) {
        private val buffer = java.io.ByteArrayOutputStream(expectedLength)
        private var lastSeq = -1

        /** Returns the message once complete, null while more fragments are due. */
        fun feed(fragment: ByteArray): ByteArray? {
            if (fragment.isEmpty()) throw IllegalStateException("empty security fragment")
            val seq = fragment[0].toInt() and 0xFF
            if (lastSeq >= 0 && seq != ((lastSeq + 1) and 0xFF)) {
                throw IllegalStateException("security fragment sequence $seq after $lastSeq")
            }
            lastSeq = seq
            buffer.write(fragment, 1, fragment.size - 1)
            if (buffer.size() < expectedLength) return null
            return buffer.toByteArray().copyOf(expectedLength)
        }
    }

    /** glucoseData arrives as 15 + 20 bytes; one frame is 29 plaintext + 4 tag + 2 sequence. */
    class GlucoseAssembler {
        private val buffer = java.io.ByteArrayOutputStream(FRAME_SIZE)

        fun feed(fragment: ByteArray): ByteArray? {
            buffer.write(fragment)
            if (buffer.size() < FRAME_SIZE) return null
            val all = buffer.toByteArray()
            buffer.reset()
            if (all.size > FRAME_SIZE) buffer.write(all, FRAME_SIZE, all.size - FRAME_SIZE)
            return all.copyOf(FRAME_SIZE)
        }

        fun reset() = buffer.reset()

        companion object {
            const val FRAME_SIZE = 35
        }
    }
}

enum class Libre3Channel(val uuid: UUID, val descriptorKind: Int) {
    PATCH_CONTROL(Libre3Gatt.PATCH_CONTROL, 1),
    PATCH_STATUS(Libre3Gatt.PATCH_STATUS, 2),
    GLUCOSE_DATA(Libre3Gatt.GLUCOSE_DATA, 3),
    HISTORIC_DATA(Libre3Gatt.HISTORIC_DATA, 4),
    CLINICAL_DATA(Libre3Gatt.CLINICAL_DATA, 5),
    EVENT_LOG(Libre3Gatt.EVENT_LOG, 6),
    FACTORY_DATA(Libre3Gatt.FACTORY_DATA, 7);

    companion object {
        fun of(uuid: UUID): Libre3Channel? = entries.firstOrNull { it.uuid == uuid }
    }
}

/**
 * One authorized link's data-plane crypto. Outbound sequence numbers start at 1 for each new
 * session, as the legacy native code does (bcrypt.cpp, initcrypt).
 */
class Libre3DataPlaneSession(kEnc: ByteArray, private val ivEnc: ByteArray) {
    init {
        require(kEnc.size == 16 && ivEnc.size == 8) { "kEnc must be 16 bytes and ivEnc 8 bytes" }
    }

    private val ccm = Libre3AesCcm(kEnc)
    private var outSequence = 1

    data class Decrypted(val plaintext: ByteArray, val sequence: Int, val descriptorKind: Int)

    fun nonce(sequence: Int, descriptorKind: Int): ByteArray {
        val nonce = ByteArray(13)
        Libre3Bytes.putU16le(nonce, 0, sequence)
        System.arraycopy(DESCRIPTORS[descriptorKind], 0, nonce, 2, 3)
        System.arraycopy(ivEnc, 0, nonce, 5, 8)
        return nonce
    }

    /**
     * Decrypts a frame received on [channel] under that channel's own descriptor only, as the
     * legacy native intDecrypt does. A frame sealed for another channel would otherwise be parsed
     * with this channel's layout; it is rejected (null) instead.
     */
    fun decrypt(channel: Libre3Channel, frame: ByteArray): Decrypted? {
        if (frame.size < 2 + Libre3Auth.TAG_SIZE) return null
        val sequence = Libre3Bytes.u16le(frame, frame.size - 2)
        val body = frame.copyOfRange(0, frame.size - 2)
        val pt = try {
            ccm.decrypt(nonce(sequence, channel.descriptorKind), body, Libre3Auth.TAG_SIZE)
        } catch (_: Libre3AesCcm.AuthenticationException) {
            return null
        }
        return Decrypted(pt, sequence, channel.descriptorKind)
    }

    /** A patchControl write: ct || tag4 || seq_LE2 with descriptor 0. */
    @Synchronized
    fun encryptControl(plaintext: ByteArray): ByteArray {
        val sequence = outSequence and 0xFFFF
        outSequence++
        val sealed = ccm.encrypt(nonce(sequence, 0), plaintext, Libre3Auth.TAG_SIZE)
        val out = sealed.copyOf(sealed.size + 2)
        Libre3Bytes.putU16le(out, sealed.size, sequence)
        return out
    }

    /** Test helper and replay tooling: seal a frame the way the sensor does. */
    fun encryptInbound(channel: Libre3Channel, plaintext: ByteArray, sequence: Int): ByteArray {
        val sealed = ccm.encrypt(nonce(sequence, channel.descriptorKind), plaintext, Libre3Auth.TAG_SIZE)
        val out = sealed.copyOf(sealed.size + 2)
        Libre3Bytes.putU16le(out, sealed.size, sequence)
        return out
    }

    companion object {
        private val DESCRIPTORS: Array<ByteArray> = arrayOf(
            byteArrayOf(0x00, 0x00, 0x00),
            byteArrayOf(0x00, 0x00, 0x0F),
            byteArrayOf(0x00, 0x00, 0xF0.toByte()),
            byteArrayOf(0x00, 0x0F, 0x00),
            byteArrayOf(0x00, 0xF0.toByte(), 0x00),
            byteArrayOf(0x0F, 0x00, 0x00),
            byteArrayOf(0xF0.toByte(), 0x00, 0x00),
            byteArrayOf(0x44, 0x00, 0x00),
        )
    }
}
