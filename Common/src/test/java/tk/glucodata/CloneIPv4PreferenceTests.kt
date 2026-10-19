package tk.glucodata

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class CloneIPv4PreferenceTests {
    private fun source(path: String): String {
        val root = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
            .first { File(it, "Common/src").isDirectory }
        return File(root, path).readText()
    }

    @Test fun preferenceIsOptInAndCopiesPreserveIt() {
        val defaults = CloneIceNetworkConfig()
        assertFalse(defaults.preferIPv4)
        assertTrue(defaults.useLocalDiscovery)
        assertTrue(defaults.copy(preferIPv4 = true).copy(rendezvousHost = "example.test").preferIPv4)
    }

    @Test fun persistenceAndNativeStartupCarryThePreference() {
        val config = source("Common/src/main/java/tk/glucodata/CloneIceNetworkConfig.kt")
        assertTrue(config.contains("prefs.getBoolean(KEY_PREFER_IPV4, false)"))
        assertTrue(config.contains(".putBoolean(KEY_PREFER_IPV4, normalized.preferIPv4)"))
        assertTrue(config.substringAfter("Natives.setCloneICEConfig(").contains("config.preferIPv4,"))
        val native = source("Common/src/main/cpp/net/ICE/ICE.cpp")
        assertTrue(native.contains(".prefer_ipv4=networkConfig.preferIPv4"))
        assertTrue(native.contains("ice_config.preferIPv4=preferIPv4"))
    }

    @Test fun qrImportRetainsTheLocalPhonesPreference() {
        val screen = source("Common/src/mobile/java/tk/glucodata/ui/MirrorSettingsScreen.kt")
        assertTrue(screen.contains("preferIPv4 = previousIceConfig?.preferIPv4 ?: false"))
    }
}
