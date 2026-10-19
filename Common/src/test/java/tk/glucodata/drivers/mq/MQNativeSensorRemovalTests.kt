package tk.glucodata.drivers.mq

import org.junit.Assert.*
import org.junit.Test

class MQNativeSensorRemovalTests {
    private val id = "CFD8EBDDF969"

    @Test fun removesNativeSlotByResolvedNameAndLeavesOtherSensorsAlone() {
        val removed = mutableListOf<String>()
        assertTrue(MQNativeSensorRemoval.removeAndConfirm(id, "BDDF969",
            { removed.add(it); true }, { arrayOf("ABCDEF123456", "DEXCOM7") }))
        assertEquals(listOf("BDDF969"), removed)
    }

    @Test fun aRemainingCanonicalOrNativeAliasMeansDisconnectDidNotFinish() {
        for (remaining in listOf(id, "CF:D8:EB:DD:F9:69", "FD8EBDDF969", "BDDF969")) {
            assertFalse(MQNativeSensorRemoval.removeAndConfirm(id, "BDDF969",
                { true }, { arrayOf("ABCDEF123456", remaining) }))
        }
    }

    @Test fun nativeFailureOrUnavailableInventoryCannotReportSuccessfulRemoval() {
        assertFalse(MQNativeSensorRemoval.removeAndConfirm(id, id,
            { false }, { error("No inventory lookup after failed removal") }))
        assertFalse(MQNativeSensorRemoval.removeAndConfirm(id, id, { true }, { null }))
        assertTrue(MQNativeSensorRemoval.removeAndConfirm(id, id, { true }, { emptyArray() }))
    }
}
