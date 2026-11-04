// Libre3Bytes.kt — small byte helpers shared by the Kotlin Libre 3 protocol code.
//
// Everything in this package except the Android driver classes is plain JVM Kotlin so the
// protocol can be unit tested on the host without a device.

package tk.glucodata.drivers.libre3

internal object Libre3Bytes {

    fun u8(data: ByteArray, offset: Int): Int = data[offset].toInt() and 0xFF

    fun u16le(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)

    fun s16le(data: ByteArray, offset: Int): Int = u16le(data, offset).toShort().toInt()

    fun u32le(data: ByteArray, offset: Int): Long =
        (u16le(data, offset).toLong()) or (u16le(data, offset + 2).toLong() shl 16)

    fun putU16le(out: ByteArray, offset: Int, value: Int) {
        out[offset] = value.toByte()
        out[offset + 1] = (value ushr 8).toByte()
    }

    fun putU32le(out: ByteArray, offset: Int, value: Long) {
        out[offset] = value.toByte()
        out[offset + 1] = (value ushr 8).toByte()
        out[offset + 2] = (value ushr 16).toByte()
        out[offset + 3] = (value ushr 24).toByte()
    }

    fun hex(data: ByteArray?): String {
        if (data == null) return "null"
        val sb = StringBuilder(data.size * 2)
        for (b in data) {
            val v = b.toInt() and 0xFF
            sb.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
        }
        return sb.toString()
    }

    /** Lenient hex parser: ignores whitespace, ':' and '-' separators. Null on any other junk. */
    fun parseHex(text: String?): ByteArray? {
        if (text == null) return null
        val cleaned = text.filterNot { it.isWhitespace() || it == ':' || it == '-' }
        if (cleaned.length % 2 != 0) return null
        val out = ByteArray(cleaned.length / 2)
        for (i in out.indices) {
            val hi = Character.digit(cleaned[2 * i], 16)
            val lo = Character.digit(cleaned[2 * i + 1], 16)
            if (hi < 0 || lo < 0) return null
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
        return diff == 0
    }

    private val HEX = "0123456789abcdef".toCharArray()
}
