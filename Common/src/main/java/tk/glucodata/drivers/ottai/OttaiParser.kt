package tk.glucodata.drivers.ottai

object OttaiParser {

    const val RECORD_SIZE = 8
    const val PARSER_RECORD_SIZE = 12

    data class GlucoseRecord(
        val dataNo: Int,
        val voltage: Int,
        val runtime: Int,
        val rawCurrent: Int,
        val temperature: Float,
        val monitorTime: Long,
        val dataType: Int = 1,
    )

    data class ParsedPayload(
        val records: List<GlucoseRecord>,
        val frontDataNo: Int,
        val continuationMarker: Int,
        val prefix: ByteArray,
    )

    fun parsePayload(decrypted: ByteArray, activeTimeMs: Long): ParsedPayload {
        if (decrypted.size < 8) return ParsedPayload(emptyList(), 0, 0, decrypted)

        val prefix = decrypted.copyOfRange(0, 4)
        val frontDataNo = OttaiBleAuth.le16toInt(decrypted[4], decrypted[5])
        val continuation = OttaiBleAuth.le16toInt(decrypted[6], decrypted[7])

        val records = mutableListOf<GlucoseRecord>()
        var offset = 8
        while (offset + RECORD_SIZE <= decrypted.size) {
            val record = decrypted.copyOfRange(offset, offset + RECORD_SIZE)
            val parsed = parseRecord(record, frontDataNo + records.size, activeTimeMs)
            if (parsed != null && isValidRecord(parsed)) {
                records.add(parsed)
            }
            offset += RECORD_SIZE
        }

        return ParsedPayload(records, frontDataNo, continuation, prefix)
    }

    fun parseRecord(record8: ByteArray, dataNo: Int, activeTimeMs: Long): GlucoseRecord? {
        if (record8.size < RECORD_SIZE) return null

        val parserInput = ByteArray(PARSER_RECORD_SIZE)
        parserInput[0] = 0
        parserInput[1] = 0
        parserInput[2] = (dataNo and 0xFF).toByte()
        parserInput[3] = ((dataNo shr 8) and 0xFF).toByte()
        System.arraycopy(record8, 0, parserInput, 4, RECORD_SIZE)

        return parseParserRecord(parserInput, dataNo, activeTimeMs)
    }

    private fun parseParserRecord(input: ByteArray, dataNo: Int, activeTimeMs: Long): GlucoseRecord {
        val voltage = input[4].toInt() and 0xFF
        val runtime = ((input[5].toInt() and 0xFF) shl 16) or
            ((input[7].toInt() and 0xFF) shl 8) or
            (input[6].toInt() and 0xFF)
        val rawCurrent = (input[8].toInt() and 0xFF) or ((input[9].toInt() and 0xFF) shl 8)
        val tempRaw = (input[10].toInt() and 0xFF) or ((input[11].toInt() and 0xFF) shl 8)
        val temperature = tempRaw / 100f
        val monitorTime = activeTimeMs + runtime * 1000L

        return GlucoseRecord(
            dataNo = dataNo,
            voltage = voltage,
            runtime = runtime,
            rawCurrent = rawCurrent,
            temperature = temperature,
            monitorTime = monitorTime,
        )
    }

    private fun isValidRecord(record: GlucoseRecord): Boolean {
        if (record.dataNo == 65535) return false
        if (record.dataNo >= 60 && kotlin.math.abs(record.dataNo - record.runtime / 60) > 120) return false
        return true
    }

    fun isWarmupComplete(runtime: Int, warmupSeconds: Int = OttaiConstants.DEFAULT_WARMUP_SECONDS): Boolean {
        return runtime >= warmupSeconds
    }

    fun shouldEmitAverage(dataNo: Int, runtime: Int, warmupSeconds: Int = OttaiConstants.DEFAULT_WARMUP_SECONDS): Boolean {
        return runtime >= warmupSeconds && dataNo >= 5 && dataNo % 5 == 0
    }

    fun isAllZeroPayload(data: ByteArray): Boolean {
        return data.all { it == 0.toByte() }
    }
}
