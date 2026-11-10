package tk.glucodata

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class CloneFallbackServersTests {
    private fun source(path: String): String {
        val root = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
            .first { File(it, "Common/src").isDirectory }
        return File(root, path).readText()
    }

    @Test fun customServersAreUsedAloneByDefault() {
        assertFalse(CloneIceNetworkConfig().allowFallbackServers)
        assertTrue(CloneIceNetworkConfig().copy(allowFallbackServers = true).copy(preferIPv4 = true).allowFallbackServers)
    }

    @Test fun persistenceAndNativeCarryThePreference() {
        val config = source("Common/src/main/java/tk/glucodata/CloneIceNetworkConfig.kt")
        assertTrue(config.contains("prefs.getBoolean(KEY_ALLOW_FALLBACK_SERVERS, false)"))
        assertTrue(config.contains(".putBoolean(KEY_ALLOW_FALLBACK_SERVERS, normalized.allowFallbackServers)"))
        assertTrue(config.substringAfter("Natives.setCloneICEConfig(").contains("config.allowFallbackServers,"))
        val native = source("Common/src/main/cpp/net/ICE/ICE.cpp")
        assertTrue(native.contains("ice_config.allowFallbackServers=allowFallbackServers"))
        // Rendezvous: public servers join a custom one only when allowed.
        assertTrue(native.contains("const bool usePublic=config.rendezvousHost.empty()||config.allowFallbackServers;"))
        // TURN: bundled / Twilio relays join a custom one only when allowed,
        // and the custom relay is then pinned in every selection.
        assertTrue(native.contains("const bool useBundled=!has_configured_turn||networkConfig.allowFallbackServers;"))
        assertTrue(native.contains("if(useBundled) {"))
        assertTrue(native.contains("connection->currentAgentGeneration(),\n                                                       has_configured_turn);"))
    }

    @Test fun relayRotationWaitsForThePeer() {
        val native = source("Common/src/main/cpp/net/ICE/ICE.cpp")
        assertFalse(native.contains("turnRelayRotation.beginAttempt("))
        val apply = native.substringAfter("static bool applyRemoteDescription(").substringBefore("bool applyLocalICEDescription(")
        assertTrue(apply.contains("con->turnRelayRotation.answered(generation);"))
    }

    @Test fun qrImportRetainsTheLocalPhonesPreference() {
        val screen = source("Common/src/mobile/java/tk/glucodata/ui/MirrorSettingsScreen.kt")
        assertTrue(screen.contains("allowFallbackServers = previousIceConfig?.allowFallbackServers ?: false"))
    }
}
