package tk.glucodata.alerts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tk.glucodata.alerts.CompressionHoldLog.Entry
import tk.glucodata.alerts.CompressionHoldLog.Outcome

class CompressionHoldLogTests {

    private val t0 = 1_700_000_000_000L
    private val minute = 60_000L
    private val day = 24 * 60 * minute

    private fun resolved(at: Long) = Entry(at, at + 6 * minute, Outcome.RESOLVED, "resolved")
    private fun escalated(at: Long) = Entry(at, at + 4 * minute, Outcome.ESCALATED, "no-upturn")

    @Test
    fun countsSplitByOutcome() {
        val log = CompressionHoldLog.EMPTY
            .record(resolved(t0))
            .record(escalated(t0 + day))
            .record(resolved(t0 + 2 * day))
        assertEquals(2, log.resolvedCount())
        assertEquals(1, log.escalatedCount())
    }

    @Test
    fun selfDisableTriggersAtTheLimitAndZeroDisablesTheCheck() {
        val twoEscalations = CompressionHoldLog.EMPTY
            .record(escalated(t0))
            .record(escalated(t0 + day))
        assertTrue(twoEscalations.selfDisableDue(limit = 2))
        assertFalse(twoEscalations.selfDisableDue(limit = 3))
        assertFalse(twoEscalations.selfDisableDue(limit = 0))
    }

    @Test
    fun pruningDropsEntriesPastRetention() {
        val log = CompressionHoldLog.EMPTY
            .record(resolved(t0))
            .record(resolved(t0 + 20 * day))
        val pruned = log.pruned(nowMs = t0 + 21 * day)
        assertEquals(1, pruned.entries.size)
        assertEquals(t0 + 20 * day, pruned.entries[0].startMs)
    }

    @Test
    fun encodeDecodeRoundTrips() {
        val log = CompressionHoldLog.EMPTY
            .record(resolved(t0))
            .record(escalated(t0 + day))
        assertEquals(log, CompressionHoldLog.decode(log.encode()))
    }

    @Test
    fun garbageDecodesToWhatSurvives() {
        assertEquals(CompressionHoldLog.EMPTY, CompressionHoldLog.decode(null))
        assertEquals(CompressionHoldLog.EMPTY, CompressionHoldLog.decode(""))
        assertEquals(CompressionHoldLog.EMPTY, CompressionHoldLog.decode("not,a;log"))
        val half = "$t0,${t0 + minute},RESOLVED,resolved;banana"
        assertEquals(1, CompressionHoldLog.decode(half).entries.size)
    }

    @Test
    fun theLogIsCapped() {
        var log = CompressionHoldLog.EMPTY
        repeat(CompressionHoldLog.MAX_TRACKED + 25) { i ->
            log = log.record(resolved(t0 + i * minute))
        }
        assertEquals(CompressionHoldLog.MAX_TRACKED, log.entries.size)
        assertEquals(t0 + 25L * minute, log.entries.first().startMs)
    }
}
