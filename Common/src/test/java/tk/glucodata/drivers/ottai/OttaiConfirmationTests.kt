package tk.glucodata.drivers.ottai

import org.junit.Assert.assertEquals
import org.junit.Test
import tk.glucodata.drivers.ottai.OttaiBleManager.ConfirmStep

/**
 * The one-way activation-start commit (offerConfirmedActiveTime). The committed start dates every
 * reading and is the only start the dataNo ceiling trusts, and nothing repairs it once written,
 * so a single frame must never be able to set it.
 */
class OttaiConfirmationTests {

    private val now = 1_700_001_600_000L
    private val minute = 60_000L
    private val start = now - 1_600L * minute

    private fun step(confirmed: Long, pending: Long, pendingDataNo: Int, startMs: Long, dataNo: Int) =
        OttaiBleManager.confirmationStep(confirmed, pending, pendingDataNo, startMs, dataNo, now)

    @Test
    fun nothingToWeighIsIgnored() {
        assertEquals(ConfirmStep.Ignore, step(0L, 0L, -1, 0L, 5))
        assertEquals(ConfirmStep.Ignore, step(0L, 0L, -1, -1L, 5))
        // A confirmed start never moves, whatever the claim.
        assertEquals(ConfirmStep.Ignore, step(start, 0L, -1, start + 10L * minute, 1_610))
        assertEquals(ConfirmStep.Ignore, step(start, start + minute, 1_600, start + minute, 1_601))
    }

    @Test
    fun aStartInTheFutureOrOlderThanTheLongestLifetimeIsImplausible() {
        assertEquals(ConfirmStep.Implausible, step(0L, 0L, -1, now + 1L, 0))
        assertEquals(ConfirmStep.Implausible, step(0L, 0L, -1, now - OttaiConstants.EXTENDED_LIFETIME_MS - 1L, 0))
        // Both edges themselves are possible starts. Implausible wins over a pending claim.
        assertEquals(ConfirmStep.Pend(now, 0), step(0L, 0L, -1, now, 0))
        val oldest = now - OttaiConstants.EXTENDED_LIFETIME_MS
        assertEquals(ConfirmStep.Pend(oldest, 43_200), step(0L, 0L, -1, oldest, 43_200))
        assertEquals(ConfirmStep.Implausible, step(0L, start, 1_600, now + 1L, 1_601))
    }

    @Test
    fun theFirstClaimPends() {
        assertEquals(ConfirmStep.Pend(start, 0), step(0L, 0L, -1, start, 0))
        // No pending start is no claim, whatever dataNo is left beside it.
        assertEquals(ConfirmStep.Pend(start, 5), step(0L, 0L, 5, start, 5))
    }

    @Test
    fun theClaimingRecordReadAgainIsNotASecondObservation() {
        assertEquals(ConfirmStep.Ignore, step(0L, start, 0, start, 0))
        assertEquals(ConfirmStep.Ignore, step(0L, start, 0, start + minute, 0))
    }

    /**
     * A claim from another record within CONFIRMED_START_AGREEMENT_MS commits, and what it commits
     * is that newer claim, not the pending one.
     */
    @Test
    fun aCorroboratingClaimCommitsTheNewerStart() {
        assertEquals(ConfirmStep.Commit(start + minute), step(0L, start, 0, start + minute, 1))
        assertEquals(ConfirmStep.Commit(start - 2L * minute), step(0L, start, 0, start - 2L * minute, 1))
        assertEquals(ConfirmStep.Commit(start), step(0L, start, 0, start, 1))
        // The commit has no bound on how far apart the two records sit.
        assertEquals(ConfirmStep.Commit(start), step(0L, start, 0, start, 1_000))
    }

    @Test
    fun aDisagreeingClaimReplacesThePendingOne() {
        assertEquals(ConfirmStep.Rearm(start + 10L * minute, 1), step(0L, start, 0, start + 10L * minute, 1))
        assertEquals(ConfirmStep.Rearm(start + 2L * minute + 1L, 1), step(0L, start, 0, start + 2L * minute + 1L, 1))
    }

    /** The steps chained the way offerConfirmedActiveTime applies them to its fields. */
    @Test
    fun aCorruptFrontCannotCommitButTwoGenuineRecordsDo() {
        var confirmed = 0L
        var pending = 0L
        var pendingDataNo = -1
        fun offer(startMs: Long, dataNo: Int) {
            when (val s = step(confirmed, pending, pendingDataNo, startMs, dataNo)) {
                ConfirmStep.Ignore, ConfirmStep.Implausible -> Unit
                is ConfirmStep.Pend -> { pending = s.startMs; pendingDataNo = s.dataNo }
                is ConfirmStep.Rearm -> { pending = s.startMs; pendingDataNo = s.dataNo }
                is ConfirmStep.Commit -> { confirmed = s.startMs; pending = 0L; pendingDataNo = -1 }
            }
        }
        val corrupt = now - 17_000L * minute
        offer(corrupt, 17_000) // a corrupt front: pends
        offer(corrupt, 17_000) // read again by the poll: still only one observation
        assertEquals(0L, confirmed)
        offer(start, 1_600) // the genuine record disagrees: re-arms onto it
        assertEquals(0L, confirmed)
        assertEquals(start to 1_600, pending to pendingDataNo)
        offer(start + minute, 1_601)
        assertEquals(start + minute, confirmed)
        offer(start + 30L * minute, 1_602)
        assertEquals(start + minute, confirmed)
    }
}
