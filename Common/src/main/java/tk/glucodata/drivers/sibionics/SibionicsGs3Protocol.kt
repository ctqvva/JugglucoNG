package tk.glucodata.drivers.sibionics

import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * Wire protocol of the Sibionics GS3 CN firmware (FF30 service, AES/RC4 framed).
 *
 * This is a different protocol from the GS1/Sibionics 2 V120 one in [SibionicsProtocol]:
 * the sensor reports finished glucose values (mmol/L x 10), so there is no local algorithm.
 *
 * Plaintext frame:
 * ```
 * byte 0      len   bytes before the CRC
 * byte 1      cmd
 * byte 2..    payload
 * byte len    crc8  (-sum(bytes[0..len-1])) & 0xFF
 * ```
 * The whole frame, length byte and CRC included, is encrypted as one buffer, so there is
 * no visible length prefix on air. Every cipher here is a stream cipher (AES-128 in OFB
 * mode, or RC4): the keystream depends only on the byte position, which is what lets a
 * prefix of a ciphertext be decrypted on its own.
 */
object SibionicsGs3Protocol {
    // Requests built by the client.
    const val CMD_AUTH_V2 = 0x01
    const val CMD_TIME = 0x03
    const val CMD_ACTIVATE = 0x0F
    const val CMD_ID_BIND = 0x13
    const val CMD_GET_DATA_V2 = 0x14
    const val CMD_BIND_IN = 0x35
    const val CMD_GET_DATA_V3 = 0x39
    const val CMD_AUTH_V3 = 0xE2
    const val CMD_SYS_INFO = 0xF0

    /** Sent by the sensor only; its ACK (0xC039) is handled like the BindIn ACK. */
    const val CMD_BIND_IN_ALT = 0x38

    // Glucose pages sent by the sensor.
    const val CMD_DATA_LIVE = 0x32
    const val CMD_DATA_HISTORY = 0x39

    const val SYSINFO_V3_READY = 0x0F
    const val SYSINFO_V2_FIRST = 0x01
    const val SYSINFO_V2_SECOND = 0x07

    const val ID_BIND_RESULT_OK = 0
    const val ID_BIND_RESULT_WRONG_ACCOUNT = 2

    const val RECORD_SIZE = 16
    private const val PAGE_HEADER_SIZE = 5
    private const val RECORD_GLUCOSE_OFFSET = 4 // frame offset 9..10 for the first record
    private const val RECORD_TREND_OFFSET = 14 // frame offset 19 for the first record

    /**
     * Spacing between consecutive record indices. Ages are counted in records, and the
     * Sibionics family reports one reading per minute.
     */
    const val RECORD_INTERVAL_MS = 60_000L

    /** AES-128 key for whole-frame encryption; also the RC4 key ([SibionicsProtocol.encrypt]). */
    internal val AUTH_KEY = hex("01380B9A005B025DCD9EC3990937AAE8")

    /** Second layer on the two glucose bytes of every record, used with a zero IV. */
    internal val GLUCOSE_KEY = hex("11B8AB00014B2A5FCB91F3AA9927BFE0")

    /**
     * The 16-byte session key of the Chinese app (com.sisensing.sisensingcgm). It is what
     * [SibionicsProtocol.deriveSessionKey] yields for the CN registration key.
     */
    internal val SESSION_KEY = "GKSHGDU0TYA456G4".toByteArray(Charsets.US_ASCII)

    /** Fixed, not random: the app sends the same nonce on every auth and bind. */
    internal val FIXED_NONCE = hex("1C7C8BDB4E02000000000000")

    private val ZERO_IV = ByteArray(16)

    enum class FrameCipher {
        PLAIN,

        /** Default: AES-128 OFB, IV = identity MAC (6 bytes) + 10 zero bytes. */
        REGISTERED_V3,

        /** Fallback: AES-128 OFB with an all-zero IV. */
        AES_OFB_ZERO,

        /** V2 fallback: RC4 with [AUTH_KEY]. */
        RC4,
    }

    fun replyType(requestCmd: Int): Int = (requestCmd or 0xC000) + 1

    // ---- crypto ----

    /**
     * AES-128 ECB used as an OFB keystream: the IV is re-encrypted for every 16-byte block
     * and XORed onto the input. Encryption and decryption are the same operation.
     */
    internal fun aesOfb(key: ByteArray, iv: ByteArray, input: ByteArray): ByteArray {
        require(key.size == 16) { "AES-128 key must be 16 bytes" }
        require(iv.size == 16) { "IV must be 16 bytes" }
        val aes = Cipher.getInstance("AES/ECB/NoPadding")
        aes.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
        var block = iv.copyOf()
        val out = ByteArray(input.size)
        for (i in input.indices) {
            if (i % 16 == 0) block = aes.doFinal(block)
            out[i] = (input[i].toInt() xor block[i % 16].toInt()).toByte()
        }
        return out
    }

    internal fun registeredIv(mac: ByteArray): ByteArray {
        require(mac.size == 6) { "identity MAC must be 6 bytes" }
        return mac.copyOf(16)
    }

    /** Symmetric: applies the cipher's keystream. Null when [cipher] needs a MAC and has none. */
    fun applyCipher(cipher: FrameCipher, mac: ByteArray?, input: ByteArray): ByteArray? =
        when (cipher) {
            FrameCipher.PLAIN -> input.copyOf()
            FrameCipher.REGISTERED_V3 -> mac?.takeIf { it.size == 6 }?.let { aesOfb(AUTH_KEY, registeredIv(it), input) }
            FrameCipher.AES_OFB_ZERO -> aesOfb(AUTH_KEY, ZERO_IV, input)
            FrameCipher.RC4 -> SibionicsProtocol.encrypt(input)
        }

    // ---- framing ----

    fun crc(bytes: ByteArray, count: Int): Byte {
        var sum = 0
        for (i in 0 until count) sum = (sum + (bytes[i].toInt() and 0xFF)) and 0xFF
        return ((-sum) and 0xFF).toByte()
    }

    fun frame(cmd: Int, payload: ByteArray = ByteArray(0)): ByteArray {
        val len = 2 + payload.size
        require(len <= 0xFF) { "frame too long" }
        val out = ByteArray(len + 1)
        out[0] = len.toByte()
        out[1] = cmd.toByte()
        payload.copyInto(out, 2)
        out[len] = crc(out, len)
        return out
    }

    /** Exactly one plaintext frame whose length byte and CRC agree with [plain]. */
    fun isValidFrame(plain: ByteArray): Boolean {
        if (plain.size < 3) return false
        val len = plain[0].toInt() and 0xFF
        if (len < 2 || len + 1 != plain.size) return false
        return crc(plain, len) == plain[len]
    }

    data class Decoded(val cipher: FrameCipher, val plain: ByteArray)

    /**
     * Order in which an incoming notification is tried: plain, the session's current cipher,
     * REGISTERED_V3, AES_OFB_ZERO, RC4. The first buffer passing the length/CRC check wins.
     */
    fun incomingOrder(current: FrameCipher): List<FrameCipher> =
        listOf(
            FrameCipher.PLAIN,
            current,
            FrameCipher.REGISTERED_V3,
            FrameCipher.AES_OFB_ZERO,
            FrameCipher.RC4,
        ).distinct()

    fun decodeIncoming(raw: ByteArray, current: FrameCipher, mac: ByteArray?): Decoded? {
        for (cipher in incomingOrder(current)) {
            val plain = applyCipher(cipher, mac, raw) ?: continue
            if (isValidFrame(plain)) return Decoded(cipher, plain)
        }
        return null
    }

    /**
     * Whether [raw] could be the start of a longer frame under some cipher: the decrypted
     * length byte announces more bytes than have arrived. Used to hold a fragment until the
     * rest of a frame split over several notifications arrives.
     */
    fun couldBeFramePrefix(raw: ByteArray, current: FrameCipher, mac: ByteArray?): Boolean {
        if (raw.isEmpty()) return false
        return incomingOrder(current).any { cipher ->
            val first = applyCipher(cipher, mac, raw.copyOf(1))?.get(0)?.toInt()?.and(0xFF) ?: return@any false
            first >= 2 && first + 1 > raw.size
        }
    }

    fun encodeOutgoing(cipher: FrameCipher, mac: ByteArray?, plain: ByteArray): ByteArray? =
        applyCipher(cipher, mac, plain)

    // ---- request builders (plaintext; the session applies the frame cipher) ----

    fun buildAuthV3(mac: ByteArray, sessionKey: ByteArray = SESSION_KEY): ByteArray {
        require(mac.size == 6 && sessionKey.size == 16)
        return frame(CMD_AUTH_V3, byteArrayOf(0x00) + mac + sessionKey + FIXED_NONCE)
    }

    fun buildAuthV2(mac: ByteArray, sessionKey: ByteArray = SESSION_KEY): ByteArray {
        require(mac.size == 6 && sessionKey.size == 16)
        return frame(CMD_AUTH_V2, byteArrayOf(0x00) + mac + sessionKey)
    }

    fun buildBindIn(unixSeconds: Long): ByteArray =
        frame(CMD_BIND_IN, u32Le(unixSeconds) + FIXED_NONCE)

    fun buildSysInfo(subtype: Int): ByteArray =
        frame(CMD_SYS_INFO, byteArrayOf(subtype.toByte()))

    fun buildIdBind(seq: Int): ByteArray =
        frame(CMD_ID_BIND, byteArrayOf(seq.toByte()) + FIXED_NONCE)

    fun buildActivate(unixSeconds: Long): ByteArray =
        frame(CMD_ACTIVATE, u32Le(unixSeconds))

    fun buildTime(unixSeconds: Long): ByteArray =
        frame(CMD_TIME, u32Le(unixSeconds))

    fun buildGetDataV3(startIndex: Int): ByteArray =
        frame(CMD_GET_DATA_V3, u16Le(startIndex) + byteArrayOf(0, 0))

    fun buildGetDataV2(startIndex: Int): ByteArray =
        frame(CMD_GET_DATA_V2, u16Le(startIndex) + byteArrayOf(0, 0))

    /** `cursor == 0 ? 1 : cursor + 1`, kept inside the 16-bit request field. */
    fun requestStartIndex(cursor: Int): Int =
        if (cursor <= 0) 1 else (cursor + 1).coerceAtMost(0xFFFF)

    // ---- incoming frames ----

    sealed class Message {
        /** `04 <cmd> <result> <arg> CRC`, or the 10-byte BindIn form. */
        data class Ack(val cmd: Int, val result: Int, val arg: Int) : Message() {
            val replyType: Int get() = 0xC000 or cmd
        }

        data class SysInfo(val subtype: Int, val payload: ByteArray) : Message()

        data class GlucosePage(
            val cmd: Int,
            val startIndex: Int,
            /** The trailing 16-bit field (`last` in the vendor code). */
            val trailer: Int,
            val records: List<Record>,
        ) : Message() {
            val isHistoryPage: Boolean get() = cmd == CMD_DATA_HISTORY
        }

        data class Unknown(val plain: ByteArray) : Message()
    }

    data class Record(
        val index: Int,
        val glucoseX10: Int,
        val trend: SibionicsProtocol.Trend,
        /** Records between this one and the newest the sensor holds; 0 is current. */
        val ageRecords: Int,
        val raw: ByteArray,
    ) {
        val mmol: Float get() = glucoseX10 / 10f
        val mgdl: Float get() = mmol * SibionicsConstants.MGDL_PER_MMOLL
        val isPlausible: Boolean
            get() = glucoseX10 > 0 && mmol <= SibionicsConstants.MAX_ALGORITHM_GLUCOSE_MMOL
    }

    fun parse(plain: ByteArray): Message {
        if (!isValidFrame(plain)) return Message.Unknown(plain)
        val cmd = plain[1].toInt() and 0xFF
        if ((cmd == CMD_DATA_LIVE || cmd == CMD_DATA_HISTORY) && plain.size >= PAGE_HEADER_SIZE + 3) {
            val count = plain[2].toInt() and 0xFF
            if (plain.size == count * RECORD_SIZE + 8) return parsePage(plain, cmd, count)
        }
        // A SysInfo request (0xF0) is answered as 0xF1 by the ACK rule; accept either.
        if ((cmd == CMD_SYS_INFO || cmd == CMD_SYS_INFO + 1) && plain.size >= 4) {
            return Message.SysInfo(
                subtype = plain[2].toInt() and 0xFF,
                payload = plain.copyOfRange(3, plain.size - 1),
            )
        }
        if (plain.size == 5 || plain.size == 10) {
            return Message.Ack(
                cmd = cmd,
                result = plain[2].toInt() and 0xFF,
                arg = plain[3].toInt() and 0xFF,
            )
        }
        return Message.Unknown(plain)
    }

    private fun parsePage(plain: ByteArray, cmd: Int, count: Int): Message.GlucosePage {
        val start = u16Le(plain, 3)
        val trailer = u16Le(plain, PAGE_HEADER_SIZE + count * RECORD_SIZE)
        val records = ArrayList<Record>(count)
        for (i in 0 until count) {
            val base = PAGE_HEADER_SIZE + i * RECORD_SIZE
            val glucose = aesOfb(
                GLUCOSE_KEY,
                ZERO_IV,
                byteArrayOf(plain[base + RECORD_GLUCOSE_OFFSET], plain[base + RECORD_GLUCOSE_OFFSET + 1]),
            )
            records += Record(
                index = start + i,
                glucoseX10 = u16Le(glucose, 0),
                trend = SibionicsProtocol.Trend.fromFlags(plain[base + RECORD_TREND_OFFSET].toInt() and 0xFF),
                ageRecords = ageRecords(cmd, trailer, count, i),
                raw = plain.copyOfRange(base, base + RECORD_SIZE),
            )
        }
        return Message.GlucosePage(cmd, start, trailer, records)
    }

    /**
     * History pages (0x39): `(last + count - 1) - i`, i.e. the trailer counts the records the
     * sensor holds beyond this page. Live pages (0x32) are current: the vendor code uses 0 for
     * every record. A multi-record live page is spaced back from its newest record instead, so
     * its readings do not all land on the same minute and overwrite each other.
     */
    internal fun ageRecords(cmd: Int, trailer: Int, count: Int, i: Int): Int =
        if (cmd == CMD_DATA_HISTORY) (trailer + count - 1) - i else (count - 1) - i

    // ---- helpers ----

    fun toHex(bytes: ByteArray): String =
        bytes.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }

    private fun hex(s: String): ByteArray =
        ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private fun u16Le(value: Int): ByteArray =
        byteArrayOf((value and 0xFF).toByte(), ((value ushr 8) and 0xFF).toByte())

    private fun u32Le(value: Long): ByteArray =
        byteArrayOf(
            (value and 0xFF).toByte(),
            ((value ushr 8) and 0xFF).toByte(),
            ((value ushr 16) and 0xFF).toByte(),
            ((value ushr 24) and 0xFF).toByte(),
        )

    private fun u16Le(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)
}
