package tk.glucodata.drivers.sibionics

/** Version-1 probe calibration from the official connect_decode/sens_decode routines. */
internal object SibionicsProbeSensitivity {
    private const val ALPHABET = "123456789ACDEFGHJKLMNPQRSTUVWXYZ"
    private val IDENTITY_BITS = intArrayOf(1, 2, 3, 4, 18, 6, 7, 8, 9, 19, 11, 12, 13, 14, 16, 17, 0, 5, 10, 15)
    private val SENSITIVITY_BITS = intArrayOf(7, 8, 9, 0, 1, 2, 4, 5, 6, 3)

    fun tryDecode(code: String?): Float? {
        if (code == null || code.length != 14) return null
        val values = code.map(ALPHABET::indexOf)
        if (values.any { it < 0 }) return null
        if ((values[7] + values[11]) % 32 != 1) return null

        val batch = (0..3).map { (values[it] + values[it + 8]) % 32 }
        val sensitivity = (0..2).map { (values[it] + values[it + 4] + values[it + 8]) % 32 }
        val checksum = (batch.sum() + values.subList(8, 12).sum() + sensitivity.sum()) % 1024
        if (checksum != values[12] * 32 + values[13]) return null

        // connect_decode ignores its batch/serial routines' error returns. Require
        // both inner checksums as well, rather than accepting a partial decode.
        val batchBits = permute(batch, IDENTITY_BITS)
        val year = batchBits shr 15
        val month = (batchBits shr 11) and 15
        val lot = (batchBits shr 4) and 127
        if (month !in 1..12 || (year + month + lot) % 16 != (batchBits and 15)) return null
        val serialBits = permute(values.subList(8, 12), IDENTITY_BITS)
        val serial = serialBits shr 4
        val serialChecksum = serial / 2400 + (serial % 2400) / 100 + (serial % 100) / 10 + serial % 10
        if (serialChecksum % 16 != (serialBits and 15)) return null

        val packed = sensitivity[0] * 32 + sensitivity[1]
        val hundredths = permute(sensitivity.take(2), SENSITIVITY_BITS)
        if (hundredths >= 1000) return null
        val digitSum = packed / 100 + (packed / 10) % 10 + packed % 10
        if (sensitivity[2] != (hundredths % 4) * 8 + digitSum % 8) return null
        return (hundredths / 100f).takeIf(SibionicsSensitivity::isSupported)
    }
    private fun permute(values: List<Int>, order: IntArray): Int {
        val packed = values.fold(0) { result, value -> (result shl 5) or value }
        return order.fold(0) { result, bit ->
            (result shl 1) or ((packed shr (order.size - 1 - bit)) and 1)
        }
    }
}
