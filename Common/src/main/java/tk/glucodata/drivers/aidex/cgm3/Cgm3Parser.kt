// JugglucoNG — AiDex CGM3 (G9) driver
// Cgm3Parser.kt — Frame parsing for CGM3 status / broadcast / device-spec /
// notification payloads.
//
// Recovered from ARM64 disassembly of `Cgm3StatusParser::getStatus`,
// `Cgm3BroadcastParser::getBroadcast`, `Cgm3DeviceSpecParser::getDeviceSpec`
// and `Cgm3Parser::getCgm3NotificationEntity` (v1.16.1 libblecomm-lib.so),
// cross-checked against the decompiled entity field lists
// (`Cgm3StatusEntity`, `Cgm3BroadcastEntity`, …).
//
// Byte order: the native cursor is `InputByteStream`
// (`readUnsignedByte/readUnsignedShort/readByte`), which follows the Java
// `DataInputStream` convention, i.e. multi-byte fields are BIG-endian.
// This is marked TBD against one live sample (see `ByteOrder` note below).
// Full analysis: docs/aidex-g9-cgm3-protocol.md (5.3.3).

package tk.glucodata.drivers.aidex.cgm3

object Cgm3Parser {

    /**
     * Byte-order assumption for multi-byte fields. Everything below parses
     * big-endian; flip to little-endian if the first live frame disagrees.
     */
    enum class ByteOrder { BIG_ENDIAN }

    /** Parsed CGM3 status entity (≥6 B: u16, u16[2b flags + 14b value], u8, u8). */
    data class Status(
        val word0: Int,
        val flags2: Int,
        val value14: Int,
        val byte4: Int,
        val byte5: Int,
    )

    /**
     * One broadcast history entry: float channel (/10), 10-bit glucose +
     * 2-bit flags, trailing byte, validity (u16 != 0xFFFF).
     */
    data class BroadcastEntry(
        val channel: Float,
        val glucoseMgDl: Int,
        val flags: Int,
        val extra: Int,
        val isValid: Boolean,
    )

    /** Parsed CGM3 broadcast entity: 6-B status header + up to 2 entries. */
    data class Broadcast(
        val status: Status,
        val entries: List<BroadcastEntry>,
    )

    /** Parsed 23-B device-spec entity (structural field order). */
    data class DeviceSpec(
        val byte0: Int,
        val word1: Int,
        val word2: Int,
        val word3: Int,
        val byte6: Int,
        val byte7: Int,
        val word8: Int,
        val byte10: Int,
        val byte11: Int,
        val flag12: Int,
    )

    /** Parsed notification-config entity (structural field offsets). */
    data class Notification(
        val wordAt2: Int,
        val byteAt4: Int,
        val wordAt6: Int,
        val byteAt8: Int,
        val byteAt9: Int,
        val byteAt1: Int,
    )

    private class Cursor(private val data: ByteArray) {
        var pos: Int = 0
        val remaining: Int get() = data.size - pos
        fun u8(): Int {
            if (pos + 1 > data.size) throw IndexOutOfBoundsException("u8@$pos/${data.size}")
            return data[pos++].toInt() and 0xFF
        }
        fun s8(): Int {
            if (pos + 1 > data.size) throw IndexOutOfBoundsException("s8@$pos/${data.size}")
            return data[pos++].toInt()
        }
        fun isEnd(): Boolean = pos >= data.size
        fun u16be(): Int {
            if (pos + 2 > data.size) throw IndexOutOfBoundsException("u16@$pos/${data.size}")
            val v = ((data[pos].toInt() and 0xFF) shl 8) or (data[pos + 1].toInt() and 0xFF)
            pos += 2
            return v
        }
    }

    /**
     * Parse a status payload (≥6 B). Layout: u16, u16, u8, u8; the second
     * u16 splits into 2 flag bits (`>> 14`) and a 14-bit value (`& 0x3FFF`).
     */
    fun parseStatus(data: ByteArray): Status? = runCatching {
        val c = Cursor(data)
        if (c.remaining < 6) return null
        val w0 = c.u16be()
        val w1 = c.u16be()
        Status(
            word0 = w0,
            flags2 = (w1 shr 14) and 0x03,
            value14 = w1 and 0x3FFF,
            byte4 = c.u8(),
            byte5 = c.u8(),
        )
    }.getOrNull()

    /**
     * Parse a broadcast payload: 1 skipped byte, then the 6-B status header,
     * then up to 2 entries. Entry 0 is full (s8 -> float /10, u16 ->
     * glucose&0x3FF + flags, u8); entry 1+ carries no trend byte
     * (channel 0.0, u16, u8) -- mirrored exactly from
     * `Cgm3BroadcastParser::getBroadcast` (the trend float is only decoded
     * when the entry index is 0). The loop also stops when word0-index < 1.
     * of (s8 → float /10, u16 → glucose&0x3FF +
     * flags>>10, u8). Entry validity: raw u16 != 0xFFFF.
     */
    fun parseBroadcast(data: ByteArray): Broadcast? = runCatching {
        val c = Cursor(data)
        if (c.remaining < 7) return null
        c.u8() // leading byte skipped by the vendor parser
        val status = parseStatus(data.copyOfRange(c.pos, data.size)) ?: return null
        c.pos += 6
        val word0 = status.word0
        val entries = ArrayList<BroadcastEntry>(2)
        var index = 0
        while (!c.isEnd() && word0 - index >= 1 && (index and 0xFF) <= 1) {
            val channel = if (index == 0) {
                if (c.remaining < 4) break
                c.s8() / 10.0f
            } else {
                if (c.remaining < 3) break
                0.0f
            }
            val raw = c.u16be()
            val extra = c.u8()
            entries.add(
                BroadcastEntry(
                    channel = channel,
                    glucoseMgDl = raw and Cgm3Opcodes.GLUCOSE_MASK_10BIT,
                    flags = (raw shr 10) and 0x03,
                    extra = extra,
                    isValid = raw != Cgm3Opcodes.SENTINEL_BROADCAST,
                ),
            )
            index++
        }
        Broadcast(status, entries)
    }.getOrNull()

    /**
     * Parse the 14-B device-spec payload:
     * `[u8, u16, u16, u16, u8, u8, u16, u8, u8, u8&1]`
     * (1+2+2+2+1+1+2+1+1+1 bytes, entity offsets +8..+0x16).
     */
    fun parseDeviceSpec(data: ByteArray): DeviceSpec? = runCatching {
        val c = Cursor(data)
        if (c.remaining < 14) return null
        DeviceSpec(
            byte0 = c.u8(),
            word1 = c.u16be(),
            word2 = c.u16be(),
            word3 = c.u16be(),
            byte6 = c.u8(),
            byte7 = c.u8(),
            word8 = c.u16be(),
            byte10 = c.u8(),
            byte11 = c.u8(),
            flag12 = c.u8() and 0x01,
        )
    }.getOrNull()

    /**
     * History (`0x09`/`0x0A`) and calibration (`0x0C`) row layouts are NOT
     * recovered statically — the row decoders live behind `LongAttribute`
     * chunking and entity builders that need one live sample to pin down.
     * The vendor reuses `AidexXHistoryEntity`/`AidexXRawHistoryEntity` shapes
     * when saving Cgm3 histories, so the X row formats
     * (`AiDexParser.parseHistoryResponse` / `parseBriefHistoryResponse`)
     * are the prime suspects. Deliberately no guessing decoders here.
     */
    object HistoryRows {
        const val TODO_CAPTURE_LIVE_SAMPLE = true
    }
}
