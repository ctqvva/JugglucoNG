package tk.glucodata.drivers.sibionics

import java.util.Locale
import kotlin.math.max

object SibionicsProtocol {
    private val masterKey = byteArrayOf(
        0x01, 0x38, 0x0B, 0x9A.toByte(), 0x00, 0x5B, 0x02, 0x5D,
        0xCD.toByte(), 0x9E.toByte(), 0xC3.toByte(), 0x99.toByte(),
        0x09, 0x37, 0xAA.toByte(), 0xE8.toByte(),
    )

    private val v120AuthMarker = byteArrayOf(0x23, 0xF7.toByte(), 0x6F, 0xD9.toByte(), 0xF4.toByte())

    enum class Trend(val code: Int) {
        NOT_DETERMINED(0),
        RISING_RAPIDLY(1),
        RISING(2),
        RISING_SLOWLY(3),
        STABLE(4),
        FALLING_SLOWLY(5),
        FALLING(6),
        FALLING_RAPIDLY(7);

        companion object {
            fun fromFlags(flags: Int): Trend =
                entries.firstOrNull { it.code == ((flags ushr 3) and 0x07) } ?: NOT_DETERMINED
        }
    }

    sealed class ParseResult {
        data class Handshake(val response: ResponseType) : ParseResult()
        data class V120Data(val entries: List<V120Entry>) : ParseResult()
        data class ChineseData(val entries: List<ChineseEntry>) : ParseResult()
        data object ChineseEcho : ParseResult()
        data object V120AuthRequired : ParseResult()
        data class ChecksumError(val plaintext: ByteArray) : ParseResult()
        data class Unknown(val bytes: ByteArray) : ParseResult()
    }

    enum class ResponseType(val code: Int) {
        AUTH_ACCEPTED(0x01),
        TIME_SYNC_NEEDED(0x07),
        DATA_REQUESTED(0x03),
        STREAMING_READY(0x08);

        companion object {
            fun fromCode(code: Int): ResponseType? = entries.firstOrNull { it.code == code }
        }
    }

    data class V120Entry(
        val index: Int,
        val eventTimeMs: Long,
        val rawTemperature: Int,
        val rawImpedance: Int,
        val rawGlucose: Int,
        val trend: Trend,
        val reindex: Int,
    ) {
        val rawMmol: Float get() = rawGlucose / 10f
        val rawMgdl: Float get() = rawMmol * SibionicsConstants.MGDL_PER_MMOLL
        val temperatureC: Float get() = rawTemperature / 10f
        val isLive: Boolean get() = reindex == 0
    }

    data class ChineseEntry(
        val index: Int,
        val rawTemperature: Int,
        val rawImpedance: Int,
        val rawGlucose: Int,
        val status: Int,
        val numOfUnreceived: Int,
        val addTimeSeconds: Int,
    ) {
        val rawMmol: Float get() = rawGlucose / 10f
        val rawMgdl: Float get() = rawMmol * SibionicsConstants.MGDL_PER_MMOLL
        val temperatureC: Float get() = rawTemperature / 10f
        val isLive: Boolean get() = numOfUnreceived == 0

        fun eventTimeMs(nowMs: Long): Long {
            val offsetSeconds = addTimeSeconds - numOfUnreceived * 60
            // Chinese firmware occasionally reports a positive addTime after reconnect.
            // It is not a trustworthy future wall-clock timestamp: the legacy driver also
            // anchored these packets at receipt time. Persisting it verbatim advances the
            // native high-water mark and suppresses the following legitimate minute(s).
            return nowMs + offsetSeconds.coerceAtMost(0) * 1000L
        }
    }

    fun encrypt(plain: ByteArray): ByteArray = rc4(masterKey, plain)

    fun decrypt(encrypted: ByteArray): ByteArray = encrypt(encrypted)

    fun checksum(bytes: ByteArray, count: Int = bytes.size): Byte {
        var sum = 0
        for (i in 0 until count) {
            sum = (sum + (bytes[i].toInt() and 0xFF)) and 0xFF
        }
        return ((-sum) and 0xFF).toByte()
    }

    fun verifyChecksum(plaintext: ByteArray): Boolean {
        if (plaintext.size < 2) return false
        val length = plaintext[0].toInt() and 0xFF
        if (length >= plaintext.size) return false
        var sum = 0
        for (i in 0..length) {
            sum = (sum + (plaintext[i].toInt() and 0xFF)) and 0xFF
        }
        return sum == 0
    }

    fun deriveSessionKey(variant: SibionicsConstants.Variant): ByteArray? {
        val hex = variant.registrationKeyHex.uppercase(Locale.US)
        if (hex.length < 44 || hex.length % 2 != 0) return null
        val packed = ByteArray(hex.length / 2)
        for (i in packed.indices) {
            packed[i] = hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        val decrypted = decrypt(packed)
        if (decrypted.size < 22) return null
        val app = decrypted.drop(22)
            .takeWhile { (it.toInt() and 0xFF) in 0x20..0x7E }
            .map { it.toInt().toChar() }
            .joinToString("")
        if (!app.startsWith(variant.appId)) return null
        return decrypted.copyOfRange(6, 22)
    }

    fun buildAuthPacket(macAddress: ByteArray?, sessionKey: ByteArray): ByteArray {
        val mac = macAddress.takeIf { it?.size == 6 } ?: ByteArray(6)
        require(sessionKey.size == 16)
        val p = ByteArray(26)
        p[0] = 0x19
        p[1] = 0x01
        p[2] = 0x00
        for (i in 0 until 6) p[3 + i] = mac[5 - i]
        for (i in 0 until 16) p[9 + i] = sessionKey[i]
        p[25] = checksum(p, 25)
        return encrypt(p)
    }

    fun buildActivationPacket(unixSeconds: Long = System.currentTimeMillis() / 1000L): ByteArray {
        val p = ByteArray(11)
        p[0] = 0x0A
        p[1] = 0x07
        writeU32Le(p, 2, unixSeconds)
        writeU32Le(p, 6, 1234L)
        p[10] = checksum(p, 10)
        return encrypt(p)
    }

    fun buildTimeSyncPacket(unixSeconds: Long = System.currentTimeMillis() / 1000L): ByteArray {
        val p = ByteArray(7)
        p[0] = 0x06
        p[1] = 0x03
        writeU32Le(p, 2, unixSeconds)
        p[6] = checksum(p, 6)
        return encrypt(p)
    }

    fun buildDataRequestPacket(lastIndex: Int): ByteArray {
        val p = ByteArray(7)
        p[0] = 0x06
        p[1] = 0x08
        writeU16Le(p, 2, lastIndex.coerceIn(0, 0xFFFF))
        p[4] = 0
        p[5] = 0
        p[6] = checksum(p, 6)
        return encrypt(p)
    }

    fun buildResetPacket(resetType: Int = 0): ByteArray {
        val p = ByteArray(4)
        p[0] = 0x03
        p[1] = 0x10
        p[2] = (resetType and 0xFF).toByte()
        p[3] = checksum(p, 3)
        return encrypt(p)
    }

    /** Direct FF32 reset command observed on GS1/V120 firmware. */
    fun buildGs1ResetPacket(): ByteArray = byteArrayOf(0x24, 0xE7.toByte(), 0x6F, 0x34)

    /** Direct FF32 reset command used by Chinese AA55 firmware. */
    fun buildChineseResetPacket(): ByteArray =
        byteArrayOf(0xAA.toByte(), 0x55, 0x10, 0xF1.toByte())

    fun buildMaintenanceResetPacket(variant: SibionicsConstants.Variant): ByteArray = when (variant) {
        SibionicsConstants.Variant.EU,
        SibionicsConstants.Variant.HEMATONIX -> buildGs1ResetPacket()

        SibionicsConstants.Variant.CHINESE -> buildChineseResetPacket()
        SibionicsConstants.Variant.SIBIONICS2,
        SibionicsConstants.Variant.GS3 -> buildResetPacket(0)
    }

    fun estimateChineseHistoryTotal(
        previousTotal: Int,
        receivedCount: Int,
        entries: List<ChineseEntry>,
    ): Int {
        if (entries.isEmpty()) return maxOf(previousTotal, receivedCount)
        return maxOf(
            previousTotal,
            receivedCount + entries.minOf { it.numOfUnreceived },
            receivedCount,
        )
    }

    fun buildChineseDataRequest(lastIndex: Int, macAddress: ByteArray?): ByteArray {
        val mac = macAddress.takeIf { it?.size == 6 } ?: ByteArray(6)
        val p = ByteArray(20)
        p[0] = 0xAA.toByte()
        p[1] = 0x55
        p[2] = 0x07
        writeU16Le(p, 3, max(lastIndex, 1).coerceIn(1, 0xFFFF))
        for (i in 0 until 6) p[5 + i] = mac[5 - i]
        p[19] = checksum(p, 19)
        return p
    }

    fun parseChinese(raw: ByteArray): ParseResult {
        if (raw.contentEquals(v120AuthMarker)) return ParseResult.V120AuthRequired
        if (raw.size >= 3 && raw[0] == 0xAA.toByte() && raw[1] == 0x55.toByte() && raw[2] == 0x07.toByte()) {
            return ParseResult.ChineseEcho
        }
        if (raw.size < 4 || raw[0] != 0xAA.toByte() || raw[1] != 0x55.toByte() || raw[2] != 0x09.toByte()) {
            return ParseResult.Unknown(raw)
        }
        val expected = checksum(raw, raw.size - 1)
        if (raw.last() != expected) return ParseResult.ChecksumError(raw)
        val count = raw[3].toInt() and 0xFF
        val expectedSize = 4 + count * 14 + 1
        if (raw.size < expectedSize) return ParseResult.Unknown(raw)
        val entries = ArrayList<ChineseEntry>(count)
        for (i in 0 until count) {
            val o = 4 + i * 14
            entries += ChineseEntry(
                index = u16Be(raw, o),
                rawTemperature = u16Be(raw, o + 2),
                rawImpedance = u16Be(raw, o + 4),
                rawGlucose = u16Be(raw, o + 6),
                status = u16Be(raw, o + 8),
                numOfUnreceived = u16Be(raw, o + 10),
                addTimeSeconds = u16Be(raw, o + 12),
            )
        }
        return ParseResult.ChineseData(entries)
    }

    fun parseV120(encrypted: ByteArray): ParseResult {
        if (encrypted.contentEquals(v120AuthMarker)) return ParseResult.V120AuthRequired
        val plain = decrypt(encrypted)
        if (!verifyChecksum(plain)) return ParseResult.ChecksumError(plain)
        val length = plain[0].toInt() and 0xFF
        if (plain.size == 5 && length == 4) {
            return ResponseType.fromCode(plain[1].toInt() and 0xFF)
                ?.let { ParseResult.Handshake(it) }
                ?: ParseResult.Unknown(plain)
        }
        if (plain.size > 9 && (plain[1].toInt() and 0xFF) == 0x08) {
            return parseV120Data(plain)
        }
        return ParseResult.Unknown(plain)
    }

    private fun parseV120Data(plain: ByteArray): ParseResult {
        val count = plain[2].toInt() and 0xFF
        if (count == 0) return ParseResult.V120Data(emptyList())
        val expectedSize = 9 + count * 8 + 1
        if (plain.size < expectedSize) return ParseResult.Unknown(plain)
        val baseIndex = u16Le(plain, 3)
        val baseEventMs = u32Le(plain, 5) * 1000L
        val entries = ArrayList<V120Entry>(count)
        for (i in 0 until count) {
            val o = 9 + i * 8
            entries += V120Entry(
                index = baseIndex + i,
                eventTimeMs = baseEventMs + i * SibionicsConstants.READING_INTERVAL_MS,
                rawTemperature = u16Le(plain, o),
                rawImpedance = u16Le(plain, o + 2),
                rawGlucose = u16Le(plain, o + 4),
                trend = Trend.fromFlags(plain[o + 6].toInt() and 0xFF),
                reindex = count - i - 1,
            )
        }
        return ParseResult.V120Data(entries)
    }

    fun toHex(bytes: ByteArray): String =
        bytes.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }

    private fun rc4(key: ByteArray, data: ByteArray): ByteArray {
        val s = IntArray(256) { it }
        var j = 0
        for (i in 0 until 256) {
            j = (j + s[i] + (key[i % key.size].toInt() and 0xFF)) and 0xFF
            val tmp = s[i]
            s[i] = s[j]
            s[j] = tmp
        }
        var i = 0
        j = 0
        val out = ByteArray(data.size)
        for (k in data.indices) {
            i = (i + 1) and 0xFF
            j = (j + s[i]) and 0xFF
            val tmp = s[i]
            s[i] = s[j]
            s[j] = tmp
            val stream = s[(s[i] + s[j]) and 0xFF]
            out[k] = (data[k].toInt() xor stream).toByte()
        }
        return out
    }

    private fun writeU16Le(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value and 0xFF).toByte()
        target[offset + 1] = ((value ushr 8) and 0xFF).toByte()
    }

    private fun writeU32Le(target: ByteArray, offset: Int, value: Long) {
        target[offset] = (value and 0xFF).toByte()
        target[offset + 1] = ((value ushr 8) and 0xFF).toByte()
        target[offset + 2] = ((value ushr 16) and 0xFF).toByte()
        target[offset + 3] = ((value ushr 24) and 0xFF).toByte()
    }

    private fun u16Le(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

    private fun u16Be(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)

    private fun u32Le(bytes: ByteArray, offset: Int): Long =
        ((bytes[offset].toLong() and 0xFFL) or
            ((bytes[offset + 1].toLong() and 0xFFL) shl 8) or
            ((bytes[offset + 2].toLong() and 0xFFL) shl 16) or
            ((bytes[offset + 3].toLong() and 0xFFL) shl 24))
}

object SibionicsSensitivity {
    const val MIN_SENSITIVITY = 0.8f
    const val MAX_SENSITIVITY = 2.5f

    fun isSupported(value: Float): Boolean =
        value.isFinite() && value in MIN_SENSITIVITY..MAX_SENSITIVITY

    fun deriveShortCode(source: String?, fallback: String): String {
        val normalized = SibionicsConstants.normalizeBleName(source)
        if (normalized.length >= 8) {
            val prefix = normalized.take(8)
            if (tryDecode(prefix) != null) return prefix
        }

        val shortName = when {
            normalized.length <= 11 && normalized.startsWith("LT") && normalized.length >= 8 ->
                (normalized.takeLast(4) + normalized).take(11)
            normalized.length in 1..10 -> normalized.padStart(11, '0')
            normalized.length >= 11 -> normalized.take(11)
            else -> fallback
        }
        val candidate = shortName.take(8)
        return if (candidate.length == 8) candidate else fallback
    }

    fun sensitivityFor(
        shortCode: String?,
        variant: SibionicsConstants.Variant,
        probeCode: String? = null,
    ): Float =
        (if (variant == SibionicsConstants.Variant.SIBIONICS2) SibionicsProbeSensitivity.tryDecode(probeCode) else null)
            ?: tryDecode(shortCode)
            // The legacy library retries this variant token when QR initialization
            // rejects an otherwise valid sensor identity (notably V120 XPT codes).
            ?: tryDecode(variant.fallbackShortCode)
            ?: DEFAULT_SENSITIVITY

    fun tryDecode(shortCode: String?): Float? {
        val token = SibionicsConstants.normalizeBleName(shortCode).takeLast(4)
        if (token.length != 4) return null
        if (token.all(Char::isDigit)) {
            val value = token.toFloatOrNull()?.div(1000f) ?: return null
            return value.takeIf(::isSupported)
        }
        decodedDigits(token, 'A')?.let {
            val value = it / 100f
            if (isSupported(value)) return value
        }
        decodedDigits(token, 'P')?.let {
            val value = it / 100f
            if (isSupported(value)) return value
        }
        return null
    }

    private const val DEFAULT_SENSITIVITY = 1.27f

    private fun decodedDigits(token: String, base: Char): Int? {
        val mapped = token.uppercase(Locale.US).map {
            when (it) {
                'K' -> 'I'
                'I' -> '!'
                else -> it
            }
        }
        if (mapped.size != 4) return null
        val chars = mapped.map { it.code }
        val baseValue = base.code
        val t0 = chars[0]
        val t1 = chars[1]
        val t2 = chars[2]
        val t3 = chars[3]

        val out3 = (baseValue - t3) + 57
        var w9 = (t3 - baseValue) + 48

        val w10: Int
        val out2: Int
        if (t2 >= baseValue) {
            val w11 = t2 - baseValue
            val localW10 = w11 + 48
            val w12 = 9 - w11
            w9 = w12 + w9
            w10 = localW10
            out2 = w9
        } else {
            val w11 = if (t2 <= (w9 and 0xFF)) 48 else 57
            val w12 = w11 - t2
            val localW10 = t2
            w9 = w12 + w9
            w10 = localW10
            out2 = w9
        }

        val out1: Int
        val w11b: Int
        var w10b = w10
        if (t1 >= baseValue) {
            val w12 = t1 - baseValue
            w11b = w12 + 48
            val w12b = 9 - w12
            w10b = w12b + w10b
            out1 = w10b
        } else {
            val w12 = (if (t1 <= (w10b and 0xFF)) 48 else 57) - t1
            w11b = t1
            w10b = w12 + w10b
            out1 = w10b
        }

        val out0 = if (t0 >= baseValue) {
            (baseValue - t0) + 9 + w11b
        } else {
            val w12 = (if (t0 <= (w11b and 0xFF)) 48 else 57) - t0
            w12 + w11b
        }

        val checksum = (out0 + out1 + out2 - 0x90).floorMod(10)
        if (out3 - 48 != checksum) return null
        val charsOut = charArrayOf(out0.toChar(), out1.toChar(), out2.toChar())
        return String(charsOut).toIntOrNull()
    }

    private fun Int.floorMod(other: Int): Int {
        val r = this % other
        return if (r < 0) r + other else r
    }
}
