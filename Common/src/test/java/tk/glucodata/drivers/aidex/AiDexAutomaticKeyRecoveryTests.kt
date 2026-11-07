package tk.glucodata.drivers.aidex

import org.junit.Assert.*
import org.junit.Test
import tk.glucodata.drivers.aidex.bloodcontrol.AiDexAutomaticKeyRecovery
import tk.glucodata.drivers.aidex.bloodcontrol.BloodControlAidex

class AiDexAutomaticKeyRecoveryTests {
    private val keys = AiDexProvisionedKeys(ByteArray(16) { 1 }, ByteArray(16) { 2 })

    @Test fun abandonedCompletionCannotBlockOrClearANewerAttempt() {
        val recovery = AiDexAutomaticKeyRecovery()
        val cancelled = recovery.begin()!!
        recovery.abandonInFlight()
        val current = recovery.begin()!!
        assertTrue(current.tryCn)
        assertFalse(recovery.complete(cancelled, BloodControlAidex.AutomaticResult(keys, true),
            isCurrent = { true }, stage = { fail("Cancelled attempt must never stage keys"); false }))
        assertNull(recovery.begin()) // Late completion must not clear the newer in-flight attempt.
        assertTrue(recovery.complete(current, BloodControlAidex.AutomaticResult(keys, true), { true }, { true }))
    }

    @Test fun abandonmentPreservesCompletedCnProgressAndTransientBackoff() {
        var clock = 1_000L
        val recovery = AiDexAutomaticKeyRecovery { clock }
        recovery.complete(recovery.begin()!!, BloodControlAidex.AutomaticResult(keys, false), { true }, { true })
        recovery.begin()!!
        recovery.abandonInFlight()
        val retry = recovery.begin()!!
        assertFalse(retry.tryCn)
        recovery.complete(retry, BloodControlAidex.AutomaticResult(null, true, 901_000), { true }, { true })
        recovery.abandonInFlight()
        assertNull(recovery.begin())
        clock = 901_000
        assertFalse(recovery.begin()!!.tryCn)
    }

    @Test fun staleConnectionCompletionCannotStageOverNewerMaterial() {
        val recovery = AiDexAutomaticKeyRecovery()
        val pending = recovery.begin()!!
        var saved = AiDexProvisionedKeys(ByteArray(16) { 3 }, ByteArray(16) { 4 })
        val newer = saved
        val installed = recovery.complete(pending, BloodControlAidex.AutomaticResult(keys, true),
            isCurrent = { false }, stage = { saved = it; true })
        assertFalse(installed)
        assertSame(newer, saved)
        // Discarding a stale response must not exhaust a new exchange's provisioning.
        assertTrue(recovery.begin()!!.tryCn)
    }

    @Test fun onlyFreshCompletionCanStageAndDuplicateCompletionCannotStageAgain() {
        val recovery = AiDexAutomaticKeyRecovery()
        val attempt = recovery.begin()!!
        assertNull(recovery.begin())
        var writes = 0
        assertTrue(recovery.complete(attempt, BloodControlAidex.AutomaticResult(keys, true),
            isCurrent = { true }, stage = { writes++; true }))
        assertFalse(recovery.complete(attempt, BloodControlAidex.AutomaticResult(keys, true),
            isCurrent = { true }, stage = { writes++; true }))
        assertEquals(1, writes)
        assertNull(recovery.begin())
    }

    @Test fun cnMaterialRejectionCanProceedToBloodControlExactlyOnce() {
        val recovery = AiDexAutomaticKeyRecovery()
        val cn = recovery.begin()!!
        assertTrue(cn.tryCn)
        assertTrue(recovery.complete(cn, BloodControlAidex.AutomaticResult(keys, false), { true }, { true }))
        val bc = recovery.begin()!!
        assertFalse(bc.tryCn)
        assertTrue(recovery.complete(bc, BloodControlAidex.AutomaticResult(keys, true), { true }, { true }))
        assertNull(recovery.begin())
    }

    @Test fun transientFailureAllowsReconnectRetryOnlyAfterDeadline() {
        var clock = 1_000L
        val recovery = AiDexAutomaticKeyRecovery { clock }
        val first = recovery.begin()!!
        recovery.complete(first, BloodControlAidex.AutomaticResult(null, true, 901_000), { true }) { fail(); false }
        assertNull(recovery.begin())
        clock = 900_999
        assertNull(recovery.begin())
        clock = 901_000
        val retry = recovery.begin()!!
        assertFalse(retry.tryCn)
        assertTrue(recovery.complete(retry, BloodControlAidex.AutomaticResult(keys, true), { true }, { true }))
        assertNull(recovery.begin())
    }

    @Test fun transientRetriesAreBoundedAcrossReconnects() {
        var clock = 1_000L
        val recovery = AiDexAutomaticKeyRecovery { clock }
        repeat(5) {
            val attempt = recovery.begin()!!
            val deadline = clock + 900_000
            recovery.complete(attempt, BloodControlAidex.AutomaticResult(null, true, deadline), { true }) { fail(); false }
            clock = deadline
        }
        assertNull(recovery.begin())
    }

    @Test fun permanentFailureNeverRetriesAutomatically() {
        val recovery = AiDexAutomaticKeyRecovery()
        recovery.complete(recovery.begin()!!, BloodControlAidex.AutomaticResult(null, true), { true }) { fail(); false }
        assertNull(recovery.begin())
    }

    @Test fun changedSavedRevisionCannotBeStagedOverByOtherwiseFreshCompletion() {
        val recovery = AiDexAutomaticKeyRecovery()
        var saved = "imported-after-request"
        val expected = "before-request"
        val installed = recovery.complete(recovery.begin()!!, BloodControlAidex.AutomaticResult(keys, true),
            isCurrent = { true }, stage = {
                if (saved == expected) { saved = "downloaded"; true } else false
            })
        assertFalse(installed)
        assertEquals("imported-after-request", saved)
    }
}
