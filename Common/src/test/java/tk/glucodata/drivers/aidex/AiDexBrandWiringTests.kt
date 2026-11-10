package tk.glucodata.drivers.aidex

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The badge brand ("LUMI") must be learned from every matched scan, not only from the stack's
 * cached device name: a scan matched by address never stores the advertised name, and the
 * driver's own broadcast scan reads it only to match. Reads the source, as
 * AiDexWearLifeWiringTests does, because the scan paths need the Android runtime.
 */
class AiDexBrandWiringTests {

    private fun repoRoot(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            if (File(dir, "Common/src/main/java/tk/glucodata/SuperGattCallback.java").isFile) return dir
            dir = dir.parentFile
        }
        throw AssertionError("Common/src not found from ${System.getProperty("user.dir")}")
    }

    private val manager by lazy {
        File(repoRoot(), "Common/src/main/java/tk/glucodata/drivers/aidex/native/ble/AiDexBleManager.kt")
            .readText()
            .replace(Regex("(?s)/\\*.*?\\*/"), " ")
            .replace(Regex("(?m)//.*$"), " ")
            .replace(Regex("\\s+"), " ")
    }

    @Test
    fun matchedScansFeedTheBadgeBrand() {
        // Shared scanner: SensorBluetooth hands every matched result, address matches included.
        assertTrue(manager.contains(
            "override fun onScanResult(result: ScanResult) { super.onScanResult(result) " +
                "noteAdvertisedBrand(result.scanRecord?.deviceName) }",
        ))
        // The driver's own broadcast scan, after its identity check.
        assertTrue(manager.contains("if (!identityMatches) return noteAdvertisedBrand(advertisedName)"))
        // Plus the stack's cached name in the getter; the definition makes four.
        assertEquals(4, Regex("noteAdvertisedBrand\\(").findAll(manager).count())
    }
}
