// JugglucoNG — iCanHealth (Sinocare iCan i3/i6/i7) Driver
// ICanHealthParser.kt — Glucose notification, auth, and session metadata parsing
//
// Known `0x2AA7` payload families:
// - 19-byte live/current frames:
//   Header (3 bytes cleartext): [seq_lo, seq_hi, 0x02]
//   Payload (16 bytes AES-128-ECB encrypted with a firmware-selected hardcoded key)
// - raw 16-byte authenticated history frames (AES-CBC/PKCS7)
// - vendor `SN*R` history batches (AES-CBC/PKCS7 envelope)

package tk.glucodata.drivers.icanhealth

import java.util.Calendar
import java.util.TimeZone
import tk.glucodata.Log

/** Parsed glucose reading from a single 19-byte notification. */
data class ICanHealthGlucoseReading(
    val sequenceNumber: Int,     // From cleartext header (u16 LE)
    val glucoseMmolL: Float,     // mmol/L (raw sensor value)
    val glucoseMgdl: Float,      // mg/dL (converted)
    val statusByte: Int,         // Session-varying status
    /** High nibble of the glucose field. Purpose unknown; constant per sensor. */
    val dataState: Int,
    /** Unsigned 12-bit glucose value exactly as it came off the wire. */
    val mantissa: Int = 0,
    val currentValue: Float,
    val temperatureC: Float,
)

/**
 * Parsed CGM session start time (BT SIG `CGM Session Start Time`, 0x2AAA).
 *
 * [timezoneOffset15Min] and [dstOffset15Min] are null when the sensor did not send the field
 * at all, or sent the specification's "unknown" sentinel. They are deliberately kept apart
 * from a genuine zero so [toEpochMillis] can tell "UTC" from "no idea".
 */
data class ICanHealthSessionStartTime(
    val year: Int,
    val month: Int,
    val day: Int,
    val hour: Int,
    val minute: Int,
    val second: Int,
    val timezoneOffset15Min: Int?, // Time Zone, 15-minute increments, null when unknown
    val dstOffset15Min: Int? = null, // DST Offset, 15-minute increments, null when unknown
) {
    /** Total UTC offset in 15-minute increments, or null when the sensor told us nothing usable. */
    fun utcOffset15MinOrNull(): Int? {
        val tz = timezoneOffset15Min ?: return null
        return tz + (dstOffset15Min ?: 0)
    }

    /**
     * Convert to Unix epoch milliseconds.
     *
     * iCan sensors send the session start as a *local* wall clock and leave the Time Zone field
     * absent, zero, or set to the "unknown" sentinel. Reading those fields as UTC put the session
     * start one whole UTC offset into the future, which in turn made every session-derived
     * history timestamp land an offset late — the interleaved "ghost curve" spikes on the chart.
     *
     * So an offset of exactly zero is treated as *unknown* rather than as UTC. That costs nothing
     * for a genuinely-UTC device (local and UTC agree there) and repairs every other timezone.
     * Only a non-zero offset is taken at face value.
     */
    fun toEpochMillis(): Long {
        val offset15Min = utcOffset15MinOrNull()
        if (offset15Min == null || offset15Min == 0) {
            // Interpret the wall clock in the device's own zone. Calendar applies the DST rules
            // in force at that wall time, which a fixed offset could not.
            val local = Calendar.getInstance(TimeZone.getDefault())
            local.clear()
            local.set(year, month - 1, day, hour, minute, second)
            return local.timeInMillis
        }
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.clear()
        cal.set(year, month - 1, day, hour, minute, second)
        // Subtract the reported offset to convert the local wall clock to UTC
        return cal.timeInMillis - offset15Min * 15 * 60 * 1000L
    }
}

/** Parsed CGM status payload. */
data class ICanHealthStatusInfo(
    val sequenceNumber: Int,
    val launcherState: Int,
    val rawFlags: ByteArray,
)

data class ICanHealthSnHistoryRecord(
    val sequenceNumber: Int,
    val subtype: Int,
    val glucoseMmolL: Float? = null,
    val glucoseMgdl: Float? = null,
    val dataState: Int = 0,
    val currentValue: Float? = null,
    val temperatureC: Float? = null,
)

data class ICanHealthSnHistoryBatch(
    val subtype: Int,
    val baseSequenceNumber: Int,
    val records: List<ICanHealthSnHistoryRecord>,
)

object ICanHealthParser {

    private const val TAG = "ICanHealthParser"

    /** BT SIG sentinels and bounds for the 0x2AAA Time Zone / DST Offset fields. */
    private const val TIME_ZONE_UNKNOWN = -128
    private const val DST_OFFSET_UNKNOWN = 255
    private const val MIN_TIME_ZONE_15MIN = -48 // UTC-12:00
    private const val MAX_TIME_ZONE_15MIN = 56 // UTC+14:00
    private const val MAX_DST_OFFSET_15MIN = 8 // double daylight time, +2h

    /**
     * Parse a 19-byte glucose notification.
     *
     * @param data Raw 19-byte BLE notification from CGM Measurement characteristic
     * @param aesKey 16-byte AES key for decryption
     * @return Parsed reading, or null on failure
     */
    fun parseGlucoseNotification(
        data: ByteArray,
        aesKey: ByteArray,
        authChallenge: ByteArray?,
        onboardingDeviceSn: String?,
        usesNewBundledCrypto: Boolean,
        warmupMinutes: Int,
    ): ICanHealthGlucoseReading? {
        if (data.size != ICanHealthConstants.GLUCOSE_NOTIFICATION_SIZE) {
            logError("parseGlucose: expected ${ICanHealthConstants.GLUCOSE_NOTIFICATION_SIZE} bytes, got ${data.size}")
            return null
        }

        // Header: seq u16 LE + type byte
        val seqLo = data[0].toInt() and 0xFF
        val seqHi = data[1].toInt() and 0xFF
        val headerSeq = seqLo or (seqHi shl 8)
        val typeByte = data[2]

        if (typeByte != ICanHealthConstants.MEASUREMENT_TYPE_BYTE) {
            logWarn("parseGlucose: unexpected type byte 0x${"%02X".format(typeByte)}, expected 0x02")
            // Continue anyway — some firmware versions may differ
        }

        // Decrypt the 16-byte payload
        val encrypted = data.copyOfRange(3, 19)
        val decrypted = ICanHealthCrypto.decryptBlock(encrypted, aesKey) ?: run {
            logError("parseGlucose: AES decryption failed")
            return null
        }

        // Validate fixed marker before accepting any decrypted glucose. With the
        // wrong firmware key AES output can still decode to an in-range number.
        val sizeFlags = decrypted[ICanHealthConstants.OFFSET_SIZE_FLAGS].toInt() and 0xFF
        if (sizeFlags != 0x0D) {
            logWarn("parseGlucose: rejecting packet with sizeFlags=0x${"%02X".format(sizeFlags)}, expected 0x0D")
            return null
        }

        val statusByte = decrypted[ICanHealthConstants.OFFSET_STATUS].toInt() and 0xFF

        val glucoseBytes = decodeHistoryGlucoseBytes(
            low = decrypted[ICanHealthConstants.OFFSET_GLUCOSE_U16LE],
            high = decrypted[ICanHealthConstants.OFFSET_GLUCOSE_U16LE + 1],
            sequenceNumber = headerSeq,
            authChallenge = authChallenge,
            usesNewBundledCrypto = usesNewBundledCrypto,
            warmupMinutes = warmupMinutes,
        ) ?: return null
        val directGlucoseEncoding =
            ICanHealthConstants.usesDirectSnHistoryGlucoseEncoding(onboardingDeviceSn)
        val field = ICanHealthConstants.decodeGlucoseField(
            low = glucoseBytes[0].toInt(),
            high = glucoseBytes[1].toInt(),
            directGlucoseEncoding = directGlucoseEncoding,
        )
        val dataState = field.nibble
        val glucoseMmolL = field.glucoseMmolL
        val glucoseMgdl = field.glucoseMgdl
        val currentValue = decodeCurrentValue(
            decrypted[ICanHealthConstants.OFFSET_CURRENT_U16LE],
            decrypted[ICanHealthConstants.OFFSET_CURRENT_U16LE + 1]
        )
        val temperatureC = parseLeUnsigned(decrypted, ICanHealthConstants.OFFSET_TEMPERATURE_U16LE) / 10.0f

        // Sequence integrity check: bytes 4-5 should match header seq
        val seqCheckLo = decrypted[ICanHealthConstants.OFFSET_SEQ_CHECK_U16LE].toInt() and 0xFF
        val seqCheckHi = decrypted[ICanHealthConstants.OFFSET_SEQ_CHECK_U16LE + 1].toInt() and 0xFF
        val seqCheck = seqCheckLo or (seqCheckHi shl 8)
        if (seqCheck != headerSeq) {
            logWarn("parseGlucose: rejecting seq mismatch header=$headerSeq payload=$seqCheck")
            return null
        }

        // Validate glucose range
        if (glucoseMgdl < ICanHealthConstants.MIN_VALID_GLUCOSE_MGDL ||
            glucoseMgdl > ICanHealthConstants.MAX_VALID_GLUCOSE_MGDL) {
            logWarn("parseGlucose: glucose out of range ${glucoseMgdl} mg/dL (seq=$headerSeq)")
        }

        logGlucoseFieldDiagnostics(decrypted, headerSeq, field, temperatureC)

        return ICanHealthGlucoseReading(
            sequenceNumber = headerSeq,
            glucoseMmolL = glucoseMmolL,
            glucoseMgdl = glucoseMgdl,
            statusByte = statusByte,
            dataState = dataState,
            mantissa = field.mantissa,
            currentValue = currentValue,
            temperatureC = temperatureC,
        )
    }

    /**
     * Parse CGM Session Start Time characteristic value.
     *
     * Format (BT SIG `CGM Session Start Time`, 0x2AAA):
     *   [0..6] date_time: year_u16_LE, month, day, hour, minute, second
     *   [7]    Time Zone:  sint8, 15-minute increments, -128 (0x80) means "unknown"
     *   [8]    DST Offset: uint8, 15-minute increments, 255 (0xFF) means "unknown"
     *
     * Time Zone and DST Offset are two separate one-byte fields, not one little-endian int16 —
     * reading them as a pair silently folded the DST byte into the high half of the offset.
     *
     * @param data 7-9 byte characteristic value
     * @return Parsed session start time, or null on failure
     */
    fun parseSessionStartTime(data: ByteArray): ICanHealthSessionStartTime? {
        if (data.size < 7) {
            logError("parseSessionStart: expected >= 7 bytes, got ${data.size}")
            return null
        }

        val yearLo = data[0].toInt() and 0xFF
        val yearHi = data[1].toInt() and 0xFF
        val year = yearLo or (yearHi shl 8)
        val month = data[2].toInt() and 0xFF
        val day = data[3].toInt() and 0xFF
        val hour = data[4].toInt() and 0xFF
        val minute = data[5].toInt() and 0xFF
        val second = data[6].toInt() and 0xFF
        if (year == 0 || year == 0xFFFF || month !in 1..12 || day !in 1..31) {
            return null
        }
        // Time Zone: sint8. -128 is the spec's "unknown"; anything outside UTC-12..UTC+14 is junk.
        val tzOffset15Min = data.getOrNull(7)
            ?.toInt()
            ?.takeIf { it != TIME_ZONE_UNKNOWN && it in MIN_TIME_ZONE_15MIN..MAX_TIME_ZONE_15MIN }

        // DST Offset: uint8. 255 is the spec's "unknown"; real values are 0, 2, 4 or 8.
        val dstOffset15Min = data.getOrNull(8)
            ?.let { it.toInt() and 0xFF }
            ?.takeIf { it != DST_OFFSET_UNKNOWN && it in 0..MAX_DST_OFFSET_15MIN }

        return ICanHealthSessionStartTime(
            year = year,
            month = month,
            day = day,
            hour = hour,
            minute = minute,
            second = second,
            timezoneOffset15Min = tzOffset15Min,
            dstOffset15Min = dstOffset15Min,
        )
    }

    fun buildSessionStartWrite(epochMs: Long, timeZone: TimeZone = TimeZone.getDefault()): ByteArray {
        val calendar = Calendar.getInstance(timeZone).apply {
            timeInMillis = epochMs
        }
        val year = calendar.get(Calendar.YEAR)
        val offset15Min = (timeZone.getOffset(epochMs) / (15 * 60 * 1000)).toShort().toInt()
        return byteArrayOf(
            (year and 0xFF).toByte(),
            ((year ushr 8) and 0xFF).toByte(),
            (calendar.get(Calendar.MONTH) + 1).toByte(),
            calendar.get(Calendar.DAY_OF_MONTH).toByte(),
            calendar.get(Calendar.HOUR_OF_DAY).toByte(),
            calendar.get(Calendar.MINUTE).toByte(),
            calendar.get(Calendar.SECOND).toByte(),
            (offset15Min and 0xFF).toByte(),
            ((offset15Min shr 8) and 0xFF).toByte()
        )
    }

    /**
     * Parse CGM Status characteristic value.
     *
     * Format: [time_offset_u16_LE, flags/status (4 bytes)].
     *
     * Different RE notes disagree on whether this is expressed directly in minutes
     * or in 3-minute slots. The driver keeps the raw u16 value and aligns it against
     * live sequence numbers instead of scaling here.
     *
     * @param data 6 byte characteristic value
     * @return Raw time/sequence offset, or -1 on failure
     */
    fun parseTimeOffset(data: ByteArray): Int {
        return parseCgmStatus(data)?.sequenceNumber ?: -1
    }

    fun parseCgmStatus(data: ByteArray): ICanHealthStatusInfo? {
        if (data.size < 2) return null
        val lo = data[0].toInt() and 0xFF
        val hi = data[1].toInt() and 0xFF
        return ICanHealthStatusInfo(
            sequenceNumber = lo or (hi shl 8),
            launcherState = data.getOrElse(2) { 0 }.toInt() and 0xFF,
            rawFlags = if (data.size > 2) data.copyOfRange(2, data.size) else byteArrayOf()
        )
    }

    /**
     * Check if a notification is an SN (serial number) type.
     *
     * @param data Notification bytes
     * @return true if it starts with "SN" ASCII header
     */
    fun isSNNotification(data: ByteArray): Boolean {
        return data.size >= 2 && data[0] == 0x53.toByte() && data[1] == 0x4E.toByte()
    }

    fun parseSNType(data: ByteArray): Int? {
        if (data.size < 6 || !isSNNotification(data)) return null
        if (data[3] != 0x2A.toByte() || data[4] != 0x52.toByte()) return null
        return data[5].toInt() and 0xFF
    }

    fun parseSnHistoryBatch(
        data: ByteArray,
        authChallenge: ByteArray,
        onboardingDeviceSn: String?,
        launcherSerial: String?,
        softwareVersion: String?,
        readingIntervalMinutes: Int,
    ): ICanHealthSnHistoryBatch? {
        val subtype = parseSNType(data) ?: return null
        if (data.size < 8) return null
        val declaredPayloadLength = data[2].toInt() and 0xFF
        if (declaredPayloadLength != data.size - 3) {
            logWarn("parseSnHistoryBatch: declared length=$declaredPayloadLength actual=${data.size - 3}")
        }
        val encryptedPayload = data.copyOfRange(6, data.size - 1)
        if (encryptedPayload.isEmpty()) {
            return ICanHealthSnHistoryBatch(subtype, -1, emptyList())
        }
        val keyAscii = when (subtype) {
            ICanHealthConstants.SN_HISTORY_SUBTYPE_GLUCOSE ->
                ICanHealthConstants.resolveBundledGlucoseKey(launcherSerial, softwareVersion)
            ICanHealthConstants.SN_HISTORY_SUBTYPE_ORIGINAL ->
                ICanHealthConstants.resolveBundledOriginalHistoryKey(launcherSerial, softwareVersion)
            else -> return null
        }
        val key = ICanHealthCrypto.keyFromASCII(keyAscii) ?: return null
        val iv = buildAuthIv(authChallenge) ?: return null
        val decrypted = ICanHealthCrypto.decryptCbcPkcs7(encryptedPayload, key, iv) ?: return null
        if (decrypted.size < 4 || decrypted.first() != 0x55.toByte() || decrypted.last() != 0xAA.toByte()) {
            logWarn("parseSnHistoryBatch: invalid decrypted envelope ${decrypted.toHexCompact()}")
            return null
        }
        val baseSequence = parseLeUnsigned(decrypted, 1)
        val directGlucoseEncoding = ICanHealthConstants.usesDirectSnHistoryGlucoseEncoding(onboardingDeviceSn)
        val records = ArrayList<ICanHealthSnHistoryRecord>()
        var sequenceNumber = baseSequence
        var offset = ICanHealthConstants.SN_HISTORY_RECORD_START_OFFSET
        val recordSize = when (subtype) {
            ICanHealthConstants.SN_HISTORY_SUBTYPE_ORIGINAL -> ICanHealthConstants.SN_HISTORY_RECORD_SIZE_ORIGINAL
            ICanHealthConstants.SN_HISTORY_SUBTYPE_GLUCOSE -> ICanHealthConstants.SN_HISTORY_RECORD_SIZE_GLUCOSE
            else -> return null
        }
        while (offset + recordSize <= decrypted.lastIndex) {
            when (subtype) {
                ICanHealthConstants.SN_HISTORY_SUBTYPE_ORIGINAL -> {
                    val currentValue = decodeCurrentValue(decrypted[offset], decrypted[offset + 1])
                    val temperature = parseLeUnsigned(decrypted, offset + 2) / 10.0f
                    records += ICanHealthSnHistoryRecord(
                        sequenceNumber = sequenceNumber,
                        subtype = subtype,
                        currentValue = currentValue,
                        temperatureC = temperature,
                    )
                }
                ICanHealthConstants.SN_HISTORY_SUBTYPE_GLUCOSE -> {
                    val field = ICanHealthConstants.decodeGlucoseField(
                        low = decrypted[offset].toInt(),
                        high = decrypted[offset + 1].toInt(),
                        directGlucoseEncoding = directGlucoseEncoding,
                    )
                    val dataState = field.nibble
                    val glucoseMmolL = field.glucoseMmolL
                    val glucoseMgdl = field.glucoseMgdl
                    records += ICanHealthSnHistoryRecord(
                        sequenceNumber = sequenceNumber,
                        subtype = subtype,
                        glucoseMmolL = glucoseMmolL,
                        glucoseMgdl = glucoseMgdl,
                        dataState = dataState,
                    )
                }
            }
            sequenceNumber += readingIntervalMinutes
            offset += recordSize
        }
        return ICanHealthSnHistoryBatch(
            subtype = subtype,
            baseSequenceNumber = baseSequence,
            records = records,
        )
    }

    fun parseEncryptedHistoryRecord(
        data: ByteArray,
        authChallenge: ByteArray,
        readingOriginalHistory: Boolean,
        onboardingDeviceSn: String?,
        launcherSerial: String?,
        softwareVersion: String?,
        warmupMinutes: Int,
    ): ICanHealthSnHistoryRecord? {
        val subtype = when {
            data.size == ICanHealthConstants.AES_BLOCK_SIZE ->
                if (readingOriginalHistory) {
                    ICanHealthConstants.SN_HISTORY_SUBTYPE_ORIGINAL
                } else {
                    ICanHealthConstants.SN_HISTORY_SUBTYPE_GLUCOSE
                }
            data.size >= ICanHealthConstants.GLUCOSE_NOTIFICATION_SIZE -> {
                val headerType = data[2].toInt() and 0xFF
                when (headerType) {
                    ICanHealthConstants.SN_HISTORY_SUBTYPE_ORIGINAL,
                    ICanHealthConstants.SN_HISTORY_SUBTYPE_GLUCOSE -> headerType
                    else -> return null
                }
            }
            else -> return null
        }
        val encryptedPayload = when {
            data.size == ICanHealthConstants.AES_BLOCK_SIZE -> data
            data.size >= ICanHealthConstants.GLUCOSE_NOTIFICATION_SIZE -> data.copyOfRange(3, 19)
            else -> return null
        }
        val keyAscii = when (subtype) {
            ICanHealthConstants.SN_HISTORY_SUBTYPE_ORIGINAL ->
                ICanHealthConstants.resolveBundledOriginalHistoryKey(launcherSerial, softwareVersion)
            ICanHealthConstants.SN_HISTORY_SUBTYPE_GLUCOSE ->
                ICanHealthConstants.resolveBundledGlucoseKey(launcherSerial, softwareVersion)
            else -> return null
        }
        val key = ICanHealthCrypto.keyFromASCII(keyAscii) ?: return null
        val iv = buildAuthIv(authChallenge) ?: return null
        val decrypted = ICanHealthCrypto.decryptCbcPkcs7(encryptedPayload, key, iv) ?: return null
        return parseDecryptedHistoryRecord(
            decrypted = decrypted,
            subtype = subtype,
            authChallenge = authChallenge,
            onboardingDeviceSn = onboardingDeviceSn,
            usesNewBundledCrypto = ICanHealthConstants.usesNewBundledCrypto(launcherSerial, softwareVersion),
            warmupMinutes = warmupMinutes,
        )
    }

    /**
     * Check if data matches the challenge response prefix (06 F3 01).
     */
    fun isChallengeResponse(data: ByteArray): Boolean {
        if (data.size < 7) return false
        return data[0] == 0x06.toByte() &&
                data[1] == 0xF3.toByte() &&
                data[2] == 0x01.toByte()
    }

    /**
     * Extract 4-byte challenge from a challenge response.
     */
    fun extractChallenge(data: ByteArray): ByteArray? {
        if (!isChallengeResponse(data) || data.size < 7) return null
        return data.copyOfRange(3, 7)
    }

    /**
     * Check if data matches auth success response (1C F4 01).
     */
    fun isAuthSuccess(data: ByteArray): Boolean {
        if (data.size < 3) return false
        return data[0] == 0x1C.toByte() &&
                data[1] == 0xF4.toByte() &&
                data[2] == 0x01.toByte()
    }

    fun parseAuthResult(data: ByteArray): Int? {
        if (data.size < 3) return null
        if (data[1] != 0xF4.toByte()) return null
        return data[2].toInt() and 0xFF
    }

    /**
     * When the sensor rejects auth with status 0x02 and a non-zero trailing byte,
     * the response contains the **real userId** the sensor expects, encrypted with
     * the same auth key and challenge-derived IV.
     *
     * Response format: [XX, F4, 02, <16 encrypted bytes>, <non-zero trailer>]
     *
     * Returns the recovered userId string (alphanumeric only), or null if not applicable.
     */
    fun recoverUserIdFromAuthReject(
        data: ByteArray,
        challenge: ByteArray?,
        authKey: ByteArray
    ): String? {
        // Must be F4 response with status 0x02 and enough data
        if (data.size < 20) return null                        // need at least 3 header + 16 cipher + 1 trailer
        if (data[1] != 0xF4.toByte()) return null
        if ((data[2].toInt() and 0xFF) != 0x02) return null
        if (data[data.size - 1] == 0.toByte()) return null     // last byte must be non-zero

        val chal = challenge ?: return null
        val iv = buildAuthIv(chal) ?: return null

        // Extract 16 encrypted bytes starting at offset 3
        val ciphertext = data.copyOfRange(3, 19)
        val plaintext = ICanHealthCrypto.decryptCbcPkcs7(ciphertext, authKey, iv) ?: return null

        // Decode as UTF-8 and strip non-alphanumeric chars (same as vendor SDK)
        val raw = String(plaintext, Charsets.UTF_8)
        val cleaned = raw.replace(Regex("[^a-zA-Z0-9\\s]"), "").trim()
        return cleaned.ifEmpty { null }
    }

    fun parseStartSensorResult(data: ByteArray): Boolean? {
        if (data.size < 3) return null
        if ((data[1].toInt() and 0xFF) != 0x1A) return null
        return data[2].toInt() == 0x01
    }

    fun parseCalibrationResult(data: ByteArray): Int? {
        if (data.size < 3) return null
        if (data[1] != ICanHealthConstants.CALIBRATION_OPCODE) return null
        return data[2].toInt() and 0xFF
    }

    fun isSensorInfoResponse(data: ByteArray): Boolean {
        if (data.size < 3) return false
        return data[0] == 0x1C.toByte() &&
                data[1] == 0xF6.toByte() &&
                data[2] == 0x01.toByte()
    }

    fun parseRawDeviceSerial(data: ByteArray): String {
        if (data.isEmpty()) return ""
        val printableAscii = data.all { byte ->
            val ch = byte.toInt() and 0xFF
            ch in 0x20..0x7E
        }
        return if (printableAscii) {
            String(data, Charsets.US_ASCII).trim().ifEmpty { data.toHexCompact() }
        } else {
            data.toHexCompact()
        }
    }

    fun parseDeviceSerial(data: ByteArray): String {
        val rawSerial = parseRawDeviceSerial(data)
        return ICanHealthConstants.canonicalSensorId(rawSerial)
    }

    /**
     * Build F4 auth token packet: [F4, 10, token_16bytes]
     */
    fun buildAuthTokenPacket(token: ByteArray): ByteArray? {
        if (token.size != 16) return null
        val packet = ByteArray(18)
        packet[0] = ICanHealthConstants.AUTH_TOKEN_PREFIX
        packet[1] = ICanHealthConstants.AUTH_TOKEN_LENGTH_BYTE
        System.arraycopy(token, 0, packet, 2, 16)
        return packet
    }

    fun deriveAuthToken(challenge: ByteArray, userId: String, authKey: ByteArray): ByteArray? {
        val iv = buildAuthIv(challenge) ?: return null
        val plaintext = buildAuthPlaintext(userId)
        val encrypted = ICanHealthCrypto.encryptCbcPkcs7(plaintext, authKey, iv) ?: return null
        if (encrypted.size != ICanHealthConstants.AES_BLOCK_SIZE) {
            logError("deriveAuthToken: expected 16-byte auth block, got ${encrypted.size}")
            return null
        }
        return encrypted
    }

    fun buildCalibrationPacket(
        glucoseMgDl: Int,
        sequenceNumber: Int,
        authChallenge: ByteArray,
        glucoseKey: ByteArray,
    ): ByteArray? {
        val iv = buildAuthIv(authChallenge) ?: return null
        val centiMmol = ((glucoseMgDl / ICanHealthConstants.MMOL_TO_MGDL) * 100f)
            .toInt()
            .coerceIn(0, 0xFFFF)
        val safeSequence = sequenceNumber.coerceIn(0, 0xFFFF)
        val plaintext = ByteArray(14).apply {
            this[0] = (safeSequence and 0xFF).toByte()
            this[1] = ((safeSequence ushr 8) and 0xFF).toByte()
            this[2] = (centiMmol and 0xFF).toByte()
            this[3] = ((centiMmol ushr 8) and 0xFF).toByte()
        }
        val encrypted = ICanHealthCrypto.encryptCbcPkcs7(plaintext, glucoseKey, iv) ?: return null
        return ByteArray(2 + encrypted.size).apply {
            this[0] = ICanHealthConstants.CALIBRATION_OPCODE
            this[1] = encrypted.size.toByte()
            System.arraycopy(encrypted, 0, this, 2, encrypted.size)
        }
    }

    private fun buildAuthIv(challenge: ByteArray): ByteArray? {
        if (challenge.size != 4) {
            logError("buildAuthIv: expected 4 challenge bytes, got ${challenge.size}")
            return null
        }
        return ByteArray(ICanHealthConstants.AES_BLOCK_SIZE).apply {
            this[1] = challenge[0]
            this[6] = challenge[1]
            this[10] = challenge[2]
            this[15] = challenge[3]
        }
    }

    private fun buildAuthPlaintext(userId: String): ByteArray {
        val normalizedUserId = ICanHealthConstants.normalizeStandaloneUserId(userId)
        val userIdBytes = normalizedUserId.toByteArray(Charsets.US_ASCII)
        return ByteArray(14).apply {
            this[0] = ICanHealthConstants.AUTH_TOKEN_PREFIX
            this[1] = userIdBytes.size.toByte()
            System.arraycopy(userIdBytes, 0, this, 2, userIdBytes.size)
        }
    }

    /**
     * Check if data matches the glucose-history/original-history success response (06 00 F1 01).
     */
    fun isRACPComplete(data: ByteArray): Boolean {
        return parseRacpResultCode(data) == ICanHealthConstants.RACP_RESULT_SUCCESS
    }

    fun parseRacpResultCode(data: ByteArray): Int? {
        if (data.size < 4) return null
        if (data[0] != 0x06.toByte() ||
            data[1] != 0x00.toByte() ||
            (data[2] != 0xF1.toByte() && data[2] != 0x01.toByte())) {
            return null
        }
        return data[3].toInt() and 0xFF
    }

    private fun parseDecryptedHistoryRecord(
        decrypted: ByteArray,
        subtype: Int,
        authChallenge: ByteArray,
        onboardingDeviceSn: String?,
        usesNewBundledCrypto: Boolean,
        warmupMinutes: Int,
    ): ICanHealthSnHistoryRecord? {
        if (decrypted.size < 13) {
            logWarn("parseDecryptedHistoryRecord: decrypted payload too short (${decrypted.size} bytes)")
            return null
        }
        val sequenceNumber = parseLeUnsigned(decrypted, 4)
        return when (subtype) {
            ICanHealthConstants.SN_HISTORY_SUBTYPE_ORIGINAL -> {
                val currentValue = decodeCurrentValue(decrypted[9], decrypted[10])
                val temperature = parseLeUnsigned(decrypted, 11) / 10.0f
                ICanHealthSnHistoryRecord(
                    sequenceNumber = sequenceNumber,
                    subtype = subtype,
                    currentValue = currentValue,
                    temperatureC = temperature,
                )
            }

            ICanHealthConstants.SN_HISTORY_SUBTYPE_GLUCOSE -> {
                val directGlucoseEncoding =
                    ICanHealthConstants.usesDirectSnHistoryGlucoseEncoding(onboardingDeviceSn)
                val glucoseBytes = decodeHistoryGlucoseBytes(
                    low = decrypted[2],
                    high = decrypted[3],
                    sequenceNumber = sequenceNumber,
                    authChallenge = authChallenge,
                    usesNewBundledCrypto = usesNewBundledCrypto,
                    warmupMinutes = warmupMinutes,
                ) ?: return null
                val field = ICanHealthConstants.decodeGlucoseField(
                    low = glucoseBytes[0].toInt(),
                    high = glucoseBytes[1].toInt(),
                    directGlucoseEncoding = directGlucoseEncoding,
                )
                val dataState = field.nibble
                val glucoseMmolL = field.glucoseMmolL
                val glucoseMgdl = field.glucoseMgdl
                val currentValue = decodeCurrentValue(decrypted[9], decrypted[10])
                val temperature = parseLeUnsigned(decrypted, 11) / 10.0f
                ICanHealthSnHistoryRecord(
                    sequenceNumber = sequenceNumber,
                    subtype = subtype,
                    glucoseMmolL = glucoseMmolL,
                    glucoseMgdl = glucoseMgdl,
                    dataState = dataState,
                    currentValue = currentValue,
                    temperatureC = temperature,
                )
            }

            else -> null
        }
    }

    private fun decodeHistoryGlucoseBytes(
        low: Byte,
        high: Byte,
        sequenceNumber: Int,
        authChallenge: ByteArray?,
        usesNewBundledCrypto: Boolean,
        warmupMinutes: Int,
    ): ByteArray? {
        if (sequenceNumber >= warmupMinutes) {
            return byteArrayOf(low, high)
        }
        val challenge = authChallenge
        if (challenge == null || challenge.size < 4) {
            // During warm-up these bytes are challenge-dependent XOR data. Without all four
            // challenge bytes they cannot be decoded safely; accepting them verbatim can turn
            // the encoded value into plausible but dangerously wrong glucose.
            logWarn(
                "decodeGlucose: rejecting warm-up seq=$sequenceNumber without a 4-byte auth challenge"
            )
            return null
        }
        val table = if (usesNewBundledCrypto) {
            ICanHealthConstants.LEGACY_HISTORY_GLUCOSE_XOR_TABLE_NEW
        } else {
            ICanHealthConstants.LEGACY_HISTORY_GLUCOSE_XOR_TABLE_OLD
        }
        val offset = (challenge[3].toInt() and 0xFF) % (table.size - 2)
        return byteArrayOf(
            ((low.toInt() and 0xFF) xor (table[offset].toInt() and 0xFF)).toByte(),
            ((high.toInt() and 0xFF) xor (table[offset + 1].toInt() and 0xFF)).toByte(),
        )
    }

    private fun parseLeUnsigned(bytes: ByteArray, offset: Int): Int {
        if (offset !in 0 until bytes.size) return 0
        val lo = bytes[offset].toInt() and 0xFF
        val hi = bytes.getOrElse(offset + 1) { 0 }.toInt() and 0xFF
        return lo or (hi shl 8)
    }

    private fun decodeCurrentValue(lo: Byte, hi: Byte): Float {
        // Vendor SDK decodes current with the fixed `b.c[]` table (all 0x88 bytes),
        // then prefixes the resulting 5-digit number with "25" before parsing it
        // as a float. Keep the exact vendor representation here for internal use;
        // the generic app raw-glucose lane should not consume this value directly.
        val decoded = ((lo.toInt() and 0xFF) xor 0x88) or (((hi.toInt() and 0xFF) xor 0x88) shl 8)
        val vendorFormatted = "25${decoded.toString().padStart(5, '0')}"
        return vendorFormatted.toFloatOrNull() ?: Float.NaN
    }

    /**
     * Dump everything needed to understand one measurement from a single trace.
     *
     * The decrypted block is otherwise invisible, which has repeatedly made captures unable to
     * answer questions about the encoding. Bytes 6..8 come along because the driver parses
     * nothing from them, and the whole block because the fields it does parse have turned out
     * not to mean what their names say.
     */
    private fun logGlucoseFieldDiagnostics(
        decrypted: ByteArray,
        headerSeq: Int,
        field: ICanHealthGlucoseField,
        temperatureC: Float,
    ) {
        runCatching {
            Log.i(
                TAG,
                "glucose field seq=$headerSeq nibble=${field.nibble} mantissa=${field.mantissa}" +
                    " -> ${"%.1f".format(field.glucoseMmolL)} mmol/L" +
                    " (${"%.1f".format(field.glucoseMgdl)} mg/dL)" +
                    " | flags=0x${"%02X".format(decrypted[1])}" +
                    " status=${decrypted.copyOfRange(6, minOf(9, decrypted.size)).toHex()}" +
                    " field11_12=${"%.1f".format(temperatureC)}" +
                    " block=${decrypted.toHex()}"
            )
        }
    }

    private fun ByteArray.toHex(): String = joinToString(" ") { "%02X".format(it) }

    private fun logWarn(message: String) {
        runCatching { Log.w(TAG, message) }
    }

    private fun logError(message: String) {
        runCatching { Log.e(TAG, message) }
    }
}

private fun ByteArray.toHexCompact(): String =
    joinToString(separator = "") { "%02X".format(it) }
