// Libre3ReceiverId.kt — the 4-byte receiver identity carried in the A0/A8 NFC body.
//
// A sensor remembers the receiver ID it was activated with and refuses a later A0/A8 with a
// different one (NFC error 0xB1). The ID is not a LibreView account; an app that activates its
// own sensors may use any stable value. Two account folds are known for mirroring an Abbott
// app's identity (RoundWhiteDiscKit, Libre3ReceiverID.swift, MIT):
//   FREESTYLE_LIBRE_3 — classic app: h = (h * 0x811c9dc5) xor codeUnit, h0 = 0, over UTF-16 units
//   LIBRE_BY_ABBOTT   — US "Libre by Abbott": sum of consecutive 4-byte big-endian UTF-8 words
// Both lowercase the account ID first.

package tk.glucodata.drivers.libre3

object Libre3ReceiverId {

    enum class Derivation { FREESTYLE_LIBRE_3, LIBRE_BY_ABBOTT }

    fun fromAccountId(accountId: String, derivation: Derivation): Long {
        val normalized = accountId.trim().lowercase()
        return when (derivation) {
            Derivation.FREESTYLE_LIBRE_3 -> classicFold(normalized)
            Derivation.LIBRE_BY_ABBOTT -> wordSumFold(normalized)
        }
    }

    private fun classicFold(text: String): Long {
        var h = 0L
        for (unit in text) {
            h = ((h * 0x811c9dc5L) xor unit.code.toLong()) and 0xFFFFFFFFL
        }
        return h
    }

    private fun wordSumFold(text: String): Long {
        var sum = 0L
        var word = 0L
        var filled = 0
        for (b in text.toByteArray(Charsets.UTF_8)) {
            word = ((word shl 8) or (b.toLong() and 0xFF)) and 0xFFFFFFFFL
            if (++filled == 4) {
                sum = (sum + word) and 0xFFFFFFFFL
                word = 0
                filled = 0
            }
        }
        if (filled > 0) sum = (sum + word) and 0xFFFFFFFFL
        return sum
    }

    /** Little-endian hex as it appears on the wire, e.g. 0x1f416d8d -> "8d6d411f". */
    fun toWireHex(value: Long): String {
        val out = ByteArray(4)
        Libre3Bytes.putU32le(out, 0, value)
        return Libre3Bytes.hex(out)
    }

    /** Parses the 8-hex-digit little-endian wire form (an optional "0x" prefix is ignored). */
    fun parseWireHex(text: String?): Long? {
        val bytes = Libre3Bytes.parseHex(text?.trim()?.removePrefix("0x")?.removePrefix("0X")) ?: return null
        if (bytes.size != 4) return null
        return Libre3Bytes.u32le(bytes, 0)
    }

    /** A random non-zero ID for an install that activates its own sensors. */
    fun random(random: java.security.SecureRandom = java.security.SecureRandom()): Long {
        while (true) {
            val v = random.nextInt().toLong() and 0xFFFFFFFFL
            if (v != 0L) return v
        }
    }
}
