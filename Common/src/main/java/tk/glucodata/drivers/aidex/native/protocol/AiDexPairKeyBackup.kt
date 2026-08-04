package tk.glucodata.drivers.aidex.native.protocol

import tk.glucodata.drivers.aidex.native.crypto.SerialCrypto
import java.security.MessageDigest
import java.util.Locale

/** Portable, versioned representation of the stable AiDex PAIR credential. */
object AiDexPairKeyBackup {
    private const val MAGIC = "JUGGLUCONG_AIDEX_PAIR_KEY"
    private const val VERSION = "1"
    const val PAIR_KEY_BYTES = 16

    data class Record(
        val bareSerial: String,
        val pairKey: ByteArray,
    )

    fun canonicalBareSerial(serial: String): String =
        SerialCrypto.stripPrefix(serial).trim().uppercase(Locale.US)

    fun encode(serial: String, pairKey: ByteArray): String? {
        if (pairKey.size != PAIR_KEY_BYTES) return null
        val bareSerial = canonicalBareSerial(serial)
        if (bareSerial.isBlank()) return null
        val keyHex = pairKey.toHex()
        val checksum = checksum(bareSerial, keyHex)
        return buildString {
            appendLine(MAGIC)
            appendLine("version=$VERSION")
            appendLine("sensor=$bareSerial")
            appendLine("pair_key=$keyHex")
            appendLine("sha256=$checksum")
        }
    }

    fun decode(payload: String): Record? {
        val lines = payload.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
        if (lines.firstOrNull() != MAGIC) return null
        val fields = lines.drop(1).mapNotNull { line ->
            val separator = line.indexOf('=')
            if (separator <= 0) null else line.substring(0, separator) to line.substring(separator + 1)
        }.toMap()
        if (fields["version"] != VERSION) return null

        val bareSerial = canonicalBareSerial(fields["sensor"].orEmpty())
        val keyHex = fields["pair_key"]?.uppercase(Locale.US) ?: return null
        val pairKey = keyHex.hexToBytes(PAIR_KEY_BYTES) ?: return null
        val expectedChecksum = checksum(bareSerial, keyHex)
        val actualChecksum = fields["sha256"]?.lowercase(Locale.US) ?: return null
        if (!MessageDigest.isEqual(expectedChecksum.toByteArray(), actualChecksum.toByteArray())) return null
        return Record(bareSerial, pairKey)
    }

    private fun checksum(bareSerial: String, keyHex: String): String {
        val input = "$MAGIC\n$VERSION\n$bareSerial\n$keyHex".toByteArray(Charsets.UTF_8)
        return MessageDigest.getInstance("SHA-256").digest(input).toHex().lowercase(Locale.US)
    }

    internal fun ByteArray.toHex(): String =
        joinToString(separator = "") { byte -> "%02X".format(Locale.US, byte.toInt() and 0xFF) }

    internal fun decodePairKeyHex(value: String): ByteArray? =
        value.hexToBytes(PAIR_KEY_BYTES)

    internal fun String.hexToBytes(expectedBytes: Int): ByteArray? {
        val text = trim()
        if (text.length != expectedBytes * 2) return null
        return ByteArray(expectedBytes) { index ->
            val high = Character.digit(text[index * 2], 16)
            val low = Character.digit(text[index * 2 + 1], 16)
            if (high < 0 || low < 0) return null
            ((high shl 4) or low).toByte()
        }
    }
}
