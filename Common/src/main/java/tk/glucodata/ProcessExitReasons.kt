// ProcessExitReasons.kt — read back why the previous process(es) of this app ended.

package tk.glucodata

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build

/**
 * Writes Android's own record of how the last processes of this app died into the app's trace log.
 *
 * On 2026-08-01 both running apps died and restarted inside the jamming-storm window
 * ("Sat Aug 1 20:32:50 2026 10382 Start logging" and "... 20:33:01 2026 11747 Start logging"), and
 * 767 s — 13 % of the measured downtime — stayed unattributed because nothing in either log says
 * why: the kill could only be bounded to [1785605312, 1785605570]. The platform has kept the answer
 * since API 30 and the app simply never asked for it.
 *
 * Asked at startup, because the record of a process is dropped once the app is uninstalled or the
 * ring buffer wraps, and written through [Log] rather than android.util.Log, because the trace log
 * is the file the user exports — the logcat of the dead process is gone by the time anyone looks.
 */
object ProcessExitReasons {

    private const val LOG_ID = "ProcessExit"

    /**
     * Enough to show a restart loop rather than only its last iteration; on 2026-08-01 the two
     * deaths of interest were 11 s apart. Each record is one parcelled row, read once per process.
     */
    private const val MAX_RECORDS = 5

    @JvmStatic
    fun logPrevious(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        runCatching {
            val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                ?: return
            val records = manager.getHistoricalProcessExitReasons(context.packageName, 0, MAX_RECORDS)
            if (records.isEmpty()) {
                Log.i(LOG_ID, "no historical process exit reasons recorded")
                return
            }
            for (info in records) {
                Log.i(
                    LOG_ID,
                    describe(
                        processName = info.processName,
                        pid = info.pid,
                        reason = info.reason,
                        status = info.status,
                        importance = info.importance,
                        timestampMs = info.timestamp,
                        description = info.description,
                    ),
                )
            }
        }.onFailure { Log.stack(LOG_ID, "getHistoricalProcessExitReasons", it) }
    }

    /**
     * One line per dead process. The timestamp is emitted in seconds because that is the clock
     * every other line of trace.log carries, so the kill lands on the same axis as the BLE events
     * it has to be correlated with.
     */
    internal fun describe(
        processName: String?,
        pid: Int,
        reason: Int,
        status: Int,
        importance: Int,
        timestampMs: Long,
        description: String?,
    ): String =
        "previous process ${processName ?: "?"} pid=$pid died at=${timestampMs / 1000L} " +
            "reason=${reasonName(reason)}($reason) status=$status importance=$importance " +
            "description=${description ?: "none"}"

    /**
     * ApplicationExitInfo reports the reason as a bare int, and the numbers that matter here are
     * the ones that look alike in a log: LOW_MEMORY vs EXCESSIVE_RESOURCE_USAGE vs FREEZER all
     * mean "the system took the app away" but for very different, differently-fixable reasons.
     */
    internal fun reasonName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
        ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
        ApplicationExitInfo.REASON_CRASH -> "CRASH"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
        ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
        ApplicationExitInfo.REASON_OTHER -> "OTHER"
        ApplicationExitInfo.REASON_FREEZER -> "FREEZER"
        ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE -> "PACKAGE_STATE_CHANGE"
        ApplicationExitInfo.REASON_PACKAGE_UPDATED -> "PACKAGE_UPDATED"
        ApplicationExitInfo.REASON_UNKNOWN -> "UNKNOWN"
        else -> "UNNAMED"
    }
}
