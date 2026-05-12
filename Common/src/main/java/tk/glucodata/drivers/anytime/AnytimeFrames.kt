// AnytimeFrames.kt — Wire-format builders, parsers and integrity checks.
//
// Plain CT3 frames are 1-7 bytes for most commands and have NO trailing sum
// byte. CT2.5 / CT3_YUWELL / CT3_PLUS / CT3_ULTRASONIC / CT4 use the same
// opcode set but append an 8-bit sum byte to protocol requests. Voltage switch
// commands always carry a sum byte. We expose:
//
//   AnytimeFrames.sum(bytes, [from..to])     — compute sum byte
//   AnytimeFrames.verifySum(bytes)           — true if last byte == sum of rest
//   AnytimeFrames.builders                   — CT3 packet builders
//   AnytimeFrames.parseRawRecords(bytes)     — 9-byte or 11-byte raw records
//   AnytimeFrames.parseComputedRecord(bytes) — 19-byte computed glucose record
//   AnytimeFrames.parseCheckResponse(bytes)  — 0x05 health response
//   AnytimeFrames.parseResetResponse(bytes)  — 0x11 reset response

package tk.glucodata.drivers.anytime

import java.util.Calendar
import kotlin.math.roundToInt

/** Single raw current record from RX_PUSH_GLUCOSE / RX_PULL_GLUCOSE. */
data class AnytimeRawRecord(
    val indexInPacket: Int,
    val glucoseId: Int,
    val ibNa: Float,
    val iwNa: Float,
    val temperatureC: Float,
    val recordBytes: ByteArray,
)

/** 19-byte computed-glucose record (CT5 push, CT3 on demand). */
data class AnytimeComputedRecord(
    val glucoseId: Int,
    val hypoEarlyWarnMinutes: Int,
    val hyperEarlyWarnMinutes: Int,
    val ibNa: Float,
    val iwNa: Float,
    val temperatureC: Float,
    val gluMmol: Float,
    val referenceBgMmol: Float,
    val errorCode: Int,
    val trend: Int,
    val warnCode: Int,
) {
    val gluMgdl: Int get() = (gluMmol * 18.0f + 0.5f).toInt()
}

/** 0x05 check-response — battery, sensor age, IW. */
data class AnytimeCheckStatus(
    val sensorAgeReadings: Int,
    val workingElectrodeCurrentNa: Float,
    val batteryVolts: Float,
    /** True when the snapshot looks healthy enough to enter active session. */
    val isHealthy: Boolean,
    /** When unhealthy, a hint at the failure reason (mirrors `EnumConnect.*`). */
    val failure: CheckFailure?,
) {
    enum class CheckFailure { LOW_BATTERY, LOW_IW, BAD_TEMPERATURE, MALFORMED }
}

/** 0x11 reset response. byte 2 != 0 ⇒ device was bound. */
data class AnytimeResetStatus(val isBound: Boolean)

/** Generic frame parsed from a notification. */
data class AnytimeFrame(
    val opcode: Byte,
    val payload: ByteArray,
    val raw: ByteArray,
) {
    val opcodeUnsigned: Int get() = opcode.toInt() and 0xFF
}

object AnytimeFrames {

    // ---- Integrity ----

    @JvmStatic
    fun sum(bytes: ByteArray, fromInclusive: Int = 0, toInclusive: Int = bytes.size - 2): Byte {
        var acc = 0
        var i = fromInclusive
        while (i <= toInclusive && i < bytes.size) {
            acc += bytes[i].toInt() and 0xFF
            i++
        }
        return acc.toByte()
    }

    /**
     * Whether the trailing byte equals `sum(bytes[0..len-2])`.
     * For CT3 single-byte commands (`{0x06}`, etc.) this is meaningless — callers
     * gate on opcode + length first.
     */
    @JvmStatic
    fun verifySum(bytes: ByteArray): Boolean {
        if (bytes.size < 2) return false
        val expected = bytes[bytes.size - 1]
        val computed = sum(bytes, 0, bytes.size - 2)
        return expected == computed
    }

    // ---- Generic frame split (find opcode/payload boundaries) ----

    @JvmStatic
    fun parse(buffer: ByteArray?): AnytimeFrame? {
        if (buffer == null || buffer.isEmpty()) return null
        val opcode = buffer[0]
        val payload = if (buffer.size > 1) buffer.copyOfRange(1, buffer.size) else ByteArray(0)
        return AnytimeFrame(opcode = opcode, payload = payload, raw = buffer.copyOf())
    }

    // ---- Builders (TX → sensor) ----

    object Builders {

        @JvmStatic
        fun version(): ByteArray = byteArrayOf(AnytimeConstants.TX_VERSION)

        /** {0x05} — health check. */
        @JvmStatic
        fun check(): ByteArray = byteArrayOf(AnytimeConstants.TX_CHECK)

        /** {0x05, 0x55, 0xAA, sum} — CT2.5/CT3A/CT4 health check. */
        @JvmStatic
        fun checkSummed(): ByteArray = withSum(0x05, 0x55, 0xAA)

        /** {0x06} — init. */
        @JvmStatic
        fun init(): ByteArray = byteArrayOf(AnytimeConstants.TX_INIT)

        /** {0x06, 0x55, 0xAA, sum} — CT2.5/CT3A/CT4 init. */
        @JvmStatic
        fun initSummed(): ByteArray = withSum(0x06, 0x55, 0xAA)

        /** {0x0F} — low power. */
        @JvmStatic
        fun lowPower(): ByteArray = byteArrayOf(AnytimeConstants.TX_LOW_POWER)

        /** {0x0F, 0x55, 0xAA, sum} — CT2.5/CT3A/CT4/CT5 low power. */
        @JvmStatic
        fun lowPowerSummed(): ByteArray = withSum(0x0F, 0x55, 0xAA)

        /** {0x11} — reset. */
        @JvmStatic
        fun reset(): ByteArray = byteArrayOf(AnytimeConstants.TX_RESET)

        /** {0x11, 0x55, 0xAA, sum} — CT2.5/CT3A/CT4 reset. */
        @JvmStatic
        fun resetSummed(): ByteArray = withSum(0x11, 0x55, 0xAA)

        /** {0x0A} — unbind. */
        @JvmStatic
        fun unbind(): ByteArray = byteArrayOf(AnytimeConstants.TX_UNBIND)

        /** {0x0A, 0x55, 0xAA, sum} — CT2.5/CT3A/CT4 unbind. */
        @JvmStatic
        fun unbindSummed(): ByteArray = withSum(0x0A, 0x55, 0xAA)

        /** {0x03, year-1900, mon+1, day, hour, min, sec}. */
        @JvmStatic
        @JvmOverloads
        fun setDate(calendar: Calendar = Calendar.getInstance()): ByteArray {
            val year = (calendar.get(Calendar.YEAR) - 1900) and 0xFF
            val month = (calendar.get(Calendar.MONTH) + 1) and 0xFF
            val day = calendar.get(Calendar.DAY_OF_MONTH) and 0xFF
            val hour = calendar.get(Calendar.HOUR_OF_DAY) and 0xFF
            val minute = calendar.get(Calendar.MINUTE) and 0xFF
            val second = calendar.get(Calendar.SECOND) and 0xFF
            return byteArrayOf(
                AnytimeConstants.TX_SET_DATE,
                year.toByte(),
                month.toByte(),
                day.toByte(),
                hour.toByte(),
                minute.toByte(),
                second.toByte(),
            )
        }

        /** {0x03, year-1900, mon+1, day, hour, min, sec, sum}. */
        @JvmStatic
        @JvmOverloads
        fun setDateSummed(calendar: Calendar = Calendar.getInstance()): ByteArray {
            val base = setDate(calendar)
            val frame = base.copyOf(base.size + 1)
            frame[frame.lastIndex] = sum(frame, 0, frame.lastIndex - 1)
            return frame
        }

        /** {0x08, idLo, idHi}. */
        @JvmStatic
        fun pullGlucose(nextId: Int): ByteArray {
            val id = nextId and 0xFFFF
            return byteArrayOf(
                AnytimeConstants.TX_PULL_GLUCOSE,
                (id and 0xFF).toByte(),
                ((id ushr 8) and 0xFF).toByte(),
            )
        }

        /** {0x08, idLo, idHi, sum} — CT2.5/CT3A/CT4 pull. */
        @JvmStatic
        fun pullGlucoseSummed(nextId: Int): ByteArray {
            val id = nextId and 0xFFFF
            return withSum(
                0x08,
                id and 0xFF,
                (id ushr 8) and 0xFF,
            )
        }

        /** {0x0C, idLo, idHi} — re-fetch as transmitter-computed glucose. */
        @JvmStatic
        fun fetchComputedGlucose(nextId: Int): ByteArray {
            val id = nextId and 0xFFFF
            return byteArrayOf(
                AnytimeConstants.TX_FETCH_COMPUTED_GLUCOSE,
                (id and 0xFF).toByte(),
                ((id ushr 8) and 0xFF).toByte(),
            )
        }

        /**
         * {0x09, mmolInt, mmolFrac/10}.
         * The transmitter takes a fingerstick reference in mg/dL ÷ 18, encoded as
         * `int + fraction/10`. Convert mg/dL → mmol/L × 10 then split.
         */
        @JvmStatic
        fun inputBgMg(mgdl: Int): ByteArray {
            // mmol = mgdl / 18.0; one decimal of precision.
            val tenths = ((mgdl * 10) / 18) // truncating mirror of the official app
            val intPart = tenths / 10
            val fracPart = tenths - intPart * 10
            return byteArrayOf(
                AnytimeConstants.TX_INPUT_BG_MG,
                (intPart and 0xFF).toByte(),
                (fracPart and 0xFF).toByte(),
            )
        }

        /** {0x09, mmolInt, mmolFrac/10, sum}. */
        @JvmStatic
        fun inputBgMgSummed(mgdl: Int): ByteArray {
            val tenths = ((mgdl * 10) / 18)
            val intPart = tenths / 10
            val fracPart = tenths - intPart * 10
            return withSum(
                0x09,
                intPart and 0xFF,
                fracPart and 0xFF,
            )
        }

        /** {0x0B, KInt, KFrac/10, RInt, RFrac/10}. */
        @JvmStatic
        fun inputKR(k: Float, r: Float): ByteArray {
            val kInt = k.toInt()
            val kFrac = ((k - kInt) * 10f).toInt() and 0xFF
            val rInt = r.toInt()
            val rFrac = ((r - rInt) * 10f).toInt() and 0xFF
            return byteArrayOf(
                AnytimeConstants.TX_INPUT_KR,
                (kInt and 0xFF).toByte(),
                kFrac.toByte(),
                (rInt and 0xFF).toByte(),
                rFrac.toByte(),
            )
        }

        /** {0x0B, KInt, KFrac/100, RInt, RFrac/100, sum}. */
        @JvmStatic
        fun inputKRSummed(k: Float, r: Float): ByteArray {
            val kInt = k.toInt()
            val kFrac = ((k - kInt) * 100f).toInt() and 0xFF
            val rInt = r.toInt()
            val rFrac = ((r - rInt) * 100f).toInt() and 0xFF
            return withSum(
                0x0B,
                kInt and 0xFF,
                kFrac,
                rInt and 0xFF,
                rFrac,
            )
        }

        /**
         * {0x15, voltage, 0×9, sum} — CT3_PLUS / CT3_YUWELL / CT3_ULTRASONIC / CT4
         * voltage switch. `voltage` is 0 (1V mode) or 1 (2V mode), determined by
         * the QR contents (`KRDecodeData` first sensor character).
         */
        @JvmStatic
        fun modifyVoltage(voltage: Int): ByteArray {
            val frame = ByteArray(12)
            frame[0] = AnytimeConstants.TX_MODIFY_VOLTAGE
            frame[1] = (voltage and 0xFF).toByte()
            // Bytes 2..10 are zero.
            frame[11] = sum(frame, 0, 10)
            return frame
        }

        /** {0x20} — formal version request for plain CT3. */
        @JvmStatic
        fun transmitterFormal(): ByteArray = byteArrayOf(AnytimeConstants.TX_TRANSMITTER_FORMAL)

        /** {0x20, 0x55, 0xAA, sum} — CT2.5/CT3A/CT4 formal version request. */
        @JvmStatic
        fun transmitterFormalSummed(): ByteArray = withSum(0x20, 0x55, 0xAA)

        /** CT5 get-date request. The response is opcode 0x03. */
        @JvmStatic
        fun ct5GetDate(): ByteArray = withSum(0x04, 0x55, 0xAA)

        /** CT5 reconnect identity check. */
        @JvmStatic
        fun ct5CheckId(randomB: IntArray): ByteArray {
            require(randomB.size == 4) { "CT5 randomB must be 4 bytes" }
            return withSum(
                AnytimeConstants.TX_CT5_CHECK_ID.toInt() and 0xFF,
                randomB[0],
                randomB[1],
                randomB[2],
                randomB[3],
            )
        }

        /** CT5 identity challenge: opcode 0x30 + randomB + convolve(randomB, randomA). */
        @JvmStatic
        fun ct5SetId(randomA: IntArray, randomB: IntArray): ByteArray {
            require(randomA.size == 4 && randomB.size == 4) { "CT5 random vectors must be 4 bytes" }
            val convolution = ct5Convolve(randomB, randomA)
            val values = IntArray(1 + randomB.size + convolution.size)
            values[0] = AnytimeConstants.TX_CT5_SET_ID.toInt() and 0xFF
            for (i in randomB.indices) values[1 + i] = randomB[i]
            for (i in convolution.indices) values[1 + randomB.size + i] = convolution[i]
            return withSum(*values)
        }

        /** CT5 encrypted QR/KR query. */
        @JvmStatic
        fun ct5QuerySsn(): ByteArray = withSum(AnytimeConstants.TX_CT5_QUERY_SSN.toInt() and 0xFF, 0x55, 0xAA)

        /** CT5 push ACK. */
        @JvmStatic
        fun ct5PushAck(): ByteArray = withSum(AnytimeConstants.TX_CT5_PUSH_ACK.toInt() and 0xFF, 0x55, 0xAA)

        /** CT5 series-history pull. */
        @JvmStatic
        @JvmOverloads
        fun ct5PullGlucoseSeries(nextId: Int, count: Int = 15): ByteArray {
            val id = nextId and 0xFFFF
            return withSum(
                AnytimeConstants.TX_CT5_PULL_SERIES.toInt() and 0xFF,
                id and 0xFF,
                (id ushr 8) and 0xFF,
                count.coerceAtLeast(1) and 0xFF,
            )
        }

        /** CT5 fingerstick reference BG is mg/dL big-endian, not mmol split. */
        @JvmStatic
        fun ct5InputBgMg(mgdl: Int): ByteArray =
            withSum(0x09, (mgdl ushr 8) and 0xFF, mgdl and 0xFF)

        /** CT5 unbind needs the same four-character temp id used during setup. */
        @JvmStatic
        fun ct5Unbind(tempId: String): ByteArray {
            val bytes = tempId.take(4).padStart(4, '0').toByteArray(Charsets.US_ASCII)
            return withSum(0x0A, bytes[0].toInt(), bytes[1].toInt(), bytes[2].toInt(), bytes[3].toInt())
        }

        /** CT5 encrypted K/R + temporary id setup. */
        @JvmStatic
        fun ct5SetParameters(k: Float, r: Float, cipherKey: Int, tempId: String): ByteArray {
            require(cipherKey in 0..255) { "CT5 cipher key must be in 0..255" }
            val tempBytes = tempId.take(4).padStart(4, '0').toByteArray(Charsets.US_ASCII)
            val payload = ByteArray(12)
            payload[0] = k.toInt().coerceIn(0, 255).toByte()
            payload[1] = fractionByte(k, scaleTenths = 100, roundToTenthsFirst = false)
            payload[2] = r.toInt().coerceIn(0, 255).toByte()
            payload[3] = fractionByte(r, scaleTenths = 100, roundToTenthsFirst = true)
            payload[4] = 0x03
            payload[5] = 0x53
            payload[6] = 0x55
            payload[7] = 0x00
            for (i in tempBytes.indices) payload[8 + i] = tempBytes[i]
            val encrypted = ct5Decode(payload, cipherKey)
            val frame = ByteArray(14)
            frame[0] = AnytimeConstants.TX_CT5_SET_PARAMETERS
            encrypted.copyInto(frame, destinationOffset = 1)
            frame[13] = sum(frame, 0, 12)
            return frame
        }

        private fun withSum(vararg values: Int): ByteArray {
            val frame = ByteArray(values.size + 1)
            for (i in values.indices) {
                frame[i] = (values[i] and 0xFF).toByte()
            }
            frame[frame.lastIndex] = sum(frame, 0, frame.lastIndex - 1)
            return frame
        }

        private fun fractionByte(value: Float, scaleTenths: Int, roundToTenthsFirst: Boolean): Byte {
            val intPart = value.toInt()
            val rounded = if (roundToTenthsFirst) {
                (value * 10f).roundToInt() / 10f
            } else {
                (value * 100f).roundToInt() / 100f
            }
            return (((rounded - intPart) * scaleTenths).roundToInt() and 0xFF).toByte()
        }
    }

    // ---- CT5 obfuscation/session helpers ----

    @JvmStatic
    fun ct5Convolve(first: IntArray, second: IntArray): IntArray {
        val out = IntArray(first.size)
        for (i in first.indices) {
            for (j in second.indices) {
                val k = i - j
                if (k >= 0 && j < first.size && k < second.size) {
                    out[i] += (first[j] and 0xFF) * (second[k] and 0xFF)
                }
            }
        }
        return out
    }

    /** ConvertTools.decode(): used by the official app for outgoing encrypted CT5 setup payloads. */
    @JvmStatic
    fun ct5Decode(bytes: ByteArray, cipherKey: Int): ByteArray {
        val bits = bytesToBits(bytes)
        for (i in bits.size - 2 downTo 0) {
            if (bits[i + 1] == 0) bits[i] = bits[i] xor 1
        }
        val out = ByteArray(bytes.size)
        for (i in out.indices) {
            out[i] = (bitsToByte(bits, i * 8) xor (cipherKey and 0xFF)).toByte()
        }
        return out
    }

    /** ConvertTools.encode(): used by the official app for incoming encrypted CT5 payloads. */
    @JvmStatic
    fun ct5Encode(bytes: ByteArray, cipherKey: Int): ByteArray {
        val bits = IntArray(bytes.size * 8)
        for (i in bytes.indices) {
            val value = (bytes[i].toInt() and 0xFF) xor (cipherKey and 0xFF)
            for (bit in 0 until 8) {
                bits[i * 8 + bit] = (value ushr (7 - bit)) and 1
            }
        }
        var i = 0
        while (i < bits.size - 1) {
            if (bits[i + 1] == 0) bits[i] = bits[i] xor 1
            i++
        }
        val out = ByteArray(bytes.size)
        for (byteIndex in out.indices) {
            out[byteIndex] = bitsToByte(bits, byteIndex * 8).toByte()
        }
        return out
    }

    @JvmStatic
    fun ct5SessionKeyFromSetIdResponse(bytes: ByteArray, randomA: IntArray): Int {
        if (randomA.size != 4 || bytes.isEmpty() || bytes[0] != AnytimeConstants.TX_CT5_SET_ID) return -1
        if (!verifySum(bytes) || bytes.size < 7) return -1
        val responseTail = bytes.copyOfRange(5, bytes.size - 1).map { it.toInt() and 0xFF }.toIntArray()
        if (responseTail.isEmpty()) return -1
        return ct5Convolve(responseTail, randomA).fold(0) { acc, value -> acc xor value } and 0xFF
    }

    @JvmStatic
    fun parseCt5QuerySsnResponse(bytes: ByteArray, cipherKey: Int): String? {
        if (bytes.isEmpty() || bytes[0] != AnytimeConstants.RX_CT5_QUERY_SSN || cipherKey !in 0..255) return null
        val encrypted = bytes.copyOfRange(1, bytes.size)
        val decoded = ct5Encode(encrypted, cipherKey)
        return decoded
            .toString(Charsets.US_ASCII)
            .trim { it <= ' ' || it.code > 0x7E }
            .takeIf { it.isNotBlank() }
    }

    @JvmStatic
    fun parseCt5CurrentRecord(bytes: ByteArray, cipherKey: Int): AnytimeComputedRecord? {
        val voltage = bytes.size == 19
        val expected = if (voltage) 19 else 15
        if (bytes.size != expected || bytes[0] != AnytimeConstants.RX_CT5_PUSH_GLUCOSE) return null
        if (cipherKey !in 0..255 || !verifySum(bytes)) return null
        val decoded = decryptCt5PayloadFrame(bytes, cipherKey)
        val glucoseId = (decoded[1].toInt() and 0xFF) or ((decoded[2].toInt() and 0xFF) shl 8)
        return parseCt5DecodedRecord(decoded, offset = 3, glucoseId = glucoseId, chunkSize = if (voltage) 15 else 11)
    }

    @JvmStatic
    @JvmOverloads
    fun parseCt5SeriesRecords(bytes: ByteArray, cipherKey: Int, voltage: Boolean = false): List<AnytimeComputedRecord> {
        val chunkSize = if (voltage) AnytimeConstants.CT5_VOLTAGE_CHUNK_SIZE else AnytimeConstants.CT5_RAW_CHUNK_SIZE
        if (bytes.isEmpty() || bytes[0] != AnytimeConstants.RX_CT5_SERIES || cipherKey !in 0..255) return emptyList()
        if (!verifySum(bytes)) return emptyList()
        val sanitized = sanitizeCt5SeriesFrame(bytes, chunkSize)
        if (sanitized.size == 4) return emptyList()
        val decoded = decryptCt5PayloadFrame(sanitized, cipherKey)
        val startId = (decoded[1].toInt() and 0xFF) or ((decoded[2].toInt() and 0xFF) shl 8)
        val count = (decoded.size - 4) / chunkSize
        val out = ArrayList<AnytimeComputedRecord>(count)
        for (i in 0 until count) {
            parseCt5DecodedRecord(
                decoded = decoded,
                offset = 3 + i * chunkSize,
                glucoseId = startId + i,
                chunkSize = chunkSize,
            )?.let { out.add(it) }
        }
        return out
    }

    private fun decryptCt5PayloadFrame(bytes: ByteArray, cipherKey: Int): ByteArray {
        val payload = if (bytes.size > 4) bytes.copyOfRange(3, bytes.size - 1) else ByteArray(0)
        val decodedPayload = ct5Encode(payload, cipherKey)
        val decoded = bytes.copyOf()
        decodedPayload.copyInto(decoded, destinationOffset = 3)
        decoded[decoded.lastIndex] = sum(decoded, 0, decoded.lastIndex - 1)
        return decoded
    }

    private fun sanitizeCt5SeriesFrame(bytes: ByteArray, chunkSize: Int): ByteArray {
        if (bytes.size <= 4) return bytes.copyOf()
        val chunks = ArrayList<ByteArray>()
        var offset = 3
        while (offset + chunkSize <= bytes.size - 1) {
            val chunk = bytes.copyOfRange(offset, offset + chunkSize)
            if (chunk.all { (it.toInt() and 0xFF) == 0xFC }) break
            if (!chunk.all { (it.toInt() and 0xFF) == 0xFF }) chunks.add(chunk)
            offset += chunkSize
        }
        val out = ByteArray(4 + chunks.sumOf { it.size })
        out[0] = bytes[0]
        out[1] = bytes[1]
        out[2] = bytes[2]
        var dst = 3
        for (chunk in chunks) {
            chunk.copyInto(out, destinationOffset = dst)
            dst += chunk.size
        }
        out[out.lastIndex] = bytes[bytes.lastIndex]
        return out
    }

    private fun parseCt5DecodedRecord(
        decoded: ByteArray,
        offset: Int,
        glucoseId: Int,
        chunkSize: Int,
    ): AnytimeComputedRecord? {
        if (offset + chunkSize > decoded.size - 1) return null
        val ibRaw = u16(decoded[offset], decoded[offset + 1])
        val iwRaw = u16(decoded[offset + 2], decoded[offset + 3])
        val tPlus40 = decoded[offset + 4].toInt() and 0xFF
        val tFrac = decoded[offset + 5].toInt() and 0xFF
        val temperature = (tPlus40 - AnytimeConstants.TEMP_INT_OFFSET) + tFrac / 100f
        if (ibRaw >= 0xFFF0 || iwRaw >= 0xFFF0 || temperature !in -20f..80f) return null
        val trendAndHigh = decoded[offset + 6].toInt() and 0xFF
        val trend = (trendAndHigh ushr 4) and 0x0F
        val glucoseMgdl = ((trendAndHigh and 0x0F) shl 8) or (decoded[offset + 7].toInt() and 0xFF)
        val error = decoded[offset + 8].toInt() and 0xFF
        if (glucoseMgdl <= 0) return null
        return AnytimeComputedRecord(
            glucoseId = glucoseId,
            hypoEarlyWarnMinutes = 0,
            hyperEarlyWarnMinutes = 0,
            ibNa = ibRaw / 100f,
            iwNa = iwRaw / 100f,
            temperatureC = temperature,
            gluMmol = glucoseMgdl / 18f,
            referenceBgMmol = 0f,
            errorCode = error,
            trend = trend,
            warnCode = 0,
        )
    }

    private fun bytesToBits(bytes: ByteArray): IntArray {
        val bits = IntArray(bytes.size * 8)
        for (i in bytes.indices) {
            val value = bytes[i].toInt() and 0xFF
            for (bit in 0 until 8) {
                bits[i * 8 + bit] = (value ushr (7 - bit)) and 1
            }
        }
        return bits
    }

    private fun bitsToByte(bits: IntArray, offset: Int): Int {
        var value = 0
        for (bit in 0 until 8) value = (value shl 1) or (bits[offset + bit] and 1)
        return value and 0xFF
    }

    private fun u16(hi: Byte, lo: Byte): Int =
        ((hi.toInt() and 0xFF) shl 8) or (lo.toInt() and 0xFF)

    // ---- Parsers (RX from sensor) ----

    /**
     * Parse N raw-current records from a `0x07` push or `0x08` pull notification.
     * The first record is `RAW_RECORD_SIZE` bytes (with leading opcode byte).
     * Subsequent records pack 8 bytes each (opcode skipped).
     */
    @JvmStatic
    fun parseRawRecords(bytes: ByteArray): List<AnytimeRawRecord> =
        parseRawRecords(bytes, wideRecords = false)

    /**
     * CT2.5/CT3A/CT4 raw records encode Ib/Iw as unsigned big-endian shorts
     * divided by 100 and include electric-current bytes after temperature.
     */
    @JvmStatic
    fun parseRawRecords(bytes: ByteArray, wideRecords: Boolean): List<AnytimeRawRecord> {
        if (wideRecords) return parseWideRawRecords(bytes)
        if (bytes.size < AnytimeConstants.RAW_RECORD_SIZE) return emptyList()
        val out = ArrayList<AnytimeRawRecord>()
        var offset = 0
        var index = 0
        // First record: skip opcode byte then read 8 bytes
        if (bytes[0] != AnytimeConstants.RX_PUSH_GLUCOSE &&
            bytes[0] != AnytimeConstants.RX_PULL_GLUCOSE
        ) {
            return emptyList()
        }
        offset = 1
        while (offset + AnytimeConstants.RAW_RECORD_CONTINUATION_SIZE <= bytes.size) {
            val rec = decodeRaw(bytes, offset - 1, index, withLeadingOpcode = (index == 0))
            if (rec != null) out.add(rec)
            offset += AnytimeConstants.RAW_RECORD_CONTINUATION_SIZE
            index++
        }
        return out
    }

    private fun parseWideRawRecords(bytes: ByteArray): List<AnytimeRawRecord> {
        if (bytes.size < AnytimeConstants.WIDE_RAW_RECORD_SIZE) return emptyList()
        if (bytes[0] != AnytimeConstants.RX_PUSH_GLUCOSE &&
            bytes[0] != AnytimeConstants.RX_PULL_GLUCOSE
        ) {
            return emptyList()
        }
        val out = ArrayList<AnytimeRawRecord>()
        var offset = 0
        var index = 0
        while (offset + AnytimeConstants.WIDE_RAW_RECORD_SIZE <= bytes.size) {
            val opcode = bytes[offset]
            if (opcode != AnytimeConstants.RX_PUSH_GLUCOSE &&
                opcode != AnytimeConstants.RX_PULL_GLUCOSE
            ) {
                break
            }
            val idLo = bytes[offset + 1].toInt() and 0xFF
            val idHi = bytes[offset + 2].toInt() and 0xFF
            val isPlaceholder = (offset + 10 < bytes.size) &&
                (offset + 3..offset + 10).all { (bytes[it].toInt() and 0xFF) == 0xFF }
            if (isPlaceholder) break
            val ibRaw = ((bytes[offset + 3].toInt() and 0xFF) shl 8) or (bytes[offset + 4].toInt() and 0xFF)
            val iwRaw = ((bytes[offset + 5].toInt() and 0xFF) shl 8) or (bytes[offset + 6].toInt() and 0xFF)
            val tPlus40 = bytes[offset + 7].toInt() and 0xFF
            val tFrac = bytes[offset + 8].toInt() and 0xFF
            val temperature = (tPlus40 - AnytimeConstants.TEMP_INT_OFFSET) + tFrac / 100f
            if (temperature < -10f || temperature > 60f || ibRaw >= 0xFFF0 || iwRaw >= 0xFFF0) break
            out.add(
                AnytimeRawRecord(
                    indexInPacket = index,
                    glucoseId = idLo or (idHi shl 8),
                    ibNa = ibRaw / 100f,
                    iwNa = iwRaw / 100f,
                    temperatureC = temperature,
                    recordBytes = bytes.copyOfRange(offset + 1, offset + AnytimeConstants.WIDE_RAW_RECORD_SIZE),
                )
            )
            offset += AnytimeConstants.WIDE_RAW_RECORD_SIZE
            index++
        }
        return out
    }

    private fun decodeRaw(
        source: ByteArray,
        baseInclLeadingOpcode: Int,
        index: Int,
        withLeadingOpcode: Boolean,
    ): AnytimeRawRecord? {
        val base = if (withLeadingOpcode) baseInclLeadingOpcode + 1 else baseInclLeadingOpcode + 1
        // Layout (after opcode skipped): id_lo, id_hi, ib_int, ib_frac, iw_int, iw_frac, t+40, t_frac
        if (base + 7 >= source.size) return null
        val idLo = source[base].toInt() and 0xFF
        val idHi = source[base + 1].toInt() and 0xFF
        val ibInt = source[base + 2].toInt() and 0xFF
        val ibFrac = source[base + 3].toInt() and 0xFF
        val iwInt = source[base + 4].toInt() and 0xFF
        val iwFrac = source[base + 5].toInt() and 0xFF
        val tPlus40 = source[base + 6].toInt() and 0xFF
        val tFrac = source[base + 7].toInt() and 0xFF
        val glucoseId = idLo or (idHi shl 8)
        val ib = ibInt + ibFrac / 100f
        val iw = iwInt + iwFrac / 100f
        val temperature = (tPlus40 - AnytimeConstants.TEMP_INT_OFFSET) + tFrac / 100f
        val recordBytes = source.copyOfRange(base, base + 8)
        return AnytimeRawRecord(
            indexInPacket = index,
            glucoseId = glucoseId,
            ibNa = ib,
            iwNa = iw,
            temperatureC = temperature,
            recordBytes = recordBytes,
        )
    }

    /** Parse a 19-byte 0x0C computed-glucose record. */
    @JvmStatic
    fun parseComputedRecord(bytes: ByteArray): AnytimeComputedRecord? {
        if (bytes.size < AnytimeConstants.COMPUTED_RECORD_SIZE) return null
        if (bytes[AnytimeConstants.COMPUTED_OFFSET_OPCODE] != AnytimeConstants.RX_COMPUTED_GLUCOSE) return null
        val idLo = bytes[AnytimeConstants.COMPUTED_OFFSET_ID_LO].toInt() and 0xFF
        val idHi = bytes[AnytimeConstants.COMPUTED_OFFSET_ID_HI].toInt() and 0xFF
        val hypo = bytes[AnytimeConstants.COMPUTED_OFFSET_HYPO_MIN].toInt() and 0xFF
        val hyper = bytes[AnytimeConstants.COMPUTED_OFFSET_HYPER_MIN].toInt() and 0xFF
        val ibInt = bytes[AnytimeConstants.COMPUTED_OFFSET_IB_INT].toInt() and 0xFF
        val ibFrac = bytes[AnytimeConstants.COMPUTED_OFFSET_IB_FRAC].toInt() and 0xFF
        val iwInt = bytes[AnytimeConstants.COMPUTED_OFFSET_IW_INT].toInt() and 0xFF
        val iwFrac = bytes[AnytimeConstants.COMPUTED_OFFSET_IW_FRAC].toInt() and 0xFF
        val tPlus40 = bytes[AnytimeConstants.COMPUTED_OFFSET_T_INT_PLUS_40].toInt() and 0xFF
        val tFrac = bytes[AnytimeConstants.COMPUTED_OFFSET_T_FRAC].toInt() and 0xFF
        val gluInt = bytes[AnytimeConstants.COMPUTED_OFFSET_GLU_INT].toInt() and 0xFF
        val gluFrac = bytes[AnytimeConstants.COMPUTED_OFFSET_GLU_FRAC].toInt() and 0xFF
        val bgInt = bytes[AnytimeConstants.COMPUTED_OFFSET_BG_INT].toInt() and 0xFF
        val bgFrac = bytes[AnytimeConstants.COMPUTED_OFFSET_BG_FRAC].toInt() and 0xFF
        val err = bytes[AnytimeConstants.COMPUTED_OFFSET_ERROR].toInt() and 0xFF
        val trend = bytes[AnytimeConstants.COMPUTED_OFFSET_TREND].toInt() and 0xFF
        val warn = bytes[AnytimeConstants.COMPUTED_OFFSET_WARN].toInt() and 0xFF
        return AnytimeComputedRecord(
            glucoseId = idLo or (idHi shl 8),
            hypoEarlyWarnMinutes = hypo,
            hyperEarlyWarnMinutes = hyper,
            ibNa = ibInt + ibFrac / 100f,
            iwNa = iwInt + iwFrac / 100f,
            temperatureC = (tPlus40 - AnytimeConstants.TEMP_INT_OFFSET) + tFrac / 100f,
            gluMmol = gluInt + gluFrac / 100f,
            referenceBgMmol = bgInt + bgFrac / 10f,
            errorCode = err,
            trend = trend,
            warnCode = warn,
        )
    }

    /**
     * Parse a 0x05 check response.
     *
     * Layout (CT3/CT4): battery is bytes 9/10 as int + frac*0.01. Official
     * CT3_A/CT4 check handling only gates on battery by default; the byte 2/3
     * current field is used only by the optional low-current variant and is
     * big-endian /100. Bytes 6/7 are diagnostic on some firmware and can look
     * like a 190nA current, so do not let them block the handshake.
     */
    @JvmStatic
    fun parseCheckResponse(bytes: ByteArray, lowBatteryThresholdVolts: Float): AnytimeCheckStatus {
        if (bytes.size < 12 || bytes[0] != AnytimeConstants.RX_CHECK) {
            return AnytimeCheckStatus(0, 0f, 0f, isHealthy = false, failure = AnytimeCheckStatus.CheckFailure.MALFORMED)
        }
        val field23 = (((bytes[2].toInt() and 0xFF) shl 8) or (bytes[3].toInt() and 0xFF))
        val sensorAge = field23
        val checkCurrent = field23 / 100f
        val diagInt = bytes[6].toInt() and 0xFF
        val diagFrac = bytes[7].toInt() and 0xFF
        val diagCurrent = diagInt + diagFrac / 100f
        val iw = when {
            diagCurrent in 0.5f..80f -> diagCurrent
            checkCurrent in 0.5f..80f -> checkCurrent
            else -> 0f
        }
        val batVoltsInt = bytes[9].toInt() and 0xFF
        val batVoltsFrac = bytes[10].toInt() and 0xFF
        val volts = batVoltsInt + batVoltsFrac / 100f
        val failure = when {
            volts > 0f && volts < lowBatteryThresholdVolts -> AnytimeCheckStatus.CheckFailure.LOW_BATTERY
            else -> null
        }
        return AnytimeCheckStatus(
            sensorAgeReadings = sensorAge,
            workingElectrodeCurrentNa = iw,
            batteryVolts = volts,
            isHealthy = failure == null,
            failure = failure,
        )
    }

    /** Parse 0x11 reset response. */
    @JvmStatic
    fun parseResetResponse(bytes: ByteArray): AnytimeResetStatus? {
        if (bytes.size < 3) return null
        if (bytes[0] != AnytimeConstants.RX_RESET) return null
        val isBound = (bytes[2].toInt() and 0xFF) != 0
        return AnytimeResetStatus(isBound = isBound)
    }

    /** Extract version string from 0x20 formal-version response (best-effort). */
    @JvmStatic
    fun parseFormalVersion(bytes: ByteArray): String {
        if (bytes.size < 2 || bytes[0] != AnytimeConstants.RX_TRANSMITTER_FORMAL) return ""
        val end = bytes.indexOfFirst { it == 0.toByte() }.let { if (it <= 1) bytes.size else it }
        val raw = bytes.copyOfRange(1, end.coerceAtMost(bytes.size))
        return raw.toString(Charsets.US_ASCII).trim().filter { it.isLetterOrDigit() || it in ".-_" }
    }

    /** Battery percent from volts (rough mapping). */
    @JvmStatic
    fun batteryPercent(volts: Float, lowThresholdVolts: Float): Int {
        if (volts <= 0f) return -1
        val span = AnytimeConstants.BATTERY_FULL_VOLTS - lowThresholdVolts
        if (span <= 0f) return -1
        val pct = ((volts - lowThresholdVolts) / span) * 100f
        return pct.toInt().coerceIn(0, 100)
    }
}
