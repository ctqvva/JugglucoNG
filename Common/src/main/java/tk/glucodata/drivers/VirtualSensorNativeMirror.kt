package tk.glucodata.drivers

import tk.glucodata.Log

/**
 * Keeps a native stream shell in step with a source that owns no BLE packet
 * stream of its own — the API source, the Nightscout follower, the MQ follower.
 *
 * Those sources stored their readings in Room and stopped, which looks complete
 * because Room is what the phone's own UI reads. Everything served out of native
 * walks the native sensor list instead and skips any sensor with no poll data,
 * so a follower-only setup was invisible to the built-in web server (it answered
 * `{}`), to the /data stream, and to the Nightscout uploader.
 *
 * The native side addresses poll storage by minute offset from the shell's start
 * time — `lifeCount = (timestamp - start) / 60` in g.cpp's
 * `storeGlucoseStreamSample()` — and fixes the shell's geometry when it is
 * created. Two consequences shape this class:
 *
 *  - A reading *older* than the start is not refused. `lifeCount` stays 0 and the
 *    sample lands on slot 0, overwriting a good reading with a stale one. Points
 *    below the window have to be dropped here, not by native.
 *  - A reading past the end is dropped by native. A follower runs indefinitely,
 *    so its window is eventually outgrown and has to be rebased — and rebasing
 *    clears the poll store. Placing the newest reading at the far end of the
 *    fresh window would wipe the mirror again one reading later, so it goes near
 *    the start instead and the wipe happens roughly twice a month.
 *
 * The native calls are injected so the window arithmetic can be exercised on the
 * JVM, the same way [tk.glucodata.drivers.ottai.OttaiNativeGlucoseMirror] is.
 */
internal class VirtualSensorNativeMirror(
    private val readShellStartSeconds: (String) -> Long,
    private val openShell: (String, Long, Int) -> Unit,
    private val rebaseShell: (String, Long) -> Unit,
    private val writeBatch: (LongArray, FloatArray, FloatArray, String) -> Int,
) {

    companion object {
        private const val TAG = "VirtualGlucose"

        /** Minute slots a freshly created mirror shell asks for: 14 days' worth. */
        const val WINDOW_MINUTES: Int = 14 * 24 * 60

        const val WINDOW_SECONDS: Long = WINDOW_MINUTES * 60L

        /**
         * How much room a rebase leaves *behind* the newest reading. A follower's
         * next history page reaches back a few hours; six hours keeps those
         * points writable instead of dropping them for being under the window.
         */
        const val REBASE_LOOKBACK_SECONDS: Long = 6L * 60L * 60L

        /** First second past the end of the window opened at [startSeconds]. */
        fun windowEndSeconds(startSeconds: Long): Long = startSeconds + WINDOW_SECONDS

        /** True when a reading at [readingSeconds] maps to a slot native will accept. */
        fun isWritable(startSeconds: Long, readingSeconds: Long): Boolean =
            startSeconds > 0L &&
                readingSeconds >= startSeconds &&
                readingSeconds < windowEndSeconds(startSeconds)

        /**
         * Start time for a shell that does not exist yet, given the batch about
         * to be written. The oldest reading sets the start so a first import is
         * kept whole; a batch wider than the window gives up its oldest end
         * rather than its newest.
         */
        fun initialStartSeconds(oldestReadingSeconds: Long, newestReadingSeconds: Long): Long {
            if (oldestReadingSeconds <= 0L || newestReadingSeconds <= 0L) return 0L
            val widestStart = newestReadingSeconds - WINDOW_SECONDS + 60L
            return maxOf(oldestReadingSeconds - 60L, widestStart).coerceAtLeast(1L)
        }

        /** True when [newestReadingSeconds] has run past the window at [startSeconds]. */
        fun needsRebase(startSeconds: Long, newestReadingSeconds: Long): Boolean =
            startSeconds > 0L && newestReadingSeconds >= windowEndSeconds(startSeconds)

        /** Start time an outgrown window is moved to. */
        fun rebasedStartSeconds(newestReadingSeconds: Long): Long =
            (newestReadingSeconds - REBASE_LOOKBACK_SECONDS).coerceAtLeast(1L)

        /**
         * Native multiplies its glucose argument by 10 before storing it
         * (`mgVal = glucose * 10`), so callers hand it mg/dL ÷ 10. The raw
         * argument is passed as plain mg/dL and converted by native itself.
         */
        fun nativeGlucose(mgdl: Float): Float = mgdl / 10f
    }

    /**
     * Writes whichever of [readings] the current mirror window can hold,
     * creating or rebasing that window first. Returns the number of points
     * native accepted.
     */
    fun mirror(
        sensorSerial: String,
        readings: List<VirtualGlucoseSensorBridge.Reading>,
        logLabel: String,
    ): Int {
        if (sensorSerial.isBlank() || readings.isEmpty()) return 0
        val ordered = readings
            .asSequence()
            .filter { it.storageGlucoseMgdl.isFinite() && it.storageGlucoseMgdl > 0f }
            .map { (it.timestampMs / 1000L) to it }
            .filter { (seconds, _) -> seconds > 0L }
            .sortedBy { (seconds, _) -> seconds }
            .toList()
        if (ordered.isEmpty()) return 0

        val startSeconds = openWindow(
            sensorSerial = sensorSerial,
            oldestSeconds = ordered.first().first,
            newestSeconds = ordered.last().first,
            logLabel = logLabel,
        )
        if (startSeconds <= 0L) return 0

        val writable = ordered.filter { (seconds, _) -> isWritable(startSeconds, seconds) }
        if (writable.size < ordered.size) {
            Log.i(
                TAG,
                "Skipped ${ordered.size - writable.size} $logLabel points outside the native " +
                    "mirror window of $sensorSerial (start=$startSeconds)"
            )
        }
        if (writable.isEmpty()) return 0

        val stored = writeBatch(
            LongArray(writable.size) { writable[it].first },
            FloatArray(writable.size) { nativeGlucose(writable[it].second.storageGlucoseMgdl) },
            FloatArray(writable.size) {
                writable[it].second.rawMgdl.takeIf { raw -> raw.isFinite() && raw > 0f } ?: 0f
            },
            sensorSerial,
        )
        if (stored <= 0) {
            Log.w(TAG, "Native mirror stored no $logLabel points for $sensorSerial")
        }
        return stored
    }

    /**
     * Returns the start second the window is addressed from, creating the shell
     * on first use and rebasing it once the source has outlived it.
     */
    private fun openWindow(
        sensorSerial: String,
        oldestSeconds: Long,
        newestSeconds: Long,
        logLabel: String,
    ): Long {
        val existing = readShellStartSeconds(sensorSerial)
        if (existing <= 0L) {
            val start = initialStartSeconds(oldestSeconds, newestSeconds)
            if (start <= 0L) return 0L
            openShell(sensorSerial, start, WINDOW_MINUTES)
            Log.i(TAG, "Opened native $logLabel mirror for $sensorSerial at $start")
        } else if (needsRebase(existing, newestSeconds)) {
            val rebased = rebasedStartSeconds(newestSeconds)
            Log.w(
                TAG,
                "Rebasing native $logLabel mirror of $sensorSerial from $existing to $rebased; " +
                    "the window it held is cleared"
            )
            rebaseShell(sensorSerial, rebased)
        } else {
            return existing
        }
        // Read back rather than trusting the value just asked for: native only
        // ever lowers an existing start, so the window may not be where we put it.
        return readShellStartSeconds(sensorSerial)
    }
}
