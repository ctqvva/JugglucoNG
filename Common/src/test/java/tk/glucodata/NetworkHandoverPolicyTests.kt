package tk.glucodata

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkHandoverPolicyTests {
    private fun repoRoot(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            if (File(dir, "Common/src/main/java/tk/glucodata/NetworkHandoverPolicy.kt").isFile) return dir
            dir = dir.parentFile
        }
        throw AssertionError("Common/src not found from ${System.getProperty("user.dir")}")
    }

    private fun source(relative: String): String = File(repoRoot(), relative).readText()

    @Test
    fun replacementNetworkKeepsExistingRecoveryPathAlive() {
        assertTrue(
            NetworkHandoverPolicy.hasUsableReplacement(
                remainingInternetNetworks = 1,
                hasIpAddress = true,
            )
        )
    }

    @Test
    fun finalNetworkLossStillResetsConnectivity() {
        assertFalse(
            NetworkHandoverPolicy.hasUsableReplacement(
                remainingInternetNetworks = 0,
                hasIpAddress = false,
            )
        )
    }

    @Test
    fun unusableTrackedNetworkDoesNotSuppressReset() {
        assertFalse(
            NetworkHandoverPolicy.hasUsableReplacement(
                remainingInternetNetworks = 1,
                hasIpAddress = false,
            )
        )
    }

    @Test
    fun replacementNetworkRebuildsOnlyLanSignaledIceGenerations() {
        val application = source("Common/src/main/java/tk/glucodata/Applic.java")
            .replace(Regex("\\s+"), " ")
        val bridge = source("Common/src/main/cpp/backupjava.cpp")
            .replace(Regex("\\s+"), " ")
        val ice = source("Common/src/main/cpp/net/ICE/ICE.cpp")
            .replace(Regex("\\s+"), " ")
        val replacementStart = application.indexOf("if (hasReplacement) {")
        val replacementEnd = application.indexOf("} else {", replacementStart)
        assertTrue(replacementStart >= 0 && replacementEnd > replacementStart)
        val replacementBranch = application.substring(replacementStart, replacementEnd)
        assertTrue(replacementBranch.contains("Natives.networkhandover();"))
        assertFalse(replacementBranch.contains("Natives.networkpresent();"))
        assertTrue(bridge.contains("fromjava(networkhandover)"))
        assertTrue(bridge.contains("wakeICEReceiversForNetworkChange(false, true)"))
        assertTrue(ice.contains("resetLanSignaledConnections && connection->remoteDescriptionWasLocal.load()"))
    }
}
