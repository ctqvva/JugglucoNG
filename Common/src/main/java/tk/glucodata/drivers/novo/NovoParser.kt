package tk.glucodata.drivers.novo

import java.math.BigDecimal
import java.math.RoundingMode
import java.nio.charset.StandardCharsets

data class NovoCalibration(
    val k: Double,
    val b: Double,
) {
    val isUsable: Boolean get() = k.isFinite() && k > 0.0 && b.isFinite()
}

data class NovoMeasurement(
    val length: Int,
    val offset: Int,
    val isHistory: Int,
    val trend: Int,
    val electricityMv: Int,
    val reserved1: Int,
    val reserved2: Int,
    val reserved3: Int,
    val status: Int,
    val cvDiv100: Int,
    val crc: Int,
)

data class NovoDeviceInfo(
    val origin: Int,
    val version: String,
    val passwordExist: Int,
    val crcSupported: Int,
    val factoryCalibrationSupported: Int,
    val workTime: Int,
    val sampleIntervalMinutes: Int,
    val crc: Int,
)

data class NovoFlashInquiry(
    val count: Int,
    val status: Int,
    val factoryCode: Int,
    val crc: Int,
)

data class NovoSessionTime(
    val startTimeU32Le: Long,
    val runTimeU32Le: Long,
    val timeZone: Int,
    val crc: Int,
)

data class NovoAck(
    val flag: Int,
    val tmpOpCode: Int,
    val result: Int,
    val crc: Int,
)

object NovoParser {

    private fun u8(frame: ByteArray, index: Int): Int = frame[index].toInt() and 0xFF

    private fun le16(frame: ByteArray, index: Int): Int = u8(frame, index) or (u8(frame, index + 1) shl 8)

    private fun le32(frame: ByteArray, index: Int): Long =
        u8(frame, index).toLong() or
            (u8(frame, index + 1).toLong() shl 8) or
            (u8(frame, index + 2).toLong() shl 16) or
            (u8(frame, index + 3).toLong() shl 24)

    private fun crcBytes(value: Int): ByteArray = byteArrayOf(
        (value and 0xFF).toByte(),
        ((value ushr 8) and 0xFF).toByte(),
    )

    fun setupPassword(lo: Byte, hi: Byte): ByteArray =
        byteArrayOf(NovoConstants.OP_SETUP_PASSWORD.toByte(), lo, hi) +
            NovoConstants.SETUP_PASSWORD_SALT.toByteArray(StandardCharsets.US_ASCII)

    fun verifyPassword(lo: Byte, hi: Byte, crc: Int): ByteArray =
        byteArrayOf(NovoConstants.OP_VERIFY_PASSWORD.toByte(), 0, lo, hi) + crcBytes(crc)

    fun startup(startEpochSeconds: Long, factoryCode: Int, timeZone: Int, crc: Int): ByteArray {
        val seconds = byteArrayOf(
            ((startEpochSeconds ushr 24) and 0xFF).toByte(),
            ((startEpochSeconds ushr 16) and 0xFF).toByte(),
            ((startEpochSeconds ushr 8) and 0xFF).toByte(),
            (startEpochSeconds and 0xFF).toByte(),
        )
        return byteArrayOf(NovoConstants.OP_STARTUP.toByte()) + seconds +
            byteArrayOf(
                (factoryCode and 0xFF).toByte(),
                ((factoryCode ushr 8) and 0xFF).toByte(),
                timeZone.toByte(),
                0,
                1,
                1,
                0,
                0,
            ) + crcBytes(crc)
    }

    fun historyData(startOffset: Int, endOffset: Int, crc: Int): ByteArray =
        byteArrayOf(NovoConstants.OP_HISTORY_DATA.toByte(), 0) +
            byteArrayOf(
                (startOffset and 0xFF).toByte(),
                ((startOffset ushr 8) and 0xFF).toByte(),
                (endOffset and 0xFF).toByte(),
                ((endOffset ushr 8) and 0xFF).toByte(),
            ) + crcBytes(crc)

    fun clearHistory(): ByteArray = byteArrayOf(NovoConstants.CMD_CLEAR_HISTORY.toByte())

    fun inquireFlashData(startPos: Int): ByteArray = byteArrayOf(
        NovoConstants.CMD_INQUIRE_FLASH_DATA.toByte(),
        2,
        (startPos and 0xFF).toByte(),
        ((startPos ushr 8) and 0xFF).toByte(),
    )

    fun getFlashData(offset: Int, count: Int): ByteArray = byteArrayOf(
        NovoConstants.CMD_GET_FLASH_DATA.toByte(),
        4,
        (offset and 0xFF).toByte(),
        ((offset ushr 8) and 0xFF).toByte(),
        (count and 0xFF).toByte(),
        ((count ushr 8) and 0xFF).toByte(),
    )

    fun localPackage(index1Based: Int): ByteArray = byteArrayOf(
        NovoConstants.CMD_LOCAL_PACKAGE.toByte(),
        ((index1Based ushr 8) and 0xFF).toByte(),
        (index1Based and 0xFF).toByte(),
    )

    fun opcode16(frame: ByteArray): Int? =
        if (frame.size >= 4) le16(frame, 2) else null

    fun parseMeasurement(frame: ByteArray): NovoMeasurement? {
        if (frame.size < 15) return null
        return NovoMeasurement(
            length = u8(frame, 0),
            offset = le16(frame, 1),
            reserved1 = le16(frame, 3),
            isHistory = u8(frame, 5),
            trend = u8(frame, 6),
            electricityMv = le16(frame, 7),
            reserved2 = u8(frame, 9),
            reserved3 = u8(frame, 10),
            status = u8(frame, 11),
            cvDiv100 = u8(frame, 12),
            crc = le16(frame, 13),
        )
    }

    fun parseDeviceInfo(frame: ByteArray): NovoDeviceInfo? {
        if (frame.size < 11) return null
        val version = "${u8(frame, 3)}.${u8(frame, 2)}.${u8(frame, 1)}"
        return NovoDeviceInfo(
            origin = u8(frame, 0),
            version = version,
            passwordExist = u8(frame, 4),
            crcSupported = u8(frame, 5),
            factoryCalibrationSupported = u8(frame, 6),
            workTime = u8(frame, 7),
            sampleIntervalMinutes = u8(frame, 8),
            crc = le16(frame, 9),
        )
    }

    fun parseFlashInquiry(frame: ByteArray): NovoFlashInquiry? {
        if (frame.size < 7) return null
        return NovoFlashInquiry(
            count = le16(frame, 0),
            status = u8(frame, 2),
            factoryCode = le16(frame, 3),
            crc = le16(frame, 5),
        )
    }

    fun parseSessionTime(frame: ByteArray): NovoSessionTime? {
        if (frame.size < 11) return null
        return NovoSessionTime(
            startTimeU32Le = le32(frame, 0),
            runTimeU32Le = le32(frame, 4),
            timeZone = u8(frame, 8),
            crc = le16(frame, 9),
        )
    }

    fun parseAck(frame: ByteArray): NovoAck? {
        if (frame.size < 5) return null
        return NovoAck(
            flag = u8(frame, 0),
            tmpOpCode = u8(frame, 1),
            result = u8(frame, 2),
            crc = le16(frame, 3),
        )
    }

    private fun round2(value: Double): Double =
        BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_EVEN).toDouble()

    fun adcToNaX100(adc: Int): Double = round2(
        (((adc * NovoConstants.ADC_SCALE / NovoConstants.ADC_DIVISOR - NovoConstants.ADC_OFFSET) *
            NovoConstants.NA_SCALE * 1000.0) / NovoConstants.NA_DIVISOR),
    )

    fun glucoseMmol(adc: Int, calibration: NovoCalibration): Double {
        if (!calibration.isUsable) return 0.0
        val mmol = round2(adcToNaX100(adc) / NovoConstants.NA_SCALE * calibration.k - calibration.b)
        return if (mmol.isFinite() && mmol > 0.0) mmol else 0.0
    }

    fun glucoseMgdl(adc: Int, calibration: NovoCalibration): Float =
        (glucoseMmol(adc, calibration) * NovoConstants.MGDL_PER_MMOL).toFloat()

    fun isLowPower(electricityMv: Int): Boolean =
        electricityMv != 0 && electricityMv <= NovoConstants.LOW_POWER_MV

    fun historyTimestampMs(index: Int, endMs: Long): Long =
        endMs - index * NovoConstants.SAMPLE_INTERVAL_MS
}
