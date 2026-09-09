package tk.glucodata.webserver

import java.math.BigInteger
import java.security.KeyPair
import java.security.MessageDigest
import java.security.Signature
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * Builds a self-signed X.509 v3 server certificate for the built-in web server.
 *
 * Turning on HTTPS should not require the user to know what a private key or a
 * full chain is. The native server reads `fullchain.pem` and `privkey.pem` out
 * of the app's files directory and does not care who wrote them, so the app can
 * write its own pair and the import path stays for people who have a real
 * CA-issued certificate.
 */
internal object SelfSignedCertificate {

    /**
     * Certificate lifetime. iOS rejects any TLS server certificate valid for
     * more than 825 days, including a self-signed one the user has trusted by
     * hand, so a longer life would silently cost iPhone access rather than
     * saving anyone a regeneration. The expiry is shown in the settings screen
     * with a button to reissue.
     */
    const val VALIDITY_DAYS = 825

    private const val OID_SHA256_WITH_RSA = "1.2.840.113549.1.1.11"
    private const val OID_COMMON_NAME = "2.5.4.3"
    private const val OID_SUBJECT_KEY_IDENTIFIER = "2.5.29.14"
    private const val OID_KEY_USAGE = "2.5.29.15"
    private const val OID_SUBJECT_ALT_NAME = "2.5.29.17"
    private const val OID_BASIC_CONSTRAINTS = "2.5.29.19"
    private const val OID_EXT_KEY_USAGE = "2.5.29.37"
    private const val OID_SERVER_AUTH = "1.3.6.1.5.5.7.3.1"

    private const val GENERAL_NAME_DNS = 2
    private const val GENERAL_NAME_IP = 7

    /** The names a certificate should answer to, in the order they are encoded. */
    data class SubjectAltNames(
        val dnsNames: List<String> = emptyList(),
        val ipAddresses: List<ByteArray> = emptyList(),
    ) {
        val isEmpty: Boolean get() = dnsNames.isEmpty() && ipAddresses.isEmpty()
    }

    /**
     * Returns the DER of a certificate for [keyPair], signed by its own private
     * key, valid from [notBeforeMillis] for [VALIDITY_DAYS].
     */
    fun create(
        keyPair: KeyPair,
        commonName: String,
        subjectAltNames: SubjectAltNames,
        notBeforeMillis: Long,
        serialNumber: BigInteger = BigInteger.valueOf(notBeforeMillis),
    ): ByteArray {
        // A backdated notBefore absorbs clock skew between the phone and
        // whatever is browsing it, which is otherwise a "not yet valid" error
        // on a certificate that was correct when it was written.
        val notBefore = notBeforeMillis - 24L * 60L * 60L * 1000L
        val notAfter = notBeforeMillis + VALIDITY_DAYS * 24L * 60L * 60L * 1000L

        val algorithm = Der.sequence(Der.oid(OID_SHA256_WITH_RSA), Der.nullValue())
        val name = Der.sequence(
            Der.set(Der.sequence(Der.oid(OID_COMMON_NAME), Der.utf8String(commonName)))
        )
        // publicKey.encoded is already a DER SubjectPublicKeyInfo, which is
        // exactly the field this slot wants.
        val publicKeyInfo = keyPair.public.encoded

        val tbsCertificate = Der.sequence(
            Der.explicit(0, Der.integer(2)), // v3
            Der.integer(serialNumber.abs().max(BigInteger.ONE)),
            algorithm,
            name,
            Der.sequence(time(notBefore), time(notAfter)),
            name,
            publicKeyInfo,
            Der.explicit(3, Der.sequence(*extensions(publicKeyInfo, subjectAltNames))),
        )

        val signature = Signature.getInstance("SHA256withRSA").run {
            initSign(keyPair.private)
            update(tbsCertificate)
            sign()
        }

        return Der.sequence(tbsCertificate, algorithm, Der.bitString(signature))
    }

    private fun extensions(
        publicKeyInfo: ByteArray,
        subjectAltNames: SubjectAltNames,
    ): Array<ByteArray> {
        val extensions = mutableListOf<ByteArray>()

        // cA defaults to FALSE, so a leaf certificate's BasicConstraints is an
        // empty SEQUENCE rather than an explicit FALSE.
        extensions += extension(OID_BASIC_CONSTRAINTS, critical = true, value = Der.sequence())

        // digitalSignature | keyEncipherment: bits 0 and 2 of the leading octet,
        // leaving five unused bits at the end.
        extensions += extension(
            OID_KEY_USAGE,
            critical = true,
            value = Der.bitString(byteArrayOf(0xA0.toByte()), unusedBits = 5),
        )

        extensions += extension(
            OID_EXT_KEY_USAGE,
            critical = false,
            value = Der.sequence(Der.oid(OID_SERVER_AUTH)),
        )

        extensions += extension(
            OID_SUBJECT_KEY_IDENTIFIER,
            critical = false,
            value = Der.octetString(subjectKeyIdentifier(publicKeyInfo)),
        )

        if (!subjectAltNames.isEmpty) {
            val names = subjectAltNames.dnsNames.map {
                Der.implicitPrimitive(GENERAL_NAME_DNS, it.toByteArray(Charsets.US_ASCII))
            } + subjectAltNames.ipAddresses.map {
                Der.implicitPrimitive(GENERAL_NAME_IP, it)
            }
            extensions += extension(
                OID_SUBJECT_ALT_NAME,
                critical = false,
                value = Der.sequence(*names.toTypedArray()),
            )
        }

        return extensions.toTypedArray()
    }

    private fun extension(oid: String, critical: Boolean, value: ByteArray): ByteArray {
        // The DEFAULT FALSE on `critical` means a non-critical extension omits
        // the field entirely; writing an explicit FALSE is not valid DER.
        val parts = if (critical) {
            arrayOf(Der.oid(oid), Der.boolean(true), Der.octetString(value))
        } else {
            arrayOf(Der.oid(oid), Der.octetString(value))
        }
        return Der.sequence(*parts)
    }

    /** RFC 5280's method 1: SHA-1 over the public key BIT STRING's own bits. */
    private fun subjectKeyIdentifier(publicKeyInfo: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-1").digest(publicKeyBits(publicKeyInfo))

    /**
     * Pulls the raw key bits out of a SubjectPublicKeyInfo. Walking the DER by
     * hand is the price of not depending on a parser; if the structure is not
     * what is expected the whole encoding is hashed instead, which still yields
     * a stable, unique identifier.
     */
    private fun publicKeyBits(publicKeyInfo: ByteArray): ByteArray {
        var index = readHeader(publicKeyInfo, 0, 0x30) ?: return publicKeyInfo
        // Skip the AlgorithmIdentifier that precedes the key.
        val algorithmStart = readHeader(publicKeyInfo, index, 0x30) ?: return publicKeyInfo
        index = algorithmStart + lengthAt(publicKeyInfo, index)
        val bitsStart = readHeader(publicKeyInfo, index, 0x03) ?: return publicKeyInfo
        val bitsLength = lengthAt(publicKeyInfo, index)
        if (bitsLength < 1 || bitsStart + bitsLength > publicKeyInfo.size) return publicKeyInfo
        // The first content octet is the unused-bit count, not key material.
        return publicKeyInfo.copyOfRange(bitsStart + 1, bitsStart + bitsLength)
    }

    /** Returns the offset of the content of the element at [offset], or null on a mismatch. */
    private fun readHeader(bytes: ByteArray, offset: Int, expectedTag: Int): Int? {
        if (offset >= bytes.size || (bytes[offset].toInt() and 0xFF) != expectedTag) return null
        val first = bytes.getOrNull(offset + 1)?.toInt()?.and(0xFF) ?: return null
        return if (first < 0x80) offset + 2 else offset + 2 + (first and 0x7F)
    }

    private fun lengthAt(bytes: ByteArray, offset: Int): Int {
        val first = bytes[offset + 1].toInt() and 0xFF
        if (first < 0x80) return first
        var length = 0
        for (index in 1..(first and 0x7F)) {
            length = (length shl 8) or (bytes[offset + 1 + index].toInt() and 0xFF)
        }
        return length
    }

    /** RFC 5280: UTCTime through 2049, GeneralizedTime from 2050. */
    private fun time(epochMillis: Long): ByteArray {
        val calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.US).apply {
            timeInMillis = epochMillis
        }
        val year = calendar.get(Calendar.YEAR)
        val rest = String.format(
            Locale.US,
            "%02d%02d%02d%02d%02dZ",
            calendar.get(Calendar.MONTH) + 1,
            calendar.get(Calendar.DAY_OF_MONTH),
            calendar.get(Calendar.HOUR_OF_DAY),
            calendar.get(Calendar.MINUTE),
            calendar.get(Calendar.SECOND),
        )
        return if (year < 2050) {
            Der.utcTime(String.format(Locale.US, "%02d", year % 100) + rest)
        } else {
            Der.generalizedTime(String.format(Locale.US, "%04d", year) + rest)
        }
    }
}
