package tk.glucodata

import org.junit.Assert.*
import org.junit.Test

class GluciferDeliveryBudgetTests {
    @Test fun `a day long background interval never holds up minute glucose events`() {
        val budget = GluciferDeliveryBudget()
        assertTrue(budget.acquire("phone", 86400, true, false, 0))
        for (minute in 1..60) {
            val now = minute * 60_000L
            assertTrue(budget.acquire("phone", 86400, true, true, now))
            assertFalse(budget.acquire("phone", 86400, true, false, now + 1000))
        }
        assertTrue(budget.acquire("phone", 86400, true, false, 86_400_000L))
    }

    @Test fun `all requests still have one second separation`() {
        val budget = GluciferDeliveryBudget()
        assertTrue(budget.acquire("phone", 360, true, false, 0))
        assertFalse(budget.acquire("phone", 360, true, true, 999))
        assertTrue(budget.acquire("phone", 360, true, true, 1000))
        assertFalse(budget.acquire("phone", 360, true, false, 1001))
    }

    @Test fun `opting out applies selected interval to live events too`() {
        val budget = GluciferDeliveryBudget()
        assertTrue(budget.acquire("phone", 360, false, false, 0))
        assertEquals(300_000L, budget.remaining("phone", 360, false, true, 60_000L))
        assertFalse(budget.acquire("phone", 360, false, true, 60_000L))
        assertTrue(budget.acquire("phone", 360, false, true, 360_000L))
    }
}
