package tk.glucodata.drivers.sibionics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SibionicsResetPolicyTest {
    private val start = 1_800_000_000_000L
    private val due = SibionicsResetPolicy.dueAtMs(start)
    private val hardDeadline = SibionicsResetPolicy.hardDeadlineMs(start)

    @Test
    fun `sibionics 2 defaults to 22 days and preserves an earlier target`() {
        assertEquals(
            22,
            SibionicsResetPolicy.normalizedDays(
                SibionicsConstants.Variant.SIBIONICS2,
                persistedDays = 300,
                hasPersistedSetting = false,
            ),
        )
        assertEquals(
            9,
            SibionicsResetPolicy.normalizedDays(
                SibionicsConstants.Variant.SIBIONICS2,
                persistedDays = 9,
                hasPersistedSetting = true,
            ),
        )
        assertEquals(
            300,
            SibionicsResetPolicy.normalizedDays(
                SibionicsConstants.Variant.SIBIONICS2,
                persistedDays = 300,
                hasPersistedSetting = true,
            ),
        )
    }

    @Test
    fun `selected reset day controls the comfortable reset window`() {
        val selectedDue = SibionicsResetPolicy.dueAtMs(start, autoResetDays = 9)
        val before = decision(
            nowMs = selectedDue - SibionicsResetPolicy.REMINDER_LEAD_MS - 1L,
            autoResetDays = 9,
        )
        val comfortable = decision(
            nowMs = selectedDue - SibionicsResetPolicy.REMINDER_LEAD_MS,
            autoResetDays = 9,
        )

        assertEquals(start + 9L * SibionicsConstants.DAY_MS, selectedDue)
        assertFalse(before.resetNow)
        assertTrue(comfortable.resetNow)
    }

    @Test
    fun `first generation variants never expose automatic reset`() {
        SibionicsConstants.Variant.entries
            .filter { it != SibionicsConstants.Variant.SIBIONICS2 }
            .forEach { variant ->
                assertEquals(
                    300,
                    SibionicsResetPolicy.normalizedDays(
                        variant,
                        persistedDays = 9,
                        hasPersistedSetting = true,
                    ),
                )
            }
    }

    @Test
    fun `reminder starts four hours before the 22 day reset point`() {
        val before = decision(
            nowMs = due - SibionicsResetPolicy.REMINDER_LEAD_MS - 1L,
            autoResetDays = SibionicsResetPolicy.DISABLED_DAYS,
        )
        val atWindow = decision(
            nowMs = due - SibionicsResetPolicy.REMINDER_LEAD_MS,
            autoResetDays = SibionicsResetPolicy.DISABLED_DAYS,
        )

        assertFalse(before.showReminder)
        assertTrue(atWindow.showReminder)
        assertFalse(atWindow.resetNow)
    }

    @Test
    fun `automatic reset uses a comfortable window before official expiry`() {
        val windowStart = due - SibionicsResetPolicy.REMINDER_LEAD_MS
        val unstable = decision(nowMs = windowStart, glucose = 210f, rate = 3f)
        val comfortable = decision(nowMs = windowStart, glucose = 120f, rate = 1f)

        assertFalse(unstable.resetNow)
        assertTrue(comfortable.resetNow)
        assertFalse(comfortable.forced)
    }

    @Test
    fun `hard reset runs four hours before the 23 day expected end`() {
        assertEquals(
            start + SibionicsConstants.SIBIONICS2_EXPECTED_LIFETIME_MS -
                SibionicsResetPolicy.EXPECTED_END_GUARD_MS,
            hardDeadline,
        )
        val before = decision(nowMs = hardDeadline - 1L, glucose = 210f, rate = 3f)
        val atDeadline = decision(nowMs = hardDeadline, glucose = 210f, rate = 3f)

        assertFalse(before.resetNow)
        assertTrue(atDeadline.resetNow)
        assertTrue(atDeadline.forced)
    }

    @Test
    fun `postpone suppresses normal reset but never the hard pre-expiry reset`() {
        val resetWindow = due - SibionicsResetPolicy.REMINDER_LEAD_MS
        val postponed = decision(
            nowMs = resetWindow,
            postponedUntilMs = resetWindow + SibionicsResetPolicy.POSTPONE_MS,
            glucose = 120f,
            rate = 0f,
        )
        val hard = decision(
            nowMs = hardDeadline,
            autoResetDays = SibionicsResetPolicy.DISABLED_DAYS,
            postponedUntilMs = hardDeadline + SibionicsResetPolicy.POSTPONE_MS,
        )

        assertFalse(postponed.resetNow)
        assertFalse(postponed.showReminder)
        assertTrue(hard.resetNow)
        assertTrue(hard.forced)
    }

    private fun decision(
        nowMs: Long,
        autoResetDays: Int = SibionicsResetPolicy.ENABLED_DAYS,
        postponedUntilMs: Long = 0L,
        glucose: Float = 120f,
        rate: Float = 0f,
    ): SibionicsResetPolicy.Decision = SibionicsResetPolicy.evaluate(
        nowMs = nowMs,
        startTimeMs = start,
        autoResetDays = autoResetDays,
        postponedUntilMs = postponedUntilMs,
        lastReminderAtMs = 0L,
        glucoseMgdl = glucose,
        rateMgdlPerMin = rate,
    )
}
