package tk.glucodata.webserver

import java.io.ByteArrayInputStream
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.cert.CertificateExpiredException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Date
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

/**
 * The certificate is hand-encoded, so every test here parses the bytes back with
 * the platform's own X.509 parser rather than checking them against a fixture.
 * If the DER is malformed in any way that matters, `generateCertificate` throws.
 */
class SelfSignedCertificateTests {

    companion object {
        private lateinit var keyPair: KeyPair

        @BeforeClass
        @JvmStatic
        fun generateKeyPairOnce() {
            // One 2048-bit key pair for the whole class: generating one per test
            // is most of the runtime.
            keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        }
    }

    private val now = 1_760_000_000_000L

    private val sans = SelfSignedCertificate.SubjectAltNames(
        dnsNames = listOf("localhost"),
        ipAddresses = listOf(
            byteArrayOf(127, 0, 0, 1),
            byteArrayOf(192.toByte(), 168.toByte(), 1, 42),
        ),
    )

    private fun parse(der: ByteArray): X509Certificate =
        CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(der)) as X509Certificate

    private fun certificate(
        subjectAltNames: SelfSignedCertificate.SubjectAltNames = sans,
        commonName: String = "JugglucoNG",
    ): X509Certificate = parse(
        SelfSignedCertificate.create(
            keyPair = keyPair,
            commonName = commonName,
            subjectAltNames = subjectAltNames,
            notBeforeMillis = now,
        )
    )

    @Test
    fun theEncodingParsesAsAnX509V3Certificate() {
        val certificate = certificate()

        assertEquals(3, certificate.version)
        assertEquals("CN=JugglucoNG", certificate.subjectX500Principal.name)
        assertEquals("CN=JugglucoNG", certificate.issuerX500Principal.name)
    }

    @Test
    fun itVerifiesUnderItsOwnPublicKey() {
        // A self-signed certificate whose signature does not check out is the
        // failure mode a hand-rolled encoder is most likely to produce: it means
        // the bytes signed are not the bytes written.
        certificate().verify(keyPair.public)
    }

    @Test
    fun subjectAlternativeNamesCarryTheHostAndEveryAddress() {
        val entries = certificate().subjectAlternativeNames!!
            .map { (it[0] as Int) to (it[1] as String) }

        assertTrue(entries.contains(2 to "localhost"))
        assertTrue(entries.contains(7 to "127.0.0.1"))
        assertTrue(entries.contains(7 to "192.168.1.42"))
    }

    @Test
    fun anIpv6AddressSurvivesTheRoundTrip() {
        val loopbackV6 = ByteArray(16).also { it[15] = 1 }
        val certificate = certificate(
            SelfSignedCertificate.SubjectAltNames(ipAddresses = listOf(loopbackV6))
        )

        val addresses = certificate.subjectAlternativeNames!!
            .filter { it[0] == 7 }
            .map { it[1] as String }
        assertEquals(listOf("0:0:0:0:0:0:0:1"), addresses)
    }

    @Test
    fun aCertificateWithNoAlternativeNamesOmitsTheExtension() {
        val certificate = certificate(SelfSignedCertificate.SubjectAltNames())

        // An empty SAN SEQUENCE is invalid DER, so the extension has to be left
        // out rather than written empty.
        assertEquals(null, certificate.subjectAlternativeNames)
    }

    @Test
    fun itIsValidNowAndForTheStatedLifetime() {
        val certificate = certificate()

        certificate.checkValidity(Date(now))
        certificate.checkValidity(Date(now + (SelfSignedCertificate.VALIDITY_DAYS - 1) * 86_400_000L))
    }

    @Test
    fun itToleratesAClientClockThatRunsBehind() {
        // Backdating notBefore is what keeps a freshly written certificate from
        // reading as "not yet valid" on a browser with a slightly slow clock.
        certificate().checkValidity(Date(now - 60L * 60L * 1000L))
    }

    @Test(expected = CertificateExpiredException::class)
    fun itStopsBeingValidAfterTheLifetime() {
        certificate().checkValidity(
            Date(now + (SelfSignedCertificate.VALIDITY_DAYS + 2) * 86_400_000L)
        )
    }

    @Test
    fun theLifetimeStaysUnderTheLimitIosEnforcesOnServerCertificates() {
        assertTrue(SelfSignedCertificate.VALIDITY_DAYS <= 825)
    }

    @Test
    fun itIsUsableAsAServerCertificateAndNotAsACa() {
        val certificate = certificate()

        assertEquals(-1, certificate.basicConstraints)
        assertTrue(certificate.extendedKeyUsage.contains("1.3.6.1.5.5.7.3.1"))
        val keyUsage = certificate.keyUsage!!
        assertTrue("digitalSignature", keyUsage[0])
        assertTrue("keyEncipherment", keyUsage[2])
        assertFalse("keyCertSign", keyUsage[5])
    }

    @Test
    fun criticalExtensionsAreTheOnesThatShouldBeCritical() {
        val certificate = certificate()

        assertEquals(
            setOf("2.5.29.19", "2.5.29.15"),
            certificate.criticalExtensionOIDs,
        )
    }

    @Test
    fun theSubjectKeyIdentifierNamesTheKeyNotTheCertificate() {
        val first = certificate().getExtensionValue("2.5.29.14")
        val second = certificate(commonName = "Something else").getExtensionValue("2.5.29.14")

        assertNotNull(first)
        // extnValue OCTET STRING wrapping the extension's own OCTET STRING
        // wrapping 20 bytes of SHA-1.
        assertEquals(24, first!!.size)
        // Two different certificates over one key share an identifier; that is
        // the whole point of the extension.
        assertArrayEquals(first, second)
    }

    @Test
    fun theSerialNumberIsPositive() {
        // A negative or zero serial is a hard parse failure for some clients.
        val certificate = parse(
            SelfSignedCertificate.create(
                keyPair = keyPair,
                commonName = "JugglucoNG",
                subjectAltNames = sans,
                notBeforeMillis = now,
                serialNumber = BigInteger.valueOf(-5L),
            )
        )

        assertTrue(certificate.serialNumber.signum() > 0)
    }

    @Test
    fun aLongCommonNameStillEncodesItsLengthCorrectly() {
        // Anything over 127 bytes moves DER into long-form lengths, which is a
        // separate branch of the encoder.
        val longName = "j".repeat(200)

        assertEquals("CN=$longName", certificate(commonName = longName).subjectX500Principal.name)
    }
}
