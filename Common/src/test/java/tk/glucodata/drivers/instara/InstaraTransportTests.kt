package tk.glucodata.drivers.instara

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source-shape checks, in the spirit of the repository's ConnectModeLeverTests: these two
 * transport rules are otherwise only observable on a real GATT link.
 */
class InstaraTransportTests {

    private val source: String by lazy {
        val relative = "src/main/java/tk/glucodata/drivers/instara/InstaraBleManager.kt"
        val candidates = listOf(
            File("Common", relative),
            File(relative),
            File("../Common", relative),
        )
        candidates.firstOrNull { it.isFile }?.readText()
            ?: error("InstaraBleManager.kt not found in ${candidates.map { it.path }}")
    }

    @Test
    fun connectDoesNotCoalesceOnAKnownDeviceAddress() {
        val body = source.substringAfter("override fun connectDevice")
            .substringBefore("override fun onConnectionStateChange")
        assertFalse(
            "mActiveBluetoothDevice survives disconnect; coalescing on it blocks every reconnect",
            body.contains("mActiveBluetoothDevice != null"),
        )
    }

    @Test
    fun stateChangeRejectsRetiredGattBeforeDelegating() {
        assertTrue(source.contains("acceptConnectionAttemptCallback(gatt, newState)"))
    }
}
