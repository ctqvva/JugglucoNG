package tk.glucodata

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The registry is written from a sensor's full name while the GATT callbacks on
 * the receiving device carry the native short form, so the two have to resolve
 * to the same entry. When they did not, a mirrored sensor kept losing its Clone
 * flag between syncs and rendered as whatever the native kind defaulted to.
 *
 * Source checks: the registry reads Android preferences through Applic.app and
 * cannot be constructed in a local JVM test.
 */
class CloneSensorAliasTests {
    private fun repoRoot(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            if (File(dir, "Common/src/main/java/tk/glucodata/CloneSensorRegistry.kt").isFile) return dir
            dir = dir.parentFile
        }
        throw AssertionError("Common/src not found")
    }

    private val registry: String by lazy {
        File(repoRoot(), "Common/src/main/java/tk/glucodata/CloneSensorRegistry.kt")
            .readText().replace(Regex("\\s+"), " ")
    }

    @Test
    fun candidateKeysCoverTheNativeShortName() {
        assertTrue(registry.contains("""!raw.startsWith("X-") && raw.length > 5"""))
        assertTrue(registry.contains("CloneSensorKeyCodec.normalize(raw.substring(5))"))
    }

    @Test
    fun markingStoresEveryAliasSoEitherNameResolves() {
        assertTrue(registry.contains("aliases.associateWith { effectiveTransport }"))
    }

    @Test
    fun clearingLocalOwnershipRemovesEveryAliasOfAStoredKey() {
        assertTrue(
            registry.contains("stored !in localKeys && candidateKeys(stored).none { it in localKeys }")
        )
    }

    /**
     * The gate has to release a mirror that stopped delivering, or unplugging the
     * sender would leave the sensor unreachable from either device.
     */
    @Test
    fun theConnectGateReleasesAQuietMirror() {
        val bluetooth = File(repoRoot(), "Common/src/main/java/tk/glucodata/SensorBluetooth.java")
            .readText().replace(Regex("\\s+"), " ")
        assertTrue(bluetooth.contains("CloneSensorRegistry.isMirrorDelivering(cb.SerialNumber)"))
        assertTrue(registry.contains("now - seen < MIRROR_LIVE_WINDOW_MS"))
        assertTrue(registry.contains("now - processStartedAt < MIRROR_LIVE_WINDOW_MS"))
    }
}
