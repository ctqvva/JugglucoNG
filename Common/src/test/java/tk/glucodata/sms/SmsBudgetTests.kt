package tk.glucodata.sms

import org.junit.Assert.assertEquals
import org.junit.Test

class SmsBudgetTests {

    private val now = 1_700_000_000_000L

    @Test
    fun sendsOlderThanADayStopCounting() {
        val budget = SmsBudget(
            listOf(
                now - 2 * SmsBudget.DAY_MS,
                now - 2 * SmsBudget.HOUR_MS,
                now - 10 * 60_000L
            )
        )

        assertEquals(1, budget.countInLastHour(now))
        assertEquals(2, budget.countInLastDay(now))
        assertEquals(2, budget.pruned(now).sendsAtMs.size)
    }

    @Test
    fun recordingAddsOneEntryPerMessage() {
        val budget = SmsBudget().record(now, 3)

        assertEquals(3, budget.countInLastHour(now))
    }

    @Test
    fun theLedgerSurvivesEncodingAndDecoding() {
        val budget = SmsBudget(listOf(now - 1000, now))

        assertEquals(budget, SmsBudget.decode(budget.encode()))
    }

    @Test
    fun decodingGarbageYieldsAnEmptyLedger() {
        assertEquals(SmsBudget(), SmsBudget.decode("not,a,ledger"))
        assertEquals(SmsBudget(), SmsBudget.decode(null))
    }
}
