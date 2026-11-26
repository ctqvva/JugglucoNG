package tk.glucodata.drivers.bioe

data class BioeRealtime(
    val adc: Int,
    val referenceRaw: Int,
    val batteryRaw: Int,
    val ntcRaw: Int,
    val timestampMs: Long,
)

data class BioeLocalDataInquiry(
    val hasPackage: Boolean,
    val dataCount: Int,
    val packageCount: Int,
)

data class BioeLocalDataBatch(
    val packageIndex: Int,
    val count: Int,
    val adc: List<Int>,
) {
    fun sampleIndex(position: Int): Int = (packageIndex - 1) * BioeConstants.HISTORY_PAGE_SIZE + position
}

data class BioeFlashInquiry(
    val latestIndex: Int,
    val count: Int,
)

data class BioeFlashBatch(
    val index: Int,
    val count: Int,
    val adc: List<Int>,
)

data class BioeVersion(
    val hardFirmwareVersion: Int,
    val manufacturerCode: Int,
)

data class BioeSysPara(
    val samplePeriod: Int,
    val dataType: Int,
    val workMode: Int,
    val afeOutEnabled: Int?,
    val dataFilterEnabled: Int?,
)

data class BioeCalibration(
    val k: Double,
    val b: Double,
    val lowCurrentThresholdNa: Double = BioeConstants.DEFAULT_LOW_CURRENT_NA,
) {
    val isUsable: Boolean get() = k.isFinite() && k > 0.0 && b.isFinite()
}

object BioeParser {

    fun frame(cmd: Int, payload: ByteArray = byteArrayOf()): ByteArray =
        byteArrayOf(
            BioeConstants.PREAMBLE_0.toByte(),
            BioeConstants.PREAMBLE_1.toByte(),
            cmd.toByte(),
        ) + payload

    fun getRealtime(): ByteArray = frame(BioeConstants.CMD_GET_DATA, byteArrayOf(0, 0))

    fun inquireLocalData(): ByteArray = frame(BioeConstants.CMD_INQ_LOCAL_DATA)

    fun getLocalData(packageIndex: Int): ByteArray {
        if (packageIndex <= 0) return frame(BioeConstants.CMD_GET_LOCAL_DATA)
        return frame(
            BioeConstants.CMD_GET_LOCAL_DATA,
            byteArrayOf((packageIndex ushr 8).toByte(), packageIndex.toByte()),
        )
    }

    fun clearHistory(): ByteArray = frame(BioeConstants.CMD_CLEAR_HIS)

    fun getVersion(): ByteArray = frame(BioeConstants.CMD_GET_VER)

    fun getSysPara(): ByteArray = frame(BioeConstants.CMD_GET_SYSPARA)

    fun inquireFlashData(startIndex: Int): ByteArray {
        if (startIndex < 0) return frame(BioeConstants.CMD_INQ_FLASH_DATA)
        return frame(
            BioeConstants.CMD_INQ_FLASH_DATA,
            byteArrayOf(2, startIndex.toByte(), (startIndex ushr 8).toByte()),
        )
    }

    fun getFlashData(offset: Int, count: Int): ByteArray {
        if (offset < 0 || count <= 0) return frame(BioeConstants.CMD_GET_FLASH_DATA)
        return frame(
            BioeConstants.CMD_GET_FLASH_DATA,
            byteArrayOf(
                4,
                offset.toByte(),
                (offset ushr 8).toByte(),
                count.toByte(),
                (count ushr 8).toByte(),
            ),
        )
    }

    fun clearFlash(): ByteArray = frame(BioeConstants.CMD_CLEAR_FLASH)

    fun getCgmPara(): ByteArray = frame(BioeConstants.CMD_GET_CGMPARA)

    fun heartbeat(): ByteArray = frame(BioeConstants.CMD_HEARTBEAT)

    fun command(frame: ByteArray): Int? {
        if (frame.size < 3) return null
        if (u(frame[0]) != BioeConstants.PREAMBLE_0 || u(frame[1]) != BioeConstants.PREAMBLE_1) return null
        return u(frame[2])
    }

    fun isHeartbeat(frame: ByteArray): Boolean =
        frame.isNotEmpty() && u(frame[0]) == BioeConstants.CMD_HEARTBEAT

    private fun u(byte: Byte): Int = byte.toInt() and 0xFF

    private fun be16(frame: ByteArray, offset: Int): Int = (u(frame[offset]) shl 8) or u(frame[offset + 1])

    private fun le16(frame: ByteArray, offset: Int): Int = u(frame[offset]) or (u(frame[offset + 1]) shl 8)

    private fun beWords(frame: ByteArray, offset: Int, count: Int): List<Int> {
        val words = ArrayList<Int>(count)
        var cursor = offset
        repeat(count) {
            if (cursor + 1 >= frame.size) return words
            words += be16(frame, cursor)
            cursor += 2
        }
        return words
    }

    fun parseRealtime(frame: ByteArray, nowMs: Long): BioeRealtime? {
        if (command(frame) != BioeConstants.CMD_GET_DATA || frame.size < 12) return null
        return BioeRealtime(
            adc = be16(frame, 4),
            referenceRaw = be16(frame, 6),
            batteryRaw = be16(frame, 8),
            ntcRaw = be16(frame, 10),
            timestampMs = nowMs,
        )
    }

    fun parseLocalDataInquiry(frame: ByteArray): BioeLocalDataInquiry? {
        if (command(frame) != BioeConstants.CMD_INQ_LOCAL_DATA || frame.size < 9) return null
        return BioeLocalDataInquiry(
            hasPackage = u(frame[4]) == 1,
            dataCount = be16(frame, 5),
            packageCount = be16(frame, 7),
        )
    }

    fun parseLocalDataBatch(frame: ByteArray): BioeLocalDataBatch? {
        if (command(frame) != BioeConstants.CMD_GET_LOCAL_DATA || frame.size < 7) return null
        val packageIndex = be16(frame, 3)
        val count = be16(frame, 5)
        if (count <= 0 || frame.size < 7 + count * 2) return null
        return BioeLocalDataBatch(packageIndex, count, beWords(frame, 7, count))
    }

    fun parseFlashInquiry(frame: ByteArray): BioeFlashInquiry? {
        if (command(frame) != BioeConstants.CMD_INQ_FLASH_DATA || frame.size < 8) return null
        return BioeFlashInquiry(latestIndex = be16(frame, 4), count = be16(frame, 6))
    }

    fun parseFlashBatch(frame: ByteArray): BioeFlashBatch? {
        if (command(frame) != BioeConstants.CMD_GET_FLASH_DATA || frame.size < 8) return null
        val index = le16(frame, 4)
        val count = le16(frame, 6)
        if (count <= 0 || frame.size < 8 + count * 2) return null
        return BioeFlashBatch(index, count, beWords(frame, 8, count))
    }

    fun parseVersion(frame: ByteArray): BioeVersion? {
        if (command(frame) != BioeConstants.CMD_GET_VER || frame.size < 10) return null
        return BioeVersion(
            hardFirmwareVersion = (u(frame[6]) shl 16) or (u(frame[7]) shl 8) or u(frame[8]),
            manufacturerCode = u(frame[9]),
        )
    }

    fun parseSysPara(frame: ByteArray): BioeSysPara? {
        if (command(frame) != BioeConstants.CMD_GET_SYSPARA || frame.size < 8) return null
        return BioeSysPara(
            samplePeriod = u(frame[3]),
            dataType = u(frame[4]),
            workMode = u(frame[5]),
            afeOutEnabled = u(frame[6]),
            dataFilterEnabled = u(frame[7]),
        )
    }

    fun clampFlashAdc(word: Int): Int = word.coerceIn(0, BioeConstants.ADC_MAX)

    fun sanitizeHistoryAdc(word: Int, previous: Int?): Int = when (word) {
        in 0..BioeConstants.ADC_MAX -> word
        else -> previous ?: BioeConstants.ADC_FALLBACK
    }

    fun adcToNa(adc: Int): Double =
        (((adc * 1.8 / 4096.0 - 0.34) * 1000.0) / 30.0) * 100.0

    fun glucoseMmol(adc: Int, calibration: BioeCalibration): Double {
        val na = adcToNa(clampFlashAdc(adc))
        if (!calibration.isUsable || na < calibration.lowCurrentThresholdNa) return 0.0
        val mmol = (na / 100.0) * calibration.k - calibration.b
        return if (mmol.isFinite() && mmol > 0.0) mmol else 0.0
    }

    fun glucoseMgdl(adc: Int, calibration: BioeCalibration): Float =
        (glucoseMmol(adc, calibration) * BioeConstants.MGDL_PER_MMOL).toFloat()

    fun displayValue(mmol: Double, unit: Int): Float =
        if (unit == 1) mmol.toFloat() else (mmol * BioeConstants.MGDL_PER_MMOL).toFloat()

    fun sampleTimeMs(index: Int, totalCount: Int, nowMs: Long, useAtMs: Long): Long = when {
        useAtMs > 0L -> useAtMs + index * BioeConstants.SAMPLE_INTERVAL_MS
        totalCount > 0 -> nowMs - (totalCount - 1 - index) * BioeConstants.SAMPLE_INTERVAL_MS
        else -> nowMs
    }

    fun flashTimeMs(index: Int, latestIndex: Int, nowMs: Long, useAtMs: Long): Long = when {
        useAtMs > 0L -> useAtMs + index * BioeConstants.SAMPLE_INTERVAL_MS
        else -> nowMs - (latestIndex - index).coerceAtLeast(0) * BioeConstants.SAMPLE_INTERVAL_MS
    }
}
