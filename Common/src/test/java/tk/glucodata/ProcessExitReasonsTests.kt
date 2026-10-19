package tk.glucodata

import android.app.ApplicationExitInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The 767 s of the 2026-08-01 downtime that stayed unattributed did so because the log said only
 * that a new process had started, never why the old one ended. What matters about the line that
 * closes that gap is that it is readable next to the BLE events: same seconds-since-epoch clock,
 * and a reason that is a name rather than a bare int — LOW_MEMORY, EXCESSIVE_RESOURCE_USAGE and
 * FREEZER all read as "the system took the app away" and are three different problems.
 */
class ProcessExitReasonsTests {

    @Test
    fun everyDocumentedReasonHasAName() {
        val reasons = intArrayOf(
            ApplicationExitInfo.REASON_UNKNOWN,
            ApplicationExitInfo.REASON_EXIT_SELF,
            ApplicationExitInfo.REASON_SIGNALED,
            ApplicationExitInfo.REASON_LOW_MEMORY,
            ApplicationExitInfo.REASON_CRASH,
            ApplicationExitInfo.REASON_CRASH_NATIVE,
            ApplicationExitInfo.REASON_ANR,
            ApplicationExitInfo.REASON_INITIALIZATION_FAILURE,
            ApplicationExitInfo.REASON_PERMISSION_CHANGE,
            ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE,
            ApplicationExitInfo.REASON_USER_REQUESTED,
            ApplicationExitInfo.REASON_USER_STOPPED,
            ApplicationExitInfo.REASON_DEPENDENCY_DIED,
            ApplicationExitInfo.REASON_OTHER,
            ApplicationExitInfo.REASON_FREEZER,
            ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE,
            ApplicationExitInfo.REASON_PACKAGE_UPDATED,
        )
        val names = reasons.map { ProcessExitReasons.reasonName(it) }
        assertTrue("an unnamed reason would read as a bare int: $names", names.none { it == "UNNAMED" })
        assertEquals("no two reasons may share a name: $names", reasons.size, names.toSet().size)
        assertEquals("UNNAMED", ProcessExitReasons.reasonName(Int.MAX_VALUE))
    }

    /**
     * The kill of 2026-08-01 could only be bounded to [1785605312, 1785605570] because nothing tied
     * it to a timestamp. trace.log lines carry seconds since the epoch, so this one has to as well
     * or it cannot be lined up with the drops around it.
     */
    @Test
    fun theLineCarriesTheSameClockAsTheRestOfTheTrace() {
        val line = ProcessExitReasons.describe(
            processName = "tk.glucodata",
            pid = 10382,
            reason = ApplicationExitInfo.REASON_LOW_MEMORY,
            status = 0,
            importance = 400,
            timestampMs = 1_785_605_570_123L,
            description = null,
        )
        assertTrue(line, line.contains("at=1785605570"))
        assertTrue(line, line.contains("pid=10382"))
        assertTrue(line, line.contains("reason=LOW_MEMORY(${ApplicationExitInfo.REASON_LOW_MEMORY})"))
        assertTrue("a missing description must not read as a null: $line", line.contains("description=none"))
    }
}
