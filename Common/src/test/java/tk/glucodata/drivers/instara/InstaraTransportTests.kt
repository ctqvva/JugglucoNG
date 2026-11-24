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
    fun wizardNeverLogsTheSensorAuthenticationCode() {
        val wizard = File("src/mobile/java/tk/glucodata/ui/setup/InstaraSetupWizard.kt").let {
            if (it.isFile) it else File("../Common/src/mobile/java/tk/glucodata/ui/setup/InstaraSetupWizard.kt")
        }
        assertFalse(
            "the SN is a sensor key and must not enter the log ring",
            wizard.readText().contains("setup \$code"),
        )
    }

    @Test
    fun staleDisconnectClearsTheGattReferenceSoResumeCanConnect() {
        val body = source.substringAfter("if (!acceptConnectionAttemptCallback")
            .substringBefore("super.onConnectionStateChange")
        assertTrue(body.contains("mBluetoothGatt = null"))
    }

    @Test
    fun historyCursorIsStoredOnlyAfterBlockingWriteSucceeds() {
        val body = source.substringAfter("override fun history(").substringBefore("private fun mirror(")
        assertTrue(body.contains("storeSensorHistoryBatchBlocking"))
        val stored = body.indexOf("storeSensorHistoryBatchBlocking")
        val cursor = body.indexOf("setLastSequenceId")
        assertTrue("cursor must be saved after the blocking write", cursor > stored)
    }

    @Test
    fun stateChangeRejectsRetiredGattBeforeDelegating() {
        assertTrue(source.contains("acceptConnectionAttemptCallback(gatt, newState)"))
    }
}
