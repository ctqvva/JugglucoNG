// JugglucoNG — AiDex Native Kotlin Driver
// AiDexParser.kt — Frame parsing for F003 data and F002 responses
//
// Ported from iGlucco AiDexProtocol.swift (AiDexParser enum).

package tk.glucodata.drivers.aidex.native.protocol

import tk.glucodata.drivers.aidex.native.data.*

object AiDexParser {

    data class StartupDeviceInfo(
        val firmwareVersion: String,
        val hardwareVersion: String,
        val wearDays: Int,
        val modelName: String,
    )

    data class LocalStartTime(
        val year: Int,
        val month: Int,
        val day: Int,
        val hour: Int,
        val minute: Int,
        val second: Int,
        val tzQuarters: Int,
        val dstQuarters: Int,
    ) {
        val isAllZeros: Boolean =
            year == 0 && month == 0 && day == 0 && hour == 0 && minute == 0 && second == 0
    }

    data class DefaultParamChunk(
        val leadByte: Int,
        val totalWords: Int,
        val startIndex: Int,
        val rawChunk: ByteArray,
    ) {
        val nextStartIndex: Int get() = startIndex + (rawChunk.size / 2)
        val isComplete: Boolean get() = nextStartIndex > totalWords
    }

    // -- F003 Frame Classification --

    enum class FrameType { DATA, STATUS, UNKNOWN }

    fun classifyFrame(data: ByteArray): FrameType = when (data.size) {
        AiDexOpcodes.DATA_FRAME_LENGTH -> FrameType.DATA
        AiDexOpcodes.STATUS_FRAME_LENGTH -> FrameType.STATUS
        else -> FrameType.UNKNOWN
    }

    // -- 17-Byte Data Frame Parsing --

    /**
     * Parse a 17-byte F003 data frame.
     *
     * Layout:
     *   byte[0]: opcode
     *   bytes[1..3]: unidentified (observed non-zero and varying per frame)
     *   bytes[4..5]: timeOffsetMinutes (u16 LE) — minutes since sensor start
     *   bytes[6..7]: glucosePacked (u16 LE), glucose = packed & 0x03FF
     *   bytes[8..9]: i1 raw channel (u16 LE / 100)
     *   bytes[10..11]: i2 raw channel (u16 LE / 100)
     *   bytes[15..16]: CRC-16 (u16 LE)
     *
     * The offset used to be read as a u32 LE of *seconds* at bytes[1..4]. That produced values
     * in the tens of millions of minutes on every firmware, which is why the driver carried a
     * "garbage timing bytes" workaround that re-derived the offset from session metadata.
     * The field is really the same u16 LE minute offset that the `0x23`/`0x24`/`0x27` history
     * and calibration payloads use, and it sits at bytes[4..5]. See
     * `AiDexParserDataFrameTests` for the evidence this is decoded from.
     *
     * Valid means: not the sentinel, raw in 20..500 and the scaled mg/dL in 20..500. The 0xD2
     * half scale is an unverified port (no capture), so a 0xD2 raw above 500 stays rejected
     * until a capture confirms the scale: 0xD2 readings above 250 mg/dL are dropped.
     */
    fun parseDataFrame(data: ByteArray): GlucoseFrame? {
        if (data.size != AiDexOpcodes.DATA_FRAME_LENGTH) return null

        val opcode = data[0].toInt() and 0xFF
        val timeOffsetMinutes = u16LE(data, 4)
        val glucosePacked = u16LE(data, 6)
        val rawGlucose = glucosePacked and AiDexOpcodes.GLUCOSE_MASK
        val i1Raw = u16LE(data, 8)
        val i2Raw = u16LE(data, 10)
        val crc16 = u16LE(data, 15)

        val i1 = i1Raw / 100f
        val i2 = i2Raw / 100f

        val scaling = AiDexOpcodes.scalingFactor(opcode)
        val glucoseMgDl = if (scaling != null) rawGlucose * scaling else rawGlucose.toFloat()

        val isSentinel = rawGlucose == AiDexOpcodes.SENTINEL_GLUCOSE
        val isValid = !isSentinel &&
                rawGlucose in AiDexOpcodes.MIN_VALID_GLUCOSE..AiDexOpcodes.MAX_VALID_GLUCOSE &&
                glucoseMgDl in AiDexOpcodes.MIN_VALID_GLUCOSE.toFloat()..AiDexOpcodes.MAX_VALID_GLUCOSE.toFloat()

        return GlucoseFrame(
            opcode = opcode,
            timeOffsetMinutes = timeOffsetMinutes,
            glucoseMgDl = glucoseMgDl,
            rawGlucosePacked = glucosePacked,
            i1 = i1,
            i2 = i2,
            crc16 = crc16,
            isValid = isValid,
        )
    }

    // -- 5-Byte Status Frame Parsing --

    fun parseStatusFrame(data: ByteArray): StatusFrame? {
        if (data.size != AiDexOpcodes.STATUS_FRAME_LENGTH) return null
        return StatusFrame(header = data[0].toInt() and 0xFF)
    }

    // -- Broadcast Sample (advertisement payload or connected 0x11 reply) --

    data class BroadcastSample(val offsetMinutes: Int, val trend: Int, val glucoseMgDl: Int)

    data class BroadcastSampleParse(val sample: BroadcastSample? = null, val rejectionReason: String? = null)

    const val BROADCAST_MAX_OFFSET_MINUTES: Int = 30 * 24 * 60

    fun decodePackedBroadcastGlucoseMgDl(data: ByteArray, loIndex: Int = 5, carryIndex: Int = 6): Int {
        if (loIndex !in data.indices) return 0
        val lo = data[loIndex].toInt() and 0xFF
        val carry = data.getOrNull(carryIndex)?.toInt()?.and(0xFF) ?: 0
        return lo or ((carry and 0x03) shl 8)
    }

    /**
     * Parse a broadcast glucose sample. No captured advert exists; the tests use synthetic
     * payloads.
     *
     * Layout:
     *   bytes[0..3]: offset minutes, read as u32 LE. Any accepted value is <= 43200, so
     *                bytes[2..3] are zero; an out-of-range u32 falls back to u16 LE of
     *                bytes[0..1]. Either way only bytes[0..1] effectively count.
     *   byte[4]:     trend (signed i8)
     *   bytes[5..6]: glucose mg/dL, 10-bit: lo | ((carry & 0x03) << 8)
     *
     * A 10-bit glucose outside 20..500 is rejected. There is deliberately no byte-5 fallback:
     * it read the low byte alone, so 1023 became 255, 501 became 245 and 540 became a false 28.
     */
    fun parseBroadcastSample(payload: ByteArray): BroadcastSampleParse {
        if (payload.size < 7) {
            return BroadcastSampleParse(rejectionReason = "too-short")
        }

        // .toInt() of the u32 is kept from the manager: FF FF FF FF reads as -1, then u16 65535.
        val offsetCandidate = u32LE(payload, 0).toInt()
        val offsetMinutes = when {
            offsetCandidate > 0 && offsetCandidate <= BROADCAST_MAX_OFFSET_MINUTES -> offsetCandidate
            else -> u16LE(payload, 0)
        }
        if (offsetMinutes <= 0 || offsetMinutes > BROADCAST_MAX_OFFSET_MINUTES) {
            return BroadcastSampleParse(
                rejectionReason = "invalid-offset(u32=$offsetCandidate,u16=${u16LE(payload, 0)})"
            )
        }

        val trend = payload[4].toInt()
        val packed = decodePackedBroadcastGlucoseMgDl(payload)
        if (packed !in AiDexOpcodes.MIN_VALID_GLUCOSE..AiDexOpcodes.MAX_VALID_GLUCOSE) {
            return BroadcastSampleParse(rejectionReason = "invalid-glucose(packed=$packed)")
        }

        return BroadcastSampleParse(
            sample = BroadcastSample(offsetMinutes = offsetMinutes, trend = trend, glucoseMgDl = packed)
        )
    }

    // -- History Parsing: GET_HISTORIES_RAW (0x23) --

    /**
     * Parse a 0x23 response payload (after opcode+status bytes and before CRC).
     *
     * Format:
     *   bytes[0..1]: startOffset (u16 LE) — time offset in minutes
     *   body: N * 2-byte rows
     *     row glucose = b0 | ((b1 & 0x03) << 8) — 10-bit packed
     *     row status bit = (b1 & 0x04)
     */
    fun parseHistoryResponse(data: ByteArray): List<CalibratedHistoryEntry> {
        if (data.size < 4) return emptyList()

        val startOffset = u16LE(data, 0)
        val bodyOffset = 2
        val bodySize = data.size - bodyOffset
        val rowCount = bodySize / AiDexOpcodes.HISTORY_RAW_ROW_SIZE

        return (0 until rowCount).map { i ->
            val off = bodyOffset + i * AiDexOpcodes.HISTORY_RAW_ROW_SIZE
            val b0 = data[off].toInt() and 0xFF
            val b1 = data[off + 1].toInt() and 0xFF

            val glucose = b0 or ((b1 and 0x03) shl 8)
            val statusBit = (b1 and 0x04) != 0
            val isSentinel = glucose == AiDexOpcodes.SENTINEL_GLUCOSE

            CalibratedHistoryEntry(
                timeOffsetMinutes = startOffset + i,
                glucoseMgDl = glucose,
                statusBit = statusBit,
                isSentinel = isSentinel,
            )
        }
    }

    // -- History Parsing: GET_HISTORIES (0x24) --

    /**
     * Parse a 0x24 response payload.
     *
     * Format:
     *   bytes[0..1]: startOffset (u16 LE)
     *   body: N * 5-byte rows: [i1_lo, i1_hi, i2_lo, i2_hi, vc]
     *     i1 = u16LE / 100.0
     *     i2 = u16LE / 100.0
     *     vc = u8 / 100.0
     *     rawValue = i1 * 10 (matches Android selectVendorRawLane)
     *     sensorGlucose = i1 * 18.0182 (treating i1 as mmol/L -> mg/dL)
     *     Invalid: i1_raw == 0 && i2_raw == 0 && vc_raw == 0
     */
    fun parseBriefHistoryResponse(data: ByteArray): List<AdcHistoryEntry> {
        if (data.size < 7) return emptyList() // 2 header + at least 5 body

        val startOffset = u16LE(data, 0)
        val bodyOffset = 2
        val bodySize = data.size - bodyOffset
        val rowCount = bodySize / AiDexOpcodes.HISTORY_BRIEF_ROW_SIZE

        return (0 until rowCount).mapNotNull { i ->
            val off = bodyOffset + i * AiDexOpcodes.HISTORY_BRIEF_ROW_SIZE
            val i1Raw = u16LE(data, off)
            val i2Raw = u16LE(data, off + 2)
            val vcRaw = data[off + 4].toInt() and 0xFF

            // Skip invalid entries
            if (i1Raw == 0 && i2Raw == 0 && vcRaw == 0) return@mapNotNull null

            val i1 = i1Raw / 100f
            val i2 = i2Raw / 100f

            AdcHistoryEntry(
                timeOffsetMinutes = startOffset + i,
                i1 = i1,
                i2 = i2,
                vc = vcRaw / 100f,
                rawValue = i1 * 10f,
                sensorGlucose = i1 * 18.0182f,
            )
        }
    }

    // -- Calibration Parsing (0x27) --

    /**
     * Parse a GET_CALIBRATION response.
     *
     * Format:
     *   bytes[0..1]: startIndex (u16 LE)
     *   body: N * 8-byte rows:
     *     [0..1] timeOffsetMin (u16 LE)
     *     [2..3] referenceGlucose (u16 LE)
     *     [4..5] cf (u16 LE / 100)
     *     [6..7] offset (s16 LE / 100)
     */
    fun parseCalibrationResponse(data: ByteArray): List<CalibrationRecord> {
        if (data.size < 10) return emptyList() // 2 header + at least 8 body

        val startIndex = u16LE(data, 0)
        if (startIndex > 10000) return emptyList() // implausible

        val bodyOffset = 2
        val bodySize = data.size - bodyOffset
        if (bodySize % AiDexOpcodes.CALIBRATION_ROW_SIZE != 0) return emptyList() // corrupt

        val rowCount = bodySize / AiDexOpcodes.CALIBRATION_ROW_SIZE

        return (0 until rowCount).map { i ->
            val off = bodyOffset + i * AiDexOpcodes.CALIBRATION_ROW_SIZE
            val timeOffset = u16LE(data, off)
            val reference = u16LE(data, off + 2)
            val cfRaw = u16LE(data, off + 4)
            val offsetRaw = s16LE(data, off + 6)

            CalibrationRecord(
                index = startIndex + i,
                timeOffsetMinutes = timeOffset,
                referenceGlucoseMgDl = reference,
                calibrationFactor = cfRaw / 100f,
                calibrationOffset = offsetRaw / 100f,
            )
        }
    }

    // -- Handshake Echo Validation --

    /**
     * Validate that an F002 notify is an echo of the command we sent.
     */
    fun isHandshakeEcho(sent: ByteArray, received: ByteArray): Boolean {
        if (sent.isEmpty() || received.isEmpty()) return false
        return sent[0] == received[0]
    }

    // -- Default Param Parsing (0x31) --

    /**
     * Parse the payload returned by raw `0x10` startup/device-info.
     *
     * Vendor wrapper traces expose this as 16 bytes after the response header:
     *   [status_lo, status_hi, fw_major, fw_minor, hw_major, hw_minor,
     *    wear_days, model_code, model_ascii...]
     */
    fun parseStartupDeviceInfoPayload(payload: ByteArray): StartupDeviceInfo? {
        if (payload.size < 16) return null
        if (u16LE(payload, 0) != 0) return null

        val fwMajor = payload[2].toInt() and 0xFF
        val fwMinor = payload[3].toInt() and 0xFF
        val hwMajor = payload[4].toInt() and 0xFF
        val hwMinor = payload[5].toInt() and 0xFF
        val wearDays = payload[6].toInt() and 0xFF
        val modelBytes = payload.copyOfRange(8, payload.size)
        val nullIdx = modelBytes.indexOf(0.toByte())
        val modelName = (
            if (nullIdx >= 0) String(modelBytes, 0, nullIdx, Charsets.US_ASCII)
            else String(modelBytes, Charsets.US_ASCII)
        ).trim()

        if (modelName.isBlank()) return null

        return StartupDeviceInfo(
            firmwareVersion = "$fwMajor.$fwMinor",
            hardwareVersion = "$hwMajor.$hwMinor",
            wearDays = wearDays,
            modelName = modelName,
        )
    }

    fun parseStartupDeviceInfoFrame(
        frame: ByteArray,
        payloadEndExclusive: Int = frame.size,
    ): StartupDeviceInfo? {
        if (payloadEndExclusive <= 1 || payloadEndExclusive > frame.size) return null
        val payload = frame.copyOfRange(1, payloadEndExclusive)
        parseStartupDeviceInfoPayload(payload)?.let { return it }
        if (payloadEndExclusive <= 2) return null
        return parseStartupDeviceInfoPayload(frame.copyOfRange(2, payloadEndExclusive))
    }

    /**
     * Bluetooth Time Zone is signed quarters of an hour. 0x80 means the offset is not known;
     * sign-extending it is -128 quarters and shifts the session start by 32 hours.
     * Genuine west-of-UTC zones stay negative.
     */
    fun sessionTimeZoneQuarters(raw: Byte): Int {
        if ((raw.toInt() and 0xFF) == 0x80) return 0
        return raw.toInt()
    }

    /**
     * Parse a local/session start-time payload.
     *
     * Shared by standard CGM `2AAA`, legacy raw `0x21`, and the upstream
     * zero-start-time recovery branch. Returns null unless the payload is
     * either all zeros or a plausible timestamp.
     */
    fun parseLocalStartTimePayload(payload: ByteArray): LocalStartTime? {
        if (payload.size < 7) return null

        val parsed = LocalStartTime(
            year = u16LE(payload, 0),
            month = payload[2].toInt() and 0xFF,
            day = payload[3].toInt() and 0xFF,
            hour = payload[4].toInt() and 0xFF,
            minute = payload[5].toInt() and 0xFF,
            second = payload[6].toInt() and 0xFF,
            tzQuarters = if (payload.size >= 8) sessionTimeZoneQuarters(payload[7]) else 0,
            dstQuarters = if (payload.size >= 9) payload[8].toInt() and 0xFF else 0,
        )

        if (parsed.isAllZeros) return parsed

        val plausibleDate = parsed.year in 2020..2040 &&
            parsed.month in 1..12 &&
            parsed.day in 1..31 &&
            parsed.hour in 0..23 &&
            parsed.minute in 0..59 &&
            parsed.second in 0..59

        return parsed.takeIf { plausibleDate }
    }

    /**
     * Parse a GET_DEFAULT_PARAM payload after stripping the opcode byte and any CRC.
     *
     * Static RE of `AidexXController::LongAttribute::queryResp()` shows the payload
     * shape is:
     *   byte[0]    = lead byte (preserved separately on the first chunk)
     *   byte[1]    = total DP word count
     *   byte[2]    = 1-based start index for this chunk
     *   byte[3..]  = raw binary chunk bytes
     *
     * Continuation progresses in 2-byte words, so the next query start index is
     * `startIndex + rawChunk.size / 2`.
     */
    fun parseDefaultParamChunk(payload: ByteArray): DefaultParamChunk? {
        if (payload.size < 5) return null

        val totalWords = payload[1].toInt() and 0xFF
        val startIndex = payload[2].toInt() and 0xFF
        val rawChunk = payload.copyOfRange(3, payload.size)

        if (totalWords <= 0 || startIndex <= 0 || startIndex > totalWords) return null
        if (rawChunk.isEmpty() || (rawChunk.size % 2) != 0) return null

        return DefaultParamChunk(
            leadByte = payload[0].toInt() and 0xFF,
            totalWords = totalWords,
            startIndex = startIndex,
            rawChunk = rawChunk,
        )
    }

    fun appendDefaultParamChunk(buffer: ByteArray?, chunk: DefaultParamChunk): ByteArray {
        val requiredSize = 1 + (chunk.totalWords * 2)
        val target = buffer?.takeIf { it.size >= requiredSize } ?: ByteArray(requiredSize)
        if (chunk.startIndex == 1) {
            target[0] = chunk.leadByte.toByte()
        }
        val destOffset = if (chunk.startIndex == 1) 1 else ((chunk.startIndex * 2) - 1).coerceAtLeast(1)
        val copyLen = minOf(chunk.rawChunk.size, target.size - destOffset)
        if (copyLen > 0) {
            System.arraycopy(chunk.rawChunk, 0, target, destOffset, copyLen)
        }
        return target
    }

    fun defaultParamRawHex(buffer: ByteArray?, totalWords: Int): String? {
        if (buffer == null || totalWords <= 0) return null
        val expectedLen = 1 + (totalWords * 2)
        if (buffer.size < expectedLen) return null
        return compactHex(buffer.copyOf(expectedLen))
    }

    // -- Hex Utilities --

    fun hexString(data: ByteArray): String =
        data.joinToString(" ") { "%02X".format(it) }

    fun compactHex(data: ByteArray): String =
        data.joinToString("") { "%02X".format(it) }

    fun dataFromHex(hex: String): ByteArray {
        val clean = hex.replace(" ", "")
        return ByteArray(clean.length / 2) { i ->
            clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    // -- Internal helpers --

    private fun u16LE(data: ByteArray, offset: Int): Int {
        return (data[offset].toInt() and 0xFF) or
                ((data[offset + 1].toInt() and 0xFF) shl 8)
    }

    private fun u32LE(data: ByteArray, offset: Int): Long {
        return (data[offset].toInt() and 0xFF).toLong() or
                ((data[offset + 1].toInt() and 0xFF).toLong() shl 8) or
                ((data[offset + 2].toInt() and 0xFF).toLong() shl 16) or
                ((data[offset + 3].toInt() and 0xFF).toLong() shl 24)
    }

    private fun s16LE(data: ByteArray, offset: Int): Int {
        val unsigned = u16LE(data, offset)
        return if (unsigned >= 0x8000) unsigned - 0x10000 else unsigned
    }

}
