package tk.glucodata.drivers.aidex

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every GATT callback after connect drops a callback from a replaced link before it touches the
 * session. This reads the source (comments stripped), as AiDexWearLifeWiringTests does, because
 * AiDexBleManager cannot be built without the native library.
 */
class AiDexStaleGattWiringTests {

    private fun repoRoot(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            if (File(dir, "Common/src/main/java/tk/glucodata/SuperGattCallback.java").isFile) return dir
            dir = dir.parentFile
        }
        throw AssertionError("Common/src not found from ${System.getProperty("user.dir")}")
    }

    private val manager by lazy {
        File(repoRoot(), "Common/src/main/java/tk/glucodata/drivers/aidex/native/ble/AiDexBleManager.kt").readText()
            .replace(Regex("(?s)/\\*.*?\\*/"), " ")
            .replace(Regex("(?m)//.*$"), " ")
            .replace(Regex("\\s+"), " ")
    }

    @Test
    fun everyCallbackAfterConnectIgnoresAReplacedLink() {
        for (name in listOf(
            "onMtuChanged",
            "onServicesDiscovered",
            "onDescriptorWrite",
            "onCharacteristicChanged",
            "onCharacteristicWrite",
            "onCharacteristicRead",
        )) {
            val guardFirst = Regex(
                "override fun $name\\(gatt: BluetoothGatt[^)]*\\) \\{ " +
                    "(super\\.$name\\(gatt[^)]*\\) )?" +
                    "if \\(gatt !== mBluetoothGatt\\) \\{ Log\\.w\\(TAG, \"$name: stale callback, ignoring\"\\) return \\}",
            )
            assertTrue("$name must drop a stale gatt before anything else", guardFirst.containsMatchIn(manager))
        }
    }
}
