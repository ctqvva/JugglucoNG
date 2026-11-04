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
    private fun read(name: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            val file = File(dir, "Common/src/main/java/tk/glucodata/drivers/sibionics/$name")
            if (file.isFile) return file.readText().replace(Regex("\\s+"), " ")
            dir = dir.parentFile
        }
        throw AssertionError("$name not found from ${System.getProperty("user.dir")}")
    }

    private val source: String by lazy { read("SibionicsGs3BleManager.kt") }
    private val gs1Source: String by lazy { read("SibionicsBleManager.kt") }

    private fun constant(text: String, name: String): String =
        Regex("const val $name = ([0-9_L]+)").find(text)?.groupValues?.get(1)
            ?: throw AssertionError("$name not declared")

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
    fun `connection handling keeps the field-tested sibionics values`() {
        // These carry fixes SibionicsBleManager learned on real sensors; keep them in step.
        listOf("CONNECT_CALLBACK_TIMEOUT_MS", "ADVERTISEMENT_RECOVERY_TIMEOUT_MS", "HIGH_PRIORITY_MAX_MS").forEach {
            assertTrue(it, constant(source, it) == constant(gs1Source, it))
        }
    }

    @Test
    fun `high priority is capped and relaxed`() {
        assertTrue(source.contains("requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_BALANCED)"))
        assertTrue(body("private fun handleIncoming").contains("armHighPriorityCap()"))
        assertTrue(body("private fun publishLiveReading").contains("settleConnectionPriority()"))
    }

    @Test
    fun `a failed connect scans for the advertisement like the other sibionics driver`() {
        assertTrue(body("private val connectCallbackTimeoutRunnable").contains("beginAdvertisementRecovery("))
        assertTrue(source.contains("SibionicsSessionPolicy.shouldUseAdvertisementRecovery("))
        assertTrue(source.contains("SibionicsSessionPolicy.connectCallbackTimeoutDelayMs("))
        assertTrue(source.contains("!beginAdvertisementRecovery(\"connect failed status=\$status\", failedDuringConnect)"))
    }

    @Test
    fun `an incompatible device is rejected rather than retried`() {
        val reject = body("private fun rejectIncompatibleDevice")
        assertTrue(reject.contains("incompatibleAddress = address"))
        assertFalse(reject.contains("scheduleReconnect("))
        assertTrue(source.contains("if (isIncompatible(knownBleAddress()))"))
    }
}
