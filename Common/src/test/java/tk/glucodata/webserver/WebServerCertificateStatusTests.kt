package tk.glucodata.webserver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the settings screen offers about the installed certificate. The whole
 * point of this decision is that it stays a decision — the certificate is never
 * reissued behind the user's back, because that would change the fingerprint of
 * something they may already have exported and trusted elsewhere.
 */
class WebServerCertificateStatusTests {

    private val now = 1_760_000_000_000L

    private fun installed(
        selfSigned: Boolean = true,
        hostnames: List<String> = listOf("localhost", "127.0.0.1", "192.168.1.42"),
        notAfterMillis: Long = now + 200L * 86_400_000L,
    ) = WebServerCertificate.Installed(
        selfSigned = selfSigned,
        subject = "JugglucoNG",
        hostnames = hostnames,
        notAfterMillis = notAfterMillis,
    )

    private fun status(
        installed: WebServerCertificate.Installed?,
        lanAddress: String?,
    ) = WebServerCertificate.statusOf(installed, lanAddress, now)

    @Test
    fun nothingInstalledIsMissing() {
        assertEquals(WebServerCertificate.Status.MISSING, status(null, "192.168.1.42"))
    }

    @Test
    fun aCertificateCoveringTheCurrentAddressIsReady() {
        assertEquals(WebServerCertificate.Status.READY, status(installed(), "192.168.1.42"))
    }

    @Test
    fun movingToAnAddressTheCertificateDoesNotCoverAsksForAnUpdate() {
        assertEquals(
            WebServerCertificate.Status.ADDRESS_CHANGED,
            status(installed(), "10.0.0.117"),
        )
    }

    @Test
    fun loopbackOnlyNeverAsksForAnUpdate() {
        // With no LAN address in play there is no name to be wrong about, so a
        // certificate listing only localhost is exactly right.
        assertEquals(
            WebServerCertificate.Status.READY,
            status(installed(hostnames = listOf("localhost", "127.0.0.1")), null),
        )
    }

    @Test
    fun anImportedCertificateIsNeverReportedAsNeedingAnAddressUpdate() {
        // A CA-issued certificate is usually issued to a domain name and has no
        // business listing whatever IP the phone's lease handed it today.
        // Offering to replace it because the network moved would be wrong.
        assertEquals(
            WebServerCertificate.Status.READY,
            status(installed(selfSigned = false, hostnames = listOf("cgm.example.org")), "10.0.0.117"),
        )
    }

    @Test
    fun anExpiredCertificateOutranksAnAddressChange() {
        val expired = installed(notAfterMillis = now - 86_400_000L)

        // Both are wrong, but a browser refuses an expired certificate outright,
        // so that is the thing to say.
        assertEquals(WebServerCertificate.Status.EXPIRED, status(expired, "10.0.0.117"))
        assertEquals(WebServerCertificate.Status.EXPIRED, status(expired, "192.168.1.42"))
    }

    @Test
    fun anImportedCertificateStillReportsExpiry() {
        assertEquals(
            WebServerCertificate.Status.EXPIRED,
            status(installed(selfSigned = false, notAfterMillis = now - 1L), null),
        )
    }

    @Test
    fun aCertificateWithNoRecordedExpiryIsNotTreatedAsExpired() {
        // notAfter comes back as 0 when the certificate could not be parsed. An
        // unreadable certificate is still perfectly usable by the server, so it
        // must not be reported as broken.
        assertEquals(
            WebServerCertificate.Status.READY,
            status(installed(notAfterMillis = 0L, hostnames = emptyList()), null),
        )
    }

    @Test
    fun coverageIgnoresCase() {
        val certificate = installed(hostnames = listOf("localhost", "MyPhone.local"))

        assertTrue(certificate.covers("myphone.local"))
        assertTrue(certificate.covers("LOCALHOST"))
        assertFalse(certificate.covers("otherphone.local"))
    }

    @Test
    fun aSelfSignedCertificateWithNoNamesAsksForAnUpdateOnALan() {
        // Nothing to match against means nothing a browser will accept.
        assertEquals(
            WebServerCertificate.Status.ADDRESS_CHANGED,
            status(installed(hostnames = emptyList()), "192.168.1.42"),
        )
    }
}
