package tk.glucodata.drivers.sibionics

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source checks for [SibionicsGs3BleManager] rules that need a live GATT to exercise. Same
 * approach as ManagedSensorCloneOwnershipTests: they pin a boundary, not behaviour.
 */
class SibionicsGs3ManagerSourceTest {
    private val source: String by lazy {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            val file = File(dir, "Common/src/main/java/tk/glucodata/drivers/sibionics/SibionicsGs3BleManager.kt")
            if (file.isFile) return@lazy file.readText().replace(Regex("\\s+"), " ")
            dir = dir.parentFile
        }
        throw AssertionError("SibionicsGs3BleManager.kt not found from ${System.getProperty("user.dir")}")
    }

    /** From [startMarker] to the next private declaration. */
    private fun body(startMarker: String): String {
        val start = source.indexOf(startMarker)
        assertTrue("$startMarker not found", start >= 0)
        val rest = source.substring(start + startMarker.length)
        val end = Regex("private (val|fun) ").find(rest)?.range?.first ?: rest.length
        return rest.substring(0, end)
    }

    @Test
    fun `the native window is never rebased`() {
        // rebaseDirectStreamWindow zeroes every stored poll; the cursor has moved past the
        // pages that wrote them, so they would never be written back.
        assertFalse(source.contains("Natives.rebaseDirectStreamWindow("))
    }

    @Test
    fun `a write callback timeout reconnects instead of releasing the queue`() {
        val timeout = body("private val writeCallbackTimeoutRunnable")
        assertTrue(timeout.contains("reconnectNow("))
        assertFalse(timeout.contains("pumpWrites()"))
    }

    @Test
    fun `write logs go through the redaction helper`() {
        assertTrue(source.contains("write \$label bytes=\${loggable(bytes, pending.sensitive)}"))
        assertTrue(source.contains("plain=\${loggable(send.plain, send.sensitive)}"))
        assertFalse(Regex("toHex\\((send\\.)?(plain|wire)\\)").containsMatchIn(source))
    }

    @Test
    fun `an incompatible device is rejected rather than retried`() {
        val reject = body("private fun rejectIncompatibleDevice")
        assertTrue(reject.contains("incompatibleAddress = address"))
        assertFalse(reject.contains("scheduleReconnect("))
        assertTrue(source.contains("if (isIncompatible(knownBleAddress()))"))
    }
}
