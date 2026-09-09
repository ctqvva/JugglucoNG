package tk.glucodata.webserver

import java.io.ByteArrayOutputStream
import java.math.BigInteger

/**
 * The slice of DER needed to write an X.509 certificate, and nothing more.
 *
 * Android has good key generation but no public API for building an arbitrary
 * certificate, and the usual answer — Bouncy Castle — is several megabytes of
 * dependency for one screen in an app that already drops ABIs to save size. The
 * structure of a self-signed certificate is fixed and small, so it is written
 * out directly here and checked in tests by parsing the result back with the
 * platform's own `CertificateFactory`.
 */
internal object Der {

    private const val TAG_BOOLEAN = 0x01
    private const val TAG_INTEGER = 0x02
    private const val TAG_BIT_STRING = 0x03
    private const val TAG_OCTET_STRING = 0x04
    private const val TAG_NULL = 0x05
    private const val TAG_OID = 0x06
    private const val TAG_UTF8_STRING = 0x0C
    private const val TAG_IA5_STRING = 0x16
    private const val TAG_UTC_TIME = 0x17
    private const val TAG_GENERALIZED_TIME = 0x18
    private const val TAG_SEQUENCE = 0x30
    private const val TAG_SET = 0x31

    /** Wraps [content] in an element with the given identifier octet. */
    fun tagged(tag: Int, content: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(content.size + 6)
        out.write(tag)
        writeLength(out, content.size)
        out.write(content)
        return out.toByteArray()
    }

    fun sequence(vararg parts: ByteArray): ByteArray = tagged(TAG_SEQUENCE, concat(*parts))

    fun set(vararg parts: ByteArray): ByteArray = tagged(TAG_SET, concat(*parts))

    /** `[n]` in EXPLICIT form: a constructed context-specific tag around [content]. */
    fun explicit(number: Int, content: ByteArray): ByteArray = tagged(0xA0 or number, content)

    /** `[n]` in IMPLICIT form for a primitive value, as GeneralName uses. */
    fun implicitPrimitive(number: Int, content: ByteArray): ByteArray = tagged(0x80 or number, content)

    fun boolean(value: Boolean): ByteArray =
        tagged(TAG_BOOLEAN, byteArrayOf(if (value) 0xFF.toByte() else 0x00))

    fun integer(value: BigInteger): ByteArray = tagged(TAG_INTEGER, value.toByteArray())

    fun integer(value: Int): ByteArray = integer(BigInteger.valueOf(value.toLong()))

    /**
     * [unusedBits] is the count of ignored trailing bits in the final octet, and
     * it is part of the content — a BIT STRING is not an OCTET STRING with a
     * different tag.
     */
    fun bitString(bytes: ByteArray, unusedBits: Int = 0): ByteArray =
        tagged(TAG_BIT_STRING, concat(byteArrayOf(unusedBits.toByte()), bytes))

    fun octetString(bytes: ByteArray): ByteArray = tagged(TAG_OCTET_STRING, bytes)

    fun nullValue(): ByteArray = byteArrayOf(TAG_NULL.toByte(), 0x00)

    fun utf8String(value: String): ByteArray = tagged(TAG_UTF8_STRING, value.toByteArray(Charsets.UTF_8))

    fun ia5String(value: String): ByteArray = tagged(TAG_IA5_STRING, value.toByteArray(Charsets.US_ASCII))

    fun utcTime(value: String): ByteArray = tagged(TAG_UTC_TIME, value.toByteArray(Charsets.US_ASCII))

    fun generalizedTime(value: String): ByteArray =
        tagged(TAG_GENERALIZED_TIME, value.toByteArray(Charsets.US_ASCII))

    /**
     * Dotted-decimal to DER. The first two arcs share one byte as `40*a + b`;
     * every arc after that is base-128, high bit set on all but the last octet.
     */
    fun oid(dotted: String): ByteArray {
        val arcs = dotted.split('.').map { it.toLong() }
        require(arcs.size >= 2) { "OID needs at least two arcs: $dotted" }
        val out = ByteArrayOutputStream()
        out.write((arcs[0] * 40 + arcs[1]).toInt())
        for (index in 2 until arcs.size) {
            writeBase128(out, arcs[index])
        }
        return tagged(TAG_OID, out.toByteArray())
    }

    fun concat(vararg parts: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(parts.sumOf { it.size })
        parts.forEach(out::write)
        return out.toByteArray()
    }

    private fun writeLength(out: ByteArrayOutputStream, length: Int) {
        if (length < 0x80) {
            out.write(length)
            return
        }
        // Long form: one byte saying how many length bytes follow, then the
        // length itself, big-endian and with no leading zero.
        var shift = 24
        while (shift > 0 && (length ushr shift) and 0xFF == 0) shift -= 8
        val byteCount = shift / 8 + 1
        out.write(0x80 or byteCount)
        while (shift >= 0) {
            out.write((length ushr shift) and 0xFF)
            shift -= 8
        }
    }

    private fun writeBase128(out: ByteArrayOutputStream, value: Long) {
        if (value < 0x80) {
            out.write(value.toInt())
            return
        }
        val digits = ArrayDeque<Int>()
        var remaining = value
        while (remaining > 0) {
            digits.addFirst((remaining and 0x7F).toInt())
            remaining = remaining ushr 7
        }
        digits.forEachIndexed { index, digit ->
            out.write(if (index == digits.size - 1) digit else digit or 0x80)
        }
    }
}
