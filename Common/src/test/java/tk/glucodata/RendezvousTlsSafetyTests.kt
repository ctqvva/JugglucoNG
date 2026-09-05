package tk.glucodata

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class RendezvousTlsSafetyTests {
    private fun projectRoot(): File {
        var dir = File(System.getProperty("user.dir") ?: error("user.dir is unavailable")).absoluteFile
        repeat(8) {
            if (File(dir, "Common/src/main/cpp/net/ICE/ContextHTTPS.cpp").isFile) return dir
            dir = dir.parentFile ?: return@repeat
        }
        error("Project root not found")
    }

    private fun source(path: String): String = File(projectRoot(), path).readText()

    @Test
    fun rendezvousHttpsFailsClosedOnCertificateErrors() {
        val https = source("Common/src/main/cpp/net/ICE/ContextHTTPS.cpp")
        val verification = https.substring(https.indexOf("long verify_result"))

        assertTrue(verification.contains("if (verify_result != X509_V_OK)"))
        assertTrue(verification.contains("X509 *peerCertificate=SSL_get_peer_certificate(ssl)"))
        assertTrue(verification.contains("X509_check_host(peerCertificate"))
        assertTrue(verification.contains("X509_check_ip_asc(peerCertificate"))
        assertTrue(verification.indexOf("return {uit,-1};") < verification.indexOf("SSL_write"))
    }

    @Test
    fun certificateVerificationDefaultsOnAndCanBeExplicitlyDisabled() {
        val https = source("Common/src/main/cpp/net/ICE/ContextHTTPS.cpp")
        val options = source("Common/src/main/cpp/net/ICE/ContextHTTPS.hpp")
        val config = source("Common/src/main/java/tk/glucodata/CloneIceNetworkConfig.kt")
        val screen = source("Common/src/mobile/java/tk/glucodata/ui/TurnServerSettingsScreen.kt")

        assertTrue(options.contains("bool verifyCertificate = true"))
        assertTrue(config.contains("val verifyRendezvousCertificate: Boolean = true"))
        assertTrue(config.contains("KEY_VERIFY_RENDEZVOUS_CERTIFICATE,\n                true"))
        assertTrue(https.contains("if(!options.verifyCertificate)"))
        assertTrue(https.contains("SSL_set_verify(ssl,SSL_VERIFY_NONE,nullptr)"))
        assertTrue(https.contains("if(options.verifyCertificate)"))
        assertTrue(https.contains("!SSL_get_peer_certificateptr"))
        assertTrue(https.contains("!X509_check_hostptr"))
        assertTrue(https.contains("!X509_check_ip_ascptr"))
        assertTrue(screen.contains("mutableStateOf(if (isAbsent) true else initialIceConfig.useTurnForStun)"))
    }
}
