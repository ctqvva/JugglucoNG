package tk.glucodata

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SensorOwnershipReleaseStateTests {
    @Test
    fun releaseBlocksEveryReconnectTriggerUntilExplicitResume() {
        val state = SensorOwnershipReleaseState { it.lowercase() }
        val triggers = listOf("reconnectall", "connectDevices", "scanResult", "checkandconnect", "driverRetry")

        state.release("Sensor-A")
        triggers.forEach { trigger ->
            assertTrue("$trigger must remain blocked", state.isReleased("SENSOR-A"))
        }

        state.resume("sensor-a")
        assertFalse(state.isReleased("Sensor-A"))
    }

    @Test
    fun canonicalRenameCannotEscapeRelease() {
        val state = SensorOwnershipReleaseState { raw ->
            if (raw.endsWith("230E260", ignoreCase = true)) "6ca04230e260" else raw.lowercase()
        }

        state.release("6CA04230E260")

        assertTrue(state.isReleased("230E260"))
        assertEquals(setOf("6ca04230e260"), state.releasedSerials())
    }
}
