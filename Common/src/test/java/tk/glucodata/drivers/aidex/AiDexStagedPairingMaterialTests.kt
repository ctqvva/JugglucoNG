package tk.glucodata.drivers.aidex

import org.junit.Assert.*
import org.junit.Test
import tk.glucodata.drivers.aidex.AiDexProvisioningStore.ConditionalSaveResult
import tk.glucodata.drivers.aidex.AiDexProvisioningStore.ConditionalSaveStatus
import tk.glucodata.drivers.aidex.bloodcontrol.AiDexStagedPairingMaterial

class AiDexStagedPairingMaterialTests {
    private fun candidate(now: () -> Long = { 1_000 }) = AiDexStagedPairingMaterial(
        "BCTEST0001", AiDexProvisionedKeys(ByteArray(16) { 1 }, ByteArray(16) { 2 }), "before", now,
    )

    @Test fun f001AndBondWithoutAValidLiveFrameNeverReachPersistence() {
        val staged = candidate()
        var writes = 0
        repeat(3) {
            assertNull(staged.persistIfDue { writes++; ConditionalSaveResult(ConditionalSaveStatus.SAVED, "new") })
        }
        assertFalse(staged.validated)
        assertEquals(0, writes)
        // A failed F001/BOND discards this private candidate; no durable/global state changed.
    }

    @Test fun validatedMaterialCommitsExactlyOnce() {
        val staged = candidate()
        staged.validate()
        var writes = 0
        repeat(2) {
            assertEquals(ConditionalSaveStatus.SAVED, staged.persistIfDue {
                writes++; assertEquals("before", it)
                ConditionalSaveResult(ConditionalSaveStatus.SAVED, "saved")
            })
        }
        assertEquals(1, writes)
    }

    @Test fun failedWriteRetriesSameValidatedKeysAfterBackoffWithoutAnotherFetch() {
        var clock = 1_000L
        val staged = candidate { clock }
        staged.validate()
        var writes = 0
        assertEquals(ConditionalSaveStatus.RETRYABLE_FAILURE, staged.persistIfDue {
            writes++; ConditionalSaveResult(ConditionalSaveStatus.RETRYABLE_FAILURE, "own-memory-write")
        })
        assertEquals("own-memory-write", staged.expectedRevision)
        assertTrue(staged.validated)
        assertNull(staged.persistIfDue { fail("Must respect write backoff"); error("unreachable") })
        clock += 14_999
        assertNull(staged.persistIfDue { fail(); error("unreachable") })
        clock++
        assertEquals(ConditionalSaveStatus.SAVED, staged.persistIfDue {
            writes++; assertEquals("own-memory-write", it)
            ConditionalSaveResult(ConditionalSaveStatus.SAVED, "saved")
        })
        assertEquals(2, writes)
    }

    @Test fun writeRetriesAreBoundedAndNewerImportsAreNeverRetriedOver() {
        var clock = 1_000L
        val staged = candidate { clock }
        staged.validate()
        var writes = 0
        repeat(5) {
            staged.persistIfDue { writes++; ConditionalSaveResult(ConditionalSaveStatus.RETRYABLE_FAILURE, "own") }
            clock += 24 * 60 * 60_000
        }
        assertNull(staged.persistIfDue { fail("Retry budget exhausted"); error("unreachable") })
        assertEquals(5, writes)

        val superseded = candidate()
        superseded.validate()
        assertEquals(ConditionalSaveStatus.SUPERSEDED, superseded.persistIfDue {
            ConditionalSaveResult(ConditionalSaveStatus.SUPERSEDED, "manual-import")
        })
        assertEquals(ConditionalSaveStatus.SUPERSEDED, superseded.persistIfDue {
            fail("Must not overwrite a new import"); error("unreachable")
        })
    }
}
