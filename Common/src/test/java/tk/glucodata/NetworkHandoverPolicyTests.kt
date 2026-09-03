package tk.glucodata

import java.io.File
import org.junit.Assert.assertEquals
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
    fun initialDefaultNetworkAnnouncesConnectivity() {
        val policy = NetworkHandoverPolicy()

        assertEquals(
            NetworkHandoverPolicy.Action.PRESENT,
            policy.onDefaultAvailable(network = 1L),
        )
        assertEquals(1L, policy.currentIceNetwork())
    }

    @Test
    fun repeatedCallbackForSameDefaultPreservesIce() {
        val policy = NetworkHandoverPolicy()
        policy.onDefaultAvailable(network = 1L)

        assertEquals(
            NetworkHandoverPolicy.Action.PRESENT,
            policy.onDefaultAvailable(network = 1L),
        )
        assertEquals(1L, policy.currentIceNetwork())
    }

    @Test
    fun confirmedDefaultChangeStartsImmediateHandover() {
        val policy = NetworkHandoverPolicy()
        policy.onDefaultAvailable(network = 1L)

        assertEquals(
            NetworkHandoverPolicy.Action.HANDOVER,
            policy.onDefaultAvailable(network = 2L),
        )
        assertEquals(2L, policy.currentIceNetwork())
    }

    @Test
    fun lostThenAvailableCallbackOrderBecomesHandover() {
        val policy = NetworkHandoverPolicy()
        policy.onDefaultAvailable(network = 1L)

        assertEquals(
            NetworkHandoverPolicy.Action.DEFER_ABSENCE,
            policy.onInternetNetworkLost(network = 1L),
        )
        assertEquals(
            NetworkHandoverPolicy.Action.HANDOVER,
            policy.onDefaultAvailable(network = 2L),
        )
    }

    @Test
    fun defaultThenLostCallbackOrderDoesNotRestartTwice() {
        val policy = NetworkHandoverPolicy()
        policy.onDefaultAvailable(network = 1L)
        policy.onDefaultAvailable(network = 2L)

        assertEquals(
            NetworkHandoverPolicy.Action.NONE,
            policy.onInternetNetworkLost(network = 1L),
        )
        assertEquals(2L, policy.currentIceNetwork())
    }

    @Test
    fun actualNetworkAbsenceSurvivesGraceBeforeReset() {
        val policy = NetworkHandoverPolicy()
        policy.onDefaultAvailable(network = 1L)
        policy.onInternetNetworkLost(network = 1L)
        policy.onDefaultLost(network = 1L)

        assertEquals(
            NetworkHandoverPolicy.Action.ABSENT,
            policy.afterAbsenceGrace(defaultNetworkStillAvailable = false),
        )
        assertEquals(0L, policy.currentIceNetwork())
        assertEquals(
            NetworkHandoverPolicy.Action.PRESENT,
            policy.onDefaultAvailable(network = 2L),
        )
    }

    @Test
    fun delayedDefaultCallbackStillConvertsLossToHandover() {
        val policy = NetworkHandoverPolicy()
        policy.onDefaultAvailable(network = 1L)
        policy.onInternetNetworkLost(network = 1L)
        policy.onDefaultAvailable(network = 2L)

        assertEquals(
            NetworkHandoverPolicy.Action.PRESENT,
            policy.afterAbsenceGrace(defaultNetworkStillAvailable = true),
        )
        assertEquals(2L, policy.currentIceNetwork())
    }

    @Test
    fun lossOfUnrelatedNetworkDoesNothing() {
        val policy = NetworkHandoverPolicy()
        policy.onDefaultAvailable(network = 1L)

        assertEquals(
            NetworkHandoverPolicy.Action.NONE,
            policy.onInternetNetworkLost(network = 2L),
        )
        assertEquals(1L, policy.currentIceNetwork())
    }

    @Test
    fun confirmedHandoverRebuildsEveryIceGeneration() {
        val application = source("Common/src/main/java/tk/glucodata/Applic.java")
            .replace(Regex("\\s+"), " ")
        val bridge = source("Common/src/main/cpp/backupjava.cpp")
            .replace(Regex("\\s+"), " ")
        val ice = source("Common/src/main/cpp/net/ICE/ICE.cpp")
            .replace(Regex("\\s+"), " ")
        val handoverCase = application.substringAfter("case HANDOVER:").substringBefore("break;")
        assertTrue(application.contains("registerDefaultNetworkCallback"))
        assertTrue(application.contains("generation != networkAbsenceGeneration"))
        assertTrue(handoverCase.contains("Natives.networkhandover();"))
        assertFalse(handoverCase.contains("Natives.networkpresent();"))
        assertTrue(bridge.contains("fromjava(networkhandover)"))
        assertTrue(bridge.contains("wakeICEReceiversForNetworkChange(true)"))
        assertTrue(ice.contains("if (resetConnections ||"))
    }
}
