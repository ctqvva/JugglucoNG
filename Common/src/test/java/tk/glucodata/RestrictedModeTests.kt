package tk.glucodata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RestrictedModeTests {
    @Test fun pinMatchesOnlyItself() {
        val encoded = RestrictedModePin.encode("2468")
        assertTrue(RestrictedModePin.matches("2468", encoded))
        assertFalse(RestrictedModePin.matches("2469", encoded))
        assertFalse(RestrictedModePin.matches("24680", encoded))
        assertFalse(RestrictedModePin.matches("", encoded))
    }

    @Test fun pinIsStoredSaltedNotInClear() {
        val first = RestrictedModePin.encode("1234")
        val second = RestrictedModePin.encode("1234")
        // Field by field: the random Base64 salt and hash may contain "1234" by chance.
        assertFalse(first.split(':').any { it == "1234" })
        // A fresh salt each time: the same PIN does not give the same record.
        assertNotEquals(first, second)
        assertTrue(RestrictedModePin.matches("1234", second))
    }

    @Test fun malformedRecordsNeverMatch() {
        for (bad in listOf("", "1234", "p1:abc:def:ghi", "p2:1:AAAA:AAAA", "p1:0:AAAA:AAAA", "p1:10:!!:AAAA")) {
            assertFalse(bad, RestrictedModePin.matches("1234", bad))
        }
    }

    @Test fun pinLengthAndDigitsAreChecked() {
        assertFalse(RestrictedModePin.isValid("123"))
        assertTrue(RestrictedModePin.isValid("1234"))
        assertTrue(RestrictedModePin.isValid("12345678"))
        assertFalse(RestrictedModePin.isValid("123456789"))
        assertFalse(RestrictedModePin.isValid("12a4"))
        assertFalse(RestrictedModePin.isValid("１２３４")) // full-width digits are not 0–9
    }

    @Test fun lockoutStartsAfterFreeAttemptsAndGrowsToACap() {
        for (failures in 0 until RestrictedModePin.FREE_ATTEMPTS) {
            assertEquals(0L, RestrictedModePin.lockoutMs(failures))
        }
        assertEquals(30_000L, RestrictedModePin.lockoutMs(RestrictedModePin.FREE_ATTEMPTS))
        assertEquals(60_000L, RestrictedModePin.lockoutMs(RestrictedModePin.FREE_ATTEMPTS + 1))
        assertEquals(15L * 60_000L, RestrictedModePin.lockoutMs(RestrictedModePin.FREE_ATTEMPTS + 5))
        assertEquals(15L * 60_000L, RestrictedModePin.lockoutMs(1_000))
    }

    @Test fun lockoutRunsOnTheElapsedClockWithinOneBoot() {
        val wait = 60_000L
        // Same boot: only elapsed time counts, whatever the wall clock says.
        assertEquals(wait, RestrictedModePin.remainingLockoutMs(7, 100_000L + wait, wait, 7, 100_000L))
        assertEquals(1L, RestrictedModePin.remainingLockoutMs(7, 100_000L + wait, wait, 7, 100_000L + wait - 1))
        assertEquals(0L, RestrictedModePin.remainingLockoutMs(7, 100_000L + wait, wait, 7, 100_000L + wait))
    }

    @Test fun anUnknownBootCounterKeepsAWaitStartedNow() {
        // BOOT_COUNT unreadable on a phone up for 20 minutes: a wait just started must still
        // run in full, not read as already over.
        val wait = 30_000L
        val uptime = 20L * 60_000L
        assertEquals(wait, RestrictedModePin.remainingLockoutMs(-1, uptime + wait, wait, -1, uptime))
        assertEquals(10_000L, RestrictedModePin.remainingLockoutMs(-1, uptime + wait, wait, -1, uptime + 20_000L))
        assertEquals(0L, RestrictedModePin.remainingLockoutMs(-1, uptime + wait, wait, -1, uptime + wait))
    }

    @Test fun restartingThePhoneNeverShortensALockout() {
        val wait = 15L * 60_000L
        // A new boot restarts the elapsed clock: the whole wait counts again from boot.
        assertEquals(wait - 120_000L, RestrictedModePin.remainingLockoutMs(7, 9_000_000L, wait, 8, 120_000L))
        assertEquals(0L, RestrictedModePin.remainingLockoutMs(7, 9_000_000L, wait, 8, wait))
        // Boot unknown after a restart: the deadline sits more than a wait ahead of the
        // restarted clock, so the whole wait counts from boot.
        assertEquals(wait - 1_000L, RestrictedModePin.remainingLockoutMs(-1, 9_000_000L, wait, -1, 1_000L))
        // Never more than the wait itself, and nothing without a saved lockout.
        assertEquals(wait, RestrictedModePin.remainingLockoutMs(7, Long.MAX_VALUE, wait, 7, 0L))
        assertEquals(0L, RestrictedModePin.remainingLockoutMs(7, 0L, 0L, 7, 0L))
    }

    @Test fun fullModeEndsAfterTheGraceAwayOrWhenTheClockGoesBack() {
        val away = 1_000_000L
        assertFalse(RestrictedMode.shouldRelock(away, away + RestrictedMode.RELOCK_GRACE_MS - 1))
        assertTrue(RestrictedMode.shouldRelock(away, away + RestrictedMode.RELOCK_GRACE_MS))
        // A clock set back must not stretch full mode.
        assertTrue(RestrictedMode.shouldRelock(away, away - 1))
    }

    @Test fun nothingIsRestrictedWhenOffOrUnlocked() {
        val everythingClosed = RestrictedModeConfig(enabled = true)
        for (state in listOf(
            RestrictedModeState(RestrictedModeConfig(enabled = false), unlocked = false),
            RestrictedModeState(everythingClosed, unlocked = true),
        )) {
            assertFalse(state.locked)
            assertTrue(state.canChangeSensors)
            assertTrue(state.canEditJournal)
            assertTrue(state.canCalibrate)
            assertTrue(state.canStopAlarms)
            assertTrue(state.canClearData)
        }
    }

    @Test fun lockedFollowsTheAdultsChoices() {
        val defaults = RestrictedModeState(RestrictedModeConfig(enabled = true), unlocked = false)
        assertTrue(defaults.locked)
        assertFalse(defaults.canChangeSensors)
        assertTrue(defaults.canEditJournal)
        assertFalse(defaults.canCalibrate)
        assertFalse(defaults.canStopAlarms)
        assertFalse(defaults.canClearData)

        val generous = RestrictedModeState(
            RestrictedModeConfig(
                enabled = true,
                allowSensorChange = true,
                allowJournal = false,
                allowCalibration = true,
                allowAlarmStop = true,
            ),
            unlocked = false,
        )
        assertTrue(generous.canChangeSensors)
        assertFalse(generous.canEditJournal)
        assertTrue(generous.canCalibrate)
        assertTrue(generous.canStopAlarms)
        // Deleting data is never one of the choices.
        assertFalse(generous.canClearData)
    }
}
