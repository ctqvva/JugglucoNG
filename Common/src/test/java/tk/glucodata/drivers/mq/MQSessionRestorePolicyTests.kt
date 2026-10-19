package tk.glucodata.drivers.mq

import org.junit.Assert.*
import org.junit.Test

class MQSessionRestorePolicyTests {
    private val now = 1789295304000L // 2026-09-13, fresh install in the reported trace
    private val day = 86_400_000L
    private val address = "W25101399"

    @Test fun rejectsAprilSnapshotFromSeptemberTrace() {
        assertFalse(MQSessionRestorePolicy.canRestore(address, "W25101399", 1777312377000L, now))
    }

    @Test fun acceptsCurrentSessionForSameTransmitter() {
        assertTrue(MQSessionRestorePolicy.canRestore(address, "w25101399", now - day, now))
    }

    @Test fun rejectsUnrelatedOrUnidentifiedTransmitter() {
        assertFalse(MQSessionRestorePolicy.canRestore(address, "W25101398", now - day, now))
        assertFalse(MQSessionRestorePolicy.canRestore(address, null, now - day, now))
        assertFalse(MQSessionRestorePolicy.canRestore(null, null, now - day, now))
    }

    @Test fun requiresValidStartWithinRatedLifetime() {
        for (start in listOf(null, 0L, -1L, now + 1, now - 16 * day)) {
            assertFalse("start=$start", MQSessionRestorePolicy.canRestore(address, address, start, now))
        }
        assertTrue(MQSessionRestorePolicy.canRestore(address, address, now - 16 * day + 1, now))
    }

    @Test fun resetKeepsHardwareSeedButCannotImportOldCursorOrCalibration() {
        val stale = MQBootstrapConfig(
            protocolType = 2, sensitivity = 78.8f, algorithmVersion = 1,
            snapshotId = "c98fd5266bc02f471e3ad0c9e2c36fe5", sensorStartAtMs = 1777312377000L,
            restoredPacketIndex = 693, restoredKValue = 261.3793f,
            restoredBValue = 0f, restoredLastProcessed = 1137f,
        )
        val safe = MQSessionRestorePolicy.withoutSession(stale)
        assertEquals(78.8f, safe.sensitivity)
        assertEquals(2, safe.protocolType)
        assertEquals(1, safe.algorithmVersion)
        assertNull(safe.snapshotId)
        assertNull(safe.sensorStartAtMs)
        assertNull(safe.restoredPacketIndex)
        assertNull(safe.restoredKValue)
        assertNull(safe.restoredBValue)
        assertNull(safe.restoredLastProcessed)
    }
}
